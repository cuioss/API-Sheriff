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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The request-smuggling corpus, driven over a live Vert.x edge against a loopback stub upstream.
 * <p>
 * Every HTTP/1.1 smuggling entry is written as raw bytes over a plain TCP socket, so malformed framing
 * reaches the edge exactly as an attacker would send it — an HTTP client would normalise or refuse
 * each of these shapes before a byte left the process. The HTTP/2 entries and the body-framing
 * entries drive a Vert.x client instead (prior-knowledge HTTP/2, or HTTP/1.1), because their subject
 * is what a rejection does to the streams sharing a connection, and what of a request body reaches
 * the upstream and how it is framed there — not a byte shape. Each entry asserts the status of every
 * response the edge wrote, and the exact list of requests the stub upstream saw.
 * <p>
 * <strong>What the stub upstream records.</strong> Besides the request list, it records per request
 * the {@code Content-Length} and {@code Transfer-Encoding} it arrived with and how many body bytes
 * reached it, so the framing the gateway forwards a body with is observable. A request to a path
 * under {@value #HELD} is received but never answered; a request to a path under
 * {@value #STREAMING} is answered at once with a response body that never ends.
 * <p>
 * <strong>How an entry is brought to a deterministic end.</strong> The corpus bytes are followed, on
 * the same connection, by a sentinel request to an unrouted path that asks for
 * {@code Connection: close}. The edge answers pipelined requests in order, so the server-side close
 * arrives only after everything before the sentinel has been answered — or earlier, where the edge
 * retires the connection itself. Either way the upstream's request list is then read behind a
 * well-framed drain request sent over a fresh connection, which gives a smuggled request every
 * opportunity to arrive first.
 * <p>
 * <strong>What "smuggled" means here.</strong> Every smuggling entry hides a
 * {@code GET /echo/smuggled} behind the framing ambiguity. The upstream never seeing that path is the
 * invariant of the corpus; the per-entry request list additionally pins that the upstream saw at
 * most the one legitimate request.
 * <p>
 * <strong>The one entry that is not an attack.</strong> A client that simply drops its connection
 * mid-upload needs the same raw socket to be produced, so it is pinned here too: it is reported as
 * the client-attributed {@code INBOUND_BODY_ABORTED}, latched per route at {@code INFO}, and never
 * as the security-filter {@code WARN}.
 */
@EnableGeneratorController
@EnableTestLogger(debug = GatewayEdgeRoute.class)
@DisplayName("GatewayEdgeRoute — request-smuggling corpus over raw sockets")
class GatewayEdgeFramingCorpusTest {

    private static final String CRLF = "\r\n";
    /** The path segment only the hidden request carries, whichever side of the edge reports it. */
    private static final String SMUGGLED = "smuggled";
    /** The drain request as the upstream sees it: the route's {@code /echo} prefix is stripped. */
    private static final String DRAIN = "GET /drain";
    /** The chunked request with broken inbound framing, as the upstream sees it once dispatched. */
    private static final String BARE_LF_POST = "POST /bare-lf";
    /** The well-framed HTTP/2 stream sharing a connection with a rejected one, as the upstream sees it. */
    private static final String SIBLING_POST = "POST /sibling";
    /** The further request sent on a connection after one of its streams was refused, as the upstream sees it. */
    private static final String AFTER_GET = "GET /after";
    /** The upstream path prefix whose requests the stub upstream receives but never answers. */
    private static final String HELD = "/held";
    /** The upstream path prefix the stub upstream answers at once, with a response body it never ends. */
    private static final String STREAMING = "/streaming";
    /** The response header, lower-cased, by which the edge announces it is retiring the connection. */
    private static final String CONNECTION_CLOSE = "connection: close";
    /** The gateway's own framing rejection: its problem document names the status it was sent with. */
    private static final String PROBLEM_JSON_400 = "\"status\":400";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String SECURITY_FILTER_VIOLATION = "SECURITY_FILTER_VIOLATION";
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 (\\d{3}) ");

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private NetClient netClient;
    private int frontPort;
    /** The registry the default edge meters into. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    /** The registry an edge built with the {@code GET}-body opt-in meters into. */
    private final SimpleMeterRegistry optInMeterRegistry = new SimpleMeterRegistry();
    /** Every edge front server a test started, closed in {@link #tearDown()}. */
    private final List<HttpServer> frontServers = new CopyOnWriteArrayList<>();
    /** Every HTTP client a test created through {@link #client(HttpVersion)}, closed in {@link #tearDown()}. */
    private final List<HttpClient> httpClients = new CopyOnWriteArrayList<>();

    /** Every request the stub upstream received a head for, as {@code METHOD path}. */
    private final List<String> upstreamStarted = new CopyOnWriteArrayList<>();
    /** Every request whose body the stub upstream read to its end, as {@code METHOD path [body]}. */
    private final List<String> upstreamCompleted = new CopyOnWriteArrayList<>();
    /** Every request whose body ended in a failure at the stub upstream, as {@code METHOD path}. */
    private final List<String> upstreamAborted = new CopyOnWriteArrayList<>();
    /** The upstream-side connection each request arrived on, keyed by its {@code METHOD path} label. */
    private final Map<String, HttpConnection> upstreamConnections = new ConcurrentHashMap<>();
    /** Every upstream-side connection the stub upstream has seen close. */
    private final Set<HttpConnection> upstreamClosedConnections = ConcurrentHashMap.newKeySet();
    /** The framing headers each request arrived at the stub upstream with, keyed by its label. */
    private final Map<String, Framing> upstreamFraming = new ConcurrentHashMap<>();
    /** How many body bytes of each request reached the stub upstream, keyed by its label. */
    private final Map<String, AtomicLong> upstreamBodyBytes = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamServer = Awaits.connect(vertx.createHttpServer().connectionHandler(
                connection -> connection.closeHandler(closed -> upstreamClosedConnections.add(connection))
        ).requestHandler(this::handleUpstreamRequest).listen(0, LoopbackHost.ADDRESS),
                "the stub upstream server to start listening");

        // No security_defaults block at all: allow_get_with_content_length_body is unset.
        frontPort = startEdge(GatewayConfig.builder().version(1).build(), meterRegistry);
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

    /**
     * Builds an edge over the one {@code echo} route for {@code gatewayConfig}, metering into
     * {@code registry}, and starts a front server for it.
     *
     * @return the port the edge listens on
     */
    private int startEdge(GatewayConfig gatewayConfig, SimpleMeterRegistry registry) throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        RouteTable routes = new RouteTable(List.of(ResolvedRoute.builder()
                .id("echo")
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/echo").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                .build()));
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routes, gatewayConfig, new SingletonInstance<>(tokenValidator),
                vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                new SheriffMetrics(registry), BffRuntime.inert(),
                EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        HttpServer frontServer = Awaits.connect(
                vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
        frontServers.add(frontServer);
        return frontServer.actualPort();
    }

    /** Starts an edge whose gateway document declares {@code allow_get_with_content_length_body: true}. */
    private int startOptInEdge() throws Exception {
        return startEdge(GatewayConfig.builder().version(1)
                .securityDefaults(new SecurityDefaultsConfig(null, null, Boolean.TRUE, null)).build(),
                optInMeterRegistry);
    }

    /**
     * The stub upstream: records the request, its framing headers and every body byte that reaches
     * it, then answers {@code ok} once the body has ended — except under {@value #HELD}, which is
     * never answered, and under {@value #STREAMING}, which is answered at once with a body that never
     * ends.
     */
    private void handleUpstreamRequest(HttpServerRequest request) {
        String label = request.method() + " " + request.path();
        boolean held = request.path().startsWith(HELD);
        boolean streaming = request.path().startsWith(STREAMING);
        upstreamConnections.put(label, request.connection());
        upstreamFraming.put(label, new Framing(request.getHeader(CONTENT_LENGTH), request.getHeader(TRANSFER_ENCODING)));
        AtomicLong bodyBytes = upstreamBodyBytes.computeIfAbsent(label, unused -> new AtomicLong());
        upstreamStarted.add(label);
        if (streaming) {
            request.response().setChunked(true).write(Generators.letterStrings(4, 12).next());
        }
        Buffer body = Buffer.buffer();
        // A request settles exactly once: read to its end, or failed before it got there.
        AtomicBoolean settled = new AtomicBoolean();
        request.handler(chunk -> {
            bodyBytes.addAndGet(chunk.length());
            body.appendBuffer(chunk);
        });
        request.exceptionHandler(failure -> {
            if (settled.compareAndSet(false, true)) {
                upstreamAborted.add(label);
            }
        });
        request.endHandler(end -> {
            if (settled.compareAndSet(false, true)) {
                upstreamCompleted.add(label + " [" + body + "]");
                if (!held && !streaming) {
                    request.response().end("ok");
                }
            }
        });
    }

    @Test
    @DisplayName("control — a well-framed POST reaches the upstream exactly once, body intact")
    void wellFramedControl() throws Exception {
        String body = Generators.letterStrings(4, 12).next();
        String raw = "POST /echo/control HTTP/1.1" + CRLF + host()
                + "Content-Length: " + body.length() + CRLF + CRLF + body;

        Exchange exchange = exchange(raw);

        assertAll("well-framed control",
                () -> assertEquals(List.of(200, 404), exchange.statuses(), exchange.raw()),
                () -> assertEquals(List.of("POST /control", DRAIN), upstreamStarted),
                () -> assertEquals("POST /control [" + body + "]", upstreamCompleted.getFirst()));
    }

    @Test
    @DisplayName("CL.TE — Content-Length then Transfer-Encoding: chunked is refused by the transport")
    void contentLengthThenTransferEncoding() throws Exception {
        String body = "0" + CRLF + CRLF + smuggled();
        String raw = "POST /echo/cl-te HTTP/1.1" + CRLF + host()
                + "Content-Length: " + body.length() + CRLF
                + "Transfer-Encoding: chunked" + CRLF + CRLF + body;

        Exchange exchange = exchange(raw);

        assertRefusedByTransport(exchange);
    }

    @Test
    @DisplayName("TE.CL — Transfer-Encoding: chunked then Content-Length is refused by the transport")
    void transferEncodingThenContentLength() throws Exception {
        String smuggled = smuggled();
        String body = Integer.toHexString(smuggled.length()) + CRLF + smuggled + CRLF + "0" + CRLF + CRLF;
        String raw = "POST /echo/te-cl HTTP/1.1" + CRLF + host()
                + "Transfer-Encoding: chunked" + CRLF
                + "Content-Length: 3" + CRLF + CRLF + body;

        Exchange exchange = exchange(raw);

        assertRefusedByTransport(exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chunked, identity", "identity, chunked"})
    @DisplayName("TE.TE — Content-Length beside a coding list naming chunked is refused by the transport")
    void obfuscatedTransferEncodingTheTransportReadsAsChunked(String transferEncoding) throws Exception {
        String raw = contentLengthBesideTransferEncoding("Transfer-Encoding: " + transferEncoding + CRLF);

        Exchange exchange = exchange(raw);

        assertRefusedByTransport(exchange);
    }

    @Test
    @DisplayName("TE.TE — Content-Length beside a repeated Transfer-Encoding field is refused by the transport")
    void repeatedTransferEncoding() throws Exception {
        String raw = contentLengthBesideTransferEncoding(
                "Transfer-Encoding: chunked" + CRLF + "Transfer-Encoding: identity" + CRLF);

        Exchange exchange = exchange(raw);

        assertRefusedByTransport(exchange);
    }

    @Test
    @DisplayName("TE.TE — Content-Length beside a coding the transport does not read as chunked is rejected by the gate")
    void obfuscatedTransferEncodingTheTransportIgnores() throws Exception {
        String raw = contentLengthBesideTransferEncoding("Transfer-Encoding: xchunked" + CRLF);

        Exchange exchange = exchange(raw);

        assertRejectedByGate(exchange);
    }

    @Test
    @DisplayName("TE.TE — a sole Transfer-Encoding that is not exactly chunked is rejected by the gate")
    void soleTransferEncodingNotExactlyChunked() throws Exception {
        String raw = "POST /echo/te-te HTTP/1.1" + CRLF + host()
                + "Transfer-Encoding: identity, chunked" + CRLF + CRLF
                + "5" + CRLF + "hello" + CRLF + "0" + CRLF + CRLF;

        Exchange exchange = exchange(raw);

        assertRejectedByGate(exchange);
    }

    @Test
    @DisplayName("CL.0 — a Connection token naming Content-Length is rejected by the gate")
    void connectionStripsContentLength() throws Exception {
        String smuggled = smuggled();
        String raw = "POST /echo/cl-0 HTTP/1.1" + CRLF + host()
                + "Content-Length: " + smuggled.length() + CRLF
                + "Connection: Content-Length" + CRLF + CRLF + smuggled;

        Exchange exchange = exchange(raw);

        assertRejectedByGate(exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5\nhello\r\n0\r\n\r\n", "5\r\nhello\n0\r\n\r\n", "5\r\nhello\r\n0\n\n"})
    @DisplayName("a bare-LF chunk terminator is refused by the transport")
    void bareLineFeedChunkTerminator(String chunkedBody) throws Exception {
        String raw = "POST /echo/bare-lf HTTP/1.1" + CRLF + host()
                + "Transfer-Encoding: chunked" + CRLF + CRLF + chunkedBody + smuggled();

        Exchange exchange = exchange(raw);

        // The head is well-formed, so the request may already be on its way upstream when the chunk
        // framing fails. What the transport owes is that the connection ends there: nothing is
        // answered, the upstream never reads that request to its end, and nothing behind it is read.
        // What the edge owes on top is that a request it did dispatch is reset, not left open.
        boolean dispatched = upstreamStarted.contains(BARE_LF_POST);
        awaitUpstreamReset();
        assertAll("bare-LF chunk terminator",
                () -> assertEquals(List.of(), exchange.statuses(), exchange.raw()),
                () -> assertEquals(List.of(DRAIN + " []"), upstreamCompleted,
                        "the upstream must not read a request with broken chunk framing to its end"),
                () -> assertFalse(upstreamStarted.stream().anyMatch(seen -> seen.contains(SMUGGLED)),
                        () -> "the smuggled request reached the upstream: " + upstreamStarted),
                () -> assertTrue(List.of(List.of(DRAIN), List.of(BARE_LF_POST, DRAIN)).contains(upstreamStarted),
                        () -> "the upstream saw more than the one legitimate request: " + upstreamStarted),
                () -> assertEquals(dispatched ? List.of(BARE_LF_POST) : List.of(), upstreamAborted,
                        "a dispatched request whose inbound framing failed must be reset at the upstream"),
                () -> {
                    if (dispatched) {
                        assertUpstreamConnectionRetired();
                    }
                });
    }

    @Test
    @DisplayName("an inbound framing failure after dispatch resets the upstream request and retires its connection")
    void inboundFramingFailureAfterDispatchResetsTheUpstreamRequest() throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(frontPort, LoopbackHost.ADDRESS),
                "the raw TCP client to connect to the edge");
        CompletableFuture<String> closed = receivedUntilClosed(socket);
        // A well-framed head and first chunk: the edge dispatches, and the upstream sees the head.
        socket.write("POST /echo/bare-lf HTTP/1.1" + CRLF + host()
                + "Transfer-Encoding: chunked" + CRLF + CRLF + "5" + CRLF + "hello" + CRLF);
        Awaits.until(() -> upstreamStarted.contains(BARE_LF_POST),
                "the upstream to receive the head of the dispatched request", Awaits.CONNECT_CEILING_SECONDS);

        // Only now does the chunk framing break — a bare-LF terminator — so the failure is certain to
        // arrive after dispatch began.
        socket.write("5" + CRLF + "hello\n0" + CRLF + CRLF + smuggled());
        String received = Awaits.connect(closed, "the edge to close the corpus connection");
        awaitUpstreamReset();
        String drained = drain();

        assertAll("inbound framing failure after dispatch",
                () -> assertEquals(List.of(), statusesOf(received), received),
                () -> assertEquals(List.of(BARE_LF_POST), upstreamAborted,
                        "the dispatched request must be reset at the upstream"),
                () -> assertEquals(List.of(BARE_LF_POST, DRAIN), upstreamStarted, drained),
                () -> assertEquals(List.of(DRAIN + " []"), upstreamCompleted, drained),
                this::assertUpstreamConnectionRetired);
    }

    @Test
    @DisplayName("a client that disconnects mid-upload is reported once as INFO ApiSheriff-22, never as the security WARN")
    void clientDisconnectMidUploadIsNotASecurityWarning() throws Exception {
        // Act — two uploads on the same route, each abandoned by its client after dispatch began
        abandonUploadAfterDispatch(1);
        abandonUploadAfterDispatch(2);

        // Assert
        assertAll("an abandoned upload",
                () -> assertEquals(2.0, errorCount("INBOUND_BODY_ABORTED"),
                        "every abandoned upload is counted under its own event"),
                () -> assertEquals(0.0, errorCount("SECURITY_FILTER_VIOLATION"),
                        "an abandoned upload must not count as a security filter violation"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, "ApiSheriff-22"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                        "Request body on route 'echo' did not arrive whole (body-stream-failed)"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                        "Request body on route 'echo' did not arrive whole again"),
                () -> LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, "ApiSheriff-100"));
    }

    /**
     * Starts a chunked upload over a raw socket, waits until the edge has dispatched it and the
     * upstream has seen its head, then closes the client connection and waits until the edge has
     * accounted for the {@code occurrence}-th aborted inbound body.
     */
    private void abandonUploadAfterDispatch(int occurrence) throws Exception {
        String path = "/abandoned-" + occurrence;
        NetSocket socket = Awaits.connect(netClient.connect(frontPort, LoopbackHost.ADDRESS),
                "the raw TCP client to connect to the edge");
        socket.write("POST /echo" + path + " HTTP/1.1" + CRLF + host()
                + "Transfer-Encoding: chunked" + CRLF + CRLF + "5" + CRLF + "hello" + CRLF);
        Awaits.until(() -> upstreamStarted.contains("POST " + path),
                "the upstream to receive the head of the dispatched upload", Awaits.CONNECT_CEILING_SECONDS);

        Awaits.connect(socket.close(), "the client to drop its connection mid-upload");

        Awaits.until(() -> errorCount("INBOUND_BODY_ABORTED") == occurrence,
                "the edge to account for the abandoned upload", Awaits.CONNECT_CEILING_SECONDS);
    }

    /** The {@code sheriff_errors_total} count of the default edge's {@code echo} route for one event. */
    private double errorCount(String event) {
        return errorCount(meterRegistry, event);
    }

    /** The {@code sheriff_errors_total} count of the {@code echo} route for one event, {@code 0} when absent. */
    private static double errorCount(SimpleMeterRegistry registry, String event) {
        var counter = registry.find(SheriffMetrics.ERRORS_TOTAL).tags("route", "echo", "event", event).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("a request pipelined behind a gate rejection is never processed")
    void requestPipelinedBehindGateRejection() throws Exception {
        String body = Generators.letterStrings(4, 12).next();
        // One write: a request the gate rejects, and a well-framed, routed request directly behind it.
        String raw = "GET /echo/get-body HTTP/1.1" + CRLF + host()
                + "Content-Length: " + body.length() + CRLF + CRLF + body
                + "GET /echo/pipelined HTTP/1.1" + CRLF + host() + CRLF;

        Exchange exchange = exchangeVerbatim(raw);

        // No sentinel asks for the close here: the connection ending at all is the edge retiring it.
        assertRejectedByGate(exchange);
    }

    @Test
    @DisplayName("HTTP/2 — a gate rejection ends only its own stream; a sibling stream and the connection carry on")
    void gateRejectionOnHttp2EndsOnlyTheOffendingStream() throws Exception {
        // Arrange — one prior-knowledge HTTP/2 connection carrying a sibling stream whose body is
        // still open when the rejected stream arrives.
        String siblingBody = Generators.letterStrings(4, 12).next();
        String rejectedPart = Generators.letterStrings(4, 12).next();
        HttpClient http2Client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        try {
            HttpClientRequest sibling = Awaits.connect(http2Client.request(io.vertx.core.http.HttpMethod.POST,
                    frontPort, LoopbackHost.ADDRESS, "/echo/sibling"), "the sibling stream to open");
            HttpConnection connection = sibling.connection();
            Awaits.connect(sibling.setChunked(true).write(siblingBody), "the sibling stream to send its body");
            Awaits.until(() -> upstreamStarted.contains(SIBLING_POST),
                    "the upstream to receive the head of the sibling request", Awaits.CONNECT_CEILING_SECONDS);

            // Act — a GET the gate rejects, on the same connection, declaring twice the body it sends
            // so its stream is still open when the rejection is written.
            HttpClientRequest rejected = Awaits.connect(http2Client.request(io.vertx.core.http.HttpMethod.GET,
                    frontPort, LoopbackHost.ADDRESS, "/echo/get-body"), "the rejected stream to open");
            HttpConnection rejectedConnection = rejected.connection();
            CompletableFuture<Throwable> rejectedStreamEnded = new CompletableFuture<>();
            rejected.exceptionHandler(rejectedStreamEnded::complete);
            rejected.putHeader("Content-Length", String.valueOf(2 * rejectedPart.length()));
            Awaits.connect(rejected.write(rejectedPart), "the rejected stream to send part of its body");
            // The body is subscribed inside the response callback: the edge resets the stream right after
            // the 400 is written, and a body subscribed only after that reset is processed is discarded.
            Map.Entry<HttpClientResponse, String> answered = Awaits.connect(rejected.response()
                            .compose(response -> response.body().map(body -> Map.entry(response, body.toString()))),
                    "the rejection and its body to arrive");
            HttpClientResponse rejection = answered.getKey();
            String rejectionBody = answered.getValue();
            Throwable streamEnd = Awaits.connect(rejectedStreamEnded, "the edge to reset the rejected stream");

            Awaits.connect(sibling.end(), "the sibling stream to end its body");
            HttpClientResponse siblingAnswer = Awaits.connect(sibling.response(), "the sibling to be answered");
            Awaits.connect(siblingAnswer.body(), "the sibling response body to arrive");
            HttpClientRequest after = Awaits.connect(http2Client.request(io.vertx.core.http.HttpMethod.GET,
                    frontPort, LoopbackHost.ADDRESS, "/echo/after"), "a further stream to open");
            HttpConnection afterConnection = after.connection();
            HttpClientResponse afterAnswer = Awaits.connect(after.send(), "the further stream to be answered");
            Awaits.connect(afterAnswer.body(), "the further response body to arrive");
            awaitUpstreamSettled();

            // Assert
            assertAll("HTTP/2 stream-scoped rejection",
                    () -> assertEquals(HttpVersion.HTTP_2, rejection.version()),
                    () -> assertEquals(400, rejection.statusCode(), rejectionBody),
                    () -> assertTrue(rejectionBody.contains(PROBLEM_JSON_400), rejectionBody),
                    () -> assertNull(rejection.getHeader("Connection"),
                            "an HTTP/2 rejection must not carry the connection-specific header"),
                    () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                            "the rejected stream must be reset with NO_ERROR"),
                    () -> assertEquals(200, siblingAnswer.statusCode(),
                            "the sibling stream must complete normally"),
                    () -> assertEquals(200, afterAnswer.statusCode(),
                            "a further stream must succeed on the same connection"),
                    () -> assertSame(connection, rejectedConnection,
                            "the rejected stream must have shared the sibling's connection"),
                    () -> assertSame(connection, afterConnection,
                            "the further stream must reuse the connection the rejection was written on"),
                    () -> assertEquals(List.of(SIBLING_POST, "GET /after"), upstreamStarted,
                            "nothing of the rejected stream may reach the upstream"),
                    () -> assertEquals(List.of(SIBLING_POST + " [" + siblingBody + "]", "GET /after []"),
                            upstreamCompleted),
                    () -> assertEquals(List.of(), upstreamAborted));
        } finally {
            Awaits.teardown(http2Client.close(), "the HTTP/2 client to close");
        }
    }

    @Test
    @DisplayName("opt-in unset — a GET carrying a Content-Length body is rejected 400")
    void getWithContentLengthBodyIsRejectedWhenOptInIsUnset() throws Exception {
        String body = Generators.letterStrings(4, 12).next();
        String raw = "GET /echo/get-body HTTP/1.1" + CRLF + host()
                + "Content-Length: " + body.length() + CRLF + CRLF + body;

        Exchange exchange = exchange(raw);

        assertRejectedByGate(exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("HTTP/2 — a bodyless method never carries a body upstream: its stream alone is refused 400")
    void bodylessMethodNeverCarriesABodyUpstreamOnHttp2(String method) throws Exception {
        assertBodylessMethodRefused(frontPort, meterRegistry, method);
    }

    @Test
    @DisplayName("HTTP/2 — the GET-body opt-in admits only a declared-length body; any other GET body is refused 400")
    void optInAdmitsOnlyADeclaredLengthGetBodyOnHttp2() throws Exception {
        assertBodylessMethodRefused(startOptInEdge(), optInMeterRegistry, "GET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("HTTP/2 — a bodyless method whose stream ends with an empty frame is served, with no body upstream")
    void bodylessMethodEndingWithAnEmptyFrameIsServed(String method) throws Exception {
        HttpClientRequest request = open(client(HttpVersion.HTTP_2), frontPort, method, "/echo/empty-end");
        request.setChunked(true);
        Awaits.connect(request.sendHead(), "the request head to be sent");

        Answer answer = Awaits.connect(request.end().compose(sent -> answerOf(request)), "the request to be answered");
        awaitUpstreamSettled();

        String label = method + " /empty-end";
        assertAll("a bodyless method ending with an empty frame",
                () -> assertEquals(200, answer.response().statusCode(), answer.body()),
                () -> assertEquals(List.of(label), upstreamStarted),
                () -> assertEquals(List.of(label + " []"), upstreamCompleted),
                () -> assertEquals(0L, upstreamBodyBytes(label), "no body byte may reach the upstream"),
                () -> assertEquals(List.of(), upstreamAborted));
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    @DisplayName("opt-in set — a GET declaring its length reaches the upstream framed by exactly that length")
    void optInGetIsForwardedFramedByItsDeclaredLength(HttpVersion version) throws Exception {
        int optInPort = startOptInEdge();
        String body = Generators.letterStrings(4, 12).next();

        Answer answer = sendDeclaredLength(client(version), optInPort, "GET", "/echo/get-declared", body);

        assertForwardedFramedByLength(answer, "GET /get-declared", body);
    }

    @ParameterizedTest
    @CsvSource({"POST, HTTP_1_1", "POST, HTTP_2", "PUT, HTTP_1_1", "PUT, HTTP_2"})
    @DisplayName("a request declaring its length reaches the upstream framed by exactly that length")
    void declaredLengthRequestIsForwardedFramedByItsLength(String method, HttpVersion version) throws Exception {
        String body = Generators.letterStrings(4, 12).next();

        Answer answer = sendDeclaredLength(client(version), frontPort, method, "/echo/declared", body);

        assertForwardedFramedByLength(answer, method + " /declared", body);
    }

    @Test
    @DisplayName("a POST declaring a zero length reaches the upstream framed as Content-Length: 0, with no body")
    void zeroDeclaredLengthIsForwardedFramedAsZero() throws Exception {
        Answer answer = sendDeclaredLength(client(HttpVersion.HTTP_1_1), frontPort, "POST", "/echo/zero", "");

        assertForwardedFramedByLength(answer, "POST /zero", "");
    }

    @ParameterizedTest
    @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
    @DisplayName("a POST declaring no length keeps the transport-chosen framing upstream, body intact")
    void undeclaredLengthKeepsTheTransportFraming(HttpVersion version) throws Exception {
        String body = Generators.letterStrings(4, 12).next();
        HttpClientRequest request = open(client(version), frontPort, "POST", "/echo/undeclared");
        request.setChunked(true);
        Awaits.connect(request.write(body), "the request body to be sent");

        Answer answer = Awaits.connect(request.end().compose(sent -> answerOf(request)), "the request to be answered");
        awaitUpstreamSettled();

        assertAll("a POST declaring no length",
                () -> assertEquals(200, answer.response().statusCode(), answer.body()),
                () -> assertEquals(List.of("POST /undeclared [" + body + "]"), upstreamCompleted),
                () -> assertEquals(new Framing(null, "chunked"), upstreamFraming.get("POST /undeclared"),
                        "a body without a declared length keeps the framing the transport chooses"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    @DisplayName("HTTP/2 — a body longer than its declared length never reaches the upstream beyond that length")
    void bodyLongerThanItsDeclaredLengthIsRefused(String method) throws Exception {
        int optInPort = startOptInEdge();
        int declared = 3;
        String body = Generators.letterStrings(8, 16).next();
        HttpClientRequest request = open(client(HttpVersion.HTTP_2), optInPort, method, "/echo/overlong");
        CompletableFuture<Object> outcome = new CompletableFuture<>();
        request.exceptionHandler(outcome::complete);
        request.response().onSuccess(response -> outcome.complete(response.statusCode()))
                .onFailure(outcome::complete);
        request.putHeader(CONTENT_LENGTH, String.valueOf(declared));

        request.end(body);
        Object refusal = Awaits.connect(outcome, "the request to be refused");
        awaitUpstreamSettled();

        String label = method + " /overlong";
        assertAll("a body longer than its declared length",
                () -> assertTrue(refusal instanceof StreamResetException
                                || refusal instanceof Integer status && status >= 400,
                        () -> "the request must be refused, not served: " + refusal),
                () -> assertTrue(upstreamBodyBytes(label) <= declared,
                        () -> "a byte beyond the declared length reached the upstream: " + upstreamBodyBytes(label)),
                () -> assertFalse(upstreamCompleted.contains(label + " [" + body + "]"),
                        "the upstream must never read the overlong body to its end"));
    }

    @Test
    @DisplayName("HTTP/2 — opt-in set, a HEAD declaring a body is still refused 400 and only its stream ends")
    void headWithDeclaredLengthIsRefusedEvenWithTheOptIn() throws Exception {
        int optInPort = startOptInEdge();
        String part = Generators.letterStrings(4, 12).next();
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest refused = open(http2Client, optInPort, "HEAD", "/echo/head-declared");
        CompletableFuture<Throwable> streamEnded = new CompletableFuture<>();
        refused.exceptionHandler(streamEnded::complete);
        // Declaring twice the body it sends keeps the stream open when the rejection is written.
        refused.putHeader(CONTENT_LENGTH, String.valueOf(2 * part.length()));
        Awaits.connect(refused.write(part), "the refused stream to send part of its body");

        Answer answer = Awaits.connect(answerOf(refused), "the rejection to arrive");
        Throwable streamEnd = Awaits.connect(streamEnded, "the edge to end the refused stream");
        Answer after = sendAfter(http2Client, optInPort);
        awaitUpstreamSettled();

        assertAll("a HEAD declaring a body",
                () -> assertEquals(400, answer.response().statusCode()),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                        "the refused stream must be reset with NO_ERROR"),
                () -> assertEquals(200, after.response().statusCode(), "a further stream must succeed"),
                () -> assertSame(refused.connection(), after.connection(),
                        "the further stream must reuse the connection the rejection was written on"),
                () -> assertEquals(List.of(AFTER_GET), upstreamStarted,
                        "nothing of the refused stream may reach the upstream"));
    }

    @Test
    @DisplayName("HTTP/2 — a body byte on a bodyless method after the response started resets the stream")
    void bodyByteAfterTheResponseStartedResetsTheStream() throws Exception {
        HttpClientRequest request = open(client(HttpVersion.HTTP_2), frontPort, "GET", "/echo/streaming");
        request.exceptionHandler(ignored -> {
            // The reset reaches the request side as well; the response side is what is asserted.
        });
        CompletableFuture<@Nullable Throwable> responseEnded = new CompletableFuture<>();
        io.vertx.core.Future<HttpClientResponse> head = request.response().onSuccess(response -> {
            response.handler(chunk -> {
            });
            response.exceptionHandler(responseEnded::complete);
            response.endHandler(end -> responseEnded.complete(null));
        });
        request.setChunked(true);
        Awaits.connect(request.sendHead(), "the request head to be sent");
        HttpClientResponse response = Awaits.connect(head, "the response head to arrive");

        Awaits.connect(request.write(Generators.letterStrings(1, 4).next()), "the body byte to be sent");
        Throwable responseEnd = Awaits.connect(responseEnded, "the edge to end the response stream");
        Awaits.until(() -> errorCount(SECURITY_FILTER_VIOLATION) >= 1.0,
                "the edge to meter the refused body", Awaits.CONNECT_CEILING_SECONDS);
        awaitUpstreamSettled();

        assertAll("a body byte after the response started",
                () -> assertEquals(200, response.statusCode()),
                () -> assertInstanceOf(StreamResetException.class, responseEnd,
                        "the response stream must be reset, never cleanly ended"),
                () -> assertEquals(List.of("GET /streaming"), upstreamStarted),
                () -> assertEquals(List.of("GET /streaming []"), upstreamCompleted,
                        "the upstream request must have carried no body"),
                () -> assertEquals(0L, upstreamBodyBytes("GET /streaming")),
                () -> assertEquals(1.0, errorCount(SECURITY_FILTER_VIOLATION),
                        "the refused body is metered once"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, "ApiSheriff-100"));
    }

    /**
     * Sends a bodyless-method request over HTTP/2 whose stream carries body bytes no header declares,
     * to a path the stub upstream never answers, and asserts the verdict: a {@code 400} on that stream
     * alone, the stream then ended with {@code NO_ERROR}, the connection still serving a further
     * stream, no body byte at the upstream, and the refusal metered once.
     */
    private void assertBodylessMethodRefused(int port, SimpleMeterRegistry registry, String method) throws Exception {
        String path = HELD + "-" + method.toLowerCase(Locale.ROOT);
        String label = method + " " + path;
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest refused = open(http2Client, port, method, "/echo" + path);
        CompletableFuture<Throwable> streamEnded = new CompletableFuture<>();
        refused.exceptionHandler(streamEnded::complete);
        refused.setChunked(true);
        Awaits.connect(refused.write(Generators.letterStrings(4, 12).next()), "the stream to send body bytes");

        Answer answer = Awaits.connect(answerOf(refused), "the rejection to arrive");
        Throwable streamEnd = Awaits.connect(streamEnded, "the edge to end the refused stream");
        Answer after = sendAfter(http2Client, port);
        awaitUpstreamSettled();

        assertAll("a body on a bodyless method",
                () -> assertEquals(HttpVersion.HTTP_2, answer.response().version()),
                () -> assertEquals(400, answer.response().statusCode(), answer.body()),
                () -> assertTrue("HEAD".equals(method) || answer.body().contains(PROBLEM_JSON_400), answer.body()),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, streamEnd).getCode(),
                        "the refused stream must be reset with NO_ERROR"),
                () -> assertEquals(200, after.response().statusCode(), "a further stream must succeed"),
                () -> assertSame(refused.connection(), after.connection(),
                        "the further stream must reuse the connection the rejection was written on"),
                // The refusal may land before or after the bodyless upstream request was sent.
                () -> assertTrue(List.of(List.of(AFTER_GET), List.of(label, AFTER_GET)).contains(upstreamStarted),
                        () -> "the upstream saw more than the bodyless request: " + upstreamStarted),
                () -> assertEquals(0L, upstreamBodyBytes(label), "no body byte may reach the upstream"),
                () -> assertEquals(List.of(), upstreamAborted),
                () -> assertEquals(1.0, errorCount(registry, SECURITY_FILTER_VIOLATION),
                        "the refusal is metered once"));
    }

    /** Sends {@code body} with a declared {@code Content-Length} and returns the answer. */
    private Answer sendDeclaredLength(HttpClient client, int port, String method, String path, String body)
            throws Exception {
        HttpClientRequest request = open(client, port, method, path);
        request.putHeader(CONTENT_LENGTH, String.valueOf(body.length()));
        Answer answer = Awaits.connect(request.end(body).compose(sent -> answerOf(request)),
                "the request to be answered");
        awaitUpstreamSettled();
        return answer;
    }

    /**
     * The verdict of a request forwarded framed by its declared length: answered {@code 200}, the body
     * read whole by the upstream, which received it with {@code Content-Length} equal to the declared
     * value and no {@code Transfer-Encoding}.
     */
    private void assertForwardedFramedByLength(Answer answer, String label, String body) {
        assertAll("a request forwarded framed by its declared length",
                () -> assertEquals(200, answer.response().statusCode(), answer.body()),
                () -> assertEquals(List.of(label + " [" + body + "]"), upstreamCompleted),
                () -> assertEquals(new Framing(String.valueOf(body.length()), null), upstreamFraming.get(label),
                        "the upstream must receive the declared length, and never a chunked body"));
    }

    /** Sends a well-framed GET on {@code client}'s connection to {@code port} and returns its answer. */
    private Answer sendAfter(HttpClient client, int port) throws Exception {
        HttpClientRequest after = open(client, port, "GET", "/echo/after");
        return Awaits.connect(after.end().compose(sent -> answerOf(after)), "a further stream to be answered");
    }

    /** The answer to {@code request}: its response, with the body read whole. */
    private static io.vertx.core.Future<Answer> answerOf(HttpClientRequest request) {
        return request.response().compose(response -> response.body()
                .map(body -> new Answer(response, body.toString(), request.connection())));
    }

    /** Opens a request on {@code client} to the edge at {@code port}. */
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

    /** How many body bytes of the request labelled {@code label} reached the upstream; {@code 0} when it never arrived. */
    private long upstreamBodyBytes(String label) {
        AtomicLong bytes = upstreamBodyBytes.get(label);
        return bytes == null ? 0L : bytes.get();
    }

    /**
     * The verdict of an entry the gateway's own framing gate rejects: the only answer on the
     * connection is the gateway's {@code 400} problem document, it announces
     * {@code Connection: close}, nothing behind it on that connection is answered, and the upstream
     * saw nothing of it.
     */
    private void assertRejectedByGate(Exchange exchange) {
        assertAll("rejected by the framing gate",
                () -> assertEquals(List.of(400), exchange.statuses(), exchange.raw()),
                () -> assertEquals(1, occurrences(exchange.raw(), PROBLEM_JSON_400), exchange.raw()),
                () -> assertTrue(exchange.raw().toLowerCase(Locale.ROOT).contains(CONNECTION_CLOSE),
                        () -> "the rejection must announce the connection's retirement: " + exchange.raw()),
                () -> assertUpstreamSawOnlyTheDrain(exchange));
    }

    /** Waits, on the teardown tier, until every request the upstream started has ended or failed. */
    private void awaitUpstreamReset() throws Exception {
        Awaits.until(() -> upstreamStarted.size() == upstreamCompleted.size() + upstreamAborted.size(),
                "the upstream request to be reset after the inbound framing failure",
                Awaits.TEARDOWN_CEILING_SECONDS);
    }

    /**
     * The upstream connection that carried the request with broken inbound framing is closed and is
     * not the one the follow-up drain request arrived on.
     */
    private void assertUpstreamConnectionRetired() throws Exception {
        HttpConnection carrier = upstreamConnections.get(BARE_LF_POST);
        Awaits.until(() -> upstreamClosedConnections.contains(carrier),
                "the upstream connection of the reset request to close", Awaits.TEARDOWN_CEILING_SECONDS);
        assertNotSame(carrier, upstreamConnections.get(DRAIN),
                "the follow-up request must not arrive on the connection of the reset request");
    }

    /**
     * The verdict of an entry the HTTP transport refuses before the pipeline runs: one bare
     * {@code 400}, no problem document, and the connection retired — the pipelined sentinel is never
     * answered. A transport default that started admitting the shape would turn this red.
     */
    private void assertRefusedByTransport(Exchange exchange) {
        assertAll("refused by the transport",
                () -> assertEquals(List.of(400), exchange.statuses(), exchange.raw()),
                () -> assertEquals(0, occurrences(exchange.raw(), PROBLEM_JSON_400), exchange.raw()),
                () -> assertUpstreamSawOnlyTheDrain(exchange));
    }

    private void assertUpstreamSawOnlyTheDrain(Exchange exchange) throws Exception {
        awaitUpstreamSettled();
        assertAll("upstream request count",
                () -> assertFalse(upstreamStarted.stream().anyMatch(seen -> seen.contains(SMUGGLED)),
                        () -> "the smuggled request reached the upstream: " + upstreamStarted + " / " + exchange.raw()),
                () -> assertEquals(List.of(DRAIN), upstreamStarted, exchange.raw()),
                () -> assertEquals(List.of(), upstreamAborted, exchange.raw()));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * A POST carrying a {@code Content-Length} that covers a terminated chunked body <em>and</em> the
     * smuggled request behind it, beside the given {@code Transfer-Encoding} header lines: a parser
     * that framed by {@code Content-Length} would read one request, one that framed by the transfer
     * coding would read two.
     */
    private String contentLengthBesideTransferEncoding(String transferEncodingLines) {
        String body = "0" + CRLF + CRLF + smuggled();
        return "POST /echo/te-te HTTP/1.1" + CRLF + host()
                + "Content-Length: " + body.length() + CRLF
                + transferEncodingLines + CRLF + body;
    }

    private String host() {
        return "Host: localhost:" + frontPort + CRLF;
    }

    private String smuggled() {
        return "GET /echo/" + SMUGGLED + " HTTP/1.1" + CRLF + host() + CRLF;
    }

    /**
     * Writes {@code raw} followed by the closing sentinel, awaits the server-side close, then sends
     * the drain request over a fresh connection so the upstream's request list is read only after
     * anything the corpus entry set in motion has had its turn.
     */
    private Exchange exchange(String raw) throws Exception {
        String sentinel = "GET /unrouted/sentinel HTTP/1.1" + CRLF + host() + "Connection: close" + CRLF + CRLF;
        return exchangeVerbatim(raw + sentinel);
    }

    /**
     * Writes exactly {@code raw} — no sentinel — awaits the server-side close, then drains. The close
     * is then the edge's own doing, since nothing in the bytes asked for it.
     */
    private Exchange exchangeVerbatim(String raw) throws Exception {
        String received = Awaits.connect(sendUntilClosed(raw), "the edge to close the corpus connection");
        drain();
        return new Exchange(statusesOf(received), received);
    }

    /** Sends the well-framed drain request over a fresh connection and returns what it received. */
    private String drain() throws Exception {
        String drained = Awaits.connect(
                sendUntilClosed("GET /echo/drain HTTP/1.1" + CRLF + host() + "Connection: close" + CRLF + CRLF),
                "the drain request to be answered");
        assertEquals(List.of(200), statusesOf(drained), "the drain request must be proxied: " + drained);
        return drained;
    }

    /** Waits until every request the upstream received a head for has ended or failed. */
    private void awaitUpstreamSettled() throws Exception {
        Awaits.until(() -> upstreamStarted.size() == upstreamCompleted.size() + upstreamAborted.size(),
                "every request the upstream started to end or fail", Awaits.CONNECT_CEILING_SECONDS);
    }

    private static List<Integer> statusesOf(String received) {
        List<Integer> statuses = new ArrayList<>();
        Matcher matcher = STATUS_LINE.matcher(received);
        while (matcher.find()) {
            statuses.add(Integer.valueOf(matcher.group(1)));
        }
        return statuses;
    }

    /** Writes a verbatim byte sequence and completes with everything received once the connection closes. */
    private CompletableFuture<String> sendUntilClosed(String rawRequest) {
        CompletableFuture<String> closed = new CompletableFuture<>();
        netClient.connect(frontPort, LoopbackHost.ADDRESS)
                .onFailure(closed::completeExceptionally)
                .onSuccess(socket -> {
                    receivedUntilClosed(socket).thenAccept(closed::complete);
                    socket.write(rawRequest);
                });
        return closed;
    }

    /** Completes with everything {@code socket} received once the connection closes. */
    private static CompletableFuture<String> receivedUntilClosed(NetSocket socket) {
        CompletableFuture<String> closed = new CompletableFuture<>();
        Buffer received = Buffer.buffer();
        socket.handler(received::appendBuffer);
        socket.closeHandler(end -> closed.complete(received.toString()));
        // A reset after the edge has answered and retired the connection is an expected outcome; the
        // close handler above still settles the future with what arrived.
        socket.exceptionHandler(cause -> {
        });
        return closed;
    }

    /** Everything one corpus connection received: the status of each response, and the raw bytes. */
    private record Exchange(List<Integer> statuses, String raw) {
    }

    /** The response to one client request, its body read whole, and the connection it was sent on. */
    private record Answer(HttpClientResponse response, String body, HttpConnection connection) {
    }

    /** The framing headers a request arrived at the stub upstream with, each {@code null} when absent. */
    private record Framing(@Nullable String contentLength, @Nullable String transferEncoding) {
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
