/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.config;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
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
 * / not-modified toggles, the effective deny-by-default {@code forward}
 * allowlist, and the effective upstream base path (the route-level
 * {@code upstream.path} replacing the alias-derived base path when declared)
 * into a {@link ResolvedRoute}. The inheritance chains
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

    /** The {@link AuthConfig#require()} value meaning no authentication is required; also the
     * display fallback for an absent anchor name / security-filter profile in {@link #logPosture}. */
    private static final String NONE = "none";

    /** The default {@code websocket.idle_timeout_seconds} applied when a WebSocket route omits it. */
    private static final int DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS = 300;

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
        Protocol protocol = route.protocol().orElse(Protocol.HTTP);
        Set<String> allowedOrigins = effectiveAllowedOrigins(route);
        Optional<Integer> idleTimeout = protocol == Protocol.WEBSOCKET
                ? Optional.of(route.websocket().flatMap(WebSocketConfig::idleTimeoutSeconds)
                .orElse(DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS))
                : Optional.empty();
        ResolvedRoute.ResolvedRouteBuilder builder = ResolvedRoute.builder()
                .id(route.id())
                .protocol(protocol)
                .anchor(anchor.map(AnchorConfig::name))
                .match(route.match())
                .effectiveAuth(auth)
                .effectiveAllowedMethods(allowedMethods)
                .effectiveSecurityFilter(securityFilter)
                .effectiveSecurityHeaders(securityHeaders)
                .retryEnabled(retryEnabled)
                .notModifiedEnabled(notModifiedEnabled)
                .effectiveForward(effectiveForward)
                .effectiveAllowedOrigins(allowedOrigins)
                .effectiveWebSocketIdleTimeoutSeconds(idleTimeout);
        // A route resolves to exactly one terminal action: an asset action (when the route
        // declares an asset block) is materialized here; otherwise the route proxies to its
        // endpoint upstream. ADR-0014: upstream XOR asset.
        Optional<AssetConfig> asset = route.asset();
        if (asset.isPresent()) {
            builder.asset(Optional.of(resolveAsset(route, asset.get(), anchor, auth, topology)));
        } else {
            builder.upstream(Optional.of(applyRouteUpstreamPath(upstream, route)));
        }
        ResolvedRoute resolved = builder.build();
        logPosture(resolved);
        return resolved;
    }

    /**
     * Materializes the route-level {@code upstream.path} into the route's effective upstream base
     * path. A route that declares a non-blank {@code upstream.path} <em>replaces</em> the
     * alias-derived base path with it (the bare-service-path routing model): the forward URI is
     * then reconstructed as {@code stripTrailingSlash(upstream.path) + remainder-after-prefix} by
     * {@link de.cuioss.sheriff.gateway.edge.DispatchStage#upstreamRequestUri}, so a gRPC route's
     * {@code /{package}.{Service}} segment (and a benchmark route's {@code /anything/<aspect>}
     * rewrite) reaches the upstream instead of being stripped. The alias host / port / scheme are
     * carried through unchanged, so the client- and guard-sharing tuple
     * ({@link de.cuioss.sheriff.gateway.edge.RouteRuntimeAssembler.UpstreamTarget}, keyed on
     * scheme/host/port) is unaffected. A route without {@code upstream.path} keeps the
     * alias-derived base path unchanged — the default proxy behavior.
     *
     * @param aliasUpstream the endpoint's alias-resolved upstream (shared across the endpoint's
     *                      routes)
     * @param route         the route whose optional {@code upstream.path} overrides the base path
     * @return the per-route upstream carrying the effective base path
     */
    private static ResolvedUpstream applyRouteUpstreamPath(ResolvedUpstream aliasUpstream, RouteConfig route) {
        return route.upstream()
                .flatMap(UpstreamConfig::path)
                .filter(path -> !path.isBlank())
                .map(path -> new ResolvedUpstream(aliasUpstream.scheme(), aliasUpstream.host(),
                        aliasUpstream.port(), path))
                .orElse(aliasUpstream);
    }

    /**
     * Materializes a route's asset terminal action (ADR-0014). A {@code directory}
     * source carries its configured root; an {@code upstream} source resolves its
     * topology alias through the same {@link ResolvedTopology} the proxy action uses —
     * no parallel resolution. The effective access level the gateway-owned response
     * envelope (asset package) keys its caching on is
     * {@link #effectiveAccessLevel(Optional, AuthConfig) derived from the route's
     * effective auth posture}, not the anchor's static {@code access} declaration alone
     * — a route or endpoint may legally strengthen a {@code public}-access anchor's
     * floor with its own {@code auth} block (ADR-0007 forbids weakening the floor, not
     * strengthening it), and such a route must still be governed {@code no-store} even
     * though its anchor stays {@code access: public}.
     */
    private static ResolvedAsset resolveAsset(RouteConfig route, AssetConfig asset, Optional<AnchorConfig> anchor,
            AuthConfig effectiveAuth, ResolvedTopology topology) {
        AccessLevel access = effectiveAccessLevel(anchor, effectiveAuth);
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

    /**
     * The access level the asset response envelope keys its caching governance on: a route whose
     * effective auth requires authentication ({@code require} not {@code none}) is always treated
     * as {@link AccessLevel#AUTHENTICATED}, regardless of the anchor's declared {@code access} —
     * the anchor's {@code access} only supplies the fallback when the route is effectively
     * unauthenticated. This closes the gap where a route or endpoint strengthens a
     * {@code public}-access anchor's auth floor: the served asset must still be forced
     * {@code no-store} even though the anchor itself stays {@code access: public}. Defaults to
     * {@link AccessLevel#PUBLIC} for an unanchored, effectively-unauthenticated asset route (the
     * configuration validator rejects an asset action on a non-asset anchor before assembly, so
     * this default is a defensive floor).
     */
    private static AccessLevel effectiveAccessLevel(Optional<AnchorConfig> anchor, AuthConfig effectiveAuth) {
        if (!NONE.equals(effectiveAuth.require())) {
            return AccessLevel.AUTHENTICATED;
        }
        return anchor.map(AnchorConfig::access).orElse(AccessLevel.PUBLIC);
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
     * The materialized WebSocket {@code allowed_origins} allowlist, lower-cased once at
     * assembly for case-insensitive host matching (scheme and port are already
     * case-insensitive). Iteration order is not significant — origin acceptance is an
     * exact-membership test, so the set may be defensively re-copied downstream without
     * any ordering guarantee. Empty for a route that declares no {@code websocket} block.
     */
    private static Set<String> effectiveAllowedOrigins(RouteConfig route) {
        Set<String> origins = new LinkedHashSet<>();
        for (String origin : route.websocket().map(WebSocketConfig::allowedOrigins).orElseGet(List::of)) {
            origins.add(origin.toLowerCase(Locale.ROOT));
        }
        return origins;
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
