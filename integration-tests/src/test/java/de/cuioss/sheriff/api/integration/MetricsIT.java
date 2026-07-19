/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Prometheus metrics surface is exposed on the management port ({@code /q/metrics},
 * plain HTTP, off the public data-plane port) per {@code architecture.adoc} § Metrics.
 * <p>
 * Traffic is driven through the {@code /proxy} route first so the registry has request activity to
 * report, then the management endpoint is scraped and asserted to return the Prometheus exposition
 * format with the Micrometer JVM/HTTP baseline meters. The management port carries no authentication
 * of its own, so the scrape needs no credentials.
 */
class MetricsIT extends BaseIntegrationTest {

    @Test
    @DisplayName("the management metrics endpoint serves the Prometheus exposition format")
    void metricsEndpointServesPrometheusFormat() {
        // Drive one request so the registry has activity to expose.
        given()
                .when()
                .get("/proxy/get?probe=metrics")
                .then()
                .statusCode(200);

        String body = given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertTrue(body.contains("# TYPE") || body.contains("# HELP"),
                "the metrics endpoint must serve the Prometheus exposition format");
        assertTrue(body.contains("jvm_"),
                "the Micrometer JVM baseline meters must be exposed on the management port");
    }
}
