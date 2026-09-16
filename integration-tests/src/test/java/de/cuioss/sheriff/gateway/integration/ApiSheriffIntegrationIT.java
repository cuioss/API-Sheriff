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
     * The Quarkus health endpoint answers {@code 200} with a JSON body over HTTPS on the management
     * interface — the same endpoint the {@code gatewayHealth} k6 benchmark resolves to.
     * <p>
     * <strong>Contract being pinned:</strong> moving this endpoint, or reverting the management
     * interface to plain HTTP, breaks the {@code gatewayHealth} benchmark. This guard lives here
     * rather than in the benchmark pre-flight because {@code .github/workflows/benchmark.yml} runs
     * only on {@code pull_request: types: [closed]}, tags and {@code workflow_dispatch} — it never
     * runs on an open PR and is therefore not merge-queue gated. That blind spot is precisely how a
     * red {@code gatewayHealth} benchmark merged four times. The integration-test job IS merge-queue
     * gated, so a break is caught before merge only if it is caught here.
     * <p>
     * It pins the endpoint, not the benchmark's own target resolution: this test reaches the endpoint
     * through {@link BaseIntegrationTest#givenManagement()}, while
     * {@code benchmarks/src/main/resources/k6-scripts/gateway_health.js} reaches it through
     * {@code lib/target.js}'s {@code managementUrl('/health')} or {@code __ENV.TARGET_URL}, so a change
     * to the benchmark's target leaves this test green.
     */
    @Test
    void quarkusHealthEndpoint() {
        givenManagement()
                .when()
                .get("/health")
                .then()
                .statusCode(200)
                .contentType("application/json");
    }

    /**
     * Test that metrics endpoint is available on the management interface.
     */
    @Test
    void metricsEndpoint() {
        givenManagement()
                .when()
                .get("/metrics")
                .then()
                .statusCode(200);
    }
}