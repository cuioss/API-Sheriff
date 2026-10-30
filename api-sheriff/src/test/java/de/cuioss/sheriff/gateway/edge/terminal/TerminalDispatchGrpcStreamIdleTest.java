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

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
import de.cuioss.sheriff.gateway.edge.relay.GrpcStreamIdleBounds;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage;
import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamFailureMapper;
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
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The stream-idle bound of a gRPC call, driven through {@link TerminalDispatch#dispatchGrpc} itself —
 * the package-private overload taking the call's bound, which the public {@code dispatchGrpc} calls with
 * the fixed five-minute bound — against a live prior-knowledge h2c stub upstream: the dispatch function
 * is the edge's own, so the dispatch is handed the request stream the bound watches, the relay is given
 * the bound, and an expiry is recorded as {@code ApiSheriff-25}, latched per route. Only the bound itself
 * is built with the test-only {@value #IDLE_BOUND_MILLIS} ms timeout in place of the fixed five minutes.
 * The factory the public path builds its bound with, {@code GrpcStreamIdleBound.on}, is asserted to carry
 * exactly those five minutes; the public path's hand-over of that bound to the overload is not observed.
 * <p>
 * <strong>What the stub upstream does.</strong> A call is answered at once with its headers and a first
 * frame {@code head}; its answer ends with a {@code grpc-status: 0} trailer when the request stream has
 * ended. The stub records every frame it read, and whether the request stream ended or failed — a failed
 * stream is how it observes the gateway resetting the call.
 */
@EnableTestLogger(debug = TerminalDispatch.class)
@DisplayName("TerminalDispatch — a gRPC call runs under the stream-idle bound the edge wires")
@Tag("isolated-fork")
class TerminalDispatchGrpcStreamIdleTest {

    private static final String CALL_PATH = "/svc.Service/Bidi";
    private static final String ROUTE_ID = "g";
    /** The call path of the second route, used where a case needs a route other than {@value #ROUTE_ID}. */
    private static final String OTHER_CALL_PATH = "/svc.Other/Bidi";
    private static final String OTHER_ROUTE_ID = "h";
    /** The test-only stream-idle timeout the bound of every call is built with. */
    private static final long IDLE_BOUND_MILLIS = 1_000L;
    /** How often the client sends a frame while the upstream stays silent: a fifth of the bound. */
    private static final long TICK_MILLIS = IDLE_BOUND_MILLIS / 5;
    /** How long the upstream stays silent while the client keeps sending: three stream-idle bounds. */
    private static final long SILENCE_MILLIS = 3 * IDLE_BOUND_MILLIS;
    /** The timeout of the stand-in guard: longer than any case, so no case is ended by the guard. */
    private static final long GUARD_SECONDS = 120L;
    private static final String GRPC_STATUS = "grpc-status";
    private static final String GRPC_MESSAGE = "grpc-message";
    private static final String GRPC_OK = "0";
    /** The INFO record a call ended by its stream-idle bound is reported with. */
    private static final String STREAM_IDLE_RECORD = "ApiSheriff-25";

    private final List<String> upstreamFrames = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> upstreamEnded = new CompletableFuture<>();
    private final CompletableFuture<Throwable> upstreamFailed = new CompletableFuture<>();
    /** Completed when the stream-idle bound of the front server's call expired. */
    private final CompletableFuture<Void> boundExpired = new CompletableFuture<>();
    private Vertx vertx;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private HttpClient forcedH2;
    private HttpClient edgeClient;
    private TerminalDispatch terminalDispatch;
    private Route route;
    private Route otherRoute;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(this::handleUpstreamCall)
                .listen(0, LoopbackHost.ADDRESS), "the stub gRPC upstream to start listening");
        forcedH2 = vertx.createHttpClient(priorKnowledgeH2());
        edgeClient = vertx.createHttpClient(priorKnowledgeH2());
        route = grpcRoute(ROUTE_ID, "/svc.Service");
        otherRoute = grpcRoute(OTHER_ROUTE_ID, "/svc.Other");
        terminalDispatch = terminalDispatch();
        Router front = Router.router(vertx);
        front.route().handler(this::relayThroughTheEdgeWiring);
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

    @Test
    @DisplayName("a call carrying no frame in either direction ends with DEADLINE_EXCEEDED and is recorded as ApiSheriff-25")
    void idleCallIsEndedAndRecorded() throws Exception {
        ClientCall call = open(CALL_PATH);

        Awaits.connect(call.request().write("first"), "the first frame to be sent");
        Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
        MultiMap trailers = Awaits.connect(call.trailers(), "the idle call to be ended by its stream-idle bound");
        Awaits.connect(upstreamFailed, "the upstream call to be reset");

        assertAll("a call that went quiet in both directions",
                () -> assertEquals(Integer.toString(GrpcStatusMapper.DEADLINE_EXCEEDED), trailers.get(GRPC_STATUS),
                        "the call ends with DEADLINE_EXCEEDED in its trailers"),
                () -> assertEquals("stream-idle-timeout", trailers.get(GRPC_MESSAGE),
                        "the trailers name the stream-idle bound and nothing either side sent"),
                () -> assertTrue(boundExpired.isDone(), "the call's stream-idle bound expired"),
                () -> assertFalse(upstreamEnded.isDone(), "the request stream never ended at the upstream"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, STREAM_IDLE_RECORD));
    }

    /**
     * The request frames count only because {@code dispatchGrpc} hands the dispatch the stream the bound
     * watches: a client that keeps sending while the upstream is silent keeps the call open. A dispatch
     * function that streamed the request body unwatched would let the bound expire under the client's
     * traffic.
     */
    @Test
    @DisplayName("a client that keeps sending keeps the call open while the upstream is silent past the stream-idle bound")
    void clientTrafficKeepsTheCallOpen() throws Exception {
        ClientCall call = open(CALL_PATH);
        int pings = (int) (SILENCE_MILLIS / TICK_MILLIS);

        Awaits.connect(call.request().write("first"), "the first frame to be sent");
        Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
        for (int ping = 0; ping < pings; ping++) {
            Awaits.connect(pause(TICK_MILLIS), "the gap before the next client frame");
            Awaits.connect(call.request().write("ping"), "a client frame to be sent");
        }
        boolean openAfterSilence = !call.trailers().isDone() && !upstreamFailed.isDone();
        Awaits.connect(call.request().end(), "the request stream to end");
        MultiMap trailers = Awaits.connect(call.trailers(), "the call to end with its request stream");

        assertAll("a call whose upstream stayed silent while its client kept sending",
                () -> assertTrue(openAfterSilence, "the silence ended neither the client call nor the upstream call"),
                () -> assertEquals(GRPC_OK, trailers.get(GRPC_STATUS), "the call ends with the upstream's status"),
                () -> assertEquals("first" + "ping".repeat(pings), String.join("", upstreamFrames),
                        "every client frame reached the upstream"),
                () -> assertFalse(boundExpired.isDone(), "the stream-idle bound never expired"),
                () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"),
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO, STREAM_IDLE_RECORD));
    }

    @Test
    @DisplayName("GrpcStreamIdleBound.on, the factory the public dispatchGrpc builds its bound with, carries exactly five minutes")
    void onBuildsTheFixedFiveMinuteBound() {
        ResponseStage.GrpcStreamIdleBound bound = ResponseStage.GrpcStreamIdleBound.on(vertx);

        assertEquals(TimeUnit.MINUTES.toMillis(5), GrpcStreamIdleBounds.timeoutOf(bound),
                "the stream-idle bound of every gRPC call is the fixed five minutes, reached by no configuration");
    }

    /**
     * Three calls go quiet at once: two on route {@value #ROUTE_ID}, one on route {@value #OTHER_ROUTE_ID}.
     * Whichever of the two {@value #ROUTE_ID} expiries comes first is recorded at {@code INFO}, the other
     * one stays at {@code DEBUG}, and the first expiry on {@value #OTHER_ROUTE_ID} is recorded at
     * {@code INFO} again.
     */
    @Test
    @DisplayName("a repeated expiry on a route stays at DEBUG; a first expiry on another route is recorded at INFO")
    void idleExpiryRecordIsLatchedPerRoute() throws Exception {
        List<ClientCall> calls = List.of(open(CALL_PATH), open(CALL_PATH), open(OTHER_CALL_PATH));
        for (ClientCall call : calls) {
            Awaits.connect(call.request().write("first"), "the first frame of a call to be sent");
        }
        List<String> statuses = new ArrayList<>();
        for (ClientCall call : calls) {
            statuses.add(Awaits.connect(call.trailers(), "an idle call to be ended by its stream-idle bound")
                    .get(GRPC_STATUS));
        }
        String deadlineExceeded = Integer.toString(GrpcStatusMapper.DEADLINE_EXCEEDED);

        assertAll("three idle calls on two routes",
                () -> assertEquals(List.of(deadlineExceeded, deadlineExceeded, deadlineExceeded), statuses,
                        "every call is ended by its stream-idle bound"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                        routeRecord(ROUTE_ID)),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.DEBUG,
                        routeRecord(ROUTE_ID)),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                        routeRecord(OTHER_ROUTE_ID)),
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.DEBUG, routeRecord(OTHER_ROUTE_ID)));
    }

    /** The text the stream-idle record of {@code routeId} carries, at either level. */
    private static String routeRecord(String routeId) {
        return "gRPC call on route '" + routeId + "' carried no frame";
    }

    private void handleUpstreamCall(HttpServerRequest request) {
        HttpServerResponse response = request.response().setChunked(true);
        request.handler(frame -> {
            // An empty final DATA frame carries only the end of the stream, not a message.
            if (frame.length() > 0) {
                upstreamFrames.add(frame.toString());
            }
        });
        request.exceptionHandler(upstreamFailed::complete);
        request.endHandler(end -> {
            upstreamEnded.complete(null);
            response.putTrailer(GRPC_STATUS, GRPC_OK);
            response.end();
        });
        response.write("head");
    }

    /**
     * The front server's handler: arms the request body as the edge root does and dispatches the call
     * through {@link TerminalDispatch#dispatchGrpc}, under a stream-idle bound of
     * {@value #IDLE_BOUND_MILLIS} ms, on the route whose prefix the path names.
     */
    private void relayThroughTheEdgeWiring(RoutingContext ctx) {
        ctx.request().pause();
        ctx.put(ResponseEnds.INBOUND_BODY_KEY, DispatchStage.InboundBody.arm(ctx.request()));
        ResponseStage.GrpcStreamIdleBound idleBound =
                ResponseStage.GrpcStreamIdleBound.withTimeout(vertx, IDLE_BOUND_MILLIS);
        idleBound.expiry().onSuccess(expired -> boundExpired.complete(null));
        String path = ctx.request().path();
        Route selected = path.startsWith(OTHER_CALL_PATH) ? otherRoute : route;
        PipelineRequest request = PipelineRequest.builder().method(HttpMethod.POST).requestPath(path)
                .declaredContentLength(-1L).build();
        request.canonicalPath(path);
        ForwardPolicyStage.Result forward = new ForwardPolicyStage.Result(Map.of(), List.of());
        Thread.ofVirtual().start(() -> terminalDispatch.dispatchGrpc(ctx, request, selected, forward, idleBound));
    }

    /** A {@code protocol: grpc} route under {@code prefix} to the stub upstream. */
    private Route grpcRoute(String id, String prefix) {
        return ConfigFixtures.route(id, prefix)
                .protocol(Protocol.GRPC)
                .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                .build();
    }

    /** The forced-h2 client and a stand-in guard of {@value #GUARD_SECONDS} s for the route's upstream. */
    private UpstreamClients clients() {
        UpstreamTarget target = UpstreamTarget.of(route);
        Guard guard = Guard.create().withTimeout().duration(GUARD_SECONDS, ChronoUnit.SECONDS).done().build();
        return new UpstreamClients(Map.of(target, forcedH2),
                Map.of(new ResilienceShape(target, route.retryEnabled()), guard));
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
     * Opens a call to {@code path} on the front server and records what comes back.
     *
     * @param path the call path, naming the route by its prefix
     * @return the open call; its request headers are sent with its first frame
     */
    private ClientCall open(String path) throws Exception {
        HttpClientRequest request = Awaits.connect(edgeClient.request(io.vertx.core.http.HttpMethod.POST,
                frontServer.actualPort(), LoopbackHost.ADDRESS, path), "a call to the front server to open");
        request.setChunked(true);
        request.exceptionHandler(failure -> {
            // The one end rule resets an answered stream whose request is still open once the answer is
            // written; what the call observed is read from its response.
        });
        List<String> frames = new CopyOnWriteArrayList<>();
        CompletableFuture<MultiMap> trailers = new CompletableFuture<>();
        request.response().onSuccess(response -> {
            response.handler(frame -> frames.add(frame.toString()));
            response.exceptionHandler(trailers::completeExceptionally);
            response.endHandler(end -> trailers.complete(response.trailers()));
        }).onFailure(trailers::completeExceptionally);
        return new ClientCall(request, frames, trailers);
    }

    /** A future completed once {@code millis} have passed. */
    private CompletableFuture<Void> pause(long millis) {
        CompletableFuture<Void> passed = new CompletableFuture<>();
        vertx.setTimer(millis, fired -> passed.complete(null));
        return passed;
    }

    private static HttpClientOptions priorKnowledgeH2() {
        return new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
    }

    /**
     * One call to the front server: its request, every response frame it received and the trailers its
     * response ended with.
     */
    private record ClientCall(HttpClientRequest request, List<String> frames, CompletableFuture<MultiMap> trailers) {
    }
}
