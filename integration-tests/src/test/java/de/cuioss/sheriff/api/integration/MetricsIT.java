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
 * format with both the Micrometer JVM/HTTP baseline meters and the gateway's own {@code sheriff_*}
 * meters. Because Micrometer only exposes a meter once it has been recorded, the presence of a
 * {@code sheriff_*} series after proxy traffic is proof the edge records it end-to-end. The
 * management port carries no authentication of its own, so the scrape needs no credentials.
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

        String body = scrapeMetrics();

        assertTrue(body.contains("# TYPE") || body.contains("# HELP"),
                "the metrics endpoint must serve the Prometheus exposition format");
        assertTrue(body.contains("jvm_"),
                "the Micrometer JVM baseline meters must be exposed on the management port");
    }

    @Test
    @DisplayName("the sheriff_* meters appear and move on /q/metrics after proxy traffic")
    void sheriffMetersAppearAndMoveAfterProxyTraffic() {
        // Arrange + Act — drive a successful proxied GET so the edge records its request, duration,
        // and upstream-duration meters. A 200 means the request traversed the full pipeline and the
        // downstream call completed, exercising every non-error sheriff_* recording surface.
        given()
                .when()
                .get("/proxy/get?probe=sheriff-metrics")
                .then()
                .statusCode(200);

        String body = scrapeMetrics();

        // Assert — the gateway's own meters are present (Micrometer only emits recorded meters), so
        // their presence proves GatewayEdgeRoute records them end-to-end, not just that the adapter
        // registers the names.
        assertTrue(body.contains("sheriff_requests_total"),
                "sheriff_requests_total must be emitted after a proxied request");
        assertTrue(body.contains("sheriff_request_duration_seconds"),
                "sheriff_request_duration_seconds must be emitted after a proxied request");
        assertTrue(body.contains("sheriff_upstream_duration_seconds"),
                "sheriff_upstream_duration_seconds must be emitted once the downstream call completes");

        // Assert — the bounded labels are wired: the config-fixed route id and the status family
        // (never the raw status), keeping label cardinality bounded per architecture.adoc § Metrics.
        assertTrue(body.contains("route=\"httpbin-proxy\""),
                "the request counter must carry the config-fixed, bounded route label");
        assertTrue(body.contains("status_family=\"2xx\""),
                "the request counter must carry the bounded status_family label, not the raw status");
    }

    private static String scrapeMetrics() {
        return given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }
}
