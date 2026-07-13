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
package de.cuioss.sheriff.api.config.validation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.validation.rule.ValidationRule;

/**
 * Runs the cross-cutting configuration rules (pipeline step 7) that cannot be
 * expressed structurally in the D2 JSON Schemas, aggregating every violation in a
 * single pass.
 * <p>
 * Each rule is a {@link ValidationRule}; {@link #validate} executes them all and
 * returns the combined, file- and path-annotated {@link ConfigError} list — never
 * stopping at the first problem. Disabled endpoints have already been dropped by the
 * enablement resolver, so the rules that only concern live routes (alias
 * resolvability, effective-method membership) see enabled endpoints only.
 * Structural verb rules (a config naming {@code TRACE}/{@code CONNECT}) are enforced
 * upstream by the schema and the {@link HttpMethod} enum, which cannot represent
 * those verbs, so no post-binding rule is needed for them.
 * <p>
 * Framework-agnostic (ADR-0005): the rule set is supplied at construction and the
 * validator carries no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigValidator {

    private static final int SUPPORTED_VERSION = 1;
    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String TRUST_ALL_IPV4 = "0.0.0.0/0";
    private static final String TRUST_ALL_IPV6 = "::/0";
    private static final String WILDCARD_ORIGIN = "*";
    private static final int MILLIS_PER_SECOND = 1000;

    private static final List<ValidationRule> DEFAULT_RULES = List.of(
            (gateway, endpoints, topology, errors) -> validateVersion(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateEndpointIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateBaseUrlResolvable(endpoints, topology, errors),
            (gateway, endpoints, topology, errors) -> validateEffectiveAuth(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateMethodMembership(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateWholeSecondTimeouts(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateForwardedTrust(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMode(gateway, errors));

    private final List<ValidationRule> rules;

    /**
     * Creates a validator with the built-in rule set.
     */
    public ConfigValidator() {
        this(DEFAULT_RULES);
    }

    /**
     * Creates a validator with a custom rule set.
     *
     * @param rules the rules to run, in order
     */
    public ConfigValidator(List<ValidationRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /**
     * Runs every rule and returns all violations discovered in one pass.
     *
     * @param gateway          the bound gateway document
     * @param enabledEndpoints the endpoints filtered to those enabled
     * @param topology         the resolved topology
     * @return the aggregated, unmodifiable list of violations, empty when valid
     */
    public List<ConfigError> validate(GatewayConfig gateway, List<EndpointConfig> enabledEndpoints,
            ResolvedTopology topology) {
        List<ConfigError> errors = new ArrayList<>();
        for (ValidationRule rule : rules) {
            rule.validate(gateway, enabledEndpoints, topology, errors);
        }
        return List.copyOf(errors);
    }

    private static void validateVersion(GatewayConfig gateway, List<ConfigError> errors) {
        if (gateway.version() != SUPPORTED_VERSION) {
            errors.add(new ConfigError(GATEWAY_FILE, "/version",
                    "unsupported config version %d (supported: %d)".formatted(gateway.version(), SUPPORTED_VERSION)));
        }
    }

    private static void validateEndpointIdUniqueness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        Set<String> seen = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            if (!seen.add(endpoint.id())) {
                errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/id",
                        "duplicate endpoint id: " + endpoint.id()));
            }
        }
    }

    private static void validateRouteIdUniqueness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        Set<String> seen = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (!seen.add(route.id())) {
                    errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/routes",
                            "duplicate route id: " + route.id()));
                }
            }
        }
    }

    private static void validateBaseUrlResolvable(List<EndpointConfig> endpoints, ResolvedTopology topology,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            if (topology.lookup(endpoint.baseUrl()).isEmpty()) {
                errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/base_url",
                        "unresolved topology alias: " + endpoint.baseUrl()));
            }
        }
    }

    private static void validateEffectiveAuth(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Set<String> requires = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                requires.add(effectiveRequire(endpoint, route));
            }
        }
        if (requires.contains("bearer")
                && gateway.tokenValidation().map(tv -> tv.issuers().isEmpty()).orElse(true)) {
            errors.add(new ConfigError(GATEWAY_FILE, "/token_validation",
                    "effective auth 'bearer' requires token_validation with at least one issuer"));
        }
        if (requires.contains("session") && gateway.oidc().isEmpty()) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc", "effective auth 'session' requires an oidc block"));
        }
    }

    private static void validateMethodMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            Set<HttpMethod> allowed = effectiveAllowedMethods(gateway, endpoint);
            for (RouteConfig route : endpoint.routes()) {
                for (HttpMethod method : route.match().methods()) {
                    if (!allowed.contains(method)) {
                        errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/routes",
                                "route '%s' matches method %s outside the effective allowed_methods"
                                        .formatted(route.id(), method)));
                    }
                }
            }
        }
    }

    private static void validateWholeSecondTimeouts(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                route.upstream().ifPresent(upstream -> {
                    upstream.connectTimeoutMs().ifPresent(value ->
                            requireWholeSecond(endpoint, route, "connect_timeout_ms", value, errors));
                    upstream.readTimeoutMs().ifPresent(value ->
                            requireWholeSecond(endpoint, route, "read_timeout_ms", value, errors));
                });
            }
        }
    }

    private static void requireWholeSecond(EndpointConfig endpoint, RouteConfig route, String field, int value,
            List<ConfigError> errors) {
        if (value <= 0) {
            errors.add(new ConfigError(endpointFile(endpoint),
                    "/endpoint/routes/%s/upstream/%s".formatted(route.id(), field),
                    "%s must be a positive whole-second multiple of %d ms, was %d"
                            .formatted(field, MILLIS_PER_SECOND, value)));
            return;
        }
        if (value % MILLIS_PER_SECOND != 0) {
            errors.add(new ConfigError(endpointFile(endpoint),
                    "/endpoint/routes/%s/upstream/%s".formatted(route.id(), field),
                    "%s must be a whole-second multiple of %d ms, was %d".formatted(field, MILLIS_PER_SECOND, value)));
        }
    }

    private static void validateForwardedTrust(GatewayConfig gateway, List<ConfigError> errors) {
        gateway.forwarded().ifPresent(forwarded -> {
            for (String cidr : forwarded.trustedProxies()) {
                if (TRUST_ALL_IPV4.equals(cidr) || TRUST_ALL_IPV6.equals(cidr)) {
                    errors.add(new ConfigError(GATEWAY_FILE, "/forwarded/trusted_proxies",
                            "trust-all CIDR is not permitted: " + cidr));
                }
            }
        });
    }

    private static void validateCors(GatewayConfig gateway, List<ConfigError> errors) {
        gateway.securityHeaders().flatMap(headers -> headers.cors()).ifPresent(cors -> {
            if (cors.allowCredentials().orElse(false) && cors.allowedOrigins().contains(WILDCARD_ORIGIN)) {
                errors.add(new ConfigError(GATEWAY_FILE, "/security_headers/cors",
                        "wildcard origin '*' is not permitted together with allow_credentials"));
            }
        });
    }

    private static void validateSessionMode(GatewayConfig gateway, List<ConfigError> errors) {
        gateway.oidc().flatMap(oidc -> oidc.session()).ifPresent(session ->
                session.mode().ifPresent(mode -> {
                    if ("cookie".equals(mode) && session.encryptionKey().isEmpty()) {
                        errors.add(new ConfigError(GATEWAY_FILE, "/oidc/session/encryption_key",
                                "cookie session mode requires an encryption_key"));
                    }
                    if ("server".equals(mode) && session.store().isEmpty()) {
                        errors.add(new ConfigError(GATEWAY_FILE, "/oidc/session/store",
                                "server session mode requires a store"));
                    }
                }));
    }

    private static String effectiveRequire(EndpointConfig endpoint, RouteConfig route) {
        return route.auth().map(AuthConfig::require).orElse(endpoint.auth().require());
    }

    private static Set<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(endpoint.allowedMethods());
        }
        if (!gateway.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(gateway.allowedMethods());
        }
        return EnumSet.allOf(HttpMethod.class);
    }

    private static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }
}
