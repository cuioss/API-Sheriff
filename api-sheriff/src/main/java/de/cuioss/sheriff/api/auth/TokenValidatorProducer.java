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

import jakarta.enterprise.context.ApplicationScoped;
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

    /**
     * @param gatewayConfig the bound gateway document carrying the {@code token_validation} block
     */
    @Inject
    public TokenValidatorProducer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
    }

    /**
     * Builds the single shared gateway validator from configured issuers.
     *
     * @return the gateway {@link TokenValidator}
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when {@code token_validation} is
     *                          absent or an issuer declares no usable JWKS source
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

    private static de.cuioss.sheriff.token.validation.IssuerConfig toValidationIssuer(IssuerConfig issuer) {
        de.cuioss.sheriff.token.validation.IssuerConfig.IssuerConfigBuilder builder =
                de.cuioss.sheriff.token.validation.IssuerConfig.builder().issuerIdentifier(issuer.issuer());
        issuer.audience().ifPresent(builder::expectedAudience);
        IssuerConfig.Jwks jwks = issuer.jwks().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                "Issuer '" + issuer.name() + "' declares no jwks source"));
        applyJwks(builder, issuer, jwks);
        return builder.build();
    }

    private static void applyJwks(de.cuioss.sheriff.token.validation.IssuerConfig.IssuerConfigBuilder builder,
            IssuerConfig issuer, IssuerConfig.Jwks jwks) {
        if (SOURCE_HTTP.equals(jwks.source())) {
            String url = jwks.url().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' jwks source 'http' declares no url"));
            builder.httpJwksLoaderConfig(HttpJwksLoaderConfig.builder()
                    .issuerIdentifier(issuer.issuer())
                    .jwksUrl(url)
                    .build());
        } else if (SOURCE_FILE.equals(jwks.source())) {
            String file = jwks.file().orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' jwks source 'file' declares no file path"));
            builder.jwksFilePath(file);
        } else {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' declares unsupported jwks source '" + jwks.source() + "'");
        }
    }
}
