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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Protocol;
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
import de.cuioss.sheriff.gateway.events.GatewayException;
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
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A gRPC client that leaves before its upstream answers, driven through the public
 * {@link TerminalDispatch#dispatchGrpc} against a live prior-knowledge h2c stub upstream.
 * <p>
 * <strong>Why the departure needs a channel of its own.</strong> A request stream reports a reset or a
 * closed connection only while its body is still open. Once the client has ended its request stream —
 * as a unary or client-streaming call normally has before it is answered — only the response learns that
 * it can no longer be answered. The edge root hands that to the request body it armed
 * ({@link DispatchStage.InboundBody#clientDeparted}), and the dispatch reads the departure from the body
 * it is handed: it releases the upstream request at once and ends as the client's own abort, which the
 * breaker skips. The gRPC path hands the dispatch the request body its stream-idle bound watches, so
 * that body has to be the armed one. A dispatch that is handed a decorator around it never learns of the
 * departure: the upstream request stays in flight until the upstream answers or the guard's timeout ends
 * the attempt, which the breaker counts as the upstream's failure.
 * <p>
 * <strong>The rig.</strong> The front server's handler does what the edge root does around a dispatch:
 * it arms the request body, pauses the request and reports a response that ended without being written
 * as the client's departure. The call then runs through {@code dispatchGrpc} itself, with the gRPC
 * dispatch's production bounds, under a guard that carries the production breaker rule — a
 * {@link GatewayException} is skipped — and opens on the first failure it counts.
 * <p>
 * <strong>What the stub upstream does.</strong> It reads every call to the end of its request stream. A
 * call to {@value #HELD_CALL} is then held, never answered; a call to {@value #REFUSED_CALL} is reset by
 * the upstream. The stub records the end of a request stream, and the reset of a call it holds — which
 * is how it observes the gateway releasing the upstream request.
 */
@DisplayName("TerminalDispatch — a gRPC client that leaves before its upstream answers ends the call as its own departure")
@Tag("isolated-fork")
class TerminalDispatchGrpcClientDepartureTest {

    /** The prefix of the route every call takes; the dispatch strips it towards the upstream. */
    private static final String SERVICE = "/svc.Service";
    /** The call the stub upstream reads to its end and never answers. */
    private static final String HELD_CALL = SERVICE + "/Held";
    /** The method the stub upstream reads to its end and then resets, as the upstream sees its path. */
    private static final String REFUSED_METHOD = "/Refused";
    /** The call the stub upstream reads to its end and then resets. */
    private static final String REFUSED_CALL = SERVICE + REFUSED_METHOD;
    private static final String ROUTE_ID = "g";
    /**
     * The timeout of the stand-in guard: far longer than a departure takes to reach the dispatch, so no
     * case that passes is ended by it, and short enough that a dispatch the departure never reached is
     * ended by it — as the upstream's timeout — well inside the wait of the case.
     */
    private static final long GUARD_SECONDS = 10L;
    /** The HTTP/2 error code {@code INTERNAL_ERROR}, which the stub upstream refuses a call with. */
    private static final long HTTP2_INTERNAL_ERROR = 0x2L;

    /** Completed when the stub upstream has read a call's request stream to its end. */
    private final CompletableFuture<Void> upstreamEnded = new CompletableFuture<>();
    /** Completed when a call the stub upstream holds is reset or closed under it. */
    private final CompletableFuture<Void> upstreamReleased = new CompletableFuture<>();
    /** Completed with the rejection the dispatch of the front server's call ended with. */
    private final CompletableFuture<GatewayException> dispatchRejected = new CompletableFuture<>();
    /** Every state the guard's breaker moved to, in order. */
    private final List<CircuitBreakerState> breakerTransitions = new CopyOnWriteArrayList<>();
    private Vertx vertx;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private HttpClient forcedH2;
    private HttpClient edgeClient;
    private TerminalDispatch terminalDispatch;
    private Route route;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(this::handleUpstreamCall)
                .listen(0, LoopbackHost.ADDRESS), "the stub gRPC upstream to start listening");
        forcedH2 = vertx.createHttpClient(priorKnowledgeH2());
        edgeClient = vertx.createHttpClient(priorKnowledgeH2());
        route = ConfigFixtures.route(ROUTE_ID, SERVICE)
                .protocol(Protocol.GRPC)
                .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                .build();
        terminalDispatch = terminalDispatch();
        Router front = Router.router(vertx);
        front.route().handler(this::dispatchAsTheEdgeRootDoes);
        frontServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(front)
                .listen(0, LoopbackHost.ADDRESS), "the front gRPC server to start listening");
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(edgeClient.close(), "the client of the front server to close");
        Awaits.teardown(forcedH2.close(), "the forced-h2 client to close");
        Awaits.teardown(frontServer.close(), "the front gRPC server to close");
        Awaits.teardown(upstreamServer.close(), "the stub gRPC upstream to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    /**
     * The window the request stream itself is silent in: the client has sent its whole request and ended
     * its stream, the upstream holds the complete request and has not answered, and the client resets
     * the call. Only the response reports that; the dispatch must still learn of it.
     */
    @Test
    @DisplayName("a client that resets its call after ending its request stream ends the dispatch as its departure, releases the upstream call and is not counted by the breaker")
    void clientLeavingAfterItsRequestStreamEndedIsItsOwnDeparture() throws Exception {
        HttpClientRequest call = open(HELD_CALL);
        Awaits.connect(call.end(grpcMessage("request")), "the whole request to be sent and its stream ended");
        Awaits.connect(upstreamEnded, "the upstream to read the request to its end");

        call.reset();
        GatewayException ended = Awaits.connect(dispatchRejected, "the dispatch to end once its client left");
        Awaits.connect(upstreamReleased, "the upstream call to be released");

        assertAll("a call whose client left while its upstream held the complete request",
                () -> assertInstanceOf(DispatchStage.ClientDeparted.class, ended,
                        () -> "the client's departure, not an upstream failure: " + ended),
                () -> assertEquals(EventType.INBOUND_BODY_ABORTED, ended.getEventType(),
                        "the departure is the client's abort"),
                () -> assertInstanceOf(StreamResetException.class, ended.getCause(),
                        "the client's reset is kept as the cause"),
                () -> assertEquals(List.of(), breakerTransitions,
                        "the breaker counted no failure for the client's departure"));
    }

    /**
     * Matched control: the same rig, the same complete request, but the client stays and the upstream
     * resets the call. That is an upstream failure, and the guard's breaker opens on it — so a breaker
     * that made no transition after a departure is a verdict of this rig, not a breaker unable to open.
     */
    @Test
    @DisplayName("control — an upstream that resets a call its client is waiting on is an upstream failure the breaker counts")
    void upstreamResettingTheCallIsAnUpstreamFailure() throws Exception {
        HttpClientRequest call = open(REFUSED_CALL);
        Awaits.connect(call.end(grpcMessage("request")), "the whole request to be sent and its stream ended");

        GatewayException ended = Awaits.connect(dispatchRejected, "the dispatch to end once the upstream reset the call");

        assertAll("a call its upstream reset",
                () -> assertFalse(ended instanceof DispatchStage.ClientDeparted, "no client left"),
                () -> assertEquals(EventType.UPSTREAM_ERROR, ended.getEventType(), "an upstream failure"),
                () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                        "the breaker opens on the one failure it counts"));
    }

    private void handleUpstreamCall(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        // A request whose stream has ended no longer reports a reset; the unanswered response does.
        response.exceptionHandler(failure -> upstreamReleased.complete(null));
        response.closeHandler(closed -> upstreamReleased.complete(null));
        request.exceptionHandler(failure -> upstreamReleased.complete(null));
        request.handler(frame -> {
            // The call's one message is read and dropped: only the end of the stream matters here.
        });
        request.endHandler(end -> {
            upstreamEnded.complete(null);
            // The dispatch strips the route prefix, so the upstream sees the method alone.
            if (REFUSED_METHOD.equals(request.path())) {
                response.reset(HTTP2_INTERNAL_ERROR);
            }
        });
    }

    /**
     * The front server's handler, doing what the edge root does around a dispatch: the request body is
     * armed and the request paused before the virtual-thread hop, and a response that ended without
     * being written is reported to the armed body as its client's departure. The call is then dispatched
     * through the public {@link TerminalDispatch#dispatchGrpc}.
     */
    private void dispatchAsTheEdgeRootDoes(RoutingContext ctx) {
        ctx.addEndHandler(ended -> {
            if (ended.failed()) {
                DispatchStage.InboundBody armed = ctx.get(ResponseEnds.INBOUND_BODY_KEY);
                armed.clientDeparted(ended.cause());
            }
        });
        ctx.put(ResponseEnds.INBOUND_BODY_KEY, DispatchStage.InboundBody.arm(ctx.request()));
        ctx.request().pause();
        String path = ctx.request().path();
        PipelineRequest request = PipelineRequest.builder().method(HttpMethod.POST).requestPath(path)
                .declaredContentLength(-1L).build();
        request.canonicalPath(path);
        ForwardPolicyStage.Result forward = new ForwardPolicyStage.Result(Map.of(), List.of());
        Thread.ofVirtual().start(() -> {
            try {
                terminalDispatch.dispatchGrpc(ctx, request, route, forward);
                dispatchRejected.completeExceptionally(new AssertionError("the call was answered"));
            } catch (GatewayException rejected) {
                dispatchRejected.complete(rejected);
            }
        });
    }

    /**
     * The forced-h2 client and a guard that carries the production breaker rule — a
     * {@link GatewayException} is skipped — opens on the first failure it counts, and times an attempt
     * out after {@value #GUARD_SECONDS} s.
     */
    private UpstreamClients clients() {
        UpstreamTarget target = UpstreamTarget.of(route);
        Guard breakerOnFirstFailure = Guard.create()
                .withCircuitBreaker().requestVolumeThreshold(1).failureRatio(1.0).delay(1, ChronoUnit.MINUTES)
                .skipOn(GatewayException.class).onStateChange(breakerTransitions::add).done()
                .withTimeout().duration(GUARD_SECONDS, ChronoUnit.SECONDS).done()
                .build();
        return new UpstreamClients(Map.of(target, forcedH2),
                Map.of(new ResilienceShape(target, route.retryEnabled()), breakerOnFirstFailure));
    }

    /** The terminal actions with the collaborators the edge root builds them with, over the default bounds. */
    private TerminalDispatch terminalDispatch() {
        GatewayEventCounter eventCounter = new GatewayEventCounter();
        UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(eventCounter);
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        SheriffMetrics metrics = new SheriffMetrics(new SimpleMeterRegistry());
        RejectionReporter rejectionReporter = new RejectionReporter(eventCounter, metrics,
                hardening.requestBodyIdleTimeoutMillis(), hardening.requestBodyDeadlineMillis());
        return new TerminalDispatch(clients(), 1024L * 1024L, hardening, failureMapper, metrics, eventCounter,
                BffRuntime.inert(), new EdgeAdmission(hardening), new ConnectionRetirement(new FramingGate(false)),
                rejectionReporter, new ErrorResponseWriter(PortalEndpoint.inert(), new GatewayJson(new ObjectMapper()),
                        new GrpcStatusMapper(), rejectionReporter, true),
                new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway().build())),
                new OriginValidationStage(),
                new WebSocketRelayStage(vertx.createWebSocketClient(), failureMapper, eventCounter,
                        WebSocketRelayStage.RelayObserver.NO_OP));
    }

    /**
     * Opens a call to {@code path} on the front server. Its request headers are sent with its first
     * frame; what the call itself observes of its end is not what a case asserts.
     */
    private HttpClientRequest open(String path) throws Exception {
        HttpClientRequest request = Awaits.connect(edgeClient.request(io.vertx.core.http.HttpMethod.POST,
                frontServer.actualPort(), LoopbackHost.ADDRESS, path), "a call to the front server to open");
        request.setChunked(true);
        request.exceptionHandler(failure -> {
            // A call the client resets, or the dispatch refuses, fails its own request: nothing to observe.
        });
        request.response().onFailure(failure -> {
            // Likewise for the answer the call never receives.
        });
        return request;
    }

    /** @return {@code payload} framed as one uncompressed gRPC length-prefixed message */
    private static Buffer grpcMessage(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Buffer.buffer().appendByte((byte) 0).appendInt(bytes.length).appendBytes(bytes);
    }

    private static HttpClientOptions priorKnowledgeH2() {
        return new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
    }
}
