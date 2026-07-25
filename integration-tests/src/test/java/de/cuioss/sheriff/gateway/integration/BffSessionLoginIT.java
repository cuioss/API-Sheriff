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

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full server-mode BFF authorization-code login through the reserved {@code /auth/callback}
 * against the compose Keycloak {@code integration} realm.
 * <p>
 * The scripted browser-less flow ({@link BffKeycloakLoginFlow}) navigates onto the require:session
 * {@code /bff-session} route, follows the {@code 302} into Keycloak, submits the seeded
 * {@code integration-user} credentials, and follows the callback back to the gateway. The observable
 * proof that the server-side session was created is that the gateway set a session cookie on the
 * callback response <em>and</em> that replaying that cookie jar on the protected route now serves the
 * upstream (a fresh, cookieless request is challenged — covered by {@link BffSessionMediationIT}).
 */
class BffSessionLoginIT extends BaseIntegrationTest {

    @Test
    @DisplayName("a completed auth-code login establishes a session cookie that authorizes the protected route")
    void loginEstablishesUsableSession() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        assertFalse(session.gatewayCookies().isEmpty(),
                "the callback must set a session cookie establishing the server-side session");

        // The established session authorizes the require:session route: the go-httpbin echo upstream
        // is reached (method GET) only because the session mediated the request past stage 4.
        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .when()
                .get("/bff-session/get")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"),
                "the authorized session request must reach the go-httpbin echo upstream");
    }
}
