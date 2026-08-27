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
package de.cuioss.sheriff.gateway.quarkus;

import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Metadata;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.tools.logging.CuiLogger;

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
 *       resolution failure ({@link GatewayException}) marks the probe {@code DOWN} — reporting a
 *       fixed status token, never the cause, which is logged instead (see
 *       {@link #ERROR_VALIDATION_UNAVAILABLE}).
 *       A gateway with no {@code token_validation} block needs no bearer validation, so JWKS is
 *       reported {@code not-applicable} and does not gate readiness.</li>
 *   <li><strong>Issuer reachability (mode: server)</strong> — when the gateway runs a BFF
 *       {@code oidc.session.mode: server} deployment, the OIDC issuer must be reachable for the
 *       gateway to mediate and validate the confidential-client tokens. This reuses the same
 *       JWKS-backed validation health check above (resolving the {@link GatewayValidator}
 *       {@link TokenValidator} reaches every configured issuer's JWKS): a server-mode probe adds
 *       an {@code oidc=server} datum and reports the issuer as {@code reachable} /
 *       {@code unreachable} alongside the JWKS status, so a server-mode probe surfaces issuer
 *       reachability explicitly. A server-mode deployment that configures no
 *       {@code token_validation} block has no validation health check to reuse, so issuer
 *       reachability is reported {@code unverified} — the confidential-client engine reaches the
 *       issuer lazily on the first login and does not gate boot readiness.</li>
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

    private static final CuiLogger LOGGER = new CuiLogger(GatewayReadinessCheck.class);

    private static final String CHECK_NAME = "gateway-readiness";
    private static final String DATA_CONFIG = "config";
    private static final String DATA_CONFIG_VERSION = "config_version";
    private static final String DATA_JWKS = "jwks";
    private static final String DATA_ISSUERS = "issuers";
    private static final String DATA_ERROR = "error";
    private static final String DATA_OIDC = "oidc";
    private static final String DATA_ISSUER_REACHABILITY = "issuer_reachability";

    /** The readiness payload's mode label — the same canonical spelling the config model owns. */
    private static final String MODE_SERVER = OidcConfig.Session.MODE_SERVER;
    private static final String ISSUER_REACHABLE = "reachable";
    private static final String ISSUER_UNREACHABLE = "unreachable";
    private static final String ISSUER_UNVERIFIED = "unverified";

    /**
     * The fixed, non-disclosing value of the {@code error} datum on a DOWN response.
     * <p>
     * The probe previously wrote the raw exception message here. That message can carry issuer URLs,
     * internal hostnames, TLS/trust detail and filesystem paths, and this payload is served on the
     * management interface — which has exactly one port and may legitimately be plain HTTP (ADR-0025),
     * so it must be treated as reachable by anything that can reach that port. Readiness owes the
     * caller a <em>state</em>, not a cause (ADR-0027): the datum is now a bounded constant, and the
     * cause reaches the operator through {@code WARN ApiSheriff-116} instead.
     */
    private static final String ERROR_VALIDATION_UNAVAILABLE = "validation-unavailable";

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
     *         the JWKS-backed validator resolves; {@code DOWN} carrying a fixed, non-disclosing status
     *         token otherwise (the failure cause is logged, never returned)
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(CHECK_NAME)
                .withData(DATA_CONFIG, "loaded");
        Metadata metadata = gatewayConfig.metadata();
        String configVersion = metadata == null ? null : metadata.configVersion();
        if (configVersion != null) {
            builder.withData(DATA_CONFIG_VERSION, configVersion);
        }

        boolean serverMode = isServerSessionMode();
        if (serverMode) {
            builder.withData(DATA_OIDC, MODE_SERVER);
        }

        TokenValidationConfig tokenValidation = gatewayConfig.tokenValidation();
        if (tokenValidation == null) {
            // No bearer validation configured, so there is no JWKS-backed validation health check to
            // reuse. A server-mode deployment reaches its issuer lazily through the confidential-client
            // engine on the first login, which does not gate boot readiness — so issuer reachability is
            // reported unverified rather than gating the probe DOWN.
            if (serverMode) {
                builder.withData(DATA_ISSUER_REACHABILITY, ISSUER_UNVERIFIED);
            }
            return builder.withData(DATA_JWKS, "not-applicable").up().build();
        }

        int issuerCount = tokenValidation.issuers().size();
        builder.withData(DATA_ISSUERS, issuerCount);
        try {
            gatewayValidator.get();
            builder.withData(DATA_JWKS, "ready");
            if (serverMode) {
                builder.withData(DATA_ISSUER_REACHABILITY, ISSUER_REACHABLE);
            }
            return builder.up().build();
        } catch (GatewayException | CreationException failure) {
            // NOT REACHED IN THE SHIPPED EAGER-BOOT TOPOLOGY, AND DELIBERATELY RETAINED.
            //
            // TokenValidatorProducer.onStartup forces the @ApplicationScoped validator into
            // existence at StartupEvent (via a method call on the injected proxy), so boot already
            // performs whatever gatewayValidator.get() would do here. Verified empirically against
            // the distroless image, and there is no third outcome: a construction-FAILING JWKS
            // aborts boot non-zero, so the management port never listens and this probe is never
            // called; a construction-SUCCEEDING but lazily-unreachable JWKS reports UP, because the
            // fetch is lazy. Readiness therefore flips at the same moment liveness does (ADR-0027).
            //
            // Kept rather than deleted as dead code, for three reasons:
            //  (1) Removing the catch does not remove the failure path, it only moves the rendering
            //      OUT of this class -- an escaping exception would be rendered by SmallRye's own
            //      check-failure handling instead, outside the gateway's control and without the
            //      fixed-token redaction below. That is precisely the disclosure this probe closed,
            //      on a payload served by an interface that may legitimately be plain HTTP.
            //  (2) It is the designated seam for closing the gap ADR-0027 section 4 records as open:
            //      giving this probe a LIVE loader-status read (so an unreachable JWKS endpoint, a
            //      stalled rotation or an expired key take readiness DOWN) needs exactly this DOWN
            //      branch. Deleting it would mean re-adding it.
            //  (3) The eager-boot coupling it depends on is a fail-CLOSED security property worth
            //      more than probe independence: a misconfigured issuer must abort startup rather
            //      than let the gateway serve traffic and fail at the first bearer request. So the
            //      alternative disposition -- relaxing onStartup so readiness can report DOWN
            //      independently of liveness -- is deliberately NOT taken; it would trade a boot
            //      refusal for a runtime rejection.
            //
            // Consequence to state plainly: the readiness-detail redaction is defence-in-depth
            // against a future relaxation of eager assembly, NOT closure of a live exposure.
            //
            // The operator gets the cause through the log; the wire gets a fixed token. See
            // ERROR_VALIDATION_UNAVAILABLE for why the raw message must not travel on this payload.
            LOGGER.warn(failure, ConfigLogMessages.WARN.READINESS_VALIDATION_UNAVAILABLE);
            builder.withData(DATA_JWKS, "unavailable")
                    .withData(DATA_ERROR, ERROR_VALIDATION_UNAVAILABLE);
            if (serverMode) {
                builder.withData(DATA_ISSUER_REACHABILITY, ISSUER_UNREACHABLE);
            }
            return builder.down().build();
        }
    }

    /**
     * @return {@code true} when the gateway runs a BFF {@code oidc.session.mode: server} deployment,
     *         so issuer reachability is reported as part of readiness
     */
    private boolean isServerSessionMode() {
        // The SHARED predicate on the config model, identical to the one boot validation, the edge
        // cap and the runtime binding selection read — never a locally-declared constant.
        OidcConfig oidc = gatewayConfig.oidc();
        OidcConfig.Session session = oidc == null ? null : oidc.session();
        return session != null && session.isServerMode();
    }
}
