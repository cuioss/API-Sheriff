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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import de.cuioss.http.security.core.UrlSecurityFailureType;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.auth.AuthBranch;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.bff.runtime.DiscoveryStatus;
import de.cuioss.sheriff.gateway.bff.runtime.DiscoveryStatus.State;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.Metadata;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the D4/D5 metrics-and-readiness surface: {@link SheriffMetrics} registers the meter
 * names named in {@code architecture.adoc} § Metrics (including the BFF session-lifecycle counter and
 * the bounded {@code session_fallback} authentication-branch counter),
 * and {@link GatewayReadinessCheck} reflects configuration, JWKS status, and — for an active BFF
 * runtime in {@code server} or {@code cookie} mode — an {@code oidc=<mode>} datum beside an
 * {@code oidc_discovery} datum read from the non-fetching {@link DiscoveryStatus}, which is reported
 * but never changes the UP/DOWN verdict.
 */
class SheriffMetricsTest {

    @Nested
    @DisplayName("SheriffMetrics registers the architecture.adoc meter names")
    class MeterNames {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final SheriffMetrics metrics = new SheriffMetrics(registry);

        @Test
        @DisplayName("meter-name constants match architecture.adoc verbatim")
        void meterNameConstantsMatchArchitecture() {
            assertEquals("sheriff_requests_total", SheriffMetrics.REQUESTS_TOTAL);
            assertEquals("sheriff_request_duration_seconds", SheriffMetrics.REQUEST_DURATION_SECONDS);
            assertEquals("sheriff_errors_total", SheriffMetrics.ERRORS_TOTAL);
            assertEquals("sheriff_security_events_total", SheriffMetrics.SECURITY_EVENTS_TOTAL);
            assertEquals("sheriff_upstream_duration_seconds", SheriffMetrics.UPSTREAM_DURATION_SECONDS);
            assertEquals("sheriff_session_events_total", SheriffMetrics.SESSION_EVENTS_TOTAL);
            assertEquals("sheriff_auth_branch_total", SheriffMetrics.AUTH_BRANCH_TOTAL);
        }

        @Test
        @DisplayName("recordAuthBranch counts under sheriff_auth_branch_total{route,branch} per (route, branch) pair")
        void recordAuthBranchCountsPerRouteAndBranch() {
            metrics.recordAuthBranch("fallback", AuthBranch.BEARER);
            metrics.recordAuthBranch("fallback", AuthBranch.BEARER);
            metrics.recordAuthBranch("fallback", AuthBranch.SESSION);
            metrics.recordAuthBranch("other-fallback", AuthBranch.BEARER);

            var fallbackBearer = registry.find("sheriff_auth_branch_total")
                    .tags("route", "fallback", "branch", "bearer").counter();
            var fallbackSession = registry.find("sheriff_auth_branch_total")
                    .tags("route", "fallback", "branch", "session").counter();
            var otherBearer = registry.find("sheriff_auth_branch_total")
                    .tags("route", "other-fallback", "branch", "bearer").counter();
            assertAll("one series per (route, branch) pair",
                    () -> assertNotNull(fallbackBearer, "the bearer branch must be tagged with the label 'bearer'"),
                    () -> assertNotNull(fallbackSession, "the session branch must be tagged with the label 'session'"),
                    () -> assertNotNull(otherBearer, "each route carries its own series"));
            assertAll("each call increments exactly its own series",
                    () -> assertEquals(2.0, fallbackBearer.count()),
                    () -> assertEquals(1.0, fallbackSession.count()),
                    () -> assertEquals(1.0, otherBearer.count()),
                    () -> assertEquals(3, registry.find("sheriff_auth_branch_total").counters().size()));
        }

        @Test
        @DisplayName("recordAuthBranch tags exactly route and branch, with branch values bearer / session only")
        void recordAuthBranchTagsExactlyRouteAndBranch() {
            metrics.recordAuthBranch("fallback", AuthBranch.BEARER);
            metrics.recordAuthBranch("fallback", AuthBranch.SESSION);

            var counters = registry.find("sheriff_auth_branch_total").counters();
            assertEquals(2, counters.size());
            for (var counter : counters) {
                var tagKeys = counter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
                assertEquals(Set.of("route", "branch"), tagKeys,
                        "sheriff_auth_branch_total must carry exactly the route and branch tags");
            }
            var branchValues = counters.stream().map(counter -> counter.getId().getTag("branch"))
                    .collect(Collectors.toSet());
            assertEquals(Set.of("bearer", "session"), branchValues,
                    "the branch label is bounded to the two session_fallback branches");
        }

        @Test
        @DisplayName("recordAuthBranch rejects AuthBranch.NONE and registers no series for it")
        void recordAuthBranchRejectsNone() {
            assertThrows(IllegalArgumentException.class, () -> metrics.recordAuthBranch("fallback", AuthBranch.NONE));

            assertTrue(registry.find("sheriff_auth_branch_total").counters().isEmpty(),
                    "a rejected NONE must not leave a 'none' series behind");
        }

