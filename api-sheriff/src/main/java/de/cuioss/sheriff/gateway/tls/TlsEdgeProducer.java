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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage.GrpcStreamIdleBound;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * The framework-bound boot wiring for the accept-time TLS edge: it builds the immutable
 * SNI → {@code host:port} relay map from {@link GatewayConfig#tls()} and the resolved topology, then
 * — only when {@code tls.passthrough_sni} is non-empty — creates and starts the
 * {@link SniFrontListener} on the managed {@link Vertx}.
 * <p>
 * Each {@code passthrough_sni} entry maps an SNI hostname to a topology alias; the alias is resolved
 * through {@link ResolvedTopology} to a concrete backend endpoint. Per ADR-0009 the passthrough
 * alias resolution is asymmetric — an alias absent from the resolved map means the
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} already failed the boot — so a
 * present entry that unexpectedly fails to resolve is skipped defensively rather than aborting here.
 * <p>
 * When passthrough is unconfigured the front listener is never created: the default single-listener
 * topology (terminated Quarkus HTTPS on the public port) is unchanged, at zero runtime overhead. A
 * bind failure of the front listener fails the boot (fail fast), so the gateway never serves on a
 * half-open TLS edge.
 * <p>
 * <strong>The relay idle timeout is derived, not configured.</strong> Both legs of every relayed
 * connection — the front server's accepted connection and the relay's dial-client connection — carry
 * the same Vert.x idle timeout, derived once at boot by {@link #relayIdleTimeout(Duration, RouteTable)}
 * as the longest idle bound the gateway applies behind the terminated branch plus
 * {@link #RELAY_IDLE_MARGIN}: the longest of {@code quarkus.http.idle-timeout} (default thirty
 * minutes), the gRPC stream-idle bound ({@link GrpcStreamIdleBound#STREAM_IDLE_TIMEOUT_MILLIS}) and
 * the longest effective WebSocket {@code idle_timeout_seconds} across the route table (300 seconds for
 * a route that declares none). A derivation is required rather than a fixed value because
 * {@code idle_timeout_seconds} has no upper bound: only a value computed from the route table can
 * guarantee that the relay never closes a connection before the inner bound would — which would cut
 * off a WebSocket or gRPC stream the route still allows to be idle. The margin lets the inner close
 * (a WebSocket close frame, gRPC trailers, the HTTP idle close) traverse the relay first. No
 * configuration key reaches the value.
 * <p>
 * <strong>The relay's backend dial is bounded.</strong> The relay's dial client carries the fixed
 * {@link #RELAY_CONNECT_TIMEOUT} of ten seconds as its connect timeout, replacing the implicit Vert.x
 * default of sixty seconds. A connection the front has routed keeps its undecided-connection slot until
 * that dial settles, so the dial window counts against the front's cap and is bounded by this value. A
 * dial that has not connected within it fails, and the relay closes the accepted client connection.
 * Like the idle timeout, the connect timeout is not configurable.
 * <p>
 * <strong>Front refusals are counted.</strong> The front listener reports every connection it closes
 * at accept — beyond its undecided-connection cap or beyond a source's share of it — to a recorder
 * this producer wires to {@link SheriffMetrics}, so the refusal rate is visible on
 * {@value SheriffMetrics#FRONT_CONNECTIONS_REFUSED_TOTAL}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TlsEdgeProducer {

    private static final CuiLogger LOGGER = new CuiLogger(TlsEdgeProducer.class);
    private static final String TERMINATED_HOST = "localhost";
    private static final int BIND_TIMEOUT_SECONDS = 15;

    /** How much longer than the longest inner idle bound a relayed leg may stay idle. */
    static final Duration RELAY_IDLE_MARGIN = Duration.ofSeconds(60);

    /**
     * How long the relay's dial client may take to connect to a backend before the dial fails. A
     * routed connection keeps its undecided-connection slot until the dial settles, so this bounds how
     * long the dial phase holds a slot of the front's cap.
     */
    static final Duration RELAY_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final Vertx vertx;
    private final GatewayConfig gatewayConfig;
    private final ResolvedTopology topology;
    private final RouteTable routeTable;
    private final SheriffMetrics metrics;
    private final Duration httpIdleTimeout;
    private final int publicPort;
    private final int internalHttpsPort;
    private final RelayedPeerRegistry relayedPeers = new RelayedPeerRegistry();

    private @Nullable NetClient netClient;
    private @Nullable SniFrontListener listener;

    /**
     * @param vertx             the managed Vert.x instance the front listener and backend client run on
     * @param gatewayConfig     the bound global gateway document (source of {@code tls.passthrough_sni})
     * @param topology          the resolved topology the passthrough aliases are looked up against
     * @param routeTable        the route table whose WebSocket idle timeouts the relay idle timeout must
     *                          not undercut
     * @param metrics           the metrics adapter the front listener's refusals are counted on
     * @param httpIdleTimeout   the idle timeout of the terminated Quarkus HTTPS listener
     *                          ({@code quarkus.http.idle-timeout}), which the relay idle timeout must
     *                          not undercut
     * @param publicPort        the public TLS port the front listener binds when passthrough is active
     * @param internalHttpsPort the internal loopback port the terminated Quarkus HTTPS listener owns
     */
    @Inject
    @SuppressWarnings("java:S107") // CDI constructor injection: each parameter is one injected collaborator or key
    public TlsEdgeProducer(Vertx vertx, GatewayConfig gatewayConfig, ResolvedTopology topology, RouteTable routeTable,
            SheriffMetrics metrics,
            @ConfigProperty(name = "quarkus.http.idle-timeout", defaultValue = "30M") Duration httpIdleTimeout,
            @ConfigProperty(name = "sheriff.tls.public-port", defaultValue = "8443") int publicPort,
            @ConfigProperty(name = "sheriff.tls.internal-https-port", defaultValue = "8444") int internalHttpsPort) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.routeTable = Objects.requireNonNull(routeTable, "routeTable");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.httpIdleTimeout = Objects.requireNonNull(httpIdleTimeout, "httpIdleTimeout");
        this.publicPort = publicPort;
        this.internalHttpsPort = internalHttpsPort;
        if (passthroughConfigured(gatewayConfig)) {
            // Recorded before the registry is produced, so no request can reach the edge while the
            // internal terminated listener is still unknown to it.
            relayedPeers.frontTerminatedListener(internalHttpsPort);
        }
    }

    /**
     * The runtime's one relayed-peer registry: the front listener's terminated relay registers into it
     * and the edge looks a request's connection up in it. Produced whether or not the front listener
     * runs; without it the registry stays empty and every lookup misses. With
     * {@code tls.passthrough_sni} set it also knows the internal terminated listener's port, so a
     * request without a hand-off can be reported unattributed, as
     * {@link RelayedPeerRegistry#attribute} states.
     *
     * @return the registry shared by the terminated relay and the edge
     */
    @Produces
    @Singleton // NOSONAR java:S9068 RelayedPeerRegistry is final, a client proxy is neither possible nor needed
    RelayedPeerRegistry relayedPeerRegistry() {
        return relayedPeers;
    }

    /**
     * Builds the relay map and, when passthrough is configured, derives the relay idle timeout and
     * starts the front listener — blocking the boot until the public port is bound so a bind failure
     * aborts startup.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        Map<String, RelayTarget> targets = buildRelayMap();
        if (targets.isEmpty()) {
            LOGGER.debug("tls.passthrough_sni is empty — accept-time SNI front listener not started");
            return;
        }
        Duration relayIdleTimeout = relayIdleTimeout(httpIdleTimeout, routeTable);
        LOGGER.debug("Relay idle timeout derived as %s", relayIdleTimeout);
        this.netClient = vertx.createNetClient(relayClientOptions(relayIdleTimeout));
        PassthroughRelay relay = new PassthroughRelay(netClient, relayedPeers);
        RelayTarget terminatedTarget = new RelayTarget(TERMINATED_HOST, internalHttpsPort);
        SniFrontListener front = new SniFrontListener(vertx, relay, targets, terminatedTarget, publicPort,
                relayIdleTimeout, this::recordRefusal);
        this.listener = front;
        awaitBind(front);
    }

    /**
     * Counts one connection the front listener refused at accept on the metric for its reason.
     *
     * @param refusal why the front closed the connection
     */
    private void recordRefusal(SniFrontListener.Refusal refusal) {
        switch (refusal) {
            case CAP -> metrics.recordFrontConnectionRefusedAtCap();
            case SOURCE_SHARE -> metrics.recordFrontConnectionRefusedAtSourceShare();
        }
    }

    /**
     * Derives the idle timeout of both relay legs: the longest idle bound the gateway applies behind
     * the terminated branch, plus {@link #RELAY_IDLE_MARGIN}. The bounds compared are
     * {@code httpIdleTimeout}, the gRPC stream-idle bound
     * {@link GrpcStreamIdleBound#STREAM_IDLE_TIMEOUT_MILLIS}, and the longest effective WebSocket idle
     * timeout across {@code routeTable}. The route table materializes that timeout on every WebSocket
     * route, the 300-second default included for a route that declares none, and leaves it absent on
     * every other route. The result is therefore never shorter than any of them.
     *
     * @param httpIdleTimeout the idle timeout of the terminated Quarkus HTTPS listener
     * @param routeTable      the route table whose WebSocket routes are compared
     * @return the relay idle timeout
     */
    static Duration relayIdleTimeout(Duration httpIdleTimeout, RouteTable routeTable) {
        long longestWebSocketIdleSeconds = routeTable.routes().stream()
                .map(Route::effectiveWebSocketIdleTimeoutSeconds)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .max()
                .orElse(0L);
        Duration longestInnerBound = httpIdleTimeout;
        Duration grpcStreamIdle = Duration.ofMillis(GrpcStreamIdleBound.STREAM_IDLE_TIMEOUT_MILLIS);
        if (grpcStreamIdle.compareTo(longestInnerBound) > 0) {
            longestInnerBound = grpcStreamIdle;
        }
        Duration webSocketIdle = Duration.ofSeconds(longestWebSocketIdleSeconds);
        if (webSocketIdle.compareTo(longestInnerBound) > 0) {
            longestInnerBound = webSocketIdle;
        }
        return longestInnerBound.plus(RELAY_IDLE_MARGIN);
    }

    /**
     * The options of the relay's dial client: the relay idle timeout, set exactly as the front server
     * sets it ({@link SniFrontListener#applyIdleTimeout}), so both legs of a relayed connection carry
     * the same bound, and the fixed {@link #RELAY_CONNECT_TIMEOUT} as the connect timeout.
     *
     * @param relayIdleTimeout the derived relay idle timeout; positive
     * @return the dial-client options carrying that idle timeout and the fixed connect timeout
     */
    static NetClientOptions relayClientOptions(Duration relayIdleTimeout) {
        return relayClientOptions(relayIdleTimeout, RELAY_CONNECT_TIMEOUT);
    }

    /**
     * The options of the relay's dial client with an explicit connect timeout, so a test can shorten
     * the dial bound instead of waiting for {@link #RELAY_CONNECT_TIMEOUT}. Production uses
     * {@link #relayClientOptions(Duration)}.
     *
     * @param relayIdleTimeout the derived relay idle timeout; positive
     * @param connectTimeout   how long a backend dial may take to connect; at least one millisecond
     *                         and at most {@link Integer#MAX_VALUE} milliseconds
     * @return the dial-client options carrying both timeouts
     * @throws IllegalArgumentException when {@code connectTimeout} is outside that range — Vert.x takes
     *                                  whole {@code int} milliseconds, and zero would mean no bound
     */
    static NetClientOptions relayClientOptions(Duration relayIdleTimeout, Duration connectTimeout) {
        long connectTimeoutMillis = Objects.requireNonNull(connectTimeout, "connectTimeout").toMillis();
        if (connectTimeoutMillis < 1 || connectTimeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("connect timeout out of range: " + connectTimeout);
        }
        NetClientOptions clientOptions = new NetClientOptions();
        SniFrontListener.applyIdleTimeout(clientOptions, relayIdleTimeout);
        clientOptions.setConnectTimeout((int) connectTimeoutMillis);
        return clientOptions;
    }

    /**
     * Stops the front listener and closes the backend client on shutdown.
     *
     * @param event the Quarkus shutdown event
     */
    void onShutdown(@Observes ShutdownEvent event) {
        if (listener != null) {
            listener.stop();
        }
        if (netClient != null) {
            netClient.close();
        }
    }

    /**
     * Whether {@code tls.passthrough_sni} names any host — the condition the front listener starts
     * under. A validated configuration resolves every entry (ADR-0009), so this agrees with a
     * non-empty {@link #buildRelayMap()}; where it would not, the registry errs towards reporting a
     * terminated-listener connection unattributed.
     */
    private static boolean passthroughConfigured(GatewayConfig gatewayConfig) {
        TlsConfig tls = gatewayConfig.tls();
        return tls != null && !tls.passthroughSni().isEmpty();
    }

    private Map<String, RelayTarget> buildRelayMap() {
        TlsConfig tls = gatewayConfig.tls();
        Map<String, String> passthrough = tls == null ? Map.of() : tls.passthroughSni();
        Map<String, RelayTarget> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : passthrough.entrySet()) {
            String sni = entry.getKey();
            String alias = entry.getValue();
            Optional<ResolvedUpstream> resolved = topology.lookup(alias);
            if (resolved.isEmpty()) {
                // ADR-0009: an unresolved passthrough alias means the validator already failed the
                // boot; this defensive skip never fires in a validated configuration.
                LOGGER.debug("Passthrough alias '%s' for SNI '%s' is unresolved — skipping", alias, sni);
                continue;
            }
            ResolvedUpstream upstream = resolved.get();
            targets.put(SniFrontListener.normalizeSni(sni), new RelayTarget(upstream.host(), upstream.port()));
        }
        return Map.copyOf(targets);
    }

    private static void awaitBind(SniFrontListener front) {
        try {
            front.start().toCompletionStage().toCompletableFuture().get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding the TLS front listener", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Refusing to start — TLS front listener bind failed", e);
        }
    }
}
