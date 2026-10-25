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
package de.cuioss.sheriff.gateway.edge.terminal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeAdmission;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.RejectionReporter;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage;
import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamFailureMapper;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.forward.ForwardPolicyStage;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.pipeline.FramingGate;
import de.cuioss.sheriff.gateway.pipeline.OriginValidationStage;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/1.x connection close that follows a request body outliving the request-body deadline after
 * the hand-over ({@link TerminalDispatch#closeIfEndedOn}). The late callback runs on the upstream
 * request's event loop while the client response is ended on the client connection's, so whether the
 * response has ended is decided on the client connection's context.
 * <p>
 * The client context is a hand-driven fake: a task it is given runs only when a case runs it, so a
 * case can end the response after the callback and before the decision, the order a relay that ends
 * normally on the client connection's loop just after the callback produces.
 * <p>
 * The head the failed relay answers with ({@link TerminalDispatch#prepareFailedRelayHead}) is driven
 * against a fake response: a relay a late request-body timeout ended before its head was written keeps
 * the status the upstream sent, also when the relay had not set it yet — the deadline passed between the
 * hand-over and the relay starting.
 * <p>
 * The retirement of the connection after such a timeout is recorded from the upstream request's event
 * loop, so it touches only the synchronized retired set; the failed relay's end learns of it from the
 * late refusal's event, and neither side writes the request's context data from an event loop or reads
 * a retirement mark from it. {@link ThroughTheDispatchPath} drives that through the dispatch path
 * itself, over a live stub upstream and an observed routing context; the HTTP/2 counterparts and the
 * marked-request case drive the retirement calls against fakes.
 */
@DisplayName("TerminalDispatch — the connection close and the answer after a request body outlived its deadline")
@Tag("isolated-fork")
class TerminalDispatchLateBodyTimeoutTest {

    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String RELAYED_HEADER = "X-Relayed";
    private static final String RELAYED_VALUE = "kept";
    /** A status other than the {@code 200} the relay of a fresh response starts from. */
    private static final int UPSTREAM_STATUS = 201;
    private static final int BAD_GATEWAY = 502;

    @Test
    @DisplayName("HTTP/1.x — a response ended normally after the callback and before the decision: the connection is closed")
    void responseEndedBeforeTheDecisionClosesTheConnection() {
        Exchange exchange = new Exchange();

        TerminalDispatch.closeIfEndedOn(exchange.clientContext(), exchange.request());
        int closedBeforeTheDecision = exchange.closes.get();
        exchange.ended.set(true);
        exchange.runClientTasks();

        assertAll("a response the relay ended normally just before the decision",
                () -> assertEquals(0, closedBeforeTheDecision, "nothing is decided on the callback's thread"),
                () -> assertEquals(1, exchange.tasksRun.get(), "the decision runs once, on the client context"),
                () -> assertEquals(1, exchange.closes.get(), "the connection of the ended response is closed"));
    }

    @Test
    @DisplayName("HTTP/1.x — a response not ended at the decision: the connection is left to the relay's end")
    void responseNotEndedAtTheDecisionIsLeftToTheRelaysEnd() {
        Exchange exchange = new Exchange();

        TerminalDispatch.closeIfEndedOn(exchange.clientContext(), exchange.request());
        exchange.runClientTasks();

        assertAll("a response still open at the decision",
                () -> assertEquals(1, exchange.tasksRun.get(), "the decision runs once, on the client context"),
                () -> assertEquals(0, exchange.closes.get(),
                        "the connection is not closed here: the relay's end closes the marked connection"));
    }

    @Test
    @DisplayName("a relay a late request-body timeout failed before its head was written: the upstream status stands, with no body")
    void lateBodyTimeoutBeforeTheHeadKeepsTheUpstreamStatus() {
        FailedRelayResponse response = new FailedRelayResponse(false);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), true, UPSTREAM_STATUS);

        assertAll("the unwritten head of a relay a late request-body timeout ended",
                () -> assertEquals(List.of(UPSTREAM_STATUS), response.statuses,
                        "the status the upstream sent is set, whatever the relay had set before it failed"),
                () -> assertFalse(response.headers.contains(CONTENT_LENGTH), "no body byte follows the head"),
                () -> assertEquals(RELAYED_VALUE, response.headers.get(RELAYED_HEADER),
                        "the headers the relay copied are kept"));
    }

    @Test
    @DisplayName("a relay that failed for any other reason before its head was written: 502, with no body")
    void otherFailureBeforeTheHeadIsAnswered502() {
        FailedRelayResponse response = new FailedRelayResponse(false);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), false, UPSTREAM_STATUS);

        assertAll("the unwritten head of a relay that failed on its own",
                () -> assertEquals(List.of(BAD_GATEWAY), response.statuses, "the failure is answered 502"),
                () -> assertFalse(response.headers.contains(CONTENT_LENGTH), "the 502 carries no body"));
    }

    @Test
    @DisplayName("a relay that failed after its head was written: the head is left as it was")
    void failureAfterTheHeadLeavesTheHeadAsItWas() {
        FailedRelayResponse response = new FailedRelayResponse(true);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), true, UPSTREAM_STATUS);

        assertAll("the written head of a failed relay",
                () -> assertEquals(List.of(), response.statuses, "no status is set on a written head"),
                () -> assertTrue(response.headers.contains(CONTENT_LENGTH), "the written head is not changed"));
    }

    @Test
    @DisplayName("HTTP/2 — a late request-body timeout retires no connection: only the stream ends")
    void lateTimeoutOnHttp2RetiresNothing() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_2, false);
        ConnectionRetirement retirement = new ConnectionRetirement(new FramingGate(false));

        retirement.recordRetirement(exchange.routingContext());

        assertFalse(retirement.isRetired(exchange.connection()), "an HTTP/2 connection is never retired");
    }

    @Test
    @DisplayName("HTTP/2 — a late request-body timeout closes no connection at the failed relay's end")
    void failedRelayAfterLateTimeoutOnHttp2ClosesNothing() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_2, false);

        TerminalDispatch.retireFailedRelayConnection(exchange.routingContext(), EventType.REQUEST_BODY_TIMEOUT);

        assertAll("a late request-body timeout on an HTTP/2 stream",
                () -> assertEquals(List.of(), exchange.connectionHeaders, "no Connection header on HTTP/2"),
                () -> assertEquals(0, exchange.endHandlers.size(), "no close is tied to the end of the stream"));
    }

    @Test
    @DisplayName("any other relay failure retires the connection only where an earlier rejection marked the request")
    void otherFailureRetiresOnlyAMarkedRequest() {
        RetiringExchange marked = new RetiringExchange(HttpVersion.HTTP_1_1, false);
        marked.markedForRetirement = true;
        RetiringExchange unmarked = new RetiringExchange(HttpVersion.HTTP_1_1, false);

        TerminalDispatch.retireFailedRelayConnection(marked.routingContext(), null);
        marked.endHandlers.forEach(handler -> handler.handle(null));
        TerminalDispatch.retireFailedRelayConnection(unmarked.routingContext(), null);
        unmarked.endHandlers.forEach(handler -> handler.handle(null));

        assertAll("a relay that failed for another reason",
                () -> assertEquals(1, marked.closes.get(), "a marked request's connection is closed"),
                () -> assertEquals(0, unmarked.closes.get(), "an unmarked request's connection is left open"));
    }

    /**
     * The late request-body timeout of a proxied HTTP/1.1 upload, run through
     * {@link TerminalDispatch#dispatchAndRelay} against a live stub upstream that answers at once, with a
     * request body that trickles past a short request-body deadline: the late refusal, the retirement it
     * records and the failed relay that follows are the dispatch path's own, not called by the cases.
     * <p>
     * The routing context the dispatch is handed delegates to the real one and observes it: every
     * context-data write made from an event-loop thread — the late callback runs on the upstream
     * request's loop, the failed relay on the client connection's — every lookup of a key nothing had
     * stashed, which is how a retirement mark an earlier rejection leaves would be read, and every end
     * handler registered on it.
     * <p>
     * The dispatch runs under the test-only {@link EdgeHardeningOptions} constructor with a
     * {@value #IDLE_TIMEOUT_MILLIS} ms idle timeout and a {@value #BODY_DEADLINE_MILLIS} ms request-body
     * deadline in place of the fixed 10 s and 25 s; the body trickles faster than the idle timeout, so only
     * the deadline ends it.
     */
    @Nested
    @DisplayName("HTTP/1.x — a late request-body timeout through the dispatch path")
    class ThroughTheDispatchPath {

        private static final long IDLE_TIMEOUT_MILLIS = 300L;
        private static final long BODY_DEADLINE_MILLIS = 2 * IDLE_TIMEOUT_MILLIS;
        private static final long TRICKLE_INTERVAL_MILLIS = IDLE_TIMEOUT_MILLIS / 3;
        /** How long the body trickles before it is ended: far past the deadline. */
        private static final long TRICKLE_MILLIS = 10 * BODY_DEADLINE_MILLIS;
        /** The upstream path the stub answers at once with its head alone. */
        private static final String HEAD_ONLY = "/head-only";
        /** The upstream path the stub answers at once with its head and a first chunk. */
        private static final String STREAMING = "/streaming";
        private static final String ROUTE_ID = "up";
        private static final String ANSWER = "ok";
        private static final String CRLF = "\r\n";
        private static final String CONNECTION_CLOSE = CRLF + "connection: close";
        /** The timeout of the stand-in guard: longer than any case, so no case is ended by the guard. */
        private static final long GUARD_SECONDS = 30L;

        private final AtomicReference<HttpConnection> clientConnection = new AtomicReference<>();
        private final AtomicReference<ObservedContext> observed = new AtomicReference<>();
        private Vertx vertx;
        private HttpServer upstreamServer;
        private HttpServer frontServer;
        private HttpClient upstreamClient;
        private NetClient netClient;
        private ConnectionRetirement connectionRetirement;
        private TerminalDispatch terminalDispatch;
        private Route route;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                    .requestHandler(this::answerAtOnce)
                    .listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstreamClient = vertx.createHttpClient(new HttpClientOptions());
            route = ConfigFixtures.route(ROUTE_ID, "/" + ROUTE_ID)
                    .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                    .build();
            connectionRetirement = new ConnectionRetirement(new FramingGate(false));
            terminalDispatch = terminalDispatch();
            Router front = Router.router(vertx);
            front.route().handler(this::dispatchThroughTheEdge);
            frontServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                    .requestHandler(front)
                    .listen(0, LoopbackHost.ADDRESS), "the front server to start listening");
            netClient = vertx.createNetClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(netClient.close(), "the raw TCP client to close");
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(frontServer.close(), "the front server to close");
            Awaits.teardown(upstreamServer.close(), "the stub upstream to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("a late request-body timeout is recorded in the retired set without writing the request's context data from an event loop")
        void lateTimeoutRetirementLeavesTheContextDataAlone() throws Exception {
            String exchange = trickleUntilClosed(HEAD_ONLY);
            ObservedContext context = observed.get();

            assertAll("the retirement the late request-body timeout records",
                    () -> assertTrue(exchange.startsWith("HTTP/1.1 200 "),
                            () -> "the status the upstream sent stands: " + exchange),
                    () -> assertTrue(connectionRetirement.isRetired(clientConnection.get()), "the connection is retired"),
                    () -> assertEquals(0, context.writesOnAnEventLoop.get(),
                            "the context data map is not written from an event loop"));
        }

        @Test
        @DisplayName("the failed relay's answer after a late request-body timeout carries Connection: close and closes the connection")
        void failedRelayAfterLateTimeoutClosesTheConnection() throws Exception {
            String exchange = trickleUntilClosed(HEAD_ONLY);
            ObservedContext context = observed.get();
            String head = headOf(exchange);

            assertAll("a late request-body timeout on an HTTP/1.x relay whose head was not written",
                    () -> assertTrue(exchange.startsWith("HTTP/1.1 200 "),
                            () -> "the status the upstream sent stands: " + exchange),
                    () -> assertEquals(1, head.split(CONNECTION_CLOSE, -1).length - 1,
                            () -> "the unwritten head advertises Connection: close once: " + exchange),
                    () -> assertTrue(exchange.endsWith("0" + CRLF + CRLF),
                            () -> "the answer is written whole before the connection closes: " + exchange),
                    () -> assertEquals(1, context.endHandlers.get(), "the close is tied to the end of the response"),
                    () -> assertEquals(0, context.absentLookups.get(),
                            "the late timeout is told by its event, not by a mark in the context data"));
        }

        @Test
        @DisplayName("a late request-body timeout on a response whose head is written: the connection is still closed at the end")
        void failedRelayAfterLateTimeoutWithWrittenHeadStillCloses() throws Exception {
            String exchange = trickleUntilClosed(STREAMING);
            String head = headOf(exchange);

            assertAll("a late request-body timeout after the head went out",
                    () -> assertTrue(exchange.startsWith("HTTP/1.1 200 "),
                            () -> "the head the upstream sent stands: " + exchange),
                    () -> assertFalse(head.contains(CONNECTION_CLOSE),
                            () -> "a written head is not changed: " + exchange),
                    () -> assertTrue(exchange.contains(ANSWER),
                            () -> "what the upstream sent before the deadline is relayed: " + exchange));
        }

        /**
         * Answers every request at once — with its head alone under {@value #HEAD_ONLY}, with its head and
         * a first chunk otherwise — and ends the answer only when the request body has ended.
         */
        private void answerAtOnce(HttpServerRequest request) {
            HttpServerResponse response = request.response().setChunked(true);
            request.handler(chunk -> {
                // Read and discard: the body is observed only by whether it ends.
            });
            request.exceptionHandler(failure -> {
                // The gateway resets the upstream request at the deadline.
            });
            request.endHandler(end -> response.end());
            if (request.path().startsWith(HEAD_ONLY)) {
                response.writeHead();
            } else {
                response.write(ANSWER);
            }
        }

        /**
         * The front server's handler: arms the request body and captures the client connection's context
         * as the edge root does, then dispatches the upload through {@link TerminalDispatch#dispatchAndRelay}
         * on a virtual thread, handing it an observed routing context.
         */
        private void dispatchThroughTheEdge(RoutingContext ctx) {
            ctx.request().pause();
            ctx.put(ResponseEnds.INBOUND_BODY_KEY, DispatchStage.InboundBody.arm(ctx.request()));
            ctx.put(TerminalDispatch.CLIENT_CONTEXT_KEY, vertx.getOrCreateContext());
            ctx.put(RejectionReporter.ROUTE_KEY, ROUTE_ID);
            clientConnection.set(ctx.request().connection());
            ObservedContext context = new ObservedContext(ctx);
            observed.set(context);
            String path = ctx.request().path();
            PipelineRequest request = PipelineRequest.builder().method(HttpMethod.POST).requestPath(path)
                    .declaredContentLength(-1L).bodyPresent(true).build();
            request.canonicalPath(path);
            ForwardPolicyStage.Result forward = new ForwardPolicyStage.Result(Map.of(), List.of());
            Thread.ofVirtual().start(() -> terminalDispatch.dispatchAndRelay(context.proxy(), request, route, forward));
        }

        /**
         * Sends a chunked upload to {@code upstreamPath} on route {@value #ROUTE_ID}, one chunk every
         * {@value #TRICKLE_INTERVAL_MILLIS} ms, until the edge closes the connection.
         *
         * @return every byte the client received before the close
         */
        private String trickleUntilClosed(String upstreamPath) throws Exception {
            NetSocket socket = Awaits.connect(netClient.connect(frontServer.actualPort(), LoopbackHost.ADDRESS),
                    "the raw TCP client to connect to the front server");
            Buffer received = Buffer.buffer();
            CompletableFuture<String> closed = new CompletableFuture<>();
            socket.handler(received::appendBuffer);
            socket.closeHandler(end -> closed.complete(received.toString()));
            socket.write("POST /" + ROUTE_ID + upstreamPath + " HTTP/1.1" + CRLF + "Host: localhost:"
                    + frontServer.actualPort() + CRLF + "Transfer-Encoding: chunked" + CRLF + CRLF);
            long startNanos = System.nanoTime();
            vertx.setPeriodic(TRICKLE_INTERVAL_MILLIS, timer -> {
                if (closed.isDone()) {
                    vertx.cancelTimer(timer);
                } else if (System.nanoTime() - startNanos >= TimeUnit.MILLISECONDS.toNanos(TRICKLE_MILLIS)) {
                    vertx.cancelTimer(timer);
                    socket.write("0" + CRLF + CRLF);
                } else {
                    socket.write("1" + CRLF + "x" + CRLF);
                }
            });
            return Awaits.connect(closed, "the edge to close the connection of the trickling upload");
        }

        /** The terminal actions over the fixture's short request-body bounds and the stub upstream. */
        private TerminalDispatch terminalDispatch() {
            GatewayEventCounter eventCounter = new GatewayEventCounter();
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(eventCounter);
            EdgeHardeningOptions hardening = new EdgeHardeningOptions(EdgeHardeningConfig.defaults(),
                    IDLE_TIMEOUT_MILLIS, BODY_DEADLINE_MILLIS);
            SheriffMetrics metrics = new SheriffMetrics(new SimpleMeterRegistry());
            RejectionReporter rejectionReporter = new RejectionReporter(eventCounter, metrics,
                    hardening.requestBodyIdleTimeoutMillis(), hardening.requestBodyDeadlineMillis());
            UpstreamTarget target = UpstreamTarget.of(route);
            Guard guard = Guard.create().withTimeout().duration(GUARD_SECONDS, ChronoUnit.SECONDS).done().build();
            UpstreamClients clients = new UpstreamClients(Map.of(target, upstreamClient),
                    Map.of(new ResilienceShape(target, route.retryEnabled()), guard));
            return new TerminalDispatch(clients, 1024L * 1024L, hardening, failureMapper, metrics, eventCounter,
                    BffRuntime.inert(), new EdgeAdmission(hardening), connectionRetirement, rejectionReporter,
                    new ErrorResponseWriter(PortalEndpoint.inert(), new GatewayJson(new ObjectMapper()),
                            new GrpcStatusMapper(), rejectionReporter),
                    new ResponseStage(GatewayCookies.of(GatewayConfig.builder().version(1).build())),
                    new OriginValidationStage(),
                    new WebSocketRelayStage(vertx.createWebSocketClient(), failureMapper, eventCounter,
                            WebSocketRelayStage.RelayObserver.NO_OP));
        }

        /** The response head of a raw exchange, lower-cased, without the blank line that ends it. */
        private static String headOf(String exchange) {
            return exchange.substring(0, Math.max(0, exchange.indexOf(CRLF + CRLF))).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A routing context that delegates every call to a real one and observes it: the context-data writes
     * made from an event-loop thread, the lookups of a key the context holds no value for, and the end
     * handlers registered on it.
     */
    private static final class ObservedContext {

        private final RoutingContext delegate;
        private final RoutingContext proxy;
        private final AtomicInteger writesOnAnEventLoop = new AtomicInteger();
        private final AtomicInteger absentLookups = new AtomicInteger();
        private final AtomicInteger endHandlers = new AtomicInteger();

        ObservedContext(RoutingContext delegate) {
            this.delegate = delegate;
            this.proxy = RoutingContext.class.cast(Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                    new Class<?>[]{RoutingContext.class}, this::observe));
        }

        RoutingContext proxy() {
            return proxy;
        }

        private @Nullable Object observe(Object self, Method method, Object @Nullable [] args) throws Throwable {
            String name = method.getName();
            if ("put".equals(name) && Context.isOnEventLoopThread()) {
                writesOnAnEventLoop.incrementAndGet();
            } else if ("addEndHandler".equals(name)) {
                endHandlers.incrementAndGet();
            }
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            if ("get".equals(name) && result == null) {
                absentLookups.incrementAndGet();
            }
            // put returns the context itself; hand back the observed one so chained calls stay observed.
            return result == delegate ? self : result;
        }
    }

    /**
     * A request on a fake connection and its routing context: the HTTP version, whether the response head
     * has been written, the {@code Connection} headers put on it, the handlers registered for its end and
     * the closes of the connection. A context datum reads as the retirement mark exactly when
     * {@link #markedForRetirement} is set; a write to the context data fails the case.
     */
    private static final class RetiringExchange {

        private final HttpVersion version;
        private final boolean headWritten;
        private final AtomicInteger closes = new AtomicInteger();
        private final List<String> connectionHeaders = new ArrayList<>();
        private final List<Handler<AsyncResult<Void>>> endHandlers = new ArrayList<>();
        private boolean markedForRetirement;
        private final HttpConnection connection = fake(HttpConnection.class, (name, args) -> {
            if ("close".equals(name) && args.length == 0) {
                closes.incrementAndGet();
                return Future.succeededFuture();
            }
            throw new UnsupportedOperationException("the connection fake does not answer " + name);
        });

        RetiringExchange(HttpVersion version, boolean headWritten) {
            this.version = version;
            this.headWritten = headWritten;
        }

        HttpConnection connection() {
            return connection;
        }

        RoutingContext routingContext() {
            HttpServerRequest request = fake(HttpServerRequest.class, (name, args) -> switch (name) {
                case "version" -> version;
                case "connection" -> connection;
                default -> throw new UnsupportedOperationException("the request fake does not answer " + name);
            });
            HttpServerResponse response = fake(HttpServerResponse.class, (name, args) -> switch (name) {
                case "headWritten" -> headWritten;
                case "putHeader" -> {
                    connectionHeaders.add(String.valueOf(args[1]));
                    yield null;
                }
                default -> throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
            return fake(RoutingContext.class, (name, args) -> switch (name) {
                case "request" -> request;
                case "response" -> response;
                case "get" -> markedForRetirement ? Boolean.TRUE : null;
                case "addEndHandler" -> {
                    @SuppressWarnings("unchecked") // the fake hands back what the caller registered
                    Handler<AsyncResult<Void>> handler = (Handler<AsyncResult<Void>>) args[0];
                    endHandlers.add(handler);
                    yield endHandlers.size(); // the id Vert.x hands back for the registered handler
                }
                default -> throw new UnsupportedOperationException("the routing context fake does not answer " + name);
            });
        }
    }

    /**
     * The response of a failed relay as a fake: whether its head was written, the headers the relay copied
     * from the upstream — a {@code Content-Length} among them — and every status set on it.
     */
    private static final class FailedRelayResponse {

        private final boolean headWritten;
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add(CONTENT_LENGTH, "42")
                .add(RELAYED_HEADER, RELAYED_VALUE);
        private final List<Integer> statuses = new ArrayList<>();

        FailedRelayResponse(boolean headWritten) {
            this.headWritten = headWritten;
        }

        HttpServerResponse fake() {
            return TerminalDispatchLateBodyTimeoutTest.fake(HttpServerResponse.class, (name, args) -> switch (name) {
                case "headWritten" -> headWritten;
                case "headers" -> headers;
                case "setStatusCode" -> {
                    statuses.add((Integer) args[0]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
        }
    }

    /**
     * One client exchange made of fakes: the request, its response and connection, and the client
     * connection's context, which queues every task it is given until {@link #runClientTasks()}.
     */
    private static final class Exchange {

        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger tasksRun = new AtomicInteger();
        private final List<Handler<?>> clientTasks = new ArrayList<>();

        void runClientTasks() {
            List<Handler<?>> due = List.copyOf(clientTasks);
            clientTasks.clear();
            for (Handler<?> task : due) {
                tasksRun.incrementAndGet();
                task.handle(null);
            }
        }

        Context clientContext() {
            return fake(Context.class, (name, args) -> {
                if ("runOnContext".equals(name)) {
                    clientTasks.add((Handler<?>) args[0]);
                    return null;
                }
                throw new UnsupportedOperationException("the client context fake does not answer " + name);
            });
        }

        HttpServerRequest request() {
            HttpServerResponse response = fake(HttpServerResponse.class, (name, args) -> {
                if ("ended".equals(name)) {
                    return ended.get();
                }
                throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
            HttpConnection connection = fake(HttpConnection.class, (name, args) -> {
                if ("close".equals(name) && args.length == 0) {
                    closes.incrementAndGet();
                    return Future.succeededFuture();
                }
                throw new UnsupportedOperationException("the connection fake does not answer " + name);
            });
            return fake(HttpServerRequest.class, (name, args) -> switch (name) {
                case "response" -> response;
                case "connection" -> connection;
                default -> throw new UnsupportedOperationException("the request fake does not answer " + name);
            });
        }
    }

    /** Answers one method call of a fake by its name and arguments. */
    @FunctionalInterface
    private interface Answer {

        @Nullable
        Object answer(String name, Object[] args);
    }

    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    // The identity methods a retired-connection set or a map key asks of any object.
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName() + " fake";
                    default -> answer.answer(method.getName(), args == null ? new Object[0] : args);
                }));
    }
}
