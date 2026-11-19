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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import de.cuioss.sheriff.gateway.edge.error.RejectionReporter;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The client-departure channel of {@link DispatchStage.InboundBody}: how a response that ended without
 * being answered reaches the dispatch that is still waiting for its upstream answer. The end-to-end
 * effect — the upstream request released at once — is pinned by {@code GatewayEdgeHttp2AbuseTest}.
 * <p>
 * What the dispatch ends with is the client's abort: a {@link DispatchStage.ClientDeparted} for a
 * request whose body had nothing left to arrive, a {@code body-stream-failed} abort for one whose body
 * was still arriving — both {@link EventType#INBOUND_BODY_ABORTED}, both refused by the retry predicate.
 * A departure that releases an attempt still waiting for its pooled upstream connection ends the same
 * way at once, without waiting for the pool to hand the connection over; a connection handed over later
 * is reset unsent, and the circuit breaker counts no failure for it. What ended the attempt before the
 * client left takes precedence over the departure: a body refusal recorded first is what the dispatch
 * ends with, and an upstream stall claimed first stays the upstream's timeout, which the breaker counts. And
 * {@link RejectionReporter} records the two, and a gRPC call whose next complete message did not arrive
 * within the message bound ({@link DispatchStage.RequestMessageTimeout}), each under its own disposition:
 * counted per disposition on {@code sheriff_inbound_body_aborts_total}, and latched per route and
 * disposition, so the first end of each disposition on a route is its own {@code INFO}.
 */
@EnableGeneratorController
@EnableTestLogger(debug = RejectionReporter.class)
@Tag("isolated-fork")
@DisplayName("InboundBody — a client departure reaches the dispatch once, ends it as the client's abort, and is reported as client-left")
class InboundBodyDepartureTest {

    private static Throwable departureCause() {
        return new IllegalStateException(Generators.letterStrings(8, 16).next());
    }

    @Test
    @DisplayName("a departure recorded before the dispatch registers is replayed to it on registration")
    void departureBeforeRegistrationIsReplayed() {
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(new SilentStream());
        Throwable cause = departureCause();
        List<Throwable> delivered = new CopyOnWriteArrayList<>();

        body.clientDeparted(cause);
        body.departureHandler(delivered::add);

        assertAll("a departure before registration",
                () -> assertSame(cause, body.departure(), "the departure is recorded"),
                () -> assertEquals(List.of(cause), delivered, "the late handler receives it once"));
    }

    @Test
    @DisplayName("a departure reaches a registered handler once; a second departure is ignored")
    void departureIsDeliveredOnce() {
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(new SilentStream());
        Throwable first = departureCause();
        List<Throwable> delivered = new CopyOnWriteArrayList<>();
        body.departureHandler(delivered::add);

        body.clientDeparted(first);
        body.clientDeparted(departureCause());

        assertAll("two departures",
                () -> assertSame(first, body.departure(), "the first departure stands"),
                () -> assertEquals(List.of(first), delivered, "the handler receives the first one only"));
    }

    @Test
    @DisplayName("control — a stream the gateway reset itself records no departure")
    void gatewayResetStreamRecordsNoDeparture() {
        SilentStream delegate = new SilentStream();
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(delegate);
        List<Throwable> delivered = new CopyOnWriteArrayList<>();
        body.departureHandler(delivered::add);

        body.resetByGateway();
        body.clientDeparted(departureCause());

        assertAll("a departure after the gateway's own reset",
                () -> assertNull(body.departure(), "no departure is recorded"),
                () -> assertTrue(delivered.isEmpty(), "nothing is delivered"),
                () -> assertTrue(delegate.paused, "the gateway's reset paused the body"));
    }

    /**
     * A dispatch whose client left before it began, driven through {@link DispatchStage#dispatch} over a
     * route whose upstream client fails the case if it is asked for anything: the abort is decided before
     * any upstream request exists.
     */
    @Nested
    @DisplayName("the dispatch a departed client asked for ends as the client's abort")
    class DepartureAbort {

        private Route route;
        private DispatchStage stage;
        private Guard guard;

        @BeforeEach
        void setUp() {
            route = departedRoute();
            guard = timeoutGuard();
            stage = departedStage(route, guard);
        }

        @Test
        @DisplayName("a bodyless request ends as a ClientDeparted, an INBOUND_BODY_ABORTED carrying the departure")
        void bodylessRequestEndsAsClientDeparted() {
            Throwable cause = departureCause();

            GatewayException abort = dispatchAfterDeparture(stage, route, cause, bodyless());

            assertAll("the abort of a client that left with nothing left to send",
                    () -> assertInstanceOf(DispatchStage.ClientDeparted.class, abort),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED, abort.getEventType()),
                    () -> assertSame(cause, abort.getCause(), "the departure is kept as the cause"));
        }

        @Test
        @DisplayName("a request whose body was still arriving ends as the body-stream-failed abort, not a ClientDeparted")
        void bodyStillArrivingEndsAsBodyStreamFailed() {
            Throwable cause = departureCause();

            GatewayException abort = dispatchAfterDeparture(stage, route, cause, streamed());

            assertAll("the abort of a client that left mid-body",
                    () -> assertFalse(abort instanceof DispatchStage.ClientDeparted, "told apart by its type"),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED, abort.getEventType()),
                    () -> assertEquals(DispatchStage.ByteCappedBodyStream.INBOUND_BODY_FAILED, abort.getMessage()),
                    () -> assertSame(cause, abort.getCause(), "the departure is kept as the cause"));
        }

        @Test
        @DisplayName("the retry predicate refuses both aborts, where it lets a transport failure of the same dispatch retry")
        void neitherAbortIsRetried() {
            GatewayException departed = dispatchAfterDeparture(stage, route, departureCause(), bodyless());
            GatewayException bodyFailed = dispatchAfterDeparture(stage, route, departureCause(), streamed());
            Map<String, Boolean> verdicts = new LinkedHashMap<>();
            StreamAwareRetryGate retryGate = new StreamAwareRetryGate(true);

            // The verdicts are read inside a guarded call, where the dispatch's retry state is bound; outside
            // one the predicate refuses everything, so a refusal there would prove nothing.
            Callable<HttpClientResponse> readVerdicts = () -> {
                verdicts.put("transport failure", DispatchStage.allowsRetryAfter(
                        new IllegalStateException(Generators.letterStrings(8, 16).next())));
                verdicts.put("client-left", DispatchStage.allowsRetryAfter(departed));
                verdicts.put("client-left, wrapped", DispatchStage.allowsRetryAfter(
                        new ExecutionException(departed)));
                verdicts.put("body-stream-failed", DispatchStage.allowsRetryAfter(bodyFailed));
                throw departed;
            };
            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.guardedDispatch(guard,
                    retryGate, HttpMethod.GET, () -> 0L, () -> false, readVerdicts));

            assertAll("the retry verdicts on an idempotent request that sent no body byte",
                    () -> assertEquals(Boolean.TRUE, verdicts.get("transport failure"),
                            "control: the same dispatch state lets a transport failure retry"),
                    () -> assertEquals(Boolean.FALSE, verdicts.get("client-left")),
                    () -> assertEquals(Boolean.FALSE, verdicts.get("client-left, wrapped"),
                            "the departure is found in the cause chain"),
                    () -> assertEquals(Boolean.FALSE, verdicts.get("body-stream-failed")),
                    () -> assertSame(departed, thrown, "the abort leaves the guard as itself, the client's"));
        }
    }

    /**
     * A dispatch whose attempt is still waiting for a pooled upstream connection, driven through
     * {@link DispatchStage#dispatch} over an upstream client that hands the attempt its request only when
     * the case completes it, and under a guard whose breaker opens on the first failure it counts. The
     * case decides the order: the attempt asks for its connection, then the client leaves or the
     * connection fails, then the request is handed out or the connection is refused — before the dispatch
     * ended, or only after it.
     * <p>
     * The matched control is a connection that fails without any departure: the same rig ends it as an
     * upstream failure and the breaker opens on it, so a breaker that stays closed after a departure is a
     * verdict of the rig, not a breaker unable to open.
     */
    @Nested
    @DisplayName("a departure that releases an attempt waiting for its pooled connection ends as the client's abort")
    class ReleasedWhileWaitingForAConnection {

        private Route route;
        private List<CircuitBreakerState> transitions;
        private Promise<HttpClientRequest> connection;
        private CountDownLatch requested;
        private DispatchStage stage;
        private DispatchStage.InboundBody body;

        @BeforeEach
        void setUp() {
            route = departedRoute();
            transitions = new CopyOnWriteArrayList<>();
            connection = Promise.promise();
            requested = new CountDownLatch(1);
            Guard breakerOnFirstFailure = UpstreamBreakerFixtures.breakerOnFirstFailure(route.id(), transitions::add);
            UpstreamClients clients = new UpstreamClients(
                    Map.of(UpstreamTarget.of(route), pendingClient(connection, requested)),
                    Map.of(ResilienceShape.of(route), breakerOnFirstFailure));
            stage = new DispatchStage(clients, 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
            body = DispatchStage.InboundBody.arm(new SilentStream());
        }

        @Test
        @DisplayName("the dispatch ends as a ClientDeparted, its request is reset unsent, and the breaker counts no failure")
        void departureWhileWaitingForAConnectionIsTheClientsAbort() throws Exception {
            Throwable cause = departureCause();
            UnsentRequest handedOut = new UnsentRequest();

            GatewayException ended = dispatchWhile(() -> {
                body.clientDeparted(cause);
                connection.complete(handedOut.proxy());
            });

            assertAll("an attempt released by the client while it waited for its connection",
                    () -> assertInstanceOf(DispatchStage.ClientDeparted.class, ended,
                            () -> "the client's abort, not an upstream failure: " + ended),
                    () -> assertSame(cause, ended.getCause(), "the departure is kept as the cause"),
                    () -> assertTrue(handedOut.resets.get() > 0, "the request handed out late is reset"),
                    () -> assertEquals(List.of(), handedOut.unexpected, "the request is never sent"),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted the departure as a failure: " + transitions));
        }

        /**
         * The departure alone ends the dispatch: the connection is still pending when the dispatch has ended,
         * and is handed out only afterwards. A dispatching thread that waited on the pool alone would stay
         * blocked until the pool handed the connection over or the wait bound passed, and this case would time
         * out waiting for the dispatch to end.
         */
        @Test
        @DisplayName("a departure ends the dispatch at once while its connection is still pending; the connection handed out afterwards is reset unsent")
        void departureEndsTheConnectionWaitAtOnce() throws Exception {
            Throwable cause = departureCause();
            UnsentRequest handedOut = new UnsentRequest();

            GatewayException ended = dispatchWhile(() -> body.clientDeparted(cause));
            connection.complete(handedOut.proxy());

            assertAll("an attempt released by the client while its connection was still pending",
                    () -> assertInstanceOf(DispatchStage.ClientDeparted.class, ended,
                            () -> "the client's abort, not an upstream failure: " + ended),
                    () -> assertSame(cause, ended.getCause(), "the departure is kept as the cause"),
                    () -> assertTrue(handedOut.resets.get() > 0,
                            "the connection handed out after the dispatch ended is reset"),
                    () -> assertEquals(List.of(), handedOut.unexpected, "the request is never sent"),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted the departure as a failure: " + transitions));
        }

        @Test
        @DisplayName("control — a connection that fails with no departure is an upstream failure the breaker counts")
        void connectionFailureWithoutDepartureIsCounted() throws Exception {
            GatewayException ended = dispatchWhile(() ->
                    connection.fail(new IllegalStateException(Generators.letterStrings(8, 16).next())));

            assertAll("a refused connection",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, ended.getEventType(), "an upstream failure"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the breaker opens on the one failure it counts"));
        }

        /**
         * Dispatches a bodyless request on a virtual thread, runs {@code meanwhile} once its attempt asked
         * for its connection, and returns what the dispatch ended with.
         */
        private GatewayException dispatchWhile(Runnable meanwhile) throws Exception {
            try (ExecutorService dispatching = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<GatewayException> ended = CompletableFuture.supplyAsync(() -> {
                    try {
                        stage.dispatch(route, HttpMethod.GET, "/" + route.id(), Map.of(), body, bodyless());
                        return null;
                    } catch (GatewayException failure) {
                        return failure;
                    }
                }, dispatching);
                Awaits.connect(requested, "the attempt to ask for its upstream connection");
                meanwhile.run();
                GatewayException failure = Awaits.connect(ended, "the dispatch to end");
                assertNotNull(failure, "the dispatch must not be answered");
                return failure;
            }
        }
    }

    /**
     * The first precedence rule of the departure wake: a body refusal recorded before the client departs is what
     * the dispatch ends with, not the departure. Driven through {@link DispatchStage#dispatch} with a bodyless
     * request whose attempt waits for a pooled connection the case never hands over, so the dispatch can end only
     * through the departure wake. The refusal is a body byte on the bodyless request, which the case emits before
     * the client leaves; the matched control lets the client leave with no refusal recorded.
     * <p>
     * The guard holds the gateway's own {@link UpstreamCircuitBreaker} alone, opening on the first failure it
     * counts, so a breaker that stays closed shows that neither end is counted as a failure. With a one-call window
     * it does not tell a neutral outcome from a success; {@code UpstreamGuardsTest} and
     * {@code UpstreamCircuitBreakerTest} bind the neutral outcome itself.
     */
    @Nested
    @DisplayName("a body refusal recorded before the client departs ends the dispatch in place of the departure")
    class BodyRefusalBeforeDeparture {

        private Route route;
        private List<CircuitBreakerState> transitions;
        private CountDownLatch requested;
        private DispatchStage stage;
        private ChunkStream inbound;
        private DispatchStage.InboundBody body;

        @BeforeEach
        void setUp() {
            route = departedRoute();
            transitions = new CopyOnWriteArrayList<>();
            requested = new CountDownLatch(1);
            UpstreamClients clients = new UpstreamClients(
                    Map.of(UpstreamTarget.of(route), pendingClient(Promise.promise(), requested)),
                    Map.of(ResilienceShape.of(route), breakerGuard(route, transitions)));
            stage = new DispatchStage(clients, 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
            inbound = new ChunkStream();
            body = DispatchStage.InboundBody.arm(inbound);
        }

        @Test
        @DisplayName("a body byte on a bodyless request before the departure ends the dispatch as its SECURITY_FILTER_VIOLATION, not a ClientDeparted")
        void refusalBeforeDepartureEndsTheDispatch() throws Exception {
            GatewayException ended = dispatchUntilEnded(stage, route, body, bodyless(), requested, () -> {
                inbound.emit(Buffer.buffer(Generators.letterStrings(1, 16).next()));
                body.clientDeparted(departureCause());
            });

            assertAll("a refusal recorded before the client left",
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION, ended.getEventType(),
                            () -> "the body's refusal, not the departure: " + ended),
                    () -> assertFalse(ended instanceof DispatchStage.ClientDeparted,
                            "the later departure does not override the refusal"),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted the refusal as a failure: " + transitions));
        }

        @Test
        @DisplayName("control — a departure with no refusal recorded before it ends the dispatch as a ClientDeparted")
        void departureWithoutRefusalIsTheDeparture() throws Exception {
            Throwable cause = departureCause();

            GatewayException ended = dispatchUntilEnded(stage, route, body, bodyless(), requested,
                    () -> body.clientDeparted(cause));

            assertAll("a departure the body did not refuse before",
                    () -> assertInstanceOf(DispatchStage.ClientDeparted.class, ended,
                            () -> "the client's departure: " + ended),
                    () -> assertSame(cause, ended.getCause(), "the departure is kept as the cause"),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted the departure as a failure: " + transitions));
        }
    }

    /**
     * The second precedence rule of the departure wake: an upstream stall claimed before the client departs keeps
     * the failure the upstream's. The dispatch rethrows the stall, which reaches the guard as the upstream's
     * timeout and is counted by the breaker. Driven through {@link DispatchStage#dispatch} on a gRPC-shaped stage
     * — a pre-response deadline of {@value #DEADLINE_MILLIS} ms and an upstream stall threshold of
     * {@value #STALL_THRESHOLD_MILLIS} ms — over an upstream client that hands the attempt, on a Vert.x context, a
     * request which takes the body stream, pauses it at once as an upstream not taking data does, and never
     * answers. The dispatch can therefore end only through the departure wake. The case resumes the stream once
     * the pause has outlasted the deadline, so the resume ends the stream as the upstream's stall, and only then
     * lets the client depart; the matched control lets the client depart while the upstream still holds the
     * stream and no stall has been claimed.
     * <p>
     * The guard holds the gateway's own {@link UpstreamCircuitBreaker} alone, opening on the first failure it
     * counts, so an open breaker shows the stall counted as the upstream's failure, and a closed one that the
     * departure was not counted as a failure. With a one-call window a closed breaker does not tell a neutral
     * outcome from a success; {@code UpstreamGuardsTest} and {@code UpstreamCircuitBreakerTest} bind the neutral
     * outcome itself.
     */
    @Nested
    @DisplayName("an upstream stall claimed before the client departs ends the dispatch as the upstream's timeout")
    class UpstreamStallBeforeDeparture {

        /** The test-only pre-response deadline, counted from the hand-over of the body. */
        private static final long DEADLINE_MILLIS = 300L;
        /** The test-only upstream stall threshold; shorter than the deadline, as the stage requires. */
        private static final long STALL_THRESHOLD_MILLIS = 100L;
        /** The pre-response cap, long enough that it never shortens the deadline counted from the hand-over. */
        private static final long PRE_RESPONSE_CAP_MILLIS = 10_000L;
        /** The message bound, far beyond every case, so none is ended by it. */
        private static final long MESSAGE_BOUND_MILLIS = 60_000L;

        private Vertx vertx;
        private Route route;
        private List<CircuitBreakerState> transitions;
        private Promise<HttpClientRequest> connection;
        private CountDownLatch requested;
        private DispatchStage stage;
        private DispatchStage.InboundBody body;
        private HeldRequest held;

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
            route = departedRoute();
            transitions = new CopyOnWriteArrayList<>();
            connection = Promise.promise();
            requested = new CountDownLatch(1);
            UpstreamClients clients = new UpstreamClients(
                    Map.of(UpstreamTarget.of(route), pendingClient(connection, requested)),
                    Map.of(ResilienceShape.of(route), breakerGuard(route, transitions)));
            stage = new DispatchStage(clients, 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS,
                    MESSAGE_BOUND_MILLIS, PRE_RESPONSE_CAP_MILLIS, STALL_THRESHOLD_MILLIS,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
            body = DispatchStage.InboundBody.arm(new SilentStream());
            held = new HeldRequest();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(vertx.close(), "the Vert.x instance to close");
        }

        @Test
        @DisplayName("a stall claimed before the departure ends the dispatch as the stall's UPSTREAM_TIMEOUT, which the breaker counts")
        void stallBeforeDepartureStaysTheUpstreams() throws Exception {
            GatewayException ended = dispatchUntilEnded(stage, route, body, streamed(), requested, () -> {
                handOverBody();
                Awaits.until(() -> System.nanoTime() - held.pausedAtNanos
                                >= TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS),
                        "the paused body stream to outlast the pre-response deadline",
                        Awaits.CONNECT_CEILING_SECONDS);
                held.body().resume();
                assertNotNull(held.body().upstreamStall(),
                        "precondition: the resume ends the paused stream as the upstream's stall");
                body.clientDeparted(departureCause());
            });

            assertAll("a stall the upstream caused before the client left",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, ended.getEventType(),
                            () -> "the upstream's timeout, not the client's abort: " + ended),
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, ended.getCause(),
                            "the stall is what the dispatch ended with"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the breaker opens on the stall it counts"),
                    () -> assertTrue(held.resets.get() > 0, "the upstream request is reset"),
                    () -> assertEquals(List.of(), held.unexpected, "the request is never asked for more"));
        }

        @Test
        @DisplayName("control — a departure while the upstream still holds the stream ends the dispatch as the client's abort, which the breaker does not count")
        void departureBeforeStallIsTheClients() throws Exception {
            Throwable cause = departureCause();

            GatewayException ended = dispatchUntilEnded(stage, route, body, streamed(), requested, () -> {
                handOverBody();
                body.clientDeparted(cause);
            });

            assertAll("a departure before any stall was claimed",
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED, ended.getEventType(),
                            () -> "the client's abort, not the upstream's timeout: " + ended),
                    () -> assertEquals(DispatchStage.ByteCappedBodyStream.INBOUND_BODY_FAILED, ended.getMessage(),
                            "the body was still arriving when the client left"),
                    () -> assertSame(cause, ended.getCause(), "the departure is kept as the cause"),
                    () -> assertNull(held.body().upstreamStall(), "no stall was claimed"),
                    () -> assertTrue(transitions.isEmpty(),
                            () -> "the breaker counted the departure as a failure: " + transitions),
                    () -> assertTrue(held.resets.get() > 0, "the upstream request is reset"),
                    () -> assertEquals(List.of(), held.unexpected, "the request is never asked for more"));
        }

        /**
         * Hands the attempt its upstream request on a Vert.x context, where the dispatch arms the stream's clocks,
         * and waits until the request took the body stream and paused it.
         */
        private void handOverBody() throws Exception {
            vertx.getOrCreateContext().runOnContext(_ -> connection.complete(held.proxy()));
            Awaits.connect(held.bodyHandedOver, "the attempt to hand its body stream to the upstream request");
        }
    }

    /**
     * {@link RejectionReporter#reportRejection} over a routing context that answers the stashed route id
     * only: which disposition the latched {@code INFO} ApiSheriff-22 names, the latch each disposition holds
     * of its own on a route, and the {@code sheriff_inbound_body_aborts_total} series each disposition is
     * counted on. The departure and the failed body are the aborts the dispatch produces; the
     * {@link DispatchStage.RequestMessageTimeout} is the one its message clock records, whose production by a
     * gRPC dispatch is pinned by {@code GrpcDispatchStageTest}.
     */
    @Nested
    @DisplayName("the reporter records client-left, body-stream-failed and an idle gRPC client as client-idle, each on its own per-route latch and counter series")
    class Reported {

        private static final String ABORTS_TOTAL = "sheriff_inbound_body_aborts_total";
        private static final String ERRORS_TOTAL = "sheriff_errors_total";

        private SimpleMeterRegistry registry;
        private RejectionReporter reporter;
        private RoutingContext routed;
        private String routeId;
        private GatewayException departed;
        private GatewayException bodyFailed;
        private GatewayException idleClient;

        @BeforeEach
        void setUp() {
            Route route = departedRoute();
            routeId = route.id();
            routed = routingContextOn(routeId);
            registry = new SimpleMeterRegistry();
            reporter = new RejectionReporter(new GatewayEventCounter(), new SheriffMetrics(registry), 1_000L, 1_000L);
            DispatchStage stage = departedStage(route, timeoutGuard());
            departed = dispatchAfterDeparture(stage, route, departureCause(), bodyless());
            bodyFailed = dispatchAfterDeparture(stage, route, departureCause(), streamed());
            idleClient = new DispatchStage.RequestMessageTimeout();
        }

        @Test
        @DisplayName("the first departure on a route is one INFO ApiSheriff-22 naming client-left")
        void firstDepartureIsOneInfoNamingClientLeft() {
            reporter.reportRejection(routed, departed);

            assertAll("the first departure",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (client-left) — upstream "
                                    + "request aborted; further occurrences of this disposition on this route stay "
                                    + "at DEBUG"));
        }

        @Test
        @DisplayName("a repeat departure on the route logs no second INFO, only the DEBUG diagnostic")
        void repeatDepartureStaysAtDebug() {
            reporter.reportRejection(routed, departed);
            reporter.reportRejection(routed, departed);

            assertAll("a repeat departure",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                            "Request body on route '" + routeId + "' did not arrive whole again (client-left)"));
        }

        @Test
        @DisplayName("a body-stream-failed after a departure on the same route is its own INFO")
        void bodyStreamFailedAfterADepartureIsItsOwnInfo() {
            reporter.reportRejection(routed, departed);
            reporter.reportRejection(routed, bodyFailed);

            assertAll("one latch per disposition",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "did not arrive whole (client-left)"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (body-stream-failed)"));
        }

        @Test
        @DisplayName("control: a body-stream-failed first on a route is the INFO naming body-stream-failed")
        void bodyStreamFailedFirstNamesItsOwnDisposition() {
            reporter.reportRejection(routed, bodyFailed);

            assertAll("the first body-stream-failed",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (body-stream-failed)"));
        }

        @Test
        @DisplayName("the first idle gRPC client on a route is one INFO ApiSheriff-22 naming client-idle")
        void firstIdleClientIsOneInfoNamingClientIdle() {
            reporter.reportRejection(routed, idleClient);

            assertAll("the first idle client",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (client-idle)"));
        }

        @Test
        @DisplayName("a repeat idle client on the route logs no second INFO, only the DEBUG diagnostic")
        void repeatIdleClientStaysAtDebug() {
            reporter.reportRejection(routed, idleClient);
            reporter.reportRejection(routed, idleClient);

            assertAll("a repeat idle client",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                            "Request body on route '" + routeId + "' did not arrive whole again (client-idle)"));
        }

        /**
         * The case the per-disposition latch exists for: a route whose clients have already left once still
         * records its first idle-client end at {@code INFO}, so an idle-client flood behind an earlier
         * departure is not hidden at {@code DEBUG}.
         */
        @Test
        @DisplayName("an idle client after a departure on the same route is its own INFO")
        void idleClientAfterADepartureIsItsOwnInfo() {
            reporter.reportRejection(routed, departed);
            reporter.reportRejection(routed, idleClient);

            assertAll("one latch per disposition",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "did not arrive whole (client-left)"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (client-idle)"));
        }

        @Test
        @DisplayName("a body-stream-failed after an idle client on the same route is its own INFO")
        void bodyStreamFailedAfterAnIdleClientIsItsOwnInfo() {
            reporter.reportRejection(routed, idleClient);
            reporter.reportRejection(routed, bodyFailed);

            assertAll("one latch per disposition",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "did not arrive whole (client-idle)"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (body-stream-failed)"));
        }

        @Test
        @DisplayName("an idle client on a second route is that route's own INFO")
        void idleClientOnASecondRouteIsItsOwnInfo() {
            String otherRouteId = routeId + "-other";

            reporter.reportRejection(routed, idleClient);
            reporter.reportRejection(routingContextOn(otherRouteId), idleClient);

            assertAll("one latch per route",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (client-idle)"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + otherRouteId + "' did not arrive whole (client-idle)"));
        }

        /**
         * Every end is counted on its own disposition series, whether or not its {@code INFO} was already
         * latched, while {@code sheriff_errors_total} keeps the one {@code INBOUND_BODY_ABORTED} series it
         * held for all three.
         */
        @Test
        @DisplayName("every end is counted on sheriff_inbound_body_aborts_total under its route and disposition")
        void everyEndIsCountedUnderItsDisposition() {
            reporter.reportRejection(routed, departed);
            reporter.reportRejection(routed, bodyFailed);
            reporter.reportRejection(routed, bodyFailed);
            reporter.reportRejection(routed, idleClient);
            reporter.reportRejection(routed, idleClient);
            reporter.reportRejection(routed, idleClient);

            assertAll("the counter series per disposition",
                    () -> assertEquals(1.0, abortsCounted("client-left"), 0.0),
                    () -> assertEquals(2.0, abortsCounted("body-stream-failed"), 0.0),
                    () -> assertEquals(3.0, abortsCounted("client-idle"), 0.0),
                    () -> assertEquals(3, registry.find(ABORTS_TOTAL).counters().size(),
                            "one series per disposition on the route"),
                    () -> assertEquals(6.0, registry.get(ERRORS_TOTAL)
                                    .tags("route", routeId, "event", EventType.INBOUND_BODY_ABORTED.name())
                                    .counter().count(), 0.0,
                            "sheriff_errors_total keeps counting all three under one event series"));
        }

        @Test
        @DisplayName("a gRPC request stream held open past its pre-response deadline is counted on client-idle, and stays a REQUEST_BODY_TIMEOUT")
        void preResponseDeadlineIsCountedAsClientIdle() {
            reporter.reportRejection(routed, new DispatchStage.RequestBodyDeadlineExceeded(true));

            assertAll("a pre-response deadline end",
                    () -> assertEquals(1.0, abortsCounted("client-idle"),
                            "an idle gRPC client is countable on its own series, whichever bound ended it"),
                    () -> assertEquals(1.0, registry.get(ERRORS_TOTAL)
                                    .tags("event", EventType.REQUEST_BODY_TIMEOUT.name()).counter().count(),
                            "the shared REQUEST_BODY_TIMEOUT event is kept"));
        }

        @Test
        @DisplayName("control — an HTTP body past its whole-body deadline is not counted on client-idle")
        void wholeBodyDeadlineIsNotCountedAsClientIdle() {
            reporter.reportRejection(routed, new DispatchStage.RequestBodyDeadlineExceeded(false));

            assertAll("a whole-body deadline end",
                    () -> assertTrue(registry.find(ABORTS_TOTAL).counters().isEmpty(),
                            "a slow HTTP body is not an idle gRPC client"),
                    () -> assertEquals(1.0, registry.get(ERRORS_TOTAL)
                            .tags("event", EventType.REQUEST_BODY_TIMEOUT.name()).counter().count()));
        }

        private double abortsCounted(String disposition) {
            return registry.get(ABORTS_TOTAL).tags("route", routeId, "disposition", disposition).counter().count();
        }
    }

    /** A proxy route whose upstream is never reached: nothing in these cases asks for a connection. */
    private static Route departedRoute() {
        String id = Generators.letterStrings(3, 10).next();
        return Route.builder()
                .id(id)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .upstream(new ResolvedUpstream("https", "orders.example", 443, ""))
                .build();
    }

    /** The stage serving {@code route} with an upstream client that must not be used, and {@code guard}. */
    private static DispatchStage departedStage(Route route, Guard guard) {
        return new DispatchStage(new UpstreamClients(Map.of(UpstreamTarget.of(route), untouchedClient()),
                        Map.of(ResilienceShape.of(route), guard)), 1024L, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                new UpstreamFailureMapper(new GatewayEventCounter()));
    }

    /** A guard whose timeout is longer than any case, so no case is ended by it. */
    private static Guard timeoutGuard() {
        return Guard.create().withTimeout().duration(30, ChronoUnit.SECONDS).done().build();
    }

    /** Dispatches a request whose client left before the dispatch began, and returns what it ended with. */
    private static GatewayException dispatchAfterDeparture(DispatchStage stage, Route route, Throwable cause,
            DispatchStage.BodyFraming framing) {
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(new SilentStream());
        body.clientDeparted(cause);
        HttpMethod method = framing.bodyless() ? HttpMethod.GET : HttpMethod.POST;
        String requestUri = "/" + route.id();
        Map<String, String> noForwardHeaders = Map.of();
        return assertThrows(GatewayException.class,
                () -> stage.dispatch(route, method, requestUri, noForwardHeaders, body, framing));
    }

    private static DispatchStage.BodyFraming bodyless() {
        return DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH, violation -> {
            // A departed dispatch never reaches a hand-over, so no late refusal can arrive.
        });
    }

    private static DispatchStage.BodyFraming streamed() {
        return DispatchStage.BodyFraming.streamed(DispatchStage.NO_DECLARED_LENGTH);
    }

    /** An upstream client that fails the case on any call: a departed dispatch must not ask it for anything. */
    private static HttpClient untouchedClient() {
        return HttpClient.class.cast(Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                new Class<?>[]{HttpClient.class}, (proxy, method, args) -> {
                    throw new AssertionError("the departed dispatch asked its upstream client for " + method.getName());
                }));
    }

    /**
     * An upstream client whose one request waits for its connection until the case settles
     * {@code connection}: asking for it counts {@code requested} down, and any other call fails the case.
     */
    private static HttpClient pendingClient(Promise<HttpClientRequest> connection, CountDownLatch requested) {
        return HttpClient.class.cast(Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                new Class<?>[]{HttpClient.class}, (proxy, method, args) -> {
                    if ("request".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof RequestOptions) {
                        requested.countDown();
                        return connection.future();
                    }
                    throw new AssertionError("the dispatch asked its upstream client for " + method.getName());
                }));
    }

    /**
     * The upstream request an attempt obtains only after the dispatch stopped waiting for it: it may take
     * an exception handler and be reset — any number of times, a second reset being harmless — and every
     * other call, sending it above all, is recorded and fails.
     */
    private static final class UnsentRequest {

        private final AtomicInteger resets = new AtomicInteger();
        private final List<String> unexpected = new CopyOnWriteArrayList<>();

        HttpClientRequest proxy() {
            return HttpClientRequest.class.cast(Proxy.newProxyInstance(HttpClientRequest.class.getClassLoader(),
                    new Class<?>[]{HttpClientRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "exceptionHandler" -> proxy;
                        case "reset" -> {
                            resets.incrementAndGet();
                            yield Boolean.TRUE;
                        }
                        case "toString" -> "an unsent upstream request";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> {
                            unexpected.add(method.getName());
                            throw new AssertionError("the released request was asked for " + method.getName());
                        }
                    }));
        }
    }

    /**
     * The upstream request of an upstream that stops taking data the moment the body is handed to it and never
     * answers: it takes the body stream, pauses it at once — as the upstream's backpressure does — and returns a
     * send that never completes. It may take an exception handler and be reset any number of times; every other
     * call is recorded and fails.
     */
    private static final class HeldRequest {

        private final AtomicInteger resets = new AtomicInteger();
        private final List<String> unexpected = new CopyOnWriteArrayList<>();
        private final CountDownLatch bodyHandedOver = new CountDownLatch(1);
        private final AtomicReference<DispatchStage.ByteCappedBodyStream> body = new AtomicReference<>();
        /** The {@link System#nanoTime()} reading just after the body stream was paused. */
        private volatile long pausedAtNanos;

        /** @return the body stream the dispatch handed over; only valid once {@link #bodyHandedOver} reached zero */
        DispatchStage.ByteCappedBodyStream body() {
            DispatchStage.ByteCappedBodyStream handedOver = body.get();
            assertNotNull(handedOver, "the dispatch handed no body stream to the upstream request");
            return handedOver;
        }

        HttpClientRequest proxy() {
            return HttpClientRequest.class.cast(Proxy.newProxyInstance(HttpClientRequest.class.getClassLoader(),
                    new Class<?>[]{HttpClientRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "exceptionHandler" -> proxy;
                        case "send" -> holdBody(args);
                        case "reset" -> {
                            resets.incrementAndGet();
                            yield Boolean.TRUE;
                        }
                        case "toString" -> "a held upstream request";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> {
                            unexpected.add(method.getName());
                            throw new AssertionError("the held request was asked for " + method.getName());
                        }
                    }));
        }

        private Object holdBody(Object[] args) {
            if (args == null || args.length != 1 || !(args[0] instanceof DispatchStage.ByteCappedBodyStream sent)) {
                unexpected.add("send");
                throw new AssertionError("the held request was sent without the dispatch's body stream");
            }
            sent.pause();
            pausedAtNanos = System.nanoTime();
            body.set(sent);
            bodyHandedOver.countDown();
            return Promise.<HttpClientResponse>promise().future();
        }
    }

    /** A request body the case feeds by hand: each chunk it emits goes to the handler the dispatch registered. */
    private static final class ChunkStream implements ReadStream<Buffer> {

        /** Until the dispatch registers its handler, an emitted chunk fails the case. */
        private volatile Handler<Buffer> handler =
                chunk -> fail("a chunk was emitted before the dispatch read the request body");

        void emit(Buffer chunk) {
            handler.handle(chunk);
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> exceptionHandler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> chunkHandler) {
            if (chunkHandler != null) {
                handler = chunkHandler;
            }
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
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }

    /**
     * A guard holding the gateway's own {@link UpstreamCircuitBreaker} with a window of one call, so it opens on
     * the first failure it counts and stays closed on a neutral gateway rejection; the production composition, no
     * retry.
     */
    private static Guard breakerGuard(Route route, List<CircuitBreakerState> transitions) {
        return UpstreamBreakerFixtures.breakerOnFirstFailure(route.id(), transitions::add);
    }

    /** What a case does while its dispatch waits; it may wait itself. */
    @FunctionalInterface
    private interface CaseSteps {

        void run() throws Exception;
    }

    /**
     * Dispatches a request framed by {@code framing} on a virtual thread, runs {@code steps} once its attempt asked
     * for its upstream connection, and returns what the dispatch ended with. A dispatch still waiting when the case
     * gives up — {@code steps} failed before the client departed — is interrupted, so the case never waits on it.
     */
    private static GatewayException dispatchUntilEnded(DispatchStage stage, Route route, DispatchStage.InboundBody body,
            DispatchStage.BodyFraming framing, CountDownLatch requested, CaseSteps steps) throws Exception {
        HttpMethod method = framing.bodyless() ? HttpMethod.GET : HttpMethod.POST;
        String requestUri = "/" + route.id();
        try (ExecutorService dispatching = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                CompletableFuture<GatewayException> ended = CompletableFuture.supplyAsync(() -> {
                    try {
                        stage.dispatch(route, method, requestUri, Map.of(), body, framing);
                        return null;
                    } catch (GatewayException failure) {
                        return failure;
                    }
                }, dispatching);
                Awaits.connect(requested, "the attempt to ask for its upstream connection");
                steps.run();
                GatewayException failure = Awaits.connect(ended, "the dispatch to end");
                assertNotNull(failure, "the dispatch must not be answered");
                return failure;
            } finally {
                dispatching.shutdownNow();
            }
        }
    }

    /** A routing context answering the {@link RejectionReporter#ROUTE_KEY} lookup with {@code routeId} only. */
    private static RoutingContext routingContextOn(String routeId) {
        return RoutingContext.class.cast(Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && args != null && args.length == 1
                            && RejectionReporter.ROUTE_KEY.equals(args[0])) {
                        return routeId;
                    }
                    throw new UnsupportedOperationException("the routing context fake does not answer "
                            + method.getName());
                }));
    }

    /** A request body that never emits anything; it only remembers being paused. */
    private static final class SilentStream implements ReadStream<Buffer> {

        private volatile boolean paused;

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
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
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }
}
