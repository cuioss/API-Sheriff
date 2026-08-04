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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Vertx;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        securityConfigFactory = _ -> new RouteRuntimeAssembler.SecurityPosture(SecurityProfile.STRICT,
                SecurityConfiguration.builder().build());
        clientFactory = _ -> vertx.createHttpClient();
        guardFactory = _ -> new StoredOnlyGuard();
        assetSourceFactory = asset -> new DirectoryAssetSource(
                Path.of(Objects.requireNonNullElse(asset.directory(), "/tmp")), asset.access(), Map.of());
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
        securityConfigFactory = _ -> {
            invocations.incrementAndGet();
            return new RouteRuntimeAssembler.SecurityPosture(SecurityProfile.STRICT,
                    SecurityConfiguration.builder().build());
        };
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", sharedFilter, upstream("a.example")),
                route("r2", Protocol.HTTP, "none", sharedFilter, upstream("a.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(1, invocations.get(), "The factory runs once for the shared filter shape");
        assertSame(runtimes.getFirst().getSecurityConfiguration(),
                runtimes.get(1).getSecurityConfiguration(),
                "Both routes hold the same SecurityConfiguration reference");
    }

    @Test
    @DisplayName("Should build distinct SecurityConfigurations for different security-filter shapes")
    void shouldBuildDistinctSecurityConfigurationsForDifferentShapes() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none",
                        SecurityFilterConfig.builder().allowedPaths(List.of("/a")).build(), upstream("a.example")),
                route("r2", Protocol.HTTP, "none",
                        SecurityFilterConfig.builder().allowedPaths(List.of("/b")).build(), upstream("a.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertNotSame(runtimes.getFirst().getSecurityConfiguration(),
                runtimes.get(1).getSecurityConfiguration(),
                "Distinct filter shapes must not share a SecurityConfiguration");
    }

    @Test
    @DisplayName("Should reuse one client for routes sharing an upstream tuple and split by tuple")
    void shouldReuseClientForSharedUpstreamTuple() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", null, upstream("a.example")),
                route("r2", Protocol.HTTP, "none", null, upstream("a.example")),
                route("r3", Protocol.HTTP, "none", null, upstream("b.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertSame(runtimes.getFirst().getHttpClient(),
                runtimes.get(1).getHttpClient(),
                "Routes sharing an upstream tuple reuse one client");
        assertNotSame(runtimes.getFirst().getHttpClient(),
                runtimes.get(2).getHttpClient(),
                "A different upstream tuple gets a distinct client");
    }

    @Test
    @DisplayName("Should preserve the route-table order")
    void shouldPreserveRouteTableOrder() {
        RouteTable table = new RouteTable(List.of(
                route("first", Protocol.HTTP, "none", null, upstream("a.example")),
                route("second", Protocol.GRAPHQL, "bearer", null, upstream("b.example"))));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(List.of("first", "second"), runtimes.stream().map(RouteRuntime::getId).toList(),
                "Assembly preserves the longest-prefix-first order");
    }

    @Test
    @DisplayName("Should assemble a require:session route now the boot-time rejection is removed (D4)")
    void shouldAssembleSessionRoutes() {
        // A require:session route now assembles like any other route — its stage-4 runtime is the
        // SessionAuthenticationStage (D4), which replaced the boot-time CONFIG_INVALID rejection.
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, "session", null, upstream("a.example"))));
        List<RouteRuntime> sessionRuntimes = assertDoesNotThrow(
                () -> assembler.assemble(sessionTable, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "a require:session route assembles now the boot-time rejection is removed");
        assertEquals("session", sessionRuntimes.getFirst().getEffectiveAuth().require(),
                "the assembled route keeps its require:session posture for the stage-4 runtime to dispatch on");

        // A session-auth WebSocket route likewise assembles — session auth no longer gates boot, so
        // it is treated exactly like any other WebSocket route. Each remaining leg asserts on the
        // runtime it produced rather than on the absence of a throw: an assemble() that quietly
        // dropped the route would return an empty list and satisfy a bare no-throw assertion.
        RouteTable webSocketSessionTable = new RouteTable(List.of(
                route("sw", Protocol.WEBSOCKET, "session", null, upstream("a.example"))));
        RouteRuntime webSocketSession = assembler.assemble(webSocketSessionTable, securityConfigFactory,
                clientFactory, guardFactory, assetSourceFactory).getFirst();
        assertEquals("sw", webSocketSession.getId(),
                "the session-auth WebSocket route reaches the assembled table");
        assertEquals("session", webSocketSession.getEffectiveAuth().require(),
                "and keeps its require:session posture for the stage-4 runtime to dispatch on");

        // A gRPC route with non-session auth assembles cleanly — and asks for a forced-h2 upstream
        // client. The observable that proves the gRPC branch ran is the UpstreamTarget handed to the
        // client factory, whose forcedHttp2 flag the assembler sets exactly for Protocol.GRPC.
        // Capturing it is what makes this leg discriminating: the shared clientFactory discards its
        // target and never returns null, so asserting only that a client came back would hold whether
        // or not forced-h2 was ever requested.
        List<RouteRuntimeAssembler.UpstreamTarget> grpcTargets = new ArrayList<>();
        RouteTable grpcTable = new RouteTable(List.of(
                route("g", Protocol.GRPC, "none", null, upstream("a.example"))));
        RouteRuntime grpc = assembler.assemble(grpcTable, securityConfigFactory,
                capturingClientFactory(grpcTargets), guardFactory, assetSourceFactory).getFirst();
        assertEquals("g", grpc.getId(), "the gRPC route reaches the assembled table");
        assertNotNull(grpc.getHttpClient(), "a gRPC route carries the forced-h2 upstream client");
        assertEquals(1, grpcTargets.size(), "the gRPC route resolves exactly one upstream client");
        assertTrue(grpcTargets.getFirst().forcedHttp2(),
                "the gRPC route asks the client factory for a forced-h2 client");

        // A WebSocket route with non-session auth likewise assembles cleanly, and doubles as the
        // matched negative control for the forced-h2 assertion above: the identical capture over a
        // non-gRPC route must report forcedHttp2() == false, so that assertion is pinned to the
        // protocol rather than passing for every route the assembler builds.
        List<RouteRuntimeAssembler.UpstreamTarget> webSocketTargets = new ArrayList<>();
        RouteTable webSocketNoneTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, "none", null, upstream("a.example"))));
        RouteRuntime webSocketNone = assembler.assemble(webSocketNoneTable, securityConfigFactory,
                capturingClientFactory(webSocketTargets), guardFactory, assetSourceFactory).getFirst();
        assertEquals("w", webSocketNone.getId(), "the WebSocket route reaches the assembled table");
        assertEquals("none", webSocketNone.getEffectiveAuth().require(),
                "and carries its declared require:none posture");
        assertEquals(1, webSocketTargets.size(), "the WebSocket route resolves exactly one upstream client");
        assertFalse(webSocketTargets.getFirst().forcedHttp2(),
                "a non-gRPC route asks for a plain client, never a forced-h2 one");
    }

    /**
     * A client factory that records every {@link RouteRuntimeAssembler.UpstreamTarget} the assembler
     * asks it for, so a test can assert on the factory's <em>input</em> rather than only on its
     * never-null output.
     */
    private RouteRuntimeAssembler.UpstreamClientFactory capturingClientFactory(
            List<RouteRuntimeAssembler.UpstreamTarget> captured) {
        return target -> {
            captured.add(target);
            return vertx.createHttpClient();
        };
    }

    @Test
    @DisplayName("Should carry the required scopes from the effective auth")
    void shouldCarryRequiredScopes() {
        AuthConfig auth = AuthConfig.builder().require("bearer").requiredScopes(List.of("read", "write")).build();
        RouteTable table = new RouteTable(List.of(ResolvedRoute.builder()
                .id("scoped").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/s").build())
                .effectiveAuth(auth).effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(upstream("a.example")).build()));

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
                .upstream(upstream("a.example")).effectiveForward(forward).build()));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        assertEquals(forward, runtimes.getFirst().getEffectiveForward(),
                "The materialized forward allowlist flows to the runtime unchanged");
    }

    @Test
    @DisplayName("Should default an absent forward block to a deny-by-default empty allowlist")
    void shouldDefaultAbsentForwardToEmpty() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", null, upstream("a.example"))));

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
                .asset(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC))
                .build();
        RouteTable table = new RouteTable(List.of(assetRoute));

        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory,
                assetSourceFactory);

        RouteRuntime runtime = runtimes.getFirst();
        assertNotNull(runtime.getAssetSource(), "an asset route carries a live asset source");
        assertNull(runtime.getUpstream(), "an asset route holds no proxy upstream");
        assertNull(runtime.getHttpClient(), "an asset route holds no Vert.x client");
        assertNull(runtime.getResilienceGuard(), "an asset route holds no resilience guard");
    }

    @Test
    @DisplayName("Should assemble the null (no-asset) proxy path into an upstream/client/guard runtime without throwing (S3655 guard)")
    void shouldAssembleNoAssetProxyPathWithoutThrowing() {
        RouteTable table = new RouteTable(List.of(
                route("proxy-only", Protocol.HTTP, "none", null, upstream("a.example"))));

        List<RouteRuntime> runtimes = assertDoesNotThrow(
                () -> assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory),
                "the null asset branch must assemble the proxy runtime without throwing");

        RouteRuntime runtime = runtimes.getFirst();
        assertNull(runtime.getAssetSource(), "the no-asset path carries no asset source");
        assertNotNull(runtime.getUpstream(), "the guarded null-asset branch resolves the proxy upstream");
        assertNotNull(runtime.getHttpClient(), "the proxy path builds a Vert.x client");
        assertNotNull(runtime.getResilienceGuard(), "the proxy path builds a resilience guard");
    }

    @Test
    @DisplayName("Should resolve the posture for a route that declares no security_filter block")
    void shouldResolvePostureForBlockLessRoute() {
        // Arrange — the load-bearing case: a global profile must reach a route with no block at all,
        // which the previous effectiveSecurityFilter().map(...) shape silently skipped.
        List<@Nullable SecurityFilterConfig> seen = new ArrayList<>();
        securityConfigFactory = filter -> {
            seen.add(filter);
            return new RouteRuntimeAssembler.SecurityPosture(SecurityProfile.MINIMAL,
                    SecurityProfile.STRICT.preset());
        };
        RouteTable table = new RouteTable(List.of(
                route("block-less", Protocol.HTTP, "none", null, upstream("a.example"))));

        // Act
        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory,
                assetSourceFactory);

        // Assert
        assertEquals(Collections.singletonList(null), seen,
                "the resolver runs for a block-less route, receiving a null filter block");
        RouteRuntime runtime = runtimes.getFirst();
        assertEquals(SecurityProfile.MINIMAL, runtime.getSecurityProfile(),
                "the resolved profile reaches a route that declares no security_filter block");
        assertNotNull(runtime.getSecurityConfiguration(),
                "every route carries a concrete limits policy, so the body cap is always enforceable");
    }

    @ParameterizedTest
    @EnumSource(SecurityProfile.class)
    @DisplayName("Should set the resolved profile explicitly rather than leaning on the builder default")
    void shouldSetResolvedProfileExplicitly(SecurityProfile resolved) {
        // Arrange — LENIENT and NONE differ from RouteRuntime's @Builder.Default STRICT, so a runtime
        // carrying them proves the assembler assigned the field instead of inheriting the default.
        securityConfigFactory = _ -> new RouteRuntimeAssembler.SecurityPosture(resolved,
                SecurityProfile.limitsProfile(resolved, resolved).preset());
        RouteTable table = new RouteTable(List.of(
                route("declared", Protocol.HTTP, "none",
                        SecurityFilterConfig.builder().build(), upstream("a.example")),
                route("block-less", Protocol.HTTP, "none", null, upstream("a.example"))));

        // Act
        List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory,
                assetSourceFactory);

        // Assert
        for (RouteRuntime runtime : runtimes) {
            assertEquals(resolved, runtime.getSecurityProfile(),
                    "route '%s' carries the resolved %s profile".formatted(runtime.getId(), resolved));
            assertEquals(SecurityProfile.limitsProfile(resolved, resolved).preset(),
                    runtime.getSecurityConfiguration(),
                    "route '%s' carries the resolved limits policy".formatted(runtime.getId()));
        }
    }

    @Test
    @DisplayName("Should key the posture cache on the whole filter block, splitting a declared block from an absent one")
    void shouldSplitPostureCacheOnDeclaredVersusAbsentBlock() {
        // Arrange
        var invocations = new AtomicInteger();
        securityConfigFactory = _ -> {
            invocations.incrementAndGet();
            return new RouteRuntimeAssembler.SecurityPosture(SecurityProfile.STRICT,
                    SecurityConfiguration.builder().build());
        };
        SecurityFilterConfig declared = SecurityFilterConfig.builder().allowedPaths(List.of("/shared")).build();
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, "none", declared, upstream("a.example")),
                route("r2", Protocol.HTTP, "none", declared, upstream("a.example")),
                route("r3", Protocol.HTTP, "none", null, upstream("a.example"))));

        // Act
        assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);

        // Assert
        assertEquals(2, invocations.get(),
                "the shared declared shape resolves once and the absent block resolves once more");
    }

    private static ResolvedRoute route(String id, Protocol protocol, String require,
            @Nullable SecurityFilterConfig filter, ResolvedUpstream upstream) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .effectiveSecurityFilter(filter)
                .upstream(upstream)
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
