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
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the two server-mode BFF logout legs.
 * <p>
 * <strong>RP-initiated logout (caveat a).</strong> The IdP {@code TokenRevocation} seam is bound as a
 * best-effort no-op — {@code RpInitiatedLogout}'s contract makes local session destruction the
 * authoritative logout. So this suite asserts the session is destroyed <em>locally</em> (the old cookie
 * jar no longer authorizes the protected route), not that the IdP token was revoked. The
 * {@code /auth/logout} entry redirects {@code 302} into the RP-initiated round-trip that ends at the
 * configured {@code final_redirect}. That redirect targets the realm's end-session endpoint and its
 * query carries the {@code id_token_hint}, so the suite also asserts the two headers the gateway
 * answers it with: {@code Referrer-Policy: no-referrer} and {@code Cache-Control: no-store}.
 * <p>
 * <strong>Back-channel logout (caveat b).</strong> The back-channel {@code LogoutTokenVerifier} is
 * bound to the id-token JWKS validation bridge. Minting a real Keycloak-issued logout token requires
 * driving Keycloak's admin back-channel machinery, which is out of reach of a black-box suite; this
 * suite instead proves the receiver is wired at the live edge (reachable, not {@code NO_ROUTE_MATCHED})
 * and fail-closed: an empty back-channel post is rejected {@code 400} and marked uncacheable.
 */
class BffLogoutIT extends BaseIntegrationTest {

    /**
     * The end-session endpoint the integration realm publishes. The realm pins its frontend URL, so
     * the discovery document names the container-internal authority, and that is what the gateway
     * redirects the browser to.
     */
    private static final String END_SESSION_ENDPOINT = "https://" + BffKeycloakLoginFlow.KEYCLOAK_INTERNAL_AUTHORITY
            + "/realms/integration/protocol/openid-connect/logout";

    @Test
    @DisplayName("RP-initiated logout redirects into the round-trip and destroys the local session")
    void rpInitiatedLogoutDestroysLocalSession() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var logout = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .redirects().follow(false)
                .when()
                .get("/auth/logout")
                .then()
                .statusCode(302)
                .extract();
        String location = logout.header("Location");
        assertNotNull(location, "RP-initiated logout must redirect into the logout round-trip");
        // The redirect goes to the identity provider's end-session endpoint and its query carries the
        // session's ID token, so the answer that hands it out is neither cacheable nor a Referer source.
        // The location itself stays out of every message for the same reason.
        assertTrue(location.startsWith(END_SESSION_ENDPOINT + "?"),
                "RP-initiated logout with a live session must redirect to the realm's end-session endpoint");
        assertTrue(location.contains("id_token_hint="),
                "the end-session redirect must carry the id_token_hint of the session it ends");
        assertEquals("no-referrer", logout.header("Referrer-Policy"),
                "the redirect that carries the ID token must be answered with Referrer-Policy: no-referrer");
        assertEquals("no-store", logout.header("Cache-Control"),
                "the redirect that carries the ID token must not be cacheable");

        // The authoritative effect is local session destruction: replaying the (now stale) cookie jar on
        // the require:session route is challenged 401 — the session no longer resolves.
        BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .when()
                .get("/bff-session/get")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("the back-channel logout receiver is wired and fail-closed: an empty post is rejected 400 no-store")
    void backChannelReceiverIsWiredAndFailClosed() {
        var response = given()
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/auth/backchannel")
                .then()
                .statusCode(400)
                .extract();

        assertEquals("no-store", response.header("Cache-Control"),
                "a back-channel logout response must be uncacheable");
    }
}
