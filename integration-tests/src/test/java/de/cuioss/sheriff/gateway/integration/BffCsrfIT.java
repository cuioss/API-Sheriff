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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the fixed CSRF defence on the require:session {@code /bff-session} route. The defence has no
 * disable knob and runs <em>before</em> session resolution, so an unsafe-method request with a foreign
 * {@code Origin} is rejected {@code 403} regardless of whether a session cookie is present — no login is
 * required to prove the rejection. A trusted {@code Origin} passes the CSRF gate, after which the
 * (missing) session is what shapes the response, proving the {@code 403} was specifically the CSRF gate
 * and not the auth stage.
 */
class BffCsrfIT extends BaseIntegrationTest {

    @Test
    @DisplayName("an unsafe method with a foreign Origin is rejected 403 by the CSRF defence")
    void unsafeMethodForeignOriginRejected() {
        var response = given()
                .header("Origin", "https://evil.example.com")
                .header("Accept", "application/json")
                .when()
                .post("/bff-session/post")
                .then()
                .statusCode(403)
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"),
                "a CSRF rejection must render RFC 9457 problem+json");
    }

    @Test
    @DisplayName("an unsafe method with no Origin and no Sec-Fetch-Site proof is rejected 403")
    void unsafeMethodNoOriginProofRejected() {
        given()
                .header("Accept", "application/json")
                .when()
                .post("/bff-session/post")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("a trusted Origin passes the CSRF gate and then requires a session (401, not 403)")
    void trustedOriginPassesCsrfThenRequiresSession() {
        // The CSRF gate accepts the trusted gateway origin, so control reaches the session stage; with no
        // session cookie the non-navigation request is challenged 401 — distinguishing the CSRF 403 above
        // from the downstream auth 401.
        given()
                .header("Origin", BffKeycloakLoginFlow.GATEWAY_ORIGIN)
                .header("Accept", "application/json")
                .when()
                .post("/bff-session/post")
                .then()
                .statusCode(401);
    }
}
