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

import java.util.List;
import java.util.function.Function;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.token.commons.transport.EgressHostValidator;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s of the {@code token_validation} block of
 * {@code gateway.yaml}: the JWKS egress allowlist and the JWKS refresh keys of an issuer.
 * <p>
 * The JWKS egress-allowlist refusal ({@link #validateJwksEgressHosts}): every
 * {@code allowed_egress_hosts} entry of an {@code http}-sourced issuer must be usable as the
 * host-exact exemption token-sheriff's SSRF egress guard matches on, so a blank or whitespace-only
 * entry, a {@code host:port} entry and every other entry the guard's own shape check refuses are
 * refused (a bare IPv6 literal is not a {@code host:port} entry and is admitted). The schema declares
 * the entries unrestricted strings; this rule is where boot validation enforces them.
 * <p>
 * The JWKS refresh rule ({@link #validateJwksRefresh}) bounds the refresh keys of an issuer's
 * {@code jwks} block and refuses each of them on a {@code file} source, where it would parse and
 * never act. The keys and their settled ranges are defined once, by {@link JwksRefreshKey}, whose
 * Javadoc records why every range is one the token library accepts. The schema declares the keys as
 * unrestricted integers; this rule is the single enforcing authority.
 * <p>
 * Both rules append file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fail fast. Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class TokenValidationRules {

    /** The {@code jwks.source} whose {@code allowed_egress_hosts} feed token-sheriff's egress guard. */
    private static final String JWKS_SOURCE_HTTP = "http";

    /** What the operator does about a blank {@code jwks.allowed_egress_hosts} entry. */
    private static final String JWKS_BLANK_ENTRY_REMEDY =
            "remove it, or omit allowed_egress_hosts to derive the host of jwks.url";

    /**
     * The smallest {@code jwks.refresh_interval_seconds} admitted, in seconds — the lower bound of
     * {@link JwksRefreshKey#REFRESH_INTERVAL}. Public so the configuration model's Javadoc renders the
     * enforced bound instead of restating it.
     */
    public static final int REFRESH_INTERVAL_MIN_SECONDS = 60;

    /**
     * The largest {@code jwks.refresh_interval_seconds} admitted, in seconds — the upper bound of
     * {@link JwksRefreshKey#REFRESH_INTERVAL}.
     */
    public static final int REFRESH_INTERVAL_MAX_SECONDS = 86_400;

    /**
     * The smallest {@code jwks.key_rotation_grace_period_seconds} admitted, in seconds — the lower bound of
     * {@link JwksRefreshKey#KEY_ROTATION_GRACE_PERIOD}; zero retires a replaced key set at once.
     */
    public static final int KEY_ROTATION_GRACE_PERIOD_MIN_SECONDS = 0;

    /**
     * The largest {@code jwks.key_rotation_grace_period_seconds} admitted, in seconds — the upper bound of
     * {@link JwksRefreshKey#KEY_ROTATION_GRACE_PERIOD}.
     */
    public static final int KEY_ROTATION_GRACE_PERIOD_MAX_SECONDS = 3_600;

    /**
     * The smallest {@code jwks.unknown_kid_refresh_window_seconds} admitted, in seconds — the lower bound of
     * {@link JwksRefreshKey#UNKNOWN_KID_REFRESH_WINDOW}, and so the bound on how often a flood of unknown key
     * ids can make the gateway fetch.
     */
    public static final int UNKNOWN_KID_REFRESH_WINDOW_MIN_SECONDS = 5;

    /**
     * The largest {@code jwks.unknown_kid_refresh_window_seconds} admitted, in seconds — the upper bound of
     * {@link JwksRefreshKey#UNKNOWN_KID_REFRESH_WINDOW}.
     */
    public static final int UNKNOWN_KID_REFRESH_WINDOW_MAX_SECONDS = 3_600;

    /**
     * The smallest {@code jwks.max_staleness_seconds} admitted, in seconds — the lower bound of
     * {@link JwksRefreshKey#MAX_STALENESS}.
     */
    public static final int MAX_STALENESS_MIN_SECONDS = 60;

    /**
     * The largest {@code jwks.max_staleness_seconds} admitted, in seconds — the upper bound of
     * {@link JwksRefreshKey#MAX_STALENESS}.
     */
    public static final int MAX_STALENESS_MAX_SECONDS = 604_800;

    /**
     * Rule: every {@code allowed_egress_hosts} entry of an {@code http}-sourced issuer must name a
     * bare host.
     * <p>
     * Each entry is handed to token-sheriff's egress guard, which exempts exactly the host it names
     * from the private-address refusal and matches it host-exact — the port of the JWKS url plays no
     * part. An entry that is no bare host can therefore never match anything, and an operator
     * declaring one believes an exemption is in force that no fetch ever meets. Each entry is held to
     * the guard's own shape check ({@link EgressHostValidator#refusal}), worded by
     * {@link RuleSupport#egressEntryRefusalReason}:
     * <ul>
     *   <li>a <strong>blank or whitespace-only</strong> entry names no host at all;</li>
     *   <li>a <strong>{@code host:port}</strong> entry — a single colon, or a colon after a bracketed
     *       IPv6 literal — names a host the guard never compares against, and so does an entry carrying
     *       a control, whitespace or non-ASCII character, URL syntax, a malformed IPv6 literal or
     *       anything else that is no host name or IPv4 literal. A bare IPv6 literal such as
     *       {@code fd00::1} is a host, not a {@code host:port} pair, and is admitted.</li>
     * </ul>
     * The host part is never compared with the {@code jwks.url} host: a non-empty list is
     * authoritative, and whether it names the key endpoint's host is the operator's decision. Because
     * an explicit list replaces the allowance otherwise derived from {@code jwks.url}, an unusable
     * entry is refused at boot rather than left to fail every JWKS fetch at runtime.
     * <p>
     * A {@code file} issuer (its list feeds no egress guard), an issuer without a {@code jwks} block
     * and an absent {@code token_validation} block are skipped. The offending entry and the issuer
     * name are echoed through {@link RuleSupport#renderForMessage}, so a hostile value cannot forge boot log
     * lines (CWE-117). Every offending entry is collected rather than failing on the first, per
     * ADR-0009.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validateJwksEgressHosts(GatewayConfig gateway, List<ConfigError> errors) {
        TokenValidationConfig tokenValidation = gateway.tokenValidation();
        if (tokenValidation == null) {
            return;
        }
        List<IssuerConfig> issuers = tokenValidation.issuers();
        for (int index = 0; index < issuers.size(); index++) {
            IssuerConfig issuer = issuers.get(index);
            IssuerConfig.Jwks jwks = issuer.jwks();
            if (jwks == null || !JWKS_SOURCE_HTTP.equals(jwks.source())) {
                continue;
            }
            String pointer = "/token_validation/issuers/%d/jwks/allowed_egress_hosts".formatted(index);
            for (String entry : jwks.allowedEgressHosts()) {
                EgressHostValidator.refusal(entry).ifPresent(refusal -> errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "issuer '%s' jwks.allowed_egress_hosts entry '%s' is refused: %s".formatted(
                                renderForMessage(issuer.name()), renderForMessage(entry),
                                RuleSupport.egressEntryRefusalReason(entry, refusal, JWKS_BLANK_ENTRY_REMEDY)))));
            }
        }
    }

    /**
     * Rule: the JWKS refresh keys of an issuer are bounded, and none of them may be declared on a
     * {@code file} source.
     * <p>
     * The keys and the range each admits are defined once, by {@link JwksRefreshKey}, whose Javadoc
     * records why each range is safe to hand to the token library; this rule iterates that definition
     * rather than restating it.
     * <p>
     * A {@code file} source is read once and never refreshed, so any of the keys declared there
     * would parse and never act; it is refused at the key's own pointer rather than silently ignored.
     * On a {@code file} source the value itself is not range-checked — the key is refused whatever it
     * holds.
     * <p>
     * An issuer without a {@code jwks} block and an absent {@code token_validation} block are skipped,
     * and an omitted key is always admitted. Each message names the issuer's logical name — echoed
     * through {@link RuleSupport#renderForMessage}, so it cannot forge boot log lines (CWE-117) — the key and
     * the admitted range, and never the JWKS url. Every violation of every issuer is collected in one
     * pass, per ADR-0009.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validateJwksRefresh(GatewayConfig gateway, List<ConfigError> errors) {
        TokenValidationConfig tokenValidation = gateway.tokenValidation();
        if (tokenValidation == null) {
            return;
        }
        List<IssuerConfig> issuers = tokenValidation.issuers();
        for (int index = 0; index < issuers.size(); index++) {
            IssuerConfig issuer = issuers.get(index);
            IssuerConfig.Jwks jwks = issuer.jwks();
            if (jwks == null) {
                continue;
            }
            boolean http = JWKS_SOURCE_HTTP.equals(jwks.source());
            for (JwksRefreshKey refreshKey : JwksRefreshKey.values()) {
                checkJwksRefreshKey(issuer, index, http, refreshKey, refreshKey.declaredIn(jwks), errors);
            }
        }
    }

    /**
     * Reviews one JWKS refresh key of one issuer; see {@link #validateJwksRefresh} for the rules.
     *
     * @param http       whether the issuer's {@code jwks.source} is {@code http}
     * @param refreshKey the key under review, carrying its {@code gateway.yaml} spelling and admitted range
     * @param declared   the declared value, {@code null} when the key is omitted
     */
    private static void checkJwksRefreshKey(IssuerConfig issuer, int index, boolean http, JwksRefreshKey refreshKey,
            @Nullable Integer declared, List<ConfigError> errors) {
        if (declared == null) {
            return;
        }
        String key = refreshKey.key();
        int minimum = refreshKey.minimum();
        int maximum = refreshKey.maximum();
        String pointer = "/token_validation/issuers/%d/jwks/%s".formatted(index, key);
        String issuerName = renderForMessage(issuer.name());
        if (!http) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    ("issuer '%s' declares jwks.%s on a jwks source other than http; the key applies to "
                            + "source: http only (admitted range %d to %d) and would never act here — remove it")
                            .formatted(issuerName, key, minimum, maximum)));
            return;
        }
        if (declared < minimum || declared > maximum) {
            errors.add(new ConfigError(GATEWAY_FILE, pointer,
                    "issuer '%s' jwks.%s %d is outside the admitted range %d to %d"
                            .formatted(issuerName, key, declared, minimum, maximum)));
        }
    }

    /**
     * The JWKS refresh keys of an issuer's {@code jwks} block, each with the inclusive range of seconds
     * it admits — the single definition both {@link #validateJwksRefresh} enforces and the boot-validation
     * list of {@code doc/configuration/validation-rules.adoc} is bound to by a contract test, so a key
     * added here or a range changed here fails that test until the documentation moves with it.
     * <p>
     * {@link #REFRESH_INTERVAL}, {@link #KEY_ROTATION_GRACE_PERIOD} and {@link #MAX_STALENESS} are handed
     * to the token library; their ranges were checked against the value checks of token-sheriff 0.9.9's
     * {@code HttpJwksLoaderConfig} builder, which admits a refresh interval of zero or more, any
     * non-negative grace period and any positive maximum staleness, and declares no upper bound for any
     * of them, so every value admitted here is a value the library accepts. The builder's one combination
     * refusal — a maximum staleness together with a refresh interval of zero — is unreachable here,
     * because {@link #REFRESH_INTERVAL} admits no zero and an omitted interval keeps the library's
     * default of 600 seconds. No cross-key rule binds {@link #MAX_STALENESS} to the refresh interval:
     * staleness is evaluated once per refresh cycle, so a bound below the effective interval behaves like
     * one equal to it and only fails closed sooner. {@link #UNKNOWN_KID_REFRESH_WINDOW} is gateway-owned;
     * its lower bound is what bounds the cost of a flood of tokens naming arbitrary key ids: at most one
     * JWKS fetch per issuer per window.
     * <p>
     * The constants are immutable, so the type is safe for concurrent use.
     *
     * @since 1.0
     */
    public enum JwksRefreshKey {

        /** {@code refresh_interval_seconds}: the cadence of the scheduled background JWKS refresh. */
        REFRESH_INTERVAL("refresh_interval_seconds", REFRESH_INTERVAL_MIN_SECONDS, REFRESH_INTERVAL_MAX_SECONDS,
            IssuerConfig.Jwks::refreshIntervalSeconds),

        /** {@code key_rotation_grace_period_seconds}: how long a key rotated out of the JWKS stays accepted. */
        KEY_ROTATION_GRACE_PERIOD("key_rotation_grace_period_seconds", KEY_ROTATION_GRACE_PERIOD_MIN_SECONDS,
                KEY_ROTATION_GRACE_PERIOD_MAX_SECONDS, IssuerConfig.Jwks::keyRotationGracePeriodSeconds),

        /** {@code unknown_kid_refresh_window_seconds}: the minimum spacing of refreshes an unknown key id triggers. */
        UNKNOWN_KID_REFRESH_WINDOW("unknown_kid_refresh_window_seconds", UNKNOWN_KID_REFRESH_WINDOW_MIN_SECONDS,
                UNKNOWN_KID_REFRESH_WINDOW_MAX_SECONDS, IssuerConfig.Jwks::unknownKidRefreshWindowSeconds),

        /**
         * {@code max_staleness_seconds}: how long a loaded key set stays usable while every background
         * refresh fails; omitted switches the bound off.
         */
        MAX_STALENESS("max_staleness_seconds", MAX_STALENESS_MIN_SECONDS, MAX_STALENESS_MAX_SECONDS,
                IssuerConfig.Jwks::maxStalenessSeconds);

        private final String key;
        private final int minimum;
        private final int maximum;
        private final Function<IssuerConfig.Jwks, @Nullable Integer> reader;

        JwksRefreshKey(String key, int minimum, int maximum, Function<IssuerConfig.Jwks, @Nullable Integer> reader) {
            this.key = key;
            this.minimum = minimum;
            this.maximum = maximum;
            this.reader = reader;
        }

        /**
         * The key as spelled under an issuer's {@code jwks} block in {@code gateway.yaml}.
         *
         * @return the snake-case key name
         */
        public String key() {
            return key;
        }

        /**
         * The smallest value the key admits, in seconds.
         *
         * @return the inclusive lower bound
         */
        public int minimum() {
            return minimum;
        }

        /**
         * The largest value the key admits, in seconds.
         *
         * @return the inclusive upper bound
         */
        public int maximum() {
            return maximum;
        }

        /** The value {@code jwks} declares for this key, {@code null} when the key is omitted. */
        private @Nullable Integer declaredIn(IssuerConfig.Jwks jwks) {
            return reader.apply(jwks);
        }
    }
}
