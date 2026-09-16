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
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives all three {@code RefreshOutcome.Kind} branches of the server-mode BFF token refresh through
 * a live gateway edge, against the dedicated {@code api-sheriff-refresh} instance.
 * <p>
 * <strong>Why a dedicated instance.</strong> The near-expiry refresh window is a function of the
 * access token's lifespan, and that lifespan is a property of the OIDC client that minted the token —
 * so it cannot be varied per request on an existing instance. The shared realm mints a 900-second
 * token, which with {@code session.refresh.leeway_seconds: 30} puts the refresh window 870 seconds
 * into a session. This instance authenticates as {@code refresh-client}, whose client-level
 * {@code access.token.lifespan} is 45, so the window is 15s..45s after issuance and a test can reach
 * it with a bounded wait.
 * <p>
 * <strong>What this suite proves.</strong> Each of the three terminal outcomes is reached through the
 * real edge and asserted by an observable that would differ had the branch not been taken:
 * <ul>
 *   <li>{@code CURRENT} — two mediated calls made <em>before</em> the window opens carry a
 *       <em>byte-identical</em> {@code Authorization} header. Outside the near-expiry window no
 *       refresh is due, so a rotation here would mean one was driven early.</li>
 *   <li>{@code REFRESHED} — a mediated call after the window opens carries a <em>different</em>
 *       {@code Authorization} header. The proof is the changed upstream bearer, never session
 *       continuity: a session that merely still works proves only that it was not destroyed, which is
 *       equally true of {@code CURRENT}.</li>
 *   <li>{@code FAILED} — after the IdP revokes the session, a mediated call once the window has
 *       opened is rejected as unauthenticated and the session cookie is cleared. The rejection is
 *       pinned to its <em>named reason</em>: the admin logout makes Keycloak answer the refresh
 *       grant with {@code invalid_grant}, the engine classifies that as {@code CREDENTIAL_REJECTED},
 *       and the refresh instance's container log must gain a WARN {@code ApiSheriff-111} line with
 *       reason {@code credential-rejected} for the request under test. A {@code 401} or {@code 302}
 *       alone would also be produced by a session the gateway lost for any other reason, so without
 *       the log line the leg would be green without proving which disposition ran.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not exercise a replayed refresh token or
 * a targeted revocation of one refresh grant — those are {@code BffRefreshReuseIT}'s, which drives
 * the realm's strict refresh-token rotation directly. It does not exercise cookie-mode re-seal on
 * rotation ({@code BffCookieRefreshIT}), a refresh racing session expiry, concurrent requests
 * coalescing onto one single-flight refresh, or an IdP refusal other than {@code invalid_grant}: the
 * pre-redemption back-off, the redeemed-response and the persist-failure dispositions are proven
 * at unit level only. It also asserts nothing about browser cookie policy — it replays a cookie
 * map, exactly as {@link BffKeycloakLoginFlow} documents.
 * <p>
 * <strong>Why the realm-wide admin logout is safe to use here.</strong> The {@code FAILED} legs end
 * <em>every</em> Keycloak session of {@link BffKeycloakLoginFlow#REFRESH_USERNAME}, which
 * {@code BffCookieRefreshIT} logs in as too. The two suites cannot overlap: the
 * {@code integration-tests} Failsafe execution declares no {@code forkCount} (so the default of one
 * fork at a time applies), sets {@code reuseForks=false}, and configures no JUnit parallel execution,
 * so test classes run strictly one after another and every test logs in afresh. A logout therefore
 * only ever ends sessions this suite created itself. Enabling parallel IT execution would break that
 * premise, and the fix then belongs in the tests (a distinct user per suite), never in the realm's
 * rotation settings.
 * <p>
 * <strong>The defect this suite reproduced, and its cause.</strong> Run against the live stack on
 * 2026-09-07 ({@code verify -Pintegration-tests}, 130 integration tests, of which the five below),
 * two of the five passed and three failed, and all three failures shared one cause: <em>the
 * near-expiry refresh never fired</em>. {@code mediatedTokenCarriesTheDeclaredLifespan} and
 * {@code currentOutcomeReusesTheMediatedToken} passed — {@code exp - iat} was exactly 45, so
 * {@code refresh-client}'s lifespan was genuinely in effect and the near-expiry window genuinely
 * reachable, which is what made the other three failures evidence about the refresh rather than
 * about the fixture. {@code refreshedOutcomeRotatesTheMediatedToken} came back with an
 * <em>unchanged</em> bearer 22 seconds into a 45-second token, and both {@code FAILED}-branch tests
 * got {@code 200} where they expected {@code 401} and {@code 302}, after the IdP had revoked every
 * session of the user.
 * <p>
 * <strong>The mechanism was a missing session component, not a thrown exception.</strong> The
 * hypothesis this fixture was built to chase was a failure on the refresh path; there was none, and
 * the refresh instance's {@code quarkus-refresh.log} was clean across the whole window — no ERROR,
 * no WARN, no record naming a refresh attempt at all. The reason is upstream of any logging:
 * {@code CallbackEndpoint.completeLogin} is the only place a login creates a session, and it built
 * the {@code SessionRecord} <em>without a refresh token</em>, because the engine's
 * {@code AuthorizationCodeFlow.AuthenticationResult} did not carry one to seed it from.
 * {@code TokenRefreshCoordinator.refresh} therefore returned on its very first guard —
 * {@code session.refreshToken() == null} — before reaching the near-expiry arithmetic and before
 * emitting anything. That single guard explains every symptom at once: the silence in the log, the
 * unrotated bearer, and the {@code 200}s, since the refresh is the only thing that re-contacts the
 * IdP, so a session the IdP had destroyed was never re-validated. The leeway arithmetic was never
 * at fault.
 * <p>
 * <strong>Fixed.</strong> The engine gained a third {@code refreshToken} component on
 * {@code AuthenticationResult} and populates it from the token response inside {@code exchange()};
 * the gateway now threads it into the created {@code SessionRecord}, so the coordinator gets past
 * its null guard and the near-expiry path runs. The five tests asserted the specified behaviour
 * throughout, never the observed one, so they became the regression guard for the fix without a
 * line of them moving. The two {@code FAILED} legs were later tightened to also require the
 * {@code credential-rejected} log line, once refresh failures began to be disposed per kind.
 * <p>
 * <strong>Bounding the result.</strong> Exercised, against a realm enforcing strict refresh-token
 * rotation ({@code revokeRefreshToken: true}, {@code refreshTokenMaxReuse: 0}): the near-expiry
 * trigger on a server-mode session with a 45-second access token; IdP-side session invalidation via
 * admin logout, disposed as {@code credential-rejected}; the XHR and navigation challenge legs. That
 * the {@code CURRENT} and {@code REFRESHED} legs stay green under strict rotation is itself evidence
 * that none of them redeems one refresh token twice. NOT exercised here, and therefore neither
 * confirmed nor disproved by this suite: a replayed refresh token and targeted grant revocation
 * (see {@code BffRefreshReuseIT}), cookie-mode re-seal on rotation, a refresh racing session expiry,
 * concurrent requests coalescing onto one single-flight refresh, and an IdP refusal other than
 * {@code invalid_grant}.
 * <p>
 * <strong>Why this verdict could not be reached earlier.</strong> Until the failsafe include pattern
 * in {@code integration-tests/pom.xml} was corrected, the lifecycle-bound execution matched no test
 * class at all: {@code verify -Pintegration-tests} reported {@code Tests run: 0} and BUILD SUCCESS
 * while running no integration test. Every run of this fixture before that fix proved nothing, in
 * either direction.
 * <p>
 * <strong>Timing.</strong> The waits are wall-clock and deliberate: the property under test is
 * defined in terms of elapsed time against a token lifespan, so there is no state to poll for.
 * Awaitility would add a dependency without removing the wait (resolution D3), so it is not used.
 * <p>
 * This suite deliberately does not extend {@code BaseIntegrationTest}: that base binds the primary
 * instance's origin, while every request here must go to {@link BffKeycloakLoginFlow#REFRESH_GATEWAY_ORIGIN}.
 */
class BffTokenRefreshIT {

    /** The require:session route that mediates a bearer to the go-httpbin echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /**
     * The browser session cookie this instance binds. The refresh descriptor declares no
     * {@code session.cookie_name}, so it resolves {@code SessionCookieCodec.DEFAULT_COOKIE_NAME}.
     * Spelled out rather than imported: this is a black-box IT and the cookie name is part of the
     * wire contract it observes, not an implementation detail it may reach into.
     */
    private static final String SESSION_COOKIE_NAME = "__Host-sheriff-session";

    /** The client-level {@code access.token.lifespan} declared on {@code refresh-client}. */
    private static final int ACCESS_TOKEN_LIFESPAN_SECONDS = 45;

    /**
     * Seconds to wait before a call that must land inside the near-expiry window.
     * <p>
     * The window opens at {@code lifespan - leeway} = 45 - 30 = 15s and closes when the token expires
     * at 45s. 22 sits inside it with margin at both ends: comfortably past the 15s opening so a slow
     * runner cannot land early, and far enough from 45s that the token has not simply expired — which
     * would be a different failure than the refresh under test.
     */
    private static final int WAIT_INTO_REFRESH_WINDOW_SECONDS = 22;

    /** The refresh instance's container log, written by compose into {@code test.log.dir}. */
    private static final String REFRESH_INSTANCE_LOG_FILE = "quarkus-refresh.log";

    /** The identifier of the WARN every session-ending refresh disposition records. */
    private static final String SESSION_REFRESH_FAILED_IDENTIFIER = "ApiSheriff-111";

    /**
     * The bounded reason {@code ApiSheriff-111} renders when the IdP rejected the presented refresh
     * token. Spelled out as it appears in the record's template — parenthesised — so the match
     * cannot be satisfied by the other reasons ({@code redeemed-response-refused},
     * {@code persist-failure}).
     */
    private static final String CREDENTIAL_REJECTED_REASON = "(credential-rejected)";

    /**
     * How long to wait for the WARN to reach the host-mounted log file. The record is written before
     * the response is sent, but the bind mount can surface the append a moment later, so the read is
     * retried briefly rather than taken once.
     */
    private static final long LOG_VISIBILITY_TIMEOUT_MILLIS = 5_000L;

    private static final long LOG_POLL_INTERVAL_MILLIS = 250L;

    @Test
    @DisplayName("the refresh-client access token is minted with the declared 45-second lifespan")
    void mediatedTokenCarriesTheDeclaredLifespan() {
        Session session = loginToRefreshInstance();

        String bearer = authorizationOf(mediatedCall(session));
        JsonPath claims = decodeJwtPayload(bearer);
        long issuedAt = claims.getLong("iat");
        long expiresAt = claims.getLong("exp");

        assertEquals(ACCESS_TOKEN_LIFESPAN_SECONDS, expiresAt - issuedAt,
                "the mediated token must carry refresh-client's declared access.token.lifespan; "
                        + "without it the near-expiry window is unreachable and every branch below "
                        + "would silently assert the CURRENT path");
    }

    @Test
    @DisplayName("CURRENT: two calls before the window opens mediate a byte-identical bearer and rotate nothing")
    void currentOutcomeReusesTheMediatedToken() {
        Session session = loginToRefreshInstance();

        // Deliberately NO wait: both calls land within a second or two of login, and the near-expiry
        // window does not open until lifespan - leeway = 15s. This is the BEFORE-the-window control
        // that gives refreshedOutcomeRotatesTheMediatedToken (which sleeps into the window) its
        // meaning — the two straddle the 15s edge from opposite sides.
        Response first = mediatedCall(session);
        Response second = mediatedCall(session);

        String firstBearer = authorizationOf(first);
        String secondBearer = authorizationOf(second);
        assertEquals(firstBearer, secondBearer,
                "before the leeway window opens no refresh is due, so the mediated bearer must be the "
                        + "very same token; a difference here means a refresh was driven early");
        assertNoSetCookie(second, "a CURRENT outcome rebinds nothing");
    }

    @Test
    @DisplayName("REFRESHED: a call after the window opens mediates a different bearer")
    void refreshedOutcomeRotatesTheMediatedToken() {
        Session session = loginToRefreshInstance();
        String beforeRefresh = authorizationOf(mediatedCall(session));

        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        Response afterRefresh = mediatedCall(session);

        assertNotEquals(beforeRefresh, authorizationOf(afterRefresh),
                "once the mediated token is within leeway of expiry the coordinator must rotate it "
                        + "through the engine; an unchanged bearer means the refresh never ran");
        // Server mode keeps the opaque handle stable across a rotation: ServerSessionBinding.persist
        // updates the stored record in place rather than re-binding the browser. Asserting the
        // ABSENCE here is what distinguishes server mode from the cookie mode's re-seal.
        assertNoSetCookie(afterRefresh,
                "server-mode refresh must not rotate the opaque session handle");
    }

    @Test
    @DisplayName("FAILED: an XHR after IdP-side revocation is rejected 401 problem+json and clears the cookie")
    void failedOutcomeRejectsXhrAndClearsTheSession() {
        Session session = loginToRefreshInstance();
        revokeSessionsOf(BffKeycloakLoginFlow.REFRESH_USERNAME);

        // The revocation alone changes nothing observable: outside the window the coordinator returns
        // CURRENT without ever contacting the IdP, so the request would still succeed. The wait is
        // what forces the refresh attempt that then fails.
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        long rejectionsBefore = credentialRejectedRecordCount();
        Response response = BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().statusCode(401)
                .extract().response();

        assertTrue(response.contentType().contains("application/problem+json"),
                "a failed refresh on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(response);
        assertCredentialRejectedRecordedAfter(rejectionsBefore);
    }

    @Test
    @DisplayName("FAILED: a navigation after IdP-side revocation is redirected 302 into the IdP and clears the cookie")
    void failedOutcomeRedirectsNavigationAndClearsTheSession() {
        Session session = loginToRefreshInstance();
        revokeSessionsOf(BffKeycloakLoginFlow.REFRESH_USERNAME);

        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        long rejectionsBefore = credentialRejectedRecordCount();
        Response response = BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().statusCode(302)
                .extract().response();

        String location = response.getHeader("Location");
        assertNotNull(location, "a navigation challenge must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "the navigation challenge must redirect into the OIDC authorization endpoint");
        assertClearsSessionCookie(response);
        assertCredentialRejectedRecordedAfter(rejectionsBefore);
    }

    // ---------------------------------------------------------------- helpers

    private static Session loginToRefreshInstance() {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
    }

    private static Response mediatedCall(Session session) {
        return BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .when().get(MEDIATED_PATH)
                .then().statusCode(200)
                .extract().response();
    }

    /**
     * Reads the bearer the gateway injected upstream, as echoed back by go-httpbin.
     */
    private static String authorizationOf(Response echoed) {
        Object authorization = echoed.path("headers.Authorization");
        assertNotNull(authorization, "the session must mediate a bearer to the upstream");
        String value = authorization.toString();
        assertTrue(value.contains("Bearer"), "the mediated upstream credential must be a bearer token");
        return value;
    }

    private static JsonPath decodeJwtPayload(String authorizationHeader) {
        String token = authorizationHeader.replaceFirst("(?i)^\\[?Bearer\\s+", "").replaceAll("]$", "");
        String[] segments = token.split("\\.");
        assertEquals(3, segments.length, "a mediated access token must be a three-segment JWS");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        return new JsonPath(payload);
    }

    private static void assertNoSetCookie(Response response, String why) {
        List<String> setCookies = response.getHeaders().getValues("Set-Cookie");
        assertTrue(setCookies.isEmpty(), why + ", so no Set-Cookie may be emitted; got " + setCookies);
    }

    /**
     * Asserts the response clears <em>the session cookie</em> — the observable that distinguishes a
     * destroyed session from a merely rejected request.
     * <p>
     * Both halves are load-bearing and are asserted on the SAME header, never across two. Matching
     * the {@link #SESSION_COOKIE_NAME} alone would be satisfied by a session cookie re-emitted with a
     * live value; matching an expiry alone would be satisfied by any unrelated cookie the edge
     * happens to clear. Only a header that names the session cookie AND carries an empty value AND
     * carries an expiry actually removes it from the browser — without all three the FAILED tests
     * could pass while {@value #SESSION_COOKIE_NAME} stays live and the next request replays a
     * session the gateway has destroyed.
     */
    private static void assertClearsSessionCookie(Response response) {
        List<String> setCookies = response.getHeaders().getValues("Set-Cookie");
        assertFalse(setCookies.isEmpty(),
                "a failed refresh destroys the session, so the response must clear the session cookie");
        boolean clearing = setCookies.stream().anyMatch(BffTokenRefreshIT::clearsTheSessionCookie);
        assertTrue(clearing,
                "a failed refresh must clear " + SESSION_COOKIE_NAME + " itself — one Set-Cookie naming "
                        + "that cookie with an empty value AND an expiry (Max-Age=0 or the epoch "
                        + "Expires); anything less leaves the cookie live in the browser and the next "
                        + "request replays a destroyed session. Got " + setCookies);
    }

    /**
     * Whether one {@code Set-Cookie} header clears {@link #SESSION_COOKIE_NAME}. The gateway's own
     * clearing form is {@code SessionCookieCodec#toClearingSetCookieHeader()} —
     * {@code __Host-sheriff-session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax} — and the
     * epoch {@code Expires} is accepted as the equivalent legacy shape.
     */
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

    /**
     * Counts the {@code ApiSheriff-111} lines carrying reason {@code credential-rejected} in the
     * refresh instance's container log.
     * <p>
     * A count, not a presence check: both {@code FAILED} legs write to the same log, so a line the
     * earlier leg left behind would satisfy a presence check for the later one. Comparing the count
     * before and after the request under test attributes the record to that request.
     */
    private static long credentialRejectedRecordCount() {
        return readRefreshInstanceLog().lines()
                .filter(line -> line.contains(SESSION_REFRESH_FAILED_IDENTIFIER))
                .filter(line -> line.contains(CREDENTIAL_REJECTED_REASON))
                .count();
    }

    /**
     * Asserts that the request just made recorded a new {@code credential-rejected} refresh failure,
     * so the {@code FAILED} leg is green for the named disposition rather than for any session loss.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a bind-mounted log append
    private static void assertCredentialRejectedRecordedAfter(long countBefore) {
        long deadline = System.currentTimeMillis() + LOG_VISIBILITY_TIMEOUT_MILLIS;
        long observed = credentialRejectedRecordCount();
        while (observed <= countBefore && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the refresh log record", interrupted);
            }
            observed = credentialRejectedRecordCount();
        }
        long finalObserved = observed;
        assertTrue(finalObserved > countBefore,
                () -> "the IdP-side revocation must be disposed as CREDENTIAL_REJECTED: expected a new WARN "
                        + SESSION_REFRESH_FAILED_IDENTIFIER + " line with reason " + CREDENTIAL_REJECTED_REASON
                        + " in " + REFRESH_INSTANCE_LOG_FILE + " (count before the request " + countBefore
                        + ", after " + finalObserved + "); without it the 401/302 proves only that the "
                        + "session was lost, not which refresh disposition ended it");
    }

    private static String readRefreshInstanceLog() {
        Path logFile = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs"))
                .resolve(REFRESH_INSTANCE_LOG_FILE);
        assertTrue(Files.isRegularFile(logFile),
                () -> "expected the refresh instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }

    /**
     * Revokes every Keycloak session of {@code username} in the {@code integration} realm through the
     * admin API, so the gateway's stored refresh token is no longer honoured.
     */
    private static void revokeSessionsOf(String username) {
        String adminToken = given().relaxedHTTPSValidation()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", "admin")
                .formParam("password", "admin")
                .when().post(KEYCLOAK_ORIGIN + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");

        String userId = given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .queryParam("username", username)
                .queryParam("exact", true)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users")
                .then().statusCode(200)
                .extract().path("[0].id");
        assertNotNull(userId, "the admin API must resolve " + username + " in the integration realm");

        given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .when().post(KEYCLOAK_ORIGIN + "/admin/realms/integration/users/" + userId + "/logout")
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
