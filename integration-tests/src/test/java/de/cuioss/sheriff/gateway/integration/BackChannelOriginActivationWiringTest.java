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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the integration stack addresses Keycloak through one
 * identity and one dial address, and through nothing else.
 * <p>
 * <strong>The contract.</strong> Keycloak's one identity is the published, browser-facing
 * {@value #PUBLISHED_ORIGIN}: {@code KC_HOSTNAME} in {@code docker-compose.yml} names it as a full URL,
 * so every issuer, discovery document and redirect Keycloak mints carries it, whichever address a
 * request arrived on. The gateways reach the same server on the compose network at
 * {@value #BACK_CHANNEL_ORIGIN}, and that dial address reaches a gateway through exactly one key,
 * {@code oidc.back_channel_origin}. A realm-level {@code frontendUrl} would compete with the static
 * hostname and hand the browser an address it cannot resolve, and
 * {@code KC_HOSTNAME_BACKCHANNEL_DYNAMIC} would let Keycloak mint the dial address into documents the
 * browser follows; both are therefore asserted absent.
 * <p>
 * <strong>Why a descriptor assertion.</strong> Every leg this contract governs is otherwise observable
 * only on a running stack. A descriptor that dropped {@code back_channel_origin} would make its gateway
 * dial the published identity from inside a container — where {@code localhost:1443} is the container
 * itself — and fail only once a Failsafe login reached it; a realm that regained {@code frontendUrl}
 * would strand every host-driven login on a name the test JVM cannot resolve. This guard turns both into
 * a red surefire run.
 * <p>
 * <strong>Every descriptor is covered, none is listed.</strong> The descriptors are enumerated from the
 * {@code sheriff-config*} directories and the realm imports from the {@code keycloak} directory of both
 * stacks that run Keycloak — the integration-test stack and the compose sample, whose realm and compose
 * file CI never starts — so a new instance or realm is held to the contract the moment it is committed.
 * The non-vacuity floor of
 * {@value #OIDC_DESCRIPTOR_FLOOR} oidc-bearing descriptors keeps an empty or mis-rooted enumeration from
 * passing the per-descriptor loop by finding nothing to check.
 * <p>
 * It parses the committed files only — it starts no container and reaches no network. Modelled on
 * {@code EgressAllowlistActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BackChannelOriginActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");
    private static final Path COMPOSE_SAMPLE = MODULE.resolve("../deployment/compose-sample").normalize();

    /**
     * The realm-import directory of every stack that runs Keycloak: the integration-test stack and the
     * compose sample. CI never starts the sample, so this guard is the only check its realm gets.
     */
    private static final List<Path> REALM_IMPORT_DIRECTORIES = List.of(DOCKER.resolve("keycloak"),
            COMPOSE_SAMPLE.resolve("docker/keycloak"));

    /** The compose file of every stack that runs Keycloak, held to the same static-hostname contract. */
    private static final List<Path> COMPOSE_FILES = List.of(MODULE.resolve("docker-compose.yml"),
            COMPOSE_SAMPLE.resolve("docker-compose.yml"));

    /** Keycloak's one identity: the published, browser-facing origin {@code KC_HOSTNAME} names. */
    private static final String PUBLISHED_ORIGIN = "https://localhost:1443";

    /** The authority of {@link #PUBLISHED_ORIGIN}, which every {@code oidc.issuer} must carry. */
    private static final String PUBLISHED_AUTHORITY = "localhost:1443";

    /** The compose-network address every gateway dials Keycloak at, through its back channel. */
    private static final String BACK_CHANNEL_ORIGIN = "https://keycloak:8443";

    /** The authority of {@link #BACK_CHANNEL_ORIGIN}, which no issuer may carry. */
    private static final String BACK_CHANNEL_AUTHORITY = "keycloak:8443";

    /**
     * The oidc-bearing descriptors committed today. The enumeration must find at least this many, so
     * an empty or mis-rooted directory scan fails loudly instead of passing vacuously.
     */
    private static final int OIDC_DESCRIPTOR_FLOOR = 10;

    private static final String KEYCLOAK_SERVICE = "keycloak";
    private static final String HOSTNAME_VARIABLE = "KC_HOSTNAME";
    private static final String DYNAMIC_BACK_CHANNEL_VARIABLE = "KC_HOSTNAME_BACKCHANNEL_DYNAMIC";
    private static final String FRONTEND_URL_KEY = "frontendUrl";

    @Test
    @DisplayName("every descriptor with an oidc block dials keycloak:8443 through back_channel_origin and issues on localhost:1443")
    void everyOidcDescriptorDeclaresTheBackChannelOriginAndThePublishedIssuer() throws Exception {
        int oidcDescriptorsSeen = 0;

        for (Path descriptor : committedGatewayDescriptors()) {
            Object oidcNode = loadYaml(descriptor).get("oidc");
            if (oidcNode == null) {
                continue;
            }
            Map<?, ?> oidc = assertInstanceOf(Map.class, oidcNode, descriptor + " declares a non-map oidc block");
            oidcDescriptorsSeen++;

            assertEquals(BACK_CHANNEL_ORIGIN, oidc.get("back_channel_origin"), descriptor
                    + " declares an oidc block whose back_channel_origin is not " + BACK_CHANNEL_ORIGIN + ". Without"
                    + " it the gateway dials the published identity " + PUBLISHED_ORIGIN + " from inside its"
                    + " container, where that name is the container itself.");

            String issuer = assertInstanceOf(String.class, oidc.get("issuer"),
                    descriptor + " declares an oidc block without a textual issuer");
            URI issuerUri = URI.create(issuer);
            assertEquals("https", issuerUri.getScheme(), descriptor + " oidc.issuer " + issuer + " is not https");
            assertEquals(PUBLISHED_AUTHORITY, issuerUri.getAuthority(), descriptor + " oidc.issuer " + issuer
                    + " is not on Keycloak's published identity " + PUBLISHED_ORIGIN + ", the one origin Keycloak"
                    + " mints into every token and discovery document.");
        }

        assertTrue(oidcDescriptorsSeen >= OIDC_DESCRIPTOR_FLOOR, "expected at least " + OIDC_DESCRIPTOR_FLOOR
                + " committed descriptors with an oidc block under " + DOCKER + ", found " + oidcDescriptorsSeen
                + " — the back-channel contract would pass vacuously; check the directory enumeration");
    }

    @Test
    @DisplayName("no token_validation issuer names the back-channel address keycloak:8443")
    void noTokenValidationIssuerNamesTheBackChannelAddress() throws Exception {
        int issuersSeen = 0;

        for (Path descriptor : committedGatewayDescriptors()) {
            for (Map<?, ?> issuer : tokenValidationIssuers(descriptor)) {
                issuersSeen++;
                String declared = assertInstanceOf(String.class, issuer.get("issuer"), descriptor
                        + " token_validation issuer '" + issuer.get("name") + "' declares no textual issuer");
                assertFalse(declared.contains(BACK_CHANNEL_AUTHORITY), descriptor + " token_validation issuer '"
                        + issuer.get("name") + "' declares issuer " + declared + ", which names the back-channel"
                        + " address. Keycloak mints its published identity " + PUBLISHED_ORIGIN + " into every"
                        + " token, so this issuer would reject every token of its realm.");
            }
        }

        assertTrue(issuersSeen > 0, "no token_validation issuer was found in any committed descriptor under "
                + DOCKER + " — this control would pass vacuously; check the directory enumeration");
    }

    @Test
    @DisplayName("no realm import declares a frontendUrl competing with the static hostname")
    void noRealmImportDeclaresAFrontendUrl() throws Exception {
        for (Path realm : realmImports()) {
            List<String> found = new ArrayList<>();
            collectKeyPaths(loadYaml(realm), "", FRONTEND_URL_KEY, found);
            assertEquals(List.of(), found, realm + " declares " + FRONTEND_URL_KEY + " at " + found + ". A"
                    + " frontend URL overrides KC_HOSTNAME for its realm and hands the browser an address other"
                    + " than the published identity " + PUBLISHED_ORIGIN + ".");
        }
    }

    @Test
    @DisplayName("every docker-compose gives Keycloak a full-URL KC_HOSTNAME and no dynamic back channel")
    void composeGivesKeycloakAFullUrlHostnameAndNoDynamicBackChannel() throws Exception {
        for (Path composeFile : COMPOSE_FILES) {
            List<String> environment = keycloakEnvironment(composeFile);

            List<String> hostnames = environment.stream()
                    .filter(entry -> entry.startsWith(HOSTNAME_VARIABLE + "="))
                    .map(entry -> entry.substring(HOSTNAME_VARIABLE.length() + 1))
                    .toList();

            assertEquals(List.of(PUBLISHED_ORIGIN), hostnames, composeFile + ": the keycloak service must set "
                    + HOSTNAME_VARIABLE + " exactly once, to the full URL " + PUBLISHED_ORIGIN + " — scheme and"
                    + " port included, so the identity Keycloak mints is the published one whichever address a"
                    + " request arrives on; found " + hostnames);
            assertFalse(environment.stream().anyMatch(entry -> entry.startsWith(DYNAMIC_BACK_CHANNEL_VARIABLE)),
                    composeFile + ": the keycloak service must not set " + DYNAMIC_BACK_CHANNEL_VARIABLE + ": the"
                            + " back channel is the gateway's routing decision through oidc.back_channel_origin,"
                            + " never a second identity Keycloak mints into its documents");
        }
    }

    /**
     * Every committed gateway descriptor under a {@code sheriff-config} directory, enumerated from the
     * directory so a new instance is covered automatically.
     *
     * @return the descriptor paths, in directory-stream order
     * @throws IOException when the docker directory cannot be listed
     */
    private static List<Path> committedGatewayDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve("gateway.yaml");
                if (Files.isRegularFile(descriptor)) {
                    descriptors.add(descriptor);
                }
            }
        }
        assertFalse(descriptors.isEmpty(), "no sheriff-config*/gateway.yaml descriptor was found under " + DOCKER);
        return descriptors;
    }

    /**
     * Every realm import a stack's Keycloak loads, enumerated from each stack's import directory. Each
     * directory must contribute at least one realm, so a mis-rooted path cannot pass by finding nothing.
     *
     * @return the realm import paths, in directory-stream order
     * @throws IOException when an import directory cannot be listed
     */
    private static List<Path> realmImports() throws IOException {
        List<Path> realms = new ArrayList<>();
        for (Path directory : REALM_IMPORT_DIRECTORIES) {
            int before = realms.size();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
                files.forEach(realms::add);
            }
            assertTrue(realms.size() > before, "no realm import was found under " + directory
                    + " — this control would pass vacuously; check the directory enumeration");
        }
        return realms;
    }

    /**
     * The {@code token_validation.issuers} entries of a descriptor.
     *
     * @param descriptor the gateway descriptor to parse
     * @return the declared issuers, or an empty list when the block is absent
     * @throws IOException when the descriptor cannot be read
     */
    private static List<Map<?, ?>> tokenValidationIssuers(Path descriptor) throws IOException {
        if (!(loadYaml(descriptor).get("token_validation") instanceof Map<?, ?> block)
                || !(block.get("issuers") instanceof List<?> declared)) {
            return List.of();
        }
        List<Map<?, ?>> issuers = new ArrayList<>();
        for (Object entry : declared) {
            issuers.add(assertInstanceOf(Map.class, entry, descriptor + " declares a non-map token_validation issuer"));
        }
        return issuers;
    }

    /**
     * Collects the path of every mapping key named {@code key} anywhere below {@code node}, so a key
     * nested in a client or in an attribute block is found as surely as one at the realm root.
     *
     * @param node  the parsed node to walk
     * @param path  the dotted path of {@code node}, for the failure message
     * @param key   the key name to find
     * @param found receives the dotted path of every occurrence
     */
    private static void collectKeyPaths(Object node, String path, String key, List<String> found) {
        if (node instanceof Map<?, ?> mapping) {
            mapping.forEach((name, value) -> {
                String childPath = path.isEmpty() ? String.valueOf(name) : path + "." + name;
                if (key.equals(name)) {
                    found.add(childPath);
                }
                collectKeyPaths(value, childPath, key, found);
            });
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectKeyPaths(list.get(index), path + "[" + index + "]", key, found);
            }
        }
    }

    /**
     * The environment list of a compose file's {@code keycloak} service.
     *
     * @param composeFile the compose file to parse
     * @return the environment entries, in {@code NAME=value} form
     * @throws IOException when the compose file cannot be read
     */
    private static List<String> keycloakEnvironment(Path composeFile) throws IOException {
        Map<?, ?> services = assertInstanceOf(Map.class, loadYaml(composeFile).get("services"),
                composeFile + " must declare services");
        Map<?, ?> keycloak = assertInstanceOf(Map.class, services.get(KEYCLOAK_SERVICE),
                composeFile + " must declare the '" + KEYCLOAK_SERVICE + "' service");
        List<?> environment = assertInstanceOf(List.class, keycloak.get("environment"),
                composeFile + ": the '" + KEYCLOAK_SERVICE + "' service environment must be a list");
        return environment.stream().map(String::valueOf).toList();
    }

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Object document = new Yaml().load(in);
            assertNotNull(document, path + " is empty");
            return assertInstanceOf(Map.class, document, path + " is not a mapping at its root");
        }
    }
}
