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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.CachingDiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.DiscoveryCacheStatus;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;

/**
 * Drives the engine's {@link CachingDiscoveryResolver} without a network: its delegate is a
 * {@link DiscoveryResolver} whose fetch is a test-supplied {@link Supplier}, so a test reaches each
 * {@link DiscoveryCacheStatus.State} through real refreshes of the real cache.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StubDiscovery {

    /** The issuer the stub's configuration names; no request is ever sent to it. */
    public static final String ISSUER = "https://idp.example.com/realms/stub";

    private StubDiscovery() {
    }

    /**
     * @param fetch what each refresh of the delegate returns or throws
     * @param ttl   the cache's time to live
     * @param grace the cache's grace period
     * @param clock the cache's clock
     * @return a caching resolver whose delegate refreshes through {@code fetch}
     */
    public static CachingDiscoveryResolver caching(Supplier<ProviderMetadata> fetch, Duration ttl, Duration grace,
            InstantSource clock) {
        return new CachingDiscoveryResolver(new FetchingResolver(fetch), ttl, grace, Duration.ZERO, Duration.ZERO,
                clock);
    }

    /**
     * @param state the state the returned resolver must report
     * @return a caching resolver driven into {@code state} through a real refresh, or none for
     *         {@code NOT_ATTEMPTED}
     */
    public static CachingDiscoveryResolver inState(DiscoveryCacheStatus.State state) {
        CachingDiscoveryResolver resolver = switch (state) {
            case NOT_ATTEMPTED -> caching(StubDiscovery::metadata, Duration.ofHours(1), Duration.ZERO,
                    InstantSource.fixed(Instant.EPOCH));
            case RESOLVED -> {
                CachingDiscoveryResolver resolved = caching(StubDiscovery::metadata, Duration.ofHours(1),
                        Duration.ZERO, InstantSource.fixed(Instant.EPOCH));
                resolved.resolve();
                yield resolved;
            }
            case FAILED -> {
                CachingDiscoveryResolver failed = caching(() -> {
                    throw new IllegalStateException("discovery refused");
                }, Duration.ofHours(1), Duration.ZERO, InstantSource.fixed(Instant.EPOCH));
                assertThrows(IllegalStateException.class, failed::resolve);
                yield failed;
            }
        };
        assertEquals(state, resolver.status().state(), "precondition: the resolver reached the state under test");
        return resolver;
    }

    /** @return provider metadata naming {@link #ISSUER} and a token endpoint, nothing else */
    public static ProviderMetadata metadata() {
        ProviderMetadata metadata = new ProviderMetadata();
        metadata.issuer = ISSUER;
        metadata.tokenEndpoint = ISSUER + "/token";
        return metadata;
    }

    /** A delegate that never dials: every refresh runs the supplied fetch. */
    private static final class FetchingResolver extends DiscoveryResolver {

        private final Supplier<ProviderMetadata> fetch;

        FetchingResolver(Supplier<ProviderMetadata> fetch) {
            super(ClientConfiguration.builder().issuer(ISSUER).clientId("stub-client").scopes(List.of())
                    .redirectUri("https://gateway.example.com/callback").authMethod(ClientAuthMethod.PRIVATE_KEY_JWT)
                    .build());
            this.fetch = Objects.requireNonNull(fetch, "fetch");
        }

        @Override
        public ProviderMetadata resolve() {
            return fetch.get();
        }
    }
}
