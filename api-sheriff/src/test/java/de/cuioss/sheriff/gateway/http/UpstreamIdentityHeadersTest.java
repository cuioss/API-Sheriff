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
package de.cuioss.sheriff.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UpstreamIdentityHeaders}: the exact membership of the response-direction identity
 * set, its case-insensitive matching, and its independence from
 * {@link ConnectionHeaders#RESPONSE_STRIP}.
 */
@EnableGeneratorController
@DisplayName("UpstreamIdentityHeaders — the response-direction identity set")
class UpstreamIdentityHeadersTest {

    @Test
    @DisplayName("Should declare exactly server and x-powered-by, in lower case")
    void shouldPinExactMembership() {
        assertEquals(UpstreamIdentityHeaders.NAMES, Set.of("server", "x-powered-by"),
                "A membership change must be a deliberate edit of this assertion, never a drift");
    }

    @ParameterizedTest(name = "''{0}'' is an identity header")
    @ValueSource(strings = {"server", "SERVER", "Server", "x-powered-by", "X-Powered-By", "X-POWERED-BY"})
    @DisplayName("Should match a member in any letter case")
    void shouldMatchMembersCaseInsensitively(String name) {
        assertTrue(UpstreamIdentityHeaders.isIdentityHeader(name), name + " names the upstream's software");
    }

    @ParameterizedTest(name = "''{0}'' is not an identity header")
    @ValueSource(strings = {"Content-Type", "X-Custom", "Server-Timing", "Via", "X-Powered"})
    @DisplayName("Should not match an ordinary header, nor a name that merely shares a prefix")
    void shouldNotMatchOrdinaryHeaders(String name) {
        assertFalse(UpstreamIdentityHeaders.isIdentityHeader(name), name + " is not in the identity set");
    }

    @Test
    @DisplayName("Should be disjoint from the connection-specific response strip")
    void shouldBeDisjointFromResponseStrip() {
        Set<String> overlap = new HashSet<>(UpstreamIdentityHeaders.NAMES);
        overlap.retainAll(ConnectionHeaders.RESPONSE_STRIP);

        assertTrue(overlap.isEmpty(), () -> "the identity set is a separate, route-overridable set and must "
                + "never be merged into RESPONSE_STRIP, but both name: " + overlap);
    }

    @Test
    @DisplayName("Should refuse a null header name")
    void shouldRefuseNullName() {
        assertThrows(NullPointerException.class, () -> UpstreamIdentityHeaders.isIdentityHeader(null),
                "A header name is mandatory");
    }
}
