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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


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
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.MultiMap;
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
    private WebSocketClient relayUpstreamClient;
    private int frontPort;
    private int upstreamPort;
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
        upstreamPort = upstreamServer.actualPort();
        relayUpstreamClient = vertx.createWebSocketClient();

        // A definitely-closed port for the unreachable-upstream case.
        HttpServer throwaway = vertx.createHttpServer().requestHandler(req -> req.response().end())
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        deadPort = throwaway.actualPort();
        throwaway.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

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
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, gatewayConfig,
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor,
                new EdgeHardeningOptions(), new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert());

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
        relayUpstreamClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
    @DisplayName("forwards no non-allow-listed handshake header on a positive-list route")
    void deniesNonAllowlistedForwardHeader() throws Exception {
        // Arrange — a custom header the route's declared-empty positive-list does not name
        WebSocket socket = connectWithCustomHeader("/ws-open/room");
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        // Act
        socket.writeTextMessage("go");
        echoed.get(15, TimeUnit.SECONDS);

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
        socket.writeTextMessage("go");
        echoed.get(15, TimeUnit.SECONDS);

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
                socket.writeTextMessage("live");
                echoed.get(15, TimeUnit.SECONDS);
                assertEquals(0, releases.get(), "an established relay keeps holding its admission permit");

                // Act
                socket.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

                // Assert — closeBoth is re-entered from the upstream leg's close handler once the first
                // pass closed it, so the latch is what keeps the release at exactly one
                awaitReleases(releases, "a graceful close releases the admission permit");
                assertNoFurtherRelease(releases, "the closeBoth latch makes a repeated teardown a no-op");
            } finally {
                relayServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
                plainClient.request(io.vertx.core.http.HttpMethod.GET, relayServer.actualPort(), "localhost",
                        "/relay").compose(HttpClientRequest::send);

                // Assert
                awaitReleases(releases, "the client-upgrade-failure branch releases the admission permit");
                assertNoFurtherRelease(releases, "the upgrade-failure branch releases exactly once");
            } finally {
                plainClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                relayServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
                relayServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        private WebSocket connectTo(int port) throws Exception {
            return wsClient.connect(new WebSocketConnectOptions()
                    .setHost("localhost").setPort(port).setURI("/relay"))
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }

    /**
     * Stands up a front server whose single route hands the request straight to a fresh
     * {@link WebSocketRelayStage} — no edge, no pipeline — so {@code releaseAdmission} is observable as
     * a plain callback instead of the edge's private admission semaphore.
     */
    private HttpServer startRelayOnlyServer(int upstreamTargetPort, Runnable releaseAdmission) throws Exception {
        RouteRuntime route = RouteRuntime.builder()
                .id("relay-only")
                .protocol(Protocol.WEBSOCKET)
                .upstream(new ResolvedUpstream("http", "localhost", upstreamTargetPort, ""))
                .effectiveWebSocketIdleTimeoutSeconds(300)
                .build();
        WebSocketRelayStage stage = new WebSocketRelayStage(relayUpstreamClient,
                new UpstreamFailureMapper(new GatewayEventCounter()), new GatewayEventCounter());
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            // Mirror GatewayEdgeRoute.handle(): the request stream is paused before the dispatch, so the
            // asynchronous upstream dial cannot race the body being read out from under toWebSocket().
            ctx.request().pause();
            stage.relay(ctx, route, Map.of(), Map.of(), "/", releaseAdmission);
        });
        return vertx.createHttpServer().requestHandler(router)
                .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    // NOSONAR java:S2925 - Thread.sleep is load-bearing: relay teardown is a real socket round-trip on
    // a live Vert.x event loop and no virtual clock can advance it, so the callback count is polled
    // until it settles rather than assumed to have landed.
    @SuppressWarnings("java:S2925")
    private static void awaitReleases(AtomicInteger releases, String message) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && releases.get() < 1; attempt++) {
            Thread.sleep(25);
        }
        assertEquals(1, releases.get(), message);
    }

    // NOSONAR java:S2925 - see awaitReleases: a bounded settle window is the only way to observe that
    // no *further* release lands after the first one on a live event loop.
    @SuppressWarnings("java:S2925")
    private static void assertNoFurtherRelease(AtomicInteger releases, String message)
            throws InterruptedException {
        Thread.sleep(250);
        assertEquals(1, releases.get(), message);
    }

    private WebSocket connect(String uri, String origin) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost("localhost").setPort(frontPort).setURI(uri).addHeader("Origin", origin);
        return wsClient.connect(options).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /** Opens a handshake carrying the {@code X-Custom} header the forward-policy assertions key on. */
    private WebSocket connectWithCustomHeader(String uri) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost("localhost").setPort(frontPort).setURI(uri)
                .addHeader("Origin", ALLOWED_ORIGIN).addHeader("X-Custom", "leak");
        return wsClient.connect(options).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
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
                .upstream(new ResolvedUpstream("http", "localhost", upstreamPort, ""))
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
