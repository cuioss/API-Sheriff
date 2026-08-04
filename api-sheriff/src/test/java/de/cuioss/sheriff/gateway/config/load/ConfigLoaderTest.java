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
package de.cuioss.sheriff.gateway.config.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigLoader}: binding a valid {@code gateway.yaml} (including
 * secret resolution and the flattened {@code upstream_defaults} block), endpoint
 * binding with the {@code enabled} default, and the aggregated, path-annotated error
 * reporting for schema violations, missing secrets, and a missing gateway file.
 */
class ConfigLoaderTest {

    /**
     * The operator-facing sentence {@code gateway.schema.json} attaches to the always-failing
     * {@code management.port} subschema through the {@code errorMessage} extension. Copied verbatim
     * from the schema — the point of the assertion is that the schema's own sentence, not a generic
     * unknown-key message, reaches the operator.
     */
    private static final String MANAGEMENT_PORT_REJECTION =
            "the management port is deployment-bound and is not settable in gateway.yaml: "
                    + "set quarkus.management.port (environment QUARKUS_MANAGEMENT_PORT, default 9000) "
                    + "instead — see ADR-0025";

    /**
     * Mirrors the loader's private {@code MAX_CONFIG_FILE_BYTES}. The value is pinned from both
     * directions rather than by widening the constant's visibility:
     * {@link #bindsAConfigFileExactlyAtTheByteCap} fails if the loader's cap ever drops below this
     * number, and {@link #rejectsAConfigFileOneByteOverTheByteCap} fails if it ever rises above it.
     */
    private static final int MAX_CONFIG_FILE_BYTES = 512 * 1024;

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

    /**
     * Builds a {@code gateway.yaml}-shaped document of exactly {@code totalBytes} bytes by padding a
     * YAML comment. A comment contributes no keys, so the padding cannot perturb schema validation or
     * binding — only the document's size. The text is pure ASCII, so its character count is its UTF-8
     * byte count. Under the cap the document binds; over it, size is the only thing about it that is
     * ever examined.
     */
    private static String configDocumentOfExactly(int totalBytes) {
        String head = "version: 1\n# ";
        int padding = totalBytes - head.length() - 1;
        assertTrue(padding > 0, "requested size is too small to hold the padded document");
        return head + "x".repeat(padding) + "\n";
    }

