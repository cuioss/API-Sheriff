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
 * An entry lives exactly as long as its relay session: {@link Registration#release()} removes it
 * only while the entry is still the one that registration put there, so a session that ends after
 * its local port was reused never drops the newer session's entry.
 * <p>
 * <strong>Thread safety.</strong> Safe for concurrent use: the relay registers and releases on its
 * event loop while the edge looks up from request threads.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RelayedPeerRegistry {

    private final Map<String, Registration> byRelayLeg = new ConcurrentHashMap<>();

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
     * The original client address of the connection whose remote socket address is
     * {@code connectionRemoteAddress}.
     *
     * @param connectionRemoteAddress the remote socket address of the connection a request arrived on,
     *                                {@code null} when the transport supplied none
     * @return the client address on an exact hit (host and port); empty for any connection this
     *         process did not relay
     */
    public Optional<String> clientOf(@Nullable SocketAddress connectionRemoteAddress) {
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
