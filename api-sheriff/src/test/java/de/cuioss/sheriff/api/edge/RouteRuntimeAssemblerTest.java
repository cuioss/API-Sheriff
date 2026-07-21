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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.api.asset.DirectoryAssetSource;
import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.ForwardConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedAsset;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Vertx;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteRuntimeAssembler — boot-time assembly and heavy-object dedup")
class RouteRuntimeAssemblerTest {

    private Vertx vertx;
    private RouteRuntimeAssembler assembler;
    private RouteRuntimeAssembler.SecurityConfigurationFactory securityConfigFactory;
    private RouteRuntimeAssembler.UpstreamClientFactory clientFactory;
    private RouteRuntimeAssembler.ResilienceGuardFactory guardFactory;
    private RouteRuntimeAssembler.AssetSourceFactory assetSourceFactory;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        assembler = new RouteRuntimeAssembler(new ProtocolProcessorRegistry());
        securityConfigFactory = _ -> SecurityConfiguration.builder().build();
        clientFactory = _ -> vertx.createHttpClient();
        guardFactory = _ -> new StoredOnlyGuard();
        assetSourceFactory = asset -> new DirectoryAssetSource(Path.of(asset.directory().orElse("/tmp")),
                asset.access());
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("Should reuse one SecurityConfiguration for routes sharing a security-filter shape")
    void shouldReuseSecurityConfigurationForSharedShape() {
        SecurityFilterConfig sharedFilter = SecurityFilterConfig.builder().allowedPaths(List.of("/shared")).build();
        var invocations = new AtomicInteger();
        securityConfigFactory = filter -> {
            invocations.incrementAndGet();
            return SecurityConfiguration.builder().build();
        };
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", Optional.of(sharedFilter), upstream("a.example")),
                route("r2", Protocol.HTTP, "none", Optional.of(sharedFilter), upstream("a.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(1, invocations.get(), "The factory runs once for the shared filter shape");
        assertSame(runtimes.getFirst().getSecurityConfiguration().orElseThrow(),
                runtimes.get(1).getSecurityConfiguration().orElseThrow(),
                "Both routes hold the same SecurityConfiguration reference");
    }

    @Test
    @DisplayName("Should build distinct SecurityConfigurations for different security-filter shapes")
    void shouldBuildDistinctSecurityConfigurationsForDifferentShapes() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none",
                        Optional.of(SecurityFilterConfig.builder().allowedPaths(List.of("/a")).build()), upstream("a.example")),
                route("r2", Protocol.HTTP, "none",
                        Optional.of(SecurityFilterConfig.builder().allowedPaths(List.of("/b")).build()), upstream("a.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertNotSame(runtimes.getFirst().getSecurityConfiguration().orElseThrow(),
                runtimes.get(1).getSecurityConfiguration().orElseThrow(),
                "Distinct filter shapes must not share a SecurityConfiguration");
    }

    @Test
    @DisplayName("Should reuse one client for routes sharing an upstream tuple and split by tuple")
    void shouldReuseClientForSharedUpstreamTuple() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", Optional.empty(), upstream("a.example")),
                route("r2", Protocol.HTTP, "none", Optional.empty(), upstream("a.example")),
                route("r3", Protocol.HTTP, "none", Optional.empty(), upstream("b.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertSame(runtimes.getFirst().getHttpClient().orElseThrow(),
                runtimes.get(1).getHttpClient().orElseThrow(),
                "Routes sharing an upstream tuple reuse one client");
        assertNotSame(runtimes.getFirst().getHttpClient().orElseThrow(),
                runtimes.get(2).getHttpClient().orElseThrow(),
                "A different upstream tuple gets a distinct client");
    }

    @Test
    @DisplayName("Should preserve the route-table order")
    void shouldPreserveRouteTableOrder() {
        RouteTable table = new RouteTable(List.of(
                route("first", Protocol.HTTP, "none", Optional.empty(), upstream("a.example")),
                route("second", Protocol.GRAPHQL, "bearer", Optional.empty(), upstream("b.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(List.of("first", "second"), runtimes.stream().map(RouteRuntime::getId).toList(),
                "Assembly preserves the longest-prefix-first order");
    }

    @Test
    @DisplayName("Should fail boot for session auth and a session-auth WebSocket; gRPC and WebSocket assemble")
    void shouldFailBootForSessionAndAssembleProtocolRoutes() {
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, "session", Optional.empty(), upstream("a.example"))));
        var session = assertThrows(GatewayException.class,
                () -> assembler.assemble(sessionTable, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "session auth must fail boot");
        RouteTable webSocketTable = new RouteTable(List.of(
                route("sw", Protocol.WEBSOCKET, "session", Optional.empty(), upstream("a.example"))));
        var sessionWebSocket = assertThrows(GatewayException.class,
                () -> assembler.assemble(webSocketTable, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "session-auth WebSocket must fail boot");

        assertEquals(EventType.CONFIG_INVALID, session.getEventType(), "session rejection is a config failure");
        assertEquals(EventType.CONFIG_INVALID, sessionWebSocket.getEventType(),
                "session-auth WebSocket rejection is a config failure");

        // A gRPC route now assembles cleanly (its boot rejection was removed when the gRPC processor
        // was registered) — the forced-h2 upstream client is built by the injected client factory.
        RouteTable grpcTable = new RouteTable(List.of(
                route("g", Protocol.GRPC, "none", Optional.empty(), upstream("a.example"))));
        assertDoesNotThrow(
                () -> assembler.assemble(grpcTable, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "a gRPC route with non-session auth assembles cleanly");

        // A WebSocket route with non-session auth likewise assembles cleanly.
        RouteTable webSocketNoneTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, "none", Optional.empty(), upstream("a.example"))));
        assertDoesNotThrow(
                () -> assembler.assemble(webSocketNoneTable, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "a WebSocket route with non-session auth assembles cleanly");
    }

    @Test
    @DisplayName("Should carry the required scopes from the effective auth")
    void shouldCarryRequiredScopes() {
        AuthConfig auth = AuthConfig.builder().require("bearer").requiredScopes(List.of("read", "write")).build();
        RouteTable table = new RouteTable(List.of(ResolvedRoute.builder()
                .id("scoped").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/s").build())
                .effectiveAuth(auth).effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(upstream("a.example"))).build()));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertTrue(runtimes.getFirst().getRequiredScopes().containsAll(List.of("read", "write")),
                "Required scopes flow from the effective auth to the runtime");
    }

    @Test
    @DisplayName("Should carry the effective forward allowlist from the resolved route")
    void shouldCarryEffectiveForward() {
        ForwardConfig forward = new ForwardConfig(List.of("Accept"), List.of("page"),
                Map.of("X-Gateway", "api-sheriff"));
        RouteTable table = new RouteTable(List.of(ResolvedRoute.builder()
                .id("fwd").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/f").build())
                .effectiveAuth(AuthConfig.builder().require("none").build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(Optional.of(upstream("a.example"))).effectiveForward(forward).build()));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(forward, runtimes.getFirst().getEffectiveForward(),
                "The materialized forward allowlist flows to the runtime unchanged");
    }

    @Test
    @DisplayName("Should default an absent forward block to a deny-by-default empty allowlist")
    void shouldDefaultAbsentForwardToEmpty() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", Optional.empty(), upstream("a.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertTrue(runtimes.getFirst().getEffectiveForward().headersAllow().isEmpty(),
                "An unforwarded route carries an empty, deny-by-default allowlist");
    }

    @Test
    @DisplayName("Should assemble an asset route with a live source and no client or guard")
    void shouldAssembleAssetRouteWithoutClientOrGuard() {
        ResolvedRoute assetRoute = ResolvedRoute.builder()
                .id("bundle").protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/assets").build())
                .effectiveAuth(AuthConfig.builder().require("none").build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .asset(Optional.of(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC)))
                .build();
        RouteTable table = new RouteTable(List.of(assetRoute));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory,
                assetSourceFactory);

        RouteRuntime runtime = runtimes.getFirst();
        assertTrue(runtime.getAssetSource().isPresent(), "an asset route carries a live asset source");
        assertTrue(runtime.getUpstream().isEmpty(), "an asset route holds no proxy upstream");
        assertTrue(runtime.getHttpClient().isEmpty(), "an asset route holds no Vert.x client");
        assertTrue(runtime.getResilienceGuard().isEmpty(), "an asset route holds no resilience guard");
    }

