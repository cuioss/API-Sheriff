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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.LogRecord;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.ClientHelloSniParserTest.ClientHelloFixture;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import de.cuioss.sheriff.gateway.tls.SniFrontListener.Refusal;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.jspecify.annotations.Nullable;
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
 * the client sent — and the bounds of the front: the ClientHello deadline, the undecided-connection
 * cap, its per-source share, the dial phase held inside that cap and the relay idle timeout, together
 * with how a refusal at accept is reported.
 * <p>
 * The bounds are shortened through the package-private constructor so each case observes its bound
 * within a fraction of a second instead of waiting out the production values. Each bound case is
 * paired with a matched control that shows the bound does not fire where it must not: a ClientHello
 * dripped slowly but within the deadline is still routed, a slot freed by a decided connection admits
 * a new one, a second source is admitted while the first one's share is full, and a relay that keeps
 * carrying traffic outlives its idle timeout.
 */
@DisplayName("SniFrontListener")
@Tag("isolated-fork")
@EnableTestLogger(debug = SniFrontListener.class)
class SniFrontListenerTest {

    private static final String HOST = LoopbackHost.ADDRESS;
    private static final String MAPPED_SNI = "api.example.com";
    private static final String SECOND_MAPPED_SNI = "second.example.com";

    /** An idle timeout long enough that no case outside {@link IdleTimeout} can ever reach it. */
    private static final Duration LONG_IDLE = Duration.ofMinutes(31);

    /** A close within this of the connect is a refusal at accept, never a deadline of six seconds. */
    private static final long PROMPT_CLOSE_MILLIS = 2000;

    private final RelayedPeerRegistry relayedPeers = new RelayedPeerRegistry();
    /** Every refusal the front under test reported to its recorder, in order. */
    private final List<Refusal> refusals = new CopyOnWriteArrayList<>();
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

        /**
         * The address hand-off is decided by the routing branch: the terminated relay names the client
         * of its loopback leg to the edge, and a passthrough relay — whose connection the gateway never
         * terminates — names nothing. Each is observed at the moment the backend reads the ClientHello,
         * the first byte any listener could act on.
         */
        @Test
        @DisplayName("hands the client's address over for a terminated connection, and for a passthrough one never")
        void handsTheClientOverOnlyOnTheTerminatedPath() throws Exception {
            byte[] mapped = ClientHelloFixture.withSni(MAPPED_SNI);
            byte[] unmapped = ClientHelloFixture.withSni("unmapped.example.net");
            Backend passthrough = startBackend(mapped.length);
            Backend terminated = startBackend(unmapped.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), terminated.target());

            sendToFront(front, mapped);
            sendToFront(front, unmapped);

            assertAll("the hand-off as each backend reads its first bytes",
                    () -> assertEquals(Optional.of(HOST), Awaits.connect(terminated.handOverAtFirstBytes(),
                                    "the terminated backend to read the ClientHello"),
                            "the terminated relay leg already names the client when its first byte arrives"),
                    () -> assertEquals(Optional.empty(), Awaits.connect(passthrough.handOverAtFirstBytes(),
                                    "the passthrough backend to read the ClientHello"),
                            "a passthrough relay leg names no client"));
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

    /**
     * The per-source share of the cap and how a refusal at accept is reported. Every held connection
     * here stays silent, so it keeps its slot for the whole case: the deadline is far longer than any
     * case runs. Sources are assigned through the front's source-key seam where a case needs a second
     * source, because a second loopback address is not available on every platform; the one case that
     * proves the production keying uses {@link SniFrontListener#sourceOf} itself.
     */
    @Nested
    @DisplayName("per-source share and refusal reporting")
    class RefusalsAtAccept {

        /** Long enough that a close well inside it cannot be the deadline's doing. */
        private static final Duration DEADLINE = Duration.ofSeconds(6);

        private final AtomicLong clockNanos = new AtomicLong();

