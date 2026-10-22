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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.WILDCARD_ORIGIN;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.util.List;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code security_headers} blocks — the global
 * block and the block of every anchor alike.
 * <p>
 * The CORS rule ({@link #validateCors}) refuses a wildcard origin combined with
 * {@code allow_credentials}. CORS is global only (ADR-0007 Amendment A1): it is evaluated before route
 * selection and authentication, so no anchor can scope it, and the rule checks the gateway block
 * alone. The content-security-policy rule ({@link #validateContentSecurityPolicy}) refuses a policy
 * carrying a CR, LF or other control character, because the value is served verbatim as a response
 * header and a line terminator would inject one (CWE-113); a refused value is echoed only through
 * {@link RuleSupport#renderForMessage}, so it cannot forge boot log lines (CWE-117). The header-modes
 * rule ({@link #validateHeaderModes}) refuses a {@code header_modes} entry for a header its own block
 * does not enable, since such a mode would parse and do nothing.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast (ADR-0009). Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class HeaderRules {

    /**
     * Rule: the global CORS block must not combine a wildcard origin with {@code allow_credentials}.
     * <p>
     * CORS is global only (ADR-0007 Amendment A1): it is evaluated at stage 0, before route selection
     * and authentication, so no anchor can scope it. The bundled schema refuses a {@code cors} block
     * under {@code anchors.*.security_headers} at load, which is why only the gateway block is checked
     * here.
     *
     * @param gateway the bound gateway document, whose global {@code security_headers.cors} is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateCors(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose global and anchor {@code security_headers} are judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateContentSecurityPolicy(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose global and anchor {@code security_headers} are judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateHeaderModes(GatewayConfig gateway, List<ConfigError> errors) {
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
}
