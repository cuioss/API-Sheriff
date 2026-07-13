/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.quarkus;

import java.nio.file.Path;
import java.util.List;

import de.cuioss.sheriff.api.config.ConfigLogMessages;
import de.cuioss.sheriff.api.config.RouteTableBuilder;
import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.load.ConfigLoadException;
import de.cuioss.sheriff.api.config.load.ConfigLoader;
import de.cuioss.sheriff.api.config.load.EnvSecretResolver;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.Metadata;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.topology.TopologyResolver;
import de.cuioss.sheriff.api.config.validation.ConfigValidator;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
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
 * supplying every collaborator by construction. On the first collected violation
 * the producer logs every problem through structured ERROR
 * {@link ConfigLogMessages} records and throws, so Quarkus exits non-zero and never
 * serves on partial configuration. On success it emits the {@code CONFIG_LOADED}
 * INFO record carrying the audit {@code config_version} and publishes the bound
 * {@link GatewayConfig} and assembled {@link RouteTable} as {@code @ApplicationScoped}
 * beans.
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

    private GatewayConfig gateway;
    private RouteTable routeTable;
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

    private synchronized void buildOnce() {
        if (built) {
            return;
        }
        Path directory = Path.of(configDir);
        try {
            ConfigLoader.LoadedConfig loaded = new ConfigLoader(directory, new EnvSecretResolver()).load();
            List<EndpointConfig> enabled = loaded.endpoints().stream().filter(EndpointConfig::enabled).toList();
            ResolvedTopology topology = new TopologyResolver().resolve(directory.resolve(TOPOLOGY_FILE), enabled);
            List<ConfigError> violations = new ConfigValidator().validate(loaded.gateway(), enabled, topology);
            if (!violations.isEmpty()) {
                abort(violations);
            }
            this.gateway = loaded.gateway();
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
        return gateway.metadata().flatMap(Metadata::configVersion).orElse("unversioned");
    }
}
