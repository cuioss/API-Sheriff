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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed integration-test deployment descriptors
 * actually <strong>activate</strong> the accept-time TLS edge and the mTLS listener — the exact wiring
 * whose absence made the five TLS-edge ITs ({@code TlsPassthroughIT}, {@code MtlsHandshakeIT},
 * {@code HostSmuggleGuardIT}, {@code PassthroughFaultIT}) pass their black-box setup yet fail their
 * assertions against the real Docker runtime.
 * <p>
 * The production TLS components ({@code tls/SniFrontListener}, {@code tls/MtlsServerCustomizer},
 * {@code pipeline/PassthroughHostGuardStage}, …) are each unit-covered and correct in isolation; the
 * whole edge is nonetheless <em>opt-in</em>, so a mounted {@code gateway.yaml} that omits
 * {@code tls.passthrough_sni} / {@code tls.mtls}, a compose stack that never performs the
 * public/internal port split, an absent mTLS gateway instance, or missing client-cert material leaves
 * every component inert. A per-component unit test structurally cannot see that gap; this descriptor
 * assertion is the lowest-level regression guard that can.
 * <p>
 * It parses the committed descriptors only (YAML / properties / POM text) and asserts the activation
 * is present — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class TlsEdgeActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path CERTS = DOCKER.resolve("certificates");

    private static final String PASSTHROUGH_SNI = "passthrough.test.example";
    private static final String FAULT_SNI = "fault.test.example";

    @Test
    @DisplayName("the mounted gateway.yaml declares a non-empty passthrough_sni mapping the test SNIs to aliases")
    void primaryGatewayDeclaresPassthroughSni() throws Exception {
        // Arrange
        Map<String, Object> tls = tlsBlock(DOCKER.resolve("sheriff-config/gateway.yaml"));

        // Act
        Object passthrough = tls.get("passthrough_sni");

        // Assert — the front listener (and the runtime Host-smuggle guard) only activate on a
        // non-empty passthrough_sni that names the test hostnames the ITs open connections to.
        assertNotNull(passthrough, "tls.passthrough_sni must be present so the SNI front listener starts");
        assertInstanceOf(Map.class, passthrough, "tls.passthrough_sni must be a map of SNI -> alias");
        @SuppressWarnings("unchecked")
        Map<String, Object> sniMap = (Map<String, Object>) passthrough;
        assertFalse(sniMap.isEmpty(), "tls.passthrough_sni must be non-empty to activate the accept-time split");
        assertTrue(sniMap.containsKey(PASSTHROUGH_SNI),
                "tls.passthrough_sni must map the passthrough SNI '" + PASSTHROUGH_SNI + "'");
        assertTrue(sniMap.containsKey(FAULT_SNI),
                "tls.passthrough_sni must map the fault-injection SNI '" + FAULT_SNI + "'");
    }

    @Test
    @DisplayName("every passthrough_sni alias resolves base-path-free in topology.properties")
    void passthroughAliasesResolveBasePathFree() throws Exception {
        // Arrange
        Map<String, Object> tls = tlsBlock(DOCKER.resolve("sheriff-config/gateway.yaml"));
        Object passthrough = tls.get("passthrough_sni");
        assertInstanceOf(Map.class, passthrough, "tls.passthrough_sni must be a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> sniMap = (Map<String, Object>) passthrough;
        Properties topology = topology();

        // Act + Assert — mirror ConfigValidator.validatePassthroughAliasResolvable: the alias must be
        // present in topology.properties and resolve to an origin without a base path.
        for (Map.Entry<String, Object> entry : sniMap.entrySet()) {
            String alias = String.valueOf(entry.getValue());
            String url = topology.getProperty(alias);
            assertNotNull(url, "passthrough_sni alias '" + alias + "' (SNI '" + entry.getKey()
                    + "') must resolve in topology.properties");
            String path = URI.create(url.trim()).getPath();
            assertTrue(path == null || path.isEmpty() || "/".equals(path),
                    "passthrough_sni alias '" + alias + "' must resolve to a base-path-free origin, was: " + url);
        }
    }

    @Test
    @DisplayName("the mTLS instance gateway.yaml enables mtls with a client_ca trust anchor")
    void mtlsGatewayEnablesClientAuth() throws Exception {
        // Arrange
        Map<String, Object> tls = tlsBlock(DOCKER.resolve("sheriff-config-mtls/gateway.yaml"));

        // Act
        Object mtls = tls.get("mtls");

        // Assert — MtlsServerCustomizer flips the terminated listener to require-and-verify only when
        // mtls.enabled is set; a missing client_ca fails the boot fast, so both must be present.
        assertNotNull(mtls, "the mTLS instance must declare tls.mtls to require client certificates");
        assertInstanceOf(Map.class, mtls, "tls.mtls must be a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> mtlsMap = (Map<String, Object>) mtls;
        assertEquals(Boolean.TRUE, mtlsMap.get("enabled"), "tls.mtls.enabled must be true on the mTLS instance");
        assertNotNull(mtlsMap.get("client_ca"), "tls.mtls.client_ca trust anchor must be configured");
    }

    @Test
    @DisplayName("docker-compose splits the primary listener to the internal port and publishes the mTLS instance")
    void composePerformsPortSplitAndPublishesMtlsInstance() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Act — the primary gateway must move its terminated Quarkus HTTPS listener to the internal
        // port so the SNI front owns the public port without a bind conflict.
        List<String> primaryEnv = environment(services, "api-sheriff");

        // Assert
        assertTrue(primaryEnv.contains("QUARKUS_HTTP_SSL_PORT=8444"),
                "the primary api-sheriff service must set QUARKUS_HTTP_SSL_PORT=8444 (internal-port split)");

        Object mtlsService = services.get("api-sheriff-mtls");
        assertNotNull(mtlsService, "a dedicated api-sheriff-mtls gateway instance must be defined");
        @SuppressWarnings("unchecked")
        Map<String, Object> mtls = (Map<String, Object>) mtlsService;
        Object ports = mtls.get("ports");
        assertInstanceOf(List.class, ports, "the mTLS instance must publish ports");
        boolean publishesMtlsPort = ((List<?>) ports).stream()
                .map(String::valueOf)
                .anyMatch(p -> p.startsWith("10444:"));
        assertTrue(publishesMtlsPort, "the mTLS instance must publish host port 10444 for MtlsHandshakeIT");
    }

    @Test
    @DisplayName("the client and wrong-CA mTLS keystores are provisioned")
    void mtlsKeystoresExist() {
        // Assert — MtlsHandshakeIT loads these PKCS#12 keystores from the host; they must be generated.
        assertTrue(Files.isRegularFile(CERTS.resolve("mtls-client.p12")),
                "the trusted client keystore mtls-client.p12 must exist");
        assertTrue(Files.isRegularFile(CERTS.resolve("mtls-wrong.p12")),
                "the foreign-CA client keystore mtls-wrong.p12 must exist");
        assertTrue(Files.isRegularFile(CERTS.resolve("mtls-client-ca.crt")),
                "the client CA trust anchor mtls-client-ca.crt must exist for the mTLS instance");
    }

    @Test
    @DisplayName("the Failsafe configuration wires the test.mtls.* system properties")
    void failsafeWiresMtlsSystemProperties() throws Exception {
        // Arrange
        String pom = Files.readString(MODULE.resolve("pom.xml"));

        // Assert — without these, MtlsHandshakeIT falls back to null keystores / port 10443 and cannot
        // reach the mTLS instance with a client identity.
        assertTrue(pom.contains("<test.mtls.port>"), "pom must wire test.mtls.port to the mTLS instance port");
        assertTrue(pom.contains("<test.mtls.client.keystore>"),
                "pom must wire test.mtls.client.keystore to the trusted client keystore");
        assertTrue(pom.contains("<test.mtls.wrong.keystore>"),
                "pom must wire test.mtls.wrong.keystore to the foreign-CA client keystore");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tlsBlock(Path gatewayYaml) throws IOException {
        Map<String, Object> doc = loadYaml(gatewayYaml);
        Object tls = doc.get("tls");
        assertNotNull(tls, "gateway.yaml " + gatewayYaml + " must declare a tls block");
        assertInstanceOf(Map.class, tls, "the tls block must be a mapping");
        return (Map<String, Object>) tls;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> doc = loadYaml(MODULE.resolve("docker-compose.yml"));
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    @SuppressWarnings("unchecked")
    private static List<String> environment(Map<String, Object> services, String service) {
        Object node = services.get(service);
        assertNotNull(node, "docker-compose.yml must declare the '" + service + "' service");
        Map<String, Object> serviceMap = (Map<String, Object>) node;
        Object env = serviceMap.get("environment");
        assertInstanceOf(List.class, env, "the '" + service + "' service environment must be a list");
        return ((List<?>) env).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }

    private static Properties topology() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(DOCKER.resolve("sheriff-config/topology.properties"))) {
            properties.load(reader);
        }
        return properties;
    }
}
