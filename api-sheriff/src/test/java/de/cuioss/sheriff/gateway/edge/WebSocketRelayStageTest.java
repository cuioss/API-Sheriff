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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.edge.WebSocketRelayStage.RelayObserver;
import de.cuioss.sheriff.gateway.edge.WebSocketRelayStage.RelayObserver.Direction;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.testsupport.UnreachablePort;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;
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
 * End-to-end contract of the WebSocket protocol-dispatch seam, driven over a live Vert.x front server
 * that hosts the {@link GatewayEdgeRoute} against a local stub upstream WebSocket echo server — no
 * Docker, no Quarkus. A real upgrade crosses the whole fixed pipeline, the {@code OriginValidationStage},
 * and the {@link WebSocketRelayStage}: Origin enforcement and bearer auth reject the handshake before
 * the upstream is ever dialed, an accepted upgrade relays frames opaquely in both directions, an
 * unreachable upstream maps to {@code 502} before the {@code 101}, and an idle relay is reclaimed while
 * a heartbeated one survives. The Docker-backed matrix in {@code integration-tests} complements these
 * server-local guarantees.
 * <p>
 * Every await on a frame the relay forwards — through the full edge or through a relay-only server —
 * goes through {@link #awaitRelayed}, so a timeout carries the relay's wiring-versus-frame timeline: the
 * client's first write, the moment the relay installed its handlers, and its first relayed frame per
 * direction. A stall shape that has more than one cause is then told apart in the message itself. Awaits
 * on something other than a relayed frame — the handshake rejections, the idle reclaim's close — keep
 * their plain {@link Awaits} calls.
 */
@EnableGeneratorController
@DisplayName("WebSocketRelayStage — end-to-end WebSocket dispatch over a live Vert.x server")
class WebSocketRelayStageTest {

    private static final String ALLOWED_ORIGIN = "https://app.example";
    private static final String FOREIGN_ORIGIN = "https://evil.example";

    /**
     * Absolute paths probed for {@code lsof}, in order. Absolute rather than {@code PATH}-resolved so
     * the diagnostic cannot be redirected by the environment it is diagnosing.
     */
    private static final List<String> LSOF_BINARIES = List.of("/usr/sbin/lsof", "/usr/bin/lsof");

    /** Bound on the diagnostic subprocess: a report that has not arrived by now is not worth waiting for. */
    private static final long LSOF_TIMEOUT_SECONDS = 2;

    /**
     * Sequential upgrades the full-edge early-first-frame regression drives through
     * {@link GatewayEdgeRoute}, each writing its first frame inside the client's own upgrade callback.
     */
    private static final int EARLY_FRAME_EDGE_UPGRADES = 20;

    /** The one-line verdict a relay timeline gives when the client wrote before the relay was wired. */
    private static final String FRAME_WRITTEN_BEFORE_HANDLER = "frame written before handler installed";

    /**
     * The release callback for relay-only servers whose tests observe frames or threads rather than the
     * admission permit; it records nothing.
     */
    private static final Runnable UNOBSERVED_ADMISSION_RELEASE = () -> {
        // the admission lifecycle is pinned by AdmissionReleaseCallback, not here
    };

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private WebSocketClient wsClient;
    private WebSocketClient relayUpstreamClient;
    private int frontPort;
    private int upstreamPort;
    private int deadPort;
    private final AtomicInteger upstreamConnects = new AtomicInteger();
    private final AtomicReference<String> upstreamCustomHeader = new AtomicReference<>();

    /**
     * The timeline of the relay under await. The full edge and every relay-only server report into it,
     * and {@link #awaitRelayed} folds it into the message of a relay await that times out.
     */
    private final RelayTimeline relayTimeline = new RelayTimeline();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Stub upstream WebSocket echo server: records each accepted handshake and echoes text frames.
        upstreamServer = Awaits.connect(vertx.createHttpServer().webSocketHandler(ws -> {
            upstreamConnects.incrementAndGet();
            upstreamCustomHeader.set(ws.headers().get("X-Custom"));
            ws.textMessageHandler(ws::writeTextMessage);
        }).listen(0, LoopbackHost.ADDRESS), "the stub upstream WebSocket server to start listening");
        upstreamPort = upstreamServer.actualPort();
        relayUpstreamClient = vertx.createWebSocketClient();

        // A port refusing connections for the unreachable-upstream case, below the ephemeral range so
        // neither the front server bound below nor a relay-only server can ever be handed it.
        deadPort = UnreachablePort.pick();

        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

        RouteTable routeTable = new RouteTable(List.of(
                wsRoute("wsopen", "/ws-open", Require.NONE, upstreamPort, Set.of(), null, positiveListNamingNothing()),
                wsRoute("wsforwardall", "/ws-forward-all", Require.NONE, upstreamPort, Set.of(), null, null),
                wsRoute("wsorigin", "/ws-origin", Require.NONE, upstreamPort, Set.of(ALLOWED_ORIGIN), null,
                        positiveListNamingNothing()),
                wsRoute("wssecure", "/ws-secure", Require.BEARER, upstreamPort, Set.of(ALLOWED_ORIGIN), null,
                        positiveListNamingNothing()),
                wsRoute("wsidle", "/ws-idle", Require.NONE, upstreamPort, Set.of(), 1, positiveListNamingNothing()),
                wsRoute("wsdead", "/ws-dead", Require.NONE, deadPort, Set.of(), null, positiveListNamingNothing())));

        GatewayConfig gatewayConfig = GatewayConfig.builder()
                .version(1)
                .securityHeaders(securityHeaders())
                .build();
        // Built through the test-only constructor so every relay this edge establishes reports its
        // wiring-versus-frame timeline into the recorder the relay awaits read on timeout.
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(),
                EgressTrustProfiles.unconsulted(), PortalEndpoint.inert(), relayTimeline.observer(RelayObserver.NO_OP));

        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        frontServer = Awaits.connect(
                vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
        frontPort = frontServer.actualPort();

        wsClient = vertx.createWebSocketClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(wsClient.close(), "the WebSocket client to close");
        Awaits.teardown(relayUpstreamClient.close(), "the relay upstream client to close");
        Awaits.teardown(frontServer.close(), "the edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("relays text frames opaquely in both directions once the upgrade is accepted")
    void relaysBidirectionalTextFrames() throws Exception {
        // Arrange
        WebSocket socket = connect("/ws-open/room", ALLOWED_ORIGIN);
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        writeRelayed(socket, "hello-relay");

        // Assert — the frame crosses to the upstream, is echoed, and relays back to the client
        assertEquals("hello-relay", awaitRelayed(echoed, "the echoed frame to return through the relay"));
    }

    /**
     * The full-edge regression for the relay's pre-wiring frame window. The edge runs its pipeline on the
     * fixture's plain virtual-thread executor, which binds no Vert.x context to the thread that calls
     * {@link WebSocketRelayStage#relay} — the shape that ran the race before the relay hopped onto the
     * client connection's captured context and paused both legs until wired. Every upgrade writes its
     * first frame inside the client's own upgrade callback, with no gap after the {@code 101}.
     */
    @Test
    @DisplayName("relays a first frame written inside the client's upgrade callback through the full edge, on every upgrade")
    void relaysEarlyFirstFrameThroughTheEdgeOnEveryUpgrade() throws Exception {
        for (int upgrade = 1; upgrade <= EARLY_FRAME_EDGE_UPGRADES; upgrade++) {
            // Arrange
            String frame = Generators.letterStrings(1, 32).next();
            CompletableFuture<String> echoed = new CompletableFuture<>();
            CompletableFuture<WebSocket> opened = new CompletableFuture<>();
            WebSocketConnectOptions options = new WebSocketConnectOptions()
                    .setHost(LoopbackHost.ADDRESS).setPort(frontPort).setURI("/ws-open/room")
                    .addHeader("Origin", ALLOWED_ORIGIN);

            relayTimeline.startUpgrade();

            // Act — the first frame leaves the client inside its upgrade callback
            wsClient.connect(options)
                    .onSuccess(socket -> {
                        socket.textMessageHandler(echoed::complete);
                        writeRelayed(socket, frame);
                        opened.complete(socket);
                    })
                    .onFailure(failure -> {
                        echoed.completeExceptionally(failure);
                        opened.completeExceptionally(failure);
                    });

            // Assert — the frame crosses the edge's relay to the echo upstream and comes back
            assertEquals(frame, awaitRelayed(echoed, "upgrade " + upgrade + " of " + EARLY_FRAME_EDGE_UPGRADES
                    + ": the first frame, written in the client's upgrade callback, to echo through the edge"));
            Awaits.teardown(Awaits.connect(opened, "upgrade " + upgrade + " to report its socket").close(),
                    "upgrade " + upgrade + "'s relayed WebSocket to close");
        }
    }

    @Test
    @DisplayName("rejects a foreign Origin with 403 before dialing the upstream")
    void rejectsForeignOriginBeforeDial() {
        // Act
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-origin/room", FOREIGN_ORIGIN));

        // Assert — the upgrade is refused 403 and the upstream is never contacted
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(403, rejected.getStatus(), () -> rejectionReport(failure));
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
        writeRelayed(socket, "allowed");

        // Assert
        assertEquals("allowed", awaitRelayed(echoed, "the echoed frame to return through the relay"));
    }

    @Test
    @DisplayName("rejects a bearer handshake without a token 401 before dialing the upstream")
    void rejectsMissingBearerTokenBeforeDial() {
        // Act
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-secure/room", ALLOWED_ORIGIN));

        // Assert — authentication (stage 4) rejects the handshake before the WebSocket dispatch runs
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(401, rejected.getStatus(), () -> rejectionReport(failure));
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
        assertEquals(502, rejected.getStatus(), () -> rejectionReport(failure));
    }

    @Test
    @DisplayName("preserves the route's security headers on a WebSocket handshake failure")
    void preservesSecurityHeadersOnHandshakeFailure() {
        // Act — the /ws-dead route's upstream is unreachable, so the handshake fails 502 before the 101
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect("/ws-dead/room", ALLOWED_ORIGIN));

        // Assert — the failed-handshake response still carries the route's resolved security headers
        // (applied at stage 2a), mirroring the HTTP (ResponseStage.relay) and gRPC
        // (GrpcStatusMapper.renderRejection) contract
        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(502, rejected.getStatus(), () -> rejectionReport(failure));
        MultiMap headers = rejected.getHeaders();
        assertEquals("nosniff", headers.get("X-Content-Type-Options"),
                "a failed WebSocket handshake carries the route's X-Content-Type-Options header");
        assertEquals("DENY", headers.get("X-Frame-Options"),
                "a failed WebSocket handshake carries the route's X-Frame-Options header");
    }

    @Test
    @DisplayName("forwards no non-allow-listed handshake header on a positive-list route")
    void deniesNonAllowlistedForwardHeader() throws Exception {
        // Arrange — a custom header the route's declared-empty positive-list does not name
        WebSocket socket = connectWithCustomHeader("/ws-open/room");
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        writeRelayed(socket, "go");
        awaitRelayed(echoed, "the echoed frame to return through the relay");

        // Assert — the upstream handshake never saw the unlisted header
        assertNull(upstreamCustomHeader.get(),
                "a header the route's positive-list does not name is not relayed to the upstream");
    }

    /**
     * The matched control for the assertion above: the same handshake against a route declaring no
     * forward block at all. The forward policy governs the WebSocket handshake headers on both
     * postures, so the discriminator is the route's declared mode — not the relay path ignoring the
     * policy. Without this control the assertion above would stay green against a relay that had
     * stopped consulting the forward policy entirely.
     */
    @Test
    @DisplayName("relays an unlisted handshake header on a forward-all route")
    void relaysUnlistedForwardHeaderOnForwardAllRoute() throws Exception {
        // Arrange — the same custom header, against a route that declares neither list
        WebSocket socket = connectWithCustomHeader("/ws-forward-all/room");
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        writeRelayed(socket, "go");
        awaitRelayed(echoed, "the echoed frame to return through the relay");

        // Assert
        assertEquals("leak", upstreamCustomHeader.get(),
                "a route declaring no forward block is forward-all, so the client header crosses");
    }

    @Test
    @DisplayName("reclaims an idle relay after the per-route idle timeout, closing 1001")
    void reclaimsIdleRelay() throws Exception {
        // Arrange — the /ws-idle route has idle_timeout_seconds=1
        WebSocket socket = connect("/ws-idle/room", ALLOWED_ORIGIN);
        CompletableFuture<Short> closeCode = new CompletableFuture<>();
        socket.closeHandler(v -> closeCode.complete(socket.closeStatusCode()));

        // Act + Assert — with no frame in either direction the relay is reclaimed and closed 1001
        assertEquals((short) 1001, Awaits.connect(closeCode, "the idle relay to be reclaimed and closed"),
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
            writeRelayed(socket, "beat-" + i);
            awaitRelayed(echoed, "the heartbeat frame to be echoed");
            Thread.sleep(400);
        }

        // Assert — activity kept the relay alive; it was never reclaimed
        assertFalse(closed.isDone(), "a heartbeated relay is not reclaimed while activity continues");
    }

    /**
     * The admission permit a WebSocket request acquires at the edge is held for the relay's whole
     * lifetime, because a completed upgrade takes the connection over and the HTTP end handler never
     * fires. These tests drive {@link WebSocketRelayStage#relay} directly — bypassing the edge, so the
     * release callback is a plain counter rather than a private semaphore — and pin that it runs
     * exactly once on each teardown path, and not at all on the path the edge still releases itself.
     */
    @Nested
    @DisplayName("admission-release callback — exactly once per relay teardown")
    class AdmissionReleaseCallback {

        @Test
        @DisplayName("releases exactly once when an established relay closes, staying idempotent as both legs tear down")
        void releasesOnceOnEstablishedRelayTeardown() throws Exception {
            // Arrange — a live relay over the stub upstream, with the release callback counted
            AtomicInteger releases = new AtomicInteger();
            HttpServer relayServer = startRelayOnlyServer(upstreamPort, releases::incrementAndGet);
            try {
                WebSocket socket = connectTo(relayServer.actualPort());
                CompletableFuture<String> echoed = new CompletableFuture<>();
                socket.textMessageHandler(echoed::complete);
                writeRelayed(socket, "live");
                awaitRelayed(echoed, "the echoed frame to return through the relay");
                assertEquals(0, releases.get(), "an established relay keeps holding its admission permit");

                // Act
                Awaits.teardown(socket.close(), "the relayed WebSocket to close");

                // Assert — closeBoth is re-entered from the upstream leg's close handler once the first
                // pass closed it, so the latch is what keeps the release at exactly one
                awaitReleases(releases, "a graceful close releases the admission permit");
                assertNoFurtherRelease(releases, "the closeBoth latch makes a repeated teardown a no-op");
            } finally {
                Awaits.teardown(relayServer.close(), "the relay-only server to close");
            }
        }

        @Test
        @DisplayName("releases once on the client-upgrade-failure branch, which builds no relay session")
        void releasesOnceWhenTheClientUpgradeFails() throws Exception {
            // Arrange — the upstream accepts the upgrade, but the client request is a plain GET, so
            // ctx.request().toWebSocket() fails. That branch constructs no RelaySession and renders no
            // HTTP response, so it must return the permit itself or the request strands one forever.
            AtomicInteger releases = new AtomicInteger();
            HttpServer relayServer = startRelayOnlyServer(upstreamPort, releases::incrementAndGet);
            HttpClient plainClient = vertx.createHttpClient();
            try {
                // Act — deliberately not awaited: the branch under test ends no response, so the
                // send future never completes; the release callback is the observable outcome.
                plainClient.request(io.vertx.core.http.HttpMethod.GET, relayServer.actualPort(),
                        LoopbackHost.ADDRESS, "/relay").compose(HttpClientRequest::send);

                // Assert
                awaitReleases(releases, "the client-upgrade-failure branch releases the admission permit");
                assertNoFurtherRelease(releases, "the upgrade-failure branch releases exactly once");
            } finally {
                Awaits.teardown(plainClient.close(), "the plain HTTP client to close");
                Awaits.teardown(relayServer.close(), "the relay-only server to close");
            }
        }

        @Test
        @DisplayName("never fires on an upstream-dial failure, which the edge's own end handler already covers")
        void doesNotReleaseOnUpstreamDialFailure() throws Exception {
            // Arrange — the route points at a closed port, so the dial fails before any upgrade
            AtomicInteger releases = new AtomicInteger();
            HttpServer relayServer = startRelayOnlyServer(deadPort, releases::incrementAndGet);
            try {
                // Act
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> connectTo(relayServer.actualPort()));

                // Assert — the dial-failure path ends the HTTP response, so the edge's end handler
                // releases the permit through the same CAS guard; firing the callback too would be a
                // double-release attempt rather than a fix.
                assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
                assertEquals(0, releases.get(),
                        "the dial-failure path leaves the release to the edge's end handler");
            } finally {
                Awaits.teardown(relayServer.close(), "the relay-only server to close");
            }
        }

        private WebSocket connectTo(int port) throws Exception {
            relayTimeline.startUpgrade();
            return Awaits.connect(wsClient.connect(relayOnlyOptions(port)),
                    "the WebSocket upgrade against the relay-only server");
        }
    }

    /**
     * A frame that reaches a relay leg the instant that leg is upgraded must survive until the relay has
     * installed its handlers. Both cases run over a relay-only server whose stage defers every relay's
     * wiring by {@link #DEFERRED_WIRING_MILLIS} — a fixed stand-in for any scheduling gap between a
     * leg's upgrade and the installation of its frame handler — and whose router handler hands
     * {@link WebSocketRelayStage#relay} to the fixture's virtual-thread executor, mirroring the edge's
     * own hop off the event loop.
     * <p>
     * The first frame on each leg is written with no gap after that leg's {@code 101}: the client writes
     * inside its own upgrade-completion callback, and the upstream greets from inside its own accept
     * callback. A frame the relay drops is never delivered, so a loss surfaces as the connect-tier await
     * timing out rather than as a wrong value.
     */
    @Nested
    @DisplayName("early frame — a frame sent the instant a leg is upgraded survives until the relay is wired")
    class EarlyFrame {

        /** How long the stage under test defers each relay's wiring once both legs are upgraded. */
        private static final long DEFERRED_WIRING_MILLIS = 500;

        @Test
        @DisplayName("relays a client frame written inside the client's own upgrade callback")
        void relaysClientFrameWrittenOnUpgrade() throws Exception {
            // Arrange — the relay dials the setUp echo upstream
            String frame = Generators.letterStrings(1, 32).next();
            HttpServer relayServer = startDeferredWiringRelayServer(upstreamPort);
            try {
                CompletableFuture<String> echoed = new CompletableFuture<>();
                relayTimeline.startUpgrade();

                // Act — the first frame leaves the client inside its upgrade callback, with no gap
                wsClient.connect(relayOnlyOptions(relayServer.actualPort()))
                        .onSuccess(socket -> {
                            socket.textMessageHandler(echoed::complete);
                            writeRelayed(socket, frame);
                        })
                        .onFailure(echoed::completeExceptionally);

                // Assert — the frame crosses the relay to the echo upstream and comes back
                assertEquals(frame, awaitRelayed(echoed,
                        "the client's first frame, written in its upgrade callback, to echo through the relay"));
            } finally {
                Awaits.teardown(relayServer.close(), "the deferred-wiring relay server to close");
            }
        }

        @Test
        @DisplayName("relays an upstream greeting written the instant the upstream accepts the upgrade")
        void relaysUpstreamGreetingWrittenOnAccept() throws Exception {
            // Arrange — an upstream that speaks first: it greets from inside its own accept callback
            String greeting = Generators.letterStrings(1, 32).next();
            HttpServer greetingServer = Awaits.connect(vertx.createHttpServer()
                            .requestHandler(request -> request.toWebSocket()
                                    .onSuccess(upstreamSide -> upstreamSide.writeTextMessage(greeting)))
                            .listen(0, LoopbackHost.ADDRESS),
                    "the greeting stub upstream to start listening");
            try {
                HttpServer relayServer = startDeferredWiringRelayServer(greetingServer.actualPort());
                try {
                    CompletableFuture<String> greeted = new CompletableFuture<>();
                    relayTimeline.startUpgrade();

                    // Act — the client only listens; the upstream's greeting is the first frame on the relay
                    wsClient.connect(relayOnlyOptions(relayServer.actualPort()))
                            .onSuccess(socket -> socket.textMessageHandler(greeted::complete))
                            .onFailure(greeted::completeExceptionally);

                    // Assert — the greeting crosses the relay to the client
                    assertEquals(greeting, awaitRelayed(greeted,
                            "the upstream's greeting, written on accept, to reach the client"));
                } finally {
                    Awaits.teardown(relayServer.close(), "the deferred-wiring relay server to close");
                }
            } finally {
                Awaits.teardown(greetingServer.close(), "the greeting stub upstream to close");
            }
        }

        /**
         * A {@link WebSocketRelayStageTest#startHoppingRelayServer hopping relay server} whose stage
         * defers every relay's wiring by {@link #DEFERRED_WIRING_MILLIS} on the relay's own Vert.x context.
         */
        private HttpServer startDeferredWiringRelayServer(int upstreamTargetPort) throws Exception {
            return startHoppingRelayServer(upstreamTargetPort, relayUpstreamClient,
                    wiring -> vertx.setTimer(DEFERRED_WIRING_MILLIS, timerId -> wiring.run()), thread -> {
                        // this fixture observes frames, not threads
                    });
        }

    }

    /**
     * A received pong is control traffic, not data. Vert.x hands a pong that reaches a relay leg to
     * that leg's frame handler as well as to its pong handler, so the relay has to forward it as a pong
     * itself. Were it to fall through to the data path, the other leg would receive it as a binary
     * message, and it would take the direction's single first-data-frame report away from the first
     * real data frame.
     * <p>
     * The stub upstream here sends an unsolicited pong the instant it accepts the upgrade — so it is the
     * first frame on the upstream leg — and a text greeting only when the test signals it. The relay
     * reports a data frame before writing it, so a pong it had reported would be on record before the
     * client could receive it.
     */
    @Nested
    @DisplayName("pong relay — a received pong is forwarded as a pong, never as data")
    class PongRelay {

        @Test
        @DisplayName("forwards an upstream pong as a pong that takes no first-data-frame report")
        void forwardsUpstreamPongAsPongWithoutDataFrameReport() throws Exception {
            // Arrange — an upstream that sends a pong on accept and greets only when signalled
            String pongPayload = Generators.letterStrings(1, 32).next();
            String greeting = Generators.letterStrings(1, 32).next();
            CompletableFuture<ServerWebSocket> upstreamSide = new CompletableFuture<>();
            HttpServer pongServer = Awaits.connect(vertx.createHttpServer()
                            .requestHandler(request -> request.toWebSocket().onSuccess(accepted -> {
                                accepted.writeFrame(WebSocketFrame.pongFrame(Buffer.buffer(pongPayload)));
                                upstreamSide.complete(accepted);
                            }))
                            .listen(0, LoopbackHost.ADDRESS),
                    "the pong-first stub upstream to start listening");
            try {
                HttpServer relayServer = startRelayOnlyServer(pongServer.actualPort(), UNOBSERVED_ADMISSION_RELEASE);
                try {
                    List<WebSocketFrameType> clientFrameTypes = new CopyOnWriteArrayList<>();
                    CompletableFuture<WebSocketFrameType> firstFrameType = new CompletableFuture<>();
                    CompletableFuture<String> pongReceived = new CompletableFuture<>();
                    CompletableFuture<String> greeted = new CompletableFuture<>();
                    relayTimeline.startUpgrade();

                    // Act — the client only listens; the upstream's pong is the first frame on the relay
                    wsClient.connect(relayOnlyOptions(relayServer.actualPort()))
                            .onSuccess(socket -> {
                                socket.pongHandler(data -> pongReceived.complete(data.toString(StandardCharsets.UTF_8)));
                                socket.frameHandler(frame -> {
                                    clientFrameTypes.add(frame.type());
                                    firstFrameType.complete(frame.type());
                                    if (frame.isText()) {
                                        greeted.complete(frame.textData());
                                    }
                                });
                            })
                            .onFailure(failure -> {
                                firstFrameType.completeExceptionally(failure);
                                pongReceived.completeExceptionally(failure);
                                greeted.completeExceptionally(failure);
                            });

                    // Assert (a) — the pong reached the client as a pong, and nothing arrived as binary data
                    assertEquals(WebSocketFrameType.PONG,
                            awaitRelayed(firstFrameType, "the upstream's pong to reach the client"),
                            "the first frame the client receives is the upstream's pong, not a data frame");
                    assertEquals(pongPayload, awaitRelayed(pongReceived, "the client's pong handler to fire"),
                            "the client's pong handler receives the upstream's pong payload");
                    assertEquals(List.of(WebSocketFrameType.PONG), List.copyOf(clientFrameTypes),
                            "the relayed pong reaches the client once, and never as a binary frame");

                    // Assert (b) — the pong took no first-data-frame report
                    assertEquals(0, relayTimeline.reportCount(Direction.UPSTREAM_TO_CLIENT),
                            "a relayed pong is not reported as the direction's first data frame");

                    // Act — the upstream now sends its first data frame
                    Awaits.connect(upstreamSide, "the pong-first upstream to accept the relay's upgrade")
                            .writeTextMessage(greeting);

                    // Assert (c) — the greeting is relayed and is the one first-data-frame report
                    assertEquals(greeting, awaitRelayed(greeted, "the upstream's greeting to reach the client"));
                    assertAll("the greeting, not the pong, is the direction's first data frame",
                            () -> assertEquals(1, relayTimeline.reportCount(Direction.UPSTREAM_TO_CLIENT),
                                    "exactly one first-data-frame report is recorded towards the client"),
                            () -> assertEquals(List.of(WebSocketFrameType.PONG, WebSocketFrameType.TEXT),
                                    List.copyOf(clientFrameTypes),
                                    "the client received the pong and then the greeting, and no binary frame"));
                } finally {
                    Awaits.teardown(relayServer.close(), "the relay-only server to close");
                }
            } finally {
                Awaits.teardown(pongServer.close(), "the pong-first stub upstream to close");
            }
        }
    }

    /**
     * A forwarded control frame is held to the same write-queue bound as a data frame. Vert.x's
     * {@code writeQueueFull()} is a flow-control signal, not a limit — {@code writeFrame} keeps queueing
     * — so a relay that checked it only after data frames would let a peer throttled on data switch to
     * pings or unsolicited pongs and grow the other leg's write queue without bound.
     * <p>
     * The relay dials through a {@link WebSocketRelayStageTest#queueFullDialer dialer} whose upstream leg reports a full write
     * queue and records every frame the relay writes to it. A relay that applies backpressure after a
     * control frame installs its drain handler on that leg right after writing the frame; one that does
     * not never installs it, so the await on the drain handler times out. The client's frame is the only
     * frame the relay writes to the upstream leg, so the recorded writes name the frame the backpressure
     * followed.
     */
    @Nested
    @DisplayName("control-frame backpressure — a relayed ping or pong pauses the source while the target's write queue is full")
    class ControlFrameBackpressure {

        @Test
        @DisplayName("applies write-queue backpressure after relaying a client ping to the upstream")
        void appliesBackpressureAfterRelayingPing() throws Exception {
            assertEquals(List.of(WebSocketFrameType.PING),
                    backpressuredWritesAfter(WebSocketFrame.pingFrame(Buffer.buffer(
                            Generators.letterStrings(1, 32).next()))),
                    "the relay waits for the upstream leg to drain right after forwarding the ping");
        }

        @Test
        @DisplayName("applies write-queue backpressure after relaying an unsolicited client pong to the upstream")
        void appliesBackpressureAfterRelayingPong() throws Exception {
            assertEquals(List.of(WebSocketFrameType.PONG),
                    backpressuredWritesAfter(WebSocketFrame.pongFrame(Buffer.buffer(
                            Generators.letterStrings(1, 32).next()))),
                    "the relay waits for the upstream leg to drain right after forwarding the pong");
        }

        /**
         * Sends {@code controlFrame} from the client through a relay whose upstream leg reports a full
         * write queue, and returns the frame types the relay had written to that leg when it installed
         * its drain handler there.
         */
        private List<WebSocketFrameType> backpressuredWritesAfter(WebSocketFrame controlFrame) throws Exception {
            // Arrange — a relay over the setUp echo upstream whose upstream leg reports a full write queue
            CompletableFuture<List<WebSocketFrameType>> drainAwaited = new CompletableFuture<>();
            HttpServer relayServer = startHoppingRelayServer(upstreamPort,
                    queueFullDialer(relayUpstreamClient, drainAwaited), RelayObserver.NO_OP, thread -> {
                        // this fixture observes the upstream leg, not threads
                    });
            try {
                relayTimeline.startUpgrade();
                WebSocket socket = Awaits.connect(wsClient.connect(relayOnlyOptions(relayServer.actualPort())),
                        "the WebSocket upgrade against the queue-full relay server");

                // Act
                relayTimeline.recordClientWrite();
                socket.writeFrame(controlFrame);

                // Assert — handed back to the caller
                return awaitRelayed(drainAwaited,
                        "the relay to install a drain handler on the full upstream leg after the control frame");
            } finally {
                Awaits.teardown(relayServer.close(), "the queue-full relay server to close");
            }
        }
    }

    /**
     * Pins the threading {@link WebSocketRelayStage}'s Javadoc claims: the relay's wiring and the
     * upstream leg's frame handling run on the client connection's context — the event-loop thread the
     * router handler ran on — whatever thread called {@link WebSocketRelayStage#relay}.
     * <p>
     * The relay-only server records its router handler's thread, captures the client connection's
     * context there and hands {@code relay()} to the fixture's plain virtual-thread executor, which binds
     * no Vert.x context to the calling thread. A relay that hopped onto the calling thread's context
     * instead of the captured one would dial the upstream — and so run the upstream leg's frame handling
     * — on a freshly bound context. Vert.x spreads fresh contexts across its event loops, so a single
     * upgrade could land on the router's loop by coincidence; the test therefore drives
     * {@link #THREAD_IDENTITY_UPGRADES} sequential upgrades and asserts every one of them.
     */
    @Nested
    @DisplayName("threading — wiring and upstream-leg frame handling run on the client connection's event loop")
    class Threading {

        /** Sequential upgrades whose relay threads are each checked against the router handler's thread. */
        private static final int THREAD_IDENTITY_UPGRADES = 4;

        private final AtomicReference<@Nullable Thread> routerThread = new AtomicReference<>();
        private final AtomicReference<@Nullable Thread> wiringThread = new AtomicReference<>();
        private final Set<Thread> upstreamFrameThreads = ConcurrentHashMap.newKeySet();

        @Test
        @DisplayName("runs the relay wiring and the upstream leg's frame handling on the router handler's event-loop thread")
        void runsWiringAndUpstreamFrameHandlingOnTheClientConnectionEventLoop() throws Exception {
            HttpServer relayServer = startThreadRecordingRelayServer();
            try {
                for (int upgrade = 1; upgrade <= THREAD_IDENTITY_UPGRADES; upgrade++) {
                    // Arrange
                    routerThread.set(null);
                    wiringThread.set(null);
                    upstreamFrameThreads.clear();
                    relayTimeline.startUpgrade();
                    String frame = Generators.letterStrings(1, 32).next();
                    WebSocket socket = Awaits.connect(wsClient.connect(relayOnlyOptions(relayServer.actualPort())),
                            "upgrade " + upgrade + " against the thread-recording relay server");
                    CompletableFuture<String> echoed = new CompletableFuture<>();
                    socket.textMessageHandler(echoed::complete);

                    // Act — one frame round-trip: client leg to the echo upstream, upstream leg back
                    writeRelayed(socket, frame);
                    assertEquals(frame, awaitRelayed(echoed,
                            "upgrade " + upgrade + ": the frame to echo through the relay"));

                    // Assert
                    Thread eventLoop = routerThread.get();
                    assertNotNull(eventLoop, "upgrade " + upgrade + ": the router handler recorded its thread");
                    int current = upgrade;
                    assertSame(eventLoop, wiringThread.get(), () -> "upgrade " + current
                            + ": the relay's wiring runs on the client connection's event-loop thread "
                            + eventLoop.getName() + ", not on " + threadName(wiringThread.get()));
                    assertEquals(Set.of(eventLoop), Set.copyOf(upstreamFrameThreads), () -> "upgrade " + current
                            + ": the upstream leg's frame handling runs on the client connection's event-loop thread "
                            + eventLoop.getName() + ", not on " + upstreamFrameThreads.stream()
                            .map(Thread::getName).toList());
                    Awaits.teardown(socket.close(), "upgrade " + upgrade + "'s relayed WebSocket to close");
                }
            } finally {
                Awaits.teardown(relayServer.close(), "the thread-recording relay server to close");
            }
        }

        /**
         * A {@link WebSocketRelayStageTest#startHoppingRelayServer hopping relay server} against the setUp
         * echo upstream that records the thread of its router handler, of every relay's wiring and of its
         * upstream leg's text-frame handling.
         */
        private HttpServer startThreadRecordingRelayServer() throws Exception {
            return startHoppingRelayServer(upstreamPort,
                    frameThreadRecordingDialer(relayUpstreamClient, upstreamFrameThreads), wiring -> {
                        wiringThread.set(Thread.currentThread());
                        wiring.run();
                    }, routerThread::set);
        }

        private static String threadName(@Nullable Thread thread) {
            return thread == null ? "no thread (the wiring never ran)" : thread.getName();
        }
    }

    /**
     * Wraps the stage's upstream dialer so that every upstream leg it dials records, in
     * {@code frameThreads}, the thread each text frame reaching that leg's frame handler is handled on.
     * Every call is forwarded to the real dialer and the real leg unchanged; only the frame handler the
     * relay installs on the upstream leg is decorated. The relay's frame handlers are private, so the
     * dialer — a constructor argument of the stage — is the one place the upstream leg can be observed
     * without a production seam.
     *
     * @param delegate     the real dialer
     * @param frameThreads receives the thread of every text frame the upstream leg's handler receives
     * @return a dialer handing the relay thread-recording upstream legs
     */
    private static WebSocketClient frameThreadRecordingDialer(WebSocketClient delegate, Set<Thread> frameThreads) {
        return WebSocketClient.class.cast(Proxy.newProxyInstance(WebSocketClient.class.getClassLoader(),
                new Class<?>[]{WebSocketClient.class}, (proxy, method, args) -> {
                    Object result = invokeOn(delegate, method, args);
                    if ("connect".equals(method.getName()) && result instanceof Future<?> dialed) {
                        return dialed.map(upstreamWs -> frameThreadRecordingLeg(WebSocket.class.cast(upstreamWs),
                                frameThreads));
                    }
                    return result;
                }));
    }

    private static WebSocket frameThreadRecordingLeg(WebSocket delegate, Set<Thread> frameThreads) {
        return WebSocket.class.cast(Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if ("frameHandler".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof Handler<?> handler) {
                        return invokeOn(delegate, method, new Object[]{threadRecording(handler, frameThreads)});
                    }
                    return invokeOn(delegate, method, args);
                }));
    }

    private static <T> Handler<T> threadRecording(Handler<T> handler, Set<Thread> frameThreads) {
        return event -> {
            if (event instanceof WebSocketFrame frame && frame.isText()) {
                frameThreads.add(Thread.currentThread());
            }
            handler.handle(event);
        };
    }

    /**
     * Wraps the stage's upstream dialer so that every upstream leg it dials reports a full write queue
     * and records the type of every frame the relay writes to it. When the relay installs a drain
     * handler on such a leg, {@code drainAwaited} completes with the frame types written to it so far.
     * Every other call is forwarded to the real dialer and the real leg unchanged, and the drain handler
     * itself is installed on the real leg as well.
     *
     * @param delegate     the real dialer
     * @param drainAwaited completed with the frame types written before the first drain handler was installed
     * @return a dialer handing the relay upstream legs that report a full write queue
     */
    private static WebSocketClient queueFullDialer(WebSocketClient delegate,
            CompletableFuture<List<WebSocketFrameType>> drainAwaited) {
        return WebSocketClient.class.cast(Proxy.newProxyInstance(WebSocketClient.class.getClassLoader(),
                new Class<?>[]{WebSocketClient.class}, (proxy, method, args) -> {
                    Object result = invokeOn(delegate, method, args);
                    if ("connect".equals(method.getName()) && result instanceof Future<?> dialed) {
                        return dialed.map(upstreamWs -> queueFullLeg(WebSocket.class.cast(upstreamWs), drainAwaited));
                    }
                    return result;
                }));
    }

    private static WebSocket queueFullLeg(WebSocket delegate,
            CompletableFuture<List<WebSocketFrameType>> drainAwaited) {
        List<WebSocketFrameType> written = new CopyOnWriteArrayList<>();
        return WebSocket.class.cast(Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if (args == null || args.length == 0) {
                        return "writeQueueFull".equals(method.getName()) ? Boolean.TRUE
                                : invokeOn(delegate, method, args);
                    }
                    if ("writeFrame".equals(method.getName()) && args[0] instanceof WebSocketFrame frame) {
                        written.add(frame.type());
                    } else if ("drainHandler".equals(method.getName()) && args[0] instanceof Handler<?>) {
                        drainAwaited.complete(List.copyOf(written));
                    }
                    return invokeOn(delegate, method, args);
                }));
    }

    private static @Nullable Object invokeOn(Object delegate, Method method, @Nullable Object @Nullable [] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            throw cause != null ? cause : wrapped;
        }
    }

    /**
     * A relay-await timeout reports the relay's wiring-versus-frame timeline, so a relay test that
     * times out says whether its first frame was written before the relay installed its handlers. The
     * first test drives a real timeout through the helper; the second proves the full edge reports
     * into the recorder at all — without it, every full-edge timeline could silently read
     * "never wired".
     */
    @Nested
    @DisplayName("relay timeline — a relay-await timeout says whether the first frame preceded the wiring")
    class RelayTimelineReport {

        /** The bounded wait the forced timeout runs against; the relay's wiring is held well past it. */
        private static final long BOUNDED_AWAIT_MILLIS = 250;

        @Test
        @DisplayName("names a first write that preceded the wiring when a relay await times out")
        void reportsFrameWrittenBeforeHandlerInstalledOnTimeout() throws Exception {
            // Arrange — a relay-only server whose relay wiring is held until the test releases it
            AtomicReference<@Nullable Runnable> heldWiring = new AtomicReference<>();
            HttpServer relayServer = startRelayOnlyServer(upstreamPort, UNOBSERVED_ADMISSION_RELEASE, wiring -> {
                Context relayContext = Vertx.currentContext();
                heldWiring.set(() -> relayContext.runOnContext(v -> wiring.run()));
            });
            try {
                relayTimeline.startUpgrade();
                WebSocket socket = Awaits.connect(wsClient.connect(relayOnlyOptions(relayServer.actualPort())),
                        "the WebSocket upgrade against the wiring-holding relay server");
                CompletableFuture<String> echoed = new CompletableFuture<>();
                socket.textMessageHandler(echoed::complete);
                writeRelayed(socket, Generators.letterStrings(1, 32).next());
                AtomicReference<@Nullable TimeoutException> original = new AtomicReference<>();

                // Act — a real bounded wait on an echo that cannot arrive while the wiring is held
                TimeoutException failure = assertThrows(TimeoutException.class,
                        () -> withRelayTimeline(relayTimeline, () -> {
                            try {
                                return echoed.get(BOUNDED_AWAIT_MILLIS, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException timedOut) {
                                original.set(timedOut);
                                throw timedOut;
                            }
                        }));

                // Assert — the rethrown timeout keeps the original as cause and carries the timeline
                String message = failure.getMessage();
                assertAll("the relay timeline in the timeout message",
                        () -> assertSame(original.get(), failure.getCause(),
                                "the rethrown timeout carries the original timeout as its cause"),
                        () -> assertFalse(message.contains("client first write: none recorded"),
                                () -> "the client's first write is on the timeline: " + message),
                        () -> assertTrue(message.contains("relay wired: never wired"),
                                () -> "the held wiring reads as never wired: " + message),
                        () -> assertTrue(message.contains("first frame " + Direction.CLIENT_TO_UPSTREAM + ": none"),
                                () -> "no frame was relayed towards the upstream: " + message),
                        () -> assertTrue(message.contains("first frame " + Direction.UPSTREAM_TO_CLIENT + ": none"),
                                () -> "no frame was relayed towards the client: " + message),
                        () -> assertTrue(message.contains("verdict: " + FRAME_WRITTEN_BEFORE_HANDLER),
                                () -> "the verdict names the write that preceded the wiring: " + message));
            } finally {
                Runnable wiring = heldWiring.get();
                if (wiring != null) {
                    wiring.run();
                }
                Awaits.teardown(relayServer.close(), "the wiring-holding relay server to close");
            }
        }

        @Test
        @DisplayName("records the wiring and a first relayed frame in each direction for a relay the full edge builds")
        void fullEdgeRelayReportsIntoTheRecorder() throws Exception {
            // Arrange
            WebSocket socket = connect("/ws-open/room", ALLOWED_ORIGIN);
            CompletableFuture<String> echoed = new CompletableFuture<>();
            socket.textMessageHandler(echoed::complete);

            // Act — one frame round-trip through the edge's relay
            writeRelayed(socket, Generators.letterStrings(1, 32).next());
            awaitRelayed(echoed, "the echoed frame to return through the edge's relay");

            // Assert — the edge handed its observer to the relay stage it built
            assertAll("the full edge reports its relay into the recorder",
                    () -> assertTrue(relayTimeline.wasWired(), "the edge's relay reported its wiring"),
                    () -> assertEquals(Set.of(Direction.CLIENT_TO_UPSTREAM, Direction.UPSTREAM_TO_CLIENT),
                            relayTimeline.relayedDirections(),
                            "the edge's relay reported a first relayed frame in both directions"));
        }
    }

    /**
     * Stands up a front server whose single route hands the request straight to a fresh
     * {@link WebSocketRelayStage} — no edge, no pipeline — so {@code releaseAdmission} is observable as
     * a plain callback instead of the edge's private admission semaphore. The stage runs each relay's
     * wiring immediately and reports into {@link #relayTimeline}.
     */
    private HttpServer startRelayOnlyServer(int upstreamTargetPort, Runnable releaseAdmission) throws Exception {
        return startRelayOnlyServer(upstreamTargetPort, releaseAdmission, RelayObserver.NO_OP);
    }

    /**
     * As {@link #startRelayOnlyServer(int, Runnable)}, with each relay's wiring handed to
     * {@code wiringDelegate}, so a test can hold or defer it.
     */
    private HttpServer startRelayOnlyServer(int upstreamTargetPort, Runnable releaseAdmission,
            RelayObserver wiringDelegate) throws Exception {
        RouteRuntime route = RouteRuntime.builder()
                .id("relay-only")
                .protocol(Protocol.WEBSOCKET)
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamTargetPort, ""))
                .effectiveWebSocketIdleTimeoutSeconds(300)
                .build();
        WebSocketRelayStage stage = new WebSocketRelayStage(relayUpstreamClient,
                new UpstreamFailureMapper(new GatewayEventCounter()), new GatewayEventCounter(),
                relayTimeline.observer(wiringDelegate));
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            // Mirror GatewayEdgeRoute.handle(): the request stream is paused before the dispatch, so the
            // asynchronous upstream dial cannot race the body being read out from under toWebSocket().
            ctx.request().pause();
            // The router handler runs on the client connection's event loop, so its current context is the
            // one GatewayEdgeRoute.handle() captures and hands the relay.
            stage.relay(ctx, Vertx.currentContext(), route, Map.of(), Map.of(), "/", releaseAdmission);
        });
        return Awaits.connect(
                vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the relay-only server to start listening");
    }

    /**
     * Stands up a relay-only front server whose router handler pauses the request, hands its own thread
     * to {@code routerThreadSink} and captures the client connection's context on the event loop, then
     * dispatches {@link WebSocketRelayStage#relay} from the fixture's virtual-thread executor — the same
     * pause, capture and hop the edge performs. The stage dials through {@code dialer}, hands each relay's
     * wiring to {@code wiringDelegate} and reports into {@link #relayTimeline}.
     */
    private HttpServer startHoppingRelayServer(int upstreamTargetPort, WebSocketClient dialer,
            RelayObserver wiringDelegate, Consumer<Thread> routerThreadSink) throws Exception {
        RouteRuntime route = RouteRuntime.builder()
                .id("hopping-relay")
                .protocol(Protocol.WEBSOCKET)
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamTargetPort, ""))
                .effectiveWebSocketIdleTimeoutSeconds(300)
                .build();
        WebSocketRelayStage stage = new WebSocketRelayStage(dialer,
                new UpstreamFailureMapper(new GatewayEventCounter()), new GatewayEventCounter(),
                relayTimeline.observer(wiringDelegate));
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            ctx.request().pause();
            routerThreadSink.accept(Thread.currentThread());
            // Captured on the event loop, before the hop, exactly as GatewayEdgeRoute.handle() does.
            Context clientContext = Vertx.currentContext();
            virtualThreadExecutor.execute(() -> stage.relay(ctx, clientContext, route, Map.of(), Map.of(),
                    "/", UNOBSERVED_ADMISSION_RELEASE));
        });
        return Awaits.connect(
                vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the hopping relay server to start listening");
    }

    private static WebSocketConnectOptions relayOnlyOptions(int port) {
        return new WebSocketConnectOptions().setHost(LoopbackHost.ADDRESS).setPort(port).setURI("/relay");
    }

    /**
     * Writes a text frame the relay under await is expected to forward, recording the client's first
     * write on {@link #relayTimeline}.
     */
    private void writeRelayed(WebSocket socket, String text) {
        relayTimeline.recordClientWrite();
        socket.writeTextMessage(text);
    }

    /**
     * Awaits a frame the relay forwards, on the connect tier. A timeout is rethrown with the relay's
     * wiring-versus-frame timeline appended — see {@link #withRelayTimeline}.
     *
     * @param relayed the future the relayed frame completes
     * @param what    what is being awaited, surfaced verbatim in the timeout diagnostics
     * @return the relayed value
     */
    private <T> T awaitRelayed(CompletableFuture<T> relayed, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        return withRelayTimeline(relayTimeline, () -> Awaits.connect(relayed, what));
    }

    /**
     * Runs a relay await and, when it times out, rethrows a {@link TimeoutException} whose cause is the
     * original and whose message is the original message plus {@code timeline}'s rendering. Every
     * other outcome passes through unchanged. {@code Awaits} itself stays generic: the timeline is
     * appended here, at the one place that knows a relay is being awaited.
     *
     * @param timeline   the timeline of the relay under await
     * @param relayAwait the await to run
     * @return the awaited value
     */
    private static <T> T withRelayTimeline(RelayTimeline timeline, RelayAwait<T> relayAwait)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return relayAwait.await();
        } catch (TimeoutException timedOut) {
            TimeoutException withTimeline = new TimeoutException(
                    Objects.requireNonNullElse(timedOut.getMessage(), timedOut.toString())
                            + System.lineSeparator() + timeline.render());
            withTimeline.initCause(timedOut);
            throw withTimeline;
        }
    }

    /**
     * An await on a relayed frame.
     *
     * @param <T> the awaited value's type
     */
    @FunctionalInterface
    private interface RelayAwait<T> {

        T await() throws InterruptedException, ExecutionException, TimeoutException;
    }

    /**
     * Records the timeline of the relay under await: the client's first write, the relay's wiring
     * time and its first relayed frame per direction, all as {@link System#nanoTime()} readings.
     * <p>
     * One timeline covers one relay. {@link #startUpgrade()} clears it before each client upgrade, and
     * each relay restarts the relay-side half at its {@code beforeWiring}. That is sound because every
     * test in this class drives its upgrades sequentially: no two relays are ever live against one
     * recorder at once. Thread-safe: the relay reports from an event loop while the test thread records
     * writes and renders.
     */
    private static final class RelayTimeline {

        private final AtomicReference<@Nullable Long> clientFirstWriteAt = new AtomicReference<>();
        private final AtomicReference<@Nullable Long> wiredAt = new AtomicReference<>();
        private final Map<Direction, Long> firstFrameAt = new ConcurrentHashMap<>();
        /** Every first-frame report in arrival order, so a test can count them rather than only see one. */
        private final List<Direction> reports = new CopyOnWriteArrayList<>();

        /** Clears the whole timeline ahead of a new client upgrade. */
        void startUpgrade() {
            clientFirstWriteAt.set(null);
            restartRelay();
        }

        /** Records the client's first write since {@link #startUpgrade()}; later writes are ignored. */
        void recordClientWrite() {
            clientFirstWriteAt.compareAndSet(null, System.nanoTime());
        }

        boolean wasWired() {
            return wiredAt.get() != null;
        }

        Set<Direction> relayedDirections() {
            return Set.copyOf(firstFrameAt.keySet());
        }

        /**
         * The number of first-frame reports the current relay made in {@code direction}.
         *
         * @param direction the direction to count
         * @return the report count — at most one from a correct relay
         */
        int reportCount(Direction direction) {
            return (int) reports.stream().filter(direction::equals).count();
        }

        /**
         * An observer that records into this timeline and hands each relay's wiring to
         * {@code wiringDelegate}.
         *
         * @param wiringDelegate receives the wiring — {@link RelayObserver#NO_OP} runs it immediately
         * @return the recording observer
         */
        RelayObserver observer(RelayObserver wiringDelegate) {
            return new RelayObserver() {

                @Override
                public void beforeWiring(Runnable wiring) {
                    restartRelay();
                    wiringDelegate.beforeWiring(wiring);
                }

                @Override
                public void wired(long nanoTime) {
                    wiredAt.set(nanoTime);
                }

                @Override
                public void frameRelayed(Direction direction, long nanoTime) {
                    reports.add(direction);
                    firstFrameAt.putIfAbsent(direction, nanoTime);
                }
            };
        }

        /**
         * Renders the timeline, each relay event with its offset from the client's first write, and a
         * one-line verdict.
         *
         * @return the rendering, never {@code null}
         */
        String render() {
            Long write = clientFirstWriteAt.get();
            Long wiring = wiredAt.get();
            StringBuilder text = new StringBuilder(256)
                    .append("relay timeline (System.nanoTime; offsets from the client's first write):");
            line(text, "client first write", write == null ? "none recorded" : write + " ns");
            line(text, "relay wired", wiring == null ? "never wired" : at(wiring, write));
            for (Direction direction : Direction.values()) {
                Long frame = firstFrameAt.get(direction);
                line(text, "first frame " + direction, frame == null ? "none" : at(frame, write));
            }
            line(text, "verdict", verdict(write, wiring));
            return text.toString();
        }

        private void restartRelay() {
            wiredAt.set(null);
            firstFrameAt.clear();
            reports.clear();
        }

        private String verdict(@Nullable Long write, @Nullable Long wiring) {
            if (write == null) {
                return "no client write recorded";
            }
            boolean writePrecededWiring = wiring == null || wiring - write > 0;
            if (writePrecededWiring && firstFrameAt.isEmpty()) {
                return FRAME_WRITTEN_BEFORE_HANDLER;
            }
            return "the first write did not precede an unwired relay";
        }

        private static String at(long nanos, @Nullable Long write) {
            if (write == null) {
                return nanos + " ns";
            }
            long offset = nanos - write;
            return nanos + " ns (" + (offset >= 0 ? "+" : "") + offset + " ns)";
        }

        private static void line(StringBuilder text, String label, String value) {
            text.append(System.lineSeparator()).append("  ").append(label).append(": ").append(value);
        }
    }

    private static void awaitReleases(AtomicInteger releases, String message) throws TimeoutException {
        Awaits.until(() -> releases.get() >= 1, message, Awaits.ADMISSION_RELEASE_CEILING_SECONDS);
        assertEquals(1, releases.get(), message);
    }

    // NOSONAR java:S2925 - Thread.sleep is load-bearing and deliberately NOT an Awaits tier: a bounded
    // settle window is the only way to observe that no *further* release lands after the first one on
    // a live event loop. Absence of an event is not pollable — polling would return as soon as the
    // count is 1, which is exactly the state that holds both when nothing further lands and when a
    // second release is still in flight.
    @SuppressWarnings("java:S2925")
    private static void assertNoFurtherRelease(AtomicInteger releases, String message)
            throws InterruptedException {
        Thread.sleep(250);
        assertEquals(1, releases.get(), message);
    }

    /**
     * The failure message for an unexpected upgrade status: what the rejection said, plus who was
     * actually listening on each port this fixture bound.
     * <p>
     * These two halves answer the two halves of the question. {@code Awaits} enriches the
     * {@link ExecutionException} message with the rejected status, the response headers and the body,
     * and header <em>presence</em> is evidence in ONE direction only. {@code WebSocketRelayStage}
     * applies the gateway's stage-0 security headers in {@code onUpstreamFailure} only on the branch
     * where the head has not yet been written, so a rejection carrying them was written by this
     * gateway relaying an upstream's verbatim status — that inference is sound.
     * <p>
     * The converse is NOT sound, and the discriminator must not be read as if it were: the same
     * method returns early when {@code response.ended()}, and falls through to a bare
     * {@code response.end()} when the head was already written, so a rejection the gateway DID
     * render can arrive with no stage-0 headers. A bare rejection therefore means <em>either</em> the
     * connection never reached the gateway <em>or</em> the gateway rendered it past the header
     * branch — the LISTEN report below is what separates those two, and is the reason it is captured
     * in every case rather than only on the bare path.
     * <p>
     * Supplied lazily to {@code assertEquals}, so a green run pays for none of it.
     *
     * @param failure the wrapper the awaited upgrade failed with
     * @return the rendered report, never {@code null}
     */
    private String rejectionReport(ExecutionException failure) {
        return failure.getMessage() + listenOwners(frontPort, upstreamPort, deadPort);
    }

    /**
     * Renders the LISTEN owner of each named port, one line per port.
     * <p>
     * This is a diagnostic, never a gate: it must not throw and must not fail a test, so every
     * failure mode — a missing binary, an I/O error, an overrun of the two-second bound — degrades to
     * a stated note in the report rather than propagating.
     *
     * @param ports the ports to report on
     * @return the rendered report, never {@code null}
     */
    private static String listenOwners(int... ports) {
        StringBuilder report = new StringBuilder(256)
                .append(System.lineSeparator()).append("LISTEN owners:");
        for (int port : ports) {
            report.append(System.lineSeparator()).append("  ").append(port).append(": ")
                    .append(listenOwner(port));
        }
        return report.toString();
    }

    /**
     * Runs {@code lsof -nP -iTCP:<port> -sTCP:LISTEN} against an absolute binary path.
     *
     * @param port the port to report on
     * @return the single-line rendering, or a stated degradation note
     */
    private static String listenOwner(int port) {
        Optional<String> binary = LSOF_BINARIES.stream()
                .filter(candidate -> Files.isExecutable(Path.of(candidate)))
                .findFirst();
        if (binary.isEmpty()) {
            return "lsof unavailable (no executable at " + String.join(" or ", LSOF_BINARIES) + ")";
        }
        try {
            Process process = new ProcessBuilder(binary.get(), "-nP", "-iTCP:" + port, "-sTCP:LISTEN")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(LSOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "lsof unavailable (no answer within " + LSOF_TIMEOUT_SECONDS + "s)";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return output.isEmpty() ? "no LISTEN owner" : output.replace("\n", " | ");
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            return "lsof unavailable (interrupted: " + cause + ")";
        } catch (IOException cause) {
            return "lsof unavailable (" + cause + ")";
        }
    }

    private WebSocket connect(String uri, String origin) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(LoopbackHost.ADDRESS).setPort(frontPort).setURI(uri).addHeader("Origin", origin);
        relayTimeline.startUpgrade();
        return Awaits.connect(wsClient.connect(options), "the WebSocket upgrade to " + options.getURI());
    }

    /** Opens a handshake carrying the {@code X-Custom} header the forward-policy assertions key on. */
    private WebSocket connectWithCustomHeader(String uri) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(LoopbackHost.ADDRESS).setPort(frontPort).setURI(uri)
                .addHeader("Origin", ALLOWED_ORIGIN).addHeader("X-Custom", "leak");
        relayTimeline.startUpgrade();
        return Awaits.connect(wsClient.connect(options), "the WebSocket upgrade to " + options.getURI());
    }

    private static SecurityHeadersConfig securityHeaders() {
        return SecurityHeadersConfig.builder()
                .contentTypeNosniff(Boolean.TRUE)
                .frameDeny(Boolean.TRUE)
                .build();
    }

    /**
     * A WebSocket route whose forward posture is declared explicitly rather than left to the default.
     * <p>
     * Every route here declares a positive-list, and the {@code /ws-forward-all} route deliberately
     * does not — the pair is what keeps both postures pinned. Leaving the posture implicit is what
     * made these fixtures silently change meaning when the absent state flipped from nothing-crosses
     * to forward-all, so the posture is now stated at each call site.
     */
    private static ResolvedRoute wsRoute(String id, String pathPrefix, Require require, int upstreamPort,
            Set<String> allowedOrigins, @Nullable Integer idleTimeoutSeconds, @Nullable ForwardConfig forward) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                // Every route here is unanchored, so it resolves the gateway block — exactly what
                // RouteTableBuilder materializes. Stage 2a applies the ROUTE's block after route
                // selection (ADR-0007 Amendment A1), so a fixture route carrying none would strip them.
                .effectiveSecurityHeaders(securityHeaders())
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                .effectiveAllowedOrigins(allowedOrigins)
                .effectiveWebSocketIdleTimeoutSeconds(idleTimeoutSeconds)
                .effectiveForward(forward)
                .build();
    }

    /**
     * A declared-empty positive-list: the route names no forwardable client header, so none crosses.
     * This is the posture these fixtures shipped with, now stated rather than inherited from a
     * default that has since inverted.
     */
    private static ForwardConfig positiveListNamingNothing() {
        return ForwardConfig.builder().headersAllow(List.of()).build();
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
