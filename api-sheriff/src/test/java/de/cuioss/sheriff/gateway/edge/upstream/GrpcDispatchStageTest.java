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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
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
import io.vertx.core.http.RequestOptions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract of the {@code protocol: grpc} dispatch path (deliverable 6): the constructor contract, the
 * h2-negotiation-failure → gRPC {@code UNAVAILABLE} mapping, the response-trailer relay carrying
 * {@code grpc-status} / {@code grpc-message} to the client, and the stream bounds of a call over a live
 * forced-h2 upstream, the request-body cap, the message bound and the pre-response deadline among them.
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

        @ParameterizedTest(name = "a bound of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_FIRST_MESSAGE_TIMEOUT, -1L})
        @DisplayName("refuses a message bound that is not positive: a gRPC dispatch always bounds the wait for each message")
        void refusesANonPositiveMessageBound(long messageTimeoutMillis) {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrpcDispatchStage(noClients, 1024L, failureMapper, messageTimeoutMillis,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS, GrpcDispatchStage.GRPC_HAND_OVER_GRACE_MILLIS,
                            GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS));

            assertTrue(refused.getMessage().contains("messageTimeoutMillis"), "the refusal names the bound");
        }

        /**
         * A pre-response deadline no longer than the message bound would end a client still within the
         * bound for its next message: the deadline must be the longer of the two.
         */
        @ParameterizedTest(name = "a deadline {0} ms past the bound")
        @ValueSource(longs = {0L, -1L})
        @DisplayName("refuses a pre-response deadline that is not longer than the message bound")
        void refusesADeadlineNotLongerThanTheMessageBound(long pastTheBoundMillis) {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
            long messageTimeoutMillis = GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrpcDispatchStage(noClients, 1024L, failureMapper, messageTimeoutMillis,
                            messageTimeoutMillis + pastTheBoundMillis, GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS,
                            GrpcDispatchStage.GRPC_HAND_OVER_GRACE_MILLIS, GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS));

            assertTrue(refused.getMessage().contains("preResponseDeadlineMillis"), "the refusal names the deadline");
        }

        /**
         * A pre-response cap shorter than the deadline would shorten the deadline of every call, however
         * promptly its attempt handed the body over.
         */
        @Test
        @DisplayName("refuses a pre-response cap shorter than the pre-response deadline")
        void refusesACapShorterThanTheDeadline() {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
            long deadlineMillis = GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS;

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrpcDispatchStage(noClients, 1024L, failureMapper,
                            GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS, deadlineMillis, deadlineMillis - 1,
                            GrpcDispatchStage.GRPC_HAND_OVER_GRACE_MILLIS, GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS));

            assertTrue(refused.getMessage().contains("preResponseCapMillis"), "the refusal names the cap");
        }

        /**
         * A hand-over grace that is not positive would let a hand-over just short of the cap be dispatched with
         * a deadline too short for an end the client already sent to be delivered; one no shorter than the
         * deadline would refuse even a prompt hand-over.
         */
        @ParameterizedTest(name = "a grace of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_HAND_OVER_GRACE, -1L, GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS})
        @DisplayName("refuses a hand-over grace that is not positive or not shorter than the pre-response deadline")
        void refusesAGraceNotPositiveOrNotShorterThanTheDeadline(long handOverGraceMillis) {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrpcDispatchStage(noClients, 1024L, failureMapper,
                            GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS, handOverGraceMillis,
                            GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS));

            assertTrue(refused.getMessage().contains("handOverGraceMillis"), "the refusal names the grace");
        }

        /**
         * An upstream stall threshold that is not positive would charge every flow-control pause at the deadline
         * to the upstream — a client out-sending a healthy upstream could open its breaker; one no shorter than
         * the deadline could never be reached by a stall that starts within the call.
         */
        @ParameterizedTest(name = "a stall threshold of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_UPSTREAM_STALL, -1L, GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS})
        @DisplayName("refuses an upstream stall threshold that is not positive or not shorter than the pre-response deadline")
        void refusesAStallThresholdNotPositiveOrNotShorterThanTheDeadline(long upstreamStallMillis) {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrpcDispatchStage(noClients, 1024L, failureMapper,
                            GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS,
                            GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS, GrpcDispatchStage.GRPC_HAND_OVER_GRACE_MILLIS,
                            upstreamStallMillis));

            assertTrue(refused.getMessage().contains("upstreamStallMillis"), "the refusal names the stall threshold");
        }

        /**
         * Both request-side bounds exist so that a client holding its request stream open is ended as its own
         * abort before the guard's timeout could end it as the upstream's. Both are counted from the hand-over,
         * the guard's timeout from the start of the attempt, so the deadline alone cannot promise that; the cap
         * does: the deadline never passes later than the cap after the start of the attempt, and the cap is
         * shorter than the guard's timeout, which starts at the same moment.
         */
        @Test
        @DisplayName("the production message bound is below the pre-response deadline, the deadline within the cap, the cap below the guard's timeout, and the grace one second")
        void messageBoundDeadlineAndCapAreBelowTheGuardTimeout() {
            long guardMillis = TimeUnit.SECONDS.toMillis(UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS);

            assertAll("the fixed request-side bounds of a gRPC call",
                    () -> assertTrue(GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS
                            < GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS,
                            () -> "the message bound of " + GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS
                                    + " ms must be shorter than the pre-response deadline"),
                    () -> assertTrue(GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS
                            <= GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS,
                            () -> "a prompt hand-over must keep the whole pre-response deadline of "
                                    + GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS + " ms"),
                    () -> assertEquals(GrpcDispatchStage.GRPC_PRE_RESPONSE_CAP_MILLIS, guardMillis - 1_000L,
                            () -> "the pre-response cap must pass one second before the guard's "
                                    + UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS + " s timeout"),
                    () -> assertEquals(1_000L, GrpcDispatchStage.GRPC_HAND_OVER_GRACE_MILLIS,
                            "a dispatched attempt keeps at least one second between its hand-over and its deadline"));
        }

        /**
         * The upstream stall threshold decides whose failure a passed pre-response deadline is: ten seconds, the
         * span the message bound already treats as no progress — far above any flow-control pause, so a client
         * out-sending a healthy upstream keeps its deadline, and well below the deadline itself.
         */
        @Test
        @DisplayName("the production upstream stall threshold is ten seconds, the message bound, below the pre-response deadline")
        void upstreamStallThresholdIsTheMessageBoundBelowTheDeadline() {
            assertAll("the fixed upstream stall threshold of a gRPC call",
                    () -> assertEquals(10_000L, GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS,
                            "only a stall of ten seconds without a break is charged to the upstream"),
                    () -> assertEquals(GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS, GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS,
                            "the threshold is the span the message bound treats as no progress"),
                    () -> assertTrue(GrpcDispatchStage.GRPC_UPSTREAM_STALL_MILLIS
                            < GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS,
                            "a stall starting within the call can still reach the threshold"));
        }

        /**
         * The pre-response deadline is derived, never configured: it is the HTTP request-body deadline, so a
         * gRPC call and an HTTP upload are given the same time to finish sending.
         */
        @Test
        @DisplayName("the pre-response deadline equals the HTTP request-body deadline")
        void preResponseDeadlineEqualsTheRequestBodyDeadline() {
            assertEquals(
                    GrpcDispatchStage.GRPC_PRE_RESPONSE_DEADLINE_MILLIS, new EdgeHardeningOptions().requestBodyDeadlineMillis(),
                    "the pre-response deadline is derived from EdgeHardeningOptions#requestBodyDeadlineMillis()");
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
                            .relayWithTrailers(upResp, clientReq.response(), false, CookiesConfig.NONE, false, Map.of(),
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
     * The gRPC dispatch arms no request-body idle timeout. Before the upstream sends its response headers the
     * guarded call is still waiting, and two request-side bounds run beside the guard's timeout; once the
     * headers have arrived the guarded call has returned, the request-side bounds have stopped with the send,
     * and the guard no longer bounds the call: its stream-idle bound does. That bound ends a call that carried
     * no frame in either direction for its timeout, and only such a call: traffic in either direction alone
     * keeps the call open. What the call may send is bounded by the route's request-body cap, which the gRPC
     * dispatch enforces on the streamed body like any other dispatch.
     * <p>
     * <strong>The message bound and the pre-response deadline.</strong> Until the upstream answers, every
     * complete request message must arrive within the message bound of the one before it — the first within
     * the bound of the attempt handing its body over — and the request stream must end within the pre-response
     * deadline, also counted from that hand-over and passing no later than the pre-response cap after the start
     * of the attempt. A client that sends nothing, only part of a message, or stops between two messages
     * without ending its stream is ended at the message bound as a {@link DispatchStage.RequestMessageTimeout};
     * a client that keeps sending complete messages, each within the bound, without ever ending its stream is
     * ended at the deadline as a {@link DispatchStage.RequestBodyDeadlineExceeded}. Both are the client's own
     * aborts, which the breaker skips, instead of the guard's timeout, an upstream failure the breaker counts —
     * unless the upstream's backpressure had stalled the stream without a break for at least the upstream stall
     * threshold when the deadline was acted on, which ends the call as the upstream's timeout once the upstream
     * reads again. The brief flow-control pauses of a client sending faster than a healthy upstream reads stay far
     * below that threshold, so such a client's deadline stays its own.
     * That timeout is reached by an upstream holding a complete, half-closed request it does not answer, and by
     * a connection wait that itself lasts it; an attempt whose connection wait leaves less than the hand-over
     * grace before the cap, or reaches the cap, is ended as the same upstream timeout without its body being
     * read. The cases on the two bounds build the stage with the bound shortened to
     * {@value #MESSAGE_BOUND_MILLIS} ms, the deadline to {@value #DEADLINE_MILLIS} ms, the cap to
     * {@value #DEADLINE_CAP_MILLIS} ms, the grace to {@value #HAND_OVER_GRACE_MILLIS} ms and the stall threshold to
     * {@value #UPSTREAM_STALL_THRESHOLD_MILLIS} ms, all under the {@value #BREAKER_GUARD_MILLIS} ms timeout of a
     * guard
     * carrying the production breaker rules, beside a matched control whose upstream reads every call's one
     * complete message to the half-close and never answers. The connection-wait cases stand in for a slow
     * upstream connection with a client that hands the dispatch its upstream request only a set time after the
     * dispatch asked for it.
     * <p>
     * <strong>Every request payload is a gRPC message.</strong> The dispatch reads the length prefix of every
     * request message to tell when it is complete, so every payload a case sends is framed by
     * {@link GrpcDispatchStageTest#grpcFrame(String)}; a payload sent unframed would be read as a prefix
     * declaring a huge length, and the bound would end the call. Response frames are not framed: the
     * dispatch does not read them.
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
     * {@value #TICK_MILLIS} ms until the request stream has ended; a call to {@value #LATE_ANSWER} is
     * answered {@value #LATE_ANSWER_MILLIS} ms after its request stream has ended; a call to
     * {@value #NEVER_ANSWERS} reads its request stream to its end and is never answered; a call to
     * {@value #STOPS_READING} reads nothing of its request stream for {@value #UPSTREAM_STALL_MILLIS} ms after its
     * head arrived, so a client sending more than the HTTP/2 window meets backpressure, and is never answered; a
     * call to {@value #SLOW_READER} is read all the time, but only two DATA frames every {@value #SLOW_READ_MILLIS}
     * ms, so a client sending faster meets brief backpressure again and again, and is never answered.
     * Every answered call
     * ends with a {@code grpc-status: 0} trailer. The stub reassembles the request stream into its gRPC messages and
     * records the payload of every complete one, and whether the request stream ended or failed — a failed
     * stream is how it observes the gateway resetting the call.
     */
    @Nested
    @DisplayName("stream bounds over a live forced-h2 upstream")
    class StreamBounds {

        private static final String CLIENT_STREAMING = "/svc.Service/ClientStreaming";
        private static final String BIDI = "/svc.Service/Bidi";
        private static final String TICKING = "/svc.Service/Ticking";
        private static final String LATE_ANSWER = "/svc.Service/LateAnswer";
        private static final String NEVER_ANSWERS = "/svc.Service/NeverAnswers";
        private static final String STOPS_READING = "/svc.Service/StopsReading";
        private static final String SLOW_READER = "/svc.Service/SlowReader";
        /** The test-only message bound the message-bound cases shorten the fixed bound to. */
        private static final long MESSAGE_BOUND_MILLIS = 400L;
        /**
         * The test-only pre-response deadline the message-bound cases shorten the fixed deadline to: longer
         * than the bound and well below the breaker guard's timeout, as the production values are.
         */
        private static final long DEADLINE_MILLIS = 1_200L;
        /**
         * The timeout of the breaker guard the message-bound cases run under: well above the shortened
         * deadline, so a call the client holds open ends at a request-side bound if the bounds are armed,
         * and at this timeout only if they are not.
         */
        private static final long BREAKER_GUARD_MILLIS = 2_000L;
        /**
         * The test-only pre-response cap: as the production cap does, it leaves a prompt hand-over the whole
         * shortened deadline and passes well before the breaker guard's timeout.
         */
        private static final long DEADLINE_CAP_MILLIS = 1_600L;
        /**
         * The test-only hand-over grace: an attempt handing its body over with less than this left before the
         * cap — later than {@value #DEADLINE_CAP_MILLIS} ms less this after its start — is refused unsent.
         */
        private static final long HAND_OVER_GRACE_MILLIS = 100L;
        /**
         * The test-only upstream stall threshold: shorter than the shortened deadline, as the production threshold
         * is, and far above the flow-control pauses of a client outpacing the {@value #SLOW_READER} upstream — a
         * few tens of milliseconds each — while the {@value #STOPS_READING} upstream holds its stream for more than
         * twice as long.
         */
        private static final long UPSTREAM_STALL_THRESHOLD_MILLIS = 500L;
        /**
         * How often the {@value #SLOW_READER} upstream reads: it takes two DATA frames — one large message — every
         * this many milliseconds, a rate the outpacing client's {@value #OUTPACING_GAP_MILLIS} ms gap exceeds.
         */
        private static final long SLOW_READ_MILLIS = 40L;
        /**
         * The gap between the outpacing client's large messages: shorter than {@value #SLOW_READ_MILLIS}, so it
         * sends faster than the {@value #SLOW_READER} upstream reads.
         */
        private static final long OUTPACING_GAP_MILLIS = 20L;
        /**
         * How many large messages an outpacing call sends at most: their framed bytes stay under the roomy cap, and
         * they last longer than the deadline at {@value #OUTPACING_GAP_MILLIS} ms apart.
         */
        private static final int MOST_OUTPACING_MESSAGES = 60;
        /**
         * A connection wait longer than the shortened deadline and shorter than the cap less the grace: a
         * deadline counted from the start of the attempt would already have passed at the hand-over, the one
         * counted from the hand-over is held to the cap, and the hand-over still leaves more than the grace.
         */
        private static final long SLOW_CONNECTION_MILLIS = 1_350L;
        /**
         * A connection wait inside the grace window: past the cap less the grace, so less than the grace is left
         * before the cap at the hand-over, and still short of the cap itself.
         */
        private static final long INSIDE_THE_GRACE_CONNECTION_MILLIS = 1_520L;
        /** A connection wait past the cap and short of the breaker guard's timeout. */
        private static final long PAST_THE_CAP_CONNECTION_MILLIS = 1_700L;
        /** The gap between a steady sender's complete messages: a quarter of the shortened bound. */
        private static final long STEADY_GAP_MILLIS = MESSAGE_BOUND_MILLIS / 4;
        /** The request-volume threshold of the breaker guard: this many counted failures open it. */
        private static final int BREAKER_THRESHOLD = 2;
        /** How long after the request stream ended the stub upstream answers a call to {@value #LATE_ANSWER}. */
        private static final long LATE_ANSWER_MILLIS = 3 * MESSAGE_BOUND_MILLIS;
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
        /**
         * How long the stub upstream reads nothing of a call to {@value #STOPS_READING} after its request head
         * arrived: past the shortened deadline, which the head arrives after, and short of the breaker guard's
         * timeout, so it takes data again in between.
         */
        private static final long UPSTREAM_STALL_MILLIS = DEADLINE_MILLIS + 200L;
        /**
         * The payload of each large message the backpressure case sends: sixteen KiB, so a few of them exhaust
         * the 64 KiB HTTP/2 flow-control window the stub upstream grants and, not reading, never returns.
         */
        private static final int LARGE_MESSAGE_CHARS = 16 * 1024;
        /** How many large messages the backpressure case sends: twice the stub upstream's window and more. */
        private static final int LARGE_MESSAGES = 8;
        /** The request-body cap of every case but the cap case: far above anything a case sends. */
        private static final long ROOMY_CAP_BYTES = 1024L * 1024L;
        /**
         * The request-body cap of the cap case, counted in framed bytes: the first message it sends is under
         * it, and the two messages cross it only together.
         */
        private static final long BODY_CAP_BYTES = 16L;
        /** The first message of the cap case: five payload bytes, ten framed, under the cap. */
        private static final String UNDER_CAP_FRAME = "first";
        /** The second message of the cap case: six payload bytes, eleven framed, carrying the running count to 21. */
        private static final String BREACHING_FRAME = "second";

        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        private final GrpcStatusMapper grpcStatusMapper = new GrpcStatusMapper();
        private final ResponseStage responseStage =
                new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway().build()));
        /** Every call the stub upstream received the request head of. */
        private final AtomicInteger upstreamCalls = new AtomicInteger();
        /** Every state the breaker guard of the message-bound cases moved to, in order. */
        private final List<CircuitBreakerState> breakerTransitions = new CopyOnWriteArrayList<>();
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
            upstreamCalls.incrementAndGet();
            HttpServerResponse response = request.response().setChunked(true);
            boolean ticking = TICKING.equals(request.path());
            long ticker = periodicTask(request, response);
            // The request arrives as length-prefixed gRPC messages, split into DATA frames however the
            // transport chose; the stub reassembles them and records each message's payload.
            MessageReassembler messages = new MessageReassembler(upstreamFrames::add);
            request.handler(messages::read);
            request.exceptionHandler(failure -> {
                vertx.cancelTimer(ticker);
                upstreamFailed.complete(failure);
            });
            request.endHandler(end -> {
                vertx.cancelTimer(ticker);
                upstreamEnded.complete(null);
                if (NEVER_ANSWERS.equals(request.path())) {
                    // The whole request was read, half-close included, and the upstream holds it unanswered.
                    return;
                }
                if (LATE_ANSWER.equals(request.path())) {
                    vertx.setTimer(LATE_ANSWER_MILLIS, fired -> answerOk(response));
                } else {
                    answerOk(response);
                }
            });
            if (ticking || BIDI.equals(request.path())) {
                response.write("head");
            }
            if (STOPS_READING.equals(request.path())) {
                // Not reading returns no HTTP/2 flow-control credit, so once the client has sent the window the
                // upstream granted, the gateway's upstream request stops being writable: backpressure.
                request.pause();
                vertx.setTimer(UPSTREAM_STALL_MILLIS, fired -> request.resume());
            }
        }

        /**
         * Starts the stub's periodic task for a call: a call to {@value #TICKING} sends a frame every
         * {@value #TICK_MILLIS} ms; a call to {@value #SLOW_READER} is read continuously but slowly — paused, and
         * two DATA frames fetched every {@value #SLOW_READ_MILLIS} ms, so HTTP/2 flow-control credit flows back
         * all the time, only slower than an outpacing client spends it.
         *
         * @return the periodic timer's id, or {@code -1} for a call that has none
         */
        private long periodicTask(HttpServerRequest request, HttpServerResponse response) {
            if (TICKING.equals(request.path())) {
                return vertx.setPeriodic(TICK_MILLIS, fired -> response.write("tick"));
            }
            if (SLOW_READER.equals(request.path())) {
                request.pause();
                return vertx.setPeriodic(SLOW_READ_MILLIS, fired -> request.fetch(2));
            }
            return -1L;
        }

        private static void answerOk(HttpServerResponse response) {
            if (!response.closed()) {
                response.putTrailer(GRPC_STATUS, GRPC_OK);
                response.end();
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
                        .relayWithTrailers(upstream, ctx.response(), false, route.effectiveCookies(),
                                route.keepIdentityHeaders(), Map.of(), Map.of(), end, idleBound));
            });
        }

        /**
         * A client-streaming call whose client sends a complete message every {@value #STEADY_GAP_MILLIS} ms —
         * each gap a quarter of the shortened message bound — for longer than that bound in all, then ends its
         * stream before the deadline. The message bound restarts with every message, so it never ends the call,
         * and the upstream answers once the stream has ended. The call runs under the long stand-in guard, so the
         * guard's own timeout cannot end it.
         */
        @Test
        @DisplayName("a steady sender whose stream outlasts the message bound, each gap shorter than it, is answered after it half-closes")
        void steadySenderIsAnsweredAfterItHalfCloses() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, longGuard());
            PushedBody body = new PushedBody();
            List<String> sent = new ArrayList<>();
            int messages = (int) (2 * MESSAGE_BOUND_MILLIS / STEADY_GAP_MILLIS);

            CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                    () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), body, -1L));
            for (int message = 0; message < messages; message++) {
                Awaits.connect(pause(STEADY_GAP_MILLIS), "the gap before the next message");
                String payload = "message-" + message;
                body.push(payload);
                sent.add(payload);
            }
            boolean openBeforeTheHalfClose = !answered.isDone() && !upstreamFailed.isDone();
            body.end();
            HttpClientResponse response = Awaits.connect(answered, "the client-streaming call to be answered");

            assertAll("a client-streaming call sending steadily for %d ms, then half-closing"
                    .formatted(messages * STEADY_GAP_MILLIS),
                    () -> assertTrue(openBeforeTheHalfClose,
                            "no gap reached the message bound: the call was open until the client ended its stream"),
                    () -> assertEquals(200, response.statusCode(), "the call is answered once its stream ends"),
                    () -> assertEquals(sent, upstreamFrames, "every message reached the upstream"),
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

            Awaits.connect(call.request().write(grpcFrame("first")), "the first frame to be sent");
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

            Awaits.connect(call.request().write(grpcFrame("first")), "the first frame to be sent");
            Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                    "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
            Awaits.connect(pause(SILENCE_MILLIS), "the client to stay silent for three stream-idle bounds");
            boolean openAfterSilence = !call.trailers().isDone() && !upstreamFailed.isDone();
            Awaits.connect(call.request().end(grpcFrame("last")), "the request stream to end");
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

            Awaits.connect(call.request().write(grpcFrame("first")), "the first frame to be sent");
            Awaits.until(() -> call.frames().contains("head") && upstreamFrames.contains("first"),
                    "the first frame each way to arrive", Awaits.CONNECT_CEILING_SECONDS);
            for (int ping = 0; ping < pings; ping++) {
                Awaits.connect(pause(TICK_MILLIS), "the gap before the next client frame");
                Awaits.connect(call.request().write(grpcFrame("ping")), "a client frame to be sent");
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
         * Pins that the stage enforces the route's request-body cap on a streamed body: a message whose
         * framed bytes carry the running byte count past {@value #BODY_CAP_BYTES} bytes refuses the dispatch
         * as {@code CONTENT_TOO_LARGE} and is never forwarded, while the message before it was. The call is
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
         * A client that opens a call and sends no request message — the request body never emits, so the
         * forced-h2 upstream request head is never written. Every idle call is ended at the shortened
         * message bound as the client's own abort, long before the breaker guard's timeout; the
         * breaker skips the abort, so one more idle call than its threshold leaves it closed, and a call
         * behind them still reaches the upstream.
         */
        @Test
        @DisplayName("an idle client sending no request message ends as its own abort within the message bound and never moves the breaker")
        void idleClientEndsAsItsOwnAbortAndLeavesTheBreakerClosed() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<GatewayException> aborts = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();

            for (int call = 0; call <= BREAKER_THRESHOLD; call++) {
                PushedBody idle = new PushedBody();
                long started = System.nanoTime();
                aborts.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), idle, -1L),
                        "an idle call is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PushedBody following = new PushedBody();
            following.push("first");
            following.end();
            HttpClientResponse response = stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), following,
                    -1L);

            assertAll("idle calls beyond the breaker's threshold",
                    () -> assertTrue(aborts.stream().allMatch(DispatchStage.RequestMessageTimeout.class::isInstance),
                            () -> "every idle call is the client's message-bound abort: " + aborts),
                    () -> assertTrue(aborts.stream().allMatch(abort -> abort.getEventType() == EventType.INBOUND_BODY_ABORTED),
                            () -> "every idle call is an INBOUND_BODY_ABORTED: " + aborts),
                    () -> assertTrue(aborts.stream().allMatch(abort -> grpcStatusMapper
                                    .toGrpcStatus(abort.getEventType()) == GrpcStatusMapper.INVALID_ARGUMENT),
                            "an idle call renders as the client-abort status, INVALID_ARGUMENT"),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= MESSAGE_BOUND_MILLIS && millis < DEADLINE_MILLIS),
                            () -> "each idle call ends at the bound, before the deadline and the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertEquals(200, response.statusCode(), "the call behind the idle calls is answered"),
                    () -> assertEquals(List.of("first"), upstreamFrames, "only the following call's message arrived"),
                    () -> assertEquals(1, upstreamCalls.get(), "no idle call ever reached the upstream"));
        }

        /**
         * A client that opens a call and sends only part of its first message — the length prefix and one
         * payload byte of a longer message — and then stalls. The partial message writes the forced-h2
         * upstream request head, but the upstream holds no message it could act on, so the call is the
         * client's to end: every such call is ended at the shortened message bound as the client's own
         * abort, long before the breaker guard's timeout, and one more of them than the breaker's threshold
         * leaves it closed.
         */
        @Test
        @DisplayName("a client that sends part of its first message and stalls ends as its own abort within the message bound and never moves the breaker")
        void partialFirstMessageEndsAsTheClientsAbortAndLeavesTheBreakerClosed() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<GatewayException> aborts = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            Buffer partialMessage = grpcFrame(UNDER_CAP_FRAME).slice(0, GRPC_PREFIX_LENGTH + 1);

            for (int call = 0; call <= BREAKER_THRESHOLD; call++) {
                PushedBody stalled = new PushedBody();
                stalled.pushBytes(partialMessage.copy());
                long started = System.nanoTime();
                aborts.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), stalled, -1L),
                        "a stalled call is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }

            assertAll("stalled partial first messages beyond the breaker's threshold",
                    () -> assertTrue(aborts.stream().allMatch(DispatchStage.RequestMessageTimeout.class::isInstance),
                            () -> "every stalled call is the client's message-bound abort: " + aborts),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= MESSAGE_BOUND_MILLIS && millis < DEADLINE_MILLIS),
                            () -> "each stalled call ends at the bound, before the guard's timeout: " + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertEquals(List.of(), upstreamFrames, "no complete message reached the upstream"));
        }

        /**
         * A client-streaming call whose client sends one complete message and then holds its request stream
         * open without another. Before this bound re-armed after every message, that call reached the guard's
         * timeout and was counted against the upstream. Now each such call is ended one shortened message bound
         * after its message as the client's own abort, before the deadline and long before the guard's timeout,
         * and one more of them than the breaker's threshold leaves it closed.
         */
        @Test
        @DisplayName("a client that sends a complete message and holds its stream open ends as its own abort at the message bound and never moves the breaker")
        void heldOpenAfterACompleteMessageEndsAsTheClientsAbortAndLeavesTheBreakerClosed() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<GatewayException> aborts = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            List<String> sent = new ArrayList<>();

            for (int call = 0; call <= BREAKER_THRESHOLD; call++) {
                PushedBody heldOpen = new PushedBody();
                String payload = "held-" + call;
                heldOpen.push(payload);
                sent.add(payload);
                long started = System.nanoTime();
                aborts.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), heldOpen, -1L),
                        "a call held open after its message is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PushedBody following = new PushedBody();
            following.push("following");
            following.end();
            sent.add("following");
            HttpClientResponse response = stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), following,
                    -1L);

            assertAll("calls held open after a complete message, beyond the breaker's threshold",
                    () -> assertTrue(aborts.stream().allMatch(DispatchStage.RequestMessageTimeout.class::isInstance),
                            () -> "every held call is the client's message-bound abort: " + aborts),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= MESSAGE_BOUND_MILLIS && millis < DEADLINE_MILLIS),
                            () -> "each held call ends at the bound, before the deadline and the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the held upstream calls"),
                    () -> assertEquals(200, response.statusCode(), "the call behind the held calls is answered"),
                    () -> assertEquals(sent, upstreamFrames, "every complete message reached the upstream"));
        }

        /**
         * A client-streaming call whose client keeps sending complete messages, each {@value #STEADY_GAP_MILLIS}
         * ms after the one before — well within the message bound — and never ends its request stream. The
         * message bound never fires; the pre-response deadline does, ending each call as the client's own
         * {@link DispatchStage.RequestBodyDeadlineExceeded} before the guard's timeout, rendered to the client as
         * {@code DEADLINE_EXCEEDED}. The breaker skips it: as many such calls as its threshold leave it closed,
         * and a call behind them is answered.
         */
        @Test
        @DisplayName("a steady sender that never half-closes ends at the pre-response deadline before the guard, and never moves the breaker")
        void steadySenderThatNeverHalfClosesEndsAtTheDeadlineAndLeavesTheBreakerClosed() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<Throwable> refusals = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            long mostMessages = 2 * BREAKER_GUARD_MILLIS / STEADY_GAP_MILLIS;

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody steady = new PushedBody();
                long started = System.nanoTime();
                CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                        () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), steady, -1L));
                for (long message = 0; !answered.isDone() && message < mostMessages; message++) {
                    Awaits.connect(pause(STEADY_GAP_MILLIS), "the gap before the next message");
                    steady.push("steady-" + message);
                }
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                refusals.add(assertThrows(ExecutionException.class,
                        () -> Awaits.connect(answered, "the steady sender's call to end"),
                        "a call whose stream never ends is not answered").getCause());
            }
            PushedBody following = new PushedBody();
            following.push("following");
            following.end();
            HttpClientResponse response = stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), following,
                    -1L);

            assertAll("steady senders that never half-close, as many as the breaker's threshold",
                    () -> assertTrue(refusals.stream().allMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "every such call ends at the pre-response deadline as the client's: " + refusals),
                    () -> assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED,
                            grpcStatusMapper.toGrpcStatus(EventType.REQUEST_BODY_TIMEOUT),
                            "the deadline renders to the client as DEADLINE_EXCEEDED"),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= DEADLINE_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends at the deadline, before the guard's timeout: " + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the open upstream calls"),
                    () -> assertEquals(200, response.statusCode(), "the call behind them is answered"));
        }

        /**
         * A client-streaming call whose upstream stops reading as soon as the request head arrives and reads again
         * {@value #UPSTREAM_STALL_MILLIS} ms later — past the pre-response deadline, before the guard's timeout.
         * The client sends large messages steadily, more than the upstream's flow-control window, so the gateway's
         * upstream request stops being writable and the forwarding pipe pauses the request stream: the upstream
         * holds the stream when the deadline passes, and by the time it reads again it has held it without a break
         * for well over the {@value #UPSTREAM_STALL_THRESHOLD_MILLIS} ms stall threshold. That sustained stall is the
         * upstream's, so each call ends when the upstream reads again as the upstream's timeout, counted by the
         * breaker, which opens at its threshold — never as the client's {@code REQUEST_BODY_TIMEOUT}, which the
         * breaker would skip.
         */
        @Test
        @DisplayName("an upstream whose backpressure stalls the stream past the deadline and the stall threshold, then reads again, ends the call as the upstream's timeout, counted by the breaker")
        void deadlinePassingWhileTheUpstreamHoldsTheStreamIsTheUpstreamsTimeoutAndOpensTheBreaker() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            String largePayload = "a".repeat(LARGE_MESSAGE_CHARS);
            List<Throwable> ended = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            List<Boolean> heldBack = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody large = new PushedBody();
                large.push(largePayload);
                long started = System.nanoTime();
                CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                        () -> stage.dispatch(route, HttpMethod.POST, STOPS_READING, Map.of(), large, -1L));
                for (int message = 1; message < LARGE_MESSAGES; message++) {
                    Awaits.connect(pause(STEADY_GAP_MILLIS), "the gap before the next message");
                    large.push(largePayload);
                }
                heldBack.add(large.heldBack());
                ended.add(assertThrows(ExecutionException.class,
                        () -> Awaits.connect(answered, "the call the upstream held to end"),
                        "a call the upstream held past its deadline is not answered").getCause());
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PushedBody behind = new PushedBody();
            behind.push("behind");
            behind.end();
            Throwable behindTheThreshold = Awaits.connect(CompletableFuture
                    .supplyAsync(() -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), behind, -1L))
                    .handle((answer, refusal) -> refusal), "the call behind the threshold to end");

            assertAll("calls whose upstream stalled the request stream past the threshold across the deadline",
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, true), heldBack,
                            "the upstream's backpressure paused every request stream before its deadline"),
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, EventType.UPSTREAM_TIMEOUT),
                            ended.stream().map(failure -> assertInstanceOf(GatewayException.class, failure).getEventType())
                                    .toList(),
                            () -> "each call is the upstream's timeout, never the client's deadline: " + ended),
                    () -> assertTrue(ended.stream().allMatch(
                                    failure -> failure.getCause() instanceof DispatchStage.UpstreamStalledRequest),
                            () -> "each call was ended by the stall, not by the guard's timeout or the connection: " + ended),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= UPSTREAM_STALL_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends when the upstream reads again, before the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED,
                            grpcStatusMapper.toGrpcStatus(EventType.UPSTREAM_TIMEOUT),
                            "the upstream's timeout renders as DEADLINE_EXCEEDED"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the held upstream calls"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "the breaker counts the upstream's stalls and opens at its threshold"),
                    () -> assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN,
                            assertInstanceOf(GatewayException.class,
                                    behindTheThreshold == null ? null : behindTheThreshold.getCause(),
                                    "the call behind the threshold is refused, not answered").getEventType(),
                            "the call behind the threshold is refused by the open breaker"));
        }

        /**
         * The flow-control attack the stall threshold exists for: a client that sends large messages faster than a
         * healthy upstream reads them, and never ends its request stream. The {@value #SLOW_READER} upstream reads
         * all the time, two DATA frames every {@value #SLOW_READ_MILLIS} ms; the client sends a large message every
         * {@value #OUTPACING_GAP_MILLIS} ms. HTTP/2 flow control therefore keeps pausing the request stream — it is
         * paused whenever the deadline passes — but each pause lasts only until the upstream's next reads return
         * window, far below the {@value #UPSTREAM_STALL_THRESHOLD_MILLIS} ms stall threshold. The call is the
         * client's: each ends at the pre-response deadline as its {@code REQUEST_BODY_TIMEOUT}, which the breaker
         * skips, so as many such calls as the breaker's threshold leave it closed and a call behind them is
         * answered. Were any pause at the deadline charged to the upstream, each call would be an upstream timeout
         * and these calls would open the breaker for every caller of the route.
         */
        @Test
        @DisplayName("a client outpacing a healthy upstream past the deadline ends as its own REQUEST_BODY_TIMEOUT and never opens the breaker")
        void clientOutpacingAHealthyUpstreamIsTheClientsDeadlineAndLeavesTheBreakerClosed() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            String largePayload = "o".repeat(LARGE_MESSAGE_CHARS);
            List<Throwable> refusals = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            List<Boolean> heldBack = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody outpacing = new PushedBody();
                outpacing.push(largePayload);
                long started = System.nanoTime();
                CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                        () -> stage.dispatch(route, HttpMethod.POST, SLOW_READER, Map.of(), outpacing, -1L));
                for (int message = 1; !answered.isDone() && message < MOST_OUTPACING_MESSAGES; message++) {
                    Awaits.connect(pause(OUTPACING_GAP_MILLIS), "the gap before the next message");
                    outpacing.push(largePayload);
                }
                heldBack.add(outpacing.heldBack());
                refusals.add(Awaits.connect(answered.handle((answer, refusal) -> refusal),
                        "the outpacing client's call to end"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PushedBody behind = new PushedBody();
            behind.push("behind");
            behind.end();
            Object behindTheCalls = Awaits.connect(CompletableFuture
                            .supplyAsync(() -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), behind, -1L))
                            .handle((answer, refusal) -> refusal == null ? answer.statusCode() : refusal.getCause()),
                    "the call behind the outpacing calls to end");

            assertAll("clients outpacing a healthy upstream, as many as the breaker's threshold",
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, true), heldBack,
                            "the upstream's flow control paused every request stream: the client outpaced it"),
                    () -> assertTrue(refusals.stream().map(refusal -> refusal == null ? null : refusal.getCause())
                                    .allMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "every such call ends at the pre-response deadline as the client's: " + refusals),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= DEADLINE_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends at the deadline, before the guard's timeout: " + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the open upstream calls"),
                    () -> assertEquals(200, behindTheCalls, "the call behind them is answered"));
        }

        /**
         * The upstream's stall claimed in the moment the upstream answers. The read that claims a stall may run on
         * the inbound connection's event loop while the answer completes the send on the upstream request's, before
         * the reset the claim sent reaches the stream. The client here stands in for that race: its upstream request
         * holds the body paused — the upstream not taking data — past the deadline and the stall threshold, reads it
         * again, which claims the stall and resets the request, and only then completes the send with an answer.
         * The answer is never relayed: the call ends as the upstream's timeout, from the stall the dispatch reads
         * before handing the response over.
         */
        @Test
        @DisplayName("an answer that arrives just after the upstream's stall was claimed is not relayed: the call is the upstream's timeout")
        void answerArrivingJustAfterTheStallWasClaimedIsTheUpstreamsTimeout() throws Exception {
            Route route = grpcRoute();
            AnswerAfterTheStall upstreamRequest = new AnswerAfterTheStall();
            GrpcDispatchStage stage = boundedStage(route, upstreamRequest.client(), breakerGuard());
            PushedBody body = new PushedBody();
            body.push("first");

            CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                    () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), body, -1L));
            ReadStream<?> handedOver = Awaits.connect(upstreamRequest.handedOver,
                    "the dispatch to hand its body to the upstream request");
            Awaits.connect(pause(DEADLINE_MILLIS + PAUSE_MARGIN_MILLIS),
                    "the deadline and the stall threshold to pass while the upstream holds the body");
            boolean openWhileHeld = !answered.isDone() && upstreamRequest.resets.get() == 0;
            handedOver.resume();
            int resetsByTheStall = upstreamRequest.resets.get();
            upstreamRequest.answer();
            Throwable ended = Awaits.connect(answered.handle((answer, refusal) -> refusal),
                    "the call whose stall was claimed to end");

            GatewayException timeout = assertInstanceOf(GatewayException.class,
                    ended == null ? null : ended.getCause(), "the answered call still fails the dispatch");
            assertAll("an answer arriving after the upstream's stall was claimed",
                    () -> assertTrue(openWhileHeld, "the held stream was not ended before it was read again"),
                    () -> assertEquals(1, resetsByTheStall, "the read again claimed the stall and reset the request"),
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, timeout.getEventType(),
                            "the call is the upstream's timeout"),
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, timeout.getCause(),
                            "it was ended by the stall read before the hand-over, not by the guard"),
                    () -> assertTrue(upstreamRequest.resets.get() > resetsByTheStall,
                            "the attempt holding the answer was released instead of handing it on for relay"));
        }

        /**
         * The forced-h2 client of {@link #answerArrivingJustAfterTheStallWasClaimedIsTheUpstreamsTimeout}: every
         * upstream request it hands the dispatch pauses the body it is sent, as an upstream not taking data does,
         * and sends nothing upstream; its send is answered only once the case calls {@link #answer()}. Every reset
         * of the request is counted.
         */
        private final class AnswerAfterTheStall {

            private final CompletableFuture<ReadStream<?>> handedOver = new CompletableFuture<>();
            private final Promise<HttpClientResponse> sendAnswered = Promise.promise();
            private final AtomicInteger resets = new AtomicInteger();

            HttpClient client() {
                return (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                        new Class<?>[]{HttpClient.class}, (_, method, args) -> {
                            if ("request".equals(method.getName()) && args != null && args.length == 1
                                    && args[0] instanceof RequestOptions options) {
                                return forcedH2.request(options).map(this::holding);
                            }
                            return invokeOn(forcedH2, method, args);
                        });
            }

            private HttpClientRequest holding(HttpClientRequest real) {
                return (HttpClientRequest) Proxy.newProxyInstance(HttpClientRequest.class.getClassLoader(),
                        new Class<?>[]{HttpClientRequest.class}, (_, method, args) -> {
                            if ("send".equals(method.getName()) && args != null && args.length == 1
                                    && args[0] instanceof ReadStream<?> sent) {
                                sent.pause();
                                handedOver.complete(sent);
                                return sendAnswered.future();
                            }
                            if ("reset".equals(method.getName())) {
                                resets.incrementAndGet();
                            }
                            return invokeOn(real, method, args);
                        });
            }

            /** Completes the held send with an answer: a response whose only use here is being paused. */
            void answer() {
                sendAnswered.complete((HttpClientResponse) Proxy.newProxyInstance(
                        HttpClientResponse.class.getClassLoader(), new Class<?>[]{HttpClientResponse.class},
                        (response, method, args) -> switch (method.getName()) {
                            case "pause" -> response;
                            case "hashCode" -> System.identityHashCode(response);
                            case "equals" -> response == args[0];
                            case "toString" -> "the answer that arrived after the stall";
                            default -> throw new AssertionError(
                                    "the answer is only paused before the call fails: " + method.getName());
                        }));
            }
        }

        /**
         * Matched control of the backpressure case: the same upstream, which stops reading at the request head and
         * reads again only after the deadline, but a client whose small messages never fill the upstream's
         * flow-control window. The gateway's upstream request stays writable, the request stream is never paused,
         * and the client holds it open when the deadline passes: each call is the client's
         * {@code REQUEST_BODY_TIMEOUT} at the deadline, which the breaker skips.
         */
        @Test
        @DisplayName("control — a client holding its stream open within the upstream's window is ended at the deadline as the client's, and never moves the breaker")
        void clientHoldingTheStreamWithinTheWindowIsTheClientsDeadlineAndLeavesTheBreakerClosed() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<Throwable> refusals = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            List<Boolean> heldBack = new ArrayList<>();
            long mostMessages = 2 * BREAKER_GUARD_MILLIS / STEADY_GAP_MILLIS;

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody small = new PushedBody();
                small.push("first");
                long started = System.nanoTime();
                CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                        () -> stage.dispatch(route, HttpMethod.POST, STOPS_READING, Map.of(), small, -1L));
                boolean heldBeforeTheDeadline = false;
                for (long message = 0; !answered.isDone() && message < mostMessages; message++) {
                    Awaits.connect(pause(STEADY_GAP_MILLIS), "the gap before the next message");
                    small.push("small-" + message);
                    // Read only well before the deadline: the deadline's own end pauses the stream too.
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < DEADLINE_MILLIS - STEADY_GAP_MILLIS) {
                        heldBeforeTheDeadline = small.heldBack();
                    }
                }
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                heldBack.add(heldBeforeTheDeadline);
                refusals.add(assertThrows(ExecutionException.class,
                        () -> Awaits.connect(answered, "the call the client held open to end"),
                        "a call whose stream never ends is not answered").getCause());
            }

            assertAll("calls whose client held the request stream when the deadline passed",
                    () -> assertTrue(refusals.stream().allMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "every such call ends at the pre-response deadline as the client's: " + refusals),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= DEADLINE_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends at the deadline, before the guard's timeout: " + endedAfterMillis),
                    () -> assertTrue(heldBack.stream().noneMatch(Boolean::booleanValue),
                            "the small messages never filled the window: no request stream was paused"),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the open upstream calls"));
        }

        /**
         * Matched control of the request-side bounds: the same rig and guard, but each call sends one complete
         * message and ends its request stream, and the upstream reads the request to its end and never answers.
         * The upstream holds everything the client was going to send, so neither request-side bound applies, and
         * the guard's timeout ends each call as an upstream failure, counted by the breaker, which opens at its
         * threshold and refuses the call behind them without calling the upstream.
         */
        @Test
        @DisplayName("control — an upstream holding a half-closed request is the guard's timeout, counted until the breaker opens")
        void upstreamHoldingAHalfClosedRequestIsTheGuardTimeoutAndOpensTheBreaker() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, breakerGuard());
            List<EventType> ended = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody halfClosed = new PushedBody();
                halfClosed.push("first");
                halfClosed.end();
                ended.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), halfClosed, -1L),
                        "an unanswered call is not answered").getEventType());
            }
            PushedBody behind = new PushedBody();
            behind.push("first");
            behind.end();
            EventType behindTheThreshold = assertThrows(GatewayException.class,
                    () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), behind, -1L),
                    "the call behind the threshold is refused").getEventType();

            assertAll("unanswered half-closed calls up to the breaker's threshold",
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, EventType.UPSTREAM_TIMEOUT), ended,
                            "each unanswered call is the guard's timeout"),
                    () -> assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED, grpcStatusMapper.toGrpcStatus(ended.getFirst()),
                            "an upstream timeout renders as DEADLINE_EXCEEDED, not the client-abort status"),
                    () -> assertEquals("upstream", ended.getFirst().category().slug(),
                            "and reports under the upstream grpc-message slug, never the client's"),
                    () -> assertTrue(upstreamEnded.isDone(), "the upstream read the request to its half-close"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "the breaker counts the timeouts and opens at its threshold"),
                    () -> assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN, behindTheThreshold,
                            "the call behind the threshold is refused by the open breaker"),
                    () -> assertEquals(BREAKER_THRESHOLD, upstreamCalls.get(),
                            "the open breaker does not call the upstream"));
        }

        /**
         * A client that sent its whole request and half-closed, behind an upstream connection that took
         * {@value #SLOW_CONNECTION_MILLIS} ms — longer than the shortened deadline, so a deadline counted from
         * the start of the attempt had passed before the body was handed over and ended the call at its first
         * read as the client's. Counted from the hand-over, the deadline finds the request stream already ended:
         * the upstream holds a complete request it never answers, and each call ends at the guard's timeout as
         * the upstream's, counted by the breaker until it opens.
         */
        @Test
        @DisplayName("a half-closed request behind a slow upstream connection is the guard's timeout, counted until the breaker opens")
        void halfClosedRequestBehindASlowConnectionIsTheGuardTimeoutAndOpensTheBreaker() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, slowToConnect(SLOW_CONNECTION_MILLIS), breakerGuard());
            List<GatewayException> ended = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody halfClosed = new PushedBody();
                halfClosed.push("first");
                halfClosed.end();
                long started = System.nanoTime();
                ended.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), halfClosed, -1L),
                        "an unanswered call is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            PushedBody behind = new PushedBody();
            EventType behindTheThreshold = assertThrows(GatewayException.class,
                    () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), behind, -1L),
                    "the call behind the threshold is refused").getEventType();

            assertAll("half-closed calls behind a slow upstream connection, up to the breaker's threshold",
                    () -> assertTrue(ended.stream().noneMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "the slow connection is never charged to the client as its deadline: " + ended),
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, EventType.UPSTREAM_TIMEOUT),
                            ended.stream().map(GatewayException::getEventType).toList(),
                            "each call is the guard's timeout, an upstream failure"),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(millis -> millis >= BREAKER_GUARD_MILLIS),
                            () -> "each call ran to the guard's timeout: " + endedAfterMillis),
                    () -> assertTrue(upstreamEnded.isDone(), "the upstream read the request to its half-close"),
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, "first"), upstreamFrames,
                            "each client's whole request reached the upstream"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "the breaker counts the upstream timeouts and opens at its threshold"),
                    () -> assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN, behindTheThreshold,
                            "the call behind the threshold is refused by the open breaker"));
        }

        /**
         * The cap's side of the same slow connection: a client that keeps sending complete messages, each well
         * within the message bound, and never ends its stream. Counted from the hand-over alone, the deadline
         * would pass only after the guard's timeout, which would then end the call as the upstream's; held to the
         * cap, it ends each call there, as the client's own abort, before the guard, and the breaker skips it.
         */
        @Test
        @DisplayName("a steady sender that never half-closes behind a slow upstream connection is ended at the cap as the client's, and never moves the breaker")
        void steadySenderBehindASlowConnectionEndsAtTheCapAndLeavesTheBreakerClosed() throws Exception {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, slowToConnect(SLOW_CONNECTION_MILLIS), breakerGuard());
            List<Throwable> refusals = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();
            long mostMessages = 2 * BREAKER_GUARD_MILLIS / STEADY_GAP_MILLIS;

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody steady = new PushedBody();
                long started = System.nanoTime();
                CompletableFuture<HttpClientResponse> answered = CompletableFuture.supplyAsync(
                        () -> stage.dispatch(route, HttpMethod.POST, CLIENT_STREAMING, Map.of(), steady, -1L));
                for (long message = 0; !answered.isDone() && message < mostMessages; message++) {
                    Awaits.connect(pause(STEADY_GAP_MILLIS), "the gap before the next message");
                    steady.push("steady-" + message);
                }
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                refusals.add(assertThrows(ExecutionException.class,
                        () -> Awaits.connect(answered, "the steady sender's call to end"),
                        "a call whose stream never ends is not answered").getCause());
            }

            assertAll("steady senders behind a slow upstream connection, as many as the breaker's threshold",
                    () -> assertTrue(refusals.stream().allMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "every such call ends at the pre-response deadline as the client's: " + refusals),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= DEADLINE_CAP_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends at the cap, after the hand-over and before the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker stays closed"),
                    () -> assertTrue(upstreamFailed.isDone(), "the gateway reset the open upstream calls"));
        }

        /**
         * An upstream connection that took {@value #PAST_THE_CAP_CONNECTION_MILLIS} ms — past the cap: the
         * client is given no time to send anything before the guard's timeout. The attempt is ended the moment
         * it obtains its connection, as the upstream's timeout, before its request is sent or its body read, and
         * the breaker counts it — however complete the client's request was.
         */
        @Test
        @DisplayName("a connection obtained only past the cap ends the call at once as the upstream's timeout, unsent, counted by the breaker")
        void connectionPastTheCapIsTheUpstreamsTimeoutAndOpensTheBreaker() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, slowToConnect(PAST_THE_CAP_CONNECTION_MILLIS),
                    breakerGuard());
            List<GatewayException> ended = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody halfClosed = new PushedBody();
                halfClosed.push("first");
                halfClosed.end();
                long started = System.nanoTime();
                ended.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), halfClosed, -1L),
                        "a call whose connection came past the cap is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }

            assertAll("calls whose upstream connection came only past the cap",
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, EventType.UPSTREAM_TIMEOUT),
                            ended.stream().map(GatewayException::getEventType).toList(),
                            () -> "each call is the upstream's timeout, never the client's deadline: " + ended),
                    () -> assertTrue(ended.stream().allMatch(
                                    timeout -> timeout.getCause() instanceof DispatchStage.UpstreamConnectionTooLate),
                            () -> "each call was ended at its hand-over, not by the guard's own timeout: " + ended),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= PAST_THE_CAP_CONNECTION_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends when its connection came, before the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(0, upstreamCalls.get(), "no request was ever sent to the upstream"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "the breaker counts the upstream timeouts and opens at its threshold"));
        }

        /**
         * An upstream connection that took {@value #INSIDE_THE_GRACE_CONNECTION_MILLIS} ms — short of the cap, but
         * leaving less than the hand-over grace before it — behind a client that had already sent its whole
         * request and half-closed. Dispatched, such an attempt would get a deadline of a few milliseconds, which
         * can pass before the client's buffered end crosses from the inbound stream to the dispatch, and end the
         * call as the client's REQUEST_BODY_TIMEOUT, which the breaker skips: a connection stall charged to a
         * client that had finished. The attempt is instead refused the moment it obtains its connection, as the
         * upstream's timeout, unsent, and the breaker counts it.
         */
        @Test
        @DisplayName("a connection obtained inside the grace before the cap ends a half-closed call at once as the upstream's timeout, unsent, counted by the breaker")
        void connectionInsideTheGraceBeforeTheCapIsTheUpstreamsTimeoutAndOpensTheBreaker() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, slowToConnect(INSIDE_THE_GRACE_CONNECTION_MILLIS),
                    breakerGuard());
            List<GatewayException> ended = new ArrayList<>();
            List<Long> endedAfterMillis = new ArrayList<>();

            for (int call = 0; call < BREAKER_THRESHOLD; call++) {
                PushedBody halfClosed = new PushedBody();
                halfClosed.push("first");
                halfClosed.end();
                long started = System.nanoTime();
                ended.add(assertThrows(GatewayException.class,
                        () -> stage.dispatch(route, HttpMethod.POST, NEVER_ANSWERS, Map.of(), halfClosed, -1L),
                        "a call whose connection came inside the grace is not answered"));
                endedAfterMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }

            assertAll("half-closed calls whose upstream connection came inside the grace before the cap",
                    () -> assertTrue(ended.stream().noneMatch(DispatchStage.RequestBodyDeadlineExceeded.class::isInstance),
                            () -> "the connection stall is never charged to the client as its deadline: " + ended),
                    () -> assertEquals(Collections.nCopies(BREAKER_THRESHOLD, EventType.UPSTREAM_TIMEOUT),
                            ended.stream().map(GatewayException::getEventType).toList(),
                            () -> "each call is the upstream's timeout: " + ended),
                    () -> assertTrue(ended.stream().allMatch(
                                    timeout -> timeout.getCause() instanceof DispatchStage.UpstreamConnectionTooLate),
                            () -> "each call was refused at its hand-over, not dispatched: " + ended),
                    () -> assertTrue(endedAfterMillis.stream().allMatch(
                                    millis -> millis >= INSIDE_THE_GRACE_CONNECTION_MILLIS && millis < BREAKER_GUARD_MILLIS),
                            () -> "each call ends when its connection came, before the guard's timeout: "
                                    + endedAfterMillis),
                    () -> assertEquals(0, upstreamCalls.get(), "no request was ever sent to the upstream"),
                    () -> assertEquals(List.of(), upstreamFrames, "no message reached the upstream"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "the breaker counts the upstream timeouts and opens at its threshold"));
        }

        /**
         * A request stream that ends without any message — END_STREAM only — is not an idle client. The
         * stub upstream answers {@value #LATE_ANSWER} three bounds after the stream ended — as long as the
         * shortened deadline — so the call is still waiting for its answer when the bound passes: only the end
         * stops the bound and the deadline there.
         */
        @Test
        @DisplayName("a call whose request stream ends without any message is not ended by the message bound")
        void bodyEndingWithoutAMessageIsNotAborted() {
            Route route = grpcRoute();
            GrpcDispatchStage stage = boundedStage(route, longGuard());
            PushedBody noMessage = new PushedBody();
            noMessage.end();

            HttpClientResponse response = stage.dispatch(route, HttpMethod.POST, LATE_ANSWER, Map.of(), noMessage, -1L);

            assertAll("a call that ended its request stream without a message",
                    () -> assertEquals(200, response.statusCode(), "the call is answered"),
                    () -> assertTrue(upstreamEnded.isDone(), "the request stream ended at the upstream"),
                    () -> assertEquals(List.of(), upstreamFrames, "no message reached the upstream"),
                    () -> assertFalse(upstreamFailed.isDone(), "the upstream call was never reset"));
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

        /**
         * A stage whose message bound is shortened to {@value #MESSAGE_BOUND_MILLIS} ms, whose pre-response
         * deadline is shortened to {@value #DEADLINE_MILLIS} ms, whose pre-response cap is shortened to
         * {@value #DEADLINE_CAP_MILLIS} ms, whose hand-over grace is shortened to {@value #HAND_OVER_GRACE_MILLIS} ms
         * and whose upstream stall threshold is shortened to {@value #UPSTREAM_STALL_THRESHOLD_MILLIS} ms, all
         * below {@value #BREAKER_GUARD_MILLIS} ms.
         */
        private GrpcDispatchStage boundedStage(Route route, Guard guard) {
            return boundedStage(route, forcedH2, guard);
        }

        /** {@link #boundedStage(Route, Guard)} over {@code client} in place of the plain forced-h2 client. */
        private GrpcDispatchStage boundedStage(Route route, HttpClient client, Guard guard) {
            UpstreamTarget target = UpstreamTarget.of(route);
            UpstreamClients clients = new UpstreamClients(Map.of(target, client),
                    Map.of(new ResilienceShape(target, route.retryEnabled()), guard));
            return new GrpcDispatchStage(clients, ROOMY_CAP_BYTES, failureMapper, MESSAGE_BOUND_MILLIS,
                    DEADLINE_MILLIS, DEADLINE_CAP_MILLIS, HAND_OVER_GRACE_MILLIS, UPSTREAM_STALL_THRESHOLD_MILLIS);
        }

        /**
         * The forced-h2 client, slow to connect: every upstream request it is asked for is handed to the
         * dispatch only {@code connectionWaitMillis} after the dispatch asked for it, as a pool or a dial that
         * takes that long would hand it over.
         */
        private HttpClient slowToConnect(long connectionWaitMillis) {
            return (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[]{HttpClient.class}, (_, method, args) -> {
                        if ("request".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof RequestOptions options) {
                            Promise<Void> connected = Promise.promise();
                            vertx.setTimer(connectionWaitMillis, _ -> connected.complete());
                            return connected.future().compose(_ -> forcedH2.request(options));
                        }
                        return invokeOn(forcedH2, method, args);
                    });
        }

        /** Invokes {@code method} on {@code target}, rethrowing what it throws unwrapped. */
        private static Object invokeOn(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        /**
         * The breaker rules of the production guard (UpstreamGuards#guardFor) — the breaker skipping a
         * {@link GatewayException}, the timeout inside it — with a threshold of {@value #BREAKER_THRESHOLD}
         * and a timeout of {@value #BREAKER_GUARD_MILLIS} ms. {@code UpstreamGuardsTest} is the test that binds
         * these mirrored rules to the production guard.
         */
        private Guard breakerGuard() {
            return Guard.create()
                    .withCircuitBreaker()
                    .requestVolumeThreshold(BREAKER_THRESHOLD)
                    .failureRatio(0.5)
                    .delay(1, ChronoUnit.MINUTES)
                    .skipOn(GatewayException.class)
                    .onStateChange(breakerTransitions::add)
                    .done()
                    .withTimeout().duration(BREAKER_GUARD_MILLIS, ChronoUnit.MILLIS).done()
                    .build();
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

    /** Length of a gRPC message prefix: one compressed-flag byte and a 4-byte big-endian payload length. */
    private static final int GRPC_PREFIX_LENGTH = 5;

    /**
     * Frames {@code payload} as one uncompressed gRPC length-prefixed message: compressed flag {@code 0},
     * the payload's length as a 4-byte big-endian integer, then the UTF-8 payload. Every request payload a
     * {@link StreamBounds} case sends goes through it, because the gRPC dispatch reads that framing to tell
     * when a complete message has crossed.
     */
    private static Buffer grpcFrame(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Buffer.buffer().appendByte((byte) 0).appendInt(bytes.length).appendBytes(bytes);
    }

    /**
     * The stub upstream's reader of a gRPC request stream: it collects the bytes of each DATA frame and hands
     * on the payload of every complete length-prefixed message, however the frames split the messages. An
     * empty final DATA frame carries only the end of the stream and adds nothing. Used on one request's event
     * loop only.
     */
    private static final class MessageReassembler {

        private final Consumer<String> payloads;
        private Buffer pending = Buffer.buffer();

        MessageReassembler(Consumer<String> payloads) {
            this.payloads = payloads;
        }

        void read(Buffer frame) {
            pending.appendBuffer(frame);
            while (pending.length() >= GRPC_PREFIX_LENGTH) {
                int end = GRPC_PREFIX_LENGTH + pending.getInt(1);
                if (pending.length() < end) {
                    return;
                }
                payloads.accept(pending.getString(GRPC_PREFIX_LENGTH, end, StandardCharsets.UTF_8.name()));
                pending = pending.getBuffer(end, pending.length());
            }
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
     * A request body the test pushes gRPC messages into: each pushed payload is framed as one message, and
     * the messages are delivered to the consumer while it has not paused the stream, and held until it
     * resumes otherwise; the end is delivered after the last one.
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
        private boolean resumedOnce;
        private boolean heldBack;
        private boolean ended;
        private boolean endDelivered;

        /** Pushes {@code payload} as one complete gRPC message ({@link GrpcDispatchStageTest#grpcFrame(String)}). */
        void push(String payload) {
            pushBytes(grpcFrame(payload));
        }

        /** Pushes {@code bytes} as they are — part of a message, when a case needs one. */
        synchronized void pushBytes(Buffer bytes) {
            if (paused && resumedOnce) {
                heldBack = true;
            }
            pending.add(bytes);
            drain();
        }

        /**
         * @return {@code true} once a message was pushed while the consumer, having read the stream, had paused
         *         it again — the forwarding pipe does so when the upstream request stops being writable
         */
        synchronized boolean heldBack() {
            return heldBack;
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
            resumedOnce = true;
            drain();
            return this;
        }

        @Override
        public synchronized ReadStream<Buffer> fetch(long amount) {
            paused = false;
            resumedOnce = true;
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
