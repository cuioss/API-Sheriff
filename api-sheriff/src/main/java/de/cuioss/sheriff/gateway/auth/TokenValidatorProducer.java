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
package de.cuioss.sheriff.gateway.auth;

import java.util.ArrayList;
import java.util.List;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer of the gateway's single shared {@link TokenValidator}, built once from the
 * {@code token_validation} block of {@code gateway.yaml}.
 * <p>
 * The produced validator carries the {@link GatewayValidator} qualifier so it coexists with the
 * unqualified validator the {@code token-sheriff-validation-quarkus} extension produces from its
 * {@code sheriff.token.*} property surface — the gateway drives issuers from its own YAML model, not
 * the extension's properties. Each gateway {@link IssuerConfig} maps to a token-sheriff
 * {@link de.cuioss.sheriff.token.validation.IssuerConfig}: an {@code http} JWKS source becomes an
 * {@link HttpJwksLoaderConfig}, a {@code file} source becomes a JWKS file path. Validation is fully
 * offline once the key material has loaded.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TokenValidatorProducer {

    private static final CuiLogger LOGGER = new CuiLogger(TokenValidatorProducer.class);

    private static final String SOURCE_HTTP = "http";
    private static final String SOURCE_FILE = "file";
    /** Shared prefix for the per-issuer {@code CONFIG_INVALID} error messages. */
    private static final String ISSUER_PREFIX = "Issuer '";

    private final GatewayConfig gatewayConfig;
    private final JwksTrustProfileResolver trustProfileResolver;
    /**
     * The resolved global {@code egress_tls.jwks_verify_hostname} posture, applied to every
     * {@code http} JWKS source this producer builds.
     * <p>
     * Resolved ONCE here rather than per issuer, for the same reason the edge resolves its egress-TLS
     * settings once (ADR-0040): the key is gateway-global and has no per-issuer override, so a
     * per-issuer lookup would only invite one to be invented. The flag is bound at loader-config
     * construction, exactly as the edge's counterpart is bound at client construction.
     */
    private final boolean jwksVerifyHostname;

    /**
     * @param gatewayConfig        the bound gateway document carrying the {@code token_validation}
     *                             block and the global {@code egress_tls} block
     * @param trustProfileResolver the single seam mapping a logical {@code jwks.tls_profile} name
     *                             to concrete trust anchors
     */
    @Inject
    public TokenValidatorProducer(GatewayConfig gatewayConfig, JwksTrustProfileResolver trustProfileResolver) {
        this.gatewayConfig = gatewayConfig;
        this.trustProfileResolver = trustProfileResolver;
        // An absent egress_tls block resolves to the defaults rather than to a null-guarded false —
        // mirroring GatewayEdgeRoute.egressTlsOf. Reading the primitive off a defaults() instance is
        // what keeps 'block omitted entirely' and 'block present, key omitted' the same posture:
        // verification ON. (ConfigLoader's EgressTlsDeserializer handles the second case.)
        EgressTlsConfig egressTls = gatewayConfig.egressTls();
        this.jwksVerifyHostname = (egressTls == null ? EgressTlsConfig.defaults() : egressTls).jwksVerifyHostname();
        // Emitted ONCE at bean construction rather than per issuer, so a gateway with a dozen issuers
        // reports the relaxed posture once. A WARN and never a boot refusal, exactly as the edge's
        // ApiSheriff-118 counterpart: a JWKS endpoint reached through an address its certificate does
        // not name is a legitimate deployment — it just may not reach production silently.
        if (!jwksVerifyHostname) {
            LOGGER.warn(ConfigLogMessages.WARN.JWKS_HOSTNAME_VERIFICATION_DISABLED);
        }
    }

    /**
     * Forces the validator to be assembled at boot rather than on the first bearer request, so a
     * misconfigured issuer — an unresolvable {@code jwks.tls_profile}, a missing JWKS source —
     * fails startup instead of surfacing as a runtime rejection once traffic arrives.
     * <p>
     * Merely observing {@link StartupEvent} with the validator as a parameter is NOT enough: an
     * {@code @ApplicationScoped} bean is injected as a lazy client proxy, and ArC does not invoke
     * {@link #gatewayTokenValidator()} until the first business method is called on that proxy.
     * This method therefore invokes a method on the injected proxy ({@link Object#toString()}) to
     * force contextual-instance creation at boot, which runs the full assembly path
     * ({@code gatewayTokenValidator} → {@code toValidationIssuer} → {@code applyJwks} →
     * {@code toHttpJwksLoaderConfig} → {@code trustProfileResolver.resolve}) and aborts startup on a
     * misconfiguration.
     *
     * @param event     the Quarkus startup event
     * @param validator the produced gateway validator proxy, whose first method call forces eager
     *                  assembly
     */
    void onStartup(@Observes StartupEvent event, @GatewayValidator TokenValidator validator) {
        // Invoke a method on the injected proxy to force contextual-instance creation at boot;
        // without this the @ApplicationScoped validator stays unassembled until the first request.
        validator.toString();
    }

    /**
     * Builds the single shared gateway validator from configured issuers.
     *
     * @return the gateway {@link TokenValidator}
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when {@code token_validation} is
     *                          absent, an issuer declares no usable JWKS source, an
     *                          {@code http}-sourced issuer names a {@code jwks.tls_profile} the
     *                          deployment does not define, or an {@code http}-sourced issuer names a
     *                          {@code jwks.tls_profile} while
     *                          {@code egress_tls.jwks_verify_hostname} is {@code false}. Both
     *                          {@code tls_profile} conditions are reached only from the
     *                          {@code http} branch of {@code applyJwks}: a {@code file} source
     *                          opens no TLS connection, so neither the profile resolution nor the
     *                          hostname-posture collision exists on that leg
     */
    @Produces
    @ApplicationScoped
    @GatewayValidator
    public TokenValidator gatewayTokenValidator() {
        TokenValidationConfig config = gatewayConfig.tokenValidation();
        if (config == null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "token_validation is required to build the bearer-token validator");
        }
        List<de.cuioss.sheriff.token.validation.IssuerConfig> issuers = new ArrayList<>();
        for (IssuerConfig issuer : config.issuers()) {
            issuers.add(toValidationIssuer(issuer));
        }
        return TokenValidator.builder().issuerConfigs(issuers).build();
    }

    private de.cuioss.sheriff.token.validation.IssuerConfig toValidationIssuer(IssuerConfig issuer) {
        de.cuioss.sheriff.token.validation.IssuerConfig.IssuerConfigBuilder builder =
                de.cuioss.sheriff.token.validation.IssuerConfig.builder().issuerIdentifier(issuer.issuer());
        // Audience is optional in the gateway config model (IssuerConfig#audience). token-sheriff
        // requires an explicit choice at build time — either a non-empty expected audience OR an
        // explicit opt-out — so an issuer that configures no audience must disable audience
        // validation; otherwise IssuerConfig.build() throws and the (lazily created) validator bean
        // fails on the first bearer request instead of validating the token.
        String audience = issuer.audience();
        if (audience != null) {
            builder.expectedAudience(audience);
        } else {
            builder.audienceValidationDisabled(true);
        }
        IssuerConfig.Jwks jwks = issuer.jwks();
        if (jwks == null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    ISSUER_PREFIX + issuer.name() + "' declares no jwks source");
        }
        applyJwks(builder, issuer, jwks);
        return builder.build();
    }

    private void applyJwks(de.cuioss.sheriff.token.validation.IssuerConfig.IssuerConfigBuilder builder,
            IssuerConfig issuer, IssuerConfig.Jwks jwks) {
        if (SOURCE_HTTP.equals(jwks.source())) {
            builder.httpJwksLoaderConfig(toHttpJwksLoaderConfig(issuer, jwks));
        } else if (SOURCE_FILE.equals(jwks.source())) {
            String file = jwks.file();
            if (file == null) {
                throw new GatewayException(EventType.CONFIG_INVALID,
                        ISSUER_PREFIX + issuer.name() + "' jwks source 'file' declares no file path");
            }
            builder.jwksFilePath(file);
        } else {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    ISSUER_PREFIX + issuer.name() + "' declares unsupported jwks source '" + jwks.source() + "'");
        }
    }

    /**
     * Builds the loader config for an {@code http} JWKS source, applying the issuer's
     * {@code allowed_egress_hosts} allowlist on top of token-sheriff's SSRF egress guard and
     * the trust anchors its {@code tls_profile} names.
     * <p>
     * <strong>Secure by default.</strong> When {@code allowed_egress_hosts} is absent or
     * empty this method calls no egress builder method at all, so the built config keeps
     * {@link de.cuioss.sheriff.token.commons.transport.EgressPolicy#secureDefault()} — a
     * JWKS URL resolving to a loopback, link-local, site-local, any-local, multicast, or
     * unique-local address is refused. Each configured host is passed to
     * {@link HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder#allowedEgressHost(String)},
     * which exempts that single host and nothing else; the allowlist is host-exact, never
     * a wildcard or a suffix match. This is the narrow widening the threat model's GW-05
     * and BFF-07 prescribe for a trusted IdP that lives on a private network.
     * <p>
     * <strong>Default trust unless a profile is named.</strong> When {@code tls_profile} is
     * absent no SSL context is set, so the JWKS client keeps the JVM's default trust store —
     * the correct behaviour for an IdP presenting a publicly-trusted certificate. When a
     * profile IS named, {@link JwksTrustProfileResolver} maps it to the deployment's trust
     * anchors; an unresolvable name fails startup rather than falling back to default trust.
     * <p>
     * <strong>Hostname verification is bound here, and the two knobs are mutually exclusive.</strong>
     * The global {@code egress_tls.jwks_verify_hostname} posture is passed to
     * {@link HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder#verifyHostname(boolean)} on every
     * {@code http} source. It relaxes hostname <em>matching</em> only — chain trust is untouched, so
     * an untrusted or self-signed JWKS certificate is still refused. token-sheriff implements the
     * relaxation by swapping the trust manager of the context <em>it</em> derives from the JVM default
     * trust store, so it refuses {@code verifyHostname(false)} combined with a caller-supplied
     * {@code sslContext(...)} — there is nothing to relax in a context the caller built. An issuer
     * naming {@code jwks.tls_profile} supplies exactly such a context, so this method refuses that
     * combination itself, at boot, with a message naming both keys and the issuer — ahead of
     * token-sheriff's own {@link IllegalArgumentException}, which would surface as an opaque
     * builder failure rather than as a configuration error.
     *
     * @param issuer the gateway issuer entry, for the identifier and error context
     * @param jwks   the issuer's {@code http} JWKS block
     * @return the loader config carrying the resolved egress policy, hostname posture and trust anchors
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the block
     *                          declares no url, names an unresolvable {@code tls_profile}, or names a
     *                          {@code tls_profile} while {@code egress_tls.jwks_verify_hostname} is
     *                          {@code false}
     */
    HttpJwksLoaderConfig toHttpJwksLoaderConfig(IssuerConfig issuer, IssuerConfig.Jwks jwks) {
        String url = jwks.url();
        if (url == null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    ISSUER_PREFIX + issuer.name() + "' jwks source 'http' declares no url");
        }
        String tlsProfile = jwks.tlsProfile();
        // Refused BEFORE the builder is touched, so the operator gets a CONFIG_INVALID naming both
        // keys and the issuer instead of token-sheriff's IllegalArgumentException from build().
        if (!jwksVerifyHostname && tlsProfile != null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    ISSUER_PREFIX + issuer.name() + "' names jwks.tls_profile '" + tlsProfile
                            + "' while egress_tls.jwks_verify_hostname is false — the two are mutually "
                            + "exclusive. The hostname relaxation applies only to the default-trust-store "
                            + "context the JWKS client derives, so a profile-supplied context leaves "
                            + "nothing to relax. Either drop jwks.tls_profile for this issuer and bind "
                            + "its anchors into the JVM default trust store, or set "
                            + "egress_tls.jwks_verify_hostname back to true");
        }
        HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder builder = HttpJwksLoaderConfig.builder()
                .issuerIdentifier(issuer.issuer())
                .jwksUrl(url)
                // Called unconditionally, on the true path as well: passing the resolved posture every
                // time is what makes the key's effect independent of token-sheriff's own default, so an
                // upstream default change cannot silently move this gateway's posture (ADR-0022).
                // Falsification-checked: deleting this call turns the RELAXED leg of
                // TokenValidatorProducerTest.JwksVerifyHostname's two matched controls red (the JWKS
                // fetch then fails hostname verification and the issuer never becomes healthy).
                .verifyHostname(jwksVerifyHostname);
        for (String host : jwks.allowedEgressHosts()) {
            builder.allowedEgressHost(host);
        }
        if (tlsProfile != null) {
            builder.sslContext(trustProfileResolver.resolve(issuer, tlsProfile));
        }
        return builder.build();
    }
}
