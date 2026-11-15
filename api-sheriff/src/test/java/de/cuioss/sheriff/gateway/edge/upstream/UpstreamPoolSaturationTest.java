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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


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
import io.vertx.core.http.HttpServer;
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
 * A dispatch that cannot obtain a pooled upstream connection: the upstream client's queue of attempts
 * waiting for a connection is full, or the wait for a connection outlasted the connection wait bound. Either
 * is the gateway's own saturation, not the upstream's failure: the dispatch ends at once as a {@code 503} of
 * the upstream category, and the circuit breaker counts no failure for it. Every case runs under a guard
 * whose breaker opens on the first failure it counts, so a breaker that stays closed is a verdict, not a
 * breaker unable to open — the matched control, a connection the upstream refuses, opens it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Tag("isolated-fork")
@DisplayName("Upstream pool saturation — a dispatch that cannot obtain a pooled connection is answered 503 and is not a breaker failure")
class UpstreamPoolSaturationTest {

    private static final String HELD_PATH = "/held";

    private List<CircuitBreakerState> transitions;
    private Route route;

    @BeforeEach
    void setUpGuard() {
        transitions = new CopyOnWriteArrayList<>();
    }

    private Guard breakerOnFirstFailure() {
        return Guard.create()
                .withCircuitBreaker().requestVolumeThreshold(1).failureRatio(1.0).delay(30, ChronoUnit.SECONDS)
                .skipOn(GatewayException.class).onStateChange(transitions::add).done()
                .withTimeout().duration(30, ChronoUnit.SECONDS).done()
                .build();
    }

    private DispatchStage stageOver(ResolvedUpstream upstream, HttpClient client) {
        route = Route.builder()
                .id("saturated")
                .match(MatchConfig.builder().pathPrefix("/saturated").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .upstream(upstream)
                .build();
        UpstreamClients clients = new UpstreamClients(Map.of(UpstreamTarget.of(route), client),
                Map.of(ResilienceShape.of(route), breakerOnFirstFailure()));
        return new DispatchStage(clients, 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                new UpstreamFailureMapper(new GatewayEventCounter()));
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
     * attempt's wait for a connection ends, and records the options the attempt asked with.
     */
    @Nested
    @DisplayName("the wait for a connection")
    class ConnectionWait {

        private final AtomicReference<@Nullable RequestOptions> askedWith = new AtomicReference<>();

        private HttpClient failingClient(Throwable failure) {
            return HttpClient.class.cast(Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[]{HttpClient.class}, (proxy, method, args) -> {
                        if ("request".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof RequestOptions options) {
                            askedWith.set(options);
                            return Future.failedFuture(failure);
                        }
                        throw new AssertionError("the dispatch asked its upstream client for " + method.getName());
                    }));
        }

        private GatewayException dispatchOver(HttpClient client) throws Exception {
            DispatchStage stage = stageOver(new ResolvedUpstream("http", "upstream.example", 8080, ""), client);
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
        @DisplayName("a wait that outlasted the bound ends as a 503 of the upstream category the breaker does not count")
        void waitThatOutlastedTheBoundIsAnswered503() throws Exception {
            GatewayException ended = dispatchOver(failingClient(
                    new TimeoutException("the wait for a pooled connection timed out")));

            assertAll("a wait for a connection that outlasted the bound",
                    () -> assertEquals(503, ended.getEventType().httpStatus(), () -> "answered " + ended),
                    () -> assertEquals(EventCategory.UPSTREAM, ended.getEventType().category()),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted local saturation as a failure: " + transitions));
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
