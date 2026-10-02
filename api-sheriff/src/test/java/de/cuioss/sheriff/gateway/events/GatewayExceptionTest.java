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
package de.cuioss.sheriff.gateway.events;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnableGeneratorController
@DisplayName("GatewayException — event type and RFC 9457 problem extension members")
class GatewayExceptionTest {

    private static final String MISSING_SCOPES = "missing_scopes";
    private static final String STEP_UP_URL = "step_up_url";

    /** The internal log message — never rendered, so any non-blank value serves. */
    private static String logMessage() {
        return Generators.letterStrings(8, 32).next();
    }

    @Nested
    @DisplayName("Without extension members")
    class WithoutMembers {

        @Test
        @DisplayName("carries the event type and an empty member map from every member-less constructor")
        void memberLessConstructorsCarryNoMembers() {
            String message = logMessage();
            IllegalStateException cause = new IllegalStateException(message);

            GatewayException eventOnly = new GatewayException(EventType.TOKEN_MISSING);
            GatewayException withMessage = new GatewayException(EventType.TOKEN_INVALID, message);
            GatewayException withCause = new GatewayException(EventType.UPSTREAM_ERROR, message, cause);

            assertAll("a failure without members renders the standard body",
                    () -> assertEquals(EventType.TOKEN_MISSING, eventOnly.getEventType(), "event type is kept"),
                    () -> assertTrue(eventOnly.getProblemExtensions().isEmpty(), "event-only carries no members"),
                    () -> assertEquals(message, withMessage.getMessage(), "the log message is kept"),
                    () -> assertTrue(withMessage.getProblemExtensions().isEmpty(), "message form carries no members"),
                    () -> assertSame(cause, withCause.getCause(), "the cause is kept"),
                    () -> assertTrue(withCause.getProblemExtensions().isEmpty(), "cause form carries no members"));
        }

        @Test
        @DisplayName("accepts an empty member map as a failure without members")
        void emptyMemberMapCarriesNoMembers() {
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING,
                    logMessage(), Map.of());

            assertTrue(rejected.getProblemExtensions().isEmpty(), "an empty map adds nothing to the body");
        }

        @Test
        @DisplayName("reports no members after a serialization round trip, keeping the event type")
        void deserializedInstanceCarriesNoMembers() throws Exception {
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING,
                    logMessage(), Map.of(MISSING_SCOPES, List.of("orders:read")));

            GatewayException restored = roundTrip(rejected);

            assertAll("the members are not part of the serialized form",
                    () -> assertEquals(EventType.SCOPE_MISSING, restored.getEventType(), "event type survives"),
                    () -> assertEquals(rejected.getMessage(), restored.getMessage(), "the message survives"),
                    () -> assertTrue(restored.getProblemExtensions().isEmpty(),
                            "a restored instance reports an empty map, never null"));
        }

        private static GatewayException roundTrip(GatewayException original) throws IOException,
                ClassNotFoundException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (GatewayException) in.readObject();
            }
        }
    }

    @Nested
    @DisplayName("With extension members")
    class WithMembers {

        @Test
        @DisplayName("keeps the members in insertion order alongside the event type and message")
        void keepsMembersInInsertionOrder() {
            String message = logMessage();
            Map<String, Object> members = new LinkedHashMap<>();
            members.put(STEP_UP_URL, "/auth/step-up?returnUrl=%2Forders");
            members.put(MISSING_SCOPES, List.of("orders:read"));

            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING, message, members);

            assertAll(
                    () -> assertEquals(EventType.SCOPE_MISSING, rejected.getEventType(), "event type is kept"),
                    () -> assertEquals(message, rejected.getMessage(), "the log message is kept"),
                    () -> assertEquals(List.of(STEP_UP_URL, MISSING_SCOPES),
                            List.copyOf(rejected.getProblemExtensions().keySet()),
                            "the rendering order is the insertion order"),
                    () -> assertEquals(members, rejected.getProblemExtensions(), "every member is carried"));
        }

        @Test
        @DisplayName("is unaffected by later changes to the map and the collection it was built from")
        void copiesMapAndCollectionValues() {
            List<String> scopes = new ArrayList<>(List.of("orders:read"));
            Map<String, Object> members = new HashMap<>();
            members.put(MISSING_SCOPES, scopes);
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING,
                    logMessage(), members);

            scopes.add("orders:write");
            members.put(STEP_UP_URL, "/auth/step-up");

            assertEquals(Map.of(MISSING_SCOPES, List.of("orders:read")), rejected.getProblemExtensions(),
                    "the rendered body cannot change after the failure was raised");
        }

        @Test
        @DisplayName("exposes the members as an unmodifiable map")
        void exposesUnmodifiableMembers() {
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING,
                    logMessage(), Map.of(MISSING_SCOPES, List.of("orders:read")));
            Map<String, Object> exposed = rejected.getProblemExtensions();

            assertThrows(UnsupportedOperationException.class, () -> exposed.put(STEP_UP_URL, "/auth/step-up"),
                    "a caller must not add members to a raised failure");
        }
    }

    @Nested
    @DisplayName("Rejected extension members")
    class RejectedMembers {

        @Test
        @DisplayName("rejects an absent member map")
        void rejectsNullMemberMap() {
            String message = logMessage();

            assertThrows(NullPointerException.class,
                    () -> new GatewayException(EventType.SCOPE_MISSING, message, (Map<String, Object>) null),
                    "an absent map is a programming error");
        }

        @Test
        @DisplayName("rejects a member without a name or without a value")
        void rejectsNullNameOrValue() {
            String message = logMessage();
            Map<String, Object> nullName = new HashMap<>();
            nullName.put(null, "value");
            Map<String, Object> nullValue = new HashMap<>();
            nullValue.put(MISSING_SCOPES, null);

            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> new GatewayException(EventType.SCOPE_MISSING, message, nullName),
                            "a member needs a name"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new GatewayException(EventType.SCOPE_MISSING, message, nullValue),
                            "a member needs a value"));
        }

        @ParameterizedTest(name = "member \"{0}\"")
        @ValueSource(strings = {"type", "title", "status", "detail", "instance"})
        @DisplayName("rejects a member that redefines a standard RFC 9457 problem member")
        void rejectsStandardMemberRedefinition(String standardMember) {
            String message = logMessage();
            Map<String, Object> members = Map.of(standardMember, "overridden");

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new GatewayException(EventType.SCOPE_MISSING, message, members),
                    "an extension must not replace a standard member");

            assertTrue(thrown.getMessage().contains(standardMember), "the refusal names the offending member");
        }
    }
}
