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

import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.MEDIATED_PATH;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.TOKEN_NOT_BOUND_RECORD;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.TOKEN_REFRESHED_RECORD;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.WAIT_INTO_REFRESH_WINDOW_SECONDS;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.bearerToken;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.confirmationThumbprint;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.jwkThumbprint;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.mediatedCall;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.sleepSeconds;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.soleKey;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.restartGateway;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the <em>generated</em> key mode end to end in the native image: a gateway with no signing key
 * configured at all generates one key per purpose at boot, publishes the client key, logs in through
 * Keycloak — and is then restarted under a live session, which replaces both keys.
 * <p>
 * <strong>The restart verdict this test asserts.</strong> After a restart that replaced the client key
 * and the proof key, the refresh of a session established before the restart succeeds, the session
 * continues, and the new access token is bound to the new proof key.
 * <p>
 * That sentence is the fact the rotation documentation states, and the last leg of the test asserts
 * it, so it holds exactly as long as this test is green. It is what RFC 9449 section 5 and Keycloak's
 * DPoP guide lead one to expect: the refresh token of a confidential client is not bound to the proof
 * key, so a refresh that presents a new proof yields a token bound to the new key. The other possible
 * verdict — the refresh is refused and the session ends through
 * {@code oidc.session.refresh.on_failure} — is named in the failure message, so a Keycloak upgrade
 * that changes the behaviour turns this test red instead of changing silently what an operator can
 * rely on.
 * <p>
 * <strong>Why a one-off {@code docker run} rather than a compose service.</strong> The test restarts
 * its gateway, and a compose service that is restarted under the suite would take every other suite's
 * sessions with it. So the gateway is started through {@link OneOffGatewayContainers}, on the compose
 * network, and removed on every exit path.
 * <p>
 * <strong>The topology.</strong>
 * <ul>
 *   <li>The container is named and aliased {@value #NETWORK_ALIAS} on the compose network. Keycloak
 *       fetches the generated client key from
 *       {@code https://sheriff-generated-keys:8443/auth/client-keys}, the {@code jwks.url} of the
 *       realm client {@value #CLIENT_ID}, and verifies the name it dials — so the application listener
 *       presents {@value #CERTIFICATE}, whose subject alternative names cover that alias, and the
 *       Keycloak service trusts that certificate. {@code BffClientKeyWiringTest} guards all three
 *       statements on the committed files.</li>
 *   <li>The descriptor is derived at test time from the committed cookie-refresh descriptor: both
 *       {@code key_file} entries removed, {@code client_id} {@value #CLIENT_ID}, a declared
 *       {@code client_authentication.jwks_path} of {@value #JWKS_PATH}, and the origin triple
 *       retargeted at the fixed loopback port {@value #APPLICATION_PORT}. Everything else — cookie
 *       mode, refresh on, the 30-second leeway — is the committed document.</li>
 *   <li>A sealing key is passed, so the sealed session cookie survives the restart. Without it the
 *       cookie would be undecipherable after the restart and the test would observe a lost session for
 *       a reason that has nothing to do with the signing keys.</li>
 *   <li>No signing-key file is mounted: the harness mounts the certificates, the configuration, the
 *       assets and the demo directory, and nothing else.</li>
 * </ul>
 * <p>
 * <strong>What is asserted, in order.</strong>
 * <ol>
 *   <li>The boot log carries the generated-key record ({@value #KEY_GENERATED_RECORD}) once per
 *       purpose, and no member of the published key.</li>
 *   <li>{@code GET /auth/client-keys} answers one EC P-256 key with {@code use: sig},
 *       {@code alg: ES256} and a {@code kid} equal to the thumbprint this test computes from the
 *       published coordinates; {@code /auth/jwks}, the default path the descriptor moved away from, is
 *       not served.</li>
 *   <li>A login completes. No key is registered at Keycloak, so this is the proof that Keycloak
 *       fetched the generated key from the gateway over TLS.</li>
 *   <li>The mediated token carries a {@code cnf.jkt} that differs from the published {@code kid}: the
 *       proof key is a second generated key.</li>
 *   <li>After {@code docker restart} and renewed readiness, the published {@code kid} differs from the
 *       one before.</li>
 *   <li>No earlier than the opening of the near-expiry window — and therefore more than the ten
 *       seconds Keycloak leaves between two fetches of a client's key set — the pre-restart session
 *       cookie is replayed, and the restart verdict above is asserted.</li>
 * </ol>
 * The test is one method on purpose: the legs are successive states of one container and one session,
 * so splitting them would make each depend on the previous one's side effects through test ordering.
 * <p>
 * <strong>What this test does NOT prove.</strong> It cannot compute the thumbprint of the proof key —
 * that key is never published — so "bound to the new proof key" is asserted as a {@code jkt} that
 * changed across the restart on a gateway that refuses any token response not bound to its own key.
 * It does not prove the provided-key rotation (replace the file, restart); the mechanism is the same
 * and only the origin of the new key differs.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Generated keys: client key published, both usable for a login, both replaced by a restart under a live session")
class BffGeneratedKeysIT {

    /** The container name and the network alias; the host of the realm client's {@code jwks.url}. */
    static final String NETWORK_ALIAS = "sheriff-generated-keys";

    /** The realm client this gateway authenticates as (see {@code integration-realm.json}). */
    static final String CLIENT_ID = "generated-key-client";

    /** The JWKS path the derived descriptor declares; the path of the realm client's {@code jwks.url}. */
    static final String JWKS_PATH = "/auth/client-keys";

    /** The server certificate the application listener presents; its SAN list covers the alias. */
    static final String CERTIFICATE = "generated-keys-gateway.crt";

    private static final String CERTIFICATE_KEY = "generated-keys-gateway.key";

    /**
     * The fixed loopback host port of the application listener. The compose stack publishes
     * {@code 10443}–{@code 10455}; this is the next free one.
     */
    private static final int APPLICATION_PORT = 10456;

    private static final String ORIGIN = "https://localhost:" + APPLICATION_PORT;

    /** The origin the committed cookie-refresh descriptor names, which the derivation retargets. */
    private static final String SOURCE_ORIGIN = BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN;

    private static final Path SOURCE_DESCRIPTOR =
            DOCKER.resolve(Path.of("sheriff-config-cookie-refresh", "gateway.yaml"));

    /** Where the derived descriptor is written; a build output, never committed. */
    private static final Path DERIVED_DESCRIPTOR = Path.of("target", "generated-keys-gateway", "gateway.yaml");

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    /**
     * The AES-256 sealing key, base64 of 32 bytes. A fixed test value of this suite alone: it seals
     * throwaway integration-realm sessions and shares state with no other instance.
     */
    private static final String SEALING_KEY = Base64.getEncoder()
            .encodeToString("generated-keys-sealing-key-01234".getBytes(StandardCharsets.US_ASCII));

    /**
     * INFO — a signing key was generated at startup; names the purpose, never the key. The trailing
     * colon keeps the match off the identifiers from 210 to 219.
     */
    private static final String KEY_GENERATED_RECORD = "ApiSheriff-21:";

    private static final List<String> KEY_PURPOSES = List.of("client-authentication", "sender-constraint");

    /** The members of a JWK that would disclose an EC private key. */
    private static final String PRIVATE_EC_MEMBER = "d";

    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    @Test
    @DisplayName("generates its keys and publishes the client key, logs in, and keeps the session across a restart that replaces both keys")
    void generatedKeysServeALoginAndARestartUnderALiveSession() throws Exception {
        dockerQuietly("rm", "-f", NETWORK_ALIAS);
        try {
            // Arrange — the derived descriptor and the one-off gateway, with no signing-key mount
            writeDerivedDescriptor();
            startBffGateway(new BffGateway(NETWORK_ALIAS, composeNetwork(), NETWORK_ALIAS, APPLICATION_PORT,
                    DERIVED_DESCRIPTOR, CERTIFICATE, CERTIFICATE_KEY, List.of("SESSION_ENCRYPTION_KEY=" + SEALING_KEY)));
            awaitUp("https://localhost:" + publishedPort(NETWORK_ALIAS, 9000));

            // Assert (1) and (2) — one generated key per purpose, and the published client key
            Map<String, Object> keyBefore = publishedKey();
            String keyIdBefore = String.valueOf(keyBefore.get("kid"));
            String bootLog = docker("read the gateway log", "logs", NETWORK_ALIAS);
            assertAll("a gateway with no key configured generates one per purpose and says so",
                    () -> assertAll(KEY_PURPOSES.stream().map(purpose -> () -> assertEquals(1,
                            bootLog.lines().filter(line -> line.contains(KEY_GENERATED_RECORD))
                                    .filter(line -> line.contains("Signing key for " + purpose + " ")).count(),
                            () -> "the boot log must carry " + KEY_GENERATED_RECORD + " exactly once for "
                                    + purpose + ". " + gatewayLog(NETWORK_ALIAS)))),
                    () -> assertAll(List.of("kid", "x", "y").stream().map(member -> () -> assertFalse(
                            bootLog.contains(String.valueOf(keyBefore.get(member))),
                            "the log must not carry the '" + member + "' member of the generated key"))),
                    () -> assertPublishedGeneratedKey(keyBefore),
                    () -> assertEquals(404, gateway().when().get(BffFapiControlsIT.JWKS_PATH).statusCode(),
                            "the descriptor declares its own jwks_path, so the default path is not served"));

            // Act + Assert (3) and (4) — a login, which Keycloak can only verify with the fetched key
            Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH, ORIGIN,
                    BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
            long loggedInAt = System.nanoTime();
            String tokenBefore = bearerToken(mediatedCall(session.gatewayCookies(), ORIGIN));
            String proofKeyBefore = confirmationThumbprint(tokenBefore);
            assertNotEquals(keyIdBefore, proofKeyBefore,
                    "the proof key is generated separately from the client-authentication key");

            // Act (5) — the restart replaces both generated keys
            String managementPort = restartGateway(NETWORK_ALIAS);
            awaitUp("https://localhost:" + managementPort);
            String keyIdAfter = String.valueOf(publishedKey().get("kid"));
            assertNotEquals(keyIdBefore, keyIdAfter,
                    "a generated client key is replaced by a restart, so the published key id must change");

            // Act (6) — replay the pre-restart cookie once the near-expiry window is open
            long elapsed = Duration.ofNanos(System.nanoTime() - loggedInAt).toSeconds();
            sleepSeconds(Math.max(0, WAIT_INTO_REFRESH_WINDOW_SECONDS - elapsed));
            Response replay = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), ORIGIN)
                    .header("Accept", "application/json")
                    .redirects().follow(false)
                    .when().get(MEDIATED_PATH)
                    .then().extract().response();

            // Assert (6) — the restart verdict
            long sinceLogin = Duration.ofNanos(System.nanoTime() - loggedInAt).toSeconds();
            assertEquals(200, replay.statusCode(), () -> "observed verdict: the refresh of the pre-restart session "
                    + "was REFUSED (status " + replay.statusCode() + ", " + sinceLogin + "s after the login) and the "
                    + "session ended through oidc.session.refresh.on_failure. The expected verdict is that the "
                    + "refresh succeeds with a token bound to the new proof key. If Keycloak now refuses, this "
                    + "test, its class Javadoc, the rotation documentation and the decision record must change "
                    + "together. " + gatewayLog(NETWORK_ALIAS));
            String tokenAfter = bearerToken(replay);
            String logAfter = docker("read the gateway log", "logs", NETWORK_ALIAS);
            assertAll("the session continued on the replaced keys",
                    () -> assertNotEquals(tokenBefore, tokenAfter,
                            "the replay inside the near-expiry window must mediate a refreshed token"),
                    () -> assertNotEquals(proofKeyBefore, confirmationThumbprint(tokenAfter),
                            "the refreshed token must be bound to the proof key generated by the restart"),
                    () -> assertNotEquals(keyIdAfter, confirmationThumbprint(tokenAfter),
                            "which is still not the client-authentication key"),
                    () -> assertTrue(logAfter.contains(TOKEN_REFRESHED_RECORD),
                            () -> "the refresh must be recorded. " + gatewayLog(NETWORK_ALIAS)),
                    () -> assertFalse(logAfter.contains(TOKEN_NOT_BOUND_RECORD),
                            () -> "no token response may have been refused. " + gatewayLog(NETWORK_ALIAS)));
        } finally {
            dockerQuietly("rm", "-f", NETWORK_ALIAS);
        }
    }

    private static void awaitUp(String managementOrigin) {
        awaitReadiness(NETWORK_ALIAS, managementOrigin, response -> response.statusCode() == 200,
                BOOT_TIMEOUT_SECONDS, "the generated-keys gateway to report readiness UP");
    }

    private static RequestSpecification gateway() {
        return BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN);
    }

    /** The one key the gateway publishes at its declared JWKS path. */
    private static Map<String, Object> publishedKey() {
        return soleKey(gateway().when().get(JWKS_PATH).then().statusCode(200).extract().response());
    }

    private static void assertPublishedGeneratedKey(Map<String, Object> key) {
        assertAll("the published client key of the generated mode",
                () -> assertEquals("EC", key.get("kty"), "a generated key is an EC key"),
                () -> assertEquals("P-256", key.get("crv")),
                () -> assertEquals("sig", key.get("use")),
                () -> assertEquals("ES256", key.get("alg")),
                () -> assertEquals(jwkThumbprint(key), key.get("kid"),
                        "the key id must be the RFC 7638 thumbprint of the published coordinates"),
                () -> assertFalse(key.containsKey(PRIVATE_EC_MEMBER), "no private member may be published"));
    }

    /**
     * Writes the descriptor of the one-off gateway: the committed cookie-refresh descriptor with both
     * key files removed, the generated-key client, a declared JWKS path and the origin triple
     * retargeted at this gateway's port. The source is asserted to carry what the derivation removes
     * and retargets, so a change to the committed descriptor fails here instead of producing a gateway
     * that differs from it in more than the stated ways.
     */
    private static void writeDerivedDescriptor() throws IOException {
        Map<String, Object> document = loadDescriptor();
        Map<String, Object> oidc = mapping(document.get("oidc"), "oidc");
        assertAll(SOURCE_DESCRIPTOR + " must name both key files the derivation removes",
                () -> assertTrue(mapping(oidc.get("client_authentication"), "oidc.client_authentication")
                        .containsKey("key_file")),
                () -> assertTrue(mapping(oidc.get("sender_constraint"), "oidc.sender_constraint")
                        .containsKey("key_file")));

        oidc.put("client_id", CLIENT_ID);
        oidc.put("client_authentication", new LinkedHashMap<>(Map.of("jwks_path", JWKS_PATH)));
        oidc.remove("sender_constraint");
        oidc.put("redirect_uri", retargeted(oidc.get("redirect_uri"), "oidc.redirect_uri"));
        Map<String, Object> logout = mapping(oidc.get("logout"), "oidc.logout");
        logout.put("post_logout_redirect_uri",
                retargeted(logout.get("post_logout_redirect_uri"), "oidc.logout.post_logout_redirect_uri"));
        Map<String, Object> csrf = mapping(mapping(oidc.get("session"), "oidc.session").get("csrf"),
                "oidc.session.csrf");
        List<?> trustedOrigins = assertInstanceOf(List.class, csrf.get("trusted_origins"),
                "oidc.session.csrf.trusted_origins must be a list");
        csrf.put("trusted_origins", trustedOrigins.stream()
                .map(origin -> retargeted(origin, "oidc.session.csrf.trusted_origins")).toList());

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.createDirectories(DERIVED_DESCRIPTOR.getParent());
        Files.writeString(DERIVED_DESCRIPTOR, new Yaml(options).dump(document));
        Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(DESCRIPTOR_MODE));

        assertFalse(Files.readString(DERIVED_DESCRIPTOR).contains("key_file"),
                "the derived descriptor must name no key file, so both keys are generated");
    }

    private static String retargeted(Object value, String key) {
        String configured = String.valueOf(value);
        assertTrue(configured.startsWith(SOURCE_ORIGIN),
                () -> key + " of " + SOURCE_DESCRIPTOR + " must name the origin " + SOURCE_ORIGIN + ", was " + configured);
        return ORIGIN + configured.substring(SOURCE_ORIGIN.length());
    }

    private static Map<String, Object> loadDescriptor() throws IOException {
        try (Reader reader = Files.newBufferedReader(SOURCE_DESCRIPTOR)) {
            return mapping(new Yaml().load(reader), SOURCE_DESCRIPTOR.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object node, String what) {
        assertInstanceOf(Map.class, node, () -> what + " must be a mapping");
        return (Map<String, Object>) node;
    }
}
