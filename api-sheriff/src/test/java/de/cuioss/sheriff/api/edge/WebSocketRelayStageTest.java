/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.quarkus.SheriffMetrics;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end contract of the WebSocket protocol-dispatch seam, driven over a live Vert.x front server
 * that hosts the {@link GatewayEdgeRoute} against a local stub upstream WebSocket echo server — no
 * Docker, no Quarkus. A real upgrade crosses the whole fixed pipeline, the {@code OriginValidationStage},
 * and the {@link WebSocketRelayStage}: Origin enforcement and bearer auth reject the handshake before
 * the upstream is ever dialed, an accepted upgrade relays frames opaquely in both directions, an
 * unreachable upstream maps to {@code 502} before the {@code 101}, and an idle relay is reclaimed while
 * a heartbeated one survives. The Docker-backed matrix in {@code integration-tests} complements these
 * server-local guarantees.
 */
@EnableGeneratorController
@DisplayName("WebSocketRelayStage — end-to-end WebSocket dispatch over a live Vert.x server")
class WebSocketRelayStageTest {

    private static final String ALLOWED_ORIGIN = "https://app.example";
    private static final String FOREIGN_ORIGIN = "https://evil.example";

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private WebSocketClient wsClient;
    private int frontPort;
    private int deadPort;
    private final AtomicInteger upstreamConnects = new AtomicInteger();
    private final AtomicReference<String> upstreamCustomHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Stub upstream WebSocket echo server: records each accepted handshake and echoes text frames.
        upstreamServer = vertx.createHttpServer().webSocketHandler(ws -> {
            upstreamConnects.incrementAndGet();
            upstreamCustomHeader.set(ws.headers().get("X-Custom"));
            ws.textMessageHandler(ws::writeTextMessage);
        }).listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        int upstreamPort = upstreamServer.actualPort();

        // A definitely-closed port for the unreachable-upstream case.
        HttpServer throwaway = vertx.createHttpServer().requestHandler(req -> req.response().end())
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        deadPort = throwaway.actualPort();
        throwaway.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

        RouteTable routeTable = new RouteTable(List.of(
                wsRoute("wsopen", "/ws-open", "none", upstreamPort, Set.of(), Optional.empty()),
                wsRoute("wsorigin", "/ws-origin", "none", upstreamPort, Set.of(ALLOWED_ORIGIN), Optional.empty()),
                wsRoute("wssecure", "/ws-secure", "bearer", upstreamPort, Set.of(ALLOWED_ORIGIN), Optional.empty()),
                wsRoute("wsidle", "/ws-idle", "none", upstreamPort, Set.of(), Optional.of(1)),
                wsRoute("wsdead", "/ws-dead", "none", deadPort, Set.of(), Optional.empty())));

