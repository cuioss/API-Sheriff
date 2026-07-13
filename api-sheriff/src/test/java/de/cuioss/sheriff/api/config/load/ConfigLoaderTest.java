/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;

/**
 * Tests for {@link ConfigLoader}: binding a valid {@code gateway.yaml} (including
 * secret resolution and the flattened {@code upstream_defaults} block), endpoint
 * binding with the {@code enabled} default, and the aggregated, path-annotated error
 * reporting for schema violations, missing secrets, and a missing gateway file.
 */
class ConfigLoaderTest {

    @TempDir
    Path configDir;

    private ConfigLoader loader(Map<String, String> environment) {
        return new ConfigLoader(configDir, new EnvSecretResolver(environment::get));
    }

    private void copyFixtureAs(String resource, String relativeTarget) throws IOException {
        Path target = configDir.resolve(relativeTarget);
        Files.createDirectories(target.getParent());
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture not on classpath: " + resource);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeConfig(String relativeTarget, String content) throws IOException {
        Path target = configDir.resolve(relativeTarget);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void bindsValidGatewayConfigAndResolvesSecrets() throws Exception {
        copyFixtureAs("/config/valid/gateway.yaml", "gateway.yaml");

        ConfigLoader.LoadedConfig loaded = loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t")).load();

        GatewayConfig gateway = loaded.gateway();
        assertEquals(1, gateway.version());
        assertEquals(List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE),
                gateway.allowedMethods());
        assertEquals(Optional.of("1.3"), gateway.tls().orElseThrow().minVersion());
        assertEquals(new UpstreamDefaultsConfig(true, false), gateway.upstreamDefaults().orElseThrow());
        assertEquals(Optional.of("s3cr3t"), gateway.oidc().orElseThrow().clientSecret());
        assertEquals(1, gateway.tokenValidation().orElseThrow().issuers().size());
        assertEquals("primary", gateway.tokenValidation().orElseThrow().issuers().getFirst().name());
        assertTrue(loaded.endpoints().isEmpty());
    }

    @Test
    void reportsMissingEnvSecretWithPointer() throws Exception {
        copyFixtureAs("/config/valid/gateway.yaml", "gateway.yaml");

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("client_secret")
                                && error.message().contains("OIDC_CLIENT_SECRET")),
                () -> "expected a pointer-annotated missing-secret error, got: " + exception.errors());
    }

    @Test
    void aggregatesSchemaErrorsWithPathAnnotation() throws Exception {
        copyFixtureAs("/config/invalid/unknown-key.yaml", "gateway.yaml");

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertFalse(exception.errors().isEmpty());
        assertTrue(exception.errors().stream().allMatch(error -> "gateway.yaml".equals(error.file())));
        assertTrue(exception.errors().stream().anyMatch(error -> error.pointer().contains("min_version")),
                () -> "expected a path-annotated enum violation, got: " + exception.errors());
    }

    @Test
    void reportsMissingGatewayFile() {
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.message().contains("not found")),
                () -> "expected a missing-file error, got: " + exception.errors());
    }

    @Test
    void bindsEndpointApplyingEnabledDefaultAndFlattenedUpstreamDefaults() throws Exception {
        writeConfig("gateway.yaml", "version: 1\n");
        writeConfig("endpoints/orders.yaml", """
                endpoint:
                  id: orders
                  base_url: ORDERS
                  auth:
                    require: bearer
                    required_scopes: ["orders.read"]
                  allowed_methods: ["GET", "POST"]
                  upstream_defaults:
                    retry:
                      enabled: false
                    not_modified:
                      enabled: true
                  routes:
                    - id: orders-read
                      protocol: http
                      match:
                        path_prefix: /orders
                        methods: ["GET"]
                      auth:
                        require: bearer
                      upstream:
                        path: /v1/orders
                        retry:
                          enabled: true
                          max_attempts: 3
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(1, loaded.endpoints().size());
        EndpointConfig endpoint = loaded.endpoints().getFirst();
        assertEquals("orders", endpoint.id());
        assertTrue(endpoint.enabled(), "an endpoint omitting 'enabled' defaults to enabled");
        assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), endpoint.allowedMethods());
        assertEquals(new UpstreamDefaultsConfig(false, true), endpoint.upstreamDefaults().orElseThrow());
        assertEquals(1, endpoint.routes().size());
        RouteConfig route = endpoint.routes().getFirst();
        assertEquals("orders-read", route.id());
        assertEquals(Optional.of(Protocol.HTTP), route.protocol());
        assertTrue(route.upstream().orElseThrow().retry().isPresent());
    }

    @Test
    void loadsWithoutEndpointsWhenDirectoryAbsent() throws Exception {
        writeConfig("gateway.yaml", "version: 1\n");

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(1, loaded.gateway().version());
        assertTrue(loaded.endpoints().isEmpty());
    }
}
