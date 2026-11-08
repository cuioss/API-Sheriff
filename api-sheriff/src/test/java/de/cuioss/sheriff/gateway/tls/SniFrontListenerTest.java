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

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.ClientHelloSniParserTest.ClientHelloFixture;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SniFrontListener}, exercised against real Vert.x sockets: the accept-time
 * routing decision — a mapped SNI is relayed opaquely to the passthrough backend, an empty /
 * non-matching SNI takes the terminated-strict path, and the backend receives the exact ClientHello
 * the client sent — and the three bounds of the front: the ClientHello deadline, the
 * undecided-connection cap and the relay idle timeout.
 * <p>
 * The bounds are shortened through the package-private constructor so each case observes its bound
 * within a fraction of a second instead of waiting out the production values. Each bound case is
 * paired with a matched control that shows the bound does not fire where it must not: a ClientHello
 * dripped slowly but within the deadline is still routed, a slot freed by a decided connection admits
 * a new one, and a relay that keeps carrying traffic outlives its idle timeout.
 */
@DisplayName("SniFrontListener")
@Tag("isolated-fork")
class SniFrontListenerTest {

    private static final String HOST = LoopbackHost.ADDRESS;
    private static final String MAPPED_SNI = "api.example.com";
    private static final String SECOND_MAPPED_SNI = "second.example.com";

    /** An idle timeout long enough that no case outside {@link IdleTimeout} can ever reach it. */
    private static final Duration LONG_IDLE = Duration.ofMinutes(31);

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

