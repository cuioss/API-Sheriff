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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Boot-time and lifecycle contract of the public data-plane edge. The per-request serving behaviour
 * (pipeline stages over a live Vert.x server, h2 abuse bounds, streamed relay on the public port) is
 * exercised end-to-end by the {@code integration-tests} module; these module tests cover the
 * deterministic, server-free guarantees: clean boot assembly, fail-fast on an invalid route set,
 * the catch-all registered last, and a bounded graceful drain.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — boot-time assembly, catch-all registration, and graceful drain")
class GatewayEdgeRouteTest {

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private GatewayConfig gatewayConfig;
    private TokenValidator tokenValidator;
    private EdgeHardeningOptions hardening;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        gatewayConfig = GatewayConfig.builder().version(1).build();
        tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        hardening = new EdgeHardeningOptions();
    }

    @AfterEach
    void tearDown() {
        virtualThreadExecutor.close();
        vertx.close();
    }

    @Test
    @DisplayName("boots cleanly over an empty route table")
    void bootsCleanlyOverEmptyRouteTable() {
        // Arrange
        RouteTable emptyTable = new RouteTable(List.of());

        // Act + Assert — assembling every stage once, at boot, must not throw for a valid config.
        assertDoesNotThrow(() -> newEdge(emptyTable),
                "A valid route set assembles every stage without error");
    }

    @Test
    @DisplayName("registers the catch-all data-plane route so management routes keep priority")
    void registersCatchAllRoute() {
        // Arrange
        GatewayEdgeRoute edge = newEdge(new RouteTable(List.of()));
        Router router = Router.router(vertx);

        // Act
        edge.registerRoutes(router);

        // Assert — exactly one catch-all route is registered; it is added last so management /
        // health routes registered earlier keep priority.
        assertEquals(1, router.getRoutes().size(), "The edge registers a single catch-all route");
    }

    @Test
    @DisplayName("boots a require:session route now the boot-time rejection is removed (D4)")
    void bootsSessionAuthRoute() {
        // Arrange
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, "session")));

        // Act + Assert — a require:session route now assembles at boot; its stage-4 runtime is the
        // SessionAuthenticationStage (D4), which replaced the boot-time CONFIG_INVALID rejection. This
        // edge wires no session runtime, so such a route is only rejected at request time, not at boot.
        assertDoesNotThrow(() -> newEdge(sessionTable),
                "A require:session route assembles at boot now the boot-time rejection is removed");
    }

    @Test
    @DisplayName("boots a gRPC route (now served by the gRPC processor)")
    void bootsGrpcProtocol() {
        // Arrange
        RouteTable grpcTable = new RouteTable(List.of(
                route("g", Protocol.GRPC, "none")));

        // Act + Assert — GRPC is now registered, so a gRPC route assembles cleanly at boot (the boot
        // rejection was removed with the gRPC processor).
        assertDoesNotThrow(() -> newEdge(grpcTable),
                "A gRPC route is served by the registered gRPC processor and boots cleanly");
    }

    @Test
    @DisplayName("boots a WebSocket route (now served by the WebSocket processor)")
    void bootsWebSocketProtocol() {
        // Arrange
        RouteTable webSocketTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, "none")));

        // Act + Assert — WEBSOCKET is now registered, so a WebSocket route assembles cleanly at boot
        // (the boot rejection was removed with the WebSocket processor).
        assertDoesNotThrow(() -> newEdge(webSocketTable),
                "A WebSocket route is served by the registered WebSocket processor and boots cleanly");
    }

    @Test
    @DisplayName("boots a session-auth WebSocket route now the boot-time rejection is removed (D4)")
    void bootsSessionAuthWebSocketRoute() {
        // Arrange
        RouteTable webSocketTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, "session")));

        // Act + Assert — session auth no longer gates boot, so a session-auth WebSocket route
        // assembles exactly like any other WebSocket route.
        assertDoesNotThrow(() -> newEdge(webSocketTable),
                "A session-auth WebSocket route assembles at boot now session auth no longer fails boot");
    }

    @Test
    @DisplayName("drains within the bounded window on shutdown when nothing is in flight")
    void drainsPromptlyWhenIdle() {
        // Arrange
        GatewayEdgeRoute edge = newEdge(new RouteTable(List.of()));

        // Act + Assert — with zero in-flight requests the drain loop returns immediately, well
        // within its bounded window, so the shutdown completes cleanly and never hangs.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> edge.onShutdown(new ShutdownEvent()),
                "Graceful drain returns promptly when no request is in flight");
    }

    /**
     * The admission-accounting plumbing a WebSocket route depends on. {@code handle()} registers its
     * release inside {@code ctx.addEndHandler}, but a completed WebSocket upgrade takes the connection
     * over so that handler never fires — the release CAS guard is therefore stashed on the
     * {@link io.vertx.ext.web.RoutingContext} and read back by the WebSocket branch, which releases at
     * relay teardown instead. These tests pin both halves of that seam against a refactor that reverts
     * to the end-handler-only assumption and silently strands one permit per upgrade.
     */
    @Nested
    @DisplayName("admission-release guard stashed on the RoutingContext")
    class AdmissionGuardPlumbing {

        /** Mirrors the private {@code GatewayEdgeRoute.ADMISSION_GUARD_KEY}. */
        private static final String ADMISSION_GUARD_KEY = "sheriff.admissionguard";

        @Test
        @DisplayName("stashes the release guard on the context before the pipeline is dispatched")
        void stashesReleaseGuardBeforeDispatch() throws Exception {
            // Arrange — an empty route table renders 404 without dialing anything; a probe handler
            // registered ahead of the catch-all reads the stash back the moment handle() returns.
            CompletableFuture<Object> stashed = new CompletableFuture<>();
            HttpServer front = startFront(new RouteTable(List.of()), stashed);
            HttpClient client = vertx.createHttpClient();
            try {
                // Act
                client.request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), "localhost", "/nothing")
                        .compose(HttpClientRequest::send);

                // Assert
                assertInstanceOf(AtomicBoolean.class, stashed.get(15, TimeUnit.SECONDS),
                        "handle() stashes the admission-release CAS guard under its context key");
            } finally {
                client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                front.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        @Test
        @DisplayName("the WebSocket branch reads the guard back and releases at relay teardown")
        void webSocketBranchReleasesThroughTheStashedGuard() throws Exception {
            // Arrange — a real upgrade against a stub upstream, so the HTTP response never ends
            HttpServer upstream = vertx.createHttpServer()
                    .webSocketHandler(ws -> ws.textMessageHandler(ws::writeTextMessage))
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            CompletableFuture<Object> stashed = new CompletableFuture<>();
            HttpServer front = startFront(new RouteTable(List.of(webSocketRoute(upstream.actualPort()))), stashed);
            WebSocketClient client = vertx.createWebSocketClient();
            try {
                WebSocket socket = connectWs(client, front.actualPort());
                AtomicBoolean guard = assertInstanceOf(AtomicBoolean.class, stashed.get(15, TimeUnit.SECONDS),
                        "the WebSocket request stashes the same release guard");
                assertFalse(guard.get(),
                        "an established relay still holds its admission permit — release at upgrade "
                                + "completion would under-count concurrent relays");

                // Act
                socket.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

                // Assert — nothing ever ended the HTTP response, so only the relay's teardown callback
                // can have flipped the guard
                awaitReleased(guard, "the WebSocket relay releases the admission permit at teardown");
            } finally {
                client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                front.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                upstream.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        @Test
        @DisplayName("bounds concurrent relays by the sub-budget and returns both permits at teardown")
        void boundsConcurrentRelaysByTheSubBudget() throws Exception {
            // Arrange — admission_cap 2 with a websocket_relay_cap of 1, so a single established relay
            // exhausts the sub-budget while leaving one general permit for ordinary traffic
            HttpServer upstream = vertx.createHttpServer()
                    .webSocketHandler(ws -> ws.textMessageHandler(ws::writeTextMessage))
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            Router router = Router.router(vertx);
            new GatewayEdgeRoute(new RouteTable(List.of(webSocketRoute(upstream.actualPort()))), gatewayConfig,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(new EdgeHardeningConfig(Optional.of(2), Optional.of(1))),
                    new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert()).registerRoutes(router);
            HttpServer front = vertx.createHttpServer().requestHandler(router)
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            WebSocketClient wsClient = vertx.createWebSocketClient();
            HttpClient httpClient = vertx.createHttpClient();
            try {
                WebSocket held = connectWs(wsClient, front.actualPort());

                // Act + Assert — further upgrades are refused while the one relay slot is occupied
                for (int attempt = 0; attempt < 5; attempt++) {
                    ExecutionException refused = assertThrows(ExecutionException.class,
                            () -> connectWs(wsClient, front.actualPort()));
                    assertEquals(503,
                            assertInstanceOf(UpgradeRejectedException.class, refused.getCause()).getStatus(),
                            "an upgrade beyond the relay sub-budget is refused 503");
                }

                // Assert — each refusal returned the general permit it had already taken; had it not,
                // five refusals would have drained the two-permit pool and this would answer 503
                assertEquals(404, statusOf(httpClient, front.actualPort()),
                        "a refused upgrade releases the general admission permit it was holding");

                // Act — tearing the relay down must return the sub-permit too
                held.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

                // Assert
                connectWhenAdmitted(wsClient, front.actualPort())
                        .close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            } finally {
                httpClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                wsClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                front.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                upstream.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        private HttpServer startFront(RouteTable table, CompletableFuture<Object> stashed) throws Exception {
            Router router = Router.router(vertx);
            // Registered before the edge, which adds its catch-all last: ctx.next() runs handle()
            // synchronously up to the asynchronous dispatch, so the stash is visible on return.
            router.route().handler(ctx -> {
                ctx.next();
                stashed.complete(ctx.get(ADMISSION_GUARD_KEY));
            });
            newEdge(table).registerRoutes(router);
            return vertx.createHttpServer().requestHandler(router)
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }

    private WebSocket connectWs(WebSocketClient client, int port) throws Exception {
        return client.connect(new WebSocketConnectOptions()
                        .setHost("localhost").setPort(port).setURI("/w/room"))
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Retries the upgrade until the relay sub-permit released at teardown becomes visible. The release
     * lands on the Vert.x event loop after the socket close round-trips, so the first attempt can
     * legitimately still see the exhausted budget.
     */
    // NOSONAR java:S2925 - Thread.sleep is load-bearing: see awaitReleased.
    @SuppressWarnings("java:S2925")
    private WebSocket connectWhenAdmitted(WebSocketClient client, int port) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                return connectWs(client, port);
            } catch (ExecutionException refused) {
                Thread.sleep(25);
            }
        }
        throw new AssertionFailedError(
                "no upgrade was admitted after teardown — the relay sub-permit was never returned");
    }

    private static int statusOf(HttpClient client, int port) throws Exception {
        return client.request(io.vertx.core.http.HttpMethod.GET, port, "localhost", "/unmatched")
                .compose(HttpClientRequest::send)
                .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS).statusCode();
    }

    // NOSONAR java:S2925 - Thread.sleep is load-bearing: relay teardown is a real socket round-trip on
    // a live Vert.x event loop with no virtual clock to advance, so the guard is polled until it flips.
    @SuppressWarnings("java:S2925")
    private static void awaitReleased(AtomicBoolean guard, String message) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && !guard.get(); attempt++) {
            Thread.sleep(25);
        }
        assertTrue(guard.get(), message);
    }

    private static ResolvedRoute webSocketRoute(int upstreamPort) {
        return ResolvedRoute.builder()
                .id("w")
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix("/w").build())
                .effectiveAuth(AuthConfig.builder().require("none").build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(new ResolvedUpstream("http", "localhost", upstreamPort, "")))
                .build();
    }

    private GatewayEdgeRoute newEdge(RouteTable table) {
        return new GatewayEdgeRoute(table, gatewayConfig, new SingletonInstance<>(tokenValidator), vertx,
                virtualThreadExecutor, hardening, new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert());
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied bean. These boot / drain
     * tests exercise only {@link #get()} (and none of them reaches a {@code require: bearer} route, so
     * even that is not resolved); the remaining CDI accessors are unused and throw.
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

    private static ResolvedRoute route(String id, Protocol protocol, String require) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(new ResolvedUpstream("https", id + ".example", 443, "")))
                .build();
    }
}
