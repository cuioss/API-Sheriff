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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Locale;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The operator-facing top-level {@code enumeration_detection} block: the tuning of the per-source
 * detection substrate the edge feeds with every pre-trust rejection of the recon set, and the
 * response to a source that trips it.
 * <p>
 * Every member is optional and independently omissible, and an omitted block resolves to the
 * documented defaults ({@link #defaults()}): detection is on, a source trips at {@value #DEFAULT_THRESHOLD}
 * recon events within {@value #DEFAULT_WINDOW_SECONDS} seconds, and the response is
 * {@link Action#OBSERVE}. Detection therefore runs on every gateway, and a trip is recorded — the trip
 * {@code WARN}, the event counter and the trip metric — without changing any answer.
 * <p>
 * <strong>Why {@code observe} is the default.</strong> The gateway keys a source by its address, and
 * it cannot tell at boot whether one address stands for one client or for every client behind a NAT
 * or an untrusted load balancer. A default {@link Action#BLOCK} or {@link Action#DELAY} would refuse
 * or slow all of them for the probing of one. Both are therefore operator opt-ins, to be chosen once
 * the deployment's source addresses are known to be per client — directly, or through
 * {@code forwarded.trusted_proxies}.
 * <p>
 * Values are validated at boot ({@code LimitRules.validateEnumerationDetection}); this record only
 * carries them.
 *
 * @param enabled       whether detection runs at all, {@code null} when not declared
 * @param threshold     the number of recon events within the window at which a source trips,
 *                      {@code null} when not declared
 * @param windowSeconds the window over which a full threshold drains, {@code null} when not declared
 * @param action        the response to a tripped source, {@code null} when not declared
 * @param blockSeconds  how long a tripped source stays blocked under {@link Action#BLOCK}, {@code null}
 *                      when not declared
 * @param delayMillis   how long a tripped source's rejection answers are held under
 *                      {@link Action#DELAY}, {@code null} when not declared
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record EnumerationDetectionConfig(
@Nullable Boolean enabled,
@Nullable Integer threshold,
@Nullable Integer windowSeconds,
@Nullable Action action,
@Nullable Integer blockSeconds,
@Nullable Integer delayMillis) {

    /** Detection runs when the operator declares nothing. */
    public static final boolean DEFAULT_ENABLED = true;

    /** The trip threshold applied when the operator declares none. */
    public static final int DEFAULT_THRESHOLD = 100;

    /** The drain window, in seconds, applied when the operator declares none. */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    /** The response applied when the operator declares none; see the class documentation for why. */
    public static final Action DEFAULT_ACTION = Action.OBSERVE;

    /** The block duration, in seconds, applied when the operator declares none. */
    public static final int DEFAULT_BLOCK_SECONDS = 60;

    /** The answer hold, in milliseconds, applied when the operator declares none. */
    public static final int DEFAULT_DELAY_MILLIS = 2000;

    /**
     * The response to a source that trips.
     * <p>
     * The constants are uppercase per Java convention; the case-insensitive YAML binding maps the
     * lowercase {@code block} / {@code delay} / {@code observe} configuration values onto them.
     */
    public enum Action {

        /** A tripped source is answered {@code 429} with {@code Retry-After} for the block duration. */
        BLOCK,

        /** A tripped source's rejection answers are held for the configured delay. */
        DELAY,

        /** A trip is recorded and no answer changes — the default. */
        OBSERVE;

        /**
         * @return the lowercase configuration spelling of this action, as it appears in
         *         {@code gateway.yaml}
         */
        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @return the block an omitted {@code enumeration_detection} resolves to — every member at its
     *         documented default
     */
    public static EnumerationDetectionConfig defaults() {
        return new EnumerationDetectionConfig(DEFAULT_ENABLED, DEFAULT_THRESHOLD, DEFAULT_WINDOW_SECONDS,
                DEFAULT_ACTION, DEFAULT_BLOCK_SECONDS, DEFAULT_DELAY_MILLIS);
    }

    /**
     * @return the declared switch, or {@link #DEFAULT_ENABLED} when the member is absent
     */
    public boolean effectiveEnabled() {
        return enabled != null ? enabled : DEFAULT_ENABLED;
    }

    /**
     * @return the declared threshold, or {@link #DEFAULT_THRESHOLD} when the member is absent
     */
    public int effectiveThreshold() {
        return threshold != null ? threshold : DEFAULT_THRESHOLD;
    }

    /**
     * @return the declared window, or {@link #DEFAULT_WINDOW_SECONDS} when the member is absent
     */
    public int effectiveWindowSeconds() {
        return windowSeconds != null ? windowSeconds : DEFAULT_WINDOW_SECONDS;
    }

    /**
     * @return the declared action, or {@link #DEFAULT_ACTION} ({@link Action#OBSERVE}) when the member
     *         is absent
     */
    public Action effectiveAction() {
        return action != null ? action : DEFAULT_ACTION;
    }

    /**
     * @return the declared block duration, or {@link #DEFAULT_BLOCK_SECONDS} when the member is absent
     */
    public int effectiveBlockSeconds() {
        return blockSeconds != null ? blockSeconds : DEFAULT_BLOCK_SECONDS;
    }

    /**
     * @return the declared answer hold, or {@link #DEFAULT_DELAY_MILLIS} when the member is absent
     */
    public int effectiveDelayMillis() {
        return delayMillis != null ? delayMillis : DEFAULT_DELAY_MILLIS;
    }
}
