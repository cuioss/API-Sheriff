/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.auth;

import java.util.ArrayList;
import java.util.List;


import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.IssuerConfig;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;

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
 * {@code cui.jwt.*} property surface — the gateway drives issuers from its own YAML model, not the
 * extension's properties. Each gateway {@link IssuerConfig} maps to a token-sheriff
 * {@link de.cuioss.sheriff.token.validation.IssuerConfig}: an {@code http} JWKS source becomes an
 * {@link HttpJwksLoaderConfig}, a {@code file} source becomes a JWKS file path. Validation is fully
 * offline once the key material has loaded.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TokenValidatorProducer {

    private static final String SOURCE_HTTP = "http";
    private static final String SOURCE_FILE = "file";

    private final GatewayConfig gatewayConfig;
    private final JwksTrustProfileResolver trustProfileResolver;

    /**
     * @param gatewayConfig        the bound gateway document carrying the {@code token_validation}
     *                             block
     * @param trustProfileResolver the single seam mapping a logical {@code jwks.tls_profile} name
     *                             to concrete trust anchors
     */
    @Inject
    public TokenValidatorProducer(GatewayConfig gatewayConfig, JwksTrustProfileResolver trustProfileResolver) {
        this.gatewayConfig = gatewayConfig;
        this.trustProfileResolver = trustProfileResolver;
    }

    /**
     * Forces the validator to be assembled at boot rather than on the first bearer request, so a
     * misconfigured issuer — an unresolvable {@code jwks.tls_profile}, a missing JWKS source —
     * fails startup instead of surfacing as a runtime rejection once traffic arrives. The observed
     * parameter is the injection point that triggers the producer below.
     *
     * @param event     the Quarkus startup event
     * @param validator the produced gateway validator, injected to force eager assembly
     */
    void onStartup(@Observes StartupEvent event, @GatewayValidator TokenValidator validator) {
        // Assembly happens as a side effect of injecting the validator parameter.
    }

    /**
     * Builds the single shared gateway validator from configured issuers.
     *
     * @return the gateway {@link TokenValidator}
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when {@code token_validation} is
     *                          absent, an issuer declares no usable JWKS source, or an issuer names
     *                          a {@code jwks.tls_profile} the deployment does not define
     */
    @Produces
    @ApplicationScoped
    @GatewayValidator
    public TokenValidator gatewayTokenValidator() {
        TokenValidationConfig config = gatewayConfig.tokenValidation()
                .orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                        "token_validation is required to build the bearer-token validator"));
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
        issuer.audience().ifPresentOrElse(
                builder::expectedAudience,
                () -> builder.audienceValidationDisabled(true));
        IssuerConfig.Jwks jwks = issuer.jwks().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                "Issuer '" + issuer.name() + "' declares no jwks source"));
        applyJwks(builder, issuer, jwks);
        return builder.build();
    }

    private void applyJwks(de.cuioss.sheriff.token.validation.IssuerConfig.IssuerConfigBuilder builder,
            IssuerConfig issuer, IssuerConfig.Jwks jwks) {
        if (SOURCE_HTTP.equals(jwks.source())) {
            builder.httpJwksLoaderConfig(toHttpJwksLoaderConfig(issuer, jwks));
        } else if (SOURCE_FILE.equals(jwks.source())) {
            String file = jwks.file().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' jwks source 'file' declares no file path"));
            builder.jwksFilePath(file);
        } else {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' declares unsupported jwks source '" + jwks.source() + "'");
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
     *
     * @param issuer the gateway issuer entry, for the identifier and error context
     * @param jwks   the issuer's {@code http} JWKS block
     * @return the loader config carrying the resolved egress policy and trust anchors
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the block
     *                          declares no url, or names an unresolvable {@code tls_profile}
     */
    HttpJwksLoaderConfig toHttpJwksLoaderConfig(IssuerConfig issuer, IssuerConfig.Jwks jwks) {
        String url = jwks.url().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                "Issuer '" + issuer.name() + "' jwks source 'http' declares no url"));
        HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder builder = HttpJwksLoaderConfig.builder()
                .issuerIdentifier(issuer.issuer())
                .jwksUrl(url);
        for (String host : jwks.allowedEgressHosts()) {
            builder.allowedEgressHost(host);
        }
        jwks.tlsProfile().ifPresent(profile -> builder.sslContext(trustProfileResolver.resolve(issuer, profile)));
        return builder.build();
    }
}
