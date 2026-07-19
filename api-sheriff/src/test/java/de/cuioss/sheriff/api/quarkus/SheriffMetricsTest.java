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
package de.cuioss.sheriff.api.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cuioss.http.security.core.UrlSecurityFailureType;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.Metadata;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.events.EventCategory;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.validation.TokenValidator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the D4/D5 metrics-and-readiness surface: {@link SheriffMetrics} registers the meter
 * names named in {@code architecture.adoc} § Metrics, and {@link GatewayReadinessCheck} reflects
 * configuration and JWKS status.
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
        }

        @Test
        @DisplayName("recordRequest counts under sheriff_requests_total{route,method,status_family}")
        void recordRequestCountsPathsView() {
            metrics.recordRequest("api", "GET", "2xx");
            metrics.recordRequest("api", "GET", "2xx");

            var counter = registry.find("sheriff_requests_total")
                    .tags("route", "api", "method", "GET", "status_family", "2xx").counter();
            assertNotNull(counter, "sheriff_requests_total must be registered with the labelled tags");
            assertEquals(2.0, counter.count());
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
        @DisplayName("recordError counts under sheriff_errors_total{route,category} keyed by category slug")
        void recordErrorCountsErrorsView() {
            metrics.recordError("api", EventCategory.UPSTREAM);

            var counter = registry.find("sheriff_errors_total")
                    .tags("route", "api", "category", "upstream").counter();
            assertNotNull(counter, "sheriff_errors_total must be keyed by the category slug");
            assertEquals(1.0, counter.count());
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
    }

    @Nested
    @DisplayName("GatewayReadinessCheck reflects config and JWKS status")
    class Readiness {

        @Test
        @DisplayName("UP with jwks=not-applicable when no token_validation is configured")
        void upWhenNoTokenValidation() {
            GatewayConfig config = configWith(Optional.empty(), Optional.empty());
            GatewayReadinessCheck check = new GatewayReadinessCheck(config, FakeValidatorInstance.resolving());

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("loaded", data.get("config"));
            assertEquals("not-applicable", data.get("jwks"));
        }

        @Test
        @DisplayName("UP with jwks=ready when the gateway validator resolves")
        void upWhenValidatorResolves() {
            GatewayConfig config = configWith(Optional.empty(),
                    Optional.of(new TokenValidationConfig(List.of())));
            GatewayReadinessCheck check = new GatewayReadinessCheck(config, FakeValidatorInstance.resolving());

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("ready", data.get("jwks"));
            assertEquals(0L, data.get("issuers"));
        }

        @Test
        @DisplayName("DOWN with jwks=unavailable when the validator fails to resolve")
        void downWhenValidatorFails() {
            GatewayConfig config = configWith(Optional.empty(),
                    Optional.of(new TokenValidationConfig(List.of())));
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID, "no usable jwks source");
            GatewayReadinessCheck check = new GatewayReadinessCheck(config, FakeValidatorInstance.failing(failure));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("unavailable", data.get("jwks"));
            assertTrue(String.valueOf(data.get("error")).contains("no usable jwks source"));
        }

        @Test
        @DisplayName("config_version is surfaced when metadata carries one")
        void configVersionSurfaced() {
            GatewayConfig config = configWith(Optional.of(new Metadata(Optional.of("2026-07-19"))),
                    Optional.empty());
            GatewayReadinessCheck check = new GatewayReadinessCheck(config, FakeValidatorInstance.resolving());

            HealthCheckResponse response = check.call();

            assertEquals("2026-07-19", response.getData().orElseThrow().get("config_version"));
        }

        private GatewayConfig configWith(Optional<Metadata> metadata, Optional<TokenValidationConfig> tokenValidation) {
            return new GatewayConfig(1, metadata, Optional.empty(), Optional.empty(), Optional.empty(),
                    null, null, Optional.empty(), Optional.empty(), tokenValidation, Optional.empty());
        }
    }

    /**
     * Minimal {@link Instance} test double: {@link #get()} either returns a resolved (unused)
     * validator or throws the supplied failure, exercising the readiness UP / DOWN branches without
     * a CDI container. Only {@code get()} is consumed by {@link GatewayReadinessCheck}; the remaining
     * contract methods are unsupported.
     */
    private static final class FakeValidatorInstance implements Instance<TokenValidator> {

        private final RuntimeException failure;

        private FakeValidatorInstance(RuntimeException failure) {
            this.failure = failure;
        }

        static FakeValidatorInstance resolving() {
            return new FakeValidatorInstance(null);
        }

        static FakeValidatorInstance failing(RuntimeException failure) {
            return new FakeValidatorInstance(failure);
        }

        @Override
        public TokenValidator get() {
            if (failure != null) {
                throw failure;
            }
            // The readiness check only asserts that resolution does not throw; the value is unused.
            return null;
        }

        @Override
        public Iterator<TokenValidator> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<TokenValidator> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends TokenValidator> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends TokenValidator> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
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
        public void destroy(TokenValidator instance) {
            // no-op
        }

        @Override
        public Handle<TokenValidator> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<TokenValidator>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
