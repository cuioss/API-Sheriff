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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the reserved OIDC paths an operator declares in the
 * {@code oidc} block: the client JWKS path, {@code oidc.user_info.path}, {@code oidc.login.path} and
 * {@code oidc.step_up.path}.
 * <p>
 * The client JWKS path rule ({@link #validateClientJwksPath}) refuses an
 * {@code oidc.client_authentication.jwks_path} — declared, or the default it resolves to when omitted —
 * that another reserved OIDC endpoint already claims. The reserved-path registry keeps the first
 * registration for a path, so the collision would otherwise drop the key-set endpoint without a
 * diagnostic. The same rule refuses a path that is not a canonical reserved path
 * ({@link #canonicalReservedPathRefusal}) — held to the canonical-path review of {@link PortalRules}, and
 * carrying neither a matrix parameter nor a percent-encoded character — because the path is matched by
 * exact equality with the canonical request path and a request that spells such a value as configured
 * would never be matched.
 * <p>
 * The reserved-path rules ({@link #validateUserInfo}, {@link #validateLoginPath} and
 * {@link #validateStepUpPath}) hold {@code oidc.user_info.path}, {@code oidc.login.path} and
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
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast (ADR-0009). Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ReservedPathRules {

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
     *
     * @param gateway the bound gateway document, whose {@code oidc.client_authentication.jwks_path} is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateClientJwksPath(GatewayConfig gateway, List<ConfigError> errors) {
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
     * Rule: the session/user-info reserved endpoint (fold, D1). When an
     * {@code oidc.user_info} block is present, its {@code path} is held to
     * {@link #validateReservedPath} — an absolute gateway path in canonical form, since the path is
     * matched exactly against the canonical request path — and every {@code default_view} claim must
     * lie within the {@code allowed_claims} allowlist — the operator-owned allowlist caps
     * disclosure and the default view can never exceed it. An empty allowlist is the
     * secure closed default (nothing disclosed) and is not itself an error. Every
     * violation collects into the shared list; the rule never fails fast.
     *
     * @param gateway the bound gateway document, whose {@code oidc.user_info} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateUserInfo(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.login.path} is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateLoginPath(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.step_up.path} is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateStepUpPath(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        OidcConfig.StepUp stepUp = oidc == null ? null : oidc.stepUp();
        validateReservedPath(STEP_UP_PATH, stepUp == null ? null : stepUp.path(), errors);
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
                && path.chars().noneMatch(ReservedPathRules::isControlOrWhitespace);
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
