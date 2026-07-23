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

import java.util.Objects;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.tools.logging.CuiLogger;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

/**
 * The opaque L4 TCP pipe: a {@link NetSocket} ↔ {@link NetSocket} relay, backpressured in both
 * directions, with half-close and abort propagation.
 * <p>
 * The relay dials the resolved backend over a plain TCP {@link NetClient} — it never speaks TLS —
 * replays the already-buffered ClientHello bytes verbatim, and then pipes every subsequent byte
 * transparently. The gateway therefore never participates in the handshake: the backend presents
 * its own certificate directly to the client, end-to-end. Backpressure follows the Vert.x
 * write-queue contract (pause the busy source until the target drains); a graceful {@code FIN} on
 * either leg is propagated as a half-close, and an exception on either leg aborts both.
 * <p>
 * The same pipe backs both branches of the {@link SniFrontListener}: a {@code passthrough_sni} match
 * relays to the topology-resolved backend, and a terminated (non-passthrough) connection relays to
 * the internal Quarkus HTTPS listener — the only difference is the target and the lifecycle log
 * level.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PassthroughRelay {

    private static final CuiLogger LOGGER = new CuiLogger(PassthroughRelay.class);

    private final NetClient netClient;

    /**
     * @param netClient the shared plain-TCP client the relay dials the backend with (no TLS)
     */
    public PassthroughRelay(NetClient netClient) {
        this.netClient = Objects.requireNonNull(netClient, "netClient");
    }

    /**
     * Dials the target and, on success, replays the buffered ClientHello and establishes the opaque
     * bidirectional pipe. On a dial failure the accepted client connection is closed (abort, never a
     * hang).
     *
     * @param client      the accepted client connection, already paused by the caller
     * @param clientHello the bytes buffered while the SNI was being read, replayed to the backend
     *                    first so the handshake is byte-identical
     * @param target      the resolved backend endpoint to relay to
     * @param kind        whether the connection is a passthrough match or a terminated hand-off,
     *                    controlling the lifecycle log level
     * @param sniLabel    the matched SNI hostname for a passthrough relay's audit log ({@code kind ==
     *                    PASSTHROUGH}); ignored for a terminated relay
     */
    public void relay(NetSocket client, Buffer clientHello, RelayTarget target, RelayKind kind, String sniLabel) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(clientHello, "clientHello");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sniLabel, "sniLabel");
        netClient.connect(target.port(), target.host())
                .onSuccess(backend -> establish(client, backend, clientHello, target, kind, sniLabel))
                .onFailure(failure -> {
                    LOGGER.debug(failure, "Relay backend dial to %s failed: %s", target, failure.getMessage());
                    closeQuietly(client);
                });
    }

    private static void establish(NetSocket client, NetSocket backend, Buffer clientHello, RelayTarget target,
            RelayKind kind, String sniLabel) {
        if (kind == RelayKind.PASSTHROUGH) {
            LOGGER.info(ApiSheriffLogMessages.INFO.PASSTHROUGH_RELAY_ESTABLISHED, sniLabel, target.toString());
        } else {
            LOGGER.debug("Terminated relay established to %s", target);
        }
        new RelaySession(client, backend).start(clientHello);
    }

    private static void closeQuietly(NetSocket socket) {
        socket.close().onFailure(failure ->
                LOGGER.debug(failure, "Relay socket close failed: %s", failure.getMessage()));
    }

    /**
     * A resolved backend endpoint the relay dials at L4.
     *
     * @param host the backend host
     * @param port the backend port
     * @author API Sheriff Team
     * @since 1.0
     */
    public record RelayTarget(String host, int port) {

        /** Canonical constructor requiring a non-null {@code host}. */
        public RelayTarget {
            Objects.requireNonNull(host, "host");
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /** Whether a relayed connection is a passthrough SNI match or a terminated hand-off. */
    public enum RelayKind {
        /** The SNI matched {@code tls.passthrough_sni}; relayed opaquely to the resolved backend. */
        PASSTHROUGH,
        /** No passthrough match; relayed to the internal terminated Quarkus HTTPS listener. */
        TERMINATED
    }

    /**
     * One established relay: the two legs and their bidirectional, backpressured wiring. Every
     * callback runs on the accepted connection's Vert.x event loop, so the {@code closed} flag is
     * single-threaded and needs no synchronization.
     */
    private static final class RelaySession {

        private final NetSocket client;
        private final NetSocket backend;
        private boolean closed;

        RelaySession(NetSocket client, NetSocket backend) {
            this.client = client;
            this.backend = backend;
        }

        void start(Buffer clientHello) {
            wire(client, backend);
            wire(backend, client);
            client.closeHandler(v -> closeBoth());
            backend.closeHandler(v -> closeBoth());
            client.exceptionHandler(this::abort);
            backend.exceptionHandler(this::abort);
            if (clientHello.length() > 0) {
                backend.write(clientHello);
            }
            client.resume();
        }

        private void wire(NetSocket source, NetSocket target) {
            source.handler(buffer -> {
                if (closed) {
                    return;
                }
                target.write(buffer);
                if (target.writeQueueFull()) {
                    source.pause();
                    target.drainHandler(v -> source.resume());
                }
            });
            // A graceful FIN on the read side is propagated as a half-close on the peer's write side.
            source.endHandler(v -> target.end()
                    .onFailure(failure -> LOGGER.debug(failure, "Relay half-close failed: %s", failure.getMessage())));
        }

        private void abort(Throwable failure) {
            LOGGER.debug(failure, "Passthrough relay error: %s", failure.getMessage());
            closeBoth();
        }

        private void closeBoth() {
            if (closed) {
                return;
            }
            closed = true;
            closeLeg(client);
            closeLeg(backend);
        }

        private static void closeLeg(NetSocket socket) {
            socket.close().onFailure(failure ->
                    LOGGER.debug(failure, "Relay leg close failed: %s", failure.getMessage()));
        }
    }
}
