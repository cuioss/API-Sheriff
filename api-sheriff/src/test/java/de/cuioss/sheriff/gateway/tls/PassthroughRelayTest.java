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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PassthroughRelay}'s opaque L4 pipe: byte fidelity (including the replayed
 * ClientHello prefix), integrity under backpressure with a large payload, half-close propagation on
 * a graceful {@code FIN}, and abort propagation when a leg is closed. Exercised against real Vert.x
 * sockets so the backpressure and close semantics are the production ones.
 */
@DisplayName("PassthroughRelay")
class PassthroughRelayTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String HOST = "localhost";

    private Vertx vertx;
    private NetClient dialClient;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        dialClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(dialClient.close());
        await(vertx.close());
    }

    @Test
    @DisplayName("replays the buffered ClientHello prefix then relays subsequent bytes, in order")
    void relaysPrefixThenLiveBytes() throws Exception {
        // Arrange
        RelayTarget backend = startEchoBackend();
        int frontPort = startRelayHarness(backend, Buffer.buffer("PREFIX"));
        byte[] expected = "PREFIXDATA".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CompletableFuture<Buffer> echoed = new CompletableFuture<>();

        // Act
        NetSocket client = await(dialClient.connect(frontPort, HOST));
        accumulateUntil(client, expected.length, echoed);
        await(client.write(Buffer.buffer("DATA")));

        // Assert
        assertEquals(Buffer.buffer(expected), echoed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
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
        NetSocket client = await(dialClient.connect(frontPort, HOST));
        accumulateUntil(client, payload.length, echoed);
        await(client.write(Buffer.buffer(payload)));

        // Assert
        assertEquals(Buffer.buffer(payload), echoed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
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
        NetSocket client = await(dialClient.connect(frontPort, HOST));
        await(client.end());

        // Assert
        assertNull(backendEnded.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
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
        NetSocket client = await(dialClient.connect(frontPort, HOST));
        await(client.write(Buffer.buffer("x")));
        await(client.close());

        // Assert
        assertNull(backendClosed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
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
        return await(harness.listen(0)).actualPort();
    }

    private RelayTarget startEchoBackend() throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> socket.handler(socket::write));
        int port = await(server.listen(0)).actualPort();
        return new RelayTarget(HOST, port);
    }

    private RelayTarget startSignalBackend(java.util.function.Consumer<NetSocket> wiring) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(wiring::accept);
        int port = await(server.listen(0)).actualPort();
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
