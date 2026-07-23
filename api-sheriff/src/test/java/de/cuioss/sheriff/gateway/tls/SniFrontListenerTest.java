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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.tls.ClientHelloSniParserTest.ClientHelloFixture;
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
 * Unit tests for {@link SniFrontListener}'s accept-time routing decision, exercised against real
 * Vert.x sockets: a mapped SNI is relayed opaquely to the passthrough backend, and an empty /
 * non-matching SNI takes the terminated-strict path. Byte fidelity is asserted alongside routing —
 * the backend receives the exact ClientHello the client sent.
 */
@DisplayName("SniFrontListener")
class SniFrontListenerTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String HOST = "localhost";
    private static final String MAPPED_SNI = "api.example.com";

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
    @DisplayName("relays a mapped SNI opaquely to the passthrough backend, byte-for-byte")
    void mappedSniRelaysToPassthroughBackend() throws Exception {
        // Arrange
        byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
        Backend passthrough = startBackend(hello.length);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

        // Act
        sendToFront(front, hello);

        // Assert
        Buffer received = passthrough.firstBytes.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Buffer.buffer(hello), received, "the passthrough backend receives the exact ClientHello");
        await(front.stop());
    }

    @Test
    @DisplayName("routes a non-matching SNI to the terminated-strict path")
    void nonMatchingSniRelaysToTerminatedBackend() throws Exception {
        byte[] hello = ClientHelloFixture.withSni("unmapped.example.net");
        Backend passthrough = startBackend(hello.length);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

        sendToFront(front, hello);

        Buffer received = terminated.firstBytes.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Buffer.buffer(hello), received, "a non-matching SNI is handed to the terminated listener");
        await(front.stop());
    }

    @Test
    @DisplayName("fails an SNI-less ClientHello closed to the terminated-strict path")
    void sniLessHelloFailsClosedToTerminated() throws Exception {
        byte[] hello = ClientHelloFixture.withoutSni();
        Backend passthrough = startBackend(hello.length);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

        sendToFront(front, hello);

        Buffer received = terminated.firstBytes.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Buffer.buffer(hello), received, "a missing SNI fails closed to the terminated path (GW-06)");
        await(front.stop());
    }

    @Test
    @DisplayName("routes every connection to the terminated path when no SNI is mapped")
    void emptyPassthroughMapRelaysEverythingToTerminated() throws Exception {
        byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(), terminated.target());

        sendToFront(front, hello);

        Buffer received = terminated.firstBytes.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Buffer.buffer(hello), received, "an empty passthrough map relays everything terminated");
        await(front.stop());
    }

    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget)
            throws Exception {
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient());
        SniFrontListener front = new SniFrontListener(vertx, relay, targets, terminatedTarget, 0);
        await(front.start());
        return front;
    }

    private void sendToFront(SniFrontListener front, byte[] payload) throws Exception {
        NetSocket client = await(dialClient.connect(front.actualPort(), HOST));
        await(client.write(Buffer.buffer(payload)));
    }

    private Backend startBackend(int expectedBytes) throws Exception {
        CompletableFuture<Buffer> firstBytes = new CompletableFuture<>();
        Buffer accumulator = Buffer.buffer();
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> socket.handler(chunk -> {
            accumulator.appendBuffer(chunk);
            if (accumulator.length() >= expectedBytes && !firstBytes.isDone()) {
                firstBytes.complete(accumulator.copy());
            }
        }));
        int port = await(server.listen(0)).actualPort();
        return new Backend(new RelayTarget(HOST, port), firstBytes);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** A capturing backend server: its endpoint plus the future completed with its first bytes. */
    private record Backend(RelayTarget target, CompletableFuture<Buffer> firstBytes) {
    }
}
