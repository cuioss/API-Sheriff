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
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The request-smuggling corpus, driven over a live Vert.x edge against a loopback stub upstream.
 * <p>
 * Every HTTP/1.1 entry is written as raw bytes over a plain TCP socket, so malformed framing reaches
 * the edge exactly as an attacker would send it — an HTTP client would normalise or refuse each of
 * these shapes before a byte left the process. The one HTTP/2 entry drives a prior-knowledge HTTP/2
 * client instead, because its subject is what a rejection does to the streams sharing a connection,
 * not a byte shape. Each entry asserts two things: the status of every response
 * the edge wrote on that connection, and the exact list of requests the stub upstream saw.
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
    /** The response header, lower-cased, by which the edge announces it is retiring the connection. */
    private static final String CONNECTION_CLOSE = "connection: close";
    /** The gateway's own framing rejection: its problem document names the status it was sent with. */
    private static final String PROBLEM_JSON_400 = "\"status\":400";
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 (\\d{3}) ");

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private NetClient netClient;
    private int frontPort;
    /** The registry the edge under test meters into. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

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

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamServer = Awaits.connect(vertx.createHttpServer().connectionHandler(
                connection -> connection.closeHandler(closed -> upstreamClosedConnections.add(connection))
        ).requestHandler(request -> {
            String label = request.method() + " " + request.path();
            upstreamConnections.put(label, request.connection());
            upstreamStarted.add(label);
            request.body().onComplete(read -> {
                if (read.succeeded()) {
                    upstreamCompleted.add(label + " [" + read.result() + "]");
                    request.response().end("ok");
                } else {
                    upstreamAborted.add(label);
                }
            });
        }).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        // No security_defaults block at all: allow_get_with_content_length_body is unset.
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).build();
        RouteTable routes = new RouteTable(List.of(ResolvedRoute.builder()
                .id("echo")
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/echo").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET, HttpMethod.POST))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                .build()));
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routes, gatewayConfig, new SingletonInstance<>(tokenValidator),
                vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                new SheriffMetrics(meterRegistry), BffRuntime.inert(),
                EgressTrustProfiles.unconsulted(), PortalEndpoint.inert());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = Awaits.connect(
                vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
        frontPort = frontServer.actualPort();
        netClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(netClient.close(), "the raw TCP client to close");
        Awaits.teardown(frontServer.close(), "the edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
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

    /** The {@code sheriff_errors_total} count of the {@code echo} route for one event, {@code 0} when absent. */
    private double errorCount(String event) {
        var counter = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL).tags("route", "echo", "event", event).counter();
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
