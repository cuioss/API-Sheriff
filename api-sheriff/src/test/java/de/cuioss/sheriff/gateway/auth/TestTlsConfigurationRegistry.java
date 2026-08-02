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
package de.cuioss.sheriff.gateway.auth;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.TrustOptions;

/**
 * An in-memory {@link TlsConfigurationRegistry} standing in for the runtime's registry, so the
 * trust-profile mapping can be exercised without booting Quarkus.
 * <p>
 * Hand-written rather than generated: mocking frameworks are not used in this project, and the
 * registry contract is small enough that a literal implementation is clearer than a stubbing DSL.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class TestTlsConfigurationRegistry implements TlsConfigurationRegistry {

    private final Map<String, TlsConfiguration> byName = new HashMap<>();
    private final SSLContext profileContext;

    private TestTlsConfigurationRegistry() {
        this.profileContext = freshContext();
    }

    /**
     * @return a registry that defines no profile at all — the shape a deployment has when the
     *         operator names a profile nobody bound
     */
    static TestTlsConfigurationRegistry empty() {
        return new TestTlsConfigurationRegistry();
    }

    /**
     * @param name the logical profile name to define
     * @return a registry defining {@code name} against trust material and a usable SSL context —
     *         the shape a correctly bound deployment has
     */
    static TestTlsConfigurationRegistry with(String name) {
        return usable(name, trustStore(), null);
    }

    /**
     * The runtime may express the same anchors as Vert.x {@link TrustOptions} rather than as a
     * loaded {@link KeyStore} — a PEM-bound bucket is the common case. Both count as material, so
     * the resolver must accept this shape too.
     *
     * @param name the logical profile name to define
     * @return a registry where {@code name} carries trust material only as {@link TrustOptions}
     */
    static TestTlsConfigurationRegistry withTrustOptionsOnly(String name) {
        return usable(name, null, trustOptions());
    }

    /**
     * The shape a bucket has when the deployment declares {@code quarkus.tls.<name>.*} without any
     * anchors — the gateway ships exactly such a bucket for the plain-HTTP management marker, so
     * this is reachable by naming the wrong profile rather than by exotic misconfiguration.
     *
     * @param name the logical profile name to define
     * @return a registry where {@code name} resolves but carries no trust material at all
     */
    static TestTlsConfigurationRegistry withoutTrustMaterial(String name) {
        return usable(name, null, null);
    }

    /**
     * The shape a bucket has when the deployment sets {@code quarkus.tls.<name>.trust-all}. The
     * runtime binds such a bucket to a trust-everything {@link TrustOptions}, so it carries material
     * by the anchor-free guard's measure while verifying nothing — which is why the resolver must
     * refuse it on its own signal rather than on the absence of anchors.
     *
     * @param name the logical profile name to define
     * @return a registry where {@code name} resolves to a verification-disabled bucket
     */
    static TestTlsConfigurationRegistry withTrustAll(String name) {
        TestTlsConfigurationRegistry registry = new TestTlsConfigurationRegistry();
        registry.register(name, new TrustAllTlsConfiguration(registry.profileContext));
        return registry;
    }

    /**
     * @param name the logical profile name to define
     * @return a registry where {@code name} carries trust material that cannot be loaded
     */
    static TestTlsConfigurationRegistry withBrokenMaterial(String name) {
        TestTlsConfigurationRegistry registry = new TestTlsConfigurationRegistry();
        registry.register(name, new BrokenTlsConfiguration());
        return registry;
    }

    /**
     * Shared body of the three usable-profile factories above. They differ only in which of the two
     * trust-material shapes they supply — including neither, which is the anchor-free bucket — so the
     * distinct scenario NAMES stay, and only the construct-register-return triplet is shared.
     *
     * @param name         the logical profile name to define
     * @param trustStore   the loaded trust store, or {@code null} when this shape is not supplied
     * @param trustOptions the Vert.x trust options, or {@code null} when this shape is not supplied
     * @return a registry defining {@code name} against a usable SSL context and the given material
     */
    private static TestTlsConfigurationRegistry usable(String name, KeyStore trustStore, TrustOptions trustOptions) {
        TestTlsConfigurationRegistry registry = new TestTlsConfigurationRegistry();
        registry.register(name, new UsableTlsConfiguration(registry.profileContext, trustStore, trustOptions));
        return registry;
    }

    /**
     * The exact instance this registry's profile resolves to. Tests assert on identity rather than
     * mere non-nullness, because the JWKS client fabricates its own default context when no
     * profile is applied — a non-null assertion would pass even if the mapping never ran.
     *
     * @return the SSL context bound to the defined profile
     */
    SSLContext profileContext() {
        return profileContext;
    }

    @Override
    public Optional<TlsConfiguration> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public Optional<TlsConfiguration> getDefault() {
        return Optional.empty();
    }

    @Override
    public void register(String name, TlsConfiguration configuration) {
        byName.put(name, configuration);
    }

    private static SSLContext freshContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLSv1.3 must be available to the test JVM", e);
        }
    }

    /**
     * A loaded, empty trust store. Presence is what the resolver checks — the seam refuses a bucket
     * that carries no anchors at all, and never inspects which anchors a bound bucket holds — so an
     * empty store is the faithful stand-in for "the deployment supplied material".
     *
     * @return a trust store instance the resolver accepts as material
     */
    private static KeyStore trustStore() {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("PKCS12 must be available to the test JVM", e);
        }
    }

    /**
     * @return the same material expressed the way a PEM-bound bucket expresses it
     */
    private static TrustOptions trustOptions() {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore());
            return TrustOptions.wrap(factory);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the default trust-manager algorithm must be available", e);
        }
    }

    /**
     * A profile whose trust material — in either of the two shapes the runtime exposes — resolves
     * to a real, usable context. Passing {@code null} for both shapes models a bound bucket that
     * carries no anchors.
     */
    private static final class UsableTlsConfiguration extends BaseTlsConfiguration {

        private final SSLContext context;
        private final KeyStore trustStore;
        private final TrustOptions trustOptions;

        private UsableTlsConfiguration(SSLContext context, KeyStore trustStore, TrustOptions trustOptions) {
            this.context = context;
            this.trustStore = trustStore;
            this.trustOptions = trustOptions;
        }

        @Override
        public KeyStore getTrustStore() {
            return trustStore;
        }

        @Override
        public TrustOptions getTrustStoreOptions() {
            return trustOptions;
        }

        @Override
        public SSLContext createSSLContext() {
            return context;
        }
    }

    /**
     * A profile with verification disabled. It reports trust options — the trust-everything shape
     * the runtime installs for {@code trust-all} — precisely so a test using it proves the resolver
     * refuses on {@link TlsConfiguration#isTrustAll()} and not merely on missing anchors.
     */
    private static final class TrustAllTlsConfiguration extends BaseTlsConfiguration {

        private final SSLContext context;

        private TrustAllTlsConfiguration(SSLContext context) {
            this.context = context;
        }

        @Override
        public boolean isTrustAll() {
            return true;
        }

        @Override
        public TrustOptions getTrustStoreOptions() {
            return trustOptions();
        }

        @Override
        public SSLContext createSSLContext() {
            return context;
        }
    }

    /** A profile that carries trust material but fails to load it — an unreadable or
     * wrong-password trust store in a real deployment. */
    private static final class BrokenTlsConfiguration extends BaseTlsConfiguration {

        private final KeyStore trustStore = trustStore();

        @Override
        public KeyStore getTrustStore() {
            return trustStore;
        }

        @Override
        public SSLContext createSSLContext() {
            throw new IllegalStateException("trust store could not be read");
        }
    }
}
