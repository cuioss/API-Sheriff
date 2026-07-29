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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed integration-test deployment descriptors
 * actually <strong>activate</strong> the low admission budget the relay-permit exhaustion regression
 * depends on — the exact wiring whose absence would let that regression pass while proving nothing.
 * <p>
 * The failure mode this test exists for is silent, and it is the reason a descriptor assertion is
 * warranted at all. {@code WebSocketProxyIT}'s exhaustion test opens ten sequential relays and
 * asserts they all succeed. Against a gateway running the shipped defaults — 2048 general permits and
 * 512 relay permits — ten relays succeed <em>whether or not</em> the relay ever returns a permit,
 * because ten never approaches either cap. So an overlay that is never mounted, a compose service
 * that is never defined, or an {@code edge_hardening} block whose caps drift upward all leave a green
 * test that has stopped testing anything. No black-box assertion inside the IT itself can see that:
 * the wire looks identical either way.
 * <p>
 * It parses the committed descriptors only (YAML text) and asserts the activation is present — it
 * starts no container and reaches no network. Modelled on {@code TlsEdgeActivationWiringTest} and
 * {@code BffCookieActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class WsAdmissionActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path WS_ADMISSION_GATEWAY = DOCKER.resolve("sheriff-config-ws-admission/gateway.yaml");

    private static final String WS_ADMISSION_SERVICE = "api-sheriff-ws-admission";

    private static final String WS_ADMISSION_OVERLAY_MOUNT =
            "./src/main/docker/sheriff-config-ws-admission/gateway.yaml:/app/sheriff-config/gateway.yaml:ro";

    /**
     * The published host port {@code WebSocketProxyIT} reaches the low-cap instance on, kept in
     * lockstep with the {@code test.ws.admission.port} failsafe system property in {@code pom.xml}.
     */
    private static final String WS_ADMISSION_PORT_PREFIX = "10447:";

    /**
     * The ceiling both caps must stay under. The exhaustion regression drives ten sequential
     * upgrades, so any cap at ten or above is never reached and the regression silently stops
     * regressing — this bound is what makes cap drift a build failure rather than a quiet no-op.
     */
    private static final int SEQUENTIAL_UPGRADES = 10;

    @Test
    @DisplayName("the ws-admission overlay declares an edge_hardening budget small enough to exhaust")
    void overlayDeclaresAnExhaustibleAdmissionBudget() throws Exception {
        // Arrange
        Map<String, Object> hardening = edgeHardeningBlock();

        // Act
        Object admissionCap = hardening.get("admission_cap");
        Object relayCap = hardening.get("websocket_relay_cap");

        // Assert — an absent member silently resolves to the shipped default (2048 / 512), which the
        // regression can never reach, so both must be declared explicitly and both must stay below
        // the number of upgrades the regression actually drives.
        assertInstanceOf(Integer.class, admissionCap, "edge_hardening.admission_cap must be declared explicitly");
        assertInstanceOf(Integer.class, relayCap, "edge_hardening.websocket_relay_cap must be declared explicitly");
        assertTrue((Integer) admissionCap < SEQUENTIAL_UPGRADES,
                "admission_cap must stay below the " + SEQUENTIAL_UPGRADES
                        + " sequential upgrades the regression drives, was: " + admissionCap);
        assertTrue((Integer) relayCap < SEQUENTIAL_UPGRADES,
                "websocket_relay_cap must stay below the " + SEQUENTIAL_UPGRADES
                        + " sequential upgrades the regression drives, was: " + relayCap);
    }

    @Test
    @DisplayName("the relay sub-budget stays within the general pool it draws from")
    void theRelaySubBudgetStaysWithinTheGeneralPool() throws Exception {
        // Arrange
        Map<String, Object> hardening = edgeHardeningBlock();

        // Act
        int admissionCap = (Integer) hardening.get("admission_cap");
        int relayCap = (Integer) hardening.get("websocket_relay_cap");

        // Assert — a relay permit is taken IN ADDITION to a general one, so a sub-budget larger than
        // the pool can never bind. ConfigValidator refuses that at boot, which would turn a descriptor
        // mistake into a stack that never reaches readiness rather than a legible test failure.
        assertTrue(relayCap <= admissionCap,
                "websocket_relay_cap (" + relayCap + ") must not exceed admission_cap (" + admissionCap + ")");
    }

    @Test
    @DisplayName("the overlay terminates directly on the public port with no passthrough front")
    void overlayDeclaresNoPassthroughFront() throws Exception {
        // Arrange
        Map<String, Object> doc = loadYaml(WS_ADMISSION_GATEWAY);
        Object tls = doc.get("tls");
        assertInstanceOf(Map.class, tls, "the ws-admission gateway.yaml must declare a tls block");
        @SuppressWarnings("unchecked")
        Map<String, Object> tlsMap = (Map<String, Object>) tls;

        // Assert — a declared passthrough_sni starts the SNI front listener on the public port, which
        // would require the terminated listener to move aside (QUARKUS_HTTP_SSL_PORT) exactly as the
        // primary instance does. This instance publishes no such override, so declaring one here
        // would bind-conflict at boot. Same shape as the mTLS and cookie overlays.
        assertNull(tlsMap.get("passthrough_sni"),
                "the ws-admission overlay must not declare tls.passthrough_sni — it terminates on the public port");
    }

    @Test
    @DisplayName("docker-compose defines the ws-admission instance mounting the low-cap overlay")
    void composeDefinesTheWsAdmissionInstance() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Act
        Object wsAdmissionService = services.get(WS_ADMISSION_SERVICE);

        // Assert — without a dedicated instance the exhaustion regression would drive the primary
        // gateway's production-shaped caps and pass without exhausting anything.
        assertNotNull(wsAdmissionService,
                "a dedicated " + WS_ADMISSION_SERVICE + " gateway instance must be defined");
        @SuppressWarnings("unchecked")
        Map<String, Object> wsAdmission = (Map<String, Object>) wsAdmissionService;

        Object ports = wsAdmission.get("ports");
        assertInstanceOf(List.class, ports, "the ws-admission instance must publish ports");
        assertTrue(((List<?>) ports).stream().map(String::valueOf).anyMatch(p -> p.startsWith(WS_ADMISSION_PORT_PREFIX)),
                "the ws-admission instance must publish host port 10447 for test.ws.admission.port");

        Object volumes = wsAdmission.get("volumes");
        assertInstanceOf(List.class, volumes, "the ws-admission instance must declare volumes");
        List<String> mounts = ((List<?>) volumes).stream().map(String::valueOf).toList();
        assertTrue(mounts.contains(WS_ADMISSION_OVERLAY_MOUNT),
                "the ws-admission instance must overlay ONLY gateway.yaml with the low-cap variant, was: " + mounts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> edgeHardeningBlock() throws IOException {
        Map<String, Object> doc = loadYaml(WS_ADMISSION_GATEWAY);
        Object hardening = doc.get("edge_hardening");
        assertNotNull(hardening, "the ws-admission gateway.yaml must declare an edge_hardening block");
        assertInstanceOf(Map.class, hardening, "the edge_hardening block must be a mapping");
        return (Map<String, Object>) hardening;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> doc = loadYaml(MODULE.resolve("docker-compose.yml"));
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
