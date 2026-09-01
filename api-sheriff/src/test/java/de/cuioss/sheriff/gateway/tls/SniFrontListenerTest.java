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

import java.util.Map;
import java.util.concurrent.CompletableFuture;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.tls.ClientHelloSniParserTest.ClientHelloFixture;
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
 * Unit tests for {@link SniFrontListener}'s accept-time routing decision, exercised against real
 * Vert.x sockets: a mapped SNI is relayed opaquely to the passthrough backend, and an empty /
 * non-matching SNI takes the terminated-strict path. Byte fidelity is asserted alongside routing —
 * the backend receives the exact ClientHello the client sent.
 */
@DisplayName("SniFrontListener")
class SniFrontListenerTest {

    private static final String HOST = "127.0.0.1";
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
        Awaits.teardown(dialClient.close(), "the dial client to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
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
        Buffer received = Awaits.connect(passthrough.firstBytes,
                "the passthrough backend to receive the relayed bytes");
        assertEquals(Buffer.buffer(hello), received, "the passthrough backend receives the exact ClientHello");
        Awaits.teardown(front.stop(), "the SNI front listener to stop");
    }

    @Test
    @DisplayName("routes a non-matching SNI to the terminated-strict path")
    void nonMatchingSniRelaysToTerminatedBackend() throws Exception {
        byte[] hello = ClientHelloFixture.withSni("unmapped.example.net");
        Backend passthrough = startBackend(hello.length);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

        sendToFront(front, hello);

        Buffer received = Awaits.connect(terminated.firstBytes,
                "the terminated backend to receive the relayed bytes");
        assertEquals(Buffer.buffer(hello), received, "a non-matching SNI is handed to the terminated listener");
        Awaits.teardown(front.stop(), "the SNI front listener to stop");
    }

    @Test
    @DisplayName("fails an SNI-less ClientHello closed to the terminated-strict path")
    void sniLessHelloFailsClosedToTerminated() throws Exception {
        byte[] hello = ClientHelloFixture.withoutSni();
        Backend passthrough = startBackend(hello.length);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

        sendToFront(front, hello);

        Buffer received = Awaits.connect(terminated.firstBytes,
                "the terminated backend to receive the relayed bytes");
        assertEquals(Buffer.buffer(hello), received, "a missing SNI fails closed to the terminated path (GW-06)");
        Awaits.teardown(front.stop(), "the SNI front listener to stop");
    }

    @Test
    @DisplayName("normalizeSni lower-cases, strips whitespace, and removes a single trailing FQDN dot")
    void normalizeSniLowersAndStripsTrailingDot() {
        // Act + Assert — a trailing FQDN dot and case/whitespace are normalized away so lookup and
        // insertion agree; a host without a trailing dot is only lower-cased.
        assertEquals("api.example.com", SniFrontListener.normalizeSni("  API.Example.COM.  "),
                "trailing FQDN dot, case, and surrounding whitespace are normalized");
        assertEquals("api.example.com", SniFrontListener.normalizeSni("API.EXAMPLE.COM"),
                "a host without a trailing dot is only lower-cased");
    }

    @Test
    @DisplayName("routes every connection to the terminated path when no SNI is mapped")
    void emptyPassthroughMapRelaysEverythingToTerminated() throws Exception {
        byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
        Backend terminated = startBackend(hello.length);
        SniFrontListener front = startFront(Map.of(), terminated.target());

        sendToFront(front, hello);

        Buffer received = Awaits.connect(terminated.firstBytes,
                "the terminated backend to receive the relayed bytes");
        assertEquals(Buffer.buffer(hello), received, "an empty passthrough map relays everything terminated");
        Awaits.teardown(front.stop(), "the SNI front listener to stop");
    }

    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget)
            throws Exception {
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient());
        SniFrontListener front = new SniFrontListener(vertx, relay, targets, terminatedTarget, 0);
        Awaits.connect(front.start(), "the SNI front listener to start");
        return front;
    }

    private void sendToFront(SniFrontListener front, byte[] payload) throws Exception {
        NetSocket client = Awaits.connect(dialClient.connect(front.actualPort(), HOST),
                "the client leg to connect to the SNI front");
        Awaits.connect(client.write(Buffer.buffer(payload)), "the ClientHello bytes to be written");
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
        int port = Awaits.connect(server.listen(0), "the capturing backend to start listening").actualPort();
        return new Backend(new RelayTarget(HOST, port), firstBytes);
    }

    /** A capturing backend server: its endpoint plus the future completed with its first bytes. */
    private record Backend(RelayTarget target, CompletableFuture<Buffer> firstBytes) {
    }
}
