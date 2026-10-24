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
package de.cuioss.sheriff.gateway.edge.assembly;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import java.util.Set;
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
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("RouteAssembler — boot-time assembly and heavy-object dedup")
@Tag("isolated-fork")
class RouteAssemblerTest {

    private Vertx vertx;
    private RouteAssembler assembler;
    private SecurityConfigurationFactory securityConfigFactory;
    private UpstreamClientFactory clientFactory;
    private ResilienceGuardFactory guardFactory;
    private AssetSourceFactory assetSourceFactory;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        assembler = new RouteAssembler(new ProtocolProcessorRegistry());
        securityConfigFactory = _ -> new SecurityPosture(SecurityProfile.STRICT,
                SecurityConfiguration.builder().build());
        clientFactory = _ -> vertx.createHttpClient();
        guardFactory = _ -> new StoredOnlyGuard();
        assetSourceFactory = asset -> new DirectoryAssetSource(
                Path.of(Objects.requireNonNullElse(asset.directory(), "/tmp")), asset.access(), asset.index(),
                asset.fallback(), Map.of());
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
            return new SecurityPosture(SecurityProfile.STRICT,
                    SecurityConfiguration.builder().build());
        };
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, Require.NONE, sharedFilter, upstream("a.example")),
                route("r2", Protocol.HTTP, Require.NONE, sharedFilter, upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        assertEquals(1, invocations.get(), "The factory runs once for the shared filter shape");
        assertSame(routes.getFirst().securityConfiguration(),
                routes.get(1).securityConfiguration(),
                "Both routes hold the same SecurityConfiguration reference");
    }

    @Test
    @DisplayName("Should build distinct SecurityConfigurations for different security-filter shapes")
    void shouldBuildDistinctSecurityConfigurationsForDifferentShapes() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, Require.NONE,
                        SecurityFilterConfig.builder().allowedPaths(List.of("/a")).build(), upstream("a.example")),
                route("r2", Protocol.HTTP, Require.NONE,
                        SecurityFilterConfig.builder().allowedPaths(List.of("/b")).build(), upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        assertNotSame(routes.getFirst().securityConfiguration(),
                routes.get(1).securityConfiguration(),
                "Distinct filter shapes must not share a SecurityConfiguration");
    }

    @Test
    @DisplayName("Should reuse one client and one guard for routes sharing an upstream tuple and split by tuple")
    void shouldReuseClientForSharedUpstreamTuple() {
        var clientInvocations = new AtomicInteger();
        var guardInvocations = new AtomicInteger();
        clientFactory = _ -> {
            clientInvocations.incrementAndGet();
            return vertx.createHttpClient();
        };
        guardFactory = _ -> {
            guardInvocations.incrementAndGet();
            return new StoredOnlyGuard();
        };
        Route r1 = route("r1", Protocol.HTTP, Require.NONE, null, upstream("a.example"));
        Route r2 = route("r2", Protocol.HTTP, Require.NONE, null, upstream("a.example"));
        Route r3 = route("r3", Protocol.HTTP, Require.NONE, null, upstream("b.example"));

        UpstreamClients clients = assemble(new RouteTable(List.of(r1, r2, r3))).upstreamClients();

        HttpClient shared = clients.client(UpstreamTarget.of(r1)).orElseThrow();
        assertAll("one client per upstream tuple and one guard per resilience shape",
                () -> assertEquals(2, clientInvocations.get(), "Three routes over two tuples build two clients"),
                () -> assertEquals(2, guardInvocations.get(), "Three routes over two shapes build two guards"),
                () -> assertSame(shared, clients.client(UpstreamTarget.of(r2)).orElseThrow(),
                        "Routes sharing an upstream tuple reuse one client"),
                () -> assertNotSame(shared, clients.client(UpstreamTarget.of(r3)).orElseThrow(),
                        "A different upstream tuple gets a distinct client"),
                () -> assertSame(clients.guard(ResilienceShape.of(r1)).orElseThrow(),
                        clients.guard(ResilienceShape.of(r2)).orElseThrow(),
                        "Routes sharing a resilience shape reuse one guard"),
                () -> assertNotSame(clients.guard(ResilienceShape.of(r1)).orElseThrow(),
                        clients.guard(ResilienceShape.of(r3)).orElseThrow(),
                        "A different resilience shape gets a distinct guard"));
    }

    @Test
    @DisplayName("Should preserve the route-table order")
    void shouldPreserveRouteTableOrder() {
        RouteTable table = new RouteTable(List.of(
                route("first", Protocol.HTTP, Require.NONE, null, upstream("a.example")),
                route("second", Protocol.GRAPHQL, Require.BEARER, null, upstream("b.example"))));

        List<Route> routes = assemble(table).routes();

        assertEquals(List.of("first", "second"), routes.stream().map(Route::id).toList(),
                "Assembly preserves the route-table order");
    }

    @Test
    @DisplayName("Should complete each route through toBuilder(), leaving every resolved value unchanged")
    void shouldCompleteRoutesWithoutChangingResolvedValues() {
        Route proxy = route("proxy", Protocol.HTTP, Require.BEARER,
                SecurityFilterConfig.builder().allowedPaths(List.of("/proxy")).build(), upstream("a.example"));
        Route asset = Route.builder()
                .id("asset").match(MatchConfig.builder().pathPrefix("/assets").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .asset(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC, "index.html", "index.html"))
                .build();
        RouteTable table = new RouteTable(List.of(proxy, asset));

        List<Route> routes = assemble(table).routes();

        assertAll("only the boot-attached components differ from the table route",
                () -> assertEquals(proxy, detachBootComponents(routes.getFirst()), "proxy route"),
                () -> assertEquals(asset, detachBootComponents(routes.get(1)), "asset route"),
                () -> assertNotNull(routes.getFirst().protocolProcessor(), "the processor is attached"),
                () -> assertNotNull(routes.getFirst().securityConfiguration(), "the posture is attached"),
                () -> assertNotNull(routes.get(1).assetSource(), "the asset source is attached"));
    }

    @Test
    @DisplayName("Should assemble a require:session route now the boot-time rejection is removed (D4)")
    void shouldAssembleSessionRoutes() {
        // A require:session route now assembles like any other route — its stage-4 runtime is the
        // SessionAuthenticationStage (D4), which replaced the boot-time CONFIG_INVALID rejection.
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, Require.SESSION, null, upstream("a.example"))));
        RouteAssembler.AssembledRoutes sessionRoutes = assertDoesNotThrow(
                () -> assemble(sessionTable),
                "a require:session route assembles now the boot-time rejection is removed");
        assertEquals(Require.SESSION, sessionRoutes.routes().getFirst().effectiveAuth().require(),
                "the assembled route keeps its require:session posture for the stage-4 runtime to dispatch on");

        // A session-auth WebSocket route likewise assembles — session auth no longer gates boot, so
        // it is treated exactly like any other WebSocket route. Each remaining leg asserts on the
        // route it produced rather than on the absence of a throw: an assemble() that quietly
        // dropped the route would return an empty list and satisfy a bare no-throw assertion.
        RouteTable webSocketSessionTable = new RouteTable(List.of(
                route("sw", Protocol.WEBSOCKET, Require.SESSION, null, upstream("a.example"))));
        Route webSocketSession = assemble(webSocketSessionTable).routes().getFirst();
        assertEquals("sw", webSocketSession.id(),
                "the session-auth WebSocket route reaches the assembled table");
        assertEquals(Require.SESSION, webSocketSession.effectiveAuth().require(),
                "and keeps its require:session posture for the stage-4 runtime to dispatch on");

        // A gRPC route with non-session auth assembles cleanly — and asks for a forced-h2 upstream
        // client. The observable that proves the gRPC branch ran is the UpstreamTarget handed to the
        // client factory, whose forcedHttp2 flag the assembler sets exactly for Protocol.GRPC.
        // Capturing it is what makes this leg discriminating: the shared clientFactory discards its
        // target and never returns null, so asserting only that a client came back would hold whether
        // or not forced-h2 was ever requested.
        List<UpstreamTarget> grpcTargets = new ArrayList<>();
        RouteTable grpcTable = new RouteTable(List.of(
                route("g", Protocol.GRPC, Require.NONE, null, upstream("a.example"))));
        RouteAssembler.AssembledRoutes grpcAssembly = assembler.assemble(grpcTable, securityConfigFactory,
                capturingClientFactory(grpcTargets), guardFactory, assetSourceFactory);
        Route grpc = grpcAssembly.routes().getFirst();
        assertEquals("g", grpc.id(), "the gRPC route reaches the assembled table");
        assertTrue(grpcAssembly.upstreamClients().client(UpstreamTarget.of(grpc)).isPresent(),
                "a gRPC route is served by the forced-h2 upstream client");
        assertEquals(1, grpcTargets.size(), "the gRPC route resolves exactly one upstream client");
        assertTrue(grpcTargets.getFirst().forcedHttp2(),
                "the gRPC route asks the client factory for a forced-h2 client");

        // An HTTP route is the matched negative control for the forced-h2 assertion above: the
        // identical capture over a non-gRPC dispatched route must report forcedHttp2() == false, so
        // that assertion is pinned to the protocol rather than passing for every route the assembler
        // builds a client for.
        List<UpstreamTarget> httpTargets = new ArrayList<>();
        RouteTable httpTable = new RouteTable(List.of(
                route("h", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));
        assembler.assemble(httpTable, securityConfigFactory, capturingClientFactory(httpTargets), guardFactory,
                assetSourceFactory);
        assertEquals(1, httpTargets.size(), "the HTTP route resolves exactly one upstream client");
        assertFalse(httpTargets.getFirst().forcedHttp2(),
                "a non-gRPC route asks for a plain client, never a forced-h2 one");

        // A WebSocket route with non-session auth likewise assembles cleanly — and, being dialed by the
        // edge-wide WebSocket client, asks the client factory for nothing at all (Defect 12).
        List<UpstreamTarget> webSocketTargets = new ArrayList<>();
        RouteTable webSocketNoneTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, Require.NONE, null, upstream("a.example"))));
        Route webSocketNone = assembler.assemble(webSocketNoneTable, securityConfigFactory,
                capturingClientFactory(webSocketTargets), guardFactory, assetSourceFactory).routes().getFirst();
        assertEquals("w", webSocketNone.id(), "the WebSocket route reaches the assembled table");
        assertEquals(Require.NONE, webSocketNone.effectiveAuth().require(),
                "and carries its declared require:none posture");
        assertTrue(webSocketTargets.isEmpty(), "the WebSocket route requests no upstream client");
    }

    @Test
    @DisplayName("Should build neither a client nor a guard for a WebSocket route (Defect 12)")
    void shouldBuildNoClientOrGuardForWebSocketRoute() {
        var clientInvocations = new AtomicInteger();
        var guardInvocations = new AtomicInteger();
        clientFactory = _ -> {
            clientInvocations.incrementAndGet();
            return vertx.createHttpClient();
        };
        guardFactory = _ -> {
            guardInvocations.incrementAndGet();
            return new StoredOnlyGuard();
        };
        Route webSocket = route("ws", Protocol.WEBSOCKET, Require.NONE, null, upstream("ws.example"));
        Route sessionWebSocket = route("ws-session", Protocol.WEBSOCKET, Require.SESSION, null,
                upstream("ws.example"));

        RouteAssembler.AssembledRoutes assembled = assemble(new RouteTable(List.of(webSocket, sessionWebSocket)));

        UpstreamClients clients = assembled.upstreamClients();
        assertAll("the WebSocket relay dials through the edge-wide WebSocket client",
                () -> assertEquals(2, assembled.routes().size(), "both WebSocket routes are assembled"),
                () -> assertTrue(clients.clients().isEmpty(), "no upstream client is held for a WebSocket route"),
                () -> assertTrue(clients.guards().isEmpty(), "no resilience guard is held for a WebSocket route"),
                () -> assertTrue(clients.client(UpstreamTarget.of(webSocket)).isEmpty(),
                        "the WebSocket route's upstream tuple resolves no client"),
                () -> assertTrue(clients.guard(ResilienceShape.of(webSocket)).isEmpty(),
                        "the WebSocket route's resilience shape resolves no guard"),
                () -> assertEquals(0, clientInvocations.get(), "the client factory is never invoked"),
                () -> assertEquals(0, guardInvocations.get(), "the guard factory is never invoked"));
    }

    @Test
    @DisplayName("Should map every protocol onto the dispatch path the edge serves it on")
    void shouldMapProtocolsOntoDispatchPaths() {
        assertAll("the one protocol split",
                () -> assertEquals(DispatchPath.HTTP_DISPATCH,
                        DispatchPath.of(Protocol.HTTP), "HTTP takes the HTTP dispatch"),
                () -> assertEquals(DispatchPath.HTTP_DISPATCH,
                        DispatchPath.of(Protocol.GRAPHQL), "GraphQL takes the HTTP dispatch"),
                () -> assertEquals(DispatchPath.GRPC_DISPATCH,
                        DispatchPath.of(Protocol.GRPC), "gRPC takes the forced-h2 dispatch"),
                () -> assertEquals(DispatchPath.WEBSOCKET_RELAY,
                        DispatchPath.of(Protocol.WEBSOCKET), "WebSocket takes the opaque relay"),
                () -> assertTrue(DispatchPath.HTTP_DISPATCH.usesDispatchStage(),
                        "the HTTP dispatch runs through DispatchStage"),
                () -> assertTrue(DispatchPath.GRPC_DISPATCH.usesDispatchStage(),
                        "the gRPC dispatch delegates to DispatchStage"),
                () -> assertFalse(DispatchPath.WEBSOCKET_RELAY.usesDispatchStage(),
                        "the WebSocket relay does not touch DispatchStage"));
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    @DisplayName("Should build a client and a guard for a proxy route exactly when its dispatch path needs them")
    void shouldBuildClientExactlyForDispatchStagePaths(Protocol protocol) {
        List<UpstreamTarget> requestedClients = new ArrayList<>();
        Route proxy = route("p", protocol, Require.NONE, null, upstream("a.example"));

        RouteAssembler.AssembledRoutes assembled = assembler.assemble(new RouteTable(List.of(proxy)),
                securityConfigFactory, capturingClientFactory(requestedClients), guardFactory, assetSourceFactory);

        boolean needsClient = DispatchPath.of(protocol).usesDispatchStage();
        assertAll("the assembler and the dispatch switch read the same split for " + protocol,
                () -> assertEquals(needsClient, !requestedClients.isEmpty(),
                        "a client is requested exactly for a DispatchStage path"),
                () -> assertEquals(needsClient,
                        assembled.upstreamClients().guard(ResilienceShape.of(proxy)).isPresent(),
                        "a guard is held exactly for a DispatchStage path"));
    }

    /**
     * A client factory that records every {@link UpstreamTarget} the assembler
     * asks it for, so a test can assert on the factory's <em>input</em> rather than only on its
     * never-null output.
     */
    private UpstreamClientFactory capturingClientFactory(
            List<UpstreamTarget> captured) {
        return target -> {
            captured.add(target);
            return vertx.createHttpClient();
        };
    }

    @Test
    @DisplayName("Should carry the needed scopes from the resolved route")
    void shouldCarryNeededScopes() {
        AuthConfig auth = AuthConfig.builder().require(Require.BEARER).build();
        RouteTable table = new RouteTable(List.of(Route.builder()
                .id("scoped").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/s").build())
                .effectiveAuth(auth).effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .neededScopes(Set.of("openid", "read", "write"))
                .upstream(upstream("a.example")).build()));

        List<Route> routes = assemble(table).routes();

        assertEquals(Set.of("openid", "read", "write"), routes.getFirst().neededScopes(),
                "The materialized needed scopes flow from the table route to the assembled route unchanged");
    }

    @Test
    @DisplayName("Should carry an empty needed-scope set for a route needing no scope")
    void shouldCarryEmptyNeededScopes() {
        RouteTable table = new RouteTable(List.of(Route.builder()
                .id("open").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/o").build())
                .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .upstream(upstream("a.example")).build()));

        List<Route> routes = assemble(table).routes();

        assertTrue(routes.getFirst().neededScopes().isEmpty(), "A route needing no scope carries an empty set");
    }

    @Test
    @DisplayName("Should carry the effective forward filter from the resolved route")
    void shouldCarryEffectiveForward() {
        ForwardConfig forward = ForwardConfig.builder().headersAllow(List.of("Accept"))
                .queryAllow(List.of("page")).setHeaders(Map.of("X-Gateway", "api-sheriff")).build();
        RouteTable table = new RouteTable(List.of(Route.builder()
                .id("fwd").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/f").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .upstream(upstream("a.example")).effectiveForward(forward).build()));

        List<Route> routes = assemble(table).routes();

        assertEquals(forward, routes.getFirst().effectiveForward(),
                "The materialized forward filter flows to the assembled route unchanged");
    }

    @Test
    @DisplayName("Should carry a negative-list forward block from the resolved route")
    void shouldCarryEffectiveForwardDenyLists() {
        ForwardConfig forward = ForwardConfig.builder().headersDeny(List.of("X-Internal"))
                .queryDeny(List.of("debug")).setHeaders(Map.of()).build();
        RouteTable table = new RouteTable(List.of(Route.builder()
                .id("deny").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/d").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .upstream(upstream("a.example")).effectiveForward(forward).build()));

        List<Route> routes = assemble(table).routes();

        assertEquals(forward, routes.getFirst().effectiveForward(),
                "The deny lists reach the assembled route through the same wholesale carry as the allow lists");
    }

    @Test
    @DisplayName("Should default an absent forward block to the forward-all posture")
    void shouldDefaultAbsentForwardToForwardAll() {
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        ForwardConfig effective = routes.getFirst().effectiveForward();
        assertAll("a route declaring no forward block carries forward-all, not a nothing-crosses allowlist",
                () -> assertNull(effective.headersAllow()),
                () -> assertNull(effective.headersDeny()),
                () -> assertNull(effective.queryAllow()),
                () -> assertNull(effective.queryDeny()));
    }

    @Test
    @DisplayName("Should carry the resolved security headers onto every route kind and leave a block-less route without any")
    void shouldCarryResolvedSecurityHeaders() {
        SecurityHeadersConfig anchorBlock = SecurityHeadersConfig.builder()
                .frameDeny(true).contentSecurityPolicy("default-src 'self'").build();
        Route proxy = Route.builder()
                .id("proxy").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/p").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .securityHeaders(anchorBlock)
                .upstream(upstream("a.example")).build();
        Route asset = Route.builder()
                .id("asset").protocol(Protocol.HTTP).match(MatchConfig.builder().pathPrefix("/a").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .securityHeaders(anchorBlock)
                .asset(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC, "index.html", "index.html")).build();
        Route redirect = Route.builder()
                .id("redirect").protocol(Protocol.HTTP).match(MatchConfig.builder().path("/old").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .securityHeaders(anchorBlock)
                .redirect(new RedirectConfig("/new", 301, false, false)).build();
        RouteTable table = new RouteTable(List.of(proxy, asset, redirect,
                route("block-less", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        assertAll("the resolved block reaches Route.securityHeaders unchanged for every terminal action",
                () -> assertEquals(anchorBlock, routes.getFirst().securityHeaders(), "proxy route"),
                () -> assertEquals(anchorBlock, routes.get(1).securityHeaders(), "asset route"),
                () -> assertEquals(anchorBlock, routes.get(2).securityHeaders(), "redirect route"),
                () -> assertNull(routes.get(3).securityHeaders(),
                        "a route resolving no block carries none, so stage 2a seeds no gateway-owned header"));
    }

    @Test
    @DisplayName("Should carry the pre-boot Location rewriter unchanged and add none to a route that did not opt in")
    void shouldBuildLocationRewriterOnlyForOptedInRoute() {
        ResolvedUpstream effectiveUpstream = new ResolvedUpstream("https", "a.example", 443, "/svc/v1");
        LocationRewriter rewriter = new LocationRewriter(effectiveUpstream, "/api", false);
        RouteTable table = new RouteTable(List.of(
                Route.builder()
                        .id("rewritten").protocol(Protocol.HTTP)
                        .match(MatchConfig.builder().pathPrefix("/api").build())
                        .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                        .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                        .locationRewriter(rewriter)
                        .upstream(effectiveUpstream).build(),
                Route.builder()
                        .id("verbatim").protocol(Protocol.HTTP)
                        .match(MatchConfig.builder().pathPrefix("/other").build())
                        .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                        .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                        .upstream(effectiveUpstream).build()));

        List<Route> routes = assemble(table).routes();

        Route rewritten = routes.getFirst();
        assertAll("rewrite_location wiring",
                () -> assertSame(rewriter, rewritten.locationRewriter(),
                        "an opted-in proxy route keeps the rewriter the route-table builder set"),
                () -> assertEquals("/api/items?id=7",
                        Objects.requireNonNull(rewritten.locationRewriter())
                                .rewrite("https://a.example/svc/v1/items?id=7"),
                        "the rewriter maps the effective upstream base path onto the route's match key"),
                () -> assertNull(routes.get(1).locationRewriter(),
                        "a route that does not opt in carries no rewriter, so Location relays unchanged"));
    }

    @Test
    @DisplayName("Should assemble an asset route with a live source and no client or guard")
    void shouldAssembleAssetRouteWithoutClientOrGuard() {
        Route assetRoute = Route.builder()
                .id("bundle").protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/assets").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .asset(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC, "index.html", "index.html"))
                .build();
        RouteTable table = new RouteTable(List.of(assetRoute));
        List<ResolvedAsset> requestedSources = new ArrayList<>();
        AssetSourceFactory capturingAssetSourceFactory = asset -> {
            requestedSources.add(asset);
            return assetSourceFactory.create(asset);
        };

        RouteAssembler.AssembledRoutes assembled = assembler.assemble(table, securityConfigFactory, clientFactory,
                guardFactory, capturingAssetSourceFactory);

        Route route = assembled.routes().getFirst();
        assertNotNull(route.assetSource(), "an asset route carries a live asset source");
        assertNull(route.upstream(), "an asset route holds no proxy upstream");
        assertTrue(assembled.upstreamClients().clients().isEmpty(), "no Vert.x client is built for an asset route");
        assertTrue(assembled.upstreamClients().guards().isEmpty(),
                "no resilience guard is built for an asset route");
        assertNull(route.locationRewriter(), "an asset route holds no Location rewriter");
        assertEquals(1, requestedSources.size(), "the asset source is built once for the route");
        assertEquals("index.html", requestedSources.getFirst().index(),
                "the resolved index reaches the asset-source factory");
        assertEquals("index.html", requestedSources.getFirst().fallback(),
                "the resolved fallback reaches the asset-source factory");
    }

    @Test
    @DisplayName("Should assemble a redirect route carrying its redirect and no client, guard, source or upstream")
    void shouldAssembleRedirectRouteWithoutClientOrGuard() {
        RedirectConfig redirect = new RedirectConfig("/new-home", 301, false, false);
        Route redirectRoute = Route.builder()
                .id("moved").protocol(Protocol.HTTP)
                .match(MatchConfig.builder().path("/old-home").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .redirect(redirect)
                .build();
        RouteTable table = new RouteTable(List.of(redirectRoute));
        List<UpstreamTarget> requestedClients = new ArrayList<>();

        RouteAssembler.AssembledRoutes assembled = assembler.assemble(table, securityConfigFactory,
                capturingClientFactory(requestedClients), guardFactory, assetSourceFactory);

        Route route = assembled.routes().getFirst();
        assertAll("a redirect route never touches the proxy data plane",
                () -> assertEquals(redirect, route.redirect(), "the redirect block reaches the assembled route"),
                () -> assertTrue(route.matcher().isExact(), "the exact matcher is compiled for the route"),
                () -> assertNull(route.upstream(), "a redirect route holds no proxy upstream"),
                () -> assertTrue(assembled.upstreamClients().clients().isEmpty(),
                        "no Vert.x client is held for a redirect route"),
                () -> assertTrue(assembled.upstreamClients().guards().isEmpty(),
                        "no resilience guard is held for a redirect route"),
                () -> assertNull(route.assetSource(), "a redirect route holds no asset source"),
                () -> assertTrue(requestedClients.isEmpty(), "no upstream client is requested for a redirect"));
    }

    @Test
    @DisplayName("Should assemble the null (no-asset) proxy path into an upstream/client/guard route without throwing (S3655 guard)")
    void shouldAssembleNoAssetProxyPathWithoutThrowing() {
        Route proxyOnly = route("proxy-only", Protocol.HTTP, Require.NONE, null, upstream("a.example"));
        RouteTable table = new RouteTable(List.of(proxyOnly));

        RouteAssembler.AssembledRoutes assembled = assertDoesNotThrow(
                () -> assemble(table),
                "the null asset branch must assemble the proxy route without throwing");

        Route route = assembled.routes().getFirst();
        assertNull(route.assetSource(), "the no-asset path carries no asset source");
        assertNotNull(route.upstream(), "the guarded null-asset branch resolves the proxy upstream");
        assertTrue(assembled.upstreamClients().client(UpstreamTarget.of(route)).isPresent(),
                "the proxy path builds a Vert.x client");
        assertTrue(assembled.upstreamClients().guard(ResilienceShape.of(route)).isPresent(),
                "the proxy path builds a resilience guard");
    }

    @Test
    @DisplayName("Should resolve the posture for a route that declares no security_filter block")
    void shouldResolvePostureForBlockLessRoute() {
        // The load-bearing case: a global profile must reach a route with no block at all, which the
        // previous effectiveSecurityFilter().map(...) shape silently skipped.
        List<@Nullable SecurityFilterConfig> seen = new ArrayList<>();
        securityConfigFactory = filter -> {
            seen.add(filter);
            return new SecurityPosture(SecurityProfile.MINIMAL,
                    SecurityProfile.STRICT.preset());
        };
        RouteTable table = new RouteTable(List.of(
                route("block-less", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        assertEquals(Collections.singletonList(null), seen,
                "the resolver runs for a block-less route, receiving a null filter block");
        Route route = routes.getFirst();
        assertEquals(SecurityProfile.MINIMAL, route.securityProfile(),
                "the resolved profile reaches a route that declares no security_filter block");
        assertNotNull(route.securityConfiguration(),
                "every route carries a concrete limits policy, so the body cap is always enforceable");
    }

    @ParameterizedTest
    @EnumSource(SecurityProfile.class)
    @DisplayName("Should set the resolved profile explicitly rather than leaning on the fail-closed default")
    void shouldSetResolvedProfileExplicitly(SecurityProfile resolved) {
        // LENIENT and MINIMAL differ from Route's fail-closed STRICT default, so a route carrying them
        // proves the assembler assigned the component instead of inheriting the default.
        securityConfigFactory = _ -> new SecurityPosture(resolved,
                SecurityProfile.limitsProfile(resolved, resolved).preset());
        RouteTable table = new RouteTable(List.of(
                route("declared", Protocol.HTTP, Require.NONE,
                        SecurityFilterConfig.builder().build(), upstream("a.example")),
                route("block-less", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));

        List<Route> routes = assemble(table).routes();

        for (Route route : routes) {
            assertEquals(resolved, route.securityProfile(),
                    "route '%s' carries the resolved %s profile".formatted(route.id(), resolved));
            assertEquals(SecurityProfile.limitsProfile(resolved, resolved).preset(),
                    route.securityConfiguration(),
                    "route '%s' carries the resolved limits policy".formatted(route.id()));
        }
    }

    @Test
    @DisplayName("Should key the posture cache on the whole filter block, splitting a declared block from an absent one")
    void shouldSplitPostureCacheOnDeclaredVersusAbsentBlock() {
        var invocations = new AtomicInteger();
        securityConfigFactory = _ -> {
            invocations.incrementAndGet();
            return new SecurityPosture(SecurityProfile.STRICT,
                    SecurityConfiguration.builder().build());
        };
        SecurityFilterConfig declared = SecurityFilterConfig.builder().allowedPaths(List.of("/shared")).build();
        RouteTable table = new RouteTable(List.of(
                route("r1", Protocol.HTTP, Require.NONE, declared, upstream("a.example")),
                route("r2", Protocol.HTTP, Require.NONE, declared, upstream("a.example")),
                route("r3", Protocol.HTTP, Require.NONE, null, upstream("a.example"))));

        assemble(table);

        assertEquals(2, invocations.get(),
                "the shared declared shape resolves once and the absent block resolves once more");
    }

    private RouteAssembler.AssembledRoutes assemble(RouteTable table) {
        return assembler.assemble(table, securityConfigFactory, clientFactory, guardFactory, assetSourceFactory);
    }

    /**
     * Removes the boot-attached components from an assembled route, restoring the route the
     * route-table builder produced if — and only if — the assembler changed nothing else.
     */
    private static Route detachBootComponents(Route assembled) {
        return assembled.toBuilder()
                .protocolProcessor(null)
                .securityProfile(null)
                .securityConfiguration(null)
                .assetSource(null)
                .build();
    }

    private static Route route(String id, Protocol protocol, Require require,
            @Nullable SecurityFilterConfig filter, ResolvedUpstream upstream) {
        return Route.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .effectiveSecurityFilter(filter)
                .upstream(upstream)
                .build();
    }

    private static ResolvedUpstream upstream(String host) {
        return new ResolvedUpstream("https", host, 443, "");
    }

    /**
     * A {@link Guard} test double that is only ever held in the {@link UpstreamClients}
     * lookup and never invoked during assembly, so its guard methods reject execution.
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
