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
 * Verifies the Prometheus metrics surface is exposed on the management port — at {@code metrics}
 * beneath the configured {@code quarkus.management.root-path}, off the public data-plane port — per
 * {@code architecture.adoc} § Metrics.
 * <p>
 * Traffic is driven through the {@code /proxy} route first so the registry has request activity to
 * report, then the management endpoint is scraped and asserted to return the Prometheus exposition
 * format with both the Micrometer JVM/HTTP baseline meters and the gateway's own {@code sheriff_*}
 * meters. Because Micrometer only exposes a meter once it has been recorded, the presence of a
 * {@code sheriff_*} series after proxy traffic is proof the edge records it end-to-end. The
 * management port carries no authentication of its own, so the scrape needs no credentials.
 */
class MetricsIT extends BaseIntegrationTest {

    private static final String REQUESTS_TOTAL = "sheriff_requests_total";
    private static final String HTTPBIN_ROUTE_LABEL = "route=\"httpbin-proxy\"";
    private static final String SECURITY_EVENTS_TOTAL = "sheriff_security_events_total";
    private static final String FAILURE_TYPE_LABEL = "failure_type=";

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
    @DisplayName("the sheriff_* meters appear and move on the metrics endpoint after proxy traffic")
    void sheriffMetersAppearAndMoveAfterProxyTraffic() {
        // Arrange — capture the route's request count before the act. Micrometer only emits a meter
        // once it has been recorded, so on a fresh instance the series may be absent and sums to 0.0.
        double baseline = meterSum(scrapeMetrics(), REQUESTS_TOTAL, HTTPBIN_ROUTE_LABEL);

        // Act — drive a successful proxied GET so the edge records its request, duration, and
        // upstream-duration meters. A 200 means the request traversed the full pipeline and the
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

        // Assert — the counter MOVED: presence alone is satisfied by a series recorded by any earlier
        // request, so only a strictly greater route sum proves this request was counted.
        double after = meterSum(body, REQUESTS_TOTAL, HTTPBIN_ROUTE_LABEL);
        assertTrue(after > baseline,
                "a proxied request must move sheriff_requests_total for route httpbin-proxy: before="
                        + baseline + ", after=" + after);
    }

    @Test
    @DisplayName("sheriff_security_events_total appears and moves on the metrics endpoint after a security-filter rejection")
    void securityEventsMeterAppearsAndMovesAfterRejection() {
        // Arrange — the boot-shared cui-http SecurityEventCounter is bound to Micrometer, so the meter
        // is exposed from boot for the fixed UrlSecurityFailureType enum; capture its pre-rejection sum.
        String before = scrapeMetrics();
        assertTrue(before.contains("sheriff_security_events_total"),
                "sheriff_security_events_total must be exposed once the shared counter is bound at boot");
        double baseline = meterSum(before, SECURITY_EVENTS_TOTAL, FAILURE_TYPE_LABEL);

        // Act — drive a filter rejection: a query-parameter value carrying an encoded path-traversal
        // attack. urlEncodingEnabled(false) sends the pre-encoded value verbatim so cui-http decodes
        // and rejects it (400 SECURITY_FILTER_VIOLATION), incrementing the shared SecurityEventCounter
        // the meter is bound to. The rejection now happens POST-route in ThoroughChecksStage — the
        // url-parameter validation was relocated there so it runs under the route's own profile
        // (ADR-0024) — which is immaterial to the meter, but the /proxy route must stay non-'none'
        // for this rejection to occur at all.
        given()
                .urlEncodingEnabled(false)
                .when()
                .get("/proxy/get?probe=%2E%2E%2F%2E%2E%2F%2E%2E%2Fetc%2Fpasswd")
                .then()
                .statusCode(400);

        // Assert — the bound function counter moved: the summed sheriff_security_events_total series is
        // strictly greater than the baseline, proving the counter → Micrometer bridge works end-to-end.
        String after = scrapeMetrics();
        assertTrue(after.contains("sheriff_security_events_total"),
                "sheriff_security_events_total must remain exposed after the rejection");
        assertTrue(meterSum(after, SECURITY_EVENTS_TOTAL, FAILURE_TYPE_LABEL) > baseline,
                "a security-filter rejection must move sheriff_security_events_total on the metrics endpoint");
    }

    /**
     * Sums every sample of one meter in a Prometheus exposition body across the labelled series that
     * carry {@code requiredLabel}. A sample line starts with the meter name followed directly by its
     * label set, so {@code # HELP} / {@code # TYPE} comment lines (which begin with {@code #}) and
     * meters that merely share the name as a prefix are never counted. A meter with no matching series
     * — for example one not yet recorded — sums to {@code 0.0}.
     *
     * @param body the scraped exposition body
     * @param meterName the exposed meter name, including any {@code _total} suffix Micrometer appends
     * @param requiredLabel a label fragment every counted series must contain, such as
     *            {@code route="httpbin-proxy"}
     * @return the summed sample value of the matching series
     */
    private static double meterSum(String body, String meterName, String requiredLabel) {
        double sum = 0.0;
        for (String line : body.split("\n")) {
            if (line.startsWith(meterName + "{") && line.contains(requiredLabel)) {
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace >= 0) {
                    sum += Double.parseDouble(line.substring(lastSpace + 1).strip());
                }
            }
        }
        return sum;
    }

    private static String scrapeMetrics() {
        return givenManagement()
                .when()
                .get("/metrics")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }
}
