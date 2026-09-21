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
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus;
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
 * SmallRye {@code @Readiness} probe served on the management port, reporting whether the gateway is
 * ready to serve traffic.
 * <p>
 * The probe's path is not fixed here, and WHICH key places it depends on
 * {@code quarkus.management.enabled}. While the management interface is enabled — which every shipped
 * deployment is, because {@code src/main/resources/application.properties} pins
 * {@code quarkus.management.enabled=true} — every non-application route is served by the MANAGEMENT
 * router, so readiness is served beneath the configured management root path,
 * {@code quarkus.management.root-path} (default {@code /q}), at {@code health/ready} under it, on the
 * management port; {@code quarkus.http.non-application-root-path} governs nothing there. Set
 * {@code quarkus.management.enabled=false} and the non-application endpoints move back onto the MAIN
 * HTTP port, where readiness is served at
 * <code>{quarkus.http.root-path}/{quarkus.http.non-application-root-path}/health/ready</code> instead —
 * a different port and a different path. Whichever route applies, the placing keys are build-time
 * fixed, so a deployment that moves the context path rebuilds the image rather than setting an
 * environment variable — see {@code doc/user/context-path.adoc}, and block (d) of
 * {@code application.properties} for the same two-mode routing stated at its source.
 * <p>
 * Readiness reflects two facts, per {@code architecture.adoc} § Metrics (Health):
 * <ul>
 *   <li><strong>Configuration</strong> — the {@link GatewayConfig} bean is present, which proves
 *       the boot-time load-and-validate pipeline in {@link ConfigProducer} succeeded (an invalid
 *       configuration aborts startup, so the application would never reach readiness with an
 *       unbound config);</li>
 *   <li><strong>JWKS</strong> — when a {@code token_validation} block is configured, the gateway's
 *       own {@link GatewayValidator}-qualified {@link TokenValidator} resolves AND every configured
 *       issuer has a loaded key set. JWKS fetching is lazy and asynchronous, so a resolved validator
 *       alone proves nothing about the keys: the probe additionally reads the
 *       {@link IssuerKeySetStatus} — a <em>non-fetching</em> per-issuer view over the gateway-owned
 *       loaders, so polling the probe never causes a JWKS request. When every issuer is loaded the
 *       probe reports {@code jwks: ready} and {@code UP}. Otherwise it reports {@code DOWN} with
 *       {@code jwks: loading} (no load attempt has produced a key set yet) or
 *       {@code jwks: unavailable} (at least one issuer's last attempt failed), together with the
 *       bounded count {@code issuers_loaded} beside {@code issuers}. A validator whose assembly
 *       fails ({@link GatewayException}) is reported {@code DOWN} through the same renderer with a
 *       fixed status token (see {@link #ERROR_VALIDATION_UNAVAILABLE}). No DOWN payload names an
 *       issuer, a URL, a hostname or a cause; causes reach the operator through the log.
 *       A gateway with no {@code token_validation} block needs no bearer validation, so JWKS is
 *       reported {@code not-applicable} and does not gate readiness.</li>
 *   <li><strong>Issuer reachability (mode: server)</strong> — when the gateway runs a BFF
 *       {@code oidc.session.mode: server} deployment, the probe adds an {@code oidc=server} datum and
 *       an {@code issuer_reachability} datum that is <em>always</em> {@code unverified}. The probe
 *       has no live reachability signal and deliberately sends no network request, and the key-set
 *       state is not one: once an issuer's key set has loaded it stays loaded through later refresh
 *       failures, so a loaded key set would claim {@code reachable} for an issuer that has since gone
 *       offline, and a missing one would claim {@code unreachable} for an issuer that answered with
 *       unusable keys. What the key-set state <em>does</em> prove is reported by the {@code jwks}
 *       and {@code issuers_loaded} data above, which drive the verdict unchanged. The
 *       confidential-client engine reaches the issuer lazily on the first login, which does not gate
 *       boot readiness.</li>
 * </ul>
 * The validator and the key-set view are resolved lazily through {@link Instance}s, together and
 * only on the {@code token_validation} leg: the view is published by the validator's assembly, so a
 * gateway without bearer validation never needs it, and a misconfigured JWKS source yields a clean
 * {@code DOWN} response rather than failing this probe's own construction.
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
    private static final String DATA_ISSUERS_LOADED = "issuers_loaded";
    private static final String DATA_ERROR = "error";

    private static final String JWKS_READY = "ready";
    /** At least one issuer has no key set yet and none of the pending issuers' attempts has failed. */
    private static final String JWKS_LOADING = "loading";
    /** At least one issuer's most recent load attempt failed, or the validator could not be built. */
    private static final String JWKS_UNAVAILABLE = "unavailable";
    private static final String DATA_OIDC = "oidc";
    private static final String DATA_ISSUER_REACHABILITY = "issuer_reachability";

    /** The readiness payload's mode label — the same canonical spelling the config model owns. */
    private static final String MODE_SERVER = OidcConfig.Session.MODE_SERVER;
    /**
     * The only value the {@code issuer_reachability} datum carries: readiness holds no live
     * reachability signal, and cached key-set state is not one (see the class Javadoc).
     */
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
    private final Instance<IssuerKeySetStatus> issuerKeySetStatus;

    /**
     * @param gatewayConfig      the bound, boot-validated gateway document
     * @param gatewayValidator   the lazily-resolved gateway bearer-token validator
     * @param issuerKeySetStatus the lazily-resolved, non-fetching per-issuer key-set view published by
     *                           the validator's assembly; resolved only when {@code token_validation}
     *                           is configured
     */
    @Inject
    public GatewayReadinessCheck(GatewayConfig gatewayConfig,
            @GatewayValidator Instance<TokenValidator> gatewayValidator,
            Instance<IssuerKeySetStatus> issuerKeySetStatus) {
        this.gatewayConfig = gatewayConfig;
        this.gatewayValidator = gatewayValidator;
        this.issuerKeySetStatus = issuerKeySetStatus;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code UP} when the configuration is bound and — if bearer validation is configured —
     *         the JWKS-backed validator resolves and every configured issuer has a loaded key set;
     *         {@code DOWN} carrying fixed, non-disclosing status tokens and counts otherwise (any
     *         failure cause is logged, never returned)
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

        if (isServerSessionMode()) {
            // Reachability is never inferred from key-set state: a loaded key set survives later refresh
            // failures and a missing one says nothing about the network. With no live signal to report,
            // the datum says so, on UP and DOWN alike.
            builder.withData(DATA_OIDC, MODE_SERVER)
                    .withData(DATA_ISSUER_REACHABILITY, ISSUER_UNVERIFIED);
        }

        TokenValidationConfig tokenValidation = gatewayConfig.tokenValidation();
        if (tokenValidation == null) {
            // No bearer validation configured, so there is no JWKS-backed validation health check to
            // reuse, and nothing here gates readiness.
            return builder.withData(DATA_JWKS, "not-applicable").up().build();
        }

        builder.withData(DATA_ISSUERS, tokenValidation.issuers().size());
        IssuerKeySetStatus keySets;
        try {
            gatewayValidator.get();
            keySets = issuerKeySetStatus.get();
        } catch (GatewayException | CreationException failure) {
            // The CONSTRUCTION-FAILURE leg — not reached in the shipped eager-boot topology, and
            // deliberately retained. TokenValidatorProducer.onStartup forces the validator (and with
            // it the key-set view) into existence at StartupEvent, so a construction-failing JWKS
            // source aborts boot non-zero and this probe is never called. The eager-boot coupling is
            // a fail-CLOSED security property (ADR-0027): a misconfigured issuer must abort startup
            // rather than let the gateway serve traffic and fail at the first bearer request.
            //
            // Kept because removing the catch would not remove the failure path, only move its
            // rendering OUT of this class: an escaping exception would be rendered by SmallRye's own
            // check-failure handling, outside the gateway's control and without the fixed-token
            // redaction below, on a payload served by an interface that may be plain HTTP. The
            // redaction is therefore defence-in-depth against a future relaxation of eager assembly.
            //
            // The LIVE key-set leg below IS reachable: a validator that assembled cleanly still has
            // no keys until each issuer's asynchronous first fetch succeeds, and that leg shares the
            // same DOWN renderer. The operator gets the cause through the log; the wire gets a fixed
            // token. See ERROR_VALIDATION_UNAVAILABLE for why the raw message must not travel here.
            LOGGER.warn(failure, ConfigLogMessages.WARN.READINESS_VALIDATION_UNAVAILABLE);
            builder.withData(DATA_ERROR, ERROR_VALIDATION_UNAVAILABLE);
            return down(builder, JWKS_UNAVAILABLE);
        }

        // One read of the loaded count drives both the datum and the verdict, so the payload can
        // never report UP beside an issuers_loaded below issuers. Every read is non-fetching.
        int loaded = keySets.loadedCount();
        builder.withData(DATA_ISSUERS_LOADED, loaded);
        if (loaded == keySets.configuredCount()) {
            return builder.withData(DATA_JWKS, JWKS_READY).up().build();
        }
        return down(builder, keySets.failedCount() > 0 ? JWKS_UNAVAILABLE : JWKS_LOADING);
    }

    /**
     * The single DOWN renderer both the construction-failure leg and the live key-set leg use: a fixed
     * {@code jwks} status token beside whatever fixed data the caller already gathered — never an
     * issuer name, URL, hostname or cause. It adds no reachability claim; a server-mode payload keeps
     * the {@code unverified} datum recorded before either leg ran.
     *
     * @param builder    the response builder carrying the data gathered so far
     * @param jwksStatus the fixed {@code jwks} status token
     * @return the {@code DOWN} response
     */
    private static HealthCheckResponse down(HealthCheckResponseBuilder builder, String jwksStatus) {
        return builder.withData(DATA_JWKS, jwksStatus).down().build();
    }

    /**
     * @return {@code true} when the gateway runs a BFF {@code oidc.session.mode: server} deployment,
     *         so the {@code oidc} and {@code issuer_reachability} data are part of readiness
     */
    private boolean isServerSessionMode() {
        // The SHARED predicate on the config model, identical to the one boot validation, the edge
        // cap and the runtime binding selection read — never a locally-declared constant.
        OidcConfig oidc = gatewayConfig.oidc();
        OidcConfig.Session session = oidc == null ? null : oidc.session();
        return session != null && session.isServerMode();
    }
}
