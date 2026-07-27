/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the byte ceiling the edge enforces on a gateway-terminated reserved POST body (the OIDC
 * {@code response_mode=form_post} callback and the back-channel logout receiver), driven over a live
 * Vert.x HTTP server — no Docker, no Quarkus.
 * <p>
 * Those two paths are read <em>pre-authentication</em> and <em>before</em> the pipeline's per-route
 * {@code maxBodySize} cap can apply, so without a ceiling at the read itself an unauthenticated caller
 * could buffer an arbitrarily large body straight into the gateway heap. The ceiling is enforced twice
 * — a {@code Content-Length} pre-check and a streaming cumulative counter — and both arms are pinned
 * here, because {@code Content-Length} is attacker-controlled and absent entirely under chunked
 * transfer-encoding.
 * <p>
 * The gateway under test wires the {@linkplain BffRuntime#inert() inert} BFF runtime, so a reserved
 * path that <em>passes</em> the ceiling is carved out of proxy dispatch and rendered {@code 404}.
 * {@code 404} is therefore the observable "the body was accepted and the pipeline ran" outcome, and
 * {@code 413} the "the read itself refused it" outcome — the two are unambiguous.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — reserved-path request-body byte ceiling")
class ReservedBodyCeilingTest {

    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String CRLF = "\r\n";
    /** Bounded wait proving the pre-check refused BEFORE buffering: far below the 20s body deadline. */
    private static final long NO_BUFFERING_TIMEOUT_SECONDS = 5L;

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer frontServer;
    private HttpClient client;
    private NetClient netClient;
    private int frontPort;
    private long ceiling;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        GatewayConfig gatewayConfig = GatewayConfig.builder()
                .version(1)
                .oidc(Optional.of(OidcConfig.builder()
                        .redirectUri(Optional.of("https://localhost" + CALLBACK_PATH))
                        .build()))
                .build();
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        ceiling = hardening.reservedBodyMaxBytes();

        GatewayEdgeRoute edge = new GatewayEdgeRoute(new RouteTable(List.of()), gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, hardening,
                new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert());

        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = vertx.createHttpServer().requestHandler(router)
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        frontPort = frontServer.actualPort();

        client = vertx.createHttpClient();
        netClient = vertx.createNetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        netClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        frontServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        virtualThreadExecutor.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("accepts a reserved POST body exactly at the ceiling")
    void acceptsBodyExactlyAtTheCeiling() throws Exception {
        String body = formBody(ceiling);

        int status = post(CALLBACK_PATH, body);

        assertEquals(ceiling, body.length(),
                "The arranged body must sit exactly on the boundary for this to pin the ceiling");
        assertEquals(404, status,
                "A body at the ceiling is read and the pipeline runs to the reserved carve-out (404), never 413");
    }

    @Test
    @DisplayName("rejects a reserved POST body one byte over the ceiling with 413")
    void rejectsBodyOverTheCeiling() throws Exception {
        String body = formBody(ceiling + 1);

        int status = post(CALLBACK_PATH, body);

        assertEquals(413, status,
                "A body beyond the ceiling is refused 413 — the honest status for an over-large payload");
    }

    @Test
    @DisplayName("rejects an over-ceiling declared Content-Length without buffering any body byte")
    void rejectsOverCeilingContentLengthWithoutBuffering() {
        // A raw request head declaring far more than the ceiling, with ZERO body bytes ever sent. A
        // gateway that buffered first would still be blocked on its 20-second body deadline; only a
        // gateway that refuses on the declared length alone can answer inside the bounded wait below.
        String head = "POST " + CALLBACK_PATH + " HTTP/1.1" + CRLF
                + "Host: localhost:" + frontPort + CRLF
                + "Content-Length: " + (ceiling * 64) + CRLF
                + "Connection: close" + CRLF + CRLF;

        CompletableFuture<String> responded = sendRaw(head);

        String statusLine = assertDoesNotThrow(
                () -> responded.get(NO_BUFFERING_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "An over-ceiling Content-Length must be refused before any body byte is buffered");
        assertTrue(statusLine.startsWith("HTTP/1.1 413"),
                "The declared-length pre-check refuses 413: " + statusLine);
    }

    @Test
    @DisplayName("rejects an over-ceiling chunked body whose declared length is absent")
    void rejectsOverCeilingChunkedBody() throws Exception {
        // Chunked transfer-encoding carries NO Content-Length, so the pre-check is structurally blind
        // to it — exactly the bypass the streaming cumulative counter exists to close. Each chunk stays
        // under the transport's per-chunk bound; only their SUM crosses the ceiling.
        //
        // This IS the "declared length understates the actual body" case at the protocol level: over
        // HTTP/1.1 a body cannot outrun an honest Content-Length (the codec stops the body at the
        // declared octet and reads the surplus as a new pipelined message), so the only framing in
        // which the real size can exceed what the pre-check can see is a length that is absent
        // altogether. That is what is exercised here.
        int chunkSize = 1024;
        long chunks = ceiling / chunkSize + 2;
        StringBuilder raw = new StringBuilder("POST " + CALLBACK_PATH + " HTTP/1.1" + CRLF
                + "Host: localhost:" + frontPort + CRLF
                + "Transfer-Encoding: chunked" + CRLF
                + "Connection: close" + CRLF + CRLF);
        String chunk = "x".repeat(chunkSize);
        for (long i = 0; i < chunks; i++) {
            raw.append(Integer.toHexString(chunkSize)).append(CRLF).append(chunk).append(CRLF);
        }
        raw.append('0').append(CRLF).append(CRLF);

        String statusLine = sendRaw(raw.toString()).get(15, TimeUnit.SECONDS);

        assertTrue(chunks * chunkSize > ceiling,
                "The arranged chunked body must exceed the ceiling for this to pin the streaming counter");
        assertTrue(statusLine.startsWith("HTTP/1.1 413"),
                "The streaming cumulative counter refuses 413 with no declared length present: " + statusLine);
    }

    /**
     * Builds a urlencoded form body of exactly {@code length} characters, shaped like the
     * {@code state=…} a real form_post callback carries so the payload is representative rather than
     * arbitrary filler.
     */
    private static String formBody(long length) {
        String prefix = "state=" + Generators.letterStrings(8, 16).next() + "&code=";
        return prefix + "a".repeat((int) length - prefix.length());
    }

    /** POSTs {@code body} to {@code path} over a real HTTP client and returns the response status. */
    private int post(String path, String body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setHost("localhost").setPort(frontPort).setMethod(HttpMethod.POST).setURI(path);
        return client.request(options)
                .compose(request -> request.send(Buffer.buffer(body)))
                .map(response -> {
                    int status = response.statusCode();
                    response.body();
                    return status;
                })
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Writes a verbatim HTTP/1.1 message over a raw socket and completes with the response status line.
     * The raw socket is what makes the two framing shapes above expressible at all — an HTTP client
     * would never emit a declared length it does not honour, nor let the test choose chunk boundaries.
     */
    private CompletableFuture<String> sendRaw(String rawRequest) {
        CompletableFuture<String> statusLine = new CompletableFuture<>();
        netClient.connect(frontPort, "localhost")
                .onFailure(statusLine::completeExceptionally)
                .onSuccess(socket -> {
                    readStatusLine(socket, statusLine);
                    socket.write(rawRequest).onFailure(cause -> {
                        // A mid-write reset is expected once the gateway has already answered 413 and
                        // closed; only fail the wait when no status line ever arrived.
                        if (!statusLine.isDone()) {
                            statusLine.completeExceptionally(cause);
                        }
                    });
                });
        return statusLine;
    }

    private static void readStatusLine(NetSocket socket, CompletableFuture<String> statusLine) {
        Buffer received = Buffer.buffer();
        socket.handler(chunk -> {
            received.appendBuffer(chunk);
            int eol = received.toString().indexOf(CRLF);
            if (eol >= 0 && !statusLine.isDone()) {
                statusLine.complete(received.toString().substring(0, eol));
                socket.close();
            }
        });
        socket.exceptionHandler(cause -> {
            if (!statusLine.isDone()) {
                statusLine.completeExceptionally(cause);
            }
        });
        socket.endHandler(end -> {
            if (!statusLine.isDone()) {
                statusLine.completeExceptionally(new IllegalStateException("connection closed with no response"));
            }
        });
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied validator; no reserved-path
     * test reaches a {@code require: bearer} route, so the remaining CDI accessors are unused and throw.
     */
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
