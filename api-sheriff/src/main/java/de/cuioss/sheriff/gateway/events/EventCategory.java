/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.events;

/**
 * Impact grouping for gateway <em>failure</em> events. The category names the
 * RFC 9457 problem type (see {@link #problemType()}) and keys the error metrics;
 * the concrete HTTP status is carried per {@link EventType} (two events in one
 * category may map to different statuses).
 * <p>
 * {@link #CONFIGURATION} events occur at boot only — the gateway refuses to start —
 * and never surface as an HTTP response. Success / informational events carry no
 * category at all (see {@link EventType}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum EventCategory {

    /** Path / parameter / header pipeline, collection limits, or body-size violations. */
    INPUT_VALIDATION("input-validation", "Input Validation"),

    /** Missing or invalid bearer token, or invalid / expired session. */
    AUTHENTICATION("authentication", "Authentication"),

    /** Valid credentials lacking a required scope, or a failed CSRF origin check. */
    AUTHORIZATION("authorization", "Authorization"),

    /** Upstream connection failure, timeout, or open circuit breaker. */
    UPSTREAM("upstream", "Upstream"),

    /** Boot-time configuration failure — never rendered as an HTTP response. */
    CONFIGURATION("configuration", "Configuration");

    private final String slug;
    private final String title;

    EventCategory(String slug, String title) {
        this.slug = slug;
        this.title = title;
    }

    /**
     * @return the stable, lowercase, hyphenated slug for this category
     */
    public String slug() {
        return slug;
    }

    /**
     * @return the short, human-readable title used as the RFC 9457 {@code title} member
     */
    public String title() {
        return title;
    }

    /**
     * @return the RFC 9457 {@code type} URI naming this problem category, e.g.
     *         {@code urn:api-sheriff:problem:input-validation}
     */
    public String problemType() {
        return "urn:api-sheriff:problem:" + slug;
    }
}
