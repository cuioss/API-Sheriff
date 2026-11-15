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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;


import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the anchors: the named path namespaces of
 * {@code gateway.anchors} (ADR-0007) and the access→auth matrix every anchor is held to (ADR-0013).
 * <p>
 * The anchor rules (ADR-0007) are: pairwise-disjoint anchor prefixes
 * ({@link #validateAnchorPrefixDisjointness}), declared-anchor existence
 * ({@link #validateAnchorReferencesExist}), route/namespace membership agreement and
 * anchor-namespace coverage, rule (4b) ({@link #validateAnchorNamespaceMembership}), and the
 * non-weakenable auth floor ({@link #validateAnchorAuthFloor}). The fail-closed access→auth matrix
 * (ADR-0013, {@link #validateAccessAuthMatrix}) adds per-anchor checks: a {@code type: bff} anchor
 * requires {@code access: authenticated}; an {@code access: authenticated} anchor requires a
 * non-{@code none} auth floor whose backing block is present; and an {@code access: public} anchor
 * must not declare an auth block.
 * <p>
 * Policy blocks are resolved only through the {@link PolicyBlock} descriptors over
 * {@link ScopeChain#of}, the same resolution the route table uses — the auth floor reads the anchor's
 * declared {@code auth} block through the anchor-scope accessor of {@link PolicyBlock#AUTH}. Every
 * rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast. Stateless, and therefore safe for concurrent use. Free of framework dependencies as the
 * pre-boot rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class AnchorRules {

    private static final String ENDPOINT_ANCHOR_POINTER = "/endpoint/anchor";

    /**
     * The {@link PolicyBlock#AUTH} accessor reading the {@code auth} block declared at the route's
     * anchor — {@code null} when the chain resolves no anchor or the anchor declares no {@code auth}.
     * Looked up once from the descriptor. Only the auth floor reads the anchor's {@code auth} block
     * this way; the access-to-auth matrix and the authenticated-anchor backing still read
     * {@code anchor.auth()} from the anchor model directly.
     */
    private static final Function<ScopeChain, @Nullable AuthConfig> ANCHOR_AUTH =
            Objects.requireNonNull(PolicyBlock.AUTH.accessors().get(PolicyBlock.Scope.ANCHOR));

    /**
     * Rule: anchor {@code path_prefix} values are pairwise disjoint — no anchor
     * prefix (normalized) is a path-prefix of another (ADR-0007).
     *
     * @param gateway the bound gateway document
     * @param errors  the shared list every violation is appended to
     */
    public static void validateAnchorPrefixDisjointness(GatewayConfig gateway, List<ConfigError> errors) {
        List<AnchorConfig> anchors = new ArrayList<>(gateway.anchors().values());
        for (int i = 0; i < anchors.size(); i++) {
            for (int j = i + 1; j < anchors.size(); j++) {
                AnchorConfig first = anchors.get(i);
                AnchorConfig second = anchors.get(j);
                if (prefixContains(first.pathPrefix(), second.pathPrefix())
                        || prefixContains(second.pathPrefix(), first.pathPrefix())) {
                    errors.add(new ConfigError(GATEWAY_FILE, ANCHORS_POINTER,
                            "anchor prefixes must be pairwise disjoint, but '%s' (%s) and '%s' (%s) overlap"
                                    .formatted(first.name(), first.pathPrefix(), second.name(), second.pathPrefix())));
                }
            }
        }
    }

    /**
     * Rule: every anchor referenced by an endpoint or route is declared in
     * {@code gateway.anchors} (ADR-0007).
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateAnchorReferencesExist(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            String endpointAnchor = endpoint.anchor();
            if (endpointAnchor != null && !gateway.anchors().containsKey(endpointAnchor)) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ANCHOR_POINTER,
                        "endpoint '%s' references undefined anchor '%s'".formatted(endpoint.id(), endpointAnchor)));
            }
            for (RouteConfig route : endpoint.routes()) {
                String routeAnchor = route.anchor();
                if (routeAnchor != null && !gateway.anchors().containsKey(routeAnchor)) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' references undefined anchor '%s'".formatted(route.id(), routeAnchor)));
                }
            }
        }
    }

    /**
     * Rules: (3) every enabled route's match key ({@code match.path_prefix}, or the exact
     * {@code match.path}) lies inside its
     * declared anchor's namespace; (4) every enabled route whose path lies inside
     * any anchor namespace declares exactly that anchor — an undeclared squatter
     * fails the boot (ADR-0007); (4b) the anchor-coverage rule, the mirror of rule (4), enforced by
     * {@code checkRouteContainsUncoveredAnchor} and stated in the configuration reference
     * {@code doc/configuration/validation-rules.adoc} (anchor id {@code anchor-coverage-rule}).
     * <p>
     * {@code protocol: grpc} routes are exempt from rules (3) and (4) — and from those two only.
     * Both are <em>declaration</em>-scoped: they judge a route by where its own match key sits. A
     * gRPC method path is the service-rooted {@code /{package}.{Service}/{Method}} whose
     * service segment is a single opaque path segment (dots, no slashes), so it is
     * structurally never nested under a gateway path namespace on a segment boundary
     * — no non-root anchor {@code path_prefix} can contain it, and stock gRPC clients
     * cannot be told to prepend a namespace prefix.
     * <p>
     * Why rule (4b) is not waived for gRPC routes: it judges the opposite geometry — whether the
     * route's own prefix <em>contains</em> an anchor namespace — so the service-rooted rationale
     * does not reach it. Nothing constrains a gRPC route's declared match key to a bare service
     * path, and selection is protocol-blind ({@code RouteTable.lookup} filters on path alone, and
     * {@code RouteMatcher} carries no protocol dimension), so a gRPC route declaring a broad
     * {@code path_prefix} answers plain HTTP requests inside the swallowed anchor namespace with
     * <em>its</em> auth posture and <em>its</em> {@code security_headers} block — the same CWE-284
     * desync rule (4b) exists to close.
     * <p>
     * Only the two declaration-scoped containment checks are skipped for gRPC routes: a gRPC route
     * still declares an anchor for its ADR-0013 type/access classification and its ADR-0007 auth
     * floor, and every other anchor rule (declared-anchor existence, pairwise-disjoint prefixes,
     * non-weakenable auth floor, access→auth matrix) stays enforced for it unchanged.
     * {@code websocket} and {@code http} routes keep full containment enforcement.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateAnchorNamespaceMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        if (gateway.anchors().isEmpty()) {
            return;
        }
        Set<String> coveredAnchors = anchorsCoveredByOwnPrefixRoute(gateway, endpoints);
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                String declaredName = declaredAnchorName(endpoint, route);
                String routeMatchKey = route.match().matchKey();
                // gRPC routes ride a service-rooted single-segment path that no gateway path namespace
                // can contain on a segment boundary, so the two declaration-scoped containment rules
                // (3) and (4) are skipped for them — and only those two.
                if (effectiveProtocol(route) != Protocol.GRPC) {
                    checkRouteInsideDeclaredAnchorNamespace(gateway, endpoint, route, declaredName, routeMatchKey,
                            errors);
                    checkRouteDeclaresContainingAnchor(gateway, endpoint, route, declaredName, routeMatchKey, errors);
                }
                // Rule 4b judges what the route's prefix CONTAINS, not where its match key sits, and
                // selection is protocol-blind — so it applies to every protocol, gRPC included.
                checkRouteContainsUncoveredAnchor(gateway, endpoint, route, declaredName, coveredAnchors, errors);
            }
        }
    }

    /**
     * Rule (3): an enabled route's match key ({@code match.path_prefix}, or the exact
     * {@code match.path}) must lie inside its declared anchor's namespace (ADR-0007). A no-op when
     * the route declares no anchor, or its declared anchor name is not a defined anchor.
     */
    private static void checkRouteInsideDeclaredAnchorNamespace(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable String declaredName, String routeMatchKey, List<ConfigError> errors) {
        AnchorConfig anchor = declaredName == null ? null : gateway.anchors().get(declaredName);
        if (anchor != null && !prefixContains(anchor.pathPrefix(), routeMatchKey)) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' path '%s' is not inside its declared anchor '%s' namespace '%s'"
                            .formatted(route.id(), routeMatchKey, anchor.name(), anchor.pathPrefix())));
        }
    }

    /**
     * Rule (4): an enabled route whose path lies inside any anchor namespace must declare exactly
     * that anchor — an undeclared squatter fails the boot (ADR-0007).
     */
    private static void checkRouteDeclaresContainingAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable String declaredName, String routeMatchKey, List<ConfigError> errors) {
        for (AnchorConfig anchor : gateway.anchors().values()) {
            if (prefixContains(anchor.pathPrefix(), routeMatchKey)
                    && !anchor.name().equals(declaredName)) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        "route '%s' path '%s' lies inside anchor '%s' namespace '%s' but does not declare it"
                                .formatted(route.id(), routeMatchKey, anchor.name(), anchor.pathPrefix())));
            }
        }
    }

    /**
     * Enforces rule (4b), the anchor-coverage rule — the mirror of rule (4). The rule is stated once,
     * in the configuration reference {@code doc/configuration/validation-rules.adoc} (anchor id
     * {@code anchor-coverage-rule}); {@code AnchorRulesTest} is its behavioural assertion.
     * <p>
     * An exact route returns at once, because an exact route inside an anchor namespace is rule (4)'s
     * case. An anchor the route itself declares is never reported against it, and which anchors count
     * as covered is computed once per validation pass by {@link #anchorsCoveredByOwnPrefixRoute}. The
     * check runs for every protocol — see {@link #validateAnchorNamespaceMembership} for why the gRPC
     * exemption of rules (3) and (4) does not reach this rule.
     */
    private static void checkRouteContainsUncoveredAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable String declaredName, Set<String> coveredAnchors, List<ConfigError> errors) {
        if (route.match().isExact()) {
            return;
        }
        String routePrefix = route.match().matchKey();
        for (AnchorConfig anchor : gateway.anchors().values()) {
            if (!anchor.name().equals(declaredName)
                    && strictlyContains(routePrefix, anchor.pathPrefix())
                    && !coveredAnchors.contains(anchor.name())) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        ("route '%s' path_prefix '%s' contains anchor '%s' namespace '%s' without declaring it, and "
                                + "no route under that anchor covers the namespace; declare a route with "
                                + "path_prefix '%s' under anchor '%s', or narrow this route so it stays outside "
                                + "the namespace")
                                .formatted(route.id(), routePrefix, anchor.name(), anchor.pathPrefix(),
                                        anchor.pathPrefix(), anchor.name())));
            }
        }
    }

    /**
     * The names of the anchors whose whole namespace is served by a prefix route resolving that
     * anchor — a route whose normalized {@code match.path_prefix} equals the normalized anchor
     * {@code path_prefix}. Computed once per validation pass and consumed by
     * {@link #checkRouteContainsUncoveredAnchor}.
     * <p>
     * Three tests, all required: the route is not exact, it narrows on no dimension beyond the path
     * ({@link MatchConfig#narrowsBeyondPath()}), and its normalized match key equals the normalized
     * anchor prefix — the coverage definition of rule (4b) in {@code doc/configuration/validation-rules.adoc}.
     */
    private static Set<String> anchorsCoveredByOwnPrefixRoute(GatewayConfig gateway,
            List<EndpointConfig> endpoints) {
        Set<String> covered = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (route.match().isExact() || route.match().narrowsBeyondPath()) {
                    continue;
                }
                String declaredName = declaredAnchorName(endpoint, route);
                AnchorConfig anchor = declaredName == null ? null : gateway.anchors().get(declaredName);
                if (anchor != null && RouteTableBuilder.normalizePrefix(route.match().matchKey())
                        .equals(RouteTableBuilder.normalizePrefix(anchor.pathPrefix()))) {
                    covered.add(anchor.name());
                }
            }
        }
        return covered;
    }

    /**
     * Rule: where the route's anchor declares {@code auth.require} of {@code bearer}
     * or {@code session}, the route's effective auth must not resolve to
     * {@code require: none} — the anchor floor cannot be weakened (ADR-0007).
     * <p>
     * The anchor's declared {@code auth} block is read through the anchor-scope accessor of
     * {@link PolicyBlock#AUTH}, the descriptor that declares the {@code auth} block once; a route whose
     * chain resolves no anchor, or whose anchor declares no {@code auth}, has no floor to weaken and is
     * skipped.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateAnchorAuthFloor(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                ScopeChain chain = ScopeChain.of(gateway, endpoint, route);
                AnchorConfig anchor = chain.anchor();
                AuthConfig anchorAuth = ANCHOR_AUTH.apply(chain);
                if (anchor == null || !PolicyBlock.isDeclared(anchorAuth)) {
                    continue;
                }
                Require anchorRequire = anchorAuth.require();
                if (anchorRequire != Require.NONE && requireOf(chain) == Require.NONE) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' effective auth 'none' weakens the anchor '%s' floor '%s'"
                                    .formatted(route.id(), anchor.name(), anchorRequire)));
                }
            }
        }
    }

    /**
     * Rule: the fail-closed access→auth matrix on every anchor (ADR-0013). A
     * {@code type: bff} anchor must declare {@code access: authenticated}; an
     * {@code access: authenticated} anchor must declare a non-{@code none} auth floor
     * whose backing block is present (a {@code bearer} floor needs
     * {@code token_validation} issuers, a {@code session} floor needs an {@code oidc}
     * block); and an {@code access: public} anchor must not declare an auth block.
     * Every violation collects into the shared list; the rule never fails fast.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared list every violation is appended to
     */
    public static void validateAccessAuthMatrix(GatewayConfig gateway, List<ConfigError> errors) {
        for (AnchorConfig anchor : gateway.anchors().values()) {
            String pointer = ANCHORS_POINTER + "/" + anchor.name();
            if (anchor.type() == AnchorType.BFF && anchor.access() != AccessLevel.AUTHENTICATED) {
                errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "anchor '%s' is type 'bff' and must declare access: authenticated".formatted(anchor.name())));
            }
            if (anchor.access() == AccessLevel.PUBLIC) {
                if (anchor.auth() != null) {
                    errors.add(new ConfigError(GATEWAY_FILE, pointer,
                            "anchor '%s' is access: public and must not declare an auth block"
                                    .formatted(anchor.name())));
                }
            } else {
                validateAuthenticatedAnchorBacking(gateway, anchor, pointer, errors);
            }
        }
    }

    /**
     * The {@code access: authenticated} half of the matrix (ADR-0013): the anchor must
     * declare a non-{@code none} auth floor, and that floor's backing block must be
     * present — a {@code bearer} floor needs {@code token_validation} issuers, a
     * {@code session} floor needs an {@code oidc} block.
     */
    private static void validateAuthenticatedAnchorBacking(GatewayConfig gateway, AnchorConfig anchor, String pointer,
            List<ConfigError> errors) {
        AuthConfig anchorAuth = anchor.auth();
        Require require = anchorAuth == null ? Require.NONE : anchorAuth.require();
        if (require == Require.NONE) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' is access: authenticated but declares no non-'none' auth floor"
                            .formatted(anchor.name())));
            return;
        }
        if (require == Require.BEARER && lacksConfiguredIssuer(gateway)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated bearer floor requires token_validation with at least one issuer"
                            .formatted(anchor.name())));
        }
        if (require == Require.SESSION && gateway.oidc() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated session floor requires an oidc block".formatted(anchor.name())));
        }
    }
}
