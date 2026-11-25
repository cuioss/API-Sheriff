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
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Boots the whole application in the default, bearer-only test profile and proves the gateway's
 * discovery wiring does not stop the start.
 * <p>
 * The token-sheriff-client Quarkus extension refuses to start an application that declares an
 * injection point of the engine {@code CachingDiscoveryResolver} unless the extension's own
 * {@code sheriff.client.discovery-cache.*} keys are set: its build step records that guard for any
 * such injection point, whatever its qualifier and whether or not it is an {@code Instance}. The
 * gateway assembles its resolver itself, with fixed values (ADR-0084), so it must not declare one.
 * Without the fix every {@code @QuarkusTest} fails with {@code Failed to start quarkus}, and this
 * class with it. {@link DiscoveryResolverBffBootTest} is the same proof with the BFF runtime active.
 */
@QuarkusTest
@DisplayName("Application boot — bearer-only, with the gateway-owned discovery wiring")
@Tag("quarkus-fork")
class DiscoveryResolverBootTest {

    @Inject
    BffRuntime runtime;

    @Inject
    @Readiness
    GatewayReadinessCheck readiness;

    @Test
    @DisplayName("Should boot bearer-only and report readiness without any OIDC datum")
    void shouldBootBearerOnly() {
        HealthCheckResponse response = readiness.call();
        Map<String, Object> data = response.getData().orElseThrow();

        assertAll("the default profile runs no BFF runtime, so no discovery resolver is ever reached",
                () -> assertFalse(runtime.isActive(), "precondition: the default test profile is bearer-only"),
                () -> assertEquals("loaded", data.get("config"), "the probe answered on the booted container"),
                () -> assertFalse(data.containsKey("oidc"), "no BFF runtime, no oidc datum"),
                () -> assertFalse(data.containsKey("oidc_discovery"), "no BFF runtime, no oidc_discovery datum"));
    }
}
