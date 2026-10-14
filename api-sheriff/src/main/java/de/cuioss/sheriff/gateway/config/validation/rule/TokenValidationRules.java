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
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s of the {@code token_validation} block of
 * {@code gateway.yaml}: the JWKS egress allowlist and the JWKS refresh keys of an issuer.
 * <p>
 * The JWKS egress-allowlist refusal ({@link #validateJwksEgressHosts}): every
 * {@code allowed_egress_hosts} entry of an {@code http}-sourced issuer must be usable as the
 * host-exact exemption token-sheriff's SSRF egress guard matches on, so a blank or whitespace-only
 * entry and a {@code host:port} entry are refused (a bare IPv6 literal is not a {@code host:port}
 * entry and is admitted). The schema declares the entries unrestricted strings; this rule is the
 * single enforcing authority.
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

    /**
     * An {@code allowed_egress_hosts} entry carrying a port, matched against the WHOLE entry
     * ({@link java.util.regex.Matcher#matches()}): a single-colon {@code name:digits} form, or a
     * bracketed {@code [v6]:digits} form. The name part of the first alternative excludes every colon,
     * so a bare IPv6 literal such as {@code fd00::1} — whose second colon cannot be followed by digits
     * only — never matches. Every quantifier is possessive; the character classes are disjoint from
     * the delimiters that follow them, so no give-back could ever succeed.
     */
    private static final Pattern HOST_WITH_PORT = Pattern.compile("[^:\\[\\]]*+:\\d++|\\[[^\\]]*+\\]:\\d++");

    /**
     * Rule: every {@code allowed_egress_hosts} entry of an {@code http}-sourced issuer must name a
     * bare host.
     * <p>
     * Each entry is handed to token-sheriff's egress guard, which exempts exactly the host it names
     * from the private-address refusal and matches it host-exact — the port of the JWKS url plays no
     * part. Two shapes can therefore never match anything, and an operator declaring one believes an
     * exemption is in force that no fetch ever meets:
     * <ul>
     *   <li>a <strong>blank or whitespace-only</strong> entry names no host at all;</li>
     *   <li>a <strong>{@code host:port}</strong> entry — a single-colon {@code name:digits} form or a
     *       bracketed {@code [v6]:digits} form ({@code HOST_WITH_PORT}) — names a host the guard never
     *       compares against. A bare IPv6 literal such as {@code fd00::1} is a host, not a
     *       {@code host:port} pair, and is admitted.</li>
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
                jwksEgressHostRefusal(entry).ifPresent(reason -> errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "issuer '%s' jwks.allowed_egress_hosts entry '%s' is refused: %s"
                                .formatted(renderForMessage(issuer.name()), renderForMessage(entry), reason))));
            }
        }
    }

    /**
     * The shape review of one {@code allowed_egress_hosts} entry; see {@link #validateJwksEgressHosts}
     * for the rules and why each exists.
     *
     * @return the refusal reason, or empty when the entry names a bare host
     */
    private static Optional<String> jwksEgressHostRefusal(String entry) {
        if (entry.isBlank()) {
            return Optional.of("a blank entry names no host to exempt from the SSRF egress guard — remove it, "
                    + "or omit allowed_egress_hosts to derive the host of jwks.url");
        }
        if (HOST_WITH_PORT.matcher(entry).matches()) {
            return Optional.of("the allowlist matches hosts only (host-exact), so an entry carrying a port "
                    + "never matches — declare the bare host");
        }
        return Optional.empty();
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
     * list of {@code doc/configuration.adoc} is bound to by a contract test, so a key added here or a range
     * changed here fails that test until the documentation moves with it.
     * <p>
     * {@link #REFRESH_INTERVAL} and {@link #KEY_ROTATION_GRACE_PERIOD} are handed to the token library;
     * both ranges were checked against the value checks of token-sheriff 0.9.6's
     * {@code HttpJwksLoaderConfig} builder, which admits a refresh interval of zero or more and any
     * non-negative grace period and declares no upper bound, so every value admitted here is a value the
     * library accepts. {@link #UNKNOWN_KID_REFRESH_WINDOW} is gateway-owned; its lower bound is what bounds
     * the cost of a flood of tokens naming arbitrary key ids: at most one JWKS fetch per issuer per window.
     * <p>
     * The constants are immutable, so the type is safe for concurrent use.
     *
     * @since 1.0
     */
    public enum JwksRefreshKey {

        /** {@code refresh_interval_seconds}: the cadence of the scheduled background JWKS refresh. */
        REFRESH_INTERVAL("refresh_interval_seconds", 60, 86_400, IssuerConfig.Jwks::refreshIntervalSeconds),

        /** {@code key_rotation_grace_period_seconds}: how long a key rotated out of the JWKS stays accepted. */
        KEY_ROTATION_GRACE_PERIOD("key_rotation_grace_period_seconds", 0, 3_600,
                IssuerConfig.Jwks::keyRotationGracePeriodSeconds),

        /** {@code unknown_kid_refresh_window_seconds}: the minimum spacing of refreshes an unknown key id triggers. */
        UNKNOWN_KID_REFRESH_WINDOW("unknown_kid_refresh_window_seconds", 5, 3_600,
                IssuerConfig.Jwks::unknownKidRefreshWindowSeconds);

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
