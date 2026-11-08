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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A client that leaves after the gateway handed the upstream response over for relay frees the upstream
 * connection that response held, driven over a live Vert.x edge against a loopback stub upstream.
 * <p>
 * <strong>Why it matters.</strong> When a client leaves mid-body — an HTTP/1.1 client closing its
 * connection, an HTTP/2 client resetting its stream — the write side of the relay pipe fails. A Vert.x
 * pipe whose destination fails resumes its source with no handler on it, so without a reset the upstream
 * body would be read and discarded to its end, and the pooled upstream connection would stay leased until
 * the upstream finished. An upstream whose body never ends would hold the connection for the life of the
 * process, and a few such requests would exhaust the pool for every client of the route. The terminal
 * dispatch therefore resets the upstream exchange when the relay fails after the hand-over.
 * <p>
 * <strong>What the stub upstream does.</strong> It is HTTP/1.1 only, as the gateway's default upstream
 * client is. A request under {@value #ENDLESS} is answered at once with a chunked {@code 200} head and a
 * first chunk, then one chunk every {@value #CHUNK_INTERVAL_MILLIS} ms until its response is closed or
 * fails — which is how it observes the gateway resetting the upstream request, the reset closing the
 * HTTP/1.1 upstream connection. Every other request is answered {@code ok} at once.
 * <p>
 * <strong>How the release shows.</strong> Every connection the upstream client pools — the Vert.x default
 * HTTP/1.1 pool size of a fresh {@link PoolOptions}, which the gateway does not change — is taken by an
 * endless answer whose client leaves once it has read the response head. Each upstream request must be
 * reset within {@value #RESET_BOUND_MILLIS} ms of its client leaving, and a request that follows on the
 * same route must then be served within the same bound. Without the reset no upstream request is reset,
 * and the request that follows waits on the exhausted pool.
 */
@DisplayName("GatewayEdgeRoute — a relay that fails after the hand-over resets the upstream exchange")
@Tag("isolated-fork")
class GatewayEdgeRelayFailureResetTest {

    /** The route every request of this fixture takes; the prefix is stripped upstream. */
    private static final String ROUTE = "up";
    /** The upstream path prefix the stub answers with a body that never ends. */
    private static final String ENDLESS = "/endless";
    /** How often the stub upstream sends one chunk of an endless body. */
    private static final long CHUNK_INTERVAL_MILLIS = 20L;
    /**
     * How soon after its client left an upstream request must be reset, and how soon the request that
     * follows must be served. A reset on loopback takes milliseconds; the bound leaves room for a loaded
     * machine while staying far below anything an exhausted pool would answer within.
     */
    private static final long RESET_BOUND_MILLIS = 2_000L;
    private static final String CRLF = "\r\n";
    private static final String CHUNK = "x";
    private static final String ANSWER = "ok";

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private NetClient netClient;
    private final List<HttpClient> httpClients = new CopyOnWriteArrayList<>();

    /** Every request the stub upstream received a head for, as {@code METHOD path}. */
    private final List<String> upstreamStarted = new CopyOnWriteArrayList<>();
    /** Per endless upstream path, completed with the time its response was closed or failed. */
    private final Map<String, CompletableFuture<Long>> upstreamReset = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                .requestHandler(this::handleUpstreamRequest)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        ResolvedUpstream upstream = new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), "");
        frontServer = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .routes(ConfigFixtures.route(ROUTE, "/" + ROUTE).upstream(upstream).build())
                .serve();
        netClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (HttpClient client : httpClients) {
            Awaits.teardown(client.close(), "an HTTP client to close");
        }
        Awaits.teardown(netClient.close(), "the raw TCP client to close");
        Awaits.teardown(frontServer.close(), "the edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    private void handleUpstreamRequest(HttpServerRequest request) {
        upstreamStarted.add(request.method() + " " + request.path());
        HttpServerResponse response = request.response();
        if (!request.path().startsWith(ENDLESS)) {
            response.end(ANSWER);
            return;
        }
        CompletableFuture<Long> reset = upstreamReset.computeIfAbsent(request.path(), path -> new CompletableFuture<>());
        response.closeHandler(closed -> reset.complete(System.nanoTime()));
        response.exceptionHandler(failure -> reset.complete(System.nanoTime()));
        response.setChunked(true).write(CHUNK);
        vertx.setPeriodic(CHUNK_INTERVAL_MILLIS, timer -> {
            if (reset.isDone() || response.closed()) {
                vertx.cancelTimer(timer);
            } else {
                response.write(CHUNK);
            }
        });
    }

    @Test
    @DisplayName("HTTP/1.1 — clients closing after the response head free every pooled upstream connection")
    void http1ClientClosingAfterTheHeadResetsTheUpstreamExchange() throws Exception {
        int pooled = new PoolOptions().getHttp1MaxSize();
        List<Long> leftNanos = new ArrayList<>();
        for (int i = 0; i < pooled; i++) {
            leftNanos.add(readHeadThenClose(ENDLESS + "-" + i));
        }

        assertEachUpstreamResetAndTheRouteServes(pooled, leftNanos);
    }

    @Test
    @DisplayName("HTTP/2 — clients resetting their stream after the response head free every pooled upstream connection")
    void http2ClientResettingAfterTheHeadResetsTheUpstreamExchange() throws Exception {
        int pooled = new PoolOptions().getHttp1MaxSize();
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        List<Long> leftNanos = new ArrayList<>();
        for (int i = 0; i < pooled; i++) {
            leftNanos.add(readHeadThenReset(http2Client, ENDLESS + "-" + i));
        }

        assertEachUpstreamResetAndTheRouteServes(pooled, leftNanos);
    }

    /**
     * Awaits the reset of every endless upstream request, then sends a request that follows on the same
     * route, and asserts that each reset and the answer came within {@link #RESET_BOUND_MILLIS}.
     */
    private void assertEachUpstreamResetAndTheRouteServes(int pooled, List<Long> leftNanos) throws Exception {
        List<Long> resetMillis = new ArrayList<>();
        for (int i = 0; i < pooled; i++) {
            long reset = Awaits.connect(upstreamReset(ENDLESS + "-" + i),
                    "the upstream request of endless answer " + i + " to be reset after its client left");
            resetMillis.add(TimeUnit.NANOSECONDS.toMillis(reset - leftNanos.get(i)));
        }
        HttpClient http1Client = client(HttpVersion.HTTP_1_1);
        long followUpNanos = System.nanoTime();
        HttpClientRequest after = Awaits.connect(http1Client.request(HttpMethod.GET, frontServer.actualPort(),
                LoopbackHost.ADDRESS, "/" + ROUTE + "/after"), "the follow-up request to open");
        Answer answer = Awaits.connect(after.end().compose(sent -> answerOf(after)),
                "the follow-up request to be answered");
        long followUpMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - followUpNanos);

        assertAll("clients leaving after the hand-over on a route whose pooled upstream connections they held",
                () -> assertTrue(resetMillis.stream().allMatch(millis -> millis <= RESET_BOUND_MILLIS),
                        () -> "every upstream request is reset promptly after its client left: " + resetMillis + " ms"),
                () -> assertEquals(200, answer.response().statusCode(), "the follow-up request is served"),
                () -> assertEquals(ANSWER, answer.body()),
                () -> assertTrue(followUpMillis <= RESET_BOUND_MILLIS,
                        () -> "the follow-up request is served on a freed connection, not after the pool waits: "
                                + followUpMillis + " ms"),
                () -> assertTrue(upstreamStarted.contains("GET /after"), "the follow-up request reached the upstream"));
    }

    /**
     * Sends {@code GET /up{path}} over a raw HTTP/1.1 connection, waits for the response head and closes
     * the connection.
     *
     * @return the time the client left, in {@link System#nanoTime()} units
     */
    private long readHeadThenClose(String path) throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(frontServer.actualPort(), LoopbackHost.ADDRESS),
                "the raw TCP client to connect to the edge");
        Buffer received = Buffer.buffer();
        CompletableFuture<String> head = new CompletableFuture<>();
        socket.handler(chunk -> {
            received.appendBuffer(chunk);
            if (received.toString().contains(CRLF + CRLF)) {
                head.complete(received.toString());
            }
        });
        socket.write("GET /" + ROUTE + path + " HTTP/1.1" + CRLF + "Host: localhost:" + frontServer.actualPort()
                + CRLF + CRLF);
        String exchange = Awaits.connect(head, "the response head of " + path + " to arrive");
        assertTrue(exchange.startsWith("HTTP/1.1 200 "), () -> "the endless answer is relayed: " + exchange);
        awaitUpstreamStarted(path);
        Awaits.connect(socket.close(), "the client of " + path + " to close its connection");
        return System.nanoTime();
    }

    /**
     * Sends {@code GET /up{path}} on an HTTP/2 stream, waits for the response head and resets the stream.
     *
     * @return the time the client left, in {@link System#nanoTime()} units
     */
    private long readHeadThenReset(HttpClient client, String path) throws Exception {
        HttpClientRequest request = Awaits.connect(client.request(HttpMethod.GET, frontServer.actualPort(),
                LoopbackHost.ADDRESS, "/" + ROUTE + path), "the GET " + path + " request to open");
        request.exceptionHandler(GatewayEdgeRelayFailureResetTest::ignoreOwnReset);
        HttpClientResponse response = Awaits.connect(request.end().compose(sent -> request.response()),
                "the response head of " + path + " to arrive");
        response.exceptionHandler(GatewayEdgeRelayFailureResetTest::ignoreOwnReset);
        assertEquals(200, response.statusCode(), "the endless answer is relayed");
        awaitUpstreamStarted(path);
        request.reset();
        return System.nanoTime();
    }

    /** The stream a client resets fails its own request and response; there is nothing to observe there. */
    private static void ignoreOwnReset(Throwable failure) {
        // intentionally empty: the reset is this client's own doing
    }

    private void awaitUpstreamStarted(String path) throws Exception {
        Awaits.until(() -> upstreamStarted.contains("GET " + path), "the upstream to receive " + path,
                Awaits.CONNECT_CEILING_SECONDS);
    }

    private CompletableFuture<Long> upstreamReset(String path) {
        return upstreamReset.computeIfAbsent(path, key -> new CompletableFuture<>());
    }

    /** The answer to {@code request}: its response, with the body read whole. */
    private static Future<Answer> answerOf(HttpClientRequest request) {
        return request.response().compose(response -> response.body()
                .map(body -> new Answer(response, body.toString())));
    }

    /** A client speaking {@code version} (HTTP/2 with prior knowledge), closed in {@link #tearDown()}. */
    private HttpClient client(HttpVersion version) {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(version).setHttp2ClearTextUpgrade(false));
        httpClients.add(client);
        return client;
    }

    /** The response to one client request, its body read whole. */
    private record Answer(HttpClientResponse response, String body) {
    }
}
