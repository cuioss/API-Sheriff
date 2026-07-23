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
package de.cuioss.sheriff.gateway.tls;

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
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TlsEdgeProducer {

    private static final CuiLogger LOGGER = new CuiLogger(TlsEdgeProducer.class);
    private static final String TERMINATED_HOST = "localhost";
    private static final int BIND_TIMEOUT_SECONDS = 15;

    private final Vertx vertx;
    private final GatewayConfig gatewayConfig;
    private final ResolvedTopology topology;
    private final int publicPort;
    private final int internalHttpsPort;

    private @Nullable NetClient netClient;
    private @Nullable SniFrontListener listener;

    /**
     * @param vertx             the managed Vert.x instance the front listener and backend client run on
     * @param gatewayConfig     the bound global gateway document (source of {@code tls.passthrough_sni})
     * @param topology          the resolved topology the passthrough aliases are looked up against
     * @param publicPort        the public TLS port the front listener binds when passthrough is active
     * @param internalHttpsPort the internal loopback port the terminated Quarkus HTTPS listener owns
     */
    @Inject
    public TlsEdgeProducer(Vertx vertx, GatewayConfig gatewayConfig, ResolvedTopology topology,
            @ConfigProperty(name = "sheriff.tls.public-port", defaultValue = "8443") int publicPort,
            @ConfigProperty(name = "sheriff.tls.internal-https-port", defaultValue = "8444") int internalHttpsPort) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.publicPort = publicPort;
        this.internalHttpsPort = internalHttpsPort;
    }

    /**
     * Builds the relay map and, when passthrough is configured, starts the front listener — blocking
     * the boot until the public port is bound so a bind failure aborts startup.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        Map<String, RelayTarget> targets = buildRelayMap();
        if (targets.isEmpty()) {
            LOGGER.debug("tls.passthrough_sni is empty — accept-time SNI front listener not started");
            return;
        }
        this.netClient = vertx.createNetClient();
        PassthroughRelay relay = new PassthroughRelay(netClient);
        RelayTarget terminatedTarget = new RelayTarget(TERMINATED_HOST, internalHttpsPort);
        SniFrontListener front = new SniFrontListener(vertx, relay, targets, terminatedTarget, publicPort);
        this.listener = front;
        awaitBind(front);
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

    private Map<String, RelayTarget> buildRelayMap() {
        Map<String, String> passthrough = gatewayConfig.tls().map(TlsConfig::passthroughSni).orElse(Map.of());
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
