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

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.api.config.ConfigLogMessages;
import de.cuioss.sheriff.api.config.RouteTableBuilder;
import de.cuioss.sheriff.api.config.load.ConfigError;
import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.AnchorConfig;
import de.cuioss.sheriff.api.config.model.AnchorType;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.api.config.model.OidcConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.config.model.TlsConfig;
import de.cuioss.sheriff.api.config.validation.rule.ValidationRule;
import de.cuioss.tools.logging.CuiLogger;

/**
 * Runs the cross-cutting configuration rules (pipeline step 7) that cannot be
 * expressed structurally in the D2 JSON Schemas, aggregating every violation in a
 * single pass.
 * <p>
 * Each rule is a {@link ValidationRule}; {@link #validate} executes them all and
 * returns the combined, file- and path-annotated {@link ConfigError} list — never
 * stopping at the first problem. Disabled endpoints have already been dropped by the
 * enablement resolver, so the rules that only concern live routes (alias
 * resolvability, effective-method membership, anchor namespace membership) see
 * enabled endpoints only. Structural verb rules (a config naming {@code TRACE}/
 * {@code CONNECT}) are enforced upstream by the schema and the {@link HttpMethod}
 * enum, which cannot represent those verbs, so no post-binding rule is needed for
 * them.
 * <p>
 * The anchor rules (ADR-0007) — pairwise-disjoint anchor prefixes, declared-anchor
 * existence, route/namespace membership agreement, the non-weakenable auth floor,
 * the per-route auth resolvability (every route must resolve an auth posture from
 * its own {@code auth}, its endpoint, or a declared anchor), and the anchor-aware
 * effective-auth completeness check — all collect into the same shared
 * {@code errors} list with file/pointer context and never fail fast.
 * <p>
 * The fail-closed access→auth matrix (ADR-0013) adds per-anchor checks: a
 * {@code type: bff} anchor requires {@code access: authenticated}; an
 * {@code access: authenticated} anchor requires a non-{@code none} auth floor whose
 * backing block is present; and an {@code access: public} anchor must not declare an
 * auth block. These collect into the same shared list and never fail fast.
 * <p>
 * Framework-agnostic (ADR-0005): the rule set is supplied at construction and the
 * validator carries no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigValidator {

    private static final CuiLogger LOGGER = new CuiLogger(ConfigValidator.class);

    private static final int SUPPORTED_VERSION = 1;
    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String PASSTHROUGH_SNI_POINTER = "/tls/passthrough_sni";
    private static final String ANCHORS_POINTER = "/anchors";
    private static final String ENDPOINT_ANCHOR_POINTER = "/endpoint/anchor";
    private static final String ENDPOINT_ROUTES_POINTER = "/endpoint/routes";
    private static final String FORWARDED_TRUSTED_POINTER = "/forwarded/trusted_proxies";
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;
    private static final int BROAD_PREFIX_IPV4 = 8;
    private static final int BROAD_PREFIX_IPV6 = 32;
    private static final String WILDCARD_ORIGIN = "*";
    private static final String REQUIRE_NONE = "none";
    private static final String REQUIRE_BEARER = "bearer";
    private static final String REQUIRE_SESSION = "session";

    private static final List<ValidationRule> DEFAULT_RULES = List.of(
            (gateway, endpoints, topology, errors) -> validateVersion(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateEndpointIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteDisjointness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateBaseUrlResolvable(endpoints, topology, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorPrefixDisjointness(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorReferencesExist(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorNamespaceMembership(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteAuthResolvable(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorAuthFloor(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateEffectiveAuth(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAccessAuthMatrix(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateMethodMembership(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateForwardedTrust(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMode(gateway, errors),
            (gateway, endpoints, topology, errors) -> validatePassthroughHostCollision(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validatePassthroughAliasResolvable(gateway, topology, errors));

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
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "duplicate route id: " + route.id()));
                }
            }
        }
    }

    /**
     * Rule: no two enabled routes share a (normalized) {@code match.path_prefix}
     * without being distinguished by host, method, or a header matcher. The
     * same-prefix disjointness check runs here in the all-violations pass rather than
     * being thrown during route-table assembly (ADR-0009 single-reporter principle);
     * prefix normalization makes {@code /api} and {@code /api/} collide.
     */
    private static void validateRouteDisjointness(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        List<RouteWithOwner> routes = new ArrayList<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                routes.add(new RouteWithOwner(endpoint, route));
            }
        }
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                RouteWithOwner first = routes.get(i);
                RouteWithOwner second = routes.get(j);
                String firstPrefix = RouteTableBuilder.normalizePrefix(first.route().match().pathPrefix());
                String secondPrefix = RouteTableBuilder.normalizePrefix(second.route().match().pathPrefix());
                if (firstPrefix.equals(secondPrefix)
                        && overlaps(first.route().match(), second.route().match())) {
                    errors.add(new ConfigError(endpointFile(first.endpoint()), ENDPOINT_ROUTES_POINTER,
                            "routes '%s' and '%s' share prefix '%s' and are not disjoint".formatted(
                                    first.route().id(), second.route().id(), firstPrefix)));
                }
            }
        }
    }

    private record RouteWithOwner(EndpointConfig endpoint, RouteConfig route) {
    }

    private static boolean overlaps(MatchConfig first, MatchConfig second) {
        return hostsOverlap(first, second) && methodsOverlap(first, second) && !headersDistinguish(first, second);
    }

    private static boolean hostsOverlap(MatchConfig first, MatchConfig second) {
        Optional<String> firstHost = first.host();
        Optional<String> secondHost = second.host();
        if (firstHost.isEmpty() || secondHost.isEmpty()) {
            return true;
        }
        return firstHost.get().equalsIgnoreCase(secondHost.get());
    }

    private static boolean methodsOverlap(MatchConfig first, MatchConfig second) {
        return first.methods().isEmpty() || second.methods().isEmpty()
                || !Collections.disjoint(first.methods(), second.methods());
    }

    private static boolean headersDistinguish(MatchConfig first, MatchConfig second) {
        for (HeaderMatcher headerA : first.headers()) {
            for (HeaderMatcher headerB : second.headers()) {
                if (headerA.name().equalsIgnoreCase(headerB.name())
                        && (valuesDistinguish(headerA, headerB) || presenceDistinguishes(headerA, headerB))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valuesDistinguish(HeaderMatcher headerA, HeaderMatcher headerB) {
        Optional<String> valueA = headerA.value();
        Optional<String> valueB = headerB.value();
        return valueA.isPresent() && valueB.isPresent() && !valueA.get().equals(valueB.get());
    }

    private static boolean presenceDistinguishes(HeaderMatcher headerA, HeaderMatcher headerB) {
        Optional<Boolean> presentA = headerA.present();
        Optional<Boolean> presentB = headerB.present();
        return presentA.isPresent() && presentB.isPresent() && !presentA.get().equals(presentB.get());
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

    /**
     * Rule: anchor {@code path_prefix} values are pairwise disjoint — no anchor
     * prefix (normalized) is a path-prefix of another (ADR-0007).
     */
    private static void validateAnchorPrefixDisjointness(GatewayConfig gateway, List<ConfigError> errors) {
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
     */
    private static void validateAnchorReferencesExist(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            endpoint.anchor().filter(name -> !gateway.anchors().containsKey(name))
                    .ifPresent(name -> errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ANCHOR_POINTER,
                            "endpoint '%s' references undefined anchor '%s'".formatted(endpoint.id(), name))));
            for (RouteConfig route : endpoint.routes()) {
                route.anchor().filter(name -> !gateway.anchors().containsKey(name))
                        .ifPresent(name -> errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                "route '%s' references undefined anchor '%s'".formatted(route.id(), name))));
            }
        }
    }

    /**
     * Rules: (3) every enabled route's {@code match.path_prefix} lies inside its
     * declared anchor's namespace; (4) every enabled route whose path lies inside
     * any anchor namespace declares exactly that anchor — an undeclared squatter
     * fails the boot (ADR-0007).
     */
    private static void validateAnchorNamespaceMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        if (gateway.anchors().isEmpty()) {
            return;
        }
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                Optional<String> declaredName = declaredAnchorName(endpoint, route);
                String routePrefix = route.match().pathPrefix();
                declaredName.map(gateway.anchors()::get).filter(Objects::nonNull).ifPresent(anchor -> {
                    if (!prefixContains(anchor.pathPrefix(), routePrefix)) {
                        errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                "route '%s' path '%s' is not inside its declared anchor '%s' namespace '%s'"
                                        .formatted(route.id(), routePrefix, anchor.name(), anchor.pathPrefix())));
                    }
                });
                for (AnchorConfig anchor : gateway.anchors().values()) {
                    if (prefixContains(anchor.pathPrefix(), routePrefix)
                            && !declaredName.map(anchor.name()::equals).orElse(false)) {
                        errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                "route '%s' path '%s' lies inside anchor '%s' namespace '%s' but does not declare it"
                                        .formatted(route.id(), routePrefix, anchor.name(), anchor.pathPrefix())));
                    }
                }
            }
        }
    }

    /**
     * Rule: every enabled route must resolve an auth posture through the same
     * {@code route → endpoint → declared-anchor} chain the route table uses
     * ({@link #effectiveAuth}); a route for which none of the three supplies an
     * {@code auth} block fails the boot (ADR-0007). Checking per route — rather than
     * once per endpoint — closes two gaps of a per-endpoint anchor check: it no longer
     * falsely rejects an endpoint whose every route supplies its own auth, and it
     * catches a route that overrides to a different, auth-less anchor via
     * {@code route.anchor} (or omits auth entirely) even when the endpoint-level anchor
     * would have satisfied a per-endpoint check. This surfaces the failure here, in the
     * all-violations pass (ADR-0009), instead of letting it escape to a
     * {@code RouteTableException} thrown during route-table assembly.
     */
    private static void validateRouteAuthResolvable(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (effectiveAuth(gateway, endpoint, route).isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' has no resolvable auth: neither the route, its endpoint '%s', nor a declared anchor provides an auth posture"
                                    .formatted(route.id(), endpoint.id())));
                }
            }
        }
    }

    /**
     * Rule: where the route's anchor declares {@code auth.require} of {@code bearer}
     * or {@code session}, the route's effective auth must not resolve to
     * {@code require: none} — the anchor floor cannot be weakened (ADR-0007).
     */
    private static void validateAnchorAuthFloor(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                Optional<AnchorConfig> anchor = resolveAnchor(gateway, endpoint, route);
                if (anchor.isEmpty()) {
                    continue;
                }
                AnchorConfig anchorConfig = anchor.get();
                Optional<String> anchorRequire = anchorConfig.auth().map(AuthConfig::require)
                        .filter(require -> !REQUIRE_NONE.equals(require));
                if (anchorRequire.isPresent() && REQUIRE_NONE.equals(effectiveRequire(gateway, endpoint, route))) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' effective auth 'none' weakens the anchor '%s' floor '%s'"
                                    .formatted(route.id(), anchorConfig.name(), anchorRequire.get())));
                }
            }
        }
    }

    private static void validateEffectiveAuth(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Set<String> requires = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                requires.add(effectiveRequire(gateway, endpoint, route));
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

    /**
     * Rule: the fail-closed access→auth matrix on every anchor (ADR-0013). A
     * {@code type: bff} anchor must declare {@code access: authenticated}; an
     * {@code access: authenticated} anchor must declare a non-{@code none} auth floor
     * whose backing block is present (a {@code bearer} floor needs
     * {@code token_validation} issuers, a {@code session} floor needs an {@code oidc}
     * block); and an {@code access: public} anchor must not declare an auth block.
     * Every violation collects into the shared list; the rule never fails fast.
     */
    private static void validateAccessAuthMatrix(GatewayConfig gateway, List<ConfigError> errors) {
        for (AnchorConfig anchor : gateway.anchors().values()) {
            String pointer = ANCHORS_POINTER + "/" + anchor.name();
            if (anchor.type() == AnchorType.BFF && anchor.access() != AccessLevel.AUTHENTICATED) {
                errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "anchor '%s' is type 'bff' and must declare access: authenticated".formatted(anchor.name())));
            }
            if (anchor.access() == AccessLevel.PUBLIC) {
                if (anchor.auth().isPresent()) {
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
        String require = anchor.auth().map(AuthConfig::require).orElse(REQUIRE_NONE);
        if (REQUIRE_NONE.equals(require)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' is access: authenticated but declares no non-'none' auth floor"
                            .formatted(anchor.name())));
            return;
        }
        if (REQUIRE_BEARER.equals(require)
                && gateway.tokenValidation().map(tv -> tv.issuers().isEmpty()).orElse(true)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated bearer floor requires token_validation with at least one issuer"
                            .formatted(anchor.name())));
        }
        if (REQUIRE_SESSION.equals(require) && gateway.oidc().isEmpty()) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated session floor requires an oidc block".formatted(anchor.name())));
        }
    }

    private static void validateMethodMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                Set<HttpMethod> allowed = effectiveAllowedMethods(gateway, endpoint,
                        resolveAnchor(gateway, endpoint, route));
                for (HttpMethod method : route.match().methods()) {
                    if (!allowed.contains(method)) {
                        errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                "route '%s' matches method %s outside the effective allowed_methods"
                                        .formatted(route.id(), method)));
                    }
                }
            }
        }
    }

    /**
     * Rule: every {@code trusted_proxies} entry is a well-formed CIDR; the union of
     * the parsed ranges per address family must not cover the entire IPv4 or IPv6
     * space — catching a single full-space CIDR <em>and</em> complementary
     * combinations such as {@code 0.0.0.0/1} + {@code 128.0.0.0/1}. Individually very
     * broad — but not total — prefixes (shorter than {@code /8} for IPv4 or
     * {@code /32} for IPv6) are surfaced as a boot WARN. The parsed range set is
     * retained for a later per-request trust decision (Plan 04) (D5).
     */
    private static void validateForwardedTrust(GatewayConfig gateway, List<ConfigError> errors) {
        gateway.forwarded().ifPresent(forwarded -> {
            List<CidrRange> ipv4 = new ArrayList<>();
            List<CidrRange> ipv6 = new ArrayList<>();
            for (String cidr : forwarded.trustedProxies()) {
                Optional<CidrRange> parsed = parseCidr(cidr);
                if (parsed.isEmpty()) {
                    errors.add(new ConfigError(GATEWAY_FILE, FORWARDED_TRUSTED_POINTER,
                            "malformed trusted_proxies CIDR: " + cidr));
                    continue;
                }
                CidrRange range = parsed.get();
                (range.bits() == IPV4_BITS ? ipv4 : ipv6).add(range);
            }
            checkFamilyTrust(ipv4, IPV4_BITS, BROAD_PREFIX_IPV4, "IPv4", errors);
            checkFamilyTrust(ipv6, IPV6_BITS, BROAD_PREFIX_IPV6, "IPv6", errors);
        });
    }

    private static void checkFamilyTrust(List<CidrRange> ranges, int bits, int broadPrefix, String family,
            List<ConfigError> errors) {
        if (ranges.isEmpty()) {
            return;
        }
        if (coversEntireSpace(ranges, bits)) {
            errors.add(new ConfigError(GATEWAY_FILE, FORWARDED_TRUSTED_POINTER,
                    "trusted_proxies cover the entire %s address space; a trust-all range is not permitted"
                            .formatted(family)));
            return;
        }
        for (CidrRange range : ranges) {
            if (range.prefixLength() < broadPrefix) {
                LOGGER.warn(ConfigLogMessages.WARN.BROAD_TRUSTED_PROXY, range.cidr(),
                        Integer.toString(range.prefixLength()));
            }
        }
    }

    /**
     * Whether the union of the supplied ranges covers the whole {@code [0, 2^bits-1]}
     * address space, merging contiguous or overlapping ranges (so complementary
     * halves are caught).
     */
    private static boolean coversEntireSpace(List<CidrRange> ranges, int bits) {
        List<CidrRange> sorted = new ArrayList<>(ranges);
        sorted.sort((first, second) -> first.start().compareTo(second.start()));
        BigInteger max = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger nextExpected = BigInteger.ZERO;
        for (CidrRange range : sorted) {
            if (range.start().compareTo(nextExpected) > 0) {
                return false;
            }
            BigInteger endPlusOne = range.end().add(BigInteger.ONE);
            if (endPlusOne.compareTo(nextExpected) > 0) {
                nextExpected = endPlusOne;
            }
        }
        return nextExpected.compareTo(max) > 0;
    }

    /**
     * Parses a {@code address/prefix} CIDR into its inclusive address range, or an
     * empty optional when the entry is malformed (not exactly one {@code /}, a
     * non-numeric or out-of-range prefix, or an address that is not an IP literal).
     */
    private static Optional<CidrRange> parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0 || cidr.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int bits = bytes.length * 8;
        if (prefixLength < 0 || prefixLength > bits) {
            return Optional.empty();
        }
        BigInteger value = new BigInteger(1, bytes);
        int hostBits = bits - prefixLength;
        BigInteger start = value.shiftRight(hostBits).shiftLeft(hostBits);
        BigInteger end = start.add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE);
        return Optional.of(new CidrRange(cidr, bits, prefixLength, start, end));
    }

    private record CidrRange(String cidr, int bits, int prefixLength, BigInteger start, BigInteger end) {
    }

    private static void validateCors(GatewayConfig gateway, List<ConfigError> errors) {
        checkCors(gateway.securityHeaders(), "/security_headers/cors", errors);
        for (AnchorConfig anchor : gateway.anchors().values()) {
            checkCors(anchor.securityHeaders(), "/anchors/%s/security_headers/cors".formatted(anchor.name()), errors);
        }
    }

    private static void checkCors(Optional<SecurityHeadersConfig> securityHeaders, String pointer,
            List<ConfigError> errors) {
        securityHeaders.flatMap(SecurityHeadersConfig::cors).ifPresent(cors -> {
            if (cors.allowCredentials().orElse(false) && cors.allowedOrigins().contains(WILDCARD_ORIGIN)) {
                errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "wildcard origin '*' is not permitted together with allow_credentials"));
            }
        });
    }

    private static void validateSessionMode(GatewayConfig gateway, List<ConfigError> errors) {
        gateway.oidc().flatMap(OidcConfig::session).ifPresent(session ->
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

    private static void validatePassthroughHostCollision(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Map<String, String> passthrough = passthroughSni(gateway);
        if (passthrough.isEmpty()) {
            return;
        }
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                routeHost(route).ifPresent(host -> {
                    for (String sniHost : passthrough.keySet()) {
                        if (sniHost.equalsIgnoreCase(host)) {
                            errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                                    "route '%s' matches host '%s', which is relayed at L4 by passthrough_sni and is never routed"
                                            .formatted(route.id(), sniHost)));
                        }
                    }
                });
            }
        }
    }

    private static void validatePassthroughAliasResolvable(GatewayConfig gateway, ResolvedTopology topology,
            List<ConfigError> errors) {
        for (Map.Entry<String, String> entry : passthroughSni(gateway).entrySet()) {
            String alias = entry.getValue();
            Optional<ResolvedUpstream> upstream = topology.lookup(alias);
            if (upstream.isEmpty()) {
                errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                        "unresolved topology alias '%s' referenced by passthrough_sni host '%s'"
                                .formatted(alias, entry.getKey())));
                continue;
            }
            String basePath = upstream.get().basePath();
            if (!basePath.isEmpty() && !"/".equals(basePath)) {
                errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                        "passthrough_sni alias '%s' must resolve to an origin without a base path, but resolved to '%s'"
                                .formatted(alias, basePath)));
            }
        }
    }

    private static Map<String, String> passthroughSni(GatewayConfig gateway) {
        return gateway.tls().map(TlsConfig::passthroughSni).orElseGet(Map::of);
    }

    private static Optional<String> routeHost(RouteConfig route) {
        return route.match().host().map(host -> host.toLowerCase(Locale.ROOT)).filter(host -> !host.isEmpty());
    }

    private static Optional<String> declaredAnchorName(EndpointConfig endpoint, RouteConfig route) {
        return route.anchor().or(endpoint::anchor);
    }

    private static Optional<AnchorConfig> resolveAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        return declaredAnchorName(endpoint, route).map(gateway.anchors()::get).filter(Objects::nonNull);
    }

    /**
     * The route's effective auth, resolved through the wholesale
     * {@code route → endpoint → declared-anchor} replacement chain (ADR-0007) — the
     * same order {@code RouteTableBuilder} materializes. Empty when none of the three
     * declares an {@code auth} block.
     */
    private static Optional<AuthConfig> effectiveAuth(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        return route.auth().or(endpoint::auth)
                .or(() -> resolveAnchor(gateway, endpoint, route).flatMap(AnchorConfig::auth));
    }

    private static String effectiveRequire(GatewayConfig gateway, EndpointConfig endpoint, RouteConfig route) {
        return effectiveAuth(gateway, endpoint, route).map(AuthConfig::require).orElse(REQUIRE_NONE);
    }

    private static Set<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint,
            Optional<AnchorConfig> anchor) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(endpoint.allowedMethods());
        }
        Optional<List<HttpMethod>> anchorMethods = anchor.map(AnchorConfig::allowedMethods)
                .filter(methods -> !methods.isEmpty());
        if (anchorMethods.isPresent()) {
            return EnumSet.copyOf(anchorMethods.get());
        }
        if (!gateway.allowedMethods().isEmpty()) {
            return EnumSet.copyOf(gateway.allowedMethods());
        }
        return EnumSet.allOf(HttpMethod.class);
    }

    /**
     * Whether {@code candidate} lies within the {@code container} namespace on a
     * segment boundary: an exact match, or a child path under {@code container/}.
     *
     * @param container the owning prefix
     * @param candidate the prefix tested for containment
     * @return {@code true} when {@code candidate} is inside {@code container}
     */
    private static boolean prefixContains(String container, String candidate) {
        String owner = RouteTableBuilder.normalizePrefix(container);
        String child = RouteTableBuilder.normalizePrefix(candidate);
        if ("/".equals(owner)) {
            return true;
        }
        return child.equals(owner) || child.startsWith(owner + "/");
    }

    private static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }
}
