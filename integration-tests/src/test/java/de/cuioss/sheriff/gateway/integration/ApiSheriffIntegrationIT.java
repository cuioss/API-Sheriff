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

import org.junit.jupiter.api.Test;

/**
 * Integration tests for the API Sheriff management interface in a native Quarkus application context.
 * <p>
 * The pre-1.0 clean-break removed the placeholder {@code /api/health} + {@code /api/info} data-plane
 * endpoints; the gateway now exposes only the deny-by-default data-plane edge (covered by
 * {@link PipelineVerbIT} / {@link BearerValidationIT}) plus the Quarkus management port. These tests
 * assert the surviving management-port surface.
 *
 * @author API Sheriff Team
 */
class ApiSheriffIntegrationIT extends BaseIntegrationTest {

    /**
     * Test that the Quarkus health check endpoint is available on the management interface.
     */
    @Test
    void quarkusHealthEndpoint() {
        given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health")
                .then()
                .statusCode(200)
                .contentType("application/json");
    }

    /**
     * Test that metrics endpoint is available on the management interface.
     */
    @Test
    void metricsEndpoint() {
        given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200);
    }
}