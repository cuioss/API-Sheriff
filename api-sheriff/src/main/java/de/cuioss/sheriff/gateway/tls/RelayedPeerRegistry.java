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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.net.SocketAddress;
import org.jspecify.annotations.Nullable;

/**
 * The in-process hand-off of a relayed client's address from the {@link SniFrontListener} to the
 * internal terminated listener.
 * <p>
 * Behind the SNI front, every terminated connection reaches the internal Quarkus HTTPS listener over
 * a loopback relay leg, so the TCP peer the edge sees is that leg's loopback address — the same for
 * every client. The {@link PassthroughRelay} therefore records, for each terminated relay, the exact
 * local socket address of its backend leg (host and port) against the original client's address, and
 * the edge looks up the remote address of the connection a request arrived on. Only an exact hit — a
 * connection this process dialled itself — yields a client address; a connection the registry does
 * not hold yields nothing, so a client that reaches the internal listener directly, or sends any
 * header, cannot supply an address. No header is read.
 * <p>
 * <strong>Behind the front, a hit is honoured only on the terminated listener.</strong> Once the
 * front listener is known ({@link #frontTerminatedListener}), an exact hit names its client only when
 * the connection's local port is the terminated listener's port, or when the transport supplied no
 * local address; a hit on a connection whose known local port is another listener's is
 * {@linkplain Attribution#NONE neutral}, since the relay never dials any other listener.
 * <p>
 * <strong>A miss on the terminated listener is reported, not just absent.</strong> Once the front
 * listener is known to relay to the internal terminated listener ({@link #frontTerminatedListener}),
 * a connection that arrived on that listener's port without an exact hit — one the relay never
 * dialled, or one whose session the relay has already released — is reported as
 * {@linkplain Attribution#unattributed() unattributed}, so its consumer can refuse to let any header
 * stand in for the missing hand-off. A miss whose local address the transport did not supply is
 * reported unattributed too, since it cannot be shown to have arrived elsewhere. Without a front
 * listener nothing is ever reported unattributed.
 * <p>
 * An entry lives exactly as long as its relay session: {@link Registration#release()} removes it
 * only while the entry is still the one that registration put there, so a session that ends after
 * its local port was reused never drops the newer session's entry.
 * <p>
 * <strong>Thread safety.</strong> Safe for concurrent use: the relay registers and releases on its
 * event loop while the edge looks up from request threads; the terminated listener's port is set at
 * most once.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RelayedPeerRegistry {

    /** The {@link #terminatedListenerPort} value while no front listener relays to a terminated listener. */
    private static final int NOT_FRONTED = -1;
    private static final int MAX_PORT = 65_535;

    private final Map<String, Registration> byRelayLeg = new ConcurrentHashMap<>();
    private final AtomicInteger terminatedListenerPort = new AtomicInteger(NOT_FRONTED);

    /**
     * Records that the SNI front listener relays terminated connections to the internal listener on
     * {@code port}. From then on a connection without an exact hit can be reported
     * {@linkplain Attribution#unattributed() unattributed}, and a hit is honoured only on this port or
     * on an unknown local address, as {@link #attribute} states. Set once, before the first request
     * can reach the edge; setting the same port again changes nothing.
     *
     * @param port the local port of the internal terminated listener, {@code 1..65535}
     * @throws IllegalArgumentException when {@code port} is outside {@code 1..65535}
     * @throws IllegalStateException    when another port was already recorded
     */
    public void frontTerminatedListener(int port) {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("Terminated listener port out of range: " + port);
        }
        if (!terminatedListenerPort.compareAndSet(NOT_FRONTED, port) && terminatedListenerPort.get() != port) {
            throw new IllegalStateException("A different terminated listener port is already recorded");
        }
    }

    /**
     * Records that the relay leg whose local socket address is {@code relayLegLocalAddress} carries
     * the connection of {@code clientAddress}, replacing any entry held for the same address.
     *
     * @param relayLegLocalAddress the local socket address of the relay's backend leg
     * @param clientAddress        the original client's address, as the front listener's peer
     * @return the registration, to be {@linkplain Registration#release() released} when the relay
     *         session ends
     */
    public Registration register(SocketAddress relayLegLocalAddress, String clientAddress) {
        String key = keyOf(Objects.requireNonNull(relayLegLocalAddress, "relayLegLocalAddress"));
        Registration registration = new Registration(key, Objects.requireNonNull(clientAddress, "clientAddress"));
        byRelayLeg.put(key, registration);
        return registration;
    }

    /**
     * What the registry knows about the connection a request arrived on: the relayed client's address
     * on an exact hit the registry honours, else whether the connection, behind the front and without
     * one, arrived on the terminated listener's port or on an unknown local address.
     *
     * <p>
     * Behind the front, a hit is honoured only on the terminated listener: the relay dials nothing
     * else, so a connection whose known local port is another listener's yields
     * {@link Attribution#NONE} even when its remote address matches a registered relay leg. A hit
     * whose local address is unknown is honoured, because the registry cannot rule out that it
     * arrived on the terminated listener. Without a front listener every hit names its client.
     *
     * <p>
     * Behind the front, a miss whose local address is unknown fails closed: without a local address
     * the registry cannot rule out that the connection arrived on the terminated listener, so it is
     * reported {@linkplain Attribution#unattributed() unattributed} rather than neutral.
     *
     * @param connectionLocalAddress  the local socket address of the connection a request arrived on,
     *                                {@code null} when the transport supplied none
     * @param connectionRemoteAddress the remote socket address of the connection a request arrived on,
     *                                {@code null} when the transport supplied none
     * @return the attribution; the relayed client for a hit with no front listener configured, or for
     *         a hit behind the front whose local address is the terminated listener's port or unknown;
     *         {@link Attribution#UNATTRIBUTED_CONNECTION} for a miss behind the front whose local
     *         address is the terminated listener's port or unknown; {@link Attribution#NONE} for a miss
     *         with no front listener configured, or for any connection behind the front whose known
     *         local port is another listener's
     */
    public Attribution attribute(@Nullable SocketAddress connectionLocalAddress,
            @Nullable SocketAddress connectionRemoteAddress) {
        Optional<String> client = clientOf(connectionRemoteAddress);
        int port = terminatedListenerPort.get();
        if (port == NOT_FRONTED) {
            return client.map(address -> new Attribution(address, false)).orElse(Attribution.NONE);
        }
        boolean mayBeTerminatedListener = connectionLocalAddress == null || connectionLocalAddress.port() == port;
        if (!mayBeTerminatedListener) {
            return Attribution.NONE;
        }
        return client.map(address -> new Attribution(address, false)).orElse(Attribution.UNATTRIBUTED_CONNECTION);
    }

    /**
     * The original client address of the connection whose remote socket address is
     * {@code connectionRemoteAddress}.
     *
     * @param connectionRemoteAddress the remote socket address of the connection a request arrived on,
     *                                {@code null} when the transport supplied none
     * @return the client address on an exact hit (host and port); empty for any connection this
     *         process did not relay
     */
    Optional<String> clientOf(@Nullable SocketAddress connectionRemoteAddress) {
        if (connectionRemoteAddress == null) {
            return Optional.empty();
        }
        Registration registration = byRelayLeg.get(keyOf(connectionRemoteAddress));
        return registration == null ? Optional.empty() : Optional.of(registration.clientAddress);
    }

    /**
     * @return the number of relay sessions currently registered
     */
    public int size() {
        return byRelayLeg.size();
    }

    private static String keyOf(SocketAddress address) {
        String host = address.hostAddress() != null ? address.hostAddress() : address.host();
        return host + "|" + address.port();
    }

    /**
     * The registry's verdict on one connection. At most one of the two components is set: a relayed
     * client's address, or the {@code unattributed} mark of a connection behind the front that arrived
     * without a hand-off on the terminated listener's port or on an unknown local address.
     *
     * @param clientAddress the relayed client's address on an exact hit the registry honours — any hit
     *                      without a front listener, and behind the front only a hit on the terminated
     *                      listener's port or on an unknown local address; {@code null} otherwise
     * @param unattributed  {@code true} when the connection arrived behind the front, on the terminated
     *                      listener's port or on an unknown local address, and the registry holds no
     *                      entry for it
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Attribution(@Nullable String clientAddress, boolean unattributed) {

        /**
         * A connection the registry neither attributes nor marks: a miss with no front, or — hit or
         * miss — a connection behind the front on a known other listener.
         */
        public static final Attribution NONE = new Attribution(null, false);

        /**
         * A connection behind the front that the registry holds no entry for, arriving on the
         * terminated listener or on an unknown local address.
         */
        public static final Attribution UNATTRIBUTED_CONNECTION = new Attribution(null, true);

        /**
         * Canonical constructor rejecting a verdict that both names a client and marks it unattributed.
         *
         * @throws IllegalArgumentException when {@code clientAddress} is set and {@code unattributed} too
         */
        public Attribution {
            if (clientAddress != null && unattributed) {
                throw new IllegalArgumentException("An attributed connection cannot be unattributed");
            }
        }

        /**
         * Overridden so the client address is never printed.
         *
         * @return a fixed text naming the verdict only
         */
        @Override
        public String toString() {
            if (clientAddress != null) {
                return "Attribution[relayed]";
            }
            return unattributed ? "Attribution[unattributed]" : "Attribution[none]";
        }
    }

    /**
     * One relay session's entry. Its identity — not its content — is what {@link #release()} compares,
     * so two sessions of the same client on a reused local port are told apart.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public final class Registration {

        private final String key;
        private final String clientAddress;

        private Registration(String key, String clientAddress) {
            this.key = key;
            this.clientAddress = clientAddress;
        }

        /**
         * Removes this entry, unless the relay leg's address has since been registered by another
         * session. Idempotent.
         */
        public void release() {
            byRelayLeg.remove(key, this);
        }

        /**
         * Overridden so the client address is never printed.
         *
         * @return a fixed text without the address
         */
        @Override
        public String toString() {
            return "RelayedPeerRegistry.Registration";
        }
    }
}
