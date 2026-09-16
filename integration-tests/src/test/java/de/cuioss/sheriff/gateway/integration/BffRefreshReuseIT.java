/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves end to end that a <em>replayed</em> refresh token and an <em>IdP-revoked</em> refresh grant
 * each end the gateway session they belong to, and pins what Keycloak itself does when it detects
 * refresh-token reuse.
 * <p>
 * <strong>Why this suite exists.</strong> The gateway cannot recognise a replayed refresh token: a
 * confidential client only ever sees the tokens it presents, and a stateless cookie-mode instance keeps
 * no token family. Reuse detection is therefore the identity provider's job, and the {@code integration}
 * realm enforces it through strict refresh-token rotation ({@code revokeRefreshToken: true},
 * {@code refreshTokenMaxReuse: 0}). The claim under test is the composition of the two halves: Keycloak
 * refuses the replay with {@code invalid_grant}, the engine classifies that refusal as
 * {@code CREDENTIAL_REJECTED}, and the gateway disposes it by ending the session. A unit test can prove
 * the gateway half against a stubbed refusal; only a live realm proves that Keycloak really refuses.
 * <p>
 * It runs against the two existing refresh instances and adds no compose instance and no realm client:
 * {@code api-sheriff-cookie-refresh} ({@link BffKeycloakLoginFlow#COOKIE_REFRESH_GATEWAY_ORIGIN},
 * stateless sealed cookie) and {@code api-sheriff-refresh}
 * ({@link BffKeycloakLoginFlow#REFRESH_GATEWAY_ORIGIN}, server mode). Both authenticate as
 * {@code refresh-client}, whose 45-second access token puts the near-expiry window at 15s..45s.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li><em>Cookie-mode replay ends the replayed session.</em> A login seals cookie {@code A1} around
 *       refresh token {@code R1}. Inside the window a mediated call on {@code A1} redeems {@code R1},
 *       and the gateway re-seals the rotated tokens into cookie {@code A2}. The browser-side attacker
 *       then replays {@code A1}, which is still inside its own window and still unseals. The gateway
 *       presents the spent {@code R1} again, Keycloak refuses it, and the replay is answered
 *       {@code 401} problem+json with a {@code Set-Cookie} clearing the session cookie, attributed to a
 *       new WARN {@code ApiSheriff-111} with reason {@code credential-rejected} in the cookie-refresh
 *       instance log. The log line is what separates this disposition from any other way a session can
 *       be lost.</li>
 *   <li><em>Keycloak's reuse verdict is pinned, in two parts.</em> Keycloak 26.5.7 does not stop at
 *       refusing the replayed token.
 *       <ol>
 *         <li><strong>The successor grant is revoked too.</strong> This was observed on the live stack:
 *             after the replay, {@code A2} is driven into its own window and its refresh is refused as
 *             well, answering {@code 401} problem+json with a clearing cookie and its own new
 *             {@code credential-rejected} {@code ApiSheriff-111} line. A detected replay therefore ends
 *             the legitimate holder's session along with the attacker's. That is the security property
 *             an operator gets from strict rotation: a stolen refresh token that is replayed burns the
 *             whole token family, not just the copy that was replayed.</li>
 *         <li><strong>The Keycloak user session that {@code A1}'s login created survives.</strong>
 *             Right after the replay, the suite looks that session up through the admin API. The pin
 *             is that it still exists, so the revocation is scoped to the refresh grant (the client
 *             session), not a logout of the whole user session, which other clients' SSO would share.
 *             The failure message reports the observed state, including which clients the session
 *             still carries. If a run finds the session gone, Keycloak ended the whole user session;
 *             this pin, this bullet and any threat-model wording derived from it must then change
 *             together.</li>
 *       </ol>
 *       Both halves are assertions rather than notes, so a Keycloak upgrade that changes either verdict
 *       turns this leg red instead of silently changing what an operator can rely on after a detected
 *       replay.</li>
 *   <li><em>A targeted server-mode revocation ends exactly one session.</em> Two logins by the same user
 *       create two Keycloak user sessions and two gateway sessions. Only the first Keycloak session is
 *       deleted, through {@code DELETE /admin/realms/integration/sessions/{id}}. Inside the window the
 *       first gateway session is answered {@code 401} problem+json with a clearing cookie and a new
 *       {@code credential-rejected} {@code ApiSheriff-111} line in the refresh instance log, while the
 *       second is refreshed normally, {@code 200} with a rotated bearer. The surviving sibling is what
 *       makes the revocation <em>targeted</em>: a user-wide logout would end both.</li>
 *   <li><em>An IdP-revoked cookie-mode session ends on its next refresh.</em>
 *       {@code BffCookieRefreshIT} never drives the {@code FAILED} branch, so the stateless binding's
 *       session-ending path is proven here: the Keycloak session behind a sealed cookie is deleted, and
 *       inside the window the cookie is answered {@code 401} problem+json with a clearing cookie and a
 *       new {@code credential-rejected} record in the cookie-refresh instance log.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong>
 * <ul>
 *   <li><em>IdP-initiated back-channel logout reaching a server-mode session.</em> That path is
 *       unreachable in this topology, not merely untested: neither {@code refresh-client} nor
 *       {@code integration-client} registers a {@code backchannel.logout.url} in
 *       {@code integration-realm.json}, so Keycloak pushes no logout token to any gateway instance when a
 *       session ends. {@code BffLogoutIT} proves only that the receiver is wired and fail-closed, and
 *       {@code BffCookieBackchannelDisabledIT} that cookie mode gates it off. In this stack an
 *       IdP-side session end reaches a server-mode gateway session only through the refresh leg proven
 *       above. Registering a back-channel URL would need a realm change this suite does not make.</li>
 *   <li><em>Cross-instance replay.</em> The replayed cookie is presented to the instance that sealed
 *       it. A replay against a peer sharing the sealing key would reach the same IdP refusal, but that
 *       is inferred here, not observed.</li>
 *   <li><em>Detection without strict rotation.</em> Nothing here runs against a realm that permits
 *       reuse. Without {@code revokeRefreshToken} neither side detects a replay, and the replay would
 *       simply refresh.</li>
 *   <li><em>Concurrent replay.</em> The replay is sequential. Two requests racing on one cookie are
 *       coalesced per instance by the coordinator's single-flight, which is proven at unit level.</li>
 *   <li><em>Browser cookie policy.</em> Like every {@code Bff*IT}, this suite replays a cookie map (see
 *       {@link BffKeycloakLoginFlow}'s LIMITATION note). Replaying an old cookie is exactly what an
 *       attacker holding a copied cookie does, so the map is the right model for the replay, but it
 *       proves nothing about what a browser keeps.</li>
 * </ul>
 * <p>
 * <strong>Why the session to revoke is found through the admin API, not the token.</strong> The realm
 * imports its own client-scope set and omits Keycloak's built-in {@code basic} scope, so whether a
 * mediated access token carries a {@code sid} claim is a property of the import, not a guarantee. The
 * suite instead lists the user's Keycloak sessions immediately before and after each login and requires
 * the difference to be exactly one id. That id is, by construction, the session this login created,
 * whatever other sessions earlier suites left behind.
 * <p>
 * <strong>Why the shared refresh user is safe here.</strong> Every revocation is targeted at one
 * session id this suite created itself; the suite never logs a user out realm-wide. It therefore
 * cannot end a session another suite holds, even though it authenticates as
 * {@link BffKeycloakLoginFlow#REFRESH_USERNAME} like {@code BffTokenRefreshIT} and
 * {@code BffCookieRefreshIT}. Those suites' all-sessions logouts cannot reach this one's sessions either:
 * Failsafe runs the IT classes one at a time (see {@code BffTokenRefreshIT}).
 * <p>
 * <strong>Timing.</strong> The waits are wall-clock and deliberate, following the
 * {@code BffTokenRefreshIT} rationale: the property under test is defined in elapsed time against a
 * token lifespan, so there is no state to poll for, and Awaitility would add a dependency without
 * removing the wait. The only poll is the bounded read of a bind-mounted log file.
 * <p>
 * This suite deliberately does not extend {@code BaseIntegrationTest}: that base binds the primary
 * instance's origin, while every request here goes to one of the two refresh instances.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffRefreshReuseIT {

    /** The require:session route that mediates a bearer to the go-httpbin echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /**
     * The browser session cookie both refresh instances bind. Neither overlay declares a
     * {@code session.cookie_name}, so both resolve the default. Spelled out rather than imported: this
     * is a black-box IT and the cookie name is part of the wire contract it observes.
     */
    private static final String SESSION_COOKIE_NAME = "__Host-sheriff-session";

    /**
     * Seconds to wait before a call that must land inside the near-expiry window of a token minted at
     * the start of the wait.
     * <p>
     * The window opens at {@code lifespan - leeway} = 45 - 30 = 15s and closes when the token expires
     * at 45s. 22 sits inside it with margin at both ends, exactly as in {@code BffTokenRefreshIT}.
     */
    private static final int WAIT_INTO_REFRESH_WINDOW_SECONDS = 22;

    /** The server-mode refresh instance's container log. */
    private static final String REFRESH_INSTANCE_LOG_FILE = "quarkus-refresh.log";

    /** The cookie-mode refresh instance's container log. */
    private static final String COOKIE_REFRESH_INSTANCE_LOG_FILE = "quarkus-cookie-refresh.log";

    /** The identifier of the WARN every session-ending refresh disposition records. */
    private static final String SESSION_REFRESH_FAILED_IDENTIFIER = "ApiSheriff-111";

    /**
     * The bounded reason {@code ApiSheriff-111} renders when the IdP rejected the presented refresh
     * token, spelled as the record template renders it, parenthesised, so the other reasons cannot
     * satisfy the match.
     */
    private static final String CREDENTIAL_REJECTED_REASON = "(credential-rejected)";

    /** How long to wait for a WARN to reach the host-mounted log file. */
    private static final long LOG_VISIBILITY_TIMEOUT_MILLIS = 5_000L;

    private static final long LOG_POLL_INTERVAL_MILLIS = 250L;

    @Test
    @DisplayName("cookie mode: a replayed pre-refresh cookie ends that session and Keycloak revokes the successor grant, not the user session")
    void replayedCookieEndsItsSessionAndRevokesTheSuccessorGrantButNotTheUserSession() {
        // Arrange: A1 seals the login's refresh token R1; the Keycloak session this login creates is
        // captured so its fate after the replay can be observed.
        String setupAdminToken = adminToken();
        String userId = userIdOf(setupAdminToken, BffKeycloakLoginFlow.REFRESH_USERNAME);
        Set<String> beforeLogin = keycloakSessionIds(setupAdminToken, userId);
        Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH,
                BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
        String keycloakSessionA1 = singleNewSession(beforeLogin, keycloakSessionIds(setupAdminToken, userId));
        Map<String, String> cookieA1 = Map.copyOf(session.gatewayCookies());

        // Act 1: inside A1's window the gateway redeems R1 and re-seals the rotated tokens into A2.
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        Response rotation = mediatedCall(cookieA1, BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN);
        authorizationOf(rotation);
        String sealedA2 = rotation.getCookie(SESSION_COOKIE_NAME);
        assertNotNull(sealedA2,
                "the precondition of a replay is a rotation: the in-window call must re-seal the rotated "
                        + "tokens into a new session cookie, otherwise there is no spent token to replay");
        assertNotEquals(cookieA1.get(SESSION_COOKIE_NAME), sealedA2,
                "the re-sealed cookie must differ from the login cookie, or R1 was never redeemed");
        Map<String, String> cookieA2 = new HashMap<>(cookieA1);
        cookieA2.put(SESSION_COOKIE_NAME, sealedA2);

        // Act 2: replay A1. It is still inside its own window, so the gateway presents the spent R1.
        long rejectionsBefore = credentialRejectedRecordCount(COOKIE_REFRESH_INSTANCE_LOG_FILE);
        Response replay = xhr(cookieA1, BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN)
                .then().statusCode(401)
                .extract().response();

        // Assert 2: the replay is refused as a credential rejection and the session cookie is cleared.
        assertTrue(replay.contentType().contains("application/problem+json"),
                "a refused replay on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(replay);
        assertCredentialRejectedRecordedAfter(COOKIE_REFRESH_INSTANCE_LOG_FILE, rejectionsBefore,
                "a replayed refresh token must be refused by the realm's strict rotation");

        // Assert 2b (pinned): reuse detection is scoped to the refresh grant, not the user session. The
        // observed state is rendered into the message either way, so a red run answers the question.
        Optional<Map<String, Object>> sessionA1AfterReplay = keycloakSession(adminToken(), userId,
                keycloakSessionA1);
        assertTrue(sessionA1AfterReplay.isPresent(),
                "pinned Keycloak behaviour: after a detected refresh-token replay the Keycloak USER session "
                        + "created by A1's login (" + keycloakSessionA1 + ") still exists, so reuse detection "
                        + "revoked the grant, not the whole user session. OBSERVED: that session is GONE, so "
                        + "Keycloak ended the whole user session; flip this pin and the class javadoc's "
                        + "pinned-verdict bullet together");

        // Act 3: drive the successor A2 into its own window. R2 was minted at the rotation above.
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        long successorRejectionsBefore = credentialRejectedRecordCount(COOKIE_REFRESH_INSTANCE_LOG_FILE);
        Response successor = xhr(cookieA2, BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN)
                .then().extract().response();

        // Assert 3 (observed on the live stack): Keycloak revoked the successor grant as well, so the
        // legitimate holder's session ends as a credential rejection too.
        assertEquals(401, successor.statusCode(),
                () -> "pinned Keycloak behaviour: a detected replay also revokes the successor refresh token, "
                        + "so A2's refresh must be refused. OBSERVED status " + successor.statusCode()
                        + " (Keycloak session " + keycloakSessionA1 + " after the replay: "
                        + sessionA1AfterReplay.map(present -> "PRESENT, clients " + present.get("clients"))
                        .orElse("GONE")
                        + "); a 200 means Keycloak now revokes only "
                        + "the replayed token, and this pin and the class javadoc must change together");
        assertTrue(successor.contentType().contains("application/problem+json"),
                "the refused successor on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(successor);
        assertCredentialRejectedRecordedAfter(COOKIE_REFRESH_INSTANCE_LOG_FILE, successorRejectionsBefore,
                "the successor's refresh must be refused as a credential rejection, not lost for another reason");
    }

    @Test
    @DisplayName("server mode: deleting one Keycloak session ends that gateway session and leaves the sibling refreshing")
    void targetedGrantRevocationEndsOnlyThatServerSession() {
        // Arrange: two logins by one user create two Keycloak user sessions and two gateway sessions.
        String adminToken = adminToken();
        String userId = userIdOf(adminToken, BffKeycloakLoginFlow.REFRESH_USERNAME);
        Set<String> beforeFirst = keycloakSessionIds(adminToken, userId);
        Session revoked = loginToRefreshInstance();
        String revokedKeycloakSession = singleNewSession(beforeFirst, keycloakSessionIds(adminToken, userId));
        Set<String> beforeSecond = keycloakSessionIds(adminToken, userId);
        Session sibling = loginToRefreshInstance();
        String siblingKeycloakSession = singleNewSession(beforeSecond, keycloakSessionIds(adminToken, userId));
        assertNotEquals(revokedKeycloakSession, siblingKeycloakSession,
                "the two logins must be two distinct Keycloak sessions, or the revocation cannot be targeted");
        String siblingBearerBefore = authorizationOf(
                mediatedCall(sibling.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN));

        // Act: revoke exactly one grant, then let both sessions reach the near-expiry window.
        deleteKeycloakSession(adminToken, revokedKeycloakSession);
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        long rejectionsBefore = credentialRejectedRecordCount(REFRESH_INSTANCE_LOG_FILE);
        Response revokedResponse = xhr(revoked.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .then().statusCode(401)
                .extract().response();
        Response siblingResponse = mediatedCall(sibling.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN);

        // Assert: the revoked session ends as a credential rejection ...
        assertTrue(revokedResponse.contentType().contains("application/problem+json"),
                "a failed refresh on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(revokedResponse);
        assertCredentialRejectedRecordedAfter(REFRESH_INSTANCE_LOG_FILE, rejectionsBefore,
                "a refresh grant whose Keycloak session was deleted must be refused as invalid_grant");
        // ... and the sibling grant of the same user was untouched and refreshed normally.
        assertNotEquals(siblingBearerBefore, authorizationOf(siblingResponse),
                "the sibling session must still refresh inside its window; an unchanged bearer or a failure "
                        + "here means the revocation reached beyond the one Keycloak session it named");
    }

    @Test
    @DisplayName("cookie mode: deleting the Keycloak session behind a sealed cookie ends it on the next refresh")
    void idpRevokedCookieSessionEndsOnTheNextRefresh() {
        // Arrange
        String adminToken = adminToken();
        String userId = userIdOf(adminToken, BffKeycloakLoginFlow.REFRESH_USERNAME);
        Set<String> beforeLogin = keycloakSessionIds(adminToken, userId);
        Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH,
                BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
        String keycloakSession = singleNewSession(beforeLogin, keycloakSessionIds(adminToken, userId));

        // Act: the deletion alone changes nothing observable. Outside the window the coordinator
        // never contacts the IdP, so the wait is what forces the refresh attempt that then fails.
        deleteKeycloakSession(adminToken, keycloakSession);
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        long rejectionsBefore = credentialRejectedRecordCount(COOKIE_REFRESH_INSTANCE_LOG_FILE);
        Response response = xhr(session.gatewayCookies(), BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN)
                .then().statusCode(401)
                .extract().response();

        // Assert
        assertTrue(response.contentType().contains("application/problem+json"),
                "a failed refresh on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(response);
        assertCredentialRejectedRecordedAfter(COOKIE_REFRESH_INSTANCE_LOG_FILE, rejectionsBefore,
                "a sealed cookie whose Keycloak session was deleted must end as a credential rejection");
    }

    // ---------------------------------------------------------------- gateway helpers

    private static Session loginToRefreshInstance() {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
    }

    private static Response mediatedCall(Map<String, String> gatewayCookies, String origin) {
        return BffKeycloakLoginFlow.gateway(gatewayCookies, origin)
                .when().get(MEDIATED_PATH)
                .then().statusCode(200)
                .extract().response();
    }

    /** A non-navigation request on the mediated route, so a refused session renders problem+json. */
    private static Response xhr(Map<String, String> gatewayCookies, String origin) {
        return BffKeycloakLoginFlow.gateway(gatewayCookies, origin)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH);
    }

    /** Reads the bearer the gateway injected upstream, as echoed back by go-httpbin. */
    private static String authorizationOf(Response echoed) {
        Object authorization = echoed.path("headers.Authorization");
        assertNotNull(authorization, "the session must mediate a bearer to the upstream");
        String value = authorization.toString();
        assertTrue(value.contains("Bearer"), "the mediated upstream credential must be a bearer token");
        return value;
    }

    /**
     * Asserts the response clears the session cookie: one {@code Set-Cookie} naming
     * {@value #SESSION_COOKIE_NAME} with an empty value AND an expiry, the same three-part rule
     * {@code BffTokenRefreshIT} documents. Anything less leaves the cookie live in the browser.
     */
    private static void assertClearsSessionCookie(Response response) {
        List<String> setCookies = response.getHeaders().getValues("Set-Cookie");
        assertFalse(setCookies.isEmpty(),
                "an ended session must be answered with a Set-Cookie clearing the session cookie");
        assertTrue(setCookies.stream().anyMatch(BffRefreshReuseIT::clearsTheSessionCookie),
                "the response must clear " + SESSION_COOKIE_NAME + " itself (empty value AND Max-Age=0 or "
                        + "the epoch Expires); got " + setCookies);
    }

    private static boolean clearsTheSessionCookie(String setCookie) {
        int equals = setCookie.indexOf('=');
        if (equals < 0 || !SESSION_COOKIE_NAME.equals(setCookie.substring(0, equals).trim())) {
            return false;
        }
        String remainder = setCookie.substring(equals + 1);
        int firstAttribute = remainder.indexOf(';');
        String value = (firstAttribute < 0 ? remainder : remainder.substring(0, firstAttribute)).trim();
        if (!value.isEmpty()) {
            return false;
        }
        String lower = remainder.toLowerCase(Locale.ROOT);
        return lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970");
    }

    // ---------------------------------------------------------------- container-log helpers

    /**
     * Counts the {@code credential-rejected} {@code ApiSheriff-111} lines in one instance log. A count,
     * not a presence check: earlier legs and earlier suites write to the same log, so only the delta
     * across the request under test attributes a record to that request.
     */
    private static long credentialRejectedRecordCount(String logFileName) {
        return readInstanceLog(logFileName).lines()
                .filter(line -> line.contains(SESSION_REFRESH_FAILED_IDENTIFIER))
                .filter(line -> line.contains(CREDENTIAL_REJECTED_REASON))
                .count();
    }

    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a bind-mounted log append
    private static void assertCredentialRejectedRecordedAfter(String logFileName, long countBefore, String why) {
        long deadline = System.currentTimeMillis() + LOG_VISIBILITY_TIMEOUT_MILLIS;
        long observed = credentialRejectedRecordCount(logFileName);
        while (observed <= countBefore && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the refresh log record", interrupted);
            }
            observed = credentialRejectedRecordCount(logFileName);
        }
        long finalObserved = observed;
        assertTrue(finalObserved > countBefore,
                () -> why + ": expected a new WARN " + SESSION_REFRESH_FAILED_IDENTIFIER + " line with reason "
                        + CREDENTIAL_REJECTED_REASON + " in " + logFileName + " (count before the request "
                        + countBefore + ", after " + finalObserved + "); without it the 401 proves only that "
                        + "the session was lost, not which refresh disposition ended it");
    }

    private static String readInstanceLog(String logFileName) {
        Path logFile = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs")).resolve(logFileName);
        assertTrue(Files.isRegularFile(logFile), () -> "expected the instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }

    // ---------------------------------------------------------------- Keycloak admin helpers

    private static String adminToken() {
        String token = given().relaxedHTTPSValidation()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", "admin")
                .formParam("password", "admin")
                .when().post(KEYCLOAK_ORIGIN + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
        assertNotNull(token, "the Keycloak master realm must issue an admin token");
        return token;
    }

    private static String userIdOf(String adminToken, String username) {
        String userId = given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .queryParam("username", username)
                .queryParam("exact", true)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users")
                .then().statusCode(200)
                .extract().path("[0].id");
        assertNotNull(userId, "the admin API must resolve " + username + " in the integration realm");
        return userId;
    }

    private static Set<String> keycloakSessionIds(String adminToken, String userId) {
        List<String> ids = given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users/" + userId + "/sessions")
                .then().statusCode(200)
                .extract().path("id");
        return ids == null ? Set.of() : new HashSet<>(ids);
    }

    /**
     * Looks one Keycloak user session of {@code userId} up by id.
     *
     * @return the session representation (its {@code clients} map names the clients still attached),
     *         or empty when the user no longer has a session with that id
     */
    private static Optional<Map<String, Object>> keycloakSession(String adminToken, String userId,
            String sessionId) {
        List<Map<String, Object>> sessions = given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users/" + userId + "/sessions")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        if (sessions == null) {
            return Optional.empty();
        }
        return sessions.stream()
                .filter(candidate -> sessionId.equals(candidate.get("id")))
                .findFirst();
    }

    /**
     * The one Keycloak session a login created: the difference between the user's sessions after and
     * before it. Exactly one is required, so a login that created none, or a concurrent writer that
     * created more, fails here rather than pointing the revocation at the wrong session.
     */
    private static String singleNewSession(Set<String> before, Set<String> after) {
        Set<String> created = new HashSet<>(after);
        created.removeAll(before);
        assertEquals(1, created.size(),
                "a login must create exactly one Keycloak user session; before " + before.size()
                        + ", after " + after.size() + ", new " + created.size());
        return created.iterator().next();
    }

    /** Deletes exactly one Keycloak user session, leaving every other session of the user alive. */
    private static void deleteKeycloakSession(String adminToken, String sessionId) {
        given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .when().delete(KEYCLOAK_ORIGIN + "/admin/realms/integration/sessions/" + sessionId)
                .then().statusCode(204);
    }

    /**
     * Bounded wall-clock wait. The property under test is defined in elapsed time against a token
     * lifespan, so there is no condition to poll and nothing for Awaitility to shorten.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the token lifespan IS the clock under test
    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the refresh window", interrupted);
        }
    }
}
