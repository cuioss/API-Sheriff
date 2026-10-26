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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed integration-test deployment descriptors
 * actually <strong>activate</strong> the Variant 3 cookie-mode session — the exact wiring whose
 * absence would let the cookie ITs ({@code BffCookieSessionIT}, {@code BffCookieStatelessnessIT},
 * {@code BffCookieBackchannelDisabledIT}, {@code BffCookieRefreshIT}) pass their black-box setup while
 * silently exercising the landed server-mode binding instead.
 * <p>
 * The cookie-mode components ({@code bff/cookie/CookieSessionBinding},
 * {@code bff/cookie/SealedSessionCookieCodec}, {@code bff/cookie/CookieKeyMaterial}) are each
 * unit-covered and correct in isolation; the whole variant is nonetheless <em>opt-in</em>, so a
 * mounted {@code gateway.yaml} that keeps {@code session.mode: server}, a compose stack that never
 * defines the cookie instance, or an overlay that is never mounted leaves every component inert. A
 * per-component unit test structurally cannot see that gap; this descriptor assertion is the
 * lowest-level regression guard that can.
 * <p>
 * <strong>The sealing key files.</strong> Every cookie-mode instance reads its AES-256 sealing key
 * from the file its descriptor names through {@code oidc.session.key_file}, on the read-only
 * signing-key mount. {@code api-sheriff-cookie} and its peer {@code api-sheriff-cookie-2} mount the
 * same overlay and so read the same file, {@code cookie-sealing.key}: that shared file is the only
 * state the two share, and it is what {@code BffCookieStatelessnessIT} proves portable.
 * {@code api-sheriff-cookie-refresh} names a file of its own, {@code cookie-refresh-sealing.key}, so an
 * equal key file means exactly the statelessness pair. Each file is a committed test fixture of one
 * line of base64 that decodes to 32 bytes, and no compose service passes a key through the
 * environment.
 * <p>
 * It parses the committed descriptors and reads the committed key files only — it starts no container
 * and reaches no network. Modelled on {@code TlsEdgeActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path COOKIE_GATEWAY = DOCKER.resolve("sheriff-config-cookie/gateway.yaml");
    private static final Path SIGNING_KEYS = DOCKER.resolve("signing-keys");

    private static final String COOKIE_SERVICE = "api-sheriff-cookie";

    /** The key-sharing peer instance {@code BffCookieStatelessnessIT} replays a sealed cookie against. */
    private static final String COOKIE_PEER_SERVICE = "api-sheriff-cookie-2";

    /** The cookie-mode instance that also refreshes; it shares no state with any other instance. */
    private static final String COOKIE_REFRESH_SERVICE = "api-sheriff-cookie-refresh";

    private static final String COOKIE_OVERLAY_MOUNT =
            "./src/main/docker/sheriff-config-cookie/gateway.yaml:/app/sheriff-config/gateway.yaml:ro";

    /** Where a gateway reads its global document, whether from the shared mount or an overlay. */
    private static final String DESCRIPTOR_IN_CONTAINER = "/app/sheriff-config/gateway.yaml";

    private static final String SIGNING_KEYS_IN_CONTAINER = "/app/signing-keys";

    /** The sealing key file the cookie overlay names, shared by the cookie instance and its peer. */
    private static final String SHARED_SEALING_KEY_FILE = SIGNING_KEYS_IN_CONTAINER + "/cookie-sealing.key";

    /** The environment variable that carried the sealing key before it moved to a key file. */
    private static final String RETIRED_SEALING_KEY_VARIABLE = "SESSION_ENCRYPTION_KEY";

    /** An AES-256 key: the length every sealing key file must decode to. */
    private static final int SEALING_KEY_BYTES = 32;

    @Test
    @DisplayName("the cookie instance gateway.yaml declares session.mode cookie with no store")
    void cookieGatewayDeclaresCookieSessionMode() throws Exception {
        Map<String, Object> session = session(COOKIE_GATEWAY);

        // BffRuntimeProducer selects the stateless CookieSessionBinding only on session.mode=cookie; a
        // 'store' here would be a server-mode leftover contradicting the stateless binding, which by
        // construction holds no server-side session index.
        assertEquals("cookie", session.get("mode"),
                "oidc.session.mode must be 'cookie' to activate the stateless sealed-cookie binding");
        assertNull(session.get("store"),
                "a stateless binding holds no server-side index — oidc.session.store must be absent");
    }

    @Test
    @DisplayName("the cookie instance gateway.yaml names the shared sealing key file")
    void cookieGatewayNamesTheSharedSealingKeyFile() throws Exception {
        Map<String, Object> session = session(COOKIE_GATEWAY);

        // A named key file (rather than the generate-on-startup mode) is what makes a sealed cookie
        // portable across instances that share nothing else — the property BffCookieStatelessnessIT
        // proves. The retired encryption_key would be refused by the schema.
        assertEquals(SHARED_SEALING_KEY_FILE, session.get("key_file"),
                "oidc.session.key_file must name the shared sealing key file, so the key is shared, not generated per boot");
        assertNull(session.get("encryption_key"), "the retired oidc.session.encryption_key must be gone");
    }

    @Test
    @DisplayName("the cookie instance still registers the back-channel logout reserved path")
    void cookieGatewayStillRegistersTheBackchannelPath() throws Exception {
        Map<String, Object> logout = mapping(oidc(COOKIE_GATEWAY).get("logout"), "the oidc.logout block");

        // The endpoint's own capability gate answers a deliberate 404 in cookie mode, so the reserved
        // path must stay registered; dropping it here would let the path fall through to the proxy
        // route table instead, which is exactly what BffCookieBackchannelDisabledIT rejects.
        assertNotNull(logout.get("backchannel_path"),
                "oidc.logout.backchannel_path must stay declared so the gate answers 404, not a route fall-through");
    }

    @Test
    @DisplayName("docker-compose defines the cookie gateway service mounting the cookie overlay")
    void composeDefinesTheCookieInstance() throws Exception {
        Map<String, Object> cookie = service(composeServices(), COOKIE_SERVICE);

        // Without a dedicated instance the cookie ITs would drive the primary server-mode gateway and
        // pass against the wrong binding.
        assertTrue(strings(cookie.get("ports")).stream().anyMatch(port -> port.startsWith("10445:")),
                "the cookie instance must publish host port 10445 for the cookie ITs");
        List<String> mounts = strings(cookie.get("volumes"));
        assertTrue(mounts.contains(COOKIE_OVERLAY_MOUNT),
                "the cookie instance must overlay ONLY gateway.yaml with the cookie variant, was: " + mounts);
    }

    @Test
    @DisplayName("docker-compose defines the key-sharing peer instance backing the statelessness proof")
    void composeDefinesThePeerInstance() throws Exception {
        Map<String, Object> peer = service(composeServices(), COOKIE_PEER_SERVICE);

        // BffCookieStatelessnessIT replays a sealed cookie against a genuinely separate process on
        // 10446; without the peer defined that suite would have no second instance and its proof
        // would silently collapse back into a single-instance round trip.
        assertTrue(strings(peer.get("ports")).stream().anyMatch(port -> port.startsWith("10446:")),
                "the peer instance must publish host port 10446 so it is addressed without sticky routing");
        List<String> mounts = strings(peer.get("volumes"));
        assertTrue(mounts.contains(COOKIE_OVERLAY_MOUNT),
                "the peer must overlay the SAME cookie gateway.yaml as the sealing instance, was: " + mounts);
    }

    @Test
    @DisplayName("the cookie instance and its peer read the one shared sealing key file and nothing else stateful")
    void thePeerReadsTheSameSealingKeyFile() throws Exception {
        Map<String, Object> services = composeServices();

        Path sealingKey = sealingKeyFile(services, COOKIE_SERVICE);
        Path peerSealingKey = sealingKeyFile(services, COOKIE_PEER_SERVICE);

        // The shared key IS the experiment: it is the only state the two processes have in common, so
        // a drifted file would turn the statelessness proof into an unexplained failure, and a shared
        // session store would turn its success into a false pass.
        assertEquals(SIGNING_KEYS.resolve("cookie-sealing.key"), sealingKey,
                "the cookie instance must read cookie-sealing.key from the signing-key mount");
        assertEquals(sealingKey, peerSealingKey, "both cookie instances must read the very same sealing key file");
    }

    @Test
    @DisplayName("the cookie-refresh instance names a sealing key file of its own")
    void theCookieRefreshInstanceNamesASealingKeyFileOfItsOwn() throws Exception {
        Map<String, Object> services = composeServices();

        Path refreshSealingKey = sealingKeyFile(services, COOKIE_REFRESH_SERVICE);

        // The cookie-refresh instance takes no part in the statelessness experiment, so its own file
        // keeps an equal key file meaning exactly that pair.
        assertEquals(SIGNING_KEYS.resolve("cookie-refresh-sealing.key"), refreshSealingKey,
                "the cookie-refresh instance must read cookie-refresh-sealing.key from the signing-key mount");
        assertNotEquals(sealingKeyFile(services, COOKIE_SERVICE), refreshSealingKey,
                "the cookie-refresh instance must not share the statelessness pair's sealing key file");
    }

    @Test
    @DisplayName("every sealing key file exists and decodes with the strict base64 decoder to exactly 32 bytes")
    void everySealingKeyFileDecodesToAnAes256Key() throws Exception {
        Map<String, Object> services = composeServices();
        Set<Path> keyFiles = new TreeSet<>();
        for (String service : cookieModeServices(services)) {
            keyFiles.add(sealingKeyFile(services, service));
        }

        assertEquals(2, keyFiles.size(), () -> "the stack holds two sealing key files, was " + keyFiles);
        assertAll(keyFiles.stream().map(file -> () -> assertEquals(SEALING_KEY_BYTES, decodedKey(file).length,
                () -> file + " must decode to an AES-256 key of " + SEALING_KEY_BYTES + " bytes")));
    }

    @Test
    @DisplayName("the two sealing key files hold different keys")
    void theTwoSealingKeyFilesHoldDifferentKeys() throws Exception {
        Map<String, Object> services = composeServices();

        byte[] sharedKey = decodedKey(sealingKeyFile(services, COOKIE_SERVICE));
        byte[] refreshKey = decodedKey(sealingKeyFile(services, COOKIE_REFRESH_SERVICE));

        // Two names for one key would make the cookie-refresh instance a silent third member of the
        // statelessness pair: a cookie sealed there would unseal on 10445 and 10446.
        assertFalse(Arrays.equals(sharedKey, refreshKey),
                "cookie-sealing.key and cookie-refresh-sealing.key must hold different keys");
    }

    @Test
    @DisplayName("every cookie-mode service mounts the signing-key directory its sealing key file lies in")
    void everyCookieModeServiceMountsTheSigningKeys() throws Exception {
        Map<String, Object> services = composeServices();

        Set<String> cookieMode = cookieModeServices(services);

        assertEquals(Set.of(COOKIE_SERVICE, COOKIE_PEER_SERVICE, COOKIE_REFRESH_SERVICE), cookieMode,
                "the compose stack runs exactly these cookie-mode instances");
        assertAll(cookieMode.stream().map(service -> () -> assertEquals(Optional.of(SIGNING_KEYS),
                hostPath(services, service, SIGNING_KEYS_IN_CONTAINER),
                () -> service + " must mount the signing-key directory at " + SIGNING_KEYS_IN_CONTAINER
                        + ", or the sealing key file its descriptor names does not exist and the boot is refused")));
    }

    @Test
    @DisplayName("no compose service passes a sealing key through the environment")
    void noServiceSetsTheRetiredSealingKeyVariable() throws Exception {
        Set<String> setting = new TreeSet<>();
        for (Map.Entry<String, Object> service : composeServices().entrySet()) {
            Object environment = mapping(service.getValue(), service.getKey()).get("environment");
            boolean sets = environment instanceof Map<?, ?> map
                    ? map.containsKey(RETIRED_SEALING_KEY_VARIABLE)
                    : strings(environment).stream().anyMatch(entry -> RETIRED_SEALING_KEY_VARIABLE.equals(entry)
                    || entry.startsWith(RETIRED_SEALING_KEY_VARIABLE + "="));
            if (sets) {
                setting.add(service.getKey());
            }
        }

        assertEquals(Set.of(), setting, RETIRED_SEALING_KEY_VARIABLE
                + " is read by nothing: the sealing key is the file oidc.session.key_file names");
    }

    // ---------------------------------------------------------------------------------------------
    // The compose model
    // ---------------------------------------------------------------------------------------------

    /** The services whose effective descriptor declares {@code oidc.session.mode: cookie}. */
    private static Set<String> cookieModeServices(Map<String, Object> services) throws IOException {
        Set<String> cookieMode = new TreeSet<>();
        for (String name : services.keySet()) {
            Optional<Path> descriptor = hostPath(services, name, DESCRIPTOR_IN_CONTAINER);
            if (descriptor.isPresent() && loadYaml(descriptor.get()).get("oidc") instanceof Map<?, ?> oidc
                    && oidc.get("session") instanceof Map<?, ?> session && "cookie".equals(session.get("mode"))) {
                cookieMode.add(name);
            }
        }
        assertFalse(cookieMode.isEmpty(), "no compose service runs in cookie mode, so this guard checks nothing");
        return cookieMode;
    }

    /** The host file a service reads its sealing key from: its descriptor's key file, through its mounts. */
    private static Path sealingKeyFile(Map<String, Object> services, String service) throws IOException {
        Path descriptor = hostPath(services, service, DESCRIPTOR_IN_CONTAINER).orElseThrow(() -> new AssertionError(
                service + " mounts no document at " + DESCRIPTOR_IN_CONTAINER));
        String keyFile = assertInstanceOf(String.class, session(descriptor).get("key_file"),
                () -> descriptor + " must name oidc.session.key_file");
        return hostPath(services, service, keyFile).orElseThrow(() -> new AssertionError(
                service + " mounts nothing at or above " + keyFile + ", the sealing key file its descriptor names"));
    }

    /**
     * The host path a container path of a service is served from: the most specific bind mount at or
     * above it, so a single-file overlay wins over the directory mount it overlays. A volume entry is
     * {@code source:target[:mode]}; it is read from the right, because a source may itself carry a
     * colon ({@code ${VAR:-default}}).
     */
    private static Optional<Path> hostPath(Map<String, Object> services, String service, String containerPath) {
        return strings(service(services, service).get("volumes")).stream()
                .map(volume -> volume.replaceFirst(":(ro|rw)$", ""))
                .filter(volume -> volume.lastIndexOf(':') > 0)
                .filter(volume -> {
                    String target = volume.substring(volume.lastIndexOf(':') + 1);
                    return containerPath.equals(target) || containerPath.startsWith(target + "/");
                })
                .max(Comparator.comparingInt(volume -> volume.length() - volume.lastIndexOf(':')))
                .map(volume -> {
                    int separator = volume.lastIndexOf(':');
                    String target = volume.substring(separator + 1);
                    Path source = MODULE.resolve(volume.substring(0, separator)).normalize();
                    return containerPath.equals(target)
                            ? source
                            : source.resolve(containerPath.substring(target.length() + 1)).normalize();
                });
    }

    private static Map<String, Object> composeServices() throws IOException {
        return mapping(loadYaml(MODULE.resolve("docker-compose.yml")).get("services"),
                "the services of docker-compose.yml");
    }

    private static Map<String, Object> service(Map<String, Object> services, String name) {
        assertNotNull(services.get(name), () -> "docker-compose.yml must declare the '" + name + "' service");
        return mapping(services.get(name), "the '" + name + "' service");
    }

    // ---------------------------------------------------------------------------------------------
    // Document and key access
    // ---------------------------------------------------------------------------------------------

    /**
     * The key a sealing key file holds: its one line of base64, stripped of surrounding whitespace,
     * through the strict decoder, which refuses any character outside the base64 alphabet.
     */
    private static byte[] decodedKey(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> "the sealing key file " + file + " does not exist");
        String content = Files.readString(file).strip();
        return assertDoesNotThrow(() -> Base64.getDecoder().decode(content),
                () -> file + " must hold one line of standard base64");
    }

    private static Map<String, Object> oidc(Path descriptor) throws IOException {
        return mapping(loadYaml(descriptor).get("oidc"), "the oidc block of " + descriptor);
    }

    private static Map<String, Object> session(Path descriptor) throws IOException {
        return mapping(oidc(descriptor).get("session"), "the oidc.session block of " + descriptor);
    }

    private static Map<String, Object> loadYaml(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), () -> "this guard reads " + path + ", which does not exist");
        try (InputStream in = Files.newInputStream(path)) {
            return mapping(new Yaml().load(in), path.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object node, String what) {
        assertInstanceOf(Map.class, node, () -> what + " must be a mapping");
        return (Map<String, Object>) node;
    }

    /** The entries of a list node as strings; an absent node is an empty list. */
    private static List<String> strings(Object node) {
        return node instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
