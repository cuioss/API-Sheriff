/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.forward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TcpPeerGate — immediate-TCP-peer trust gate (ADR-0003)")
class TcpPeerGateTest {

    @Nested
    @DisplayName("IPv4 CIDR membership")
    class IPv4Membership {

        @ParameterizedTest
        @CsvSource({
                "127.0.0.0/8,127.0.0.1",
                "127.0.0.0/8,127.255.255.254",
                "10.0.0.0/8,10.9.9.9",
                "192.168.1.0/24,192.168.1.0",
                "192.168.1.0/24,192.168.1.255",
                "203.0.113.5/32,203.0.113.5"
        })
        @DisplayName("trusts a peer inside the configured range")
        void trustsPeerInsideRange(String cidr, String peer) {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of(cidr));

            // Act + Assert
            assertTrue(gate.isTrustedPeer(peer), peer + " must be trusted inside " + cidr);
        }

        @ParameterizedTest
        @CsvSource({
                "127.0.0.0/8,128.0.0.1",
                "10.0.0.0/8,11.0.0.1",
                "192.168.1.0/24,192.168.2.1",
                "203.0.113.5/32,203.0.113.6"
        })
        @DisplayName("rejects a peer outside the configured range")
        void rejectsPeerOutsideRange(String cidr, String peer) {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of(cidr));

            // Act + Assert
            assertFalse(gate.isTrustedPeer(peer), peer + " must be rejected outside " + cidr);
        }

        @Test
        @DisplayName("trusts a peer matching any of several configured ranges")
        void trustsAcrossMultipleRanges() {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of("10.0.0.0/8", "192.168.0.0/16"));

            // Act + Assert
            assertTrue(gate.isTrustedPeer("10.1.2.3"));
            assertTrue(gate.isTrustedPeer("192.168.50.1"));
            assertFalse(gate.isTrustedPeer("172.16.0.1"));
        }
    }

    @Nested
    @DisplayName("IPv6 CIDR membership is address-family aware")
    class IPv6Membership {

        @Test
        @DisplayName("trusts an IPv6 peer inside an IPv6 range")
        void trustsIpv6PeerInsideRange() {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of("2001:db8::/32"));

            // Act + Assert
            assertTrue(gate.isTrustedPeer("2001:db8::1"));
            assertFalse(gate.isTrustedPeer("2001:dead::1"));
        }

        @Test
        @DisplayName("an IPv4 peer never matches an IPv6 range and vice versa")
        void crossFamilyNeverMatches() {
            // Arrange
            TcpPeerGate ipv6Gate = new TcpPeerGate(List.of("2001:db8::/32"));
            TcpPeerGate ipv4Gate = new TcpPeerGate(List.of("10.0.0.0/8"));

            // Act + Assert
            assertFalse(ipv6Gate.isTrustedPeer("10.0.0.1"), "IPv4 candidate must not match an IPv6 range");
            assertFalse(ipv4Gate.isTrustedPeer("2001:db8::1"), "IPv6 candidate must not match an IPv4 range");
        }
    }

    @Nested
    @DisplayName("fail-closed behaviour")
    class FailClosed {

        @Test
        @DisplayName("an empty trusted set trusts no peer")
        void emptySetTrustsNoPeer() {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of());

            // Act + Assert
            assertFalse(gate.isTrustedPeer("127.0.0.1"), "an empty trusted set must trust no peer");
        }

        @Test
        @DisplayName("a null peer address is never trusted")
        void nullPeerNeverTrusted() {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of("0.0.0.0/0"));

            // Act + Assert
            assertFalse(gate.isTrustedPeer(null), "a null peer must never be trusted, even under 0.0.0.0/0");
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-an-ip", "999.0.0.1", "127.0.0.1/8", ""})
        @DisplayName("an unparseable peer address is never trusted")
        void unparseablePeerNeverTrusted(String peer) {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of("127.0.0.0/8"));

            // Act + Assert
            assertFalse(gate.isTrustedPeer(peer), "an unparseable peer literal must never be trusted");
        }

        @Test
        @DisplayName("surrounding whitespace on the peer address is stripped before matching")
        void stripsSurroundingWhitespace() {
            // Arrange
            TcpPeerGate gate = new TcpPeerGate(List.of("127.0.0.0/8"));

            // Act + Assert
            assertTrue(gate.isTrustedPeer("  127.0.0.1  "), "a padded but valid peer literal must be trusted");
        }
    }

    @Nested
    @DisplayName("boot-time CIDR parsing rejects malformed input")
    class CidrParsing {

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "10.0.0.0/99", "10.0.0.0/-1", "999.0.0.0/8", "not-a-cidr"})
        @DisplayName("rejects a malformed trusted-proxy CIDR at construction")
        void rejectsMalformedCidr(String badCidr) {
            // Act + Assert
            List<String> cidrs = List.of(badCidr);
            assertThrows(IllegalArgumentException.class, () -> new TcpPeerGate(cidrs),
                    "malformed CIDR must fail loud at boot");
        }

        @Test
        @DisplayName("rejects a null CIDR collection")
        void rejectsNullCollection() {
            // Act + Assert
            assertThrows(NullPointerException.class, () -> new TcpPeerGate(null));
        }
    }
}
