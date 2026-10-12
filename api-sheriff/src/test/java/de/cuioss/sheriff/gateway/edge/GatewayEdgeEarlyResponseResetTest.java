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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The one end rule of the edge, driven over a live Vert.x edge against a loopback stub upstream: an
 * HTTP/2 response answered while its request body is still open is ended once no request data can still
 * be in flight and is then followed by a {@code NO_ERROR} stream reset, and nothing else is.
 * <p>
 * <strong>Why it matters.</strong> A client whose upload is answered before its body was sent — an
 * upstream that answers without reading the body, or any answer the gateway writes itself — is left
 * with a half-open stream. A Go-style client then resets every such stream itself, and enough client
 * resets on one connection trip the server's Rapid-Reset guard, which drops the connection with every
 * sibling stream on it. The gateway's own {@code NO_ERROR} reset after the complete response tells the
 * client to stop sending, so it does not have to reset the stream itself. That reset must not race the
 * data the client is still sending, either: the transport answers every data frame that arrives after
 * it with a {@code STREAM_CLOSED} reset and counts those against the same limit — the case
 * {@link #uploadsStillSendingWhenAnsweredStayOnOneConnection} drives.
 * <p>
 * <strong>What the stub upstream does.</strong> A request to a path under {@value #EARLY} is answered
 * at once, before its body is read; one under {@value #HELD} is received and never answered; every
 * other request is answered {@code ok} once its body has ended. It records every request it saw, and
 * whether that request's body ended or failed — a failed body is how the upstream observes the gateway
 * resetting the upstream request, i.e. forwarding being stopped.
 * <p>
 * <strong>Abort attribution.</strong> The gateway's own reset is never a client abort: it is neither
 * metered nor logged as {@code INBOUND_BODY_ABORTED}, while a client dropping its connection mid-body
 * still is.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("GatewayEdgeRoute — HTTP/2 responses that end while the request body is still open")
@Tag("isolated-fork")
class GatewayEdgeEarlyResponseResetTest {

    /** The upstream path prefix the stub answers at once, before reading the request body. */
    private static final String EARLY = "/early";
    /** The upstream path prefix the stub receives and never answers. */
    private static final String HELD = "/held";
    /** The route every proxied request of this fixture takes; the prefix is stripped upstream. */
    private static final String ROUTE = "up";
    private static final String ORIGIN = "https://app.example";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String INBOUND_BODY_ABORTED = "INBOUND_BODY_ABORTED";
    /** The INFO record an aborted inbound body is reported with. */
    private static final String INBOUND_BODY_ABORTED_RECORD = "ApiSheriff-22";
    private static final String CRLF = "\r\n";
    /** The further request sent on a connection after a reset, as the upstream sees it. */
    private static final String AFTER_GET = "GET /after";
    /**
     * The per-connection reset limit the front server of the still-sending case enforces — in both
     * directions, as the transport applies it to the resets it receives and to those it sends. Lower than
     * the production default so the case stays short; the mechanism is the same.
     */
    private static final int RESET_LIMIT = 20;
    /** Early-answered uploads the still-sending case sends over one connection: well above the limit. */
    private static final int SENDING_UPLOADS = 3 * RESET_LIMIT;
    /**
     * Uploads the still-sending case keeps in flight at once on that connection: enough that the
     * bodies the gateway holds back exhaust the connection's flow-control window, as many concurrent
     * uploads on one connection do.
     */
    private static final int SENDING_BATCH = 10;
    /** The size of each chunk a still-sending upload writes. */
    private static final int CHUNK_BYTES = 8 * 1024;
    /** What one still-sending upload sends at most: far beyond any flow-control window. */
    private static final long UPLOAD_CAP_BYTES = 16L * 1024 * 1024;

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private NetClient netClient;
    private int frontPort;
    /** The body the stub upstream answers an early request with. */
    private String earlyAnswer;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<HttpServer> frontServers = new CopyOnWriteArrayList<>();
    private final List<HttpClient> httpClients = new CopyOnWriteArrayList<>();

    /** Every request the stub upstream received a head for, as {@code METHOD path}. */
    private final List<String> upstreamStarted = new CopyOnWriteArrayList<>();
    /** Every request whose body the stub upstream read to its end. */
    private final List<String> upstreamCompleted = new CopyOnWriteArrayList<>();
    /** Every request whose body failed at the stub upstream — the gateway reset it. */
    private final List<String> upstreamAborted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        earlyAnswer = Generators.letterStrings(16, 32).next();
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(this::handleUpstreamRequest)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        frontPort = startEdge(new EdgeHardeningOptions());
        netClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (HttpClient client : httpClients) {
            Awaits.teardown(client.close(), "an HTTP client to close");
        }
        Awaits.teardown(netClient.close(), "the raw TCP client to close");
        for (HttpServer frontServer : frontServers) {
            Awaits.teardown(frontServer.close(), "an edge front server to close");
        }
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    private void handleUpstreamRequest(HttpServerRequest request) {
        String label = request.method() + " " + request.path();
        boolean early = request.path().startsWith(EARLY);
        boolean held = request.path().startsWith(HELD);
        upstreamStarted.add(label);
        AtomicBoolean settled = new AtomicBoolean();
        request.handler(chunk -> {
            // Read and discard: the body is observed only by whether it ends or fails.
        });
        request.exceptionHandler(failure -> {
            if (settled.compareAndSet(false, true)) {
                upstreamAborted.add(label);
            }
        });
        request.endHandler(end -> {
            if (settled.compareAndSet(false, true)) {
                upstreamCompleted.add(label);
                if (!early && !held) {
                    request.response().end("ok");
                }
            }
        });
        if (early) {
            request.response().end(earlyAnswer);
        }
    }

    @Test
    @DisplayName("an upstream answering before the body ended: whole 200, then a NO_ERROR reset; forwarding stops")
    void earlyUpstreamAnswerIsFollowedByANoErrorReset() throws Exception {
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest sibling = open(http2Client, frontPort, "POST", "/up/sibling");
        HttpConnection connection = sibling.connection();
        AtomicBoolean connectionLost = new AtomicBoolean();
        connection.closeHandler(closed -> connectionLost.set(true));
        connection.goAwayHandler(goAway -> connectionLost.set(true));
        Awaits.connect(sibling.setChunked(true).write(Generators.letterStrings(4, 12).next()),
                "the sibling stream to send part of its body");
        Awaits.until(() -> upstreamStarted.contains("POST /sibling"),
                "the upstream to receive the head of the sibling request", Awaits.CONNECT_CEILING_SECONDS);

        Upload upload = startUpload(http2Client, frontPort, "POST", "/up" + EARLY + "-upload", Map.of());
        Answer answer = Awaits.connect(answerOf(upload.request()), "the early answer to arrive whole");
        Throwable streamEnd = Awaits.connect(upload.streamEnd(), "the edge to reset the open upload stream");
        Awaits.until(() -> upstreamAborted.contains("POST " + EARLY + "-upload"),
                "the upstream request of the early-answered upload to be reset", Awaits.CONNECT_CEILING_SECONDS);
        Answer siblingAnswer = Awaits.connect(sibling.end().compose(sent -> answerOf(sibling)),
                "the sibling to be answered");
        Answer after = sendAfter(http2Client, frontPort);

        assertAll("an early upstream answer on an open HTTP/2 upload",
                () -> assertEquals(200, answer.response().statusCode()),
                () -> assertEquals(earlyAnswer, answer.body(), "the response must arrive whole before the reset"),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                        "the stream must be reset with NO_ERROR"),
                () -> assertEquals(List.of("POST " + EARLY + "-upload"), upstreamAborted,
                        "the upstream request still receiving the body is reset, and only that one"),
                () -> assertEquals(200, siblingAnswer.response().statusCode(), "the sibling stream completes"),
                () -> assertEquals(200, after.response().statusCode(), "a further stream is served"),
                () -> assertSame(connection, upload.request().connection()),
                () -> assertSame(connection, after.connection(), "the further stream reuses the connection"),
                () -> assertFalse(connectionLost.get(), "the connection must neither close nor receive GOAWAY"),
                () -> assertEquals(0.0, errorCount(INBOUND_BODY_ABORTED),
                        "the gateway's own reset is never metered as an aborted inbound body"),
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO, INBOUND_BODY_ABORTED_RECORD));
    }

    /**
     * Uploads that keep sending — as fast as flow control lets them — when the upstream answers them,
     * many more than the reset limit, over one connection. A reset written while their data is still on
     * the way would draw one {@code STREAM_CLOSED} reset from the transport per frame that arrives after
     * it, and the limit would close the connection with {@code GOAWAY ENHANCE_YOUR_CALM}. The gateway
     * instead stops granting window, ends each response once nothing more can arrive and resets the
     * stream then.
     */
    @Test
    @DisplayName("uploads still sending when answered early: each answered whole, then reset by the gateway; no GOAWAY")
    void uploadsStillSendingWhenAnsweredStayOnOneConnection() throws Exception {
        int port = startEdge(new EdgeHardeningOptions(),
                new HttpServerOptions().setHttp2RstFloodMaxRstFramePerWindow(RESET_LIMIT));
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        List<HttpConnection> connections = new CopyOnWriteArrayList<>();
        List<Long> goAwayCodes = new CopyOnWriteArrayList<>();
        List<SendingUpload> uploads = new ArrayList<>();

        for (int started = 0; started < SENDING_UPLOADS; started += SENDING_BATCH) {
            List<SendingUpload> batch = new ArrayList<>();
            for (int i = 0; i < SENDING_BATCH; i++) {
                batch.add(startSendingUpload(http2Client, port, "/up" + EARLY + "-sending-" + (started + i),
                        connections, goAwayCodes));
            }
            for (SendingUpload upload : batch) {
                upload.settle();
            }
            uploads.addAll(batch);
        }

        List<SendingUpload> notAnswered = uploads.stream()
                .filter(upload -> upload.answer() == null || upload.answer().response().statusCode() != 200
                        || !earlyAnswer.equals(upload.answer().body()))
                .toList();
        List<SendingUpload> notResetByGateway = uploads.stream()
                .filter(upload -> !(upload.streamEnd() instanceof StreamResetException reset && reset.getCode() == 0L))
                .toList();
        assertAll("uploads still sending when answered early",
                () -> assertEquals(SENDING_UPLOADS, uploads.size()),
                () -> assertTrue(notAnswered.isEmpty(), () -> notAnswered.size()
                        + " upload(s) were not answered whole: "
                        + notAnswered.stream().map(SendingUpload::outcome).toList()),
                () -> assertTrue(notResetByGateway.isEmpty(), () -> notResetByGateway.size()
                        + " stream(s) did not end in the gateway's NO_ERROR reset, first: "
                        + notResetByGateway.getFirst().outcome()),
                () -> assertTrue(goAwayCodes.isEmpty(), () -> "the edge sent GOAWAY " + goAwayCodes),
                () -> assertEquals(1, connections.size(), "every upload must share the one connection"),
                () -> assertTrue(uploads.stream().allMatch(upload -> upload.sent().get() < UPLOAD_CAP_BYTES),
                        "every upload must have been stopped by the gateway, not by its own cap"),
                () -> assertEquals(0.0, errorCount(INBOUND_BODY_ABORTED),
                        "the gateway's own resets are never metered as aborted inbound bodies"));
    }

    @Test
    @DisplayName("a body that ended before the response completed is not reset")
    void endedBodyIsNotReset() throws Exception {
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest request = open(http2Client, frontPort, "POST", "/up/ended");
        CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
        request.exceptionHandler(streamEnd::complete);

        Answer answer = Awaits.connect(request.send(Generators.letterStrings(4, 12).next())
                        .compose(response -> response.body()
                                .map(body -> new Answer(response, body.toString(), request.connection()))),
                "the request to be answered");
        Answer after = sendAfter(http2Client, frontPort);

        assertAll("a body that ended before the response",
                () -> assertEquals(200, answer.response().statusCode()),
                () -> assertEquals("ok", answer.body()),
                () -> assertEquals(200, after.response().statusCode()),
                () -> assertFalse(streamEnd.isDone(), () -> "the stream must not be reset: " + streamEnd.getNow(null)),
                () -> assertTrue(upstreamCompleted.contains("POST /ended"), "the body reached the upstream whole"),
                () -> assertEquals(List.of(), upstreamAborted));
    }

    /**
     * Answers the gateway writes itself to an upload whose body is still open — the framing rejection
     * among them — each reset because the body is still open.
     */
    enum GatewayAnswer {
        UNROUTED_PROBLEM("POST", "/unrouted/upload", 404, Map.of()),
        REDIRECT("POST", "/moved/upload", 308, Map.of()),
        PREFLIGHT_SHORT_CIRCUIT("OPTIONS", "/up/upload", 204,
                Map.of("Origin", ORIGIN, "Access-Control-Request-Method", "POST")),
        FRAMING_REJECTION("GET", "/up/get-body", 400, Map.of(CONTENT_LENGTH, "4096"));

        private final String method;
        private final String path;
        private final int status;
        private final Map<String, String> headers;

        GatewayAnswer(String method, String path, int status, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.status = status;
            this.headers = headers;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(GatewayAnswer.class)
    @DisplayName("an answer the gateway writes to an open HTTP/2 upload is followed by a NO_ERROR reset")
    void gatewayWrittenAnswerIsFollowedByANoErrorReset(GatewayAnswer gatewayAnswer) throws Exception {
        HttpClient http2Client = client(HttpVersion.HTTP_2);

        Upload upload = startUpload(http2Client, frontPort, gatewayAnswer.method, gatewayAnswer.path,
                gatewayAnswer.headers);
        Answer answer = Awaits.connect(answerOf(upload.request()), "the gateway's answer to arrive whole");
        Throwable streamEnd = Awaits.connect(upload.streamEnd(), "the edge to reset the open upload stream");
        Answer after = sendAfter(http2Client, frontPort);

        assertAll(gatewayAnswer.name(),
                () -> assertEquals(gatewayAnswer.status, answer.response().statusCode(), answer.body()),
                () -> assertNull(answer.response().getHeader("Connection"),
                        "an HTTP/2 answer must not carry the connection-specific header"),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                        "the stream must be reset with NO_ERROR"),
                () -> assertEquals(200, after.response().statusCode(), "a further stream is served"),
                () -> assertSame(upload.request().connection(), after.connection(),
                        "the further stream reuses the connection the answer was written on"),
                () -> assertEquals(List.of(AFTER_GET), upstreamStarted, "nothing of the upload reached the upstream"));
    }

    @Test
    @DisplayName("the admission 503 written to an open HTTP/2 upload is followed by a NO_ERROR reset")
    void admissionRejectIsFollowedByANoErrorReset() throws Exception {
        int port = startEdge(new EdgeHardeningOptions(new EdgeHardeningConfig(1, 1)));
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest holding = open(http2Client, port, "GET", "/up" + HELD + "-admission");
        Awaits.connect(holding.end(), "the request holding the one admission permit to be sent");
        Awaits.until(() -> upstreamStarted.contains("GET " + HELD + "-admission"),
                "the held request to occupy the admission permit", Awaits.CONNECT_CEILING_SECONDS);

        Upload upload = startUpload(http2Client, port, "POST", "/up/refused", Map.of());
        Answer answer = Awaits.connect(answerOf(upload.request()), "the 503 to arrive");
        Throwable streamEnd = Awaits.connect(upload.streamEnd(), "the edge to reset the refused upload stream");

        assertAll("an admission reject of an open HTTP/2 upload",
                () -> assertEquals(503, answer.response().statusCode()),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                        "the stream must be reset with NO_ERROR"),
                () -> assertSame(holding.connection(), upload.request().connection(),
                        "the refused stream shares the connection of the held one, which stays open"),
                () -> assertFalse(upstreamStarted.contains("POST /refused"), "the refused upload never reaches the upstream"));
    }

    @Test
    @DisplayName("HTTP/1.1 — an early upstream answer is not reset and adds no Connection: close")
    void http1EarlyAnswerIsUnchanged() throws Exception {
        HttpClient http1Client = client(HttpVersion.HTTP_1_1);

        Upload upload = startUpload(http1Client, frontPort, "POST", "/up" + EARLY + "-h1", Map.of());
        Answer answer = Awaits.connect(answerOf(upload.request()), "the early answer to arrive");
        Awaits.connect(upload.request().end(), "the rest of the upload to be sent");
        Awaits.until(() -> upstreamCompleted.contains("POST " + EARLY + "-h1"),
                "the upstream to read the whole upload", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("an early answer over HTTP/1.1",
                () -> assertEquals(200, answer.response().statusCode()),
                () -> assertEquals(earlyAnswer, answer.body()),
                () -> assertNull(answer.response().getHeader("Connection"),
                        "an early answer must not retire the HTTP/1.1 connection"),
                () -> assertFalse(upload.streamEnd().isDone(),
                        () -> "an HTTP/1.1 request is never reset: " + upload.streamEnd().getNow(null)),
                () -> assertEquals(List.of(), upstreamAborted, "the upstream received the upload whole"));
    }

    @Test
    @DisplayName("a client dropping its connection mid-body before the response is still metered and logged")
    void clientDropBeforeTheResponseIsStillAnAbortedInboundBody() throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(frontPort, LoopbackHost.ADDRESS),
                "the raw TCP client to connect to the edge");
        socket.write("POST /up" + HELD + "-drop HTTP/1.1" + CRLF + "Host: localhost:" + frontPort + CRLF
                + "Transfer-Encoding: chunked" + CRLF + CRLF + "5" + CRLF + "hello" + CRLF);
        Awaits.until(() -> upstreamStarted.contains("POST " + HELD + "-drop"),
                "the upstream to receive the head of the dispatched upload", Awaits.CONNECT_CEILING_SECONDS);

        Awaits.connect(socket.close(), "the client to drop its connection mid-upload");
        Awaits.until(() -> errorCount(INBOUND_BODY_ABORTED) == 1.0,
                "the edge to account for the abandoned upload", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("a client drop before the response",
                () -> assertEquals(1.0, errorCount(INBOUND_BODY_ABORTED)),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                        INBOUND_BODY_ABORTED_RECORD));
    }

    /**
     * Builds an edge over the proxied {@value #ROUTE} route and a redirect route, with a global CORS
     * policy so a preflight is short-circuited, and starts a front server for it.
     *
     * @return the port the edge listens on
     */
    private int startEdge(EdgeHardeningOptions hardening) throws Exception {
        return startEdge(hardening, new HttpServerOptions());
    }

    /**
     * Builds the edge as {@link #startEdge(EdgeHardeningOptions)} does, on a front server started with
     * {@code serverOptions}.
     *
     * @return the port the edge listens on
     */
    private int startEdge(EdgeHardeningOptions hardening, HttpServerOptions serverOptions) throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        RouteTable routes = new RouteTable(List.of(
                ResolvedRoute.builder()
                        .id(ROUTE)
                        .protocol(Protocol.HTTP)
                        .match(MatchConfig.builder().pathPrefix("/" + ROUTE).build())
                        .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                        .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                        .build(),
                ResolvedRoute.builder()
                        .id("moved")
                        .protocol(Protocol.HTTP)
                        .match(MatchConfig.builder().pathPrefix("/moved").build())
                        .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                        .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                        .redirect(RedirectConfig.builder().location("/" + ROUTE + "/new-home").status(308).build())
                        .build()));
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1)
                .securityHeaders(SecurityHeadersConfig.builder()
                        .cors(SecurityHeadersConfig.Cors.builder()
                                .enabled(Boolean.TRUE)
                                .allowedOrigins(List.of(ORIGIN))
                                .allowedMethods(List.of("GET", "POST"))
                                .build())
                        .build())
                .build();
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routes, gatewayConfig, new SingletonInstance<>(tokenValidator),
                vertx, virtualThreadExecutor, hardening, new SheriffMetrics(meterRegistry), BffRuntime.inert(),
                EgressTrustProfiles.unconsulted(), PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        HttpServer frontServer = Awaits.connect(
                vertx.createHttpServer(serverOptions).requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
        frontServers.add(frontServer);
        return frontServer.actualPort();
    }

    /**
     * Opens an upload on {@code client}, records how its stream ends, and sends the first part of its
     * body without ending it — chunked, unless {@code headers} declares a length.
     */
    private Upload startUpload(HttpClient client, int port, String method, String path, Map<String, String> headers)
            throws Exception {
        HttpClientRequest request = open(client, port, method, path);
        CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
        request.exceptionHandler(streamEnd::complete);
        headers.forEach(request::putHeader);
        if (!headers.containsKey(CONTENT_LENGTH)) {
            request.setChunked(true);
        }
        Awaits.connect(request.write(Generators.letterStrings(8, 16).next()),
                "the first part of the " + method + " " + path + " body to be sent");
        return new Upload(request, streamEnd);
    }

    /**
     * Opens an upload on {@code client} that keeps sending its body chunk after chunk — as fast as flow
     * control lets it, up to {@link #UPLOAD_CAP_BYTES} — until its stream ends, and records the first
     * GOAWAY of every connection it is sent on.
     */
    private SendingUpload startSendingUpload(HttpClient client, int port, String path,
            List<HttpConnection> connections, List<Long> goAwayCodes) throws Exception {
        HttpClientRequest request = open(client, port, "POST", path);
        HttpConnection connection = request.connection();
        if (!connections.contains(connection)) {
            connections.add(connection);
            connection.goAwayHandler(goAway -> goAwayCodes.add(goAway.getErrorCode()));
        }
        CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
        request.exceptionHandler(streamEnd::complete);
        request.setChunked(true);
        Future<Answer> answer = answerOf(request);
        AtomicLong sent = new AtomicLong();
        pump(request, Buffer.buffer(Generators.letterStrings(CHUNK_BYTES, CHUNK_BYTES).next()), streamEnd, sent);
        return new SendingUpload(request, answer, streamEnd, sent);
    }

    /**
     * Writes {@code chunk} to {@code request} while the stream accepts it and has not ended, and resumes
     * once it drains again — the way an uploading client keeps its data in flight.
     */
    private static void pump(HttpClientRequest request, Buffer chunk, CompletableFuture<Throwable> streamEnd,
            AtomicLong sent) {
        while (!streamEnd.isDone() && sent.get() < UPLOAD_CAP_BYTES && !request.writeQueueFull()) {
            request.write(chunk);
            sent.addAndGet(chunk.length());
        }
        if (!streamEnd.isDone() && sent.get() < UPLOAD_CAP_BYTES) {
            request.drainHandler(drained -> pump(request, chunk, streamEnd, sent));
        }
    }

    /** Sends a well-framed GET on {@code client}'s connection to {@code port} and returns its answer. */
    private Answer sendAfter(HttpClient client, int port) throws Exception {
        HttpClientRequest after = open(client, port, "GET", "/up/after");
        return Awaits.connect(after.end().compose(sent -> answerOf(after)), "a further stream to be answered");
    }

    /** The answer to {@code request}: its response, with the body read whole. */
    private static Future<Answer> answerOf(HttpClientRequest request) {
        return request.response().compose(response -> response.body()
                .map(body -> new Answer(response, body.toString(), request.connection())));
    }

    private static HttpClientRequest open(HttpClient client, int port, String method, String path) throws Exception {
        return Awaits.connect(client.request(io.vertx.core.http.HttpMethod.valueOf(method), port,
                LoopbackHost.ADDRESS, path), "the " + method + " " + path + " request to open");
    }

    /** A client speaking {@code version} (HTTP/2 with prior knowledge), closed in {@link #tearDown()}. */
    private HttpClient client(HttpVersion version) {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(version).setHttp2ClearTextUpgrade(false));
        httpClients.add(client);
        return client;
    }

    /** The {@code sheriff_errors_total} count of the {@value #ROUTE} route for one event, {@code 0} when absent. */
    private double errorCount(String event) {
        var counter = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL).tags("route", ROUTE, "event", event).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** An upload whose body is still open, and the way its stream ended once it has. */
    private record Upload(HttpClientRequest request, CompletableFuture<Throwable> streamEnd) {
    }

    /** An upload that keeps sending until its stream ends: its answer, how it ended and what it sent. */
    private static final class SendingUpload {

        private final HttpClientRequest request;
        private final Future<Answer> answerFuture;
        private final CompletableFuture<Throwable> streamEndFuture;
        private final AtomicLong sent;
        private @Nullable Answer answer;
        private @Nullable Throwable streamEnd;
        private @Nullable Throwable failure;

        SendingUpload(HttpClientRequest request, Future<Answer> answer, CompletableFuture<Throwable> streamEnd,
                AtomicLong sent) {
            this.request = request;
            this.answerFuture = answer;
            this.streamEndFuture = streamEnd;
            this.sent = sent;
        }

        /** Waits for the answer and the end of the stream, recording a failure of either instead of throwing. */
        void settle() throws InterruptedException {
            try {
                answer = Awaits.connect(answerFuture, "the early answer to " + request.path() + " to arrive whole");
                streamEnd = Awaits.connect(streamEndFuture, "the stream of " + request.path() + " to end");
            } catch (ExecutionException | TimeoutException settleFailure) {
                failure = settleFailure;
            }
        }

        @Nullable
        Answer answer() {
            return answer;
        }

        @Nullable
        Throwable streamEnd() {
            return streamEnd;
        }

        AtomicLong sent() {
            return sent;
        }

        /** @return how this upload ended, for a failure message */
        String outcome() {
            return request.path() + ": status=" + (answer == null ? "none" : answer.response().statusCode())
                    + ", body=" + (answer == null ? "none" : answer.body())
                    + ", streamEnd=" + streamEnd + ", failure=" + (failure == null ? null : failure.getClass().getName()
                    + ": " + failure.getMessage().lines().findFirst().orElse("")) + ", sent=" + sent.get();
        }
    }

    /** The response to one client request, its body read whole, and the connection it was sent on. */
    private record Answer(HttpClientResponse response, String body, HttpConnection connection) {
    }

    /** Minimal {@link Instance} double resolving to one supplied validator; unused accessors throw. */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
