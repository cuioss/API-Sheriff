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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
 * Fast, no-Docker <em>surefire</em> guard that every committed deployment descriptor keeps the SSRF
 * egress allowlist on the JWKS fetches that need one — and, just as importantly, does <em>not</em>
 * widen it on the fetches that do not.
 * <p>
 * token-sheriff's egress guard (GW-05 / BFF-07) refuses a JWKS URL resolving to a private address
 * unless its host is named in {@code jwks.allowed_egress_hosts}. Every {@code source: http} issuer
 * in this stack points at the compose-internal {@code keycloak} service, which resolves to a
 * site-local bridge address — so dropping that allowlist entry does not fail loudly at boot. The key
 * set simply never loads, every bearer request is rejected 401, and a suite measuring bearer
 * throughput would report the <em>rejection</em> path as a success. That silent-degradation shape is
 * why this needs a descriptor assertion rather than a runtime one.
 * <p>
 * <strong>The negative half is the load-bearing one.</strong> Asserting only "every http issuer
 * declares an allowlist" would pass equally well against a descriptor that had widened the allowlist
 * onto <em>every</em> issuer, including the offline {@code source: file} one that needs no egress at
 * all. An unjustified widening on a security gateway is exactly the drift worth catching, so the
 * file-sourced issuers are asserted to declare no allowlist — and the issuer counters below keep
 * either half from passing vacuously against a descriptor set that happened to contain none of that
 * kind.
 * <p>
 * It parses the committed descriptors only and asserts the activation is present — it starts no
 * container and reaches no network. The sibling guard covering the body-size floor the same way is
 * {@code BodyLimitActivationWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class EgressAllowlistActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /**
     * The descriptor count committed today. The glob must match at least this many, so an empty or
     * mis-rooted glob fails loudly instead of satisfying the per-descriptor loop vacuously.
     */
    private static final int COMMITTED_DESCRIPTOR_COUNT = 4;

    private static final String HTTP_SOURCE = "http";
    private static final String FILE_SOURCE = "file";
    private static final String ALLOWLIST_KEY = "allowed_egress_hosts";

    @Test
    @DisplayName("every http-sourced JWKS issuer declares a non-empty egress allowlist")
    void httpSourcedIssuersDeclareAnEgressAllowlist() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);
        int httpIssuersSeen = 0;

        // Act + Assert
        for (Path descriptor : descriptors) {
            for (Map<String, Object> issuer : issuers(descriptor)) {
                Map<String, Object> jwks = jwks(issuer);
                if (!HTTP_SOURCE.equals(String.valueOf(jwks.get("source")))) {
                    continue;
                }
                httpIssuersSeen++;
                Object allowlist = jwks.get(ALLOWLIST_KEY);
                assertNotNull(allowlist, descriptor + " issuer '" + issuer.get("name")
                        + "' fetches its JWKS over http but declares no " + ALLOWLIST_KEY
                        + ". The egress guard then refuses the compose-internal address, the key set never"
                        + " loads, and every bearer request is rejected 401.");
                assertInstanceOfList(allowlist, descriptor, issuer);
                List<?> hosts = (List<?>) allowlist;
                assertFalse(hosts.isEmpty(), descriptor + " issuer '" + issuer.get("name")
                        + "' declares an EMPTY " + ALLOWLIST_KEY + ", which guards nothing");
                for (Object host : hosts) {
                    assertFalse(String.valueOf(host).isBlank(), descriptor + " issuer '" + issuer.get("name")
                            + "' declares a blank host in " + ALLOWLIST_KEY);
                }
            }
        }

        // The anti-vacuity control: a descriptor set with no http issuer at all would satisfy the loop
        // above without asserting anything.
        assertTrue(httpIssuersSeen > 0,
                "no http-sourced JWKS issuer was found in any committed descriptor — this guard would pass"
                        + " vacuously; check the glob and the token_validation block");
    }

    @Test
    @DisplayName("a file-sourced JWKS issuer declares no egress allowlist")
    void fileSourcedIssuersDeclareNoEgressAllowlist() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);
        int fileIssuersSeen = 0;

        // Act + Assert — an offline issuer loads from a mounted file and performs no outbound fetch, so
        // an allowlist entry here would be an unjustified widening of the egress guard.
        for (Path descriptor : descriptors) {
            for (Map<String, Object> issuer : issuers(descriptor)) {
                Map<String, Object> jwks = jwks(issuer);
                if (!FILE_SOURCE.equals(String.valueOf(jwks.get("source")))) {
                    continue;
                }
                fileIssuersSeen++;
                assertNull(jwks.get(ALLOWLIST_KEY), descriptor + " issuer '" + issuer.get("name")
                        + "' loads its JWKS from a mounted file yet declares " + ALLOWLIST_KEY
                        + ". It performs no outbound fetch, so that entry widens the SSRF egress guard for"
                        + " nothing — remove it rather than carrying an unjustified allowance.");
            }
        }

        assertTrue(fileIssuersSeen > 0,
                "no file-sourced JWKS issuer was found in any committed descriptor — this control would pass"
                        + " vacuously; check the glob and the token_validation block");
    }

    /**
     * Every committed gateway descriptor under a {@code sheriff-config} directory, discovered by glob
     * so a new instance directory is covered automatically.
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
        return descriptors;
    }

    /**
     * The {@code token_validation.issuers} list of a descriptor.
     *
     * @param descriptor the gateway descriptor to parse
     * @return the declared issuers, or an empty list when the block is absent
     * @throws IOException when the descriptor cannot be read
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issuers(Path descriptor) throws IOException {
        Map<String, Object> document = loadYaml(descriptor);
        Object tokenValidation = document.get("token_validation");
        if (!(tokenValidation instanceof Map<?, ?> block)) {
            return List.of();
        }
        Object declared = block.get("issuers");
        if (!(declared instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }

    /**
     * The {@code jwks} block of one issuer.
     *
     * @param issuer the parsed issuer node
     * @return the jwks block; never {@code null} — a missing block fails the assertion instead
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jwks(Map<String, Object> issuer) {
        Object jwks = issuer.get("jwks");
        assertNotNull(jwks, "issuer '" + issuer.get("name") + "' declares no jwks block");
        assertTrue(jwks instanceof Map, "issuer '" + issuer.get("name") + "' declares a non-map jwks block");
        return (Map<String, Object>) jwks;
    }

    private static void assertInstanceOfList(Object allowlist, Path descriptor, Map<String, Object> issuer) {
        assertTrue(allowlist instanceof List, descriptor + " issuer '" + issuer.get("name") + "' declares "
                + ALLOWLIST_KEY + " as " + allowlist.getClass().getSimpleName() + ", expected a list");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
