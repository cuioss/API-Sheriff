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
     * Pins the target of the {@code gatewayHealth} k6 benchmark.
     * <p>
     * Consumer: {@code benchmarks/src/main/resources/k6-scripts/gateway_health.js}, whose
     * {@code TARGET_URL} default is {@code https://api-sheriff:9000/q/health} — the same scheme and
     * path this test drives, on the same management port.
     * <p>
     * <strong>Contract being pinned:</strong> moving this endpoint, or reverting the management
     * interface to plain HTTP, breaks the {@code gatewayHealth} benchmark. This guard lives here
     * rather than in the benchmark pre-flight because {@code .github/workflows/benchmark.yml} runs
     * only on {@code pull_request: types: [closed]}, tags and {@code workflow_dispatch} — it never
     * runs on an open PR and is therefore not merge-queue gated. That blind spot is precisely how a
     * red {@code gatewayHealth} benchmark merged four times. The integration-test job IS merge-queue
     * gated, so a break is caught before merge only if it is caught here.
     */
    @Test
    void benchmarkGatewayHealthTargetServesOverHttps() {
        given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health")
                .then()
                .statusCode(200);
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