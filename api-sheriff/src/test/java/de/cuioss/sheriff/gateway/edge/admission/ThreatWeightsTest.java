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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The weight a rejection adds to its source's threat score, over every {@link EventType} constant: the
 * configured weight of its caller category, before and after the trust boundary alike, and {@code 0}
 * for the fixed categories, for an event without a category, for the closed exclusion set and for a
 * static resource.
 */
@DisplayName("ThreatWeights — the weight of a rejection in the threat score")
class ThreatWeightsTest {

    /** Distinct declared weights, so an event weighed under the wrong category is told apart. */
    private static final int INPUT_VALIDATION = 11;
    private static final int ROUTING = 3;
    private static final int AUTHENTICATION = 5;
    private static final int AUTHORIZATION = 7;
    private static final int RECON_TRIP = 13;

    /** The three categorized events never scored, written out: the literal IS the contract. */
    private static final Set<EventType> EXPECTED_EXCLUSIONS = EnumSet.of(EventType.INBOUND_BODY_ABORTED,
            EventType.REQUEST_BODY_TIMEOUT, EventType.PRE_TRUST_INTERNAL_FAILURE);

    private final ThreatWeights declared = new ThreatWeights(new ThreatClassificationConfig.Weights(
            INPUT_VALIDATION, ROUTING, AUTHENTICATION, AUTHORIZATION, RECON_TRIP));

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventType.class)
    @DisplayName("an untrusted, non-static request weighs the configured weight of the event's caller category")
    void weightFollowsTheCategory(EventType eventType) {
        assertEquals(expectedWeight(eventType), declared.weightOf(request("/orders"), eventType), eventType.name());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventType.class)
    @DisplayName("a request past the trust boundary weighs the same: a hostile input stays hostile")
    void postTrustRequestWeighsTheSame(EventType eventType) {
        PipelineRequest trusted = request("/orders");
        trusted.markTrustBoundaryPassed();

        assertEquals(expectedWeight(eventType), declared.weightOf(trusted, eventType), eventType.name());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventType.class)
    @DisplayName("a rejection raised before a request was built weighs the configured weight")
    void noRequestWeighsTheConfiguredWeight(EventType eventType) {
        assertEquals(expectedWeight(eventType), declared.weightOf(null, eventType), eventType.name());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventType.class)
    @DisplayName("a static resource weighs 0 under every event")
    void staticResourceWeighsZero(EventType eventType) {
        assertEquals(0, declared.weightOf(request("/favicon.ico"), eventType), eventType.name());
    }

    @Test
    @DisplayName("the closed exclusion set is exactly the two body conditions and the operator-side failure")
    void exclusionSetIsClosed() {
        assertEquals(EXPECTED_EXCLUSIONS, ThreatWeights.EXCLUDED_EVENTS);
    }

    @Test
    @DisplayName("the recon trip weighs the declared recon_trip")
    void reconTripWeighsTheDeclaredWeight() {
        assertEquals(RECON_TRIP, declared.reconTrip());
    }

    @Test
    @DisplayName("omitted weights resolve to the documented defaults")
    void omittedWeightsResolveToTheDefaults() {
        ThreatWeights defaults = new ThreatWeights(ThreatClassificationConfig.Weights.builder().build());
        PipelineRequest request = request("/orders");

        assertAll("the defaults per caller category",
                () -> assertEquals(EventCategory.INPUT_VALIDATION, EventType.SECURITY_FILTER_VIOLATION.category(),
                        "precondition"),
                () -> assertEquals(10, defaults.weightOf(request, EventType.SECURITY_FILTER_VIOLATION)),
                () -> assertEquals(EventCategory.ROUTING, EventType.NO_ROUTE_MATCHED.category(), "precondition"),
                () -> assertEquals(1, defaults.weightOf(request, EventType.NO_ROUTE_MATCHED)),
                () -> assertEquals(EventCategory.AUTHENTICATION, EventType.TOKEN_MISSING.category(), "precondition"),
                () -> assertEquals(2, defaults.weightOf(request, EventType.TOKEN_MISSING)),
                () -> assertEquals(EventCategory.AUTHORIZATION, EventType.SCOPE_MISSING.category(), "precondition"),
                () -> assertEquals(2, defaults.weightOf(request, EventType.SCOPE_MISSING)),
                () -> assertEquals(50, defaults.reconTrip()));
    }

    @Test
    @DisplayName("a declared member overrides its default while the omitted members keep theirs")
    void declaredMemberOverridesOnlyItsDefault() {
        ThreatWeights partial = new ThreatWeights(ThreatClassificationConfig.Weights.builder()
                .inputValidation(4).reconTrip(0).build());
        PipelineRequest request = request("/orders");

        assertAll("one declared member",
                () -> assertEquals(4, partial.weightOf(request, EventType.SECURITY_FILTER_VIOLATION)),
                () -> assertEquals(2, partial.weightOf(request, EventType.TOKEN_MISSING), "the default stays"),
                () -> assertEquals(0, partial.reconTrip(), "a declared 0 is kept, not replaced by the default"));
    }

    /**
     * The expected weight, derived from the contract: the exclusion set and an event without a category
     * weigh {@code 0}; a caller category weighs its declared weight; every other category {@code 0}.
     */
    private static int expectedWeight(EventType eventType) {
        if (EXPECTED_EXCLUSIONS.contains(eventType)) {
            return 0;
        }
        EventCategory category = eventType.category();
        if (category == null) {
            return 0;
        }
        return switch (category) {
            case INPUT_VALIDATION -> INPUT_VALIDATION;
            case ROUTING -> ROUTING;
            case AUTHENTICATION -> AUTHENTICATION;
            case AUTHORIZATION -> AUTHORIZATION;
            case UPSTREAM, RATE_LIMITING, CONFIGURATION -> 0;
        };
    }

    private static PipelineRequest request(String path) {
        PipelineRequest request = PipelineRequest.builder().method(HttpMethod.GET).requestPath(path).build();
        request.canonicalPath(path);
        return request;
    }
}
