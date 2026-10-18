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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.declaredAnchorName;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.effectiveProtocol;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.requireOf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
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
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.AssetDefaultsRules;
import de.cuioss.sheriff.gateway.config.validation.rule.EdgeRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ForwardingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.LimitRules;
import de.cuioss.sheriff.gateway.config.validation.rule.PortalRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ProtocolRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RouteMatchingRules;
import de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport;
import de.cuioss.sheriff.gateway.config.validation.rule.TerminalActionRules;
import de.cuioss.sheriff.gateway.config.validation.rule.TokenValidationRules;
import de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule;
import de.cuioss.tools.logging.CuiLogger;
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
 * same. The helpers every rule shares (the reporting file and pointer, the CWE-117-safe echo of a
 * configured value, anchor resolution, which protocol a route rides, which {@code auth.require} a
 * route resolves, and the wildcard-origin token) live once, in {@link RuleSupport}.
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
 * existence, route/namespace membership agreement, anchor-namespace coverage (no route
 * outside an anchor may swallow the anchor's namespace unless a route under the anchor
 * covers it), the non-weakenable auth floor,
 * the per-route auth resolvability (every route must resolve an auth posture from
 * its own {@code auth}, its endpoint, or a declared anchor), and the anchor-aware
 * effective-auth completeness check — all collect into the same shared
 * {@code errors} list with file/pointer context and never fail fast.
 * <p>
 * The session-fallback refusal adds one more: every declared {@code auth} block — anchor, endpoint
 * or route — that sets {@code session_fallback: true} must declare {@code require: bearer}, since the
 * key only splits a bearer posture into a bearer and a session branch, and the gateway must declare an
 * {@code oidc} block, since the session branch needs the BFF runtime exactly as a
 * {@code require: session} route does. The two refusals are independent and carry fixed detail
 * texts. The anchor floor comparison is untouched by the key — it compares {@code require} only —
 * and the effective-auth completeness check keeps demanding {@code token_validation} issuers for a
 * session-fallback route's bearer posture, so such a route is held to both backing blocks.
 * <p>
 * The fail-closed access→auth matrix (ADR-0013) adds per-anchor checks: a
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
 * The session-cookie name refusal adds one more: a declared {@code oidc.session.cookie_name} must
 * keep the {@code __Host-} guarantee the gateway-owned cookies carry, be writable verbatim into a
 * {@code Set-Cookie} header, and not collide with a gateway-owned cookie. The schema declares the key
 * an unrestricted string; this rule is the single enforcing authority.
 * <p>
 * The client-authentication refusal adds one more: {@code oidc.client_secret} and
 * {@code oidc.client_authentication.key_file} select different client authentications, so a document
 * declaring both is refused, and so is a declared {@code oidc.client_secret} that resolves to a blank
 * value. The schema does not encode the exclusion; this rule owns it, because its message has to
 * name both keys.
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
 * scopes act only on authenticated routes; and a declared {@code oidc.login.default_return_url}
 * must be same-origin with {@code redirect_uri}.
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
 * The session idle-timeout rule adds one more: a declared {@code oidc.session.idle_timeout_seconds}
 * must be at least {@code 1} and at most the effective {@code ttl_seconds}. An omitted key is always
 * accepted, because it resolves to a value inside that range. The per-subject session bound follows
 * the same shape: a declared {@code oidc.session.max_sessions_per_subject} must be at least {@code 1}
 * and at most the effective {@code max_sessions}, and is refused in cookie mode.
 * <p>
 * Pre-boot: the offline {@code --validate-config} check runs this validator before the framework
 * starts (ADR-0061, ADR-0062), so the rule set is supplied at construction and the validator carries
 * no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigValidator {

    private static final CuiLogger LOGGER = new CuiLogger(ConfigValidator.class);

    private static final int SUPPORTED_VERSION = 1;
    private static final String ANCHORS_POINTER = "/anchors";
    private static final String ENDPOINT_ANCHOR_POINTER = "/endpoint/anchor";
    private static final String ENDPOINT_AUTH_POINTER = "/endpoint/auth";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_USER_INFO_PATH_POINTER = "/oidc/user_info/path";
    private static final String OIDC_USER_INFO_DEFAULT_VIEW_POINTER = "/oidc/user_info/default_view";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_LOGIN_PATH_POINTER = "/oidc/login/path";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_LOGIN_DEFAULT_RETURN_URL_POINTER = "/oidc/login/default_return_url";
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
    private static final String ENDPOINT_SCOPES_POINTER = "/endpoint/scopes";

    /**
     * The fixed detail of the refusal of {@code session_fallback: true} on a posture other than
     * {@code require: bearer}. Fixed text, so the boot log never echoes a configured scalar.
     */
    private static final String SESSION_FALLBACK_NOT_BEARER_DETAIL =
            "declares auth.session_fallback: true on a posture other than require: bearer; session_fallback only "
                    + "adds a session branch to a bearer posture for requests without an Authorization header — "
                    + "declare require: bearer, or drop session_fallback";

    /**
     * The fixed detail of the refusal of {@code session_fallback: true} when the gateway declares no
     * {@code oidc} block. Fixed text, so the boot log never echoes a configured scalar.
     */
    private static final String SESSION_FALLBACK_NO_OIDC_DETAIL =
            "declares auth.session_fallback: true but the gateway declares no oidc block; the session branch "
                    + "authenticates through the BFF runtime exactly as a require: session route does — declare "
                    + "an oidc block, or drop session_fallback";
    private static final String OIDC_SESSION_MAX_SESSIONS_POINTER = "/oidc/session/max_sessions";
    private static final String OIDC_SESSION_MAX_COOKIE_SIZE_POINTER = "/oidc/session/max_cookie_size";
    private static final String OIDC_SESSION_IDLE_TIMEOUT_POINTER = "/oidc/session/idle_timeout_seconds";
    private static final String OIDC_SESSION_MAX_SESSIONS_PER_SUBJECT_POINTER =
            "/oidc/session/max_sessions_per_subject";
    private static final String OIDC_SESSION_COOKIE_NAME_POINTER = "/oidc/session/cookie_name";
    private static final String OIDC_CLIENT_SECRET_POINTER = "/oidc/client_secret";
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
     * The fixed detail of the refusal of {@code oidc.client_secret} together with
     * {@code oidc.client_authentication.key_file}. Fixed text, so the boot log echoes neither the
     * secret nor the configured path. It says "at most one", not "exactly one": a document declaring
     * neither is valid and authenticates with a generated key.
     */
    private static final String CLIENT_SECRET_WITH_KEY_FILE_DETAIL =
            "oidc.client_secret and oidc.client_authentication.key_file are both declared; the two select "
                    + "different client authentications — client_secret selects client_secret_basic, "
                    + "client_authentication.key_file selects private_key_jwt — so at most one of them may be "
                    + "configured: remove oidc.client_secret to authenticate with the key, or remove "
                    + "oidc.client_authentication.key_file to authenticate with the secret";

    /**
     * The fixed detail of the refusal of a declared {@code oidc.client_secret} that resolves to a
     * blank value. Fixed text, so the boot log never echoes a configured scalar.
     */
    private static final String CLIENT_SECRET_BLANK_DETAIL =
            "oidc.client_secret is declared but resolves to a blank value; a declared client secret selects "
                    + "client_secret_basic and must carry a value — set the variable it references, or remove "
                    + "oidc.client_secret to authenticate with private_key_jwt";

    /**
     * The cookie-name prefix every gateway-owned cookie carries. A browser honours a
     * {@code __Host-}-prefixed cookie only when it is {@code Secure}, scoped to {@code Path=/} and
     * carries no {@code Domain}, so the prefix is what turns those attributes from a gateway habit
     * into a browser-enforced guarantee.
     */
    private static final String HOST_COOKIE_PREFIX = "__Host-";

    /** The gateway-owned cookie names a configured session cookie name must never collide with. */
    private static final List<String> GATEWAY_OWNED_COOKIE_NAMES = List.of(
            BindingCookieCodec.COOKIE_NAME, RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME);

    /**
     * A non-empty RFC 9110 {@code token} matched against the WHOLE configured session cookie name
     * ({@link java.util.regex.Matcher#matches()}). RFC 6265 draws a cookie name from exactly this
     * character set, and the name is written verbatim into {@code Set-Cookie}: a {@code ;}, {@code =},
     * whitespace or control character in it would be response-header injection. It is the same
     * {@link RuleSupport#MEDIA_TYPE_TOKEN} class, reused rather than restated so the two gates cannot
     * drift.
     */
    private static final Pattern COOKIE_NAME_SHAPE = Pattern.compile(RuleSupport.MEDIA_TYPE_TOKEN);

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
            (gateway, endpoints, topology, errors) -> validateAnchorPrefixDisjointness(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorReferencesExist(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorNamespaceMembership(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateRouteAuthResolvable(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAnchorAuthFloor(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateEffectiveAuth(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateSessionFallback(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateAccessAuthMatrix(gateway, errors),
            (gateway, endpoints, topology, errors) -> LimitRules.validateSecurityProfileMinimal(gateway, endpoints,
                    errors),
            TerminalActionRules::validateTerminalAction,
            (gateway, endpoints, topology, errors) -> RouteMatchingRules.validateMethodMembership(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> EdgeRules.validateForwardedTrust(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateCors(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateContentSecurityPolicy(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateHeaderModes(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMode(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMaxSessions(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionIdleTimeout(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMaxSessionsPerSubject(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionMaxCookieSize(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateSessionCookieName(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateClientAuthentication(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateClientJwksPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateUserInfo(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateLoginPath(gateway, errors),
            (gateway, endpoints, topology, errors) -> validateStepUpPath(gateway, errors),
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
            (gateway, endpoints, topology, errors) -> validateEndpointScopesNeedAuthentication(gateway, endpoints,
                    errors),
            (gateway, endpoints, topology, errors) -> validateDefaultReturnUrl(gateway, errors),
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

    /**
     * Rule: an endpoint declaring a non-empty {@code scopes} list must have at least one route whose
     * effective auth is not {@code require: none}.
     * <p>
     * {@code endpoint.scopes} is consumed only by authenticated routes. On an endpoint whose every route
     * resolves {@code require: none} the key would parse and act nowhere, so an operator reading it
     * would believe a scope requirement is in force that no request ever meets. The boot refuses it
     * rather than letting the declaration stand inert. Only enabled endpoints reach this rule. Every
     * violation collects into the shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateEndpointScopesNeedAuthentication(GatewayConfig gateway,
            List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            if (endpoint.scopes().isEmpty()) {
                continue;
            }
            boolean allUnauthenticated = endpoint.routes().stream()
                    .allMatch(route -> requireOf(ScopeChain.of(gateway, endpoint, route)) == Require.NONE);
            if (allUnauthenticated) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_SCOPES_POINTER,
                        ("endpoint '%s' declares scopes but every one of its routes resolves require: none; "
                                + "scopes are consumed only by authenticated routes, so on an unauthenticated "
                                + "endpoint they would never act — remove scopes, or give a route of this "
                                + "endpoint a bearer or session posture")
                                .formatted(endpoint.id())));
            }
        }
    }

    /**
     * Rule: a declared {@code oidc.login.default_return_url} must be same-origin with
     * {@code oidc.redirect_uri}.
     * <p>
     * The value is where a browser is sent whenever no usable return target was supplied, so a
     * cross-origin value would make every such redirect an open redirect to a foreign
     * origin. The check reuses {@link PendingAuthorizationRecord#sameOrigin}, the single predicate
     * the runtime applies to a request-supplied return target, so the boot review and the request
     * path can never disagree about what counts as same-origin: a gateway-relative path is admitted,
     * an absolute URL only on the {@code redirect_uri} origin, and a scheme-relative, backslash, blank
     * or unparseable value is refused. With no {@code redirect_uri} there is no origin to compare an
     * absolute value against, so only a gateway-relative path is admitted. The value is echoed
     * through {@link RuleSupport#renderForMessage}, so it cannot forge boot log lines (CWE-117).
     */
    private static void validateDefaultReturnUrl(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        if (oidc == null) {
            return;
        }
        OidcConfig.Login login = oidc.login();
        String declared = login == null ? null : login.defaultReturnUrl();
        if (declared == null) {
            return;
        }
        String redirectUri = oidc.redirectUri();
        boolean admitted;
        if (redirectUri == null) {
            // No origin to compare an absolute value against: only a gateway-relative path passes, and
            // sameOrigin decides such a path without ever reading the origin argument.
            admitted = declared.startsWith("/") && PendingAuthorizationRecord.sameOrigin(declared, "/");
        } else {
            admitted = PendingAuthorizationRecord.sameOrigin(declared, redirectUri);
        }
        if (!admitted) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_LOGIN_DEFAULT_RETURN_URL_POINTER,
                    ("oidc login default_return_url '%s' is not same-origin with redirect_uri; it is the "
                            + "fallback redirect target, so it must be a gateway path starting with a single "
                            + "'/' or an absolute URL on the redirect_uri origin")
                            .formatted(renderForMessage(declared))));
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
     * Rules: (3) every enabled route's match key ({@code match.path_prefix}, or the exact
     * {@code match.path}) lies inside its
     * declared anchor's namespace; (4) every enabled route whose path lies inside
     * any anchor namespace declares exactly that anchor — an undeclared squatter
     * fails the boot (ADR-0007); (4b) no enabled <em>prefix</em> route whose namespace strictly
     * contains an anchor's {@code path_prefix} serves that anchor's namespace, unless the anchor
     * covers it with a prefix route of its own — the mirror of rule (4), see
     * {@link #checkRouteContainsUncoveredAnchor}.
     * <p>
     * {@code protocol: grpc} routes are exempt from rules (3) and (4) — and from those two only.
     * Both are <em>declaration</em>-scoped: they judge a route by where its own match key sits. A
     * gRPC method path is the service-rooted {@code /{package}.{Service}/{Method}} whose
     * service segment is a single opaque path segment (dots, no slashes), so it is
     * structurally never nested under a gateway path namespace on a segment boundary
     * — no non-root anchor {@code path_prefix} can contain it, and stock gRPC clients
     * cannot be told to prepend a namespace prefix.
     * <p>
     * Rule (4b) judges the opposite geometry — whether the route's own prefix <em>contains</em> an
     * anchor namespace — so that rationale does not reach it, and it is applied to gRPC routes
     * unchanged. Nothing constrains a gRPC route's declared match key to a bare service path, and
     * selection is protocol-blind ({@code RouteTable.lookup} filters on path alone, and
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
     */
    private static void validateAnchorNamespaceMembership(GatewayConfig gateway, List<EndpointConfig> endpoints,
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
     * Rule (4b): the namespace-coverage mirror of rule (4). A <em>prefix</em> route whose namespace
     * strictly contains an anchor's {@code path_prefix}, and which does not resolve that anchor,
     * fails the boot unless the anchor's own namespace is covered by a prefix route that does
     * resolve it.
     * <p>
     * Rules (3) and (4) are <em>declaration</em>-scoped: they judge a route by where its own match
     * key sits. That leaves the opposite geometry unjudged — a route sitting <em>outside</em> and
     * <em>above</em> an anchor, whose namespace swallows the anchor's. Selection then decides what
     * is actually served: for an address inside the anchor namespace, an exact route wins outright
     * and the longest matching prefix wins among the rest. So with an exact {@code path: /admin}
     * route as the anchor's only member, {@code /admin/} and {@code /admin/x} match no route
     * belonging to the anchor and fall through to the broader route — served with <em>its</em> auth
     * posture and <em>its</em> {@code security_headers} block, with the anchor's floor escaped for
     * every address in the namespace but the one exact string (CWE-284).
     * <p>
     * The coverage exception is what keeps the ordinary topology legal, and it is exact rather than
     * approximate. Rule (3) confines an anchored route's match key to the anchor's namespace, so
     * the broadest prefix a member can declare is the anchor prefix itself. When such a route
     * exists, every address in the namespace matches it at the anchor prefix's full length, while
     * any containing route matches at a strictly shorter one — so longest-prefix selection always
     * picks the member, and rule (4) has already refused every exact route outside the anchor whose
     * path lies inside it. The namespace is then genuinely closed and a broader route alongside it
     * is not a bypass.
     * <p>
     * Only prefix routes are judged here: an exact route covers exactly one address, and an exact
     * route whose address lies inside an anchor namespace is rule (4)'s case, not this one. Unlike
     * rules (3) and (4), this rule is <em>not</em> waived for {@code protocol: grpc} routes — see
     * {@link #validateAnchorNamespaceMembership} for why the exemption's rationale stops at the two
     * declaration-scoped rules.
     * <p>
     * Coverage requires the covering route to match its whole namespace, not merely to name it.
     * {@link #anchorsCoveredByOwnPrefixRoute} therefore tests prefix equality <em>and</em>
     * {@link MatchConfig#narrowsBeyondPath()}: a member narrowed on {@code match.methods},
     * {@code match.host} or {@code match.headers} serves only part of its namespace, and the
     * complement — a {@code POST} where the member matched {@code GET}, a request from another host —
     * would fall through to the containing route and be answered under <em>its</em> auth and
     * {@code security_headers} posture. That is the same bypass this rule exists to refuse, reached
     * one dimension over, so such a route does not count as coverage and the containing route is
     * refused as if the anchor had no covering member at all.
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
     * anchor prefix. The middle test is what makes "covered" mean <em>every</em> address in the
     * namespace rather than merely every address the member chose to serve — see
     * {@link #checkRouteContainsUncoveredAnchor} for the bypass it refuses.
     */
    private static Set<String> anchorsCoveredByOwnPrefixRoute(GatewayConfig gateway,
            List<EndpointConfig> endpoints) {
        Set<String> covered = new HashSet<>();
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (route.match().isExact()) {
                    continue;
                }
                if (route.match().narrowsBeyondPath()) {
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
     * Rule: every enabled route must resolve an auth posture through the same
     * {@code route → endpoint → declared-anchor} chain the route table uses
     * ({@link PolicyBlock#AUTH}); a route for which none of the three supplies an
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
                if (PolicyBlock.AUTH.declared(ScopeChain.of(gateway, endpoint, route)) == null) {
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
                ScopeChain chain = ScopeChain.of(gateway, endpoint, route);
                AnchorConfig anchor = chain.anchor();
                AuthConfig anchorAuth = anchor == null ? null : anchor.auth();
                if (anchorAuth == null) {
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

    private static void validateEffectiveAuth(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Set<Require> requires = EnumSet.noneOf(Require.class);
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                requires.add(requireOf(ScopeChain.of(gateway, endpoint, route)));
            }
        }
        if (requires.contains(Require.BEARER) && lacksConfiguredIssuer(gateway)) {
            errors.add(new ConfigError(GATEWAY_FILE, "/token_validation",
                    "effective auth 'bearer' requires token_validation with at least one issuer"));
        }
        if (requires.contains(Require.SESSION) && gateway.oidc() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc", "effective auth 'session' requires an oidc block"));
        }
    }

    /**
     * Rule: every declared {@code auth} block that sets {@code session_fallback: true} — on an anchor,
     * on an enabled endpoint, or on a route of an enabled endpoint — must declare
     * {@code require: bearer}, and the gateway must declare an {@code oidc} block.
     * <p>
     * The key splits a bearer posture into two branches selected by {@code Authorization}-header
     * presence; on {@code require: none} or {@code require: session} there is no bearer branch to
     * split, so it would parse and act nowhere. Its session branch authenticates through the BFF
     * runtime, which exists only when {@code oidc} is declared. The two refusals are independent — a
     * block violating both yields both — and each names the owning anchor, endpoint or route with
     * its JSON pointer and a fixed detail text, never a configured scalar value.
     * <p>
     * Each declared block is judged where it is declared rather than through the effective-auth
     * cascade, so an anchor default that no route inherits is still refused. The anchor floor
     * ({@link #validateAnchorAuthFloor}) compares {@code require} only and is not consulted here.
     * Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateSessionFallback(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        boolean oidcDeclared = gateway.oidc() != null;
        for (AnchorConfig anchor : gateway.anchors().values()) {
            checkSessionFallbackBlock(anchor.auth(), oidcDeclared, GATEWAY_FILE,
                    ANCHORS_POINTER + "/" + anchor.name() + "/auth", "anchor '%s'".formatted(anchor.name()), errors);
        }
        for (EndpointConfig endpoint : endpoints) {
            checkSessionFallbackBlock(endpoint.auth(), oidcDeclared, endpointFile(endpoint), ENDPOINT_AUTH_POINTER,
                    "endpoint '%s'".formatted(endpoint.id()), errors);
            for (RouteConfig route : endpoint.routes()) {
                checkSessionFallbackBlock(route.auth(), oidcDeclared, endpointFile(endpoint),
                        ENDPOINT_ROUTES_POINTER, "route '%s'".formatted(route.id()), errors);
            }
        }
    }

    /**
     * Judges one declared {@code auth} block for {@link #validateSessionFallback}: a no-op when the
     * block is absent or does not set {@code session_fallback: true}.
     */
    private static void checkSessionFallbackBlock(@Nullable AuthConfig auth, boolean oidcDeclared, String file,
            String pointer, String owner, List<ConfigError> errors) {
        if (auth == null || !auth.effectiveSessionFallback()) {
            return;
        }
        if (auth.require() != Require.BEARER) {
            errors.add(new ConfigError(file, pointer, owner + " " + SESSION_FALLBACK_NOT_BEARER_DETAIL));
        }
        if (!oidcDeclared) {
            errors.add(new ConfigError(file, pointer, owner + " " + SESSION_FALLBACK_NO_OIDC_DETAIL));
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
     * Rule: the session mode's mandatory companions (D1/D2b).
     * <p>
     * {@code server} mode requires a {@code store}. {@code cookie} mode does <em>not</em> require an
     * {@code encryption_key} — omitting it selects the generate-on-startup key mode, a first-class
     * production option (at the cost of dropping every session on restart and being unshareable
     * across replicas).
     */
    private static void validateSessionMode(GatewayConfig gateway, List<ConfigError> errors) {
        // isServerMode() is the SHARED predicate over the mode value the OidcConfig.Session canonical
        // constructor already canonicalized. Comparing against a literal here is what previously let
        // a value like 'Server' skip this rule while the runtime still read it as an active mode.
        OidcConfig.Session session = oidcSession(gateway);
        if (session == null) {
            return;
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
     * Rule: the bound on the live sessions of one subject (server mode). A <em>declared</em>
     * {@code oidc.session.max_sessions_per_subject} must be at least {@code 1} and at most the
     * effective {@code max_sessions}, and must not be declared in cookie mode.
     * <p>
     * A value below {@code 1} would leave no subject able to hold a session. A value above the
     * effective {@code max_sessions} can never take effect — the store refuses a session at
     * {@code max_sessions} first — so it states a bound the gateway does not enforce and is refused
     * rather than silently capped. Cookie mode stores no sessions, so it can neither count a subject's
     * sessions nor end one: the key would parse and do nothing there, and is refused for that reason.
     * <p>
     * The upper bound is the one the runtime resolves
     * ({@link OidcConfig.Session#effectiveMaxSessions()}), so an omitted {@code max_sessions} is
     * compared as its default. A no-op when the key is omitted:
     * {@link OidcConfig.Session#effectiveMaxSessionsPerSubject()} then resolves a value inside the
     * range by construction, and cookie mode never reads it. Every violation collects into the shared
     * list; the rule never fails fast (ADR-0009).
     */
    private static void validateSessionMaxSessionsPerSubject(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        Integer declared = session == null ? null : session.maxSessionsPerSubject();
        if (declared == null) {
            return;
        }
        if (session.isCookieMode()) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_SESSIONS_PER_SUBJECT_POINTER,
                    "oidc session max_sessions_per_subject is declared in cookie session mode, which stores no "
                            + "sessions and cannot bound them per subject — remove the key, or use server "
                            + "session mode"));
            return;
        }
        if (declared < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_SESSIONS_PER_SUBJECT_POINTER,
                    "oidc session max_sessions_per_subject must be at least 1, but was %d".formatted(declared)));
            return;
        }
        int effectiveMaxSessions = session.effectiveMaxSessions();
        if (declared > effectiveMaxSessions) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_SESSIONS_PER_SUBJECT_POINTER,
                    ("oidc session max_sessions_per_subject %d exceeds the effective max_sessions %d; the store "
                            + "refuses a session at max_sessions first, so a bound per subject above it never "
                            + "takes effect — declare a value of at most max_sessions, or omit the key")
                            .formatted(declared, effectiveMaxSessions)));
        }
    }

    /**
     * Rule: the session idle timeout, in both session modes. A <em>declared</em>
     * {@code oidc.session.idle_timeout_seconds} must be at least {@code 1} and at most the effective
     * {@code ttl_seconds}.
     * <p>
     * A value below {@code 1} would end every session at once. A value above the effective
     * {@code ttl_seconds} can never take effect — the absolute lifetime ends the session first — so
     * it states an idle timeout the gateway does not enforce, and it is refused rather than silently
     * capped. The schema already declares the lower bound; it is repeated here because this validator
     * is also reached with documents the schema did not see, and because the upper bound depends on
     * another key and cannot be declared there at all.
     * <p>
     * The upper bound is the lifetime the runtime producer resolves ({@link #resolvedSessionTtl}), so
     * an omitted {@code ttl_seconds} is compared as its default. A no-op when the key is omitted:
     * {@link OidcConfig.Session#effectiveIdleTimeoutSeconds()} then resolves a value inside the range
     * by construction, for any {@code ttl_seconds}. Every violation collects into the shared list; the
     * rule never fails fast (ADR-0009).
     */
    private static void validateSessionIdleTimeout(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        Integer declared = session == null ? null : session.idleTimeoutSeconds();
        if (declared == null) {
            return;
        }
        if (declared < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_IDLE_TIMEOUT_POINTER,
                    "oidc session idle_timeout_seconds must be at least 1, but was %d".formatted(declared)));
            return;
        }
        long effectiveTtlSeconds = resolvedSessionTtl(session).toSeconds();
        if (declared > effectiveTtlSeconds) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_IDLE_TIMEOUT_POINTER,
                    ("oidc session idle_timeout_seconds %d exceeds the effective ttl_seconds %d; a session ends "
                            + "at ttl_seconds whatever its activity, so an idle timeout above it never takes "
                            + "effect — declare a value of at most ttl_seconds, or omit the key")
                            .formatted(declared, effectiveTtlSeconds)));
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
     * rejects with {@code 431}. The <em>range</em> check is a no-op when the key is omitted; the
     * deliverability warning below is not — see "the EFFECTIVE budget" further down.
     * <p>
     * <strong>An in-range budget whose emitted header outgrows the browser guarantee is warned,
     * never refused.</strong> The validated range stays {@code 40..8192} exactly as it is. The
     * comparison is against the emitted {@code Set-Cookie} <em>header</em>, not against the value
     * budget: this key declares a sealed <em>value</em> budget, while the ~4096 bytes RFC 6265 6.1
     * guarantees govern the whole header — name, value and attributes together. Comparing the value
     * budget against the header guarantee would leave a band silent while the gateway emitted a
     * header no browser is obliged to keep — the same false guarantee this record exists to
     * announce. A budget above the threshold is still a legitimate posture for a client that
     * keeps whatever it is sent, so it boots — and it emits
     * {@link ConfigLogMessages.WARN#COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE}, because raising the
     * budget that far silences the {@code ApiSheriff-114} seal refusal without removing the problem:
     * the browser then drops the oversized {@code Set-Cookie} with no error on either side, and no
     * later signal exists for the gateway to report.
     * <p>
     * <strong>The overhead is derived from THIS gateway's configuration, never from a constant.</strong>
     * The header {@link SealedSessionCookieCodec#toSetCookieHeader} emits wraps the configured
     * {@code session.cookie_name} and a {@code Max-Age} carrying the configured
     * {@code session.ttl_seconds} around the value, so the threshold is
     * {@link SealedSessionCookieCodec#BROWSER_PER_COOKIE_HEADER_GUARANTEE} less
     * {@link SealedSessionCookieCodec#setCookieHeaderOverhead(String, java.time.Duration)} for the
     * <em>resolved</em> pair — the same derivation the header assembly itself counts. A fixed
     * threshold assumes the default name and a four-digit {@code Max-Age}: a longer cookie name or a
     * TTL past 9999 seconds emits a larger header than the constant describes, and the warning would
     * then stay silent on a configuration that is already undeliverable. Both keys fall back to the
     * values the runtime producer resolves for them, so validation reasons about the header the
     * gateway will actually send.
     * <p>
     * <strong>The warning is cookie-mode only; the range check is not.</strong> Outside
     * {@code session.mode: cookie} no sealed session {@code Set-Cookie} is ever emitted — a
     * server-mode or bearer-only gateway has no header for the guarantee to govern — so warning
     * there is noise about a header that does not exist. The bounds check stays unconditional: an
     * explicitly declared out-of-range value is a misconfiguration whatever the mode, and refusing
     * it at boot is what stops it becoming live the moment the mode is switched.
     * <p>
     * <strong>The comparison runs on the EFFECTIVE budget, declared or defaulted.</strong> The
     * gateway emits a {@code Set-Cookie} of exactly the same size whether the operator wrote
     * {@code max_cookie_size} down or left it to the default, so a guard that only looked at
     * declared values was blind to the configuration most gateways actually run. That blindness had
     * a concrete band: with the default at {@code 4096} and the key omitted, every default
     * cookie-mode gateway emitted a header past the browser guarantee and nothing said so. Closing it
     * takes both halves — the default is now the browser-safe {@code 4019}
     * ({@link SealedSessionCookieCodec#DEFAULT_COOKIE_VALUE_BUDGET}), and the comparison below reads
     * the effective budget rather than returning early on an omitted key.
     * <p>
     * Warning on the omitted case therefore no longer fires on every cookie-mode gateway — under the
     * default name and TTL the default budget lands exactly ON the guarantee, not past it. What it
     * does still catch is the case that used to be silent: a gateway running a longer
     * {@code cookie_name} or a wider {@code Max-Age}, where the same default budget produces a header
     * the browser is not obliged to keep.
     * <p>
     * The <em>range</em> check stays gated on a declared value, because there is nothing to range-check
     * when nothing was declared and the default is in range by construction.
     */
    private static void validateSessionMaxCookieSize(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        if (session == null) {
            return;
        }
        Integer declared = session.maxCookieSize();
        if (declared != null && (declared < SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_FLOOR
                || declared > SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING)) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_MAX_COOKIE_SIZE_POINTER,
                    "oidc session max_cookie_size must be between %d and %d bytes, but was %d"
                            .formatted(SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_FLOOR,
                                    SealedSessionCookieCodec.COOKIE_VALUE_BUDGET_CEILING, declared)));
            return;
        }
        // isCookieMode() is the SHARED mode predicate — the spelling is never re-derived here.
        if (!session.isCookieMode()) {
            return;
        }
        int effectiveBudget = Objects.requireNonNullElse(declared,
                SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET);
        int overhead = SealedSessionCookieCodec.setCookieHeaderOverhead(
                resolvedCookieName(session), resolvedSessionTtl(session));
        int headerBytes = effectiveBudget + overhead;
        if (headerBytes > SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE) {
            LOGGER.warn(ConfigLogMessages.WARN.COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE, effectiveBudget,
                    headerBytes,
                    SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE - overhead);
        }
    }

    /**
     * Rule: a declared {@code oidc.session.cookie_name} must not drop the guarantees the gateway-owned
     * cookies carry. The name is refused at boot unless it
     * <ol>
     * <li>starts with the case-sensitive {@code __Host-} prefix followed by at least one further
     * character — the prefix shared by {@link SessionCookieCodec#DEFAULT_COOKIE_NAME},
     * {@link BindingCookieCodec#COOKIE_NAME} and {@link RpInitiatedLogout#LOGOUT_STATE_COOKIE_NAME},
     * and the one thing that makes the browser <em>enforce</em> {@code Secure} + {@code Path=/} + no
     * {@code Domain} rather than merely receive them;</li>
     * <li>is a non-empty RFC 9110 {@code token} ({@link #COOKIE_NAME_SHAPE}) — the runtime writes the
     * name verbatim into {@code Set-Cookie}, so a {@code ;}, {@code =}, whitespace or control
     * character would be header injection; and</li>
     * <li>differs from the binding and logout-state cookie names, since a collision would let one
     * cookie silently overwrite another in the browser.</li>
     * </ol>
     * <p>
     * The refusal is the only guard: the BFF runtime producer consumes the declared name verbatim, and
     * the schema declares the key an unrestricted string so this validator stays the single enforcing
     * authority. It applies in both session modes, because both emit the configured name. An omitted
     * key is never refused — it resolves to {@link SessionCookieCodec#DEFAULT_COOKIE_NAME}.
     * <p>
     * The offending value is echoed through {@link RuleSupport#renderForMessage}, so a name carrying CR/LF cannot
     * forge boot ERROR log lines (CWE-117). All failed requirements of one name are reported in a
     * single violation, collected into the shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateSessionCookieName(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig.Session session = oidcSession(gateway);
        String declared = session == null ? null : session.cookieName();
        if (declared == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        if (!declared.startsWith(HOST_COOKIE_PREFIX)) {
            failures.add("does not start with the case-sensitive '" + HOST_COOKIE_PREFIX + "' prefix");
        } else if (declared.length() == HOST_COOKIE_PREFIX.length()) {
            failures.add("is the bare '" + HOST_COOKIE_PREFIX + "' prefix with no name after it");
        }
        if (!COOKIE_NAME_SHAPE.matcher(declared).matches()) {
            failures.add("is not a non-empty RFC 9110 token (no whitespace, control characters or "
                    + "separators such as ';' or '=')");
        }
        if (GATEWAY_OWNED_COOKIE_NAMES.contains(declared)) {
            failures.add("collides with the gateway-owned cookie of the same name");
        }
        if (!failures.isEmpty()) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_SESSION_COOKIE_NAME_POINTER,
                    ("oidc session cookie_name '%s' %s; the name is written verbatim into Set-Cookie, so it "
                            + "must be a '%s'-prefixed RFC 9110 token distinct from the gateway-owned "
                            + "cookies %s — declare such a name, or omit the key for the default '%s'")
                            .formatted(renderForMessage(declared), String.join(" and ", failures),
                                    HOST_COOKIE_PREFIX, String.join(", ", GATEWAY_OWNED_COOKIE_NAMES),
                                    SessionCookieCodec.DEFAULT_COOKIE_NAME)));
        }
    }

    /**
     * Rule: the client-authentication mode must be unambiguous and usable.
     * <p>
     * The presence of {@code oidc.client_secret} selects {@code client_secret_basic}; its absence
     * selects {@code private_key_jwt}, signed with the key {@code oidc.client_authentication.key_file}
     * names or with a generated one. The mode is read through
     * {@link OidcConfig#usesClientSecret()}, the predicate the runtime producer reads as well, so
     * validation and runtime cannot resolve it differently. Two documents are refused:
     * <ul>
     *   <li><strong>Both keys declared.</strong> The two select different client authentications, and
     *       preferring one would be a silent choice between two credentials. One error is collected
     *       at {@code /oidc/client_secret}; it names both keys and neither value.</li>
     *   <li><strong>A blank secret.</strong> A variable set to the empty string resolves to an empty
     *       secret. It is a present key the token engine cannot use, so it is refused here instead of
     *       surfacing as the engine's own exception while the runtime is assembled.</li>
     * </ul>
     * A document with only the secret, only the key file, or neither is admitted. The rule holds for
     * a gateway whose BFF runtime is inert as well, because it is a property of the document. Both
     * detail texts are fixed, so neither the secret nor the configured path reaches the boot log.
     * Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     */
    private static void validateClientAuthentication(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        if (oidc == null || !oidc.usesClientSecret()) {
            return;
        }
        String clientSecret = oidc.clientSecret();
        if (clientSecret != null && clientSecret.isBlank()) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_CLIENT_SECRET_POINTER, CLIENT_SECRET_BLANK_DETAIL));
        }
        OidcConfig.ClientAuthenticationSettings clientAuthentication = oidc.clientAuthentication();
        if (clientAuthentication != null && clientAuthentication.keyFile() != null) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_CLIENT_SECRET_POINTER,
                    CLIENT_SECRET_WITH_KEY_FILE_DETAIL));
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
     * The session-cookie name the runtime will resolve, for deriving the emitted header size.
     * <p>
     * A blank declared name is folded onto the default rather than passed through: the schema
     * declares {@code cookie_name} an unrestricted string, so {@code cookie_name: ""} is
     * schema-valid, and handing it to the codec's overhead derivation would raise from inside a
     * validator whose contract is to <em>collect</em> violations rather than throw. The fold stays
     * necessary even though a blank name is now refused at boot by {@link #validateSessionCookieName}:
     * rules do not fail fast, so this derivation still runs over the refused value in the same pass.
     */
    private static String resolvedCookieName(OidcConfig.Session session) {
        String declared = session.cookieName();
        return declared == null || declared.isBlank()
                ? SessionCookieCodec.DEFAULT_COOKIE_NAME
                : declared;
    }

    /**
     * The absolute session lifetime the runtime will resolve, for deriving the {@code Max-Age} width
     * of the emitted header. Shares {@link OidcConfig.Session#DEFAULT_TTL_SECONDS} with the runtime
     * producer so validation can never reason about a TTL the gateway does not run.
     */
    private static Duration resolvedSessionTtl(OidcConfig.Session session) {
        return Duration.ofSeconds(Objects.requireNonNullElse(
                session.ttlSeconds(), OidcConfig.Session.DEFAULT_TTL_SECONDS));
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

    /**
     * Whether {@code candidate} lies <em>strictly</em> within the {@code container} namespace: the
     * containment of {@link #prefixContains} minus the equal-namespace case, so a route declared at
     * an anchor's own prefix does not count as containing it.
     *
     * @param container the owning prefix
     * @param candidate the prefix tested for strict containment
     * @return {@code true} when {@code candidate} is inside, and not equal to, {@code container}
     */
    private static boolean strictlyContains(String container, String candidate) {
        return prefixContains(container, candidate)
                && !RouteTableBuilder.normalizePrefix(container)
                .equals(RouteTableBuilder.normalizePrefix(candidate));
    }
}
