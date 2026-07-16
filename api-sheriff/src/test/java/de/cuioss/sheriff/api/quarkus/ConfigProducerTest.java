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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

/**
 * Tests for {@link ConfigProducer}: CDI bean assembly from a valid configuration
 * directory, the memoized single build, eager startup assembly, and the
 * {@code CONFIG_LOADED} INFO log record.
 */
@EnableTestLogger
class ConfigProducerTest {

    private static final String VALID_GATEWAY = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            """;

    private static final String PASSTHROUGH_HOST = "payments.example.com";
    private static final String PASSTHROUGH_ALIAS = "PAYMENTS_BACKEND";
    private static final String PASSTHROUGH_ORIGIN = "https://payments.internal:8443";

    private static final String GATEWAY_WITH_PASSTHROUGH_SNI = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            tls:
              passthrough_sni:
                "%s": "%s"
            """.formatted(PASSTHROUGH_HOST, PASSTHROUGH_ALIAS);

    @TempDir
    Path configDir;

    private ConfigProducer producerForGateway(String gatewayYaml) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), gatewayYaml);
        ConfigProducer producer = new ConfigProducer();
        producer.configDir = configDir.toString();
        return producer;
    }

    private ConfigProducer producerForValidConfig() throws IOException {
        return producerForGateway(VALID_GATEWAY);
    }

    @Test
    void shouldProduceBeansFromValidConfig() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        GatewayConfig gateway = producer.gatewayConfig();
        RouteTable routeTable = producer.routeTable();

        assertEquals(1, gateway.version(), "the bound gateway should carry the configured version");
        assertNotNull(routeTable, "the route table bean should be produced");
        assertTrue(routeTable.routes().isEmpty(), "a config with no endpoints yields an empty route table");
    }

    @Test
    void shouldLogConfigLoadedOnSuccess() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        producer.gatewayConfig();

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "Configuration loaded successfully");
    }

    @Test
    void shouldBuildOnceAndCacheTheResult() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        GatewayConfig first = producer.gatewayConfig();
        GatewayConfig second = producer.gatewayConfig();

        assertSame(first, second, "the pipeline should be assembled once and cached");
    }

    @Test
    void shouldAssembleEagerlyOnStartupForValidConfig() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        assertDoesNotThrow(() -> producer.onStartup(null),
                "a valid configuration should assemble without failing startup");
        assertNotNull(producer.gatewayConfig(), "beans should be available after startup assembly");
    }

    @Test
    void shouldNotFailBootWhenDisabledEndpointAliasIsUnresolvable() throws Exception {
        ConfigProducer producer = producerForValidConfig();
        Files.createDirectories(configDir.resolve("endpoints"));
        Files.writeString(configDir.resolve("endpoints/disabled.yaml"), """
                endpoint:
                  id: disabled
                  enabled: false
                  base_url: MISSING_ALIAS
                  auth:
                    require: none
                  routes:
                    - id: disabled-route
                      match:
                        path_prefix: /disabled
                        methods: ["GET"]
                """);

        assertDoesNotThrow(() -> producer.onStartup(null),
                "a disabled endpoint whose base_url alias resolves to nothing must not fail boot");
        assertTrue(producer.routeTable().routes().isEmpty(),
                "a disabled endpoint contributes no route to the assembled table");
    }

    /**
     * Pins the {@code tls.passthrough_sni} wiring at {@code ConfigProducer}: the map is
     * host -> alias, so the producer must hand the map's <em>values</em> (the aliases) to
     * {@link de.cuioss.sheriff.api.config.topology.TopologyResolver}'s
     * {@code additionalAliases}, never its {@code keySet()} (the SNI hosts).
     * <p>
     * The alias is resolvable via {@code topology.properties} while the host key is not a
     * topology alias at all, which is what makes this test discriminating: with the
     * correct {@code values()} wiring the alias resolves and the boot is clean, whereas a
     * {@code keySet()} swap would hand the resolver the host, leave the alias unresolved,
     * and trip {@code ConfigValidator}'s unresolved-passthrough-alias rule — failing this
     * test. Asserting on an <em>unresolvable</em> alias instead would be vacuous: the
     * resolver skips unresolved additional aliases silently, so the validator reports the
     * same violation under either wiring.
     */
    @Test
    void shouldResolvePassthroughSniAliasValueRatherThanHostKey() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_PASSTHROUGH_SNI);
        Files.writeString(configDir.resolve("topology.properties"),
                "%s=%s%n".formatted(PASSTHROUGH_ALIAS, PASSTHROUGH_ORIGIN));

        assertDoesNotThrow(() -> producer.onStartup(null),
                "the passthrough_sni alias value must reach the topology resolver and resolve");
        assertNotNull(producer.gatewayConfig(), "beans should be available after startup assembly");
    }

    @Test
    void shouldFailBootWhenPassthroughSniAliasIsUnresolvable() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_PASSTHROUGH_SNI);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.onStartup(null),
                "an unresolvable passthrough_sni alias should abort startup");

        assertTrue(exception.getMessage().contains("Refusing to start"),
                "the abort should carry the refusing-to-start summary");
    }
}
