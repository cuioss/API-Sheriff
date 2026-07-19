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

import java.time.Duration;
import java.util.Objects;


import de.cuioss.http.security.core.UrlSecurityFailureType;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.api.events.EventCategory;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The framework-bound metrics adapter (ADR-0005 seam) that surfaces the gateway's request,
 * error, security, and upstream signals as Micrometer meters on the management port, exposed
 * as Prometheus data via {@code quarkus-micrometer-registry-prometheus}.
 * <p>
 * The meter names are the fixed contract named in {@code architecture.adoc} § Metrics:
 * <ul>
 *   <li>{@value #REQUESTS_TOTAL}{@code {route,method,status_family}} — the "paths" view;</li>
 *   <li>{@value #REQUEST_DURATION_SECONDS}{@code {route}} — per-route latency distribution;</li>
 *   <li>{@value #ERRORS_TOTAL}{@code {route,category}} — the "errors" view, keyed by
 *       {@link EventCategory};</li>
 *   <li>{@value #SECURITY_EVENTS_TOTAL}{@code {failure_type}} — the {@code cui-http}
 *       security-filter counts;</li>
 *   <li>{@value #UPSTREAM_DURATION_SECONDS}{@code {route}} — downstream-call time, separated
 *       from gateway overhead.</li>
 * </ul>
 * Route cardinality is bounded (route id is a config-fixed label; unmatched requests share the
 * fixed {@value #NO_ROUTE} value), so every meter is safe to keep always on. Each record call
 * resolves its meter through the {@link MeterRegistry}, which caches meters by name and tag set,
 * so the recorder holds no per-meter state and is thread-safe by delegation.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class SheriffMetrics {

    /** Counter of requests per route, method, and status family — the "paths" view. */
    public static final String REQUESTS_TOTAL = "sheriff_requests_total";
    /** Per-route request latency distribution (timer). */
    public static final String REQUEST_DURATION_SECONDS = "sheriff_request_duration_seconds";
    /** Counter of rejected / failed requests keyed by {@link EventCategory}. */
    public static final String ERRORS_TOTAL = "sheriff_errors_total";
    /** The {@code cui-http} security-filter counts, per failure type. */
    public static final String SECURITY_EVENTS_TOTAL = "sheriff_security_events_total";
    /** Per-route downstream-call time (timer). */
    public static final String UPSTREAM_DURATION_SECONDS = "sheriff_upstream_duration_seconds";

    /** The bounded label value shared by requests that matched no route. */
    public static final String NO_ROUTE = "<no-route>";

    private static final String TAG_ROUTE = "route";
    private static final String TAG_METHOD = "method";
    private static final String TAG_STATUS_FAMILY = "status_family";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_FAILURE_TYPE = "failure_type";

    private final MeterRegistry registry;

    /**
     * @param registry the Micrometer registry the meters are registered against
     */
    @Inject
    public SheriffMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Counts one completed request against {@link #REQUESTS_TOTAL}.
     *
     * @param route        the config-fixed route id, or {@link #NO_ROUTE} when unmatched
     * @param method       the request method (e.g. {@code GET})
     * @param statusFamily the response status family ({@code 2xx} / {@code 3xx} / {@code 4xx} /
     *                     {@code 5xx})
     */
    public void recordRequest(String route, String method, String statusFamily) {
        registry.counter(REQUESTS_TOTAL,
                TAG_ROUTE, route, TAG_METHOD, method, TAG_STATUS_FAMILY, statusFamily).increment();
    }

    /**
     * Records the total time spent handling a request against {@link #REQUEST_DURATION_SECONDS}.
     *
     * @param route    the config-fixed route id, or {@link #NO_ROUTE} when unmatched
     * @param duration the elapsed request time
     */
    public void recordRequestDuration(String route, Duration duration) {
        registry.timer(REQUEST_DURATION_SECONDS, TAG_ROUTE, route).record(duration);
    }

    /**
     * Counts one rejected / failed request against {@link #ERRORS_TOTAL}, keyed by category slug.
     *
     * @param route    the config-fixed route id, or {@link #NO_ROUTE} when unmatched
     * @param category the failure category
     */
    public void recordError(String route, EventCategory category) {
        registry.counter(ERRORS_TOTAL, TAG_ROUTE, route, TAG_CATEGORY, category.slug()).increment();
    }

    /**
     * Binds the boot-shared {@code cui-http} {@link SecurityEventCounter} to the registry, exposing its
     * per-{@link UrlSecurityFailureType} violation counts as the {@link #SECURITY_EVENTS_TOTAL} meter.
     * One {@link FunctionCounter} is registered per failure type, so the {@code failure_type} label
     * cardinality is fixed at the {@link UrlSecurityFailureType} enum (never operator-controlled input),
     * keeping the meter safe to keep always on. Each function counter reads the live
     * {@link SecurityEventCounter#getCount(UrlSecurityFailureType) count} the security-filter stages
     * accumulate on the hot path, so a security-relevant rejection moves the meter on the next scrape
     * without any per-request recording call.
     * <p>
     * Called once at boot with the single {@link SecurityEventCounter} instance shared across the
     * baseline / thorough security-filter stages and the forwarded-header resolver, so the meter
     * reflects every {@code cui-http} violation the gateway counts, not only those observed at the edge.
     *
     * @param securityEventCounter the boot-shared cui-http security event counter (never a local instance)
     */
    public void bindSecurityEventCounter(SecurityEventCounter securityEventCounter) {
        Objects.requireNonNull(securityEventCounter, "securityEventCounter");
        for (UrlSecurityFailureType failureType : UrlSecurityFailureType.values()) {
            FunctionCounter.builder(SECURITY_EVENTS_TOTAL, securityEventCounter,
                    counter -> counter.getCount(failureType))
                    .tag(TAG_FAILURE_TYPE, failureType.name())
                    .register(registry);
        }
    }

    /**
     * Records the time spent in the downstream call against {@link #UPSTREAM_DURATION_SECONDS}.
     *
     * @param route    the config-fixed route id
     * @param duration the elapsed upstream-call time
     */
    public void recordUpstreamDuration(String route, Duration duration) {
        registry.timer(UPSTREAM_DURATION_SECONDS, TAG_ROUTE, route).record(duration);
    }

    /**
     * Classifies an HTTP status code into its bounded {@code status_family} label
     * ({@code 1xx} / {@code 2xx} / {@code 3xx} / {@code 4xx} / {@code 5xx}) for the
     * {@link #REQUESTS_TOTAL} counter, keeping the {@code status_family} label cardinality fixed
     * regardless of the concrete status.
     *
     * @param statusCode the response HTTP status code (e.g. {@code 200}, {@code 404})
     * @return the leading-digit status family (e.g. {@code "2xx"}, {@code "4xx"})
     */
    public static String statusFamily(int statusCode) {
        return (statusCode / 100) + "xx";
    }
}