    @Nested
    @DisplayName("routing")
    class Routing {

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
            Buffer received = Awaits.connect(passthrough.firstBytes(),
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

            Buffer received = Awaits.connect(terminated.firstBytes(),
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

            Buffer received = Awaits.connect(terminated.firstBytes(),
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

            Buffer received = Awaits.connect(terminated.firstBytes(),
                    "the terminated backend to receive the relayed bytes");
            assertEquals(Buffer.buffer(hello), received, "an empty passthrough map relays everything terminated");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }
    }

    @Nested
    @DisplayName("ClientHello deadline")
    class ClientHelloDeadline {

        private static final Duration DEADLINE = Duration.ofMillis(400);

        @Test
        @DisplayName("closes a connection that sends nothing once the deadline passes")
        void closesSilentConnection() throws Exception {
            // Arrange
            Backend terminated = startBackend(1);
            SniFrontListener front = startFront(Map.of(), terminated.target(), DEADLINE,
                    SniFrontListener.MAX_UNDECIDED_CONNECTIONS, LONG_IDLE);

            // Act — connect and send nothing at all
            ClientLeg client = connectClient(front);

            // Assert — the gateway closes the connection, and not before the deadline
            long closedAt = Awaits.connect(client.closedNanos(), "the front to close the silent connection");
            assertNotEarlierThanDeadline(client, closedAt);
            assertFalse(terminated.firstBytes().isDone(), "an undecided connection is never relayed");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("closes a connection that sends only half a ClientHello once the deadline passes")
        void closesHalfSentClientHello() throws Exception {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(1);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    DEADLINE, SniFrontListener.MAX_UNDECIDED_CONNECTIONS, LONG_IDLE);
            ClientLeg client = connectClient(front);

            // Act — the first half of a valid ClientHello, then silence
            Awaits.connect(client.socket().write(Buffer.buffer(Arrays.copyOf(hello, hello.length / 2))),
                    "the first half of the ClientHello to be written");

            // Assert
            long closedAt = Awaits.connect(client.closedNanos(),
                    "the front to close the connection holding half a ClientHello");
            assertNotEarlierThanDeadline(client, closedAt);
            assertFalse(passthrough.firstBytes().isDone(), "a half-sent ClientHello is never relayed");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("still routes a complete ClientHello dripped in small chunks within the deadline")
        void routesSlowChunkedClientHelloWithinDeadline() throws Exception {
            // Arrange — the matched control for the two cases above: a deadline long enough for the
            // drip, which takes well under a second in total
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(hello.length);
            Backend terminated = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target(),
                    Duration.ofSeconds(5), SniFrontListener.MAX_UNDECIDED_CONNECTIONS, LONG_IDLE);
            ClientLeg client = connectClient(front);

            // Act — eight bytes at a time, 20 ms apart
            Awaits.connect(drip(client.socket(), hello, 8, 20), "the ClientHello to be dripped to the front");

            // Assert — routed to the mapped backend, byte for byte, and the client leg is still open
            Buffer received = Awaits.connect(passthrough.firstBytes(),
                    "the passthrough backend to receive the dripped ClientHello");
            assertEquals(Buffer.buffer(hello), received, "a dripped ClientHello is relayed byte-for-byte");
            assertFalse(client.closedNanos().isDone(), "a ClientHello completed within the deadline is not closed");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        private void assertNotEarlierThanDeadline(ClientLeg client, long closedAtNanos) {
            // The server arms the deadline at accept, which precedes the client's connect completion,
            // so the client-side measurement may trail the timer by a little; 100 ms covers that.
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(closedAtNanos - client.connectedNanos());
            assertTrue(elapsedMillis >= DEADLINE.toMillis() - 100,
                    "the connection must be closed by the deadline, not before it: closed after " + elapsedMillis
                            + " ms");
        }
    }

    @Nested
    @DisplayName("undecided-connection cap")
    class UndecidedConnectionCap {

        private static final int CAP = 2;
        /** Long enough that a close well inside it cannot be the deadline's doing. */
        private static final Duration DEADLINE = Duration.ofSeconds(6);
        private static final long PROMPT_CLOSE_MILLIS = 2000;

        @Test
        @DisplayName("closes the connection beyond the cap at once and admits a new one after a slot is freed")
        void closesConnectionBeyondCapAndReleasesSlot() throws Exception {
            // Arrange — two backends, so the first and the last connection are each observable on
            // their own
            byte[] firstHello = ClientHelloFixture.withSni(MAPPED_SNI);
            byte[] lastHello = ClientHelloFixture.withSni(SECOND_MAPPED_SNI);
            Backend firstBackend = startBackend(firstHello.length);
            Backend lastBackend = startBackend(lastHello.length);
            SniFrontListener front = startFront(
                    Map.of(MAPPED_SNI, firstBackend.target(), SECOND_MAPPED_SNI, lastBackend.target()),
                    firstBackend.target(), DEADLINE, CAP, LONG_IDLE);
            ClientLeg first = connectClient(front);
            ClientLeg second = connectClient(front);

            // Act — one connection more than the cap
            ClientLeg beyondCap = connectClient(front);

            // Assert — closed promptly, far inside the deadline, while the first two stay open
            long closedAt = Awaits.connect(beyondCap.closedNanos(), "the front to close the connection beyond the cap");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(closedAt - beyondCap.connectedNanos());
            assertTrue(elapsedMillis < PROMPT_CLOSE_MILLIS,
                    "the connection beyond the cap is closed at accept, not by the deadline: closed after "
                            + elapsedMillis + " ms");
            assertFalse(first.closedNanos().isDone(), "the first admitted connection stays open");
            assertFalse(second.closedNanos().isDone(), "the second admitted connection stays open");

            // Act — the first connection is decided, which returns its slot
            Awaits.connect(first.socket().write(Buffer.buffer(firstHello)), "the first ClientHello to be written");
            Awaits.connect(firstBackend.firstBytes(), "the first connection to be routed");
            ClientLeg admitted = connectClient(front);
            Awaits.connect(admitted.socket().write(Buffer.buffer(lastHello)), "the last ClientHello to be written");

            // Assert — the freed slot admitted the new connection, which is routed normally
            Buffer received = Awaits.connect(lastBackend.firstBytes(),
                    "the connection admitted into the freed slot to be routed");
            assertEquals(Buffer.buffer(lastHello), received, "a freed slot admits a new connection again");

            // Assert — the second connection, never decided, is still bounded by its deadline
            Awaits.connect(second.closedNanos(), "the deadline to close the undecided second connection");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }
    }

    @Nested
    @DisplayName("relay idle timeout")
    class IdleTimeout {

        private static final Duration IDLE = Duration.ofMillis(500);

        @Test
        @DisplayName("closes both legs of a relay that carries nothing for the idle timeout")
        void closesSilentRelayOnBothLegs() throws Exception {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    SniFrontListener.CLIENT_HELLO_DEADLINE, SniFrontListener.MAX_UNDECIDED_CONNECTIONS, IDLE);
            ClientLeg client = connectClient(front);

            // Act — establish the relay, then fall silent
            Awaits.connect(client.socket().write(Buffer.buffer(hello)), "the ClientHello to be written");
            Awaits.connect(passthrough.firstBytes(), "the relay to be established");

            // Assert — both legs are closed
            Awaits.connect(client.closedNanos(), "the idle timeout to close the client leg");
            Awaits.connect(passthrough.firstClosed(), "the idle timeout to close the backend leg");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("keeps a relay open while it carries traffic more often than the idle timeout")
        void keepsActiveRelayOpen() throws Exception {
            // Arrange — the matched control for the case above
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    SniFrontListener.CLIENT_HELLO_DEADLINE, SniFrontListener.MAX_UNDECIDED_CONNECTIONS, IDLE);
            ClientLeg client = connectClient(front);
            Awaits.connect(client.socket().write(Buffer.buffer(hello)), "the ClientHello to be written");
            Awaits.connect(passthrough.firstBytes(), "the relay to be established");

            // Act — one byte every 100 ms for three idle timeouts
            byte[] traffic = new byte[15];
            Awaits.connect(drip(client.socket(), traffic, 1, 100), "the relay traffic to be written");

            // Assert — neither leg was closed while the relay carried traffic
            assertFalse(client.closedNanos().isDone(), "an active relay keeps its client leg open");
            assertFalse(passthrough.firstClosed().isDone(), "an active relay keeps its backend leg open");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }
    }

    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget)
            throws Exception {
        return startFront(targets, terminatedTarget, SniFrontListener.CLIENT_HELLO_DEADLINE,
                SniFrontListener.MAX_UNDECIDED_CONNECTIONS, LONG_IDLE);
    }