    @Test
    @DisplayName("Should assemble the empty-Optional (no-asset) proxy path into an upstream/client/guard runtime without throwing (S3655 guard)")
    void shouldAssembleNoAssetProxyPathWithoutThrowing() {
        RouteTable table = new RouteTable(List.of(
                route("proxy-only", Protocol.HTTP, "none", Optional.empty(), upstream("a.example"))));

        List<RouteRuntime> runtimes = assertDoesNotThrow(
                () -> assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "the empty-Optional asset branch must assemble the proxy runtime without throwing");

        RouteRuntime runtime = runtimes.getFirst();
        assertTrue(runtime.getAssetSource().isEmpty(), "the no-asset path carries no asset source");
        assertTrue(runtime.getUpstream().isPresent(), "the guarded empty-asset branch resolves the proxy upstream");
        assertTrue(runtime.getHttpClient().isPresent(), "the proxy path builds a Vert.x client");
        assertTrue(runtime.getResilienceGuard().isPresent(), "the proxy path builds a resilience guard");
    }

    private static ResolvedRoute route(String id, Protocol protocol, String require,
            Optional<SecurityFilterConfig> filter, ResolvedUpstream upstream) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .effectiveSecurityFilter(filter)
                .upstream(Optional.of(upstream))
                .build();
    }

    private static ResolvedUpstream upstream(String host) {
        return new ResolvedUpstream("https", host, 443, "");
    }

    /**
     * A {@link Guard} test double that is only ever stored on a {@link RouteRuntime} and never
     * invoked during assembly, so its guard methods reject execution.
     */
    private static final class StoredOnlyGuard implements Guard {

        @Override
        public <T> T call(Callable<T> action, Class<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T call(Callable<T> action, TypeLiteral<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T get(Supplier<T> action, Class<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T get(Supplier<T> action, TypeLiteral<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }
    }
}
