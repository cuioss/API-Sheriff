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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.resolveAnchor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s of the {@code match} block of a route: route disjointness
 * ({@link #validateRouteDisjointness}), header-matcher consistency
 * ({@link #validateHeaderMatcherConsistency}) and the membership of a route's {@code match.methods}
 * in its effective {@code allowed_methods} ({@link #validateMethodMembership}).
 * <p>
 * The header-matcher consistency refusal runs directly after route disjointness: a
 * {@code match.headers} matcher must not declare {@code present: false} together with
 * {@code value}, because {@code present: false} forbids the header the value requires and the
 * two compose with AND, so such a matcher could never match and its route would be silently dead.
 * The same refusal covers two matchers of one route that name the same header (compared exactly as
 * the runtime normalises the names, lower-cased under {@link Locale#ROOT}) and can never hold
 * together — two differing values, or one requiring the
 * header while the other forbids it — because every matcher of a route must hold.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fails fast. Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class RouteMatchingRules {

    /**
     * Rule: no two enabled routes share a path matcher without being distinguished by host, method,
     * or a header matcher. The disjointness check runs here in the all-violations pass rather than
     * being thrown during route-table assembly (ADR-0009 single-reporter principle).
     * <ul>
     *   <li>Two prefix routes collide when their normalized {@code match.path_prefix} values are
     *       equal — prefix normalization makes {@code /api} and {@code /api/} collide.</li>
     *   <li>Two exact routes collide when their {@code match.path} strings are equal. An exact path
     *       is compared un-normalized, so {@code /a} and {@code /a/} are distinct addresses and do
     *       not collide.</li>
     *   <li>An exact route never collides with a prefix route, even for the same string: the exact
     *       route is selected first by design, and the prefix route keeps serving every other path
     *       below it.</li>
     * </ul>
     * A header matcher distinguishes two routes when both name the same header (compared exactly as
     * the runtime normalises the names — see {@code runtimeHeaderName}) and either both declare a
     * {@code value} and the values differ (compared case-sensitively), or one matcher requires the
     * header — it declares
     * {@code present: true} or a {@code value} — while the other forbids it with
     * {@code present: false}. So an exact {@code value} and {@code present: false} are disjoint, while
     * {@code value} vs {@code present: true}, {@code present: true} vs {@code present: true} and
     * {@code present: false} vs {@code present: false} collide. These are exactly the pairs the
     * compiled route matcher can never select for the same request, so the precedence this rule
     * certifies never depends on declaration order.
     *
     * @param endpoints the enabled endpoints
     * @param errors    the shared error accumulator
     */
    public static void validateRouteDisjointness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        List<RouteWithOwner> routes = flattenRoutes(endpoints);
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                checkRoutePairDisjoint(routes.get(i), routes.get(j), errors);
            }
        }
    }

    /**
     * @return every enabled-or-not route across every endpoint, each paired with the endpoint that
     *         declares it — the flat population the pairwise disjointness check iterates
     */
    private static List<RouteWithOwner> flattenRoutes(List<EndpointConfig> endpoints) {
        List<RouteWithOwner> routes = new ArrayList<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                routes.add(new RouteWithOwner(endpoint, route));
            }
        }
        return routes;
    }

    /**
     * Records a disjointness violation for one route pair, or returns having found none. The three
     * refusals are applied in the order the rule states them: two routes of different match forms
     * never collide, two routes with different collision keys never collide, and two routes a host,
     * method or header matcher tells apart never collide.
     */
    private static void checkRoutePairDisjoint(RouteWithOwner first, RouteWithOwner second, List<ConfigError> errors) {
        MatchConfig firstMatch = first.route().match();
        MatchConfig secondMatch = second.route().match();
        if (firstMatch.isExact() != secondMatch.isExact()) {
            return;
        }
        String firstKey = disjointnessKey(firstMatch);
        if (!firstKey.equals(disjointnessKey(secondMatch)) || !overlaps(firstMatch, secondMatch)) {
            return;
        }
        String kind = firstMatch.isExact() ? "path" : "prefix";
        errors.add(new ConfigError(endpointFile(first.endpoint()), ENDPOINT_ROUTES_POINTER,
                "routes '%s' and '%s' share %s '%s' and are not disjoint".formatted(
                        renderForMessage(first.route().id()), renderForMessage(second.route().id()), kind,
                        renderForMessage(firstKey))));
    }

    /**
     * The key two same-form routes collide on: the exact path verbatim, or the normalized prefix.
     */
    private static String disjointnessKey(MatchConfig match) {
        return match.isExact() ? match.matchKey() : RouteTableBuilder.normalizePrefix(match.matchKey());
    }

    private record RouteWithOwner(EndpointConfig endpoint, RouteConfig route) {
    }

    private static boolean overlaps(MatchConfig first, MatchConfig second) {
        return hostsOverlap(first, second) && methodsOverlap(first, second) && !headersDistinguish(first, second);
    }

    private static boolean hostsOverlap(MatchConfig first, MatchConfig second) {
        String firstHost = first.host();
        String secondHost = second.host();
        if (firstHost == null || secondHost == null) {
            return true;
        }
        return firstHost.equalsIgnoreCase(secondHost);
    }

    private static boolean methodsOverlap(MatchConfig first, MatchConfig second) {
        return first.methods().isEmpty() || second.methods().isEmpty()
                || !Collections.disjoint(first.methods(), second.methods());
    }

    private static boolean headersDistinguish(MatchConfig first, MatchConfig second) {
        for (HeaderMatcher headerA : first.headers()) {
            for (HeaderMatcher headerB : second.headers()) {
                if (neverHoldTogether(headerA, headerB)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether no request can satisfy both matchers: they name the same header (their
     * {@link #runtimeHeaderName runtime names} are equal) and either declare differing values or one
     * requires the header the other forbids. Across two routes this makes the routes disjoint; within
     * one route it makes the route dead, since every matcher of a route must hold.
     */
    private static boolean neverHoldTogether(HeaderMatcher headerA, HeaderMatcher headerB) {
        return runtimeHeaderName(headerA).equals(runtimeHeaderName(headerB))
                && (valuesDistinguish(headerA, headerB) || presenceContradicts(headerA, headerB));
    }

    /**
     * The name a header matcher reads at runtime: its configured name lower-cased under
     * {@link Locale#ROOT}, exactly the normalisation {@code RouteMatcher.from} applies before the
     * matcher looks the name up in the lower-case-keyed request header map.
     * <p>
     * Every comparison of two {@code match.headers} names in this validator goes through here, never
     * through {@link String#equalsIgnoreCase}. The two disagree outside ASCII: {@code equalsIgnoreCase}
     * folds case per character, so a name spelled with U+0130 (capital I with dot above) where another
     * has a plain {@code I} equals it, while full {@code Locale.ROOT} lower-casing maps U+0130 to
     * {@code i} plus a combining dot and so yields two different names. Judging names by per-character
     * folding would let this validator certify two routes disjoint, or refuse a route as dead, on the
     * strength of a name equality the runtime never sees.
     */
    private static String runtimeHeaderName(HeaderMatcher header) {
        return header.name().toLowerCase(Locale.ROOT);
    }

    private static boolean valuesDistinguish(HeaderMatcher headerA, HeaderMatcher headerB) {
        String valueA = headerA.value();
        String valueB = headerB.value();
        return valueA != null && valueB != null && !valueA.equals(valueB);
    }

    /**
     * Whether one matcher requires the header another one forbids — no request can satisfy both, so
     * the two routes can never match the same request.
     */
    private static boolean presenceContradicts(HeaderMatcher headerA, HeaderMatcher headerB) {
        return (requiresPresence(headerA) && forbidsPresence(headerB))
                || (requiresPresence(headerB) && forbidsPresence(headerA));
    }

    /**
     * A matcher requires the header when it declares {@code present: true} or a {@code value} — an
     * exact value can only match a header that is there.
     */
    private static boolean requiresPresence(HeaderMatcher header) {
        return header.value() != null || Boolean.TRUE.equals(header.present());
    }

    private static boolean forbidsPresence(HeaderMatcher header) {
        return Boolean.FALSE.equals(header.present());
    }

    /**
     * Rule: a header matcher must not declare {@code present: false} together with {@code value}.
     * <p>
     * {@code present: false} forbids the header while {@code value} requires it, and the two compose
     * with AND, so such a matcher can never hold: its route is silently dead. The boot refuses it
     * rather than letting the declaration stand inert. {@code present: true} with {@code value} is
     * coherent (the value already implies presence) and stays valid.
     * <p>
     * The rule also judges every pair of matchers within one route that name the same header
     * (compared exactly as {@code RouteMatcher} normalises the names — see
     * {@code runtimeHeaderName}). Every matcher of a route must hold, so a pair that can never hold
     * together — both declare a value and the values
     * differ, or one requires the header ({@code value} or {@code present: true}) while the other
     * forbids it ({@code present: false}) — leaves the route silently dead, and the boot refuses it
     * with one error per conflicting pair. Redundant but compatible pairs ({@code value} with
     * {@code present: true}, identical values, or matching presence flags) stay valid.
     * <p>
     * Route ids and header names are echoed through {@link RuleSupport#renderForMessage}, so they cannot
     * forge boot log lines (CWE-117). Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     *
     * @param endpoints the enabled endpoints
     * @param errors    the shared error accumulator
     */
    public static void validateHeaderMatcherConsistency(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                List<HeaderMatcher> headers = route.match().headers();
                for (int i = 0; i < headers.size(); i++) {
                    HeaderMatcher header = headers.get(i);
                    if (forbidsPresence(header) && header.value() != null) {
                        errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                ("route '%s' declares header matcher '%s' with both present: false and value; "
                                        + "present: false forbids the header while value requires it, so the "
                                        + "matcher can never match — drop value, or declare present: true")
                                        .formatted(renderForMessage(route.id()), renderForMessage(header.name()))));
                    }
                    checkSameNameMatchersCompatible(endpoint, route, header, headers.subList(i + 1, headers.size()),
                            errors);
                }
            }
        }
    }

    /**
     * Refuses every matcher in {@code laterHeaders} that names the same header as {@code header} and
     * can never hold together with it — one error per conflicting pair, so each pair of one route is
     * reported exactly once.
     */
    private static void checkSameNameMatchersCompatible(EndpointConfig endpoint, RouteConfig route,
            HeaderMatcher header, List<HeaderMatcher> laterHeaders, List<ConfigError> errors) {
        for (HeaderMatcher other : laterHeaders) {
            if (neverHoldTogether(header, other)) {
                String conflict = valuesDistinguish(header, other)
                        ? "they require different values"
                        : "one requires the header while the other forbids it";
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        ("route '%s' declares header matchers '%s' and '%s' that can never hold together: %s; "
                                + "header names compare lower-cased, as the runtime reads them, and every "
                                + "matcher of a route must hold, so the route can never match — drop one "
                                + "matcher, or make them agree")
                                .formatted(renderForMessage(route.id()), renderForMessage(header.name()),
                                        renderForMessage(other.name()), conflict)));
            }
        }
    }

    /**
     * Rule: every method an enabled route names in {@code match.methods} must lie inside the route's
     * effective {@code allowed_methods} — the endpoint's list, else the resolved anchor's, else the
     * gateway's, else every method. A route matching a method its effective allowlist excludes
     * could never be served for it, so the boot refuses it with one error per offending method.
     * Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared error accumulator
     */
    public static void validateMethodMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                Set<HttpMethod> allowed = effectiveAllowedMethods(gateway, endpoint,
                        resolveAnchor(gateway, endpoint, route));
                for (HttpMethod method : route.match().methods()) {
                    if (!allowed.contains(method)) {
                        errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                "route '%s' matches method %s outside the effective allowed_methods"
                                        .formatted(renderForMessage(route.id()), method)));
                    }
                }
            }
        }
    }

    private static Set<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint,
            @Nullable AnchorConfig anchor) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(endpoint.allowedMethods());
        }
        if (anchor != null && !anchor.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(anchor.allowedMethods());
        }
        if (!gateway.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(gateway.allowedMethods());
        }
        return EnumSet.allOf(HttpMethod.class);
    }
}
