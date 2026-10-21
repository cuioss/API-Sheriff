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
package de.cuioss.sheriff.gateway.config.validation;

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ENDPOINT_ROUTES_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.WILDCARD_ORIGIN;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.AnchorRules;
import de.cuioss.sheriff.gateway.config.validation.rule.AssetDefaultsRules;
import de.cuioss.sheriff.gateway.config.validation.rule.AuthRules;
import de.cuioss.sheriff.gateway.config.validation.rule.EdgeRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ForwardingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.LimitRules;
import de.cuioss.sheriff.gateway.config.validation.rule.OidcClientRules;
import de.cuioss.sheriff.gateway.config.validation.rule.OidcSessionRules;
import de.cuioss.sheriff.gateway.config.validation.rule.PortalRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ProtocolRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RouteMatchingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport;
import de.cuioss.sheriff.gateway.config.validation.rule.TerminalActionRules;
import de.cuioss.sheriff.gateway.config.validation.rule.TokenValidationRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule;
import org.jspecify.annotations.Nullable;

/**
 * Runs the cross-cutting configuration rules (pipeline step 7) that cannot be
 * expressed structurally in the D2 JSON Schemas, aggregating every violation in a
 * single pass.
 * <p>
 * The validator is the orchestration of ONE ordered rule list, {@code DEFAULT_RULES}: one entry per
 * rule, in the order the violations are reported. That list is the only place that decides the
 * order. A rule body lives in the concern class of the {@code rule} package that owns its
 * configuration block ({@link PortalRules} for the application portal, for example) or — for a
 * concern not yet extracted — as a private method of this class, registered at its position all the
 * same. The helpers every rule shares (the reporting file, pointer and anchors pointer, the
 * CWE-117-safe echo of a configured value, anchor resolution, which protocol a route rides, which
 * {@code auth.require} a route resolves, the wildcard-origin token, the RFC 9110 media-type token,
 * anchor-namespace containment and whether the gateway lacks a configured token issuer) live once, in
 * {@link RuleSupport}.
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
 * The anchor rules (ADR-0007, owned by {@link AnchorRules}) — pairwise-disjoint anchor prefixes,
 * declared-anchor existence, route/namespace membership agreement, anchor-namespace coverage (no
 * route outside an anchor may swallow the anchor's namespace unless a route under the anchor covers
 * it) and the non-weakenable auth floor — are joined by two auth rules owned by {@link AuthRules}:
 * the per-route auth resolvability (every route must resolve an auth posture from its own
 * {@code auth}, its endpoint, or a declared anchor) and the anchor-aware effective-auth completeness
 * check. All of them collect into the same shared {@code errors} list with file/pointer context and
 * never fail fast.
 * <p>
 * The session-fallback refusal (ADR-0055, owned by {@link AuthRules}) adds one more: every declared
 * {@code auth} block — anchor, endpoint or route — that sets {@code session_fallback: true} must
 * declare {@code require: bearer}, since the key only splits a bearer posture into a bearer and a
 * session branch, and the gateway must declare an {@code oidc} block, since the session branch needs
 * the BFF runtime exactly as a {@code require: session} route does. The two refusals are independent and carry fixed detail
 * texts. The anchor floor comparison is untouched by the key — it compares {@code require} only —
 * and the effective-auth completeness check keeps demanding {@code token_validation} issuers for a
 * session-fallback route's bearer posture, so such a route is held to both backing blocks.
 * <p>
 * The fail-closed access→auth matrix (ADR-0013, owned by {@link AnchorRules}) adds per-anchor checks: a
 * {@code type: bff} anchor requires {@code access: authenticated}; an
 * {@code access: authenticated} anchor requires a non-{@code none} auth floor whose
 * backing block is present; and an {@code access: public} anchor must not declare an
 * auth block. These collect into the same shared list and never fail fast.
 * <p>
 * The fail-closed inbound-filter mode refusal (ADR-0024, owned by {@link LimitRules}) adds one more:
 * a route whose effective {@code profile} resolves to {@code minimal} must be neither effectively
 * authenticated nor anchored under a {@code type: bff} anchor. The {@code profile} <em>value
 * range</em> is owned by the bundled JSON Schema, so no post-binding range rule exists — only this
 * posture refusal.
 * <p>
 * The forward-mode exclusivity refusal (owned by {@link ForwardingRules}) adds one more: neither an
 * endpoint nor a route may declare both {@code *_allow} and {@code *_deny} for the same {@code forward}
 * dimension, because each dimension resolves exactly one of the three modes (positive-list,
 * negative-list, forward-all). The schema bounds the {@code forward} key <em>set</em>; a mutual
 * exclusion between two of its keys is cross-cutting and lives with the post-binding rules.
 * <p>
 * The session-cookie name refusal (owned by {@link OidcSessionRules}) adds one more: a declared
 * {@code oidc.session.cookie_name} must keep the {@code __Host-} guarantee the gateway-owned cookies
 * carry, be writable verbatim into a {@code Set-Cookie} header, and not collide with a gateway-owned
 * cookie. The schema declares the key
 * an unrestricted string; this rule is the single enforcing authority.
 * <p>
 * The client-authentication refusal (owned by {@link OidcClientRules}) adds one more:
 * {@code oidc.client_secret} and {@code oidc.client_authentication.key_file} select different client
 * authentications, so a document declaring both is refused, and so is a declared
 * {@code oidc.client_secret} that resolves to a blank value. The schema does not encode the exclusion;
 * this rule owns it, because its message has to name both keys. The back-channel origin rule
 * ({@link OidcClientRules#validateBackChannelOrigin}) holds {@code oidc.back_channel_origin} to a bare
 * {@code http}/{@code https} origin, admits an {@code http} origin only under
 * {@code oidc.back_channel_allow_insecure_http: true}, and refuses that opt-in when there is nothing to
 * opt in for — each refusal on its key's own pointer, in fixed text.
 * <p>
 * The client JWKS path rule refuses an {@code oidc.client_authentication.jwks_path} — declared, or
 * the default it resolves to when omitted — that another reserved OIDC endpoint already claims. The
 * reserved-path registry keeps the first registration for a path, so the collision would otherwise
 * drop the key-set endpoint without a diagnostic. The same rule refuses a path that is not a
 * canonical reserved path ({@link #canonicalReservedPathRefusal}) — held to the canonical-path review
 * of {@link PortalRules}, and carrying neither a matrix parameter nor a percent-encoded character —
 * because the path is matched by exact equality with the canonical request path and a request that
 * spells such a value as configured would never be matched.
 * <p>
 * The terminal-action rule (ADR-0014 and its Amendment A1, owned by {@link TerminalActionRules})
 * holds every route to exactly one of upstream, asset or redirect, reviews a redirect's
 * {@code location} for open-redirect spellings at boot — a redirect is written verbatim at request
 * time, so this review is the only place its target is ever judged — and refuses
 * {@code keep_query} together with {@code allow_external}, the pair that would hand the inbound
 * query to a foreign origin.
 * <p>
 * The session-and-scope rules add three more: a route resolving {@code auth.token_relay: false}
 * must not re-admit {@code Authorization} through the {@code forward.headers_allow} of its effective
 * {@code forward} block, whether the route or its endpoint declares that block (owned by
 * {@link ForwardingRules}); an endpoint
 * declaring {@code scopes} must have at least one route that is not {@code require: none}, since
 * scopes act only on authenticated routes (owned by {@link AuthRules}); and a declared
 * {@code oidc.login.default_return_url} must be same-origin with {@code redirect_uri} (owned by
 * {@link OidcClientRules}).
 * <p>
 * The reserved-path rules hold {@code oidc.user_info.path}, {@code oidc.login.path} and
 * {@code oidc.step_up.path} alike to an absolute gateway path starting with a single {@code /} and
 * carrying no query ({@code ?}) and no fragment ({@code #}), no backslash, no control character and
 * no whitespace. A reserved path is matched exactly against the canonical request path, which
 * carries neither a query nor a fragment, so a value holding one would boot and name an endpoint no
 * request can reach; a backslash, a control character or whitespace is refused because a browser
 * reads {@code /\host} and {@code /<TAB>/host} as the scheme-relative {@code //host}. A path that
 * passes that shape check is then held to the same canonical form the client JWKS path is held to
 * ({@link #canonicalReservedPathRefusal}): no {@code //}, no dot segment, no matrix parameter
 * ({@code ;}) and no percent-encoded character, since a request that spells such a value as
 * configured is never matched by the reserved endpoint. Both refusals are reported on the key's own
 * JSON pointer.
 * <p>
 * The application-portal rules ({@link PortalRules}) add four more, run after every rule above: a
 * declared {@code portal.path} must be canonical, must not equal a reserved OIDC path (compared
 * host-independently, since the portal matches on any host), and must not equal an enabled
 * endpoint's exact route; and every enabled endpoint's {@code catalog.entry} must be an
 * origin-relative path on the gateway's own origin.
 * <p>
 * The session idle-timeout rule (owned by {@link OidcSessionRules}) adds one more: a declared
 * {@code oidc.session.idle_timeout_seconds} must be at least {@code 1} and at most the effective
 * {@code ttl_seconds}. An omitted key is always accepted, because it resolves to a value inside that
 * range. The per-subject session bound (owned by {@link OidcSessionRules} as well) follows the same
 * shape: a declared {@code oidc.session.max_sessions_per_subject} must be at least {@code 1} and at
 * most the effective {@code max_sessions}, and is refused in cookie mode.
 * <p>
 * Pre-boot: the offline {@code --validate-config} check runs this validator before the framework
 * starts (ADR-0061, ADR-0062), so the rule set is supplied at construction and the validator carries
 * no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigValidator {

    private static final int SUPPORTED_VERSION = 1;
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_USER_INFO_PATH_POINTER = "/oidc/user_info/path";
    private static final String OIDC_USER_INFO_DEFAULT_VIEW_POINTER = "/oidc/user_info/default_view";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_LOGIN_PATH_POINTER = "/oidc/login/path";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_STEP_UP_PATH_POINTER = "/oidc/step_up/path";

    /**
     * The requirement every reserved-path shape refusal states, shared by the three keys
     * {@link #validateReservedPath} holds to {@link #isAbsoluteGatewayPath}. Fixed text carrying no
     * format specifier, so it is appended to a key's own subject and never interprets a configured
     * scalar.
     */
    private static final String ABSOLUTE_GATEWAY_PATH_REQUIREMENT =
            " must be an absolute gateway path starting with a single '/' and carrying no query ('?') and no "
                    + "fragment ('#'), no backslash ('\\'), no control character and no whitespace";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_CLIENT_JWKS_PATH_POINTER = "/oidc/client_authentication/jwks_path";
    private static final String OIDC_CLIENT_JWKS_PATH_KEY = "oidc.client_authentication.jwks_path";

    /** The operator-declared reserved path {@code oidc.user_info.path}, held to {@link #validateReservedPath}. */
    private static final ReservedPathKey USER_INFO_PATH = new ReservedPathKey("oidc user_info path",
            "oidc.user_info.path", OIDC_USER_INFO_PATH_POINTER);

    /** The operator-declared reserved path {@code oidc.login.path}, held to {@link #validateReservedPath}. */
    private static final ReservedPathKey LOGIN_PATH = new ReservedPathKey("oidc login path", "oidc.login.path",
            OIDC_LOGIN_PATH_POINTER);

    /** The operator-declared reserved path {@code oidc.step_up.path}, held to {@link #validateReservedPath}. */
    private static final ReservedPathKey STEP_UP_PATH = new ReservedPathKey("oidc step_up path",
            "oidc.step_up.path", OIDC_STEP_UP_PATH_POINTER);

    /** Opens a matrix parameter in a path segment; a reserved path must not carry one. */
    private static final char MATRIX_PARAMETER_DELIMITER = ';';

    /** Opens a percent-encoded octet; a reserved path must not carry one. */
    private static final char PERCENT_SIGN = '%';

    /**
     * The built-in rules, one entry per rule, in the order their violations are reported. The
     * application-portal rules run after every other rule.
     */
    private static final List<ValidationRule> DEFAULT_RULES = List.of(
            (gateway, endpoints, topology, errors) -> validateVersion(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateEndpointIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateRouteDisjointness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateHeaderMatcherConsistency(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> validateBaseUrlResolvable(endpoints, topology, errors),
            (gateway, endpoints, topology, errors) -> AnchorRules.validateAnchorPrefixDisjointness(gateway, errors),
            (gateway, endpoints, topology, errors) -> AnchorRules.validateAnchorReferencesExist(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> AnchorRules.validateAnchorNamespaceMembership(gateway,
                    endpoints, errors),
            (gateway, endpoints, topology, errors) -> AuthRules.validateRouteAuthResolvable(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> AnchorRules.validateAnchorAuthFloor(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> AuthRules.validateEffectiveAuth(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> AuthRules.validateSessionFallback(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> AnchorRules.validateAccessAuthMatrix(gateway, errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateSecurityProfileMinimal(gateway, endpoints,
                    errors),
            TerminalActionRules::validateTerminalAction,
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateMethodMembership(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validateForwardedTrust(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateContentSecurityPolicy(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateHeaderModes(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMode(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxSessions(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionIdleTimeout(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxSessionsPerSubject(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxCookieSize(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionCookieName(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateClientAuthentication(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateClientJwksPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateUserInfo(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateLoginPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateStepUpPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateBackChannelOrigin(gateway, errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validatePassthroughHostCollision(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validatePassthroughAliasResolvable(gateway, topology,
                    errors),
            (gateway, endpoints, topology, errors) -> ProtocolRules.validateWebSocketConfig(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> ProtocolRules.validateRewriteLocationProtocol(endpoints, errors),
            (gateway, endpoints, topology, errors) -> TerminalActionRules.validateAssetIndexAndFallback(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateEdgeHardening(gateway, errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateAuthorizationHeaderValueLength(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> AssetDefaultsRules.validateAssetContentTypesAddOnly(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> AssetDefaultsRules.validateAssetContentTypeValues(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> ForwardingRules.validateForwardModeExclusivity(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> ForwardingRules.validateTokenRelayForwardConflict(gateway,
                    endpoints, errors),
            (gateway, endpoints, topology, errors) -> AuthRules.validateEndpointScopesNeedAuthentication(gateway,
                    endpoints, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateDefaultReturnUrl(gateway, errors),
            (gateway, endpoints, topology, errors) -> TokenValidationRules.validateJwksEgressHosts(gateway, errors),
            (gateway, endpoints, topology, errors) -> TokenValidationRules.validateJwksRefresh(gateway, errors),
            (gateway, endpoints, topology, errors) -> PortalRules.validatePortalPathCanonical(gateway, errors),
            (gateway, endpoints, topology, errors) -> PortalRules.validatePortalPathNotReserved(gateway, errors),
            (gateway, endpoints, topology, errors) -> PortalRules.validatePortalPathNotExactRoute(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> PortalRules.validateCatalogEntries(endpoints, errors));

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
     * Rule: {@code base_url} is mandatory exactly when the endpoint carries a proxy route
     * ({@link RouteConfig#isProxyRoute()} — the same predicate the route-table builder resolves the
     * alias on), and a declared alias must resolve in the topology whether or not a proxy route uses
     * it. An endpoint serving only {@code asset} and/or {@code redirect} routes may omit
     * {@code base_url}.
     */
    private static void validateBaseUrlResolvable(List<EndpointConfig> endpoints, ResolvedTopology topology,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            String baseUrl = endpoint.baseUrl();
            if (baseUrl == null) {
                if (endpoint.routes().stream().anyMatch(RouteConfig::isProxyRoute)) {
                    errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/base_url",
                            "endpoint '%s' declares proxy route(s) but no base_url".formatted(endpoint.id())));
                }
            } else if (topology.lookup(baseUrl).isEmpty()) {
                errors.add(new ConfigError(endpointFile(endpoint), "/endpoint/base_url",
                        "unresolved topology alias: " + baseUrl));
            }
        }
    }

    /**
     * Rule: the global CORS block must not combine a wildcard origin with {@code allow_credentials}.
     * <p>
     * CORS is global only (ADR-0007 Amendment A1): it is evaluated at stage 0, before route selection
     * and authentication, so no anchor can scope it. The bundled schema refuses a {@code cors} block
     * under {@code anchors.*.security_headers} at load, which is why only the gateway block is checked
     * here.
     */
    private static void validateCors(GatewayConfig gateway, List<ConfigError> errors) {
        checkCors(gateway.securityHeaders(), "/security_headers/cors", errors);
    }

    /**
     * Rule: a {@code content_security_policy} value must carry no CR, LF or other control character,
     * on the global block and on every anchor block alike.
     * <p>
     * The value is served verbatim as the {@code Content-Security-Policy} response header, so a line
     * terminator would inject a header (CWE-113). The bundled schema already bounds the value's shape;
     * this rule is the boot-time authority that names the offending block, echoing the value only
     * through the injection-safe {@code renderForMessage} (CWE-117). Every violation collects into the
     * shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateContentSecurityPolicy(GatewayConfig gateway, List<ConfigError> errors) {
        checkContentSecurityPolicy(gateway.securityHeaders(), "/security_headers/content_security_policy", errors);
        for (AnchorConfig anchor : gateway.anchors().values()) {
            checkContentSecurityPolicy(anchor.securityHeaders(),
                    "/anchors/%s/security_headers/content_security_policy".formatted(anchor.name()), errors);
        }
    }

    private static void checkContentSecurityPolicy(@Nullable SecurityHeadersConfig securityHeaders, String pointer,
            List<ConfigError> errors) {
        String policy = securityHeaders == null ? null : securityHeaders.contentSecurityPolicy();
        if (policy == null) {
            return;
        }
        if (policy.isBlank() || policy.chars().anyMatch(Character::isISOControl)) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "content_security_policy %s must be a non-blank value without CR, LF or other control characters; it is served verbatim as a response header"
                            .formatted(renderForMessage(policy))));
        }
    }

    /**
     * Rule: a {@code header_modes} entry may only name a header the same block enables — on the global
     * block and on every anchor block alike.
     * <p>
     * A mode for a header that is absent (or {@code false}) in its block would configure the precedence
     * of a header the gateway never emits for that block: the key would parse and do nothing. It is
     * refused rather than silently ignored, naming the offending entry. Every violation collects into the
     * shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateHeaderModes(GatewayConfig gateway, List<ConfigError> errors) {
        checkHeaderModes(gateway.securityHeaders(), "/security_headers/header_modes", errors);
        for (AnchorConfig anchor : gateway.anchors().values()) {
            checkHeaderModes(anchor.securityHeaders(),
                    "/anchors/%s/security_headers/header_modes".formatted(anchor.name()), errors);
        }
    }

    private static void checkHeaderModes(@Nullable SecurityHeadersConfig securityHeaders, String pointer,
            List<ConfigError> errors) {
        if (securityHeaders == null) {
            return;
        }
        SecurityHeadersConfig.HeaderModes modes = securityHeaders.headerModes();
        if (modes == null) {
            return;
        }
        checkHeaderMode(modes.hsts(), securityHeaders.hsts() != null, "hsts", pointer, errors);
        checkHeaderMode(modes.contentTypeNosniff(), Boolean.TRUE.equals(securityHeaders.contentTypeNosniff()),
                "content_type_nosniff", pointer, errors);
        checkHeaderMode(modes.frameDeny(), Boolean.TRUE.equals(securityHeaders.frameDeny()), "frame_deny", pointer,
                errors);
        checkHeaderMode(modes.contentSecurityPolicy(), securityHeaders.contentSecurityPolicy() != null,
                "content_security_policy", pointer, errors);
    }

    private static void checkHeaderMode(SecurityHeadersConfig.@Nullable HeaderMode mode, boolean headerEnabled,
            String key, String pointer, List<ConfigError> errors) {
        if (mode != null && !headerEnabled) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer + "/" + key,
                    "header_modes.%s is declared but the same security_headers block does not enable %s; a mode for a header the gateway never emits is refused"
                            .formatted(key, key)));
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
     * Rule: the client JWKS path must be a canonical gateway path, and must not be the path of another
     * reserved OIDC endpoint.
     * <p>
     * <strong>Canonical form.</strong> The reserved-path registry registers the path verbatim and the
     * edge matches it by exact equality with the canonical request path. A path that is not itself
     * canonical — a {@code //}, a dot segment, a {@code ?} or {@code #}, a matrix parameter, a
     * percent-encoded character, or anything else {@link #canonicalReservedPathRefusal} names — is never
     * matched by a request that spells it as configured, with two consequences: the key set is not
     * published at the URL the operator registers, so in key mode the identity provider cannot fetch
     * the key that verifies the gateway's client assertion and every login fails without a boot
     * diagnostic; and in both client-authentication modes the canonical path such a request resolves
     * to is left unreserved and falls through to the route table, where a proxied upstream could
     * answer under the client's key-set URL. Such a path is refused instead, with a fixed reason that
     * never echoes the configured value.
     * <p>
     * The claim is about the configured spelling, not about every request. A path carrying a
     * percent-encoded character is matched by no request at all: the request path is decoded once
     * and a second encoding layer is refused. A path carrying a matrix parameter <em>is</em> matched
     * by a request that percent-encodes the {@code ;}, because the canonical-path guard reads the raw
     * path and the registry matches the decoded one — which is no URL an operator registers, so the
     * refusal stands on the configured spelling alone
     * ({@code GatewayEdgeRouteBffWiringTest.NonCanonicalClientJwksPath} pins both).
     * <p>
     * <strong>No collision.</strong> The reserved-path registry keeps the first registration for a
     * path and registers the client JWKS path last. A JWKS path that another key of the {@code oidc}
     * block also names — the callback of {@code redirect_uri}, the logout path, its return leg, the
     * back-channel logout path, the user-info path, the login path or the step-up path — would
     * therefore lose silently:
     * the other endpoint would answer there, the key set would never be published, and the identity
     * provider could not verify the gateway's client assertion. The collision is refused instead,
     * naming both keys.
     * <p>
     * The path is read through {@link OidcConfig#effectiveClientJwksPath()}, the accessor the registry
     * resolves it with, and the owner of the path through
     * {@link ReservedPathRegistry#reservedKind(OidcConfig, String)}, the registry's own derivation — so
     * the rule judges exactly what the runtime would register. It therefore covers the default path as
     * well: a document that declares no {@code jwks_path} and names {@code /bff/jwks} under another key
     * is refused like one that declares the collision outright.
     * <p>
     * The rule does not read the client-authentication mode. The path is reserved with a client secret
     * configured too, so a non-canonical path and a collision are the same defects in both modes. The
     * two refusals are independent, and every violation collects into the shared list; the rule never
     * fails fast (ADR-0009).
     */
    private static void validateClientJwksPath(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        if (oidc == null) {
            return;
        }
        String path = oidc.effectiveClientJwksPath();
        canonicalReservedPathRefusal(path).ifPresent(reason -> errors.add(new ConfigError(GATEWAY_FILE,
                OIDC_CLIENT_JWKS_PATH_POINTER,
                ("%s must be a canonical gateway path: %s. A request that spells the path as configured "
                        + "would never be answered by the key-set endpoint, and where such a request "
                        + "resolves to a canonical path, the key-set endpoint would not be reserved "
                        + "there — declare a canonical path, or omit the key for the default %s")
                        .formatted(OIDC_CLIENT_JWKS_PATH_KEY, reason,
                                OidcConfig.ClientAuthenticationSettings.DEFAULT_JWKS_PATH))));
        ReservedPathRegistry.reservedKind(oidc, path)
                .filter(owner -> owner != ReservedEndpoint.CLIENT_JWKS)
                .ifPresent(owner -> errors.add(new ConfigError(GATEWAY_FILE, OIDC_CLIENT_JWKS_PATH_POINTER,
                        ("%s and %s resolve to the same gateway path; two reserved endpoints cannot share one "
                                + "path, and the client key set would never be published there — when %s is "
                                + "omitted it resolves to %s. Declare an %s that no other oidc key names, or "
                                + "change %s")
                                .formatted(OIDC_CLIENT_JWKS_PATH_KEY, reservedPathKey(owner),
                                        OIDC_CLIENT_JWKS_PATH_KEY,
                                        OidcConfig.ClientAuthenticationSettings.DEFAULT_JWKS_PATH,
                                        OIDC_CLIENT_JWKS_PATH_KEY, reservedPathKey(owner)))));
    }

    /**
     * The reason a reserved path is not a canonical gateway path, or empty when it is one.
     * <p>
     * Every reserved path is registered verbatim and matched by exact equality with the canonical
     * request path, so the client JWKS path, {@code oidc.user_info.path}, {@code oidc.login.path} and
     * {@code oidc.step_up.path} are all held to this one review. It is
     * {@link PortalRules#canonicalPathRefusal(String)} — the one {@code portal.path} is held to,
     * reused here rather than restated: no {@code //}, no dot segment, no {@code ?} or {@code #} —
     * followed by two refusals that review does not make. Both name a path that could never be
     * answered at the URL an operator would register for it:
     * <ul>
     *   <li><strong>A matrix parameter.</strong> The canonical-path guard refuses every request whose
     *       raw path carries a {@code ;}, so a request for the configured URL is answered {@code 400}
     *       and never by the reserved endpoint.</li>
     *   <li><strong>A {@code %}.</strong> The request path is percent-decoded before it is matched, so
     *       an encoded character in the configured path never equals the decoded one the request
     *       yields; and a request path carrying a second encoding layer is refused, so no request
     *       yields the configured string either.</li>
     * </ul>
     * Every reason is fixed text; none echoes the configured value.
     */
    private static Optional<String> canonicalReservedPathRefusal(String path) {
        Optional<String> shared = PortalRules.canonicalPathRefusal(path);
        if (shared.isPresent()) {
            return shared;
        }
        if (path.indexOf(MATRIX_PARAMETER_DELIMITER) >= 0) {
            return Optional.of("a matrix parameter (';') is refused on every request path");
        }
        if (path.indexOf(PERCENT_SIGN) >= 0) {
            return Optional.of("a percent-encoded character is decoded before the request path is matched");
        }
        return Optional.empty();
    }

    /**
     * The {@code gateway.yaml} key a reserved endpoint's path is declared under, for a refusal that has
     * to name it.
     */
    private static String reservedPathKey(ReservedEndpoint endpoint) {
        return switch (endpoint) {
            case CALLBACK -> "oidc.redirect_uri";
            case LOGOUT -> "oidc.logout.path";
            case LOGOUT_RETURN -> "oidc.logout.post_logout_redirect_uri";
            case BACKCHANNEL_LOGOUT -> "oidc.logout.backchannel_path";
            case USER_INFO -> USER_INFO_PATH.key();
            case LOGIN -> LOGIN_PATH.key();
            case STEP_UP -> STEP_UP_PATH.key();
            case CLIENT_JWKS -> OIDC_CLIENT_JWKS_PATH_KEY;
        };
    }

    /**
     * Rule: the session/user-info reserved endpoint (fold, D1). When an
     * {@code oidc.user_info} block is present, its {@code path} is held to
     * {@link #validateReservedPath} — an absolute gateway path in canonical form, since the path is
     * matched exactly against the canonical request path — and every {@code default_view} claim must
     * lie within the {@code allowed_claims} allowlist — the operator-owned allowlist caps
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
        validateReservedPath(USER_INFO_PATH, userInfo.path(), errors);
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
     * {@code oidc.login.path} is present it is held to {@link #validateReservedPath}: it must be an
     * absolute gateway path — a schema-relative ({@code //host}) or off-path value is rejected as an
     * open-redirect hazard. A value carrying a query ({@code ?}) or a fragment
     * ({@code #}) is rejected as well: the path is matched exactly against the canonical
     * request path, which carries neither, so such a value names an endpoint no request
     * can reach. A backslash, a control character or whitespace is rejected too
     * ({@link #isAbsoluteGatewayPath}), and so is a path that is not in canonical form
     * ({@link #canonicalReservedPathRefusal}).
     */
    private static void validateLoginPath(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        OidcConfig.Login login = oidc == null ? null : oidc.login();
        validateReservedPath(LOGIN_PATH, login == null ? null : login.path(), errors);
    }

    /**
     * Rule: the step-up reserved path. When {@code oidc.step_up.path} is present it is held to
     * {@link #validateReservedPath}, exactly as {@link #validateLoginPath} holds
     * {@code oidc.login.path}: a blank, relative or scheme-relative ({@code //host}) value is
     * rejected, since the path is named verbatim in the {@code step_up_url} a session route hands to
     * the browser. A value carrying a query ({@code ?}) or a fragment ({@code #}) is rejected too,
     * for two reasons: the path is matched exactly against the canonical request path, which carries
     * neither, so the endpoint would be unreachable; and {@code step_up_url} appends
     * {@code ?returnUrl=…} to the configured value, so a query already in it would swallow the
     * return target into its own parameter and a fragment would keep it from being sent at all. A
     * backslash, a control character or whitespace is rejected as well
     * ({@link #isAbsoluteGatewayPath}), because a browser reads a {@code step_up_url} naming
     * {@code /\host} or {@code /<TAB>/host} as the scheme-relative {@code //host}. A path that is not
     * in canonical form ({@link #canonicalReservedPathRefusal}) is rejected as well. A
     * collision with the application portal needs no rule here — the portal rules compare
     * {@code portal.path} against every reserved path, this one included. A collision with the client
     * JWKS path needs none here either: {@link #validateClientJwksPath} refuses it, naming both keys,
     * whichever of the two the operator thinks of as the one that moved.
     */
    private static void validateStepUpPath(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        OidcConfig.StepUp stepUp = oidc == null ? null : oidc.stepUp();
        validateReservedPath(STEP_UP_PATH, stepUp == null ? null : stepUp.path(), errors);
    }

    /**
     * The two refusals an operator-declared reserved path ({@code oidc.user_info.path},
     * {@code oidc.login.path}, {@code oidc.step_up.path}) is held to, both reported on the key's own
     * JSON pointer. A no-op when the path is omitted.
     * <ol>
     *   <li><strong>Shape</strong> ({@link #isAbsoluteGatewayPath}): an absolute gateway path starting
     *       with a single {@code /}, carrying no query, no fragment, no backslash, no control character
     *       and no whitespace. The refused value is echoed through {@link RuleSupport#renderForMessage}, so it
     *       cannot forge boot log lines (CWE-117).</li>
     *   <li><strong>Canonical form</strong> ({@link #canonicalReservedPathRefusal}), checked once the
     *       shape holds: no {@code //}, no dot segment, no matrix parameter ({@code ;}) and no
     *       percent-encoded character. The path is registered verbatim and matched by exact equality
     *       with the canonical request path, so a request that spells a non-canonical value as
     *       configured never reaches the reserved endpoint. The reason is fixed text and the refusal
     *       echoes no configured value.</li>
     * </ol>
     * A value that fails the shape check is not held to the canonical form as well, so one defect is
     * reported once. Every violation collects into the shared list; the check never fails fast.
     */
    private static void validateReservedPath(ReservedPathKey reservedPath, @Nullable String path,
            List<ConfigError> errors) {
        if (path == null) {
            return;
        }
        if (!isAbsoluteGatewayPath(path)) {
            errors.add(new ConfigError(GATEWAY_FILE, reservedPath.pointer(),
                    "%s '%s'".formatted(reservedPath.subject(), renderForMessage(path))
                            + ABSOLUTE_GATEWAY_PATH_REQUIREMENT));
            return;
        }
        canonicalReservedPathRefusal(path).ifPresent(reason -> errors.add(new ConfigError(GATEWAY_FILE,
                reservedPath.pointer(),
                ("%s must be a canonical gateway path: %s. The path is matched by exact equality with the "
                        + "canonical request path, so a request that spells it as configured would never reach "
                        + "the reserved endpoint — declare a canonical path")
                        .formatted(reservedPath.key(), reason))));
    }

    /**
     * An operator-declared reserved path, named the way its refusals have to name it.
     *
     * @param subject the subject the shape refusal opens with, e.g. {@code oidc login path}
     * @param key     the dotted {@code gateway.yaml} key, e.g. {@code oidc.login.path}
     * @param pointer the JSON pointer every refusal of the key is reported on
     */
    private record ReservedPathKey(String subject, String key, String pointer) {
    }

    /**
     * Whether {@code path} is an absolute gateway path usable as a reserved path: it starts with a
     * single {@code /}, is not a schema-relative {@code //host} URL (which would be an
     * open-redirect vector for a reserved path), carries neither a query ({@code ?}) nor a
     * fragment ({@code #}), and holds no backslash, no control character and no whitespace anywhere.
     * <p>
     * The query and fragment conditions follow from how a reserved path is served: it is registered
     * verbatim and matched exactly against the canonical request path, which never carries the
     * query, and a browser never sends a fragment. A value holding either delimiter would therefore
     * boot and name an endpoint no request can reach — a bare trailing {@code ?} or {@code #}
     * included, since the registered key would still end in a character the request path never has.
     * <p>
     * The last three conditions close the spellings a browser normalizes into the scheme-relative
     * form the {@code //} test refuses: a browser parses a backslash as {@code /}, so
     * {@code /\host} is read as {@code //host}, and it removes an ASCII tab or line break from a
     * URL before parsing it, so {@code /<TAB>/host} is read as {@code //host} too. The same two
     * tests guard the login return URL ({@code PendingAuthorizationRecord.sameOrigin}); whitespace
     * is refused alongside the control characters, so no blank of any kind passes. Percent-encoded
     * spellings, dot segments and matrix parameters are not judged here; a reserved path that passes
     * this check is held to {@link #canonicalReservedPathRefusal} next.
     */
    private static boolean isAbsoluteGatewayPath(String path) {
        return path.startsWith("/") && !path.startsWith("//")
                && path.indexOf('?') < 0 && path.indexOf('#') < 0
                && path.indexOf('\\') < 0
                && path.chars().noneMatch(ConfigValidator::isControlOrWhitespace);
    }

    /**
     * Whether {@code character} is an ISO control character or whitespace — a Java whitespace
     * character or a Unicode space separator, so a no-break space counts as well.
     */
    private static boolean isControlOrWhitespace(int character) {
        return Character.isISOControl(character) || Character.isWhitespace(character)
                || Character.isSpaceChar(character);
    }
}
