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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.SessionIdentity;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigLoader;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.portal.PortalRenderer;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.EgressTrustProfileResolver;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.TrustOptions;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import lombok.experimental.Delegate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Boot-time and lifecycle contract of the public data-plane edge. The per-request serving behaviour
 * (pipeline stages over a live Vert.x server, h2 abuse bounds, streamed relay on the public port) is
 * exercised end-to-end by the {@code integration-tests} module; these module tests cover the
 * deterministic, server-free guarantees: clean boot assembly, fail-fast on an invalid route set,
 * the catch-all registered last, and a bounded graceful drain.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — boot-time assembly, catch-all registration, and graceful drain")
@Tag("isolated-fork")
class GatewayEdgeRouteTest {

    /** The logical trust-profile name the egress-TLS binding tests bind and assert against. */
    private static final String PROFILE = "corporate-up";

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
    @DisplayName("boots over an empty route table with no upstream client and the one edge-wide WebSocket client")
    void bootsCleanlyOverEmptyRouteTable() {
        // Arrange
        RouteTable emptyTable = new RouteTable(List.of());

        // Act — assembling every stage once, at boot
        ClientWiring wiring = clientWiringOf(emptyTable);

        // Assert
        assertEquals(new ClientWiring(0, 0, 1), wiring,
                "an empty route table names no upstream, so no upstream HTTP client is built; the WebSocket "
                        + "client is edge-wide and is built whatever the routes are");
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
        RouteTable openTable = new RouteTable(List.of(
                route("s", Protocol.HTTP, Require.NONE)));

        // Act — a require:session route assembles at boot; its stage-4 runtime is the
        // SessionAuthenticationStage (D4), which replaced the boot-time CONFIG_INVALID rejection. This
        // edge wires no session runtime, so such a route is only rejected at request time, not at boot.
        ClientWiring sessionWiring = clientWiringOf(sessionTable);
        ClientWiring openWiring = clientWiringOf(openTable);

        // Assert
        assertAll("a require:session route is assembled, not skipped",
                () -> assertEquals(new ClientWiring(1, 0, 1), sessionWiring,
                        "the route's one default upstream HTTP client is built at boot"),
                () -> assertEquals(openWiring, sessionWiring,
                        "session auth is a stage-4 concern: the upstream wiring is that of the same route "
                                + "without it"));
    }

    @Test
    @DisplayName("boots a gRPC route onto a forced-HTTP/2 upstream client")
    void bootsGrpcProtocol() {
        // Arrange
        RouteTable grpcTable = new RouteTable(List.of(
                route("g", Protocol.GRPC, Require.NONE)));

        // Act
        ClientWiring wiring = clientWiringOf(grpcTable);

        // Assert — GRPC is registered, so the route is assembled by the gRPC processor's rules
        assertEquals(new ClientWiring(0, 1, 1), wiring,
                "a gRPC route dials its upstream over a forced-HTTP/2 client and builds no default one");
    }

