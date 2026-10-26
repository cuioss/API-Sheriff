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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
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
 * release.
 * <p>
 * Every negative is observed by a probe timer scheduled on the same event loop for after the release,
 * so it is decided by timer order rather than by a sleep.
 */
@EnableGeneratorController
@DisplayName("ResponseDelay — an answer is written no earlier than its release time, from a timer")
@Tag("isolated-fork")
class ResponseDelayTest {

    private static final String ANSWER_BODY = "released";

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
            ResponseDelay.endNoEarlierThan(ctx, System.nanoTime() - 1, () -> answer(ctx));
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
            ResponseDelay.endNoEarlierThan(ctx, calledAt + TimeUnit.MILLISECONDS.toNanos(holdMillis), () -> {
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
            ResponseDelay.endNoEarlierThan(ctx, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMillis),
                    () -> answer(ctx));
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
            ResponseDelay.endNoEarlierThan(ctx, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMillis),
                    () -> answer(ctx));
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

    /** The held answer: counts itself and ends the response through the one end rule. */
    private void answer(RoutingContext ctx) {
        answers.incrementAndGet();
        ResponseEnds.endResponse(ctx, () -> ctx.response().end(ANSWER_BODY));
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
