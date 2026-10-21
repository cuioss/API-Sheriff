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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.tools.logging.CuiLogger;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code oidc.session} block: the session mode
 * and its companions, the bounds on the session store, the idle timeout, the sealed-cookie size budget
 * and the session cookie's name.
 * <p>
 * The mode rule ({@link #validateSessionMode}) requires a {@code store} in {@code server} mode. The
 * store bounds ({@link #validateSessionMaxSessions}, {@link #validateSessionMaxSessionsPerSubject})
 * hold {@code max_sessions} to a positive integer and {@code max_sessions_per_subject} to the range
 * {@code 1..max_sessions}, refusing the latter in cookie mode, which stores no sessions. The idle-timeout
 * rule ({@link #validateSessionIdleTimeout}) holds {@code idle_timeout_seconds} to the range
 * {@code 1..ttl_seconds}.
 * <p>
 * The cookie-size rule ({@link #validateSessionMaxCookieSize}) range-checks {@code max_cookie_size} and,
 * in cookie mode, emits {@link ConfigLogMessages.WARN#COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE} when the
 * {@code Set-Cookie} header the effective budget produces outgrows the browser guarantee — the only
 * warning, never a refusal, this class issues. The cookie-name rule ({@link #validateSessionCookieName})
 * holds a declared {@code cookie_name} to the {@code __Host-} guarantee, to an RFC 9110 token, and to a
 * name distinct from the gateway-owned cookies.
 * <p>
 * Each bound is compared against the value the runtime producer resolves for the key it depends on, so
 * validation reasons about the session the gateway will actually run. Every rule appends file- and
 * pointer-annotated {@link ConfigError}s to the shared list and never fails fast (ADR-0009). Stateless,
 * and free of framework dependencies as the pre-boot rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class OidcSessionRules {

    private static final CuiLogger LOGGER = new CuiLogger(OidcSessionRules.class);

    private static final String OIDC_SESSION_MAX_SESSIONS_POINTER = "/oidc/session/max_sessions";
    private static final String OIDC_SESSION_MAX_COOKIE_SIZE_POINTER = "/oidc/session/max_cookie_size";
    private static final String OIDC_SESSION_IDLE_TIMEOUT_POINTER = "/oidc/session/idle_timeout_seconds";
    private static final String OIDC_SESSION_MAX_SESSIONS_PER_SUBJECT_POINTER =
            "/oidc/session/max_sessions_per_subject";
    private static final String OIDC_SESSION_COOKIE_NAME_POINTER = "/oidc/session/cookie_name";

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
     * Rule: the session mode's mandatory companions (D1/D2b).
     * <p>
     * {@code server} mode requires a {@code store}. {@code cookie} mode does <em>not</em> require an
     * {@code encryption_key} — omitting it selects the generate-on-startup key mode, a first-class
     * production option (at the cost of dropping every session on restart and being unshareable
     * across replicas).
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionMode(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionMaxSessions(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionMaxSessionsPerSubject(GatewayConfig gateway, List<ConfigError> errors) {
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
     * The upper bound is the lifetime the runtime producer resolves ({@code resolvedSessionTtl}), so
     * an omitted {@code ttl_seconds} is compared as its default. A no-op when the key is omitted:
     * {@link OidcConfig.Session#effectiveIdleTimeoutSeconds()} then resolves a value inside the range
     * by construction, for any {@code ttl_seconds}. Every violation collects into the shared list; the
     * rule never fails fast (ADR-0009).
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionIdleTimeout(GatewayConfig gateway, List<ConfigError> errors) {
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionMaxCookieSize(GatewayConfig gateway, List<ConfigError> errors) {
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
     * <li>is a non-empty RFC 9110 {@code token} ({@code COOKIE_NAME_SHAPE}) — the runtime writes the
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
     *
     * @param gateway the bound gateway document, whose {@code oidc.session} block is judged
     * @param errors  the shared violation list every refusal is appended to
     */
    public static void validateSessionCookieName(GatewayConfig gateway, List<ConfigError> errors) {
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
}
