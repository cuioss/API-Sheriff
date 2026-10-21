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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that judge how the gateway's OIDC client authenticates to the
 * identity provider, where it dials it, and where it sends a browser back to.
 * <p>
 * The client-authentication rule ({@link #validateClientAuthentication}) refuses a document that
 * declares both {@code oidc.client_secret} and {@code oidc.client_authentication.key_file}, since the
 * two select different client authentications, and a declared {@code oidc.client_secret} that resolves
 * to a blank value. The schema does not encode the exclusion; this rule owns it, because its message has
 * to name both keys.
 * <p>
 * The back-channel origin rule ({@link #validateBackChannelOrigin}) holds
 * {@code oidc.back_channel_origin} to a bare {@code http}/{@code https} origin, admits an {@code http}
 * origin only under {@code oidc.back_channel_allow_insecure_http: true}, and refuses that opt-in when
 * there is nothing to opt in for — each refusal on its key's own pointer, in fixed text. The cleartext
 * WARN for an admitted {@code http} origin is emitted where the runtime is assembled, not here: this
 * class logs nothing.
 * <p>
 * The default-return-URL rule ({@link #validateDefaultReturnUrl}) requires a declared
 * {@code oidc.login.default_return_url} to be same-origin with {@code oidc.redirect_uri}, judged by the
 * predicate the runtime applies to a request-supplied return target.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast (ADR-0009). Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class OidcClientRules {

    private static final String OIDC_CLIENT_SECRET_POINTER = "/oidc/client_secret";
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    private static final String OIDC_LOGIN_DEFAULT_RETURN_URL_POINTER = "/oidc/login/default_return_url";
    private static final String OIDC_BACK_CHANNEL_ORIGIN_POINTER = "/oidc/back_channel_origin";
    private static final String OIDC_BACK_CHANNEL_ALLOW_INSECURE_HTTP_POINTER =
            "/oidc/back_channel_allow_insecure_http";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";

    /**
     * The remedy every shape refusal of {@code oidc.back_channel_origin} closes with. Fixed text, so
     * the boot log never echoes the configured value.
     */
    private static final String BACK_CHANNEL_ORIGIN_REMEDY =
            " — declare an origin of the form scheme://host[:port] (optionally followed by a single '/'), "
                    + "or remove oidc.back_channel_origin to dial the issuer's own origin";

    /**
     * The fixed detail of the refusal of an {@code http} {@code oidc.back_channel_origin} without the
     * explicit cleartext opt-in.
     */
    private static final String BACK_CHANNEL_HTTP_WITHOUT_OPT_IN_DETAIL =
            "oidc.back_channel_origin uses the http scheme but oidc.back_channel_allow_insecure_http is not true; "
                    + "a cleartext back channel carries the client credential and every token, so it is admitted "
                    + "only by the explicit opt-in — declare an https origin, or set "
                    + "oidc.back_channel_allow_insecure_http: true";

    /**
     * The fixed detail of the refusal of the cleartext opt-in when no {@code oidc.back_channel_origin}
     * is declared.
     */
    private static final String BACK_CHANNEL_OPT_IN_WITHOUT_ORIGIN_DETAIL =
            "oidc.back_channel_allow_insecure_http is true but no oidc.back_channel_origin is declared; the "
                    + "opt-in admits an http back-channel origin and has nothing to admit — remove "
                    + "oidc.back_channel_allow_insecure_http, or declare the http origin it is meant for";

    /**
     * The fixed detail of the refusal of the cleartext opt-in when the declared
     * {@code oidc.back_channel_origin} is {@code https}.
     */
    private static final String BACK_CHANNEL_OPT_IN_WITH_HTTPS_DETAIL =
            "oidc.back_channel_allow_insecure_http is true but oidc.back_channel_origin uses the https scheme; "
                    + "the opt-in would widen the whole OIDC client to cleartext for nothing — remove "
                    + "oidc.back_channel_allow_insecure_http";

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
     *
     * @param gateway the bound gateway document, whose {@code oidc} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateClientAuthentication(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateDefaultReturnUrl(GatewayConfig gateway, List<ConfigError> errors) {
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

    /**
     * Rule: the back-channel origin and its cleartext opt-in.
     * <p>
     * {@code oidc.back_channel_origin} names where the gateway dials its server-to-server calls to the
     * identity provider; {@code oidc.issuer} stays the identity. The token engine re-targets only the
     * endpoints the issuer advertises on its own origin, and the value comes from configuration alone,
     * so no private-address rule applies — naming an internal address is the purpose of the key. What
     * is held here is the shape and the transport:
     * <ul>
     *   <li><strong>Shape.</strong> A declared origin must be an absolute {@code http} or {@code https}
     *       URI with a host, carrying no user-info, no query, no fragment and no path other than a
     *       single trailing {@code /}. A blank value is refused as such. These are the refusals the
     *       engine itself raises while the runtime is assembled, moved to boot validation so they arrive
     *       on the key's own pointer instead of as the engine's exception.</li>
     *   <li><strong>Cleartext.</strong> An {@code http} origin is refused unless
     *       {@code oidc.back_channel_allow_insecure_http} is {@code true}: the back channel carries the
     *       client credential and every token, and the opt-in widens the whole OIDC client to cleartext,
     *       not just this origin.</li>
     *   <li><strong>An inert opt-in.</strong> {@code oidc.back_channel_allow_insecure_http: true} with no
     *       origin, or with an {@code https} origin, has nothing to opt in for and is refused rather
     *       than left in place, where it would still widen the client.</li>
     * </ul>
     * An absent origin with no opt-in passes: the issuer's own origin is then dialled for everything. A
     * value that fails the shape check is not judged against the opt-in as well, so one defect is
     * reported once. Every refusal is reported on its key's own JSON pointer in fixed text that never
     * echoes the configured value, and collects into the shared list; the rule never fails fast
     * (ADR-0009).
     *
     * @param gateway the bound gateway document, whose {@code oidc} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateBackChannelOrigin(GatewayConfig gateway, List<ConfigError> errors) {
        OidcConfig oidc = gateway.oidc();
        if (oidc == null) {
            return;
        }
        boolean optIn = Boolean.TRUE.equals(oidc.backChannelAllowInsecureHttp());
        String origin = oidc.backChannelOrigin();
        if (origin == null) {
            if (optIn) {
                errors.add(new ConfigError(GATEWAY_FILE, OIDC_BACK_CHANNEL_ALLOW_INSECURE_HTTP_POINTER,
                        BACK_CHANNEL_OPT_IN_WITHOUT_ORIGIN_DETAIL));
            }
            return;
        }
        Optional<String> shapeRefusal = backChannelOriginShapeRefusal(origin);
        if (shapeRefusal.isPresent()) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_BACK_CHANNEL_ORIGIN_POINTER,
                    "oidc.back_channel_origin " + shapeRefusal.get() + BACK_CHANNEL_ORIGIN_REMEDY));
            return;
        }
        // The shape check admitted the value, so it parses and its scheme is http or https.
        boolean cleartext = HTTP_SCHEME.equalsIgnoreCase(URI.create(origin).getScheme());
        if (cleartext && !optIn) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_BACK_CHANNEL_ORIGIN_POINTER,
                    BACK_CHANNEL_HTTP_WITHOUT_OPT_IN_DETAIL));
        } else if (!cleartext && optIn) {
            errors.add(new ConfigError(GATEWAY_FILE, OIDC_BACK_CHANNEL_ALLOW_INSECURE_HTTP_POINTER,
                    BACK_CHANNEL_OPT_IN_WITH_HTTPS_DETAIL));
        }
    }

    /**
     * The reason a declared {@code oidc.back_channel_origin} is not a bare {@code http}/{@code https}
     * origin, or empty when it is one. Every reason is fixed text; none echoes the configured value.
     *
     * @param origin the declared origin, never {@code null}
     * @return the refusal reason, phrased to follow the key name, or empty when the shape holds
     */
    private static Optional<String> backChannelOriginShapeRefusal(String origin) {
        if (origin.isBlank()) {
            return Optional.of("is declared but blank");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException _) {
            return Optional.of("is not a well-formed URI");
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null
                || !(HTTP_SCHEME.equalsIgnoreCase(scheme) || HTTPS_SCHEME.equalsIgnoreCase(scheme))) {
            return Optional.of("must be an absolute http or https URI naming a host");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("must not carry user-info; credentials never belong in an origin");
        }
        if (uri.getRawQuery() != null) {
            return Optional.of("must not carry a query ('?')");
        }
        if (uri.getRawFragment() != null) {
            return Optional.of("must not carry a fragment ('#')");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return Optional.of("must not carry a path; an origin is scheme, host and port only");
        }
        return Optional.empty();
    }
}
