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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A dispatch that cannot obtain a pooled upstream connection, by the two ways that can happen.
 * <ul>
 *   <li><strong>The upstream client's queue of attempts waiting for a connection is full.</strong> That is the
 *       gateway's own saturation, not the upstream's failure: the dispatch ends at once as a {@code 503} of the
 *       upstream category, and the circuit breaker counts no failure for it.</li>
 *   <li><strong>The wait for a connection outlasted the connection wait bound.</strong> The attempt queued
 *       behind connections the upstream is holding, so it is the upstream's failure: the dispatch ends as an
 *       {@link EventType#UPSTREAM_TIMEOUT} ({@code 504}) the breaker counts, and a retry-enabled route never
 *       retries it. Under load, the production guard's breaker therefore opens on an upstream that accepts but
 *       never answers, while the same load against an upstream that answers leaves it closed.</li>
 * </ul>
 * The single-dispatch cases run under a guard whose breaker opens on the first failure it counts, so a breaker
 * that stays closed is a verdict, not a breaker unable to open — and one that opens is a counted failure. The
 * retry and load cases run under the production guard ({@link UpstreamGuards#guardFor}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Tag("isolated-fork")
@DisplayName("Upstream pool saturation — a full wait queue is a 503 the breaker does not count; an expired wait is the upstream's 504 it counts")
class UpstreamPoolSaturationTest {

    private static final String HELD_PATH = "/held";

    private List<CircuitBreakerState> transitions;
    private GatewayEventCounter eventCounter;
    private UpstreamFailureMapper failureMapper;
    private Route route;

    @BeforeEach
    void setUpGuard() {
        transitions = new CopyOnWriteArrayList<>();
        eventCounter = new GatewayEventCounter();
        failureMapper = new UpstreamFailureMapper(eventCounter);
    }

    private Guard breakerOnFirstFailure() {
        return UpstreamBreakerFixtures.breakerOnFirstFailure("saturated", transitions::add);
    }

    /** A dispatch of a route without retry, under the guard whose breaker opens on the first failure it counts. */
    private DispatchStage stageOver(ResolvedUpstream upstream, HttpClient client) {
        return stageOver(upstream, client, false, shape -> breakerOnFirstFailure());
    }

    /** A dispatch under the production guard, which reports its breaker's transitions to the shared counter. */
    private DispatchStage productionStageOver(ResolvedUpstream upstream, HttpClient client, boolean retryEnabled) {
        return stageOver(upstream, client, retryEnabled, new UpstreamGuards(failureMapper)::guardFor);
    }

    private DispatchStage stageOver(ResolvedUpstream upstream, HttpClient client, boolean retryEnabled,
            Function<ResilienceShape, Guard> guardFor) {
        route = Route.builder()
                .id("saturated")
                .match(MatchConfig.builder().pathPrefix("/saturated").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .upstream(upstream)
                .retryEnabled(retryEnabled)
                .build();
        ResilienceShape shape = ResilienceShape.of(route);
        UpstreamClients clients = new UpstreamClients(Map.of(UpstreamTarget.of(route), client),
                Map.of(shape, guardFor.apply(shape)));
        return new DispatchStage(clients, 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT, failureMapper);
    }

    private static DispatchStage.BodyFraming bodyless() {
        return DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH, violation -> {
            // No case reaches a hand-over, so no late refusal can arrive.
        });
    }

    /** Dispatches one bodyless GET of {@code path}; the future completes with what the dispatch ended with. */
    private CompletableFuture<@Nullable GatewayException> dispatch(ExecutorService executor, DispatchStage stage,
            String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                stage.dispatch(route, HttpMethod.GET, path, Map.of(), new SilentStream(), bodyless());
                return null;
            } catch (GatewayException failure) {
                return failure;
            }
        }, executor);
    }

    /**
     * A real upstream that never answers, reached through a client pooling one connection and queueing
     * {@value #QUEUE_BOUND} attempts for it.
     */
    @Nested
    @DisplayName("a full wait queue")
    class FullWaitQueue {

        private static final int QUEUE_BOUND = 2;

        private Vertx vertx;
        private HttpServer upstream;
        private HttpClient client;
        private final AtomicInteger upstreamReceived = new AtomicInteger();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                upstreamReceived.incrementAndGet();
                // Received, never answered: the one pooled connection stays busy.
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            client = vertx.createHttpClient(new HttpClientOptions(),
                    new PoolOptions().setHttp1MaxSize(1).setMaxWaitQueueSize(QUEUE_BOUND));
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("the attempt beyond the bound ends at once as a 503 of the upstream category; the queued ones keep waiting and the breaker counts nothing")
        void attemptBeyondTheBoundIsAnswered503() throws Exception {
            DispatchStage stage = stageOver(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""),
                    client);
            List<CompletableFuture<@Nullable GatewayException>> dispatches = new ArrayList<>();
            GatewayException refused;
            List<Boolean> stillWaiting;
            List<CircuitBreakerState> transitionsAtTheRefusal;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    dispatches.add(dispatch(executor, stage, HELD_PATH));
                    Awaits.until(() -> upstreamReceived.get() == 1, "the first dispatch to hold the one connection",
                            Awaits.CONNECT_CEILING_SECONDS);
                    for (int queued = 0; queued <= QUEUE_BOUND; queued++) {
                        dispatches.add(dispatch(executor, stage, HELD_PATH));
                    }

                    refused = (GatewayException) Awaits.connect(
                            CompletableFuture.anyOf(dispatches.toArray(CompletableFuture[]::new)),
                            "one dispatch beyond the wait-queue bound to end");
                    stillWaiting = dispatches.stream().map(future -> !future.isDone()).toList();
                    transitionsAtTheRefusal = List.copyOf(transitions);
                } finally {
                    // Closing the client fails every attempt still waiting, so the executor can close.
                    Awaits.teardown(client.close(), "the upstream client to close");
                }
            }

            GatewayException answered = refused;
            assertAll("the attempt beyond the wait-queue bound",
                    () -> assertNotNull(answered, "the dispatch must not be answered by the upstream"),
                    () -> assertEquals(503, answered.getEventType().httpStatus(), () -> "answered " + answered),
                    () -> assertEquals(EventCategory.UPSTREAM, answered.getEventType().category()),
                    () -> assertEquals(QUEUE_BOUND + 1, stillWaiting.stream().filter(Boolean::booleanValue).count(),
                            "the dispatch holding the connection and the queued ones are still waiting"),
                    () -> assertEquals(1, upstreamReceived.get(), "nothing beyond the one connection reached the upstream"),
                    () -> assertTrue(transitionsAtTheRefusal.isEmpty(),
                            () -> "the breaker counted local saturation as a failure: " + transitionsAtTheRefusal));
        }
    }

    /**
     * An upstream client faked at {@link HttpClient#request(RequestOptions)}: the case decides how the
     * attempt's wait for a connection ends, and records how often and with which options the dispatch asked.
     */
    @Nested
    @DisplayName("the wait for a connection")
    class ConnectionWait {

        /**
         * How many times a retry-enabled route asks for a connection when every attempt fails and the retry
         * predicate allows each retry: the first attempt and two retries. Stated here as the contract the
         * retry cases pin, not read off the production constant.
         */
        private static final int ATTEMPTS_OF_A_RETRIED_DISPATCH = 3;

        private final AtomicReference<@Nullable RequestOptions> askedWith = new AtomicReference<>();
        private final AtomicInteger asked = new AtomicInteger();

        private HttpClient failingClient(Throwable failure) {
            return HttpClient.class.cast(Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[]{HttpClient.class}, (proxy, method, args) -> {
                        if ("request".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof RequestOptions options) {
                            asked.incrementAndGet();
                            askedWith.set(options);
                            return Future.failedFuture(failure);
                        }
                        throw new AssertionError("the dispatch asked its upstream client for " + method.getName());
                    }));
        }

        /** @return the timeout the upstream client reports for a wait that outlasted its connect timeout */
        private TimeoutException expiredWait() {
            return new TimeoutException("the wait for a pooled connection timed out");
        }

        private ResolvedUpstream fakedUpstream() {
            return new ResolvedUpstream("http", "upstream.example", 8080, "");
        }

        private GatewayException dispatchOver(HttpClient client) throws Exception {
            return endedDispatch(stageOver(fakedUpstream(), client));
        }

        private GatewayException dispatchOverRetryRoute(HttpClient client) throws Exception {
            return endedDispatch(productionStageOver(fakedUpstream(), client, true));
        }

        private GatewayException endedDispatch(DispatchStage stage) throws Exception {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                GatewayException ended = Awaits.connect(dispatch(executor, stage, HELD_PATH), "the dispatch to end");
                assertNotNull(ended, "the dispatch must not be answered");
                return ended;
            }
        }

        @Test
        @DisplayName("every attempt asks for its connection with the connection wait bound")
        void everyAttemptAsksWithTheWaitBound() throws Exception {
            dispatchOver(failingClient(new ConnectException("refused")));

            RequestOptions options = askedWith.get();
            assertNotNull(options, "the attempt asked for a connection");
            assertEquals(DispatchStage.UPSTREAM_CONNECTION_WAIT_MILLIS, options.getConnectTimeout(),
                    "the wait for a pooled connection is bounded below the request-body deadline");
        }

        @Test
        @DisplayName("a wait that outlasted the bound ends as the upstream's timeout, a 504 the breaker counts")
        void waitThatOutlastedTheBoundIsAnUpstreamTimeout() throws Exception {
            GatewayException ended = dispatchOver(failingClient(expiredWait()));

            assertAll("a wait for a connection that outlasted the bound",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, ended.getEventType(), () -> "answered " + ended),
                    () -> assertEquals(504, ended.getEventType().httpStatus(), "the upstream's timeout status"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the breaker opens on the one failure it counts: the expired wait"),
                    () -> assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_TIMEOUT),
                            "the expired wait is metered as the upstream's timeout"),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_POOL_SATURATED),
                            "the expired wait is not metered as the gateway's own saturation"));
        }

        @Test
        @DisplayName("control — a connection the upstream refuses is an upstream failure the breaker counts")
        void refusedConnectionIsAnUpstreamFailure() throws Exception {
            GatewayException ended = dispatchOver(failingClient(new ConnectException("refused")));

            assertAll("a refused connection",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, ended.getEventType(), "an upstream failure"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the breaker opens on the one failure it counts"));
        }

        @Test
        @DisplayName("on a retry-enabled route, a wait that outlasted the bound is asked for once: it is never retried")
        void expiredWaitIsNeverRetried() throws Exception {
            GatewayException ended = dispatchOverRetryRoute(failingClient(expiredWait()));

            assertAll("an expired wait on a retry-enabled route",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, ended.getEventType(), () -> "answered " + ended),
                    () -> assertEquals(1, asked.get(),
                            "the dispatch asked for a connection once: the retry predicate refused the expired wait"));
        }

        @Test
        @DisplayName("control — on the same retry-enabled route, a connection the upstream refuses is retried")
        void refusedConnectionIsRetried() throws Exception {
            GatewayException ended = dispatchOverRetryRoute(failingClient(new ConnectException("refused")));

            assertAll("a refused connection on a retry-enabled route",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, ended.getEventType(), () -> "answered " + ended),
                    () -> assertEquals(ATTEMPTS_OF_A_RETRIED_DISPATCH, asked.get(),
                            "the refused connection was retried: the route's retry is live for this dispatch"));
        }
    }

    /**
     * A real upstream behind a client that pools one connection and queues more attempts than the production
     * breaker's window, driven under the production guard with more concurrent dispatches than that window: the
     * first holds the one connection and every other one queues for it.
     */
    @Nested
    @DisplayName("an expired wait under load, under the production guard")
    class ExpiredWaitUnderLoad {

        /**
         * The window the production guard gives its breaker: it opens once half of this many upstream-decided
         * calls failed. Stated here as the contract the case pins, not read off the production constant.
         */
        private static final int PRODUCTION_WINDOW = 20;

        /** The concurrent dispatches: one holds the connection, the rest — more than the window — queue. */
        private static final int DISPATCHES = PRODUCTION_WINDOW + 4;

        /** The wait-queue bound: above every queued dispatch, so the queue refuses none of them. */
        private static final int QUEUE_BOUND = 2 * DISPATCHES;

        /** How long the answering upstream takes per request: far inside the connection wait bound. */
        private static final long ANSWER_DELAY_MILLIS = 20L;

        private Vertx vertx;
        private @Nullable HttpServer upstream;
        private HttpClient client;
        private final AtomicInteger upstreamReceived = new AtomicInteger();

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient(new HttpClientOptions(),
                    new PoolOptions().setHttp1MaxSize(1).setMaxWaitQueueSize(QUEUE_BOUND));
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the upstream client to close");
            if (upstream != null) {
                Awaits.teardown(upstream.close(), "the stub upstream to close");
            }
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private DispatchStage stageOverUpstream(Handler<HttpServerRequest> requestHandler) throws Exception {
            HttpServer started = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                upstreamReceived.incrementAndGet();
                requestHandler.handle(request);
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstream = started;
            return productionStageOver(new ResolvedUpstream("http", LoopbackHost.ADDRESS, started.actualPort(), ""),
                    client, false);
        }

        /**
         * Dispatches one bodyless GET and reads its response to the end, so the one pooled connection goes back
         * to the pool for the next queued attempt.
         *
         * @return the future completing with the status the upstream answered
         */
        private CompletableFuture<Integer> answeredDispatch(ExecutorService executor, DispatchStage stage) {
            return CompletableFuture.supplyAsync(() -> stage.dispatch(route, HttpMethod.GET, HELD_PATH, Map.of(),
                    new SilentStream(), bodyless()), executor).thenCompose(this::readToTheEnd);
        }

        private CompletableFuture<Integer> readToTheEnd(HttpClientResponse response) {
            Future<Buffer> body = response.body();
            // The dispatch hands its response over paused: resuming it lets the body, and the end, through.
            response.resume();
            return body.map(_ -> response.statusCode()).toCompletionStage().toCompletableFuture();
        }

        @Test
        @DisplayName("an upstream that accepts but never answers: the queued waits expire at the bound as its timeouts, open the breaker, and the next dispatch is refused unsent")
        void expiredWaitsOpenTheBreaker() throws Exception {
            DispatchStage stage = stageOverUpstream(request -> {
                // Received, never answered: the one pooled connection stays busy.
            });
            List<CompletableFuture<@Nullable GatewayException>> queued = new ArrayList<>();
            GatewayException behind;
            List<@Nullable GatewayException> queuedEnded;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    dispatch(executor, stage, HELD_PATH);
                    Awaits.until(() -> upstreamReceived.get() == 1, "the first dispatch to hold the one connection",
                            Awaits.CONNECT_CEILING_SECONDS);
                    for (int dispatched = 1; dispatched < DISPATCHES; dispatched++) {
                        queued.add(dispatch(executor, stage, HELD_PATH));
                    }

                    Awaits.until(() -> eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN) > 0,
                            "the expired waits to open the breaker", Awaits.CONNECT_CEILING_SECONDS);
                    behind = Awaits.connect(dispatch(executor, stage, HELD_PATH),
                            "the dispatch behind the open breaker to end");
                    Awaits.connect(CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)),
                            "every queued dispatch to end");
                    queuedEnded = queued.stream().map(CompletableFuture::join).toList();
                } finally {
                    // Closing the client fails the attempt still holding the connection, so the executor can close.
                    Awaits.teardown(client.close(), "the upstream client to close");
                }
            }

            GatewayException refused = behind;
            assertAll("more queued waits than the breaker's window, behind an upstream that never answers",
                    () -> assertEquals(Collections.nCopies(DISPATCHES - 1, EventType.UPSTREAM_TIMEOUT),
                            queuedEnded.stream().map(ended -> ended == null ? null : ended.getEventType()).toList(),
                            "every queued dispatch ended as the upstream's timeout"),
                    () -> assertNotNull(refused, "the dispatch behind the open breaker must not be answered"),
                    () -> assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN, refused.getEventType(),
                            () -> "the open breaker refuses the dispatch behind it: " + refused),
                    () -> assertEquals(1, upstreamReceived.get(),
                            "only the dispatch holding the connection reached the upstream; the refused one was never sent"),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_POOL_SATURATED),
                            "no expired wait was taken for the gateway's own saturation"));
        }

        @Test
        @DisplayName("a connection held by a request body still arriving: the queued waits that expire behind it are not the upstream's failures and do not open the breaker")
        void waitsBehindABodyStillArrivingLeaveTheBreakerClosed() throws Exception {
            DispatchStage stage = stageOverUpstream(request -> {
                if (request.method() == io.vertx.core.http.HttpMethod.GET) {
                    request.response().end();
                }
                // The POST's body is read as it arrives; it never ends, so the POST is never answered.
            });
            TricklingBody body = new TricklingBody(vertx);
            List<CompletableFuture<@Nullable GatewayException>> queued = new ArrayList<>();
            List<@Nullable GatewayException> queuedEnded;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    CompletableFuture.runAsync(() -> stage.dispatch(route, HttpMethod.POST, HELD_PATH, Map.of(), body,
                            DispatchStage.BodyFraming.of(HttpMethod.POST, DispatchStage.NO_DECLARED_LENGTH, violation -> {
                                // The held POST is never handed over, so no late refusal can arrive.
                            })), executor);
                    Awaits.until(() -> upstreamReceived.get() == 1 && body.emitted() > 0,
                            "the POST to hold the one connection with its body still arriving",
                            Awaits.CONNECT_CEILING_SECONDS);
                    for (int dispatched = 1; dispatched < DISPATCHES; dispatched++) {
                        queued.add(dispatch(executor, stage, HELD_PATH));
                    }

                    Awaits.connect(CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)),
                            "every queued dispatch to end");
                    queuedEnded = queued.stream().map(CompletableFuture::join).toList();
                } finally {
                    body.stop();
                    // Closing the client fails the POST still holding the connection, so the executor can close.
                    Awaits.teardown(client.close(), "the upstream client to close");
                }
            }

            assertAll("more queued waits than the breaker's window, behind a body the client is still sending",
                    () -> assertTrue(queuedEnded.stream().allMatch(ended -> ended != null
                                    && ended.getEventType() != EventType.UPSTREAM_TIMEOUT),
                            () -> "an expired wait behind a client's body was charged to the upstream: " + queuedEnded),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                            "the breaker never opened on an upstream that answers every request it was sent"),
                    () -> assertEquals(1, upstreamReceived.get(),
                            "nothing beyond the held POST reached the upstream: the waits expired unsent"));
        }

        @Test
        @DisplayName("control — the same load against an upstream that answers each request after a short timer: every wait completes inside the bound, every dispatch is answered and the breaker stays closed")
        void answeredLoadLeavesTheBreakerClosed() throws Exception {
            DispatchStage stage = stageOverUpstream(
                    request -> vertx.setTimer(ANSWER_DELAY_MILLIS, _ -> request.response().end()));
            List<CompletableFuture<Integer>> dispatches = new ArrayList<>();
            List<Integer> statuses;
            int behind;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    dispatches.add(answeredDispatch(executor, stage));
                    Awaits.until(() -> upstreamReceived.get() >= 1, "the first dispatch to reach the upstream",
                            Awaits.CONNECT_CEILING_SECONDS);
                    for (int dispatched = 1; dispatched < DISPATCHES; dispatched++) {
                        dispatches.add(answeredDispatch(executor, stage));
                    }

                    Awaits.connect(CompletableFuture.allOf(dispatches.toArray(CompletableFuture[]::new)),
                            "every dispatch to be answered");
                    statuses = dispatches.stream().map(CompletableFuture::join).toList();
                    behind = Awaits.connect(answeredDispatch(executor, stage),
                            "the dispatch behind them to be answered");
                } finally {
                    Awaits.teardown(client.close(), "the upstream client to close");
                }
            }

            int answeredBehind = behind;
            assertAll("as many dispatches against an upstream that answers",
                    () -> assertEquals(Collections.nCopies(DISPATCHES, 200), statuses,
                            "every dispatch was answered by the upstream"),
                    () -> assertEquals(200, answeredBehind, "the dispatch behind them was answered: the breaker is closed"),
                    () -> assertEquals(DISPATCHES + 1, upstreamReceived.get(), "every dispatch reached the upstream"),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_TIMEOUT),
                            "no wait expired"),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                            "the breaker never opened"));
        }
    }

    /**
     * A request body still arriving: one byte every {@value #TRICKLE_MILLIS} ms while it is flowing, and never an
     * end — a client sending its body slowly, well inside every per-byte bound.
     */
    private static final class TricklingBody implements ReadStream<Buffer> {

        private static final long TRICKLE_MILLIS = 200L;

        private final Vertx vertx;
        private final long timer;
        private final AtomicInteger emitted = new AtomicInteger();
        private volatile @Nullable Handler<Buffer> handler;
        private volatile boolean paused;

        private TricklingBody(Vertx vertx) {
            this.vertx = vertx;
            this.timer = vertx.setPeriodic(TRICKLE_MILLIS, _ -> {
                Handler<Buffer> current = handler;
                if (current != null && !paused) {
                    emitted.incrementAndGet();
                    current.handle(Buffer.buffer("x"));
                }
            });
        }

        int emitted() {
            return emitted.get();
        }

        void stop() {
            vertx.cancelTimer(timer);
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> exceptionHandler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> bodyHandler) {
            this.handler = bodyHandler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            paused = false;
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            paused = false;
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            return this;
        }
    }

    /** A request body that never emits anything; a bodyless dispatch never subscribes it. */
    private static final class SilentStream implements ReadStream<Buffer> {

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            return this;
        }
    }
}
