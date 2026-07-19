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
package de.cuioss.sheriff.api.quarkus;

import de.cuioss.sheriff.api.auth.GatewayValidator;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.Metadata;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.validation.TokenValidator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.CreationException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * SmallRye {@code @Readiness} probe served on the management port ({@code /q/health/ready}),
 * reporting whether the gateway is ready to serve traffic.
 * <p>
 * Readiness reflects two facts, per {@code architecture.adoc} § Metrics (Health):
 * <ul>
 *   <li><strong>Configuration</strong> — the {@link GatewayConfig} bean is present, which proves
 *       the boot-time load-and-validate pipeline in {@link ConfigProducer} succeeded (an invalid
 *       configuration aborts startup, so the application would never reach readiness with an
 *       unbound config);</li>
 *   <li><strong>JWKS</strong> — when a {@code token_validation} block is configured, the gateway's
 *       own {@link GatewayValidator}-qualified {@link TokenValidator} resolves successfully. Building
 *       that validator requires every configured issuer to declare a usable JWKS source, so a
 *       resolution failure ({@link GatewayException}) marks the probe {@code DOWN} with the cause.
 *       A gateway with no {@code token_validation} block needs no bearer validation, so JWKS is
 *       reported {@code not-applicable} and does not gate readiness.</li>
 * </ul>
 * The validator is resolved lazily through an {@link Instance} so a misconfigured JWKS source
 * yields a clean {@code DOWN} response rather than failing this probe's own construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Readiness
@ApplicationScoped
public class GatewayReadinessCheck implements HealthCheck {

    private static final String CHECK_NAME = "gateway-readiness";
    private static final String DATA_CONFIG = "config";
    private static final String DATA_CONFIG_VERSION = "config_version";
    private static final String DATA_JWKS = "jwks";
    private static final String DATA_ISSUERS = "issuers";
    private static final String DATA_ERROR = "error";

    private final GatewayConfig gatewayConfig;
    private final Instance<TokenValidator> gatewayValidator;

    /**
     * @param gatewayConfig    the bound, boot-validated gateway document
     * @param gatewayValidator the lazily-resolved gateway bearer-token validator
     */
    @Inject
    public GatewayReadinessCheck(GatewayConfig gatewayConfig,
            @GatewayValidator Instance<TokenValidator> gatewayValidator) {
        this.gatewayConfig = gatewayConfig;
        this.gatewayValidator = gatewayValidator;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code UP} when the configuration is bound and — if bearer validation is configured —
     *         the JWKS-backed validator resolves; {@code DOWN} carrying the failure cause otherwise
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(CHECK_NAME)
                .withData(DATA_CONFIG, "loaded");
        gatewayConfig.metadata().flatMap(Metadata::configVersion)
                .ifPresent(version -> builder.withData(DATA_CONFIG_VERSION, version));

        if (gatewayConfig.tokenValidation().isEmpty()) {
            return builder.withData(DATA_JWKS, "not-applicable").up().build();
        }

        int issuerCount = gatewayConfig.tokenValidation().get().issuers().size();
        builder.withData(DATA_ISSUERS, issuerCount);
        try {
            gatewayValidator.get();
            return builder.withData(DATA_JWKS, "ready").up().build();
        } catch (GatewayException | CreationException failure) {
            return builder.withData(DATA_JWKS, "unavailable")
                    .withData(DATA_ERROR, String.valueOf(failure.getMessage()))
                    .down().build();
        }
    }
}