    /**
     * Starts a front with the given bounds. The relay's dial client carries the same idle timeout as
     * the front, as {@link TlsEdgeProducer} wires it in production.
     */
    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget,
            Duration deadline, int cap, Duration idle) throws Exception {
        NetClientOptions relayOptions = new NetClientOptions();
        SniFrontListener.applyIdleTimeout(relayOptions, idle);
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient(relayOptions));
        SniFrontListener front = new SniFrontListener(vertx, relay, targets, terminatedTarget, 0, HOST, deadline,
                cap, idle);
        Awaits.connect(front.start(), "the SNI front listener to start");
        return front;
    }

    private void sendToFront(SniFrontListener front, byte[] payload) throws Exception {
        ClientLeg client = connectClient(front);
        Awaits.connect(client.socket().write(Buffer.buffer(payload)), "the ClientHello bytes to be written");
    }

    /**
     * Connects a client leg to the front. The close handler is registered while the connect completes,
     * on the socket's own event loop, so a close the front issues at accept can never slip past it.
     */
    private ClientLeg connectClient(SniFrontListener front) throws Exception {
        CompletableFuture<Long> closedNanos = new CompletableFuture<>();
        long[] connectedNanos = new long[1];
        NetSocket socket = Awaits.connect(dialClient.connect(front.actualPort(), HOST).map(connected -> {
            connectedNanos[0] = System.nanoTime();
            connected.closeHandler(closed -> closedNanos.complete(System.nanoTime()));
            return connected;
        }), "the client leg to connect to the SNI front");
        return new ClientLeg(socket, connectedNanos[0], closedNanos);
    }

    /**
     * Writes {@code payload} {@code chunkSize} bytes at a time, {@code intervalMillis} apart, on Vert.x
     * timers rather than by sleeping the test thread.
     *
     * @return a future completing once the last chunk was handed to the socket
     */
    private Future<Void> drip(NetSocket socket, byte[] payload, int chunkSize, long intervalMillis) {
        Promise<Void> done = Promise.promise();
        dripFrom(socket, payload, 0, chunkSize, intervalMillis, done);
        return done.future();
    }

    private void dripFrom(NetSocket socket, byte[] payload, int offset, int chunkSize, long intervalMillis,
            Promise<Void> done) {
        int end = Math.min(offset + chunkSize, payload.length);
        socket.write(Buffer.buffer(Arrays.copyOfRange(payload, offset, end))).onFailure(done::tryFail);
        if (end == payload.length) {
            done.tryComplete();
            return;
        }
        vertx.setTimer(intervalMillis, timerId -> dripFrom(socket, payload, end, chunkSize, intervalMillis, done));
    }

    private Backend startBackend(int expectedBytes) throws Exception {
        CompletableFuture<Buffer> firstBytes = new CompletableFuture<>();
        CompletableFuture<Void> firstClosed = new CompletableFuture<>();
        Buffer accumulator = Buffer.buffer();
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> {
            socket.closeHandler(closed -> firstClosed.complete(null));
            socket.handler(chunk -> {
                accumulator.appendBuffer(chunk);
                if (accumulator.length() >= expectedBytes && !firstBytes.isDone()) {
                    firstBytes.complete(accumulator.copy());
                }
            });
        });
        int port = Awaits.connect(server.listen(0, LoopbackHost.ADDRESS),
                "the capturing backend to start listening").actualPort();
        return new Backend(new RelayTarget(HOST, port), firstBytes, firstClosed);
    }

    /**
     * A capturing backend server: its endpoint, the future completed with its first bytes, and the
     * future completed when its first accepted connection closes.
     */
    private record Backend(RelayTarget target, CompletableFuture<Buffer> firstBytes,
    CompletableFuture<Void> firstClosed) {
    }

    /**
     * A client leg connected to the front: the socket, when the connect completed, and the future
     * completed with the time the connection was closed.
     */
    private record ClientLeg(NetSocket socket, long connectedNanos, CompletableFuture<Long> closedNanos) {
    }
}
