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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage.GrpcStreamIdleBound;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Boot-wiring contract of {@link TlsEdgeProducer}: the relay map is built from {@code
 * tls.passthrough_sni} against the resolved topology, the accept-time front listener is started only
 * when at least one passthrough SNI resolves, and shutdown is a clean no-op when nothing was started.
 * An unresolved passthrough alias is defensively skipped rather than aborting boot (ADR-0009). The
 * relay idle timeout is derived as the longest inner idle bound plus 60 seconds, which
 * {@link RelayIdleTimeoutDerivation} pins for each bound that can be the longest — a disabled
 * ({@code 0}) HTTP idle timeout included, which is no bound rather than a bound of zero.
 * <p>
 * The positive case is asserted against the <em>public port itself</em>: it allocates a free port,
 * hands it to the producer, and probes whether anything accepts a connection there. A quiet
 * {@code onStartup} would not say this — a startup that silently skipped the front completes just as
 * quietly.
 * <p>
 * The two negative cases use the opposite discriminator, because "the port never became listening" is
 * unfalsifiable evidence for them: a port that was never bound looks exactly like a port some other
 * process happens not to be using. They instead <em>hold</em> a {@link ServerSocket} open on the port
 * for the whole test and assert that {@code onStartup} does not throw. The question they answer is
 * therefore "did {@code onStartup} refuse to bind a held port?", not "did the port become listening?".
 * Because {@code SniFrontListener.start()} binds a {@code NetServer} whose options never set
 * {@code setReusePort}, a producer that attempted the bind would fail and
 * {@code TlsEdgeProducer.onStartup} would surface that as an {@link IllegalStateException} — so a quiet
 * return proves no bind was attempted.
 * <p>
 * {@link PassthroughConfigured#failsWhenThePublicPortIsHeld()} is the matched control that keeps this
 * honest. It points a <em>resolvable</em> configuration at an equally-held port and asserts the
 * {@link IllegalStateException} does arrive, proving the negative cases' silence is a real observation
 * about the producer rather than a tautology about held sockets. If {@code SniFrontListener} ever set
 * {@code setReusePort}, that control fails and the negative cases lose their discriminator together.
 */
@DisplayName("TlsEdgeProducer — accept-time front listener boot wiring")
@Tag("isolated-fork")
class TlsEdgeProducerTest {

    private static final int INTERNAL_HTTPS_PORT = 8444;
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int FREE_PORT_ATTEMPTS = 20;
    private static final String RESOLVED_ALIAS = "backend-alias";
    private static final String UNRESOLVED_ALIAS = "missing-alias";

    /** The {@code quarkus.http.idle-timeout} default, thirty minutes. */
    private static final Duration HTTP_IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final RouteTable NO_ROUTES = new RouteTable(List.of());
    private static final ResolvedUpstream WS_UPSTREAM = new ResolvedUpstream("http", "ws.backend.local", 8080, "");

    private final SheriffMetrics metrics = new SheriffMetrics(new SimpleMeterRegistry());

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    /**
     * Every case pins the derived value exactly, and every case also asserts the invariant the
     * derivation exists for: the relay never undercuts an idle bound the gateway still applies behind
     * the terminated branch.
     * <p>
     * The three cases with an HTTP idle timeout of zero, or one above both other bounds, are regression
     * pins on existing behaviour rather than failing-first tests: they were written against the
     * unchanged derivation and pass there. They keep the zero rule documented on
     * {@link TlsEdgeProducer#relayIdleTimeout(Duration, RouteTable)} from silently changing.
     */
    @Nested
    @DisplayName("Relay idle timeout derivation")
    class RelayIdleTimeoutDerivation {

        @Test
        @DisplayName("is the HTTP idle timeout plus 60 s when no WebSocket route exists")
        void httpIdleTimeoutWithoutWebSocketRoute() {
            // A plain HTTP route carries no WebSocket idle timeout and must not count
            RouteTable routes = new RouteTable(List.of(
                    ConfigFixtures.route("api", "/api").upstream(WS_UPSTREAM).build()));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, routes);

            assertEquals(HTTP_IDLE_TIMEOUT.plusSeconds(60), derived,
                    "without a WebSocket route the HTTP idle timeout is the longest inner bound");
            assertNotShorterThanEveryPositiveInnerBound(derived, HTTP_IDLE_TIMEOUT, routes);
        }

        @Test
        @DisplayName("is a WebSocket idle timeout above the HTTP idle timeout plus 60 s")
        void webSocketIdleTimeoutAboveHttpIdleTimeout() {
            // A route declaring one hour, above the thirty-minute HTTP idle timeout, next to a route
            // declaring less, so the longest one has to win
            RouteTable routes = new RouteTable(List.of(
                    webSocketRoute("ws-short", "/ws-short", 120),
                    webSocketRoute("ws-long", "/ws-long", 3600)));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, routes);

            assertEquals(Duration.ofSeconds(3600 + 60), derived,
                    "the longest WebSocket idle timeout plus the margin bounds the relay");
            assertNotShorterThanEveryPositiveInnerBound(derived, HTTP_IDLE_TIMEOUT, routes);
        }

        @Test
        @DisplayName("is the gRPC stream-idle bound of five minutes plus 60 s when the HTTP idle timeout is shorter")
        void grpcStreamIdleBoundAboveShortHttpIdleTimeout() {
            // An HTTP idle timeout below five minutes and a WebSocket route below it too
            Duration httpIdleTimeout = Duration.ofMinutes(2);
            RouteTable routes = new RouteTable(List.of(webSocketRoute("ws", "/ws", 30)));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(httpIdleTimeout, routes);

            assertEquals(Duration.ofMinutes(5).plusSeconds(60), derived,
                    "the gRPC stream-idle bound is the floor of the derivation");
            assertNotShorterThanEveryPositiveInnerBound(derived, httpIdleTimeout, routes);
        }

        @Test
        @DisplayName("is the gRPC stream-idle bound of five minutes plus 60 s when the HTTP idle timeout is zero")
        void zeroHttpIdleTimeoutLeavesTheGrpcStreamIdleBound() {
            // quarkus.http.idle-timeout=0 is Vert.x's "no idle timeout": it is no bound, so the relay
            // must not collapse to the margin alone. The WebSocket route stays below five minutes, so
            // max(gRPC stream idle, longest WebSocket idle) is the gRPC bound.
            RouteTable routes = new RouteTable(List.of(webSocketRoute("ws", "/ws", 120)));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(Duration.ZERO, routes);

            assertEquals(Duration.ofMinutes(6), derived,
                    "a disabled HTTP idle timeout leaves the gRPC stream-idle bound plus the margin");
            assertNotShorterThanEveryPositiveInnerBound(derived, Duration.ZERO, routes);
        }

        @Test
        @DisplayName("is a WebSocket idle timeout above the gRPC bound plus 60 s when the HTTP idle timeout is zero")
        void zeroHttpIdleTimeoutLeavesTheLongestWebSocketIdleTimeout() {
            // Ten minutes, above the five-minute gRPC bound, next to a route declaring less
            RouteTable routes = new RouteTable(List.of(
                    webSocketRoute("ws-short", "/ws-short", 120),
                    webSocketRoute("ws-long", "/ws-long", 600)));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(Duration.ZERO, routes);

            assertEquals(Duration.ofSeconds(600 + 60), derived,
                    "under a disabled HTTP idle timeout the longest WebSocket idle timeout still wins");
            assertNotShorterThanEveryPositiveInnerBound(derived, Duration.ZERO, routes);
        }

        @Test
        @DisplayName("is a positive HTTP idle timeout above the WebSocket and gRPC bounds plus 60 s")
        void positiveHttpIdleTimeoutAboveBothOtherBounds() {
            // Thirty minutes of HTTP idle, above a ten-minute WebSocket route and the five-minute gRPC
            // bound, so neither of those may win
            RouteTable routes = new RouteTable(List.of(webSocketRoute("ws", "/ws", 600)));

            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, routes);

            assertEquals(Duration.ofMinutes(31), derived,
                    "a positive HTTP idle timeout above both other bounds still bounds the relay");
            assertNotShorterThanEveryPositiveInnerBound(derived, HTTP_IDLE_TIMEOUT, routes);
        }

        /**
         * Asserts that {@code derived} is not shorter than any positive inner idle bound: the HTTP idle
         * timeout unless it is zero (no bound), the gRPC stream-idle bound as production applies it, and
         * every WebSocket route's effective idle timeout.
         */
        private static void assertNotShorterThanEveryPositiveInnerBound(Duration derived, Duration httpIdleTimeout,
                RouteTable routes) {
            Stream<Duration> webSocketBounds = routes.routes().stream()
                    .map(Route::effectiveWebSocketIdleTimeoutSeconds)
                    .filter(Objects::nonNull)
                    .mapToLong(Integer::longValue)
                    .mapToObj(Duration::ofSeconds);
            Stream<Duration> positiveInnerBounds = Stream.concat(
                    Stream.of(httpIdleTimeout, Duration.ofMillis(GrpcStreamIdleBound.STREAM_IDLE_TIMEOUT_MILLIS)),
                    webSocketBounds)
                    .filter(Duration::isPositive);
            assertAll("the relay idle timeout " + derived + " never undercuts a positive inner idle bound",
                    positiveInnerBounds.<Executable>map(bound -> () -> assertFalse(derived.compareTo(bound) < 0,
                            "the relay idle timeout is shorter than the inner bound " + bound)));
        }

        private static Route webSocketRoute(String id, String prefix, int idleTimeoutSeconds) {
            return ConfigFixtures.route(id, prefix)
                    .protocol(Protocol.WEBSOCKET)
                    .upstream(WS_UPSTREAM)
                    .effectiveWebSocketIdleTimeoutSeconds(idleTimeoutSeconds)
                    .build();
        }
    }

    @Nested
    @DisplayName("Relay dial-client options")
    class DialClientOptions {

        @Test
        @DisplayName("carry the derived relay idle timeout, in milliseconds when it fits")
        void carryTheDerivedRelayIdleTimeout() {
            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, NO_ROUTES);

            NetClientOptions options = TlsEdgeProducer.relayClientOptions(derived);

            assertAll("the dial client is bounded exactly like the front server",
                    () -> assertEquals(derived.toMillis(), options.getIdleTimeout()),
                    () -> assertEquals(TimeUnit.MILLISECONDS, options.getIdleTimeoutUnit()));
        }

        @Test
        @DisplayName("keep the margin over a WebSocket idle timeout of Integer.MAX_VALUE seconds")
        void keepTheMarginOverTheLongestWebSocketIdleTimeout() {
            RouteTable routes = new RouteTable(List.of(ConfigFixtures.route("ws", "/ws")
                    .protocol(Protocol.WEBSOCKET)
                    .upstream(WS_UPSTREAM)
                    .effectiveWebSocketIdleTimeoutSeconds(Integer.MAX_VALUE)
                    .build()));
            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, routes);

            NetClientOptions options = TlsEdgeProducer.relayClientOptions(derived);

            Duration set = Duration.of(options.getIdleTimeout(), options.getIdleTimeoutUnit().toChronoUnit());
            assertTrue(set.compareTo(Duration.ofSeconds(Integer.MAX_VALUE).plus(TlsEdgeProducer.RELAY_IDLE_MARGIN)) >= 0,
                    "the timeout set is never shorter than the WebSocket bound plus the margin: " + set);
        }

        /**
         * The ten seconds are the documented contract, so they are written out here rather than read
         * from {@link TlsEdgeProducer#RELAY_CONNECT_TIMEOUT}: a changed constant must turn this red.
         */
        @Test
        @DisplayName("carry the fixed ten-second connect timeout next to the derived relay idle timeout")
        void carryTheFixedConnectTimeoutNextToTheIdleTimeout() {
            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, NO_ROUTES);

            NetClientOptions options = TlsEdgeProducer.relayClientOptions(derived);

            assertAll("the production dial client bounds the dial and the idle relay",
                    () -> assertEquals(10_000, options.getConnectTimeout(),
                            "the backend dial is bounded by ten seconds, not the Vert.x default of sixty"),
                    () -> assertEquals(derived.toMillis(), options.getIdleTimeout(),
                            "the derived idle timeout is kept next to the connect timeout"),
                    () -> assertEquals(TimeUnit.MILLISECONDS, options.getIdleTimeoutUnit()));
        }

        @Test
        @DisplayName("refuse a connect timeout of no whole millisecond or beyond Integer.MAX_VALUE milliseconds")
        void refuseAConnectTimeoutOutsideTheVertxRange() {
            Duration derived = TlsEdgeProducer.relayIdleTimeout(HTTP_IDLE_TIMEOUT, NO_ROUTES);
            Duration subMillisecond = Duration.ofNanos(999_999);
            Duration beyondInt = Duration.ofMillis(Integer.MAX_VALUE + 1L);

            assertAll("Vert.x takes whole int milliseconds, and zero would mean no bound",
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> TlsEdgeProducer.relayClientOptions(derived, subMillisecond)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> TlsEdgeProducer.relayClientOptions(derived, beyondInt)),
                    () -> assertEquals(1, TlsEdgeProducer.relayClientOptions(derived, Duration.ofMillis(1))
                            .getConnectTimeout(), "one millisecond is the shortest bound accepted"));
        }
    }

    @Nested
    @DisplayName("Passthrough configured")
    class PassthroughConfigured {

        @Test
        @DisplayName("starts the front listener and shuts it down cleanly when a passthrough SNI resolves")
        void startsAndStopsFrontListener() throws Exception {
            // Arrange — one SNI maps to a resolvable alias, one to an alias absent from the topology.
            // The resolvable entry makes the relay map non-empty (front started); the unresolved entry
            // exercises the defensive skip branch. A concrete free port is used rather than the
            // ephemeral 0 so that "is the front actually bound?" is an observable fact.
            int port = freePort();
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of(
                            "sni.resolved.example", RESOLVED_ALIAS,
                            "sni.unresolved.example", UNRESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(tls).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, NO_ROUTES, metrics,
                    HTTP_IDLE_TIMEOUT, port, INTERNAL_HTTPS_PORT);
            assertFalse(isListening(port), "control precondition: nothing owns the port before startup");

            // Act
            producer.onStartup(new StartupEvent());

            // Assert — the front is genuinely accepting connections on the public port. Absence of an
            // exception would not say this: a startup that silently skipped the front (an empty relay
            // map, a swallowed bind failure) completes just as quietly.
            assertTrue(isListening(port),
                    "a resolvable passthrough SNI starts the front listener on the public port");

            // Act — and shutdown releases it
            producer.onShutdown(new ShutdownEvent());

            // Assert
            awaitNotListening(port, "shutdown stops the started listener and releases the public port");
        }

        /**
         * The relay idle timeout {@code onStartup} derives reaches both legs of a relayed connection: the
         * front server's options and the dial client's options. Both are observed through the
         * package-private test seams rather than by waiting for an idle connection to close, which would
         * make the test wait out the derived duration. A WebSocket route of one hour makes the derived
         * value differ from the thirty-minute HTTP default, so the route table demonstrably reached the
         * derivation {@code onStartup} ran.
         */
        @Test
        @DisplayName("wires the derived relay idle timeout onto the front server and the dial client")
        void wiresTheDerivedRelayIdleTimeoutOntoBothLegs() throws Exception {
            int port = freePort();
            RouteTable routes = new RouteTable(List.of(ConfigFixtures.route("ws", "/ws")
                    .protocol(Protocol.WEBSOCKET)
                    .upstream(WS_UPSTREAM)
                    .effectiveWebSocketIdleTimeoutSeconds(3600)
                    .build()));
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of("sni.resolved.example", RESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1).tls(tls).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, routes, metrics,
                    HTTP_IDLE_TIMEOUT, port, INTERNAL_HTTPS_PORT);
            Duration expected = Duration.ofSeconds(3600 + 60);
            assertAll("control precondition: nothing is observable before onStartup",
                    () -> assertTrue(producer.dialClientOptions().isEmpty(), "no dial client before startup"),
                    () -> assertTrue(producer.frontListener().isEmpty(), "no front listener before startup"));

            producer.onStartup(new StartupEvent());

            try {
                NetClientOptions dialClient = producer.dialClientOptions()
                        .orElseThrow(() -> new AssertionError("onStartup built no dial client"));
                SniFrontListener front = producer.frontListener()
                        .orElseThrow(() -> new AssertionError("onStartup started no front listener"));
                Duration frontIdleTimeout = front.appliedIdleTimeout()
                        .orElseThrow(() -> new AssertionError("the started front listener applied no idle timeout"));
                Duration dialIdleTimeout = Duration.of(dialClient.getIdleTimeout(),
                        dialClient.getIdleTimeoutUnit().toChronoUnit());
                assertAll("both relay legs carry the idle timeout derived from the route table",
                        () -> assertEquals(expected, frontIdleTimeout,
                                "the front server carries the longest WebSocket idle timeout plus the margin"),
                        () -> assertEquals(expected, dialIdleTimeout,
                                "the dial client carries the same idle timeout as the front server"),
                        () -> assertEquals(10_000, dialClient.getConnectTimeout(),
                                "the dial client onStartup built carries the fixed ten-second connect timeout"));
            } finally {
                producer.onShutdown(new ShutdownEvent());
                awaitNotListening(port, "shutdown stops the started listener and releases the public port");
            }
        }

        /**
         * The matched control for both negative cases. They read "onStartup returned quietly" as
         * "no bind was attempted"; that inference is only sound if a bind attempt against a held
         * port would in fact have been noisy. This pins that it is.
         */
        @Test
        @DisplayName("refuses to boot when the public port is already held by another socket")
        void failsWhenThePublicPortIsHeld() throws Exception {
            // Arrange — a resolvable passthrough SNI, so the front listener genuinely attempts a bind,
            // aimed at a port this test holds open for the duration.
            //
            // The wildcard bind here is deliberate, and this holder is one of the guard's named
            // per-site exemptions. The exempt sites are the collision holders (this test and the two
            // that follow it), which occupy a port so production's own wildcard bind is refused, and
            // the re-probe in isFreeForWildcardBind(), which tests that a candidate is still free for
            // the bind production will attempt. The allocation socket in freePort() is not among
            // them: like every other ephemeral listener in the module's test tree it is loopback-bound
            // through LoopbackHost.ADDRESS, because a wildcard bind would leave it reachable from any
            // interface. This socket is never dialled at all — it exists solely to OCCUPY the port —
            // and what it must collide with is production's own bind: SniFrontListener.start() binds
            // through the host-bound listen overload, and the delegating constructor TlsEdgeProducer
            // uses supplies the Vert.x wildcard default host.
            // Narrowing this holder to loopback would leave the wildcard free, the producer's bind
            // would succeed, and the control would silently stop controlling anything.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                TlsConfig tls = TlsConfig.builder()
                        .passthroughSni(Map.of("sni.resolved.example", RESOLVED_ALIAS))
                        .build();
                GatewayConfig config = GatewayConfig.builder().version(1)
                        .tls(tls).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of(
                        RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, NO_ROUTES, metrics,
                        HTTP_IDLE_TIMEOUT, port, INTERNAL_HTTPS_PORT);

                // Act + Assert — the bind cannot succeed against a held port, and the producer refuses
                // to boot rather than continuing without its front listener
                StartupEvent startup = new StartupEvent();
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> producer.onStartup(startup),
                        "a bind against a held public port must fail the boot, which is what makes the "
                                + "negative cases' quiet onStartup meaningful");

                // Assert — and specifically down the bind-failure branch. Accepting any
                // IllegalStateException would let the interrupted-while-binding branch, or an
                // unrelated wiring failure, masquerade as the bind refusal this control depends on.
                assertTrue(refused.getMessage().contains("bind failed"),
                        "the refusal must be the bind-failure branch, not merely some IllegalStateException: "
                                + refused.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Passthrough unconfigured")
    class PassthroughUnconfigured {

        @Test
        @DisplayName("never starts the front listener when passthrough_sni is empty")
        void noFrontListenerWhenPassthroughEmpty() throws Exception {
            // Arrange — no tls block at all, so the relay map is empty. The port is HELD open for the
            // whole test: a producer that tried to start the front here would be refused the bind and
            // would fail the boot, so a quiet onStartup is positive evidence that it never tried.
            // Wildcard-bound for the same reason as failsWhenThePublicPortIsHeld, and a named per-site
            // exemption of the guard for that reason — the holder has to collide with production's
            // wildcard-default bind, and it is never dialled.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                GatewayConfig config = GatewayConfig.builder().version(1).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of());
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, NO_ROUTES, metrics,
                        HTTP_IDLE_TIMEOUT, port, INTERNAL_HTTPS_PORT);

                // Act + Assert
                assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                        "an empty passthrough map short-circuits before any bind is attempted");
                assertAll("an unconfigured front builds neither relay leg",
                        () -> assertTrue(producer.dialClientOptions().isEmpty(), "no dial client is built"),
                        () -> assertTrue(producer.frontListener().isEmpty(), "no front listener is started"));

                // Act + Assert — shutdown is a clean no-op because nothing was ever created
                assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                        "shutdown is a no-op when no front listener was started");
            }
        }

        @Test
        @DisplayName("skips a passthrough SNI whose alias does not resolve, leaving the map empty")
        void skipsUnresolvedAlias() throws Exception {
            // Arrange — the only passthrough SNI maps to an alias absent from the resolved topology, so
            // the defensive skip leaves the relay map empty and no front listener is started. This
            // differs from noFrontListenerWhenPassthroughEmpty in the arrange that matters: a
            // passthrough entry IS declared here, and only the alias lookup empties the map. As there,
            // the port is held open so that an attempted bind would be refused and surface loudly.
            // Wildcard-bound for the same reason as failsWhenThePublicPortIsHeld, and a named per-site
            // exemption of the guard for that reason — the holder has to collide with production's
            // wildcard-default bind, and it is never dialled.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                TlsConfig tls = TlsConfig.builder()
                        .passthroughSni(Map.of("sni.unresolved.example", UNRESOLVED_ALIAS))
                        .build();
                GatewayConfig config = GatewayConfig.builder().version(1)
                        .tls(tls).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of());
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, NO_ROUTES, metrics,
                        HTTP_IDLE_TIMEOUT, port, INTERNAL_HTTPS_PORT);

                // Act + Assert — the declared-but-unresolvable entry contributed no relay target, so
                // the map stayed empty and no bind was ever attempted
                assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                        "an unresolved alias is skipped, so no bind is attempted");

                // Act + Assert
                assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                        "shutdown is a no-op when the only alias was skipped");
            }
        }
    }

    /**
     * The refusal recorder the producer hands the front listener counts each refusal on the metric
     * series of its own reason. The metric name and the two label values are the documented contract,
     * so they are written out here rather than read from {@link SheriffMetrics}. The two reasons are
     * recorded a different number of times, so a recorder that swapped the mapping, or sent both reasons
     * to one series, would leave a different count on each series than the one asserted.
     */
    @Nested
    @DisplayName("Front refusal recorder")
    class FrontRefusalRecorder {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

        @Test
        @DisplayName("counts a cap refusal under reason=cap and a share refusal under reason=source_share")
        void countsEachRefusalUnderItsOwnReason() {
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, GatewayConfig.builder().version(1).build(),
                    new ResolvedTopology(Map.of()), NO_ROUTES, new SheriffMetrics(registry), HTTP_IDLE_TIMEOUT, 8443,
                    INTERNAL_HTTPS_PORT);

            producer.recordRefusal(SniFrontListener.Refusal.CAP);
            producer.recordRefusal(SniFrontListener.Refusal.SOURCE_SHARE);
            producer.recordRefusal(SniFrontListener.Refusal.SOURCE_SHARE);

            assertAll("each refusal reason lands on its own series of the refusal counter",
                    () -> assertEquals(1.0, refusedCount("cap"), "one cap refusal on reason=cap"),
                    () -> assertEquals(2.0, refusedCount("source_share"),
                            "two share refusals on reason=source_share"),
                    () -> assertEquals(2, registry.find("sheriff_front_connections_refused_total").counters().size(),
                            "the refusal counter carries exactly the two reason series"));
        }

        private double refusedCount(String reason) {
            var counter = registry.find("sheriff_front_connections_refused_total").tag("reason", reason).counter();
            return counter == null ? 0.0 : counter.count();
        }
    }

    /**
     * The produced registry knows the internal terminated listener exactly when a front listener is
     * configured: a connection on that port without a hand-off is unattributed with
     * {@code passthrough_sni} set, and neutral without it. No listener is started here — the producer
     * records the port on construction, before the registry can be handed to the edge.
     */
    @Nested
    @DisplayName("The produced relayed-peer registry")
    class ProducedRegistry {

        private final SocketAddress onTerminated = SocketAddress.inetSocketAddress(INTERNAL_HTTPS_PORT,
                LoopbackHost.ADDRESS);
        private final SocketAddress unregisteredLeg = SocketAddress.inetSocketAddress(50001, LoopbackHost.ADDRESS);

        @Test
        @DisplayName("knows the internal terminated listener when passthrough_sni is set")
        void frontedWhenPassthroughConfigured() {
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(TlsConfig.builder().passthroughSni(Map.of("sni.resolved.example", RESOLVED_ALIAS)).build())
                    .build();
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, new ResolvedTopology(Map.of()), NO_ROUTES,
                    metrics, HTTP_IDLE_TIMEOUT, 8443, INTERNAL_HTTPS_PORT);

            assertEquals(RelayedPeerRegistry.Attribution.UNATTRIBUTED_CONNECTION,
                    producer.relayedPeerRegistry().attribute(onTerminated, unregisteredLeg));
        }

        @Test
        @DisplayName("control: leaves every connection neutral when passthrough_sni is empty")
        void neutralWhenPassthroughUnconfigured() {
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, GatewayConfig.builder().version(1).build(),
                    new ResolvedTopology(Map.of()), NO_ROUTES, metrics, HTTP_IDLE_TIMEOUT, 8443, INTERNAL_HTTPS_PORT);

            assertEquals(RelayedPeerRegistry.Attribution.NONE,
                    producer.relayedPeerRegistry().attribute(onTerminated, unregisteredLeg));
        }
    }

    /**
     * A port no process owns at the moment of the call. The socket is closed before the port is
     * handed back, which is what makes the pre-startup {@code assertFalse(isListening(port))} control
     * meaningful — and is also what makes this inherently a check-then-act: another process may take
     * the released port before the producer binds it.
     * <p>
     * That window cannot be closed for the positive case, which needs a port that is genuinely
     * <em>free</em> so the front listener can really bind it; holding the socket, as the negative
     * cases do, would defeat the very bind being asserted. It is instead narrowed by re-probing the
     * released port and retrying a bounded number of times, so a port that was taken inside the
     * window is discarded rather than handed out.
     * <p>
     * The allocation socket is bound on {@code LoopbackHost.ADDRESS}: it only has to yield a candidate
     * number, and like every other ephemeral listener in this tree it has no reason to be reachable
     * from any other interface. The re-probe is <em>wildcard</em>-bound, and deliberately so. The port
     * handed back is the one {@code SniFrontListener.start()} will bind through the host-bound
     * {@code listen} overload, and the delegating constructor {@code TlsEdgeProducer} uses supplies the
     * Vert.x wildcard default host, so that bind is a wildcard one. Probing loopback alone would answer
     * a narrower question than the one being asked — a port free on the loopback address but already
     * held on another interface would be reported free and then fail the producer's bind. That is why
     * the re-probe is {@link #isFreeForWildcardBind(int)} rather than {@link #isListening(int)}: the
     * retry has to test the same bind scope it is predicting. Nothing dials either socket; each is
     * bound and immediately closed.
     *
     * @return a currently-free localhost port
     * @throws IOException when no free ephemeral port can be allocated within the retry budget
     */
    private static int freePort() throws IOException {
        for (int attempt = 0; attempt < FREE_PORT_ATTEMPTS; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(LoopbackHost.ADDRESS))) {
                candidate = socket.getLocalPort();
            }
            if (isFreeForWildcardBind(candidate)) {
                return candidate;
            }
        }
        throw new IOException(
                "no free localhost port after " + FREE_PORT_ATTEMPTS + " attempts");
    }

    /**
     * Whether {@code port} can be taken by the <em>wildcard</em> bind the producer will perform.
     *
     * <p>Deliberately a bind attempt rather than a loopback connect. {@link #isListening(int)} dials
     * {@code 127.0.0.1}, which answers a narrower question than the one this retry asks: a port free
     * on loopback but already held on another interface passes that probe and then fails
     * {@code SniFrontListener.start()}'s wildcard bind. Attempting the same bind the producer will
     * attempt is the only probe whose scope matches the act it predicts.
     *
     * <p>The bare single-int constructor is the point here, not an oversight — it is the wildcard
     * bind under test. This method is one of the guard's named per-site exemptions for exactly that
     * reason.
     *
     * @param port the candidate port
     * @return {@code true} when a wildcard bind of {@code port} succeeds
     */
    private static boolean isFreeForWildcardBind(int port) {
        try (ServerSocket probe = new ServerSocket(port)) {
            return probe.isBound();
        } catch (IOException _) {
            // A refused bind IS the answer: something holds the port on some interface.
            return false;
        }
    }

    /**
     * Whether something accepts a TCP connection on {@code port}. A refused connection is the
     * observation, not an error, so it is reported as {@code false} rather than raised.
     *
     * @param port the localhost port to probe
     * @return {@code true} when the connection is accepted
     */
    private static boolean isListening(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(LoopbackHost.ADDRESS, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException _) {
            // A refused connection IS the answer: nothing is listening on the probed port.
            return false;
        }
    }

    /**
     * Waits for the public port to stop accepting connections. {@code SniFrontListener.stop()}
     * completes on the Vert.x event loop, so the unbind is a real asynchronous release.
     *
     * @param port    the localhost port that must fall silent
     * @param message surfaced in the timeout diagnostics
     * @throws TimeoutException when the port is still listening at the teardown ceiling
     */
    private static void awaitNotListening(int port, String message) throws TimeoutException {
        Awaits.until(() -> !isListening(port), message, Awaits.TEARDOWN_CEILING_SECONDS);
    }
}
