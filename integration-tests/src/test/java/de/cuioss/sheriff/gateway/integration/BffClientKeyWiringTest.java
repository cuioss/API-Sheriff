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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard over the client-key wiring of the integration stack: it
 * reads the committed compose file, the gateway descriptors, the realm import, the signing-key files
 * and the release workflow, and asserts that they agree. It starts no container and reaches no
 * network.
 * <p>
 * <strong>Why the wiring needs a guard of its own.</strong> No realm client carries a registered key.
 * A key-authenticated client names a JWKS URL, and Keycloak fetches the public key from the gateway
 * instance that URL names. A gateway that signs its client assertion with any other key than the one
 * that instance publishes is refused by Keycloak — on the first login, several minutes into
 * a native build, with an identity-provider error that names neither file. Every statement that makes
 * the fetch work lives in a different file, so each can be changed on its own and still parse.
 * <p>
 * <strong>The two parts.</strong> The gateway services are the compose services named after the
 * application, and they are partitioned by one fact read from the descriptor each one mounts: whether
 * its {@code oidc} block declares {@code client_secret}. Both parts are derived, and both are asserted
 * non-empty, so a guard below never passes over an empty set.
 * <ul>
 *   <li><em>Key-authenticated</em> — the client the descriptor names is registered for signed-JWT
 *       authentication with a JWKS URL, with no secret and no statically registered key, and requires
 *       pushed authorization requests and DPoP-bound tokens; the host of that URL is a gateway service
 *       of the compose model and its path is that service's effective JWKS path; the service signs
 *       with the same key file as the service the URL names.</li>
 *   <li><em>Secret-authenticated</em> — exactly {@value #SECRET_AUTHENTICATED_SERVICE}: its descriptor
 *       declares no {@code client_authentication} block, it is the only compose service that sets
 *       {@value #CLIENT_SECRET_VARIABLE}, and its realm client authenticates with that secret,
 *       registers no key and no JWKS URL, and still requires both controls.</li>
 * </ul>
 * Across both parts, every key file a descriptor or a service variable names exists in the
 * signing-keys directory and holds exactly one private and one public block, and that directory is
 * mounted into every gateway service and into no other service.
 * <p>
 * <strong>The third key-authenticated client.</strong> {@code generated-key-client} is named by no
 * compose service: {@code BffGeneratedKeysIT} starts its gateway as a one-off container. Its wiring
 * is spread over the realm import, the compose file and a certificate, so {@link GeneratedKeyClient}
 * guards it here: the client's JWKS URL names the network alias and the JWKS path that test uses, the
 * client declares no secret and requires both controls, and the Keycloak service trusts the
 * certificate that covers the alias. The same group closes the set: every realm client registered for
 * signed-JWT authentication is either the client of a key-authenticated compose service or this one.
 * <p>
 * <strong>The release lane.</strong> {@code .github/workflows/release.yml} boots the published image
 * over the base descriptor in a step no build of this repository executes, after the artifacts are
 * published. {@link ReleaseLane} reads that step's {@code docker run} and asserts that it supplies
 * every variable the base descriptor references, mounts a directory that holds every key file the
 * descriptor resolves to, and sets no client secret. The step is located by its name, and the parsed
 * variable and mount sets are asserted non-empty, so a renamed or reshaped step fails this test
 * instead of being passed over.
 * <p>
 * <strong>Shown to fail.</strong> Two edits were made to the committed files while this class was
 * written, and each turned it red before being reverted:
 * <ul>
 *   <li>the {@code client_authentication.key_file} of {@code sheriff-config-cookie/gateway.yaml} was
 *       pointed at {@code dpop-rsa.pem}, a file the publishing instance does not sign with —
 *       {@code signsWithTheKeyThePublishingServiceHolds} failed for {@code api-sheriff-cookie} and
 *       {@code api-sheriff-cookie-2}, the two services that mount that descriptor;</li>
 *   <li>the signing-keys {@code -v} line was removed from the smoke step —
 *       {@code everyKeyFileLiesUnderAMountedDirectory} failed for both key files of the base
 *       descriptor.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Client-key wiring — compose, descriptors, realm import, key files and the release smoke step")
class BffClientKeyWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path REPOSITORY = MODULE.resolve("..").normalize();
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path SIGNING_KEYS = DOCKER.resolve("signing-keys");
    private static final Path REALM_IMPORT = DOCKER.resolve("keycloak/integration-realm.json");
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");
    private static final Path RELEASE_WORKFLOW = REPOSITORY.resolve(".github/workflows/release.yml");

    /** Every gateway instance of the compose model is named after the application. */
    private static final String GATEWAY_SERVICE_PREFIX = "api-sheriff";

    /** Where a gateway reads its global document, whether from the shared mount or an overlay. */
    private static final String DESCRIPTOR_IN_CONTAINER = "/app/sheriff-config/gateway.yaml";

    private static final String SIGNING_KEYS_IN_CONTAINER = "/app/signing-keys";

    /** The path the gateway publishes its client key at when {@code jwks_path} is not declared. */
    private static final String DEFAULT_JWKS_PATH = "/auth/jwks";

    private static final String CLIENT_SECRET_VARIABLE = "OIDC_CLIENT_SECRET";
    private static final String DPOP_KEY_VARIABLE = "OIDC_DPOP_KEY_FILE";

    /** The one gateway service kept on client-secret authentication. */
    private static final String SECRET_AUTHENTICATED_SERVICE = "api-sheriff-refresh";

    /** The realm client the fixtures mint bearer tokens from; no gateway authenticates as it. */
    private static final String TOKEN_MINT_CLIENT = "token-mint-client";

    private static final String SMOKE_STEP = "Smoke the published image by digest";

    private static final String KEYCLOAK_SERVICE = "keycloak";

    /** The Keycloak setting that lists the certificates Keycloak trusts when it dials a gateway. */
    private static final String KEYCLOAK_TRUST_VARIABLE = "KC_TRUSTSTORE_PATHS";

    /** The port a gateway terminates TLS on inside the compose network. */
    private static final int GATEWAY_TLS_PORT = 8443;

    /** The {@code GeneralName} tag of a DNS subject alternative name (RFC 5280). */
    private static final int DNS_NAME = 2;

    private static final String PUSHED_REQUESTS_REQUIRED = "require.pushed.authorization.requests";
    private static final String DPOP_BOUND_TOKENS = "dpop.bound.access.tokens";

    /** A bare {@code ${VAR}} reference; a reference carrying a default does not match and needs no supply. */
    private static final Pattern BARE_REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private static final Pattern SHELL_ASSIGNMENT = Pattern.compile("^\\s*([A-Z_][A-Z0-9_]*)=\"(.*)\"\\s*$");

    @Test
    @DisplayName("the descriptors partition the gateway services into a key-authenticated part and exactly one secret-authenticated service")
    void theDescriptorsPartitionTheGatewayServices() throws Exception {
        List<String> keyAuthenticated = keyAuthenticated().stream().map(Gateway::name).toList();
        List<String> secretAuthenticated = secretAuthenticated().stream().map(Gateway::name).toList();

        assertAll(
                () -> assertFalse(keyAuthenticated.isEmpty(),
                        "no gateway service is key-authenticated, so the key wiring is guarded over nothing"),
                () -> assertEquals(List.of(SECRET_AUTHENTICATED_SERVICE), secretAuthenticated,
                        "exactly one gateway service stays on client-secret authentication, as the stack "
                                + "proof of that mode; its descriptor is the only one declaring oidc.client_secret"));
    }

    @Test
    @DisplayName("every key file a descriptor or a service variable names holds one private and one public block")
    void everyNamedKeyFileHoldsOnePrivateAndOnePublicBlock() throws Exception {
        Map<Path, String> named = new LinkedHashMap<>();
        for (Gateway gateway : gateways()) {
            gateway.clientKeyFile().ifPresent(file -> named.put(gateway.hostFile(file),
                    gateway.name() + " oidc.client_authentication.key_file"));
            gateway.dpopKeyFile().ifPresent(file -> named.put(gateway.hostFile(file),
                    gateway.name() + " oidc.sender_constraint.key_file"));
            gateway.environmentValue(DPOP_KEY_VARIABLE).ifPresent(file -> named.put(gateway.hostFile(file),
                    gateway.name() + " " + DPOP_KEY_VARIABLE));
        }

        assertFalse(named.isEmpty(), "no gateway service names a key file, so there is nothing to check");
        assertAll(named.entrySet().stream()
                .map(entry -> () -> assertIsASigningKeyFile(entry.getKey(), entry.getValue())));
    }

    @Test
    @DisplayName("the signing-keys directory is mounted into every gateway service and into no other service")
    void theSigningKeysAreMountedIntoGatewayServicesOnly() throws Exception {
        Set<String> mounting = new TreeSet<>();
        for (Map.Entry<String, Object> service : composeServices().entrySet()) {
            boolean mounts = mounts(mapping(service.getValue(), service.getKey())).stream()
                    .anyMatch(mount -> mount.hostPath().startsWith(SIGNING_KEYS));
            if (mounts) {
                mounting.add(service.getKey());
            }
        }
        Set<String> gatewayNames = new TreeSet<>(gateways().stream().map(Gateway::name).toList());

        assertEquals(gatewayNames, mounting,
                "the private keys must reach the gateway instances and nothing else: Keycloak is given the "
                        + "public client key over the JWKS URL, never the file");
        assertAll(gateways().stream().map(gateway -> () -> assertEquals(SIGNING_KEYS,
                gateway.hostFile(SIGNING_KEYS_IN_CONTAINER),
                gateway.name() + " must mount the signing-keys directory at " + SIGNING_KEYS_IN_CONTAINER)));
    }

    @Nested
    @DisplayName("A key-authenticated gateway service")
    class KeyAuthenticated {

        @Test
        @DisplayName("names a realm client registered for signed-JWT authentication through a JWKS URL, with no secret and no static key")
        void namesARealmClientRegisteredForSignedJwtAuthentication() throws Exception {
            Map<String, Map<String, Object>> clients = realmClients();

            assertAll(keyAuthenticated().stream().map(gateway -> () -> {
                Map<String, Object> client = realmClient(clients, gateway);
                Map<String, Object> attributes = attributes(client);
                String described = gateway.name() + " -> realm client " + gateway.clientId();
                assertAll(described,
                        () -> assertEquals("client-jwt", client.get("clientAuthenticatorType"),
                                "the client must authenticate with a signed JWT"),
                        () -> assertEquals("true", attributes.get("use.jwks.url"),
                                "the client must take its key from a JWKS URL"),
                        () -> assertInstanceOf(String.class, attributes.get("jwks.url"),
                                "the client must name the JWKS URL Keycloak fetches the key from"),
                        () -> assertFalse(client.containsKey("secret"),
                                "a key-authenticated client must declare no secret"),
                        () -> assertEquals(List.of(), staticKeyAttributes(attributes),
                                "the key must be fetched, never registered in the realm import"),
                        () -> assertRequiresBothControls(attributes));
            }));
        }

        @Test
        @DisplayName("is verified against a JWKS URL that names a gateway service of the compose model and that service's JWKS path")
        void jwksUrlNamesAGatewayServiceAndItsJwksPath() throws Exception {
            Map<String, Map<String, Object>> clients = realmClients();
            List<Gateway> gateways = gateways();

            assertAll(keyAuthenticated().stream().map(gateway -> () -> {
                URI jwksUrl = jwksUrl(clients, gateway);
                Gateway publisher = publisher(gateways, jwksUrl);
                String described = gateway.name() + " -> " + jwksUrl;
                assertAll(described,
                        () -> assertEquals("https", jwksUrl.getScheme(), "the key is fetched over TLS"),
                        () -> assertTrue(publisher.containerPorts().contains(jwksUrl.getPort()),
                                () -> "the port must be one " + publisher.name() + " serves inside the "
                                        + "compose network, which are " + publisher.containerPorts()),
                        () -> assertEquals(publisher.jwksPath(), jwksUrl.getPath(),
                                () -> "the path must be the effective JWKS path of " + publisher.name()),
                        () -> assertFalse(publisher.secretAuthenticated(),
                                () -> publisher.name() + " authenticates with a secret, so its JWKS path "
                                        + "answers 404 and publishes no key"));
            }));
        }

        @Test
        @DisplayName("signs with the key file the service its JWKS URL names holds")
        void signsWithTheKeyThePublishingServiceHolds() throws Exception {
            Map<String, Map<String, Object>> clients = realmClients();
            List<Gateway> gateways = gateways();

            assertAll(keyAuthenticated().stream().map(gateway -> () -> {
                Gateway publisher = publisher(gateways, jwksUrl(clients, gateway));
                assertEquals(publisher.hostFile(requiredClientKeyFile(publisher)),
                        gateway.hostFile(requiredClientKeyFile(gateway)),
                        () -> gateway.name() + " signs its client assertion with another key file than "
                                + publisher.name() + " publishes at the JWKS URL of realm client "
                                + gateway.clientId() + ", so Keycloak would find no key for the assertion");
            }));
        }
    }

    @Nested
    @DisplayName("The secret-authenticated gateway service")
    class SecretAuthenticated {

        @Test
        @DisplayName("declares no client_authentication block beside its secret")
        void declaresNoClientAuthenticationBlock() throws Exception {
            assertAll(secretAuthenticated().stream().map(gateway -> () -> assertNull(
                    gateway.oidc().get("client_authentication"),
                    gateway.name() + " declares oidc.client_secret; a key file beside a secret is refused at boot")));
        }

        @Test
        @DisplayName("is the only compose service that sets the client-secret variable, and sets its realm client's secret")
        void isTheOnlyServiceSettingTheClientSecret() throws Exception {
            Set<String> setting = new TreeSet<>();
            for (Map.Entry<String, Object> service : composeServices().entrySet()) {
                boolean sets = environment(mapping(service.getValue(), service.getKey())).stream()
                        .anyMatch(entry -> CLIENT_SECRET_VARIABLE.equals(entry)
                                || entry.startsWith(CLIENT_SECRET_VARIABLE + "="));
                if (sets) {
                    setting.add(service.getKey());
                }
            }
            Map<String, Map<String, Object>> clients = realmClients();

            assertEquals(Set.of(SECRET_AUTHENTICATED_SERVICE), setting,
                    CLIENT_SECRET_VARIABLE + " belongs to the one secret-authenticated service and to no other");
            assertAll(secretAuthenticated().stream().map(gateway -> () -> assertEquals(
                    realmClient(clients, gateway).get("secret"),
                    gateway.resolve(String.valueOf(gateway.oidc().get("client_secret"))),
                    gateway.name() + " must present the secret the realm registers for " + gateway.clientId())));
        }

        @Test
        @DisplayName("names a realm client that authenticates with a secret, registers no key and no JWKS URL, and requires both controls")
        void namesARealmClientOnASecretThatRequiresBothControls() throws Exception {
            Map<String, Map<String, Object>> clients = realmClients();

            assertAll(secretAuthenticated().stream().map(gateway -> () -> {
                Map<String, Object> client = realmClient(clients, gateway);
                Map<String, Object> attributes = attributes(client);
                String described = gateway.name() + " -> realm client " + gateway.clientId();
                assertAll(described,
                        () -> assertEquals("client-secret", client.get("clientAuthenticatorType"),
                                "the client must authenticate with its secret"),
                        () -> assertInstanceOf(String.class, client.get("secret"),
                                "the client must declare the secret the gateway presents"),
                        () -> assertNull(attributes.get("jwks.url"), "no JWKS URL may be registered"),
                        () -> assertNull(attributes.get("use.jwks.url"), "no JWKS URL may be switched on"),
                        () -> assertEquals(List.of(), staticKeyAttributes(attributes),
                                "no key may be registered for a secret-authenticated client"),
                        () -> assertRequiresBothControls(attributes));
            }));
        }
    }

    @Nested
    @DisplayName("The realm import")
    class RealmClients {

        @Test
        @DisplayName("lets only the secret-authenticated gateway client and the token-mint client authenticate with a secret")
        void onlyTheTwoDeclaredClientsAuthenticateWithASecret() throws Exception {
            Set<String> expected = new TreeSet<>(secretAuthenticated().stream().map(Gateway::clientId).toList());
            expected.add(TOKEN_MINT_CLIENT);
            Set<String> onASecret = new TreeSet<>();
            realmClients().forEach((clientId, client) -> {
                if (client.containsKey("secret") || !"client-jwt".equals(client.get("clientAuthenticatorType"))) {
                    onASecret.add(clientId);
                }
            });

            assertEquals(expected, onASecret,
                    "a further secret-authenticated client would be a gateway client outside both parts of this guard");
        }

        @Test
        @DisplayName("enables direct access grants on the token-mint client and on no other client")
        void onlyTheTokenMintClientAllowsDirectAccessGrants() throws Exception {
            Set<String> directGrants = new TreeSet<>();
            realmClients().forEach((clientId, client) -> {
                if (Boolean.TRUE.equals(client.get("directAccessGrantsEnabled"))) {
                    directGrants.add(clientId);
                }
            });

            assertEquals(Set.of(TOKEN_MINT_CLIENT), directGrants,
                    "a gateway client with direct access grants would hand out tokens for a password, "
                            + "outside the pushed and DPoP-bound flow");
        }

        @Test
        @DisplayName("registers the token-mint client for no gateway service")
        void noGatewayAuthenticatesAsTheTokenMintClient() throws Exception {
            assertAll(gateways().stream().map(gateway -> () -> assertNotEquals(TOKEN_MINT_CLIENT, gateway.clientId(), gateway.name() + " must not authenticate as the fixture client that allows direct access grants")));
        }
    }

    /**
     * The realm client of the gateway {@code BffGeneratedKeysIT} starts as a one-off container. No
     * compose service names it, so its wiring is read from the realm import, the Keycloak service and
     * the certificate that container presents.
     */
    @Nested
    @DisplayName("The realm client of the one-off generated-keys gateway")
    class GeneratedKeyClient {

        @Test
        @DisplayName("is registered for signed-JWT authentication through a JWKS URL, with no secret, and requires both controls")
        void isRegisteredForSignedJwtAuthentication() throws Exception {
            Map<String, Object> client = generatedKeyClient();
            Map<String, Object> attributes = attributes(client);

            assertAll("realm client " + BffGeneratedKeysIT.CLIENT_ID,
                    () -> assertEquals("client-jwt", client.get("clientAuthenticatorType")),
                    () -> assertEquals("true", attributes.get("use.jwks.url")),
                    () -> assertFalse(client.containsKey("secret"), "a key-authenticated client declares no secret"),
                    () -> assertEquals(List.of(), staticKeyAttributes(attributes),
                            "a generated key cannot be registered: it exists only once the gateway has booted"),
                    () -> assertEquals("ES256", attributes.get("token.endpoint.auth.signing.alg"),
                            "a generated client key is an EC P-256 key and signs ES256"),
                    () -> assertRequiresBothControls(attributes));
        }

        @Test
        @DisplayName("names a JWKS URL on the one-off gateway's network alias and declared JWKS path")
        void jwksUrlNamesTheOneOffGateway() throws Exception {
            URI jwksUrl = URI.create(assertInstanceOf(String.class, attributes(generatedKeyClient()).get("jwks.url"),
                    "realm client " + BffGeneratedKeysIT.CLIENT_ID + " must name a JWKS URL"));
            List<String> composeNames = gateways().stream().flatMap(gateway -> gateway.networkNames().stream()).toList();

            assertAll("the URL Keycloak fetches the generated client key from: " + jwksUrl,
                    () -> assertEquals("https", jwksUrl.getScheme(), "the key is fetched over TLS"),
                    () -> assertEquals(BffGeneratedKeysIT.NETWORK_ALIAS, jwksUrl.getHost(),
                            "the host must be the network alias the one-off gateway is started under"),
                    () -> assertEquals(GATEWAY_TLS_PORT, jwksUrl.getPort(),
                            "the port must be the gateway's TLS port inside the compose network"),
                    () -> assertEquals(BffGeneratedKeysIT.JWKS_PATH, jwksUrl.getPath(),
                            "the path must be the jwks_path the one-off gateway's descriptor declares"),
                    () -> assertFalse(composeNames.contains(jwksUrl.getHost()),
                            "the alias must belong to the one-off gateway alone, not to a compose service"));
        }

        @Test
        @DisplayName("is reachable for Keycloak: the Keycloak service trusts the certificate that covers the alias")
        void keycloakTrustsTheCertificateThatCoversTheAlias() throws Exception {
            Map<String, Object> keycloak = mapping(composeServices().get(KEYCLOAK_SERVICE), KEYCLOAK_SERVICE);
            List<Mount> mounts = mounts(keycloak);
            String prefix = KEYCLOAK_TRUST_VARIABLE + "=";
            List<Path> trusted = environment(keycloak).stream().filter(entry -> entry.startsWith(prefix))
                    .flatMap(entry -> Stream.of(entry.substring(prefix.length()).split(",")))
                    .map(String::strip)
                    .map(path -> hostFile(mounts, path).orElseThrow(() -> new AssertionError(
                            "the Keycloak service mounts nothing at or above the trust path " + path)))
                    .toList();
            Path presented = DOCKER.resolve("certificates").resolve(BffGeneratedKeysIT.CERTIFICATE);

            assertTrue(trusted.contains(presented), () -> "Keycloak verifies the gateway it fetches the key from, "
                    + "so " + KEYCLOAK_TRUST_VARIABLE + " must list " + BffGeneratedKeysIT.CERTIFICATE
                    + "; it lists " + trusted);
            assertTrue(dnsNames(presented).contains(BffGeneratedKeysIT.NETWORK_ALIAS),
                    () -> "Keycloak verifies the name it dials, so " + presented + " must name "
                            + BffGeneratedKeysIT.NETWORK_ALIAS + " as a subject alternative name");
        }

        @Test
        @DisplayName("is the only signed-JWT client that no compose gateway service names")
        void everySignedJwtClientIsGuarded() throws Exception {
            Set<String> expected = new TreeSet<>(keyAuthenticated().stream().map(Gateway::clientId).toList());
            expected.add(BffGeneratedKeysIT.CLIENT_ID);
            Set<String> signedJwt = new TreeSet<>();
            realmClients().forEach((clientId, client) -> {
                if ("client-jwt".equals(client.get("clientAuthenticatorType"))) {
                    signedJwt.add(clientId);
                }
            });

            assertEquals(expected, signedJwt,
                    "a further signed-JWT client would have a JWKS URL that no guard of this class checks");
        }

        private static Map<String, Object> generatedKeyClient() throws IOException {
            Map<String, Object> client = realmClients().get(BffGeneratedKeysIT.CLIENT_ID);
            assertNotNull(client, "the realm import must declare " + BffGeneratedKeysIT.CLIENT_ID);
            return client;
        }

        /** The DNS subject alternative names of a PEM certificate. */
        private static List<String> dnsNames(Path certificate) throws IOException, CertificateException {
            assertTrue(Files.isRegularFile(certificate), () -> "this guard reads " + certificate + ", which does not exist");
            try (InputStream in = Files.newInputStream(certificate)) {
                X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                Collection<List<?>> names = parsed.getSubjectAlternativeNames();
                assertNotNull(names, () -> certificate + " carries no subject alternative name");
                return names.stream().filter(name -> Integer.valueOf(DNS_NAME).equals(name.get(0)))
                        .map(name -> String.valueOf(name.get(1))).toList();
            }
        }
    }

    /**
     * The step {@value #SMOKE_STEP} of the release workflow, which boots the published image over the
     * base descriptor and is executed by no build of this repository.
     */
    @Nested
    @DisplayName("The release smoke step")
    class ReleaseLane {

        @Test
        @DisplayName("supplies every variable the base descriptor references")
        void suppliesEveryVariableTheBaseDescriptorReferences() throws Exception {
            Set<String> referenced = bareReferences(loadYaml(BASE_DESCRIPTOR));
            SmokeRun run = smokeRun();

            assertFalse(referenced.isEmpty(),
                    "the base descriptor references no variable, so this group checks nothing: " + BASE_DESCRIPTOR);
            assertAll(referenced.stream().map(variable -> () -> assertTrue(
                    run.environment().containsKey(variable),
                    () -> "the smoke step must pass -e " + variable + ": the base descriptor references it and "
                            + "an undefined variable aborts the boot of the published image. It passes "
                            + run.environment().keySet())));
        }

        @Test
        @DisplayName("mounts a directory that holds every key file the base descriptor resolves to")
        void everyKeyFileLiesUnderAMountedDirectory() throws Exception {
            SmokeRun run = smokeRun();
            Map<String, Object> oidc = mapping(loadYaml(BASE_DESCRIPTOR).get("oidc"), "the base descriptor oidc block");
            Map<String, String> keyFiles = new LinkedHashMap<>();
            keyFile(oidc, "client_authentication").ifPresent(file -> keyFiles.put(
                    "oidc.client_authentication.key_file", run.resolve(file)));
            keyFile(oidc, "sender_constraint").ifPresent(file -> keyFiles.put(
                    "oidc.sender_constraint.key_file", run.resolve(file)));

            assertFalse(keyFiles.isEmpty(),
                    "the base descriptor names no key file, so this group checks nothing: " + BASE_DESCRIPTOR);
            assertAll(keyFiles.entrySet().stream().map(entry -> () -> {
                Optional<Path> hostFile = hostFile(run.mounts(), entry.getValue());
                assertTrue(hostFile.isPresent(),
                        () -> "the smoke step mounts no directory holding " + entry.getValue() + ", which "
                                + entry.getKey() + " of the base descriptor resolves to; it mounts "
                                + run.mounts().stream().map(Mount::containerPath).toList());
                assertIsASigningKeyFile(hostFile.orElseThrow(), "the smoke step, " + entry.getKey());
            }));
        }

        @Test
        @DisplayName("sets no client secret")
        void setsNoClientSecret() throws Exception {
            SmokeRun run = smokeRun();

            assertFalse(run.environment().containsKey(CLIENT_SECRET_VARIABLE),
                    "the base descriptor authenticates with a key; a secret passed to the smoke container "
                            + "is read by nothing and misstates the mode the published image boots in");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The compose model
    // ---------------------------------------------------------------------------------------------

    /**
     * A bind mount.
     *
     * @param hostPath      the mounted host path, normalised
     * @param containerPath where it appears in the container
     */
    private record Mount(Path hostPath, String containerPath) {
    }

    /**
     * One gateway service of the compose model with the {@code oidc} block of the descriptor it mounts.
     *
     * @param name           the compose service name
     * @param environment    its {@code environment} entries
     * @param mounts         its bind mounts
     * @param containerPorts the container ports it publishes
     * @param networkNames   the names it answers to on the compose network: its service name and aliases
     * @param oidc           the {@code oidc} block of its effective descriptor
     */
    private record Gateway(String name, List<String> environment, List<Mount> mounts, Set<Integer> containerPorts,
    Set<String> networkNames, Map<String, Object> oidc) {

        boolean secretAuthenticated() {
            return oidc.containsKey("client_secret");
        }

        String clientId() {
            return assertInstanceOf(String.class, oidc.get("client_id"), name + " must declare oidc.client_id");
        }

        Optional<String> environmentValue(String variable) {
            String prefix = variable + "=";
            return environment.stream().filter(entry -> entry.startsWith(prefix))
                    .map(entry -> entry.substring(prefix.length())).findFirst();
        }

        /** Resolves a bare {@code ${VAR}} value against this service's environment; a literal is returned as is. */
        String resolve(String value) {
            Matcher reference = BARE_REFERENCE.matcher(value);
            if (!reference.matches()) {
                return value;
            }
            return environmentValue(reference.group(1)).orElseThrow(() -> new AssertionError(
                    name + " mounts a descriptor referencing " + value + " and sets no such variable, which aborts its boot"));
        }

        Optional<String> clientKeyFile() {
            return keyFile(oidc, "client_authentication").map(this::resolve);
        }

        Optional<String> dpopKeyFile() {
            return keyFile(oidc, "sender_constraint").map(this::resolve);
        }

        String jwksPath() {
            Object block = oidc.get("client_authentication");
            Object declared = block instanceof Map<?, ?> map ? map.get("jwks_path") : null;
            return declared == null ? DEFAULT_JWKS_PATH : String.valueOf(declared);
        }

        Path hostFile(String containerPath) {
            return BffClientKeyWiringTest.hostFile(mounts, containerPath).orElseThrow(() -> new AssertionError(
                    name + " mounts nothing at or above " + containerPath));
        }
    }

    private static List<Gateway> gateways() throws IOException {
        List<Gateway> gateways = new ArrayList<>();
        for (Map.Entry<String, Object> service : composeServices().entrySet()) {
            if (service.getKey().startsWith(GATEWAY_SERVICE_PREFIX)) {
                gateways.add(gateway(service.getKey(), mapping(service.getValue(), service.getKey())));
            }
        }
        assertFalse(gateways.isEmpty(), "docker-compose.yml declares no " + GATEWAY_SERVICE_PREFIX
                + "* service, so every guard of this class would pass over nothing");
        return List.copyOf(gateways);
    }

    private static List<Gateway> keyAuthenticated() throws IOException {
        List<Gateway> part = gateways().stream().filter(gateway -> !gateway.secretAuthenticated()).toList();
        assertFalse(part.isEmpty(), "the key-authenticated part of the gateway services is empty");
        return part;
    }

    private static List<Gateway> secretAuthenticated() throws IOException {
        List<Gateway> part = gateways().stream().filter(Gateway::secretAuthenticated).toList();
        assertFalse(part.isEmpty(), "the secret-authenticated part of the gateway services is empty");
        return part;
    }

    private static Gateway gateway(String name, Map<String, Object> service) throws IOException {
        List<Mount> mounts = mounts(service);
        Path descriptor = hostFile(mounts, DESCRIPTOR_IN_CONTAINER).orElseThrow(() -> new AssertionError(
                name + " mounts no document at " + DESCRIPTOR_IN_CONTAINER));
        Map<String, Object> oidc = mapping(loadYaml(descriptor).get("oidc"), "the oidc block of " + descriptor);
        Set<Integer> containerPorts = new LinkedHashSet<>();
        for (String port : strings(service.get("ports"))) {
            containerPorts.add(Integer.valueOf(port.substring(port.lastIndexOf(':') + 1).strip()));
        }
        Set<String> networkNames = new LinkedHashSet<>();
        networkNames.add(name);
        if (service.get("networks") instanceof Map<?, ?> networks) {
            for (Object network : networks.values()) {
                if (network instanceof Map<?, ?> settings) {
                    networkNames.addAll(strings(settings.get("aliases")));
                }
            }
        }
        return new Gateway(name, environment(service), mounts, containerPorts, networkNames, oidc);
    }

    private static List<String> environment(Map<String, Object> service) {
        return strings(service.get("environment"));
    }

    /**
     * The bind mounts of a compose service. A volume entry is {@code source:target[:mode]}; it is read
     * from the right, because a source may itself carry a colon ({@code ${VAR:-default}}).
     */
    private static List<Mount> mounts(Map<String, Object> service) {
        List<Mount> mounts = new ArrayList<>();
        for (String volume : strings(service.get("volumes"))) {
            mounts.add(mount(volume, MODULE));
        }
        return mounts;
    }

    private static Mount mount(String volume, Path base) {
        String withoutMode = volume.replaceFirst(":(ro|rw)$", "");
        int separator = withoutMode.lastIndexOf(':');
        assertTrue(separator > 0, () -> "not a source:target volume entry: " + volume);
        return new Mount(base.resolve(withoutMode.substring(0, separator)).normalize(),
                withoutMode.substring(separator + 1));
    }

    /**
     * The host path a container path is served from: the most specific mount at or above it, so a
     * single-file overlay wins over the directory mount it overlays.
     */
    private static Optional<Path> hostFile(List<Mount> mounts, String containerPath) {
        return mounts.stream()
                .filter(mount -> containerPath.equals(mount.containerPath())
                        || containerPath.startsWith(mount.containerPath() + "/"))
                .max(Comparator.comparingInt(mount -> mount.containerPath().length()))
                .map(mount -> containerPath.equals(mount.containerPath())
                        ? mount.hostPath()
                        : mount.hostPath().resolve(containerPath.substring(mount.containerPath().length() + 1))
                        .normalize());
    }

    private static Optional<String> keyFile(Map<String, Object> oidc, String block) {
        return oidc.get(block) instanceof Map<?, ?> map && map.get("key_file") != null
                ? Optional.of(String.valueOf(map.get("key_file")))
                : Optional.empty();
    }

    private static String requiredClientKeyFile(Gateway gateway) {
        return gateway.clientKeyFile().orElseThrow(() -> new AssertionError(gateway.name()
                + " declares no oidc.client_authentication.key_file: a client key generated at boot differs "
                + "per instance and cannot be the key another instance publishes"));
    }

    private static void assertIsASigningKeyFile(Path file, String namedBy) throws IOException {
        assertTrue(file.startsWith(SIGNING_KEYS),
                () -> namedBy + " names " + file + ", which is outside " + SIGNING_KEYS);
        assertTrue(Files.isRegularFile(file), () -> namedBy + " names " + file + ", which does not exist");
        List<String> blocks = Files.readAllLines(file).stream().filter(line -> line.startsWith("-----BEGIN ")).toList();
        assertEquals(List.of("-----BEGIN PRIVATE KEY-----", "-----BEGIN PUBLIC KEY-----"), blocks,
                () -> namedBy + " names " + file + ", which must hold exactly one PKCS#8 private key block "
                        + "followed by its public key block");
    }

    // ---------------------------------------------------------------------------------------------
    // The realm import
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Map<String, Object>> realmClients() throws IOException {
        Map<String, Map<String, Object>> clients = new LinkedHashMap<>();
        Object declared = loadYaml(REALM_IMPORT).get("clients");
        assertInstanceOf(List.class, declared, "the realm import must declare a clients array: " + REALM_IMPORT);
        for (Object entry : (List<?>) declared) {
            Map<String, Object> client = mapping(entry, "a realm client");
            clients.put(assertInstanceOf(String.class, client.get("clientId"), "a realm client must carry a clientId"),
                    client);
        }
        assertFalse(clients.isEmpty(), "the realm import declares no client: " + REALM_IMPORT);
        return clients;
    }

    private static Map<String, Object> realmClient(Map<String, Map<String, Object>> clients, Gateway gateway) {
        Map<String, Object> client = clients.get(gateway.clientId());
        assertNotNull(client, () -> gateway.name() + " names client " + gateway.clientId()
                + ", which the realm import does not declare; it declares " + clients.keySet());
        return client;
    }

    private static Map<String, Object> attributes(Map<String, Object> client) {
        return mapping(client.get("attributes"), "the attributes of realm client " + client.get("clientId"));
    }

    /**
     * The attributes under which Keycloak holds a client key in the realm itself — a certificate or a
     * public key, or a key set given as text — in place of a JWKS URL.
     */
    private static List<String> staticKeyAttributes(Map<String, Object> attributes) {
        return attributes.keySet().stream()
                .filter(key -> key.startsWith("jwt.credential.") || key.contains("jwks.string"))
                .sorted().toList();
    }

    private static void assertRequiresBothControls(Map<String, Object> attributes) {
        assertAll(
                () -> assertEquals("true", attributes.get(PUSHED_REQUESTS_REQUIRED),
                        "the client must be required to push its authorization requests"),
                () -> assertEquals("true", attributes.get(DPOP_BOUND_TOKENS),
                        "the client's access tokens must be bound to a DPoP proof key"));
    }

    private static URI jwksUrl(Map<String, Map<String, Object>> clients, Gateway gateway) {
        Map<String, Object> client = realmClient(clients, gateway);
        return URI.create(assertInstanceOf(String.class, attributes(client).get("jwks.url"),
                "realm client " + gateway.clientId() + " must name a JWKS URL"));
    }

    private static Gateway publisher(List<Gateway> gateways, URI jwksUrl) {
        return gateways.stream().filter(candidate -> candidate.networkNames().contains(jwksUrl.getHost()))
                .findFirst().orElseThrow(() -> new AssertionError("the JWKS URL " + jwksUrl
                + " names host " + jwksUrl.getHost() + ", which is no gateway service of the compose model"));
    }

    // ---------------------------------------------------------------------------------------------
    // The release smoke step
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@code docker run} of the smoke step.
     *
     * @param environment the {@code -e NAME=value} entries
     * @param mounts      the {@code -v} mounts, their sources resolved against this checkout
     */
    private record SmokeRun(Map<String, String> environment, List<Mount> mounts) {

        String resolve(String value) {
            Matcher reference = BARE_REFERENCE.matcher(value);
            if (!reference.matches()) {
                return value;
            }
            String supplied = environment.get(reference.group(1));
            assertNotNull(supplied, () -> "the smoke step passes no -e " + reference.group(1)
                    + ", which the base descriptor references as " + value);
            return supplied;
        }
    }

    private static SmokeRun smokeRun() throws IOException {
        String script = smokeScript().replace("\\\n", " ");
        List<String> dockerRuns = script.lines().map(String::strip)
                .filter(line -> line.startsWith("docker run ")).toList();
        assertEquals(1, dockerRuns.size(),
                () -> "the step '" + SMOKE_STEP + "' must carry exactly one docker run, found " + dockerRuns);
        Map<String, String> assignments = new LinkedHashMap<>();
        assignments.put("GITHUB_WORKSPACE", REPOSITORY.toString());
        script.lines().map(SHELL_ASSIGNMENT::matcher).filter(Matcher::matches)
                .forEach(assignment -> assignments.put(assignment.group(1), assignment.group(2)));

        Map<String, String> environment = new LinkedHashMap<>();
        List<Mount> mounts = new ArrayList<>();
        List<String> words = shellWords(dockerRuns.getFirst());
        for (int index = 0; index < words.size() - 1; index++) {
            String argument = words.get(index + 1);
            if ("-e".equals(words.get(index))) {
                int separator = argument.indexOf('=');
                assertTrue(separator > 0, () -> "the smoke step passes a variable without a value: -e " + argument);
                environment.put(argument.substring(0, separator), argument.substring(separator + 1));
            } else if ("-v".equals(words.get(index))) {
                mounts.add(mount(expand(argument, assignments), REPOSITORY));
            }
        }
        assertFalse(environment.isEmpty(), "no -e entry was read from the docker run of '" + SMOKE_STEP
                + "', so the step was reshaped and this group would check nothing");
        assertFalse(mounts.isEmpty(), "no -v mount was read from the docker run of '" + SMOKE_STEP
                + "', so the step was reshaped and this group would check nothing");
        return new SmokeRun(environment, mounts);
    }

    /** The {@code run} script of the smoke step, found by the step's name in whichever job carries it. */
    private static String smokeScript() throws IOException {
        List<String> scripts = new ArrayList<>();
        for (Object job : mapping(loadYaml(RELEASE_WORKFLOW).get("jobs"), "the jobs of " + RELEASE_WORKFLOW).values()) {
            if (mapping(job, "a workflow job").get("steps") instanceof List<?> steps) {
                for (Object step : steps) {
                    Map<String, Object> declared = mapping(step, "a workflow step");
                    if (SMOKE_STEP.equals(declared.get("name"))) {
                        scripts.add(assertInstanceOf(String.class, declared.get("run"),
                                "the step '" + SMOKE_STEP + "' must carry a run script"));
                    }
                }
            }
        }
        assertEquals(1, scripts.size(), () -> RELEASE_WORKFLOW + " must carry exactly one step named '"
                + SMOKE_STEP + "'; a renamed step is not guarded by this class any more");
        return scripts.getFirst();
    }

    /** Splits a shell command line into words, honouring double quotes and dropping them. */
    private static List<String> shellWords(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        for (char character : line.toCharArray()) {
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(character);
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    /** Expands the {@code ${NAME}} references of a shell word from the script's own assignments. */
    private static String expand(String word, Map<String, String> assignments) {
        String expanded = word;
        for (int pass = 0; pass < assignments.size() + 1 && BARE_REFERENCE.matcher(expanded).find(); pass++) {
            expanded = BARE_REFERENCE.matcher(expanded).replaceAll(reference -> {
                String value = assignments.get(reference.group(1));
                assertNotNull(value, () -> "the smoke step uses ${" + reference.group(1) + "} in '" + word
                        + "' and the script assigns no such variable");
                return Matcher.quoteReplacement(value);
            });
        }
        return expanded;
    }

    // ---------------------------------------------------------------------------------------------
    // Document access
    // ---------------------------------------------------------------------------------------------

    /** Every bare {@code ${VAR}} reference a parsed document carries in a value, at any depth. */
    private static Set<String> bareReferences(Object node) {
        Set<String> references = new TreeSet<>();
        Stream<?> children = switch (node) {
            case Map<?, ?> map -> map.values().stream();
            case List<?> list -> list.stream();
            case null, default -> Stream.empty();
        };
        children.forEach(child -> references.addAll(bareReferences(child)));
        if (node instanceof String value) {
            Matcher reference = BARE_REFERENCE.matcher(value);
            while (reference.find()) {
                references.add(reference.group(1));
            }
        }
        return references;
    }

    private static Map<String, Object> composeServices() throws IOException {
        return mapping(loadYaml(MODULE.resolve("docker-compose.yml")).get("services"),
                "the services of docker-compose.yml");
    }

    /**
     * Parses a committed document. JSON is a subset of YAML, so the parser already on this module's
     * test path reads the realm import as well.
     */
    private static Map<String, Object> loadYaml(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> "this guard reads " + file + ", which does not exist");
        try (InputStream in = Files.newInputStream(file)) {
            return mapping(new Yaml().load(in), file.toString());
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
