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
package de.cuioss.sheriff.gateway.config.validation;

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


import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

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
 * The fail-closed inbound-filter mode refusal (ADR-0024) adds one more: a route whose effective
 * {@code profile} resolves to {@code minimal} must be neither effectively authenticated nor anchored
 * under a {@code type: bff} anchor. The {@code profile} <em>value range</em> is owned by the bundled
 * JSON Schema, so no post-binding range rule exists here — only this posture refusal.
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
    private static final String EDGE_HARDENING_ADMISSION_POINTER = "/edge_hardening/admission_cap";
    private static final String EDGE_HARDENING_WEBSOCKET_POINTER = "/edge_hardening/websocket_relay_cap";
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;
    private static final int BROAD_PREFIX_IPV4 = 8;
    private static final int BROAD_PREFIX_IPV6 = 32;
    private static final String WILDCARD_ORIGIN = "*";
    private static final String REQUIRE_NONE = "none";
    private static final String REQUIRE_BEARER = "bearer";
    private static final String REQUIRE_SESSION = "session";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_USER_INFO_PATH_POINTER = "/oidc/user_info/path";
    private static final String OIDC_USER_INFO_DEFAULT_VIEW_POINTER = "/oidc/user_info/default_view";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_LOGIN_PATH_POINTER = "/oidc/login/path";
    private static final String OIDC_SESSION_MAX_SESSIONS_POINTER = "/oidc/session/max_sessions";
    private static final String OIDC_SESSION_MAX_COOKIE_SIZE_POINTER = "/oidc/session/max_cookie_size";
    private static final String SECURITY_DEFAULTS_AUTHORIZATION_POINTER =
            "/security_defaults/max_authorization_header_value_length";

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
            (gateway, endpoints, topology, errors) -> validateSecurityProfileMinimal(gateway, endpoints, errors),
            ConfigValidator::validateTerminalAction,
            (gateway, endpoints, topology, errors) -> validateMethodMembership(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateForwardedTrust(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMode(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMaxSessions(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMaxCookieSize(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateUserInfo(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateLoginPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> validatePassthroughHostCollision(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validatePassthroughAliasResolvable(gateway, topology, errors),
            (gateway, endpoints, topology, errors) -> validateWebSocketConfig(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateEdgeHardening(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateAuthorizationHeaderValueLength(gateway, errors));

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

    /**
     * Bounds the operator-facing admission budget. Both caps must admit at least one request — a cap
     * of zero or below would refuse every request (or every relay) and is always a misconfiguration
     * rather than a deliberate posture. The WebSocket sub-budget must additionally stay within the
     * general pool it draws from: a {@code websocket_relay_cap} above {@code admission_cap} can never
     * bind, so it would silently disable the sub-cap the operator meant to impose.
     */
    private static void validateEdgeHardening(GatewayConfig gateway, List<ConfigError> errors) {
        EdgeHardeningConfig hardening = gateway.edgeHardening();
        if (hardening == null) {
            return;
        }
        Integer admissionCap = hardening.admissionCap();
        if (admissionCap != null && admissionCap < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_ADMISSION_POINTER,
                    "admission_cap must be at least 1 (was %d)".formatted(admissionCap)));
        }
        Integer relayCap = hardening.websocketRelayCap();
        if (relayCap != null && relayCap < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_WEBSOCKET_POINTER,
                    "websocket_relay_cap must be at least 1 (was %d)".formatted(relayCap)));
        }
        int admission = hardening.effectiveAdmissionCap();
        int relay = hardening.effectiveWebsocketRelayCap();
        if (admission >= 1 && relay >= 1 && relay > admission) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_WEBSOCKET_POINTER,
                    "websocket_relay_cap %d must not exceed admission_cap %d — the relay budget is a "
                            .formatted(relay, admission)
                            + "sub-budget of the admission pool and could never bind"));
        }
    }

    /**
     * Bounds the operator-declared {@code Authorization} header-value carve-out: a
     * <em>declared</em> {@code security_defaults.max_authorization_header_value_length} must not fall
     * below the resolved gateway-wide baseline {@code maxHeaderValueLength}.
     * <p>
     * The key exists to <em>raise</em> the non-skippable pre-route floor's header-value cap for
     * {@code Authorization} values alone (ADR-0019), because a bearer token plus its {@code Bearer }
     * prefix routinely exceeds the {@code strict} preset's cap and would otherwise be rejected before
     * route selection. A value below the baseline is therefore not a carve-out at all but a per-header
     * <em>tightening</em> expressed through the one key that cannot express one, and it would
     * silently make {@code Authorization} the single most-restricted header while reading like a
     * relaxation key. That is always a misconfiguration rather than a deliberate posture, the same
     * shape {@link #validateEdgeHardening} applies to the admission budget.
     * <p>
     * The refusal deliberately offers no downward path, because none exists: neither remedy an
     * operator might reach for can lower an {@code Authorization} value cap. A route's
     * {@code security_filter} cannot, since
     * {@code ThoroughChecksStage.carveOutConfigurationFor} floors the effective cap back up to
     * {@code max(routeConfig.maxHeaderValueLength(), budget)} for the carved-out header name; and
     * {@code security_defaults.profile} cannot either, since the budget resolves through
     * {@link SecurityDefaultsConfig#effectiveMaxAuthorizationHeaderValueLength()} — the declared key
     * or the fixed {@link SecurityDefaultsConfig#DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH} —
     * and does not track the profile at all. Both govern general header values instead.
     * <p>
     * The comparison is genuinely cross-cutting and cannot move into the bundled JSON Schema: the
     * baseline is the <em>resolved</em> profile's preset limit, which the schema cannot see. The
     * schema owns the value range ({@code integer}, {@code minimum: 1}) alone. The baseline is
     * resolved through the same {@link RouteTableBuilder#globalProfile} plus
     * {@link SecurityProfile#limitsProfile} chain the edge performs, so the boot refusal and the
     * runtime cap can never disagree.
     * <p>
     * An omitted key is never refused — it resolves to
     * {@link SecurityDefaultsConfig#DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH}. No upper bound is
     * enforced here: the gateway's 16 KiB inbound header-block transport limit already caps what can
     * arrive, and it is owned by the edge layer — restating it as a second constant in this
     * framework-agnostic class (ADR-0005) would fork the value.
     */
    private static void validateAuthorizationHeaderValueLength(GatewayConfig gateway, List<ConfigError> errors) {
        SecurityDefaultsConfig securityDefaults = gateway.securityDefaults();
        Integer declared = securityDefaults == null ? null : securityDefaults.maxAuthorizationHeaderValueLength();
        if (declared == null) {
            return;
        }
        SecurityProfile global = RouteTableBuilder.globalProfile(gateway);
        int baseline = SecurityProfile.limitsProfile(global, global).preset().maxHeaderValueLength();
        if (declared < baseline) {
            errors.add(new ConfigError(GATEWAY_FILE, SECURITY_DEFAULTS_AUTHORIZATION_POINTER,
                    ("max_authorization_header_value_length %d is below the resolved baseline "
                            + "max_header_value_length %d; the key raises the pre-route cap for "
                            + "Authorization values only and can never lower it — declare at least "
                            + "the baseline here, as security_defaults.profile and a route "
                            + "security_filter bound general header values rather than the "
                            + "Authorization carve-out")
                            .formatted(declared, baseline)));
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
                if (headerA.name().equalsIgnoreCase(headerB.name())
                        && (valuesDistinguish(headerA, headerB) || presenceDistinguishes(headerA, headerB))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valuesDistinguish(HeaderMatcher headerA, HeaderMatcher headerB) {
        String valueA = headerA.value();
        String valueB = headerB.value();
        return valueA != null && valueB != null && !valueA.equals(valueB);
    }

    private static boolean presenceDistinguishes(HeaderMatcher headerA, HeaderMatcher headerB) {
        Boolean presentA = headerA.present();
        Boolean presentB = headerB.present();
        return presentA != null && presentB != null && !presentA.equals(presentB);
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
     * Rules: (3) every enabled route's {@code match.path_prefix} lies inside its
     * declared anchor's namespace; (4) every enabled route whose path lies inside
     * any anchor namespace declares exactly that anchor — an undeclared squatter
     * fails the boot (ADR-0007).
     * <p>
     * {@code protocol: grpc} routes are exempt from both containment rules. A gRPC
     * method path is the service-rooted {@code /{package}.{Service}/{Method}} whose
     * service segment is a single opaque path segment (dots, no slashes), so it is
     * structurally never nested under a gateway path namespace on a segment boundary
     * — no non-root anchor {@code path_prefix} can contain it, and stock gRPC clients
     * cannot be told to prepend a namespace prefix. Only the path-prefix containment
     * geometry is skipped for gRPC routes: a gRPC route still declares an anchor for
     * its ADR-0013 type/access classification and its ADR-0007 auth floor, and every
     * other anchor rule (declared-anchor existence, pairwise-disjoint prefixes,
     * non-weakenable auth floor, access→auth matrix) stays enforced for it unchanged.
     * {@code websocket} and {@code http} routes keep full containment enforcement.
     */
    private static void validateAnchorNamespaceMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        if (gateway.anchors().isEmpty()) {
            return;
        }
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (effectiveProtocol(route) == Protocol.GRPC) {
                    // gRPC routes ride a service-rooted single-segment path that no gateway path
                    // namespace can contain on a segment boundary — exempt from containment (rules 3 & 4).
                    continue;
                }
                String declaredName = declaredAnchorName(endpoint, route);
                String routePrefix = route.match().pathPrefix();
                checkRouteInsideDeclaredAnchorNamespace(gateway, endpoint, route, declaredName, routePrefix, errors);
                checkRouteDeclaresContainingAnchor(gateway, endpoint, route, declaredName, routePrefix, errors);
            }
        }
    }

    /**
     * Rule (3): an enabled route's {@code match.path_prefix} must lie inside its declared anchor's
     * namespace (ADR-0007). A no-op when the route declares no anchor, or its declared anchor name
     * is not a defined anchor.
     */
    private static void checkRouteInsideDeclaredAnchorNamespace(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable String declaredName, String routePrefix, List<ConfigError> errors) {
        AnchorConfig anchor = declaredName == null ? null : gateway.anchors().get(declaredName);
        if (anchor != null && !prefixContains(anchor.pathPrefix(), routePrefix)) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' path '%s' is not inside its declared anchor '%s' namespace '%s'"
                            .formatted(route.id(), routePrefix, anchor.name(), anchor.pathPrefix())));
        }
    }

    /**
     * Rule (4): an enabled route whose path lies inside any anchor namespace must declare exactly
     * that anchor — an undeclared squatter fails the boot (ADR-0007).
     */
    private static void checkRouteDeclaresContainingAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable String declaredName, String routePrefix, List<ConfigError> errors) {
        for (AnchorConfig anchor : gateway.anchors().values()) {
            if (prefixContains(anchor.pathPrefix(), routePrefix)
                    && !anchor.name().equals(declaredName)) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        "route '%s' path '%s' lies inside anchor '%s' namespace '%s' but does not declare it"
                                .formatted(route.id(), routePrefix, anchor.name(), anchor.pathPrefix())));
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
                if (effectiveAuth(gateway, endpoint, route) == null) {
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
                AnchorConfig anchor = resolveAnchor(gateway, endpoint, route);
                AuthConfig anchorAuth = anchor == null ? null : anchor.auth();
                if (anchorAuth == null) {
                    continue;
                }
                String anchorRequire = anchorAuth.require();
                if (!REQUIRE_NONE.equals(anchorRequire)
                        && REQUIRE_NONE.equals(effectiveRequire(gateway, endpoint, route))) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' effective auth 'none' weakens the anchor '%s' floor '%s'"
                                    .formatted(route.id(), anchor.name(), anchorRequire)));
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
        if (requires.contains(REQUIRE_BEARER) && lacksConfiguredIssuer(gateway)) {
            errors.add(new ConfigError(GATEWAY_FILE, "/token_validation",
                    "effective auth 'bearer' requires token_validation with at least one issuer"));
        }
        if (requires.contains(REQUIRE_SESSION) && gateway.oidc() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc", "effective auth 'session' requires an oidc block"));
        }
    }

    /**
     * Whether the gateway declares no usable bearer-validation backing — either no
     * {@code token_validation} block at all, or one that declares no issuer. The single shared
     * predicate behind both the effective-auth rule and the {@code access: authenticated} anchor
     * matrix, so the two can never disagree about what backs a {@code bearer} posture.
     */
    private static boolean lacksConfiguredIssuer(GatewayConfig gateway) {
        TokenValidationConfig tokenValidation = gateway.tokenValidation();
        return tokenValidation == null || tokenValidation.issuers().isEmpty();
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
        String require = anchorAuth == null ? REQUIRE_NONE : anchorAuth.require();
        if (REQUIRE_NONE.equals(require)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' is access: authenticated but declares no non-'none' auth floor"
                            .formatted(anchor.name())));
            return;
        }
        if (REQUIRE_BEARER.equals(require) && lacksConfiguredIssuer(gateway)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated bearer floor requires token_validation with at least one issuer"
                            .formatted(anchor.name())));
        }
        if (REQUIRE_SESSION.equals(require) && gateway.oidc() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "anchor '%s' access: authenticated session floor requires an oidc block".formatted(anchor.name())));
        }
    }

    /**
     * Rule: the fail-closed inbound-filter mode refusal (ADR-0024). A route whose effective
     * {@code profile} resolves to {@link SecurityProfile#MINIMAL} must be neither effectively
     * authenticated nor anchored under a {@code type: bff} anchor. {@code minimal} drops the
     * url-parameter name/value validation, and dropping it in front of a token- or session-bearing
     * surface is never a deliberate posture — so the boot fails rather than serving the weakened
     * route.
     * <p>
     * The access level is derived through {@link RouteTableBuilder#effectiveAccessLevel} — the same
     * seam the route table uses — rather than from {@link AnchorConfig#access()} directly: a route or
     * endpoint may legally <em>strengthen</em> a {@code public}-access anchor's auth floor (ADR-0007
     * forbids weakening it, not strengthening it), and reading the anchor's static {@code access}
     * would under-refuse exactly those routes. The gateway-wide case needs no separate rule: a
     * {@code security_defaults.profile: minimal} reaching an authenticated or BFF route through the
     * fallback resolves to {@code minimal} here and is refused identically.
     * <p>
     * The message names the route, the refusing dimension and the remedy, and echoes no configured
     * scalar value. Every violation collects into the shared list; the rule never fails fast
     * (ADR-0009).
     */
    private static void validateSecurityProfileMinimal(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                AnchorConfig anchor = resolveAnchor(gateway, endpoint, route);
                if (effectiveProfile(gateway, route, anchor) != SecurityProfile.MINIMAL) {
                    continue;
                }
                List<String> refusals = minimalRefusalDimensions(gateway, endpoint, route, anchor);
                if (!refusals.isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            ("route '%s' resolves inbound-filter profile 'minimal' but %s; 'minimal' is refused on "
                                    + "authenticated and bff routes — declare profile 'strict' or 'lenient' for this "
                                    + "route, or stop routing it through a 'minimal' fallback")
                                    .formatted(route.id(), String.join(" and ", refusals))));
                }
            }
        }
    }

    /**
     * The dimensions on which a {@code profile: minimal} route is refused: an
     * effectively-authenticated access level, a {@code type: bff} anchor, or both. An empty list
     * means the route may legally run under {@code minimal}.
     */
    private static List<String> minimalRefusalDimensions(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, @Nullable AnchorConfig anchor) {
        List<String> refusals = new ArrayList<>();
        AuthConfig auth = effectiveAuth(gateway, endpoint, route);
        if (auth != null && RouteTableBuilder.effectiveAccessLevel(anchor, auth) == AccessLevel.AUTHENTICATED) {
            refusals.add("its effective access level is 'authenticated'");
        }
        if (anchor != null && anchor.type() == AnchorType.BFF) {
            refusals.add("its anchor '%s' is type 'bff'".formatted(anchor.name()));
        }
        return refusals;
    }

    /**
     * The route's effective inbound-filter profile, resolved through the same
     * {@code route → declared-anchor} wholesale {@code security_filter} replacement chain
     * {@code RouteTableBuilder} materializes, falling back to the gateway-wide
     * {@link RouteTableBuilder#globalProfile(GatewayConfig) profile} (itself
     * {@link SecurityProfile#DEFAULT_PROFILE} when undeclared). The whole {@code security_filter}
     * block is replaced, not merged: a route declaring a block without a {@code profile} falls back
     * to the gateway-wide value, never to its anchor's profile.
     */
    private static SecurityProfile effectiveProfile(GatewayConfig gateway, RouteConfig route,
            @Nullable AnchorConfig anchor) {
        SecurityFilterConfig securityFilter = route.securityFilter();
        if (securityFilter == null && anchor != null) {
            securityFilter = anchor.securityFilter();
        }
        String declaredProfile = securityFilter == null ? null : securityFilter.profile();
        return SecurityProfile.parse(declaredProfile)
                .orElseGet(() -> RouteTableBuilder.globalProfile(gateway));
    }

    /**
     * Rule: the terminal-action / anchor-type consistency matrix (ADR-0014). A route whose
     * resolving anchor is {@code type: asset} must declare an {@code asset} terminal action; a
     * route under a {@code proxy} / {@code bff} anchor (or with no anchor) must not — its terminal
     * action is the endpoint upstream. An {@code asset} block on a non-asset route is a boot
     * failure. For a declared asset action, the source-specific field must be present and, for a
     * {@code source: upstream} action, its topology alias must resolve. Every violation collects
     * into the shared list; the rule never fails fast.
     */
    private static void validateTerminalAction(GatewayConfig gateway, List<EndpointConfig> endpoints,
            ResolvedTopology topology, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                validateRouteTerminalAction(gateway, endpoint, route, topology, errors);
            }
        }
    }

    /**
     * The per-route half of the terminal-action rule (ADR-0014): the anchor-type / asset-action
     * consistency check, plus the source-field check for a declared asset action. Every violation
     * collects into the shared list; the check never fails fast.
     */
    private static void validateRouteTerminalAction(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, ResolvedTopology topology, List<ConfigError> errors) {
        AnchorConfig anchor = resolveAnchor(gateway, endpoint, route);
        boolean assetAnchor = anchor != null && anchor.type() == AnchorType.ASSET;
        AssetConfig asset = route.asset();
        if (assetAnchor && asset == null) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' resolves to asset anchor '%s' but declares no asset terminal action"
                            .formatted(route.id(), anchor.name())));
        } else if (!assetAnchor && asset != null) {
            String context = anchor == null
                    ? "the route is unanchored"
                    : "its anchor '%s' is type '%s'".formatted(anchor.name(),
                    anchor.type().name().toLowerCase(Locale.ROOT));
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' declares an asset terminal action but %s; an asset action requires an asset-type anchor"
                            .formatted(route.id(), context)));
        }
        if (asset != null) {
            validateAssetSource(endpoint, route, asset, topology, errors);
        }
    }

    /**
     * The source-field half of the terminal-action rule (ADR-0014): a {@code source: directory}
     * action requires a {@code directory} root; a {@code source: upstream} action requires an
     * {@code upstream} topology alias that resolves.
     */
    // NOSONAR java:S6916 - the rule suggests replacing each case's if with a `when` guard, but
    // guards attach only to PATTERN labels; `case DIRECTORY when ...` on an enum CONSTANT label
    // is a compile error (verified against javac --release 25). Restructuring further would move
    // alias-resolvability error reporting out of this method, which ADR-9 forbids.
    @SuppressWarnings("java:S6916")
    private static void validateAssetSource(EndpointConfig endpoint, RouteConfig route, AssetConfig asset,
            ResolvedTopology topology, List<ConfigError> errors) {
        switch (asset.source()) {
            case DIRECTORY -> {
                if (asset.directory() == null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' declares source: directory but no directory root".formatted(route.id())));
                }
            }
            case UPSTREAM -> {
                String alias = asset.upstream();
                if (alias == null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' declares source: upstream but no upstream alias".formatted(route.id())));
                } else if (topology.lookup(alias).isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' upstream alias '%s' does not resolve in the topology"
                                    .formatted(route.id(), alias)));
                }
            }
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
        ForwardedConfig forwarded = gateway.forwarded();
        if (forwarded == null) {
            return;
        }
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
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
        } catch (IllegalArgumentException _) {
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

    private static void checkCors(@Nullable SecurityHeadersConfig securityHeaders, String pointer,
            List<ConfigError> errors) {
        SecurityHeadersConfig.Cors cors = securityHeaders == null ? null : securityHeaders.cors();
        if (cors == null) {
            return;
        }
        if (Boolean.TRUE.equals(cors.allowCredentials()) && cors.allowedOrigins().contains(WILDCARD_ORIGIN)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "wildcard origin '*' is not permitted together with allow_credentials"));
        }
    }

    /**
     * Rule: the session mode's mandatory companions (D1/D2b).
     * <p>
     * {@code server} mode requires a {@code store}. {@code cookie} mode does <em>not</em> require an
     * {@code encryption_key} — omitting it selects the generate-on-startup key mode, a first-class
     * production option (at the cost of dropping every session on restart and being unshareable
     * across replicas). Its companion rule survives the relaxation: a {@code previous_key} present
     * <em>without</em> an {@code encryption_key} is still invalid, because a decrypt-only rotation
     * key with no current key to roll onto is semantically nonsensical and rotation composes with
     * the passed-key mode only.
     */
    private static void validateSessionMode(GatewayConfig gateway, List<ConfigError> errors) {
        // isCookieMode() / isServerMode() are the SHARED predicates over the mode value the
        // OidcConfig.Session canonical constructor already canonicalized. Comparing against a
        // literal here is what previously let 'Cookie' skip both rules while the edge still read it
        // as active cookie mode and relaxed the pre-route Cookie header-value cap.
        OidcConfig.Session session = oidcSession(gateway);
        if (session == null) {
            return;
        }
        if (session.isCookieMode() && session.encryptionKey() == null && session.previousKey() != null) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc/session/previous_key",
                    "cookie session mode with a previous_key requires an encryption_key — "
                            + "the decrypt-only rotation key composes with the passed-key mode only"));
        }
        if (session.isServerMode() && session.store() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc/session/store",
                    "server session mode requires a store"));
        }
    }

    /**
     * The gateway's {@code oidc.session} block, {@code null} when either the {@code oidc} block or
     * its {@code session} sub-block is absent.
     */
    private static OidcConfig.@Nullable Session oidcSession(GatewayConfig gateway) {
        OidcConfig oidc = gateway.oidc();
        return oidc == null ? null : oidc.session();
    }

    /**
     * Rule: the server-mode session store bound (D1/D3). When
     * {@code oidc.session.max_sessions} is present it must be a positive integer —
     * the bound is a DoS guard on the in-memory store, so a non-positive value is a
     * misconfiguration. A no-op when the bound is omitted (the store applies its own
     * documented default bound).
     */
    private static void validateSessionMaxSessions(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        Integer max = session == null ? null : session.maxSessions();
        if (max != null && max <= 0) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_SESSIONS_POINTER,
                    "oidc session max_sessions must be a positive integer, but was %d".formatted(max)));
        }
    }

    /**
     * Rule: the cookie-mode sealed-cookie size budget (D1). When
     * {@code oidc.session.max_cookie_size} is present it must lie between
     * {@link SealedSessionCookieCodec#COOKIE_VALUE_BUDGET_FLOOR} and
     * {@link SealedSessionCookieCodec#COOKIE_VALUE_BUDGET_CEILING} inclusive.
     * <p>
     * The floor is the encoded length of a structurally minimal sealed value — below it no sealed
     * cookie could ever be emitted, so the boot fails with a clear message rather than every seal
     * failing at runtime. The ceiling keeps the budget below what the transport will carry: the same
     * number also raises the gateway's pre-route {@code Cookie} header-value cap, and a budget at or
     * above the inbound header-block limit would produce a value the seal accepts but the transport
     * rejects with {@code 431}. A no-op when the key is omitted (the codec default applies).
     */
    private static void validateSessionMaxCookieSize(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        Integer size = session == null ? null : session.maxCookieSize();
        if (size != null && (size < SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_FLOOR
                || size > SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING)) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_COOKIE_SIZE_POINTER,
                    "oidc session max_cookie_size must be between %d and %d bytes, but was %d"
                            .formatted(SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_FLOOR,
                                    SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING, size)));
        }
    }

    /**
     * Rule: the session/user-info reserved endpoint (fold, D1). When an
     * {@code oidc.user_info} block is present, its {@code path} must be an absolute
     * gateway path, and every {@code default_view} claim must lie within the
     * {@code allowed_claims} allowlist — the operator-owned allowlist caps
     * disclosure and the default view can never exceed it. An empty allowlist is the
     * secure closed default (nothing disclosed) and is not itself an error. Every
     * violation collects into the shared list; the rule never fails fast.
     */
    private static void validateUserInfo(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        OidcConfig.UserInfo userInfo = oidc == null ? null : oidc.userInfo();
        if (userInfo == null) {
            return;
        }
        String path = userInfo.path();
        if (path != null && !isAbsoluteGatewayPath(path)) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_USER_INFO_PATH_POINTER,
                    "oidc user_info path '%s' must be an absolute gateway path starting with a single '/'"
                            .formatted(path)));
        }
        Set<String> allowed = new HashSet<>(userInfo.allowedClaims());
        for (String claim : userInfo.defaultView()) {
            if (!allowed.contains(claim)) {
                errors.add(new ConfigError(GATEWAY_FILE, OIDC_USER_INFO_DEFAULT_VIEW_POINTER,
                        "oidc user_info default_view claim '%s' is not in allowed_claims; the default view cannot disclose a claim outside the operator allowlist"
                                .formatted(claim)));
            }
        }
    }

    /**
     * Rule: the login-initiation reserved path (fold, D1). When
     * {@code oidc.login.path} is present it must be an absolute gateway path — a
     * schema-relative ({@code //host}) or off-path value is rejected as an
     * open-redirect hazard.
     */
    private static void validateLoginPath(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        OidcConfig.Login login = oidc == null ? null : oidc.login();
        String path = login == null ? null : login.path();
        if (path != null && !isAbsoluteGatewayPath(path)) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_LOGIN_PATH_POINTER,
                    "oidc login path '%s' must be an absolute gateway path starting with a single '/'"
                            .formatted(path)));
        }
    }

    /**
     * Whether {@code path} is an absolute gateway path: it starts with a single
     * {@code /} and is not a schema-relative {@code //host} URL (which would be an
     * open-redirect vector for a reserved path).
     */
    private static boolean isAbsoluteGatewayPath(String path) {
        return path.startsWith("/") && !path.startsWith("//");
    }

    private static void validatePassthroughHostCollision(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Map<String, String> passthrough = passthroughSni(gateway);
        if (passthrough.isEmpty()) {
            return;
        }
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                String host = routeHost(route);
                if (host == null) {
                    continue;
                }
                for (String sniHost : passthrough.keySet()) {
                    if (sniHost.equalsIgnoreCase(host)) {
                        errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                                "route '%s' matches host '%s', which is relayed at L4 by passthrough_sni and is never routed"
                                        .formatted(route.id(), sniHost)));
                    }
                }
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

    /**
     * Rule: the fail-closed WebSocket allowlist contract (ADR-0015). Each
     * {@code protocol: websocket} route is validated by {@link #validateWebSocketRoute}
     * (bearer allowlist, exact-match origins, positive idle timeout). A route that
     * declares a {@code websocket} block while its protocol is <em>not</em>
     * {@code websocket} is rejected here, since those settings would otherwise be
     * silently ignored. Every violation collects into the shared list; the rule never
     * fails fast.
     */
    private static void validateWebSocketConfig(GatewayConfig gateway, List<EndpointConfig> endpoints,
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
     * WebSocket allowlist contract (ADR-0015): a bearer route needs a non-empty
     * {@code allowed_origins}, no origin may contain a wildcard, and a declared
     * {@code idle_timeout_seconds} must be positive. Every violation collects into the
     * shared list; the rule never fails fast.
     */
    private static void validateWebSocketRoute(GatewayConfig gateway, EndpointConfig endpoint, RouteConfig route,
            List<ConfigError> errors) {
        WebSocketConfig websocket = route.websocket();
        List<String> origins = websocket == null ? List.of() : websocket.allowedOrigins();
        if (REQUIRE_BEARER.equals(effectiveRequire(gateway, endpoint, route)) && origins.isEmpty()) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "websocket route '%s' with effective auth 'bearer' must declare a non-empty allowed_origins allowlist (fail-closed)"
                            .formatted(route.id())));
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

    private static Map<String, String> passthroughSni(GatewayConfig gateway) {
        TlsConfig tls = gateway.tls();
        return tls == null ? Map.of() : tls.passthroughSni();
    }

    /** The route's effective protocol — the declared value, or {@link Protocol#HTTP} when omitted. */
    private static Protocol effectiveProtocol(RouteConfig route) {
        Protocol declared = route.protocol();
        return declared != null ? declared : Protocol.HTTP;
    }

    private static @Nullable String routeHost(RouteConfig route) {
        String host = route.match().host();
        if (host == null) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static @Nullable String declaredAnchorName(EndpointConfig endpoint, RouteConfig route) {
        return route.anchor() != null ? route.anchor() : endpoint.anchor();
    }

    private static @Nullable AnchorConfig resolveAnchor(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        String name = declaredAnchorName(endpoint, route);
        return name == null ? null : gateway.anchors().get(name);
    }

    /**
     * The route's effective auth, resolved through the wholesale
     * {@code route → endpoint → declared-anchor} replacement chain (ADR-0007) — the
     * same order {@code RouteTableBuilder} materializes. {@code null} when none of the
     * three declares an {@code auth} block.
     */
    private static @Nullable AuthConfig effectiveAuth(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route) {
        if (route.auth() != null) {
            return route.auth();
        }
        if (endpoint.auth() != null) {
            return endpoint.auth();
        }
        AnchorConfig anchor = resolveAnchor(gateway, endpoint, route);
        return anchor == null ? null : anchor.auth();
    }

    private static String effectiveRequire(GatewayConfig gateway, EndpointConfig endpoint, RouteConfig route) {
        AuthConfig auth = effectiveAuth(gateway, endpoint, route);
        return auth == null ? REQUIRE_NONE : auth.require();
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
