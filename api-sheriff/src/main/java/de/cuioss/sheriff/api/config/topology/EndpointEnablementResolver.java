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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

import de.cuioss.sheriff.api.config.model.EndpointConfig;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the effective enabled state of each endpoint (pipeline step 5), applying
 * the {@code ENDPOINT_<ID>_ENABLED} environment override on top of the file
 * {@code enabled} field.
 * <p>
 * Precedence: the environment variable wins over the file value, which itself
 * defaults to {@code true}. The variable name derives from the endpoint id
 * uppercased with every non-alphanumeric character mapped to an underscore
 * ({@code order-api} → {@code ENDPOINT_ORDER_API_ENABLED}). Disabled endpoints are
 * dropped before route-table assembly and are exempt from alias resolution and the
 * disjointness / passthrough checks, though they remain schema-validated.
 * <p>
 * Framework-agnostic (ADR-0005): the environment lookup is constructor-injected.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EndpointEnablementResolver {

    private final UnaryOperator<@Nullable String> environment;

    /**
     * Creates a resolver backed by the process environment
     * ({@link System#getenv(String)}).
     */
    public EndpointEnablementResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the supplied environment lookup.
     *
     * @param environment maps an environment-variable name to its value, or
     *                    {@code null} when undefined
     */
    public EndpointEnablementResolver(UnaryOperator<@Nullable String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Reports whether the endpoint is effectively enabled.
     *
     * @param endpoint the endpoint to evaluate
     * @return the environment override when set, otherwise the file {@code enabled}
     *         value
     */
    public boolean isEnabled(EndpointConfig endpoint) {
        String override = environment.apply(environmentVariableName(endpoint.id()));
        if (override != null) {
            return Boolean.parseBoolean(override.trim());
        }
        return endpoint.enabled();
    }

    /**
     * Filters the endpoints down to those effectively enabled, preserving order.
     *
     * @param endpoints the schema-validated endpoints
     * @return the enabled endpoints
     */
    public List<EndpointConfig> enabledEndpoints(List<EndpointConfig> endpoints) {
        return endpoints.stream().filter(this::isEnabled).toList();
    }

    /**
     * Derives the enablement-override environment variable name for an endpoint id.
     *
     * @param id the endpoint id
     * @return the {@code ENDPOINT_<ID>_ENABLED} variable name
     */
    public static String environmentVariableName(String id) {
        String normalized = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return "ENDPOINT_" + normalized + "_ENABLED";
    }
}
