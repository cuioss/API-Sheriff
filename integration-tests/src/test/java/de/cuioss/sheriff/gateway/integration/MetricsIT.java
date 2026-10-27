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
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.Set;

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
 * <p>
 * {@code sheriff_auth_branch_total{route,branch}} is recorded only on {@code session_fallback}
 * routes, once per request at branch resolution, so it is driven on the {@code bff-session-fallback}
 * route (see {@link BffSessionFallbackIT}) and asserted absent for every route that is not a
 * {@code session_fallback} route. The stack declares two such routes; the second,
 * {@code bff-unassigned-scope-fallback}, is driven by {@link BffSessionScopeParityIT} against this
 * same gateway instance, so its series may or may not be present when this suite scrapes.
 * <p>
 * {@code sheriff_token_validation_errors_total} is not registered by gateway code: the
 * token-validation extension's metrics collector publishes it for the {@code @GatewayValidator}
 * validator (ADR-0074). A rejected bearer token moving that series on the native image is the proof
 * that the collector is registered in the image and observes the validator the request path uses.
 */
class MetricsIT extends BaseIntegrationTest {

    private static final String REQUESTS_TOTAL = "sheriff_requests_total";
    private static final String HTTPBIN_ROUTE_LABEL = "route=\"httpbin-proxy\"";
    private static final String SECURITY_EVENTS_TOTAL = "sheriff_security_events_total";
    private static final String FAILURE_TYPE_LABEL = "failure_type=";
    private static final String AUTH_BRANCH_TOTAL = "sheriff_auth_branch_total";
    private static final String FALLBACK_ROUTE_LABEL = "route=\"" + BffSessionFallbackIT.FALLBACK_ROUTE_ID + "\"";

    /**
     * The {@code route} label of every {@code session_fallback} route the stack declares — the only
     * routes that may appear on a {@code sheriff_auth_branch_total} series. Each id is owned by the
     * suite that drives its route.
     */
    private static final Set<String> SESSION_FALLBACK_ROUTE_LABELS = Set.of(FALLBACK_ROUTE_LABEL,
            "route=\"" + BffSessionScopeParityIT.UNASSIGNED_FALLBACK_ROUTE_ID + "\"");
    private static final String BEARER_BRANCH_LABEL = "branch=\"bearer\"";
    private static final String SESSION_BRANCH_LABEL = "branch=\"session\"";

    /** A path on the plain {@code require: session} route {@code bff-session-mediated}. */
    private static final String SESSION_ROUTE_PATH = "/app/get";

    /** A path on the plain {@code require: bearer} route of the {@code secure} anchor. */
    private static final String BEARER_ROUTE_PATH = "/secure/get";

    /**
     * The token-validation error counter the token-validation extension's metrics collector publishes
     * for the gateway validator (ADR-0074). Its base unit {@code errors} already ends the meter name,
     * so the exposed name carries no unit infix.
     */
    private static final String TOKEN_VALIDATION_ERRORS_TOTAL = "sheriff_token_validation_errors_total";
    private static final String FAILURE_RESULT_LABEL = "result=\"failure\"";

    /** The closed set of {@code category} labels the error counter carries, one per failure category. */
    private static final Set<String> TOKEN_VALIDATION_CATEGORY_LABELS = Set.of("category=\"INVALID_STRUCTURE\"",
            "category=\"INVALID_SIGNATURE\"", "category=\"SEMANTIC_ISSUES\"");

    /**
     * How long a rejected token may take to reach the error counter. The collector exports the gateway
     * validator's counts once per collection interval (10 s by default), so the bound covers two ticks
     * plus scrape latency on a loaded host.
     */
    private static final Duration TOKEN_VALIDATION_EXPORT_BOUND = Duration.ofSeconds(45);
    private static final long POLL_INTERVAL_MILLIS = 500;

    @Test
    @DisplayName("the management metrics endpoint serves the Prometheus exposition format")
    void metricsEndpointServesPrometheusFormat() {
        // Drive one request so the registry has activity to expose.
        given()
                .when()
                .get("/proxy/get?probe=metrics")
                .then()
                .statusCode(200);

        String body = PrometheusScrape.scrape();

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
        double baseline = PrometheusScrape.sum(PrometheusScrape.scrape(), REQUESTS_TOTAL,
                HTTPBIN_ROUTE_LABEL);

        // Act — drive a successful proxied GET so the edge records its request, duration, and
        // upstream-duration meters. A 200 means the request traversed the full pipeline and the
        // downstream call completed, exercising every non-error sheriff_* recording surface.
        given()
                .when()
                .get("/proxy/get?probe=sheriff-metrics")
                .then()
                .statusCode(200);

        String body = PrometheusScrape.scrape();

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
        double after = PrometheusScrape.sum(body, REQUESTS_TOTAL, HTTPBIN_ROUTE_LABEL);
        assertTrue(after > baseline,
                "a proxied request must move sheriff_requests_total for route httpbin-proxy: before="
                        + baseline + ", after=" + after);
    }

