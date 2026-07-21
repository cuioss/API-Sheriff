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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.quarkus.SheriffMetrics;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("fails boot fast when a route requires session authentication")
    void failsBootForSessionAuth() {
        // Arrange
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, "session")));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> newEdge(sessionTable),
                "Session auth is not implemented and must fail boot");

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType(),
                "A session-auth route is rejected as an invalid configuration");
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
    @DisplayName("fails boot fast for a session-auth WebSocket route (session unimplemented)")
    void failsBootForSessionAuthWebSocket() {
        // Arrange
        RouteTable webSocketTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, "session")));

        // Act — session-auth WebSocket routes remain unimplemented until Plan 07
        GatewayException thrown = assertThrows(GatewayException.class, () -> newEdge(webSocketTable),
                "Session-auth WebSocket routes are not yet implemented and must fail boot");

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType(),
                "A session-auth WebSocket route is rejected as an invalid configuration");
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

    private GatewayEdgeRoute newEdge(RouteTable table) {
        return new GatewayEdgeRoute(table, gatewayConfig, new SingletonInstance<>(tokenValidator), vertx,
                virtualThreadExecutor, hardening, new SheriffMetrics(new SimpleMeterRegistry()));
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
