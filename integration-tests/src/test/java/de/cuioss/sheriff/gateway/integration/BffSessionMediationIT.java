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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the server-mode BFF token-mediation semantics on the require:session {@code /bff-session}
 * route: an authenticated session mediates a bearer to the upstream while the browser session cookie is
 * stripped, an unauthenticated XHR is rejected {@code 401 application/problem+json}, and an
 * unauthenticated navigation is redirected {@code 302} into the IdP.
 * <p>
 * The mediated route proxies to the go-httpbin echo backend, so the forwarded request is fully
 * observable: {@code headers.Authorization} carries the injected bearer (Authorization is allow-listed
 * on the route) and {@code headers.Cookie} is absent (Cookie is deny-by-default and never forwarded).
 * <p>
 * The sibling {@code /bff-session/norelay} route resolves {@code auth.token_relay: false}: the same
 * session is required, but no {@code Authorization} header reaches the origin — neither the mediated
 * bearer nor one the client sends — while the unauthenticated {@code 302}/{@code 401} negotiation is
 * unchanged.
 */
class BffSessionMediationIT extends BaseIntegrationTest {

    private static final String NORELAY_PATH = "/bff-session/norelay/get";

    @Test
    @DisplayName("an authenticated session injects the bearer upstream and never forwards the session cookie")
    void mediatesBearerAndStripsSessionCookie() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .when()
                .get("/bff-session/get")
                .then()
                .statusCode(200)
                .extract().response();

        Object authorization = response.path("headers.Authorization");
        assertNotNull(authorization, "the session must mediate a bearer to the upstream");
        assertTrue(authorization.toString().contains("Bearer"),
                "the mediated upstream credential must be a bearer token");
        assertNull(response.path("headers.Cookie"),
                "the browser session cookie must never be forwarded upstream");
    }

    @Test
    @DisplayName("an unauthenticated XHR on a require:session route is rejected 401 problem+json")
    void unauthenticatedXhrRejected() {
        var response = given()
                .header("Accept", "application/json")
                .when()
                .get("/bff-session/get")
                .then()
                .statusCode(401)
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"),
                "an unauthenticated non-navigation request must render RFC 9457 problem+json");
        assertNull(response.path("method"), "a challenged request must never reach the upstream");
    }

    @Test
    @DisplayName("an unauthenticated navigation on a require:session route is redirected 302 into the IdP")
    void unauthenticatedNavigationRedirectsToIdp() {
        var response = given()
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when()
                .get("/bff-session/get")
                .then()
                .statusCode(302)
                .extract();

        String location = response.header("Location");
        assertNotNull(location, "a navigation challenge must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "the navigation challenge must redirect into the OIDC authorization endpoint");
    }

    @Test
    @DisplayName("the mediated session stays usable across sequential requests (transparent refresh path)")
    void mediatedSessionStaysUsableAcrossRequests() {
        // Session continuity: two sequential mediated requests both reach the upstream through the same
        // server-side session. The transparent near-expiry refresh (leeway_seconds: 30) only re-drives
        // the RefreshFlow when the mediated token is within its leeway of expiry; the integration realm's
        // 900s access-token lifespan keeps the token well inside its validity for both requests, so this
        // asserts the always-available continuity path rather than forcing a refresh. Forcing the refresh
        // needs a client whose access-token lifespan is short enough to reach the window; that is what
        // BffTokenRefreshIT does, against the dedicated api-sheriff-refresh instance and its 45s
        // refresh-client.
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        for (int request = 0; request < 2; request++) {
            var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                    .when()
                    .get("/bff-session/get")
                    .then()
                    .statusCode(200)
                    .extract();
            assertEquals("GET", response.path("method"),
                    "every mediated request in the live session must reach the upstream");
        }
    }

    @Test
    @DisplayName("a token_relay:false route serves a live session without relaying any Authorization upstream")
    void tokenRelayOptOutRelaysNoAuthorization() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        // The client also sends an Authorization of its own: it must not cross either, so the origin sees
        // no Authorization header at all — the session's token stays at the gateway.
        Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Authorization", "Bearer client-supplied-token")
                .when()
                .get(NORELAY_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("GET", response.path("method"),
                "the live session must still authorize the token_relay:false route to reach the upstream");
        assertNull(response.path("headers.Authorization"),
                "a token_relay:false route must relay neither the mediated bearer nor the client's Authorization");
        assertNull(response.path("headers.Cookie"),
                "the browser session cookie must never be forwarded upstream");
    }

    @Test
    @DisplayName("a token_relay:false route still challenges an unauthenticated XHR with 401")
    void tokenRelayOptOutStillChallengesXhr() {
        var response = given()
                .header("Accept", "application/json")
                .when()
                .get(NORELAY_PATH)
                .then()
                .statusCode(401)
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"),
                "token_relay:false must not relax the session requirement for a non-navigation request");
        assertNull(response.path("method"), "a challenged request must never reach the upstream");
    }

    @Test
    @DisplayName("a token_relay:false route still redirects an unauthenticated navigation into the IdP")
    void tokenRelayOptOutStillRedirectsNavigation() {
        var response = given()
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when()
                .get(NORELAY_PATH)
                .then()
                .statusCode(302)
                .extract();

        String location = response.header("Location");
        assertNotNull(location, "a navigation challenge must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "token_relay:false must not relax the login requirement for a navigation");
    }
}
