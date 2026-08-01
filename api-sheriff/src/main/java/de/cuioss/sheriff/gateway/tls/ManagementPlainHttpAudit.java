/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Audits, at startup, whether the management interface actually resolved to plain HTTP, and emits
 * {@code WARN ApiSheriff-115} when it did.
 * <p>
 * <strong>It audits the observed effective state, never a declared intention.</strong> The trigger is
 * the TLS material the management listener really resolved — the same question
 * {@code VertxHttpRecorder.initializeManagementInterface} asks when it decides between the SSL and
 * the plain options — rather than any single configuration key that is believed to cause a downgrade.
 * A configuration key can be renamed, superseded, or silently ignored, and an audit keyed on one
 * would then report a comfortable fiction. The resolved key material cannot: if there is none, the
 * port is plain, whatever route got it there.
 * <p>
 * <strong>Why a WARN and never a boot refusal.</strong> A plain-HTTP management port behind a trusted
 * network boundary is a legitimate deployment, and the gateway must not block it. But because Quarkus'
 * management configuration declares no {@code ssl-port} and no {@code insecure-requests} key, the
 * management interface has exactly one port: the downgrade takes health <em>and</em> metrics in their
 * entirety, and every consumer probing that port over HTTPS breaks. That is worth saying loudly once,
 * at boot, in the log an operator actually reads.
 * <p>
 * <strong>Lazy-proxy hazard (lesson 2026-07-20-18-002).</strong> A normal-scoped observer bean that
 * merely <em>holds</em> an injected collaborator gets a lazy CDI proxy that is never touched, so the
 * check silently never runs while unit tests calling the method directly stay green. The observer
 * here therefore actively invokes {@link #auditManagementTls()}, which in turn actively calls into the
 * injected {@link TlsConfigurationRegistry} — and the accompanying test asserts the warning fires
 * through the real startup-event path, not by direct invocation.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ManagementPlainHttpAudit {

    private static final CuiLogger LOGGER = new CuiLogger(ManagementPlainHttpAudit.class);

    private final TlsConfigurationRegistry registry;
    private final Optional<String> tlsConfigurationName;
    private final boolean managementCertificateConfigured;
    private final int managementPort;

    /**
     * @param registry             the live TLS registry, resolved exactly as the management recorder
     *                             resolves it
     * @param tlsConfigurationName the selected named TLS bucket, empty when the deployment selects
     *                             none
     * @param certificateFiles     the management certificate chain, empty when the deployment
     *                             supplies none
     * @param managementPort       the management port, reported in the warning
     */
    @Inject
    public ManagementPlainHttpAudit(
            TlsConfigurationRegistry registry,
            @ConfigProperty(name = "quarkus.management.tls-configuration-name") Optional<String> tlsConfigurationName,
            @ConfigProperty(name = "quarkus.management.ssl.certificate.files") Optional<List<String>> certificateFiles,
            @ConfigProperty(name = "quarkus.management.port", defaultValue = "9000") int managementPort) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tlsConfigurationName = Objects.requireNonNull(tlsConfigurationName, "tlsConfigurationName");
        this.managementCertificateConfigured = certificateFiles.filter(files -> !files.isEmpty()).isPresent();
        this.managementPort = managementPort;
    }

    /**
     * Runs the audit on the real startup event.
     * <p>
     * The observer actively invokes {@link #auditManagementTls()} rather than delegating to a
     * collaborator's lifecycle callback; see the class documentation for the lazy-proxy hazard this
     * defeats.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        auditManagementTls();
    }

    /**
     * Resolves the management interface's effective TLS state and warns when it is plain HTTP.
     *
     * @return {@code true} when the management interface resolved to plain HTTP
     */
    public boolean auditManagementTls() {
        Optional<TlsConfiguration> resolved = TlsConfiguration.from(registry, tlsConfigurationName);
        boolean plain = resolvesToPlainHttp(resolved, managementCertificateConfigured);
        if (plain) {
            LOGGER.warn(ConfigLogMessages.WARN.MANAGEMENT_PLAIN_HTTP, managementPort);
        } else {
            LOGGER.debug("Management interface resolved to HTTPS on port %s", managementPort);
        }
        return plain;
    }

    /**
     * Decides whether the resolved TLS state leaves the management listener on plain HTTP, mirroring
     * the recorder's own test: a selected TLS configuration that carries no key material replaces the
     * deployment's certificate rather than adding to it, so the listener ends up with no key/cert and
     * the recorder swaps in the plain options.
     *
     * @param resolved               the TLS configuration the management interface resolved to, empty
     *                               when none applies
     * @param certificateConfigured  whether {@code quarkus.management.ssl.certificate.files} supplies
     *                               a chain
     * @return {@code true} when no key material reaches the management listener
     */
    static boolean resolvesToPlainHttp(Optional<TlsConfiguration> resolved, boolean certificateConfigured) {
        if (resolved.isPresent()) {
            return resolved.get().getKeyStoreOptions() == null;
        }
        return !certificateConfigured;
    }
}
