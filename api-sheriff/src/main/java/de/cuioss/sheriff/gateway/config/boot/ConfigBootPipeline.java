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
package de.cuioss.sheriff.gateway.config.boot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.ConfigLoadException;
import de.cuioss.sheriff.gateway.config.load.ConfigLoader;
import de.cuioss.sheriff.gateway.config.load.DefaultedPlaceholder;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.config.topology.TopologyResolver;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import org.jspecify.annotations.Nullable;

/**
 * The single boot configuration pipeline, shared by the gateway boot and the offline
 * configuration check so the two verdicts cannot drift.
 * <p>
 * {@link #run(Path, Long)} runs the boot's framework-agnostic stages in boot order:
 * <ol>
 * <li>{@link ConfigLoader} — read, schema-validate, resolve placeholders, bind;</li>
 * <li>the endpoint-enablement filter;</li>
 * <li>{@link TopologyResolver} — the enabled endpoints' {@code base_url} aliases plus the
 * additional aliases ({@code tls.passthrough_sni} targets and {@code source: upstream} asset
 * aliases);</li>
 * <li>{@link ConfigValidator} together with the framework body-limit check;</li>
 * <li>{@link RouteTableBuilder} — only when no violation was collected.</li>
 * </ol>
 * A loader or topology failure stops the run at that stage, exactly as the boot does; its
 * errors become the {@link Outcome#violations()}. A route-table assembly failure becomes one
 * violation whose file names the {@code route table} stage. Nothing is thrown for a
 * configuration defect and nothing is logged — reporting is the caller's concern, so the boot
 * and the offline check can each render the same verdict their own way.
 * <p>
 * Every {@code ${VAR:-default}} that fell back to its in-file default is collected, in stage
 * order (loader, then topology), from every stage that ran — including when a later stage
 * failed — as {@link Outcome#defaulted()}.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> The class carries no framework import:
 * the {@link EnvSecretResolver} is constructor-injected, and the one framework value the run
 * needs — the HTTP request-body ceiling ({@code quarkus.http.limits.max-body-size}) — is passed
 * in by the caller, which owns the framework-key knowledge (ADR-0023). A caller that has no such
 * ceiling passes {@code null}; the check is then reported as not checked rather than passed.
 * <p>
 * <strong>Thread safety.</strong> Instances hold only the immutable resolver and are stateless
 * between runs; concurrent {@link #run(Path, Long)} calls are safe.
 * <p>
 * Usage:
 * {@snippet :
 * ConfigBootPipeline.Outcome outcome = new ConfigBootPipeline(new EnvSecretResolver())
 *         .run(Path.of("config"), 10_485_760L);
 * if (outcome.violations().isEmpty()) {
 *     RouteTable routeTable = Objects.requireNonNull(outcome.assembly()).routeTable();
 * }
 * }
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigBootPipeline {

    /** The framework key whose ceiling the body-limit check compares the declared caps against. */
    public static final String FRAMEWORK_BODY_LIMIT_KEY = "quarkus.http.limits.max-body-size";

    private static final String TOPOLOGY_FILE = "topology.properties";
    private static final String FRAMEWORK_CONFIG_FILE = "application.properties";
    private static final String ROUTE_TABLE_STAGE = "route table";

    private final EnvSecretResolver secretResolver;

    /**
     * Creates a pipeline resolving placeholders through the supplied engine.
     *
     * @param secretResolver the {@code ${VAR}} / {@code ${VAR:-default}} substitution engine used
     *                       by every stage
     */
    public ConfigBootPipeline(EnvSecretResolver secretResolver) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
    }

    /**
     * Runs the boot configuration pipeline over a configuration directory.
     *
     * @param configDir               the directory holding {@code gateway.yaml}, the
     *                                {@code endpoints/} subdirectory and
     *                                {@code topology.properties}
     * @param frameworkBodyLimitBytes the framework request-body ceiling in bytes, or {@code null}
     *                                when no framework ceiling is known — the body-limit check is
     *                                then skipped and named in {@link Outcome#notChecked()}
     * @return the outcome; its {@link Outcome#assembly()} is present exactly when
     *         {@link Outcome#violations()} is empty
     */
    public Outcome run(Path configDir, @Nullable Long frameworkBodyLimitBytes) {
        Objects.requireNonNull(configDir, "configDir");
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();

        ConfigLoader.LoadedConfig loaded;
        try {
            loaded = new ConfigLoader(configDir, secretResolver).load(defaulted::add);
        } catch (ConfigLoadException e) {
            return new Outcome(e.errors(), defaulted, notChecked, null);
        }
        GatewayConfig gateway = loaded.gateway();
        List<EndpointConfig> enabled = loaded.endpoints().stream().filter(EndpointConfig::enabled).toList();

        ResolvedTopology topology;
        try {
            topology = new TopologyResolver(secretResolver).resolve(configDir.resolve(TOPOLOGY_FILE), enabled,
                    additionalTopologyAliases(gateway, enabled), defaulted::add);
        } catch (TopologyResolver.TopologyResolutionException e) {
            return new Outcome(e.errors(), defaulted, notChecked, null);
        }

        List<ConfigError> violations = new ArrayList<>(new ConfigValidator().validate(gateway, enabled, topology));
        if (frameworkBodyLimitBytes == null) {
            notChecked.add(FRAMEWORK_BODY_LIMIT_KEY);
        } else {
            violations.addAll(frameworkBodyLimitViolations(gateway, enabled, frameworkBodyLimitBytes));
        }
        if (!violations.isEmpty()) {
            return new Outcome(violations, defaulted, notChecked, null);
        }

        RouteTable routeTable;
        try {
            routeTable = new RouteTableBuilder().build(gateway, enabled, topology);
        } catch (RouteTableBuilder.RouteTableException e) {
            return new Outcome(List.of(new ConfigError(ROUTE_TABLE_STAGE, "", Objects.toString(e.getMessage(),
                    e.getClass().getSimpleName()))), defaulted, notChecked, null);
        }
        return new Outcome(List.of(), defaulted, notChecked, new Assembly(gateway, enabled, topology, routeTable));
    }

    /**
     * The fail-closed framework-limit check: a declared per-route body cap above the framework
     * ceiling is unreachable, because the framework rejects the request in a root handler installed
     * in front of the gateway router. Rather than let that mismatch stay silent, the boot refuses to
     * start and names the key the operator must raise.
     *
     * @param gateway the bound gateway document (source of the anchor-level caps)
     * @param enabled the enabled endpoints whose routes carry the route-level caps
     * @param limit   the framework request-body ceiling in bytes
     * @return the single violation when the largest declared cap exceeds the framework limit,
     *         otherwise an empty list
     */
    private static List<ConfigError> frameworkBodyLimitViolations(GatewayConfig gateway,
            List<EndpointConfig> enabled, long limit) {
        long declared = maxDeclaredBodyBytes(gateway, enabled);
        if (declared <= limit) {
            return List.of();
        }
        return List.of(new ConfigError(FRAMEWORK_CONFIG_FILE, FRAMEWORK_BODY_LIMIT_KEY,
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
     * collection in {@link TopologyResolver} does not see — and its endpoint may declare no
     * {@code base_url} at all, since {@code base_url} is mandatory only for an endpoint carrying a
     * proxy route — so it is gathered here and passed alongside the passthrough targets, otherwise
     * the {@link ConfigValidator} and {@link RouteTableBuilder} asset-source lookup would reject a
     * well-formed {@code /assets/cdn}-style upstream asset route as unresolved.
     *
     * @param gateway the bound gateway document (source of the passthrough targets)
     * @param enabled the enabled endpoints whose asset routes are scanned for upstream aliases
     * @return the deduplicated, insertion-ordered set of additional aliases to resolve
     */
    private static Set<String> additionalTopologyAliases(GatewayConfig gateway, List<EndpointConfig> enabled) {
        Set<String> aliases = new LinkedHashSet<>();
        TlsConfig tls = gateway.tls();
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

    /**
     * The result of one {@link ConfigBootPipeline#run(Path, Long)} call.
     *
     * @param violations every configuration violation of the stage that stopped the run, or of the
     *                   validation stage; empty when the configuration is valid
     * @param defaulted  every {@code ${VAR:-default}} that fell back to its in-file default, in stage
     *                   order, from every stage that ran — never a value
     * @param notChecked the boot refusals this run could not evaluate (a framework key whose value
     *                   was not supplied); empty when every check ran
     * @param assembly   the assembled configuration, present exactly when {@code violations} is empty
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Outcome(List<ConfigError> violations, List<DefaultedPlaceholder> defaulted,
            List<String> notChecked, @Nullable Assembly assembly) {

        /**
         * Canonical constructor copying every list defensively and enforcing that an assembly is
         * present exactly when no violation was collected.
         */
        public Outcome {
            violations = List.copyOf(violations);
            defaulted = List.copyOf(defaulted);
            notChecked = List.copyOf(notChecked);
            if (violations.isEmpty() == (assembly == null)) {
                throw new IllegalArgumentException("an assembly is present exactly when violations is empty");
            }
        }
    }

    /**
     * The configuration a successful run assembled — the inputs the boot publishes as beans.
     *
     * @param gateway          the bound global {@code gateway.yaml} document
     * @param enabledEndpoints the enabled endpoints, in deterministic (file-sorted) order
     * @param topology         the resolved topology
     * @param routeTable       the assembled route table
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Assembly(GatewayConfig gateway, List<EndpointConfig> enabledEndpoints, ResolvedTopology topology,
            RouteTable routeTable) {

        /**
         * Canonical constructor requiring every component and copying the endpoint list.
         */
        public Assembly {
            Objects.requireNonNull(gateway, "gateway");
            enabledEndpoints = List.copyOf(enabledEndpoints);
            Objects.requireNonNull(topology, "topology");
            Objects.requireNonNull(routeTable, "routeTable");
        }
    }
}
