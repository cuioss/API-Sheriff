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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.http.Cookie;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the transparent token refresh of a <em>stateless sealed-cookie</em> session end to end,
 * against the dedicated {@code api-sheriff-cookie-refresh} instance
 * ({@link BffKeycloakLoginFlow#COOKIE_REFRESH_GATEWAY_ORIGIN}).
 * <p>
 * <strong>Why a dedicated instance.</strong> Three process-wide properties have to hold at once here,
 * and no existing instance holds all three: the session mode must be {@code cookie}, refresh must be
 * enabled, and the access token must be short-lived enough for a test to reach the near-expiry
 * window. {@code api-sheriff-cookie} runs cookie mode with refresh <em>off</em>, and
 * {@code api-sheriff-refresh} runs the refresh path in <em>server</em> mode, where the browser
 * holds an opaque handle and a rotation writes server-side.
 * This instance is the only place the two meet: it authenticates as {@code refresh-client} (whose
 * client-level {@code access.token.lifespan} is 45, so with {@code leeway_seconds: 30} the window is
 * 15s..45s) and declares no {@code max_cookie_size} override, so it runs on the lowered browser-safe
 * default.
 * <p>
 * <strong>What this suite proves.</strong> In cookie mode the session <em>is</em> the cookie, so a
 * refresh cannot be persisted server-side — the only way to keep the rotated tokens is to re-seal and
 * re-emit them to the browser. Each assertion below is an observable that would differ had that
 * re-seal not happened:
 * <ul>
 *   <li><em>CURRENT</em> — two mediated calls before the window opens carry a byte-identical
 *       {@code Authorization} header and the response emits no {@code Set-Cookie}: outside the window
 *       nothing is due, so a re-seal here would mean a refresh was driven early.</li>
 *   <li><em>REFRESHED</em> — a mediated call after a bounded wait into the window carries a
 *       <em>different</em> bearer <em>and</em> exactly one {@code Set-Cookie} naming the session
 *       cookie with a non-empty value. Both halves are load-bearing: a rotated bearer with no
 *       re-seal would mean the browser keeps a cookie sealing the <em>old</em> tokens, and a re-seal
 *       with an unchanged bearer would mean nothing rotated.</li>
 *   <li><em>Deliverability</em> — that re-sealed {@code Set-Cookie} must fit the browser's per-cookie
 *       budget ({@link BffKeycloakLoginFlow#assertCookiesFitBrowserBudget(Response)}). This is the
 *       measurement the whole rig exists for: a seal carrying access + id + <em>refresh</em> is the
 *       largest a cookie-mode session ever gets.</li>
 *   <li><em>Continuity</em> — the re-sealed value still resolves on a subsequent request, so the
 *       rotation left the browser holding a cookie that works rather than one that merely arrived.</li>
 *   <li><em>No lifetime extension</em> — the re-seal's {@code Max-Age} is strictly less than the
 *       login's. A refresh rotates tokens; it must never restart the session's absolute lifetime,
 *       which would make an endlessly-refreshed session immortal.</li>
 * </ul>
 * <p>
 * <strong>A red run at login is a result, not a broken fixture.</strong> If the sealed session with
 * refresh material exceeds the configured budget, {@code CookieSessionBinding} fails loudly rather
 * than handing back an unusable binding, so the login round trip itself goes red. That outcome is the
 * negative answer to the question this instance was built to ask, and it must be read as such rather
 * than worked around by raising the instance's budget — which would only move the failure into the
 * browser, where an oversized {@code Set-Cookie} is dropped silently.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It asserts nothing about real browser cookie
 * policy: like every {@code Bff*IT}, it replays a cookie map and enforces no {@code SameSite},
 * {@code Secure} or {@code __Host-} rule (see {@link BffKeycloakLoginFlow}'s LIMITATION note). The
 * size assertion above is the one narrow deliverability check available at this layer — it measures
 * what the gateway <em>emits</em>, never what a browser <em>keeps</em>. It also does not exercise the
 * {@code FAILED} refresh branch, refresh-token reuse or family revocation, concurrent requests
 * coalescing onto one single-flight refresh, or a refresh racing the session's absolute deadline.
 * <p>
 * <strong>Timing.</strong> The waits are wall-clock and deliberate: the property under test is
 * defined in elapsed time against a token lifespan, so there is no state to poll for.
 * <p>
 * This suite deliberately does not extend {@code BaseIntegrationTest}: that base binds the primary
 * instance's origin, while every request here must go to this instance.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieRefreshIT {

    /** The cookie-mode-with-refresh gateway instance every request in this suite drives. */
    private static final String COOKIE_REFRESH_ORIGIN = BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN;

    /**
     * The browser session cookie this instance binds. The overlay declares no
     * {@code session.cookie_name}, so it resolves the default. Spelled out rather than imported: this
     * is a black-box IT and the cookie name is part of the wire contract it observes.
     */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** The require:session route that mediates a bearer to the go-httpbin echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    /**
     * Seconds to wait before a call that must land inside the near-expiry window.
     * <p>
     * The window opens at {@code lifespan - leeway} = 45 - 30 = 15s and closes when the token expires
     * at 45s. 22 sits inside it with margin at both ends: past the 15s opening so a slow runner cannot
     * land early, and far enough from 45s that the token has not simply expired — which would be a
     * different failure than the re-seal under test.
     */
    private static final int WAIT_INTO_REFRESH_WINDOW_SECONDS = 22;

    @Test
    @DisplayName("CURRENT: before the window opens the bearer is byte-identical and nothing is re-sealed")
    void currentOutcomeMediatesTheSameBearerAndResealsNothing() {
        // Arrange
        Session session = login();

        // Act — deliberately NO wait: both calls land within a second or two of login, and the
        // near-expiry window does not open until 15s. This is the before-the-window control that gives
        // the refreshed test below its meaning; the two straddle the same edge from opposite sides.
        Response first = mediatedCall(session.gatewayCookies());
        Response second = mediatedCall(session.gatewayCookies());

        // Assert
        assertEquals(authorizationOf(first), authorizationOf(second),
                "before the leeway window opens no refresh is due, so the mediated bearer must be the "
                        + "very same token; a difference here means a refresh was driven early");
        List<String> setCookies = second.getHeaders().getValues("Set-Cookie");
        assertTrue(setCookies.isEmpty(),
                "a CURRENT outcome rotates nothing, so there is nothing to re-seal and no Set-Cookie "
                        + "may be emitted; got " + setCookies);
    }

    @Test
    @DisplayName("REFRESHED: a call inside the window rotates the bearer and re-seals one browser-sized cookie")
    void refreshedOutcomeRotatesTheBearerAndResealsTheCookie() {
        // Arrange
        Session session = login();
        String beforeRefresh = authorizationOf(mediatedCall(session.gatewayCookies()));

        // Act
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        Response afterRefresh = mediatedCall(session.gatewayCookies());

        // Assert — the rotated bearer and the re-seal are asserted together. Either one alone is
        // satisfiable by a broken half: a rotation the browser never receives, or a cookie re-emitted
        // around unchanged tokens.
        assertNotEquals(beforeRefresh, authorizationOf(afterRefresh),
                "once the mediated token is within leeway of expiry the coordinator must rotate it; "
                        + "an unchanged bearer means the refresh never ran");
        List<String> setCookies = afterRefresh.getHeaders().getValues("Set-Cookie");
        assertEquals(1, setCookies.size(),
                "the sealed cookie IS the session, so a rotation must re-emit exactly one Set-Cookie — "
                        + "there is nowhere else the rotated tokens could be kept; got " + setCookies);
        Cookie resealed = afterRefresh.getDetailedCookies().get(SESSION_COOKIE);
        assertNotNull(resealed, "the re-emitted Set-Cookie must name " + SESSION_COOKIE);
        assertFalse(resealed.getValue() == null || resealed.getValue().isEmpty(),
                "a re-seal carries a fresh sealed value; an empty one would clear the session instead");
        assertNotEquals(session.gatewayCookies().get(SESSION_COOKIE), resealed.getValue(),
                "the re-sealed value must differ from the one sealed at login, or the rotated tokens "
                        + "never reached the browser");

        // The measurement this whole instance exists for: a seal carrying access + id + refresh is the
        // largest a cookie-mode session ever gets, and a browser drops an oversized Set-Cookie
        // silently — no error, no header, no session.
        BffKeycloakLoginFlow.assertCookiesFitBrowserBudget(afterRefresh);
    }

    @Test
    @DisplayName("the re-sealed cookie still resolves on the next request")
    void theResealedCookieStillResolvesOnASubsequentRequest() {
        // Arrange
        Session session = login();
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        Response afterRefresh = mediatedCall(session.gatewayCookies());
        String resealed = afterRefresh.getCookie(SESSION_COOKIE);
        assertNotNull(resealed, "this test needs an actual re-seal to replay; none was emitted");
        Map<String, String> resealedJar = new HashMap<>(session.gatewayCookies());
        resealedJar.put(SESSION_COOKIE, resealed);

        // Act — replay the browser's NEW cookie, exactly as a browser would on the following request.
        Response subsequent = mediatedCall(resealedJar);

        // Assert — a re-seal that arrives but cannot be unsealed again would leave the browser holding
        // a cookie that fails on the very next navigation, which the rotation assertion alone cannot
        // detect.
        assertEquals("GET", subsequent.path("method"),
                "the re-sealed cookie must resolve to the same live session and keep mediating");
        assertNotNull(authorizationOf(subsequent),
                "the re-sealed session must still mediate a bearer upstream");
    }

    @Test
    @DisplayName("the re-seal carries the remaining lifetime, never a restarted one")
    void theResealDoesNotExtendTheSession() {
        // Arrange
        Session session = login();
        Cookie atLogin = session.callbackCookies().get(SESSION_COOKIE);
        assertNotNull(atLogin, "the login must establish the sealed session cookie");

        // Act
        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        Cookie resealed = mediatedCall(session.gatewayCookies()).getDetailedCookies().get(SESSION_COOKIE);
        assertNotNull(resealed, "this test needs an actual re-seal to measure; none was emitted");

        // Assert — strictly less, not merely different: the absolute deadline is anchored at login, so
        // by the time the window opens the remaining lifetime has provably shrunk. An equal or larger
        // Max-Age is the immortal-session defect, where every refresh restarts the clock.
        assertTrue(resealed.getMaxAge() < atLogin.getMaxAge(),
                "a refresh rotates tokens and must not restart the session lifetime: login carried "
                        + "Max-Age=" + atLogin.getMaxAge() + ", the re-seal carried Max-Age="
                        + resealed.getMaxAge());
    }

    // ---------------------------------------------------------------- helpers

    private static Session login() {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, COOKIE_REFRESH_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
    }

    private static Response mediatedCall(Map<String, String> gatewayCookies) {
        return BffKeycloakLoginFlow.gateway(gatewayCookies, COOKIE_REFRESH_ORIGIN)
                .when().get(MEDIATED_PATH)
                .then().statusCode(200)
                .extract().response();
    }

    /**
     * Reads the bearer the gateway injected upstream, as echoed back by go-httpbin.
     *
     * @param echoed the upstream echo response
     * @return the echoed {@code Authorization} header value
     */
    private static String authorizationOf(Response echoed) {
        Object authorization = echoed.path("headers.Authorization");
        assertNotNull(authorization, "the sealed session must mediate a bearer to the upstream");
        String value = authorization.toString();
        assertTrue(value.contains("Bearer"), "the mediated upstream credential must be a bearer token");
        return value;
    }

    /**
     * Bounded wall-clock wait. The property under test is defined in elapsed time against a token
     * lifespan, so there is no condition to poll and nothing for Awaitility to shorten.
     *
     * @param seconds the seconds to wait
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
