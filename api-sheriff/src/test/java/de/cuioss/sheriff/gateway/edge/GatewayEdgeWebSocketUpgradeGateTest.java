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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
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

/**
 * A request on a {@code protocol: websocket} route that is no valid WebSocket opening handshake is
 * answered by the edge itself, promptly, and never dials the upstream. Every case drives a real request
 * through {@link GatewayEdgeRoute} against a stub upstream that counts every handshake and request it
 * receives, so a refusal that still dialed is visible as a non-zero count.
 * <p>
 * The edge runs with two general admission permits and a relay sub-budget of one. A refusal that kept
 * the general permit it took would therefore leave too few for the valid relay and the plain request
 * that follow it, which is how each case proves the permit came back.
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
        front = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .routes(webSocketRoute(upstream.actualPort()))
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
        NetSocket socket = Awaits.connect(netClient.connect(front.actualPort(), LoopbackHost.ADDRESS),
                "the TCP connection to the edge");
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
        List<String> request = new ArrayList<>();
        request.add("GET " + WS_URI + " HTTP/1.1");
        request.add("Host: " + LoopbackHost.ADDRESS + ":" + front.actualPort());
        request.addAll(headerLines);
        request.add("");
        request.add("");
        try {
            Awaits.connect(socket.write(String.join("\r\n", request)), "the raw request to be written");
            return Awaits.connect(answer.orTimeout(PROMPT_ANSWER_SECONDS, TimeUnit.SECONDS),
                    "the edge's prompt answer to the raw HTTP/1.1 request");
        } finally {
            Awaits.teardown(socket.close(), "the raw connection to close");
        }
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

    private static Route webSocketRoute(int upstreamPort) {
        return ConfigFixtures.route("ws", WS_PATH)
                .protocol(Protocol.WEBSOCKET)
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                .effectiveAllowedOrigins(Set.of(ORIGIN))
                .effectiveForward(ForwardConfig.builder().headersAllow(List.of()).build())
                .build();
    }

    /** What the client saw of the edge's answer: its status and its headers, looked up case-insensitively. */
    private record Answer(int status, Map<String, String> headers) {
    }
}
