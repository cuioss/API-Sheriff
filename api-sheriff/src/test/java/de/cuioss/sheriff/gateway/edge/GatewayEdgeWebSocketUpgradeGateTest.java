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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A request on a {@code protocol: websocket} route that is no valid WebSocket opening handshake is
 * answered by the edge itself, promptly, and never dials the upstream. Every case drives a real request
 * through {@link GatewayEdgeRoute} against a stub upstream that counts every handshake and request it
 * receives, so a refusal that still dialed is visible as a non-zero count.
 * <p>
 * The edge runs with two general admission permits and a relay sub-budget of one. A refusal that kept
 * the general permit it took would therefore leave too few for the valid relay and the plain request
 * that follow it, which is how each case proves the permit came back.
 * <p>
 * Two further properties are pinned against the real stack. A refusal that leaves a declared body of up
 * to 8192 bytes unread — one it does not retire the connection for — must leave the keep-alive connection
 * serving the request that follows as itself. And the gate must upgrade exactly the {@code Connection}
 * and {@code Upgrade} header shapes vertx-core itself upgrades: the stub upstream is a plain vertx-core
 * server, so each shape is asked of it directly first, and the edge is held to its verdict.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — a request on a WebSocket route that is no valid upgrade is refused before any upstream dial")
@Tag("isolated-fork")
class GatewayEdgeWebSocketUpgradeGateTest {

    private static final String ORIGIN = "https://gw.example.com";
    private static final String WS_PATH = "/ws";
    private static final String WS_URI = WS_PATH + "/room";
    /** How long a refusal may take to arrive; far below any timeout a stalled dial or upgrade would run into. */
    private static final long PROMPT_ANSWER_SECONDS = 5;
    /** The refusals each case sends: more than the two general permits, so one kept permit would starve the rest. */
    private static final int REFUSED_ATTEMPTS = 3;
    private static final String SAMPLE_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    /** One byte above the 8192 handshake body bytes vertx-core 4.5.34 buffers. */
    private static final int OVER_BOUND_BODY = 8193;
    /** The prefix of the {@code profile: minimal} WebSocket route, whose query is not validated before the gate. */
    private static final String MINIMAL_WS_PATH = "/minimal";
    /**
     * A request URI {@code java.net.URI} cannot parse — {@code |} is no legal query character — on the
     * minimal-profile route: on the default route the security filter refuses that query first.
     */
    private static final String UNPARSEABLE_URI = MINIMAL_WS_PATH + "/room?a|b";
    /** The path a request following a refusal on the same connection is sent to: no route matches it. */
    private static final String UNMATCHED_PATH = "/unmatched";
    /** The status of {@link #UNMATCHED_PATH}: the request answered as itself, not as bytes of a body before it. */
    private static final int UNMATCHED_STATUS = 404;
    /**
     * How long a request following a refusal on the same connection may take to be answered, or the edge
     * to close that connection: room for the response-timing floor of its unrouted {@code 404}, far below
     * the forever an unread body left in front of it would hold it.
     */
    private static final long FOLLOW_UP_SECONDS = 10;
    /** The status of a completed opening handshake (RFC 6455 §4.2.2). */
    private static final int SWITCHING_PROTOCOLS = 101;
    private static final String CRLF = "\r\n";
    private static final String HTTP_1_1 = "HTTP/1.1 ";
    private static final String HSTS = "Strict-Transport-Security";
    private static final String CSP = "Content-Security-Policy";
    private static final int ROUTE_HSTS_MAX_AGE = 31536000;
    private static final String ROUTE_POLICY = "default-src 'self'";

