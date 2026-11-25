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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Boots the whole application with an active BFF runtime and proves the readiness probe reaches the
 * runtime's discovery state through the container — the BFF half of {@link DiscoveryResolverBootTest}.
 * <p>
 * Booting alone is not enough here: the probe resolves the discovery state lazily, so a second bean of
 * the same type (the token-sheriff-client extension ships a producer for the engine
 * {@code CachingDiscoveryResolver}) would surface only when the probe asks for it. The test therefore
 * calls the probe and pins the {@code oidc_discovery} datum: boot resolves no provider metadata, so a
 * fresh runtime reports {@code not-attempted} (ADR-0079). The fixture under {@code config/cookieboot}
 * declares a cookie-mode {@code oidc} block whose discovery is never fetched, so no identity provider is
 * needed.
 */
@QuarkusTest
@TestProfile(DiscoveryResolverBffBootTest.BffBootProfile.class)
@DisplayName("Application boot — active BFF runtime, readiness reads the gateway-owned discovery state")
@Tag("quarkus-fork")
class DiscoveryResolverBffBootTest {

    @Inject
    BffRuntime runtime;

    @Inject
    @Readiness
    GatewayReadinessCheck readiness;

    /** Points the gateway at the cookie-mode BFF fixture instead of the bearer-only testboot config. */
    public static final class BffBootProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("sheriff.config.dir", "target/test-classes/config/cookieboot");
        }
    }

    @Test
    @DisplayName("Should boot an active BFF runtime and report oidc_discovery not-attempted")
    void shouldBootWithActiveBffRuntime() {
        HealthCheckResponse response = readiness.call();
        Map<String, Object> data = response.getData().orElseThrow();

        assertAll("the probe reads the runtime's discovery state without fetching it",
                () -> assertTrue(runtime.isActive(), "precondition: the fixture activates the BFF runtime"),
                () -> assertEquals("cookie", data.get("oidc"), "the active session mode is reported"),
                () -> assertEquals("not-attempted", data.get("oidc_discovery"),
                        "boot resolves no provider metadata, and the probe does not fetch it"));
    }
}