    @Test
    @DisplayName("boots a WebSocket route without a forced-HTTP/2 client, beside the edge-wide WebSocket client")
    void bootsWebSocketProtocol() {
        // Arrange
        RouteTable webSocketTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, Require.NONE)));

        // Act
        ClientWiring wiring = clientWiringOf(webSocketTable);

        // Assert — WEBSOCKET is registered, so the route is assembled by the WebSocket processor's rules
        assertEquals(new ClientWiring(1, 0, 1), wiring,
                "a WebSocket route's upstream tuple builds a default client and never a forced-HTTP/2 one; "
                        + "the relay itself runs over the one edge-wide WebSocket client");
    }

    @Test
    @DisplayName("boots a session-auth WebSocket route with the wiring of the same route without session auth")
    void bootsSessionAuthWebSocketRoute() {
        // Arrange
        RouteTable sessionTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, Require.SESSION)));
        RouteTable openTable = new RouteTable(List.of(
                route("w", Protocol.WEBSOCKET, Require.NONE)));

        // Act
        ClientWiring sessionWiring = clientWiringOf(sessionTable);
        ClientWiring openWiring = clientWiringOf(openTable);

        // Assert — session auth no longer gates boot, so a session-auth WebSocket route assembles
        // exactly like any other WebSocket route.
        assertAll("a session-auth WebSocket route is assembled, not skipped",
                () -> assertEquals(1, sessionWiring.webSocketClients(),
                        "the edge-wide WebSocket client its relay runs over is built"),
                () -> assertEquals(openWiring, sessionWiring,
                        "session auth leaves the route's upstream wiring unchanged"));
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
                client.request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS,
                        "/nothing")
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
                            .listen(0, LoopbackHost.ADDRESS),
                    "the stub upstream WebSocket server to start listening");
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
                            .listen(0, LoopbackHost.ADDRESS),
                    "the stub upstream WebSocket server to start listening");
            Router router = Router.router(vertx);
            new GatewayEdgeRoute(new RouteTable(List.of(webSocketRoute(upstream.actualPort()))), gatewayConfig,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                    new EdgeHardeningOptions(new EdgeHardeningConfig(2, 1)),
                    new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(),
                    unconsultedTrustProfileResolver(), PortalEndpoint.inert(),
                    GatewayEdgeRouteBffWiringTest.gatewayJson()).registerRoutes(router);
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
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
            return Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
        }
    }

    /**
     * A relay that fails while it is being started — here the real {@link ResponseStage} handed an
     * upstream response that already ended, whose {@code pipeTo} throws "Response already ended" — is
     * answered through the same failure path as a relay that fails mid-stream: the client receives a
     * well-framed {@code 502} instead of waiting for a response that never comes, and the upstream
     * response the relay never consumed is released.
     */
    @Nested
    @DisplayName("a relay that cannot start still answers the client")
    class RelayStartFailure {

        /** The size of an upstream answer the relay never reads, far beyond what the transport buffers. */
        private static final int LARGE_BODY_BYTES = 4 * 1024 * 1024;
        private static final String LARGE_PATH = "/large";
        private static final String ANSWERING_PATH = "/answering";
        private static final String ANSWER = "ok";

        @Test
        @DisplayName("answers an empty 502 when the upstream response ended before the relay subscribed")
        void answersBadGatewayWhenTheRelayCannotStart() throws Exception {
            HttpServer upstream = Awaits.connect(vertx.createHttpServer()
                            .requestHandler(request -> request.response().end("tiny"))
                            .listen(0, LoopbackHost.ADDRESS),
                    "the stub upstream to start listening");
            HttpClient upstreamClient = vertx.createHttpClient();
            Router router = Router.router(vertx);
            router.route().handler(ctx -> upstreamClient
                    .request(io.vertx.core.http.HttpMethod.GET, upstream.actualPort(), LoopbackHost.ADDRESS, "/")
                    .compose(HttpClientRequest::send)
                    .compose(response -> response.end().map(response))
                    .onSuccess(ended -> GatewayEdgeRoute.relayOnEventLoop(ctx, List.of(), ended, () -> new ResponseStage(Set.of())
                            .relay(ended, ctx.response(), false, null, Map.of(), Map.of()))));
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the relaying front server to start listening");
            HttpClient client = vertx.createHttpClient();
            try {
                String answer = Awaits.connect(client
                                .request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, "/")
                                .compose(HttpClientRequest::send)
                                .compose(response -> response.body()
                                        .map(body -> response.statusCode() + " [" + body + "]")),
                        "the answer to a relay that could not start");

                assertEquals("502 []", answer,
                        "the relay-start failure ends the response as a 502 framed for the empty body it carries,"
                                + " not with the upstream Content-Length the relay had already copied");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the relaying front server to close");
                Awaits.teardown(upstreamClient.close(), "the upstream client to close");
                Awaits.teardown(upstream.close(), "the stub upstream to close");
            }
        }

        /**
         * The upstream response is received and paused exactly as {@code DispatchStage} leaves it, over
         * an upstream client holding at most one HTTP/1.1 connection, and its body is far larger than
         * what the transport buffers. A released response is therefore observable twice over: the stub
         * upstream sees its connection closed, and the next request on the same client is served.
         */
        @Test
        @DisplayName("a relay that fails to start releases the upstream response: its connection closes and the client serves the next request")
        void relayThatFailsToStartReleasesTheUpstreamResponse() throws Exception {
            // Arrange
            List<String> closedConnections = new CopyOnWriteArrayList<>();
            HttpServer upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                        String path = request.path();
                        request.connection().closeHandler(_ -> closedConnections.add(path));
                        if (LARGE_PATH.equals(path)) {
                            request.response().end(Buffer.buffer(new byte[LARGE_BODY_BYTES]));
                        } else {
                            request.response().end(ANSWER);
                        }
                    }).listen(0, LoopbackHost.ADDRESS),
                    "the stub upstream to start listening");
            HttpClient upstreamClient = vertx.createHttpClient(new HttpClientOptions(),
                    new PoolOptions().setHttp1MaxSize(1));
            AtomicReference<@Nullable HttpClientResponse> pausedUpstream = new AtomicReference<>();
            Router router = Router.router(vertx);
            router.route().handler(ctx -> upstreamClient
                    .request(io.vertx.core.http.HttpMethod.GET, upstream.actualPort(), LoopbackHost.ADDRESS, LARGE_PATH)
                    .compose(request -> request.send().map(received -> {
                        received.pause();
                        return received;
                    }))
                    .onSuccess(received -> {
                        pausedUpstream.set(received);
                        GatewayEdgeRoute.relayOnEventLoop(ctx, List.of(), received, () -> {
                            throw new IllegalStateException("the relay could not be started");
                        });
                    }));
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the relaying front server to start listening");
            HttpClient client = vertx.createHttpClient();
            try {
                // Act
                String answer = Awaits.connect(client
                                .request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, "/")
                                .compose(HttpClientRequest::send)
                                .compose(response -> response.body()
                                        .map(body -> response.statusCode() + " [" + body + "]")),
                        "the answer to a relay that could not start");
                Awaits.until(() -> closedConnections.contains(LARGE_PATH),
                        "the upstream to see the connection of the unrelayed response closed",
                        Awaits.CONNECT_CEILING_SECONDS);
                String next = Awaits.connect(upstreamClient
                                .request(io.vertx.core.http.HttpMethod.GET, upstream.actualPort(), LoopbackHost.ADDRESS,
                                        ANSWERING_PATH)
                                .compose(HttpClientRequest::send)
                                .compose(response -> response.body().map(body -> response.statusCode() + " " + body)),
                        "the next request on the single pooled upstream connection to be answered");

                // Assert
                assertAll("a relay that failed to start",
                        () -> assertNotNull(pausedUpstream.get(),
                                "the upstream response must have been received and paused before the relay was started"),
                        () -> assertEquals("502 []", answer, "the client is answered with an empty 502"),
                        () -> assertTrue(closedConnections.contains(LARGE_PATH),
                                "the upstream connection of the unrelayed response must be closed"),
                        () -> assertEquals("200 " + ANSWER, next,
                                "the single pooled upstream connection must be free again for the next request"));
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the relaying front server to close");
                Awaits.teardown(upstreamClient.close(), "the upstream client to close");
                Awaits.teardown(upstream.close(), "the stub upstream to close");
            }
        }
    }

    /**
     * The RFC 9457 body the edge writes for a rejection, asserted directly on the rendering seam. The
     * expected bodies are exact literals because the wire form is the contract: member order, the
     * absence of whitespace and the escaping are all what a client parses. The same rendering over a
     * live server, for the session-route {@code 403}, is driven by
     * {@code GatewayEdgeRouteBffWiringTest}.
     */
    @Nested
    @DisplayName("problem+json body rendering (RFC 9457 extension members)")
    class ProblemBodyRendering {

        private static final String TYPE = EventCategory.AUTHORIZATION.problemType();
        private static final String TITLE = EventCategory.AUTHORIZATION.title();
        private static final String LOG_MESSAGE = "internal detail that is logged, never rendered";

        @Test
        @DisplayName("renders exactly the three standard members for a rejection without extension members")
        void rendersUnchangedBodyWithoutMembers() {
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING, LOG_MESSAGE);

            String body = GatewayEdgeRoute.problemBody(GatewayEdgeRouteBffWiringTest.gatewayJson(), TYPE, TITLE, EventType.SCOPE_MISSING.httpStatus(),
                    rejected.getProblemExtensions());

            assertEquals("""
                    {"type":"urn:api-sheriff:problem:authorization","title":"Authorization","status":403}""", body,
                    "a rejection without members keeps the pre-existing body byte for byte");
        }

        @Test
        @DisplayName("appends a rejection's extension members after the standard members, in insertion order")
        void rendersExtensionMembersInInsertionOrder() {
            Map<String, Object> members = new LinkedHashMap<>();
            members.put("missing_scopes", List.of("orders:read", "orders:write"));
            members.put("step_up_url", "/auth/step-up?returnUrl=%2Fapp%2Forders");
            GatewayException rejected = new GatewayException(EventType.SCOPE_MISSING, LOG_MESSAGE, members);

            String body = GatewayEdgeRoute.problemBody(GatewayEdgeRouteBffWiringTest.gatewayJson(), TYPE, TITLE, EventType.SCOPE_MISSING.httpStatus(),
                    rejected.getProblemExtensions());

            assertAll(
                    () -> assertEquals("""
                            {"type":"urn:api-sheriff:problem:authorization","title":"Authorization","status":403,\
                            "missing_scopes":["orders:read","orders:write"],\
                            "step_up_url":"/auth/step-up?returnUrl=%2Fapp%2Forders"}""", body,
                            "the members follow the standard ones in the order they were added"),
                    () -> assertFalse(body.contains(LOG_MESSAGE), "the log message never reaches the body"));
        }

        @Test
        @DisplayName("escapes extension member names and values as JSON strings")
        void escapesExtensionMemberNamesAndValues() {
            Map<String, Object> members = Map.of("quoted\"name", "back\\slash and\nnewline");

            String body = GatewayEdgeRoute.problemBody(GatewayEdgeRouteBffWiringTest.gatewayJson(), TYPE, TITLE, EventType.SCOPE_MISSING.httpStatus(), members);

            assertEquals("""
                    {"type":"urn:api-sheriff:problem:authorization","title":"Authorization","status":403,\
                    "quoted\\"name":"back\\\\slash and\\nnewline"}""", body,
                    "a member can never break out of its JSON string");
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
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.STRICT, null);
            RouteRuntimeAssembler.SecurityPosture lenient =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.LENIENT, null);

            // Assert
            assertEquals(SecurityProfile.STRICT, strict.profile(),
                    "a block-less route inherits the gateway-wide profile");
            assertEquals(SecurityProfile.STRICT.preset(), strict.configuration(),
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
                    GatewayEdgeRoute.securityPostureFor(noProfile, SecurityProfile.LENIENT, null);

            // Assert
            assertEquals(SecurityProfile.LENIENT, posture.profile(),
                    "a declared block that omits 'profile' still inherits the gateway-wide value");
            assertEquals(SecurityConfiguration.lenient(), posture.configuration(),
                    "an allowlist-only block declares no limit override, so the preset is unchanged");
        }

        @Test
        @DisplayName("applies a declared allow_extended_ascii to every route's preset and changes nothing else")
        void appliesDeclaredExtendedAsciiOverrideToEveryPreset() {
            // Arrange — both directions: relax the strict preset, tighten the lenient one
            SecurityFilterConfig limitsOnly = SecurityFilterConfig.builder().maxBodyBytes(2048).build();

            // Act
            SecurityConfiguration relaxedStrict =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.STRICT, true).configuration();
            SecurityConfiguration tightenedLenient =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.LENIENT, false).configuration();
            SecurityConfiguration relaxedWithLimits =
                    GatewayEdgeRoute.securityPostureFor(limitsOnly, SecurityProfile.STRICT, true).configuration();
            SecurityConfiguration omitted =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.STRICT, null).configuration();

            // Assert
            assertAll(
                    () -> assertTrue(relaxedStrict.allowExtendedAscii(), "true relaxes the strict preset"),
                    () -> assertFalse(tightenedLenient.allowExtendedAscii(), "false tightens the lenient preset"),
                    () -> assertTrue(relaxedWithLimits.allowExtendedAscii(),
                            "a route declaring its own limits still carries the gateway-wide override"),
                    () -> assertEquals(2048L, relaxedWithLimits.maxBodySize(),
                            "and still carries its own declared limit"),
                    () -> assertEquals(SecurityProfile.STRICT.preset(), omitted,
                            "an omitted key leaves the preset untouched"));
            assertDiffersFromPresetInExtendedAsciiAlone(SecurityProfile.STRICT.preset(), relaxedStrict);
            assertDiffersFromPresetInExtendedAsciiAlone(SecurityConfiguration.lenient(), tightenedLenient);
        }

        private void assertDiffersFromPresetInExtendedAsciiAlone(SecurityConfiguration preset,
                SecurityConfiguration resolved) {
            for (RecordComponent component : SecurityConfiguration.class.getRecordComponents()) {
                if ("allowExtendedAscii".equals(component.getName())) {
                    continue;
                }
                Object presetValue = assertDoesNotThrow(() -> component.getAccessor().invoke(preset));
                Object resolvedValue = assertDoesNotThrow(() -> component.getAccessor().invoke(resolved));
                assertEquals(presetValue, resolvedValue,
                        "component '%s' must stay on the preset — allow_extended_ascii overrides one dimension"
                                .formatted(component.getName()));
            }
        }

        @Test
        @DisplayName("lets a declared route profile win over the gateway-wide one")
        void letsDeclaredRouteProfileWin() {
            // Arrange
            SecurityFilterConfig declared =
                    SecurityFilterConfig.builder().profile("lenient").build();

            // Act
            RouteRuntimeAssembler.SecurityPosture posture =
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.STRICT, null);

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
                    GatewayEdgeRoute.securityPostureFor(minimal, SecurityProfile.LENIENT, null);
            RouteRuntimeAssembler.SecurityPosture allMinimal =
                    GatewayEdgeRoute.securityPostureFor(minimal, SecurityProfile.MINIMAL, null);
            RouteRuntimeAssembler.SecurityPosture globalMinimalBlockLess =
                    GatewayEdgeRoute.securityPostureFor(null, SecurityProfile.MINIMAL, null);

            // Assert
            assertEquals(SecurityProfile.MINIMAL, inheritsLenient.profile(), "the mode itself stays 'minimal'");
            assertEquals(SecurityConfiguration.lenient(), inheritsLenient.configuration(),
                    "'minimal' takes the nearest non-minimal profile's limits");
            assertEquals(SecurityProfile.STRICT.preset(), allMinimal.configuration(),
                    "an all-minimal chain lands on STRICT rather than leaving the limits unresolved");
            assertEquals(SecurityProfile.MINIMAL, globalMinimalBlockLess.profile(),
                    "a gateway-wide 'minimal' also reaches a route with no security_filter block");
            assertEquals(SecurityProfile.STRICT.preset(), globalMinimalBlockLess.configuration());
        }

        @Test
        @DisplayName("overrides only the declared limits and leaves every other dimension on the preset")
        void overridesOnlyDeclaredLimits() {
            // Arrange — one declared dimension against the strict preset
            SecurityConfiguration preset = SecurityProfile.STRICT.preset();
            SecurityFilterConfig declared = SecurityFilterConfig.builder()
                    .maxBodyBytes(4096)
                    .allowedContentTypes(List.of("application/json"))
                    .build();

            // Act
            SecurityConfiguration resolved =
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.STRICT, null).configuration();

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
                    GatewayEdgeRoute.securityPostureFor(declared, SecurityProfile.LENIENT, null).configuration();

            // Assert
            assertEquals(11, resolved.maxHeaderCount());
            assertEquals(2222, resolved.maxHeaderValueLength());
            assertEquals(7, resolved.maxParameterCount());
            assertEquals(333, resolved.maxParameterValueLength());
            assertEquals(Set.of("Accept"), resolved.allowedHeaderNames());
            assertEquals(Set.of("X-Debug"), resolved.blockedHeaderNames());
        }

        /**
         * Semantic drift guard for {@code SecurityConfigurations.builderSeededFrom} — the single
         * seam {@code GatewayEdgeRoute} seeds through — which mirrors the third-party
         * {@link SecurityConfiguration} record component-by-component. A component the copy drops
         * silently reverts to the {@code defaults()} policy as soon as a route declares any
         * {@code security_filter} limit — a posture regression with no other failing test.
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
            // Arrange — the number of components SecurityConfigurations.builderSeededFrom copies
            int copiedByBuilderSeededFrom = 27;

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
                    GatewayEdgeRoute.securityPostureFor(declared, profile, null).configuration();

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

        /** The cap the shipped-default cookie budget derives — 4531 (4019 + 512), deliberately
         * compared against BOTH baselines below so the max() bound is pinned from above and from
         * below. */
        private static final int DEFAULT_COOKIE_HEADER_CAP =
                SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET + COOKIE_HEADER_OVERHEAD_BYTES;

        @Test
        @DisplayName("the Authorization carve-out differs from a strict baseline in the length cap alone")
        void authorizationCarveOutSeededFromStrictBaseline() {
            // Arrange — a bearer-only gateway declaring no security_defaults block at all
            SecurityConfiguration baseline = SecurityProfile.STRICT.preset();

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
                    gatewayWithAuthorizationCap(null), SecurityProfile.STRICT.preset());
            SecurityConfiguration blockAbsent = GatewayEdgeRoute.authorizationHeaderConfigurationFor(
                    GatewayConfig.builder().version(1).build(), SecurityProfile.STRICT.preset());

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
                    cookieModeGateway(), BffRuntime.inert(), SecurityProfile.STRICT.preset());
            SecurityConfiguration sessionAbsent = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    GatewayConfig.builder().version(1).build(), activeCookieRuntime(),
                    SecurityProfile.STRICT.preset());
            SecurityConfiguration serverMode = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    sessionModeGateway(OidcConfig.Session.MODE_SERVER), activeCookieRuntime(),
                    SecurityProfile.STRICT.preset());

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
            SecurityConfiguration baseline = SecurityProfile.STRICT.preset();

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
            // Arrange — the regression half of the control pair. The shipped DEFAULT budget (4019)
            // plus the 512-byte overhead is 4531, which sits BELOW the lenient preset's 8192 baseline,
            // so setting the cap outright turned this carve-out into a per-header TIGHTENING: a
            // cookie-mode BFF on a lenient gateway rejected 400 every Cookie value between 4532 and
            // 8192 while admitting every other header to 8192. No misconfiguration required.
            // Lowering the default from 4096 to 4019 widened that band rather than closing it, which
            // is precisely why the max() bound and this control both have to stay.
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
         * The headroom has to cover every cookie the browser sends beside the session cookie. This case
         * builds the largest such {@code Cookie} header value the gateway's own cookies can produce — a
         * session cookie value at the full default budget, the login binding cookie and the activity
         * cookie, each from its own codec — and holds it against the cap a default cookie-mode gateway
         * derives. The cap itself is unchanged by the activity cookie; this pins that it still suffices.
         */
        @Test
        @DisplayName("the default cookie cap admits a full-budget session cookie, the binding cookie and the activity cookie together")
        void defaultCookieCapAdmitsEveryGatewayCookieTogether() {
            byte[] activityKey = new byte[32];
            Arrays.fill(activityKey, (byte) 0x44);
            SessionActivityCookieCodec activityCodec = new SessionActivityCookieCodec(
                    SessionCookieCodec.DEFAULT_COOKIE_NAME, new SecretKeySpec(activityKey, "AES"), (byte) 2);
            String sessionIdentity = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
            String sessionPair = SessionCookieCodec.DEFAULT_COOKIE_NAME + "="
                    + "v".repeat(SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET);
            String bindingSetCookie = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL)
                    .toSetCookieHeader(PendingAuthorizationRecord.create(
                            FlowContext.create("https://gw.example.com/auth/callback"), "/", List.of("openid"),
                            Instant.now()).id());
            String bindingPair = bindingSetCookie.substring(0, bindingSetCookie.indexOf(';'));
            String activityPair = activityCodec.cookieName() + "="
                    + activityCodec.sign(sessionIdentity, Instant.now());
            String cookieHeaderValue = String.join("; ", sessionPair, bindingPair, activityPair);
            SecurityConfiguration baseline = SecurityProfile.STRICT.preset();

            SecurityConfiguration carveOut = GatewayEdgeRoute.cookieHeaderConfigurationFor(
                    cookieModeGateway(), activeCookieRuntime(), baseline);

            assertNotNull(carveOut, "an active cookie-mode BFF gets a carve-out");
            assertAll("the three gateway cookies fit under the default cap with headroom to spare",
                    () -> assertEquals(DEFAULT_COOKIE_HEADER_CAP, carveOut.maxHeaderValueLength(),
                            "the activity cookie did not change the cap"),
                    () -> assertEquals(88, activityPair.length(), "the activity cookie's name=value pair"),
                    () -> assertTrue(cookieHeaderValue.length() > baseline.maxHeaderValueLength(),
                            "control precondition: the header would not pass the strict baseline on its own"),
                    () -> assertTrue(cookieHeaderValue.length() <= carveOut.maxHeaderValueLength(),
                            "the full-budget header of " + cookieHeaderValue.length() + " characters passes the cap of "
                                    + carveOut.maxHeaderValueLength()),
                    () -> assertTrue(carveOut.maxHeaderValueLength() - cookieHeaderValue.length() >= 256,
                            "and at least half of the 512-byte headroom is left for the proxied applications' "
                                    + "own cookies: " + (carveOut.maxHeaderValueLength() - cookieHeaderValue.length())));
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
                    .securityDefaults(new SecurityDefaultsConfig("strict", cap, null, null))
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
                    GatewayEdgeRouteBffWiringTest.serverBinding(GatewayEdgeRouteBffWiringTest.newStore()));
        }
    }

    /**
     * The forwarded-trust allow-list supplied by one environment variable, asserted through its
     * <em>effect</em> on the trust decision.
     * <p>
     * {@code forwarded.trusted_proxies} is the mandatory CIDR set ADR-0003 requires before any inbound
     * forwarding header is believed. Each leg below drives the whole chain — write a {@code gateway.yaml}
     * carrying a bare {@code ${VAR}}, load it through the real {@link ConfigLoader}, construct the real
     * {@link GatewayEdgeRoute} from the bound config, and serve one real request against a stub upstream
     * on loopback — and the two legs differ in <strong>nothing but the value the environment lookup
     * returns</strong>. That is what makes this an assertion about the substitution reaching the trust
     * decision, rather than an assertion about {@link de.cuioss.sheriff.gateway.forward.TcpPeerGate}:
     * the gate is a local inside the edge's constructor, and this nest adds no accessor, no reflection
     * and no production test-seam to see it.
     * <p>
     * Deleting the loader's list-valued substitution arm turns these tests red, not merely the loader's
     * own — which is the point of asserting here rather than one layer down.
     */
    @Nested
    @DisplayName("forwarded trust supplied by one environment variable")
    class ForwardedTrustFromEnvironment {

        /** A TEST-NET-3 address (RFC 5737) — never routable, so it can only have come from the header. */
        private static final String SPOOFED_CLIENT = "203.0.113.7";
        private static final String FORWARDED_FOR = "X-Forwarded-For";
        private static final String LOOPBACK = LoopbackHost.ADDRESS;

        @TempDir
        Path configDir;

        @Test
        @DisplayName("a peer inside the ${VAR}-supplied CIDR set has its X-Forwarded-For honoured")
        void honoursForwardedHeaderFromAPeerInsideTheSuppliedSet() throws Exception {
            // Arrange + Act — the loopback peer this test dials from IS the supplied set
            Observation observed = proxyOneRequestTrusting(LOOPBACK + "/32");

            // Assert
            assertTrue(observed.reached(), "the request must reach the stub upstream");
            assertNotNull(observed.forwardedFor(),
                    "a trusted peer's chain is regenerated, so the upstream receives X-Forwarded-For");
            assertTrue(observed.forwardedFor().contains(SPOOFED_CLIENT),
                    () -> "a trusted peer's inbound forwarding chain must be honoured, so the client "
                            + "address it claimed reaches the upstream; got: " + observed.forwardedFor());
        }

        @Test
        @DisplayName("matched negative control: the same request from a peer outside the set is ignored")
        void ignoresForwardedHeaderFromAPeerOutsideTheSuppliedSet() throws Exception {
            // Arrange + Act — the ONLY difference from the positive leg is this value: a TEST-NET-2
            // range (RFC 5737) that excludes the loopback address the request actually arrives from.
            Observation observed = proxyOneRequestTrusting("198.51.100.0/24");

            // Assert — the request is still proxied (trust governs header regeneration, not routing),
            // which is what stops this control passing vacuously on a request that never arrived.
            assertTrue(observed.reached(),
                    "an untrusted peer is still served — the trust decision governs which headers are "
                            + "believed, never whether the route is reached");
            // The spoofed claim is masked from the resolver, which then has no chain to regenerate at
            // all — so the upstream receives no X-Forwarded-For rather than a fabricated one. Either
            // way the security-load-bearing property is the same: the claimed address never crosses.
            assertNull(observed.forwardedFor(),
                    () -> "an untrusted peer's masked chain leaves nothing to regenerate; got: "
                            + observed.forwardedFor());
        }

        /** What the stub upstream observed: whether it was reached, and the chain it was handed. */
        private record Observation(boolean reached, @Nullable String forwardedFor) {
        }

        /**
         * Runs one real request through a real edge whose {@code trusted_proxies} was supplied entirely
         * by {@code trustedProxies} through a bare {@code ${VAR}}, and reports what the stub upstream
         * observed.
         */
        private Observation proxyOneRequestTrusting(String trustedProxies) throws Exception {
            Files.writeString(configDir.resolve("gateway.yaml"), """
                    version: 1
                    forwarded:
                      trusted_proxies: "${SHERIFF_TRUSTED_PROXIES}"
                    """);
            GatewayConfig loaded = new ConfigLoader(configDir, new EnvSecretResolver(
                    name -> "SHERIFF_TRUSTED_PROXIES".equals(name) ? trustedProxies : null))
                    .load(defaulted -> {
                        // The document declares no ${VAR:-default}, so there is no fallback to observe.
                    }).gateway();

            // Guard — without this, a substitution that silently produced an empty set would make both
            // legs agree (nothing is trusted) and the negative control would pass for the wrong reason.
            assertEquals(List.of(trustedProxies.split(",")), loaded.forwarded().trustedProxies(),
                    "the whole allow-list must have arrived from the environment before the edge is built");

            AtomicReference<@Nullable String> seenByUpstream = new AtomicReference<>();
            AtomicBoolean reached = new AtomicBoolean();
            HttpServer upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                seenByUpstream.set(request.getHeader(FORWARDED_FOR));
                reached.set(true);
                request.response().end();
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
            Router router = Router.router(vertx);
            new GatewayEdgeRoute(new RouteTable(List.of(proxyRoute(upstream.actualPort()))), loaded,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, hardening,
                    new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(),
                    unconsultedTrustProfileResolver(), PortalEndpoint.inert(),
                    GatewayEdgeRouteBffWiringTest.gatewayJson()).registerRoutes(router);
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            HttpClient client = vertx.createHttpClient();
            try {
                Awaits.connect(client.request(io.vertx.core.http.HttpMethod.GET, front.actualPort(),
                                LOOPBACK, "/t/resource")
                                .compose(request -> request.putHeader(FORWARDED_FOR, SPOOFED_CLIENT).send()),
                        "the edge response to the proxied GET");
                return new Observation(reached.get(), seenByUpstream.get());
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
            }
        }

        private ResolvedRoute proxyRoute(int upstreamPort) {
            return ResolvedRoute.builder()
                    .id("t")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/t").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("http", LOOPBACK, upstreamPort, ""))
                    .build();
        }
    }

    /**
     * The three egress client-construction sites and the gateway-global {@code egress_tls} values
     * they bind (ADR-0040). The assertions read the options objects actually handed to Vert.x rather
     * than the constructed clients, because a client exposes neither setting — and reading the
     * arguments is what makes these tests about the WIRING: a site that stopped passing the resolved
     * values would go red here, which an assertion on a freshly-built options object would not.
     * <p>
     * A route table of one HTTP and one gRPC route produces exactly two {@code HttpClient}s — the
     * assembler keys its client cache on (scheme, host, port, forced-h2) — so both {@code clientFor}
     * branches are covered, and the edge-wide WebSocket client is the third site.
     */
    @Nested
    @EnableTestLogger
    @DisplayName("gateway-global egress_tls bound at all three client-construction sites")
    class EgressTlsBinding {

        @Test
        @DisplayName("hostname verification is on at all three sites for a document with no egress_tls block")
        void verificationOnByDefaultAtAllThreeSites() {
            CapturingVertx capturing = bootWith(null, unconsultedTrustProfileResolver());

            assertAll("every constructed client verifies the upstream hostname",
                    () -> assertTrue(capturing.plainHttpOptions().isVerifyHost(),
                            "the default HTTP client must verify the upstream hostname"),
                    () -> assertTrue(capturing.forcedHttp2Options().isVerifyHost(),
                            "the forced-HTTP/2 client must verify the upstream hostname"),
                    () -> assertTrue(capturing.webSocketOptions().isVerifyHost(),
                            "the edge-wide WebSocket client must verify the upstream hostname"));
        }

        @Test
        @DisplayName("upstream_verify_hostname: false reaches all three sites")
        void verificationOffReachesAllThreeSites() {
            CapturingVertx capturing = bootWith(new EgressTlsConfig(false, true, null, true, null),
                    unconsultedTrustProfileResolver());

            assertAll("the relaxation reaches every constructed client",
                    () -> assertFalse(capturing.plainHttpOptions().isVerifyHost(),
                            "the default HTTP client must carry the relaxed setting"),
                    () -> assertFalse(capturing.forcedHttp2Options().isVerifyHost(),
                            "the forced-HTTP/2 client must carry the relaxed setting"),
                    () -> assertFalse(capturing.webSocketOptions().isVerifyHost(),
                            "the edge-wide WebSocket client must carry the relaxed setting — a wss:// "
                                    + "relay reads it from nowhere else"));
        }

        @Test
        @DisplayName("with no profile configured no trust options are set at any site")
        void noProfileLeavesTrustOptionsUnsetAtAllThreeSites() {
            CapturingVertx capturing = bootWith(new EgressTlsConfig(false, true, null, true, null),
                    unconsultedTrustProfileResolver());

            assertAll("an unnamed profile leaves every client on the JVM default trust store",
                    () -> assertNull(capturing.plainHttpOptions().getTrustOptions(),
                            "no profile named means no setTrustOptions call on the default client"),
                    () -> assertNull(capturing.forcedHttp2Options().getTrustOptions(),
                            "no profile named means no setTrustOptions call on the forced-HTTP/2 client"),
                    () -> assertNull(capturing.webSocketOptions().getTrustOptions(),
                            "no profile named means no setTrustOptions call on the WebSocket client"));
        }

        @Test
        @DisplayName("the default client differs from bare HttpClientOptions in verifyHost alone")
        void defaultClientDiffersFromBareOptionsInVerifyHostOnly() {
            CapturingVertx capturing = bootWith(new EgressTlsConfig(false, true, null, true, null),
                    unconsultedTrustProfileResolver());

            HttpClientOptions restored =
                    new HttpClientOptions(capturing.plainHttpOptions()).setVerifyHost(true);

            assertEquals(new HttpClientOptions().toJson(), restored.toJson(),
                    "restoring verifyHost to its default must leave an object indistinguishable from a "
                            + "bare new HttpClientOptions() — the restructure that introduced the options "
                            + "object must not have changed the protocol version, the client-level SSL "
                            + "flag, h2-upgrade negotiation, or anything else");
        }

        @Test
        @DisplayName("a configured profile's resolved trust options reach all three sites")
        void configuredProfileReachesAllThreeSites() {
            TrustOptions anchors = new PemTrustOptions();

            CapturingVertx capturing = bootWith(new EgressTlsConfig(true, true, PROFILE, true, null),
                    EgressTrustProfiles.binding(PROFILE, anchors));

            assertAll("the resolved anchors reach every constructed client",
                    () -> assertSame(anchors, capturing.plainHttpOptions().getTrustOptions(),
                            "the default HTTP client must verify upstreams against the named anchors"),
                    () -> assertSame(anchors, capturing.forcedHttp2Options().getTrustOptions(),
                            "the forced-HTTP/2 client must verify upstreams against the named anchors"),
                    () -> assertSame(anchors, capturing.webSocketOptions().getTrustOptions(),
                            "the edge-wide WebSocket client must verify upstreams against the named anchors"));
        }

        /**
         * The boot signal for the hostname relaxation. An operator-selected relaxation of a security
         * control must not reach production silently — the rule ApiSheriff-102 (broad trusted proxy)
         * and ApiSheriff-115 (plain-HTTP management) already follow. Asserting the EMISSION rather
         * than the bound flag is the point: the binding tests above already prove the flag acts, and
         * a key that parses is not a key that announces itself.
         */
        @Test
        @DisplayName("disabling upstream hostname verification WARNs at boot and never refuses it")
        void disabledHostnameVerificationWarnsAtBoot() {
            assertDoesNotThrow(() -> {
                bootWith(new EgressTlsConfig(false, true, null, true, null), unconsultedTrustProfileResolver());
            }, "a relaxed-hostname document is a legitimate deployment and must still boot");

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    ConfigLogMessages.WARN.EGRESS_HOSTNAME_VERIFICATION_DISABLED.resolveIdentifierString());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    "upstream_verify_hostname is false");
        }

        @Test
        @DisplayName("matched negative control: a document leaving verification on emits no relaxation WARN")
        void verificationLeftOnEmitsNoWarn() {
            bootWith(null, unconsultedTrustProfileResolver());

            assertNoWarnContaining(
                    ConfigLogMessages.WARN.EGRESS_HOSTNAME_VERIFICATION_DISABLED.resolveIdentifierString(),
                    "a document with no egress_tls block leaves verification on and must stay silent");
        }

        /**
         * The trust-profile boot signal. A named profile REPLACES the clients' anchors rather than
         * adding to them, so naming one is a wider act than it looks — and the audit flagged that as
         * likewise unlogged. The template carries the logical profile name only; no anchor material
         * is reachable from here to leak.
         */
        @Test
        @DisplayName("a named upstream_tls_profile WARNs at boot, naming the logical profile only")
        void namedTrustProfileWarnsAtBoot() {
            assertDoesNotThrow(() -> {
                bootWith(new EgressTlsConfig(true, true, PROFILE, true, null),
                        EgressTrustProfiles.binding(PROFILE, new PemTrustOptions()));
            }, "a named profile is a deliberate posture and must still boot");

            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    ConfigLogMessages.WARN.EGRESS_TRUST_PROFILE_IN_EFFECT.resolveIdentifierString());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, PROFILE);
        }

        @Test
        @DisplayName("matched negative control: no named profile emits no trust-replacement WARN")
        void noNamedProfileEmitsNoWarn() {
            bootWith(new EgressTlsConfig(true, true, null, true, null), unconsultedTrustProfileResolver());

            assertNoWarnContaining(
                    ConfigLogMessages.WARN.EGRESS_TRUST_PROFILE_IN_EFFECT.resolveIdentifierString(),
                    "an omitted upstream_tls_profile leaves the JVM default trust store and must stay silent");
        }

        private void assertNoWarnContaining(String identifier, String why) {
            assertTrue(TestLoggerFactory.getTestHandler()
                            .resolveLogMessagesContaining(TestLogLevel.WARN, identifier).isEmpty(),
                    () -> why + " — found " + identifier);
        }

        private CapturingVertx bootWith(@Nullable EgressTlsConfig egressTls,
                EgressTrustProfileResolver resolver) {
            CapturingVertx capturing = new CapturingVertx(vertx);
            RouteTable table = new RouteTable(List.of(
                    route("h", Protocol.HTTP, Require.NONE),
                    route("g", Protocol.GRPC, Require.NONE)));
            new GatewayEdgeRoute(table, GatewayConfig.builder().version(1).egressTls(egressTls).build(),
                    new SingletonInstance<>(tokenValidator), capturing, virtualThreadExecutor, hardening,
                    new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(), resolver,
                    PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
            return capturing;
        }
    }

    /**
     * The application portal's own reserved path, driven through a live edge. The portal seam runs
     * after the OIDC reserved paths and before route selection, so the two orderings pinned here are:
     * a configured {@code portal.path} is answered before a prefix route that covers it (the route's
     * upstream is never dialled), and an OIDC reserved path on the OIDC host still wins over a portal
     * path that coincides with it. Each ordering is paired with a control that proves the other side
     * of the seam is live, so neither assertion can pass because a handler was simply absent.
     */
    @Nested
    @DisplayName("application portal answered ahead of the route table")
    class PortalDispatch {

        private static final String PORTAL_PATH = "/apps/portal";
        private static final String OIDC_HOST = "gw.example.com";
        private static final String ORIGIN = "https://gw.example.com";
        private static final String USER_INFO_PATH = "/auth/userinfo";

        /** What the client observed: status, the two headers the portal owns, and the body. */
        private record Answer(int status, @Nullable String contentType, @Nullable String allow, String body) {
        }

        @Test
        @DisplayName("a configured portal path is answered before a prefix route that covers it")
        void portalWinsOverCoveringPrefixRoute() throws Exception {
            // Arrange — a live upstream behind path_prefix /apps, which covers the portal path
            AtomicBoolean upstreamReached = new AtomicBoolean();
            HttpServer upstream = startUpstream(upstreamReached);
            try {
                GatewayEdgeRoute edge = newEdge(new RouteTable(List.of(appsRoute(upstream.actualPort()))),
                        gatewayConfig, BffRuntime.inert(), portal(PORTAL_PATH));

                // Act
                Answer answer = serve(edge, io.vertx.core.http.HttpMethod.GET, LoopbackHost.ADDRESS, PORTAL_PATH);

                // Assert
                assertAll(
                        () -> assertEquals(200, answer.status()),
                        () -> assertEquals("text/html; charset=utf-8", answer.contentType()),
                        () -> assertTrue(answer.body().startsWith("<!DOCTYPE html>"), answer.body()),
                        () -> assertFalse(upstreamReached.get(),
                                "the prefix route covering the portal path never sees the request"));
            } finally {
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
            }
        }

        @Test
        @DisplayName("control: a sibling path and a trailing-slash variant under the same prefix are routed")
        void siblingAndTrailingSlashPathsAreRouted() throws Exception {
            // Arrange — the same edge; only the request path differs from the positive case
            AtomicBoolean upstreamReached = new AtomicBoolean();
            HttpServer upstream = startUpstream(upstreamReached);
            try {
                GatewayEdgeRoute edge = newEdge(new RouteTable(List.of(appsRoute(upstream.actualPort()))),
                        gatewayConfig, BffRuntime.inert(), portal(PORTAL_PATH));

                // Act
                Answer sibling = serve(edge, io.vertx.core.http.HttpMethod.GET, LoopbackHost.ADDRESS, "/apps/other");
                boolean siblingReached = upstreamReached.getAndSet(false);
                Answer trailing = serve(edge, io.vertx.core.http.HttpMethod.GET, LoopbackHost.ADDRESS,
                        PORTAL_PATH + "/");

                // Assert — the stub upstream answers 204, which the portal never does
                assertAll(
                        () -> assertEquals(204, sibling.status()),
                        () -> assertTrue(siblingReached, "a sibling path is proxied to the route's upstream"),
                        () -> assertEquals(204, trailing.status()),
                        () -> assertTrue(upstreamReached.get(),
                                "a trailing-slash variant is not the portal path and is proxied"));
            } finally {
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
            }
        }

        @Test
        @DisplayName("an unsupported method on the portal path is answered 405 by the portal, not the route")
        void portalAnswersMethodNotAllowed() throws Exception {
            // Arrange — the covering route allows GET only, so its own verb gate would also say 405;
            // the portal's Allow header is what attributes the answer to the portal seam
            AtomicBoolean upstreamReached = new AtomicBoolean();
            HttpServer upstream = startUpstream(upstreamReached);
            try {
                GatewayEdgeRoute edge = newEdge(new RouteTable(List.of(appsRoute(upstream.actualPort()))),
                        gatewayConfig, BffRuntime.inert(), portal(PORTAL_PATH));

                // Act
                Answer post = serve(edge, io.vertx.core.http.HttpMethod.POST, LoopbackHost.ADDRESS, PORTAL_PATH);
                Answer head = serve(edge, io.vertx.core.http.HttpMethod.HEAD, LoopbackHost.ADDRESS, PORTAL_PATH);

                // Assert
                assertAll(
                        () -> assertEquals(405, post.status()),
                        () -> assertEquals("GET, HEAD", post.allow()),
                        () -> assertEquals(200, head.status()),
                        () -> assertEquals("", head.body()),
                        () -> assertFalse(upstreamReached.get()));
            } finally {
                Awaits.teardown(upstream.close(), "the stub upstream server to close");
            }
        }

        @Test
        @DisplayName("an OIDC reserved path on the OIDC host still wins over a coinciding portal path")
        void oidcReservedPathWinsOverPortal() throws Exception {
            // Arrange — the portal path deliberately coincides with the user-info reserved path (boot
            // validation refuses this; the edge ordering is what is pinned here)
            GatewayConfig withOidc = GatewayConfig.builder().version(1).oidc(oidc()).build();
            GatewayEdgeRoute edge = newEdge(new RouteTable(List.of()), withOidc,
                    GatewayEdgeRouteBffWiringTest.activeRuntime(
                            GatewayEdgeRouteBffWiringTest.serverBinding(GatewayEdgeRouteBffWiringTest.newStore())),
                    portal(USER_INFO_PATH));

            // Act
            Answer onOidcHost = serve(edge, io.vertx.core.http.HttpMethod.GET, OIDC_HOST, USER_INFO_PATH);
            Answer onOtherHost = serve(edge, io.vertx.core.http.HttpMethod.GET, LoopbackHost.ADDRESS,
                    USER_INFO_PATH);

            // Assert — 401 is the user-info handler's own no-session answer; the control proves the
            // portal is live for the same path on a host the OIDC registry does not claim
            assertAll(
                    () -> assertEquals(401, onOidcHost.status(), "the OIDC reserved path is dispatched first"),
                    () -> assertFalse(onOidcHost.body().contains("<!DOCTYPE html>"), onOidcHost.body()),
                    () -> assertEquals(200, onOtherHost.status(), "the portal answers its path on any host"),
                    () -> assertTrue(onOtherHost.body().startsWith("<!DOCTYPE html>"), onOtherHost.body()));
        }

        private PortalEndpoint portal(String path) {
            return PortalEndpoint.of(PortalConfig.builder().path(path).title("Portal").build(), PortalCatalog.empty(),
                    PortalRenderer.builtIn(), (cookie, now) -> SessionIdentity.anonymous(), null, false, "/");
        }

        private OidcConfig oidc() {
            return OidcConfig.builder()
                    .redirectUri(ORIGIN + "/auth/callback")
                    .logout(OidcConfig.Logout.builder()
                            .path("/auth/logout")
                            .postLogoutRedirectUri(ORIGIN + "/auth/logout/return")
                            .backchannelPath("/auth/backchannel")
                            .build())
                    .userInfo(OidcConfig.UserInfo.builder().path(USER_INFO_PATH).build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .build();
        }

        private HttpServer startUpstream(AtomicBoolean reached) throws Exception {
            return Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                reached.set(true);
                request.response().setStatusCode(204).end();
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        }

        private ResolvedRoute appsRoute(int upstreamPort) {
            return ResolvedRoute.builder()
                    .id("apps")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/apps").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                    .build();
        }

        private Answer serve(GatewayEdgeRoute edge, io.vertx.core.http.HttpMethod method, String host, String uri)
                throws Exception {
            Router router = Router.router(vertx);
            edge.registerRoutes(router);
            HttpServer front = Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
            HttpClient client = vertx.createHttpClient();
            try {
                RequestOptions options = new RequestOptions()
                        .setServer(SocketAddress.inetSocketAddress(front.actualPort(), LoopbackHost.ADDRESS))
                        .setHost(host).setPort(front.actualPort())
                        .setMethod(method).setURI(uri);
                return Awaits.connect(client.request(options)
                                .compose(request -> request.putHeader("Accept", "text/html").send())
                                .compose(response -> response.body().map(body -> new Answer(response.statusCode(),
                                        response.getHeader("Content-Type"), response.getHeader("Allow"),
                                        body == null ? "" : body.toString()))),
                        "the edge response to " + method + " " + uri);
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }
    }

    /**
     * The three routing rejections over a live edge: an address no route serves, a method outside the
     * selected route's allowlist, and a terminated request whose {@code Host} names a reserved
     * passthrough hostname. Each answers under the {@code routing} problem type and moves the edge's
     * own {@code sheriff_errors_total} series, whose {@code event} label is the only thing that tells
     * the two {@code 404}s on an unrouted address apart — on the wire they are identical there.
     * <p>
     * The problem type, title and label values are exact literals because they are the contract a
     * client and a dashboard read.
     */
    @Nested
    @DisplayName("routing rejections over the live edge (routing problem type and event metric label)")
    class RoutingRejectionContract {

        private static final String ERRORS_TOTAL = "sheriff_errors_total";
        private static final String ROUTING_TYPE_MEMBER = "\"type\":\"urn:api-sheriff:problem:routing\"";
        private static final String ROUTING_TITLE_MEMBER = "\"title\":\"Routing\"";
        private static final String PASSTHROUGH_SNI = "backend.internal.example";
        private static final String ROUTE_ID = "api";
        private static final String UNROUTED_PATH = "/nothing";

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

        /** What the client observed: the status, every response header, and the body. */
        private record Rejection(int status, List<String> headers, String body) {
        }

        @Test
        @DisplayName("an unrouted path answers 404 under the routing problem type and counts NO_ROUTE_MATCHED")
        void unroutedPathAnswersRoutingProblem() throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection rejection = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        LoopbackHost.ADDRESS, UNROUTED_PATH, Map.of());

                assertRoutingProblem(404, rejection);
                awaitSingleRoutingSeries(SheriffMetrics.NO_ROUTE, "NO_ROUTE_MATCHED");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("a disallowed method answers 405 under the routing problem type and counts METHOD_NOT_ALLOWED")
        void disallowedMethodAnswersRoutingProblem() throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection rejection = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.DELETE,
                        LoopbackHost.ADDRESS, "/" + ROUTE_ID + "/resource", Map.of());

                assertRoutingProblem(405, rejection);
                awaitSingleRoutingSeries(ROUTE_ID, "METHOD_NOT_ALLOWED");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("a smuggled passthrough Host answers 404 under the routing problem type and counts PASSTHROUGH_HOST_SMUGGLED")
        void smuggledHostAnswersRoutingProblem() throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection rejection = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        PASSTHROUGH_SNI, UNROUTED_PATH, Map.of());

                assertRoutingProblem(404, rejection);
                awaitSingleRoutingSeries(SheriffMetrics.NO_ROUTE, "PASSTHROUGH_HOST_SMUGGLED");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("the unrouted 404 and the smuggled-host 404 are identical on the wire yet move two event series")
        void unroutedAndSmuggledAreIdenticalOnTheWireButCountedApart() throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection unrouted = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        LoopbackHost.ADDRESS, UNROUTED_PATH, Map.of());
                Rejection smuggled = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        PASSTHROUGH_SNI, UNROUTED_PATH, Map.of());

                assertAll("the two 404s cannot be told apart by a client",
                        () -> assertEquals(404, unrouted.status()),
                        () -> assertEquals(unrouted.status(), smuggled.status()),
                        () -> assertEquals(unrouted.headers(), smuggled.headers()),
                        () -> assertEquals(unrouted.body(), smuggled.body()));
                Awaits.until(() -> routingCount(SheriffMetrics.NO_ROUTE, "NO_ROUTE_MATCHED") == 1.0
                                && routingCount(SheriffMetrics.NO_ROUTE, "PASSTHROUGH_HOST_SMUGGLED") == 1.0,
                        "each 404 to move its own sheriff_errors_total event series exactly once",
                        Awaits.CONNECT_CEILING_SECONDS);
                assertEquals(2, registry.find(ERRORS_TOTAL).counters().size(),
                        "two rejections that differ only in cause are two series, never one");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @ParameterizedTest(name = "Accept: {0}")
        @CsvSource({"application/problem+json, application/problem+json",
                "'text/html,application/xhtml+xml', text/html"})
        @DisplayName("on an unrouted address a reserved passthrough Host answers a 404 identical to the unrouted one")
        void reservedHostOnAnUnroutedAddressAnswersIdentically(String accept, String expectedContentType)
                throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI, errorPagePortal());
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection unrouted = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        LoopbackHost.ADDRESS, UNROUTED_PATH, Map.of("Accept", accept));
                Rejection reserved = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        PASSTHROUGH_SNI, UNROUTED_PATH, Map.of("Accept", accept));

                assertAll("the two 404s on an unrouted address for Accept: " + accept,
                        () -> assertEquals(404, unrouted.status()),
                        () -> assertTrue(unrouted.headers().stream()
                                        .anyMatch(header -> header.startsWith("content-type: " + expectedContentType)),
                                () -> "the unrouted answer must be negotiated to " + expectedContentType + ": "
                                        + unrouted.headers()),
                        () -> assertEquals(unrouted.status(), reserved.status()),
                        () -> assertEquals(unrouted.headers(), reserved.headers()),
                        () -> assertEquals(unrouted.body(), reserved.body()));
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        @Test
        @DisplayName("on an address a route serves a reserved passthrough Host answers 404")
        void reservedHostOnAServedAddressAnswersNotFound() throws Exception {
            HttpServer front = startFront(PASSTHROUGH_SNI);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection rejection = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        PASSTHROUGH_SNI, "/" + ROUTE_ID + "/resource", Map.of());

                assertRoutingProblem(404, rejection);
                awaitSingleRoutingSeries(SheriffMetrics.NO_ROUTE, "PASSTHROUGH_HOST_SMUGGLED");
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        /** A portal answering the gateway's HTML error pages, configured as the error-page tests do. */
        private PortalEndpoint errorPagePortal() {
            return PortalEndpoint.of(PortalConfig.builder().path("/portal").title("Portal").errorPages(true).build(),
                    PortalCatalog.empty(), PortalRenderer.builtIn(), (cookie, now) -> SessionIdentity.anonymous(),
                    null, false, "/");
        }

        @Test
        @DisplayName("no sheriff_errors_total tag value carries request-derived input from a smuggled-host request")
        void errorTagsCarryNoRequestDerivedInput() throws Exception {
            String hostMarker = "mkhost7f3a";
            String pathMarker = "mkpath91c2";
            String headerMarker = "mkheader55d0";
            String markedSni = hostMarker + ".internal.example";
            HttpServer front = startFront(markedSni);
            HttpClient client = vertx.createHttpClient();
            try {
                Rejection rejection = send(client, front.actualPort(), io.vertx.core.http.HttpMethod.GET,
                        markedSni, "/" + pathMarker, Map.of("X-Probe", headerMarker));

                assertEquals(404, rejection.status());
                awaitSingleRoutingSeries(SheriffMetrics.NO_ROUTE, "PASSTHROUGH_HOST_SMUGGLED");
                for (var counter : registry.find(ERRORS_TOTAL).counters()) {
                    for (var tag : counter.getId().getTags()) {
                        for (String marker : List.of(hostMarker, pathMarker, headerMarker)) {
                            assertFalse(tag.getValue().contains(marker),
                                    () -> "sheriff_errors_total tag '%s' carried request-derived input '%s': %s"
                                            .formatted(tag.getKey(), marker, tag.getValue()));
                        }
                    }
                }
            } finally {
                Awaits.teardown(client.close(), "the HTTP client to close");
                Awaits.teardown(front.close(), "the edge front server to close");
            }
        }

        private void assertRoutingProblem(int expectedStatus, Rejection rejection) {
            assertAll("routing problem",
                    () -> assertEquals(expectedStatus, rejection.status()),
                    () -> assertTrue(rejection.body().contains(ROUTING_TYPE_MEMBER), rejection.body()),
                    () -> assertTrue(rejection.body().contains(ROUTING_TITLE_MEMBER), rejection.body()));
        }

        /** Waits for the edge's own recorder to have moved exactly the named routing series, once. */
        private void awaitSingleRoutingSeries(String route, String event) throws TimeoutException {
            Awaits.until(() -> routingCount(route, event) == 1.0,
                    "the edge to count " + event + " on route " + route + " under category=routing",
                    Awaits.CONNECT_CEILING_SECONDS);
            assertEquals(1, registry.find(ERRORS_TOTAL).counters().size(),
                    "one rejection moves one sheriff_errors_total series");
        }

        private double routingCount(String route, String event) {
            var counter = registry.find(ERRORS_TOTAL)
                    .tags("route", route, "category", "routing", "event", event).counter();
            return counter == null ? 0.0 : counter.count();
        }

        /** Boots an edge over one GET-only route, reserving {@code passthroughSni}, recording into {@link #registry}. */
        private HttpServer startFront(String passthroughSni) throws Exception {
            return startFront(passthroughSni, PortalEndpoint.inert());
        }

        /** As {@link #startFront(String)}, with {@code portal} answering the gateway's error pages. */
        private HttpServer startFront(String passthroughSni, PortalEndpoint portal) throws Exception {
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(TlsConfig.builder().passthroughSni(Map.of(passthroughSni, "backend")).build())
                    .build();
            Router router = Router.router(vertx);
            new GatewayEdgeRoute(new RouteTable(List.of(route(ROUTE_ID, Protocol.HTTP, Require.NONE))), config,
                    new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, hardening,
                    new SheriffMetrics(registry), BffRuntime.inert(), unconsultedTrustProfileResolver(),
                    portal, GatewayEdgeRouteBffWiringTest.gatewayJson()).registerRoutes(router);
            return Awaits.connect(
                    vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the edge front server to start listening");
        }

        private Rejection send(HttpClient client, int port, io.vertx.core.http.HttpMethod method, String host,
                String uri, Map<String, String> headers) throws Exception {
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(port, LoopbackHost.ADDRESS))
                    .setHost(host).setPort(port)
                    .setMethod(method).setURI(uri);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                options.putHeader(header.getKey(), header.getValue());
            }
            return Awaits.connect(client.request(options)
                            .compose(HttpClientRequest::send)
                            .compose(response -> response.body().map(body -> new Rejection(response.statusCode(),
                                    response.headers().entries().stream()
                                            .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + ": "
                                                    + entry.getValue())
                                            .sorted().toList(),
                                    body == null ? "" : body.toString()))),
                    "the edge response to " + method + " " + uri);
        }
    }

    private WebSocket connectWs(WebSocketClient client, int port) throws Exception {
        return Awaits.connect(client.connect(new WebSocketConnectOptions()
                        .setHost(LoopbackHost.ADDRESS).setPort(port).setURI("/w/room")),
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
                client.request(io.vertx.core.http.HttpMethod.GET, port, LoopbackHost.ADDRESS, "/unmatched")
                        .compose(HttpClientRequest::send),
                "the edge response to GET /unmatched").statusCode();
    }

    private static void awaitReleased(AtomicBoolean guard, String message) throws TimeoutException {
        Awaits.until(guard::get, message, Awaits.TEARDOWN_CEILING_SECONDS);
    }

    private static ResolvedRoute webSocketRoute(int upstreamPort) {
        return ResolvedRoute.builder()
                .id("w")
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix("/w").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                .build();
    }

    private GatewayEdgeRoute newEdge(RouteTable table) {
        return newEdge(table, gatewayConfig, BffRuntime.inert(), PortalEndpoint.inert());
    }

    private GatewayEdgeRoute newEdge(RouteTable table, GatewayConfig config, BffRuntime runtime,
            PortalEndpoint portal) {
        return new GatewayEdgeRoute(table, config, new SingletonInstance<>(tokenValidator), vertx,
                virtualThreadExecutor, hardening, new SheriffMetrics(new SimpleMeterRegistry()), runtime,
                unconsultedTrustProfileResolver(), portal, GatewayEdgeRouteBffWiringTest.gatewayJson());
    }

    private static EgressTrustProfileResolver unconsultedTrustProfileResolver() {
        return EgressTrustProfiles.unconsulted();
    }

    /**
     * How many clients of each kind an edge built at boot. An assembled edge exposes no view of its
     * compiled routes, so the clients it asked Vert.x for are what a boot leaves observable: an HTTP
     * or WebSocket route's upstream tuple builds a default client, a gRPC route's a forced-HTTP/2
     * one, and the WebSocket client is built once for the whole edge.
     *
     * @param defaultHttpClients  the HTTP/1.1-with-h2-upgrade upstream clients
     * @param forcedHttp2Clients  the forced-HTTP/2 upstream clients
     * @param webSocketClients    the WebSocket clients
     */
    private record ClientWiring(int defaultHttpClients, int forcedHttp2Clients, int webSocketClients) {
    }

    /** Boots a real edge over {@code table} and reports the clients it built doing so. */
    private ClientWiring clientWiringOf(RouteTable table) {
        CapturingVertx capturing = new CapturingVertx(vertx);
        new GatewayEdgeRoute(table, gatewayConfig, new SingletonInstance<>(tokenValidator), capturing,
                virtualThreadExecutor, hardening, new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(),
                unconsultedTrustProfileResolver(), PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
        return capturing.wiring();
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

    /**
     * A {@link Vertx} that records the options object handed to each client factory and otherwise
     * delegates everything to the real instance, so the edge boots for real while the arguments it
     * passed stay readable. Delegation rather than a hand-written stub: {@code Vertx} is a wide
     * interface, only two of its methods are of interest, and every other call must reach the real
     * runtime — {@code @Delegate} expresses exactly that and cannot drift as the interface grows.
     */
    private static final class CapturingVertx implements Vertx {

        @Delegate(excludes = CapturedFactories.class)
        private final Vertx delegate;

        private final List<HttpClientOptions> httpClientOptions = new ArrayList<>();
        private final List<WebSocketClientOptions> webSocketClientOptions = new ArrayList<>();

        private CapturingVertx(Vertx delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpClient createHttpClient(HttpClientOptions options) {
            httpClientOptions.add(options);
            return delegate.createHttpClient(options);
        }

        @Override
        public WebSocketClient createWebSocketClient(WebSocketClientOptions options) {
            webSocketClientOptions.add(options);
            return delegate.createWebSocketClient(options);
        }

        /** @return how many clients of each kind were requested from this instance */
        ClientWiring wiring() {
            int forcedHttp2 = (int) httpClientOptions.stream()
                    .filter(options -> options.getProtocolVersion() == HttpVersion.HTTP_2)
                    .count();
            return new ClientWiring(httpClientOptions.size() - forcedHttp2, forcedHttp2,
                    webSocketClientOptions.size());
        }

        /** @return the options of the default (HTTP/1.1 with h2 upgrade) client */
        HttpClientOptions plainHttpOptions() {
            return httpOptionsWhere(false);
        }

        /** @return the options of the gRPC route's forced-HTTP/2 client */
        HttpClientOptions forcedHttp2Options() {
            return httpOptionsWhere(true);
        }

        /** @return the options of the single edge-wide WebSocket client */
        WebSocketClientOptions webSocketOptions() {
            assertEquals(1, webSocketClientOptions.size(),
                    "the edge builds exactly one WebSocket client, for the whole edge");
            return webSocketClientOptions.getFirst();
        }

        /**
         * Selects the captured HTTP options by branch rather than by call order, so the assertions
         * name the client they mean instead of depending on the order the assembler happens to walk
         * the route table in.
         */
        private HttpClientOptions httpOptionsWhere(boolean forcedHttp2) {
            List<HttpClientOptions> matching = httpClientOptions.stream()
                    .filter(options -> (options.getProtocolVersion() == HttpVersion.HTTP_2) == forcedHttp2)
                    .toList();
            assertEquals(1, matching.size(),
                    () -> "expected exactly one " + (forcedHttp2 ? "forced-HTTP/2" : "default")
                            + " client, captured " + httpClientOptions.size() + " client(s) in total");
            return matching.getFirst();
        }

        /** The two factory methods {@link CapturingVertx} implements itself rather than delegating. */
        private interface CapturedFactories {

            HttpClient createHttpClient(HttpClientOptions options);

            WebSocketClient createWebSocketClient(WebSocketClientOptions options);
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