        @Test
        @DisplayName("logs a cap refusal at WARN once per window, every further one at DEBUG, and again after the window")
        void warnsOncePerWindowPerReason() throws Exception {
            // Arrange — a cap of one, held by one silent connection
            Backend terminated = startBackend(1);
            SniFrontListener front = startFront(Map.of(), terminated.target(), DEADLINE, 1, 1, LONG_IDLE, LONG_IDLE,
                    clockNanos::get, SniFrontListener::sourceOf);
            ClientLeg held = connectClient(front);

            // Act — three refusals inside one warn window
            refuseNext(front);
            refuseNext(front);
            refuseNext(front);

            // Assert — one WARN, the two further refusals at DEBUG, and neither names the peer
            List<LogRecord> warnings = refusalWarnings();
            assertAll("three refusals inside one window",
                    () -> assertEquals(List.of(Refusal.CAP, Refusal.CAP, Refusal.CAP), refusals),
                    () -> assertEquals(1, warnings.size(), "only the first refusal of the window is a WARN"),
                    () -> assertTrue(warnings.getFirst().getMessage().contains("(cap)"),
                            "the WARN names the reason token: " + warnings.getFirst().getMessage()),
                    () -> assertFalse(warnings.getFirst().getMessage().contains(HOST),
                            "the WARN never names the peer address: " + warnings.getFirst().getMessage()),
                    () -> assertEquals(2, debugLines("Front connection refused at accept (cap)").size(),
                            "every further refusal inside the window is a DEBUG diagnostic"),
                    () -> assertFalse(held.closedNanos().isDone(), "the connection holding the cap stays open"));

            // Act — the window passes, then one more refusal
            clockNanos.addAndGet(SniFrontListener.SATURATION_WARN_INTERVAL.toNanos());
            refuseNext(front);

            // Assert — the first refusal of the new window is a WARN again
            assertEquals(2, refusalWarnings().size(), "the first refusal after the window is logged at WARN again");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("refuses a source holding its full share while admitting and routing a second source")
        void oneSourceFillingItsShareDoesNotBlockAnother() throws Exception {
            // Arrange — a cap of four with a share of two; the first three connections come from
            // source-a, every later one from source-b
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    DEADLINE, 4, 2, LONG_IDLE, LONG_IDLE, clockNanos::get,
                    sourcesInOrder("source-a", "source-a", "source-a", "source-b"));
            ClientLeg firstOfA = connectClient(front);
            ClientLeg secondOfA = connectClient(front);

            // Act + Assert — the positive control: source-a's next connection is refused at accept,
            // although two of the cap's four slots are still free
            refuseNext(front);
            assertEquals(List.of(Refusal.SOURCE_SHARE), refusals, "source-a's full share refuses its third connection");

            // Act — a connection from source-b
            ClientLeg ofB = connectClient(front);
            Awaits.connect(ofB.socket().write(Buffer.buffer(hello)), "source-b's ClientHello to be written");

            // Assert — source-b is admitted and routed, and source-a keeps its two connections
            Buffer received = Awaits.connect(passthrough.firstBytes(), "source-b's connection to be routed");
            assertAll("a second source is unaffected by the first one's full share",
                    () -> assertEquals(Buffer.buffer(hello), received, "source-b's ClientHello is relayed"),
                    () -> assertFalse(ofB.closedNanos().isDone(), "source-b's connection is not refused"),
                    () -> assertEquals(List.of(Refusal.SOURCE_SHARE), refusals, "source-b caused no refusal"),
                    () -> assertFalse(firstOfA.closedNanos().isDone(), "source-a's first connection stays open"),
                    () -> assertFalse(secondOfA.closedNanos().isDone(), "source-a's second connection stays open"));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("reports every refusal to the recorder with its reason, and logs each reason at WARN on its own")
        void recordsEveryRefusalWithItsReason() throws Exception {
            // Arrange — a cap of three with a share of one: source-a holds its share, so its second
            // connection is a share refusal; three distinct sources then fill the cap, so the fifth
            // connection is a cap refusal
            Backend terminated = startBackend(1);
            SniFrontListener front = startFront(Map.of(), terminated.target(), DEADLINE, 3, 1, LONG_IDLE, LONG_IDLE,
                    clockNanos::get, sourcesInOrder("source-a", "source-a", "source-b", "source-c", "source-d"));
            connectClient(front);

            // Act
            refuseNext(front);
            connectClient(front);
            connectClient(front);
            refuseNext(front);

            // Assert — each refusal reached the recorder once, with its reason, and the cap refusal
            // was logged at WARN although a share refusal had just been: the window is per reason
            List<LogRecord> warnings = refusalWarnings();
            assertAll("one refusal of each reason",
                    () -> assertEquals(List.of(Refusal.SOURCE_SHARE, Refusal.CAP), refusals),
                    () -> assertEquals(2, warnings.size(), "each reason opens its own warn window"),
                    () -> assertTrue(warnings.get(0).getMessage().contains("(source-share)"),
                            "the share refusal names its reason: " + warnings.get(0).getMessage()),
                    () -> assertTrue(warnings.get(1).getMessage().contains("(cap)"),
                            "the cap refusal names its reason: " + warnings.get(1).getMessage()));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("keys the share on the TCP peer address, so a second connection from the same address shares it")
        void keysTheShareOnThePeerAddress() throws Exception {
            // Arrange — the production source key and a share of one: every connection of this test
            // comes from the loopback address, each from its own port. Two backends, so the first and
            // the last connection are each observable on their own.
            byte[] firstHello = ClientHelloFixture.withSni(MAPPED_SNI);
            byte[] nextHello = ClientHelloFixture.withSni(SECOND_MAPPED_SNI);
            Backend firstBackend = startBackend(firstHello.length);
            Backend nextBackend = startBackend(nextHello.length);
            SniFrontListener front = startFront(
                    Map.of(MAPPED_SNI, firstBackend.target(), SECOND_MAPPED_SNI, nextBackend.target()),
                    firstBackend.target(), DEADLINE, 4, 1, LONG_IDLE, LONG_IDLE, clockNanos::get,
                    SniFrontListener::sourceOf);
            ClientLeg first = connectClient(front);

            // Act + Assert — a second connection from the same address is a share refusal, although
            // the cap has room: the port is not part of the source
            refuseNext(front);
            assertEquals(List.of(Refusal.SOURCE_SHARE), refusals, "the same peer address shares one share");

            // Act — the first connection is decided, which returns both of its slots
            Awaits.connect(first.socket().write(Buffer.buffer(firstHello)), "the first ClientHello to be written");
            Awaits.connect(firstBackend.firstBytes(), "the first connection to be routed");
            ClientLeg next = connectClient(front);
            Awaits.connect(next.socket().write(Buffer.buffer(nextHello)), "the next ClientHello to be written");

            // Assert — the matched control: the returned share admits the address again
            Buffer received = Awaits.connect(nextBackend.firstBytes(),
                    "the connection admitted into the returned share to be routed");
            assertAll("the share is returned with the slot",
                    () -> assertEquals(Buffer.buffer(nextHello), received, "the address is admitted and routed again"),
                    () -> assertEquals(List.of(Refusal.SOURCE_SHARE), refusals, "no further refusal"));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        private static List<LogRecord> refusalWarnings() {
            return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN,
                    ApiSheriffLogMessages.WARN.FRONT_CONNECTION_REFUSED.resolveIdentifierString());
        }

        /**
         * A source key that assigns the accepted connections, in accept order, to the given sources,
         * and every connection beyond them to the last one. One front server handles its connections
         * on one event loop, so accept order is the order this test connects in.
         */
        private static Function<NetSocket, String> sourcesInOrder(String... sources) {
            AtomicInteger accepted = new AtomicInteger();
            return socket -> sources[Math.min(accepted.getAndIncrement(), sources.length - 1)];
        }
    }

    /**
     * The dial phase inside the cap: a routed connection keeps its slot — of the cap and of its
     * source's share — while the relay dials its backend, and returns it once the dial settles or the
     * client leaves. The relay's dial is held by the case, so "pending" is a state the case controls
     * rather than a race it hopes to win. Each case first shows the slot still held during the dial,
     * which is what tells this front from one that returned the slot at the route decision, and then
     * shows it returned.
     */
    @Nested
    @DisplayName("dial phase")
    class DialPhase {

        private static final int CAP = 1;
        /** Long enough that a close well inside it cannot be the deadline's doing. */
        private static final Duration DEADLINE = Duration.ofSeconds(6);

        private final byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
        private final CompletableFuture<Void> releaseDials = new CompletableFuture<>();
        /** Completed by the next dial the relay requests; replaced before every probe connection. */
        private volatile CompletableFuture<Void> nextDial = new CompletableFuture<>();
        /** Every probe connection {@link #admitsAConnection} saw admitted, in order. */
        private final List<ClientLeg> admittedProbes = new CopyOnWriteArrayList<>();

        @Test
        @DisplayName("a routed connection holds its slot while its backend dial is pending and returns it once the dial settles")
        void holdsItsSlotUntilTheDialSettles() throws Exception {
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startHeldDialFront(passthrough.target());
            ClientLeg dialling = holdTheOnlySlotWithAPendingDial(front);

            releaseDials.complete(null);
            Awaits.connect(passthrough.firstBytes(), "the released dial to establish the relay");

            Awaits.until(() -> admitsAConnection(front), "the slot of the connection whose dial settled to be returned",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Re-assert the awaited state: the returned slot admitted a probe, which the front routed
            // and did not close, while the connection whose dial settled is still open — the slot came
            // back because the dial settled, not because that connection went away
            assertAll("the slot of the connection whose dial settled was returned",
                    () -> assertEquals(1, admittedProbes.size(),
                            "exactly one probe was admitted, the one the wait stopped on"),
                    () -> assertFalse(admittedProbes.getFirst().closedNanos().isDone(),
                            "the admitted probe was routed, not closed at accept"),
                    () -> assertFalse(dialling.closedNanos().isDone(),
                            "the connection whose dial settled is still open, so it did not return the slot by leaving"),
                    () -> assertTrue(refusals.stream().allMatch(Refusal.CAP::equals),
                            "every refusal while the slot was held was a cap refusal: " + refusals));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        /**
         * The held dial is never released here, so the dial cannot have settled: only the client
         * leaving can return the slot.
         */
        @Test
        @DisplayName("a routed connection returns its slot when its client leaves during the backend dial")
        void returnsItsSlotWhenTheClientLeavesDuringTheDial() throws Exception {
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startHeldDialFront(passthrough.target());
            ClientLeg dialling = holdTheOnlySlotWithAPendingDial(front);

            Awaits.teardown(dialling.socket().close(), "the dialling client to leave");

            Awaits.until(() -> admitsAConnection(front),
                    "the slot of the client that left during the dial to be returned", Awaits.CONNECT_CEILING_SECONDS);

            // Re-assert the awaited state: the returned slot admitted a probe whose own dial is now held,
            // so that probe holds the cap's only slot again and the next connection is refused — the slot
            // was returned once, not leaked twice
            int refusalsBefore = refusals.size();
            refuseNext(front);
            assertAll("the slot of the client that left during the dial was returned exactly once",
                    () -> assertEquals(1, admittedProbes.size(),
                            "exactly one probe was admitted, the one the wait stopped on"),
                    () -> assertFalse(admittedProbes.getFirst().closedNanos().isDone(),
                            "the admitted probe was routed, not closed at accept"),
                    () -> assertEquals(Refusal.CAP, refusals.get(refusalsBefore),
                            "the admitted probe holds the returned slot, so the next connection is a cap refusal"),
                    () -> assertTrue(refusals.stream().allMatch(Refusal.CAP::equals),
                            "every refusal while the slot was held was a cap refusal: " + refusals));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        private SniFrontListener startHeldDialFront(RelayTarget target) throws Exception {
            PassthroughRelay relay = new PassthroughRelay(RelayTestProxies.heldDialClient(vertx.createNetClient(),
                    () -> nextDial.complete(null), releaseDials), relayedPeers);
            return startFront(relay, Map.of(MAPPED_SNI, target), target, DEADLINE, CAP);
        }

        /**
         * Routes one connection, waits until its backend dial is requested and held, and asserts that
         * the next connection is refused at accept: the routed connection still holds the cap's only
         * slot.
         */
        private ClientLeg holdTheOnlySlotWithAPendingDial(SniFrontListener front) throws Exception {
            CompletableFuture<Void> dialled = new CompletableFuture<>();
            nextDial = dialled;
            ClientLeg dialling = connectClient(front);
            Awaits.connect(dialling.socket().write(Buffer.buffer(hello)), "the ClientHello to be written");
            Awaits.connect(dialled, "the routed connection's backend dial to be requested");

            refuseNext(front);

            assertEquals(List.of(Refusal.CAP), refusals,
                    "a connection whose backend dial is pending still holds the cap's only slot");
            return dialling;
        }

        /**
         * Offers the front one more connection carrying a ClientHello. Admitted, it is routed and its
         * backend dial requested; refused, it is closed at accept. Whichever happens first decides. An
         * admitted probe is recorded in {@link #admittedProbes}, so the case can re-assert it after the
         * wait.
         *
         * @return {@code true} when the connection was admitted
         */
        private boolean admitsAConnection(SniFrontListener front) throws Exception {
            CompletableFuture<Void> dialled = new CompletableFuture<>();
            nextDial = dialled;
            ClientLeg probe = connectClient(front);
            probe.socket().write(Buffer.buffer(hello));
            Awaits.connect(CompletableFuture.anyOf(dialled, probe.closedNanos()),
                    "the front to admit or refuse the probe connection");
            if (dialled.isDone()) {
                admittedProbes.add(probe);
                return true;
            }
            return false;
        }
    }

    @Nested
    @DisplayName("relay idle timeout")
    class IdleTimeout {

        private static final Duration IDLE = Duration.ofMillis(500);

        @Test
        @DisplayName("closes both legs of a relay that carries nothing for the idle timeout")
        void closesSilentRelayOnBothLegs() throws Exception {
            assertSilentRelayClosedByItsIdleTimeout(IDLE, IDLE);
        }

        @Test
        @DisplayName("closes both legs of a silent relay when only the front server carries the idle timeout")
        void closesSilentRelayWithTheFrontTimeoutAlone() throws Exception {
            assertSilentRelayClosedByItsIdleTimeout(IDLE, null);
        }

        @Test
        @DisplayName("closes both legs of a silent relay when only the relay's dial client carries the idle timeout")
        void closesSilentRelayWithTheDialClientTimeoutAlone() throws Exception {
            // The front's own timeout is one no case reaches; the relay leg alone has to close the relay
            assertSilentRelayClosedByItsIdleTimeout(LONG_IDLE, IDLE);
        }

        /**
         * Establishes a relay through a front with the given leg timeouts, falls silent, and asserts
         * that both legs are closed and not before {@link #IDLE}: every leg was last active after the
         * client connected, so a close earlier than that is not the idle timeout's doing.
         */
        private void assertSilentRelayClosedByItsIdleTimeout(Duration frontIdle, @Nullable Duration relayIdle)
                throws Exception {
            byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    SniFrontListener.CLIENT_HELLO_DEADLINE, SniFrontListener.MAX_UNDECIDED_CONNECTIONS, frontIdle,
                    relayIdle);
            ClientLeg client = connectClient(front);
            Awaits.connect(client.socket().write(Buffer.buffer(hello)), "the ClientHello to be written");
            Awaits.connect(passthrough.firstBytes(), "the relay to be established");

            long closedAt = Awaits.connect(client.closedNanos(), "the idle timeout to close the client leg");
            Awaits.connect(passthrough.firstClosed(), "the idle timeout to close the backend leg");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(closedAt - client.connectedNanos());
            assertTrue(elapsedMillis >= IDLE.toMillis() - 100,
                    "the relay must be closed by its idle timeout, not before it: closed after " + elapsedMillis
                            + " ms");
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

    @Nested
    @DisplayName("applyIdleTimeout")
    class ApplyIdleTimeout {

        @Test
        @DisplayName("sets a positive duration below one millisecond as one millisecond, never as no timeout")
        void subMillisecondIsOneMillisecond() {
            NetClientOptions options = new NetClientOptions();

            SniFrontListener.applyIdleTimeout(options, Duration.ofNanos(1));

            assertAll("a zero would mean no idle timeout at all",
                    () -> assertEquals(1, options.getIdleTimeout()),
                    () -> assertEquals(TimeUnit.MILLISECONDS, options.getIdleTimeoutUnit()));
        }

        @Test
        @DisplayName("sets a duration too long for int milliseconds in whole seconds")
        void tooLongForMillisecondsIsSeconds() {
            NetClientOptions options = new NetClientOptions();

            SniFrontListener.applyIdleTimeout(options, Duration.ofDays(30));

            assertAll("thirty days exceed Integer.MAX_VALUE milliseconds",
                    () -> assertEquals(Duration.ofDays(30).toSeconds(), options.getIdleTimeout()),
                    () -> assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit()));
        }

        @Test
        @DisplayName("sets a duration beyond Integer.MAX_VALUE seconds in whole minutes, rounded up, never shortened")
        void beyondIntSecondsIsMinutesRoundedUp() {
            NetClientOptions options = new NetClientOptions();
            Duration beyond = Duration.ofSeconds(Integer.MAX_VALUE).plusSeconds(60);

            SniFrontListener.applyIdleTimeout(options, beyond);

            assertAll("the margin above Integer.MAX_VALUE seconds survives",
                    () -> assertEquals(TimeUnit.MINUTES, options.getIdleTimeoutUnit()),
                    () -> assertEquals((beyond.toSeconds() + 59) / 60, options.getIdleTimeout(),
                            "the minutes are rounded up, so the timeout set is never shorter than the one given"));
        }

        @Test
        @DisplayName("sets a duration beyond Integer.MAX_VALUE minutes in whole hours, rounded up, never shortened")
        void beyondIntMinutesIsHoursRoundedUp() {
            NetClientOptions options = new NetClientOptions();
            // Integer.MAX_VALUE + 1 minutes = 2_147_483_648 minutes = 35_791_394 hours and 8 minutes
            Duration beyond = Duration.ofMinutes(Integer.MAX_VALUE).plusMinutes(1);

            SniFrontListener.applyIdleTimeout(options, beyond);

            assertAll("a duration no int count of minutes carries falls back to hours",
                    () -> assertEquals(TimeUnit.HOURS, options.getIdleTimeoutUnit()),
                    () -> assertEquals(35_791_395, options.getIdleTimeout(),
                            "the hours are rounded up, so the timeout set is never shorter than the one given"));
        }

        @Test
        @DisplayName("sets a duration beyond Integer.MAX_VALUE hours in whole days, rounded up, never shortened")
        void beyondIntHoursIsDaysRoundedUp() {
            NetClientOptions options = new NetClientOptions();
            // Integer.MAX_VALUE + 1 hours = 2_147_483_648 hours = 89_478_485 days and 8 hours
            Duration beyond = Duration.ofHours(Integer.MAX_VALUE).plusHours(1);

            SniFrontListener.applyIdleTimeout(options, beyond);

            assertAll("a duration no int count of hours carries falls back to days",
                    () -> assertEquals(TimeUnit.DAYS, options.getIdleTimeoutUnit()),
                    () -> assertEquals(89_478_486, options.getIdleTimeout(),
                            "the days are rounded up, so the timeout set is never shorter than the one given"));
        }

        @Test
        @DisplayName("refuses a duration beyond Integer.MAX_VALUE days, which no unit Vert.x takes can carry")
        void refusesADurationBeyondIntDays() {
            NetClientOptions options = new NetClientOptions();
            Duration beyond = Duration.ofDays(Integer.MAX_VALUE).plusDays(1);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> SniFrontListener.applyIdleTimeout(options, beyond));

            assertTrue(refused.getMessage().contains("too long"),
                    "the refusal names why no unit carries the timeout: " + refused.getMessage());
        }

        @Test
        @DisplayName("control: still sets exactly Integer.MAX_VALUE days, the longest timeout any unit carries")
        void setsExactlyIntDays() {
            NetClientOptions options = new NetClientOptions();

            SniFrontListener.applyIdleTimeout(options, Duration.ofDays(Integer.MAX_VALUE));

            assertAll("the last unit's own bound is inclusive",
                    () -> assertEquals(TimeUnit.DAYS, options.getIdleTimeoutUnit()),
                    () -> assertEquals(Integer.MAX_VALUE, options.getIdleTimeout()));
        }
    }

    /**
     * The undecided slot is returned on every path that ends a connection before its route: the
     * ClientHello deadline, the client closing, and the connection failing. Each case holds the only
     * slot of a cap of one, shows it held — the next connection is refused at accept — then ends the
     * held connection on one path and shows a fresh connection admitted and routed. The log line each
     * path writes, or the timing that rules the deadline out, ties the returned slot to the path under
     * test.
     */
    @Nested
    @DisplayName("slot return before the route")
    class SlotReturnBeforeTheRoute {

        private static final int CAP = 1;
        /** Long enough that a slot returned well inside it cannot be the deadline's doing. */
        private static final Duration LONG_DEADLINE = Duration.ofSeconds(6);
        private static final String DEADLINE_LINE = "no complete ClientHello within";
        private static final String FAILURE_LINE = "Front connection failed before ClientHello was read";

        private final byte[] hello = ClientHelloFixture.withSni(MAPPED_SNI);
        /** Every probe connection {@link #admitsAFreshConnection} saw admitted, in order. */
        private final List<ClientLeg> admittedProbes = new CopyOnWriteArrayList<>();

        @Test
        @DisplayName("returns the slot when the ClientHello deadline closes a silent connection")
        void returnsTheSlotOnTheDeadline() throws Exception {
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    Duration.ofSeconds(1), CAP, LONG_IDLE);
            ClientLeg silent = connectClient(front);
            assertSlotHeld(front);

            Awaits.connect(silent.closedNanos(), "the deadline to close the silent connection");

            assertFreshConnectionAdmitted(front, passthrough, "the slot the deadline freed to admit a connection");
            assertFalse(debugLines(DEADLINE_LINE).isEmpty(), "the deadline path closed the silent connection");
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        @Test
        @DisplayName("returns the slot when the client closes before sending its ClientHello")
        void returnsTheSlotOnTheClose() throws Exception {
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    LONG_DEADLINE, CAP, LONG_IDLE);
            ClientLeg leaving = connectClient(front);
            assertSlotHeld(front);

            Awaits.teardown(leaving.socket().close(), "the undecided client to leave");

            assertFreshConnectionAdmitted(front, passthrough, "the slot the closed connection freed to admit a connection");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - leaving.connectedNanos());
            assertAll("the close, not the deadline, returned the slot",
                    () -> assertTrue(elapsedMillis < LONG_DEADLINE.toMillis(),
                            "the slot came back before the deadline could fire: after " + elapsedMillis + " ms"),
                    () -> assertTrue(debugLines(DEADLINE_LINE).isEmpty(), "no deadline fired"),
                    () -> assertTrue(debugLines(FAILURE_LINE).isEmpty(), "an orderly close is not a failure"));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        /**
         * A client socket closed with a zero linger time sends a reset instead of an orderly close,
         * which the front reads as a failure of the connection rather than as its end.
         */
        @Test
        @DisplayName("returns the slot when the connection fails before its ClientHello")
        void returnsTheSlotOnTheError() throws Exception {
            Backend passthrough = startBackend(hello.length);
            SniFrontListener front = startFront(Map.of(MAPPED_SNI, passthrough.target()), passthrough.target(),
                    LONG_DEADLINE, CAP, LONG_IDLE);
            long connectedNanos = System.nanoTime();
            try (Socket resetting = new Socket()) {
                resetting.connect(new InetSocketAddress(HOST, front.actualPort()), (int) PROMPT_CLOSE_MILLIS);
                assertSlotHeld(front);

                resetting.setSoLinger(true, 0);
            }

            assertFreshConnectionAdmitted(front, passthrough, "the slot the failed connection freed to admit a connection");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectedNanos);
            assertAll("the failure, not the deadline, returned the slot",
                    () -> assertTrue(elapsedMillis < LONG_DEADLINE.toMillis(),
                            "the slot came back before the deadline could fire: after " + elapsedMillis + " ms"),
                    () -> assertFalse(debugLines(FAILURE_LINE).isEmpty(), "the reset reached the failure path"),
                    () -> assertTrue(debugLines(DEADLINE_LINE).isEmpty(), "no deadline fired"));
            Awaits.teardown(front.stop(), "the SNI front listener to stop");
        }

        /** The next connection is refused at accept: the held connection has the cap's only slot. */
        private void assertSlotHeld(SniFrontListener front) throws Exception {
            refuseNext(front);
            assertEquals(List.of(Refusal.CAP), refusals, "the undecided connection holds the cap's only slot");
        }

        /**
         * Waits until a fresh connection is admitted, then asserts it was routed — its ClientHello
         * reached the backend — and not closed at accept.
         */
        private void assertFreshConnectionAdmitted(SniFrontListener front, Backend backend, String waitFor)
                throws Exception {
            Awaits.until(() -> admitsAFreshConnection(front, backend), waitFor, Awaits.CONNECT_CEILING_SECONDS);
            assertAll("a fresh connection was admitted into the returned slot",
                    () -> assertEquals(1, admittedProbes.size(), "exactly one probe was admitted, the one the wait stopped on"),
                    () -> assertEquals(Buffer.buffer(hello), Awaits.connect(backend.firstBytes(),
                            "the admitted probe to be routed"), "the admitted probe's ClientHello is relayed"),
                    () -> assertFalse(admittedProbes.getFirst().closedNanos().isDone(),
                            "the admitted probe was routed, not closed at accept"));
        }

        /**
         * Offers the front one more connection carrying a ClientHello. Admitted, it is routed to the
         * backend; refused, it is closed at accept. Whichever happens first decides.
         *
         * @return {@code true} when the connection was admitted
         */
        private boolean admitsAFreshConnection(SniFrontListener front, Backend backend) throws Exception {
            ClientLeg probe = connectClient(front);
            probe.socket().write(Buffer.buffer(hello));
            Awaits.connect(CompletableFuture.anyOf(backend.firstBytes(), probe.closedNanos()),
                    "the front to admit or refuse the probe connection");
            if (backend.firstBytes().isDone()) {
                admittedProbes.add(probe);
                return true;
            }
            return false;
        }
    }

    private static List<LogRecord> debugLines(String text) {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.DEBUG, text);
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
        return startFront(targets, terminatedTarget, deadline, cap, idle, idle);
    }

