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
package de.cuioss.sheriff.gateway.http;

import java.util.Locale;
import java.util.Optional;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import lombok.experimental.UtilityClass;

/**
 * The open-redirect review of a gateway path the gateway is about to write into a {@code Location}
 * response header, or to hand a browser as a link target — declared once and read by every path that
 * emits one (GW-13, AS-3, AS-11).
 * <p>
 * Every reader judges the same question about the same kind of value — "does this gateway path keep
 * the browser on the gateway origin, under the path it names?" — so the answer lives here rather than
 * being mirrored into each, the defect class {@link ConnectionHeaders} was extracted for, applied to a
 * second shared rule. The readers are:
 * <ul>
 *   <li>{@code config.validation.rule.TerminalActionRules} — the boot review of a <em>configured</em>
 *       {@code redirect.location} (run by {@code ConfigValidator}); a refusal is a {@code ConfigError}.</li>
 *   <li>{@code routing.LocationRewriter} — the per-request mapping of an <em>upstream</em>
 *       {@code Location} onto the gateway; a refused path or mapping is relayed unchanged rather than
 *       mapped.</li>
 *   <li>{@code bff.pending.PendingAuthorizationRecord.sameOrigin} — the BFF return URL a login, a
 *       session widening or a step-up redirects the browser to afterwards, request-supplied at runtime
 *       and {@code oidc.login.default_return_url} at boot; a refused target falls back to the
 *       configured default at runtime and is a {@code ConfigError} at boot.</li>
 *   <li>{@code config.validation.rule.PortalRules} — the boot review of {@code portal.path} and of every
 *       {@code catalog.entry}, the link targets the application portal hands the browser; the same
 *       canonical-path review also holds the reserved OIDC paths ({@code ReservedPathRules}). A refusal
 *       is a {@code ConfigError}.</li>
 * </ul>
 * Each reader hands the review a path without its query and fragment, though not all of them obtain
 * it the same way. {@code TerminalActionRules}, {@code PortalRules} for a catalog entry and the
 * gateway-relative branch of {@code sameOrigin} split a reference with {@link #pathPortion};
 * {@code LocationRewriter} and the absolute-URL branch of {@code sameOrigin} pass the raw path of the
 * parsed {@link java.net.URI}; {@code PortalRules} reviews {@code portal.path}, a bare path, whole.
 * Either way the query and fragment of a reference never reach the review: a separator after the
 * {@code ?} cannot form an authority, and refusing it would refuse an ordinary encoded query value.
 *
 * <h2>Detector, never canonicalizer</h2>
 *
 * The review runs the {@code cui-http} {@code URL_PATH} validation pipeline, but consumes only
 * <strong>whether it threw</strong> and discards the canonical value it returns. That is not an
 * oversight — it is the whole contract. The pipeline exists for <em>inbound</em> validation, where
 * the canonical form is what the application then uses. Every reader here judges a value that is
 * <strong>emitted</strong>: the browser receives the original string, so confinement proved against
 * a normalized string the browser never sees bounds nothing. Emitting the pipeline's output in place
 * of the reviewed value would silently change the target of a redirect an operator wrote, or of a
 * redirect an upstream issued.
 *
 * <h2>What the pipeline covers, and what it provably does not</h2>
 *
 * The pipeline is a <em>canonicalizer</em>: for the traversal family it throws only when the result
 * would escape above the root, and otherwise <em>fixes the value and returns it</em>. Measured
 * against {@code cui-http} 3.0 with every preset ({@code lenient}, {@code defaults}, {@code strict},
 * {@code paranoid}) and with {@code blockedPathPatterns} populated:
 * <ul>
 *   <li><strong>It refuses</strong> — and these are the classes the hand-written tests below it never
 *       caught: {@code DOUBLE_ENCODING} ({@code /%252F%252Fevil.example}), traversal reached through
 *       double encoding ({@code /a/%252f..%252fadmin}), {@code INVALID_CHARACTER} (a literal
 *       {@code \}, a raw space, any control character), {@code NULL_BYTE_INJECTION},
 *       {@code UNICODE_NORMALIZATION_CHANGED}, overlong UTF-8 sequences, {@code PATH_TOO_LONG}, and
 *       a {@code ..} run that escapes above the root ({@code /a/../../b}).</li>
 *   <li><strong>It admits</strong>, by design, every spelling below — each one normalized away rather
 *       than refused, which is why {@link #refusalReason} keeps its own tests for them:
 *       <ul>
 *         <li>a percent-encoded separator with no traversal in it — {@code /a/x%2fy} becomes
 *             {@code /a/x/y}, {@code /%5Cattacker.com} becomes {@code /\attacker.com},
 *             {@code /%2f%2fattacker.com} becomes {@code /attacker.com};</li>
 *         <li>a single-dot segment — {@code /a/./b} becomes {@code /a/b}, {@code /%2E/b} becomes
 *             {@code /b};</li>
 *         <li>a {@code ..} run that stays inside the root — {@code /svc/v1/..} becomes {@code /svc};</li>
 *         <li>a leading {@code //} — {@code //evil.example} becomes {@code /evil.example}.</li>
 *       </ul>
 *       Each of those is a value this gateway must <em>refuse</em> rather than fix, because it is
 *       emitted verbatim: {@code /%5Cattacker.com} reaches the browser unchanged, and the WHATWG URL
 *       Standard parses {@code \} as {@code /} in the special schemes, so it names a foreign
 *       authority (CWE-601). Delegating those to the pipeline would admit them.</li>
 * </ul>
 *
 * <h2>Policy</h2>
 *
 * The policy is a fixed {@link SecurityConfiguration#strict()}, deliberately <em>not</em> the route's
 * resolved {@code security_filter} configuration. That block governs what the gateway accepts
 * <em>inbound</em>; this review governs what the gateway emits, so binding them would let an operator
 * relax an outbound origin guard through an inbound knob and would let two routes disagree about
 * which {@code Location} values are dangerous. {@code paranoid} is rejected for the opposite reason:
 * its {@code blockedPathPatterns} are filesystem-shaped ({@code /etc/}, {@code /dev/}, {@code .env}),
 * which would refuse legitimate gateway route paths spelled the same way.
 * <p>
 * {@code strict} caps a reviewed path at 1024 characters. A longer path is refused, which for a
 * configured redirect is a boot error, for an upstream {@code Location} means the header is relayed
 * unchanged rather than mapped, and for a request-supplied BFF return URL means the browser lands on
 * the configured default instead.
 * <p>
 * The {@link SecurityEventCounter} is deliberately a dedicated instance rather than the boot-shared
 * one {@code SheriffMetrics} exports. That counter aggregates <em>inbound</em> request-filter events;
 * folding outbound {@code Location} reviews into the same failure-type counters would make the
 * inbound security metric report events that never came from a request. Every reader surfaces a
 * refusal on its own terms instead — a {@code ConfigError} at boot, an unmapped header or a fallback
 * to the default return URL at runtime.
 * <p>
 * Stateless and thread-safe: the pipeline is built once and {@code cui-http} documents its validators
 * as safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class LocationPathReview {

    /** The percent-encoded spelling of {@code /}; matched case-insensitively, so {@code %2F} counts too. */
    private static final String ENCODED_SLASH = "%2f";

    /** The percent-encoded spelling of {@code \}; matched case-insensitively, so {@code %5C} counts too. */
    private static final String ENCODED_BACKSLASH = "%5c";

    /** The percent-encoded spelling of {@code .}; matched case-insensitively, so {@code %2E} counts too. */
    private static final String ENCODED_DOT = "%2e";

    private static final String SCHEME_RELATIVE = "//";

    /**
     * The {@code cui-http} {@code URL_PATH} pipeline, used as a detector only — see the class
     * javadoc for why its canonical output is discarded and why the policy is fixed.
     */
    private static final HttpSecurityValidator PATH_PIPELINE = PipelineFactory.createUrlPathPipeline(
            SecurityConfiguration.strict(), new SecurityEventCounter());

    /**
     * Reviews a gateway-relative path that is about to be emitted in a {@code Location} header.
     * <p>
     * The four tests, in the order their messages are reported:
     * <ol>
     *   <li><strong>Exactly one leading {@code /}.</strong> A path-absolute reference starting with
     *       {@code //} is scheme-relative and names another origin.</li>
     *   <li><strong>No ambiguous separator.</strong> A percent-encoded {@code /} or {@code \}
     *       ({@code %2f} / {@code %5c}, either case). The encoded backslash can move the
     *       <em>origin</em> once something decodes it (CWE-601); the encoded slash hides a traversal
     *       from the dot-segment test below, which splits on the literal {@code /} only, so
     *       {@code /a%2f..%2f..%2fadmin} is a single segment carrying no dot segment at all. The
     *       pipeline decodes both spellings and admits the result, so this test cannot be delegated
     *       to it.</li>
     *   <li><strong>No dot segment.</strong> A {@code .} or {@code ..} segment in either its literal
     *       or its {@code %2e} spelling. A dot segment is refused rather than resolved for the same
     *       reason the value is not canonicalized at all: the client resolves the string it receives,
     *       so a mapping carrying {@code ..} lands wherever the client's resolution puts it. The
     *       pipeline resolves a {@code .} segment away and admits a {@code ..} run that stays inside
     *       the root, so this test cannot be delegated to it either.</li>
     *   <li><strong>The {@code cui-http} pipeline.</strong> Everything the library does own —
     *       double encoding, escaping traversal, illegal and control characters, null bytes, unicode
     *       normalization changes, and the length cap.</li>
     * </ol>
     * The returned reason names the pipeline's {@code UrlSecurityFailureType} constant and never the
     * offending input, so a caller that writes the reason into a log cannot be made to forge lines
     * with it (CWE-117).
     *
     * @param path the gateway-relative path portion, without query or fragment; never {@code null}
     * @return the refusal reason, or {@link Optional#empty()} when the path is admitted
     */
    public static Optional<String> refusalReason(String path) {
        if (path.startsWith(SCHEME_RELATIVE)) {
            return Optional.of("a leading '//' is scheme-relative and names another origin");
        }
        if (carriesEncodedSeparator(path)) {
            return Optional.of("a percent-encoded '/' or '\\' is decoded by intermediaries");
        }
        if (carriesDotSegment(path)) {
            return Optional.of("a dot-segment is normalized away by clients");
        }
        try {
            PATH_PIPELINE.validate(path);
        } catch (UrlSecurityException violation) {
            return Optional.of("the cui-http URL path review refuses it as " + violation.getFailureType());
        }
        return Optional.empty();
    }

    /**
     * Whether {@code value} carries a percent-encoded {@code /} or {@code \} ({@code %2f} /
     * {@code %5c}, matched case-insensitively so {@code %2F} and {@code %5C} count too).
     * <p>
     * Exposed separately from {@link #refusalReason} because the readers scope it differently, on
     * purpose. {@code LocationRewriter} and {@code PendingAuthorizationRecord.sameOrigin} reach it only
     * through {@link #refusalReason}, on the path alone: an encoded separator after the {@code ?} cannot
     * form an authority — a relative reference's authority is decided before the query delimiter — and
     * refusing it would stop mapping the ordinary encoded-path query parameter an upstream redirect
     * carries, or refuse the encoded query an ordinary return URL carries. {@code TerminalActionRules}
     * (a configured {@code redirect.location}) and {@code PortalRules} (a configured
     * {@code catalog.entry}) apply it to the whole configured value as well: nothing an operator can
     * express in a query needs an encoded separator, so the wider scope costs a fixed configured string
     * nothing.
     *
     * @param value any URL component, or a whole URL reference
     * @return {@code true} when {@code value} carries an encoded separator in either spelling
     */
    public static boolean carriesEncodedSeparator(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.contains(ENCODED_SLASH) || lowered.contains(ENCODED_BACKSLASH);
    }

    /**
     * The path portion of a gateway-relative reference: everything before the first {@code ?} or
     * {@code #}, or the whole value when it carries neither.
     * <p>
     * This is the split the readers of {@link #refusalReason} use when they hold an unparsed
     * <em>reference</em> rather than a bare path or a parsed {@link java.net.URI} (the class javadoc
     * names which readers those are). The split is purely lexical — the first query or fragment
     * delimiter ends the path, exactly where a client ends it — and decodes nothing.
     *
     * @param reference a gateway-relative reference, possibly carrying a query and a fragment; never
     *                  {@code null}
     * @return the path portion of {@code reference}, never {@code null}
     */
    public static String pathPortion(String reference) {
        for (int index = 0; index < reference.length(); index++) {
            char current = reference.charAt(index);
            if (current == '?' || current == '#') {
                return reference.substring(0, index);
            }
        }
        return reference;
    }

    /**
     * Whether any segment of {@code path} is a {@code .} or {@code ..} segment. The test runs on the
     * raw (still percent-encoded) path and treats {@code %2e} / {@code %2E} as the dot it encodes, so
     * an encoded traversal is caught on the same terms as a literal one.
     */
    private static boolean carriesDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (isDotSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when {@code segment} consists of exactly one or two dots, each written
     *         literally or as {@code %2e} in either case
     */
    private static boolean isDotSegment(String segment) {
        int index = 0;
        int dots = 0;
        while (index < segment.length()) {
            if (segment.charAt(index) == '.') {
                index++;
            } else if (segment.regionMatches(true, index, ENCODED_DOT, 0, ENCODED_DOT.length())) {
                index += ENCODED_DOT.length();
            } else {
                return false;
            }
            dots++;
        }
        return dots == 1 || dots == 2;
    }
}
