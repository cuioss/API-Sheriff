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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed integration-test deployment descriptors
 * actually <strong>activate</strong> the Variant 3 cookie-mode session — the exact wiring whose
 * absence would let the cookie ITs ({@code BffCookieSessionIT}, {@code BffCookieStatelessnessIT},
 * {@code BffCookieBackchannelDisabledIT}) pass their black-box setup while silently exercising the
 * landed server-mode binding instead.
 * <p>
 * The cookie-mode components ({@code bff/cookie/CookieSessionBinding},
 * {@code bff/cookie/SealedSessionCookieCodec}, {@code bff/cookie/CookieKeyMaterial}) are each
 * unit-covered and correct in isolation; the whole variant is nonetheless <em>opt-in</em>, so a
 * mounted {@code gateway.yaml} that keeps {@code session.mode: server}, a compose stack that never
 * defines the cookie instance, or an overlay that is never mounted leaves every component inert. A
 * per-component unit test structurally cannot see that gap; this descriptor assertion is the
 * lowest-level regression guard that can.
 * <p>
 * It parses the committed descriptors only (YAML text) and asserts the activation is present — it
 * starts no container and reaches no network. Modelled on {@code TlsEdgeActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path COOKIE_GATEWAY = DOCKER.resolve("sheriff-config-cookie/gateway.yaml");

    private static final String COOKIE_SERVICE = "api-sheriff-cookie";
    private static final String COOKIE_OVERLAY_MOUNT =
            "./src/main/docker/sheriff-config-cookie/gateway.yaml:/app/sheriff-config/gateway.yaml:ro";

    @Test
    @DisplayName("the cookie instance gateway.yaml declares session.mode cookie with no store")
    void cookieGatewayDeclaresCookieSessionMode() throws Exception {
        // Arrange
        Map<String, Object> session = sessionBlock();

        // Assert — BffRuntimeProducer selects the stateless CookieSessionBinding only on
        // session.mode=cookie; a 'store' here would be a server-mode leftover contradicting the
        // stateless binding, which by construction holds no server-side session index.
        assertEquals("cookie", session.get("mode"),
                "oidc.session.mode must be 'cookie' to activate the stateless sealed-cookie binding");
        assertNull(session.get("store"),
                "a stateless binding holds no server-side index — oidc.session.store must be absent");
    }

    @Test
    @DisplayName("the cookie instance supplies the sealing key as a bare ${ENV_VAR} reference")
    void cookieGatewayReferencesTheSealingKeyByEnvVar() throws Exception {
        // Arrange
        Map<String, Object> session = sessionBlock();

        // Act
        Object encryptionKey = session.get("encryption_key");

        // Assert — the D4 secrets rule refuses a literal, and an explicitly passed key (rather than
        // the generate-on-startup mode) is what makes a sealed cookie portable across instances that
        // share nothing else — the property BffCookieStatelessnessIT proves.
        assertNotNull(encryptionKey,
                "oidc.session.encryption_key must be configured so the key is shared, not generated per boot");
        String reference = String.valueOf(encryptionKey);
        assertTrue(reference.startsWith("${") && reference.endsWith("}"),
                "the sealing key must be a bare ${ENV_VAR} reference, was: " + reference);
    }

    @Test
    @DisplayName("the cookie instance still registers the back-channel logout reserved path")
    void cookieGatewayStillRegistersTheBackchannelPath() throws Exception {
        // Arrange
        Map<String, Object> oidc = oidcBlock();
        Object logout = oidc.get("logout");
        assertInstanceOf(Map.class, logout, "the oidc.logout block must be a mapping");
        @SuppressWarnings("unchecked")
        Map<String, Object> logoutMap = (Map<String, Object>) logout;

        // Assert — the endpoint's own capability gate answers a deliberate 404 in cookie mode, so the
        // reserved path must stay registered; dropping it here would let the path fall through to the
        // proxy route table instead, which is exactly what BffCookieBackchannelDisabledIT rejects.
        assertNotNull(logoutMap.get("backchannel_path"),
                "oidc.logout.backchannel_path must stay declared so the gate answers 404, not a route fall-through");
    }

    @Test
    @DisplayName("docker-compose defines the cookie gateway service mounting the cookie overlay")
    void composeDefinesTheCookieInstance() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Act
        Object cookieService = services.get(COOKIE_SERVICE);

        // Assert — without a dedicated instance the cookie ITs would drive the primary server-mode
        // gateway and pass against the wrong binding.
        assertNotNull(cookieService, "a dedicated " + COOKIE_SERVICE + " gateway instance must be defined");
        @SuppressWarnings("unchecked")
        Map<String, Object> cookie = (Map<String, Object>) cookieService;

        Object ports = cookie.get("ports");
        assertInstanceOf(List.class, ports, "the cookie instance must publish ports");
        boolean publishesCookiePort = ((List<?>) ports).stream()
                .map(String::valueOf)
                .anyMatch(p -> p.startsWith("10445:"));
        assertTrue(publishesCookiePort, "the cookie instance must publish host port 10445 for the cookie ITs");

        Object volumes = cookie.get("volumes");
        assertInstanceOf(List.class, volumes, "the cookie instance must declare volumes");
        List<String> mounts = ((List<?>) volumes).stream().map(String::valueOf).toList();
        assertTrue(mounts.contains(COOKIE_OVERLAY_MOUNT),
                "the cookie instance must overlay ONLY gateway.yaml with the cookie variant, was: " + mounts);
    }

    @Test
    @DisplayName("the cookie gateway service supplies the sealing key environment variable")
    void composeSuppliesTheSealingKey() throws Exception {
        // Arrange
        List<String> environment = environment(composeServices(), COOKIE_SERVICE);

        // Act — the variable the overlaid gateway.yaml's bare ${SESSION_ENCRYPTION_KEY} resolves to.
        boolean suppliesKey = environment.stream().anyMatch(entry -> entry.startsWith("SESSION_ENCRYPTION_KEY="));

        // Assert — a bare reference naming an undefined variable aborts the boot, so an absent value
        // here is a hard startup failure rather than a silent fallback.
        assertTrue(suppliesKey,
                "the cookie instance must supply SESSION_ENCRYPTION_KEY for the gateway.yaml reference");
        assertFalse(environment.stream().anyMatch(entry -> entry.equals("SESSION_ENCRYPTION_KEY=")),
                "SESSION_ENCRYPTION_KEY must carry a value — a blank key cannot seal a session");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> oidcBlock() throws IOException {
        Map<String, Object> doc = loadYaml(COOKIE_GATEWAY);
        Object oidc = doc.get("oidc");
        assertNotNull(oidc, "the cookie gateway.yaml must declare an oidc block");
        assertInstanceOf(Map.class, oidc, "the oidc block must be a mapping");
        return (Map<String, Object>) oidc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessionBlock() throws IOException {
        Object session = oidcBlock().get("session");
        assertNotNull(session, "the cookie gateway.yaml must declare an oidc.session block");
        assertInstanceOf(Map.class, session, "the oidc.session block must be a mapping");
        return (Map<String, Object>) session;
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
}
