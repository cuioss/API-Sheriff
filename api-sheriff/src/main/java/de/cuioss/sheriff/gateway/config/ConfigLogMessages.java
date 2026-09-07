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
package de.cuioss.sheriff.gateway.config;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the configuration subsystem.
 * <p>
 * Structured {@code INFO} / {@code ERROR} messages carry the {@code ApiSheriff}
 * prefix and a stable numeric identifier, continuing the shared identifier space
 * used by the proxy edge, so they are greppable and assertable. Identifiers are
 * allocated across every catalogue sharing that prefix, not per class, and that
 * allocation is enforced by {@code LogMessagesCatalogueTest} rather than by an
 * inventory kept here by hand. {@code DEBUG} / {@code TRACE} diagnostics use the
 * logger directly and are not catalogued here.
 * <p>
 * The catalogue lives in the framework-agnostic {@code ...config} package because
 * the cui-tools {@link LogRecord} abstraction carries no framework dependency
 * (ADR-0005).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class ConfigLogMessages {

    private static final String PREFIX = "ApiSheriff";

    /**
     * Info-level messages (INFO range 1-99).
     */
    @UtilityClass
    public static final class INFO {

        /** The configuration was loaded, validated, and assembled successfully. */
        public static final LogRecord CONFIG_LOADED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(2)
                .template("Configuration loaded successfully (config_version='%s')")
                .build();

        /**
         * The materialized effective posture of a single route, printed once per
         * route during route-table assembly (the ADR-0007 discoverability answer:
         * anchors vanish at runtime, so the boot log reports the resolved posture).
         */
        public static final LogRecord ROUTE_POSTURE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(3)
                .template("Route '%s' effective posture: anchor='%s', auth.require='%s', filter='%s'")
                .build();
    }

    /**
     * Warn-level messages (WARN range 100-199).
     */
    @UtilityClass
    public static final class WARN {

        /**
         * A route replaces an anchor-provided non-auth policy block (wholesale
         * replacement). Logged so a weakening override is always an explicit, audited
         * choice (ADR-0007).
         */
        public static final LogRecord ANCHOR_POLICY_OVERRIDDEN = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(101)
                .template("Route '%s' overrides anchor '%s' %s policy (wholesale replacement)")
                .build();

        /**
         * A {@code trusted_proxies} CIDR entry covers a very broad — but not total —
         * address range (shorter than {@code /16} for IPv4 or {@code /48} for IPv6).
         * Broad proxy trust widens the set of hosts able to spoof forwarded headers,
         * so an overly broad prefix is surfaced as a boot WARN for review (D5).
         * <p>
         * The thresholds mark the point where one entry spans more than a single
         * operator-provisioned network; see {@code ConfigValidator.validateForwardedTrust}
         * for the reasoning and for what is deliberately left un-warned.
         */
        public static final LogRecord BROAD_TRUSTED_PROXY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(102)
                .template("trusted_proxies entry '%s' covers a very broad address range (prefix /%s) — review whether such broad proxy trust is intended")
                .build();

        /**
         * The management interface resolved to plain HTTP at startup.
         * <p>
         * This reports the <em>observed effective state</em>, not a declared intention: the audit
         * inspects what the management listener actually resolved to, so the warning cannot drift
         * away from reality when the activation route changes. The management interface has exactly
         * one port, so the downgrade takes health and metrics in their entirety — there is no
         * simultaneous HTTPS listener, and every consumer probing that port over HTTPS breaks.
         * <p>
         * It is a {@code WARN} and never a boot refusal: a plain-HTTP management port behind a
         * trusted network boundary is a legitimate deployment, and blocking it would be wrong. The
         * template names the port only; it must never carry certificate paths or bucket contents.
         * <p>
         * The remedy names <em>both</em> doors onto the downgrade, because the audit observes the
         * effective state and cannot tell which one was used: the neutral {@code gateway.yaml} key and
         * the deployment environment variable land on the same Quarkus key (ADR-0025).
         */
        public static final LogRecord MANAGEMENT_PLAIN_HTTP = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(115)
                .template("Management interface is serving PLAIN HTTP on port %s — health and metrics are unencrypted and every HTTPS consumer of that port will fail. Expose it only behind a trusted boundary; restore the HTTPS default by setting management.tls.enabled back to true in gateway.yaml, or by removing a QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME override supplied by the deployment")
                .build();

        /**
         * The readiness probe could not resolve the gateway's bearer-token validator, so it reports
         * {@code jwks: unavailable} and marks readiness DOWN.
         * <p>
         * This log line is the <em>only</em> place the cause is disclosed. The readiness payload is
         * served on the management interface, which has exactly one port and may legitimately be plain
         * HTTP (ADR-0025), so the wire response carries a fixed status token and nothing else — the
         * underlying failure can name issuer URLs, internal hostnames, TLS/trust material and
         * filesystem paths. The operator keeps the full diagnostic here, where it belongs; the probe
         * response discloses no part of it.
         * <p>
         * The template takes no parameters by design: the cause travels as the logged exception, so
         * there is no formatted fragment that could drift into carrying the detail itself.
         */
        public static final LogRecord READINESS_VALIDATION_UNAVAILABLE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(116)
                .template("Readiness probe reports validation unavailable — the gateway bearer-token validator could not be resolved. The readiness payload discloses only a fixed status token; the cause is logged here")
                .build();

        /**
         * {@code egress_tls.upstream_verify_hostname} resolved to {@code false} at boot, so the
         * governed egress clients no longer compare the dialled name against the upstream
         * certificate's names.
         * <p>
         * It is a {@code WARN} and never a boot refusal, for the same reason as
         * {@link #BROAD_TRUSTED_PROXY} and {@link #MANAGEMENT_PLAIN_HTTP}: an upstream reached through
         * an address its certificate does not name is a legitimate deployment (ADR-0040), and blocking
         * it would be wrong. What must not happen is the relaxation reaching production silently.
         * <p>
         * The template states the scope rather than leaving it to be inferred, because
         * <em>"verification off"</em> is the phrase operators reach for and is broader than what the
         * key does: chain trust is untouched, the relaxation is gateway-wide rather than per route,
         * and the asset-origin leg is not governed by the key at all. It names no certificate path,
         * no anchor and no upstream address.
         */
        public static final LogRecord EGRESS_HOSTNAME_VERIFICATION_DISABLED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(118)
                .template("egress_tls.upstream_verify_hostname is false — terminated upstream dials no longer verify that the certificate names the dialled host. Certificate-chain trust is unaffected and an untrusted upstream is still refused. The relaxation applies to the proxy, gRPC and WebSocket egress clients rather than to one route, so every proxied upstream is relaxed, not just the one that motivated it; the asset-origin fetch is not governed by this key and keeps full hostname verification. Restore verification by removing the key or setting it back to true in gateway.yaml")
                .build();

        /**
         * A named {@code egress_tls.upstream_tls_profile} is in effect at boot, so the governed egress
         * clients verify upstream certificates against the deployment-bound anchors instead of the JVM
         * default trust store.
         * <p>
         * A named profile <em>replaces</em> the client's anchors rather than adding to them (ADR-0040),
         * which is the property that surprises people: an operator naming a profile that holds only
         * their private CA thereby stops trusting public certificate authorities on the whole
         * terminated-egress surface. That is a deliberate, legitimate posture, so it is reported and
         * never refused.
         * <p>
         * The template carries the <em>logical</em> profile name only — the ADR-0011 neutral name that
         * appears in {@code gateway.yaml}. It must never carry the deployment's store path, password,
         * or any anchor material.
         */
        public static final LogRecord EGRESS_TRUST_PROFILE_IN_EFFECT = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(119)
                .template("egress_tls.upstream_tls_profile '%s' is in effect — its anchors REPLACE the JVM default trust store on the proxy, gRPC and WebSocket egress clients, so a public certificate authority is no longer trusted on those legs unless the profile carries it too. The asset-origin fetch is not governed by this profile and keeps the JVM default trust store")
                .build();

        /**
         * {@code egress_tls.jwks_verify_hostname} resolved to {@code false} at boot, so the JWKS
         * back-channel no longer compares the dialled name against the IdP certificate's names.
         * <p>
         * It is a {@code WARN} and never a boot refusal, for the same reason as
         * {@link #EGRESS_HOSTNAME_VERIFICATION_DISABLED}: a JWKS endpoint reached through an address
         * its certificate does not name is a legitimate deployment (ADR-0041), and blocking it would
         * be wrong. What must not happen is the relaxation reaching production silently.
         * <p>
         * The template states the scope rather than leaving it to be inferred, because
         * <em>"verification off"</em> is the phrase operators reach for and is broader than what the
         * key does: chain trust is untouched, and the key is separate from
         * {@code upstream_verify_hostname} — relaxing the JWKS leg does not relax any proxied
         * upstream, and vice versa. It names no certificate path, no anchor and no issuer URL.
         * <p>
         * Emitted once per boot rather than once per issuer: the key is gateway-global, so a gateway
         * with several issuers reports the posture once.
         */
        public static final LogRecord JWKS_HOSTNAME_VERIFICATION_DISABLED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(120)
                .template("egress_tls.jwks_verify_hostname is false — the JWKS back-channel no longer verifies that the IdP certificate names the dialled host. Certificate-chain trust is unaffected and an untrusted JWKS endpoint is still refused. The relaxation applies to every configured issuer's JWKS fetch and to that leg only; egress_tls.upstream_verify_hostname governs the proxy, gRPC and WebSocket egress clients separately and is unchanged by this key. An issuer naming jwks.tls_profile is refused at boot while this key is false, because the two are mutually exclusive. Restore verification by removing the key or setting it back to true in gateway.yaml")
                .build();
    }

    /**
     * Error-level messages (ERROR range 200-299).
     */
    @UtilityClass
    public static final class ERROR {

        /** A single collected configuration violation, one per problem found. */
        public static final LogRecord CONFIG_VALIDATION_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(200)
                .template("Invalid configuration in '%s' at '%s': %s")
                .build();

        /** Startup is aborted because the configuration is invalid. */
        public static final LogRecord CONFIG_STARTUP_ABORTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(201)
                .template("Refusing to start — configuration is invalid: %s")
                .build();
    }
}
