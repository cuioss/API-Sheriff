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

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The operator-facing top-level {@code threat_classification} block: the tuning of the per-source
 * threat score the edge keeps on its own detection ledger, fed with every categorized rejection of the
 * per-request flow, and whose first threshold crossing per episode raises one structured ALERT record.
 * <p>
 * Every member is optional and independently omissible, and an omitted block resolves to the
 * documented defaults ({@link #defaults()}): classification is on, a source alerts at a score of
 * {@value #DEFAULT_THRESHOLD} within {@value #DEFAULT_WINDOW_SECONDS} seconds, and each rejection
 * category adds the weight {@link Weights} documents.
 * <p>
 * <strong>Observe only.</strong> The block has no {@code action} member: the threat ledger only
 * observes. An ALERT changes no answer, status, header or timing — the response to a probing source is
 * the business of {@code enumeration_detection}.
 * <p>
 * <strong>Fixed zero categories.</strong> The block has no {@code upstream}, {@code rate_limiting} or
 * {@code configuration} weight. An upstream failure describes the upstream, not the caller; a
 * rate-limiting refusal is answered before admission and never reaches the feed, and counting it would
 * extend a block by the requests it refuses; a configuration event occurs at boot only. A weight for any
 * of them could never act, so none is offered.
 * <p>
 * <strong>The residual.</strong> The score is keyed by source address. Every client behind one NAT or
 * untrusted load balancer shares a key, and a looping misbehaving client is scored as a scanner is. The
 * low {@code authentication} and {@code routing} defaults keep the expired-token {@code 401}s and stray
 * {@code 404}s of such a population below the drain rate; an ALERT has no wire effect either way.
 * <p>
 * Values are validated at boot ({@code LimitRules.validateThreatClassification}); this record only
 * carries them.
 *
 * @param enabled       whether classification runs at all, {@code null} when not declared
 * @param threshold     the score within the window at which a source raises its ALERT, {@code null}
 *                      when not declared
 * @param windowSeconds the window over which a full threshold drains, {@code null} when not declared
 * @param weights       the weight per rejection category, {@code null} when not declared
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record ThreatClassificationConfig(
@Nullable Boolean enabled,
@Nullable Integer threshold,
@Nullable Integer windowSeconds,
@Nullable Weights weights) {

    /** Classification runs when the operator declares nothing. */
    public static final boolean DEFAULT_ENABLED = true;

    /** The alert threshold applied when the operator declares none. */
    public static final int DEFAULT_THRESHOLD = 100;

    /** The drain window, in seconds, applied when the operator declares none. */
    public static final int DEFAULT_WINDOW_SECONDS = 300;

    /**
     * The weight each rejection category adds to a source's threat score.
     * <p>
     * Every member is optional and independently omissible; an omitted member resolves to its default.
     * The defaults weigh one malformed request ({@code input_validation}) at a tenth of the default
     * threshold, so a single benign mistake never alerts while an injection scanner at one request per
     * second alerts within seconds. {@code routing} is weighted a tenth of that, and
     * {@code authentication} and {@code authorization} low enough that the expired-token answers of a
     * population behind one address stay below the drain rate. A recon trip — the
     * {@code enumeration_detection} ledger reaching its own threshold — adds {@code recon_trip}, half the
     * default threshold: it is an input to the score, never an ALERT of its own.
     *
     * @param inputValidation the weight of an input-validation rejection, {@code null} when not declared
     * @param routing         the weight of a routing rejection, {@code null} when not declared
     * @param authentication  the weight of an authentication rejection, {@code null} when not declared
     * @param authorization   the weight of an authorization rejection, {@code null} when not declared
     * @param reconTrip       the weight of a recon-ledger trip, {@code null} when not declared
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Weights(
    @Nullable Integer inputValidation,
    @Nullable Integer routing,
    @Nullable Integer authentication,
    @Nullable Integer authorization,
    @Nullable Integer reconTrip) {

        /** The input-validation weight applied when the operator declares none. */
        public static final int DEFAULT_INPUT_VALIDATION = 10;

        /** The routing weight applied when the operator declares none. */
        public static final int DEFAULT_ROUTING = 1;

        /** The authentication weight applied when the operator declares none. */
        public static final int DEFAULT_AUTHENTICATION = 2;

        /** The authorization weight applied when the operator declares none. */
        public static final int DEFAULT_AUTHORIZATION = 2;

        /** The recon-trip weight applied when the operator declares none. */
        public static final int DEFAULT_RECON_TRIP = 50;

        /**
         * @return the weights an omitted {@code weights} block resolves to — every member at its
         *         documented default
         */
        public static Weights defaults() {
            return new Weights(DEFAULT_INPUT_VALIDATION, DEFAULT_ROUTING, DEFAULT_AUTHENTICATION,
                    DEFAULT_AUTHORIZATION, DEFAULT_RECON_TRIP);
        }

        /**
         * @return the declared input-validation weight, or {@link #DEFAULT_INPUT_VALIDATION} when absent
         */
        public int effectiveInputValidation() {
            return inputValidation != null ? inputValidation : DEFAULT_INPUT_VALIDATION;
        }

        /**
         * @return the declared routing weight, or {@link #DEFAULT_ROUTING} when absent
         */
        public int effectiveRouting() {
            return routing != null ? routing : DEFAULT_ROUTING;
        }

        /**
         * @return the declared authentication weight, or {@link #DEFAULT_AUTHENTICATION} when absent
         */
        public int effectiveAuthentication() {
            return authentication != null ? authentication : DEFAULT_AUTHENTICATION;
        }

        /**
         * @return the declared authorization weight, or {@link #DEFAULT_AUTHORIZATION} when absent
         */
        public int effectiveAuthorization() {
            return authorization != null ? authorization : DEFAULT_AUTHORIZATION;
        }

        /**
         * @return the declared recon-trip weight, or {@link #DEFAULT_RECON_TRIP} when absent
         */
        public int effectiveReconTrip() {
            return reconTrip != null ? reconTrip : DEFAULT_RECON_TRIP;
        }
    }

    /**
     * @return the block an omitted {@code threat_classification} resolves to — every member at its
     *         documented default
     */
    public static ThreatClassificationConfig defaults() {
        return new ThreatClassificationConfig(DEFAULT_ENABLED, DEFAULT_THRESHOLD, DEFAULT_WINDOW_SECONDS,
                Weights.defaults());
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
     * @return the declared weights, or {@link Weights#defaults()} when the member is absent; a declared
     *         block's omitted members resolve through its own effective accessors
     */
    public Weights effectiveWeights() {
        return weights != null ? weights : Weights.defaults();
    }
}
