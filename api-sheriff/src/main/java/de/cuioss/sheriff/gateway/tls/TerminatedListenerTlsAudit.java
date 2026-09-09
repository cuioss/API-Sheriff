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
package de.cuioss.sheriff.gateway.tls;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Audits, at startup, whether the terminated <em>main</em> listener actually resolved to plain HTTP,
 * and emits {@code WARN ApiSheriff-121} when it did.
 * <p>
 * This is the main-listener counterpart of {@link ManagementPlainHttpAudit}, and it deliberately
 * extends that one pattern rather than inventing a second: same {@code @ApplicationScoped} shape,
 * same actively-invoking startup observer, same verdict keyed on resolved material. Both audits ask
 * their question through the one shared discriminator, {@link ResolvedServerTlsMaterial}, so there
 * is no second copy of the resolution logic that could drift.
 * <p>
 * <strong>It audits resolved key material, never a declared intention.</strong> The trigger is the
 * TLS material the main listener really resolved — the same question
 * {@code VertxHttpRecorder.initializeMainHttpServer} asks when it decides between the SSL and the
 * plain options. A configuration key can be renamed, superseded, or silently ignored, and an audit
 * keyed on one would then report a comfortable fiction; resolved material cannot.
 * <p>
 * <strong>This audit is the authoritative report where declared and resolved disagree.</strong> The
 * no-certificate plain-HTTP degrade is keyed on <em>declared</em> key material, because the seam it
 * must act at runs while the configuration system is still being built and the
 * {@link TlsConfigurationRegistry} does not yet exist. This audit is keyed on <em>resolved</em> key
 * material. The two can disagree — a declared path that resolves to no usable key, or an
 * {@code HttpServerOptionsCustomizer} that supplies material the configuration never declared — and
 * where they do, what this audit reports is what the listener is actually doing.
 * <p>
 * <strong>Why a WARN and never a boot refusal.</strong> A plain-HTTP main listener is a legitimate
 * deployment behind a TLS-terminating boundary, and the gateway must not block it. What must not
 * happen is that it arrives silently: every HTTPS client of the gateway fails against a listener
 * that quietly stopped terminating TLS, so this is worth saying loudly once, at boot, in the log an
 * operator actually reads.
 * <p>
 * <strong>Lazy-proxy hazard (lesson 2026-07-20-18-002).</strong> A normal-scoped observer bean that
 * merely <em>holds</em> an injected collaborator gets a lazy CDI proxy that is never touched, so the
 * check silently never runs while unit tests calling the method directly stay green. The observer
 * here therefore actively invokes {@link #auditTerminatedListenerTls()}, which in turn actively
 * calls into both the injected {@link TlsConfigurationRegistry} and the injected
 * {@link GatewayConfig} — and the accompanying test asserts the warning fires through the real
 * startup-event path, not by direct invocation.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TerminatedListenerTlsAudit {

    private static final CuiLogger LOGGER = new CuiLogger(TerminatedListenerTlsAudit.class);

    /**
     * The ADR-0017 default topology: no accept-time front listener, so the terminated Quarkus
     * listener owns the public port itself.
     */
    private static final String TOPOLOGY_SINGLE_LISTENER =
            "single terminated listener (ADR-0017 default topology)";

    /**
     * The ADR-0017 split topology: {@code tls.passthrough_sni} is declared, so the accept-time SNI
     * front listener owns the public port and this terminated listener sits behind it.
     */
    private static final String TOPOLOGY_PASSTHROUGH_SPLIT =
            "SNI passthrough split (ADR-0017) — the accept-time front listener owns the public port "
                    + "and hands unmatched connections to this terminated listener";

    private final TlsConfigurationRegistry registry;
    private final GatewayConfig gatewayConfig;
    private final @Nullable String tlsConfigurationName;
    private final boolean certificateConfigured;
    private final int httpPort;
    private final int httpsPort;

    /**
     * @param registry             the live TLS registry, resolved exactly as the main-listener
     *                             recorder resolves it
     * @param gatewayConfig        the bound global gateway document, read for the live ADR-0017
     *                             topology — the same {@code tls.passthrough_sni} signal
     *                             {@link TlsEdgeProducer} decides on
     * @param tlsConfigurationName the selected named TLS bucket, empty when the deployment selects
     *                             none
     * @param certificateFiles     the main listener's certificate chain, empty when the deployment
     *                             supplies none
     * @param httpPort             the plain HTTP port, reported in the warning
     * @param httpsPort            the terminated HTTPS port, reported on the quiet path
     */
    @Inject
    public TerminatedListenerTlsAudit(
            TlsConfigurationRegistry registry,
            GatewayConfig gatewayConfig,
            @ConfigProperty(name = "quarkus.http.tls-configuration-name") Optional<String> tlsConfigurationName,
            @ConfigProperty(name = "quarkus.http.ssl.certificate.files") Optional<List<String>> certificateFiles,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080") int httpPort,
            @ConfigProperty(name = "quarkus.http.ssl-port", defaultValue = "8443") int httpsPort) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.tlsConfigurationName = Objects.requireNonNull(tlsConfigurationName, "tlsConfigurationName")
                .orElse(null);
        this.certificateConfigured = certificateFiles.filter(files -> !files.isEmpty()).isPresent();
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
    }

    /**
     * Runs the audit on the real startup event.
     * <p>
     * The observer actively invokes {@link #auditTerminatedListenerTls()} rather than delegating to a
     * collaborator's lifecycle callback; see the class documentation for the lazy-proxy hazard this
     * defeats.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        auditTerminatedListenerTls();
    }

    /**
     * Resolves the terminated main listener's effective TLS state and warns when it is plain HTTP.
     *
     * @return {@code true} when the terminated main listener resolved to plain HTTP
     */
    public boolean auditTerminatedListenerTls() {
        boolean plain = ResolvedServerTlsMaterial.resolvesToPlainHttp(registry,
                tlsConfigurationName, certificateConfigured);
        String topology = resolveTopology();
        if (plain) {
            LOGGER.warn(ConfigLogMessages.WARN.TERMINATED_LISTENER_PLAIN_HTTP, httpPort, topology);
        } else {
            LOGGER.debug("Terminated main listener resolved to HTTPS on port %s — topology: %s",
                    httpsPort, topology);
        }
        return plain;
    }

    /**
     * Reports which ADR-0017 edge topology is live, read from the same {@code tls.passthrough_sni}
     * signal {@link TlsEdgeProducer} decides the front listener on.
     *
     * @return the live topology, named for the operator
     */
    private String resolveTopology() {
        TlsConfig tls = gatewayConfig.tls();
        Map<String, String> passthrough = tls == null ? Map.of() : tls.passthroughSni();
        return passthrough.isEmpty() ? TOPOLOGY_SINGLE_LISTENER : TOPOLOGY_PASSTHROUGH_SPLIT;
    }
}
