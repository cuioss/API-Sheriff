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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.validation.rule.AnchorRules;
import de.cuioss.sheriff.gateway.config.validation.rule.AssetDefaultsRules;
import de.cuioss.sheriff.gateway.config.validation.rule.AuthRules;
import de.cuioss.sheriff.gateway.config.validation.rule.CookieRules;
import de.cuioss.sheriff.gateway.config.validation.rule.EdgeRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ForwardingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.HeaderRules;
import de.cuioss.sheriff.gateway.config.validation.rule.LimitRules;
import de.cuioss.sheriff.gateway.config.validation.rule.OidcClientRules;
import de.cuioss.sheriff.gateway.config.validation.rule.OidcSessionRules;
import de.cuioss.sheriff.gateway.config.validation.rule.PortalRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ProtocolRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ReservedPathRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RouteMatchingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport;
import de.cuioss.sheriff.gateway.config.validation.rule.TerminalActionRules;
import de.cuioss.sheriff.gateway.config.validation.rule.TokenValidationRules;
import de.cuioss.sheriff.gateway.config.validation.rule.TopologyRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule;

/**
 * Runs the cross-cutting configuration rules (pipeline step 7) that cannot be
 * expressed structurally in the D2 JSON Schemas, aggregating every violation in a
 * single pass.
 * <p>
 * The validator is the orchestration of ONE ordered rule list, {@code DEFAULT_RULES}: one entry per
 * rule, in the order the violations are reported. That list is the only place that decides the
 * order, and this class holds no rule body of its own. Every rule body lives in the concern class of
 * the {@code rule} package that owns its configuration block: {@link TopologyRules} (the
 * configuration version, endpoint and route id uniqueness, base-URL resolvability),
 * {@link RouteMatchingRules} (route disjointness, header matchers, effective-method membership),
 * {@link AnchorRules} (the anchors), {@link AuthRules} (the {@code auth} blocks and endpoint scopes),
 * {@link LimitRules} (the security-filter profile, the edge limits, the enumeration detection and the
 * unenforced {@code rate_limit}), {@link TerminalActionRules}
 * (upstream, asset or redirect), {@link EdgeRules} (forwarded trust and TLS passthrough),
 * {@link HeaderRules} (CORS, Content-Security-Policy and the security-header modes),
 * {@link OidcSessionRules} (the BFF session), {@link OidcClientRules} (the OIDC client),
 * {@link ReservedPathRules} (the reserved OIDC endpoint paths), {@link ProtocolRules} (WebSocket and
 * {@code Location} rewriting), {@link AssetDefaultsRules} (the asset content types),
 * {@link ForwardingRules} (the {@code forward} blocks), {@link CookieRules} (the {@code cookies}
 * blocks), {@link TokenValidationRules} (JWKS egress and
 * refresh) and {@link PortalRules} (the application portal). The helpers every rule shares (the
 * reporting file, pointer and anchors pointer, the
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
 * posture refusal. A further {@link LimitRules} entry refuses nothing: every route declaring the
 * {@code rate_limit} block is reported with a boot WARN, because the block is parsed but never
 * enforced, and the boot continues.
 * <p>
 * The forward-mode exclusivity refusal (owned by {@link ForwardingRules}) adds one more: neither an
 * endpoint nor a route may declare both {@code *_allow} and {@code *_deny} for the same {@code forward}
 * dimension, because each dimension resolves exactly one of the three modes (positive-list,
 * negative-list, forward-all). The schema bounds the {@code forward} key <em>set</em>; a mutual
 * exclusion between two of its keys is cross-cutting and lives with the post-binding rules. The
 * cookie-header refusal (owned by {@link ForwardingRules} as well) adds one more: a
 * {@code forward.headers_allow}, {@code forward.headers_deny} or {@code forward.set_headers} naming
 * {@code Cookie} is refused, because only the {@code cookies} block decides which cookies cross.
 * <p>
 * The cookie-policy rules (owned by {@link CookieRules}) add seven more. Four are checked at every
 * scope the {@code cookies} block is declarable at — the gateway, each anchor, each enabled endpoint
 * and each of its routes: a listed name must be an RFC 6265 cookie name; a listed name must not be
 * one of the gateway's own cookies; {@code same_origin_check} and {@code additional_origins} are
 * refused on a block whose {@code allow} is {@code none}; and every {@code additional_origins} entry
 * must be a serialized origin. The other three do not run at every scope. The {@code allow: all}
 * refusal reads the gateway-scope block only, since that mode is accepted on an anchor, an endpoint
 * and a route. The terminal-route refusal reads route-level blocks only: a route-scope block is
 * refused on an asset or a redirect route, while a broader block stays accepted. The seventh judges
 * the resolved block per route: a {@code protocol: websocket} route whose effective block forwards
 * any cookie must declare a non-empty {@code websocket.allowed_origins}, the allowlist that gates its
 * upgrade in place of a same-origin check. A declared {@code allow: []} is refused by the schema, not
 * here.
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
 * this rule owns it, because its message has to name both keys. The signing-key separation rule
 * ({@link OidcClientRules#validateSigningKeyFilesDistinct}) refuses, in the key-based mode, an
 * {@code oidc.client_authentication.key_file} and an {@code oidc.sender_constraint.key_file} that name
 * the same file, since FAPI 2.0 section 6.8 requires the two signing keys to be two keys. The
 * back-channel origin rule ({@link OidcClientRules#validateBackChannelOrigin}) holds
 * {@code oidc.back_channel_origin} to a bare {@code http}/{@code https} origin, admits an {@code http}
 * origin only under {@code oidc.back_channel_allow_insecure_http: true}, and refuses that opt-in when
 * there is nothing to opt in for — each refusal on its key's own pointer, in fixed text.
 * <p>
 * The reserved-path rules (owned by {@link ReservedPathRules}) add four more: the client JWKS path
 * must be a canonical reserved path that no other reserved OIDC endpoint claims, and
 * {@code oidc.user_info.path}, {@code oidc.login.path} and {@code oidc.step_up.path} must each be an
 * absolute gateway path in canonical form, because a reserved path is matched by exact equality with
 * the canonical request path. The user-info rule also holds every {@code default_view} claim to the
 * {@code allowed_claims} allowlist.
 * <p>
 * The terminal-action rule (ADR-0014 and its Amendment A1, owned by {@link TerminalActionRules})
 * holds every route to exactly one of upstream, asset or redirect, reviews a redirect's
 * {@code location} for open-redirect spellings at boot — a redirect is written verbatim at request
 * time, so this review is the only place its target is ever judged — and refuses
 * {@code keep_query} together with {@code allow_external}, the pair that would hand the inbound
 * query to a foreign origin. A third rule of the same owner (its second is the asset file-name rule)
 * refuses {@code keep_identity_headers}
 * on a redirect, a directory-asset and a WebSocket route, none of which relays an upstream response
 * header the key could govern.
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

    /**
     * The built-in rules, one entry per rule, in the order their violations are reported. The
     * application-portal rules run after every other rule.
     */
    private static final List<ValidationRule> DEFAULT_RULES = List.of(
            (gateway, endpoints, topology, errors) -> TopologyRules.validateVersion(gateway, errors),
            (gateway, endpoints, topology, errors) -> TopologyRules.validateEndpointIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> TopologyRules.validateRouteIdUniqueness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateRouteDisjointness(endpoints, errors),
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateHeaderMatcherConsistency(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> TopologyRules.validateBaseUrlResolvable(endpoints, topology,
                    errors),
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
            (gateway, endpoints, topology, errors) -> HeaderRules.validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> HeaderRules.validateContentSecurityPolicy(gateway, errors),
            (gateway, endpoints, topology, errors) -> HeaderRules.validateHeaderModes(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMode(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxSessions(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionIdleTimeout(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxSessionsPerSubject(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionMaxCookieSize(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcSessionRules.validateSessionCookieName(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateClientAuthentication(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateSigningKeyFilesDistinct(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> ReservedPathRules.validateClientJwksPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> ReservedPathRules.validateUserInfo(gateway, errors),
            (gateway, endpoints, topology, errors) -> ReservedPathRules.validateLoginPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> ReservedPathRules.validateStepUpPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> OidcClientRules.validateBackChannelOrigin(gateway, errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validatePassthroughHostCollision(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validatePassthroughAliasResolvable(gateway, topology,
                    errors),
            (gateway, endpoints, topology, errors) -> ProtocolRules.validateWebSocketConfig(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> ProtocolRules.validateRewriteLocationProtocol(endpoints, errors),
            (gateway, endpoints, topology, errors) -> TerminalActionRules.validateAssetIndexAndFallback(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> TerminalActionRules.validateKeepIdentityHeadersTarget(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateEdgeHardening(gateway, errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateEnumerationDetection(gateway, errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateAuthorizationHeaderValueLength(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateRateLimitNotEnforced(endpoints),
            (gateway, endpoints, topology, errors) -> AssetDefaultsRules.validateAssetContentTypesAddOnly(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> AssetDefaultsRules.validateAssetContentTypeValues(gateway,
                    errors),
            (gateway, endpoints, topology, errors) -> ForwardingRules.validateForwardModeExclusivity(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> ForwardingRules.validateTokenRelayForwardConflict(gateway,
                    endpoints, errors),
            (gateway, endpoints, topology, errors) -> ForwardingRules.validateCookieOutsideCookiesBlock(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateGatewayAllowAll(gateway, errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateCookieNames(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateGatewayCookieNotListed(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateNoInertKeysOnNone(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateAdditionalOrigins(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateNoCookiesOnTerminalRoute(endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> CookieRules.validateWebSocketCookieOrigins(gateway, endpoints,
                    errors),
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
}
