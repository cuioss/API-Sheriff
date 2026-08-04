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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
 * The {@code sheriff-config-passthrough-empty} overlay is the deliberate inverse and is guarded the
 * same way: the benchmark's empty-mode arm only measures D1's zero-overhead default while that
 * instance declares NO {@code passthrough_sni}, performs no internal-port split, differs from the
 * base descriptor in that one key and nothing else, and shares the primary's benchmark upstream. All
 * four are structural facts here rather than review conventions — the arm was previously pointed at
 * the primary instance, so the no-regression gate was comparing a configuration against itself.
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

    /** The shared base descriptor every overlay is derived from. */
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");

    /**
     * The overlay backing the benchmark's empty-passthrough arm — the base descriptor with the
     * {@code tls.passthrough_sni} block, and only that block, removed.
     */
    private static final Path EMPTY_PASSTHROUGH_DESCRIPTOR =
            DOCKER.resolve("sheriff-config-passthrough-empty/gateway.yaml");

    /** The compose service that mounts {@link #EMPTY_PASSTHROUGH_DESCRIPTOR}. */
    private static final String EMPTY_PASSTHROUGH_SERVICE = "api-sheriff-passthrough-empty";

    /** The primary gateway service the empty arm is measured against. */
    private static final String PRIMARY_SERVICE = "api-sheriff";

    private static final String PASSTHROUGH_SNI_KEY = "passthrough_sni";
    private static final String INTERNAL_SSL_PORT_KEY = "QUARKUS_HTTP_SSL_PORT";
    private static final String UPSTREAM_KEY = "TOPOLOGY_UPSTREAM";
    private static final String STATIC_BACKEND_SERVICE = "nginx-static";

    private static final String PASSTHROUGH_SNI = "passthrough.test.example";
    private static final String FAULT_SNI = "fault.test.example";

    @Test
    @DisplayName("the mounted gateway.yaml declares a non-empty passthrough_sni mapping the test SNIs to aliases")
    void primaryGatewayDeclaresPassthroughSni() throws Exception {
        // Arrange
        Map<String, Object> tls = tlsBlock(BASE_DESCRIPTOR);

        // Act
        Object passthrough = tls.get(PASSTHROUGH_SNI_KEY);

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
        Map<String, Object> tls = tlsBlock(BASE_DESCRIPTOR);
        Object passthrough = tls.get(PASSTHROUGH_SNI_KEY);
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
        Map<String, String> primaryEnv = environmentEntries(services, PRIMARY_SERVICE);

        // Assert
        assertEquals("8444", primaryEnv.get(INTERNAL_SSL_PORT_KEY),
                "the primary api-sheriff service must set " + INTERNAL_SSL_PORT_KEY
                        + "=8444 (internal-port split)");

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
    @DisplayName("the empty-passthrough overlay declares no passthrough_sni at all")
    void emptyPassthroughOverlayDeclaresNoPassthroughSni() throws Exception {
        // Arrange
        Map<String, Object> tls = tlsBlock(EMPTY_PASSTHROUGH_DESCRIPTOR);

        // Act
        Object passthrough = tls.get(PASSTHROUGH_SNI_KEY);

        // Assert — this is the mirror image of primaryGatewayDeclaresPassthroughSni. A non-empty
        // mapping here STARTS the accept-time SNI front listener, which is precisely the thing the
        // `passthroughRelayEmpty` arm claims is never created; the arm would then measure the
        // identical listener stack as the baseline it is compared against. An empty mapping is
        // tolerated because it activates nothing, but the key is expected to be absent outright.
        assertTrue(passthrough == null || (passthrough instanceof Map<?, ?> sni && sni.isEmpty()),
                EMPTY_PASSTHROUGH_DESCRIPTOR + " must declare no tls." + PASSTHROUGH_SNI_KEY
                        + " (or an empty one), otherwise the SNI front listener starts and the"
                        + " empty-mode benchmark arm measures the configuration it exists to exclude,"
                        + " was: " + passthrough);
    }

    @Test
    @DisplayName("the empty-passthrough instance performs no internal-port split")
    void emptyPassthroughInstanceSetsNoInternalSslPort() throws Exception {
        // Arrange
        Map<String, String> environment = environmentEntries(composeServices(), EMPTY_PASSTHROUGH_SERVICE);

        // Assert — the split exists only to free the public port for a front listener. With no
        // passthrough_sni there is no front listener, so relocating the terminated listener would
        // move it off 8443 with nothing left listening on the published port and the instance would
        // never reach readiness.
        assertFalse(environment.containsKey(INTERNAL_SSL_PORT_KEY),
                "the '" + EMPTY_PASSTHROUGH_SERVICE + "' service must set no " + INTERNAL_SSL_PORT_KEY
                        + " — with no front listener the terminated listener must keep the public port,"
                        + " was: " + environment.get(INTERNAL_SSL_PORT_KEY));
    }

    @Test
    @DisplayName("the empty-passthrough overlay differs from the base by passthrough_sni and nothing else")
    void emptyPassthroughOverlayDiffersFromTheBaseOnlyByPassthroughSni() throws Exception {
        // Arrange — compare PARSED structures, not file text, so comment and formatting differences
        // (the overlay carries its own header) are correctly irrelevant.
        Map<String, Object> base = loadYaml(BASE_DESCRIPTOR);
        Map<String, Object> variant = loadYaml(EMPTY_PASSTHROUGH_DESCRIPTOR);

        // Act — remove the one key the overlay exists to drop.
        Object baseTls = base.get("tls");
        assertInstanceOf(Map.class, baseTls, BASE_DESCRIPTOR + " must declare a tls block");
        ((Map<?, ?>) baseTls).remove(PASSTHROUGH_SNI_KEY);

        // Assert — the single-variable property the whole comparison rests on. Any second difference
        // (a security_defaults tweak, a diverged anchor, a drifted issuer) silently turns the
        // no-regression gate into an uncontrolled comparison, because the measured delta would then
        // carry that difference too rather than passthrough activation alone.
        assertEquals(base, variant, EMPTY_PASSTHROUGH_DESCRIPTOR + " must be " + BASE_DESCRIPTOR
                + " with tls." + PASSTHROUGH_SNI_KEY + " removed and nothing else changed; a second"
                + " difference makes the two benchmark arms differ in more than the one variable"
                + " under measurement");
    }

    @Test
    @DisplayName("the empty-passthrough instance shares the primary's benchmark upstream")
    void emptyPassthroughInstanceSharesThePrimaryBenchmarkUpstream() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices(MODULE.resolve("docker-compose.benchmark.yml"));
        String primaryUpstream = environmentEntries(services, PRIMARY_SERVICE).get(UPSTREAM_KEY);
        String emptyUpstream = environmentEntries(services, EMPTY_PASSTHROUGH_SERVICE).get(UPSTREAM_KEY);

        // Assert — the ADR-0012 "identical upstreams" parity precondition, asserted as an EQUALITY
        // between the two declared values rather than against a hard-coded literal, so repointing the
        // benchmark at a different backend keeps the two arms in lockstep instead of breaking here.
        assertNotNull(primaryUpstream,
                "the '" + PRIMARY_SERVICE + "' service must declare " + UPSTREAM_KEY
                        + " in docker-compose.benchmark.yml");
        assertEquals(primaryUpstream, emptyUpstream,
                "the '" + EMPTY_PASSTHROUGH_SERVICE + "' service must declare the same " + UPSTREAM_KEY
                        + " as '" + PRIMARY_SERVICE + "'; a different backend on one side folds that"
                        + " backend's cost into the comparison and is read as passthrough overhead");
        assertTrue(dependsOn(services, EMPTY_PASSTHROUGH_SERVICE).contains(STATIC_BACKEND_SERVICE),
                "the '" + EMPTY_PASSTHROUGH_SERVICE + "' service must declare '" + STATIC_BACKEND_SERVICE
                        + "' among its depends_on, or the benchmark can start against a backend that"
                        + " is not up yet");
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

    private static Map<String, Object> composeServices() throws IOException {
        return composeServices(MODULE.resolve("docker-compose.yml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices(Path composeFile) throws IOException {
        Map<String, Object> doc = loadYaml(composeFile);
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, composeFile + " must declare services");
        return (Map<String, Object>) services;
    }

    /**
     * A compose service's environment as key/value pairs, accepting BOTH compose forms — the
     * {@code - KEY=VALUE} list this stack uses today and the {@code KEY: VALUE} mapping compose also
     * accepts. Reading only the list form would let a form switch silently turn an absence assertion
     * vacuously green.
     *
     * @param services the parsed {@code services} block
     * @param service the service name
     * @return the declared environment, empty when the service declares none
     */
    private static Map<String, String> environmentEntries(Map<String, Object> services, String service) {
        Object node = services.get(service);
        assertNotNull(node, "compose must declare the '" + service + "' service");
        assertInstanceOf(Map.class, node, "the '" + service + "' service must be a mapping");
        Object env = ((Map<?, ?>) node).get("environment");
        Map<String, String> entries = new LinkedHashMap<>();
        switch (env) {
            case Map<?, ?> mapForm -> mapForm.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
            case Iterable<?> listForm -> {
                for (Object entry : listForm) {
                    String text = String.valueOf(entry);
                    int split = text.indexOf('=');
                    if (split < 0) {
                        entries.put(text, "");
                    } else {
                        entries.put(text.substring(0, split), text.substring(split + 1));
                    }
                }
            }
            case null, default -> assertNull(env, "the '" + service + "' service environment must be a list or a mapping, was: " + env);
        }
        return entries;
    }

    /**
     * A compose service's {@code depends_on} names, accepting BOTH the short list form and the long
     * {@code service: {condition: ...}} mapping form this stack uses in different files.
     *
     * @param services the parsed {@code services} block
     * @param service the service name
     * @return the declared dependency names, empty when the service declares none
     */
    private static Set<String> dependsOn(Map<String, Object> services, String service) {
        Object node = services.get(service);
        assertNotNull(node, "compose must declare the '" + service + "' service");
        assertInstanceOf(Map.class, node, "the '" + service + "' service must be a mapping");
        Object declared = ((Map<?, ?>) node).get("depends_on");
        Set<String> names = new LinkedHashSet<>();
        switch (declared) {
            case Map<?, ?> mapForm -> mapForm.keySet().forEach(key -> names.add(String.valueOf(key)));
            case Iterable<?> listForm -> listForm.forEach(entry -> names.add(String.valueOf(entry)));
            case null, default -> assertNull(declared,
                    "the '" + service + "' service depends_on must be a list or a mapping, was: " + declared);
        }
        return names;
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
