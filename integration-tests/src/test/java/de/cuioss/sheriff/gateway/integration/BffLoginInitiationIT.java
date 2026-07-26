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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the reserved {@code /auth/login} login-initiation fold end-to-end.
 * <p>
 * Without a session the fold starts a fresh auth-code flow — a {@code 302} into the IdP authorization
 * endpoint carrying the confidential {@code client_id}, {@code response_type=code}, and a PKCE
 * {@code code_challenge}. With a live session it short-circuits {@code 302} to the same-origin-validated
 * {@code return_to} target rather than re-driving the IdP, so an already-authenticated browser is not
 * bounced through Keycloak again.
 */
class BffLoginInitiationIT extends BaseIntegrationTest {

    @Test
    @DisplayName("login initiation without a session redirects 302 into the IdP with a PKCE auth-code request")
    void loginInitiationStartsAuthCodeFlow() {
        var response = given()
                .redirects().follow(false)
                .when()
                .get("/auth/login")
                .then()
                .statusCode(302)
                .extract();

        String location = response.header("Location");
        assertNotNull(location, "login initiation must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "login initiation must redirect into the OIDC authorization endpoint");
        assertTrue(location.contains("response_type=code"), "the flow must be an authorization-code request");
        assertTrue(location.contains("client_id=integration-client"),
                "the flow must use the confidential integration client");
        assertTrue(location.contains("code_challenge="), "the flow must be PKCE-protected");
    }

    @Test
    @DisplayName("login initiation with a live session short-circuits to the validated return target, not the IdP")
    void loginInitiationShortCircuitsForLiveSession() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .redirects().follow(false)
                .when()
                .get("/auth/login?return_to=/home")
                .then()
                .statusCode(302)
                .extract();

        String location = response.header("Location");
        assertNotNull(location, "a live-session login initiation must still redirect");
        assertFalse(location.contains("/protocol/openid-connect/auth"),
                "an already-authenticated session must not be re-driven through the IdP");
    }
}
