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
package de.cuioss.sheriff.gateway.edge.upstream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The release of a bodyless {@link DispatchStage.UpstreamAttempt} against the real Vert.x HTTP/1.1 client
 * pool, at the one moment that decides whether the pool stays usable: the release is asked for on the
 * upstream connection's own event loop while the attempt's head write, sent from the request's context, is
 * still queued on that loop.
 * <p>
 * <strong>Why the moment matters.</strong> A reset of the request run on that loop ahead of the queued write
 * is applied first: Vert.x returns the connection to the pool as unused, and the queued write then puts the
 * request on it all the same. The upstream never answers that request, so the next request handed the
 * connection is pipelined behind it and is never answered either. In the edge the release is a client's
 * departure, reported on the inbound connection's event loop, and that loop can be the upstream
 * connection's: Vert.x draws both from one event-loop group.
 * <p>
 * <strong>The order is forced, not raced.</strong> The client runs on two event loops. The one pooled
 * connection is created on the first; the attempt's request is obtained on the second, where the send is
 * made. Before sending, the request's callback blocks the connection's loop with a task that asks for the
 * release once the send has been made, so the head write is queued behind that task on every run.
 * <p>
 * <strong>What the stub upstream does.</strong> A request to a path under {@value #HELD} is received and
 * never answered, and the closing of its connection is recorded; every other request is answered
 * {@code ok}.
 */
@EnableGeneratorController
@DisplayName("UpstreamAttempt — the release of a bodyless attempt against its queued head write")
@Tag("isolated-fork")
class UpstreamAttemptReleaseOrderTest {

    /** The upstream path prefix the stub receives and never answers. */
    private static final String HELD = "/held";
    /** How many fresh contexts are tried for one on the other event loop of the two. */
    private static final int CONTEXT_ATTEMPTS = 16;

    private Vertx vertx;
    private HttpServer upstream;
    private HttpClient client;
    /** Every request path the stub upstream received a head for. */
    private final List<String> received = new CopyOnWriteArrayList<>();
    /** Every held request path whose connection closed: a request the client side released. */
    private final List<String> released = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        upstream = Awaits.connect(vertx.createHttpServer().requestHandler(this::handleUpstreamRequest)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        // One pooled connection, closed abortively, as the gateway's default upstream client does.
        client = vertx.createHttpClient(new HttpClientOptions().setSoLinger(0), new PoolOptions().setHttp1MaxSize(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the upstream client to close");
        Awaits.teardown(upstream.close(), "the stub upstream server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    private void handleUpstreamRequest(HttpServerRequest request) {
        String path = request.path();
        received.add(path);
        boolean held = path.startsWith(HELD);
        if (held) {
            // A held request is never answered, so its response can only close: its connection was closed.
            request.response().closeHandler(closed -> released.add(path));
        }
        request.endHandler(end -> {
            if (!held) {
                request.response().end("ok");
            }
        });
    }

    @Test
    @DisplayName("a release on the connection's event loop ahead of the queued head write leaves no request behind on the pooled connection")
    void releaseAheadOfTheQueuedHeadWriteLeavesThePoolUsable() throws Exception {
        Context connectionContext = newContext();
        Context requestContext = contextOnTheOtherEventLoop(connectionContext);
        String heldPath = HELD + "-" + Generators.letterStrings(8, 16).next();
        int warmStatus = statusOfARequestFrom(connectionContext);
        CompletableFuture<Void> sent = new CompletableFuture<>();

        requestContext.runOnContext(onRequestContext -> client.request(options(heldPath)).onComplete(obtained -> {
            if (obtained.failed()) {
                sent.completeExceptionally(obtained.cause());
                return;
            }
            HttpClientRequest request = obtained.result();
            request.exceptionHandler(failure -> {
                // The release fails the request: expected.
            });
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.publish(request, true);
            CountDownLatch headWriteQueued = new CountDownLatch(1);
            connectionContext.runOnContext(onConnectionLoop -> {
                awaitOnTheLoop(headWriteQueued);
                attempt.abandon();
            });
            request.send();
            headWriteQueued.countDown();
            sent.complete(null);
        }));
        Awaits.connect(sent, "the held request to be sent");
        Awaits.until(() -> released.contains(heldPath), "the released request's upstream connection to be closed",
                Awaits.ADMISSION_RELEASE_CEILING_SECONDS);
        int freshStatus = statusOfARequestFrom(requestContext);

        assertAll("a bodyless attempt released ahead of its queued head write",
                () -> assertEquals(200, warmStatus, "the pooled connection is created on the first event loop"),
                () -> assertTrue(received.contains(heldPath), "the held request reached the upstream"),
                () -> assertTrue(released.contains(heldPath), "the connection carrying it was closed"),
                () -> assertEquals(200, freshStatus, "the next request on the pool is answered"));
    }

    private RequestOptions options(String path) {
        return new RequestOptions().setMethod(HttpMethod.GET).setHost(LoopbackHost.ADDRESS)
                .setPort(upstream.actualPort()).setURI(path);
    }

    /** Sends a {@code GET} the stub answers at once, from {@code context}, and returns its status. */
    private int statusOfARequestFrom(Context context) throws Exception {
        CompletableFuture<Integer> status = new CompletableFuture<>();
        context.runOnContext(onContext -> client.request(options("/ok"))
                .compose(request -> request.send().map(HttpClientResponse::statusCode))
                .onComplete(answered -> {
                    if (answered.succeeded()) {
                        status.complete(answered.result());
                    } else {
                        status.completeExceptionally(answered.cause());
                    }
                }));
        return Awaits.connect(status, "a request to the stub upstream to be answered");
    }

    /** A new event-loop context: Vert.x creates one per thread that asks outside any context. */
    private Context newContext() throws Exception {
        try (ExecutorService thread = Executors.newVirtualThreadPerTaskExecutor()) {
            return Awaits.connect(thread.submit(vertx::getOrCreateContext), "a new Vert.x context");
        }
    }

    /** A new context whose event loop is not the one {@code other} runs on. */
    private Context contextOnTheOtherEventLoop(Context other) throws Exception {
        Thread otherLoop = eventLoopOf(other);
        Context candidate = newContext();
        for (int attempt = 1; attempt < CONTEXT_ATTEMPTS && eventLoopOf(candidate) == otherLoop; attempt++) {
            candidate = newContext();
        }
        assertNotSame(otherLoop, eventLoopOf(candidate), "a context on the other event loop");
        return candidate;
    }

    private static Thread eventLoopOf(Context context) throws Exception {
        CompletableFuture<Thread> loop = new CompletableFuture<>();
        context.runOnContext(onContext -> loop.complete(Thread.currentThread()));
        return Awaits.connect(loop, "a task to run on the context");
    }

    /** Holds the calling event loop until {@code latch} opens, which the test opens at once. */
    private static void awaitOnTheLoop(CountDownLatch latch) {
        try {
            latch.await(Awaits.TEARDOWN_CEILING_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
