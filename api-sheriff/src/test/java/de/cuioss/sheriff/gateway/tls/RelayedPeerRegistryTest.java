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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * Behind the front, a miss on the terminated listener's port is reported, not merely absent: a
     * connection the relay never dialled and one whose session it already released both come back
     * unattributed, as does a miss whose local address is unknown; a hit on the terminated listener
     * or an unknown local address keeps its client, and any connection on a known other listener —
     * a hit included — stays neutral.
     */
    @Nested
    @DisplayName("attribute — behind the front, a terminated-listener miss is reported unattributed")
    class Attribute {

        private static final int TERMINATED_PORT = 8444;
        private static final SocketAddress ON_TERMINATED = SocketAddress.inetSocketAddress(TERMINATED_PORT, LOOPBACK);
        private static final SocketAddress ON_OTHER_LISTENER = SocketAddress.inetSocketAddress(8080, LOOPBACK);

        @Test
        @DisplayName("a registered relay leg on the fronted terminated listener names its client")
        void hitNamesTheClient() {
            registry.frontTerminatedListener(TERMINATED_PORT);
            registry.register(LEG, CLIENT_A);

            assertEquals(new RelayedPeerRegistry.Attribution(CLIENT_A, false), registry.attribute(ON_TERMINATED, LEG));
        }

        @Test
        @DisplayName("a connection the relay never dialled, on the fronted terminated listener, is unattributed")
        void unregisteredConnectionIsUnattributed() {
            registry.frontTerminatedListener(TERMINATED_PORT);
            registry.register(LEG, CLIENT_A);

            assertEquals(RelayedPeerRegistry.Attribution.UNATTRIBUTED_CONNECTION,
                    registry.attribute(ON_TERMINATED, SocketAddress.inetSocketAddress(50002, LOOPBACK)));
        }

        @Test
        @DisplayName("behind the front, a registered relay leg arriving on a known other listener is neutral")
        void hitOnOtherListenerIsNeutral() {
            registry.frontTerminatedListener(TERMINATED_PORT);
            registry.register(LEG, CLIENT_A);

            RelayedPeerRegistry.Attribution attribution = registry.attribute(ON_OTHER_LISTENER, LEG);

            assertAll(
                    () -> assertEquals(RelayedPeerRegistry.Attribution.NONE, attribution,
                            "the relay dials only the terminated listener, so a hit elsewhere names no client"),
                    () -> assertNull(attribution.clientAddress()),
                    () -> assertFalse(attribution.unattributed()));
        }

        @Test
        @DisplayName("control: without a front a registered relay leg names its client on any listener")
        void withoutFrontHitOnAnyListenerNamesTheClient() {
            registry.register(LEG, CLIENT_A);

            assertAll(
                    () -> assertEquals(new RelayedPeerRegistry.Attribution(CLIENT_A, false),
                            registry.attribute(ON_OTHER_LISTENER, LEG)),
                    () -> assertEquals(new RelayedPeerRegistry.Attribution(CLIENT_A, false),
                            registry.attribute(ON_TERMINATED, LEG)));
        }

        @Test
        @DisplayName("a relay leg whose session was released is unattributed, not neutral")
        void releasedLegIsUnattributed() {
            registry.frontTerminatedListener(TERMINATED_PORT);
            RelayedPeerRegistry.Registration registration = registry.register(LEG, CLIENT_A);

            registration.release();

            assertEquals(RelayedPeerRegistry.Attribution.UNATTRIBUTED_CONNECTION, registry.attribute(ON_TERMINATED, LEG));
        }

        @Test
        @DisplayName("a connection on a known other listener is neutral even behind the front")
        void otherListenerIsNeutral() {
            registry.frontTerminatedListener(TERMINATED_PORT);

            assertEquals(RelayedPeerRegistry.Attribution.NONE,
                    registry.attribute(ON_OTHER_LISTENER, SocketAddress.inetSocketAddress(50002, LOOPBACK)));
        }

        @Test
        @DisplayName("behind the front, a miss with no local address fails closed as unattributed")
        void unknownLocalAddressFailsClosed() {
            registry.frontTerminatedListener(TERMINATED_PORT);
            registry.register(LEG, CLIENT_A);

            assertAll(
                    () -> assertEquals(RelayedPeerRegistry.Attribution.UNATTRIBUTED_CONNECTION,
                            registry.attribute(null, SocketAddress.inetSocketAddress(50002, LOOPBACK)),
                            "an unknown local address cannot be shown to be another listener"),
                    () -> assertEquals(RelayedPeerRegistry.Attribution.UNATTRIBUTED_CONNECTION,
                            registry.attribute(null, null),
                            "neither address known is unattributed too"),
                    () -> assertEquals(new RelayedPeerRegistry.Attribution(CLIENT_A, false), registry.attribute(null, LEG),
                            "a hit on an unknown local address cannot be ruled out as the terminated listener"));
        }

        @Test
        @DisplayName("control: without a front the same terminated-listener miss is neutral")
        void withoutFrontNothingIsUnattributed() {
            registry.register(LEG, CLIENT_A);
            SocketAddress miss = SocketAddress.inetSocketAddress(50002, LOOPBACK);

            assertAll(
                    () -> assertEquals(RelayedPeerRegistry.Attribution.NONE, registry.attribute(ON_TERMINATED, miss)),
                    () -> assertEquals(RelayedPeerRegistry.Attribution.NONE, registry.attribute(null, miss),
                            "with no front an unknown local address stays neutral"),
                    () -> assertEquals(new RelayedPeerRegistry.Attribution(CLIENT_A, false),
                            registry.attribute(ON_TERMINATED, LEG), "a hit needs no front"));
        }

        @Test
        @DisplayName("the terminated listener's port is set once: the same port again is accepted, another refused")
        void portIsSetOnce() {
            registry.frontTerminatedListener(TERMINATED_PORT);

            assertAll(
                    () -> assertDoesNotThrow(() -> registry.frontTerminatedListener(TERMINATED_PORT)),
                    () -> assertThrows(IllegalStateException.class, () -> registry.frontTerminatedListener(8445)));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(ints = {0, -1, 65_536})
        @DisplayName("a port outside 1..65535 is refused")
        void outOfRangePortIsRefused(int port) {
            assertThrows(IllegalArgumentException.class, () -> registry.frontTerminatedListener(port));
        }

        @Test
        @DisplayName("an attribution never prints the client's address and cannot be both relayed and unattributed")
        void attributionHidesTheAddressAndIsConsistent() {
            RelayedPeerRegistry.Attribution relayed = new RelayedPeerRegistry.Attribution(CLIENT_A, false);

            assertAll(
                    () -> assertFalse(relayed.toString().contains(CLIENT_A)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RelayedPeerRegistry.Attribution(CLIENT_A, true)));
        }
    }
}