    /**
     * Starts a front whose server and relay dial client carry different idle timeouts, so a case can
     * prove each leg's timeout on its own. The per-source share equals the cap, so it never binds
     * before the cap does, and connections are keyed on their TCP peer address as in production.
     *
     * @param frontIdle the idle timeout of the front server; positive
     * @param relayIdle the idle timeout of the relay's dial client, or {@code null} for none at all
     */
    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget,
            Duration deadline, int cap, Duration frontIdle, @Nullable Duration relayIdle) throws Exception {
        return startFront(targets, terminatedTarget, deadline, cap, cap, frontIdle, relayIdle, System::nanoTime,
                SniFrontListener::sourceOf);
    }

    /**
     * Starts a front with every bound explicit, recording its refusals into {@link #refusals}. The
     * warn window is the production {@link SniFrontListener#SATURATION_WARN_INTERVAL}; a case moves
     * past it with its own {@code clock}.
     *
     * @param perSource the per-source share of the cap; positive
     * @param clock     the monotonic nanosecond clock the warn window is measured on
     * @param sourceKey the source each accepted connection is counted under
     */
    @SuppressWarnings("java:S107") // mirrors the front's own test seam, which takes every bound
    private SniFrontListener startFront(Map<String, RelayTarget> targets, RelayTarget terminatedTarget,
            Duration deadline, int cap, int perSource, Duration frontIdle, @Nullable Duration relayIdle,
            LongSupplier clock, Function<NetSocket, String> sourceKey) throws Exception {
        NetClientOptions relayOptions = new NetClientOptions();
        if (relayIdle != null) {
            SniFrontListener.applyIdleTimeout(relayOptions, relayIdle);
        }
        PassthroughRelay relay = new PassthroughRelay(vertx.createNetClient(relayOptions), relayedPeers);
        return started(new SniFrontListener(vertx, relay, targets, terminatedTarget, 0, HOST, deadline, cap,
                perSource, frontIdle, SniFrontListener.SATURATION_WARN_INTERVAL, clock, sourceKey, refusals::add));
    }

