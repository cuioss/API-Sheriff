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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.net.NetClient;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/2 stream-abuse bounds of the inbound transport (threat {@code GW-08}), driven over a live
 * Vert.x edge whose front server carries exactly the options
 * {@link EdgeHardeningOptions#customizeHttpServer} sets in production.
 * <p>
 * The abusive shapes are written frame by frame through {@link RawHttp2Connection}: an HTTP/2 client
 * library neither resets a stream straight behind the {@code HEADERS} frame that opened it nor leaves a
 * header block open across {@code CONTINUATION} frames. The matched controls — early-answered uploads
 * the gateway itself resets, resets below the pinned limit, a header block within the bound spread
 * over {@code CONTINUATION} frames — use the same connection shapes and show the bounds trip on the
 * abuse alone.
 * <p>
 * <strong>What the stub upstream does.</strong> It speaks HTTP/1.1, as the gateway's default upstream
 * client does. A request to a path under {@value #EARLY} is answered at once, before its body is read;
 * one under {@value #HELD} is received and never answered, so a stream opened against it stays open
 * until its client resets it; every other request is answered {@code ok} once its body has ended.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — HTTP/2 stream-abuse bounds of the inbound transport")
@Tag("isolated-fork")
class GatewayEdgeHttp2AbuseTest {

    /** The upstream path prefix the stub answers at once, before reading the request body. */
    private static final String EARLY = "/early";
    /** The upstream path prefix the stub receives and never answers. */
    private static final String HELD = "/held";
    /** The pinned per-connection Rapid-Reset limit, restated so a change to it is noticed here. */
    private static final int RESET_LIMIT = 200;
    /** Client resets the flood case sends on one connection: well past the limit. */
    private static final int FLOOD_RESETS = RESET_LIMIT + 50;
    /** Client resets the below-the-limit control sends on one connection. */
    private static final int CONTROL_RESETS = RESET_LIMIT - 50;
    /** Early-answered uploads the gateway resets on one connection: more than the client limit. */
    private static final int GATEWAY_RESETS = RESET_LIMIT + 50;
    /** Uploads of the gateway-reset control kept in flight at once. */
    private static final int UPLOAD_BATCH = 10;
    /** The header-block bound in bytes, restated so a change to it is noticed here. */
    private static final int HEADER_LIST_BOUND = 16 * 1024;
    /** The size of each header value a CONTINUATION frame of the size flood carries. */
    private static final int FLOOD_FIELD_BYTES = 1024;
    /** A complete header block just above the bound: 17 KiB of fields. */
    private static final int NEAR_BOUND_BLOCK_BYTES = 17 * 1024;
    /** A complete header block far above the bound: 32 KiB of fields. */
    private static final int FAR_ABOVE_BLOCK_BYTES = 2 * HEADER_LIST_BOUND;
    /** Zero-length CONTINUATION frames the empty-frame flood sends. */
    private static final int EMPTY_CONTINUATIONS = 10_000;

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private NetClient netClient;
    private int frontPort;
    /** The body the stub upstream answers an early request with. */
    private String earlyAnswer;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<HttpClient> httpClients = new CopyOnWriteArrayList<>();
    /** Every request the stub upstream received a head for, as {@code METHOD path}. */
    private final List<String> upstreamStarted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        earlyAnswer = Generators.letterStrings(16, 32).next();
        upstreamServer = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                .requestHandler(this::handleUpstreamRequest)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        GatewayEdgeRoute edge = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .routes(ConfigFixtures.route("echo", "/echo")
                        .effectiveAllowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST))
                        .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                        .build())
                .gatewayConfig(ConfigFixtures.gateway().build()).metrics(new SheriffMetrics(meterRegistry))
                .build();
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        HttpServerOptions frontOptions = new HttpServerOptions();
        new EdgeHardeningOptions().customizeHttpServer(frontOptions);
        frontServer = Awaits.connect(vertx.createHttpServer(frontOptions).requestHandler(router)
                .listen(0, LoopbackHost.ADDRESS), "the edge front server to start listening");
        frontPort = frontServer.actualPort();
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
        boolean early = request.path().startsWith(EARLY);
        boolean held = request.path().startsWith(HELD);
        request.handler(chunk -> {
            // Read and discard: only whether the request arrived is observed.
        });
        request.exceptionHandler(failure -> {
            // The gateway resetting the upstream request is expected for every reset stream.
        });
        request.endHandler(end -> {
            if (!early && !held) {
                request.response().end("ok");
            }
        });
        if (early) {
            request.response().end(earlyAnswer);
        }
    }

    @Test
    @DisplayName("a create-then-reset flood past 200 client resets ends its connection with GOAWAY ENHANCE_YOUR_CALM")
    void rapidResetFloodEndsTheConnectionWithEnhanceYourCalm() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);

        Awaits.connect(connection.write(createThenReset(connection, FLOOD_RESETS)), "the reset flood to be sent");
        Awaits.connect(connection.closed(), "the edge to end the flooding connection");
        int afterStatus = statusOfAFreshConnection();

        assertAll("a Rapid-Reset flood",
                () -> assertEquals(Optional.of(RawHttp2Connection.ENHANCE_YOUR_CALM), connection.goAwayCode(),
                        "the connection past the pinned reset limit is ended with GOAWAY ENHANCE_YOUR_CALM"),
                () -> assertTrue(connection.isClosed(), "the flooding connection is closed"),
                () -> assertEquals(200, afterStatus, "the edge keeps serving a fresh connection"));
    }

    @Test
    @DisplayName("control — 150 client resets stay below the pinned limit and the connection carries on")
    void resetsBelowThePinnedLimitKeepTheConnection() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        int afterStream = 2 * CONTROL_RESETS + 1;

        Awaits.connect(connection.write(createThenReset(connection, CONTROL_RESETS)
                        .appendBuffer(RawHttp2Connection.headers(afterStream,
                                connection.requestHead("GET", "/echo/after"), true))),
                "the resets and a further request to be sent");
        Awaits.until(() -> connection.status(afterStream).isPresent() || connection.isClosed(),
                "the further request on the same connection to be answered", Awaits.CONNECT_CEILING_SECONDS);
        Awaits.teardown(connection.close(), "the raw HTTP/2 connection to close");

        assertAll("client resets below the pinned limit",
                () -> assertEquals(Optional.of(200), connection.status(afterStream),
                        "the further stream on the same connection is served"),
                () -> assertEquals(Optional.empty(), connection.goAwayCode(), "the edge sends no GOAWAY"));
    }

    /**
     * {@code count} streams, each opened by a {@code GET} to a path the stub upstream never answers — so
     * the stream is still open when its reset arrives and the reset counts — and reset by the client
     * with {@code CANCEL} straight behind its {@code HEADERS} frame.
     */
    private static Buffer createThenReset(RawHttp2Connection connection, int count) {
        Buffer frames = Buffer.buffer();
        for (int i = 0; i < count; i++) {
            int streamId = 2 * i + 1;
            frames.appendBuffer(RawHttp2Connection.headers(streamId,
                    connection.requestHead("GET", "/echo" + HELD + "-flood-" + i), true))
                    .appendBuffer(RawHttp2Connection.rstStream(streamId, RawHttp2Connection.CANCEL));
        }
        return frames;
    }

    @Test
    @DisplayName("control — 250 early-answered uploads the gateway resets with NO_ERROR never trip the guard")
    void gatewaySentNoErrorResetsNeverTripTheGuard() throws Exception {
        HttpClient http2Client = client();
        List<Long> goAwayCodes = new CopyOnWriteArrayList<>();
        AtomicBoolean connectionClosed = new AtomicBoolean();
        List<HttpConnection> connections = new CopyOnWriteArrayList<>();
        List<Object> outcomes = new ArrayList<>();

        for (int started = 0; started < GATEWAY_RESETS; started += UPLOAD_BATCH) {
            List<EarlyUpload> batch = new ArrayList<>();
            for (int i = started; i < started + UPLOAD_BATCH; i++) {
                batch.add(startEarlyUpload(http2Client, i, connections, goAwayCodes, connectionClosed));
            }
            for (EarlyUpload upload : batch) {
                outcomes.add(upload.settle(earlyAnswer));
            }
        }
        int afterStatus = Awaits.connect(http2Client.request(io.vertx.core.http.HttpMethod.GET, frontPort,
                        LoopbackHost.ADDRESS, "/echo/after").compose(request -> request.send()
                        .map(response -> response.statusCode())),
                "a further stream on the connection to be answered");

        List<Object> notResetByGateway = outcomes.stream().filter(outcome -> !Boolean.TRUE.equals(outcome)).toList();
        assertAll("uploads the gateway answered early and reset itself",
                () -> assertTrue(notResetByGateway.isEmpty(), () -> notResetByGateway.size()
                        + " upload(s) did not end with the whole early answer and the gateway's NO_ERROR reset: "
                        + notResetByGateway.stream().limit(5).toList()),
                () -> assertEquals(1, connections.size(), "every upload shares the one connection"),
                () -> assertTrue(goAwayCodes.isEmpty(), () -> "the edge sent GOAWAY " + goAwayCodes),
                () -> assertFalse(connectionClosed.get(), "the connection stays open"),
                () -> assertEquals(200, afterStatus, "a further stream on the same connection is served"));
    }

    private EarlyUpload startEarlyUpload(HttpClient client, int index, List<HttpConnection> connections,
            List<Long> goAwayCodes, AtomicBoolean connectionClosed) throws Exception {
        HttpClientRequest request = Awaits.connect(client.request(io.vertx.core.http.HttpMethod.POST, frontPort,
                LoopbackHost.ADDRESS, "/echo" + EARLY + "-" + index), "early upload " + index + " to open");
        HttpConnection connection = request.connection();
        if (!connections.contains(connection)) {
            connections.add(connection);
            connection.goAwayHandler(goAway -> goAwayCodes.add(goAway.getErrorCode()));
            connection.closeHandler(closed -> connectionClosed.set(true));
        }
        CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
        request.exceptionHandler(streamEnd::complete);
        request.setChunked(true);
        Future<String> answer = request.response().compose(response -> response.body().map(Buffer::toString));
        Awaits.connect(request.write(Generators.letterStrings(8, 16).next()),
                "the first part of early upload " + index + " to be sent");
        return new EarlyUpload(answer, streamEnd);
    }

    /** An upload answered early by the upstream: its answer body, and how its stream ended. */
    private record EarlyUpload(Future<String> answer, CompletableFuture<Throwable> streamEnd) {

        /** {@code TRUE} when the whole early answer arrived and the gateway reset the stream NO_ERROR. */
        Object settle(String expectedAnswer) throws Exception {
            String body = Awaits.connect(answer, "an early answer to arrive whole");
            Throwable end = Awaits.connect(streamEnd, "the edge to reset an early-answered upload");
            boolean resetNoError = end instanceof StreamResetException reset && reset.getCode() == 0L;
            return expectedAnswer.equals(body) && resetNoError ? Boolean.TRUE : body + " / " + end;
        }
    }

    @Test
    @DisplayName("a CONTINUATION flood growing the header block past 16 KiB drops the connection")
    void continuationFloodPastTheHeaderListBoundDropsTheConnection() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        String filler = Generators.letterStrings(FLOOD_FIELD_BYTES, FLOOD_FIELD_BYTES).next();
        int floodFrames = 4 * HEADER_LIST_BOUND / FLOOD_FIELD_BYTES;
        Buffer frames = RawHttp2Connection.headersLeftOpen(1,
                connection.requestHead("GET", "/echo/continuation-flood"), true);
        for (int i = 0; i < floodFrames; i++) {
            frames.appendBuffer(RawHttp2Connection.continuation(1, List.of(Map.entry("x-flood-" + i, filler)), false));
        }

        // The header block is never ended: no frame of the flood carries END_HEADERS.
        connection.write(frames);
        Awaits.connect(connection.closed(), "the edge to drop the flooding connection");
        int afterStatus = statusOfAFreshConnection();

        assertAll("a CONTINUATION flood past the header-list bound",
                () -> assertTrue(connection.goAwayCode().isPresent(), "the edge announces the drop with GOAWAY"),
                () -> assertNotEquals(Optional.of(RawHttp2Connection.NO_ERROR), connection.goAwayCode(),
                        "the GOAWAY names an error, not a graceful shutdown"),
                () -> assertEquals(Optional.empty(), connection.status(1), "the flooded stream is never answered"),
                () -> assertFalse(upstreamStarted.contains("GET /continuation-flood"),
                        "nothing of the flooded stream reaches the upstream"),
                () -> assertEquals(200, afterStatus, "the edge keeps serving a fresh connection"));
    }

    @Test
    @DisplayName("a complete header block just above 16 KiB ends the connection, not only its stream")
    void headerBlockJustAboveTheBoundEndsTheConnection() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        Awaits.connect(connection.write(completeBlock(connection, 1, "/echo/just-above", NEAR_BOUND_BLOCK_BYTES)),
                "the header block to be sent");
        Awaits.connect(connection.closed(), "the edge to end the connection");
        int afterStatus = statusOfAFreshConnection();

        assertAll("a complete header block of 17 KiB",
                () -> assertTrue(connection.goAwayCode().isPresent(), "the edge announces the end with GOAWAY"),
                () -> assertNotEquals(Optional.of(RawHttp2Connection.NO_ERROR), connection.goAwayCode(),
                        "the GOAWAY names an error"),
                () -> assertEquals(Optional.empty(), connection.status(1), "the stream is never answered"),
                () -> assertFalse(upstreamStarted.contains("GET /just-above"), "the stream never reaches the upstream"),
                () -> assertEquals(200, afterStatus, "the edge keeps serving a fresh connection"));
    }

    @Test
    @DisplayName("a complete header block of 32 KiB ends the connection with GOAWAY")
    void headerBlockFarAboveTheBoundEndsTheConnection() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        Awaits.connect(connection.write(completeBlock(connection, 1, "/echo/far-above", FAR_ABOVE_BLOCK_BYTES)),
                "the header block to be sent");
        Awaits.connect(connection.closed(), "the edge to drop the connection");

        assertAll("a header block far above the bound",
                () -> assertTrue(connection.goAwayCode().isPresent(), "the edge announces the drop with GOAWAY"),
                () -> assertNotEquals(Optional.of(RawHttp2Connection.NO_ERROR), connection.goAwayCode(),
                        "the GOAWAY names an error"),
                () -> assertFalse(upstreamStarted.contains("GET /far-above"), "the stream never reaches the upstream"));
    }

    /** A complete request header block of about {@code totalBytes}, its fields spread over CONTINUATION frames. */
    private static Buffer completeBlock(RawHttp2Connection connection, int streamId, String path, int totalBytes) {
        String filler = Generators.letterStrings(FLOOD_FIELD_BYTES, FLOOD_FIELD_BYTES).next();
        int fieldFrames = totalBytes / FLOOD_FIELD_BYTES;
        Buffer frames = RawHttp2Connection.headersLeftOpen(streamId, connection.requestHead("GET", path), true);
        for (int i = 0; i < fieldFrames; i++) {
            frames.appendBuffer(RawHttp2Connection.continuation(streamId,
                    List.of(Map.entry("x-field-" + i, filler)), i == fieldFrames - 1));
        }
        return frames;
    }

    @Test
    @DisplayName("control — a header block within the bound spread over CONTINUATION frames is served")
    void headerBlockWithinTheBoundOverContinuationFramesIsServed() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        Buffer frames = RawHttp2Connection.headersLeftOpen(1,
                connection.requestHead("GET", "/echo/continued"), true);
        for (int i = 0; i < 3; i++) {
            frames.appendBuffer(RawHttp2Connection.continuation(1,
                    List.of(Map.entry("x-part-" + i, Generators.letterStrings(64, 128).next())), i == 2));
        }

        Awaits.connect(connection.write(frames), "the header block to be sent");
        Awaits.until(() -> connection.status(1).isPresent() || connection.isClosed(),
                "the request to be answered", Awaits.CONNECT_CEILING_SECONDS);
        Awaits.teardown(connection.close(), "the raw HTTP/2 connection to close");

        assertAll("a header block within the bound",
                () -> assertEquals(Optional.of(200), connection.status(1), "the request is served"),
                () -> assertEquals(Optional.empty(), connection.goAwayCode(), "the edge sends no GOAWAY"),
                () -> assertTrue(upstreamStarted.contains("GET /continued"), "the request reached the upstream"));
    }

    /**
     * Zero-length {@code CONTINUATION} frames add nothing to the header block, so the header-list bound
     * cannot end them; the case drives {@value #EMPTY_CONTINUATIONS} of them behind an open
     * {@code HEADERS} frame, then a frame that would end the block, and asserts the outcome the
     * transport produces: the connection is ended before the stream is ever answered.
     */
    @Test
    @DisplayName("a flood of zero-length CONTINUATION frames ends the connection before the stream is answered")
    void zeroLengthContinuationFloodEndsTheConnection() throws Exception {
        RawHttp2Connection connection = RawHttp2Connection.open(netClient, frontPort);
        Buffer frames = RawHttp2Connection.headersLeftOpen(1,
                connection.requestHead("GET", "/echo/empty-continuations"), true);
        for (int i = 0; i < EMPTY_CONTINUATIONS; i++) {
            frames.appendBuffer(RawHttp2Connection.frame(RawHttp2Connection.CONTINUATION, 0, 1, new byte[0]));
        }

        // Written without awaiting: the edge may end the connection while the flood is still arriving.
        connection.write(frames);
        connection.write(RawHttp2Connection.frame(RawHttp2Connection.CONTINUATION,
                RawHttp2Connection.FLAG_END_HEADERS, 1, new byte[0]));
        Awaits.until(() -> connection.isClosed() || connection.status(1).isPresent(),
                "the edge to end the connection or answer the stream", Awaits.CONNECT_CEILING_SECONDS);
        int afterStatus = statusOfAFreshConnection();

        assertAll("a zero-length CONTINUATION flood",
                () -> assertTrue(connection.isClosed(),
                        () -> "the flooding connection must be ended: GOAWAY " + connection.goAwayCode()
                                + ", status " + connection.status(1)),
                () -> assertEquals(Optional.empty(), connection.status(1), "the flooded stream is never answered"),
                () -> assertFalse(upstreamStarted.contains("GET /empty-continuations"),
                        "nothing of the flooded stream reaches the upstream"),
                () -> assertEquals(200, afterStatus, "the edge keeps serving a fresh connection"));
    }

    /** Sends a {@code GET} over a fresh HTTP/2 connection and returns the status it was answered with. */
    private int statusOfAFreshConnection() throws Exception {
        return Awaits.connect(client().request(io.vertx.core.http.HttpMethod.GET, frontPort, LoopbackHost.ADDRESS,
                        "/echo/after").compose(request -> request.send().map(response -> response.statusCode())),
                "a fresh connection to be served");
    }

    /** A prior-knowledge HTTP/2 client, closed in {@link #tearDown()}. */
    private HttpClient client() {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
        httpClients.add(client);
        return client;
    }
}