    /** Every WebSocket handshake and every plain request the stub upstream received. */
    private final AtomicInteger upstreamContacts = new AtomicInteger();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstream;
    private HttpServer front;
    private HttpClient httpClient;
    private HttpClient http2Client;
    private WebSocketClient wsClient;
    private NetClient netClient;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstream = Awaits.connect(vertx.createHttpServer()
                .webSocketHandler(ws -> {
                    upstreamContacts.incrementAndGet();
                    ws.textMessageHandler(ws::writeTextMessage);
                })
                .requestHandler(request -> {
                    upstreamContacts.incrementAndGet();
                    request.response().setStatusCode(400).end();
                })
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
        // The GET-body opt-in lets a handshake declaring a Content-Length past the framing gate, so the
        // upgrade gate's body bound is reachable; only the over-bound cases declare a length, and none
        // sends a body byte.
        front = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .routes(webSocketRoute(upstream.actualPort()), minimalProfileWebSocketRoute(upstream.actualPort()))
                .gatewayConfig(ConfigFixtures.gateway()
                        .securityDefaults(SecurityDefaultsConfig.builder().allowGetWithContentLengthBody(Boolean.TRUE).build())
                        .build())
                .hardening(new EdgeHardeningOptions(new EdgeHardeningConfig(2, 1)))
                .serve();
        httpClient = vertx.createHttpClient();
        http2Client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        wsClient = vertx.createWebSocketClient();
        netClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(netClient.close(), "the raw TCP client to close");
        Awaits.teardown(wsClient.close(), "the WebSocket client to close");
        Awaits.teardown(http2Client.close(), "the HTTP/2 client to close");
        Awaits.teardown(httpClient.close(), "the HTTP client to close");
        Awaits.teardown(front.close(), "the edge front server to close");
        Awaits.teardown(upstream.close(), "the stub upstream to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("an HTTP/2 GET is answered 426 without Upgrade, dials nothing and returns its admission permit")
    void http2GetIsRefusedBeforeAnyDial() throws Exception {
        List<Answer> answers = new ArrayList<>();
        for (int attempt = 0; attempt < REFUSED_ATTEMPTS; attempt++) {
            answers.add(http2Get());
        }

        assertAll("every HTTP/2 GET is refused by the edge",
                answers.stream().map(answer -> (Executable) () -> assertAll("HTTP/2 refusal",
                        () -> assertEquals(426, answer.status(), "an HTTP/2 request cannot open a WebSocket"),
                        () -> assertNull(answer.headers().get("Upgrade"),
                                "Upgrade is a connection-specific field HTTP/2 forbids"))));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("an HTTP/1.1 GET without Upgrade is answered 426 naming websocket, dials nothing and returns its permit")
    void plainHttp11GetIsRefusedBeforeAnyDial() throws Exception {
        List<Answer> answers = new ArrayList<>();
        for (int attempt = 0; attempt < REFUSED_ATTEMPTS; attempt++) {
            answers.add(rawHttp11(List.of("Origin: " + ORIGIN)));
        }

        assertAll("every plain GET is refused by the edge",
                answers.stream().map(answer -> (Executable) () -> assertAll("plain GET refusal",
                        () -> assertEquals(426, answer.status(), "a plain GET is no WebSocket upgrade"),
                        () -> assertEquals("websocket", answer.headers().get("Upgrade"),
                                "the 426 names the protocol to switch to"))));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("an upgrade token on a later Connection line only, or websocket inside an Upgrade list, is answered 426 and dials nothing")
    void upgradeHeadersTheHandshakeRefusesAreRefusedBeforeAnyDial() throws Exception {
        List<String> laterConnectionLine = List.of("Upgrade: websocket", "Connection: keep-alive",
                "Connection: Upgrade", "Sec-WebSocket-Key: " + SAMPLE_KEY, "Sec-WebSocket-Version: 13",
                "Origin: " + ORIGIN);
        List<String> websocketInsideList = List.of("Upgrade: chat, websocket", "Connection: Upgrade",
                "Sec-WebSocket-Key: " + SAMPLE_KEY, "Sec-WebSocket-Version: 13", "Origin: " + ORIGIN);

        Answer laterLine = rawHttp11(laterConnectionLine);
        Answer insideList = rawHttp11(websocketInsideList);

        assertAll("both are refused by the edge, not by the HTTP stack after the upstream was dialled",
                () -> assertEquals(426, laterLine.status(), "Vert.x reads only the first Connection line"),
                () -> assertEquals("websocket", laterLine.headers().get("Upgrade"),
                        "the 426 names the protocol to switch to"),
                () -> assertEquals(426, insideList.status(),
                        "the handshake accepts websocket only as the whole Upgrade value"),
                () -> assertEquals("websocket", insideList.headers().get("Upgrade"),
                        "the 426 names the protocol to switch to"));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("an HTTP/1.1 upgrade with Sec-WebSocket-Version 8 is answered 426 naming version 13")
    void unsupportedVersionIsRefusedNamingVersion13() throws Exception {
        Answer answer = rawHttp11(handshake("8", SAMPLE_KEY));

        assertAll("the unsupported version is refused by the edge",
                () -> assertEquals(426, answer.status()),
                () -> assertEquals("13", answer.headers().get("Sec-WebSocket-Version"),
                        "the 426 names the one version the gateway speaks"));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("an HTTP/1.1 upgrade without Sec-WebSocket-Key is answered 400")
    void missingKeyIsRefused400() throws Exception {
        List<String> withoutKey = new ArrayList<>(handshake("13", SAMPLE_KEY));
        withoutKey.removeIf(line -> line.startsWith("Sec-WebSocket-Key"));

        Answer answer = rawHttp11(withoutKey);

        assertEquals(400, answer.status(), "a handshake without its key is malformed");
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a valid handshake sent with another method than GET is answered 405 Allow: GET, dials nothing and returns its permit")
    void nonGetHandshakeIsRefused405() throws Exception {
        List<Answer> answers = new ArrayList<>();
        for (String method : List.of("POST", "PUT", "DELETE")) {
            answers.add(rawHttp11(method, handshake("13", SAMPLE_KEY)));
        }

        assertAll("every non-GET handshake is refused by the edge, though the route allows every standard method",
                answers.stream().map(answer -> (Executable) () -> assertAll("non-GET refusal",
                        () -> assertEquals(405, answer.status(), "a WebSocket is opened by a GET only"),
                        () -> assertEquals("GET", answer.headers().get("Allow"),
                                "the 405 names GET as the one method the handshake allows"))));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a handshake declaring a body above 8192 bytes is answered 413 Connection: close, the edge closes the connection, dials nothing and returns its permit")
    void oversizedDeclaredBodyIsRefused413AndClosesTheConnection() throws Exception {
        List<String> oversized = new ArrayList<>(handshake("13", SAMPLE_KEY));
        oversized.add("Content-Length: " + OVER_BOUND_BODY);
        List<Answer> answers = new ArrayList<>();
        for (int attempt = 0; attempt < REFUSED_ATTEMPTS; attempt++) {
            answers.add(exchange("GET", WS_URI, oversized, true));
        }

        assertAll("every over-bound handshake body is refused by the edge before a byte of it is read",
                answers.stream().map(answer -> (Executable) () -> assertAll("413 refusal",
                        () -> assertEquals(413, answer.status(), "Vert.x buffers at most 8192 handshake body bytes"),
                        () -> assertEquals("close", answer.headers().get("Connection"),
                                "the unread body would frame the next request, so the connection is retired"))));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a 400 and a 426 of a request declaring a body above 8192 bytes keep their status, answer Connection: close and the edge closes the connection")
    void earlierRefusalOfAnOversizedDeclaredBodyClosesTheConnection() throws Exception {
        List<String> withoutKey = new ArrayList<>(handshake("13", SAMPLE_KEY));
        withoutKey.removeIf(line -> line.startsWith("Sec-WebSocket-Key"));
        withoutKey.add("Content-Length: " + OVER_BOUND_BODY);
        List<String> plainGet = List.of("Origin: " + ORIGIN, "Content-Length: " + OVER_BOUND_BODY);

        Answer badRequest = exchange("GET", WS_URI, withoutKey, true);
        Answer upgradeRequired = exchange("GET", WS_URI, plainGet, true);

        assertAll("an earlier refusal keeps its status and retires the connection whose body it never reads",
                () -> assertEquals(400, badRequest.status(), "the missing key is the first rule broken"),
                () -> assertEquals("close", badRequest.headers().get("Connection"),
                        "the unread body would frame the next request, so the connection is retired"),
                () -> assertEquals(426, upgradeRequired.status(), "the missing upgrade tokens are the first rule broken"),
                () -> assertEquals("websocket", upgradeRequired.headers().get("Upgrade"),
                        "the 426 still names the protocol to switch to"),
                () -> assertEquals("Upgrade, close", upgradeRequired.headers().get("Connection"),
                        "the 426 keeps its upgrade option beside close"));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a handshake whose request URI does not parse is answered 400, dials nothing and returns its permit")
    void unparseableRequestUriIsRefused400() throws Exception {
        List<Answer> answers = new ArrayList<>();
        for (int attempt = 0; attempt < REFUSED_ATTEMPTS; attempt++) {
            answers.add(exchange("GET", UNPARSEABLE_URI, handshake("13", SAMPLE_KEY), false));
        }

        assertAll("every handshake with an unparseable request URI is refused by the edge",
                answers.stream().map(answer -> (Executable) () -> assertEquals(400, answer.status(),
                        "Vert.x cannot compose the WebSocket location from an unparseable request URI")));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a 426 and a 400 carry the route's security headers beside their own, dial nothing and return their permits")
    void gateRefusalsCarryTheRouteSecurityHeaders() throws Exception {
        List<String> withoutKey = new ArrayList<>(handshake("13", SAMPLE_KEY));
        withoutKey.removeIf(line -> line.startsWith("Sec-WebSocket-Key"));

        Answer upgradeRequired = rawHttp11(List.of("Origin: " + ORIGIN));
        Answer badRequest = rawHttp11(withoutKey);

        assertAll("a refusal after route selection is a response after route selection",
                () -> assertEquals(426, upgradeRequired.status()),
                () -> assertEquals("websocket", upgradeRequired.headers().get("Upgrade"),
                        "the 426 keeps its own header beside the route's"),
                () -> assertRouteSecurityHeaders(upgradeRequired, "the 426"),
                () -> assertEquals(400, badRequest.status()),
                () -> assertRouteSecurityHeaders(badRequest, "the 400"));
        assertNothingDialedAndPermitsReturned();
    }

    @Test
    @DisplayName("a 503 for an exhausted relay sub-budget carries the route's security headers")
    void relayBudgetRefusalCarriesTheRouteSecurityHeaders() throws Exception {
        WebSocket held = openRelay();

        Answer refused = rawHttp11(handshake("13", SAMPLE_KEY));

        assertAll("the sub-budget refusal",
                () -> assertEquals(503, refused.status(), "the relay sub-budget of one is held by the open relay"),
                () -> assertRouteSecurityHeaders(refused, "the 503"));
        Awaits.teardown(held.close(), "the held relay to close");
    }

    /**
     * A refusal of an HTTP/1.1 request declaring a body within the 8192-byte bound — a body the gate never
     * reads, and the refusal does not retire the connection for — must not leave the keep-alive connection
     * stalled behind that body: the request that follows on the same connection is either answered as
     * itself, or the edge closes the connection. A follow-up answered with anything but its own
     * {@value #UNMATCHED_STATUS} would mean the unread body bytes were parsed as a request. Both ends of
     * the bound are driven, each through a {@code 400}, a {@code 405} and a {@code 426}.
     */
    @ParameterizedTest(name = "a declared body of {0} byte(s)")
    @ValueSource(ints = {1, 8192})
    @DisplayName("a refusal leaving a declared body of 1..8192 bytes unread does not stall the connection behind it")
    void refusalLeavingADeclaredBodyUnreadDoesNotStallTheConnection(int bodyBytes) throws Exception {
        List<String> withoutKey = new ArrayList<>(handshake("13", SAMPLE_KEY));
        withoutKey.removeIf(line -> line.startsWith("Sec-WebSocket-Key"));
        String body = "x".repeat(bodyBytes);

        FollowUp badRequest = refusalThenFollowUp("GET", withoutKey, body);
        FollowUp methodNotAllowed = refusalThenFollowUp("POST", handshake("13", SAMPLE_KEY), body);
        FollowUp upgradeRequired = refusalThenFollowUp("GET", List.of("Origin: " + ORIGIN), body);

        assertAll("each refusal of a request whose declared body it never reads",
                () -> assertEquals(400, badRequest.refusal().status(), "the missing key is the first rule broken"),
                () -> assertNotStalled(badRequest, "the 400"),
                () -> assertEquals(405, methodNotAllowed.refusal().status(), "a WebSocket is opened by a GET only"),
                () -> assertNotStalled(methodNotAllowed, "the 405"),
                () -> assertEquals(426, upgradeRequired.refusal().status(), "a plain GET is no WebSocket upgrade"),
                () -> assertNotStalled(upgradeRequired, "the 426"));
        assertEquals(0, upstreamContacts.get(), "a refused request never reaches the upstream");
    }

    /**
     * The gate admits exactly the {@code Connection} and {@code Upgrade} header shapes the HTTP/1.1 upgrade
     * path of vertx-core 4.5.34 upgrades itself — asserted against that stack, not against a reading of it.
     * The stub upstream is a plain vertx-core server with a WebSocket handler, so each shape is first sent
     * to it directly: the status it answers is the stack's verdict. The same shape is then sent through
     * the edge, which must upgrade it exactly when the stack did, and must refuse a shape the stack
     * refuses before it dials the upstream.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("upgradeHeaderShapes")
    @DisplayName("the gate upgrades exactly the Connection and Upgrade header shapes vertx-core itself upgrades")
    void gateAgreesWithTheStackOnTheUpgradeHeaderShapes(String shape, List<String> upgradeLines) throws Exception {
        List<String> headerLines = new ArrayList<>(upgradeLines);
        headerLines.addAll(List.of("Sec-WebSocket-Key: " + SAMPLE_KEY, "Sec-WebSocket-Version: 13",
                "Origin: " + ORIGIN));

        int stackStatus = exchangeOn(upstream.actualPort(), "GET", WS_URI, headerLines, false).status();
        int contactsAfterStack = upstreamContacts.get();
        int edgeStatus = exchange("GET", WS_URI, headerLines, false).status();
        int edgeContacts = upstreamContacts.get() - contactsAfterStack;

        assertAll(shape,
                () -> assertEquals(stackStatus == SWITCHING_PROTOCOLS, edgeStatus == SWITCHING_PROTOCOLS,
                        () -> "the edge upgrades exactly what vertx-core upgrades: the stack answered " + stackStatus
                                + ", the edge " + edgeStatus),
                () -> assertTrue(stackStatus == SWITCHING_PROTOCOLS || edgeContacts == 0,
                        () -> "a shape the stack refuses is refused before any upstream dial: " + edgeContacts
                                + " upstream contact(s)"));
    }

    static Stream<Arguments> upgradeHeaderShapes() {
        return Stream.of(
                Arguments.of("control: one Connection line Upgrade, one Upgrade line websocket",
                        List.of("Upgrade: websocket", "Connection: Upgrade")),
                Arguments.of("upgrade inside a Connection list",
                        List.of("Upgrade: websocket", "Connection: keep-alive, Upgrade")),
                Arguments.of("upgrade on the first of two Connection lines",
                        List.of("Upgrade: websocket", "Connection: Upgrade", "Connection: keep-alive")),
                Arguments.of("upgrade on the second of two Connection lines only",
                        List.of("Upgrade: websocket", "Connection: keep-alive", "Connection: Upgrade")),
                Arguments.of("websocket first in an Upgrade list",
                        List.of("Upgrade: websocket, x", "Connection: Upgrade")),
                Arguments.of("websocket last in an Upgrade list",
                        List.of("Upgrade: chat, websocket", "Connection: Upgrade")),
                Arguments.of("websocket on the second of two Upgrade lines",
                        List.of("Upgrade: x", "Upgrade: websocket", "Connection: Upgrade")));
    }

    @Test
    @DisplayName("control: a valid upgrade is still relayed end to end, its one handshake reaching the upstream")
    void validUpgradeIsRelayed() throws Exception {
        WebSocket socket = openRelay();
        String frame = Generators.letterStrings(4, 16).next();

        String echoed = echo(socket, frame);

        assertAll("the gate lets a valid handshake through",
                () -> assertEquals(frame, echoed, "the relay forwards the frame both ways"),
                () -> assertEquals(1, upstreamContacts.get(), "the upstream received exactly the relay's handshake"));
        Awaits.teardown(socket.close(), "the relayed WebSocket to close");
    }

    /**
     * Asserts the upstream was never contacted and that every permit the refusals took came back: the
     * relay sub-budget of one admits a valid relay, and the second general permit then serves a plain
     * request while that relay holds the first.
     */
    private void assertNothingDialedAndPermitsReturned() throws Exception {
        assertEquals(0, upstreamContacts.get(), "a refused request never reaches the upstream");
        WebSocket relay = openRelay();
        int plainStatus = plainRequestStatus();
        assertAll("no refusal kept a permit",
                () -> assertEquals(1, upstreamContacts.get(), "only the control relay reached the upstream"),
                () -> assertEquals(404, plainStatus,
                        "the second general permit is free while the relay holds the first"));
        Awaits.teardown(relay.close(), "the control relay to close");
    }

    private static void assertRouteSecurityHeaders(Answer answer, String which) {
        assertAll(which + " carries the route's security_headers",
                () -> assertEquals("max-age=" + ROUTE_HSTS_MAX_AGE, answer.headers().get(HSTS)),
                () -> assertEquals(ROUTE_POLICY, answer.headers().get(CSP)));
    }

    private WebSocket openRelay() throws Exception {
        return Awaits.connect(wsClient.connect(new WebSocketConnectOptions()
                        .setHost(LoopbackHost.ADDRESS).setPort(front.actualPort()).setURI(WS_URI)
                        .addHeader("Origin", ORIGIN)),
                "the valid WebSocket upgrade through the edge");
    }

    private static String echo(WebSocket socket, String text) throws Exception {
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);
        Awaits.connect(socket.writeTextMessage(text), "the frame to be written");
        return Awaits.connect(echoed, "the relay to echo the frame");
    }

    private int plainRequestStatus() throws Exception {
        return Awaits.connect(httpClient.request(HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, "/unmatched")
                        .compose(HttpClientRequest::send)
                        .map(HttpClientResponse::statusCode),
                "the edge's answer to a plain request beside the relay");
    }

    /** Sends an HTTP/2 GET on the WebSocket route and returns the edge's answer, which must come promptly. */
    private Answer http2Get() throws Exception {
        CompletableFuture<Answer> answer = http2Client.request(new RequestOptions()
                .setHost(LoopbackHost.ADDRESS).setPort(front.actualPort())
                .setMethod(HttpMethod.GET).setURI(WS_URI))
                .compose(request -> request.putHeader("Origin", ORIGIN).send())
                .compose(response -> response.body().map(body -> new Answer(response.statusCode(),
                        headersOf(response.headers().entries()))))
                .toCompletionStage().toCompletableFuture()
                .orTimeout(PROMPT_ANSWER_SECONDS, TimeUnit.SECONDS);
        return Awaits.connect(answer, "the edge's prompt answer to an HTTP/2 GET on the WebSocket route");
    }

    /**
     * Sends one HTTP/1.1 GET on the WebSocket route over a raw connection, carrying exactly
     * {@code headerLines} beside {@code Host}, and returns the status and headers of the edge's answer,
     * which must come promptly. A raw connection, because an HTTP client is free to rewrite the
     * handshake headers these cases depend on.
     */
    private Answer rawHttp11(List<String> headerLines) throws Exception {
        return rawHttp11("GET", headerLines);
    }

    /** Like {@link #rawHttp11(List)}, sending the request with {@code method} instead of {@code GET}. */
    private Answer rawHttp11(String method, List<String> headerLines) throws Exception {
        return exchange(method, WS_URI, headerLines, false);
    }

    /**
     * Sends one HTTP/1.1 request with {@code method} to {@code requestUri} over a raw connection, carrying
     * exactly {@code headerLines} beside {@code Host} and no body, and returns the status and headers of
     * the edge's answer, which must come promptly. With {@code awaitEdgeClose} the edge must also close
     * the connection promptly after its answer.
     */
    private Answer exchange(String method, String requestUri, List<String> headerLines, boolean awaitEdgeClose)
            throws Exception {
        return exchangeOn(front.actualPort(), method, requestUri, headerLines, awaitEdgeClose);
    }

    /**
     * Like {@link #exchange}, sent to the server listening on {@code port} — the edge, or the stub upstream
     * when the case asks the HTTP stack itself.
     */
    private Answer exchangeOn(int port, String method, String requestUri, List<String> headerLines,
            boolean awaitEdgeClose) throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(port, LoopbackHost.ADDRESS),
                "the TCP connection to the server under test");
        CompletableFuture<Void> closedByEdge = new CompletableFuture<>();
        socket.closeHandler(v -> closedByEdge.complete(null));
        CompletableFuture<Answer> answer = new CompletableFuture<>();
        Buffer received = Buffer.buffer();
        socket.handler(chunk -> {
            received.appendBuffer(chunk);
            String text = received.toString(StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf("\r\n\r\n");
            if (headEnd >= 0) {
                answer.complete(parseHead(text.substring(0, headEnd)));
            }
        });
        try {
            Awaits.connect(socket.write(requestHead(port, method, requestUri, headerLines)),
                    "the raw request to be written");
            Answer edgeAnswer = Awaits.connect(answer.orTimeout(PROMPT_ANSWER_SECONDS, TimeUnit.SECONDS),
                    "the edge's prompt answer to the raw HTTP/1.1 request");
            if (awaitEdgeClose) {
                Awaits.connect(closedByEdge.orTimeout(PROMPT_ANSWER_SECONDS, TimeUnit.SECONDS),
                        "the edge to close the connection after its answer");
            }
            return edgeAnswer;
        } finally {
            Awaits.teardown(socket.close(), "the raw connection to close");
        }
    }

    /**
     * The head of one HTTP/1.1 request with {@code method} to {@code requestUri} on the server listening
     * on {@code port}, carrying exactly {@code headerLines} beside {@code Host}.
     */
    private static String requestHead(int port, String method, String requestUri, List<String> headerLines) {
        List<String> request = new ArrayList<>();
        request.add(method + " " + requestUri + " HTTP/1.1");
        request.add("Host: " + LoopbackHost.ADDRESS + ":" + port);
        request.addAll(headerLines);
        request.add("");
        request.add("");
        return String.join(CRLF, request);
    }

    /**
     * Sends one HTTP/1.1 request on the WebSocket route over a raw connection, carrying
     * {@code headerLines}, a {@code Content-Length} of {@code body} and {@code body} itself, and awaits the
     * edge's answer. Then sends a {@code GET} to {@link #UNMATCHED_PATH} on the same connection and awaits,
     * for at most {@link #FOLLOW_UP_SECONDS}, either the answer to it or the edge closing the connection.
     *
     * @return the answer to the first request, and what became of the one that followed it
     */
    private FollowUp refusalThenFollowUp(String method, List<String> headerLines, String body) throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(front.actualPort(), LoopbackHost.ADDRESS),
                "the TCP connection to the edge");
        CompletableFuture<Void> closedByEdge = new CompletableFuture<>();
        socket.closeHandler(v -> closedByEdge.complete(null));
        CompletableFuture<Answer> refusal = new CompletableFuture<>();
        CompletableFuture<Integer> followUpStatus = new CompletableFuture<>();
        Buffer received = Buffer.buffer();
        socket.handler(chunk -> {
            received.appendBuffer(chunk);
            String text = received.toString(StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf(CRLF + CRLF);
            if (headEnd < 0) {
                return;
            }
            refusal.complete(parseHead(text.substring(0, headEnd)));
            // The refusal carries no body, so the next status line starts the follow-up's answer.
            int next = text.indexOf(HTTP_1_1, headEnd);
            int statusLineEnd = next < 0 ? -1 : text.indexOf(CRLF, next);
            if (statusLineEnd >= 0) {
                followUpStatus.complete(Integer.parseInt(text.substring(next, statusLineEnd).split(" ")[1]));
            }
        });
        List<String> withLength = new ArrayList<>(headerLines);
        withLength.add("Content-Length: " + body.length());
        try {
            Awaits.connect(socket.write(requestHead(front.actualPort(), method, WS_URI, withLength) + body),
                    "the raw request and its body to be written");
            Answer refused = Awaits.connect(refusal.orTimeout(PROMPT_ANSWER_SECONDS, TimeUnit.SECONDS),
                    "the edge's prompt answer to the raw HTTP/1.1 request");
            // A write to a connection the edge has already closed fails; that outcome is read below.
            socket.write(requestHead(front.actualPort(), "GET", UNMATCHED_PATH, List.of()));
            Awaits.connect(CompletableFuture.anyOf(followUpStatus, closedByEdge)
                            .completeOnTimeout(null, FOLLOW_UP_SECONDS, TimeUnit.SECONDS),
                    "the request that follows to be answered, the edge to close the connection, or the wait to end");
            return new FollowUp(refused,
                    followUpStatus.isDone() ? OptionalInt.of(followUpStatus.join()) : OptionalInt.empty(),
                    closedByEdge.isDone());
        } finally {
            Awaits.teardown(socket.close(), "the raw connection to close");
        }
    }

    /**
     * Asserts that the request following a refusal on its connection was not left without an answer: it
     * was answered as itself, or the edge closed the connection — and then the refusal announced that.
     */
    private static void assertNotStalled(FollowUp followUp, String which) {
        if (followUp.followUpStatus().isPresent()) {
            assertEquals(UNMATCHED_STATUS, followUp.followUpStatus().getAsInt(),
                    () -> which + ": the request that follows is answered as itself, not as bytes of the unread body");
            return;
        }
        assertAll(which + ": the request that follows got no answer within " + FOLLOW_UP_SECONDS + " s",
                () -> assertTrue(followUp.closedByEdge(),
                        "without an answer, the edge must have closed the connection instead of leaving it stalled"),
                () -> assertEquals("close", followUp.refusal().headers().get("Connection"),
                        "a refusal after which the edge closes the connection announces it"));
    }

    /** The header lines of a WebSocket opening handshake with the given version and key. */
    private static List<String> handshake(String version, String key) {
        return List.of("Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Key: " + key,
                "Sec-WebSocket-Version: " + version, "Origin: " + ORIGIN);
    }

    private static Answer parseHead(String head) {
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            entries.add(Map.entry(lines[index].substring(0, colon).trim(), lines[index].substring(colon + 1).trim()));
        }
        return new Answer(status, headersOf(entries));
    }

    private static Map<String, String> headersOf(List<Map.Entry<String, String>> entries) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        entries.forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
        return headers;
    }

    /**
     * The WebSocket route under test, allowing every standard method — what route-table assembly resolves
     * for a route no scope declares {@code allowed_methods} for ({@code PolicyBlock.ALLOWED_METHODS}) —
     * rather than the fixture seed's {@code GET} alone, so a non-{@code GET} handshake reaches the gate
     * instead of being refused by the method check in front of it. It resolves a {@code security_headers}
     * block, so a refusal can be checked for the headers every response after route selection carries.
     */
    private static Route webSocketRoute(int upstreamPort) {
        return webSocketRoute("ws", WS_PATH, upstreamPort).build();
    }

    /**
     * The same WebSocket route declaring {@code security_filter.profile: minimal}, which skips the
     * url-parameter validation of the route's security filter — the one place a query character
     * {@code java.net.URI} rejects is refused ahead of the gate — so such a request URI reaches the gate.
     */
    private static Route minimalProfileWebSocketRoute(int upstreamPort) {
        return webSocketRoute("minimal", MINIMAL_WS_PATH, upstreamPort)
                .effectiveSecurityFilter(SecurityFilterConfig.builder().profile("minimal").build())
                .build();
    }

    private static Route.RouteBuilder webSocketRoute(String id, String pathPrefix, int upstreamPort) {
        return ConfigFixtures.route(id, pathPrefix)
                .protocol(Protocol.WEBSOCKET)
                .effectiveAllowedMethods(EnumSet.allOf(de.cuioss.sheriff.gateway.config.model.HttpMethod.class))
                .securityHeaders(SecurityHeadersConfig.builder()
                        .hsts(new SecurityHeadersConfig.Hsts(ROUTE_HSTS_MAX_AGE, false))
                        .contentSecurityPolicy(ROUTE_POLICY)
                        .build())
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                .effectiveAllowedOrigins(Set.of(ORIGIN))
                .effectiveForward(ForwardConfig.builder().headersAllow(List.of()).build());
    }

    /** What the client saw of the edge's answer: its status and its headers, looked up case-insensitively. */
    private record Answer(int status, Map<String, String> headers) {
    }

    /**
     * A refusal and what became of the request sent after it on the same connection: the status it was
     * answered with, if any, and whether the edge closed the connection.
     */
    private record FollowUp(Answer refusal, OptionalInt followUpStatus, boolean closedByEdge) {
    }
}
