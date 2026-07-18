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

import de.cuioss.sheriff.api.config.model.AnchorConfig;
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

    @Test
    void rejectsForwardedBlockOmittingTrustedProxies() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                forwarded:
                  trust_scheme_host: true
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("forwarded")),
                () -> "expected a schema violation for a forwarded block omitting trusted_proxies, got: "
                        + exception.errors());
    }

    @Test
    void acceptsForwardedBlockDeclaringEmptyTrustedProxies() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                forwarded:
                  trusted_proxies: []
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertTrue(loaded.gateway().forwarded().orElseThrow().trustedProxies().isEmpty(),
                "an explicitly empty trusted_proxies list means no proxy is trusted and stays valid");
    }

    @Test
    void bindsAnchorsBlockAndInjectsTheMapKeyAsName() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    auth:
                      require: bearer
                    security_filter:
                      profile: strict
                    allowed_methods: ["GET", "POST"]
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        Map<String, AnchorConfig> anchors = loaded.gateway().anchors();
        assertEquals(1, anchors.size(), "the anchors block should bind one anchor");
        AnchorConfig api = anchors.get("api");
        assertNotNull(api, "the anchor keyed 'api' should bind");
        assertEquals("api", api.name(), "the map key is injected as the anchor name");
        assertEquals("/api", api.pathPrefix());
        assertEquals("bearer", api.auth().orElseThrow().require());
        assertEquals(Optional.of("strict"), api.securityFilter().orElseThrow().profile());
        assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), api.allowedMethods());
    }

    @Test
    void rejectsUnknownKeyInsideAnAnchorBlock() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    bogus_key: true
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file()) && error.pointer().contains("anchors")),
                () -> "an unknown key inside an anchor block must fail the boot with a pointer, got: "
                        + exception.errors());
    }

    @Test
    void bindsAnchorReferencesOnEndpointAndRoute() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    auth:
                      require: bearer
                """);
        writeConfig("endpoints/api.yaml", """
                endpoint:
                  id: api
                  base_url: API
                  anchor: api
                  routes:
                    - id: api-read
                      anchor: api
                      match:
                        path_prefix: /api/read
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        EndpointConfig endpoint = loaded.endpoints().getFirst();
        assertEquals(Optional.of("api"), endpoint.anchor(), "the endpoint anchor ref should bind");
        RouteConfig route = endpoint.routes().getFirst();
        assertEquals(Optional.of("api"), route.anchor(), "the route anchor ref should bind");
    }

    @Test
    void bindsAnEndpointOmittingAuthWhenAnchored() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    auth:
                      require: bearer
                """);
        writeConfig("endpoints/api.yaml", """
                endpoint:
                  id: api
                  base_url: API
                  anchor: api
                  routes:
                    - id: api-read
                      match:
                        path_prefix: /api/read
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        EndpointConfig endpoint = loaded.endpoints().getFirst();
        assertTrue(endpoint.auth().isEmpty(),
                "an anchored endpoint may omit its auth block and still bind at the schema level");
        assertEquals(Optional.of("api"), endpoint.anchor());
    }

    @Test
    void substitutedScalarIsSchemaTypeChecked() throws Exception {
        writeConfig("gateway.yaml", "version: \"${CONFIG_VERSION:-1}\"\n");

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(1, loaded.gateway().version(),
                "a defaulted ${VAR} on an integer field must coerce to an integer and satisfy the schema");
    }

    @Test
    void rejectsLiteralSecretValue() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                oidc:
                  issuer: "https://issuer.example.com"
                  client_id: "sheriff"
                  client_secret: "literal-not-a-reference"
                  redirect_uri: "https://gw.example.com/callback"
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("client_secret")
                                && error.message().contains("bare ${VAR}")),
                () -> "a literal client_secret must be rejected by the secrets rule, got: " + exception.errors());
    }

    @Test
    void rejectsDefaultedSecretValue() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                oidc:
                  issuer: "https://issuer.example.com"
                  client_id: "sheriff"
                  client_secret: "${OIDC_CLIENT_SECRET:-fallback}"
                  redirect_uri: "https://gw.example.com/callback"
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class,
                () -> loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t")).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("client_secret")),
                () -> "a defaulted secret placeholder must be rejected — a secret must be a bare ${VAR} reference, got: "
                        + exception.errors());
    }

    @Test
    void strayYmlEndpointFileFailsTheBoot() throws Exception {
        writeConfig("gateway.yaml", "version: 1\n");
        writeConfig("endpoints/orders.yml", """
                endpoint:
                  id: orders
                  base_url: ORDERS
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load());

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.file().contains("orders.yml")
                                && error.message().contains("rename it to '.yaml'")),
                () -> "a stray '.yml' endpoint file must fail the boot, got: " + exception.errors());
    }

    @Test
    void bindingOrSchemaErrorNeverEchoesResolvedSecret() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                oidc:
                  issuer: "https://issuer.example.com"
                  client_id: "sheriff"
                  client_secret: "${OIDC_CLIENT_SECRET}"
                  redirect_uri: "https://gw.example.com/callback"
                  bogus_unknown_key: "trigger-an-error"
                """);

        ConfigLoadException exception = assertThrows(ConfigLoadException.class,
                () -> loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t-topsecret-value")).load());

        assertTrue(exception.errors().stream()
                        .noneMatch(error -> error.message().contains("s3cr3t-topsecret-value")),
                () -> "no error may echo the resolved secret value, got: " + exception.errors());
    }

    @Test
    void rejectsYamlExceedingAliasExpansionLimit() throws Exception {
        StringBuilder yaml = new StringBuilder("version: 1\nanchor_def: &a [1, 2, 3]\naliases:\n");
        for (int i = 0; i < 60; i++) {
            yaml.append("  - *a\n");
        }
        writeConfig("gateway.yaml", yaml.toString());

        assertThrows(ConfigLoadException.class, () -> loader(Map.of()).load(),
                "a YAML document exceeding the alias-expansion limit must fail the boot");
    }
}
