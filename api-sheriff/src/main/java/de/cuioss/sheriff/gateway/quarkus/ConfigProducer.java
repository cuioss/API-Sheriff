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
package de.cuioss.sheriff.gateway.quarkus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.ConfigLoadException;
import de.cuioss.sheriff.gateway.config.load.ConfigLoader;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Metadata;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.config.topology.TopologyResolver;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.sheriff.gateway.edge.EdgeHardeningOptions;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The framework-bound edge (ADR-0005 seam) that assembles the file-based
 * configuration once, at boot, and exposes the immutable result as CDI beans.
 * <p>
 * It drives the framework-agnostic boot pipeline — {@link ConfigLoader} (read,
 * schema-validate, secret-resolve, bind) → endpoint-enablement filter →
 * {@link TopologyResolver} → {@link ConfigValidator} → {@link RouteTableBuilder} —
 * supplying every collaborator by construction. It additionally applies one check the
 * framework-agnostic validator cannot: the largest declared {@code max_body_bytes}
 * across the anchors and the enabled endpoints' routes must not exceed the Vert.x
 * {@code quarkus.http.limits.max-body-size} ceiling, because the framework rejects an
 * over-ceiling request in a root handler in front of the gateway router and the declared
 * cap would never be reached. That violation joins the validator's own collection and
 * aborts through the same path. On the first collected violation
 * the producer logs every problem through structured ERROR
 * {@link ConfigLogMessages} records and throws, so Quarkus exits non-zero and never
 * serves on partial configuration. On success it emits the {@code CONFIG_LOADED}
 * INFO record carrying the audit {@code config_version} and publishes the bound
 * {@link GatewayConfig}, the assembled {@link RouteTable}, the {@link ResolvedTopology}
 * and the resolved {@link EdgeHardeningOptions} admission budget as beans.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ConfigProducer {

    private static final CuiLogger LOGGER = new CuiLogger(ConfigProducer.class);
    private static final String TOPOLOGY_FILE = "topology.properties";

    @ConfigProperty(name = "sheriff.config.dir", defaultValue = "config")
    String configDir;

    /**
     * The Vert.x request-body ceiling the framework enforces in a root handler in front of the
     * gateway router. Injected here — the ADR-0005 framework-bound edge — rather than in the
     * framework-agnostic {@code ConfigValidator}, so Quarkus-key knowledge stays at this seam.
     */
    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize frameworkBodyLimit;

    private GatewayConfig gateway;
    private RouteTable routeTable;
    private ResolvedTopology resolvedTopology;
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
     * @return the immutable, longest-prefix-ordered {@link RouteTable}
     */
    @Produces
    @Singleton
    public RouteTable routeTable() {
        buildOnce();
        return routeTable;
    }

    /**
     * Produces the immutable, fully-resolved topology assembled at boot: every topology alias
     * referenced by an enabled endpoint or a {@code tls.passthrough_sni} target, decomposed into its
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
        return new EdgeHardeningOptions(
                Objects.requireNonNullElseGet(gateway.edgeHardening(), EdgeHardeningConfig::defaults));
    }

    private synchronized void buildOnce() {
        if (built) {
            return;
        }
        Path directory = Path.of(configDir);
        try {
            EnvSecretResolver resolver = new EnvSecretResolver();
            ConfigLoader.LoadedConfig loaded = new ConfigLoader(directory, resolver).load();
            List<EndpointConfig> enabled = loaded.endpoints().stream().filter(EndpointConfig::enabled).toList();
            ResolvedTopology topology = new TopologyResolver(resolver)
                    .resolve(directory.resolve(TOPOLOGY_FILE), enabled, additionalTopologyAliases(loaded, enabled));
            List<ConfigError> violations = new ArrayList<>(
                    new ConfigValidator().validate(loaded.gateway(), enabled, topology));
            violations.addAll(frameworkBodyLimitViolations(loaded.gateway(), enabled));
            if (!violations.isEmpty()) {
                abort(violations);
            }
            this.gateway = loaded.gateway();
            this.resolvedTopology = topology;
            this.routeTable = new RouteTableBuilder().build(loaded.gateway(), enabled, topology);
            this.built = true;
            LOGGER.info(ConfigLogMessages.INFO.CONFIG_LOADED, configVersion(gateway));
        } catch (ConfigLoadException e) {
            abort(e.errors());
        } catch (TopologyResolver.TopologyResolutionException | RouteTableBuilder.RouteTableException e) {
            LOGGER.error(e, ConfigLogMessages.ERROR.CONFIG_STARTUP_ABORTED, e.getMessage());
            throw new IllegalStateException("Refusing to start — configuration is invalid", e);
        }
    }

    /**
     * The fail-closed framework-limit check: a declared per-route body cap above the Vert.x ceiling
     * is unreachable, because Quarkus rejects the request in a root handler installed in front of the
     * gateway router. Rather than let that mismatch stay silent, the boot refuses to start and names
     * the key the operator must raise.
     *
     * @param gateway the bound gateway document (source of the anchor-level caps)
     * @param enabled the enabled endpoints whose routes carry the route-level caps
     * @return the single violation when the largest declared cap exceeds the framework limit,
     *         otherwise an empty list
     */
    private List<ConfigError> frameworkBodyLimitViolations(GatewayConfig gateway, List<EndpointConfig> enabled) {
        long limit = frameworkBodyLimit.asLongValue();
        long declared = maxDeclaredBodyBytes(gateway, enabled);
        if (declared <= limit) {
            return List.of();
        }
        return List.of(new ConfigError("application.properties", "quarkus.http.limits.max-body-size",
                "%d exceeds framework limit %d; raise quarkus.http.limits.max-body-size to at least %d"
                        .formatted(declared, limit, declared)));
    }

    /**
     * The largest {@code max_body_bytes} declared anywhere in the configuration set — across the
     * named policy anchors and every enabled endpoint's routes, the only two places a per-route cap
     * can be declared.
     *
     * @param gateway the bound gateway document
     * @param enabled the enabled endpoints
     * @return the maximum declared cap in bytes, or {@code 0} when no cap is declared
     */
    private static long maxDeclaredBodyBytes(GatewayConfig gateway, List<EndpointConfig> enabled) {
        return Stream.concat(
                gateway.anchors().values().stream().map(AnchorConfig::securityFilter),
                enabled.stream().flatMap(endpoint -> endpoint.routes().stream())
                        .map(RouteConfig::securityFilter))
                .filter(Objects::nonNull)
                .map(SecurityFilterConfig::maxBodyBytes)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .max()
                .orElse(0L);
    }

    /**
     * The topology aliases that must resolve independently of any enabled endpoint's
     * {@code base_url}: the {@code tls.passthrough_sni} relay targets and every
     * {@code source: upstream} asset route's upstream alias (ADR-0014). An asset route's
     * upstream is a per-route topology reference that the proxy-oriented {@code base_url}
     * collection in {@link TopologyResolver} does not see, so it is gathered here and
     * passed alongside the passthrough targets, otherwise the {@link ConfigValidator} and
     * {@link RouteTableBuilder} asset-source lookup would reject a well-formed
     * {@code /assets/cdn}-style upstream asset route as unresolved.
     *
     * @param loaded  the loaded gateway configuration (source of the passthrough targets)
     * @param enabled the enabled endpoints whose asset routes are scanned for upstream aliases
     * @return the deduplicated, insertion-ordered set of additional aliases to resolve
     */
    private static Set<String> additionalTopologyAliases(ConfigLoader.LoadedConfig loaded,
            List<EndpointConfig> enabled) {
        Set<String> aliases = new LinkedHashSet<>();
        TlsConfig tls = loaded.gateway().tls();
        if (tls != null) {
            aliases.addAll(tls.passthroughSni().values());
        }
        for (EndpointConfig endpoint : enabled) {
            for (RouteConfig route : endpoint.routes()) {
                AssetConfig asset = route.asset();
                if (asset == null || asset.source() != AssetConfig.Source.UPSTREAM) {
                    continue;
                }
                String alias = asset.upstream();
                if (alias != null) {
                    aliases.add(alias);
                }
            }
        }
        return aliases;
    }

    private static void abort(List<ConfigError> violations) {
        for (ConfigError violation : violations) {
            LOGGER.error(ConfigLogMessages.ERROR.CONFIG_VALIDATION_FAILED, violation.file(), violation.pointer(),
                    violation.message());
        }
        String summary = "%d configuration violation(s)".formatted(violations.size());
        LOGGER.error(ConfigLogMessages.ERROR.CONFIG_STARTUP_ABORTED, summary);
        throw new IllegalStateException("Refusing to start — " + summary);
    }

    private static String configVersion(GatewayConfig gateway) {
        Metadata metadata = gateway.metadata();
        return Objects.requireNonNullElse(metadata == null ? null : metadata.configVersion(), "unversioned");
    }
}
