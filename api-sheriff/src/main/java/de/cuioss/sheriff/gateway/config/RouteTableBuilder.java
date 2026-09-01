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
package de.cuioss.sheriff.gateway.config;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the immutable {@link RouteTable} from the validated configuration
 * (pipeline step 8).
 * <p>
 * The builder merges the routes of the <em>enabled endpoints only</em> — disabled
 * endpoints contribute no rows — orders them by descending normalized
 * {@code path_prefix} length (most specific first), and
 * materializes each route's effective auth, effective {@code allowed_methods},
 * effective {@code security_filter} / {@code security_headers}, effective retry
 * / not-modified toggles, the effective {@code forward} filter (whose
 * per-dimension positive-list / negative-list / forward-all posture is carried
 * wholesale, deny lists included), and the effective upstream base path (the route-level
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
// java:S6539 (a class should not depend on too many other classes) is suppressed here rather than
// fixed, and the reasoning is recorded at the site because the rule is fighting the design.
//
// What the dependencies ARE matters more than how many. Nearly all of them are DATA records from the
// single config.model package, not behavioural collaborators: this class is the ONE assembly point
// that maps the whole configuration record model onto ResolvedRoute, so naming every record type is
// precisely its job. The metric counts breadth of a mapping surface as if it were entanglement.
//
// Extraction was considered and rejected on the terms the ADRs set, not for convenience:
//   - ADR-0007 centralises the gateway-defaults -> anchor -> endpoint -> route inheritance chains
//     HERE, once, so the request pipeline never re-implements them and never consults an anchor.
//     Splitting the resolve* cascade into a second class would fragment exactly that single place,
//     while the orchestration in resolveRoute still has to name the same types to feed the builder —
//     so the aggregate coupling would not drop, it would only be spread across two files plus a new
//     carrier type.
//   - normalizePrefix, effectiveAccessLevel and globalProfile are documented SHARED SEAMS that
//     ConfigValidator resolves through (ADR-0009 single-reporter), so relocating them would break the
//     single-implementation property their own javadoc asserts and let the boot refusal and the
//     runtime governance drift apart.
//
// If this class ever grows real behavioural collaborators (as opposed to more model records), that is
// the signal to revisit the split — the suppression covers the mapping breadth, not unbounded growth.
@SuppressWarnings("java:S6539")
public final class RouteTableBuilder {

    private static final CuiLogger LOGGER = new CuiLogger(RouteTableBuilder.class);

    private static final List<HttpMethod> STANDARD_ALLOWED_METHODS = List.copyOf(EnumSet.allOf(HttpMethod.class));

    /** The display fallback for an absent anchor name in {@link #logPosture}. */
    private static final String NO_ANCHOR_NAME = "none";

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
                AnchorConfig anchor = resolveAnchor(gateway, endpoint, route);
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

    private static @Nullable AnchorConfig resolveAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        String name = route.anchor() != null ? route.anchor() : endpoint.anchor();
        return name == null ? null : gateway.anchors().get(name);
    }

    private static ResolvedRoute resolveRoute(GatewayConfig gateway, RouteConfig route, EndpointConfig endpoint,
            @Nullable AnchorConfig anchor, ResolvedUpstream upstream, UpstreamDefaultsConfig defaults,
            ResolvedTopology topology) {
        AuthConfig auth = resolveEffectiveAuth(route, endpoint, anchor);
        List<HttpMethod> allowedMethods = effectiveAllowedMethods(gateway, endpoint, anchor);
        SecurityFilterConfig securityFilter = resolveSecurityFilter(route, anchor);
        SecurityHeadersConfig securityHeaders = resolveSecurityHeaders(gateway, anchor);
        warnOnWeakeningOverride(route, endpoint, anchor);
        UpstreamConfig routeUpstream = route.upstream();
        boolean retryEnabled = resolveRetryEnabled(routeUpstream, defaults);
        boolean notModifiedEnabled = resolveNotModifiedEnabled(routeUpstream, defaults);
        // Both defaults are written as an explicit null check rather than Objects.requireNonNullElse*,
        // matching the idiom every other resolve* helper in this class already uses. The explicit form
        // also keeps the non-nullness visible to static analysis: Sonar's dataflow does not model
        // requireNonNullElse as null-eliminating, so it carried route.forward()/route.protocol()'s
        // @Nullable onto the result and raised java:S4449 at the first use of each local (PR #146).
        //
        // The declared block is carried WHOLESALE, so headers_deny / query_deny ride this same chain
        // with no per-list resolution: the forward block has no inheritance cascade (ADR-0007 applies
        // to auth, allowed_methods, security_* and upstream_defaults, not to forward), so a route
        // either declares the block or it does not. The fallback ForwardConfig is all-absent, which
        // is FORWARD-ALL on both dimensions — not a nothing-crosses allowlist.
        ForwardConfig declaredForward = route.forward();
        ForwardConfig effectiveForward = declaredForward != null
                ? declaredForward
                : ForwardConfig.builder().build();
        Protocol declaredProtocol = route.protocol();
        Protocol protocol = declaredProtocol != null ? declaredProtocol : Protocol.HTTP;
        Set<String> allowedOrigins = effectiveAllowedOrigins(route);
        Integer idleTimeout = resolveWebSocketIdleTimeout(route, protocol);
        ResolvedRoute.ResolvedRouteBuilder builder = ResolvedRoute.builder()
                .id(route.id())
                .protocol(protocol)
                .anchor(anchor == null ? null : anchor.name())
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
        AssetConfig asset = route.asset();
        if (asset != null) {
            builder.asset(resolveAsset(route, asset, anchor, auth, topology));
        } else {
            builder.upstream(applyRouteUpstreamPath(upstream, route));
        }
        ResolvedRoute resolved = builder.build();
        logPosture(resolved, globalProfile(gateway));
        return resolved;
    }

    /**
     * Resolves the route's effective auth block through the {@code route → endpoint → anchor}
     * cascade. Every route must end up with one: a route that reaches the end of the cascade
     * without an auth block is a configuration error, not a defaultable omission.
     *
     * @throws RouteTableException when no level of the cascade declares an auth block
     */
    private static AuthConfig resolveEffectiveAuth(RouteConfig route, EndpointConfig endpoint,
            @Nullable AnchorConfig anchor) {
        AuthConfig auth = route.auth();
        if (auth == null) {
            auth = endpoint.auth();
        }
        if (auth == null && anchor != null) {
            auth = anchor.auth();
        }
        if (auth == null) {
            throw new RouteTableException(
                    "route '%s' has no effective auth (no route, endpoint, or anchor auth block)"
                            .formatted(route.id()));
        }
        return auth;
    }

    /**
     * Resolves the effective {@code security_filter} through the {@code route → anchor} chain. The
     * endpoint level carries no such block, so it is not part of the chain.
     *
     * @return the declared filter, or {@code null} when neither level declares one
     */
    private static @Nullable SecurityFilterConfig resolveSecurityFilter(RouteConfig route,
            @Nullable AnchorConfig anchor) {
        SecurityFilterConfig routeFilter = route.securityFilter();
        if (routeFilter != null) {
            return routeFilter;
        }
        return anchor == null ? null : anchor.securityFilter();
    }

    /**
     * Resolves the effective response-header posture through the {@code anchor → gateway} chain.
     *
     * @return the declared posture, or {@code null} when neither level declares one
     */
    private static @Nullable SecurityHeadersConfig resolveSecurityHeaders(GatewayConfig gateway,
            @Nullable AnchorConfig anchor) {
        SecurityHeadersConfig anchorHeaders = anchor == null ? null : anchor.securityHeaders();
        return anchorHeaders != null ? anchorHeaders : gateway.securityHeaders();
    }

    /**
     * Resolves the route's effective retry toggle: the declared {@code upstream.retry.enabled} when
     * present, otherwise the endpoint- or gateway-level default.
     */
    private static boolean resolveRetryEnabled(@Nullable UpstreamConfig routeUpstream,
            UpstreamDefaultsConfig defaults) {
        UpstreamConfig.Retry retry = routeUpstream == null ? null : routeUpstream.retry();
        Boolean declared = retry == null ? null : retry.enabled();
        return declared != null ? declared : defaults.retryEnabled();
    }

    /**
     * Resolves the route's effective not-modified toggle: the declared
     * {@code upstream.not_modified.enabled} when present, otherwise the endpoint- or gateway-level
     * default.
     */
    private static boolean resolveNotModifiedEnabled(@Nullable UpstreamConfig routeUpstream,
            UpstreamDefaultsConfig defaults) {
        UpstreamConfig.NotModified notModified = routeUpstream == null ? null : routeUpstream.notModified();
        Boolean declared = notModified == null ? null : notModified.enabled();
        return declared != null ? declared : defaults.notModifiedEnabled();
    }

    /**
     * Resolves the WebSocket idle timeout, which exists only for a {@code WEBSOCKET} route: the
     * declared {@code websocket.idle_timeout_seconds} when present, otherwise
     * {@link #DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS}.
     *
     * @return the effective timeout in seconds, or {@code null} for a non-WebSocket route
     */
    private static @Nullable Integer resolveWebSocketIdleTimeout(RouteConfig route, Protocol protocol) {
        if (protocol != Protocol.WEBSOCKET) {
            return null;
        }
        WebSocketConfig websocket = route.websocket();
        Integer declaredIdleTimeout = websocket == null ? null : websocket.idleTimeoutSeconds();
        return declaredIdleTimeout != null ? declaredIdleTimeout : DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS;
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
        UpstreamConfig upstream = route.upstream();
        String path = upstream == null ? null : upstream.path();
        if (path == null || path.isBlank()) {
            return aliasUpstream;
        }
        return new ResolvedUpstream(aliasUpstream.scheme(), aliasUpstream.host(), aliasUpstream.port(), path);
    }

    /**
     * Materializes a route's asset terminal action (ADR-0014). A {@code directory}
     * source carries its configured root; an {@code upstream} source resolves its
     * topology alias through the same {@link ResolvedTopology} the proxy action uses —
     * no parallel resolution. The effective access level the gateway-owned response
     * envelope (asset package) keys its caching on is
     * {@link #effectiveAccessLevel(AnchorConfig, AuthConfig) derived from the route's
     * effective auth posture}, not the anchor's static {@code access} declaration alone
     * — a route or endpoint may legally strengthen a {@code public}-access anchor's
     * floor with its own {@code auth} block (ADR-0007 forbids weakening the floor, not
     * strengthening it), and such a route must still be governed {@code no-store} even
     * though its anchor stays {@code access: public}.
     */
    private static ResolvedAsset resolveAsset(RouteConfig route, AssetConfig asset, @Nullable AnchorConfig anchor,
            AuthConfig effectiveAuth, ResolvedTopology topology) {
        AccessLevel access = effectiveAccessLevel(anchor, effectiveAuth);
        return switch (asset.source()) {
            case DIRECTORY -> {
                String directory = asset.directory();
                if (directory == null) {
                    throw new RouteTableException(
                            "asset route '%s' declares source: directory but no directory root".formatted(route.id()));
                }
                yield ResolvedAsset.directory(directory, access);
            }
            case UPSTREAM -> {
                String alias = asset.upstream();
                if (alias == null) {
                    throw new RouteTableException(
                            "asset route '%s' declares source: upstream but no upstream alias".formatted(route.id()));
                }
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
     * <p>
     * A shared seam: {@code ConfigValidator}'s fail-closed {@code profile: minimal} refusal derives the
     * access level through this same method rather than reading {@link AnchorConfig#access()}
     * directly, so the boot refusal and the runtime governance can never disagree about which routes
     * count as authenticated (ADR-0009 single-reporter, the {@link #normalizePrefix} precedent).
     *
     * @param anchor        the route's resolved anchor, {@code null} when the route is unanchored
     * @param effectiveAuth the route's effective auth posture
     * @return the effective access level; {@link AccessLevel#AUTHENTICATED} whenever the effective
     *         auth requires authentication
     */
    public static AccessLevel effectiveAccessLevel(@Nullable AnchorConfig anchor, AuthConfig effectiveAuth) {
        if (effectiveAuth.require() != Require.NONE) {
            return AccessLevel.AUTHENTICATED;
        }
        return anchor == null ? AccessLevel.PUBLIC : anchor.access();
    }

    /**
     * Emits the route's boot posture line. The profile logged is the <em>resolved effective</em>
     * one — the route's own {@code security_filter.profile} when declared, otherwise the
     * gateway-wide {@code security_defaults} fallback. It is deliberately NOT the raw declared
     * value with a {@code "none"} placeholder for unset: {@code minimal} is a real mode, so that
     * placeholder would report a partial-disable posture for every route that merely omits the knob.
     */
    private static void logPosture(ResolvedRoute route, SecurityProfile globalProfile) {
        String anchorName = route.anchor() != null ? route.anchor() : NO_ANCHOR_NAME;
        SecurityFilterConfig securityFilter = route.effectiveSecurityFilter();
        SecurityProfile effectiveProfile = SecurityProfile
                .parse(securityFilter == null ? null : securityFilter.profile())
                .orElse(globalProfile);
        LOGGER.info(ConfigLogMessages.INFO.ROUTE_POSTURE, route.id(), anchorName, route.effectiveAuth().require(),
                effectiveProfile.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The gateway-wide effective profile: the declared {@code security_defaults.profile}, or
     * {@link SecurityProfile#DEFAULT_PROFILE} when the block (or the knob) is omitted.
     * <p>
     * Shared with {@code ConfigValidator}'s fail-closed {@code profile: minimal} refusal so the boot
     * refusal resolves the gateway-wide fallback through exactly this chain.
     *
     * @param gateway the bound gateway document
     * @return the resolved gateway-wide profile
     */
    public static SecurityProfile globalProfile(GatewayConfig gateway) {
        SecurityDefaultsConfig securityDefaults = gateway.securityDefaults();
        return SecurityProfile.parse(securityDefaults == null ? null : securityDefaults.profile())
                .orElse(SecurityProfile.DEFAULT_PROFILE);
    }

    private static void warnOnWeakeningOverride(RouteConfig route, EndpointConfig endpoint,
            @Nullable AnchorConfig anchor) {
        if (anchor == null) {
            return;
        }
        if (anchor.securityFilter() != null && route.securityFilter() != null) {
            LOGGER.warn(ConfigLogMessages.WARN.ANCHOR_POLICY_OVERRIDDEN, route.id(), anchor.name(),
                    "security_filter");
        }
        if (!anchor.allowedMethods().isEmpty() && !endpoint.allowedMethods().isEmpty()) {
            LOGGER.warn(ConfigLogMessages.WARN.ANCHOR_POLICY_OVERRIDDEN, route.id(), anchor.name(),
                    "allowed_methods");
        }
    }

    private static UpstreamDefaultsConfig resolveDefaults(GatewayConfig gateway, EndpointConfig endpoint) {
        UpstreamDefaultsConfig endpointDefaults = endpoint.upstreamDefaults();
        if (endpointDefaults != null) {
            return endpointDefaults;
        }
        UpstreamDefaultsConfig gatewayDefaults = gateway.upstreamDefaults();
        return gatewayDefaults == null ? UpstreamDefaultsConfig.defaults() : gatewayDefaults;
    }

    private static List<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint,
            @Nullable AnchorConfig anchor) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return List.copyOf(endpoint.allowedMethods());
        }
        if (anchor != null && !anchor.allowedMethods().isEmpty()) {
            return List.copyOf(anchor.allowedMethods());
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
        WebSocketConfig websocket = route.websocket();
        if (websocket == null) {
            return Set.of();
        }
        Set<String> origins = new LinkedHashSet<>();
        for (String origin : websocket.allowedOrigins()) {
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
