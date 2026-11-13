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
package de.cuioss.sheriff.gateway.testsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.gateway.tls.EgressTrustProfileResolver;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.TrustOptions;

/**
 * Builds the {@link EgressTrustProfileResolver} instances this module's {@code GatewayEdgeRoute}
 * fixtures pass, without booting Quarkus for a registry.
 *
 * <p>It lives in {@code testsupport} rather than inside any one test class because six fixtures
 * across five feature-package test classes construct a {@code GatewayEdgeRoute}, and all but one of
 * them names no {@code egress_tls.upstream_tls_profile} at all. Each of those needs the constructor
 * argument and nothing more, so a per-file registry stub would be the same twenty lines copied six
 * times — and six copies is six places for the shape to drift out of step with the resolver's
 * contract.
 *
 * <p>This is deliberately <em>not</em> named {@code Test*}: Surefire's default includes match
 * {@code **}{@code /Test*.java}, so that spelling would make the helper itself a test class.
 *
 * <p>Thread-safe: the class is stateless and every returned registry is freshly built.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EgressTrustProfiles {

    private EgressTrustProfiles() {
    }

    /**
     * A resolver over a registry defining nothing at all.
     *
     * <p>Intended for the fixtures whose gateway document names no trust profile: the boot path
     * never consults the resolver on that route, so an empty registry is the faithful stand-in. It
     * is empty rather than permissive on purpose — a change that started resolving a profile the
     * fixture never configured would fail loudly here instead of quietly binding anchors nobody
     * asked for.
     *
     * @return a resolver that refuses every name, and that a correct boot never calls
     */
    public static EgressTrustProfileResolver unconsulted() {
        return new EgressTrustProfileResolver(new StubRegistry());
    }

    /**
     * A resolver over a registry binding one logical name to the given anchors.
     *
     * @param profile the logical profile name the gateway document names
     * @param anchors the trust options the bound bucket exposes — returned by the resolver as-is, so
     *                a caller may assert on identity rather than on mere non-nullness
     * @return a resolver mapping {@code profile} to {@code anchors}
     */
    public static EgressTrustProfileResolver binding(String profile, TrustOptions anchors) {
        StubRegistry registry = new StubRegistry();
        registry.register(profile, new BoundTlsConfiguration(anchors));
        return new EgressTrustProfileResolver(registry);
    }

    /** An in-memory stand-in for the runtime's registry of named TLS configurations. */
    private static final class StubRegistry implements TlsConfigurationRegistry {

        private final Map<String, TlsConfiguration> byName = new HashMap<>();

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
    }

    /** A bucket carrying trust material, expressed the way a bound bucket exposes it to Vert.x. */
    private static final class BoundTlsConfiguration extends BaseTlsConfiguration {

        private final TrustOptions anchors;

        private BoundTlsConfiguration(TrustOptions anchors) {
            this.anchors = anchors;
        }

        @Override
        public TrustOptions getTrustStoreOptions() {
            return anchors;
        }
    }
}
