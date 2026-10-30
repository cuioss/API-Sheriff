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
package de.cuioss.sheriff.gateway.config.validation;

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static de.cuioss.sheriff.gateway.testsupport.ValidationAsserts.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the orchestration of {@link ConfigValidator}, and only that: a well-formed configuration
 * passes every rule, and the single pass aggregates the violations of several rules rather than
 * stopping at the first.
 * <p>
 * No rule's own cases live here. Each rule is tested beside the concern class that owns it, in that
 * class's {@code {Concern}RulesTest} in the {@code rule} test package, and those tests drive the full
 * default validator so a rule dropped from {@code DEFAULT_RULES} fails them. Which concern class owns
 * which rule is recorded in the concern map of {@code doc/development/boot-validation-rules.adoc},
 * and {@link BootValidationConcernMapContractTest} binds that map to {@code DEFAULT_RULES}.
 */
@EnableGeneratorController
class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Nested
    @DisplayName("A well-formed configuration")
    class ValidConfiguration {

        @Test
        @DisplayName("Should report no violations")
        void shouldReportNoViolations() {
            GatewayConfig gateway = validGateway().build();
            EndpointConfig endpoint = endpoint("orders", "ORDERS", List.of(), routeConfig("orders-list", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(), () -> "expected no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should accept a millisecond-precision upstream timeout")
        void shouldAcceptMillisecondPrecisionTimeout() {
            RouteConfig route = RouteConfig.builder()
                    .id("r").match(match("/r", HttpMethod.GET))
                    .upstream(UpstreamConfig.builder().readTimeoutMs(2500).build())
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("ORDERS")
                    .auth(auth(Require.NONE))
                    .routes(List.of(route))
                    .build();

            List<ConfigError> errors = validator.validate(validGateway().build(), List.of(endpoint),
                    topologyWith("ORDERS"));

            assertTrue(errors.isEmpty(),
                    () -> "expected no violations for a millisecond-precision timeout, got: " + errors);
        }
    }

    @Nested
    @DisplayName("The aggregating validate pass")
    class Aggregation {

        @Test
        @DisplayName("Should report every violation together in a single pass")
        void shouldReportEveryViolationInOnePass() {
            GatewayConfig gateway = GatewayConfig.builder().version(2).build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .id("orders").enabled(true).baseUrl("MISSING")
                    .auth(auth(Require.NONE))
                    .allowedMethods(List.of(HttpMethod.GET))
                    .routes(List.of(routeConfig("orders-post", HttpMethod.POST)))
                    .build();

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertAll("all three independent violations surface together",
                    () -> assertTrue(errors.size() >= 3, () -> "expected at least three violations, got: " + errors),
                    () -> assertHasError(errors, "/version", "unsupported config version"),
                    () -> assertHasError(errors, "/endpoint/base_url", "unresolved topology alias: MISSING"),
                    () -> assertHasError(errors, "/endpoint/routes", "outside the effective allowed_methods"));
        }
    }
}
