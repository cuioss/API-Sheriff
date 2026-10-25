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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.edge.terminal.TerminalDispatch;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract of the {@code protocol: grpc} dispatch path (deliverable 6): the constructor contract, the
 * h2-negotiation-failure → gRPC {@code UNAVAILABLE} mapping, the response-trailer relay carrying
 * {@code grpc-status} / {@code grpc-message} to the client, and the stream bounds of a call over a live
 * forced-h2 upstream, the request-body cap among them.
 * <p>
 * The forced-HTTP/2 client wiring (a gRPC route holds a client distinct from an HTTP/1.1 route to the
 * same host:port, and gRPC routes to one host:port share it) is pinned where its production class is
 * tested, by {@code RouteAssemblerTest}. The GW-07 request-body byte cap is pinned twice.
 * {@code DispatchStageTest.ByteCap} tests {@code DispatchStage.ByteCappedBodyStream} on its own. This
 * stage's whole part in the cap is handing its {@code max_body_bytes} to the {@link DispatchStage} it
 * delegates to, and {@code StreamBounds#bodyPastTheCapIsRefusedAndItsBreachingFrameNeverForwarded}
 * streams a body past a small cap through {@link GrpcDispatchStage#dispatch}: the dispatch is refused
 * as {@code CONTENT_TOO_LARGE} and the frame that crosses the cap never reaches the upstream.
 */
@DisplayName("GrpcDispatchStage — UNAVAILABLE mapping, trailer relay, stream bounds")
@Tag("isolated-fork")
class GrpcDispatchStageTest {

    @Nested
    @DisplayName("constructor contract")
    class ConstructorContract {

        @Test
        @DisplayName("rejects a null failure mapper")
        void rejectsNullFailureMapper() {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());

            assertThrows(NullPointerException.class, () -> new GrpcDispatchStage(noClients, 1024L, null),
                    "the gRPC dispatch stage requires a non-null failure mapper");
        }

        @Test
        @DisplayName("builds with a body cap and a failure mapper, and maps a failed dispatch through that mapper")
        void buildsWithFailureMapper() {
            Route route = route("g", Protocol.GRPC, upstream("svc.internal"));
            UpstreamTarget forcedH2 = UpstreamTarget.of(route);
            UpstreamClients clients = new UpstreamClients(
                    Map.of(forcedH2, unusedClient()),
                    Map.of(new ResilienceShape(forcedH2, route.retryEnabled()), new RefusingGuard()));
            GrpcDispatchStage stage = new GrpcDispatchStage(clients, 1024L,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
            Map<String, String> noHeaders = Map.of();
            TestReadStream noBody = new TestReadStream();

            GatewayException mapped = assertThrows(GatewayException.class,
                    () -> stage.dispatch(route, HttpMethod.POST, "/svc.Service/Method", noHeaders, noBody, -1L),
                    "a dispatch the guard fails leaves the built stage as a mapped GatewayException");

            assertEquals(EventType.UPSTREAM_ERROR, mapped.getEventType(),
                    "the stage holds the mapper it was built with: a refused upstream connection is the 502 arm");
        }

        /** A forced-h2 client the refusing guard never lets the dispatch reach: every call fails the test. */
        private HttpClient unusedClient() {
            return (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[]{HttpClient.class}, (_, method, _) -> {
                        throw new AssertionError("the refused dispatch must not touch the client: " + method.getName());
                    });
        }
    }

    @Nested
    @DisplayName("h2-negotiation-failure mapping to gRPC status")
    class H2FailureMapping {

        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        private final GrpcStatusMapper grpcStatusMapper = new GrpcStatusMapper();

        @Test
        @DisplayName("maps an h2-negotiation dial failure through UPSTREAM_ERROR to gRPC UNAVAILABLE")
        void mapsH2DialFailureToUnavailable() {
            // Arrange — a forced-h2 dial that could not establish h2 surfaces as a connection failure
            Throwable h2DialFailure = new ConnectException("failed to negotiate h2 with upstream");

            // Act — the failure classifies as UPSTREAM_ERROR (502), which renders as gRPC UNAVAILABLE
            EventType classified = failureMapper.classify(h2DialFailure);

            // Assert
            assertEquals(EventType.UPSTREAM_ERROR, classified,
                    "an h2-negotiation dial failure is an upstream connection failure (502)");
            assertEquals(GrpcStatusMapper.UNAVAILABLE, grpcStatusMapper.toGrpcStatus(classified),
                    "a 502 upstream failure renders as gRPC UNAVAILABLE (14) on the gRPC path");
        }

        @Test
        @DisplayName("maps an upstream timeout through UPSTREAM_TIMEOUT to gRPC DEADLINE_EXCEEDED")
        void mapsTimeoutToDeadlineExceeded() {
            // Arrange
            Throwable timeout = new TimeoutException("upstream deadline elapsed");

            // Act
            EventType classified = failureMapper.classify(timeout);

            // Assert — the timeout arm is distinct from the generic h2 dial failure
            assertEquals(EventType.UPSTREAM_TIMEOUT, classified, "a timeout classifies as UPSTREAM_TIMEOUT (504)");
            assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED, grpcStatusMapper.toGrpcStatus(classified),
                    "a 504 upstream timeout renders as gRPC DEADLINE_EXCEEDED (4)");
        }
    }

    @Nested
    @DisplayName("response-trailer relay over a live Vert.x server")
    class TrailerRelay {

        private static final String FRAME_OPTIONS = "X-Frame-Options";
        private static final String ORIGIN_FRAME_OPTIONS = "SAMEORIGIN";
        private static final String CSP = "Content-Security-Policy";
        private static final String GATEWAY_POLICY = "default-src 'none'";

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();

            // Stub upstream: a chunked opaque gRPC frame followed by grpc-status / grpc-message trailers.
            // It also sets its own X-Frame-Options, so a default-mode gateway header has an origin value to
            // defer to.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(req -> {
                HttpServerResponse response = req.response();
                response.putHeader(FRAME_OPTIONS, ORIGIN_FRAME_OPTIONS);
                response.setChunked(true);
                response.write(Buffer.buffer("opaque-grpc-frame"));
                response.putTrailer("grpc-status", "0");
                response.putTrailer("grpc-message", "ok");
                response.end();
            }).listen(0, LoopbackHost.ADDRESS), "the stub gRPC upstream to start listening");
            int upstreamPort = upstream.actualPort();

            // Front server: relays the upstream response WITH its trailers exactly as the gRPC dispatch
            // path does (ResponseStage#relayWithTrailers), with X-Frame-Options and
            // Content-Security-Policy both in default mode.
            ResponseStage responseStage = new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway().build()));
            front = Awaits.connect(vertx.createHttpServer().requestHandler(clientReq -> client
                    .request(io.vertx.core.http.HttpMethod.POST, upstreamPort, LoopbackHost.ADDRESS,
                            "/svc.Service/Method")
                    .compose(upReq -> upReq.send())
                    .onSuccess(upResp -> responseStage
                            .relayWithTrailers(upResp, clientReq.response(), false, CookiesConfig.NONE, Map.of(),
                                    Map.of(FRAME_OPTIONS, "DENY", CSP, GATEWAY_POLICY), Supplier::get,
                                    ResponseStage.GrpcStreamIdleBound.on(vertx))
                            .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .listen(0, LoopbackHost.ADDRESS), "the relaying front server to start listening");
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(front.close(), "the relaying front server to close");
            Awaits.teardown(upstream.close(), "the stub gRPC upstream to close");
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("relays the upstream grpc-status / grpc-message trailers to the client")
        void relaysUpstreamTrailers() throws Exception {
            // Arrange
            int frontPort = front.actualPort();
            AtomicReference<Buffer> body = new AtomicReference<>();

            // Act — POST the front server and read the full response including its trailers
            MultiMap trailers = Awaits.connect(client
                    .request(io.vertx.core.http.HttpMethod.POST, frontPort, LoopbackHost.ADDRESS,
                            "/svc.Service/Method")
                    .compose(req -> req.send())
                    .compose(resp -> resp.body().map(buffer -> {
                        body.set(buffer);
                        return resp.trailers();
                    })), "the relayed gRPC response and its trailers");

            // Assert — the opaque frame is streamed through and the gRPC status trailers reach the client
            assertEquals("opaque-grpc-frame", body.get().toString(),
                    "the opaque gRPC frame is streamed through untouched");
            assertEquals("0", trailers.get("grpc-status"),
                    "the upstream grpc-status trailer is relayed to the client");
            assertEquals("ok", trailers.get("grpc-message"),
                    "the upstream grpc-message trailer is relayed to the client");
        }

        @Test
        @DisplayName("keeps an origin-set header and fills an absent one for default-mode gateway headers, trailers intact")
        void defaultModeDefersToOriginOnTrailerRelay() throws Exception {
            int frontPort = front.actualPort();
            AtomicReference<MultiMap> headers = new AtomicReference<>();

            MultiMap trailers = Awaits.connect(client
                    .request(io.vertx.core.http.HttpMethod.POST, frontPort, LoopbackHost.ADDRESS,
                            "/svc.Service/Method")
                    .compose(req -> req.send())
                    .compose(resp -> resp.body().map(buffer -> {
                        headers.set(resp.headers());
                        return resp.trailers();
                    })), "the relayed gRPC response headers and trailers");

            assertEquals(List.of(ORIGIN_FRAME_OPTIONS), headers.get().getAll(FRAME_OPTIONS),
                    "the origin X-Frame-Options is kept and the default-mode gateway value is not added");
            assertEquals(List.of(GATEWAY_POLICY), headers.get().getAll(CSP),
                    "the default-mode gateway policy fills the header the origin did not send");
            assertEquals("0", trailers.get("grpc-status"),
                    "the default-mode header handling leaves the gRPC status trailer untouched");
        }
    }

    /**
     * What bounds a client-streaming or bidirectional call, driven through the stage against a live
     * prior-knowledge h2c stub upstream.
     * <p>
     * The gRPC dispatch arms neither the request-body idle timeout nor the request-body deadline, so a
     * call that pauses between messages for longer than either is ended by neither. Before the upstream
     * sends its response headers the guarded call is still waiting, so the guard's whole-dispatch timeout
     * bounds the call. Once the headers have arrived the guarded call has returned and the guard no
     * longer bounds the call; its stream-idle bound does. That bound ends a call that carried no frame in
     * either direction for its timeout, and only such a call: traffic in either direction alone keeps the
     * call open. What the call may send is bounded by the route's request-body cap, which the gRPC
     * dispatch enforces on the streamed body like any other dispatch.
     * <p>
     * <strong>How the stream-idle bound is driven.</strong> The cases on it run the call through a front
     * HTTP/2 server that composes the production pieces the way {@code TerminalDispatch#dispatchGrpc}
     * does: the request body armed as the edge root arms it, the dispatch handed the body the bound
     * watches, the relay started through {@link TerminalDispatch#relayOnEventLoop}, which ends the
     * response through the one end rule, and {@link ResponseStage#relayWithTrailers} with the bound. The
     * bound is built with the test-only {@value #IDLE_BOUND_MILLIS} ms timeout in place of the fixed five
     * minutes. The edge's own wiring of the bound, and the {@code ApiSheriff-25} record of an expiry, are
     * driven by {@code TerminalDispatchGrpcStreamIdleTest}, which runs the call through
     * {@code TerminalDispatch} itself.
     * <p>
     * <strong>What the stub upstream does.</strong> A call to {@value #CLIENT_STREAMING} is answered
     * only once its request stream has ended; a call to {@value #BIDI} is answered with its headers and
     * a first frame at once, and its answer ends when the request stream has ended; a call to
     * {@value #TICKING} is answered like {@value #BIDI} and then sends one more frame every
     * {@value #TICK_MILLIS} ms until the request stream has ended. Every call ends with a
     * {@code grpc-status: 0} trailer. The stub records every frame it read, and whether the request
     * stream ended or failed — a failed stream is how it observes the gateway resetting the call.
     */
    @Nested
    @DisplayName("stream bounds over a live forced-h2 upstream")
    class StreamBounds {

        private static final String CLIENT_STREAMING = "/svc.Service/ClientStreaming";
        private static final String BIDI = "/svc.Service/Bidi";
        private static final String TICKING = "/svc.Service/Ticking";
        /** The timeout of the stand-in guard the post-header case runs under, so the case can outlast it. */
        private static final long SHORT_GUARD_MILLIS = 500L;
        /**
         * The timeout of the stand-in guard every other case runs under: longer than any case, so no case
         * can be ended by the guard and each observes only the bound it is about.
         */
        private static final long LONG_GUARD_SECONDS = 120L;
        /** How far past a bound a pause runs before the call is looked at again. */
        private static final long PAUSE_MARGIN_MILLIS = 500L;
        /** The test-only stream-idle timeout the front server's bound is built with. */
        private static final long IDLE_BOUND_MILLIS = 1_000L;
        /**
         * How often one side keeps a call alive while the other stays silent: a fifth of the bound, so a
         * frame delayed by a loaded machine still arrives well inside it.
         */
        private static final long TICK_MILLIS = IDLE_BOUND_MILLIS / 5;
        /** How long one side stays silent: three stream-idle bounds. */
        private static final long SILENCE_MILLIS = 3 * IDLE_BOUND_MILLIS;
        private static final String GRPC_STATUS = "grpc-status";
        private static final String GRPC_MESSAGE = "grpc-message";
        private static final String GRPC_OK = "0";
        /** The request-body cap of every case but the cap case: far above anything a case sends. */
        private static final long ROOMY_CAP_BYTES = 1024L * 1024L;
        /** The request-body cap of the cap case: the two frames it sends cross it only together. */
        private static final long BODY_CAP_BYTES = 8L;
        /** The first frame of the cap case: five bytes, under the cap. */
        private static final String UNDER_CAP_FRAME = "first";
        /** The second frame of the cap case: six bytes, which carry the running count to eleven. */
        private static final String BREACHING_FRAME = "second";

        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        private final ResponseStage responseStage =
                new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway().build()));
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

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                    .requestHandler(this::handleUpstreamCall)
                    .listen(0, LoopbackHost.ADDRESS), "the stub gRPC upstream to start listening");
            Router front = Router.router(vertx);
            front.route().handler(this::relayThroughTheGrpcPath);
            frontServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions())
                    .requestHandler(front)
                    .listen(0, LoopbackHost.ADDRESS), "the front gRPC server to start listening");
            forcedH2 = vertx.createHttpClient(priorKnowledgeH2());
            edgeClient = vertx.createHttpClient(priorKnowledgeH2());
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(edgeClient.close(), "the client of the front server to close");
            Awaits.teardown(forcedH2.close(), "the forced-h2 client to close");
            Awaits.teardown(frontServer.close(), "the front gRPC server to close");
            Awaits.teardown(upstreamServer.close(), "the stub gRPC upstream to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        private static HttpClientOptions priorKnowledgeH2() {
            return new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);
        }

        private void handleUpstreamCall(HttpServerRequest request) {
            HttpServerResponse response = request.response().setChunked(true);
            boolean ticking = TICKING.equals(request.path());
            long ticker = ticking ? vertx.setPeriodic(TICK_MILLIS, fired -> response.write("tick")) : -1L;
            request.handler(frame -> {
                // An empty final DATA frame carries only the end of the stream, not a message.
                if (frame.length() > 0) {
                    upstreamFrames.add(frame.toString());
                }
            });
            request.exceptionHandler(failure -> {
                vertx.cancelTimer(ticker);
                upstreamFailed.complete(failure);
            });
            request.endHandler(end -> {
                vertx.cancelTimer(ticker);
                upstreamEnded.complete(null);
                response.putTrailer(GRPC_STATUS, GRPC_OK);
                response.end();
            });
            if (ticking || BIDI.equals(request.path())) {
                response.write("head");
            }
        }

        /**
         * The front server's handler: dispatches the call and relays its answer the way
         * {@code TerminalDispatch#dispatchGrpc} does, under a stream-idle bound of
         * {@value #IDLE_BOUND_MILLIS} ms.
         */
        private void relayThroughTheGrpcPath(RoutingContext ctx) {
            ctx.request().pause();
            DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(ctx.request());
            ctx.put(ResponseEnds.INBOUND_BODY_KEY, inbound);
            ResponseStage.GrpcStreamIdleBound idleBound =
                    ResponseStage.GrpcStreamIdleBound.withTimeout(vertx, IDLE_BOUND_MILLIS);
            idleBound.expiry().onSuccess(expired -> boundExpired.complete(null));
            Route route = grpcRoute();
            GrpcDispatchStage stage = stage(route, longGuard());
            String path = ctx.request().path();
            Thread.ofVirtual().start(() -> {
                HttpClientResponse upstream = stage.dispatch(route, HttpMethod.POST, path, Map.of(),
                        idleBound.watch(inbound), -1L);
                TerminalDispatch.relayOnEventLoop(ctx, List.of(), upstream, end -> responseStage
                        .relayWithTrailers(upstream, ctx.response(), false, route.effectiveCookies(), Map.of(),
                                Map.of(), end, idleBound));
            });
        }

        /**
         * Pins that a gRPC dispatch arms neither request-body bound: the pause outlasts the fixed
         * request-body idle timeout and the fixed request-body deadline, both counted from before the
         * pause. The call runs under the long stand-in guard, so the guard's own timeout cannot end it.
         */
        @Test
        @DisplayName("a client-streaming call pausing longer than the request-body idle timeout and deadline is ended by neither")
        void clientStreamingPauseIsEndedByNeitherRequestBodyBound() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = stage(route, longGuard());
            PushedBody body = new PushedBody();
            EdgeHardeningOptions hardening = new EdgeHardeningOptions();
            long longestRequestBodyBoundMillis =
                    Math.max(hardening.requestBodyIdleTimeoutMillis(), hardening.requestBodyDeadlineMillis());

            body.push("first");
            CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                    () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), body, -1L));
            Awaits.until(() -> upstreamFrames.contains("first"), "the first frame to reach the upstream",
                    Awaits.CONNECT_CEILING_SECONDS);
            Awaits.connect(pause(longestRequestBodyBoundMillis + PAUSE_MARGIN_MILLIS),
                    "a pause longer than the request-body idle timeout and the request-body deadline");
            boolean openAfterPause = !answered.isDone() && !upstreamFailed.isDone();
            body.push("second");
            body.end();
            HttpClientResponse response = Awaits.connect(answered, "the client-streaming call to be answered");

            assertAll("a client-streaming call pausing between its messages",
                    () -> assertTrue(openAfterPause, "the pause ended neither the dispatch nor the upstream call"),
                    () -> assertEquals(200, response.statusCode(), "the call is answered once its stream ends"),
                    () -> assertEquals(List.of("first", "second"), upstreamFrames,
                            "every message reached the upstream"),
                    () -> assertTrue(upstreamEnded.isDone(), "the request stream ended at the upstream"),
                    () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"));
        }

        /**
         * The dispatch alone, without the relay and its stream-idle bound: once the headers have arrived
         * the guarded call has returned, so its timeout no longer ends the call.
         */
        @Test
        @DisplayName("once the upstream sent its headers, the guard no longer bounds the call: it has returned")
        void callAfterHeadersOutlivesTheGuardAndEndsOnlyWithItsStream() throws Exception {
            Route route = grpcRoute();
            Guard shortGuard = Guard.create().withTimeout().duration(SHORT_GUARD_MILLIS, ChronoUnit.MILLIS).done().build();
            GrpcDispatchStage stage = stage(route, shortGuard);
            PushedBody body = new PushedBody();

            body.push("first");
            HttpClientResponse response = stage.dispatch(route, HttpMethod.POST, BIDI, Map.of(), body, -1L);
            Awaits.until(() -> upstreamFrames.contains("first"), "the first frame to reach the upstream",
                    Awaits.CONNECT_CEILING_SECONDS);
            Awaits.connect(pause(SHORT_GUARD_MILLIS + PAUSE_MARGIN_MILLIS), "a pause longer than the guard's timeout");
            boolean openAfterPause = !upstreamFailed.isDone() && !upstreamEnded.isDone();
            body.push("second");
            Awaits.until(() -> upstreamFrames.contains("second"), "a frame sent after the pause to reach the upstream",
                    Awaits.CONNECT_CEILING_SECONDS);
            body.end();
            Awaits.connect(upstreamEnded, "the call to end with its request stream");

            assertAll("a call whose upstream sent its headers before the request stream ended",
                    () -> assertEquals(200, response.statusCode(), "the dispatch returned at the response headers"),
                    () -> assertTrue(openAfterPause,
                            "the call outlived the guard's timeout: neither ended nor reset by the gateway"),
                    () -> assertEquals(List.of("first", "second"), upstreamFrames,
                            "the call still carried frames after the pause"),
                    () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"));
        }

        @Test
        @DisplayName("a call carrying no frame in either direction for the stream-idle bound ends with DEADLINE_EXCEEDED")
        void callIdleInBothDirectionsIsEndedWithDeadlineExceeded() throws Exception {
            long openedNanos = System.nanoTime();
            ClientCall call = open(BIDI);

            Awaits.connect(call.request().write("first"), "the first frame to be sent");
            Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                    "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
            MultiMap trailers = Awaits.connect(call.trailers(), "the idle call to be ended by its stream-idle bound");
            long endedAfterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - openedNanos);
            Awaits.connect(upstreamFailed, "the upstream call to be reset");

            assertAll("a call that went quiet in both directions",
                    () -> assertEquals(Integer.toString(GrpcStatusMapper.DEADLINE_EXCEEDED), trailers.get(GRPC_STATUS),
                            "the call ends with DEADLINE_EXCEEDED in its trailers"),
                    () -> assertEquals("stream-idle-timeout", trailers.get(GRPC_MESSAGE),
                            "the trailers name the stream-idle bound and nothing either side sent"),
                    () -> assertTrue(endedAfterMillis >= IDLE_BOUND_MILLIS,
                            "the call is not ended before the bound: ended after " + endedAfterMillis + " ms"),
                    () -> assertTrue(boundExpired.isDone(), "the call's stream-idle bound expired"),
                    () -> assertEquals(List.of("head"), call.frames(),
                            "the client received the upstream's only frame and then the gateway's trailers"),
                    () -> assertFalse(upstreamEnded.isDone(), "the request stream never ended at the upstream"));
        }

        @Test
        @DisplayName("an upstream that keeps sending keeps the call open while the client is silent past the stream-idle bound")
        void upstreamTrafficKeepsTheCallOpenWhileTheClientIsSilent() throws Exception {
            ClientCall call = open(TICKING);

            Awaits.connect(call.request().write("first"), "the first frame to be sent");
            Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                    "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
            Awaits.connect(pause(SILENCE_MILLIS), "the client to stay silent for three stream-idle bounds");
            boolean openAfterSilence = !call.trailers().isDone() && !upstreamFailed.isDone();
            Awaits.connect(call.request().end("last"), "the request stream to end");
            MultiMap trailers = Awaits.connect(call.trailers(), "the call to end with its request stream");

            assertAll("a call whose client stayed silent while its upstream kept sending",
                    () -> assertTrue(openAfterSilence, "the silence ended neither the client call nor the upstream call"),
                    () -> assertEquals(GRPC_OK, trailers.get(GRPC_STATUS), "the call ends with the upstream's status"),
                    () -> assertTrue(String.join("", call.frames()).contains("tick"),
                            "the upstream's frames reached the client during the silence"),
                    () -> assertEquals("firstlast", String.join("", upstreamFrames),
                            "both of the client's frames reached the upstream"),
                    () -> assertFalse(boundExpired.isDone(), "the stream-idle bound never expired"),
                    () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"));
        }

        @Test
        @DisplayName("a client that keeps sending keeps the call open while the upstream is silent past the stream-idle bound")
        void clientTrafficKeepsTheCallOpenWhileTheUpstreamIsSilent() throws Exception {
            ClientCall call = open(BIDI);
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
                    () -> assertEquals(List.of("head"), call.frames(), "the upstream sent nothing after its first frame"),
                    () -> assertFalse(boundExpired.isDone(), "the stream-idle bound never expired"),
                    () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"));
        }

        /**
         * Pins that the stage enforces the route's request-body cap on a streamed body: a frame that
         * carries the running byte count past {@value #BODY_CAP_BYTES} bytes refuses the dispatch as
         * {@code CONTENT_TOO_LARGE} and is never forwarded, while the frame before it was. The call is
         * client-streaming, so the upstream has not answered when the cap is crossed and the refusal is
         * the dispatch's own failure.
         */
        @Test
        @DisplayName("a request body streamed past the route's cap is refused as CONTENT_TOO_LARGE and its breaching frame never reaches the upstream")
        void bodyPastTheCapIsRefusedAndItsBreachingFrameNeverForwarded() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = stage(route, longGuard(), BODY_CAP_BYTES);
            PushedBody body = new PushedBody();

            body.push(UNDER_CAP_FRAME);
            CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                    () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), body, -1L));
            Awaits.until(() -> upstreamFrames.contains(UNDER_CAP_FRAME), "the frame under the cap to reach the upstream",
                    Awaits.CONNECT_CEILING_SECONDS);
            body.push(BREACHING_FRAME);
            body.end();
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> Awaits.connect(answered, "the dispatch of a body past the cap to end"),
                    "a body past the cap fails the dispatch instead of being answered");
            Awaits.connect(upstreamFailed, "the upstream call to be reset");

            assertAll("a request body streamed past the route's cap",
                    () -> assertEquals(EventType.CONTENT_TOO_LARGE,
                            assertInstanceOf(GatewayException.class, refused.getCause(),
                                    "the dispatch fails with the gateway's own refusal").getEventType(),
                            "the refusal is the request-body cap's"),
                    () -> assertEquals(List.of(UNDER_CAP_FRAME), upstreamFrames,
                            "the frame under the cap reached the upstream and the breaching frame did not"),
                    () -> assertFalse(upstreamEnded.isDone(), "the request stream never ended at the upstream"));
        }

        /**
         * Opens a call to the front server and records what comes back.
         *
         * @param path the gRPC method path the stub upstream answers by
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

        private Route grpcRoute() {
            return route("g", Protocol.GRPC,
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""));
        }

        private GrpcDispatchStage stage(Route route, Guard guard) {
            return stage(route, guard, ROOMY_CAP_BYTES);
        }

        private GrpcDispatchStage stage(Route route, Guard guard, long maxBodyBytes) {
            UpstreamTarget target = UpstreamTarget.of(route);
            UpstreamClients clients = new UpstreamClients(Map.of(target, forcedH2),
                    Map.of(new ResilienceShape(target, route.retryEnabled()), guard));
            return new GrpcDispatchStage(clients, maxBodyBytes, failureMapper);
        }

        /** The stand-in guard of {@value #LONG_GUARD_SECONDS} s every case but the post-header one runs under. */
        private static Guard longGuard() {
            return Guard.create().withTimeout().duration(LONG_GUARD_SECONDS, ChronoUnit.SECONDS).done().build();
        }

        /** A future completed once {@code millis} have passed. */
        private CompletableFuture<Void> pause(long millis) {
            CompletableFuture<Void> passed = new CompletableFuture<>();
            vertx.setTimer(millis, fired -> passed.complete(null));
            return passed;
        }
    }

    /**
     * One call to the front server of {@link StreamBounds}: its request, every response frame it received
     * and the trailers its response ended with.
     *
     * @param request  the call's request, its stream still open
     * @param frames   the response frames received, in order
     * @param trailers completed with the response trailers once the response has ended
     */
    private record ClientCall(HttpClientRequest request, List<String> frames, CompletableFuture<MultiMap> trailers) {
    }

    /**
     * A request body the test pushes frames into: frames are delivered to the consumer while it has not
     * paused the stream, and held until it resumes otherwise; the end is delivered after the last frame.
     * It starts paused, as the edge hands the dispatch a paused inbound request, so nothing flows before
     * the forwarding pipe asks for it.
     * Thread-safe: frames are pushed from the test thread while the consumer pauses and resumes from an
     * event loop; every member runs under the instance monitor.
     */
    private static final class PushedBody implements ReadStream<Buffer> {

        private final Deque<Buffer> pending = new ArrayDeque<>();
        private @Nullable Handler<Buffer> handler;
        private @Nullable Handler<Void> endHandler;
        private boolean paused = true;
        private boolean ended;
        private boolean endDelivered;

        synchronized void push(String frame) {
            pending.add(Buffer.buffer(frame));
            drain();
        }

        synchronized void end() {
            ended = true;
            drain();
        }

        private void drain() {
            while (handler != null && !paused && !pending.isEmpty()) {
                handler.handle(pending.poll());
            }
            if (ended && pending.isEmpty() && endHandler != null && !endDelivered) {
                endDelivered = true;
                endHandler.handle(null);
            }
        }

        @Override
        public synchronized ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            this.handler = handler;
            drain();
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> pause() {
            paused = true;
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> resume() {
            paused = false;
            drain();
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> fetch(long amount) {
            paused = false;
            drain();
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            drain();
            return this;
        }
    }

    private static Route route(String id, Protocol protocol, ResolvedUpstream upstream) {
        return ConfigFixtures.route(id, "/" + id)
                .protocol(protocol)
                .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                .upstream(upstream)
                .build();
    }

    private static ResolvedUpstream upstream(String host) {
        return new ResolvedUpstream("https", host, 443, "");
    }

    /** An inert {@link io.vertx.core.streams.ReadStream}: the request body of a dispatch that never reads it. */
    private static final class TestReadStream implements ReadStream<Buffer> {

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
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
     * A {@link Guard} test double that fails every guarded call the way a refused upstream connection
     * does, without running the action, so a dispatch through it never dials anything.
     */
    private static final class RefusingGuard implements Guard {

        private static final String REFUSED = "connection refused by the upstream";

        @Override
        public <T> T call(Callable<T> action, Class<T> asType) throws ConnectException {
            throw new ConnectException(REFUSED);
        }

        @Override
        public <T> T call(Callable<T> action, TypeLiteral<T> asType) throws ConnectException {
            throw new ConnectException(REFUSED);
        }

        @Override
        public <T> T get(Supplier<T> action, Class<T> asType) {
            throw new UnsupportedOperationException("the dispatch runs its attempt through call");
        }

        @Override
        public <T> T get(Supplier<T> action, TypeLiteral<T> asType) {
            throw new UnsupportedOperationException("the dispatch runs its attempt through call");
        }
    }

}
