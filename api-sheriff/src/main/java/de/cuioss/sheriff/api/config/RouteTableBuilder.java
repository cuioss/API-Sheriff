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
package de.cuioss.sheriff.api.config;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.AnchorConfig;
import de.cuioss.sheriff.api.config.model.AssetConfig;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ForwardConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedAsset;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;
import de.cuioss.tools.logging.CuiLogger;

/**
 * Assembles the immutable {@link RouteTable} from the validated configuration
 * (pipeline step 8).
 * <p>
 * The builder merges the routes of the <em>enabled endpoints only</em> — disabled
 * endpoints contribute no rows — orders them by descending normalized
 * {@code path_prefix} length (most specific first), and
 * materializes each route's effective auth, effective {@code allowed_methods},
 * effective {@code security_filter} / {@code security_headers}, effective retry
 * / not-modified toggles, and the effective deny-by-default {@code forward}
 * allowlist into a {@link ResolvedRoute}. The inheritance chains
 * (gateway defaults → anchor → endpoint → route, wholesale replacement at every
 * step — ADR-0007) are resolved here, once, so the request pipeline never
 * re-implements them and never consults an anchor. The effective posture of each
 * route is emitted to the boot log; a non-auth override that replaces an
 * anchor-provided block is logged as a boot WARN.
 * <p>
 * Framework-agnostic (ADR-0005): the collaborators are supplied as method
 * arguments and the builder carries no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteTableBuilder {

    private static final CuiLogger LOGGER = new CuiLogger(RouteTableBuilder.class);

    private static final List<HttpMethod> STANDARD_ALLOWED_METHODS = List.copyOf(EnumSet.allOf(HttpMethod.class));

    private static final String NONE = "none";

    /**
     * Builds the route table from the enabled endpoints and the resolved topology.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the endpoints to merge; disabled entries are skipped
     * @param topology  the resolved topology providing each endpoint's upstream
     * @return the immutable, longest-prefix-ordered route table
     * @throws RouteTableException when an enabled endpoint's alias does not resolve,
     *                             or a route has no resolvable effective auth
     */
    public RouteTable build(GatewayConfig gateway, List<EndpointConfig> endpoints, ResolvedTopology topology) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(endpoints, "endpoints");
        Objects.requireNonNull(topology, "topology");

        List<ResolvedRoute> resolved = new ArrayList<>();
        for (EndpointConfig endpoint : endpoints) {
            if (!endpoint.enabled()) {
                continue;
            }
            ResolvedUpstream upstream = topology.lookup(endpoint.baseUrl()).orElseThrow(() -> new RouteTableException(
                    "unresolved topology alias for enabled endpoint '%s': %s".formatted(endpoint.id(),
                            endpoint.baseUrl())));
            UpstreamDefaultsConfig defaults = resolveDefaults(gateway, endpoint);
            for (RouteConfig route : endpoint.routes()) {
                Optional<AnchorConfig> anchor = resolveAnchor(gateway, endpoint, route);
                resolved.add(resolveRoute(gateway, route, endpoint, anchor, upstream, defaults, topology));
            }
        }

        resolved.sort(Comparator
                .comparingInt((ResolvedRoute route) -> normalizePrefix(route.pathPrefix()).length()).reversed()
                .thenComparing((ResolvedRoute route) -> normalizePrefix(route.pathPrefix())));
        return new RouteTable(resolved);
    }

    /**
     * Normalizes a path prefix — ensuring a leading {@code /} and stripping a
     * trailing {@code /} (except for the bare root) — so {@code /api} and
     * {@code /api/} order identically. The single shared implementation: also used
     * by the same-prefix disjointness rule and the anchor-namespace-containment rule
     * owned by {@code ConfigValidator} (ADR-0009).
     *
     * @param prefix the raw path prefix
     * @return the normalized prefix
     */
    public static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String normalized = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Optional<AnchorConfig> resolveAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        return route.anchor().or(endpoint::anchor).map(name -> gateway.anchors().get(name)).filter(Objects::nonNull);
    }

    private static ResolvedRoute resolveRoute(GatewayConfig gateway, RouteConfig route, EndpointConfig endpoint,
            Optional<AnchorConfig> anchor, ResolvedUpstream upstream, UpstreamDefaultsConfig defaults,
            ResolvedTopology topology) {
        AuthConfig auth = route.auth().or(endpoint::auth).or(() -> anchor.flatMap(AnchorConfig::auth))
                .orElseThrow(() -> new RouteTableException(
                        "route '%s' has no effective auth (no route, endpoint, or anchor auth block)"
                                .formatted(route.id())));
        List<HttpMethod> allowedMethods = effectiveAllowedMethods(gateway, endpoint, anchor);
        Optional<SecurityFilterConfig> securityFilter = route.securityFilter()
                .or(() -> anchor.flatMap(AnchorConfig::securityFilter));
        Optional<SecurityHeadersConfig> securityHeaders = anchor.flatMap(AnchorConfig::securityHeaders)
                .or(gateway::securityHeaders);
        warnOnWeakeningOverride(route, endpoint, anchor);
        boolean retryEnabled = route.upstream()
                .flatMap(UpstreamConfig::retry)
                .flatMap(UpstreamConfig.Retry::enabled)
                .orElse(defaults.retryEnabled());
        boolean notModifiedEnabled = route.upstream()
                .flatMap(UpstreamConfig::notModified)
                .flatMap(UpstreamConfig.NotModified::enabled)
                .orElse(defaults.notModifiedEnabled());
        ForwardConfig effectiveForward = route.forward().orElseGet(() -> ForwardConfig.builder().build());
        ResolvedRoute.ResolvedRouteBuilder builder = ResolvedRoute.builder()
                .id(route.id())
                .protocol(route.protocol().orElse(Protocol.HTTP))
                .anchor(anchor.map(AnchorConfig::name))
                .match(route.match())
                .effectiveAuth(auth)
                .effectiveAllowedMethods(allowedMethods)
                .effectiveSecurityFilter(securityFilter)
                .effectiveSecurityHeaders(securityHeaders)
                .retryEnabled(retryEnabled)
                .notModifiedEnabled(notModifiedEnabled)
                .effectiveForward(effectiveForward);
        // A route resolves to exactly one terminal action: an asset action (when the route
        // declares an asset block) is materialized here; otherwise the route proxies to its
        // endpoint upstream. ADR-0014: upstream XOR asset.
        if (route.asset().isPresent()) {
            builder.asset(Optional.of(resolveAsset(route, route.asset().get(), anchor, topology)));
        } else {
            builder.upstream(Optional.of(upstream));
        }
        ResolvedRoute resolved = builder.build();
        logPosture(resolved);
        return resolved;
    }

    /**
     * Materializes a route's asset terminal action (ADR-0014). A {@code directory}
     * source carries its configured root; an {@code upstream} source resolves its
     * topology alias through the same {@link ResolvedTopology} the proxy action uses —
     * no parallel resolution. The effective access level is inherited from the route's
     * resolving anchor (ADR-0013), defaulting to {@link AccessLevel#PUBLIC} for an
     * unanchored asset route (the configuration validator rejects an asset action on a
     * non-asset anchor before assembly, so this default is a defensive floor).
     */
    private static ResolvedAsset resolveAsset(RouteConfig route, AssetConfig asset, Optional<AnchorConfig> anchor,
            ResolvedTopology topology) {
        AccessLevel access = anchor.map(AnchorConfig::access).orElse(AccessLevel.PUBLIC);
        return switch (asset.source()) {
            case DIRECTORY -> ResolvedAsset.directory(asset.directory().orElseThrow(() -> new RouteTableException(
                            "asset route '%s' declares source: directory but no directory root".formatted(route.id()))),
                    access);
            case UPSTREAM -> {
                String alias = asset.upstream().orElseThrow(() -> new RouteTableException(
                        "asset route '%s' declares source: upstream but no upstream alias".formatted(route.id())));
                ResolvedUpstream resolvedUpstream = topology.lookup(alias).orElseThrow(() -> new RouteTableException(
                        "asset route '%s' upstream alias '%s' does not resolve in the topology"
                                .formatted(route.id(), alias)));
                yield ResolvedAsset.upstream(resolvedUpstream, access);
            }
        };
    }

    private static void logPosture(ResolvedRoute route) {
        String anchorName = route.anchor().orElse(NONE);
        String filter = route.effectiveSecurityFilter().flatMap(SecurityFilterConfig::profile).orElse(NONE);
        LOGGER.info(ConfigLogMessages.INFO.ROUTE_POSTURE, route.id(), anchorName, route.effectiveAuth().require(),
                filter);
    }

    private static void warnOnWeakeningOverride(RouteConfig route, EndpointConfig endpoint,
            Optional<AnchorConfig> anchor) {
        if (anchor.isEmpty()) {
            return;
        }
        AnchorConfig anchorConfig = anchor.get();
        if (anchorConfig.securityFilter().isPresent() && route.securityFilter().isPresent()) {
            LOGGER.warn(ConfigLogMessages.WARN.ANCHOR_POLICY_OVERRIDDEN, route.id(), anchorConfig.name(),
                    "security_filter");
        }
        if (!anchorConfig.allowedMethods().isEmpty() && !endpoint.allowedMethods().isEmpty()) {
            LOGGER.warn(ConfigLogMessages.WARN.ANCHOR_POLICY_OVERRIDDEN, route.id(), anchorConfig.name(),
                    "allowed_methods");
        }
    }

    private static UpstreamDefaultsConfig resolveDefaults(GatewayConfig gateway, EndpointConfig endpoint) {
        UpstreamDefaultsConfig global = gateway.upstreamDefaults().orElseGet(UpstreamDefaultsConfig::defaults);
        return endpoint.upstreamDefaults().orElse(global);
    }

    private static List<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint,
            Optional<AnchorConfig> anchor) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return List.copyOf(endpoint.allowedMethods());
        }
        Optional<List<HttpMethod>> anchorMethods = anchor.map(AnchorConfig::allowedMethods)
                .filter(methods -> !methods.isEmpty());
        if (anchorMethods.isPresent()) {
            return List.copyOf(anchorMethods.get());
        }
        if (!gateway.allowedMethods().isEmpty()) {
            return List.copyOf(gateway.allowedMethods());
        }
        return STANDARD_ALLOWED_METHODS;
    }

    /**
     * Signals a route-table assembly failure: an enabled endpoint whose alias does
     * not resolve, or a route with no resolvable effective auth. Both are boot
     * failures for an otherwise structurally valid configuration. Same-prefix
     * disjointness is reported separately, in the all-violations
     * {@code ConfigValidator} pass (ADR-0009).
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class RouteTableException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with the given detail message.
         *
         * @param message the human-readable description of the assembly failure
         */
        public RouteTableException(String message) {
            super(message);
        }
    }
}
