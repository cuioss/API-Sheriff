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
package de.cuioss.sheriff.api.config.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;


import org.junit.jupiter.api.Test;

import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;

/**
 * Tests for {@link EndpointEnablementResolver}: environment override precedence over
 * the file {@code enabled} default, the id-to-variable-name derivation, and filtering
 * to the enabled endpoints.
 */
class EndpointEnablementResolverTest {

    private static EndpointConfig endpoint(String id, boolean enabled) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(enabled)
                .baseUrl(id.toUpperCase(Locale.ROOT))
                .auth(new AuthConfig("none", List.of()))
                .build();
    }

    private static EndpointEnablementResolver resolverWith(Map<String, String> environment) {
        return new EndpointEnablementResolver(environment::get);
    }

    @Test
    void environmentOverrideDisablesAFileEnabledEndpoint() {
        EndpointEnablementResolver resolver = resolverWith(Map.of("ENDPOINT_ORDERS_ENABLED", "false"));
        assertFalse(resolver.isEnabled(endpoint("orders", true)));
    }

    @Test
    void environmentOverrideEnablesAFileDisabledEndpoint() {
        EndpointEnablementResolver resolver = resolverWith(Map.of("ENDPOINT_ORDERS_ENABLED", "true"));
        assertTrue(resolver.isEnabled(endpoint("orders", false)));
    }

    @Test
    void fallsBackToFileValueWhenNoOverride() {
        EndpointEnablementResolver resolver = resolverWith(Map.of());
        assertTrue(resolver.isEnabled(endpoint("orders", true)));
        assertFalse(resolver.isEnabled(endpoint("orders", false)));
    }

    @Test
    void derivesEnvironmentVariableNameFromEndpointId() {
        assertEquals("ENDPOINT_ORDER_API_ENABLED", EndpointEnablementResolver.environmentVariableName("order-api"));
        assertEquals("ENDPOINT_ORDERS_ENABLED", EndpointEnablementResolver.environmentVariableName("orders"));
    }

    @Test
    void filtersDownToEnabledEndpoints() {
        EndpointEnablementResolver resolver = resolverWith(Map.of("ENDPOINT_USERS_ENABLED", "false"));
        List<EndpointConfig> enabled = resolver.enabledEndpoints(List.of(
                endpoint("orders", true),
                endpoint("users", true),
                endpoint("legacy", false)));
        assertEquals(List.of("orders"), enabled.stream().map(EndpointConfig::id).toList());
    }
}