    @Test
    @DisplayName("sheriff_security_events_total appears and moves on the metrics endpoint after a security-filter rejection")
    void securityEventsMeterAppearsAndMovesAfterRejection() {
        // Arrange — the boot-shared cui-http SecurityEventCounter is bound to Micrometer, so the meter
        // is exposed from boot for the fixed UrlSecurityFailureType enum; capture its pre-rejection sum.
        String before = PrometheusScrape.scrape();
        assertTrue(before.contains("sheriff_security_events_total"),
                "sheriff_security_events_total must be exposed once the shared counter is bound at boot");
        double baseline = PrometheusScrape.sum(before, SECURITY_EVENTS_TOTAL, FAILURE_TYPE_LABEL);

        // Act — drive a filter rejection: a query-parameter value carrying an encoded path-traversal
        // attack. urlEncodingEnabled(false) sends the pre-encoded value verbatim so cui-http decodes
        // and rejects it (SECURITY_FILTER_VIOLATION), incrementing the shared SecurityEventCounter
        // the meter is bound to. The rejection happens POST-route in ThoroughChecksStage — the
        // url-parameter validation was relocated there so it runs under the route's own profile
        // (ADR-0024) — so the untrusted caller is answered with the unrouted 404 (ADR-0073). Both are
        // immaterial to the meter, but the /proxy route must stay non-'none' for this rejection to
        // occur at all.
        given()
                .urlEncodingEnabled(false)
                .when()
                .get("/proxy/get?probe=%2E%2E%2F%2E%2E%2F%2E%2E%2Fetc%2Fpasswd")
                .then()
                .statusCode(404);

        // Assert — the bound function counter moved: the summed sheriff_security_events_total series is
        // strictly greater than the baseline, proving the counter → Micrometer bridge works end-to-end.
        String after = PrometheusScrape.scrape();
        assertTrue(after.contains("sheriff_security_events_total"),
                "sheriff_security_events_total must remain exposed after the rejection");
        assertTrue(PrometheusScrape.sum(after, SECURITY_EVENTS_TOTAL, FAILURE_TYPE_LABEL) > baseline,
                "a security-filter rejection must move sheriff_security_events_total on the metrics endpoint");
    }

    @Test
    @DisplayName("sheriff_auth_branch_total counts both branches on a session_fallback route and on no other kind of route")
    void authBranchMeterCountsBothBranchesOnSessionFallbackRoutesOnly() {
        // Arrange — capture both branch series of the fallback route before the act; Micrometer emits a
        // series only once it has been recorded, so an absent series sums to 0.0.
        String before = PrometheusScrape.scrape();
        double bearerBaseline = PrometheusScrape.sum(before, AUTH_BRANCH_TOTAL, FALLBACK_ROUTE_LABEL,
                BEARER_BRANCH_LABEL);
        double sessionBaseline = PrometheusScrape.sum(before, AUTH_BRANCH_TOTAL, FALLBACK_ROUTE_LABEL,
                SESSION_BRANCH_LABEL);
        String token = BffSessionFallbackIT.mintFallbackRouteToken();

        // Act — one BEARER and one SESSION request on the fallback route. The SESSION request is an XHR
        // with neither Authorization nor a session, so it is challenged 401, but the branch is resolved
        // and counted before authentication runs. Then one request on a plain require: session route and
        // one on a plain require: bearer route: both resolve a branch as well, and must record none.
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(BffSessionFallbackIT.FALLBACK_PATH)
                .then()
                .statusCode(200);
        given()
                .header("Accept", "application/json")
                .when()
                .get(BffSessionFallbackIT.FALLBACK_PATH)
                .then()
                .statusCode(401);
        given()
                .header("Accept", "application/json")
                .when()
                .get(SESSION_ROUTE_PATH)
                .then()
                .statusCode(401);
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(BEARER_ROUTE_PATH)
                .then()
                .statusCode(200);

        String after = PrometheusScrape.scrape();

        // Assert — each branch series of the fallback route MOVED, which presence alone cannot prove.
        double bearerAfter = PrometheusScrape.sum(after, AUTH_BRANCH_TOTAL, FALLBACK_ROUTE_LABEL,
                BEARER_BRANCH_LABEL);
        double sessionAfter = PrometheusScrape.sum(after, AUTH_BRANCH_TOTAL, FALLBACK_ROUTE_LABEL,
                SESSION_BRANCH_LABEL);
        assertTrue(bearerAfter > bearerBaseline,
                "a request with Authorization on the session_fallback route must move the branch=\"bearer\" "
                        + "series: before=" + bearerBaseline + ", after=" + bearerAfter);
        assertTrue(sessionAfter > sessionBaseline,
                "a request without Authorization on the session_fallback route must move the "
                        + "branch=\"session\" series: before=" + sessionBaseline + ", after=" + sessionAfter);

        // Assert — the meter is scoped to session_fallback routes and its branch label is bounded: every
        // exposed series names one of the declared session_fallback routes and one of the two branch
        // values. The plain session route and the plain bearer route driven above are the routes this
        // rules out: each resolved a branch in this very test, and neither may have recorded one.
        List<String> samples = PrometheusScrape.samples(after, AUTH_BRANCH_TOTAL);
        List<String> foreignRoute = samples.stream()
                .filter(line -> SESSION_FALLBACK_ROUTE_LABELS.stream().noneMatch(line::contains))
                .toList();
        assertTrue(foreignRoute.isEmpty(),
                "sheriff_auth_branch_total must be recorded only on session_fallback routes, but exposes "
                        + foreignRoute);
        List<String> foreignBranch = samples.stream()
                .filter(line -> !line.contains(BEARER_BRANCH_LABEL) && !line.contains(SESSION_BRANCH_LABEL))
                .toList();
        assertTrue(foreignBranch.isEmpty(),
                "sheriff_auth_branch_total must carry only branch=\"bearer\" or branch=\"session\", but exposes "
                        + foreignBranch);
    }

