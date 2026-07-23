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
package de.cuioss.sheriff.gateway.tls;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayKind;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import de.cuioss.tools.logging.CuiLogger;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

/**
 * The Vert.x {@link NetServer} front, bound to the public TLS port, that performs the accept-time
 * SNI split at L4.
 * <p>
 * The front is a plain-TCP server: the TLS ClientHello is sent in cleartext at the record layer, so
 * no decryption is needed to read the SNI. On each accepted connection the listener buffers bytes
 * until the {@link ClientHelloSniParser} reports a complete ClientHello, then routes:
 * <ul>
 * <li>a case-insensitively matched {@code passthrough_sni} SNI hands the connection — buffered
 * ClientHello included — to the opaque {@link PassthroughRelay} targeting the resolved backend; the
 * gateway never handshakes;</li>
 * <li>an SNI that is not in the passthrough set relays the still-encrypted stream to the internal
 * terminated Quarkus HTTPS listener;</li>
 * <li>a missing / malformed SNI fails closed (GW-06): it takes the same terminated-strict path and
 * an audited {@code WARN} records the disposition only, never raw bytes.</li>
 * </ul>
 * The listener is constructed and started only when {@code tls.passthrough_sni} is non-empty (see
 * {@link TlsEdgeProducer}); when passthrough is unconfigured the front is never created, so the
 * default single-listener topology is unchanged.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SniFrontListener {

    private static final CuiLogger LOGGER = new CuiLogger(SniFrontListener.class);

    private final Vertx vertx;
    private final PassthroughRelay relay;
    private final ClientHelloSniParser parser;
    private final Map<String, RelayTarget> passthroughTargets;
    private final RelayTarget terminatedTarget;
    private final int publicPort;

    private @org.jspecify.annotations.Nullable NetServer server;

    /**
     * @param vertx              the managed Vert.x instance the front server is created on
     * @param relay              the opaque L4 relay both routing branches hand connections to
     * @param passthroughTargets the immutable SNI (normalized, lower-cased) → backend map; a match
     *                           relays opaquely to the backend
     * @param terminatedTarget   the internal terminated Quarkus HTTPS endpoint every non-passthrough
     *                           connection is relayed to
     * @param publicPort         the public TLS port the front server binds
     */
    public SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.parser = new ClientHelloSniParser();
        this.passthroughTargets = Map.copyOf(Objects.requireNonNull(passthroughTargets, "passthroughTargets"));
        this.terminatedTarget = Objects.requireNonNull(terminatedTarget, "terminatedTarget");
        this.publicPort = publicPort;
    }

    /**
     * Creates the {@link NetServer}, wires the connect handler, and binds the public TLS port.
     *
     * @return a future completing when the front server is listening, or failing when the bind fails
     */
    public Future<Void> start() {
        NetServer netServer = vertx.createNetServer();
        netServer.connectHandler(this::onConnect);
        this.server = netServer;
        return netServer.listen(publicPort)
                .onSuccess(bound -> LOGGER.info(ApiSheriffLogMessages.INFO.SNI_FRONT_LISTENER_STARTED,
                        Integer.toString(publicPort), Integer.toString(passthroughTargets.size())))
                .mapEmpty();
    }

    /**
     * Closes the front server, if started.
     *
     * @return a future completing when the server is closed
     */
    public Future<Void> stop() {
        NetServer current = server;
        return current == null ? Future.succeededFuture() : current.close();
    }

    /**
     * The port the front server is actually bound to. When the configured port is {@code 0} the
     * server binds an ephemeral port, and this returns the concrete port it resolved to.
     *
     * @return the bound port, or {@code -1} when the server has not been started
     */
    public int actualPort() {
        NetServer current = server;
        return current == null ? -1 : current.actualPort();
    }

    private void onConnect(NetSocket socket) {
        new Incoming(socket).begin();
    }

    /**
     * Normalizes an SNI hostname for case-insensitive, root-dot-insensitive exact matching: lowered
     * (root locale), stripped, and with a single trailing FQDN dot removed. Shared by
     * {@link TlsEdgeProducer} when it builds the relay-map keys so lookup and insertion agree.
     *
     * @param host the raw SNI hostname
     * @return the normalized matching key
     */
    static String normalizeSni(String host) {
        String lower = host.toLowerCase(Locale.ROOT).strip();
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /**
     * One accepted connection while its ClientHello is being reassembled. All callbacks run on the
     * connection's Vert.x event loop, so {@code buffer} and {@code decided} are single-threaded.
     */
    private final class Incoming {

        private final NetSocket socket;
        private final Buffer buffer = Buffer.buffer();
        private boolean decided;

        Incoming(NetSocket socket) {
            this.socket = socket;
        }

        void begin() {
            socket.handler(this::onData);
            socket.exceptionHandler(this::onError);
        }

        private void onData(Buffer chunk) {
            if (decided) {
                return;
            }
            buffer.appendBuffer(chunk);
            ClientHelloSniParser.Result result = parser.parse(buffer.getBytes());
            if (!result.complete()) {
                return;
            }
            decided = true;
            socket.pause();
            route(result.serverName());
        }

        private void onError(Throwable failure) {
            if (decided) {
                return;
            }
            LOGGER.debug(failure, "Front connection failed before ClientHello was read: %s", failure.getMessage());
            socket.close();
        }

        private void route(Optional<String> serverName) {
            if (serverName.isEmpty()) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.CLIENT_HELLO_FAIL_CLOSED, "no-usable-sni");
                relay.relay(socket, buffer, terminatedTarget, RelayKind.TERMINATED, "");
                return;
            }
            String sni = serverName.get();
            RelayTarget target = passthroughTargets.get(normalizeSni(sni));
            if (target != null) {
                relay.relay(socket, buffer, target, RelayKind.PASSTHROUGH, sni);
            } else {
                relay.relay(socket, buffer, terminatedTarget, RelayKind.TERMINATED, "");
            }
        }
    }
}
