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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link InboundStreamWindow} read against a live Vert.x HTTP/2 server: it attaches to the stream of an
 * HTTP/2 request — and to nothing else — and reports what the transport knows of it. A request paused
 * by its handler returns no window to the client, so the window it reports shrinks by exactly the bytes
 * the client sends, and the stream stops being open to the client once the client ends it.
 * <p>
 * This is the test that pins the one Vert.x internal the class reaches through: should a Vert.x upgrade
 * change it, the attach fails here rather than silently in production.
 */
@EnableGeneratorController
@DisplayName("InboundStreamWindow — the transport's view of an inbound HTTP/2 stream")
@Tag("isolated-fork")
class InboundStreamWindowTest {

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    /** The first request the server received. */
    private final CompletableFuture<HttpServerRequest> received = new CompletableFuture<>();
    /** Every request the server received, in arrival order. */
    private final List<HttpServerRequest> allReceived = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
            request.pause();
            allReceived.add(request);
            received.complete(request);
        }).listen(0, LoopbackHost.ADDRESS), "the server to start listening");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            Awaits.teardown(client.close(), "the client to close");
        }
        Awaits.teardown(server.close(), "the server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("HTTP/2 — the window of a paused request shrinks by the bytes sent; ending the body closes the stream to the client")
    void windowOfAPausedRequestShrinksByTheBytesSent() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        String part = Generators.letterStrings(256, 1_024).next();
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        int initial = new HttpServerOptions().getInitialSettings().getInitialWindowSize();

        View before = look(window);
        Awaits.connect(upload.write(part), "part of the body to be sent");
        Awaits.until(() -> look(window).sendable() == initial - part.length(),
                "the window to shrink by the part sent", Awaits.CONNECT_CEILING_SECONDS);
        Awaits.connect(upload.end(), "the body to be ended");
        Awaits.until(() -> !look(window).remoteOpen(), "the stream to close to the client",
                Awaits.CONNECT_CEILING_SECONDS);
        View ended = look(window);

        assertAll("the window of a paused HTTP/2 request",
                () -> assertEquals(new View(true, initial), before, "a fresh stream holds the whole initial window"),
                () -> assertEquals(new View(false, 0), ended, "an ended stream lets the client send nothing more"));
    }

    @Test
    @DisplayName("HTTP/2 — what a client may send is bounded by the connection's window as well as the stream's")
    void sendableIsBoundedByTheConnectionWindow() throws Exception {
        int initial = new HttpServerOptions().getInitialSettings().getInitialWindowSize();
        // Each portion fits its own stream window; the two together exceed the one connection window.
        Buffer portion = Buffer.buffer(Generators.letterStrings(initial * 2 / 3, initial * 2 / 3).next());
        HttpClientRequest first = open(HttpVersion.HTTP_2);
        HttpClientRequest second = Awaits.connect(client.request(HttpMethod.POST, server.actualPort(),
                LoopbackHost.ADDRESS, "/second"), "the second request to open on the same connection");
        Awaits.connect(first.setChunked(true).sendHead(), "the first head to be sent");
        Awaits.connect(second.setChunked(true).sendHead(), "the second head to be sent");
        Awaits.until(() -> allReceived.size() == 2, "the server to receive both requests",
                Awaits.CONNECT_CEILING_SECONDS);
        InboundStreamWindow firstWindow = InboundStreamWindow.of(allReceived.get(0)).orElseThrow();
        InboundStreamWindow secondWindow = InboundStreamWindow.of(allReceived.get(1)).orElseThrow();

        first.write(portion);
        second.write(portion);
        Awaits.until(() -> look(firstWindow).sendable() == 0 && look(secondWindow).sendable() == 0,
                "the connection window to be used up", Awaits.CONNECT_CEILING_SECONDS);

        assertAll("two streams sharing one used-up connection window",
                () -> assertSame(first.connection(), second.connection(), "both streams share the connection"),
                () -> assertTrue(look(firstWindow).remoteOpen() && look(secondWindow).remoteOpen(),
                        "neither client side has ended"),
                () -> assertTrue(portion.length() < initial,
                        "neither stream can have used up its own window, so the connection's bounds both"));
    }

    @Test
    @DisplayName("HTTP/2 — the gateway's side of the stream has ended as soon as its response end is handed over")
    void localSideEndsWhenTheResponseEnds() throws Exception {
        HttpClientRequest upload = open(HttpVersion.HTTP_2);
        Awaits.connect(upload.setChunked(true).sendHead(), "the upload head to be sent");
        HttpServerRequest request = Awaits.connect(received, "the server to receive the upload");
        InboundStreamWindow window = InboundStreamWindow.of(request).orElseThrow();
        CompletableFuture<Boolean> endedBefore = new CompletableFuture<>();
        CompletableFuture<Boolean> endedInTheSameTurn = new CompletableFuture<>();

        window.execute(() -> {
            endedBefore.complete(window.localEnded());
            request.response().end(Generators.letterStrings(4, 12).next());
            endedInTheSameTurn.complete(window.localEnded());
        });

        assertAll("the gateway's side of the stream",
                () -> assertFalse(Awaits.connect(endedBefore, "the look before the end")),
                () -> assertTrue(Awaits.connect(endedInTheSameTurn, "the look right after the end"),
                        "an end the transport accepts at once ends the gateway's side in the same turn"),
                () -> assertTrue(look(window).remoteOpen(), "the client's side is still open"));
    }

    @Test
    @DisplayName("HTTP/1.1 — no stream to attach to")
    void doesNotAttachToAnHttp1Request() throws Exception {
        HttpClientRequest request = open(HttpVersion.HTTP_1_1);
        Awaits.connect(request.setChunked(true).sendHead(), "the request head to be sent");

        Optional<InboundStreamWindow> window = InboundStreamWindow.of(Awaits.connect(received,
                "the server to receive the request"));

        assertTrue(window.isEmpty(), "an HTTP/1.1 request has no HTTP/2 stream");
    }

    @Test
    @DisplayName("without a view of the stream, EarlyResponseEnd ends at once and decides the reset after the write")
    void earlyResponseEndWithoutAViewEndsAtOnce() throws Exception {
        HttpClientRequest request = open(HttpVersion.HTTP_1_1);
        Awaits.connect(request.setChunked(true).sendHead(), "the request head to be sent");
        HttpServerRequest serverRequest = Awaits.connect(received, "the server to receive the request");
        AtomicInteger endings = new AtomicInteger();
        CompletableFuture<Boolean> resetAfterWrite = new CompletableFuture<>();

        io.vertx.core.Future<Void> ended = EarlyResponseEnd.endWhenQuiet(serverRequest, () -> {
            endings.incrementAndGet();
            return serverRequest.response().end();
        }, () -> resetAfterWrite.complete(serverRequest.response().ended()));
        int endingsAtReturn = endings.get();
        Awaits.connect(ended, "the response to be ended");

        assertAll("an end with no view of the stream",
                () -> assertEquals(1, endingsAtReturn, "the response is ended before the call returns"),
                () -> assertTrue(Awaits.connect(resetAfterWrite, "the reset to be decided"),
                        "the reset is decided only after the response was ended"),
                () -> assertFalse(serverRequest.isEnded(), "the request body was still open"));
    }

    private HttpClientRequest open(HttpVersion version) throws Exception {
        client = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(version)
                .setHttp2ClearTextUpgrade(false));
        return Awaits.connect(client.request(HttpMethod.POST, server.actualPort(), LoopbackHost.ADDRESS, "/upload"),
                "the request to open");
    }

    /** Reads the stream on its event loop, where the transport's state lives. */
    private static View look(InboundStreamWindow window) throws Exception {
        CompletableFuture<View> view = new CompletableFuture<>();
        window.execute(() -> view.complete(new View(window.remoteOpen(), window.sendableBytes())));
        return Awaits.connect(view, "the stream to be looked at");
    }

    /** What one look at the stream saw. */
    private record View(boolean remoteOpen, int sendable) {
    }
}
