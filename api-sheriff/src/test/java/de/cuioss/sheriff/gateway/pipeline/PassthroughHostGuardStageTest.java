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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PassthroughHostGuardStage — runtime Host-vs-SNI smuggle 404 guard")
class PassthroughHostGuardStageTest {

    /** The reserved passthrough SNI hostname the guard protects. */
    private static final String PASSTHROUGH_SNI = "backend.internal.example";

    private final PassthroughHostGuardStage guardedStage =
            new PassthroughHostGuardStage(List.of(PASSTHROUGH_SNI));

    @Nested
    @DisplayName("Smuggled Host rejection (a terminated Host naming a passthrough SNI)")
    class SmuggledHostRejection {

        @Test
        @DisplayName("rejects a Host that names a passthrough SNI with 404 PASSTHROUGH_HOST_SMUGGLED")
        void rejectsSmuggledHost() {
            // Arrange
            PipelineRequest request = requestWithHost(PASSTHROUGH_SNI);

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> guardedStage.process(request));

            // Assert — the guard's own event maps to a 404 input-validation rejection
            assertAll("smuggle rejection",
                    () -> assertEquals(EventType.PASSTHROUGH_HOST_SMUGGLED, thrown.getEventType(),
                            "Rejection must carry the smuggle event"),
                    () -> assertEquals(404, thrown.getEventType().httpStatus(),
                            "The smuggle guard rejects with 404"),
                    () -> assertEquals(EventCategory.INPUT_VALIDATION, thrown.getEventType().category(),
                            "The smuggle guard is an input-validation rejection"));
        }

        @ParameterizedTest(name = "rejects normalized smuggle variant \"{0}\"")
        @ValueSource(strings = {
                "BACKEND.INTERNAL.EXAMPLE",       // case-insensitive match
                "Backend.Internal.Example",       // mixed case
                "backend.internal.example.",      // trailing FQDN dot stripped
                "backend.internal.example:8443",  // :port suffix stripped
                "  backend.internal.example  "    // surrounding whitespace stripped
        })
        @DisplayName("rejects a smuggled Host after case/dot/port normalization")
        void rejectsNormalizedSmuggleVariants(String smuggledHost) {
            // Arrange
            PipelineRequest request = requestWithHost(smuggledHost);

            // Act
            GatewayException thrown = assertThrows(GatewayException.class, () -> guardedStage.process(request));

            // Assert
            assertEquals(EventType.PASSTHROUGH_HOST_SMUGGLED, thrown.getEventType(),
                    "Normalized variant must still be recognized as a smuggle");
        }

        @Test
        @DisplayName("rejects the smuggle before route selection (no route is recorded on the request)")
        void rejectsBeforeRouteSelection() {
            // Arrange
            PipelineRequest request = requestWithHost(PASSTHROUGH_SNI);

            // Act
            assertThrows(GatewayException.class, () -> guardedStage.process(request));

            // Assert — the guard is a pre-check: it must not have advanced routing state
            assertAll("pre-route rejection",
                    () -> assertNull(request.selectedRoute(),
                            "No route may be selected on a rejected smuggle"),
                    () -> assertNull(request.canonicalPath(),
                            "The guard must not canonicalize a rejected request"));
        }
    }

    @Nested
    @DisplayName("Benign pass-through (a request the guard must let flow to route selection)")
    class BenignPassThrough {

        @Test
        @DisplayName("passes a benign Host through to route selection")
        void passesBenignHost() {
            // Arrange
            PipelineRequest request = requestWithHost("edge.public.example");

            // Act + Assert — a benign Host is a no-op (the guard never touches it)
            assertDoesNotThrow(() -> guardedStage.process(request));
        }

        @Test
        @DisplayName("treats an absent Host header as a no-op")
        void passesNullHost() {
            // Arrange — the edge may build a request without a Host authority
            PipelineRequest request = requestWithHost(null);

            // Act + Assert
            assertDoesNotThrow(() -> guardedStage.process(request));
        }

        @Test
        @DisplayName("passes a Host that only shares a suffix with the passthrough SNI")
        void passesSuffixLookalikeHost() {
            // Arrange — "evil-backend.internal.example" must NOT match "backend.internal.example"
            PipelineRequest request = requestWithHost("evil-backend.internal.example");

            // Act + Assert
            assertDoesNotThrow(() -> guardedStage.process(request));
        }
    }

    @Nested
    @DisplayName("Empty passthrough set (an inert guard)")
    class EmptyPassthroughSet {

        private final PassthroughHostGuardStage inertStage =
                new PassthroughHostGuardStage(Set.of());

        @Test
        @DisplayName("is a no-op even for a Host that would otherwise be a smuggle")
        void inertWhenPassthroughSetEmpty() {
            // Arrange — with no passthrough SNI configured there is no reserved identity to smuggle
            PipelineRequest request = requestWithHost(PASSTHROUGH_SNI);

            // Act + Assert
            assertDoesNotThrow(() -> inertStage.process(request));
        }
    }

    @Nested
    @DisplayName("Contract guards")
    class ContractGuards {

        @Test
        @DisplayName("rejects a null passthrough-SNI collection at construction")
        void rejectsNullConstructorArgument() {
            assertThrows(NullPointerException.class, () -> new PassthroughHostGuardStage(null));
        }

        @Test
        @DisplayName("rejects a null request at process time")
        void rejectsNullRequest() {
            assertThrows(NullPointerException.class, () -> guardedStage.process(null));
        }
    }

    private static PipelineRequest requestWithHost(String host) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/v1/resource")
                .host(host)
                .build();
    }
}
