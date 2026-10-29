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
package de.cuioss.sheriff.gateway.edge.admission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.edge.error.ReconEvents;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import org.jspecify.annotations.Nullable;

/**
 * The weight a rejection adds to its source's threat score: the configured weight of the rejection's
 * {@link EventCategory}, judged on its TRUE event, or {@code 0} when the rejection is not scored.
 * <p>
 * <strong>The caller categories.</strong> {@link EventCategory#INPUT_VALIDATION},
 * {@link EventCategory#ROUTING}, {@link EventCategory#AUTHENTICATION} and
 * {@link EventCategory#AUTHORIZATION} take the weights the {@code threat_classification.weights} block
 * resolves to. They describe what the caller sent, before and after the trust boundary alike: a
 * malformed or injection-shaped request from an authenticated caller is still hostile.
 * <p>
 * <strong>Fixed at {@code 0}, each for a recorded reason.</strong>
 * <ul>
 *   <li>{@link EventCategory#UPSTREAM} — an upstream failure describes the upstream, not the
 *       caller;</li>
 *   <li>{@link EventCategory#RATE_LIMITING} — the refusal of a blocked source is answered before
 *       admission and never reaches the feed, and counting it would extend a block by the very requests
 *       it refuses;</li>
 *   <li>{@link EventCategory#CONFIGURATION} — a configuration event occurs at boot only and is never a
 *       rejection of a request;</li>
 *   <li>an event without a category — the success, session-lifecycle and informational events — is not
 *       a rejection;</li>
 *   <li>the closed exclusion set {@link #EXCLUDED_EVENTS}: {@link EventType#INBOUND_BODY_ABORTED} and
 *       {@link EventType#REQUEST_BODY_TIMEOUT}, because a body that stopped arriving is a network or
 *       client condition, and {@link EventType#PRE_TRUST_INTERNAL_FAILURE}, because an operator-side
 *       failure is not caller behaviour;</li>
 *   <li>a static resource ({@link ReconEvents#isStaticResource}) — a miss there is a page's own asset
 *       reference or a browser-ambient fetch, not a probe.</li>
 * </ul>
 * None of the fixed zeros is a configuration key: a weight that could never act would parse without
 * acting.
 * <p>
 * The switch over {@link EventCategory} has no default arm, so a new category does not compile until it
 * is given a weight here.
 * <p>
 * Immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ThreatWeights {

    /**
     * The closed set of categorized events that are never scored, whatever their category's weight: a
     * body that stopped arriving and an operator-side internal failure.
     */
    public static final Set<EventType> EXCLUDED_EVENTS = Collections.unmodifiableSet(EnumSet.of(
            EventType.INBOUND_BODY_ABORTED,
            EventType.REQUEST_BODY_TIMEOUT,
            EventType.PRE_TRUST_INTERNAL_FAILURE));

    private final int inputValidation;
    private final int routing;
    private final int authentication;
    private final int authorization;
    private final int reconTrip;

    /**
     * Creates the weights from the resolved {@code weights} block.
     *
     * @param weights the declared weights; an omitted member resolves to its default
     */
    public ThreatWeights(ThreatClassificationConfig.Weights weights) {
        Objects.requireNonNull(weights, "weights");
        this.inputValidation = weights.effectiveInputValidation();
        this.routing = weights.effectiveRouting();
        this.authentication = weights.effectiveAuthentication();
        this.authorization = weights.effectiveAuthorization();
        this.reconTrip = weights.effectiveReconTrip();
    }

    /**
     * The weight a rejection adds to its source's threat score.
     *
     * @param request   the rejected request, {@code null} for a rejection raised before one was built —
     *                  such a rejection is never a static resource
     * @param eventType the TRUE event of the rejection, never the one it is rendered as
     * @return the configured weight of the event's category, or {@code 0} when the rejection is not
     *         scored
     */
    public int weightOf(@Nullable PipelineRequest request, EventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        if (EXCLUDED_EVENTS.contains(eventType)) {
            return 0;
        }
        EventCategory category = eventType.category();
        if (category == null) {
            return 0;
        }
        int weight = switch (category) {
            case INPUT_VALIDATION -> inputValidation;
            case ROUTING -> routing;
            case AUTHENTICATION -> authentication;
            case AUTHORIZATION -> authorization;
            case UPSTREAM, RATE_LIMITING, CONFIGURATION -> 0;
        };
        if (weight == 0 || (request != null && ReconEvents.isStaticResource(request))) {
            return 0;
        }
        return weight;
    }

    /**
     * @return the weight a trip of the recon ledger adds to the tripped source's threat score
     */
    public int reconTrip() {
        return reconTrip;
    }
}
