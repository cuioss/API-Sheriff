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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.jspecify.annotations.Nullable;

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
 * <strong>Both legs are bounded by the derived relay idle timeout.</strong> The client leg inherits it
 * from the {@link SniFrontListener}'s server and the backend leg from the dial {@link NetClient}; both
 * carry the value {@link TlsEdgeProducer} derives at boot. When a leg carries no byte in either
 * direction for that long, Vert.x closes it, and the close handlers below propagate the close to the
 * other leg, so an abandoned pipe is released on both sides. The relay itself sets no timer.
 * <p>
 * <strong>The dial phase is bounded and reported.</strong> The TCP connect to the backend is bounded by
 * the dial client's connect timeout, {@link TlsEdgeProducer#RELAY_CONNECT_TIMEOUT}. A target named by
 * host name is resolved before the connect starts; that lookup is bounded by the DNS resolver's own
 * query timeout, not by the connect timeout. {@link #relay} returns a future that completes exactly
 * once, when the dial settles — the relay is established, or the dial failed or timed out — so the
 * {@link SniFrontListener} can hold the connection's undecided slot until then. It completes on every
 * path: the completion runs in a {@code finally} of both dial outcomes, so a session that fails to
 * start, or a close that throws, still ends the dial phase and lets the listener return the slot; a
 * session that fails to start closes both legs. A client that leaves
 * during the dial does not end the dial phase: the dial keeps running and the connection keeps its slot
 * until the dial settles, so every dialling connection is bounded by the lookup and the connect timeout
 * whether or not its client is still connected. The client leaving releases only the
 * buffered ClientHello, which the dial phase holds alone and drops the moment the client is known
 * gone, not when the dial later settles.
 * <p>
 * The same pipe backs both branches of the {@link SniFrontListener}: a {@code passthrough_sni} match
 * relays to the topology-resolved backend, and a terminated (non-passthrough) connection relays to
 * the internal Quarkus HTTPS listener — the only difference is the target and the lifecycle log
 * level.
 * <p>
 * <strong>A terminated relay hands the client's address over.</strong> The internal listener sees
 * every terminated connection arrive from the relay's loopback leg, so a terminated relay registers
 * that leg's local socket address against the client's address in the {@link RelayedPeerRegistry}
 * once the backend dial succeeded and before the first byte — the buffered ClientHello — is written
 * to it, so no request can reach the internal listener on that leg without its entry. The entry is
 * released when the session closes on either leg, an abort included. A client that is already gone
 * when the dial succeeds gets no session at all, so no entry is registered for it and the backend leg
 * is closed at once. A passthrough relay registers nothing: the gateway never sees a request on it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PassthroughRelay {

    private static final CuiLogger LOGGER = new CuiLogger(PassthroughRelay.class);

    private final NetClient netClient;
    private final RelayedPeerRegistry relayedPeers;
    private final Runnable beforeSessionStart;

    /**
     * @param netClient    the shared plain-TCP client the relay dials the backend with (no TLS), carrying
     *                     the derived relay idle timeout
     * @param relayedPeers the registry a terminated relay hands its client's address over through
     */
    public PassthroughRelay(NetClient netClient, RelayedPeerRegistry relayedPeers) {
        this(netClient, relayedPeers, () -> {
        });
    }

    /**
     * Test seam: a relay that runs {@code beforeSessionStart} on the event loop just before a session is
     * wired, after a successful dial, so a test can make the session start throw. Production uses the
     * public constructor, which runs nothing there.
     *
     * @param netClient          the plain-TCP client the relay dials the backend with
     * @param relayedPeers       the registry a terminated relay hands its client's address over through
     * @param beforeSessionStart run before every session is wired
     */
    PassthroughRelay(NetClient netClient, RelayedPeerRegistry relayedPeers, Runnable beforeSessionStart) {
        this.netClient = Objects.requireNonNull(netClient, "netClient");
        this.relayedPeers = Objects.requireNonNull(relayedPeers, "relayedPeers");
        this.beforeSessionStart = Objects.requireNonNull(beforeSessionStart, "beforeSessionStart");
    }

    /**
     * Dials the target and, on success, replays the buffered ClientHello and establishes the opaque
     * bidirectional pipe. On a dial failure — the connect timeout included — the accepted client
     * connection is closed (abort, never a hang).
     * <p>
     * The client's close is observed from before the dial: a client that left while the dial was
     * pending gets no session — the buffered ClientHello is dropped at once, the dialled backend leg is
     * closed as soon as the dial succeeds and, for a terminated relay, no relayed-peer entry is ever
     * registered.
     * <p>
     * The returned future completes exactly once, when the dial settles: it succeeded — the relay is
     * established, or, for a client already gone, the backend leg closed — or it failed or timed out.
     * The completion runs in a {@code finally} of both dial outcomes, so the future completes on every
     * path, a session that fails to start included; such a session closes both legs. The client leaving
     * does not complete it, so the caller's hold on the connection spans the whole
     * dial: the host-name lookup of a target named by host name, bounded by the DNS resolver's own query
     * timeout, and the TCP connect, bounded by the dial client's connect timeout. The client's close and
     * the dial callbacks all run on the accepted connection's event loop.
     *
     * @param client      the accepted client connection, already paused by the caller
     * @param clientHello the bytes buffered while the SNI was being read, replayed to the backend
     *                    first so the handshake is byte-identical; the caller hands them over and must
     *                    not keep a reference
     * @param target      the resolved backend endpoint to relay to
     * @param kind        whether the connection is a passthrough match or a terminated hand-off,
     *                    controlling the lifecycle log level
     * @param sniLabel    the matched SNI hostname for a passthrough relay's audit log ({@code kind ==
     *                    PASSTHROUGH}); ignored for a terminated relay
     * @return a future that completes once the backend dial has settled; it never fails
     */
    public Future<Void> relay(NetSocket client, Buffer clientHello, RelayTarget target, RelayKind kind,
            String sniLabel) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(clientHello, "clientHello");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sniLabel, "sniLabel");
        Promise<Void> dialPhase = Promise.promise();
        // The single owner of the buffered ClientHello during the dial: the dial success takes it, a
        // client that leaves first drops it. The close handler is replaced by the session's own once the
        // session starts; both run on the accepted connection's event loop, as the dial callbacks do, so
        // an empty owner at the dial success means the client is gone.
        AtomicReference<@Nullable Buffer> pendingHello = new AtomicReference<>(clientHello);
        client.closeHandler(v -> {
            if (pendingHello.getAndSet(null) != null) {
                LOGGER.debug("Relay client left during the backend dial to %s — ClientHello dropped, "
                        + "the dial is held until it settles", target);
            }
        });
        netClient.connect(target.port(), target.host())
                .onSuccess(backend -> {
                    try {
                        Buffer hello = pendingHello.getAndSet(null);
                        if (hello == null) {
                            LOGGER.debug("Relay client left during the backend dial to %s — closing the backend leg",
                                    target);
                            closeQuietly(backend);
                        } else {
                            establish(client, backend, hello, target, kind, sniLabel);
                        }
                    } finally {
                        dialPhase.complete();
                    }
                })
                .onFailure(failure -> {
                    try {
                        LOGGER.debug(failure, "Relay backend dial to %s failed: %s", target, failure.getMessage());
                        pendingHello.set(null);
                        closeQuietly(client);
                    } finally {
                        dialPhase.complete();
                    }
                });
        return dialPhase.future();
    }

    /**
     * Starts the session over both legs. A session that fails to start — its hand-over, its wiring or its
     * replay throws — is closed at once: both legs are closed and a relayed-peer entry already registered
     * is released, so nothing outlives the failed start.
     */
    private void establish(NetSocket client, NetSocket backend, Buffer clientHello, RelayTarget target,
            RelayKind kind, String sniLabel) {
        @Nullable RelaySession session = null;
        try {
            Runnable release = kind == RelayKind.PASSTHROUGH ? () -> {
            } : handOver(client, backend);
            session = new RelaySession(client, backend, release);
            beforeSessionStart.run();
            session.start(clientHello);
        } catch (RuntimeException failure) {
            LOGGER.debug(failure, "Relay session to %s failed to start, closing both legs: %s", target,
                    failure.getMessage());
            if (session != null) {
                session.closeBoth();
            } else {
                closeQuietly(client);
                closeQuietly(backend);
            }
            return;
        }
        if (kind == RelayKind.PASSTHROUGH) {
            LOGGER.info(ApiSheriffLogMessages.INFO.PASSTHROUGH_RELAY_ESTABLISHED, sniLabel, target.toString());
        } else {
            LOGGER.debug("Terminated relay established to %s", target);
        }
    }

    /**
     * Registers the backend leg's local address against the client's address, before the session
     * writes its first byte to the backend.
     *
     * @return the action that releases the entry when the session ends
     */
    private Runnable handOver(NetSocket client, NetSocket backend) {
        SocketAddress clientAddress = client.remoteAddress();
        SocketAddress relayLeg = backend.localAddress();
        if (clientAddress == null || relayLeg == null) {
            return () -> {
            };
        }
        String address = clientAddress.hostAddress() != null ? clientAddress.hostAddress() : clientAddress.host();
        return relayedPeers.register(relayLeg, address)::release;
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
        /** Releases the session's relayed-peer entry; a no-op for a passthrough relay. */
        private final Runnable release;
        private boolean closed;

        RelaySession(NetSocket client, NetSocket backend, Runnable release) {
            this.client = client;
            this.backend = backend;
            this.release = release;
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
            release.run();
            closeLeg(client);
            closeLeg(backend);
        }

        private static void closeLeg(NetSocket socket) {
            socket.close().onFailure(failure ->
                    LOGGER.debug(failure, "Relay leg close failed: %s", failure.getMessage()));
        }
    }
}
