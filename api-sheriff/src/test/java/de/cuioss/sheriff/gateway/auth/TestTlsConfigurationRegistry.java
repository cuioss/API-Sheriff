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

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;

import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;

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
     * @return a registry defining {@code name} against a usable SSL context
     */
    static TestTlsConfigurationRegistry with(String name) {
        TestTlsConfigurationRegistry registry = new TestTlsConfigurationRegistry();
        registry.register(name, new UsableTlsConfiguration(registry.profileContext));
        return registry;
    }

    /**
     * @param name the logical profile name to define
     * @return a registry where {@code name} exists but its trust material cannot be loaded
     */
    static TestTlsConfigurationRegistry withBrokenMaterial(String name) {
        TestTlsConfigurationRegistry registry = new TestTlsConfigurationRegistry();
        registry.register(name, new BrokenTlsConfiguration());
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

    /** A profile whose trust material resolves to a real, usable context. */
    private static final class UsableTlsConfiguration extends BaseTlsConfiguration {

        private final SSLContext context;

        private UsableTlsConfiguration(SSLContext context) {
            this.context = context;
        }

        @Override
        public SSLContext createSSLContext() {
            return context;
        }
    }

    /** A profile that is defined but whose trust material fails to load — an unreadable or
     * wrong-password trust store in a real deployment. */
    private static final class BrokenTlsConfiguration extends BaseTlsConfiguration {

        @Override
        public SSLContext createSSLContext() {
            throw new IllegalStateException("trust store could not be read");
        }
    }
}
