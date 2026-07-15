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

    @TempDir
    Path configDir;

    private ConfigProducer producerForValidConfig() throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), VALID_GATEWAY);
        ConfigProducer producer = new ConfigProducer();
        producer.configDir = configDir.toString();
        return producer;
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
}
