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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayKind;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
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
 * a graceful {@code FIN}, and abort propagation when a leg is closed. Exercised against real Vert.x
 * sockets so the backpressure and close semantics are the production ones.
 */
@DisplayName("PassthroughRelay")
@Tag("isolated-fork")
class PassthroughRelayTest {

    private static final String HOST = LoopbackHost.ADDRESS;
    /** {@link Handler#handle}, invoked reflectively so a chained handler needs no unchecked cast. */
    private static final Method HANDLE = handleMethod();

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
        PassthroughRelay relay = new PassthroughRelay(
                heldDialClient(vertx.createNetClient(), dialRequested, releaseDial), relayedPeers);
        int frontPort = startHarness(accepted -> relay.relay(closeObserved(accepted, relaySawClose),
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

    /**
     * A dial client whose {@code connect(int, String)} — the one the relay calls — signals
     * {@code requested} and dials only once {@code release} completes, on the caller's own context so
     * the relay's callbacks keep running on the accepted connection's event loop. Every other call goes
     * to {@code delegate}.
     */
    private static NetClient heldDialClient(NetClient delegate, CompletableFuture<Void> requested,
            CompletableFuture<Void> release) {
        return (NetClient) Proxy.newProxyInstance(NetClient.class.getClassLoader(), new Class<?>[]{NetClient.class},
                (proxy, method, args) -> {
                    if ("connect".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof Integer port && args[1] instanceof String host) {
                        Context caller = Vertx.currentContext();
                        Promise<NetSocket> dialled = Promise.promise();
                        release.thenRun(() -> caller.runOnContext(v -> delegate.connect(port, host)
                                .onComplete(result -> {
                                    if (result.succeeded()) {
                                        dialled.complete(result.result());
                                    } else {
                                        dialled.fail(result.cause());
                                    }
                                })));
                        requested.complete(null);
                        return dialled.future();
                    }
                    return invokeOn(delegate, method, args);
                });
    }

    /**
     * The accepted connection as the relay sees it, with its close observed: {@code closed} completes
     * when the connection closes, whether or not the relay has installed a close handler of its own —
     * one the relay installs is run first and chained, never replaced.
     */
    private static NetSocket closeObserved(NetSocket accepted, CompletableFuture<Void> closed) {
        accepted.closeHandler(v -> closed.complete(null));
        return (NetSocket) Proxy.newProxyInstance(NetSocket.class.getClassLoader(), new Class<?>[]{NetSocket.class},
                (proxy, method, args) -> {
                    if ("closeHandler".equals(method.getName()) && args != null && args.length == 1) {
                        Object relayHandler = args[0];
                        accepted.closeHandler(v -> {
                            if (relayHandler != null) {
                                invokeOn(relayHandler, HANDLE, new Object[]{v});
                            }
                            closed.complete(null);
                        });
                        return proxy;
                    }
                    return invokeOn(accepted, method, args);
                });
    }

    private static Method handleMethod() {
        try {
            return Handler.class.getMethod("handle", Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Invokes {@code method} on {@code target}, rethrowing what the method itself threw. */
    private static Object invokeOn(Object target, Method method, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
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
}
