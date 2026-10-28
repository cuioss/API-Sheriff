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
package de.cuioss.sheriff.gateway.detection;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The key derivation of the detection substrate: which address a request is keyed on, how it is
 * canonicalized, and how the authority host is folded to the declared set. The address literals are
 * the contract, so they are written out.
 */
@EnableGeneratorController
@DisplayName("SourceKeyResolver — source canonicalization and host folding")
class SourceKeyResolverTest {

    private static final String DECLARED_HOST = "api.example.com";

    private final SourceKeyResolver resolver = new SourceKeyResolver(List.of("API.Example.com", "shop.example.com"));

    @Nested
    @DisplayName("the source")
    class Source {

        @Test
        @DisplayName("without a forwarded address the TCP peer is the source")
        void peerIsTheSource() {
            SourceKey key = resolver.resolve("192.0.2.10", null, DECLARED_HOST);

            assertEquals("192.0.2.10", key.source());
        }

        @Test
        @DisplayName("a handed-over forwarded address wins over the peer")
        void forwardedAddressWinsOverThePeer() {
            SourceKey key = resolver.resolve("10.0.0.1", "198.51.100.7", DECLARED_HOST);

            assertEquals("198.51.100.7", key.source());
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "2001:db8:1:2:3:4:5:6, 2001:db8:1:2::/64",
                "2001:db8:1:2:ffff:ffff:ffff:ffff, 2001:db8:1:2::/64",
                "[2001:DB8:0001:0002::1], 2001:db8:1:2::/64",
                "fe80::1%eth0, fe80:0:0:0::/64",
                "::1, 0:0:0:0::/64"
        })
        @DisplayName("an IPv6 address is reduced to its /64 prefix")
        void ipv6IsAggregatedToSlash64(String address, String expected) {
            assertEquals(expected, resolver.resolve(address, null, DECLARED_HOST).source());
        }

        @Test
        @DisplayName("two addresses of one /64 share a key, a neighbouring /64 does not")
        void oneSlash64SharesAKey() {
            SourceKey first = resolver.resolve("2001:db8:a:b::1", null, DECLARED_HOST);
            SourceKey second = resolver.resolve("2001:db8:a:b::ffff", null, DECLARED_HOST);
            SourceKey neighbour = resolver.resolve("2001:db8:a:c::1", null, DECLARED_HOST);

            assertAll("the /64 is the unit of keying",
                    () -> assertEquals(first, second),
                    () -> assertNotEquals(first, neighbour));
        }

        @Test
        @DisplayName("an IPv4 address and an IPv4-mapped IPv6 address are kept whole")
        void ipv4IsKeptWhole() {
            assertAll("IPv4 is not aggregated",
                    () -> assertEquals("203.0.113.9", resolver.resolve("203.0.113.9", null, null).source()),
                    () -> assertEquals("203.0.113.9", resolver.resolve("::ffff:203.0.113.9", null, null).source()),
                    () -> assertEquals("203.0.113.9", resolver.resolve(" 203.0.113.9 ", null, null).source()));
        }

        @ParameterizedTest(name = "[{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "not-an-address", "localhost", "999.1.1.1", "[::1", "2001:db8::g"})
        @DisplayName("an absent, blank or unparseable address folds to the fixed unknown source")
        void unparseableFoldsToUnknown(String address) {
            assertEquals(SourceKey.UNKNOWN_SOURCE, resolver.resolve(address, null, DECLARED_HOST).source());
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"not-an-address", "999.1.1.1", "unknown"})
        @DisplayName("an unparseable forwarded address folds to unknown and never falls back to the peer")
        void unparseableForwardedAddressNeverFallsBackToThePeer(String forwarded) {
            assertEquals(SourceKey.UNKNOWN_SOURCE, resolver.resolve("192.0.2.10", forwarded, DECLARED_HOST).source());
        }

        @Test
        @DisplayName("a host name is never resolved: it folds to unknown like any other non-literal")
        void hostNameIsNotResolved() {
            String name = Generators.letterStrings(5, 12).next().toLowerCase(Locale.ROOT) + ".example";

            assertEquals(SourceKey.UNKNOWN_SOURCE, SourceKeyResolver.canonicalSource(name));
        }
    }

    @Nested
    @DisplayName("the host")
    class Host {

        @Test
        @DisplayName("a declared host is kept, lower-cased")
        void declaredHostIsKept() {
            assertAll("declared hosts compare case-insensitively",
                    () -> assertEquals(DECLARED_HOST, resolver.resolve("192.0.2.1", null, "API.EXAMPLE.COM").host()),
                    () -> assertEquals("shop.example.com",
                            resolver.resolve("192.0.2.1", null, "shop.example.com").host()));
        }

        @Test
        @DisplayName("an undeclared or absent host folds to *, so rotating Host cannot multiply keys")
        void undeclaredHostIsFolded() {
            String rotated = Generators.letterStrings(4, 10).next() + ".attacker.example";
            SourceKey first = resolver.resolve("192.0.2.1", null, rotated);
            SourceKey second = resolver.resolve("192.0.2.1", null, "other-" + rotated);

            assertAll("every undeclared host lands on one key",
                    () -> assertEquals(SourceKey.UNDECLARED_HOST, first.host()),
                    () -> assertEquals(first, second),
                    () -> assertEquals(SourceKey.UNDECLARED_HOST, resolver.resolve("192.0.2.1", null, null).host()));
        }

        @Test
        @DisplayName("with no declared host every host folds to *")
        void noDeclaredHostFoldsEverything() {
            SourceKeyResolver noHosts = new SourceKeyResolver(List.of());

            assertEquals(SourceKey.UNDECLARED_HOST, noHosts.foldHost(DECLARED_HOST));
        }
    }

    @Test
    @DisplayName("a key refuses an absent component")
    void keyRefusesNull() {
        assertAll("both components are mandatory",
                () -> assertThrows(NullPointerException.class, () -> new SourceKey(null, "*")),
                () -> assertThrows(NullPointerException.class, () -> new SourceKey("unknown", null)));
    }
}
