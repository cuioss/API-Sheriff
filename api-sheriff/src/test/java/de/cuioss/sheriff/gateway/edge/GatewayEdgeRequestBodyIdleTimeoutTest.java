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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The request-body idle timeout of the edge, driven over a live Vert.x edge against a loopback stub
 * upstream: an upload whose body stops arriving while its dispatch holds an upstream connection is
 * answered {@code 408} with the input-validation problem document, and the upstream connection it held
 * is released at once.
 * <p>
 * <strong>Why it matters.</strong> A proxied upload holds one of the upstream client's pooled HTTP/1.1
 * connections while the gateway forwards its body. A body that stops arriving would otherwise keep that
 * connection until the upstream guard's whole-dispatch timeout ended the dispatch as an upstream
 * timeout, and the uploads queued for a connection behind it would wait as long.
 * <p>
 * <strong>How the answer ends.</strong> The body behind the {@code 408} was not read, so it cannot frame
 * a following request: on HTTP/2 the answer ends that one stream, which is then reset with
 * {@code NO_ERROR}, and the connection carries on; on HTTP/1.1 the answer carries
 * {@code Connection: close} and the connection is closed once it has been written.
 * <p>
 * <strong>What the stub upstream does.</strong> It is HTTP/1.1 only, as the gateway's default upstream
 * client is, so it reports a connection when it is accepted rather than with its first bytes. A request
 * under {@value #SLOW} is answered {@code ok} only {@link #SLOW_ANSWER_MILLIS} after its head arrived,
 * longer than the idle timeout; every other request is answered {@code ok} once its body has ended. It
 * records every request head it saw, every body it read to its end, and every connection it accepted,
 * with the requests that connection carried.
 * <p>
 * <strong>How the release shows.</strong> The upstream client writes an HTTP/1.1 request head with the
 * first body chunk, so an upload whose body never arrived never sent its head, and the reset that ends
 * its dispatch leaves the connection reusable: the request that follows is served on that same upstream
 * connection, which is how the release is observed.
 * <p>
 * The edge is built with the test-only {@link EdgeHardeningOptions} constructor carrying a
 * {@value #IDLE_TIMEOUT_MILLIS} ms idle timeout in place of the fixed 10 s.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("GatewayEdgeRoute — a request body that stops arriving releases its upstream connection")
@Tag("isolated-fork")
class GatewayEdgeRequestBodyIdleTimeoutTest {

    /** The idle timeout the edge of this fixture is built with. */
    private static final long IDLE_TIMEOUT_MILLIS = 300L;
    /** How long after its head the stub upstream answers a slow request: well beyond the idle timeout. */
    private static final long SLOW_ANSWER_MILLIS = 5 * IDLE_TIMEOUT_MILLIS;
    /** The upstream path prefix the stub answers only after {@link #SLOW_ANSWER_MILLIS}. */
    private static final String SLOW = "/slow";
    /** The route every proxied request of this fixture takes; the prefix is stripped upstream. */
    private static final String ROUTE = "up";
    private static final String CRLF = "\r\n";
    private static final String REQUEST_BODY_TIMEOUT = "REQUEST_BODY_TIMEOUT";
    /** The INFO record a request-body idle timeout is reported with. */
    private static final String REQUEST_BODY_TIMEOUT_RECORD = "ApiSheriff-23";
    /** The problem document a request-body idle timeout is answered with. */
    private static final String INPUT_VALIDATION_408 =
            "{\"type\":\"urn:api-sheriff:problem:input-validation\",\"title\":\"Input Validation\",\"status\":408}";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String ANSWER = "ok";

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private NetClient netClient;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<HttpClient> httpClients = new CopyOnWriteArrayList<>();

    /** Every request the stub upstream received a head for, as {@code METHOD path}. */
    private final List<String> upstreamStarted = new CopyOnWriteArrayList<>();
    /** Every request whose body the stub upstream read to its end. */
    private final List<String> upstreamCompleted = new CopyOnWriteArrayList<>();
    /** Every connection the stub upstream accepted, with the requests it carried and whether it closed. */
    private final List<UpstreamConnection> upstreamConnections = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                .connectionHandler(this::trackUpstreamConnection)
                .requestHandler(this::handleUpstreamRequest)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        frontServer = startEdge();
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

    private void trackUpstreamConnection(HttpConnection connection) {
        upstreamConnections.add(new UpstreamConnection(connection));
    }

    private void handleUpstreamRequest(HttpServerRequest request) {
        String label = request.method() + " " + request.path();
        upstreamStarted.add(label);
        upstreamConnections.stream().filter(tracked -> tracked.connection() == request.connection()).findFirst()
                .ifPresent(tracked -> tracked.requests().add(label));
        if (request.path().startsWith(SLOW)) {
            vertx.setTimer(SLOW_ANSWER_MILLIS, fired -> request.response().end(ANSWER));
            return;
        }
        request.body().onSuccess(body -> {
            upstreamCompleted.add(label);
            request.response().end(ANSWER);
        });
    }

    @Test
    @DisplayName("HTTP/2 — an upload with no body after its head: 408, then a NO_ERROR reset; the connection carries on")
    void http2IdleUploadIsAnswered408ThenResetAndTheConnectionCarriesOn() throws Exception {
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        HttpClientRequest upload = open(http2Client, "POST", "/" + ROUTE + "/idle-h2");
        CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
        upload.exceptionHandler(streamEnd::complete);
        HttpConnection connection = upload.connection();
        AtomicBoolean connectionLost = new AtomicBoolean();
        connection.closeHandler(closed -> connectionLost.set(true));
        connection.goAwayHandler(goAway -> connectionLost.set(true));
        Future<Answer> answered = answerOf(upload);

        Awaits.connect(upload.setChunked(true).sendHead(), "the head of the upload to be sent");
        Answer answer = Awaits.connect(answered, "the idle upload to be answered");
        Throwable reset = Awaits.connect(streamEnd, "the edge to reset the open upload stream");
        Answer after = sendAfter(http2Client);

        assertAll("an HTTP/2 upload whose body never arrives",
                () -> assertEquals(408, answer.response().statusCode()),
                () -> assertEquals(PROBLEM_JSON, answer.response().getHeader("Content-Type")),
                () -> assertEquals(INPUT_VALIDATION_408, answer.body()),
                () -> assertEquals(0L, assertInstanceOf(StreamResetException.class, reset).getCode(),
                        "the stream must be reset with NO_ERROR"),
                () -> assertFalse(connectionLost.get(), "the connection must neither close nor receive GOAWAY"),
                () -> assertEquals(200, after.response().statusCode(), "a second request is served"),
                () -> assertSame(connection, after.connection(), "the second request reuses the connection"),
                () -> assertEquals(List.of("GET /after"), upstreamStarted,
                        "the idle upload never sent its head upstream; the second request was served"),
                () -> assertEquals(List.of(List.of("GET /after")), upstreamRequestsByConnection(),
                        "the second request is served on the upstream connection the idle upload released"),
                () -> assertEquals(1.0, errorCount(REQUEST_BODY_TIMEOUT)),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                        REQUEST_BODY_TIMEOUT_RECORD));
    }

    @Test
    @DisplayName("HTTP/1.1 — an upload with no body after its head: 408 with Connection: close, then the connection closes")
    void http1IdleUploadIsAnswered408AndTheConnectionIsClosed() throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(frontServer.actualPort(), LoopbackHost.ADDRESS),
                "the raw TCP client to connect to the edge");
        Buffer received = Buffer.buffer();
        CompletableFuture<String> closed = new CompletableFuture<>();
        socket.handler(received::appendBuffer);
        socket.closeHandler(end -> closed.complete(received.toString()));

        socket.write("POST /" + ROUTE + "/idle-h1 HTTP/1.1" + CRLF + "Host: localhost:" + frontServer.actualPort()
                + CRLF + "Transfer-Encoding: chunked" + CRLF + CRLF);
        String exchange = Awaits.connect(closed, "the edge to close the connection after its answer");

        String head = exchange.substring(0, Math.max(0, exchange.indexOf(CRLF + CRLF))).toLowerCase(Locale.ROOT);
        assertAll("an HTTP/1.1 upload whose body never arrives",
                () -> assertTrue(exchange.startsWith("HTTP/1.1 408 "), () -> "the upload is answered 408: " + exchange),
                () -> assertTrue(head.contains(CRLF + "connection: close"),
                        () -> "the answer retires the connection: " + exchange),
                () -> assertTrue(exchange.contains(INPUT_VALIDATION_408),
                        () -> "the answer carries the input-validation problem document: " + exchange),
                () -> assertEquals(List.of(), upstreamStarted, "the idle upload never sent its head upstream"),
                () -> assertEquals(1.0, errorCount(REQUEST_BODY_TIMEOUT)));
    }

    @Test
    @DisplayName("a GET without a body answered only after longer than the idle timeout is served 200")
    void bodylessGetIsNeverCutOff() throws Exception {
        HttpClient http1Client = client(HttpVersion.HTTP_1_1);
        HttpClientRequest get = open(http1Client, "GET", "/" + ROUTE + SLOW);

        Answer answer = Awaits.connect(get.end().compose(sent -> answerOf(get)), "the slow GET to be answered");

        assertAll("a bodyless request the upstream answers late",
                () -> assertEquals(200, answer.response().statusCode()),
                () -> assertEquals(ANSWER, answer.body()),
                () -> assertEquals(0.0, errorCount(REQUEST_BODY_TIMEOUT), "the bound never applies to a bodyless request"));
    }

    @Test
    @DisplayName("repeated idle uploads are reported once at INFO and the route keeps serving its normal requests")
    void repeatedIdleUploadsAreReportedOnceAndTheRouteKeepsServing() throws Exception {
        HttpClient http2Client = client(HttpVersion.HTTP_2);
        int idleUploads = 3;
        for (int i = 0; i < idleUploads; i++) {
            HttpClientRequest upload = open(http2Client, "POST", "/" + ROUTE + "/idle-repeat-" + i);
            Future<Answer> answered = answerOf(upload);
            Awaits.connect(upload.setChunked(true).sendHead(), "the head of idle upload " + i + " to be sent");
            assertEquals(408, Awaits.connect(answered, "idle upload " + i + " to be answered").response().statusCode());
        }
        String body = Generators.letterStrings(8, 16).next();
        HttpClientRequest normal = open(http2Client, "POST", "/" + ROUTE + "/normal");

        Answer answer = Awaits.connect(normal.send(body).compose(response -> response.body()
                        .map(read -> new Answer(response, read.toString(), normal.connection()))),
                "the normal upload to be answered");

        assertAll("the route behind repeated idle uploads",
                () -> assertEquals(200, answer.response().statusCode(), "the route's next normal request is served"),
                () -> assertEquals(List.of("POST /normal"), upstreamCompleted, "its body reached the upstream whole"),
                () -> assertEquals(idleUploads, errorCount(REQUEST_BODY_TIMEOUT), "every idle upload is metered"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                        REQUEST_BODY_TIMEOUT_RECORD));
    }

    /**
     * Builds an edge over the proxied {@value #ROUTE} route, carrying the fixture's short idle timeout,
     * and starts a front server for it.
     */
    private HttpServer startEdge() throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        RouteTable routes = new RouteTable(List.of(Route.builder()
                .id(ROUTE)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/" + ROUTE).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                .build()));
        EdgeHardeningOptions hardening = new EdgeHardeningOptions(EdgeHardeningConfig.defaults(), IDLE_TIMEOUT_MILLIS);
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routes, GatewayConfig.builder().version(1).build(),
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, hardening,
                new SheriffMetrics(meterRegistry), BffRuntime.inert(), EgressTrustProfiles.unconsulted(),
                PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        return Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
    }

    /** Sends a well-framed GET on {@code client}'s connection and returns its answer. */
    private Answer sendAfter(HttpClient client) throws Exception {
        HttpClientRequest after = open(client, "GET", "/" + ROUTE + "/after");
        return Awaits.connect(after.end().compose(sent -> answerOf(after)), "a further request to be answered");
    }

    /** The request heads every stub upstream connection carried, one list per connection in accept order. */
    private List<List<String>> upstreamRequestsByConnection() {
        return upstreamConnections.stream().map(connection -> List.copyOf(connection.requests())).toList();
    }

    /** The answer to {@code request}: its response, with the body read whole. */
    private static Future<Answer> answerOf(HttpClientRequest request) {
        return request.response().compose(response -> response.body()
                .map(body -> new Answer(response, body.toString(), request.connection())));
    }

    private HttpClientRequest open(HttpClient client, String method, String path) throws Exception {
        return Awaits.connect(client.request(io.vertx.core.http.HttpMethod.valueOf(method), frontServer.actualPort(),
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

    /**
     * One connection the stub upstream accepted, and the requests it carried.
     *
     * @param connection the accepted connection
     * @param requests   the request heads it carried, as {@code METHOD path}
     */
    private record UpstreamConnection(HttpConnection connection, List<String> requests) {

        UpstreamConnection(HttpConnection connection) {
            this(connection, new CopyOnWriteArrayList<>());
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