    @Test
    @DisplayName("sheriff_token_validation_errors_total moves after a rejected bearer token, exported by the extension's collector")
    void tokenValidationErrorsMeterMovesAfterRejectedBearerToken() {
        // Arrange — the collector registers one series per failure event type when it is created, and it
        // is created on its first scheduled tick, so on a fresh instance the meter may still be absent
        // and sums to 0.0.
        double baseline = PrometheusScrape.sum(PrometheusScrape.scrape(), TOKEN_VALIDATION_ERRORS_TOTAL,
                FAILURE_RESULT_LABEL);

        // Act — a malformed bearer token on the plain require: bearer route reaches the gateway validator
        // and is rejected there. The caller has not passed the trust boundary, so the shipped default
        // answers with the unrouted 404 (ADR-0073); the validator still records the failure event.
        given()
                .header("Authorization", "Bearer not-a-real-jwt")
                .when()
                .get(BEARER_ROUTE_PATH)
                .then()
                .statusCode(404);

        // Assert — the series MOVED once the collector exported the validator's counts. Movement, not
        // presence, is the proof: the collector registers its series at creation, so only a strictly
        // greater sum shows it observes the validator the request path used.
        String after = awaitTokenValidationErrorsAbove(baseline);

        // Assert — the labels are the bounded set ADR-0074 names: every sample is a failure, carries an
        // event_type, and names one of the three failure categories.
        List<String> samples = PrometheusScrape.samples(after, TOKEN_VALIDATION_ERRORS_TOTAL);
        List<String> unbounded = samples.stream()
                .filter(line -> !line.contains(FAILURE_RESULT_LABEL) || !line.contains("event_type=\"")
                        || TOKEN_VALIDATION_CATEGORY_LABELS.stream().noneMatch(line::contains))
                .toList();
        assertTrue(unbounded.isEmpty(),
                "sheriff_token_validation_errors_total must carry only result=\"failure\", an event_type and a "
                        + "known category, but exposes " + unbounded);
    }

    /**
     * Scrapes until the summed failure series of {@value #TOKEN_VALIDATION_ERRORS_TOTAL} exceeds
     * {@code baseline}, bounded by {@link #TOKEN_VALIDATION_EXPORT_BOUND}.
     *
     * @param baseline the sum observed before the rejected request
     * @return the first exposition body whose sum exceeds {@code baseline}
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll for a scheduled exporter's next tick
    private static String awaitTokenValidationErrorsAbove(double baseline) {
        long deadline = System.nanoTime() + TOKEN_VALIDATION_EXPORT_BOUND.toNanos();
        double observed = baseline;
        while (System.nanoTime() < deadline) {
            String body = PrometheusScrape.scrape();
            observed = PrometheusScrape.sum(body, TOKEN_VALIDATION_ERRORS_TOTAL, FAILURE_RESULT_LABEL);
            if (observed > baseline) {
                return body;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting the token-validation export",
                        interrupted);
            }
        }
        return fail("a rejected bearer token must move " + TOKEN_VALIDATION_ERRORS_TOTAL + " within "
                + TOKEN_VALIDATION_EXPORT_BOUND.toSeconds() + "s: before=" + baseline + ", last=" + observed);
    }
}
