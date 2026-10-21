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
package de.cuioss.sheriff.gateway.config.validation.rule;

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ENDPOINT_ROUTES_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that judge the shape of the configuration as a whole: which
 * document version it is written in, whether its endpoints and routes are uniquely named, and whether
 * every endpoint's upstream resolves in the topology.
 * <p>
 * The version rule ({@link #validateVersion}) admits only the configuration version this gateway
 * understands. The two uniqueness rules ({@link #validateEndpointIdUniqueness} and
 * {@link #validateRouteIdUniqueness}) refuse a repeated endpoint id and a repeated route id, the
 * latter across every enabled endpoint. The base-URL rule ({@link #validateBaseUrlResolvable}) requires {@code base_url} exactly when an
 * endpoint carries a proxy route, and requires a declared alias to resolve in the topology.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast (ADR-0009). Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class TopologyRules {

    private static final int SUPPORTED_VERSION = 1;

    /**
     * Rule: the gateway document must be written in the configuration version this gateway supports.
     *
     * @param gateway the bound gateway document, whose {@code version} is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateVersion(GatewayConfig gateway, List<ConfigError> errors) {
        if (gateway.version() != SUPPORTED_VERSION) {
            errors.add(new ConfigError(GATEWAY_FILE, "/version",
                    "unsupported config version %d (supported: %d)".formatted(gateway.version(), SUPPORTED_VERSION)));
        }
    }

    /**
     * Rule: no two enabled endpoints may share an endpoint id. Each repeat is refused on the file of
     * the endpoint that repeats it.
     *
     * @param endpoints the enabled endpoints, in declaration order
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateEndpointIdUniqueness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        Set<String> seen = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            if (!seen.add(endpoint.id())) {
                errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/id",
                        "duplicate endpoint id: " + endpoint.id()));
            }
        }
    }

    /**
     * Rule: no two routes may share a route id, across every enabled endpoint. Each repeat is refused
     * on the file of the endpoint that declares it.
     *
     * @param endpoints the enabled endpoints, in declaration order
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateRouteIdUniqueness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        Set<String> seen = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (!seen.add(route.id())) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "duplicate route id: " + route.id()));
                }
            }
        }
    }

    /**
     * Rule: {@code base_url} is mandatory exactly when the endpoint carries a proxy route
     * ({@link RouteConfig#isProxyRoute()} — the same predicate the route-table builder resolves the
     * alias on), and a declared alias must resolve in the topology whether or not a proxy route uses
     * it. An endpoint serving only {@code asset} and/or {@code redirect} routes may omit
     * {@code base_url}.
     *
     * @param endpoints the enabled endpoints, whose {@code base_url} is judged
     * @param topology  the resolved topology a declared alias must resolve in
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateBaseUrlResolvable(List<EndpointConfig> endpoints, ResolvedTopology topology,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            String baseUrl = endpoint.baseUrl();
            if (baseUrl == null) {
                if (endpoint.routes().stream().anyMatch(RouteConfig::isProxyRoute)) {
                    errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/base_url",
                            "endpoint '%s' declares proxy route(s) but no base_url".formatted(endpoint.id())));
                }
            } else if (topology.lookup(baseUrl).isEmpty()) {
                errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/base_url",
                        "unresolved topology alias: " + baseUrl));
            }
        }
    }
}
