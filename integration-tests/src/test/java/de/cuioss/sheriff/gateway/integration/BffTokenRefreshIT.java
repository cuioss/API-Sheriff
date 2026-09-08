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

import java.nio.charset.StandardCharsets;
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
 *   <li>{@code CURRENT} — two mediated calls inside the window carry a <em>byte-identical</em>
 *       {@code Authorization} header. A refresh would have rotated it.</li>
 *   <li>{@code REFRESHED} — a mediated call after the window opens carries a <em>different</em>
 *       {@code Authorization} header. The proof is the changed upstream bearer, never session
 *       continuity: a session that merely still works proves only that it was not destroyed, which is
 *       equally true of {@code CURRENT}.</li>
 *   <li>{@code FAILED} — after the IdP revokes the session, a mediated call once the window has
 *       opened is rejected as unauthenticated and the session cookie is cleared.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not exercise refresh-token reuse or
 * family revocation, cookie-mode re-seal on rotation, a refresh racing session expiry, concurrent
 * requests coalescing onto one single-flight refresh, or an IdP returning a failure other than
 * {@code invalid_grant}. It also asserts nothing about browser cookie policy — it replays a cookie
 * map, exactly as {@link BffKeycloakLoginFlow} documents.
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
 * its null guard and the near-expiry path runs. The five assertions below are unchanged — they
 * asserted the specified behaviour throughout, never the observed one, so they became the
 * regression guard for the fix without a line of them moving.
 * <p>
 * <strong>Bounding the result.</strong> Exercised: the near-expiry trigger on a server-mode session
 * with a 45-second access token; IdP-side session invalidation via admin logout; the XHR and
 * navigation challenge legs. NOT exercised, and therefore neither confirmed nor disproved:
 * refresh-token reuse and family revocation, cookie-mode re-seal on rotation, a refresh racing
 * session expiry, concurrent requests coalescing onto one single-flight refresh, and an IdP
 * returning a failure other than {@code invalid_grant}.
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

    @Test
    @DisplayName("the refresh-client access token is minted with the declared 45-second lifespan")
    void mediatedTokenCarriesTheDeclaredLifespan() {
        Session session = loginToRefreshInstance();

        String bearer = mediatedBearer(session);
        JsonPath claims = decodeJwtPayload(bearer);
        long issuedAt = claims.getLong("iat");
        long expiresAt = claims.getLong("exp");

        assertEquals(ACCESS_TOKEN_LIFESPAN_SECONDS, expiresAt - issuedAt,
                "the mediated token must carry refresh-client's declared access.token.lifespan; "
                        + "without it the near-expiry window is unreachable and every branch below "
                        + "would silently assert the CURRENT path");
    }

    @Test
    @DisplayName("CURRENT: two calls inside the window mediate a byte-identical bearer and rotate nothing")
    void currentOutcomeReusesTheMediatedToken() {
        Session session = loginToRefreshInstance();

        Response first = mediatedCall(session);
        Response second = mediatedCall(session);

        String firstBearer = authorizationOf(first);
        String secondBearer = authorizationOf(second);
        assertEquals(firstBearer, secondBearer,
                "inside the leeway window no refresh is due, so the mediated bearer must be the very "
                        + "same token; a difference here means a refresh was driven early");
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
    }

    @Test
    @DisplayName("FAILED: a navigation after IdP-side revocation is redirected 302 into the IdP and clears the cookie")
    void failedOutcomeRedirectsNavigationAndClearsTheSession() {
        Session session = loginToRefreshInstance();
        revokeSessionsOf(BffKeycloakLoginFlow.REFRESH_USERNAME);

        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
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

    private static String mediatedBearer(Session session) {
        return authorizationOf(mediatedCall(session));
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
     * Asserts the response clears the browser session cookie — the observable that distinguishes a
     * destroyed session from a merely rejected request.
     */
    private static void assertClearsSessionCookie(Response response) {
        List<String> setCookies = response.getHeaders().getValues("Set-Cookie");
        assertFalse(setCookies.isEmpty(),
                "a failed refresh destroys the session, so the response must clear the session cookie");
        boolean clearing = setCookies.stream().anyMatch(BffTokenRefreshIT::isClearingCookie);
        assertTrue(clearing,
                "expected a clearing Set-Cookie (empty value, Max-Age=0 or a past Expires); got " + setCookies);
    }

    private static boolean isClearingCookie(String setCookie) {
        String lower = setCookie.toLowerCase(Locale.ROOT);
        return lower.contains("max-age=0")
                || lower.contains("expires=thu, 01 jan 1970")
                || lower.matches("^[^=]+=;.*");
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
