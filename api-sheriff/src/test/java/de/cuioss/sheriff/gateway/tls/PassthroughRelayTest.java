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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayKind;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PassthroughRelay}'s opaque L4 pipe: byte fidelity (including the replayed
 * ClientHello prefix), integrity under backpressure with a large payload, half-close propagation on
 * a graceful {@code FIN}, abort propagation when a leg is closed, and the bounded, reported dial
 * phase. Exercised against real Vert.x sockets so the backpressure, close and connect-timeout
 * semantics are the production ones.
 */
@DisplayName("PassthroughRelay")
@Tag("isolated-fork")
class PassthroughRelayTest {

    private static final String HOST = LoopbackHost.ADDRESS;
    /** An idle timeout no case here can reach. */
    private static final Duration LONG_IDLE = Duration.ofMinutes(31);
    /** The shortened connect timeout standing in for {@link TlsEdgeProducer#RELAY_CONNECT_TIMEOUT}. */
    private static final Duration DIAL_TIMEOUT = Duration.ofMillis(500);
    /** How much later than {@link #DIAL_TIMEOUT} a loaded machine may report the abandoned dial. */
    private static final Duration DIAL_SLACK = Duration.ofSeconds(4);

    private final RelayedPeerRegistry relayedPeers = new RelayedPeerRegistry();
    private Vertx vertx;
    private NetClient dialClient;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        dialClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(dialClient.close(), "the dial client to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("replays the buffered ClientHello prefix then relays subsequent bytes, in order")
    void relaysPrefixThenLiveBytes() throws Exception {
        // Arrange
        RelayTarget backend = startEchoBackend();
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"));
        byte[] expected = "PREFIXDATA".getBytes(StandardCharsets.US_ASCII);
        CompletableFuture<Buffer> echoed = new CompletableFuture<>();

        // Act — the echoed prefix can arrive before any byte is written, so the accumulator is wired
        // in the connect callback rather than after the connect returns
        NetSocket client = connectClient(frontPort, socket -> accumulateUntil(socket, expected.length, echoed));
        Awaits.connect(client.write(Buffer.buffer("DATA")), "the live bytes to be written");

        // Assert
        assertEquals(Buffer.buffer(expected), Awaits.connect(echoed, "the relayed bytes to arrive"),
                "the buffered prefix is replayed before the live bytes, byte-for-byte");
    }

    @Test
    @DisplayName("relays a large payload intact under write-queue backpressure")
    void relaysLargePayloadIntactUnderBackpressure() throws Exception {
        // Arrange
        RelayTarget backend = startEchoBackend();
        int frontPort = startRelayHarness(backend, Buffer.buffer());
        byte[] payload = new byte[1024 * 1024];
        new Random(42).nextBytes(payload);
        CompletableFuture<Buffer> echoed = new CompletableFuture<>();

        // Act
        NetSocket client = connectClient(frontPort, socket -> accumulateUntil(socket, payload.length, echoed));
        Awaits.connect(client.write(Buffer.buffer(payload)), "the 1 MiB payload to be written");

        // Assert
        assertEquals(Buffer.buffer(payload), Awaits.connect(echoed, "the relayed bytes to arrive"),
                "1 MiB round-trips intact, so pause/resume backpressure preserves every byte");
    }

    @Test
    @DisplayName("propagates a graceful client FIN as a half-close to the backend")
    void propagatesHalfCloseToBackend() throws Exception {
        // Arrange
        CompletableFuture<Void> backendEnded = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> socket.endHandler(v -> backendEnded.complete(null)));
        int frontPort = startRelayHarness(backend, Buffer.buffer());

        // Act
        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");
        Awaits.connect(client.end(), "the client FIN to be written");

        // Assert
        assertNull(Awaits.connect(backendEnded, "the half-close to reach the backend"),
                "the client FIN is propagated as a half-close to the backend");
    }

    @Test
    @DisplayName("aborts the backend leg when the client connection is closed")
    void propagatesAbortToBackend() throws Exception {
        // Arrange
        CompletableFuture<Void> backendClosed = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> socket.closeHandler(v -> backendClosed.complete(null)));
        int frontPort = startRelayHarness(backend, Buffer.buffer());

        // Act
        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");
        Awaits.connect(client.write(Buffer.buffer("x")), "the probe byte to be written");
        Awaits.teardown(client.close(), "the client leg to close");