    @Test
    void bindsValidGatewayConfigAndResolvesSecrets() throws Exception {
        copyFixtureAs("/config/valid/gateway.yaml", "gateway.yaml");

        ConfigLoader.LoadedConfig loaded = loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t")).load();

        GatewayConfig gateway = loaded.gateway();
        assertEquals(1, gateway.version());
        assertEquals(List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE),
                gateway.allowedMethods());
        assertEquals("1.3", gateway.tls().minVersion());
        assertEquals(List.of("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"),
                gateway.tls().cipherSuites(),
                "the neutral tls.cipher_suites allowlist must bind from gateway.yaml");
        assertEquals(List.of("h2", "http/1.1"), gateway.tls().alpn());
        assertEquals(new UpstreamDefaultsConfig(true, false), gateway.upstreamDefaults());
        assertEquals("s3cr3t", gateway.oidc().clientSecret());
        assertEquals(1, gateway.tokenValidation().issuers().size());
        assertEquals("primary", gateway.tokenValidation().issuers().getFirst().name());
        assertTrue(loaded.endpoints().isEmpty());
    }

    @Test
    void reportsMissingEnvSecretWithPointer() throws Exception {
        copyFixtureAs("/config/valid/gateway.yaml", "gateway.yaml");

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("client_secret")
                                && error.message().contains("OIDC_CLIENT_SECRET")),
                () -> "expected a pointer-annotated missing-secret error, got: " + exception.errors());
    }

    @Test
    void aggregatesSchemaErrorsWithPathAnnotation() throws Exception {
        copyFixtureAs("/config/invalid/unknown-key.yaml", "gateway.yaml");

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertFalse(exception.errors().isEmpty());
        assertTrue(exception.errors().stream().allMatch(error -> "gateway.yaml".equals(error.file())));
        assertTrue(exception.errors().stream().anyMatch(error -> error.pointer().contains("min_version")),
                () -> "expected a path-annotated enum violation, got: " + exception.errors());
    }

    @Test
    void reportsMissingGatewayFile() {
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

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
        assertEquals(new UpstreamDefaultsConfig(false, true), endpoint.upstreamDefaults());
        assertEquals(1, endpoint.routes().size());
        RouteConfig route = endpoint.routes().getFirst();
        assertEquals("orders-read", route.id());
        assertEquals(Protocol.HTTP, route.protocol());
        assertNotNull(route.upstream().retry());
    }

    @Test
    void loadsWithoutEndpointsWhenDirectoryAbsent() throws Exception {
        writeConfig("gateway.yaml", "version: 1\n");

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(1, loaded.gateway().version());
        assertTrue(loaded.endpoints().isEmpty());
    }

    @Test
    void bindsJwksAllowedEgressHosts() throws Exception {
        // Arrange — the shape the benchmark stack's benchmark-keycloak issuer uses
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: benchmark-keycloak
                      issuer: https://keycloak:8443/realms/benchmark
                      jwks:
                        source: http
                        url: https://keycloak:8443/realms/benchmark/protocol/openid-connect/certs
                        allowed_egress_hosts: ["keycloak"]
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert — the key is accepted by the schema (additionalProperties: false) and
        // binds through the SNAKE_CASE strategy to the record component
        IssuerConfig issuer = loaded.gateway().tokenValidation().issuers().getFirst();
        assertEquals(List.of("keycloak"), issuer.jwks().allowedEgressHosts());
    }

    @Test
    void omittedAllowedEgressHostsBindsToEmptyList() throws Exception {
        // Arrange — an http issuer that says nothing about egress
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: primary
                      issuer: https://issuer.example.com
                      jwks:
                        source: http
                        url: https://issuer.example.com/jwks
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert — no entries means the secure egress default is preserved downstream
        IssuerConfig issuer = loaded.gateway().tokenValidation().issuers().getFirst();
        assertEquals(List.of(), issuer.jwks().allowedEgressHosts());
    }

    @Test
    void rejectsNonArrayAllowedEgressHosts() throws Exception {
        // Arrange — a bare string is a plausible operator typo for a single host
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: primary
                      issuer: https://issuer.example.com
                      jwks:
                        source: http
                        url: https://issuer.example.com/jwks
                        allowed_egress_hosts: keycloak
                """);

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert — the schema refuses it at boot rather than silently ignoring the widening
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("allowed_egress_hosts")),
                () -> "expected a schema violation for a non-array allowed_egress_hosts, got: "
                        + exception.errors());
    }

    @Test
    void bindsJwksTlsProfile() throws Exception {
        // Arrange — an IdP presenting a certificate from an internal CA
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: corporate
                      issuer: https://idp.corp.internal/realms/main
                      jwks:
                        source: http
                        url: https://idp.corp.internal/realms/main/protocol/openid-connect/certs
                        tls_profile: corporate-idp
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert — the key is accepted by the schema (additionalProperties: false) and binds
        // through the SNAKE_CASE strategy to the record component
        IssuerConfig issuer = loaded.gateway().tokenValidation().issuers().getFirst();
        assertEquals("corporate-idp", issuer.jwks().tlsProfile());
    }

    @Test
    void omittedTlsProfileBindsToNull() throws Exception {
        // Arrange — an http issuer that says nothing about trust
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: primary
                      issuer: https://issuer.example.com
                      jwks:
                        source: http
                        url: https://issuer.example.com/jwks
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert — an absent profile binds to null, which the runtime reads as default trust
        IssuerConfig issuer = loaded.gateway().tokenValidation().issuers().getFirst();
        assertNull(issuer.jwks().tlsProfile());
    }

    @Test
    void rejectsNonStringTlsProfile() throws Exception {
        // Arrange — a list is a plausible operator confusion with allowed_egress_hosts
        writeConfig("gateway.yaml", """
                version: 1
                token_validation:
                  issuers:
                    - name: primary
                      issuer: https://issuer.example.com
                      jwks:
                        source: http
                        url: https://issuer.example.com/jwks
                        tls_profile: ["corporate-idp"]
                """);

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert — refused at boot rather than silently ignored, which would leave the issuer on
        // default trust while the operator believes a profile is in force
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("tls_profile")),
                () -> "expected a schema violation for a non-string tls_profile, got: "
                        + exception.errors());
    }

    @Test
    void rejectsForwardedBlockOmittingTrustedProxies() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                forwarded:
                  trust_scheme_host: true
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

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

        assertTrue(loaded.gateway().forwarded().trustedProxies().isEmpty(),
                "an explicitly empty trusted_proxies list means no proxy is trusted and stays valid");
    }

    @Test
    void acceptsPolicyOnlyManagementBlock() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                management:
                  tls:
                    enabled: true
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertTrue(loaded.gateway().management().tls().enabled(),
                "a policy-only management block must bind without a port component");
    }

    @Test
    void rejectsManagementPortNamingTheDeploymentKnobAtItsOwnPointer() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                management:
                  port: 9100
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("/management/port")
                                && error.message().contains(MANAGEMENT_PORT_REJECTION)),
                () -> "expected the management.port rejection to carry the deployment-knob sentence at "
                        + "its own JSON Pointer, got: " + exception.errors());
    }

    @Test
    void bindsAnchorsBlockAndInjectsTheMapKeyAsName() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    type: proxy
                    access: authenticated
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
        assertEquals("bearer", api.auth().require());
        assertEquals("strict", api.securityFilter().profile());
        assertEquals(List.of(HttpMethod.GET, HttpMethod.POST), api.allowedMethods());
        assertEquals(AnchorType.PROXY, api.type(), "the required type axis binds (case-insensitive from 'proxy')");
        assertEquals(AccessLevel.AUTHENTICATED, api.access(),
                "the required access axis binds (case-insensitive from 'authenticated')");
    }

    /**
     * The {@code profile} value range is owned by the bundled JSON Schema, which carries three
     * symmetric enum sites: {@code gateway.yaml} {@code security_defaults.profile} and
     * {@code $defs/securityFilter.profile}, plus {@code endpoint.schema.json}'s
     * {@code $defs/securityFilter.profile}. These tests pin all three together so a partial edit
     * cannot leave one site still accepting the dropped {@code default} preset.
     */
    @ParameterizedTest(name = "gateway security_defaults.profile ''{0}'' fails the boot")
    @ValueSource(strings = {"default", "bogus"})
    void rejectsUnrecognisedGlobalSecurityDefaultsProfile(String value) throws Exception {
        // Arrange
        writeConfig("gateway.yaml", """
                version: 1
                security_defaults:
                  profile: %s
                """.formatted(value));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("security_defaults")),
                () -> "expected a schema violation naming the security_defaults key, got: "
                        + exception.errors());
    }

    /**
     * The {@code max_authorization_header_value_length} value RANGE is owned by the bundled JSON
     * Schema ({@code integer}, {@code minimum: 1}), so an out-of-range value is refused at bind time,
     * before the configuration validator's cross-cutting baseline comparison ever runs.
     */
    @ParameterizedTest(name = "security_defaults.max_authorization_header_value_length ''{0}'' fails the boot")
    @ValueSource(strings = {"0", "-1", "\"not-a-number\""})
    void rejectsOutOfRangeAuthorizationHeaderValueLength(String value) throws Exception {
        // Arrange
        writeConfig("gateway.yaml", """
                version: 1
                security_defaults:
                  profile: strict
                  max_authorization_header_value_length: %s
                """.formatted(value));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("security_defaults")),
                () -> "expected a schema violation naming the security_defaults key, got: "
                        + exception.errors());
    }

    /**
     * The end-to-end proof that the snake-cased key binds to the record component without an explicit
     * Jackson annotation — {@code ConfigLoader} binds under {@code PropertyNamingStrategies.SNAKE_CASE},
     * so the mapping is by convention and would break silently if that strategy ever changed.
     */
    @Test
    void bindsDeclaredAuthorizationHeaderValueLength() throws Exception {
        // Arrange
        writeConfig("gateway.yaml", """
                version: 1
                security_defaults:
                  profile: strict
                  max_authorization_header_value_length: 12000
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert
        SecurityDefaultsConfig securityDefaults = loaded.gateway().securityDefaults();
        assertEquals(12000, securityDefaults.maxAuthorizationHeaderValueLength(),
                "the snake_cased key binds to the record component with no explicit annotation");
        assertEquals(12000, securityDefaults.effectiveMaxAuthorizationHeaderValueLength(),
                "a declared budget is what the carve-out enforces");
    }

    @Test
    void resolvesOmittedAuthorizationHeaderValueLengthToTheDefault() throws Exception {
        // Arrange — the matched negative half: an omitted key must bind null and resolve to the
        // documented default rather than to zero or to a bind failure.
        writeConfig("gateway.yaml", """
                version: 1
                security_defaults:
                  profile: strict
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert
        SecurityDefaultsConfig securityDefaults = loaded.gateway().securityDefaults();
        assertNull(securityDefaults.maxAuthorizationHeaderValueLength());
        assertEquals(SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH,
                securityDefaults.effectiveMaxAuthorizationHeaderValueLength());
    }

    @ParameterizedTest(name = "anchor security_filter.profile ''{0}'' fails the boot")
    @ValueSource(strings = {"default", "bogus"})
    void rejectsUnrecognisedAnchorSecurityFilterProfile(String value) throws Exception {
        // Arrange
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    type: proxy
                    access: public
                    security_filter:
                      profile: %s
                """.formatted(value));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("profile")),
                () -> "expected a schema violation naming the anchor's profile key, got: "
                        + exception.errors());
    }

    @ParameterizedTest(name = "route security_filter.profile ''{0}'' fails the boot")
    @ValueSource(strings = {"default", "bogus"})
    void rejectsUnrecognisedRouteSecurityFilterProfile(String value) throws Exception {
        // Arrange
        writeConfig("gateway.yaml", "version: 1\n");
        writeConfig("endpoints/orders.yaml", """
                endpoint:
                  id: orders
                  base_url: ORDERS
                  auth:
                    require: none
                  routes:
                    - id: orders-read
                      match:
                        path_prefix: /orders
                      security_filter:
                        profile: %s
                """.formatted(value));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "endpoints/orders.yaml".equals(error.file())
                                && error.pointer().contains("security_filter/profile")),
                () -> "expected a schema violation naming the route's profile key, got: "
                        + exception.errors());
    }

    @Test
    void acceptsTheMinimalProfileAtEveryEnumSite() throws Exception {
        // Arrange — the partial-disable member of the mode set, declared at all three sites at once.
        // The endpoint's `auth.require: none` rides along deliberately: it is a DIFFERENT knob in a
        // different value space, so the profile rename must leave it accepted and unchanged.
        writeConfig("gateway.yaml", """
                version: 1
                security_defaults:
                  profile: minimal
                anchors:
                  api:
                    path_prefix: /api
                    type: proxy
                    access: public
                    security_filter:
                      profile: minimal
                """);
        writeConfig("endpoints/orders.yaml", """
                endpoint:
                  id: orders
                  base_url: ORDERS
                  auth:
                    require: none
                  routes:
                    - id: orders-read
                      match:
                        path_prefix: /orders
                      security_filter:
                        profile: minimal
                """);

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert
        assertEquals("minimal", loaded.gateway().securityDefaults().profile());
        assertEquals("minimal",
                loaded.gateway().anchors().get("api").securityFilter().profile());
        assertEquals("minimal", loaded.endpoints().getFirst().routes().getFirst()
                .securityFilter().profile());
        assertEquals("none", loaded.endpoints().getFirst().auth().require(),
                "auth.require: none is a different knob and survives the profile rename");
    }

    @Test
    void rejectsUnknownKeyInsideAnAnchorBlock() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    type: proxy
                    access: authenticated
                    bogus_key: true
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

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
                    type: proxy
                    access: authenticated
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
        assertEquals("api", endpoint.anchor(), "the endpoint anchor ref should bind");
        RouteConfig route = endpoint.routes().getFirst();
        assertEquals("api", route.anchor(), "the route anchor ref should bind");
    }

    @Test
    void bindsAnEndpointOmittingAuthWhenAnchored() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                anchors:
                  api:
                    path_prefix: /api
                    type: proxy
                    access: authenticated
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
        assertNull(endpoint.auth(),
                "an anchored endpoint may omit its auth block and still bind at the schema level");
        assertEquals("api", endpoint.anchor());
    }

    @Test
    void substitutedScalarIsSchemaTypeChecked() throws Exception {
        writeConfig("gateway.yaml", "version: \"${CONFIG_VERSION:-1}\"\n");

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(1, loaded.gateway().version(),
                "a defaulted ${VAR} on an integer field must coerce to an integer and satisfy the schema");
    }

    @Test
    void keepsAnAllDigitSubstitutionTooLargeForALongAsText() throws Exception {
        copyFixtureAs("/config/valid/gateway.yaml", "gateway.yaml");
        String tooLargeForALong = "9".repeat(20);

        ConfigLoader.LoadedConfig loaded = loader(Map.of("OIDC_CLIENT_SECRET", tooLargeForALong)).load();

        assertEquals(tooLargeForALong, loaded.gateway().oidc().clientSecret(),
                "an all-digit substitution that overflows a long must fall back to a text node rather than "
                        + "propagate the parse failure");
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

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

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

        ConfigLoader loader = loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t"));
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.pointer().contains("client_secret")),
                () -> "a defaulted secret placeholder must be rejected — a secret must be a bare ${VAR} reference, got: "
                        + exception.errors());
    }

    /**
     * Pins the observable break of collapsing the cookie key material to a single key: a
     * {@code gateway.yaml} that still declares the removed {@code oidc.session.previous_key} does
     * not start.
     * <p>
     * This is a schema-level refusal, not a validator rule — {@code oidc.session} declares
     * {@code additionalProperties: false}, so removing the property from the schema converts every
     * lingering declaration into a boot failure. That is deliberately a clean break with no
     * accept-and-ignore shim: silently tolerating the key would leave an operator believing a
     * rotation key is still in force when nothing reads it. The operator remedy — delete the line —
     * is documented in {@code doc/user/bff-cookie.adoc}.
     */
    @Test
    void rejectsRetiredPreviousKeyAtBoot() throws Exception {
        writeConfig("gateway.yaml", """
                version: 1
                oidc:
                  issuer: "https://issuer.example.com"
                  client_id: "sheriff"
                  client_secret: "${OIDC_CLIENT_SECRET}"
                  redirect_uri: "https://gw.example.com/callback"
                  session:
                    mode: cookie
                    encryption_key: "${SHERIFF_SESSION_KEY}"
                    previous_key: "${SHERIFF_SESSION_KEY_PREVIOUS}"
                """);

        ConfigLoader loader = loader(Map.of(
                "OIDC_CLIENT_SECRET", "s3cr3t",
                "SHERIFF_SESSION_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "SHERIFF_SESSION_KEY_PREVIOUS", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="));
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.pointer().contains("session")),
                () -> "a config still declaring the removed oidc.session.previous_key must fail the boot "
                        + "under additionalProperties: false, got: " + exception.errors());
    }

    @Test
    void strayYmlEndpointFileFailsTheBoot() throws Exception {
        writeConfig("gateway.yaml", "version: 1\n");
        writeConfig("endpoints/orders.yml", """
                endpoint:
                  id: orders
                  base_url: ORDERS
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

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

        ConfigLoader loader = loader(Map.of("OIDC_CLIENT_SECRET", "s3cr3t-topsecret-value"));
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .noneMatch(error -> error.message().contains("s3cr3t-topsecret-value")),
                () -> "no error may echo the resolved secret value, got: " + exception.errors());
    }

    @Test
    void rejectsYamlExceedingAliasExpansionLimit() throws Exception {
        // A collection anchor dereferenced far more than MAX_YAML_ALIASES (50) times. Jackson's YAMLParser
        // consumes SnakeYAML's event stream directly and never runs the Composer, so this bomb is caught
        // ONLY by ConfigLoader's SnakeYAML-native compose pre-pass — assert on the alias-specific diagnostic
        // (not merely "some ConfigLoadException") so the test fails loudly if that guard ever regresses. Were
        // the guard absent, this document would fail solely on the schema's additionalProperties rejection of
        // the unrelated top-level keys, which carries no 'alias' diagnostic.
        StringBuilder yaml = new StringBuilder("version: 1\nanchor_def: &a [1, 2, 3]\naliases:\n");
        for (int i = 0; i < 60; i++) {
            yaml.append("  - *a\n");
        }
        writeConfig("gateway.yaml", yaml.toString());

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load,
                "a YAML document exceeding the alias-expansion limit must fail the boot");

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.message().contains("bomb protection tripped")
                                && error.message().contains("alias")),
                () -> "the alias-expansion guard must raise its alias-specific diagnostic, not merely a schema "
                        + "violation for the unrelated top-level keys, got: " + exception.errors());
    }

    @Test
    void rejectsNestedCollectionAliasBomb() throws Exception {
        // The classic billion-laughs shape: collection anchors chained through nested levels, then the
        // deepest level dereferenced past MAX_YAML_ALIASES (50). SnakeYAML's guard counts total non-scalar
        // alias EVENTS (not the exponential expansion), so both this nested form and a flat repeat trip it
        // once the count crosses the limit — and both are missed by Jackson's Composer-less readTree path.
        StringBuilder yaml = new StringBuilder("""
                version: 1
                l0: &l0 ["lol", "lol"]
                l1: &l1 [*l0, *l0]
                l2: &l2 [*l1, *l1]
                boom:
                """);
        for (int i = 0; i < 60; i++) {
            yaml.append("  - *l2\n");
        }
        writeConfig("gateway.yaml", yaml.toString());

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load,
                "a nested/exponential alias bomb must fail the boot");

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> error.message().contains("bomb protection tripped")
                                && error.message().contains("alias")),
                () -> "a nested alias bomb must trip the alias-count guard, got: " + exception.errors());
    }

    @Test
    void acceptsAModestNumberOfCollectionAliasesWithinTheLimit() throws Exception {
        // A handful of legitimate collection aliases (well under MAX_YAML_ALIASES) must NOT trip the guard —
        // the pre-pass rejects bombs, not ordinary anchor reuse. The unrelated top-level keys still fail the
        // schema, but the point is that the compose pre-pass raises no alias diagnostic for this document.
        writeConfig("gateway.yaml", """
                version: 1
                base: &b ["a", "b"]
                reuse: [*b, *b, *b]
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream().noneMatch(error -> error.message().contains("bomb protection tripped")),
                () -> "a modest number of aliases must not trip the alias-expansion guard, got: "
                        + exception.errors());
    }

    @Test
    void bindsAConfigFileExactlyAtTheByteCap() throws Exception {
        // Arrange — the inclusive boundary: the cap refuses what EXCEEDS it, so a document sitting
        // exactly on it must still load.
        writeConfig("gateway.yaml", configDocumentOfExactly(MAX_CONFIG_FILE_BYTES));
        assertEquals(MAX_CONFIG_FILE_BYTES, Files.size(configDir.resolve("gateway.yaml")),
                "the fixture must sit exactly on the cap for the boundary to be the thing under test");

        // Act
        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        // Assert
        assertEquals(1, loaded.gateway().version(),
                "a document exactly at the byte cap must bind normally");
    }

    @Test
    void rejectsAConfigFileOneByteOverTheByteCap() throws Exception {
        // Arrange — the matched negative half of the boundary pair: one byte more than the cap allows.
        writeConfig("gateway.yaml", configDocumentOfExactly(MAX_CONFIG_FILE_BYTES + 1));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load,
                "a configuration file one byte over the cap must fail the boot");

        // Assert — the refusal is a collected error, and it is the ONLY error for the file: a schema or
        // binding error alongside it would mean the over-cap document was partially bound anyway.
        List<ConfigError> gatewayErrors = exception.errors().stream()
                .filter(error -> "gateway.yaml".equals(error.file()))
                .toList();
        assertEquals(1, gatewayErrors.size(),
                () -> "the size refusal must be the only error reported for the file, got: "
                        + exception.errors());
        assertTrue(gatewayErrors.getFirst().message().contains(String.valueOf(MAX_CONFIG_FILE_BYTES)),
                () -> "the refusal must name the byte cap it enforced, got: " + gatewayErrors);
    }

    @Test
    void overCapAliasBombIsRefusedBySizeAloneProvingBothPassesShareOneSnapshot() throws Exception {
        // Arrange — an alias bomb padded past the byte cap. The size check gates the ONE snapshot that
        // both the compose-only bomb pre-pass and the bind consume, so this document must never reach
        // the composer. Were the pre-pass to reopen the file itself — the second, unbounded read this
        // deliverable removed — it would compose the whole document and report the alias diagnostic
        // alongside the size refusal. The absence of that diagnostic is what pins the single-read
        // contract against a silent reintroduction.
        StringBuilder yaml = new StringBuilder("version: 1\nanchor_def: &a [1, 2, 3]\naliases:\n");
        for (int i = 0; i < 60; i++) {
            yaml.append("  - *a\n");
        }
        yaml.append("# ").append("x".repeat(MAX_CONFIG_FILE_BYTES)).append('\n');
        writeConfig("gateway.yaml", yaml.toString());

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .noneMatch(error -> error.message().contains("bomb protection tripped")),
                () -> "an over-cap document must be refused before the composer ever sees it; an alias "
                        + "diagnostic here means a second, unbounded read of the file survived, got: "
                        + exception.errors());
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && error.message().contains(String.valueOf(MAX_CONFIG_FILE_BYTES))),
                () -> "expected the byte-cap refusal to be the reported failure, got: "
                        + exception.errors());
    }

    @Test
    void appliesTheByteCapToEndpointFilesToo() throws Exception {
        // Arrange — the cap is a property of "a configuration file", not of gateway.yaml specifically;
        // endpoints/*.yaml are read through the same single-snapshot path and must be bounded alike.
        writeConfig("gateway.yaml", "version: 1\n");
        writeConfig("endpoints/orders.yaml", configDocumentOfExactly(MAX_CONFIG_FILE_BYTES + 1));

        // Act
        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // Assert
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "endpoints/orders.yaml".equals(error.file())
                                && error.message().contains(String.valueOf(MAX_CONFIG_FILE_BYTES))),
                () -> "an over-cap endpoint file must be refused by the same byte cap, got: "
                        + exception.errors());
    }

    @Test
    void bindsWellFormedAssetContentTypeAdditions() throws Exception {
        // The schema bound on asset_defaults.content_types refuses malformed values, not the feature:
        // an ordinary addition, with and without a media-type parameter, must still bind.
        writeConfig("gateway.yaml", """
                version: 1
                asset_defaults:
                  content_types:
                    avif: image/avif
                    m4v: video/mp4;codecs=avc1
                """);

        ConfigLoader.LoadedConfig loaded = loader(Map.of()).load();

        assertEquals(Map.of("avif", "image/avif", "m4v", "video/mp4;codecs=avc1"),
                loaded.gateway().assetDefaults().contentTypes(),
                "a well-formed media type, with and without a parameter, must still bind");
    }

    @Test
    void refusesAnAssetContentTypeValueCarryingATrailingNewline() throws Exception {
        // The value is served verbatim as the asset's Content-Type response header, so a trailing CR/LF is a
        // response-header-injection vector. Both patterns in this block terminate with a negative lookahead
        // rather than '$' because '$' also matches before a final line terminator: the validator library
        // currently applies 'pattern' with whole-input semantics, under which '$' would be safe, but the
        // lookahead makes the bound hold under find() semantics too rather than depending on that choice.
        writeConfig("gateway.yaml", """
                version: 1
                asset_defaults:
                  content_types:
                    avif: "image/avif\\n"
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        // The refusal echoes the offending pattern verbatim, so assert on that rather than on the surrounding
        // sentence — the validator's diagnostics are localized and the sentence changes with the JVM locale.
        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && "/asset_defaults/content_types/avif".equals(error.pointer())
                                && error.message().contains("(?![\\s\\S])")),
                () -> "a content-type value with a trailing newline must be refused by the value pattern at "
                        + "its own pointer, got: " + exception.errors());
    }

    @Test
    void refusesAnAssetContentTypeExtensionKeyCarryingATrailingNewline() throws Exception {
        // The KEY half must close its anchor exactly as the value half does. A 'png\n' key is not the same
        // string as 'png', so it slips past the add-only boot fence (which compares against the built-in
        // extension set) and re-opens the immutability bound that stops an operator remapping a built-in
        // extension — the stored-XSS lever the add-only rule exists to close. Pinned here so the bound is
        // asserted at the gate that actually runs, not inferred from the pattern text.
        writeConfig("gateway.yaml", """
                version: 1
                asset_defaults:
                  content_types:
                    "png\\n": image/png
                """);

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load);

        assertTrue(exception.errors().stream()
                        .anyMatch(error -> "gateway.yaml".equals(error.file())
                                && "/asset_defaults/content_types".equals(error.pointer())
                                && error.message().contains("^[a-z0-9]+(?![\\s\\S])")),
                () -> "an extension key with a trailing newline must be refused by the propertyNames pattern, "
                        + "got: " + exception.errors());
    }

    @Test
    void scansAnOversizedAssetContentTypeValueWithoutExhaustingTheStack() throws Exception {
        // 50 000 well-formed ';name=value' parameters plus a trailing newline: a ~200 KB value that is both
        // over the schema's maxLength AND malformed, so BOTH keywords must report. The maxLength refusal
        // proves the cap fired; the pattern refusal proves the regex engine actually SCANNED the whole input
        // instead of dying on it. The superseded nested-quantifier pattern compiled to a java.util.regex Loop
        // node that recursed once per parameter, so this input threw StackOverflowError out of the schema
        // gate — which runs BEFORE bind, and therefore before ConfigValidator's hardened possessive pattern
        // is ever reached. A StackOverflowError escaping here is the regression.
        writeConfig("gateway.yaml", """
                version: 1
                asset_defaults:
                  content_types:
                    avif: "text/plain%s\\n"
                """.formatted(";a=b".repeat(50_000)));

        ConfigLoader loader = loader(Map.of());
        ConfigLoadException exception = assertThrows(ConfigLoadException.class, loader::load,
                "an oversized malformed content-type value must fail the boot as an aggregated "
                        + "ConfigLoadException, never as a StackOverflowError out of the regex engine");

        List<ConfigError> contentTypeErrors = exception.errors().stream()
                .filter(error -> "gateway.yaml".equals(error.file()) && error.pointer().contains("content_types"))
                .toList();
        assertEquals(2, contentTypeErrors.size(),
                () -> "both the length cap and the shape pattern must report — one alone would mean the "
                        + "pattern never ran on the oversized input, got: " + exception.errors());
        assertTrue(contentTypeErrors.stream().anyMatch(error -> error.message().contains("255")),
                () -> "expected the maxLength refusal to name the 255-character cap, got: " + contentTypeErrors);
    }
}
