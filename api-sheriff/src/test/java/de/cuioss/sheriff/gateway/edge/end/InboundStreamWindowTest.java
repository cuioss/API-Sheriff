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
package de.cuioss.sheriff.gateway.edge.end;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link InboundStreamWindow} read against a live Vert.x HTTP/2 server: it attaches to the stream of an
 * HTTP/2 request — and to nothing else — and reports what the transport knows of it. A request paused
 * by its handler returns no window to the client, so the window it reports shrinks by exactly the bytes
 * the client sends, and the stream stops being open to the client once the client ends it. The PING it
 * sends goes out on the request's own connection and completes once the client acknowledges it, and the
 * PINGs a connection's client holds unacknowledged are bounded: past the bound a PING is refused at once,
 * until an acknowledgement frees a place. A PING is sent only on the connection's event loop, so every
 * case that pings does so there, and a PING asked for anywhere else is refused at once. An
 * {@link EarlyResponseEnd} whose PING is refused at the bound sends a new one once a place is free, and
 * one whose PING the client never acknowledges ends at the time cap and lets go of its ending call while
 * that PING is still pending in the transport.
 * <p>
 * This is the test that pins the one Vert.x internal the class reaches through: should a Vert.x upgrade
 * change it, the attach fails here rather than silently in production.
 */
@EnableGeneratorController
@EnableTestLogger(debug = EarlyResponseEnd.class)
@DisplayName("InboundStreamWindow — the transport's view of an inbound HTTP/2 stream")
@Tag("isolated-fork")
class InboundStreamWindowTest {

