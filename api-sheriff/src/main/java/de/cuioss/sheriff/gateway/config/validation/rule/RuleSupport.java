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

import java.util.Optional;
import java.util.regex.Pattern;


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
 * Each helper exists exactly once. {@link #renderForMessage} and its uncapped sibling
 * {@link #renderForLog} are the CWE-117 escaping of the boot log: the route-table and WebSocket-relay
 * log records and the boot sink that logs every violation reuse them rather than carrying an escaper of
 * their own. The offline {@code --validate-config} report is escaped by a second escaper,
 * {@code ConfigValidationCommand.asSingleReportLine}, which renders a whole report line rather than one
 * value: a line feed becomes the two characters {@code \n}, a tab is kept, and every other control
 * character and every format, line-separator and paragraph-separator character becomes {@code ?}.
 * The two are kept apart because merging them would change the report the command prints. The rule for
 * every refusal message and every log record a rule emits: a configured free-form scalar it echoes — an endpoint or
 * route id, an anchor name, a path, a host, an alias, a list entry — is passed through
 * {@link #renderForMessage} first, so that a hostile value cannot forge a boot log line. Only a value
 * drawn from a closed vocabulary (an enum constant, a gateway-owned key name or built-in entry) or fixed
 * text is echoed as it is.
 * <p>
 * {@code RouteTableBuilder} follows that rule in its log records — the route posture, the anchor
 * override and the disabled same-origin check render each id through {@link #renderForMessage} — while
 * its {@code RouteTableException} refusal messages format the endpoint id and the route id as they are.
 * An id reaching it has passed the {@code endpoint.schema.json} id pattern {@code ^[a-z][a-z0-9-]*$}:
 * lower-case letters, digits and hyphens only, and no trailing line terminator, which the schema
 * validation refuses as well ({@code ConfigLoaderTest.IdsEndingInALineTerminator}). Such an id carries
 * no character either escaper would change.
 * <p>
 * Stateless, and therefore safe for concurrent use. Free of framework dependencies as the pre-boot
 * rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class RuleSupport {

    /**
     * An {@code allowed_egress_hosts} entry carrying a port, matched against the WHOLE entry
     * ({@link java.util.regex.Matcher#matches()}): a single-colon {@code name:digits} form, or a
     * bracketed {@code [v6]:digits} form. The name part of the first alternative excludes every colon,
     * so a bare IPv6 literal such as {@code fd00::1} — whose second colon cannot be followed by digits
     * only — never matches. Every quantifier is possessive; the character classes are disjoint from
     * the delimiters that follow them, so no give-back could ever succeed.
     */
    private static final Pattern HOST_WITH_PORT = Pattern.compile("[^:\\[\\]]*+:\\d++|\\[[^\\]]*+\\]:\\d++");

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

    /** {@code U+2028 LINE SEPARATOR}: a line break to a reader that honours it, though no control character. */
    private static final int LINE_SEPARATOR = 0x2028;

    /** {@code U+2029 PARAGRAPH SEPARATOR}: a paragraph break to a reader that honours it, though no control character. */
    private static final int PARAGRAPH_SEPARATOR = 0x2029;

    /**
     * The file name every refusal of an endpoint-file key is reported against.
     * <p>
     * Derived from the id rather than carried from the load, because a bound {@link EndpointConfig}
     * holds no source path. The derivation is exact, not a guess: {@code ConfigLoader} refuses an
     * endpoint whose {@code id} differs from its file name and drops it before any rule runs, so every
     * endpoint a rule sees was loaded from {@code endpoints/<id>.yaml} — the file this names.
     *
     * @param endpoint the endpoint whose file is named
     * @return the endpoint file, relative to the configuration root
     */
    public static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }

    /**
     * Renders an offending configuration value for a refusal message: every character that can forge
     * or disguise a log line becomes a {@code \\uXXXX} escape, and at most
     * {@code ECHOED_VALUE_MAX_LENGTH} (60) characters of the input are rendered, followed by
     * {@code ...} when the input is longer. Escaped are the ISO control characters — CR and LF above
     * all, every other C0 control, {@code DEL} and the C1 controls such as {@code NEL} — the line and
     * paragraph separators {@code U+2028} and {@code U+2029}, every format character (the bidi
     * overrides and isolates, the zero-width characters, the byte order mark, the tag characters) and
     * every unpaired surrogate. Printable text passes unchanged whatever its script: a letter with a
     * diacritic, an em dash, CJK, an emoji written as a valid surrogate pair. A cap that falls between
     * the two halves of a surrogate pair leaves the high half unpaired in the rendering, so it is
     * escaped. Because each escape expands one input character to six, the rendering itself can be
     * longer than 60 characters. The operator still sees what they typed; the boot ERROR log cannot be
     * line-forged or visually disguised by it.
     *
     * @param value the configured value to echo
     * @return the escaped and capped rendering, safe to embed in a refusal message
     */
    public static String renderForMessage(String value) {
        int limit = Math.min(value.length(), ECHOED_VALUE_MAX_LENGTH);
        String rendered = escapeForgingCharacters(value, limit);
        return value.length() > limit ? rendered + "..." : rendered;
    }

    /**
     * Renders a whole value for a log line with the same escaping as {@link #renderForMessage} but
     * without its length cap: every control, line or paragraph separator, format character and
     * unpaired surrogate becomes a {@code \\uXXXX} escape, printable text — non-ASCII included — is
     * kept as written, and nothing is cut. For the boot sink that logs a {@link ConfigError}'s file,
     * pointer and message, where a cut would hide which location failed. A value that is already
     * escaped passes through unchanged, because an escape consists of printable characters only.
     *
     * @param value the value to log
     * @return the escaped rendering, safe to embed in a log line
     */
    public static String renderForLog(String value) {
        return escapeForgingCharacters(value, value.length());
    }

    /**
     * The CWE-117 escaping behind {@link #renderForMessage} and {@link #renderForLog} — the boot log's
     * one copy; the {@code --validate-config} report has its own (see the class documentation): the first
     * {@code limit} characters of {@code value},
     * each one that can forge or disguise a log line replaced by its {@code \\uXXXX} escape. A valid
     * surrogate pair inside the limit is judged as the one code point it encodes, and is escaped as its
     * two halves when that code point is a format character; a surrogate without its partner inside the
     * limit is escaped on its own.
     */
    private static String escapeForgingCharacters(String value, int limit) {
        StringBuilder rendered = new StringBuilder(limit);
        int index = 0;
        while (index < limit) {
            char current = value.charAt(index);
            boolean pairInsideLimit = Character.isHighSurrogate(current) && index + 1 < limit
                    && Character.isLowSurrogate(value.charAt(index + 1));
            int width = pairInsideLimit ? 2 : 1;
            int codePoint = pairInsideLimit ? Character.toCodePoint(current, value.charAt(index + 1)) : current;
            if (forgesOrDisguises(codePoint)) {
                for (int offset = 0; offset < width; offset++) {
                    rendered.append("\\u%04X".formatted((int) value.charAt(index + offset)));
                }
            } else {
                rendered.append(value, index, index + width);
            }
            index += width;
        }
        return rendered.toString();
    }

    /**
     * Whether a code point can forge a log line or disguise its text: an ISO control character, the
     * line or paragraph separator, a format character, or a surrogate code unit — which reaches this
     * check only when it is unpaired.
     */
    private static boolean forgesOrDisguises(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == LINE_SEPARATOR
                || codePoint == PARAGRAPH_SEPARATOR
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.SURROGATE;
    }

    /**
     * The shape review every {@code allowed_egress_hosts} list is held to, whichever block declares it:
     * each entry is handed to token-sheriff's egress guard, which exempts exactly the host it names from
     * the private-address refusal and matches it host-exact. Two shapes can therefore never match
     * anything, and an operator declaring one believes an exemption is in force that no dial ever meets:
     * <ul>
     *   <li>a <strong>blank or whitespace-only</strong> entry names no host at all;</li>
     *   <li>a <strong>{@code host:port}</strong> entry — a single-colon {@code name:digits} form or a
     *       bracketed {@code [v6]:digits} form — names a host the guard never compares against. A bare
     *       IPv6 literal such as {@code fd00::1} is a host, not a {@code host:port} pair, and is
     *       admitted.</li>
     * </ul>
     * The refusal reason is fixed text and echoes nothing of the entry.
     *
     * @param entry       the configured entry
     * @param blankRemedy what the operator does about a blank entry, completing the sentence
     *                    {@code "a blank entry names no host to exempt from the SSRF egress guard — "}
     * @return the refusal reason, or empty when the entry names a bare host
     */
    public static Optional<String> egressHostRefusal(String entry, String blankRemedy) {
        if (entry.isBlank()) {
            return Optional.of("a blank entry names no host to exempt from the SSRF egress guard — " + blankRemedy);
        }
        if (HOST_WITH_PORT.matcher(entry).matches()) {
            return Optional.of("the allowlist matches hosts only (host-exact), so an entry carrying a port "
                    + "never matches — declare the bare host");
        }
        return Optional.empty();
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
