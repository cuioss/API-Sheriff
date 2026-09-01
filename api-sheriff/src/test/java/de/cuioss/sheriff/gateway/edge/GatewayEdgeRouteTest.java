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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    @DisplayName("boots a require:session route now the boot-time rejection is removed (D4)")
    void bootsSessionAuthRoute() {
        // Arrange
        RouteTable sessionTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, Require.SESSION)));

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
                route("g", Protocol.GRPC, Require.NONE)));

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
                route("w", Protocol.WEBSOCKET, Require.NONE)));

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
                route("w", Protocol.WEBSOCKET, Require.SESSION)));

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
                client.request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), "127.0.0.1", "/nothing")
                        .compose(HttpClientRequest::send);

                // Assert
                assertInstanceOf(AtomicBoolean.class,
                        Awaits.connect(stashed, "the admission guard to be stashed"),
                        "handle() stashes the admission-release CAS guard under its context key");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("the WebSocket branch reads the guard back and releases at relay teardown")
        void webSocketBranchReleasesThroughTheStashedGuard() throws Exception {
            // Arrange — a real upgrade against a stub upstream, so the HTTP response never ends
            HttpServer upstream = Awaits.connect(vertx.createHttpServer()
                    .webSocketHandler(ws -> ws.textMessageHandler(ws::writeTextMessage))
                    .listen(0), "the stub upstream WebSocket server to start listening");
            CompletableFuture<Object> stashed = new CompletableFuture<>();
            HttpServer front = startFront(new RouteTable(List.of(webSocketRoute(upstream.actualPort()))), stashed);
            WebSocketClient client = vertx.createWebSocketClient();
            try {
                WebSocket socket = connectWs(client, front.actualPort());
                AtomicBoolean guard = assertInstanceOf(AtomicBoolean.class,
                        Awaits.connect(stashed, "the admission guard to be stashed"),
                        "the WebSocket request stashes the same release guard");
                assertFalse(guard.get(),
                        "an established relay still holds its admission permit — release at upgrade "
                                + "completion would under-count concurrent relays");

                // Act
                Awaits.teardown(socket.close(), "the relayed WebSocket to close");

                // Assert — nothing ever ended the HTTP response, so only the relay's teardown callback
                // can have flipped the guard
                awaitReleased(guard, "the WebSocket relay releases the admission permit at teardown");
            } finally {
                Awaits.teardown(client.close(), "the WebSocket client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
            }
        }

        @Test
        @DisplayName("bounds concurrent relays by the sub-budget and returns both permits at teardown")
        void boundsConcurrentRelaysByTheSubBudget() throws Exception {
            // Arrange — admission_cap 2 with a websocket_relay_cap of 1, so a single established relay
            // exhausts the sub-budget while leaving one general permit for ordinary traffic
            HttpServer upstream = Awaits.connect(vertx.createHttpServer()
                    .webSocketHandler(ws -> ws.textMessageHandler(ws::writeTextMessage))
                    .listen(0), "the stub upstream WebSocket server to start listening");
            Router router = Router.router(vertx);
            new GatewayEdgeRoute(new RouteTable(List.of(webSocketRoute(upstream.actualPort()))), gatewayConfig,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(new EdgeHardeningConfig(2, 1)),
                    new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert()).registerRoutes(router);
            HttpServer front = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0),
                    "the edge front server to start listening");
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
                Awaits.teardown(held.close(), "the held WebSocket relay to close");

                // Assert
                Awaits.teardown(connectWhenAdmitted(wsClient, front.actualPort()).close(),
                        "the readmitted WebSocket to close");
            } finally {
                Awaits.teardown(httpClient.close(), "the HTTP client to close");
                Awaits.teardown(wsClient.close(), "the WebSocket client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
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
            return Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0),
                    "the edge front server to start listening");
        }
    }

    /**
     * The {@code security_filter → security_defaults} posture resolution the edge hands to the
     * {@code RouteRuntimeAssembler}. It is asserted directly rather than through a booted edge
     * because an assembled edge exposes no view of its compiled routes.
     */
    @Nested
    @DisplayName("inbound-filter posture resolution (security_filter → security_defaults)")
    class PostureResolution {

        @Test
        @DisplayName("applies the gateway-wide profile to a route that declares no security_filter block")
        void appliesGlobalProfileToBlockLessRoute() {
            // Arrange — the case the previous effectiveSecurityFilter().map(...) shape skipped entirely

            // Act
            RouteRuntimeAssembler.SecurityPosture strict =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.STRICT);
            RouteRuntimeAssembler.SecurityPosture lenient =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.LENIENT);

            // Assert
            assertEquals(SecurityProfile.STRICT, strict.profile(),
                    "a block-less route inherits the gateway-wide profile");
            assertEquals(SecurityConfiguration.strict(), strict.configuration(),
                    "and is governed by that profile's preset, not by SecurityConfiguration.defaults()");
            assertEquals(SecurityProfile.LENIENT, lenient.profile());
            assertEquals(SecurityConfiguration.lenient(), lenient.configuration());
        }

        @Test
        @DisplayName("falls back to the gateway-wide profile for a block that omits profile")
        void fallsBackForBlockWithoutProfile() {
            // Arrange
            SecurityFilterConfig noProfile =
                    SecurityFilterConfig.builder().allowedPaths(List.of("/x")).build();

            // Act
            RouteRuntimeAssembler.SecurityPosture posture =
                    GatewayEdgeRoute.securityPostureFor(noProfile, SecurityProfile.LENIENT);

            // Assert
            assertEquals(SecurityProfile.LENIENT, posture.profile(),
                    "a declared block that omits 'profile' still inherits the gateway-wide value");
            assertEquals(SecurityConfiguration.lenient(), posture.configuration(),
                    "an allowlist-only block declares no limit override, so the preset is unchanged");
        }

        @Test
        @DisplayName("lets a declared route profile win over the gateway-wide one")
        void letsDeclaredRouteProfileWin() {
            // Arrange
            SecurityFilterConfig declared =
                    SecurityFilterConfig.builder().profile("lenient").build();

            // Act
            RouteRuntimeAssembler.SecurityPosture posture =
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.STRICT);

            // Assert
            assertEquals(SecurityProfile.LENIENT, posture.profile(), "the route's own profile wins");
            assertEquals(SecurityConfiguration.lenient(), posture.configuration());
        }

        @Test
        @DisplayName("gives a minimal route the nearest non-minimal profile's limits so the body cap stays enforceable")
        void givesMinimalRouteConcreteLimits() {
            // Arrange
            SecurityFilterConfig minimal =
                    SecurityFilterConfig.builder().profile("minimal").build();

            // Act — chain minimal → lenient, then the all-minimal chain
            RouteRuntimeAssembler.SecurityPosture inheritsLenient =
                    GatewayEdgeRoute.securityPostureFor(minimal, SecurityProfile.LENIENT);
            RouteRuntimeAssembler.SecurityPosture allMinimal =
                    GatewayEdgeRoute.securityPostureFor(minimal, SecurityProfile.MINIMAL);
            RouteRuntimeAssembler.SecurityPosture globalMinimalBlockLess =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.MINIMAL);

            // Assert
            assertEquals(SecurityProfile.MINIMAL, inheritsLenient.profile(), "the mode itself stays 'minimal'");
            assertEquals(SecurityConfiguration.lenient(), inheritsLenient.configuration(),
                    "'minimal' takes the nearest non-minimal profile's limits");
            assertEquals(SecurityConfiguration.strict(), allMinimal.configuration(),
                    "an all-minimal chain lands on STRICT rather than leaving the limits unresolved");
            assertEquals(SecurityProfile.MINIMAL, globalMinimalBlockLess.profile(),
                    "a gateway-wide 'minimal' also reaches a route with no security_filter block");
            assertEquals(SecurityConfiguration.strict(), globalMinimalBlockLess.configuration());
        }

        @Test
        @DisplayName("overrides only the declared limits and leaves every other dimension on the preset")
        void overridesOnlyDeclaredLimits() {
            // Arrange — one declared dimension against the strict preset
            SecurityConfiguration preset = SecurityConfiguration.strict();
            SecurityFilterConfig declared = SecurityFilterConfig.builder()
                    .maxBodyBytes(4096)
                    .allowedContentTypes(List.of("application/json"))
                    .build();

            // Act
            SecurityConfiguration resolved =
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.STRICT).configuration();

            // Assert — the declared dimensions win …
            assertEquals(4096L, resolved.maxBodySize(), "a declared max_body_bytes overrides the preset");
            assertEquals(Set.of("application/json"), resolved.allowedContentTypes(),
                    "a declared content-type allowlist overrides the preset");

            // … and every undeclared dimension stays on the preset rather than reverting to defaults().
            assertEquals(preset.maxPathLength(), resolved.maxPathLength());
            assertEquals(preset.maxParameterCount(), resolved.maxParameterCount());
            assertEquals(preset.maxHeaderValueLength(), resolved.maxHeaderValueLength());
            assertEquals(preset.failOnSuspiciousPatterns(), resolved.failOnSuspiciousPatterns());
            assertEquals(preset.allowDoubleEncoding(), resolved.allowDoubleEncoding());
            assertNotEquals(SecurityConfiguration.defaults().maxBodySize(), resolved.maxBodySize(),
                    "the resolved policy is the route's, never the bare cui-http default");
        }

        @Test
        @DisplayName("keeps a route's declared header allow/block lists on top of the preset")
        void keepsDeclaredHeaderLists() {
            // Arrange
            SecurityFilterConfig declared = SecurityFilterConfig.builder()
                    .maxHeaderCount(11)
                    .maxHeaderValueLength(2222)
                    .maxQueryParams(7)
                    .maxParamValueLength(333)
                    .allowedHeaderNames(List.of("Accept"))
                    .blockedHeaderNames(List.of("X-Debug"))
                    .build();

            // Act
            SecurityConfiguration resolved =
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.LENIENT).configuration();

            // Assert
            assertEquals(11, resolved.maxHeaderCount());
            assertEquals(2222, resolved.maxHeaderValueLength());
            assertEquals(7, resolved.maxParameterCount());
            assertEquals(333, resolved.maxParameterValueLength());
            assertEquals(Set.of("Accept"), resolved.allowedHeaderNames());
            assertEquals(Set.of("X-Debug"), resolved.blockedHeaderNames());
        }

        /**
         * Semantic drift guard for {@code GatewayEdgeRoute.builderSeededFrom}, which mirrors the
         * third-party {@link SecurityConfiguration} record component-by-component. A component the
         * copy drops silently reverts to the {@code defaults()} policy as soon as a route declares
         * any {@code security_filter} limit — a posture regression with no other failing test.
         */
        @Test
        @DisplayName("round-trips every preset component when an override restates the preset's own value")
        void roundTripsPresetThroughTheSeededBuilder() {
            // Arrange / Act / Assert — one non-minimal preset per branch of limitsProfile
            assertPresetRoundTrips(SecurityProfile.STRICT);
            assertPresetRoundTrips(SecurityProfile.LENIENT);
        }

        /**
         * Cheap tripwire so a cui-http upgrade that grows the record surfaces the review question
         * even when the round-trip above happens to still pass (a dropped component whose preset
         * value coincides with the {@code defaults()} value).
         */
        @Test
        @DisplayName("fails when the cui-http SecurityConfiguration record grows a component the copy does not know")
        void tripwiresOnSecurityConfigurationComponentDrift() {
            // Arrange — the number of components GatewayEdgeRoute.builderSeededFrom copies
            int copiedByBuilderSeededFrom = 24;

            // Act
            int declaredComponents = SecurityConfiguration.class.getRecordComponents().length;

            // Assert
            assertEquals(copiedByBuilderSeededFrom, declaredComponents,
                    "builderSeededFrom copies %d components but SecurityConfiguration declares %d — extend the copy before upgrading cui-http"
                            .formatted(copiedByBuilderSeededFrom, declaredComponents));
        }

        private void assertPresetRoundTrips(SecurityProfile profile) {
            // Arrange — restate exactly one dimension at the preset's own value, so the rebuilt
            // configuration must come back equal to the preset unless a component was dropped.
            SecurityConfiguration preset = SecurityProfile.limitsProfile(profile, profile).preset();
            SecurityFilterConfig declared = SecurityFilterConfig.builder()
                    .maxQueryParams(preset.maxParameterCount())
                    .build();

            // Act
            SecurityConfiguration resolved =
                    GatewayEdgeRoute.securityPostureFor(declared, profile).configuration();

            // Assert
            assertEquals(preset, resolved,
                    "seeding the builder from the %s preset must round-trip to that preset".formatted(profile));
        }
    }

    /**
     * The two pre-route header-value carve-outs the edge hands to {@code BasicChecksStage}. Both are
     * asserted directly rather than through a booted edge, whose assembled stages are not observable.
     * <p>
     * The load-bearing assertion is that each carve-out differs from the <em>resolved baseline</em> in
     * {@code maxHeaderValueLength} and in nothing else — ADR-0019's "only the length cap changes"
     * bound. It was NOT true of the cookie carve-out before this change: seeding it from
     * {@code SecurityConfiguration.builder()} silently also relaxed {@code failOnSuspiciousPatterns},
     * {@code allowExtendedAscii} and {@code caseSensitiveComparison} on a strict gateway. The
     * component sweep below is exhaustive and reflection-driven, so a future cui-http component is
     * covered without editing this test.
     * <p>
     * The cookie tests are a matched control pair over ADR-0019's second bound — the DIRECTION of
     * change is admit-more-length-only. The strict baseline is the positive control (the cap is
     * genuinely raised, so the {@code max} did not neutralise the carve-out); the lenient baseline is
     * the regression control (the cap must stay at the higher baseline rather than dropping to the
     * smaller cookie budget). Each carries an explicit precondition assertion, so neither can pass
     * vacuously if a preset's cap moves relative to the shipped default budget.
     */
    @Nested
    @DisplayName("pre-route header-value carve-outs (Authorization and Cookie)")
    class HeaderCarveOutConfiguration {

        private static final int DECLARED_AUTHORIZATION_CAP = 12_000;

        /** Mirrors the private {@code GatewayEdgeRoute.COOKIE_HEADER_OVERHEAD_BYTES}. */
        private static final int COOKIE_HEADER_OVERHEAD_BYTES = 512;

        /** The cap the shipped-default cookie budget derives — 4608, deliberately compared against
         * BOTH baselines below so the max() bound is pinned from above and from below. */
        private static final int DEFAULT_COOKIE_HEADER_CAP =
                SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET + COOKIE_HEADER_OVERHEAD_BYTES;

        @Test
        @DisplayName("the Authorization carve-out differs from a strict baseline in the length cap alone")
        void authorizationCarveOutSeededFromStrictBaseline() {
            // Arrange — a bearer-only gateway declaring no security_defaults block at all
            SecurityConfiguration baseline = SecurityConfiguration.strict();

            // Act
            SecurityConfiguration carveOut = GatewayEdgeRoute.authorizationHeaderConfigurationFor(
                    GatewayConfig.builder().version(1).build(), baseline);

            // Assert
            assertEquals(SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH,
                    carveOut.maxHeaderValueLength(), "an omitted key resolves to the documented default");
            assertNotEquals(baseline.maxHeaderValueLength(), carveOut.maxHeaderValueLength(),
                    "the carve-out must actually raise the strict cap — otherwise it relaxes nothing");
            assertDiffersFromBaselineInCapAlone(baseline, carveOut);
        }

        @Test
        @DisplayName("the Authorization carve-out differs from a lenient baseline in the length cap alone")
        void authorizationCarveOutSeededFromLenientBaseline() {
            // Arrange — a declared budget, so the cap genuinely differs from the lenient preset's own
            SecurityConfiguration baseline = SecurityConfiguration.lenient();

            // Act
            SecurityConfiguration carveOut = GatewayEdgeRoute.authorizationHeaderConfigurationFor(
                    gatewayWithAuthorizationCap(DECLARED_AUTHORIZATION_CAP), baseline);

            // Assert
            assertEquals(DECLARED_AUTHORIZATION_CAP, carveOut.maxHeaderValueLength(),
                    "the operator-declared budget wins over the default");
            assertNotEquals(baseline.maxHeaderValueLength(), carveOut.maxHeaderValueLength());
            assertDiffersFromBaselineInCapAlone(baseline, carveOut);
        }

        @Test
        @DisplayName("the Authorization carve-out falls back to the default when the key is omitted")
        void authorizationCarveOutFallsBackToTheDefault() {
            // Arrange — a security_defaults block that declares a profile but omits the budget key,
            // which is a different shape from an entirely absent block and must resolve identically.

            // Act
            SecurityConfiguration blockPresent = GatewayEdgeRoute.authorizationHeaderConfigurationFor(
                    gatewayWithAuthorizationCap(null), SecurityConfiguration.strict());
            SecurityConfiguration blockAbsent = GatewayEdgeRoute.authorizationHeaderConfigurationFor(
                    GatewayConfig.builder().version(1).build(), SecurityConfiguration.strict());

            // Assert
            assertEquals(SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH,
                    blockPresent.maxHeaderValueLength(),
                    "a declared block omitting the key resolves to the default");
            assertEquals(blockAbsent, blockPresent,
                    "an omitted key and an omitted block resolve to the same policy");
        }

        @Test
        @DisplayName("the cookie carve-out is absent for a gateway that is not an active cookie-mode BFF")
        void cookieCarveOutIsAbsentOutsideCookieMode() {
            // Arrange — the three ways to miss the mode: no BFF runtime at all, an active runtime on a
            // gateway declaring no session block, and an active runtime whose declared mode is server.
            // Only the last actually invokes isCookieMode(); the middle one is answered by orElse(false)
            // before the predicate is reached, so on its own it leaves the defect-prone branch untested.

            // Act
            SecurityConfiguration inertRuntime = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    cookieModeGateway(), BffRuntime.inert(), SecurityConfiguration.strict());
            SecurityConfiguration sessionAbsent = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    GatewayConfig.builder().version(1).build(), activeCookieRuntime(),
                    SecurityConfiguration.strict());
            SecurityConfiguration serverMode = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    sessionModeGateway(OidcConfig.Session.MODE_SERVER), activeCookieRuntime(),
                    SecurityConfiguration.strict());

            // Assert
            assertNull(inertRuntime, "a bearer-only gateway keeps the resolved baseline on every header");
            assertNull(sessionAbsent,
                    "a gateway declaring no session block keeps the resolved baseline on every header");
            assertNull(serverMode,
                    "an active server-mode BFF keeps the resolved baseline on every header");
        }

        @Test
        @DisplayName("the cookie carve-out RAISES a strict baseline to the budget plus the header overhead")
        void cookieCarveOutRaisesAStrictBaseline() {
            // Arrange — the positive half of the max()-bound control pair, and the regression this
            // assertion originally existed for: on a strict gateway the cookie carve-out used to be
            // built from the builder defaults, quietly relaxing three further validators the ADR
            // promised were untouched.
            SecurityConfiguration baseline = SecurityConfiguration.strict();

            // Act
            SecurityConfiguration carveOut = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    cookieModeGateway(), activeCookieRuntime(), baseline);

            // Assert — the max() must not neutralise the carve-out where it is genuinely needed
            assertNotNull(carveOut, "an active cookie-mode BFF gets a carve-out");
            assertTrue(baseline.maxHeaderValueLength() < DEFAULT_COOKIE_HEADER_CAP,
                    "control precondition: the strict baseline must sit BELOW the default cookie cap");
            assertEquals(DEFAULT_COOKIE_HEADER_CAP, carveOut.maxHeaderValueLength(),
                    "the cap must admit the whole sealed-cookie budget plus the header overhead");
            assertDiffersFromBaselineInCapAlone(baseline, carveOut);
        }

        @Test
        @DisplayName("the cookie carve-out never LOWERS a lenient baseline to the smaller cookie budget")
        void cookieCarveOutNeverLowersALenientBaseline() {
            // Arrange — the regression half of the control pair. The shipped DEFAULT budget (4096)
            // plus the 512-byte overhead is 4608, which sits BELOW the lenient preset's 8192 baseline,
            // so setting the cap outright turned this carve-out into a per-header TIGHTENING: a
            // cookie-mode BFF on a lenient gateway rejected 400 every Cookie value between 4609 and
            // 8192 while admitting every other header to 8192. No misconfiguration required.
            SecurityConfiguration baseline = SecurityConfiguration.lenient();

            // Act
            SecurityConfiguration carveOut = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    cookieModeGateway(), activeCookieRuntime(), baseline);

            // Assert
            assertNotNull(carveOut);
            assertTrue(baseline.maxHeaderValueLength() > DEFAULT_COOKIE_HEADER_CAP,
                    "control precondition: the lenient baseline must sit ABOVE the default cookie cap, "
                            + "otherwise this test cannot observe a lowering");
            assertEquals(baseline.maxHeaderValueLength(), carveOut.maxHeaderValueLength(),
                    "a carve-out may only ever ADMIT MORE length — it must keep the higher baseline cap");
            assertEquals(baseline, carveOut,
                    "with the cap already sufficient the carve-out policy is the baseline itself");
        }

        /**
         * Exhaustive component sweep: every {@link SecurityConfiguration} record component except
         * {@code maxHeaderValueLength} must carry the resolved baseline's value. Reflection rather
         * than a hand-written list so a component added by a cui-http upgrade is covered here the
         * moment it exists.
         */
        private void assertDiffersFromBaselineInCapAlone(SecurityConfiguration baseline,
                SecurityConfiguration carveOut) {
            for (RecordComponent component : SecurityConfiguration.class.getRecordComponents()) {
                if ("maxHeaderValueLength".equals(component.getName())) {
                    continue;
                }
                Object baselineValue = assertDoesNotThrow(() -> component.getAccessor().invoke(baseline));
                Object carveOutValue = assertDoesNotThrow(() -> component.getAccessor().invoke(carveOut));
                assertEquals(baselineValue, carveOutValue,
                        "carve-out component '%s' must stay on the resolved baseline — only the length cap changes"
                                .formatted(component.getName()));
            }
        }

        private GatewayConfig gatewayWithAuthorizationCap(@Nullable Integer cap) {
            return GatewayConfig.builder().version(1)
                    .securityDefaults(new SecurityDefaultsConfig("strict", cap, null))
                    .build();
        }

        private GatewayConfig cookieModeGateway() {
            return sessionModeGateway(OidcConfig.Session.MODE_COOKIE);
        }

        /**
         * A gateway declaring an {@code oidc.session} block at the given mode. Parameterised over the
         * mode so a server-mode gateway can be built too: only a gateway that actually declares a
         * session block reaches {@link OidcConfig.Session#isCookieMode()} at all — one without an
         * {@code oidc} block is answered by the {@code orElse(false)} before the predicate is invoked.
         */
        private GatewayConfig sessionModeGateway(String mode) {
            return GatewayConfig.builder().version(1)
                    .oidc(OidcConfig.builder()
                            .session(OidcConfig.Session.builder()
                                    .mode(mode)
                                    .build())
                            .build())
                    .build();
        }

        private BffRuntime activeCookieRuntime() {
            return GatewayEdgeRouteBffWiringTest.activeRuntime(
                    GatewayEdgeRouteBffWiringTest.serverBinding(new InMemorySessionStore(16)));
        }
    }

    private WebSocket connectWs(WebSocketClient client, int port) throws Exception {
        return Awaits.connect(client.connect(new WebSocketConnectOptions()
                .setHost("127.0.0.1").setPort(port).setURI("/w/room")),
                "the WebSocket upgrade to complete");
    }

    /**
     * Retries the upgrade until the relay sub-permit released at teardown becomes visible. The release
     * lands on the Vert.x event loop after the socket close round-trips, so the first attempt can
     * legitimately still see the exhausted budget.
     */
    private WebSocket connectWhenAdmitted(WebSocketClient client, int port) throws Exception {
        AtomicReference<WebSocket> admitted = new AtomicReference<>();
        Awaits.until(() -> {
            try {
                admitted.set(connectWs(client, port));
                return true;
            } catch (ExecutionException _) {
                return false;
            }
        }, "an upgrade to be admitted after the relay sub-permit was returned at teardown",
                Awaits.CONNECT_CEILING_SECONDS);
        return admitted.get();
    }

    private static int statusOf(HttpClient client, int port) throws Exception {
        return Awaits.connect(
                client.request(io.vertx.core.http.HttpMethod.GET, port, "127.0.0.1", "/unmatched")
                        .compose(HttpClientRequest::send),
                "the edge response to GET /unmatched").statusCode();
    }

    private static void awaitReleased(AtomicBoolean guard, String message) throws TimeoutException {
        Awaits.until(guard::get, message, Awaits.TEARDOWN_CEILING_SECONDS);
        assertTrue(guard.get(), message);
    }

    private static ResolvedRoute webSocketRoute(int upstreamPort) {
        return ResolvedRoute.builder()
                .id("w")
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix("/w").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("http", "127.0.0.1", upstreamPort, ""))
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

    private static ResolvedRoute route(String id, Protocol protocol, Require require) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("https", id + ".example", 443, ""))
                .build();
    }
}
