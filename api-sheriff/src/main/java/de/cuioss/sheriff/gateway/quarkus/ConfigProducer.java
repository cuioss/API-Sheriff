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
package de.cuioss.sheriff.gateway.quarkus;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Metadata;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.edge.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The framework-bound producer that assembles the file-based configuration once,
 * at boot, and exposes the immutable result as CDI beans.
 * <p>
 * It runs the framework-agnostic boot pipeline through the shared
 * {@link ConfigBootPipeline} seam — load, endpoint-enablement filter, topology resolution,
 * semantic validation, route-table assembly — the same seam the offline configuration
 * check runs, so the boot and that check cannot reach different verdicts. The producer
 * supplies the two collaborators only it owns: the {@link EnvSecretResolver} and the
 * Vert.x {@code quarkus.http.limits.max-body-size} ceiling (ADR-0023), against which the
 * seam checks the largest declared {@code max_body_bytes}, because the framework rejects an
 * over-ceiling request in a root handler in front of the gateway router and the declared
 * cap would never be reached. Every refusal of the pipeline — a loader, topology,
 * validation, body-limit or route-table violation — arrives as one violation entry and
 * aborts through one path: the producer logs every problem through structured ERROR
 * {@link ConfigLogMessages} records and throws, so Quarkus exits non-zero and never
 * serves on partial configuration. On success it emits the {@code CONFIG_LOADED}
 * INFO record carrying the audit {@code config_version} and publishes the bound
 * {@link GatewayConfig}, the assembled {@link RouteTable}, the {@link ResolvedTopology},
 * the resolved {@link EdgeHardeningOptions} admission budget and the application portal's
 * {@link PortalCatalog} — derived from the same enabled endpoint list as the route table — as beans.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ConfigProducer {

    private static final CuiLogger LOGGER = new CuiLogger(ConfigProducer.class);

    @ConfigProperty(name = "sheriff.config.dir", defaultValue = "config")
    String configDir;

    /**
     * The Vert.x request-body ceiling the framework enforces in a root handler in front of the
     * gateway router. Injected here rather than in {@code ConfigValidator}, which runs on the
     * pre-boot path and carries no framework dependency (ADR-0062), so Quarkus-key knowledge stays
     * at this seam.
     */
    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize frameworkBodyLimit;

    /**
     * The placeholder substitution engine the boot pipeline resolves {@code ${VAR}} references
     * through — the process environment by default. Package-private so a test can supply a fixed
     * lookup and prove the boot and the offline check resolve through the same engine.
     */
    EnvSecretResolver secretResolver = new EnvSecretResolver();

    private GatewayConfig gateway;
    private RouteTable routeTable;
    private ResolvedTopology resolvedTopology;
    private PortalCatalog portalCatalog;
    private boolean built;

    /**
     * Forces eager assembly at boot so an invalid configuration fails startup
     * before any request is served.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        buildOnce();
    }

    /**
     * Produces the bound global gateway document.
     * <p>
     * {@link Singleton} (a pseudo-scope, no client proxy) because
     * {@link GatewayConfig} is a {@code record}: ArC cannot subclass a final type to
     * build the proxy a normal scope such as {@code @ApplicationScoped} would require.
     * The bean is immutable and assembled once at boot, so a single instance is exact.
     *
     * @return the immutable, validated {@link GatewayConfig}
     */
    @Produces
    @Singleton
    public GatewayConfig gatewayConfig() {
        buildOnce();
        return gateway;
    }

    /**
     * Produces the assembled route table.
     * <p>
     * {@link Singleton} (a pseudo-scope, no client proxy) because {@link RouteTable}
     * is a {@code record}: ArC cannot subclass a final type to build the proxy a
     * normal scope such as {@code @ApplicationScoped} would require. The bean is
     * immutable and assembled once at boot, so a single instance is exact.
     *
     * @return the immutable, exact-first then longest-prefix-ordered {@link RouteTable}
     */
    @Produces
    @Singleton
    public RouteTable routeTable() {
        buildOnce();
        return routeTable;
    }

    /**
     * Produces the immutable, fully-resolved topology assembled at boot: every topology alias
     * declared as an enabled endpoint's {@code base_url} (an endpoint without proxy routes may
     * declare none), referenced by a {@code source: upstream} asset route, or named as a
     * {@code tls.passthrough_sni} target, decomposed into its
     * upstream endpoint. Published (rather than recomputed) so {@code tls.TlsEdgeProducer} can build
     * the accept-time SNI relay map from the same resolved data the validator already accepted.
     * <p>
     * {@link Singleton} (a pseudo-scope, no client proxy) because {@link ResolvedTopology} is a
     * {@code record}: ArC cannot subclass a final type to build the proxy a normal scope such as
     * {@code @ApplicationScoped} would require. The bean is immutable and assembled once at boot, so
     * a single instance is exact.
     *
     * @return the immutable {@link ResolvedTopology}
     */
    @Produces
    @Singleton
    public ResolvedTopology resolvedTopology() {
        buildOnce();
        return resolvedTopology;
    }

    /**
     * Produces the application portal's catalog: the ordered overview entries of every enabled
     * endpoint declaring an {@code endpoint.catalog} block.
     * <p>
     * Built from exactly the enabled endpoint list the {@link RouteTable} is built from — after
     * placeholder resolution — so an endpoint switched off through its {@code enabled} placeholder
     * ({@code ENDPOINT_<ID>_ENABLED}) is absent from the portal as it is from the route table.
     * {@link Singleton} (a pseudo-scope, no client proxy) because {@link PortalCatalog} is a
     * {@code record}; the bean is immutable and assembled once at boot.
     *
     * @return the immutable {@link PortalCatalog}, empty when no enabled endpoint declares a catalog
     */
    @Produces
    @Singleton
    public PortalCatalog portalCatalog() {
        buildOnce();
        return portalCatalog;
    }

    /**
     * Produces the edge's transport bounds and admission budget, resolving the two operator-facing
     * caps from the {@code edge_hardening} block and falling back to
     * {@link EdgeHardeningConfig#defaults()} when the block is absent.
     * <p>
     * Produced here rather than declared as a bean on the class itself so the whole admission budget
     * comes from the single boot-time assembly this producer guards — one configuration entry point,
     * not two. {@code ApplicationScoped} is exact: {@link EdgeHardeningOptions} is a non-final class,
     * so ArC can build the client proxy a normal scope requires, and the bean also carries
     * {@code HttpServerOptionsCustomizer} in its bean types so the transport-customizer SPI still
     * discovers it.
     *
     * @return the immutable {@link EdgeHardeningOptions} for this deployment
     */
    @Produces
    @ApplicationScoped
    public EdgeHardeningOptions edgeHardeningOptions() {
        buildOnce();
        EdgeHardeningConfig declared = gateway.edgeHardening();
        return new EdgeHardeningOptions(declared == null ? EdgeHardeningConfig.defaults() : declared);
    }

    private synchronized void buildOnce() {
        if (built) {
            return;
        }
        ConfigBootPipeline.Outcome outcome = new ConfigBootPipeline(secretResolver)
                .run(Path.of(configDir), frameworkBodyLimit.asLongValue());
        ConfigBootPipeline.Assembly assembly = outcome.assembly();
        if (assembly == null) {
            throw abort(outcome.violations());
        }
        this.gateway = assembly.gateway();
        this.resolvedTopology = assembly.topology();
        this.routeTable = assembly.routeTable();
        this.portalCatalog = PortalCatalog.from(assembly.enabledEndpoints());
        this.built = true;
        LOGGER.info(ConfigLogMessages.INFO.CONFIG_LOADED, configVersion(gateway));
    }

    /**
     * Logs every violation as one {@code CONFIG_VALIDATION_FAILED} record followed by the
     * {@code CONFIG_STARTUP_ABORTED} summary, and returns the exception that refuses the boot.
     *
     * @param violations the non-empty violations of the boot pipeline
     * @return the exception the caller throws to refuse the boot
     */
    private static IllegalStateException abort(List<ConfigError> violations) {
        for (ConfigError violation : violations) {
            LOGGER.error(ConfigLogMessages.ERROR.CONFIG_VALIDATION_FAILED, violation.file(), violation.pointer(),
                    violation.message());
        }
        String summary = "%d configuration violation(s)".formatted(violations.size());
        LOGGER.error(ConfigLogMessages.ERROR.CONFIG_STARTUP_ABORTED, summary);
        return new IllegalStateException("Refusing to start — " + summary);
    }

    private static String configVersion(GatewayConfig gateway) {
        Metadata metadata = gateway.metadata();
        return Objects.requireNonNullElse(metadata == null ? null : metadata.configVersion(), "unversioned");
    }
}