    /**
     * Starts a front handing its connections to the given relay, with the per-source share equal to
     * the cap and every other bound as in {@link #startFront(Map, RelayTarget, Duration, int, Duration)}.
     */
    private SniFrontListener startFront(PassthroughRelay relay, Map<String, RelayTarget> targets,
            RelayTarget terminatedTarget, Duration deadline, int cap) throws Exception {
        return started(new SniFrontListener(vertx, relay, targets, terminatedTarget, 0, HOST, deadline, cap, cap,
                LONG_IDLE, SniFrontListener.SATURATION_WARN_INTERVAL, System::nanoTime, SniFrontListener::sourceOf,
                refusals::add));
    }

    private static SniFrontListener started(SniFrontListener front) throws Exception {
        Awaits.connect(front.start(), "the SNI front listener to start");
        return front;
    }

    /**
     * Connects one more client, asserts the front closes it at accept — promptly, far inside the
     * deadline — and waits until the refusal has reached the recorder.
     */
    private void refuseNext(SniFrontListener front) throws Exception {
        int recordedBefore = refusals.size();
        ClientLeg refused = connectClient(front);
        long closedAt = Awaits.connect(refused.closedNanos(), "the front to refuse the connection at accept");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(closedAt - refused.connectedNanos());
        assertTrue(elapsedMillis < PROMPT_CLOSE_MILLIS,
                "the connection is refused at accept, not closed by the deadline: closed after " + elapsedMillis
                        + " ms");
        Awaits.until(() -> refusals.size() > recordedBefore, "the refusal to reach the recorder",
                Awaits.CONNECT_CEILING_SECONDS);
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
        CompletableFuture<Optional<String>> handOver = new CompletableFuture<>();
        Buffer accumulator = Buffer.buffer();
        NetServer server = vertx.createNetServer();
        server.connectHandler(socket -> {
            socket.closeHandler(closed -> firstClosed.complete(null));
            socket.handler(chunk -> {
                if (!handOver.isDone()) {
                    handOver.complete(relayedPeers.clientOf(socket.remoteAddress()));
                }
                accumulator.appendBuffer(chunk);
                if (accumulator.length() >= expectedBytes && !firstBytes.isDone()) {
                    firstBytes.complete(accumulator.copy());
                }
            });
        });
        int port = Awaits.connect(server.listen(0, LoopbackHost.ADDRESS),
                "the capturing backend to start listening").actualPort();
        return new Backend(new RelayTarget(HOST, port), firstBytes, firstClosed, handOver);
    }

    /**
     * A capturing backend server: its endpoint, the future completed with its first bytes, the future
     * completed when its first accepted connection closes, and the future completed with the client the
     * relayed-peer registry named for the relay leg when the first byte arrived.
     */
    private record Backend(RelayTarget target, CompletableFuture<Buffer> firstBytes,
    CompletableFuture<Void> firstClosed, CompletableFuture<Optional<String>> handOverAtFirstBytes) {
    }

    /**
     * A client leg connected to the front: the socket, when the connect completed, and the future
     * completed with the time the connection was closed.
     */
    private record ClientLeg(NetSocket socket, long connectedNanos, CompletableFuture<Long> closedNanos) {
    }
}