    /** The failure {@link InboundStreamWindow#ping()} answers with once the per-connection bound is full. */
    private static final String PINGS_OUTSTANDING = "Too many unacknowledged PINGs on the connection";
    /** How {@link EarlyResponseEnd} logs the end of a wait, followed by its outcome. */
    private static final String END_LOGGED = "Ending the early response of an open HTTP/2 stream: ";
    /** The end of a wait the acknowledgement of its PING ended as quiet. */
    private static final String QUIET_END = END_LOGGED + EarlyResponseEnd.Outcome.QUIET;
    /** The end of a wait its time cap ended. */
    private static final String TIME_CAP_END = END_LOGGED + EarlyResponseEnd.Outcome.TIME_CAP;

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    /** The first request the server received. */
    private final CompletableFuture<HttpServerRequest> received = new CompletableFuture<>();
    /** Every request the server received, in arrival order. */
    private final List<HttpServerRequest> allReceived = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
            request.pause();
            allReceived.add(request);
            received.complete(request);
        }).listen(0, LoopbackHost.ADDRESS), "the server to start listening");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            Awaits.teardown(client.close(), "the client to close");
        }
        Awaits.teardown(server.close(), "the server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("HTTP/2 — the window of a paused request shrinks by the bytes sent; ending the body closes the stream to the client")
    void windowOfAPausedRequestShrinksByTheBytesSent() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        String part = Generators.letterStrings(256, 1_024).next();
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        int initial = new HttpServerOptions().getInitialSettings().getInitialWindowSize();

        View before = look(window);
        Awaits.connect(upload.write(part), "part of the body to be sent");
        Awaits.until(() -> look(window).sendable() == initial - part.length(),
                "the window to shrink by the part sent", Awaits.CONNECT_CEILING_SECONDS);
        Awaits.connect(upload.end(), "the body to be ended");
        Awaits.until(() -> !look(window).remoteOpen(), "the stream to close to the client",
                Awaits.CONNECT_CEILING_SECONDS);
        View ended = look(window);

        assertAll("the window of a paused HTTP/2 request",
                () -> assertEquals(new View(true, initial), before, "a fresh stream holds the whole initial window"),
                () -> assertEquals(new View(false, 0), ended, "an ended stream lets the client send nothing more"));
    }

    @Test
    @DisplayName("HTTP/2 — what a client may send is bounded by the connection's window as well as the stream's")
    void sendableIsBoundedByTheConnectionWindow() throws Exception {
        int initial = new HttpServerOptions().getInitialSettings().getInitialWindowSize();
        // Each portion fits its own stream window; the two together exceed the one connection window.
        Buffer portion = Buffer.buffer(Generators.letterStrings(initial * 2 / 3, initial * 2 / 3).next());
        HttpClientRequest first = open(HttpVersion.HTTP_2);
        HttpClientRequest second = Awaits.connect(client.request(HttpMethod.POST, server.actualPort(),
                LoopbackHost.ADDRESS, "/second"), "the second request to open on the same connection");
        Awaits.connect(first.setChunked(true).sendHead(), "the first head to be sent");
        Awaits.connect(second.setChunked(true).sendHead(), "the second head to be sent");
        Awaits.until(() -> allReceived.size() == 2, "the server to receive both requests",
                Awaits.CONNECT_CEILING_SECONDS);
        InboundStreamWindow firstWindow = InboundStreamWindow.of(allReceived.get(0)).orElseThrow();
        InboundStreamWindow secondWindow = InboundStreamWindow.of(allReceived.get(1)).orElseThrow();

        first.write(portion);
        second.write(portion);
        Awaits.until(() -> look(firstWindow).sendable() == 0 && look(secondWindow).sendable() == 0,
                "the connection window to be used up", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("two streams sharing one used-up connection window",
                () -> assertSame(first.connection(), second.connection(), "both streams share the connection"),
                () -> assertTrue(look(firstWindow).remoteOpen() && look(secondWindow).remoteOpen(),
                        "neither client side has ended"),
                () -> assertTrue(portion.length() < initial,
                        "neither stream can have used up its own window, so the connection's bounds both"));
    }

    @Test
    @DisplayName("HTTP/2 — the gateway's side of the stream has ended as soon as its response end is handed over")
    void localSideEndsWhenTheResponseEnds() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        CompletableFuture<Boolean> endedBefore = new CompletableFuture<>();
        CompletableFuture<Boolean> endedInTheSameTurn = new CompletableFuture<>();

        window.execute(() -> {
            endedBefore.complete(window.localEnded());
            request.response().end(Generators.letterStrings(4, 12).next());
            endedInTheSameTurn.complete(window.localEnded());
        });

        assertAll("the gateway's side of the stream",
                () -> assertFalse(Awaits.connect(endedBefore, "the look before the end")),
                () -> assertTrue(Awaits.connect(endedInTheSameTurn, "the look right after the end"),
                        "an end the transport accepts at once ends the gateway's side in the same turn"),
                () -> assertTrue(look(window).remoteOpen(), "the client's side is still open"));
    }

    @Test
    @DisplayName("HTTP/2 — a PING goes out on the stream's own connection, and its acknowledgement completes the future")
    void pingIsSentOnTheStreamsConnectionAndCompletesOnTheAcknowledgement() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        List<Buffer> pingsReceived = new CopyOnWriteArrayList<>();
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        upload.connection().pingHandler(pingsReceived::add);
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();

        Future<Void> ping = onTheLoop(window, window::ping);
        Awaits.connect(ping, "the PING to be acknowledged");

        assertAll("a PING on the stream's connection",
                () -> assertTrue(ping.succeeded(), "the acknowledgement completes the future"),
                () -> assertEquals(1, pingsReceived.size(),
                        "the client of the stream's connection received exactly the one PING"),
                () -> assertTrue(look(window).remoteOpen(), "the PING leaves the stream open"));
    }

    /**
     * The PING future fails when the PING cannot be written. Vert.x does not fail a PING it has written
     * when its connection closes before the acknowledgement arrives — that future never completes — so
     * the case waits until the connection is observed closed before it pings.
     */
    @Test
    @DisplayName("HTTP/2 — a PING on a connection that has closed fails its future")
    void pingOnAClosedConnectionFails() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        request.connection().closeHandler(closed::complete);
        request.connection().close();
        Awaits.connect(closed, "the connection to be closed");

        boolean failed = Awaits.connect(onTheLoop(window, window::ping)
                        .transform(result -> Future.succeededFuture(result.failed())),
                "the PING on the closed connection to settle");

        assertTrue(failed, "a PING that cannot be acknowledged must fail, never complete");
    }

    /**
     * Each early answer waits on at most one PING at a time, and a connection carries at most as many
     * early answers at once as it has streams, so the bound must not fall below the streams the edge
     * admits on one connection — or a client acknowledging every PING would have the PING of its later
     * early answers refused. Read from the listener settings the edge's customizer leaves, so the check follows
     * the edge's bound wherever it is set.
     */
    @Test
    @DisplayName("the per-connection PING bound is no lower than the concurrent streams the edge admits on a connection")
    void pingBoundCoversEveryStreamTheEdgeAdmits() {
        HttpServerOptions listener = new HttpServerOptions();
        new EdgeHardeningOptions().customizeHttpServer(listener);
        long admittedStreams = listener.getInitialSettings().getMaxConcurrentStreams();

        assertTrue(InboundStreamWindow.MAX_OUTSTANDING_PINGS >= admittedStreams,
                () -> "an acknowledging client may have one PING outstanding per admitted stream, but the bound is "
                        + InboundStreamWindow.MAX_OUTSTANDING_PINGS + " for " + admittedStreams + " admitted streams");
    }

    @Test
    @DisplayName("HTTP/2 — past the per-connection bound a PING is refused at once, and an acknowledgement frees a place")
    void pingPastTheBoundIsRefusedUntilAnAcknowledgementFreesAPlace() throws Exception {
        PingHoldingClient holding = PingHoldingClient.open(vertx, server.actualPort());
        HttpServerRequest request = Awaits.connect(received, "the server to receive the request");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        List<Future<Void>> held = onTheLoop(window,
                () -> pings(window, InboundStreamWindow.MAX_OUTSTANDING_PINGS));
        Awaits.until(() -> holding.pingsReceived() == InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "every PING within the bound to reach the client", Awaits.CONNECT_CEILING_SECONDS);

        Future<Void> refused = onTheLoop(window, window::ping);
        boolean refusedAtOnce = refused.failed();
        boolean othersPendingAtRefusal = held.stream().noneMatch(Future::isComplete);
        Awaits.connect(holding.acknowledgeOne(), "the client to acknowledge one PING");
        Awaits.connect(held.getFirst(), "the acknowledged PING to complete");
        Future<Void> afterRelease = onTheLoop(window, window::ping);
        Awaits.until(() -> holding.pingsReceived() > InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "the PING sent after the acknowledgement to reach the client", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("the per-connection bound of unacknowledged PINGs",
                () -> assertTrue(refusedAtOnce, "a PING past the bound fails at once, without waiting on the client"),
                () -> assertTrue(othersPendingAtRefusal, "the refusal settles none of the PINGs the client holds"),
                () -> assertFalse(afterRelease.isComplete(),
                        "the acknowledgement freed a place: the next PING is sent and waits for its own acknowledgement"),
                () -> assertTrue(held.stream().skip(1).noneMatch(Future::isComplete),
                        "only the acknowledged PING completed"));
    }

    /**
     * The bound belongs to the connection, not to one view of a stream: every early answer on a connection
     * attaches its own {@link InboundStreamWindow}, so the PINGs sent through one instance must count
     * against the PINGs another may send. A count kept per instance would let the second instance send
     * where this case expects a refusal.
     */
    @Test
    @DisplayName("HTTP/2 — the PING bound is shared by every view of the connection: PINGs held through one view refuse a PING through another")
    void pingBoundIsSharedByEveryViewOfTheConnection() throws Exception {
        PingHoldingClient holding = PingHoldingClient.open(vertx, server.actualPort());
        HttpServerRequest request = Awaits.connect(received, "the server to receive the request");
        InboundStreamWindow filling = InboundStreamWindow.of(request).orElseThrow();
        InboundStreamWindow other = InboundStreamWindow.of(request).orElseThrow();
        List<Future<Void>> held = onTheLoop(filling,
                () -> pings(filling, InboundStreamWindow.MAX_OUTSTANDING_PINGS));
        Awaits.until(() -> holding.pingsReceived() == InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "every PING within the bound to reach the client", Awaits.CONNECT_CEILING_SECONDS);

        Future<Void> refused = onTheLoop(other, other::ping);
        boolean refusedAtOnce = refused.failed();
        Awaits.connect(holding.acknowledgeOne(), "the client to acknowledge one PING");
        Awaits.connect(held.getFirst(), "the acknowledged PING to complete");
        Future<Void> afterRelease = onTheLoop(other, other::ping);
        Awaits.until(() -> holding.pingsReceived() > InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "the PING sent through the other view after the acknowledgement to reach the client",
                Awaits.CONNECT_CEILING_SECONDS);

        assertAll("one bound across every view of a connection",
                () -> assertNotSame(filling, other, "the two views are distinct instances"),
                () -> assertTrue(refusedAtOnce,
                        "the PINGs held through one view fill the bound for the other view as well"),
                () -> assertFalse(afterRelease.isComplete(),
                        "an acknowledgement of a PING sent through one view frees a place for the other view"));
    }

    /**
     * The lost-acknowledgement race {@link InboundStreamWindow#ping()} documents lies inside Vert.x and
     * cannot be forced from here, so the case pins the confinement that rules it out: a PING asked for off
     * the loop is refused at once, sent nowhere and counted nowhere. Every PING within the bound is still
     * sent afterwards — the refused one took no place — and the client receives exactly those.
     */
    @Test
    @DisplayName("HTTP/2 — a PING asked for off the connection's event loop is refused at once, sends nothing and takes no place")
    void pingOffTheEventLoopIsRefusedAtOnceAndTakesNoPlace() throws Exception {
        PingHoldingClient holding = PingHoldingClient.open(vertx, server.actualPort());
        HttpServerRequest request = Awaits.connect(received, "the server to receive the request");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();

        Future<Void> offTheLoop = window.ping();
        boolean refusedAtOnce = offTheLoop.failed();
        List<Future<Void>> withinTheBound = onTheLoop(window,
                () -> pings(window, InboundStreamWindow.MAX_OUTSTANDING_PINGS));
        Awaits.until(() -> holding.pingsReceived() == InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "every PING within the bound to reach the client", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("a PING asked for off the event loop",
                () -> assertTrue(refusedAtOnce, "the PING fails at once, without waiting on the client"),
                () -> assertInstanceOf(IllegalStateException.class, offTheLoop.cause(),
                        "the refusal names the broken thread confinement"),
                () -> assertTrue(withinTheBound.stream().noneMatch(Future::isComplete),
                        "the refused PING took no place: every PING within the bound is sent and awaits its acknowledgement"),
                () -> assertEquals(InboundStreamWindow.MAX_OUTSTANDING_PINGS, holding.pingsReceived(),
                        "the client received the PINGs within the bound and not the refused one"));
    }

    /**
     * The refused-then-retried path of {@link EarlyResponseEnd}, end to end on a connection whose client
     * holds every PING it receives. With the per-connection bound already full, the PING the wait asks
     * for once the stream's window has stood still is refused: it is sent nowhere and ends nothing. Once
     * an acknowledgement frees a place, the wait sends a new PING after the window has stood still for the
     * quiet period again, and the acknowledgement of that PING on the still unmoved window ends the wait
     * as quiet — not at the cap. Without the restart a refused PING would leave the wait to the cap.
     */
    @Test
    @DisplayName("HTTP/2 — an early-response end whose PING is refused at the bound sends a new one once a place is free, and ends quiet on its acknowledgement")
    void refusedPingOfAnEarlyResponseEndIsRetriedOnceAPlaceIsFree() throws Exception {
        PingHoldingClient holding = PingHoldingClient.open(vertx, server.actualPort());
        HttpServerRequest request = Awaits.connect(received, "the server to receive the request");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        onTheLoop(window, () -> pings(window, InboundStreamWindow.MAX_OUTSTANDING_PINGS));
        Awaits.until(() -> holding.pingsReceived() == InboundStreamWindow.MAX_OUTSTANDING_PINGS,
                "every PING within the bound to reach the client", Awaits.CONNECT_CEILING_SECONDS);
        AtomicInteger endings = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();

        Future<Void> ended = EarlyResponseEnd.endWhenQuiet(request, () -> {
            endings.incrementAndGet();
            return request.response().end();
        }, resets::incrementAndGet);
        Awaits.until(InboundStreamWindowTest::refusedPingLogged, "the PING of the wait to be refused at the bound",
                Awaits.CONNECT_CEILING_SECONDS);
        int receivedAtRefusal = holding.pingsReceived();
        Awaits.connect(holding.acknowledgeOne(), "the client to acknowledge one held PING");
        Awaits.until(() -> holding.pingsReceived() == InboundStreamWindow.MAX_OUTSTANDING_PINGS + 1,
                "the wait to send a new PING once a place is free", Awaits.CONNECT_CEILING_SECONDS);
        Awaits.connect(holding.acknowledgeAll(), "the client to acknowledge every PING it holds");
        Awaits.connect(ended, "the response to be ended");
        List<String> endsLogged = endsLogged();

        assertAll("an early-response end whose PING was refused at the bound",
                () -> assertEquals(InboundStreamWindow.MAX_OUTSTANDING_PINGS, receivedAtRefusal,
                        "the refused PING was sent nowhere"),
                () -> assertEquals(List.of(QUIET_END), endsLogged,
                        "the acknowledgement of the new PING ends the wait as quiet, and only once"),
                () -> assertEquals(1, endings.get(), "the response is ended once"),
                () -> assertEquals(1, resets.get(), "the stream, still open to the client, is reset once after the end"));
    }

    /**
     * The relay of {@link EarlyResponseEnd} lets go of the wait once it has ended (ADR-0066), pinned through
     * the real wait on a connection whose client holds every PING it receives. The wait sends one PING once
     * the window has stood still, the client never acknowledges it, and the wait ends at the time cap with
     * that PING still pending in the transport — which keeps the PING's future, and the relay it completes
     * into, until the connection closes. Without {@code relay.release()} in the end of the wait that future
     * keeps relay, wait and ending call reachable for as long as the connection stays open; with it, nothing
     * holds the ending call once the wait has ended, and it becomes collectable while the PING is still
     * pending.
     */
    @Test
    @DisplayName("HTTP/2 — an early-response end whose PING is never acknowledged ends at the cap and lets go of its ending call while the PING is still pending")
    void endedWaitLetsGoOfItsEndingCallWhileItsPingIsStillPending() throws Exception {
        PingHoldingClient holding = PingHoldingClient.open(vertx, server.actualPort());
        HttpServerRequest request = Awaits.connect(received, "the server to receive the request");
        AtomicInteger endings = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();

        EndingWait wait = endWithAnEndingOnlyTheWaitHolds(request, endings, resets);
        Awaits.connect(wait.ended(), "the response to be ended at the time cap");
        List<String> endsLogged = endsLogged();
        int pingsReceived = holding.pingsReceived();
        awaitCollectable(wait.ending(), "the ending call to be let go while its PING is still pending");

        assertAll("an early-response end whose PING the client never acknowledges",
                () -> assertEquals(List.of(TIME_CAP_END), endsLogged,
                        "the unacknowledged PING leaves the wait to its time cap, and it ends once"),
                () -> assertEquals(1, endings.get(), "the response is ended once"),
                () -> assertEquals(1, resets.get(), "the stream, still open to the client, is reset once after the end"),
                () -> assertEquals(1, pingsReceived, "the wait sent one PING and no other while it was outstanding"),
                () -> assertEquals(1, holding.pingsHeld(),
                        "the PING is still unacknowledged, so its future is still pending in the transport"),
                () -> assertNull(wait.ending().get(), "nothing holds the ending call once the wait has ended"));
    }

    @Test
    @DisplayName("HTTP/1.1 — no stream to attach to")
    void doesNotAttachToAnHttp1Request() throws Exception {
        HttpClientRequest request = open(HttpVersion.HTTP_1_1);
        Awaits.connect(request.setChunked(true).sendHead(), "the request head to be sent");

        Optional<InboundStreamWindow> window = InboundStreamWindow.of(Awaits.connect(received,
                "the server to receive the request"));

        assertTrue(window.isEmpty(), "an HTTP/1.1 request has no HTTP/2 stream");
    }

    @Test
    @DisplayName("without a view of the stream, EarlyResponseEnd ends at once and decides the reset after the write")
    void earlyResponseEndWithoutAViewEndsAtOnce() throws Exception {
        HttpClientRequest request = open(HttpVersion.HTTP_1_1);
        Awaits.connect(request.setChunked(true).sendHead(), "the request head to be sent");
        HttpServerRequest serverRequest = Awaits.connect(received, "the server to receive the request");
        AtomicInteger endings = new AtomicInteger();
        CompletableFuture<Boolean> resetAfterWrite = new CompletableFuture<>();

        Future<Void> ended = EarlyResponseEnd.endWhenQuiet(serverRequest, () -> {
            endings.incrementAndGet();
            return serverRequest.response().end();
        }, () -> resetAfterWrite.complete(serverRequest.response().ended()));
        int endingsAtReturn = endings.get();
        Awaits.connect(ended, "the response to be ended");

        assertAll("an end with no view of the stream",
                () -> assertEquals(1, endingsAtReturn, "the response is ended before the call returns"),
                () -> assertTrue(Awaits.connect(resetAfterWrite, "the reset to be decided"),
                        "the reset is decided only after the response was ended"),
                () -> assertFalse(serverRequest.isEnded(), "the request body was still open"));
    }

    private HttpClientRequest open(HttpVersion version) throws Exception {
        client = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(version)
                .setHttp2ClearTextUpgrade(false));
        return Awaits.connect(client.request(HttpMethod.POST, server.actualPort(), LoopbackHost.ADDRESS, "/upload"),
                "the request to open");
    }

    /** Reads the stream on its event loop, where the transport's state lives. */
    private static View look(InboundStreamWindow window) throws Exception {
        return onTheLoop(window, () -> new View(window.remoteOpen(), window.sendableBytes()));
    }

    /**
     * Runs {@code action} on the stream's event loop — where the transport's state lives and where
     * {@link InboundStreamWindow#ping()} must be called — and returns what it produced.
     */
    private static <T> T onTheLoop(InboundStreamWindow window, Supplier<T> action) throws Exception {
        return Awaits.connect(CompletableFuture.supplyAsync(action, window::execute),
                "the turn on the stream's event loop to run");
    }

    /** @return whether {@link EarlyResponseEnd} has logged a PING of its wait refused at the per-connection bound */
    private static boolean refusedPingLogged() {
        return SheriffDebugCapture.capturedRecords().stream()
                .anyMatch(captured -> EarlyResponseEnd.class.getName().equals(captured.getLoggerName())
                        && captured.getThrown() != null
                        && PINGS_OUTSTANDING.equals(captured.getThrown().getMessage()));
    }

    /** @return every end of a wait {@link EarlyResponseEnd} has logged, in order */
    private static List<String> endsLogged() {
        return SheriffDebugCapture.capturedRecords().stream()
                .filter(captured -> EarlyResponseEnd.class.getName().equals(captured.getLoggerName()))
                .map(SheriffDebugCapture::rendered)
                .filter(rendered -> rendered.startsWith(END_LOGGED))
                .toList();
    }

    /**
     * Starts the wait of {@link EarlyResponseEnd} on {@code request} with an ending call created here, and
     * hands back only a weak reference to that call: once this method returns, the wait is all that holds
     * it strongly. The ending call counts itself and ends the response.
     */
    private static EndingWait endWithAnEndingOnlyTheWaitHolds(HttpServerRequest request, AtomicInteger endings,
            AtomicInteger resets) {
        Supplier<Future<Void>> ending = () -> {
            endings.incrementAndGet();
            return request.response().end();
        };
        Future<Void> ended = EarlyResponseEnd.endWhenQuiet(request, ending, resets::incrementAndGet);
        return new EndingWait(ended, new WeakReference<>(ending));
    }

    /**
     * Polls, under explicit collections, until nothing holds what {@code reference} points to any longer.
     * The ceiling is a fixed one, not derived from the host's load.
     */
    private static void awaitCollectable(WeakReference<?> reference, String what) throws TimeoutException {
        Awaits.until(() -> {
            System.gc(); // NOSONAR java:S1215 reachability of the ending call is the property under test
            return reference.get() == null;
        }, what, Awaits.CONNECT_CEILING_SECONDS);
    }

    /** Sends {@code count} PINGs; to be run on the stream's event loop. */
    private static List<Future<Void>> pings(InboundStreamWindow window, int count) {
        List<Future<Void>> sent = new ArrayList<>(count);
        for (int ping = 0; ping < count; ping++) {
            sent.add(window.ping());
        }
        return sent;
    }

    /** What one look at the stream saw. */
    private record View(boolean remoteOpen, int sendable) {
    }

    /**
     * A started early-response end: the future it completes once the reset has been decided, and a weak
     * reference to the ending call it was given.
     */
    private record EndingWait(Future<Void> ended, WeakReference<Supplier<Future<Void>>> ending) {
    }

    /**
     * A prior-knowledge HTTP/2 client written byte for byte, because an HTTP/2 client library
     * acknowledges every PING itself: it opens one request stream, acknowledges the server's
     * {@code SETTINGS}, and holds every PING it receives unacknowledged until {@link #acknowledgeOne()}.
     * Frames are parsed on the socket's event loop; the counts are safe to read from the test thread.
     */
    private static final class PingHoldingClient {

        private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        private static final int FRAME_HEADER_BYTES = 9;
        private static final int HEADERS = 0x1;
        private static final int SETTINGS = 0x4;
        private static final int PING = 0x6;
        private static final int FLAG_ACK = 0x1;
        private static final int FLAG_END_HEADERS = 0x4;

        private final NetSocket socket;
        private final Queue<byte[]> unacknowledged = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pingsReceived = new AtomicInteger();
        /** Bytes received and not yet parsed into a whole frame; touched on the event loop only. */
        private Buffer pending = Buffer.buffer();

        private PingHoldingClient(NetSocket socket) {
            this.socket = socket;
            socket.handler(this::received);
            socket.exceptionHandler(cause -> {
                // The server closing the connection at teardown may reset it; nothing asserts on that.
            });
        }

        /** Connects, sends the preface, and opens stream 1 with a request head that leaves the body open. */
        static PingHoldingClient open(Vertx vertx, int port) throws Exception {
            NetSocket socket = Awaits.connect(vertx.createNetClient().connect(port, LoopbackHost.ADDRESS),
                    "the PING-holding client to connect");
            PingHoldingClient client = new PingHoldingClient(socket);
            Buffer head = Buffer.buffer(PREFACE)
                    .appendBuffer(frame(SETTINGS, 0, 0, new byte[0]))
                    .appendBuffer(frame(HEADERS, FLAG_END_HEADERS, 1, hpack(List.of(
                            Map.entry(":method", "POST"), Map.entry(":scheme", "http"),
                            Map.entry(":path", "/upload"), Map.entry(":authority", "localhost:" + port)))));
            Awaits.connect(socket.write(head), "the preface and the request head to be sent");
            return client;
        }

        /** How many PINGs the client has received in all, acknowledged or not. */
        int pingsReceived() {
            return pingsReceived.get();
        }

        /** How many PINGs the client holds unacknowledged right now. */
        int pingsHeld() {
            return unacknowledged.size();
        }

        /** Acknowledges the oldest PING the client holds. */
        Future<Void> acknowledgeOne() {
            byte[] payload = unacknowledged.remove();
            return socket.write(frame(PING, FLAG_ACK, 0, payload));
        }

        /** Acknowledges every PING the client holds, oldest first, in one write. */
        Future<Void> acknowledgeAll() {
            Buffer acknowledgements = Buffer.buffer();
            for (byte[] payload = unacknowledged.poll(); payload != null; payload = unacknowledged.poll()) {
                acknowledgements.appendBuffer(frame(PING, FLAG_ACK, 0, payload));
            }
            return socket.write(acknowledgements);
        }

        private void received(Buffer chunk) {
            pending.appendBuffer(chunk);
            int offset = 0;
            while (pending.length() - offset >= FRAME_HEADER_BYTES) {
                int length = pending.getUnsignedMedium(offset);
                if (pending.length() - offset < FRAME_HEADER_BYTES + length) {
                    break;
                }
                int type = pending.getUnsignedByte(offset + 3);
                int flags = pending.getUnsignedByte(offset + 4);
                byte[] payload = pending.getBytes(offset + FRAME_HEADER_BYTES, offset + FRAME_HEADER_BYTES + length);
                offset += FRAME_HEADER_BYTES + length;
                boolean acknowledgement = (flags & FLAG_ACK) != 0;
                if (type == SETTINGS && !acknowledgement) {
                    socket.write(frame(SETTINGS, FLAG_ACK, 0, new byte[0]));
                } else if (type == PING && !acknowledgement) {
                    unacknowledged.add(payload);
                    pingsReceived.incrementAndGet();
                }
            }
            pending = pending.getBuffer(offset, pending.length());
        }

        /** One frame: the 9-byte frame header followed by {@code payload}. */
        private static Buffer frame(int type, int flags, int streamId, byte[] payload) {
            return Buffer.buffer(FRAME_HEADER_BYTES + payload.length)
                    .appendUnsignedByte((short) (payload.length >>> 16 & 0xff))
                    .appendUnsignedByte((short) (payload.length >>> 8 & 0xff))
                    .appendUnsignedByte((short) (payload.length & 0xff))
                    .appendUnsignedByte((short) type)
                    .appendUnsignedByte((short) flags)
                    .appendInt(streamId & 0x7fffffff)
                    .appendBytes(payload);
        }

        /**
         * HPACK-encodes {@code fields} as literal header fields without indexing, new name, no Huffman
         * coding (RFC 7541 §6.2.2); every name and value here is shorter than 127 bytes, so each length
         * fits the 7-bit prefix.
         */
        private static byte[] hpack(List<Map.Entry<String, String>> fields) {
            Buffer block = Buffer.buffer();
            for (Map.Entry<String, String> field : fields) {
                byte[] name = field.getKey().getBytes(StandardCharsets.US_ASCII);
                byte[] value = field.getValue().getBytes(StandardCharsets.US_ASCII);
                block.appendUnsignedByte((short) 0x00)
                        .appendUnsignedByte((short) name.length).appendBytes(name)
                        .appendUnsignedByte((short) value.length).appendBytes(value);
            }
            return block.getBytes();
        }
    }
}
