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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URISyntaxException;
import java.nio.file.Path;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ConfigProducer} fails fast on an invalid configuration: the
 * bean producer and the startup observer both throw, and every collected violation
 * plus the abort are logged as structured ERROR records — so Quarkus exits non-zero
 * rather than serving on partial configuration. Also covers the ADR-0007 anchor
 * boot semantics: a valid anchored config boots cleanly, while an undeclared
 * squatter route inside an anchor namespace refuses boot.
 */
@EnableTestLogger
class ConfigFailFastTest {

    private static ConfigProducer producerFor(String resourceDir) throws URISyntaxException {
        var resource = ConfigFailFastTest.class.getResource(resourceDir);
        assertNotNull(resource, resourceDir + " fixture must be on the test classpath");
        ConfigProducer producer = new ConfigProducer();
        producer.configDir = Path.of(resource.toURI()).toString();
        return producer;
    }

    private static ConfigProducer producerForBrokenConfig() throws URISyntaxException {
        return producerFor("/config/broken");
    }

    @Test
    void shouldThrowFromProducerOnBrokenConfig() throws Exception {
        ConfigProducer producer = producerForBrokenConfig();

        assertThrows(IllegalStateException.class, producer::gatewayConfig,
                "producing a bean from a broken config must fail fast");
    }

    @Test
    void shouldFailStartupOnBrokenConfig() throws Exception {
        ConfigProducer producer = producerForBrokenConfig();

        assertThrows(IllegalStateException.class, () -> producer.onStartup(null),
                "startup must abort on a broken config");
    }

    @Test
    void shouldLogEveryViolationAndTheAbort() throws Exception {
        ConfigProducer producer = producerForBrokenConfig();

        assertThrows(IllegalStateException.class, producer::gatewayConfig);

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "Invalid configuration");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "Refusing to start");
    }

    @Test
    void shouldBootCleanlyOnAValidAnchoredConfig() throws Exception {
        ConfigProducer producer = producerFor("/config/anchored");

        assertDoesNotThrow(producer::gatewayConfig, "a valid api+bff anchored config must boot cleanly");
    }

    @Test
    void shouldRefuseBootOnAnAnchorSquatterConfig() throws Exception {
        ConfigProducer producer = producerFor("/config/anchor-squatter");

        assertThrows(IllegalStateException.class, producer::gatewayConfig,
                "an undeclared squatter route inside an anchor namespace must refuse boot");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "does not declare it");
    }
}
