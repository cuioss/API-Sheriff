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
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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
 * <strong>This instance authenticates with a client secret.</strong> It is the one gateway instance
 * of the stack whose descriptor declares {@code oidc.client_secret}; every other instance
 * authenticates with {@code private_key_jwt}. Only the client authentication differs: the realm
 * requires {@code refresh-client} to push its authorization requests and to have its access tokens
 * bound to a DPoP proof key, like the key-authenticated clients. Every login of this suite therefore
 * drives the pushed request and the code exchange, and every {@code REFRESHED} leg the refresh grant,
 * with {@code client_secret_basic}, and the two token requests with a DPoP proof. A green run is the
 * evidence that Keycloak accepts a pushed request and a DPoP-carrying code exchange and refresh from
 * a secret-authenticated client; the proofs that name those properties one by one are
 * {@code BffClientSecretModeIT}'s.
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
 *   <li>{@code REFRESHED} on a scoped route — after a login on {@code /bff-session/scoped}, whose
 *       endpoint adds {@code sheriff_it_endpoint} to {@code oidc.scopes} (ADR-0048), the rotated
 *       mediated token still carries {@code sheriff_it_endpoint}. The refresh requests the session's
 *       active scope set rather than {@code oidc.scopes}, and since the scope is optional on
 *       {@code refresh-client} a refresh that dropped it would be issued a token without it. The leg
 *       first confirms this instance serves the scoped route and asserts the rotation before the
 *       scope, so the scope assertion is about the refreshed token and never the login-time one.</li>
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
 * coalescing onto one single-flight refresh, or an IdP refusal other than {@code invalid_grant}:
 * every disposition {@code TokenRefreshCoordinator} documents other than the credential-rejected one
 * is proven at unit level only. It also asserts nothing about browser cookie policy —
 * it replays a cookie map, exactly as {@link BffKeycloakLoginFlow} documents.
 * <p>
 * <strong>Why the realm-wide admin logout is safe to use here.</strong> The {@code FAILED} legs end
 * <em>every</em> Keycloak session of the user they name, and that user is
 * {@link BffKeycloakLoginFlow#REVOCATION_USERNAME}, which no other suite logs in as. A logout
 * therefore only ever ends sessions this suite created itself, whichever suite runs before, after or
 * beside it; that no longer rests on the order or the number of the Failsafe forks. Within the class
 * the tests run one after another, in one JVM, with no JUnit parallel execution configured: the two
 * no-wait tests log in for themselves, and the two tests of each pair read sessions their pair logged
 * in once. The logout is issued inside the {@code FAILED} pair's observation, which runs from its two
 * logins to its last request with no other test in between, so it ends no session a test of this
 * class has yet to use; the sessions of tests that already finished are ended with it, unused.
 * <p>
 * <strong>Why this suite still runs in the sequential Failsafe execution.</strong> The user is not
 * what keeps it there; the log is. Each {@code FAILED} leg attributes a {@code credential-rejected}
 * record to its own request by the record count of {@code quarkus-refresh.log} on either side of
 * that request. The record ({@code ApiSheriff-111}) carries the reason and nothing else — no
 * session, no user, no request — so a record cannot be told from one that another suite's request
 * caused on the same instance. {@code BffRefreshReuseIT} causes such records on this instance, and
 * it runs in the concurrent execution ({@code sleep-bound}, see {@code integration-tests/pom.xml}).
 * Were this suite tagged as well, a record of that suite could satisfy a count assertion here whose
 * own request recorded nothing. So the longer of the two suites runs concurrently and this one does
 * not; the two Failsafe executions run one after the other, so the two suites never overlap.
 * {@code FailsafeConcurrencyContractTest} fails the build when two tagged suites name the same
 * instance log.
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
 * The class waits into the window twice per run: once for the two {@code REFRESHED} tests and once
 * for the two {@code FAILED} tests. Each pair's two sessions are logged in before its wait, so a run
 * observes four sessions inside their windows, as before, on two waits where there were four.
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

    /** The client this instance authenticates as (see {@code sheriff-config-refresh/gateway.yaml}). */
    private static final String REFRESH_CLIENT_ID = "refresh-client";

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
     * cannot be satisfied by any other reason {@code TokenRefreshCoordinator} defines for the
     * record.
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
        // The login on the plain route, the wait into the window and the call inside it are made once
        // for both REFRESHED tests; see REFRESHES_INSIDE_THE_WINDOW.
        ObservedRefreshes observed = REFRESHES_INSIDE_THE_WINDOW.get();
        String beforeRefresh = authorizationOf(served(observed.plainBeforeTheWindow()));
        Response afterRefresh = served(observed.plainInsideTheWindow());

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
    @DisplayName("REFRESHED on the scoped route: the rotated mediated token keeps the endpoint scope")
    void refreshedOutcomeKeepsTheEndpointScope() {
        // Arrange — first confirm this instance serves the scoped session route at all. It mounts the
        // shared endpoints/ tree under its own gateway.yaml, so bff-scoped is present only if that
        // overlay still declares the bff-session anchor; an unauthenticated navigation that is
        // redirected into the IdP proves the route exists and rules out a 404 masquerading as a scope
        // failure further down. The redirect is that of a pushed request and names no scope, so the
        // scope set the route asks for is proven by the granted token of the login below.
        Response initiation = BffKeycloakLoginFlow
                .gateway(Map.of(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(BffEndpointScopesIT.SCOPED_SESSION_PATH)
                .then().statusCode(302)
                .extract().response();
        BffLoginInitiationIT.assertPushedRequestRedirect(initiation, REFRESH_CLIENT_ID);

        // The login on the scoped route is one of the two logins REFRESHES_INSIDE_THE_WINDOW makes. When
        // the other REFRESHED test ran first, that login precedes the probe above; the probe needs no
        // session, so what it rules out does not depend on the order.
        ObservedRefreshes observed = REFRESHES_INSIDE_THE_WINDOW.get();
        String beforeRefresh = authorizationOf(served(observed.scopedBeforeTheWindow()));
        Set<String> grantedAtLogin = BffEndpointScopesIT.grantedScopes(beforeRefresh);
        assertTrue(grantedAtLogin.containsAll(BffEndpointScopesIT.SCOPED_ROUTE_SCOPES),
                "the login on the scoped route must be granted oidc.scopes united with the endpoint's "
                        + "scopes, so the refresh instance requests that route's needed set; granted "
                        + grantedAtLogin);

        // Act — the call on the scoped route inside the window
        String afterRefresh = authorizationOf(served(observed.scopedInsideTheWindow()));

        // Assert — the rotation is proven first, so the scope assertion is about the REFRESHED token
        // and cannot be satisfied by the login-time token merely being reused.
        assertNotEquals(beforeRefresh, afterRefresh,
                "the call inside the near-expiry window must mediate a rotated token");
        Set<String> refreshedScopes = BffEndpointScopesIT.grantedScopes(afterRefresh);
        assertTrue(refreshedScopes.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "the refresh must request the session's active scope set, so the rotated token must keep "
                        + BffEndpointScopesIT.ENDPOINT_SCOPE + "; granted " + refreshedScopes);
    }

    @Test
    @DisplayName("FAILED: an XHR after IdP-side revocation is rejected 401 problem+json and clears the cookie")
    void failedOutcomeRejectsXhrAndClearsTheSession() {
        // The login, the revocation, the wait into the window and the request are made once for both
        // FAILED tests, each on a session of its own; see REJECTIONS_AFTER_REVOCATION.
        Rejection rejection = REJECTIONS_AFTER_REVOCATION.get().ofTheXhr();
        Response response = rejection.response();

        assertEquals(401, response.statusCode(),
                "a failed refresh on a non-navigation request must be answered as unauthenticated");
        assertTrue(response.contentType().contains("application/problem+json"),
                "a failed refresh on a non-navigation request must render RFC 9457 problem+json");
        assertClearsSessionCookie(response);
        assertCredentialRejectedRecorded(rejection);
    }

    @Test
    @DisplayName("FAILED: a navigation after IdP-side revocation is redirected 302 into the IdP and clears the cookie")
    void failedOutcomeRedirectsNavigationAndClearsTheSession() {
        Rejection rejection = REJECTIONS_AFTER_REVOCATION.get().ofTheNavigation();
        Response response = rejection.response();

        assertEquals(302, response.statusCode(),
                "a failed refresh on a navigation must be answered with a redirect");
        String location = response.getHeader("Location");
        assertNotNull(location, "a navigation challenge must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "the navigation challenge must redirect into the OIDC authorization endpoint");
        assertClearsSessionCookie(response);
        assertCredentialRejectedRecorded(rejection);
    }

    // ---------------------------------------------------------------- what is observed once

    /**
     * Two sessions, each called once before the near-expiry window opens and once inside it.
     * Nothing is asserted when it is observed: each test asserts on the responses it reads.
     *
     * @param plainBeforeTheWindow  the session of a login on {@link #MEDIATED_PATH}, called right after it
     * @param plainInsideTheWindow  the same session, called inside the window
     * @param scopedBeforeTheWindow the session of a login on the scoped route, called on that route
     *                              right after it
     * @param scopedInsideTheWindow the same session, called on that route inside the window
     */
    private record ObservedRefreshes(Response plainBeforeTheWindow, Response plainInsideTheWindow,
    Response scopedBeforeTheWindow, Response scopedInsideTheWindow) {
    }

    /**
     * What one request on a session whose Keycloak session was revoked was answered with, and the
     * {@code credential-rejected} record count on either side of that one request.
     *
     * @param response      the gateway's answer
     * @param recordsBefore the count read immediately before the request was sent
     * @param recordsAfter  the count once it had risen above {@code recordsBefore}, or the last count
     *                      read when it had not within {@link #LOG_VISIBILITY_TIMEOUT_MILLIS}; read
     *                      before any other request of this suite was sent
     */
    private record Rejection(Response response, long recordsBefore, long recordsAfter) {
    }

    /**
     * @param ofTheXhr        the non-navigation request
     * @param ofTheNavigation the navigation, sent after the record of the XHR had been waited for
     */
    private record ObservedRejections(Rejection ofTheXhr, Rejection ofTheNavigation) {
    }

    /**
     * The {@code REFRESHED} pair. Both tests observe the same kind of event — the first call of a
     * session inside its near-expiry window — on two sessions that owe each other nothing, so the two
     * logins are made first and one wait carries both sessions into their windows.
     * <p>
     * Neither session waits less than {@value #WAIT_INTO_REFRESH_WINDOW_SECONDS} seconds between its
     * call before the window and its call inside it. The session of the plain route waits longer, by
     * the time the second login and its first call take, and so has that much less of the window left
     * before its token expires at 45 seconds.
     */
    private static final Once<ObservedRefreshes> REFRESHES_INSIDE_THE_WINDOW = new Once<>(
            "the two refreshes inside the window", () -> {
                Session plain = loginToRefreshInstance();
                Response plainBefore = unassertedCall(plain, MEDIATED_PATH);
                Session scoped = BffKeycloakLoginFlow.login(BffEndpointScopesIT.SCOPED_SESSION_PATH,
                        BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN,
                        BffKeycloakLoginFlow.REVOCATION_USERNAME, BffKeycloakLoginFlow.REVOCATION_PASSWORD);
                Response scopedBefore = unassertedCall(scoped, BffEndpointScopesIT.SCOPED_SESSION_PATH);

                sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
                Response plainInside = unassertedCall(plain, MEDIATED_PATH);
                Response scopedInside = unassertedCall(scoped, BffEndpointScopesIT.SCOPED_SESSION_PATH);
                return new ObservedRefreshes(plainBefore, plainInside, scopedBefore, scopedInside);
            });

    /**
     * The {@code FAILED} pair. Two logins, one revocation of every Keycloak session of the user — which
     * ends both — one wait, and then one request per session.
     * <p>
     * The revocation alone changes nothing observable: outside the window the coordinator returns
     * {@code CURRENT} without ever contacting the IdP, so a request would still succeed. The wait is
     * what forces the refresh attempt that then fails.
     * <p>
     * <strong>The two requests are not sent back to back.</strong> Each test attributes a
     * {@code credential-rejected} record to its own request by the count on either side of it. So the
     * record of the first request is waited for before the second request is sent; were both sent
     * first, the record of the second would satisfy the first test as well.
     */
    private static final Once<ObservedRejections> REJECTIONS_AFTER_REVOCATION = new Once<>(
            "the two rejections after the revocation", () -> {
                Session forTheXhr = loginToRefreshInstance();
                Session forTheNavigation = loginToRefreshInstance();
                revokeSessionsOf(BffKeycloakLoginFlow.REVOCATION_USERNAME);

                sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
                Rejection ofTheXhr = rejectionOf(forTheXhr, "application/json");
                Rejection ofTheNavigation = rejectionOf(forTheNavigation, "text/html");
                return new ObservedRejections(ofTheXhr, ofTheNavigation);
            });

    /** One request on the mediated route, with the record count read before it and waited for after it. */
    private static Rejection rejectionOf(Session session, String accept) {
        long recordsBefore = credentialRejectedRecordCount();
        Response response = BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .header("Accept", accept)
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
        return new Rejection(response, recordsBefore, awaitCredentialRejectedRecordAfter(recordsBefore));
    }

    /**
     * Something this class observes once per run, on first use, and several tests then read.
     * <p>
     * A failure to make the observation is kept as well, so the observation — and the wait inside it —
     * is attempted once per run; every test that reads it then fails with that failure as its cause.
     *
     * @param <T> what is observed
     */
    private static final class Once<T> {

        private final String what;
        private final Supplier<T> observation;
        private T observed;
        private Throwable failure;

        Once(String what, Supplier<T> observation) {
            this.what = what;
            this.observation = observation;
        }

        synchronized T get() {
            if (observed == null && failure == null) {
                // The catch below is deliberately wide: whatever stops the observation is kept, so it is
                // attempted once per run and every test that reads it fails with that failure as its cause.
                // cui-rewrite:disable InvalidExceptionUsageRecipe
                try {
                    observed = observation.get();
                } catch (RuntimeException | AssertionError thrown) {
                    failure = thrown;
                }
            }
            if (failure != null) {
                throw new AssertionError(what + " could not be observed: " + failure, failure);
            }
            return observed;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Session loginToRefreshInstance() {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.REVOCATION_USERNAME, BffKeycloakLoginFlow.REVOCATION_PASSWORD);
    }

    private static Response mediatedCall(Session session) {
        return mediatedCall(session, MEDIATED_PATH);
    }

    private static Response mediatedCall(Session session, String path) {
        return BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .when().get(path)
                .then().statusCode(200)
                .extract().response();
    }

    /** {@link #mediatedCall(Session, String)} without its status assertion, for a response a test asserts on later. */
    private static Response unassertedCall(Session session, String path) {
        return BffKeycloakLoginFlow
                .gateway(session.gatewayCookies(), BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN)
                .when().get(path)
                .then().extract().response();
    }

    /**
     * The status assertion {@link #mediatedCall(Session, String)} makes, for a response observed earlier.
     *
     * @param response the response
     * @return {@code response}
     */
    private static Response served(Response response) {
        assertEquals(200, response.statusCode(), "the session must be served by the refresh instance");
        return response;
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
     * Waits, for at most {@link #LOG_VISIBILITY_TIMEOUT_MILLIS}, for the {@code credential-rejected}
     * record count to rise above {@code countBefore}, and asserts nothing.
     *
     * @return the count once it had risen, or the last count read when it had not
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a bind-mounted log append
    private static long awaitCredentialRejectedRecordAfter(long countBefore) {
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
        return observed;
    }

    /**
     * Asserts that the request of {@code rejection} recorded a new {@code credential-rejected} refresh
     * failure, so the {@code FAILED} leg is green for the named disposition rather than for any
     * session loss.
     */
    private static void assertCredentialRejectedRecorded(Rejection rejection) {
        long countBefore = rejection.recordsBefore();
        long finalObserved = rejection.recordsAfter();
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
