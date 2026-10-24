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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteMatcher;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the immutable {@link RouteTable} from the validated configuration
 * (pipeline step 8).
 * <p>
 * The builder merges the routes of the <em>enabled endpoints only</em> — disabled
 * endpoints contribute no rows — and orders them <em>exact-first</em>: every
 * {@code match.path} (exact) route precedes every {@code match.path_prefix} route, exact
 * routes ordered by descending path length then lexically, prefix routes by descending
 * normalized {@code path_prefix} length (most specific first) then lexically. For the same
 * address an exact route therefore always wins over a prefix route.
 * <p>
 * Each route resolves exactly one of three terminal actions (ADR-0014 and its Amendment A1):
 * {@code asset} (static content), {@code redirect} (answered at the gateway) or — when it
 * declares neither — {@code upstream} (proxy). An endpoint's {@code base_url} alias is
 * resolved through the topology only when the endpoint carries at least one proxy route
 * ({@link RouteConfig#isProxyRoute()}); asset-only and redirect-only endpoints need none.
 * <p>
 * The builder materializes each route's effective auth, effective {@code allowed_methods},
 * effective {@code security_filter} / {@code security_headers}, effective retry
 * / not-modified toggles, the effective {@code forward} filter (whose
 * per-dimension positive-list / negative-list / forward-all posture is carried
 * wholesale, deny lists included), the effective {@code cookies} block (the nothing-crosses
 * {@code allow: none} for an asset or redirect route, which has no upstream), the effective upstream
 * base path (the route-level
 * {@code upstream.path} appended to the alias-derived base path when declared), and the
 * needed scope set ({@code oidc.scopes} united with the owning endpoint's {@code scopes})
 * into a {@link Route}. The route derives the compiled {@link RouteMatcher} and the effective
 * {@code allowed_paths} whitelist from those values itself, so neither derivation is restated here;
 * the builder adds only the one derivation the route cannot make on its own — for a proxy route
 * that opts into {@code upstream.rewrite_location} (absent meaning off, no inheritance) — its
 * {@link LocationRewriter}. The boot-only components (protocol processor, security posture and
 * asset source) are attached later by the edge's route assembler and are left unset here, so this
 * pass starts nothing. Every policy block is resolved through its
 * {@link PolicyBlock} descriptor — the single place the inheritance chains live
 * (gateway → anchor → endpoint → route, nearest declared scope wins, wholesale replacement —
 * ADR-0007) — and it is resolved here, once, so the request pipeline never re-implements
 * a chain and never consults an anchor. The effective posture of each
 * route is emitted to the boot log; an endpoint- or route-level declaration replacing an
 * anchor-provided block is logged as a boot WARN for every block whose descriptor opts in
 * ({@link PolicyBlock#overrideWarn()}).
 * <p>
 * The collaborators are supplied as method arguments and the builder carries no
 * framework imports: the offline configuration check runs it through
 * {@code ConfigBootPipeline} before the framework starts (ADR-0061).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
// java:S6539 (a class should not depend on too many other classes) is suppressed here rather than
// fixed, and the reasoning is recorded at the site because the rule is fighting the design.
//
// What the dependencies ARE matters more than how many. Nearly all of them are DATA records from the
// single config.model package, not behavioural collaborators: this class is the ONE assembly point
// that maps the whole configuration record model onto routing.Route, so naming every record type is
// precisely its job. The metric counts breadth of a mapping surface as if it were entanglement.
//
// Further extraction was considered and rejected on the terms the ADRs set, not for convenience:
//   - The gateway -> anchor -> endpoint -> route inheritance chains (ADR-0007) already live in ONE
//     place, the PolicyBlock descriptors, which this class and ConfigValidator both resolve through.
//     What remains here is the mapping itself: resolveRoute has to name every resolved block type to
//     feed the Route builder, so moving that orchestration into another class would not drop
//     the aggregate coupling, it would only spread it across two files plus a new carrier type.
//   - normalizePrefix, effectiveAccessLevel, globalProfile and effectiveProfile are documented SHARED SEAMS that
//     ConfigValidator resolves through (ADR-0009 single-reporter), so relocating them would break the
//     single-implementation property their own javadoc asserts and let the boot refusal and the
//     runtime governance drift apart.
//
// If this class ever grows real behavioural collaborators (as opposed to more model records), that is
// the signal to revisit the split — the suppression covers the mapping breadth, not unbounded growth.
@SuppressWarnings("java:S6539")
public final class RouteTableBuilder {

    private static final CuiLogger LOGGER = new CuiLogger(RouteTableBuilder.class);

    /** The display fallback for an absent anchor name in {@link #logPosture}. */
    private static final String NO_ANCHOR_NAME = "none";

    /** The default {@code websocket.idle_timeout_seconds} applied when a WebSocket route omits it. */
    private static final int DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS = 300;

    /**
     * The route-table order: exact routes before prefix routes, then the longer ordering key
     * first, then the ordering key lexically for a deterministic tie-break.
     */
    private static final Comparator<Route> ROUTE_ORDER = Comparator
            .comparing((Route route) -> !route.match().isExact())
            .thenComparing(Comparator.comparingInt((Route route) -> orderingKey(route).length()).reversed())
            .thenComparing(RouteTableBuilder::orderingKey);

    /**
     * Builds the route table from the enabled endpoints and the resolved topology.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the endpoints to merge; disabled entries are skipped
     * @param topology  the resolved topology providing each endpoint's upstream
     * @return the immutable, exact-first then longest-prefix-ordered route table
     * @throws RouteTableException when an enabled endpoint carrying a proxy route declares no
     *                             {@code base_url} or its alias does not resolve, or a route has
     *                             no resolvable effective auth
     */
    public RouteTable build(GatewayConfig gateway, List<EndpointConfig> endpoints, ResolvedTopology topology) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(endpoints, "endpoints");
        Objects.requireNonNull(topology, "topology");

        List<Route> resolved = new ArrayList<>();
        for (EndpointConfig endpoint : endpoints) {
            if (!endpoint.enabled()) {
                continue;
            }
            ResolvedUpstream upstream = resolveEndpointUpstream(endpoint, topology);
            for (RouteConfig route : endpoint.routes()) {
                resolved.add(resolveRoute(PolicyBlock.ScopeChain.of(gateway, endpoint, route), upstream, topology));
            }
        }

        resolved.sort(ROUTE_ORDER);
        return new RouteTable(resolved);
    }

    /**
     * Resolves the endpoint's {@code base_url} alias through the topology — but only when the
     * endpoint carries at least one proxy route ({@link RouteConfig#isProxyRoute()}). An endpoint
     * serving only {@code asset} and/or {@code redirect} routes needs no upstream, so its
     * {@code base_url} is neither required nor resolved here.
     *
     * @return the alias-resolved upstream, or {@code null} when the endpoint has no proxy route
     * @throws RouteTableException when the endpoint has a proxy route but declares no
     *                             {@code base_url}, or the declared alias does not resolve
     */
    private static @Nullable ResolvedUpstream resolveEndpointUpstream(EndpointConfig endpoint,
            ResolvedTopology topology) {
        if (endpoint.routes().stream().noneMatch(RouteConfig::isProxyRoute)) {
            return null;
        }
        String baseUrl = endpoint.baseUrl();
        if (baseUrl == null) {
            throw new RouteTableException(
                    "enabled endpoint '%s' declares proxy route(s) but no base_url".formatted(endpoint.id()));
        }
        return topology.lookup(baseUrl).orElseThrow(() -> new RouteTableException(
                "unresolved topology alias for enabled endpoint '%s': %s".formatted(endpoint.id(), baseUrl)));
    }

    /**
     * The ordering key of a route: the exact path verbatim for an exact route (an exact path is
     * compared un-normalized, so {@code /a} and {@code /a/} stay distinct), the normalized
     * {@code path_prefix} for a prefix route.
     */
    private static String orderingKey(Route route) {
        return route.match().isExact() ? route.matchKey() : normalizePrefix(route.matchKey());
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

    /**
     * Resolves one route into its {@link Route}: every resolved value plus the location rewriter; the
     * route derives its matcher and {@code allowed_paths} whitelist itself. Every policy block is read through
     * its {@link PolicyBlock} descriptor, so no block chain is restated here. The boot-only components
     * are left unset for the edge's route assembler to attach.
     */
    private static Route resolveRoute(PolicyBlock.ScopeChain chain, @Nullable ResolvedUpstream upstream,
            ResolvedTopology topology) {
        GatewayConfig gateway = chain.gateway();
        EndpointConfig endpoint = chain.endpoint();
        RouteConfig route = chain.route();
        AnchorConfig anchor = chain.anchor();
        AuthConfig auth = PolicyBlock.AUTH.resolve(chain);
        List<HttpMethod> allowedMethods = PolicyBlock.ALLOWED_METHODS.resolve(chain);
        SecurityFilterConfig securityFilter = PolicyBlock.SECURITY_FILTER.resolve(chain);
        SecurityHeadersConfig securityHeaders = PolicyBlock.SECURITY_HEADERS.resolve(chain);
        warnOnAnchorOverride(chain);
        UpstreamDefaultsConfig defaults = PolicyBlock.UPSTREAM_DEFAULTS.resolve(chain);
        UpstreamConfig routeUpstream = route.upstream();
        UpstreamConfig.Retry retry = routeUpstream == null ? null : routeUpstream.retry();
        UpstreamConfig.NotModified notModified = routeUpstream == null ? null : routeUpstream.notModified();
        boolean retryEnabled = routeToggle(retry == null ? null : retry.enabled(), defaults.retryEnabled());
        boolean notModifiedEnabled = routeToggle(notModified == null ? null : notModified.enabled(),
                defaults.notModifiedEnabled());
        // The forward block is carried WHOLESALE, so headers_deny / query_deny ride the block with no
        // per-list resolution. When no scope declares it, the descriptor's fallback is the all-absent
        // block, which is FORWARD-ALL on both dimensions — not a nothing-crosses allowlist.
        ForwardConfig effectiveForward = PolicyBlock.FORWARD.resolve(chain);
        // An asset or redirect route has no upstream to hand a cookie to or relay one from, so it
        // resolves the nothing-crosses block whatever a broader scope declares.
        CookiesConfig effectiveCookies = route.isProxyRoute() ? PolicyBlock.COOKIES.resolve(chain)
                : CookiesConfig.NONE;
        // Written as an explicit null check rather than Objects.requireNonNullElse: Sonar's dataflow
        // does not model requireNonNullElse as null-eliminating, so it carried route.protocol()'s
        // @Nullable onto the result and raised java:S4449 at the first use of the local (PR #146).
        Protocol declaredProtocol = route.protocol();
        Protocol protocol = declaredProtocol != null ? declaredProtocol : Protocol.HTTP;
        CsrfDefence cookieOriginDefence = cookieOriginDefence(route.id(), protocol, effectiveCookies);
        WebSocketConfig websocket = PolicyBlock.WEBSOCKET.resolve(chain);
        Set<String> allowedOrigins = effectiveAllowedOrigins(websocket);
        Integer idleTimeout = resolveWebSocketIdleTimeout(websocket, protocol);
        MatchConfig match = route.match();
        Route.RouteBuilder builder = Route.builder()
                .id(route.id())
                .protocol(protocol)
                .anchor(anchor == null ? null : anchor.name())
                .match(match)
                .effectiveAuth(auth)
                .effectiveAllowedMethods(Set.copyOf(allowedMethods))
                .effectiveSecurityFilter(securityFilter)
                .securityHeaders(securityHeaders)
                .retryEnabled(retryEnabled)
                .notModifiedEnabled(notModifiedEnabled)
                .effectiveForward(effectiveForward)
                .effectiveCookies(effectiveCookies)
                .cookieOriginDefence(cookieOriginDefence)
                .effectiveAllowedOrigins(allowedOrigins)
                .effectiveWebSocketIdleTimeoutSeconds(idleTimeout)
                .neededScopes(neededScopes(gateway, endpoint));
        // A route resolves to exactly one terminal action (ADR-0014 and its Amendment A1): an asset
        // action when the route declares an asset block, a redirect action when it declares a
        // redirect block, otherwise the route proxies to its endpoint upstream. The upstream is
        // present for every proxy route: resolveEndpointUpstream refuses a proxy-route endpoint
        // whose base_url is absent or unresolvable before any of its routes reach this point.
        AssetConfig asset = route.asset();
        RedirectConfig redirect = route.redirect();
        if (asset != null) {
            builder.asset(resolveAsset(route, asset, anchor, auth, topology));
        } else if (redirect != null) {
            builder.redirect(redirect);
        } else {
            ResolvedUpstream effectiveUpstream =
                    applyRouteUpstreamPath(Objects.requireNonNull(upstream, "upstream"), route);
            builder.upstream(effectiveUpstream);
            // Built once here, from the effective upstream (alias base + upstream.path), the match key
            // and the matcher's exactness, so the relay applies a ready mapping and derives nothing per
            // request. Exactness is part of the mapping rule, not a detail of matching: an exact route
            // routes its match key and nothing below it, so the rewriter must not emit a mapping the
            // route's own matcher would reject. An absent rewrite_location key means off.
            if (routeUpstream != null && Boolean.TRUE.equals(routeUpstream.rewriteLocation())) {
                builder.locationRewriter(new LocationRewriter(effectiveUpstream, match.matchKey(), match.isExact()));
            }
        }
        Route resolved = builder.build();
        logPosture(resolved, gateway);
        return resolved;
    }

    /**
     * Builds the same-origin check of a route that forwards browser cookies: one
     * {@link CsrfDefence} per route, trusting the request's received origin plus the block's
     * {@code additional_origins}. None is built for a route that forwards no cookie, and none for a
     * WebSocket route, whose upgrade is gated by its {@code websocket.allowed_origins} instead
     * ({@code CookieRules} requires that list on a cookie-forwarding WebSocket route). A forwarding
     * route whose block turns the check off gets none either, and its id is logged
     * {@link ConfigLogMessages.WARN#COOKIE_SAME_ORIGIN_CHECK_DISABLED} once here.
     *
     * @param routeId  the route id, named by the WARN
     * @param protocol the route's effective protocol
     * @param cookies  the route's effective {@code cookies} block
     * @return the route's same-origin check, or {@code null} when the route carries none
     */
    private static @Nullable CsrfDefence cookieOriginDefence(String routeId, Protocol protocol,
            CookiesConfig cookies) {
        if (protocol == Protocol.WEBSOCKET || !cookies.forwardsAny()) {
            return null;
        }
        if (!cookies.effectiveSameOriginCheck()) {
            LOGGER.warn(ConfigLogMessages.WARN.COOKIE_SAME_ORIGIN_CHECK_DISABLED, routeId);
            return null;
        }
        List<String> additionalOrigins = cookies.additionalOrigins();
        return CsrfDefence.forCookieForwardingRoute(additionalOrigins == null ? List.of() : additionalOrigins);
    }

    /**
     * Materializes the scope set a request on a route of this endpoint needs:
     * {@code oidc.scopes} united with the endpoint's additive {@code scopes}, or the endpoint's
     * {@code scopes} alone when no {@code oidc} block is configured. The union is additive by
     * construction — an endpoint can add scopes but never remove an {@code oidc.scopes} member.
     * It is the single derivation the bearer {@code insufficient_scope} check, the session-route
     * login request and the {@code /bff/login?returnUrl=} resolution all read.
     *
     * @param gateway  the bound gateway document supplying {@code oidc.scopes}
     * @param endpoint the route's owning endpoint supplying its additive {@code scopes}
     * @return the needed scope set, empty when neither level declares a scope
     */
    private static Set<String> neededScopes(GatewayConfig gateway, EndpointConfig endpoint) {
        Set<String> needed = new LinkedHashSet<>();
        OidcConfig oidc = gateway.oidc();
        if (oidc != null) {
            needed.addAll(oidc.scopes());
        }
        needed.addAll(endpoint.scopes());
        return needed;
    }

    /**
     * A route-level {@code upstream} toggle ({@code retry.enabled}, {@code not_modified.enabled}): the
     * route's declared value when present, otherwise the field of the resolved
     * {@code upstream_defaults} block. A field-level override, deliberately outside the whole-block
     * rule of {@link PolicyBlock}: replacing the block wholesale at the route would reset the
     * undeclared toggle to its default.
     *
     * @param declared     the route's declared toggle, {@code null} when the route omits it
     * @param blockDefault the same field of the resolved {@code upstream_defaults} block
     * @return the effective toggle
     */
    private static boolean routeToggle(@Nullable Boolean declared, boolean blockDefault) {
        return declared != null ? declared : blockDefault;
    }

    /**
     * Resolves the WebSocket idle timeout, which exists only for a {@code WEBSOCKET} route: the
     * declared {@code websocket.idle_timeout_seconds} when present, otherwise
     * {@link #DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS}.
     *
     * @param websocket the route's resolved {@code websocket} block, {@code null} when it declares none
     * @param protocol  the route's effective protocol
     * @return the effective timeout in seconds, or {@code null} for a non-WebSocket route
     */
    private static @Nullable Integer resolveWebSocketIdleTimeout(@Nullable WebSocketConfig websocket,
            Protocol protocol) {
        if (protocol != Protocol.WEBSOCKET) {
            return null;
        }
        Integer declaredIdleTimeout = websocket == null ? null : websocket.idleTimeoutSeconds();
        return declaredIdleTimeout != null ? declaredIdleTimeout : DEFAULT_WEBSOCKET_IDLE_TIMEOUT_SECONDS;
    }

    /**
     * Materializes the route-level {@code upstream.path} into the route's effective upstream base
     * path. A route that declares a non-blank {@code upstream.path} <em>appends</em> it to the
     * alias-derived base path (ADR-0004 Amendment A2): the two are joined on exactly one
     * {@code /}, so the effective base path is
     * {@code stripTrailingSlashes(alias base path) + "/" + stripLeadingSlashes(upstream.path)} — no
     * doubled and no missing slash, whichever side the operator wrote one on. Both helpers remove
     * <em>repeated</em> slashes at the seam, so an alias ending {@code /anything//} joined with
     * {@code //graphql} still yields {@code /anything/graphql}. The forward URI is
     * then reconstructed as {@code stripTrailingSlash(effective base path) + remainder-after-match-key}
     * by {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage#upstreamRequestUri}.
     * <p>
     * Aliases therefore carry environments and routes carry paths: an alias
     * {@code http://go-httpbin:8080/anything} with {@code upstream.path: /graphql} forwards to
     * {@code /anything/graphql}. A gRPC route rides an alias with an empty base path, so its
     * {@code upstream.path: /{package}.{Service}} is the whole effective base path and the full
     * method path reaches the upstream unchanged. The alias host / port / scheme are carried
     * through unchanged, so neither sharing key is affected: the clients are keyed by
     * {@link de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget} (scheme, host, port and
     * the forced-HTTP/2 flag), the resilience guards by
     * {@link de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape} (that upstream target
     * plus the route's retry setting), and the base path is part of neither. A route without a
     * non-blank {@code upstream.path} keeps the alias-derived base path unchanged — the default
     * proxy behavior.
     *
     * @param aliasUpstream the endpoint's alias-resolved upstream (shared across the endpoint's
     *                      routes)
     * @param route         the route whose optional {@code upstream.path} is appended to the base path
     * @return the per-route upstream carrying the effective base path
     */
    // java:S1075 — the "/" below is the RFC 3986 path separator joining two URL path segments, not a
    // hard-coded location: both operands are operator-supplied (the alias base path and upstream.path)
    // and only the separator between them is fixed, because the URI grammar admits no other character
    // for it. Making it configurable, or deriving it from File.separator, would emit a non-URI address.
    @SuppressWarnings("java:S1075")
    private static ResolvedUpstream applyRouteUpstreamPath(ResolvedUpstream aliasUpstream, RouteConfig route) {
        UpstreamConfig upstream = route.upstream();
        String path = upstream == null ? null : upstream.path();
        if (path == null || path.isBlank()) {
            return aliasUpstream;
        }
        // Only the SEAM is normalized here — a trailing slash the route declares is deliberately
        // carried through (shouldKeepDeclaredTrailingSlashOfUpstreamPath pins that), because the
        // consumers normalize it. What those consumers must agree on is HOW MANY they strip:
        // DispatchStage and LocationRewriter both remove the whole trailing run, so a stored
        // '/upload//' cannot make the rewrite and the follow-up dispatch disagree about the path.
        String effectiveBasePath = stripTrailingSlashes(aliasUpstream.basePath()) + "/" + stripLeadingSlashes(path);
        return new ResolvedUpstream(aliasUpstream.scheme(), aliasUpstream.host(), aliasUpstream.port(),
                effectiveBasePath);
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }

    /**
     * Materializes a route's asset terminal action (ADR-0014). A {@code directory}
     * source carries its configured root together with its optional {@code index} and
     * {@code fallback} file names (AS-12; an {@code upstream} source carries neither — the
     * configuration validator refuses them there); an {@code upstream} source resolves its
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
                yield ResolvedAsset.directory(directory, access, asset.index(), asset.fallback());
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
     * <p>
     * The line also carries the resolved {@code auth.session_fallback} flag for every route, so a
     * {@code require: bearer} route that additionally serves the session branch is visible at boot
     * rather than reading as a plain bearer route.
     */
    private static void logPosture(Route route, GatewayConfig gateway) {
        String anchorName = route.anchor() != null ? route.anchor() : NO_ANCHOR_NAME;
        SecurityProfile effectiveProfile = effectiveProfile(route.effectiveSecurityFilter(), gateway);
        AuthConfig effectiveAuth = route.effectiveAuth();
        LOGGER.info(ConfigLogMessages.INFO.ROUTE_POSTURE, route.id(), anchorName, effectiveAuth.require(),
                effectiveAuth.effectiveSessionFallback(), effectiveProfile.name().toLowerCase(Locale.ROOT));
    }

    /**
     * A route's effective inbound-filter profile: the {@code profile} of its resolved
     * {@code security_filter} block when the block declares one, otherwise the gateway-wide
     * {@link #globalProfile(GatewayConfig) profile}. A field-level fallback, deliberately outside the
     * whole-block rule of {@link PolicyBlock}: a route block declaring no {@code profile} falls back
     * to the gateway-wide value, never to the profile of the block it replaced.
     * <p>
     * A shared seam: {@code ConfigValidator}'s fail-closed {@code profile: minimal} refusal reads the
     * profile through this same method that the boot posture line reports, so the two can never
     * disagree about which routes run under {@code minimal} (ADR-0009 single-reporter, the
     * {@link #normalizePrefix} precedent).
     *
     * @param securityFilter the route's resolved {@code security_filter} block, {@code null} when no
     *                       scope declares one
     * @param gateway        the bound gateway document supplying {@code security_defaults.profile}
     * @return the effective profile
     */
    public static SecurityProfile effectiveProfile(@Nullable SecurityFilterConfig securityFilter,
            GatewayConfig gateway) {
        Objects.requireNonNull(gateway, "gateway");
        return SecurityProfile.parse(securityFilter == null ? null : securityFilter.profile())
                .orElseGet(() -> globalProfile(gateway));
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

    /**
     * Announces every anchor-declared block a nearer scope replaces wholesale. The rule is derived
     * from the descriptors, not restated per block: for each {@link PolicyBlock} that opts in
     * ({@link PolicyBlock#overrideWarn()}), one {@link ConfigLogMessages.WARN#ANCHOR_POLICY_OVERRIDDEN}
     * is emitted when the route's anchor declares the block <em>and</em> at least one nearer scope —
     * endpoint or route — declares it too. A route whose endpoint and route both replace the anchor
     * block is announced once, not twice, naming the nearest declaring scope: the one the block is
     * actually resolved from ({@link PolicyBlock#declared}). The record also names the owning
     * endpoint, so the operator can find the replacing declaration. An unanchored route has no
     * anchor policy to override.
     *
     * @param chain the route's scope chain
     */
    private static void warnOnAnchorOverride(PolicyBlock.ScopeChain chain) {
        AnchorConfig anchor = chain.anchor();
        if (anchor == null) {
            return;
        }
        for (PolicyBlock<?> block : PolicyBlock.ALL) {
            if (!block.overrideWarn()) {
                continue;
            }
            Set<PolicyBlock.Scope> declaring = block.declaringScopes(chain);
            if (replacesAnchor(declaring)) {
                LOGGER.warn(ConfigLogMessages.WARN.ANCHOR_POLICY_OVERRIDDEN, chain.route().id(),
                        chain.endpoint().id(), anchor.name(), block.yamlKey(), nearestScope(declaring));
            }
        }
    }

    /**
     * The nearest of a block's declaring scopes, rendered as it appears in the override WARN —
     * {@code endpoint} or {@code route}. It is the scope {@link PolicyBlock#declared} resolves the
     * block from, because {@link PolicyBlock.Scope} orders the scopes broadest first.
     *
     * @param declaring the scopes declaring one block for one route; never empty
     * @return the lower-case name of the nearest declaring scope
     */
    private static String nearestScope(Set<PolicyBlock.Scope> declaring) {
        return Collections.max(declaring).name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a set of declaring scopes holds the anchor together with a nearer scope, so the nearer
     * declaration replaces the anchor's.
     *
     * @param declaring the scopes declaring one block for one route
     * @return {@code true} when the anchor and at least one nearer scope declare the block
     */
    private static boolean replacesAnchor(Set<PolicyBlock.Scope> declaring) {
        return declaring.contains(PolicyBlock.Scope.ANCHOR)
                && declaring.stream().anyMatch(scope -> scope.compareTo(PolicyBlock.Scope.ANCHOR) > 0);
    }

    /**
     * The materialized WebSocket {@code allowed_origins} allowlist, lower-cased once at
     * assembly for case-insensitive host matching (scheme and port are already
     * case-insensitive). Iteration order is not significant — origin acceptance is an
     * exact-membership test, so the set may be defensively re-copied downstream without
     * any ordering guarantee. Empty for a route that declares no {@code websocket} block.
     *
     * @param websocket the route's resolved {@code websocket} block, {@code null} when it declares none
     */
    private static Set<String> effectiveAllowedOrigins(@Nullable WebSocketConfig websocket) {
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