        // Assert
        assertNull(Awaits.connect(backendClosed, "the abort to reach the backend"),
                "closing the client leg aborts the backend leg");
    }

    @Test
    @DisplayName("a terminated relay names its client for the backend leg before the first byte reaches the backend")
    void terminatedRelayRegistersBeforeTheFirstByte() throws Exception {
        CompletableFuture<Optional<String>> atFirstByte = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> socket.handler(chunk -> {
            if (!atFirstByte.isDone()) {
                atFirstByte.complete(relayedPeers.clientOf(socket.remoteAddress()));
            }
        }));
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"));

        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");

        assertEquals(Optional.of(client.localAddress().hostAddress()),
                Awaits.connect(atFirstByte, "the replayed ClientHello to reach the backend"),
                "the backend leg already names the client's address when its first byte arrives");
    }

    @Test
    @DisplayName("a terminated relay releases its entry when the client leg closes")
    void terminatedRelayReleasesOnClientClose() throws Exception {
        CompletableFuture<Void> backendClosed = new CompletableFuture<>();
        CompletableFuture<Integer> registeredAtFirstByte = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> {
            socket.handler(chunk -> registeredAtFirstByte.complete(relayedPeers.size()));
            socket.closeHandler(v -> backendClosed.complete(null));
        });
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"));

        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");
        int registered = Awaits.connect(registeredAtFirstByte, "the replayed ClientHello to reach the backend");
        Awaits.teardown(client.close(), "the client leg to close");
        Awaits.connect(backendClosed, "the abort to reach the backend");

        assertAll("the entry lives exactly as long as the relay session",
                () -> assertEquals(1, registered, "precondition: the session was registered"),
                () -> assertEquals(0, relayedPeers.size(), "closing the client leg releases the entry"));
    }

    @Test
    @DisplayName("a terminated relay releases its entry when the backend leg closes")
    void terminatedRelayReleasesOnBackendClose() throws Exception {
        CompletableFuture<Integer> registeredAtFirstByte = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> socket.handler(chunk -> {
            registeredAtFirstByte.complete(relayedPeers.size());
            socket.close();
        }));
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"));
        CompletableFuture<Void> clientClosed = new CompletableFuture<>();

        // The backend closes both legs as soon as the replayed prefix arrives, which can be before the
        // connect returns here, so the close observer is wired in the connect callback
        connectClient(frontPort, socket -> socket.closeHandler(v -> clientClosed.complete(null)));
        int registered = Awaits.connect(registeredAtFirstByte, "the replayed ClientHello to reach the backend");
        Awaits.connect(clientClosed, "the backend's close to reach the client");

        assertAll("the entry lives exactly as long as the relay session",
                () -> assertEquals(1, registered, "precondition: the session was registered"),
                () -> assertEquals(0, relayedPeers.size(), "closing the backend leg releases the entry"));
    }

    @Test
    @DisplayName("a passthrough relay names no client")
    void passthroughRelayRegistersNothing() throws Exception {
        CompletableFuture<Optional<String>> atFirstByte = new CompletableFuture<>();
        RelayTarget backend = startSignalBackend(socket -> socket.handler(chunk -> {
            if (!atFirstByte.isDone()) {
                atFirstByte.complete(relayedPeers.clientOf(socket.remoteAddress()));
            }
        }));
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"), RelayKind.PASSTHROUGH);

        Awaits.connect(dialClient.connect(frontPort, HOST), "the client leg to connect to the relay front");

        assertAll("the gateway never terminates a passthrough connection, so it hands nothing over",
                () -> assertEquals(Optional.empty(),
                        Awaits.connect(atFirstByte, "the replayed ClientHello to reach the backend")),
                () -> assertEquals(0, relayedPeers.size()));
    }

    /**
     * A client that leaves while the backend dial is pending. The dial is held until the relay's side
     * of the client connection has seen the close, and only then completes — the order in which a
     * session that attached its close handler only once established would miss the close.
     * <p>
     * Such a late session would still register the departed client and replay its ClientHello to the
     * backend before anything ended it; that the backend leg is closed having received no byte is what
     * shows no session was started at all. The registry is read once the backend leg is closed.
     */
    @Test
    @DisplayName("a client that leaves during the backend dial gets no session: no entry, no replayed byte, the backend leg closed")
    void clientGoneDuringTheDialLeavesNothingBehind() throws Exception {
        CompletableFuture<Void> backendAccepted = new CompletableFuture<>();
        CompletableFuture<Void> backendClosed = new CompletableFuture<>();
        Buffer backendReceived = Buffer.buffer();
        RelayTarget backend = startSignalBackend(socket -> {
            socket.handler(backendReceived::appendBuffer);
            socket.closeHandler(v -> backendClosed.complete(null));
            backendAccepted.complete(null);
        });
        CompletableFuture<Void> dialRequested = new CompletableFuture<>();
        CompletableFuture<Void> releaseDial = new CompletableFuture<>();
        CompletableFuture<Void> relaySawClose = new CompletableFuture<>();
        PassthroughRelay relay = new PassthroughRelay(RelayTestProxies.heldDialClient(vertx.createNetClient(),
                () -> dialRequested.complete(null), releaseDial), relayedPeers);
        int frontPort = startHarness(accepted -> relay.relay(RelayTestProxies.closeObserved(accepted, relaySawClose),
                Buffer.buffer("PREFIX"), backend, RelayKind.TERMINATED, ""));

        NetSocket client = connectClient(frontPort, socket -> {
        });
        Awaits.connect(dialRequested, "the relay to start dialling the backend");
        Awaits.teardown(client.close(), "the client leg to close");
        Awaits.connect(relaySawClose, "the relay's side of the client connection to see the close");
        releaseDial.complete(null);
        Awaits.connect(backendClosed, "the backend leg dialled for the departed client to be closed");

        assertAll("nothing outlives a client that left during the dial",
                () -> assertTrue(backendAccepted.isDone(), "precondition: the dial reached the backend"),
                () -> assertEquals(0, backendReceived.length(),
                        "the departed client's ClientHello is never replayed, so no session was started"),
                () -> assertEquals(0, relayedPeers.size(), "no relayed-peer entry remains"));
    }

    /**
     * A backend that never completes the TCP handshake. The relay's dial must be abandoned by the dial
     * client's connect timeout, and closing the client is the only correct reaction; the wider bound
     * is the Awaits ceiling, far below the Vert.x default of sixty seconds the connect timeout replaces.
     * <p>
     * The client is closed not before the connect timeout, which is what tells a timed-out dial from a
     * refused one: a dial to a port nothing listens on fails at once with a reset, the wrong signal for
     * this bound.
     */
    @Test
    @DisplayName("abandons a dial to a backend that never answers within the connect timeout and closes the client")
    void abandonsANeverAnsweringDialWithinTheConnectTimeout() throws Exception {
        try (NeverAnsweringBackend backend = NeverAnsweringBackend.start()) {
            PassthroughRelay relay = new PassthroughRelay(
                    vertx.createNetClient(TlsEdgeProducer.relayClientOptions(LONG_IDLE, DIAL_TIMEOUT)), relayedPeers);
            CompletableFuture<Future<Void>> dialPhase = new CompletableFuture<>();
            int frontPort = startHarness(accepted -> dialPhase.complete(relay.relay(accepted,
                    Buffer.buffer("PREFIX"), backend.target(), RelayKind.TERMINATED, "")));
            CompletableFuture<Long> clientClosed = new CompletableFuture<>();
            long startedNanos = System.nanoTime();

            connectClient(frontPort, socket -> socket.closeHandler(v -> clientClosed.complete(System.nanoTime())));
            long closedAt = Awaits.connect(clientClosed, "the relay to give up the dial and close the client");
            Future<Void> phase = Awaits.connect(dialPhase, "the relay to hand back its dial-phase future");
            Awaits.connect(phase, "the dial phase to be over once the dial timed out");

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(closedAt - startedNanos);
            assertAll("the dial is bounded by the connect timeout, and only by it",
                    () -> assertTrue(elapsedMillis >= DIAL_TIMEOUT.toMillis(),
                            "the client is closed by the timed-out dial, not by a refused one: closed after "
                                    + elapsedMillis + " ms"),
                    () -> assertTrue(elapsedMillis < DIAL_TIMEOUT.plus(DIAL_SLACK).toMillis(),
                            "the dial is abandoned within the connect timeout: closed after " + elapsedMillis + " ms"),
                    () -> assertEquals(0, relayedPeers.size(), "a dial that never connected registers nothing"));
        }
    }

    /**
     * The dial-phase future reports the client leaving at once: it is complete as soon as the relay's
     * side of the client connection has seen the close, while the backend dial is still held. The
     * matched control reads it incomplete while the client is connected and the dial pending.
     */
    @Test
    @DisplayName("ends the dial phase as soon as the client leaves, before the backend dial completes")
    void dialPhaseEndsAsSoonAsTheClientLeaves() throws Exception {
        RelayTarget backend = startSignalBackend(socket -> {
        });
        CompletableFuture<Void> dialRequested = new CompletableFuture<>();
        CompletableFuture<Void> neverReleased = new CompletableFuture<>();
        CompletableFuture<Void> relaySawClose = new CompletableFuture<>();
        PassthroughRelay relay = new PassthroughRelay(RelayTestProxies.heldDialClient(vertx.createNetClient(),
                () -> dialRequested.complete(null), neverReleased), relayedPeers);
        CompletableFuture<Future<Void>> dialPhase = new CompletableFuture<>();
        int frontPort = startHarness(accepted -> dialPhase.complete(relay.relay(
                RelayTestProxies.closeObserved(accepted, relaySawClose), Buffer.buffer("PREFIX"), backend,
                RelayKind.TERMINATED, "")));

        NetSocket client = connectClient(frontPort, socket -> {
        });
        Awaits.connect(dialRequested, "the relay to start dialling the backend");
        Future<Void> phase = Awaits.connect(dialPhase, "the relay to hand back its dial-phase future");
        boolean overWhileDialling = phase.isComplete();
        Awaits.teardown(client.close(), "the client leg to close");
        Awaits.connect(relaySawClose, "the relay's side of the client connection to see the close");
        boolean overOnceTheClientLeft = phase.isComplete();

        assertAll("the dial phase ends with the client, not with the dial",
                () -> assertFalse(overWhileDialling,
                        "the dial phase is not over while the client is connected and the dial pending"),
                () -> assertTrue(overOnceTheClientLeft,
                        "the dial phase is over as soon as the relay has seen the client leave"),
                () -> assertTrue(phase.succeeded(), "the dial-phase future never fails"));
    }

    /**
     * Connects the client leg from an event-loop context and wires it in the connect callback, in the
     * same event-loop task that completes the connect. No event of the socket — bytes the relay sends
     * first, or a close — can be delivered before {@code wiring} has run.
     */
    private NetSocket connectClient(int frontPort, Consumer<NetSocket> wiring) throws Exception {
        CompletableFuture<NetSocket> connected = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(v -> dialClient.connect(frontPort, HOST)
                .onSuccess(wiring::accept)
                .onComplete(result -> {
                    if (result.succeeded()) {
                        connected.complete(result.result());
                    } else {
                        connected.completeExceptionally(result.cause());
                    }
                }));
        return Awaits.connect(connected, "the client leg to connect to the relay front");
    }

    /** Starts a harness front server handing every accepted, paused connection to {@code onAccept}. */
    private int startHarness(Consumer<NetSocket> onAccept) throws Exception {
        NetServer harness = vertx.createNetServer();
        harness.connectHandler(accepted -> {
            accepted.pause();
            onAccept.accept(accepted);
        });
        return Awaits.connect(harness.listen(0, LoopbackHost.ADDRESS),
                "the relay harness front to start listening").actualPort();
    }

    /**
     * Starts a harness front server whose accepted connection is handed to the relay as a terminated
     * hand-off, targeting the given backend with the given buffered prefix. Returns the front's bound
     * port.
     */
    private int startRelayHarness(RelayTarget backend, Buffer prefix) throws Exception {
        return startRelayHarness(backend, prefix, RelayKind.TERMINATED);
    }

    /** As {@link #startRelayHarness(RelayTarget, Buffer)}, relaying as {@code kind}. */
    private int startRelayHarness(RelayTarget backend, Buffer prefix, RelayKind kind) throws Exception {
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient(), relayedPeers);
        return startHarness(accepted -> relay.relay(accepted, prefix, backend, kind,
                kind == RelayKind.PASSTHROUGH ? "api.example.com" : ""));
    }

    private RelayTarget startEchoBackend() throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> socket.handler(socket::write));
        int port = Awaits.connect(server.listen(0, LoopbackHost.ADDRESS),
                "the backend server to start listening").actualPort();
        return new RelayTarget(HOST, port);
    }

    private RelayTarget startSignalBackend(Consumer<NetSocket> wiring) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(wiring::accept);
        int port = Awaits.connect(server.listen(0, LoopbackHost.ADDRESS),
                "the backend server to start listening").actualPort();
        return new RelayTarget(HOST, port);
    }

    private static void accumulateUntil(NetSocket socket, int expectedBytes, CompletableFuture<Buffer> done) {
        Buffer accumulator = Buffer.buffer();
        socket.handler(chunk -> {
            accumulator.appendBuffer(chunk);
            if (accumulator.length() >= expectedBytes && !done.isDone()) {
                done.complete(accumulator.copy());
            }
        });
    }

    /**
     * A loopback port whose kernel drops the SYN of a dial, so the dial never completes the TCP
     * handshake and nothing ever answers it.
     * <p>
     * No single mechanism does this on every platform, and the two that do it each do it on one:
     * <ul>
     * <li>a socket bound to the port that never listens — macOS drops the SYN, Linux resets it;</li>
     * <li>a listener whose accept queue is full and that never accepts — Linux drops the SYN, macOS
     * resets it.</li>
     * </ul>
     * The fixture therefore chooses by measurement rather than by platform name: it builds each in
     * turn and keeps the first whose own probe dial times out after {@link #PROBE_TIMEOUT_MILLIS}
     * instead of being reset, and it fails loudly when neither does. The accept queue is filled by
     * the same probing — connections are queued until one is dropped — so the fixture does not depend
     * on how a platform sizes the queue for a given backlog.
     * <p>
     * Deliberately not {@link de.cuioss.sheriff.gateway.testsupport.UnreachablePort}: a port nothing is
     * bound to answers the dial with a reset at once on every platform, the wrong signal for a connect
     * timeout.
     */
    private static final class NeverAnsweringBackend implements AutoCloseable {

        private static final int BACKLOG = 1;
        private static final int PROBE_TIMEOUT_MILLIS = 500;
        /** Far beyond any platform's queue for a backlog of one; reaching it means the queue never filled. */
        private static final int MAX_QUEUED = 64;

        private final int port;
        private final List<Closeable> held;

        private NeverAnsweringBackend(int port, List<Closeable> held) {
            this.port = port;
            this.held = held;
        }

        static NeverAnsweringBackend start() throws IOException {
            InetAddress loopback = InetAddress.getByName(HOST);
            NeverAnsweringBackend unlistened = boundButNotListening(loopback);
            if (unlistened.dropsADial()) {
                return unlistened;
            }
            unlistened.close();
            return listeningWithAFullAcceptQueue(loopback).orElseThrow(() -> new IllegalStateException(
                    "Neither a bound socket that does not listen nor a full accept queue drops a loopback dial "
                            + "on this platform; both answered the probe dial with a reset"));
        }

        RelayTarget target() {
            return new RelayTarget(HOST, port);
        }

        private static NeverAnsweringBackend boundButNotListening(InetAddress loopback) throws IOException {
            Socket holder = new Socket();
            try {
                holder.setReuseAddress(false);
                holder.bind(new InetSocketAddress(loopback, 0));
            } catch (IOException e) {
                holder.close();
                throw e;
            }
            return new NeverAnsweringBackend(holder.getLocalPort(), new ArrayList<>(List.of(holder)));
        }

        /**
         * Queues connections on a listener that never accepts until the kernel drops one.
         *
         * @return the backend, or empty when the kernel resets a dial to the full queue instead
         */
        private static Optional<NeverAnsweringBackend> listeningWithAFullAcceptQueue(InetAddress loopback)
                throws IOException {
            ServerSocket listener = new ServerSocket(0, BACKLOG, loopback);
            NeverAnsweringBackend backend = new NeverAnsweringBackend(listener.getLocalPort(),
                    new ArrayList<>(List.of(listener)));
            try {
                for (int attempt = 0; attempt < MAX_QUEUED; attempt++) {
                    Socket queued = new Socket();
                    backend.held.add(queued);
                    switch (backend.dial(queued)) {
                        case CONNECTED -> {
                            // queued and never accepted: the queue is one connection fuller
                        }
                        case DROPPED -> {
                            return Optional.of(backend);
                        }
                        case RESET -> {
                            backend.close();
                            return Optional.empty();
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                backend.close();
                throw e;
            }
            backend.close();
            throw new IllegalStateException("The accept queue of a listener with backlog " + BACKLOG
                    + " still accepted after " + MAX_QUEUED + " connections");
        }

        private boolean dropsADial() throws IOException {
            try (Socket probe = new Socket()) {
                return dial(probe) == DialOutcome.DROPPED;
            }
        }

        private DialOutcome dial(Socket socket) throws IOException {
            try {
                socket.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MILLIS);
                return DialOutcome.CONNECTED;
            } catch (SocketTimeoutException dropped) {
                socket.close();
                return DialOutcome.DROPPED;
            } catch (IOException reset) {
                socket.close();
                return DialOutcome.RESET;
            }
        }

        @Override
        public void close() throws IOException {
            for (Closeable resource : held.reversed()) {
                resource.close();
            }
        }

        private enum DialOutcome {
            CONNECTED, DROPPED, RESET
        }
    }
}
