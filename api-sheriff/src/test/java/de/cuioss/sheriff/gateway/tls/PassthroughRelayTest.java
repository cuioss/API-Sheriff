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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayKind;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PassthroughRelay}'s opaque L4 pipe: byte fidelity (including the replayed
 * ClientHello prefix), integrity under backpressure with a large payload, half-close propagation on
 * a graceful {@code FIN}, and abort propagation when a leg is closed. Exercised against real Vert.x
 * sockets so the backpressure and close semantics are the production ones.
 */
@DisplayName("PassthroughRelay")
class PassthroughRelayTest {

    private static final String HOST = "127.0.0.1";

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

        // Act
        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");
        accumulateUntil(client, expected.length, echoed);
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
        NetSocket client = Awaits.connect(dialClient.connect(frontPort, HOST),
                "the client leg to connect to the relay front");
        accumulateUntil(client, payload.length, echoed);
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

    /**
     * Starts a harness front server whose accepted connection is handed to the relay, targeting the
     * given backend with the given buffered prefix. Returns the front's bound port.
     */
    private int startRelayHarness(RelayTarget backend, Buffer prefix) throws Exception {
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient());
        NetServer harness = vertx.createNetServer();
        harness.connectHandler(accepted -> {
            accepted.pause();
            relay.relay(accepted, prefix, backend, RelayKind.TERMINATED, "");
        });
        return Awaits.connect(harness.listen(0), "the relay harness front to start listening").actualPort();
    }

    private RelayTarget startEchoBackend() throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> socket.handler(socket::write));
        int port = Awaits.connect(server.listen(0), "the backend server to start listening").actualPort();
        return new RelayTarget(HOST, port);
    }

    private RelayTarget startSignalBackend(Consumer<NetSocket> wiring) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(wiring::accept);
        int port = Awaits.connect(server.listen(0), "the backend server to start listening").actualPort();
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