        @Test
        @DisplayName("recordAuthBranch rejects a null route or branch fail-closed")
        void recordAuthBranchRejectsNull() {
            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> metrics.recordAuthBranch(null, AuthBranch.BEARER)),
                    () -> assertThrows(NullPointerException.class,
                            () -> metrics.recordAuthBranch("fallback", null)));
            assertTrue(registry.find("sheriff_auth_branch_total").counters().isEmpty());
        }

        @Test
        @DisplayName("recordRequest counts under sheriff_requests_total{route,method,status_family}")
        void recordRequestCountsPathsView() {
            metrics.recordRequest("api", HttpMethod.GET, "2xx");
            metrics.recordRequest("api", HttpMethod.GET, "2xx");

            var counter = registry.find("sheriff_requests_total")
                    .tags("route", "api", "method", "GET", "status_family", "2xx").counter();
            assertNotNull(counter, "sheriff_requests_total must be registered with the labelled tags");
            assertEquals(2.0, counter.count());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(HttpMethod.class)
        @DisplayName("recordRequest labels every parsed method with its enum name")
        void recordRequestLabelsParsedMethodWithEnumName(HttpMethod method) {
            metrics.recordRequest("api", method, "2xx");

            var counters = registry.find("sheriff_requests_total").counters();
            assertEquals(1, counters.size());
            assertEquals(method.name(), counters.iterator().next().getId().getTag("method"));
        }

        @Test
        @DisplayName("recordRequest labels a method the gateway does not parse with the one OTHER placeholder")
        void recordRequestLabelsUnparsedMethodWithPlaceholder() {
            metrics.recordRequest(SheriffMetrics.NO_ROUTE, null, "4xx");
            metrics.recordRequest(SheriffMetrics.NO_ROUTE, null, "4xx");

            var counter = registry.find("sheriff_requests_total")
                    .tags("route", SheriffMetrics.NO_ROUTE, "method", "OTHER", "status_family", "4xx").counter();
            assertAll("the placeholder series",
                    () -> assertEquals("OTHER", SheriffMetrics.METHOD_OTHER),
                    () -> assertTrue(Arrays.stream(HttpMethod.values())
                                    .noneMatch(method -> SheriffMetrics.METHOD_OTHER.equals(method.name())),
                            "the placeholder must not collide with a parsed method's label"),
                    () -> assertNotNull(counter, "an unparsed method is counted under the placeholder"),
                    () -> assertEquals(2.0, counter.count(), "every unparsed method moves the one series"),
                    () -> assertEquals(1, registry.find("sheriff_requests_total").counters().size()));
        }

        @Test
        @DisplayName("recordRequest bounds the method label to the HttpMethod names plus the placeholder")
        void recordRequestBoundsMethodLabelCardinality() {
            // Arrange — every value the parameter admits, each recorded more than once.
            List<@Nullable HttpMethod> admitted = new ArrayList<>(Arrays.asList(HttpMethod.values()));
            admitted.add(null);

            // Act
            for (int round = 0; round < 3; round++) {
                admitted.forEach(method -> metrics.recordRequest("api", method, "2xx"));
            }

            // Assert
            var counters = registry.find("sheriff_requests_total").counters();
            Set<String> methodLabels = counters.stream().map(counter -> counter.getId().getTag("method"))
                    .collect(Collectors.toSet());
            Set<String> expected = Stream.concat(Arrays.stream(HttpMethod.values()).map(HttpMethod::name),
                    Stream.of(SheriffMetrics.METHOD_OTHER)).collect(Collectors.toSet());
            assertAll("bounded method label",
                    () -> assertEquals(expected, methodLabels),
                    () -> assertEquals(HttpMethod.values().length + 1, counters.size()),
                    () -> counters.forEach(counter -> assertEquals(Set.of("route", "method", "status_family"),
                            counter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet()),
                            "sheriff_requests_total must carry exactly the route, method and status_family tags")));
        }

        @Test
        @DisplayName("recordRequestDuration records under sheriff_request_duration_seconds{route}")
        void recordRequestDurationRecordsTimer() {
            metrics.recordRequestDuration("api", Duration.ofMillis(5));

            var timer = registry.find("sheriff_request_duration_seconds").tags("route", "api").timer();
            assertNotNull(timer, "sheriff_request_duration_seconds must be registered per route");
            assertEquals(1L, timer.count());
        }

        @Test
        @DisplayName("recordError counts under sheriff_errors_total{route,category,event}")
        void recordErrorCountsErrorsView() {
            metrics.recordError("api", EventType.UPSTREAM_ERROR);
            metrics.recordError("api", EventType.UPSTREAM_ERROR);

            var counter = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "upstream", "event", "UPSTREAM_ERROR").counter();
            assertNotNull(counter, "sheriff_errors_total must be keyed by route, category slug and event name");
            assertEquals(2.0, counter.count());
            assertEquals(1, registry.find("sheriff_errors_total").counters().size(),
                    "repeated calls for one event move one series");
        }

        @Test
        @DisplayName("recordError separates two routing events on one route by the event label alone")
        void recordErrorSeparatesRoutingEventsByEventLabel() {
            metrics.recordError("api", EventType.NO_ROUTE_MATCHED);
            metrics.recordError("api", EventType.PASSTHROUGH_HOST_SMUGGLED);

            var noRoute = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "routing", "event", "NO_ROUTE_MATCHED").counter();
            var smuggled = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "routing", "event", "PASSTHROUGH_HOST_SMUGGLED").counter();
            assertAll("two routing series",
                    () -> assertNotNull(noRoute, "the unrouted rejection has its own routing series"),
                    () -> assertNotNull(smuggled, "the smuggled-host rejection has its own routing series"));
            assertAll("each call moved only its own series",
                    () -> assertEquals(1.0, noRoute.count()),
                    () -> assertEquals(1.0, smuggled.count()),
                    () -> assertEquals(2, registry.find("sheriff_errors_total").counters().size()));
        }

        @Test
        @DisplayName("recordError keeps the routing series distinct from an input-validation one")
        void recordErrorKeepsRoutingDistinctFromInputValidation() {
            metrics.recordError("api", EventType.NO_ROUTE_MATCHED);
            metrics.recordError("api", EventType.SECURITY_FILTER_VIOLATION);

            var routing = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "routing").counters();
            var inputValidation = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "input-validation").counters();
            assertAll("one series per category",
                    () -> assertEquals(1, routing.size()),
                    () -> assertEquals(1, inputValidation.size()),
                    () -> assertEquals("NO_ROUTE_MATCHED", routing.iterator().next().getId().getTag("event")),
                    () -> assertEquals("SECURITY_FILTER_VIOLATION",
                            inputValidation.iterator().next().getId().getTag("event")));
        }

        @Test
        @DisplayName("recordError tags exactly route, category and event")
        void recordErrorTagsExactlyRouteCategoryAndEvent() {
            metrics.recordError("api", EventType.NO_ROUTE_MATCHED);
            metrics.recordError("api", EventType.UPSTREAM_TIMEOUT);

            var counters = registry.find("sheriff_errors_total").counters();
            assertEquals(2, counters.size());
            for (var counter : counters) {
                var tagKeys = counter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
                assertEquals(Set.of("route", "category", "event"), tagKeys,
                        "sheriff_errors_total must carry exactly the route, category and event tags");
            }
        }

        @Test
        @DisplayName("recordError counts an aborted inbound body as its own input-validation series")
        void recordErrorCountsAbortedInboundBodyUnderItsOwnEvent() {
            metrics.recordError("upload", EventType.INBOUND_BODY_ABORTED);

            var aborted = registry.find("sheriff_errors_total")
                    .tags("route", "upload", "category", "input-validation", "event", "INBOUND_BODY_ABORTED")
                    .counter();
            var counters = registry.find("sheriff_errors_total").counters();
            assertAll("the aborted-inbound-body series",
                    () -> assertNotNull(aborted, "the series is keyed by the literal enum name and category slug"),
                    () -> assertEquals(1.0, aborted.count()),
                    () -> assertEquals(1, counters.size(),
                            "an aborted inbound body must not also move the security-filter series"),
                    () -> assertEquals(Set.of("route", "category", "event"),
                            counters.iterator().next().getId().getTags().stream().map(Tag::getKey)
                                    .collect(Collectors.toSet())));
        }

        /** Every {@link EventType} that carries a category — the population {@code recordError} accepts. */
        static Stream<EventType> categorisedEventTypes() {
            List<EventType> categorised = Arrays.stream(EventType.values())
                    .filter(eventType -> eventType.category() != null)
                    .toList();
            if (categorised.isEmpty()) {
                throw new IllegalStateException("no EventType carries a category — the parameterisation is vacuous");
            }
            return categorised.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("categorisedEventTypes")
        @DisplayName("recordError sets the event label to the enum name and the category label to its slug")
        void recordErrorLabelsEventWithEnumName(EventType eventType) {
            metrics.recordError("api", eventType);

            var counters = registry.find("sheriff_errors_total").counters();
            assertEquals(1, counters.size());
            var id = counters.iterator().next().getId();
            EventCategory category = Objects.requireNonNull(eventType.category());
            assertAll("labels of " + eventType,
                    () -> assertEquals(eventType.name(), id.getTag("event")),
                    () -> assertEquals(category.slug(), id.getTag("category")),
                    () -> assertEquals("api", id.getTag("route")));
        }

        @Test
        @DisplayName("recordError rejects a null route or event type fail-closed")
        void recordErrorRejectsNull() {
            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> metrics.recordError(null, EventType.NO_ROUTE_MATCHED)),
                    () -> assertThrows(NullPointerException.class,
                            () -> metrics.recordError("api", null)));
            assertTrue(registry.find("sheriff_errors_total").counters().isEmpty(),
                    "a rejected null must not leave a series behind");
        }

        @Test
        @DisplayName("recordError rejects an event type without a category and registers no series for it")
        void recordErrorRejectsUncategorisedEventType() {
            assertThrows(IllegalArgumentException.class,
                    () -> metrics.recordError("api", EventType.REQUEST_FORWARDED));

            assertTrue(registry.find("sheriff_errors_total").counters().isEmpty(),
                    "a rejected uncategorised event must not leave a series behind");
        }

        @Test
        @DisplayName("recordSessionEvent counts under sheriff_session_events_total{event} keyed by the EventType name")
        void recordSessionEventCountsSessionLifecycle() {
            metrics.recordSessionEvent(EventType.SESSION_CREATED);
            metrics.recordSessionEvent(EventType.SESSION_CREATED);
            metrics.recordSessionEvent(EventType.SESSION_DESTROYED);
            metrics.recordSessionEvent(EventType.BACKCHANNEL_LOGOUT);

            var created = registry.find("sheriff_session_events_total").tags("event", "SESSION_CREATED").counter();
            var destroyed = registry.find("sheriff_session_events_total").tags("event", "SESSION_DESTROYED").counter();
            var backchannel = registry.find("sheriff_session_events_total").tags("event", "BACKCHANNEL_LOGOUT").counter();
            assertNotNull(created, "sheriff_session_events_total must be keyed by the EventType name");
            assertEquals(2.0, created.count(), "each recordSessionEvent call increments the event's series");
            assertEquals(1.0, destroyed.count());
            assertEquals(1.0, backchannel.count());
        }

        @Test
        @DisplayName("recordSessionEvent rejects a null event type fail-closed")
        void recordSessionEventRejectsNull() {
            assertThrows(NullPointerException.class, () -> metrics.recordSessionEvent(null));
        }

        @Test
        @DisplayName("bindSecurityEventCounter exposes the shared counter under sheriff_security_events_total{failure_type} and moves with it")
        void bindSecurityEventCounterExposesAndMovesWithCounter() {
            SecurityEventCounter securityEventCounter = new SecurityEventCounter();
            metrics.bindSecurityEventCounter(securityEventCounter);

            // The meter is registered up front for the fixed enum (bounded cardinality), before any event.
            var functionCounter = registry.find("sheriff_security_events_total")
                    .tags("failure_type", "PATH_TRAVERSAL_DETECTED").functionCounter();
            assertNotNull(functionCounter, "sheriff_security_events_total must be bound per UrlSecurityFailureType");
            assertEquals(0.0, functionCounter.count(), "the meter starts at zero before any violation");

            // A security-relevant rejection increments the shared counter; the bound meter tracks it live.
            securityEventCounter.increment(UrlSecurityFailureType.PATH_TRAVERSAL_DETECTED);
            securityEventCounter.increment(UrlSecurityFailureType.PATH_TRAVERSAL_DETECTED);

            assertEquals(2.0, functionCounter.count(),
                    "the function counter must reflect the live SecurityEventCounter count");
        }

        @Test
        @DisplayName("bindSecurityEventCounter fixes failure_type cardinality at the UrlSecurityFailureType enum")
        void bindSecurityEventCounterBoundsCardinalityToEnum() {
            SecurityEventCounter securityEventCounter = new SecurityEventCounter();
            metrics.bindSecurityEventCounter(securityEventCounter);

            int boundSeries = registry.find("sheriff_security_events_total").functionCounters().size();
            assertEquals(UrlSecurityFailureType.values().length, boundSeries,
                    "one bounded series per UrlSecurityFailureType, never operator-controlled input");
        }

        @Test
        @DisplayName("recordUpstreamDuration records under sheriff_upstream_duration_seconds{route}")
        void recordUpstreamDurationRecordsTimer() {
            metrics.recordUpstreamDuration("api", Duration.ofMillis(10));

            var timer = registry.find("sheriff_upstream_duration_seconds").tags("route", "api").timer();
            assertNotNull(timer, "sheriff_upstream_duration_seconds must be registered per route");
            assertEquals(1L, timer.count());
        }

        @Test
        @DisplayName("statusFamily buckets each status into its bounded leading-digit family")
        void statusFamilyBucketsByLeadingDigit() {
            // The edge feeds status_family through this classifier so the label cardinality stays
            // fixed at the five families regardless of the concrete status code.
            assertEquals("1xx", SheriffMetrics.statusFamily(100));
            assertEquals("2xx", SheriffMetrics.statusFamily(200));
            assertEquals("2xx", SheriffMetrics.statusFamily(204));
            assertEquals("3xx", SheriffMetrics.statusFamily(304));
            assertEquals("4xx", SheriffMetrics.statusFamily(404));
            assertEquals("5xx", SheriffMetrics.statusFamily(500));
            assertEquals("5xx", SheriffMetrics.statusFamily(503));
        }

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 99, 600, 999, 1000, Integer.MAX_VALUE})
        @DisplayName("status_family is clamped to its fixed set: a status outside 100..599 maps to the one placeholder")
        void statusFamilyIsClampedToItsFixedSet(int statusCode) {
            assertAll("status " + statusCode,
                    () -> assertEquals("other", SheriffMetrics.STATUS_FAMILY_OTHER),
                    () -> assertEquals(SheriffMetrics.STATUS_FAMILY_OTHER, SheriffMetrics.statusFamily(statusCode)));
        }

        @ParameterizedTest(name = "status {0} -> {1}")
        @CsvSource({"100, 1xx", "199, 1xx", "200, 2xx", "299, 2xx", "300, 3xx", "399, 3xx", "400, 4xx",
                "499, 4xx", "500, 5xx", "599, 5xx"})
        @DisplayName("status_family keeps both edges of every family inside 100..599")
        void statusFamilyKeepsTheInRangeEdges(int statusCode, String expectedFamily) {
            assertEquals(expectedFamily, SheriffMetrics.statusFamily(statusCode));
        }

        @Test
        @DisplayName("status_family draws from exactly the five families plus the placeholder")
        void statusFamilyValueSetIsFixed() {
            Set<String> families = IntStream.rangeClosed(-1_000, 10_000)
                    .mapToObj(SheriffMetrics::statusFamily)
                    .collect(Collectors.toSet());

            assertEquals(Set.of("1xx", "2xx", "3xx", "4xx", "5xx", SheriffMetrics.STATUS_FAMILY_OTHER), families);
        }
    }

    @Nested
    @DisplayName("GatewayReadinessCheck reflects config and JWKS status, and reports oidc_discovery without letting it gate")
    class Readiness {

        private static final String PRIMARY_ISSUER = "corp-main";
        private static final String SECONDARY_ISSUER = "corp-partner";
        /** The key-set view of a {@code token_validation} block with no issuers: vacuously all loaded. */
        private static final IssuerKeySetStatus NO_ISSUERS = IssuerKeySetStatus.of(Map.of());

        @Test
        @DisplayName("UP with jwks=not-applicable when no token_validation is configured")
        void upWhenNoTokenValidation() {
            GatewayConfig config = configWith(null, null, null);
            GatewayReadinessCheck check = notApplicableCheck(config);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("loaded", data.get("config"));
            assertEquals("not-applicable", data.get("jwks"));
            assertNull(data.get("oidc"), "a probe without an active BFF runtime carries no oidc datum");
            assertNull(data.get("oidc_discovery"),
                    "a probe without an active BFF runtime carries no oidc_discovery datum");
            assertNull(data.get("issuer_reachability"), "the retired issuer_reachability datum is never emitted");
        }

        @Test
        @DisplayName("UP with jwks=ready when the gateway validator resolves")
        void upWhenValidatorResolves() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), null);
            GatewayReadinessCheck check = checkWith(config, NO_ISSUERS);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("ready", data.get("jwks"));
            assertEquals(0L, data.get("issuers"));
            assertNull(data.get("oidc"), "a probe without an active BFF runtime carries no oidc datum");
        }

        @Test
        @DisplayName("DOWN with jwks=unavailable when the validator fails to resolve")
        void downWhenValidatorFails() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), null);
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID, "no usable jwks source");
            GatewayReadinessCheck check = failingCheck(config, failure);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("unavailable", data.get("jwks"));
            // Positive control: the payload reports a FIXED token. It previously echoed the raw
            // exception message, which is the disclosure this datum was redacted to close.
            assertEquals("validation-unavailable", data.get("error"));
        }

        @Test
        @DisplayName("DOWN payload discloses no fragment of the underlying failure message")
        void downPayloadNeverEchoesTheFailureCause() {
            // Arrange — a failure message shaped like the ones this redaction exists to contain: an
            // internal issuer URL, a private hostname, and a filesystem path to trust material. The
            // readiness payload is served on the management port, which has exactly one port and may
            // legitimately be plain HTTP (ADR-0025), so none of this may reach the wire.
            GatewayConfig config = configWith(null, new TokenValidationConfig(List.of()), null);
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID,
                    "no usable jwks source at https://idp.internal.example.com/realms/main"
                            + "/protocol/openid-connect/certs using truststore /etc/sheriff/trust/corporate-ca.pem");
            GatewayReadinessCheck check = failingCheck(config, failure);

            // Act
            HealthCheckResponse response = check.call();

            // Assert — negative control over the WHOLE payload, not just the error datum: a future
            // change that moved the cause onto any other datum would still be a disclosure.
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            for (Map.Entry<String, Object> datum : data.entrySet()) {
                String rendered = String.valueOf(datum.getValue());
                for (String disclosive : List.of(failure.getMessage(), "idp.internal.example.com",
                        "openid-connect", "/etc/sheriff/trust", "corporate-ca.pem")) {
                    assertFalse(rendered.contains(disclosive),
                            () -> "readiness datum '%s' disclosed '%s' from the failure cause: %s"
                                    .formatted(datum.getKey(), disclosive, rendered));
                }
            }
        }

        @Test
        @DisplayName("server mode UP reports oidc=server and oidc_discovery=not-attempted before any engine use")
        void serverModeUpReportsDiscoveryNotAttempted() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = checkWith(config, NO_ISSUERS, discovery(State.NOT_ATTEMPTED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("ready", data.get("jwks"));
            assertEquals("not-attempted", data.get("oidc_discovery"),
                    "a fresh instance has not resolved the provider metadata, and the probe does not make it");
            assertNull(data.get("issuer_reachability"), "the retired issuer_reachability datum is never emitted");
        }

        @Test
        @DisplayName("server mode construction-failure DOWN still reports oidc_discovery beside the jwks verdict")
        void serverModeDownStillReportsDiscovery() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID, "issuer JWKS unreachable");
            GatewayReadinessCheck check = failingCheck(config, failure, discovery(State.RESOLVED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("unavailable", data.get("jwks"));
            assertEquals("resolved", data.get("oidc_discovery"),
                    "resolved metadata does not lift a validator construction failure to UP");
        }

        @Test
        @DisplayName("server mode without token_validation reports oidc_discovery=failed but stays UP")
        void serverModeWithoutValidationStaysUpWhileDiscoveryFailed() {
            GatewayConfig config = configWith(null, null, bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = notApplicableCheck(config, discovery(State.FAILED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                    "a provider-metadata failure stops the identity-provider legs only, not the instance");
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("not-applicable", data.get("jwks"));
            assertEquals("failed", data.get("oidc_discovery"));
        }

        @Test
        @DisplayName("cookie mode reports oidc=cookie and its oidc_discovery datum")
        void cookieModeReportsItsModeAndDiscovery() {
            GatewayConfig config = configWith(null, twoIssuers(), bffMode(OidcConfig.Session.MODE_COOKIE));
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.LOADED),
                    discovery(State.FAILED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("cookie", data.get("oidc"), "cookie mode is a BFF mode and reports its own label");
            assertEquals("failed", data.get("oidc_discovery"));
            assertNothingDisclosed(data);
        }

        @Test
        @DisplayName("a session mode whose runtime is inactive carries neither the oidc nor the oidc_discovery datum")
        void inactiveRuntimeCarriesNoDiscoveryDatum() {
            // The producer hands out the inert view when it builds no runtime (no redirect_uri, say), so the
            // probe reads applicability off the view rather than off the bare presence of a mode.
            GatewayConfig config = configWith(null, null, bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = notApplicableCheck(config, DiscoveryStatus.inert());

            Map<String, Object> data = check.call().getData().orElseThrow();

            assertNull(data.get("oidc"));
            assertNull(data.get("oidc_discovery"));
        }

        @Test
        @DisplayName("the probe only reads the discovery view and never triggers the fetch")
        void probeNeverTriggersTheFetch() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            AtomicInteger fetches = new AtomicInteger();
            status.recording(() -> {
                fetches.incrementAndGet();
                return "provider-metadata";
            });
            GatewayConfig config = configWith(null, null, bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = notApplicableCheck(config, status);

            for (int poll = 0; poll < 5; poll++) {
                check.call();
            }

            assertEquals(0, fetches.get(), "polling readiness sends no discovery request");
            assertEquals("not-attempted", check.call().getData().orElseThrow().get("oidc_discovery"));
        }

        @Test
        @DisplayName("config_version is surfaced when metadata carries one")
        void configVersionSurfaced() {
            GatewayConfig config = configWith(new Metadata("2026-07-19"),
                    null, null);
            GatewayReadinessCheck check = notApplicableCheck(config);

            HealthCheckResponse response = check.call();

            assertEquals("2026-07-19", response.getData().orElseThrow().get("config_version"));
        }

        @Test
        @DisplayName("UP with jwks=ready and issuers_loaded=issuers when every configured issuer has a key set")
        void upWhenEveryIssuerHasKeySet() {
            GatewayConfig config = configWith(null, twoIssuers(), null);
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.LOADED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("ready", data.get("jwks"));
            assertEquals(2L, data.get("issuers"));
            assertEquals(2L, data.get("issuers_loaded"));
        }

        @Test
        @DisplayName("DOWN with jwks=loading and bounded counts while one issuer has no key set yet")
        void downWhileOneIssuerIsStillLoading() {
            GatewayConfig config = configWith(null, twoIssuers(), null);
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.NOT_LOADED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("loading", data.get("jwks"));
            assertEquals(2L, data.get("issuers"));
            assertEquals(1L, data.get("issuers_loaded"));
            assertNull(data.get("error"), "a live key-set DOWN is not a validator construction failure");
            assertNull(data.get("oidc_discovery"),
                    "a probe without an active BFF runtime carries no oidc_discovery datum");
            assertNothingDisclosed(data);
        }

        @Test
        @DisplayName("DOWN with jwks=unavailable when one issuer's last load attempt failed")
        void downWhenOneIssuerFailedToLoad() {
            GatewayConfig config = configWith(null, twoIssuers(), null);
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.FAILED, KeySetState.LOADED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("unavailable", data.get("jwks"));
            assertEquals(2L, data.get("issuers"));
            assertEquals(1L, data.get("issuers_loaded"));
            assertNothingDisclosed(data);
        }

        @Test
        @DisplayName("a failed issuer outranks a still-loading one: jwks=unavailable, issuers_loaded=0")
        void failedOutranksLoading() {
            GatewayConfig config = configWith(null, twoIssuers(), null);
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.NOT_LOADED, KeySetState.FAILED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("unavailable", data.get("jwks"));
            assertEquals(0L, data.get("issuers_loaded"));
        }

        @Test
        @DisplayName("server mode stays DOWN while an issuer has no key set, even with oidc_discovery=resolved")
        void serverModeKeySetDownIsNotLiftedByResolvedDiscovery() {
            GatewayConfig config = configWith(null, twoIssuers(), bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.NOT_LOADED),
                    discovery(State.RESOLVED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("loading", data.get("jwks"));
            assertEquals("resolved", data.get("oidc_discovery"),
                    "the datum is reported beside the key-set verdict, never in its place");
            assertNothingDisclosed(data);
        }

        @Test
        @DisplayName("server mode is UP once every issuer has a key set, while oidc_discovery reads failed")
        void serverModeKeySetUpStaysUpWhileDiscoveryFailed() {
            GatewayConfig config = configWith(null, twoIssuers(), bffMode(OidcConfig.Session.MODE_SERVER));
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.LOADED),
                    discovery(State.FAILED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                    "bearer-only and public routes keep working through an identity-provider outage");
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("ready", data.get("jwks"));
            assertEquals("failed", data.get("oidc_discovery"));
            assertNothingDisclosed(data);
        }

        @ParameterizedTest(name = "mode {0}, discovery {1}")
        @CsvSource({"server, NOT_ATTEMPTED", "server, RESOLVED", "server, FAILED",
                "cookie, NOT_ATTEMPTED", "cookie, RESOLVED", "cookie, FAILED"})
        @DisplayName("oidc=<mode> and oidc_discovery are reported for both modes, and the verdict ignores the datum")
        void discoveryNeverChangesTheVerdict(String mode, State discoveryState) {
            // Arrange — every issuer has a key set, so the key-set verdict alone is UP
            GatewayConfig config = configWith(null, twoIssuers(), bffMode(mode));
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.LOADED),
                    discovery(discoveryState));

            // Act
            HealthCheckResponse response = check.call();

            // Assert
            Map<String, Object> data = response.getData().orElseThrow();
            assertAll("mode " + mode + " with discovery " + discoveryState,
                    () -> assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                            "the provider-metadata datum never flips readiness"),
                    () -> assertEquals(mode, data.get("oidc")),
                    () -> assertEquals(discoveryState.token(), data.get("oidc_discovery")),
                    () -> assertNull(data.get("issuer_reachability")));
        }

        @Test
        @DisplayName("the key-set view is resolved only after the validator, so a validator failure never reaches it")
        void validatorFailureShortCircuitsKeySetResolution() {
            // Arrange — the key-set instance throws an exception the probe does NOT catch, so resolving
            // it would fail this test rather than render a response.
            GatewayConfig config = configWith(null, twoIssuers(), null);
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID, "no usable jwks source");

            // Act
            HealthCheckResponse response = failingCheck(config, failure).call();

            // Assert
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("validation-unavailable", data.get("error"));
            assertNull(data.get("issuers_loaded"), "no key-set count exists when the validator never assembled");
        }

        /**
         * Negative control over the whole payload: no datum may carry an issuer's name, identifier,
         * JWKS URL or host, whatever the state it reports.
         */
        private void assertNothingDisclosed(Map<String, Object> data) {
            for (Map.Entry<String, Object> datum : data.entrySet()) {
                String rendered = String.valueOf(datum.getValue());
                for (String disclosive : List.of(PRIMARY_ISSUER, SECONDARY_ISSUER, "idp.internal.example.com",
                        "realms", "openid-connect", "https://")) {
                    assertFalse(rendered.contains(disclosive),
                            () -> "readiness datum '%s' disclosed '%s': %s"
                                    .formatted(datum.getKey(), disclosive, rendered));
                }
            }
        }

        private TokenValidationConfig twoIssuers() {
            return new TokenValidationConfig(
                    List.of(issuer(PRIMARY_ISSUER, "main"), issuer(SECONDARY_ISSUER, "partner")));
        }

        private IssuerConfig issuer(String name, String realm) {
            String base = "https://idp.internal.example.com/realms/" + realm;
            IssuerConfig.Jwks jwks = new IssuerConfig.Jwks("http",
                    base + "/protocol/openid-connect/certs", null, List.of(), null, null, null, null);
            return new IssuerConfig(name, base, null, jwks);
        }

        /** @return the key-set view of {@link #twoIssuers()} with the given states, in order */
        private IssuerKeySetStatus keySets(KeySetState primary, KeySetState secondary) {
            Map<String, KeySetState> states = new LinkedHashMap<>();
            states.put(PRIMARY_ISSUER, primary);
            states.put(SECONDARY_ISSUER, secondary);
            return IssuerKeySetStatus.of(states);
        }

        /** A probe without an active BFF runtime whose validator resolves and whose key-set view is {@code keySets}. */
        private GatewayReadinessCheck checkWith(GatewayConfig config, IssuerKeySetStatus keySets) {
            return checkWith(config, keySets, DiscoveryStatus.inert());
        }

        /** A probe whose validator resolves, whose key-set view is {@code keySets} and whose discovery view is {@code discovery}. */
        private GatewayReadinessCheck checkWith(GatewayConfig config, IssuerKeySetStatus keySets,
                DiscoveryStatus discovery) {
            return new GatewayReadinessCheck(config, FakeInstance.resolving(null), FakeInstance.resolving(keySets),
                    discovery);
        }

        /**
         * A probe without an active BFF runtime whose validator resolution throws {@code failure}; the
         * key-set view must stay unresolved.
         */
        private GatewayReadinessCheck failingCheck(GatewayConfig config, RuntimeException failure) {
            return failingCheck(config, failure, DiscoveryStatus.inert());
        }

        /** A probe whose validator resolution throws {@code failure}; the key-set view must stay unresolved. */
        private GatewayReadinessCheck failingCheck(GatewayConfig config, RuntimeException failure,
                DiscoveryStatus discovery) {
            return new GatewayReadinessCheck(config, FakeInstance.failing(failure), FakeInstance.mustNotResolve(),
                    discovery);
        }

        /**
         * A probe for a gateway without {@code token_validation} and without an active BFF runtime:
         * neither the validator nor the key-set view may be resolved on that leg.
         */
        private GatewayReadinessCheck notApplicableCheck(GatewayConfig config) {
            return notApplicableCheck(config, DiscoveryStatus.inert());
        }

        /**
         * A probe for a gateway without {@code token_validation} whose discovery view is
         * {@code discovery}: neither the validator nor the key-set view may be resolved on that leg.
         */
        private GatewayReadinessCheck notApplicableCheck(GatewayConfig config, DiscoveryStatus discovery) {
            return new GatewayReadinessCheck(config, FakeInstance.mustNotResolve(), FakeInstance.mustNotResolve(),
                    discovery);
        }

        /**
         * @return the tracking view of an active BFF runtime, driven through real attempts of a recorded
         *         fetch into {@code state}
         */
        private DiscoveryStatus discovery(State state) {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            switch (state) {
                case NOT_ATTEMPTED -> {
                    // no attempt
                }
                case RESOLVED -> status.recording(() -> "provider-metadata").get();
                case FAILED -> assertThrows(IllegalStateException.class, () -> status.recording(() -> {
                    throw new IllegalStateException("discovery refused");
                }).get());
            }
            assertEquals(state, status.state(), "precondition: the view reached the state under test");
            return status;
        }

        private OidcConfig bffMode(String mode) {
            OidcConfig.Session session = OidcConfig.Session.builder().mode(mode).build();
            return OidcConfig.builder().session(session).build();
        }

        private GatewayConfig configWith(@Nullable Metadata metadata,
                @Nullable TokenValidationConfig tokenValidation, @Nullable OidcConfig oidc) {
            return GatewayConfig.builder()
                    .version(1)
                    .metadata(metadata)
                    .tokenValidation(tokenValidation)
                    .oidc(oidc)
                    .build();
        }
    }

    /**
     * Minimal {@link Instance} test double: {@link #get()} either returns the supplied value or throws
     * the supplied failure, exercising the readiness UP / DOWN branches without a CDI container. Only
     * {@code get()} is consumed by {@link GatewayReadinessCheck}; the remaining contract methods are
     * unsupported.
     *
     * @param <T> the resolved bean type
     */
    private static final class FakeInstance<T> implements Instance<T> {

        private final @Nullable T value;
        private final @Nullable RuntimeException failure;

        private FakeInstance(@Nullable T value, @Nullable RuntimeException failure) {
            this.value = value;
            this.failure = failure;
        }

        /** @return an instance whose resolution succeeds with {@code value} */
        static <T> FakeInstance<T> resolving(@Nullable T value) {
            return new FakeInstance<>(value, null);
        }

        /** @return an instance whose resolution throws {@code failure} */
        static <T> FakeInstance<T> failing(RuntimeException failure) {
            return new FakeInstance<>(null, failure);
        }

        /**
         * @return an instance the probe must never resolve: its failure is neither a
         *         {@link GatewayException} nor a {@code CreationException}, so the probe does not catch
         *         it and a resolution fails the calling test outright
         */
        static <T> FakeInstance<T> mustNotResolve() {
            return new FakeInstance<>(null, new IllegalStateException("this instance must not be resolved"));
        }

        @Override
        public T get() {
            if (failure != null) {
                throw failure;
            }
            return value;
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
