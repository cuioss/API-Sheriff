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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
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
 * <strong>The positive half is exact, not shape-only.</strong> An http issuer's allowlist is
 * asserted to be precisely {@code [host-of-its-own-jwks.url]}: each entry must be a
 * {@link String} instance (not merely something {@code String.valueOf} can render) and the set as a
 * whole must name no host beyond the one that issuer actually fetches from. A shape-only predicate —
 * non-null, a list, non-empty, no blank entry — would wave through
 * {@code ["keycloak", "untrusted.example"]}, which widens the SSRF egress exception onto a host no
 * issuer in this stack ever contacts. Adding a second host is therefore a deliberate act that must
 * update this guard, never a silent descriptor edit.
 * <p>
 * <strong>Every issuer is classified, none is skipped.</strong> Both halves partition on
 * {@code jwks.source}, so an issuer whose source is absent or misspelled would match neither branch
 * and go unchecked — the very issuer most likely to be misconfigured. The source is therefore
 * asserted to be one of the two recognised values <em>before</em> the partition, and an unrecognised
 * one fails both tests loudly instead of falling through them.
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
    private static final int COMMITTED_DESCRIPTOR_COUNT = 5;

    private static final String HTTP_SOURCE = "http";
    private static final String FILE_SOURCE = "file";
    private static final String ALLOWLIST_KEY = "allowed_egress_hosts";
    private static final String SOURCE_KEY = "source";
    private static final Set<String> RECOGNISED_SOURCES = Set.of(HTTP_SOURCE, FILE_SOURCE);

    @Test
    @DisplayName("every http-sourced JWKS issuer allows egress to exactly the host its jwks.url names")
    void httpSourcedIssuersDeclareAnEgressAllowlist() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();
        int httpIssuersSeen = 0;

        // Act + Assert
        for (Path descriptor : descriptors) {
            for (Map<String, Object> issuer : issuers(descriptor)) {
                Map<String, Object> jwks = jwks(issuer);
                if (!HTTP_SOURCE.equals(jwksSource(descriptor, issuer, jwks))) {
                    continue;
                }
                httpIssuersSeen++;
                Object allowlist = jwks.get(ALLOWLIST_KEY);
                assertNotNull(allowlist, descriptor + " issuer '" + issuer.get("name")
                        + "' fetches its JWKS over http but declares no " + ALLOWLIST_KEY
                        + ". The egress guard then refuses the compose-internal address, the key set never"
                        + " loads, and every bearer request is rejected 401.");
                List<?> hosts = assertInstanceOf(List.class, allowlist, descriptor + " issuer '"
                        + issuer.get("name") + "' declares " + ALLOWLIST_KEY + " as "
                        + allowlist.getClass().getSimpleName() + ", expected a list");
                assertFalse(hosts.isEmpty(), descriptor + " issuer '" + issuer.get("name")
                        + "' declares an EMPTY " + ALLOWLIST_KEY + ", which guards nothing");
                List<String> declaredHosts = new ArrayList<>();
                for (Object host : hosts) {
                    String entry = assertInstanceOf(String.class, host, descriptor + " issuer '"
                            + issuer.get("name") + "' declares a non-String entry in " + ALLOWLIST_KEY + ": "
                            + host + ". The egress guard matches host names, so a non-String scalar names no"
                            + " host at all.");
                    assertFalse(entry.isBlank(), descriptor + " issuer '" + issuer.get("name")
                            + "' declares a blank host in " + ALLOWLIST_KEY);
                    declaredHosts.add(entry);
                }
                String expectedHost = egressHostOf(descriptor, issuer, jwks);
                assertEquals(List.of(expectedHost), declaredHosts, descriptor + " issuer '"
                        + issuer.get("name") + "' declares " + ALLOWLIST_KEY + " " + declaredHosts
                        + ", expected exactly [" + expectedHost + "] — the host its own jwks.url fetches"
                        + " from. Any other entry widens the SSRF egress exception onto a host this issuer"
                        + " never contacts; a genuinely needed one must be justified by updating this guard.");
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
        int fileIssuersSeen = 0;

        // Act + Assert — an offline issuer loads from a mounted file and performs no outbound fetch, so
        // an allowlist entry here would be an unjustified widening of the egress guard.
        for (Path descriptor : descriptors) {
            for (Map<String, Object> issuer : issuers(descriptor)) {
                Map<String, Object> jwks = jwks(issuer);
                if (!FILE_SOURCE.equals(jwksSource(descriptor, issuer, jwks))) {
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
     * so a new instance directory is covered automatically. The
     * {@link #COMMITTED_DESCRIPTOR_COUNT} floor is asserted here rather than at each call site, so an
     * empty or mis-rooted glob fails loudly instead of satisfying a per-descriptor loop vacuously.
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
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);
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
        assertInstanceOf(Map.class, jwks, "issuer '" + issuer.get("name") + "' declares a non-map jwks block");
        return (Map<String, Object>) jwks;
    }

    /**
     * The {@code jwks.source} of one issuer, asserted to be one of the two recognised values. Both
     * tests partition on this value, so an issuer whose source is absent, non-textual or misspelled
     * would match neither branch and never be checked for an egress allowlist at all. Failing here —
     * before the partition — turns that silent skip into a loud failure.
     *
     * @param descriptor the descriptor being parsed, for the failure message
     * @param issuer the parsed issuer node
     * @param jwks the issuer's jwks block
     * @return the declared source, guaranteed to be {@code http} or {@code file}
     */
    private static String jwksSource(Path descriptor, Map<String, Object> issuer, Map<String, Object> jwks) {
        Object declared = jwks.get(SOURCE_KEY);
        assertNotNull(declared, descriptor + " issuer '" + issuer.get("name") + "' declares no jwks."
                + SOURCE_KEY + ". This guard partitions issuers on that key, so an issuer without one is"
                + " checked by neither half.");
        String source = assertInstanceOf(String.class, declared, descriptor + " issuer '"
                + issuer.get("name") + "' declares a non-String jwks." + SOURCE_KEY + ": " + declared);
        assertTrue(RECOGNISED_SOURCES.contains(source), descriptor + " issuer '" + issuer.get("name")
                + "' declares jwks." + SOURCE_KEY + " '" + source + "', which is neither '" + HTTP_SOURCE
                + "' nor '" + FILE_SOURCE + "'. It would fall through both halves of this guard and never"
                + " be checked for an egress allowlist.");
        return source;
    }

    /**
     * The host an http-sourced issuer actually fetches its key set from, derived from its own
     * {@code jwks.url}. That derived host — not a hard-coded name — is the single entry its
     * {@code allowed_egress_hosts} is allowed to contain.
     *
     * @param descriptor the descriptor being parsed, for the failure message
     * @param issuer the parsed issuer node
     * @param jwks the issuer's jwks block, already known to declare {@code source: http}
     * @return the non-blank host component of the declared JWKS URL
     */
    private static String egressHostOf(Path descriptor, Map<String, Object> issuer, Map<String, Object> jwks) {
        Object declared = jwks.get("url");
        assertNotNull(declared, descriptor + " issuer '" + issuer.get("name")
                + "' fetches its JWKS over http but declares no jwks.url");
        String url = assertInstanceOf(String.class, declared, descriptor + " issuer '" + issuer.get("name")
                + "' declares a non-String jwks.url: " + declared);
        String host = URI.create(url).getHost();
        assertNotNull(host, descriptor + " issuer '" + issuer.get("name")
                + "' declares a jwks.url with no host component: " + url);
        assertFalse(host.isBlank(), descriptor + " issuer '" + issuer.get("name")
                + "' declares a jwks.url with a blank host component: " + url);
        return host;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
