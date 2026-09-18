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

        /**
         * The effective default trust source, reported once at startup for every deployment.
         * <p>
         * It names both tiers separately because they are two independent levers reaching two
         * <em>disjoint</em> sets of outbound legs: the runtime {@code javax.net.ssl.trustStore}
         * system property governs every leg holding a raw JDK {@code TrustManager}, while the
         * Quarkus {@code <default>} TLS bucket's trust material governs every leg resolving through
         * the TLS registry. Collapsing them into one line would report a comfortable fiction for a
         * deployment that moved only one.
         * <p>
         * Emitted unconditionally, at {@code INFO}, because "which anchors is this gateway actually
         * trusting" is a question an operator must be able to answer from the boot log rather than
         * only when something is already wrong.
         * <p>
         * Each tier fragment carries the store's kind and, for the system-property route, its path —
         * an operator-supplied location, not secret material. It must never carry a password, an
         * alias, or any anchor bytes.
         */
        public static final LogRecord DEFAULT_TRUST_SOURCE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(17)
                .template("Effective default trust source — legs holding a raw JDK TrustManager: %s; legs resolving through the Quarkus TLS registry: %s")
                .build();

        /**
         * An issuer's JWKS key set was loaded by a retry after one or more failed load attempts, so
         * the issuer's tokens can be validated again and readiness no longer counts it as missing.
         * <p>
         * The pair to {@link WARN#JWKS_KEY_SET_RETRY}: that record announces every scheduled retry,
         * this one closes the episode. The template carries the configured issuer <em>name</em> and
         * the retry count only — never the JWKS URL, a host or any key material.
         */
        public static final LogRecord JWKS_KEY_SET_LOADED_AFTER_RETRY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(18)
                .template("JWKS key set for issuer '%s' loaded after %s retry attempt(s)")
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

        /**
         * {@code egress_tls.oidc_verify_hostname} resolved to {@code false} at boot, so the BFF OIDC
         * back-channel — discovery, the authorization-code exchange, refresh and refresh-token
         * revocation — no longer compares the dialled name against the identity provider
         * certificate's names.
         * <p>
         * The sibling of {@link #JWKS_HOSTNAME_VERIFICATION_DISABLED} on the sixth TLS leg, and a
         * {@code WARN} rather than a boot refusal for the same reason: an identity provider reached
         * through an address its certificate does not name is a legitimate deployment (ADR-0045).
         * What must not happen is the relaxation reaching production silently — this leg carries the
         * client secret and the authorization codes the gateway establishes sessions from.
         * <p>
         * The template states the scope rather than leaving it to be inferred: hostname matching only,
         * chain trust unaffected, this leg only, the collision refusal with
         * {@code egress_tls.oidc_tls_profile}, and how to restore. It names no certificate path, no
         * anchor and no identity-provider URL.
         * <p>
         * Emitted once per boot, from the single build of the BFF runtime, and only on the active BFF
         * path — a bearer-only gateway builds no such client.
         */
        public static final LogRecord OIDC_HOSTNAME_VERIFICATION_DISABLED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(125)
                .template("egress_tls.oidc_verify_hostname is false — the BFF OIDC back-channel (discovery, the authorization-code exchange, refresh and refresh-token revocation) no longer verifies that the identity provider certificate names the dialled host. Certificate-chain trust is unaffected and an untrusted identity provider is still refused. The relaxation applies to that leg only; egress_tls.jwks_verify_hostname and egress_tls.upstream_verify_hostname govern the JWKS back-channel and the proxy, gRPC and WebSocket egress clients separately and are unchanged by this key. Naming egress_tls.oidc_tls_profile is refused at boot while this key is false, because the two are mutually exclusive. Restore verification by removing the key or setting it back to true in gateway.yaml")
                .build();

        /**
         * A named {@code egress_tls.oidc_tls_profile} is in effect at boot, so the BFF OIDC
         * back-channel verifies the identity provider's certificate against the deployment-bound
         * anchors instead of the JVM default trust store.
         * <p>
         * The sibling of {@link #EGRESS_TRUST_PROFILE_IN_EFFECT} on the sixth TLS leg: a named profile
         * <em>replaces</em> the leg's anchors rather than adding to them (ADR-0045), so a profile
         * holding only a private CA stops trusting public certificate authorities for the identity
         * provider. That is a deliberate, legitimate posture, so it is reported and never refused.
         * <p>
         * The template carries the <em>logical</em> profile name only — the ADR-0011 neutral name that
         * appears in {@code gateway.yaml}. It must never carry the deployment's store path, password,
         * or any anchor material.
         */
        public static final LogRecord OIDC_TRUST_PROFILE_IN_EFFECT = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(126)
                .template("egress_tls.oidc_tls_profile '%s' is in effect — its anchors REPLACE the JVM default trust store on the BFF OIDC back-channel (discovery, the authorization-code exchange, refresh and refresh-token revocation), so a public certificate authority is no longer trusted for the identity provider unless the profile carries it too. The JWKS back-channel and the proxy, gRPC and WebSocket egress clients are not governed by this profile")
                .build();

        /**
         * The terminated <em>main</em> listener resolved to plain HTTP at startup — no server key
         * material reached it, so no HTTPS listener was started.
         * <p>
         * Like {@link #MANAGEMENT_PLAIN_HTTP} this reports the <em>observed effective state</em>
         * rather than a declared intention: the audit inspects the key material the listener
         * actually resolved, across all three routes the recorder evaluates, so the warning cannot
         * drift away from reality when the activation route changes. It is the authoritative report
         * where the declared and the resolved view of key material disagree.
         * <p>
         * It is a {@code WARN} and never a boot refusal: a plain-HTTP main listener behind a
         * TLS-terminating boundary is a legitimate deployment, and blocking it would be wrong. What
         * must not happen is that it arrives silently, since every HTTPS client of the gateway fails
         * against a listener that quietly stopped terminating TLS.
         * <p>
         * The live ADR-0017 edge topology rides in this same record rather than in a second one, so
         * an operator reading the downgrade also learns whether an accept-time front listener sits
         * in front of the port being reported.
         * <p>
         * The template names the port and the topology only; it must never carry a store path, a
         * password, or any anchor material. The remedy names all three routes by which key material
         * can be supplied, because the audit observes the effective state and cannot tell which one
         * a deployment intended to use.
         */
        public static final LogRecord TERMINATED_LISTENER_PLAIN_HTTP = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(121)
                .template("Terminated main listener is serving PLAIN HTTP on port %s — no server key material resolved, so no HTTPS listener was started and every HTTPS client of this gateway will fail against it. Live edge topology: %s. Expose the plain port only behind a TLS-terminating boundary; restore HTTPS by supplying server key material through exactly one of quarkus.http.tls-configuration-name, a default quarkus.tls.key-store.* bucket, or quarkus.http.ssl.certificate.* — the material stays deployment-supplied and is never named in gateway.yaml (ADR-0025)")
                .build();

        /**
         * An operator-supplied default trust store is in effect on at least one of the two tiers
         * {@link INFO#DEFAULT_TRUST_SOURCE} reports.
         * <p>
         * The hazard is <em>replacement</em>: both levers substitute the platform trust bundle
         * wholesale rather than adding to it, so every anchor the deployment did not put into its own
         * store stops being trusted on the legs that lever governs. An operator who supplies a store
         * holding only a private certificate authority has thereby stopped trusting the public roots
         * the platform shipped, which surfaces much later as an opaque handshake rejection rather
         * than as anything resembling a configuration error.
         * <p>
         * The record carries both tier fragments and, when they disagree, says so in its own sentence
         * — a deployment that moved only one lever has left half its outbound surface behind, and
         * that half is exactly what an operator would otherwise never think to check.
         * <p>
         * It is a {@code WARN} and never a boot refusal: a deployment-bound trust store is a
         * legitimate, deliberate posture. The template must never carry a password, an alias, or any
         * anchor material; the trust-store password property is not read at all.
         */
        public static final LogRecord DEFAULT_TRUST_STORE_REPLACED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(122)
                .template("An operator-supplied default trust store is in effect. It REPLACES the platform trust bundle wholesale rather than being added to it, so every anchor the deployment did not put into that store stops being trusted — the public roots the platform shipped included. Legs holding a raw JDK TrustManager: %s. Legs resolving through the Quarkus TLS registry: %s. %s")
                .build();

        /**
         * Warns once at boot that {@code oidc.session.max_cookie_size} resolved high enough that the
         * {@code Set-Cookie} header derived from it exceeds the ~4096 bytes RFC 6265 6.1 guarantees
         * a browser will keep per cookie, so a session sealing into that headroom is a session no
         * browser is obliged to hold.
         * <p>
         * <strong>The comparison is against the derived HEADER, not the value budget.</strong> The
         * key declares a sealed <em>value</em> budget; RFC 6265 6.1 budgets the cookie's name, value
         * and attributes together. The threshold is therefore the 4096-byte guarantee less
         * {@code SealedSessionCookieCodec.setCookieHeaderOverhead(cookieName, sessionTtl)} for the
         * gateway's <em>resolved</em> {@code session.cookie_name} and {@code session.ttl_seconds} —
         * 4019 under the default configuration, and lower for a longer cookie name or a five-digit
         * {@code Max-Age}. Comparing against a 4096 value budget instead left a 77-byte band in
         * which the gateway emitted an undeliverable header and this record stayed silent, which is
         * the very failure the record exists to announce; comparing against a <em>fixed</em> 4019
         * left the same band open for every configuration that is not the default one.
         * <p>
         * <strong>It reads the EFFECTIVE budget, so an omitted key is in scope.</strong> The gateway
         * emits the same header whether the operator wrote the budget down or left it defaulted, so
         * the record fires on the resolved value either way. That is the second half of closing the
         * band: the first is that the shipped default is now the browser-safe 4019 rather than 4096,
         * so the default configuration lands ON the guarantee and this record stays quiet there —
         * while a gateway running a longer cookie name or a wider {@code Max-Age}, which used to be
         * silent, now says so.
         * <p>
         * <strong>Cookie mode only.</strong> The record is emitted when {@code session.mode} is
         * {@code cookie}. A server-mode or bearer-only gateway emits no sealed session
         * {@code Set-Cookie} at all, so there is no header for the guarantee to govern and an
         * explicit {@code max_cookie_size} there is inert rather than dangerous. Its range is still
         * validated in every mode.
         * <p>
         * It is a {@code WARN} and never a boot refusal, for the same reason as
         * {@link #MANAGEMENT_PLAIN_HTTP} and {@link #EGRESS_HOSTNAME_VERIFICATION_DISABLED}: the
         * validated range stays {@code 40..8192} and a budget whose header outgrows the guarantee is
         * a legitimate posture for a non-browser client that keeps whatever it is sent. What must
         * not happen is the relaxation reaching production silently.
         * <p>
         * <strong>Why this warning is the only signal there is.</strong> Every other budget failure
         * in this area is loud: below the floor the codec refuses to construct, above the configured
         * budget the seal is refused with {@code ApiSheriff-114} and login answers {@code 500}.
         * Raising the budget past the browser-safe value removes that refusal without removing the
         * problem — the gateway then emits a {@code Set-Cookie} the browser discards with no error,
         * no header and no diagnostic on either side, and the user simply stays anonymous. The
         * gateway cannot observe a drop that happens in the browser, so nothing downstream of this
         * line will ever report it.
         * <p>
         * The template carries the resolved byte budget, the header size derived from it and the
         * browser-safe budget only — never a cookie value, never key material.
         */
        public static final LogRecord COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(124)
                .template("The effective oidc.session.max_cookie_size is %s bytes — declared, or the shipped default when the key is omitted — which the gateway emits as a Set-Cookie header of at least %s bytes once the cookie name and attributes are counted, above the ~4096 bytes RFC 6265 6.1 guarantees a browser will keep per cookie, a budget that governs the whole header rather than the sealed value alone. In cookie mode the session IS the cookie, so a session sealing into that headroom is accepted by the gateway and then dropped SILENTLY by the browser — no error surfaces on either side and the user simply stays anonymous, which the gateway cannot detect. Raising this budget moves the failure into the browser rather than removing it. Restore the browser-safe posture by setting oidc.session.max_cookie_size to %s bytes or below in gateway.yaml, or by shortening session.cookie_name or session.ttl_seconds — those two are what widen the per-header overhead this threshold subtracts, so a gateway running either off the default has less room for the value than the default budget assumes")
                .build();

        /**
         * A {@code redirect} route opted into {@code allow_external: true} at boot, so its
         * {@code Location} may send clients to another origin.
         * <p>
         * An external redirect is a legitimate posture — a moved site, a hand-off to an identity
         * provider — so it is reported and never refused, provided the location passed the boot
         * open-redirect review (an absolute {@code http}/{@code https} URI with a non-empty host
         * and no user-info). What must not happen is a gateway silently pointing clients at another
         * origin, which is exactly the shape an open redirect takes.
         * <p>
         * The template names the route id only. It never carries the location value: the target is
         * operator configuration an attacker reading the log has no business learning, and the id
         * is enough for the operator to find it.
         */
        public static final LogRecord REDIRECT_EXTERNAL_TARGET_ALLOWED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(128)
                .template("Route '%s' declares redirect.allow_external: true — its Location may send clients to another origin. Confirm the target is intended; remove allow_external to confine the redirect to a gateway path")
                .build();

        /**
         * A load attempt of an issuer's JWKS key set completed without a key set, so the gateway
         * schedules another attempt after the stated delay.
         * <p>
         * The delay starts at one second and doubles per attempt, capped at thirty seconds and never
         * above the issuer's refresh interval, so a late identity provider is picked up within seconds
         * rather than after a whole refresh interval. Until then the issuer's tokens cannot be
         * validated and readiness reports {@code DOWN}. Retries are driven by a scheduler only, never
         * by request or probe traffic.
         * <p>
         * The template carries the configured issuer <em>name</em> and the delay only — never the JWKS
         * URL, a host, the failure cause or any key material.
         */
        public static final LogRecord JWKS_KEY_SET_RETRY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(129)
                .template("JWKS key set for issuer '%s' not loaded — retrying in %s ms")
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
