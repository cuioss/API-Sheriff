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

import java.security.KeyStore;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.TrustOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Audits, at startup, which trust material the gateway's outbound legs actually verify against, and
 * emits {@code INFO ApiSheriff-17} for every deployment plus {@code WARN ApiSheriff-122} when an
 * operator-supplied store has replaced the platform bundle.
 * <p>
 * It extends the one pattern {@link ManagementPlainHttpAudit} and {@link TerminatedListenerTlsAudit}
 * established rather than inventing a second: same {@code @ApplicationScoped} shape, same
 * actively-invoking startup observer, same verdict keyed on resolved state rather than on a declared
 * key. Where those two audit the <em>server</em> side — the key material a listener resolved — this
 * one audits the <em>client</em> side: the anchors an outbound dial verifies a peer against.
 *
 * <h2>Why two tiers, and why reporting only one of them would be a fiction</h2>
 *
 * The default trust source is not one setting. Two independent levers reach two <strong>disjoint</strong>
 * sets of outbound legs, and neither one moves the other:
 * <ul>
 *   <li>The runtime {@code javax.net.ssl.trustStore} system property governs every leg that holds a
 *       raw JDK {@code TrustManager} — the confidential-client OIDC engine among them, which performs
 *       its discovery, token, refresh and logout calls with the JVM default {@code TrustManager} alone
 *       and exposes no per-client TLS-trust seam. No per-client TLS pin is attempted on that leg.</li>
 *   <li>The Quarkus {@code <default>} TLS bucket's trust material governs every leg that resolves
 *       through {@link TlsConfigurationRegistry}.</li>
 * </ul>
 * A deployment that sets one and not the other has silently moved half its outbound surface and left
 * the other half on the platform bundle. That divergence is the hazard this audit exists to surface,
 * so it reports both tiers separately and names the disagreement explicitly rather than collapsing
 * the two into a single reassuring line.
 *
 * <h2>Replacement, not extension</h2>
 *
 * Both levers <em>replace</em> the platform trust bundle rather than adding to it. An operator who
 * points either one at a store holding only their private certificate authority thereby stops
 * trusting every anchor that store does not carry, on the legs that lever governs. That is a
 * legitimate posture, so it is reported at {@code WARN} and never refused — but it must not arrive
 * silently, because the failure it produces surfaces much later as an opaque handshake rejection.
 *
 * <h2>What it reports, and what it must never report</h2>
 *
 * The records carry the store's <em>kind</em> and, for the system-property route, its path — a path
 * is an operator-supplied location rather than secret material. They never carry a password, an
 * alias, or any anchor bytes; the trust-store password system property is not read at all.
 *
 * <h2>Lazy-proxy hazard (lesson 2026-07-20-18-002)</h2>
 *
 * A normal-scoped observer bean that merely <em>holds</em> an injected collaborator gets a lazy CDI
 * proxy that is never touched, so the check silently never runs while unit tests calling the method
 * directly stay green. The observer here therefore actively invokes
 * {@link #auditDefaultTrustSource()}, which in turn actively calls into the injected
 * {@link TlsConfigurationRegistry}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class DefaultTrustSourceAudit {

    private static final CuiLogger LOGGER = new CuiLogger(DefaultTrustSourceAudit.class);

    /** The runtime system property naming the JVM default trust store. */
    static final String TRUST_STORE_PROPERTY = "javax.net.ssl.trustStore";

    /** The runtime system property naming that store's format; absent means the JDK default type. */
    static final String TRUST_STORE_TYPE_PROPERTY = "javax.net.ssl.trustStoreType";

    private static final String PLATFORM_BUNDLE_RAW_TIER =
            "platform default trust bundle (no javax.net.ssl.trustStore on the runtime command line)";

    private static final String PLATFORM_BUNDLE_REGISTRY_TIER =
            "platform default trust bundle (the <default> TLS bucket carries no trust material)";

    private static final String TIERS_AGREE =
            "Both tiers name an operator-supplied store, so the two halves of the outbound surface "
                    + "agree on their anchors.";

    private static final String ONLY_RAW_TIER_MOVED =
            "The two tiers DIVERGE: only the raw-TrustManager legs were moved, and every leg resolving "
                    + "through the TLS registry still verifies against the platform bundle.";

    private static final String ONLY_REGISTRY_TIER_MOVED =
            "The two tiers DIVERGE: only the registry-resolved legs were moved, and every leg holding "
                    + "a raw TrustManager — the confidential-client OIDC engine among them — still "
                    + "verifies against the platform bundle.";

    private final TlsConfigurationRegistry registry;

    /**
     * @param registry the live TLS registry, read for the {@code <default>} bucket's trust material
     *                 exactly as a registry-resolved outbound leg reads it
     */
    @Inject
    public DefaultTrustSourceAudit(TlsConfigurationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Runs the audit on the real startup event.
     * <p>
     * The observer actively invokes {@link #auditDefaultTrustSource()} rather than delegating to a
     * collaborator's lifecycle callback; see the class documentation for the lazy-proxy hazard this
     * defeats.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        auditDefaultTrustSource();
    }

    /**
     * Resolves the effective default trust source for both tiers, reports it, and warns when an
     * operator-supplied store has replaced the platform bundle on either one.
     *
     * @return {@code true} when an operator-supplied store is in effect on at least one tier
     */
    public boolean auditDefaultTrustSource() {
        Optional<String> rawTierStorePath = trimmedProperty(TRUST_STORE_PROPERTY);
        Optional<String> registryTierTrustKind = defaultBucketTrustKind();

        String rawTier = rawTierStorePath
                .map(path -> "operator-supplied store (kind=" + systemPropertyTrustStoreKind()
                        + ", path=" + path + ")")
                .orElse(PLATFORM_BUNDLE_RAW_TIER);
        String registryTier = registryTierTrustKind
                .map(kind -> "operator-supplied trust material (kind=" + kind + ")")
                .orElse(PLATFORM_BUNDLE_REGISTRY_TIER);

        LOGGER.info(ConfigLogMessages.INFO.DEFAULT_TRUST_SOURCE, rawTier, registryTier);

        boolean replaced = rawTierStorePath.isPresent() || registryTierTrustKind.isPresent();
        if (replaced) {
            LOGGER.warn(ConfigLogMessages.WARN.DEFAULT_TRUST_STORE_REPLACED, rawTier, registryTier,
                    tierAgreement(rawTierStorePath.isPresent(), registryTierTrustKind.isPresent()));
        }
        return replaced;
    }

    /**
     * Names whether the two tiers agree, and when they do not, which half of the outbound surface was
     * left behind on the platform bundle.
     *
     * @param rawTierMoved      whether the raw-TrustManager tier names an operator-supplied store
     * @param registryTierMoved whether the registry tier names operator-supplied trust material
     * @return the divergence sentence for the warning; only called when at least one tier moved
     */
    private static String tierAgreement(boolean rawTierMoved, boolean registryTierMoved) {
        if (rawTierMoved && registryTierMoved) {
            return TIERS_AGREE;
        }
        return rawTierMoved ? ONLY_RAW_TIER_MOVED : ONLY_REGISTRY_TIER_MOVED;
    }

    /**
     * @return the operator-supplied store's declared format, falling back to the JDK's own default
     *         type when the deployment names the store but not its kind
     */
    private static String systemPropertyTrustStoreKind() {
        return trimmedProperty(TRUST_STORE_TYPE_PROPERTY).orElseGet(KeyStore::getDefaultType);
    }

    /**
     * Reports the kind of trust material the registry's {@code <default>} bucket carries.
     * <p>
     * The bucket's material is resolved by the runtime before any {@code StartupEvent} observer runs,
     * so material that cannot be loaded has already failed the boot: reaching this point means the
     * bucket resolved, and there is no half-loaded state left for the audit to report.
     *
     * @return the trust material's kind, empty when the bucket is absent or carries none
     */
    private Optional<String> defaultBucketTrustKind() {
        return registry.getDefault()
                .map(TlsConfiguration::getTrustStoreOptions)
                .map(DefaultTrustSourceAudit::trustKindOf);
    }

    /**
     * Names the store format behind the resolved options in operator vocabulary rather than in the
     * runtime's own type names, falling back to the type name for a shape not enumerated here.
     *
     * @param options the resolved trust options
     * @return the store's kind
     */
    private static String trustKindOf(TrustOptions options) {
        return switch (options) {
            case PfxOptions _ -> "PKCS12";
            case JksOptions _ -> "JKS";
            case PemTrustOptions _ -> "PEM";
            default -> options.getClass().getSimpleName();
        };
    }

    /**
     * @param name the system-property name
     * @return the property's trimmed value, empty when it is unset or blank
     */
    private static Optional<String> trimmedProperty(String name) {
        return Optional.ofNullable(System.getProperty(name))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
