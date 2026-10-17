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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.WILDCARD_ORIGIN;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.effectiveProtocol;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.requireOf;

import java.util.List;
import java.util.Locale;

import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that judge what a route's protocol permits it to declare: the
 * WebSocket upgrade allowlist and the protocols a {@code Location} rewrite applies to.
 * <p>
 * The WebSocket allowlist rule ({@link #validateWebSocketConfig}, ADR-0015) is fail-closed: a
 * {@code protocol: websocket} route whose effective auth is authenticated must declare a non-empty
 * {@code allowed_origins} allowlist of exact origins, a declared {@code idle_timeout_seconds} must be
 * positive, and a {@code websocket} block on a route of any other protocol is refused, since its
 * settings would be silently ignored.
 * <p>
 * The {@code rewrite_location} protocol restriction ({@link #validateRewriteLocationProtocol}, AS-11,
 * ADR-0009) refuses {@code upstream.rewrite_location: true} on a {@code grpc} or {@code websocket}
 * route, because the HTTP response relay that applies the mapping never handles those protocols.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fails fast. Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ProtocolRules {

    /**
     * Rule: the fail-closed WebSocket allowlist contract (ADR-0015). Each
     * {@code protocol: websocket} route is validated by {@link #validateWebSocketRoute}
     * (bearer and session allowlist, exact-match origins, positive idle timeout). A route that
     * declares a {@code websocket} block while its protocol is <em>not</em>
     * {@code websocket} is rejected here, since those settings would otherwise be
     * silently ignored. Every violation collects into the shared list; the rule never
     * fails fast.
     */
    public static void validateWebSocketConfig(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (effectiveProtocol(route) == Protocol.WEBSOCKET) {
                    validateWebSocketRoute(gateway, endpoint, route, errors);
                } else if (route.websocket() != null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' declares a websocket block but its protocol is not 'websocket'; the websocket settings would be silently ignored"
                                    .formatted(route.id())));
                }
            }
        }
    }

    /**
     * Validates a single {@code protocol: websocket} route against the fail-closed
     * WebSocket allowlist contract (ADR-0015): a route whose effective auth is authenticated
     * ({@link Require#isAuthenticated()} — today {@code require: bearer} or
     * {@code require: session}) needs a non-empty
     * {@code allowed_origins}, no origin may contain a wildcard, and a declared
     * {@code idle_timeout_seconds} must be positive.
     * <p>
     * A session route is held to the allowlist because its credential is a cookie the browser
     * attaches to the upgrade on its own, so the handshake {@code Origin} is what tells the
     * gateway which page opened the socket. A {@code session_fallback} route is a bearer-posture
     * route and is covered as one. A {@code require: none} route may omit the allowlist. The
     * per-entry and idle-timeout checks apply to every WebSocket route on the same terms,
     * whatever its auth posture. Every violation collects into the shared list; the rule never
     * fails fast.
     */
    private static void validateWebSocketRoute(GatewayConfig gateway, EndpointConfig endpoint, RouteConfig route,
            List<ConfigError> errors) {
        WebSocketConfig websocket = route.websocket();
        List<String> origins = websocket == null ? List.of() : websocket.allowedOrigins();
        Require require = requireOf(ScopeChain.of(gateway, endpoint, route));
        if (require.isAuthenticated() && origins.isEmpty()) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "websocket route '%s' with effective auth '%s' must declare a non-empty allowed_origins allowlist (fail-closed)"
                            .formatted(route.id(), require.name().toLowerCase(Locale.ROOT))));
        }
        for (String origin : origins) {
            if (origin.contains(WILDCARD_ORIGIN)) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        "websocket route '%s' allowed_origins entry '%s' must be an exact origin; wildcards are not permitted"
                                .formatted(route.id(), origin)));
            }
        }
        Integer timeout = websocket == null ? null : websocket.idleTimeoutSeconds();
        if (timeout != null && timeout <= 0) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "websocket route '%s' idle_timeout_seconds must be a positive integer, but was %d"
                            .formatted(route.id(), timeout)));
        }
    }

    /**
     * Rule: {@code upstream.rewrite_location: true} is refused on a {@code grpc} or {@code websocket}
     * route. The mapping is applied by the HTTP response relay only — a gRPC response is relayed with
     * its trailers and carries no redirect, and a WebSocket route hands the connection to the opaque
     * relay — so on those protocols the key would be silently ignored. Every violation collects into
     * the shared list; the rule never fails fast (ADR-0009).
     */
    public static void validateRewriteLocationProtocol(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                UpstreamConfig upstream = route.upstream();
                Protocol protocol = effectiveProtocol(route);
                if (upstream != null && Boolean.TRUE.equals(upstream.rewriteLocation())
                        && (protocol == Protocol.GRPC || protocol == Protocol.WEBSOCKET)) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' declares upstream.rewrite_location but its protocol is '%s'; the Location rewrite applies to http and graphql routes only and would be silently ignored"
                                    .formatted(route.id(), protocol.name().toLowerCase(Locale.ROOT))));
                }
            }
        }
    }
}
