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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;


import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relayed-peer hand-off: an exact (host and port) hit names the client a relay leg carries, any
 * other connection names none, and a registration releases only the entry it put there.
 * <p>
 * The addresses are literals on purpose: the contract is about which socket address matches which,
 * so each value is chosen to sit exactly one component away from another.
 */
@DisplayName("RelayedPeerRegistry — the in-process hand-off of a relayed client's address")
class RelayedPeerRegistryTest {

    private static final String LOOPBACK = LoopbackHost.ADDRESS;
    private static final String CLIENT_A = "198.51.100.1";
    private static final String CLIENT_B = "198.51.100.2";
    private static final SocketAddress LEG = SocketAddress.inetSocketAddress(50001, LOOPBACK);

    private final RelayedPeerRegistry registry = new RelayedPeerRegistry();

    @Test
    @DisplayName("names the client of a registered relay leg on an exact host and port hit")
    void exactHitNamesTheClient() {
        registry.register(LEG, CLIENT_A);

        assertEquals(Optional.of(CLIENT_A), registry.clientOf(SocketAddress.inetSocketAddress(50001, LOOPBACK)));
    }

    @Test
    @DisplayName("names no client for another port, another host, or no address at all")
    void anyOtherConnectionNamesNoClient() {
        registry.register(LEG, CLIENT_A);

        assertAll("only the relay leg itself is a hit",
                () -> assertEquals(Optional.empty(), registry.clientOf(SocketAddress.inetSocketAddress(50002, LOOPBACK)),
                        "a direct connection from the same host on another port is not the relay leg"),
                () -> assertEquals(Optional.empty(),
                        registry.clientOf(SocketAddress.inetSocketAddress(50001, "127.0.0.2")),
                        "the same port on another host is not the relay leg"),
                () -> assertEquals(Optional.empty(), registry.clientOf(null), "no address names no client"));
    }

    @Test
    @DisplayName("keeps two relay legs apart")
    void twoLegsNameTheirOwnClients() {
        SocketAddress otherLeg = SocketAddress.inetSocketAddress(50002, LOOPBACK);
        registry.register(LEG, CLIENT_A);
        registry.register(otherLeg, CLIENT_B);

        assertAll(
                () -> assertEquals(Optional.of(CLIENT_A), registry.clientOf(LEG)),
                () -> assertEquals(Optional.of(CLIENT_B), registry.clientOf(otherLeg)),
                () -> assertEquals(2, registry.size()));
    }

    @Test
    @DisplayName("a released registration is forgotten, and releasing it again changes nothing")
    void releaseForgetsTheEntry() {
        RelayedPeerRegistry.Registration registration = registry.register(LEG, CLIENT_A);

        registration.release();
        registration.release();

        assertAll(
                () -> assertEquals(Optional.empty(), registry.clientOf(LEG)),
                () -> assertEquals(0, registry.size()));
    }

    @Test
    @DisplayName("a stale registration of a reused local address does not drop the newer session's entry")
    void staleReleaseKeepsTheNewerEntry() {
        RelayedPeerRegistry.Registration ended = registry.register(LEG, CLIENT_A);
        registry.register(LEG, CLIENT_A);

        ended.release();

        assertEquals(Optional.of(CLIENT_A), registry.clientOf(LEG),
                "the newer session on the reused port is still named, although its client is the same");
    }

    @Test
    @DisplayName("a registration never prints the client's address")
    void registrationHidesTheAddress() {
        RelayedPeerRegistry.Registration registration = registry.register(LEG, CLIENT_A);

        assertFalse(registration.toString().contains(CLIENT_A));
    }

    @Test
    @DisplayName("rejects an absent relay leg or client address")
    void rejectsNulls() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> registry.register(null, CLIENT_A)),
                () -> assertThrows(NullPointerException.class, () -> registry.register(LEG, null)));
    }
}
