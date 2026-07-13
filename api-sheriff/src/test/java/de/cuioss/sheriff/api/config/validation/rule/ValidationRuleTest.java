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
package de.cuioss.sheriff.api.config.validation.rule;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;

/**
 * Tests for the {@link ValidationRule} functional-interface contract: a rule
 * <em>appends</em> a {@link ConfigError} per violation to the shared accumulator,
 * leaves it untouched when nothing is wrong, may append several violations in one
 * pass, and never replaces the caller's list so that consecutive rules accumulate
 * into it.
 */
class ValidationRuleTest {

    private static final GatewayConfig GATEWAY = GatewayConfig.builder().version(1).build();
    private static final ResolvedTopology TOPOLOGY = new ResolvedTopology(Map.of());

    @Test
    @DisplayName("Should append one error per violation to the shared accumulator")
    void shouldAppendOneErrorPerViolation() {
        ValidationRule rule = (gateway, endpoints, topology, errors) ->
                errors.add(new ConfigError("gateway.yaml", "/version", "boom"));
        List<ConfigError> errors = new ArrayList<>();

        rule.validate(GATEWAY, List.of(), TOPOLOGY, errors);

        assertAll("single appended violation",
                () -> assertEquals(1, errors.size(), "exactly one error should be appended"),
                () -> assertEquals("boom", errors.getFirst().message(), "the appended message should be preserved"));
    }

    @Test
    @DisplayName("Should leave the accumulator untouched when there is no violation")
    void shouldLeaveAccumulatorUntouchedWhenNoViolation() {
        ValidationRule rule = (gateway, endpoints, topology, errors) -> {
            // a compliant configuration appends nothing
        };
        List<ConfigError> errors = new ArrayList<>();

        rule.validate(GATEWAY, List.of(), TOPOLOGY, errors);

        assertTrue(errors.isEmpty(), () -> "expected no errors, got: " + errors);
    }

    @Test
    @DisplayName("Should append several violations in a single pass without stopping at the first")
    void shouldAppendSeveralViolationsInSinglePass() {
        ValidationRule rule = (gateway, endpoints, topology, errors) -> {
            errors.add(new ConfigError("a.yaml", "/x", "first"));
            errors.add(new ConfigError("a.yaml", "/y", "second"));
        };
        List<ConfigError> errors = new ArrayList<>();

        rule.validate(GATEWAY, List.of(), TOPOLOGY, errors);

        assertEquals(2, errors.size(), "both violations from one pass should be present");
    }

    @Test
    @DisplayName("Should accumulate into the shared list across consecutive rules")
    void shouldAccumulateAcrossConsecutiveRules() {
        ValidationRule first = (gateway, endpoints, topology, errors) ->
                errors.add(new ConfigError("a.yaml", "/x", "first"));
        ValidationRule second = (gateway, endpoints, topology, errors) ->
                errors.add(new ConfigError("b.yaml", "/y", "second"));
        List<ConfigError> errors = new ArrayList<>();

        first.validate(GATEWAY, List.of(), TOPOLOGY, errors);
        second.validate(GATEWAY, List.of(), TOPOLOGY, errors);

        assertAll("violations from both rules coexist",
                () -> assertEquals(2, errors.size(), "both rules should contribute to the same list"),
                () -> assertEquals("first", errors.getFirst().message(), "the first rule's error should be retained"),
                () -> assertEquals("second", errors.get(1).message(), "the second rule's error should be appended"));
    }
}
