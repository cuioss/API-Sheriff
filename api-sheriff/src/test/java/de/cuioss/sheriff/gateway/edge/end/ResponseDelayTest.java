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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The edge's delay primitive, driven over a live Vert.x server: an answer whose release time has
 * passed is written at once, inside the call; an answer not yet due is written from a Vert.x timer no
 * earlier than its release time, while the calling event-loop thread returns at once; and a held
 * answer is dropped when another writer ended the response, or the connection closed, before the
 * release. An answer that has to wait first enters the hold the edge stashed under
 * {@link ResponseDelay#HOLD_KEY}: an entered hold arms the timer, a refused one is answered {@code 503}
 * no earlier than its refusal time — at once when that has passed, from a timer otherwise — and a
 * request that carries no hold waits as before. A held answer does not outlive its response: once the
 * connection closes, the timer no longer holds the answer, whether the close came while the answer
 * waited or before it was armed.
 * <p>
 * Every negative is observed by a probe timer scheduled on the same event loop for after the release,
 * so it is decided by timer order rather than by a sleep. That an answer is no longer held is observed
 * through a weak reference to the body it would have written, polled under explicit collections: the
 * release is set far beyond the poll's ceiling, so a timer still pending keeps the body reachable for the
 * whole poll.
 */
@EnableGeneratorController
@DisplayName("ResponseDelay — an answer is written no earlier than its release time, from a timer")
@Tag("isolated-fork")
class ResponseDelayTest {

    private static final String ANSWER_BODY = "released";
    /**
     * How far ahead the release of an answer whose connection closes is set, in minutes: far beyond the
     * poll that watches the answer being let go, so only the close can end its hold within that poll.
     */
    private static final long RELEASE_AFTER_THE_POLL_MINUTES = 10;

    /** How long a not-yet-due answer is held, in milliseconds. */
    private final long holdMillis = Generators.integers(100, 200).next();
    /** Counts the held answers that actually ran. */
    private final AtomicInteger answers = new AtomicInteger();

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    /** What the server does with the next request, set by each test before it sends one. */
    private volatile Consumer<RoutingContext> behaviour = ctx -> ctx.response().setStatusCode(500).end();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(ctx -> behaviour.accept(ctx));
        server = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the delay test server to start listening");
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(server.close(), "the delay test server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("an answer whose release time has passed is written at once, inside the call")
    void dueAnswerIsWrittenAtOnce() throws Exception {
        CompletableFuture<Boolean> ranInsideTheCall = new CompletableFuture<>();
        behaviour = ctx -> {
            long due = System.nanoTime() - 1;
            ResponseDelay.endNoEarlierThan(ctx, due, due, () -> answer(ctx));
            ranInsideTheCall.complete(answers.get() == 1);
        };

        int status = get();

        assertAll("a due answer",
                () -> assertEquals(200, status),
                () -> assertTrue(Awaits.connect(ranInsideTheCall, "the handler to return"),
                        "the answer ran before endNoEarlierThan returned"));
    }

    @Test
    @DisplayName("an answer not yet due is written from a timer no earlier than its release, and the caller returns at once")
    void heldAnswerIsWrittenFromATimerAfterItsRelease() throws Exception {
        CompletableFuture<Boolean> returnedBeforeTheAnswer = new CompletableFuture<>();
        CompletableFuture<Long> heldForNanos = new CompletableFuture<>();
        CompletableFuture<Boolean> answeredOnTheEventLoop = new CompletableFuture<>();
        behaviour = ctx -> {
            long calledAt = System.nanoTime();
            long releaseAt = calledAt + TimeUnit.MILLISECONDS.toNanos(holdMillis);
            ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> {
                heldForNanos.complete(System.nanoTime() - calledAt);
                answeredOnTheEventLoop.complete(Context.isOnEventLoopThread());
                answer(ctx);
            });
            returnedBeforeTheAnswer.complete(answers.get() == 0);
        };

        int status = get();

        long held = Awaits.connect(heldForNanos, "the held answer to run");
        assertAll("a held answer",
                () -> assertEquals(200, status),
                () -> assertTrue(Awaits.connect(returnedBeforeTheAnswer, "the handler to return"),
                        "the calling thread returned before the answer ran: nothing slept in the call"),
                () -> assertTrue(held >= TimeUnit.MILLISECONDS.toNanos(holdMillis),
                        () -> "the answer ran " + held + " ns after the call, before its release of " + holdMillis + " ms"),
                () -> assertTrue(Awaits.connect(answeredOnTheEventLoop, "the held answer to run"),
                        "the answer is run by a Vert.x timer on the event loop, not by a parked thread"));
    }

    @Test
    @DisplayName("a held answer is not written once another writer has ended the response")
    void heldAnswerIsDroppedWhenTheResponseWasEnded() throws Exception {
        CompletableFuture<Integer> answersAfterTheRelease = new CompletableFuture<>();
        behaviour = ctx -> {
            long releaseAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMillis);
            ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> answer(ctx));
            probeAfterTheRelease(answersAfterTheRelease);
            ctx.response().setStatusCode(503).end();
        };

        int status = get();

        assertAll("an answer another writer overtook",
                () -> assertEquals(503, status, "the other writer's answer stands"),
                () -> assertEquals(0, Awaits.connect(answersAfterTheRelease, "the probe after the release"),
                        "the held answer was not run on an ended response"));
    }

    @Test
    @DisplayName("a held answer is not written once its connection has closed")
    void heldAnswerIsDroppedWhenTheConnectionClosed() throws Exception {
        CompletableFuture<Void> answerHeld = new CompletableFuture<>();
        CompletableFuture<Integer> answersAfterTheRelease = new CompletableFuture<>();
        behaviour = ctx -> {
            long releaseAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMillis);
            ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> answer(ctx));
            probeAfterTheRelease(answersAfterTheRelease);
            answerHeld.complete(null);
        };
        HttpClientRequest request = Awaits.connect(client.request(options()), "the request to be opened");

        request.send();
        Awaits.connect(answerHeld, "the server to hold its answer");
        Awaits.connect(request.connection().close(), "the client connection to close");

        assertEquals(0, Awaits.connect(answersAfterTheRelease, "the probe after the release"),
                "the held answer was not run on a closed connection");
    }

    @Test
    @DisplayName("a held answer is let go once its connection closes, not kept until its release")
    void heldAnswerIsLetGoWhenTheConnectionCloses() throws Exception {
        CompletableFuture<WeakReference<byte[]>> heldBody = new CompletableFuture<>();
        behaviour = ctx -> {
            byte[] body = ANSWER_BODY.getBytes(StandardCharsets.UTF_8);
            long releaseAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(RELEASE_AFTER_THE_POLL_MINUTES);
            ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> answer(ctx, body));
            heldBody.complete(new WeakReference<>(body));
        };
        HttpClientRequest request = Awaits.connect(client.request(options()), "the request to be opened");

        request.send();
        WeakReference<byte[]> body = Awaits.connect(heldBody, "the server to hold its answer");
        Awaits.connect(request.connection().close(), "the client connection to close");

        awaitLetGo(body, "the held answer to be let go once its connection closed");

        assertNull(body.get(), "the held answer is unreachable once its connection closed");
    }

    @Test
    @DisplayName("an answer armed after its connection closed is not held until its release")
    void answerArmedAfterTheConnectionClosedIsNotHeld() throws Exception {
        CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        CompletableFuture<WeakReference<byte[]>> armedBody = new CompletableFuture<>();
        behaviour = ctx -> {
            // Armed in a later event-loop task, after the close has run the request's end handlers — the
            // order in which the edge's writer arms an answer whose client left during processing.
            ctx.addEndHandler(gone -> ctx.vertx().runOnContext(v -> {
                byte[] body = ANSWER_BODY.getBytes(StandardCharsets.UTF_8);
                long releaseAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(RELEASE_AFTER_THE_POLL_MINUTES);
                ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> answer(ctx, body));
                armedBody.complete(new WeakReference<>(body));
            }));
            requestReceived.complete(null);
        };
        HttpClientRequest request = Awaits.connect(client.request(options()), "the request to be opened");

        request.send();
        Awaits.connect(requestReceived, "the server to receive the request");
        Awaits.connect(request.connection().close(), "the client connection to close");
        WeakReference<byte[]> body = Awaits.connect(armedBody, "the answer to be armed after the close");

        awaitLetGo(body, "the answer armed after the close to be let go");

        assertNull(body.get(), "the answer armed after the close is unreachable");
    }

    @Test
    @DisplayName("an answer not yet due enters the hold once, then is written from a timer after its release")
    void enteredHoldArmsTheTimer() throws Exception {
        AtomicInteger holdsEntered = new AtomicInteger();
        CompletableFuture<Long> heldForNanos = new CompletableFuture<>();
        behaviour = ctx -> {
            ctx.put(ResponseDelay.HOLD_KEY, (ResponseDelay.Hold) () -> {
                holdsEntered.incrementAndGet();
                return true;
            });
            long calledAt = System.nanoTime();
            long releaseAt = calledAt + TimeUnit.MILLISECONDS.toNanos(holdMillis);
            ResponseDelay.endNoEarlierThan(ctx, releaseAt, releaseAt, () -> {
                heldForNanos.complete(System.nanoTime() - calledAt);
                answer(ctx);
            });
        };

        int status = get();

        long held = Awaits.connect(heldForNanos, "the held answer to run");
        assertAll("an answer that entered the hold",
                () -> assertEquals(200, status, "the held answer is written"),
                () -> assertEquals(1, holdsEntered.get(), "the hold is entered exactly once, before the timer"),
                () -> assertTrue(held >= TimeUnit.MILLISECONDS.toNanos(holdMillis),
                        () -> "the answer ran " + held + " ns after the call, before its release of " + holdMillis + " ms"));
    }

    @Test
    @DisplayName("a refused hold whose refusal time has passed is answered 503 at once, and the held answer never runs")
    void refusedHoldWithADueRefusalIsAnswered503AtOnce() throws Exception {
        CompletableFuture<Boolean> endedInsideTheCall = new CompletableFuture<>();
        CompletableFuture<Integer> answersAfterTheRelease = new CompletableFuture<>();
        behaviour = ctx -> {
            ctx.put(ResponseDelay.HOLD_KEY, (ResponseDelay.Hold) () -> false);
            long calledAt = System.nanoTime();
            ResponseDelay.endNoEarlierThan(ctx, calledAt + TimeUnit.MILLISECONDS.toNanos(holdMillis), calledAt - 1,
                    () -> answer(ctx));
            endedInsideTheCall.complete(ctx.response().ended());
            probeAfterTheRelease(answersAfterTheRelease);
        };

        int status = get();

        assertAll("an answer refused a held permit, its refusal already due",
                () -> assertEquals(503, status, "the full sub-budget is answered 503"),
                () -> assertTrue(Awaits.connect(endedInsideTheCall, "the handler to return"),
                        "the 503 was written before endNoEarlierThan returned: its refusal time had passed"),
                () -> assertEquals(0, Awaits.connect(answersAfterTheRelease, "the probe after the release"),
                        "the held answer never runs"));
    }

    @Test
    @DisplayName("a refused hold is answered 503 from a timer no earlier than its refusal time, before the answer's release")
    void refusedHoldIsAnswered503NoEarlierThanItsRefusalTime() throws Exception {
        CompletableFuture<Boolean> openWhenTheCallReturned = new CompletableFuture<>();
        CompletableFuture<Long> refusedAfterNanos = new CompletableFuture<>();
        CompletableFuture<Boolean> endedBeforeTheRelease = new CompletableFuture<>();
        CompletableFuture<Integer> answersAfterTheRelease = new CompletableFuture<>();
        long releaseMillis = holdMillis * 4;
        behaviour = ctx -> {
            ctx.put(ResponseDelay.HOLD_KEY, (ResponseDelay.Hold) () -> false);
            long calledAt = System.nanoTime();
            ctx.addHeadersEndHandler(v -> refusedAfterNanos.complete(System.nanoTime() - calledAt));
            ResponseDelay.endNoEarlierThan(ctx, calledAt + TimeUnit.MILLISECONDS.toNanos(releaseMillis),
                    calledAt + TimeUnit.MILLISECONDS.toNanos(holdMillis), () -> answer(ctx));
            openWhenTheCallReturned.complete(!ctx.response().ended());
            // Timers on one event loop fire in deadline order: this one is due after the refusal time
            // and before the answer's release.
            vertx.setTimer(holdMillis * 2, timerId -> endedBeforeTheRelease.complete(ctx.response().ended()));
            vertx.setTimer(releaseMillis * 2, timerId -> answersAfterTheRelease.complete(answers.get()));
        };

        int status = get();

        long refusedAfter = Awaits.connect(refusedAfterNanos, "the refusal to be written");
        assertAll("an answer refused a held permit, its refusal not yet due",
                () -> assertEquals(503, status, "the full sub-budget is answered 503"),
                () -> assertTrue(Awaits.connect(openWhenTheCallReturned, "the handler to return"),
                        "the 503 was not written inside the call: its refusal time had not passed"),
                () -> assertTrue(refusedAfter >= TimeUnit.MILLISECONDS.toNanos(holdMillis),
                        () -> "the 503 was written " + refusedAfter + " ns after the call, before its refusal time of "
                                + holdMillis + " ms"),
                () -> assertTrue(Awaits.connect(endedBeforeTheRelease, "the probe between refusal and release"),
                        "the 503 is written at the refusal time, not held to the answer's later release"),
                () -> assertEquals(0, Awaits.connect(answersAfterTheRelease, "the probe after the release"),
                        "the held answer never runs"));
    }

    @Test
    @DisplayName("an answer already due is written at once without entering the hold")
    void dueAnswerDoesNotEnterTheHold() throws Exception {
        AtomicInteger holdsEntered = new AtomicInteger();
        behaviour = ctx -> {
            ctx.put(ResponseDelay.HOLD_KEY, (ResponseDelay.Hold) () -> {
                holdsEntered.incrementAndGet();
                return false;
            });
            long due = System.nanoTime() - 1;
            ResponseDelay.endNoEarlierThan(ctx, due, due, () -> answer(ctx));
        };

        int status = get();

        assertAll("a due answer beside a hold that would refuse",
                () -> assertEquals(200, status, "the due answer is written, not refused"),
                () -> assertEquals(0, holdsEntered.get(), "nothing waits, so no hold is entered"));
    }

    /** The held answer: counts itself and ends the response through the one end rule. */
    private void answer(RoutingContext ctx) {
        answers.incrementAndGet();
        ResponseEnds.endResponse(ctx, () -> ctx.response().end(ANSWER_BODY));
    }

    /** The held answer writing {@code body}: what the answer holds while it waits is its own body. */
    private void answer(RoutingContext ctx, byte[] body) {
        answers.incrementAndGet();
        ResponseEnds.endResponse(ctx, () -> ctx.response().end(Buffer.buffer(body)));
    }

    /**
     * Polls, under explicit collections, until nothing holds {@code body} any longer. A timer still
     * pending for its answer holds it until the release, which lies beyond the poll's ceiling.
     */
    private static void awaitLetGo(WeakReference<byte[]> body, String what) throws TimeoutException {
        Awaits.until(() -> {
            System.gc(); // NOSONAR java:S1215 reachability of the held body is the property under test
            return body.get() == null;
        }, what, Awaits.CONNECT_CEILING_SECONDS);
    }

    /** Reads the answer count from a timer on the same event loop, due well after the held answer's. */
    private void probeAfterTheRelease(CompletableFuture<Integer> answersAfterTheRelease) {
        vertx.setTimer(holdMillis * 2, timerId -> answersAfterTheRelease.complete(answers.get()));
    }

    private RequestOptions options() {
        return new RequestOptions().setHost(LoopbackHost.ADDRESS).setPort(server.actualPort())
                .setMethod(HttpMethod.GET).setURI("/held");
    }

    private int get() throws Exception {
        return Awaits.connect(client.request(options())
                        .compose(HttpClientRequest::send)
                        .compose(response -> response.body().map(ignored -> response.statusCode())),
                "the response to the held request");
    }
}
