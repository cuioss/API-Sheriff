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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClaimAllowlistFilter}: the operator-owned claim allowlist capping what the
 * session/user-info endpoint may disclose. The security invariants under test — an empty allowlist
 * discloses nothing, a claim outside the allowlist can never appear, and the curated default view is
 * itself capped to the allowlist — are pure, container-free, and framework-agnostic.
 */
class ClaimAllowlistFilterTest {

    private static final Map<String, Object> SOURCE = Map.of(
            "sub", "user-sub-1",
            "name", "Alice Example",
            "roles", List.of("admin", "user"),
            "email", "alice@example.com");

    @Nested
    @DisplayName("Secure default closed")
    class SecureDefault {

        @Test
        @DisplayName("An empty allowlist discloses nothing")
        void shouldDiscloseNothingWhenAllowlistEmpty() {
            ClaimAllowlistFilter filter = new ClaimAllowlistFilter(List.of(), List.of("sub", "name"));

            assertTrue(filter.isEmpty(), "an empty allowlist is the secure closed default");
            assertTrue(filter.allowedClaims().isEmpty());
            assertTrue(filter.defaultView().isEmpty(), "the default view is capped away to nothing");
            assertTrue(filter.filter(SOURCE, List.of("sub", "name", "roles")).isEmpty(),
                    "no claim is disclosed through an empty allowlist");
        }

        @Test
        @DisplayName("The default view is capped to the allowlist at construction")
        void shouldCapDefaultViewToAllowlist() {
            ClaimAllowlistFilter filter = new ClaimAllowlistFilter(List.of("sub", "name"),
                    List.of("sub", "name", "email"));

            assertIterableEquals(List.of("sub", "name"), filter.defaultView(),
                    "the email default-view entry outside the allowlist is dropped");
        }
    }

    @Nested
    @DisplayName("Allowlist membership")
    class Membership {

        private final ClaimAllowlistFilter filter = new ClaimAllowlistFilter(List.of("sub", "name", "roles"),
                List.of("sub", "name"));

        @Test
        @DisplayName("isAllowed reflects the operator allowlist")
        void shouldReportMembership() {
            assertTrue(filter.isAllowed("sub"));
            assertTrue(filter.isAllowed("roles"));
            assertFalse(filter.isAllowed("email"), "a claim outside the allowlist is not permitted");
        }

        @Test
        @DisplayName("A non-empty allowlist is not the closed default")
        void shouldNotBeEmpty() {
            assertFalse(filter.isEmpty());
        }
    }

    @Nested
    @DisplayName("Filtering")
    class Filtering {

        private final ClaimAllowlistFilter filter = new ClaimAllowlistFilter(List.of("sub", "name", "roles"),
                List.of("sub", "name"));

        @Test
        @DisplayName("Only allowlisted, present claims are disclosed, in selection order")
        void shouldDiscloseAllowlistedPresentClaimsInOrder() {
            Map<String, Object> disclosed = filter.filter(SOURCE, List.of("name", "sub"));

            assertIterableEquals(List.of("name", "sub"), disclosed.keySet(),
                    "selection order is preserved");
            assertEquals("Alice Example", disclosed.get("name"));
            assertEquals("user-sub-1", disclosed.get("sub"));
        }

        @Test
        @DisplayName("A requested claim outside the allowlist is silently dropped from the filtered result")
        void shouldDropDisallowedRequestedClaim() {
            Map<String, Object> disclosed = filter.filter(SOURCE, List.of("sub", "email"));

            assertFalse(disclosed.containsKey("email"), "email is not on the allowlist");
            assertTrue(disclosed.containsKey("sub"));
        }

        @Test
        @DisplayName("A selected claim absent from the source contributes nothing")
        void shouldSkipSelectedClaimAbsentFromSource() {
            Map<String, Object> disclosed = filter.filter(SOURCE, List.of("sub", "roles"));

            assertTrue(disclosed.containsKey("roles"), "roles is allowlisted and present");
            assertEquals(2, disclosed.size());
        }
    }

    @Nested
    @DisplayName("Immutability and argument contract")
    class Contract {

        private final ClaimAllowlistFilter filter = new ClaimAllowlistFilter(List.of("sub", "name"),
                List.of("sub"));

        @Test
        @DisplayName("allowedClaims is immutable")
        void shouldExposeImmutableAllowedClaims() {
            assertThrows(UnsupportedOperationException.class, () -> filter.allowedClaims().add("email"));
        }

        @Test
        @DisplayName("defaultView is immutable")
        void shouldExposeImmutableDefaultView() {
            assertThrows(UnsupportedOperationException.class, () -> filter.defaultView().add("email"));
        }

        @Test
        @DisplayName("The filtered map is immutable")
        void shouldReturnImmutableFilteredMap() {
            Map<String, Object> disclosed = filter.filter(SOURCE, List.of("sub"));

            assertThrows(UnsupportedOperationException.class, () -> disclosed.put("email", "x"));
        }

        @Test
        @DisplayName("Null constructor arguments are rejected")
        void shouldRejectNullConstructorArguments() {
            assertThrows(NullPointerException.class, () -> new ClaimAllowlistFilter(null, List.of()));
            assertThrows(NullPointerException.class, () -> new ClaimAllowlistFilter(List.of(), null));
        }

        @Test
        @DisplayName("Null filter arguments are rejected")
        void shouldRejectNullFilterArguments() {
            assertThrows(NullPointerException.class, () -> filter.filter(null, List.of()));
            assertThrows(NullPointerException.class, () -> filter.filter(SOURCE, null));
            assertThrows(NullPointerException.class, () -> filter.isAllowed(null));
        }
    }
}
