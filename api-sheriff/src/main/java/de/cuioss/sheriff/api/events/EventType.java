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
package de.cuioss.sheriff.api.events;

import org.jspecify.annotations.Nullable;

/**
 * The catalogue of gateway events, each carrying its {@link EventCategory} and, for
 * request-time failures, the HTTP status the edge renders. Modelled on
 * {@code token-sheriff}'s {@code SecurityEventCounter.EventType}.
 * <p>
 * <strong>Success / informational events carry a {@code null} category</strong> (per CUI
 * convention) and have no HTTP mapping ({@code httpStatus == 0}) — they are metric
 * counters only. <strong>Configuration events</strong> carry {@link EventCategory#CONFIGURATION}
 * but also have no HTTP mapping: they occur at boot and abort startup rather than
 * producing a response. <strong>Request-time failures</strong> carry a category and a
 * positive HTTP status, matching the error contract in {@code architecture.adoc}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum EventType {

    // --- Success / informational (no category, no HTTP mapping) ---

    /** A request was successfully forwarded to its upstream. */
    REQUEST_FORWARDED(null, 0),
    /** A bearer token was refreshed. */
    TOKEN_REFRESHED(null, 0),
    /** Gateway configuration was loaded and validated at boot. */
    CONFIG_LOADED(null, 0),

    // --- Configuration failures (boot only, never an HTTP response) ---

    /** Configuration failed validation; the gateway refuses to start. */
    CONFIG_INVALID(EventCategory.CONFIGURATION, 0),
    /** A route weakens an authentication default via an explicit override. */
    AUTH_WEAKENED(EventCategory.CONFIGURATION, 0),

    // --- Input validation (400 / 404 / 405) ---

    /** A cui-http security filter rejected the request. */
    SECURITY_FILTER_VIOLATION(EventCategory.INPUT_VALIDATION, 400),
    /** The normalized path is outside the route's {@code allowed_paths} whitelist. */
    PATH_NOT_ALLOWED(EventCategory.INPUT_VALIDATION, 400),
    /** A collection / parameter limit was exceeded. */
    PARAMETER_LIMIT_EXCEEDED(EventCategory.INPUT_VALIDATION, 400),
    /** No route matched the canonical path (deny-by-default routing). */
    NO_ROUTE_MATCHED(EventCategory.INPUT_VALIDATION, 404),
    /** The request method is outside the route's effective {@code allowed_methods}. */
    METHOD_NOT_ALLOWED(EventCategory.INPUT_VALIDATION, 405),

    // --- Authentication (401) ---

    /** No bearer token was presented on a route requiring one. */
    TOKEN_MISSING(EventCategory.AUTHENTICATION, 401),
    /** The presented bearer token was invalid or expired. */
    TOKEN_INVALID(EventCategory.AUTHENTICATION, 401),

    // --- Authorization (403) ---

    /** Valid credentials lacking a required scope. */
    SCOPE_MISSING(EventCategory.AUTHORIZATION, 403),
    /** A CSRF origin check failed. */
    CSRF_REJECTED(EventCategory.AUTHORIZATION, 403),

    // --- Upstream (502 / 503 / 504) ---

    /** Upstream connection failure or an invalid upstream response. */
    UPSTREAM_ERROR(EventCategory.UPSTREAM, 502),
    /** The circuit breaker is open; the upstream was not called. */
    UPSTREAM_CIRCUIT_OPEN(EventCategory.UPSTREAM, 503),
    /** The upstream call exceeded its configured timeout. */
    UPSTREAM_TIMEOUT(EventCategory.UPSTREAM, 504);

    private final @Nullable EventCategory category;
    private final int httpStatus;

    EventType(@Nullable EventCategory category, int httpStatus) {
        this.category = category;
        this.httpStatus = httpStatus;
    }

    /**
     * @return this event's category, or {@code null} for a success / informational event
     */
    public @Nullable EventCategory category() {
        return category;
    }

    /**
     * @return the HTTP status the edge renders for this event, or {@code 0} when the event
     *         has no HTTP mapping (success / informational or boot-only configuration events)
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * @return {@code true} when this event represents a failure (carries a category)
     */
    public boolean isFailure() {
        return category != null;
    }

    /**
     * @return {@code true} when this event maps to an HTTP status (a request-time failure)
     */
    public boolean hasHttpMapping() {
        return httpStatus > 0;
    }
}
