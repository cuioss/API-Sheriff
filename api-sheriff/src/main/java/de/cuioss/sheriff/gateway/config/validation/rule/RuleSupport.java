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

import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The helpers the boot-time {@link ValidationRule}s share across concern classes: where a {@link ConfigError}
 * is reported (the file, the JSON pointer and the anchors pointer), how an offending configured value
 * is echoed into a refusal message, which anchor a route resolves, which protocol a route rides, which
 * {@code auth.require} a route resolves, the wildcard-origin token, the RFC 9110 media-type token,
 * anchor-namespace containment ({@link #prefixContains} and {@link #strictlyContains}), and whether
 * the gateway lacks a configured token issuer ({@link #lacksConfiguredIssuer}).
 * <p>
 * Each helper exists exactly once. {@link #renderForMessage} above all must stay the single copy of
 * the CWE-117 escaping. The rule for every refusal: a configured scalar echoed into its message is
 * passed through {@link #renderForMessage} first, so that a hostile value cannot forge a boot ERROR
 * log line.
 * <p>
 * Stateless, and therefore safe for concurrent use. Free of framework dependencies as the pre-boot
 * rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class RuleSupport {

    /** The file name every refusal of a {@code gateway.yaml} key is reported against. */
    public static final String GATEWAY_FILE = "gateway.yaml";

    /** The JSON pointer of the {@code routes} list in an endpoint file. */
    public static final String ENDPOINT_ROUTES_POINTER = "/endpoint/routes";

    /** The JSON pointer of the {@code anchors} map in {@code gateway.yaml}. */
    public static final String ANCHORS_POINTER = "/anchors";

    /** The wildcard origin token, refused in a WebSocket allowlist and together with CORS credentials. */
    public static final String WILDCARD_ORIGIN = "*";

    /**
     * An RFC 9110 {@code token}: the character set a media type's type, subtype, parameter name and
     * unquoted parameter value are each drawn from. Notably it excludes CR, LF, whitespace and every
     * other control character. The asset content-type value rule builds its media-type pattern
     * ({@code AssetDefaultsRules.MEDIA_TYPE}) from it, and the session-cookie name rule reuses it as
     * the whole-name shape, so the two gates cannot drift.
     * <p>
     * The quantifier is <em>possessive</em> ({@code ++}), not greedy. Every delimiter the surrounding
     * {@code AssetDefaultsRules.MEDIA_TYPE} pattern places between tokens — {@code /}, {@code ;},
     * {@code =}, space and tab — is outside this character class, so a token run can never productively
     * give a character back; forbidding the give-back is therefore behaviour-neutral and removes the
     * backtracking bookkeeping the engine would otherwise carry.
     */
    // java:S6418 — the RFC 9110 `token` character class MEDIA_TYPE is built from, not a credential.
    // The rule is a name heuristic and fires only on the TOKEN substring in the constant's name;
    // TOKEN is the correct RFC term here, so the name stays and the finding is suppressed instead.
    @SuppressWarnings("java:S6418")
    public static final String MEDIA_TYPE_TOKEN = "[A-Za-z0-9!#$%&'*+.^_`|~-]++";

    /** The cap on the offending value a refusal message echoes back to the operator. */
    private static final int ECHOED_VALUE_MAX_LENGTH = 60;

    /**
     * The file name every refusal of an endpoint-file key is reported against.
     *
     * @param endpoint the endpoint whose file is named
     * @return the endpoint file, relative to the configuration root
     */
    public static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }

    /**
     * Renders an offending configuration value for a refusal message: every character outside
     * printable ASCII — CR and LF above all — becomes a {@code \\uXXXX} escape, and at most
     * {@code ECHOED_VALUE_MAX_LENGTH} (60) characters of the input are rendered, followed by
     * {@code ...} when the input is longer. Because each escape expands one input character to six,
     * the rendering itself can be longer than 60 characters. The operator still sees what they
     * typed; the boot ERROR log cannot be line-forged by it.
     *
     * @param value the configured value to echo
     * @return the escaped and capped rendering, safe to embed in a refusal message
     */
    public static String renderForMessage(String value) {
        StringBuilder rendered = new StringBuilder();
        int limit = Math.min(value.length(), ECHOED_VALUE_MAX_LENGTH);
        for (int index = 0; index < limit; index++) {
            char current = value.charAt(index);
            if (current >= 0x20 && current < 0x7F) {
                rendered.append(current);
            } else {
                rendered.append("\\u%04X".formatted((int) current));
            }
        }
        if (value.length() > limit) {
            rendered.append("...");
        }
        return rendered.toString();
    }

    /**
     * The anchor name a route declares: its own {@code anchor}, or its endpoint's when the route
     * declares none.
     *
     * @param endpoint the endpoint declaring the route
     * @param route    the route whose anchor name is read
     * @return the declared anchor name, {@code null} when neither the route nor its endpoint names one
     */
    public static @Nullable String declaredAnchorName(EndpointConfig endpoint, RouteConfig route) {
        return route.anchor() != null ? route.anchor() : endpoint.anchor();
    }

    /**
     * The route's effective protocol — the declared value, or {@link Protocol#HTTP} when omitted.
     *
     * @param route the route whose protocol is resolved
     * @return the declared protocol, {@link Protocol#HTTP} when the route declares none
     */
    public static Protocol effectiveProtocol(RouteConfig route) {
        Protocol declared = route.protocol();
        return declared != null ? declared : Protocol.HTTP;
    }

    /**
     * The route's effective {@code auth.require}, resolved through {@link PolicyBlock#AUTH};
     * {@link Require#NONE} when no scope declares an auth block (that route is refused separately).
     *
     * @param chain the route's scope chain
     * @return the effective {@code auth.require}, {@link Require#NONE} when no scope declares an auth block
     */
    public static Require requireOf(ScopeChain chain) {
        AuthConfig auth = PolicyBlock.AUTH.declared(chain);
        return auth == null ? Require.NONE : auth.require();
    }

    /**
     * Whether the gateway declares no usable bearer-validation backing — either no
     * {@code token_validation} block at all, or one that declares no issuer. The single shared
     * predicate behind both the effective-auth rule and the {@code access: authenticated} anchor
     * matrix, so the two can never disagree about what backs a {@code bearer} posture.
     *
     * @param gateway the bound gateway document
     * @return {@code true} when no {@code token_validation} issuer is configured
     */
    public static boolean lacksConfiguredIssuer(GatewayConfig gateway) {
        TokenValidationConfig tokenValidation = gateway.tokenValidation();
        return tokenValidation == null || tokenValidation.issuers().isEmpty();
    }

    /**
     * Whether {@code candidate} lies within the {@code container} namespace on a
     * segment boundary: an exact match, or a child path under {@code container/}.
     *
     * @param container the owning prefix
     * @param candidate the prefix tested for containment
     * @return {@code true} when {@code candidate} is inside {@code container}
     */
    public static boolean prefixContains(String container, String candidate) {
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
    public static boolean strictlyContains(String container, String candidate) {
        return prefixContains(container, candidate)
                && !RouteTableBuilder.normalizePrefix(container)
                .equals(RouteTableBuilder.normalizePrefix(candidate));
    }
}
