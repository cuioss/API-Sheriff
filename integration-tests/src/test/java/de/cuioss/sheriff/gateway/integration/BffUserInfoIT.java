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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the reserved {@code /auth/userinfo} session/user-info fold end-to-end.
 * <p>
 * Without a session the fold is closed ({@code 401}); with a live session it serves the curated
 * default view, capped by the operator claim allowlist. The mounted {@code oidc.user_info} config sets
 * {@code default_view: [sub, preferred_username]}, so the response discloses exactly those claims — the
 * seeded {@code integration-user}'s {@code preferred_username} is the observable identity proof.
 */
class BffUserInfoIT extends BaseIntegrationTest {

    @Test
    @DisplayName("the user-info fold is closed without a session (401)")
    void userInfoRequiresSession() {
        given()
                .header("Accept", "application/json")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("the user-info fold serves the curated default view for a live session")
    void userInfoServesCuratedDefaultView() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("integration-user", response.path("claims.preferred_username"),
                "the curated default view must disclose the session user's preferred_username");
        assertNull(response.path("claims.client_secret"),
                "the fold must never disclose a claim outside the operator allowlist");
    }
}
