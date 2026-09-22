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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.http.security.core.UrlSecurityFailureType;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.Metadata;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies the D4/D5 metrics-and-readiness surface: {@link SheriffMetrics} registers the meter
 * names named in {@code architecture.adoc} § Metrics (including the BFF session-lifecycle counter),
 * and {@link GatewayReadinessCheck} reflects configuration, JWKS status, and — for a BFF
 * {@code mode: server} deployment — an {@code issuer_reachability} datum that is always
 * {@code unverified}, because cached key-set state is not a reachability signal.
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
    }

    @Nested
    @DisplayName("GatewayReadinessCheck reflects config and JWKS status, and never claims issuer reachability")
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
            assertNull(data.get("oidc"), "a non-server-mode probe carries no oidc datum");
            assertNull(data.get("issuer_reachability"), "a non-server-mode probe carries no issuer_reachability datum");
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
            assertNull(data.get("oidc"), "a non-server-mode probe carries no oidc datum");
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
        @DisplayName("server mode UP reports oidc=server and issuer_reachability=unverified, never reachable")
        void serverModeUpReportsIssuerUnverified() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), serverMode());
            GatewayReadinessCheck check = checkWith(config, NO_ISSUERS);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("ready", data.get("jwks"));
            assertEquals("unverified", data.get("issuer_reachability"),
                    "a resolved key set is not evidence the issuer is reachable now");
        }

        @Test
        @DisplayName("server mode construction-failure DOWN reports issuer_reachability=unverified, never unreachable")
        void serverModeDownReportsIssuerUnverified() {
            GatewayConfig config = configWith(null,
                    new TokenValidationConfig(List.of()), serverMode());
            GatewayException failure = new GatewayException(EventType.CONFIG_INVALID, "issuer JWKS unreachable");
            GatewayReadinessCheck check = failingCheck(config, failure);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("unavailable", data.get("jwks"));
            assertEquals("unverified", data.get("issuer_reachability"),
                    "a validator that failed to build says nothing about the issuer's network reachability");
        }

        @Test
        @DisplayName("server mode without token_validation reports issuer_reachability=unverified but stays UP")
        void serverModeWithoutValidationReportsUnverified() {
            GatewayConfig config = configWith(null, null, serverMode());
            GatewayReadinessCheck check = notApplicableCheck(config);

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("not-applicable", data.get("jwks"));
            assertEquals("unverified", data.get("issuer_reachability"));
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
            assertNull(data.get("issuer_reachability"), "a non-server-mode probe carries no issuer_reachability datum");
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
        @DisplayName("server mode stays DOWN while an issuer has no key set, with issuer_reachability=unverified")
        void serverModeKeySetDownReportsIssuerUnverified() {
            GatewayConfig config = configWith(null, twoIssuers(), serverMode());
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.NOT_LOADED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("server", data.get("oidc"));
            assertEquals("loading", data.get("jwks"));
            assertEquals("unverified", data.get("issuer_reachability"),
                    "a missing key set is not evidence the issuer is unreachable");
            assertNothingDisclosed(data);
        }

        @Test
        @DisplayName("server mode is UP once every issuer has a key set, and still reports issuer_reachability=unverified")
        void serverModeKeySetUpReportsIssuerUnverified() {
            GatewayConfig config = configWith(null, twoIssuers(), serverMode());
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, KeySetState.LOADED));

            HealthCheckResponse response = check.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
            Map<String, Object> data = response.getData().orElseThrow();
            assertEquals("ready", data.get("jwks"));
            assertEquals("unverified", data.get("issuer_reachability"),
                    "a loaded key set survives later refresh failures, so it cannot prove reachability");
        }

        @ParameterizedTest(name = "second issuer {0}")
        @EnumSource(KeySetState.class)
        @DisplayName("server mode never reports a reachability verdict, whatever the key-set state")
        void serverModeNeverReportsReachabilityVerdict(KeySetState state) {
            // Arrange
            GatewayConfig config = configWith(null, twoIssuers(), serverMode());
            GatewayReadinessCheck check = checkWith(config, keySets(KeySetState.LOADED, state));

            // Act
            Map<String, Object> data = check.call().getData().orElseThrow();

            // Assert
            assertEquals("unverified", data.get("issuer_reachability"),
                    "cached key-set state must not be reported as a reachability verdict");
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
                    base + "/protocol/openid-connect/certs", null, List.of(), null);
            return new IssuerConfig(name, base, null, jwks);
        }

        /** @return the key-set view of {@link #twoIssuers()} with the given states, in order */
        private IssuerKeySetStatus keySets(KeySetState primary, KeySetState secondary) {
            Map<String, KeySetState> states = new LinkedHashMap<>();
            states.put(PRIMARY_ISSUER, primary);
            states.put(SECONDARY_ISSUER, secondary);
            return IssuerKeySetStatus.of(states);
        }

        /** A probe whose validator resolves and whose key-set view is {@code keySets}. */
        private GatewayReadinessCheck checkWith(GatewayConfig config, IssuerKeySetStatus keySets) {
            return new GatewayReadinessCheck(config, FakeInstance.resolving(null), FakeInstance.resolving(keySets));
        }

        /** A probe whose validator resolution throws {@code failure}; the key-set view must stay unresolved. */
        private GatewayReadinessCheck failingCheck(GatewayConfig config, RuntimeException failure) {
            return new GatewayReadinessCheck(config, FakeInstance.failing(failure), FakeInstance.mustNotResolve());
        }

        /**
         * A probe for a gateway without {@code token_validation}: neither the validator nor the key-set
         * view may be resolved on that leg.
         */
        private GatewayReadinessCheck notApplicableCheck(GatewayConfig config) {
            return new GatewayReadinessCheck(config, FakeInstance.mustNotResolve(), FakeInstance.mustNotResolve());
        }

        private OidcConfig serverMode() {
            OidcConfig.Session session = OidcConfig.Session.builder().mode("server").build();
            return OidcConfig.builder().session(session).build();
        }

        private GatewayConfig configWith(@Nullable Metadata metadata,
                @Nullable TokenValidationConfig tokenValidation, @Nullable OidcConfig oidc) {
            return new GatewayConfig(1, metadata, null, null, null,
                    null, null, null, null, null, null, tokenValidation, oidc,
                    null, null, null);
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
