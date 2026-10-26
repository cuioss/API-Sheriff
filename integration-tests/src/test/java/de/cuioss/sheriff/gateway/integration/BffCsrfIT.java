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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the fixed CSRF defence on the require:session {@code /app} route. The defence has no
 * disable knob and runs <em>before</em> session resolution, so an unsafe-method request with a foreign
 * {@code Origin} is refused regardless of whether a session cookie is present — no login is required to
 * prove the refusal. A trusted {@code Origin} passes the CSRF gate, after which the (missing) session is
 * what shapes the response, proving the refusal was specifically the CSRF gate and not the auth stage.
 * <p>
 * The defence runs before the trust boundary, so the shipped default answers its refusal to the caller
 * as the unrouted {@code 404} (ADR-0073). The cause is asserted on the TRUE event,
 * {@code CSRF_REJECTED}, which the refusal still moves in {@code sheriff_errors_total} on the route.
 */
class BffCsrfIT extends BaseIntegrationTest {

    /** The config-fixed id of the {@code /app} session route, the {@code route} label of its error series. */
    private static final String SESSION_ROUTE_ID = "bff-session-mediated";

    private static final String UNSAFE_PATH = "/app/post";

    @Test
    @DisplayName("an unsafe method with a foreign Origin is refused by the CSRF defence as the unrouted 404")
    void unsafeMethodForeignOriginRejected() {
        var response = UniformNotFoundAssertions.assertTrueEventMetered(SESSION_ROUTE_ID, "CSRF_REJECTED",
                () -> given()
                        .header("Origin", "https://evil.example.com")
                        .header("Accept", "application/json")
                        .when()
                        .post(UNSAFE_PATH)
                        .then()
                        .extract());

        UniformNotFoundAssertions.assertUnroutedProblem(response, "a cross-origin unsafe request");
    }

    @Test
    @DisplayName("an unsafe method with no Origin and no Sec-Fetch-Site proof is refused by the CSRF defence")
    void unsafeMethodNoOriginProofRejected() {
        var response = UniformNotFoundAssertions.assertTrueEventMetered(SESSION_ROUTE_ID, "CSRF_REJECTED",
                () -> given()
                        .header("Accept", "application/json")
                        .when()
                        .post(UNSAFE_PATH)
                        .then()
                        .extract());

        UniformNotFoundAssertions.assertUnroutedProblem(response, "an unsafe request without an Origin proof");
    }

    @Test
    @DisplayName("a trusted Origin passes the CSRF gate and then requires a session (401, not the CSRF refusal)")
    void trustedOriginPassesCsrfThenRequiresSession() {
        // The CSRF gate accepts the trusted gateway origin, so control reaches the session stage; with no
        // session cookie the non-navigation request is challenged 401 — the session branch keeps its own
        // negotiation — distinguishing the CSRF refusal above from the downstream auth 401.
        given()
                .header("Origin", BffKeycloakLoginFlow.GATEWAY_ORIGIN)
                .header("Accept", "application/json")
                .when()
                .post(UNSAFE_PATH)
                .then()
                .statusCode(401);
    }
}
