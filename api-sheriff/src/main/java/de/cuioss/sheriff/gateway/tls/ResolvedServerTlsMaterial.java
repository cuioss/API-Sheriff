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

import java.util.Optional;


import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The single discriminator both server-TLS audits ask: does <em>resolved</em> key material reach a
 * listener, or does it end up on plain HTTP?
 * <p>
 * <strong>What it mirrors, and against which version.</strong> This is a faithful re-derivation of
 * {@code io.quarkus.vertx.http.runtime.options.HttpServerOptionsUtils#getTlsConfiguration(Optional,
 * TlsConfigurationRegistry)} (quarkus-vertx-http <strong>3.39.2</strong>, lines 112-129) together
 * with the leg its two callers add — {@code createSslOptions} (lines 72-110) for the main listener
 * and {@code createSslOptionsForManagementInterface} (lines 192-228) for the management interface.
 * Both callers use the same helper and then fall through to the same legacy
 * {@code ssl.certificate.*} block, which is why one discriminator serves both listeners. The
 * upstream method and its version are named here deliberately: this file is the one place the logic
 * lives, so an upstream change is traceable to exactly one site rather than to two approximate
 * copies (the state this class was extracted to end).
 * <p>
 * <strong>The three legs, in the order the recorder evaluates them.</strong>
 * <ol>
 *   <li>A present {@code tls-configuration-name} selects that named bucket, and the bucket decides
 *       on {@code getKeyStoreOptions() == null}. Selecting a key-less bucket <em>replaces</em> the
 *       deployment's certificate rather than adding to it, so a named-but-empty bucket lands on
 *       plain HTTP even when the legacy certificate keys are set.</li>
 *   <li>Otherwise the registry's default bucket decides, but <strong>only when it carries key
 *       material</strong> — upstream takes this fallback under
 *       {@code registry.getDefault().get().getKeyStoreOptions() != null} and otherwise leaves the
 *       bucket null. This is the leg {@link TlsConfiguration#from(TlsConfigurationRegistry,
 *       Optional)} omits: that SPI helper returns empty for an absent name and never consults the
 *       default bucket at all, so an audit built on it reports plain HTTP for a listener that a
 *       populated default {@code quarkus.tls.key-store.*} bucket has silently put on HTTPS. That
 *       false positive is the reason this class exists.</li>
 *   <li>Otherwise the legacy {@code ssl.certificate.*} block decides.</li>
 * </ol>
 * <p>
 * <strong>Two deliberate divergences from upstream, both narrowing rather than widening.</strong>
 * <ul>
 *   <li><em>A named bucket that does not resolve reports plain HTTP; it does not throw.</em>
 *       Upstream raises a {@code ConfigurationException} naming the missing
 *       {@code quarkus.tls.&lt;name&gt;} property, which fails the boot outright — before any
 *       {@code StartupEvent} observer runs. An audit that reached that state would therefore be
 *       throwing a second time over an already-failed boot, so the unresolvable name is reported as
 *       "no key material" instead. The loud failure is upstream's to raise, and it still is.</li>
 *   <li><em>Leg 3 reads a declared key, not resolved material.</em> Legs 1 and 2 read the
 *       {@code KeyCertOptions} the registry actually resolved. Leg 3 cannot: the legacy block is
 *       consumed inside {@code createSslOptions} through {@code TlsUtils.computeKeyStoreOptions}
 *       and never lands in the registry, so the caller passes the presence of a declared certificate
 *       key as a proxy — across <em>every</em> spelling the block accepts
 *       ({@code de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys}), since a keystore file
 *       and a credentials provider terminate TLS exactly as a PEM chain does. The reduction is
 *       one-directional — a declared chain that fails to load fails the boot rather than reaching a
 *       listener — so this leg cannot manufacture a false "HTTPS" verdict for a listener that is
 *       actually plain.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
final class ResolvedServerTlsMaterial {

    /**
     * Decides whether a listener resolves to plain HTTP, evaluating the three legs in the order the
     * recorder evaluates them.
     *
     * @param registry              the live TLS registry, resolved exactly as the recorder resolves
     *                              it
     * @param tlsConfigurationName  the selected named TLS bucket, {@code null} when the deployment
     *                              selects none
     * @param certificateConfigured whether the listener's legacy {@code ssl.certificate.*} block
     *                              supplies a certificate chain
     * @return {@code true} when no key material reaches the listener, so it serves plain HTTP
     */
    static boolean resolvesToPlainHttp(TlsConfigurationRegistry registry,
            @Nullable String tlsConfigurationName, boolean certificateConfigured) {
        if (tlsConfigurationName != null) {
            Optional<TlsConfiguration> named = registry.get(tlsConfigurationName);
            return named.isEmpty() || named.get().getKeyStoreOptions() == null;
        }
        if (defaultBucketCarriesKeyMaterial(registry)) {
            return false;
        }
        return !certificateConfigured;
    }

    /**
     * Reports whether the registry's default bucket carries key material, which is the only
     * condition under which the recorder takes the default-bucket fallback.
     *
     * @param registry the live TLS registry
     * @return {@code true} when a default bucket exists and carries key material
     */
    private static boolean defaultBucketCarriesKeyMaterial(TlsConfigurationRegistry registry) {
        Optional<TlsConfiguration> defaultBucket = registry.getDefault();
        return defaultBucket.isPresent() && defaultBucket.get().getKeyStoreOptions() != null;
    }
}
