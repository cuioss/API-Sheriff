/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the Variant 3 <em>stateless sealed-cookie</em> session end to end against the dedicated
 * cookie-mode gateway instance ({@code api-sheriff-cookie}, published on {@code 10445}): the
 * Keycloak login round trip, steady-state token mediation, session continuity across requests, and
 * RP-initiated logout — plus the two properties that distinguish the sealed cookie from the landed
 * server-mode opaque handle.
 * <p>
 * <strong>Hardening.</strong> The session cookie is {@code __Host-}-prefixed and carries
 * {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/} and no {@code Domain} — the
 * browser only honours the {@code __Host-} prefix under exactly that combination, so the prefix and
 * the attributes are asserted together rather than as independent facts.
 * <p>
 * <strong>Opacity.</strong> In this mode the cookie <em>is</em> the session, so the token material
 * genuinely crosses to the browser — sealed. The opacity assertion is therefore made against the
 * real mediated access token (echoed back by the go-httpbin upstream) rather than a marker string:
 * no segment of the live JWT may appear in the cookie value. A heuristic marker such as
 * {@code "eyJ"} would match a random position of a multi-kilobyte base64 ciphertext often enough to
 * be flaky, and would prove nothing when it did not.
 * <p>
 * <strong>Logout is the stateless trade-off, asserted honestly.</strong> RP-initiated logout clears
 * the browser's copy of the cookie; it cannot invalidate a copy an attacker already holds, because a
 * stateless gateway keeps no server-side index to revoke against. So this suite asserts the clearing
 * {@code Set-Cookie} — the authoritative local effect in this mode — and deliberately does
 * <em>not</em> assert that a replayed stale jar is rejected, which is a server-mode-only property
 * ({@code BffLogoutIT}). The sealed cookie's absolute TTL, enforced server-side against the sealed
 * login instant, is what bounds a retained copy.
 * <p>
 * Every request binds the cookie-instance origin explicitly rather than relying on the
 * {@code BaseIntegrationTest} defaults, which point at the primary server-mode instance ({@code
 * 10443}) — a default-bound request here would silently exercise the wrong session binding and pass.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieSessionIT {

    /** The dedicated cookie-mode gateway instance every request in this suite drives. */
    private static final String COOKIE_ORIGIN = BffKeycloakLoginFlow.COOKIE_GATEWAY_ORIGIN;

    /** The {@code __Host-}-prefixed default session-cookie name (the cookie gateway.yaml sets none). */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** The require:session route mediating a bearer to the go-httpbin echo upstream. */
    private static final String SESSION_ROUTE = "/bff-session/get";

    /** Matches the compact JWT the gateway mediates upstream, whatever surrounds it in the echo. */
    private static final Pattern COMPACT_JWT =
            Pattern.compile("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    @Test
    @DisplayName("the cookie-mode login sets a __Host- prefixed Secure HttpOnly SameSite=Lax session cookie")
    void sealedSessionCookieIsHardened() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_ORIGIN);

        // Act
        Cookie sessionCookie = session.callbackCookies().get(SESSION_COOKIE);

        // Assert — the __Host- prefix is a browser contract, not a naming convention: it is honoured
        // only together with Secure, Path=/ and no Domain, so all four are one assertion group.
        assertNotNull(sessionCookie, "the cookie-mode callback must set the sealed " + SESSION_COOKIE + " cookie");
        assertTrue(sessionCookie.isSecured(), "the __Host- prefix requires Secure");
        assertEquals("/", sessionCookie.getPath(), "the __Host- prefix requires Path=/");
        assertNull(sessionCookie.getDomain(), "the __Host- prefix requires no Domain attribute");
        assertTrue(sessionCookie.isHttpOnly(), "the sealed session must be unreadable from script");
        assertEquals("Lax", sessionCookie.getSameSite(),
                "SameSite=Lax survives a top-level navigation while blocking cross-site sends");
    }

    @Test
    @DisplayName("the sealed cookie value is opaque ciphertext carrying no readable token substring")
    void sealedCookieValueCarriesNoReadableToken() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_ORIGIN);
        String sealed = session.gatewayCookies().get(SESSION_COOKIE);
        assertNotNull(sealed, "the login must establish a sealed session cookie");

        // Act — the upstream echo hands back the exact bearer the session mediated, which is the very
        // token material the cookie carries; asserting against it makes "no readable token" precise.
        Response echo = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), COOKIE_ORIGIN)
                .when().get(SESSION_ROUTE)
                .then().statusCode(200).extract().response();
        String mediatedToken = compactJwtIn(String.valueOf(echo.path("headers.Authorization")));

        // Assert — no segment of the live access token may be readable in the cookie the browser holds.
        for (String segment : mediatedToken.split("\\.")) {
            assertFalse(sealed.contains(segment),
                    "the sealed cookie must carry no readable segment of the mediated access token");
        }
        byte[] raw = Base64.getUrlDecoder().decode(sealed);
        assertEquals((byte) 1, raw[0],
                "the cookie value must be the version-1 sealed layout, not an opaque server-side handle");
    }

    @Test
    @DisplayName("the sealed session mediates a bearer upstream and stays usable across requests")
    void sealedSessionMediatesAndStaysUsable() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_ORIGIN);

        // Act + Assert — two sequential mediated requests both reach the upstream through the same
        // sealed session. The transparent near-expiry refresh (leeway_seconds: 30) re-seals only when
        // the mediated token is within its leeway of expiry; the integration realm's 900s access-token
        // lifespan keeps it well inside validity here, so this asserts the always-available continuity
        // path rather than forcing a refresh with a wall-clock wait.
        for (int request = 0; request < 2; request++) {
            Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), COOKIE_ORIGIN)
                    .when().get(SESSION_ROUTE)
                    .then().statusCode(200).extract().response();

            assertEquals("GET", response.path("method"),
                    "every mediated request in the live sealed session must reach the upstream");
            Object authorization = response.path("headers.Authorization");
            assertNotNull(authorization, "the sealed session must mediate a bearer to the upstream");
            assertTrue(authorization.toString().contains("Bearer"),
                    "the mediated upstream credential must be a bearer token");
            assertNull(response.path("headers.Cookie"),
                    "the sealed session cookie must never be forwarded upstream");
        }
    }

    @Test
    @DisplayName("RP-initiated logout redirects into the round trip and clears the sealed cookie")
    void rpInitiatedLogoutClearsTheSealedCookie() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_ORIGIN);

        // Act
        Response logout = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), COOKIE_ORIGIN)
                .redirects().follow(false)
                .when().get("/auth/logout")
                .then().statusCode(302).extract().response();

        // Assert — a stateless binding holds nothing to destroy, so the authoritative local effect of
        // logout is the clearing Set-Cookie that removes the browser's copy.
        assertNotNull(logout.header("Location"), "RP-initiated logout must redirect into the logout round trip");
        Cookie cleared = logout.getDetailedCookies().get(SESSION_COOKIE);
        assertNotNull(cleared, "logout must emit the clearing Set-Cookie for the sealed session");
        assertEquals(0, cleared.getMaxAge(), "the clearing cookie must expire immediately");
        assertTrue(cleared.getValue() == null || cleared.getValue().isEmpty(),
                "the clearing cookie must carry no sealed value");
    }

    @Test
    @DisplayName("a tampered sealed cookie yields an unauthenticated response, never a 500")
    void tamperedCookieIsUnauthenticatedNeverServerError() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_ORIGIN);
        Map<String, String> tamperedJar = new HashMap<>(session.gatewayCookies());
        tamperedJar.put(SESSION_COOKIE, tamper(session.gatewayCookies().get(SESSION_COOKIE)));

        // Act + Assert — unsealing is fail-closed: whether the flipped character breaks the base64
        // decode or the GCM authentication tag, the outcome is "no session", so the XHR is challenged
        // 401. Asserting the exact status is what rules out the 500 a throwing unseal would produce.
        BffKeycloakLoginFlow.gateway(tamperedJar, COOKIE_ORIGIN)
                .header("Accept", "application/json")
                .when().get(SESSION_ROUTE)
                .then().statusCode(401);
    }

    /**
     * Flips the final character of the sealed value, which sits inside the authentication tag.
     *
     * @param sealed the sealed cookie value
     * @return the tampered value, still in the base64url alphabet
     */
    private static String tamper(String sealed) {
        assertNotNull(sealed, "the login must establish a sealed session cookie");
        char last = sealed.charAt(sealed.length() - 1);
        return sealed.substring(0, sealed.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    /**
     * Extracts the compact JWT out of the upstream echo's {@code Authorization} rendering, which may
     * be a bare string or a single-element list depending on how go-httpbin serialises the header.
     *
     * @param authorizationEcho the echoed header value
     * @return the compact JWT the gateway mediated
     */
    private static String compactJwtIn(String authorizationEcho) {
        Matcher matcher = COMPACT_JWT.matcher(authorizationEcho);
        assertTrue(matcher.find(),
                "the mediated upstream credential must be a compact JWT, was: " + authorizationEcho);
        return matcher.group();
    }
}