        GatewayConfig gatewayConfig = GatewayConfig.builder()
                .version(1)
                .securityHeaders(Optional.of(securityHeaders()))
                .build();
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()));

        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = vertx.createHttpServer().requestHandler(router)
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        frontPort = frontServer.actualPort();

        wsClient = vertx.createWebSocketClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        wsClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        frontServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        upstreamServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        virtualThreadExecutor.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("relays text frames opaquely in both directions once the upgrade is accepted")
    void relaysBidirectionalTextFrames() throws Exception {
        // Arrange
        WebSocket socket = connect("/ws-open/room", ALLOWED_ORIGIN);
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        socket.writeTextMessage("hello-relay");

        // Assert — the frame crosses to the upstream, is echoed, and relays back to the client
        assertEquals("hello-relay", echoed.get(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("rejects a foreign Origin with 403 before dialing the upstream")
    void rejectsForeignOriginBeforeDial() {
        // Act
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-origin/room", FOREIGN_ORIGIN));

        // Assert — the upgrade is refused 403 and the upstream is never contacted
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(403, rejected.getStatus());
        assertEquals(0, upstreamConnects.get(), "a rejected Origin never reaches the upstream");
    }

    @Test
    @DisplayName("accepts an allow-listed Origin and relays")
    void acceptsAllowlistedOrigin() throws Exception {
        // Arrange
        WebSocket socket = connect("/ws-origin/room", ALLOWED_ORIGIN);
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        socket.writeTextMessage("allowed");

        // Assert
        assertEquals("allowed", echoed.get(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("rejects a bearer handshake without a token 401 before dialing the upstream")
    void rejectsMissingBearerTokenBeforeDial() {
        // Act
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-secure/room", ALLOWED_ORIGIN));

        // Assert — authentication (stage 4) rejects the handshake before the WebSocket dispatch runs
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(401, rejected.getStatus());
        assertEquals(0, upstreamConnects.get(), "an unauthenticated handshake never reaches the upstream");
    }

    @Test
    @DisplayName("maps an unreachable upstream to 502 before the 101 upgrade")
    void mapsUnreachableUpstreamTo502() {
        // Act
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-dead/room", ALLOWED_ORIGIN));

        // Assert
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(502, rejected.getStatus());
    }

    @Test
    @DisplayName("preserves the stage-0 security headers on a WebSocket handshake failure")
    void preservesSecurityHeadersOnHandshakeFailure() {
        // Act — the /ws-dead route's upstream is unreachable, so the handshake fails 502 before the 101
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-dead/room", ALLOWED_ORIGIN));

        // Assert — the failed-handshake response still carries the gateway (stage-0) security headers,
        // mirroring the HTTP (ResponseStage.relay) and gRPC (GrpcStatusMapper.renderRejection) contract
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(502, rejected.getStatus());
        MultiMap headers = rejected.getHeaders();
        assertEquals("nosniff", headers.get("X-Content-Type-Options"),
                "a failed WebSocket handshake carries the stage-0 X-Content-Type-Options header");
        assertEquals("DENY", headers.get("X-Frame-Options"),
                "a failed WebSocket handshake carries the stage-0 X-Frame-Options header");
    }

    @Test
    @DisplayName("forwards no non-allow-listed handshake header to the upstream (deny-by-default)")
    void deniesNonAllowlistedForwardHeader() throws Exception {
        // Arrange — a custom header the route's (empty) forward allowlist does not permit
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost("localhost").setPort(frontPort).setURI("/ws-open/room")
                .addHeader("Origin", ALLOWED_ORIGIN).addHeader("X-Custom", "leak");
        WebSocket socket = wsClient.connect(options).toCompletionStage().toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        socket.writeTextMessage("go");
        echoed.get(15, TimeUnit.SECONDS);

        // Assert — the upstream handshake never saw the denied header
        assertNull(upstreamCustomHeader.get(),
                "a header outside the deny-by-default forward allowlist is not relayed to the upstream");
    }

    @Test
    @DisplayName("reclaims an idle relay after the per-route idle timeout, closing 1001")
    void reclaimsIdleRelay() throws Exception {
        // Arrange — the /ws-idle route has idle_timeout_seconds=1
        WebSocket socket = connect("/ws-idle/room", ALLOWED_ORIGIN);
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(v -> closeCode.complete(socket.closeStatusCode()));

        // Act + Assert — with no frame in either direction the relay is reclaimed and closed 1001
        assertEquals((short) 1001, closeCode.get(10, TimeUnit.SECONDS),
                "an idle relay is closed with WebSocket code 1001 (Going Away)");
    }

    @Test
    @DisplayName("keeps a heartbeated relay open past the idle window")
    // NOSONAR java:S2925 - Thread.sleep is load-bearing: the assertion under test is that
    // sub-second real activity, spaced across the real Vert.x idle-timer window, keeps the relay
    // alive; the idle reclaim is a real setTimer, and no virtual clock is available to simulate it.
    @SuppressWarnings("java:S2925")
    void heartbeatKeepsRelayOpen() throws Exception {
        // Arrange — the /ws-idle route idles after 1s; keep it busy with sub-second activity
        WebSocket socket = connect("/ws-idle/room", ALLOWED_ORIGIN);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.closeHandler(closed::complete);

        // Act — three exchanges 400ms apart span past the 1s idle window, each resetting the timer
        for (int i = 0; i < 3; i++) {
            CompletableFuture<String> echoed = new CompletableFuture<>();
            socket.textMessageHandler(echoed::complete);
            socket.writeTextMessage("beat-" + i);
            echoed.get(5, TimeUnit.SECONDS);
            Thread.sleep(400);
        }

        // Assert — activity kept the relay alive; it was never reclaimed
        assertFalse(closed.isDone(), "a heartbeated relay is not reclaimed while activity continues");
    }

    private WebSocket connect(String uri, String origin) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost("localhost").setPort(frontPort).setURI(uri).addHeader("Origin", origin);
        return wsClient.connect(options).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static SecurityHeadersConfig securityHeaders() {
        return SecurityHeadersConfig.builder()
                .contentTypeNosniff(Optional.of(Boolean.TRUE))
                .frameDeny(Optional.of(Boolean.TRUE))
                .build();
    }

    private static ResolvedRoute wsRoute(String id, String pathPrefix, String require, int upstreamPort,
            Set<String> allowedOrigins, Optional<Integer> idleTimeoutSeconds) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(new ResolvedUpstream("http", "localhost", upstreamPort, "")))
                .effectiveAllowedOrigins(allowedOrigins)
                .effectiveWebSocketIdleTimeoutSeconds(idleTimeoutSeconds)
                .build();
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied validator; only
     * {@link #get()} and {@link #iterator()} are exercised, the remaining CDI accessors throw.
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
