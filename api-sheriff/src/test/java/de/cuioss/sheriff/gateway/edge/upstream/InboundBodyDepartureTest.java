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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * way once the pool hands the connection over, and the circuit breaker counts no failure for it. And {@link RejectionReporter} records the two
 * under their own dispositions on one per-route latch.
 */
@EnableGeneratorController
@EnableTestLogger(debug = RejectionReporter.class)
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

            // The verdicts are read inside a guarded call, where the dispatch's retry state is bound; outside
            // one the predicate refuses everything, so a refusal there would prove nothing.
            GatewayException thrown = assertThrows(GatewayException.class, () -> stage.guardedDispatch(guard,
                    new StreamAwareRetryGate(true), HttpMethod.GET, () -> 0L, () -> false, () -> {
                        verdicts.put("transport failure", DispatchStage.allowsRetryAfter(
                                new IllegalStateException(Generators.letterStrings(8, 16).next())));
                        verdicts.put("client-left", DispatchStage.allowsRetryAfter(departed));
                        verdicts.put("client-left, wrapped", DispatchStage.allowsRetryAfter(
                                new ExecutionException(departed)));
                        verdicts.put("body-stream-failed", DispatchStage.allowsRetryAfter(bodyFailed));
                        throw departed;
                    }));

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
     * connection fails, then the request is handed out or the connection is refused.
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
            Guard breakerOnFirstFailure = Guard.create()
                    .withCircuitBreaker().requestVolumeThreshold(1).failureRatio(1.0).delay(30, ChronoUnit.SECONDS)
                    .skipOn(GatewayException.class).onStateChange(transitions::add).done()
                    .withTimeout().duration(30, ChronoUnit.SECONDS).done()
                    .build();
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
     * {@link RejectionReporter#reportRejection} over a routing context that answers the stashed route id
     * only: which disposition the latched {@code INFO} ApiSheriff-22 names, and the latch the two
     * dispositions share. The aborts are the ones the dispatch produces.
     */
    @Nested
    @DisplayName("the reporter records a departed client as client-left, on the latch body-stream-failed shares")
    class Reported {

        private RejectionReporter reporter;
        private RoutingContext routed;
        private String routeId;
        private GatewayException departed;
        private GatewayException bodyFailed;

        @BeforeEach
        void setUp() {
            Route route = departedRoute();
            routeId = route.id();
            routed = routingContextOn(routeId);
            reporter = new RejectionReporter(new GatewayEventCounter(), new SheriffMetrics(new SimpleMeterRegistry()),
                    1_000L, 1_000L);
            DispatchStage stage = departedStage(route, timeoutGuard());
            departed = dispatchAfterDeparture(stage, route, departureCause(), bodyless());
            bodyFailed = dispatchAfterDeparture(stage, route, departureCause(), streamed());
        }

        @Test
        @DisplayName("the first departure on a route is one INFO ApiSheriff-22 naming client-left")
        void firstDepartureIsOneInfoNamingClientLeft() {
            reporter.reportRejection(routed, departed);

            assertAll("the first departure",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            "Request body on route '" + routeId + "' did not arrive whole (client-left)"));
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
        @DisplayName("a body-stream-failed after a departure on the same route finds the latch taken")
        void bodyStreamFailedSharesTheLatch() {
            reporter.reportRejection(routed, departed);
            reporter.reportRejection(routed, bodyFailed);

            assertAll("one latch for both dispositions",
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "(client-left)"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                            "did not arrive whole again (body-stream-failed)"));
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
        return assertThrows(GatewayException.class,
                () -> stage.dispatch(route, method, "/" + route.id(), Map.of(), body, framing));
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
