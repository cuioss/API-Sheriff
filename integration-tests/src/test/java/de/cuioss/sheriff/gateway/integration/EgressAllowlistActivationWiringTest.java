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
 * Fast, no-Docker <em>surefire</em> guard that every committed deployment descriptor declares the
 * SSRF egress allowance of its JWKS fetches in one of the two accepted forms — and, just as
 * importantly, does <em>not</em> widen it on the fetches that need none.
 * <p>
 * token-sheriff's egress guard (GW-05 / BFF-07) refuses a JWKS URL resolving to a private address
 * unless its host is allowed. The gateway allows exactly one host per {@code source: http} issuer: an
 * issuer that declares no {@code jwks.allowed_egress_hosts} key is allowed the host of its own
 * {@code jwks.url} (the <em>derived</em> form), and an issuer that declares a non-empty list is allowed
 * precisely that list, never merged with the derived host (the <em>explicit</em> form).
 * <p>
 * <strong>The positive half accepts exactly those two forms, and nothing looser.</strong> An http
 * issuer either declares no {@code allowed_egress_hosts} key at all, or declares precisely
 * {@code [host-of-its-own-jwks.url]}: each entry must be a {@link String} instance (not merely something
 * {@code String.valueOf} can render) and the set as a whole must name no host beyond the one that issuer
 * actually fetches from. A shape-only predicate would wave through {@code ["keycloak",
 * "untrusted.example"]}, which widens the SSRF egress exception onto a host no issuer in this stack ever
 * contacts. A declared <em>empty</em> list, or a key declared with no value, is refused rather than read
 * as the derived form: omitting the key is the one spelling of "derive", so an empty list is either a
 * leftover or a mistake and must not pass silently.
 * <p>
 * <strong>Both forms must stay in the descriptor set.</strong> The derived form is what the native
 * stack's compose bring-up exercises — {@code integration-keycloak} loads its key set only through the
 * derived allowance — and the explicit form is the authoritative pin {@code benchmark-keycloak} keeps.
 * The two counters asserted at the end of the positive half keep either form from vanishing from the
 * committed descriptors unnoticed, which would silently drop the native proof of that form.
 * <p>
 * <strong>One fixture is carved out by name and held to the inverse.</strong>
 * {@value #MISMATCH_DIRECTORY}{@code /gateway.yaml} backs {@code JwksEgressMismatchIT}, which proves at
 * runtime that an explicit list naming <em>another</em> host keeps the key set refused. Its list is
 * therefore deliberately not the host of its own {@code jwks.url}, so it is excluded from the positive
 * half, and a dedicated test asserts the inverse: the file exists, it declares exactly one http issuer,
 * and that issuer's list is non-empty, all {@code String}, non-blank and does <em>not</em> contain the
 * host of its own {@code jwks.url}. Without that inverse assertion the fixture could degrade into a
 * matching or derived list and turn the refusal it exists for into a pass.
 * <p>
 * <strong>The negative half is the load-bearing one for widening.</strong> Asserting only the http
 * forms would pass equally well against a descriptor that had widened the allowlist onto <em>every</em>
 * issuer, including the offline {@code source: file} one that needs no egress at all. An unjustified
 * widening on a security gateway is exactly the drift worth catching, so the file-sourced issuers are
 * asserted to declare no allowlist — and the issuer counters keep either half from passing vacuously
 * against a descriptor set that happened to contain none of that kind.
 * <p>
 * <strong>Every issuer is classified, none is skipped.</strong> Both halves partition on
 * {@code jwks.source}, so an issuer whose source is absent or misspelled would match neither branch
 * and go unchecked — the very issuer most likely to be misconfigured. The source is therefore
 * asserted to be one of the two recognised values <em>before</em> the partition, and an unrecognised
 * one fails both tests loudly instead of falling through them.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network. The
 * sibling guard covering the body-size floor the same way is {@code BodyLimitActivationWiringTest}.
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
    private static final int COMMITTED_DESCRIPTOR_COUNT = 11;

    /** The directory of the refused-egress fixture, carved out of the positive half by name. */
    private static final String MISMATCH_DIRECTORY = "sheriff-config-jwks-egress-mismatch";

    private static final String HTTP_SOURCE = "http";
    private static final String FILE_SOURCE = "file";
    private static final String ALLOWLIST_KEY = "allowed_egress_hosts";
    private static final String SOURCE_KEY = "source";
    private static final Set<String> RECOGNISED_SOURCES = Set.of(HTTP_SOURCE, FILE_SOURCE);

    @Test
    @DisplayName("every http-sourced JWKS issuer declares no egress list or exactly the host its jwks.url names")
    void httpSourcedIssuersDeclareTheDerivedOrTheExactExplicitAllowance() throws Exception {
        List<Path> descriptors = committedGatewayDescriptors();
        int derivedIssuersSeen = 0;
        int explicitIssuersSeen = 0;

        for (Path descriptor : descriptors) {
            if (isMismatchFixture(descriptor)) {
                continue;
            }
            for (Map<String, Object> issuer : issuers(descriptor)) {
                Map<String, Object> jwks = jwks(issuer);
                if (!HTTP_SOURCE.equals(jwksSource(descriptor, issuer, jwks))) {
                    continue;
                }
                if (!jwks.containsKey(ALLOWLIST_KEY)) {
                    derivedIssuersSeen++;
                    continue;
                }
                List<String> declaredHosts = declaredHosts(descriptor, issuer, jwks);
                String expectedHost = egressHostOf(descriptor, issuer, jwks);
                assertEquals(List.of(expectedHost), declaredHosts, descriptor + " issuer '"
                        + issuer.get("name") + "' declares " + ALLOWLIST_KEY + " " + declaredHosts
                        + ", expected exactly [" + expectedHost + "] — the host its own jwks.url fetches"
                        + " from — or no " + ALLOWLIST_KEY + " key at all, which derives that host. Any other"
                        + " entry widens the SSRF egress exception onto a host this issuer never contacts; a"
                        + " genuinely needed one must be justified by updating this guard.");
                explicitIssuersSeen++;
            }
        }

        assertTrue(derivedIssuersSeen > 0, "no http-sourced JWKS issuer in any committed descriptor omits "
                + ALLOWLIST_KEY + " — the derived allowance is no longer exercised by the native stack; check"
                + " the glob and the token_validation blocks");
        assertTrue(explicitIssuersSeen > 0, "no http-sourced JWKS issuer in any committed descriptor declares an explicit "
                + ALLOWLIST_KEY + " — the authoritative explicit form is no longer exercised by the native stack;"
                + " check the glob and the token_validation blocks");
    }

    @Test
    @DisplayName("the refused-egress fixture's single http issuer allows a host other than its own jwks.url host")
    void mismatchFixtureDeclaresAnExplicitListNamingAnotherHost() throws Exception {
        Path fixture = committedGatewayDescriptors().stream()
                .filter(EgressAllowlistActivationWiringTest::isMismatchFixture)
                .findFirst()
                .orElse(null);
        assertNotNull(fixture, "the refused-egress fixture " + MISMATCH_DIRECTORY + "/gateway.yaml is not"
                + " committed under " + DOCKER + ", so JwksEgressMismatchIT has nothing to refuse");

        List<Map<String, Object>> httpIssuers = new ArrayList<>();
        for (Map<String, Object> issuer : issuers(fixture)) {
            if (HTTP_SOURCE.equals(jwksSource(fixture, issuer, jwks(issuer)))) {
                httpIssuers.add(issuer);
            }
        }

        assertEquals(1, httpIssuers.size(), fixture + " must declare exactly one http-sourced issuer, found "
                + httpIssuers.size());
        Map<String, Object> issuer = httpIssuers.getFirst();
        Map<String, Object> jwks = jwks(issuer);
        assertTrue(jwks.containsKey(ALLOWLIST_KEY), fixture + " issuer '" + issuer.get("name")
                + "' declares no " + ALLOWLIST_KEY + ", so it derives the jwks.url host and the refusal"
                + " JwksEgressMismatchIT asserts cannot happen");
        List<String> declaredHosts = declaredHosts(fixture, issuer, jwks);
        String ownHost = egressHostOf(fixture, issuer, jwks);
        assertFalse(declaredHosts.contains(ownHost), fixture + " issuer '" + issuer.get("name") + "' declares "
                + ALLOWLIST_KEY + " " + declaredHosts + ", which names its own jwks.url host '" + ownHost
                + "'. The fixture exists to prove that a list naming ANOTHER host keeps the key set refused.");
    }

    @Test
    @DisplayName("a file-sourced JWKS issuer declares no egress allowlist")
    void fileSourcedIssuersDeclareNoEgressAllowlist() throws Exception {
        List<Path> descriptors = committedGatewayDescriptors();
        int fileIssuersSeen = 0;

        // An offline issuer loads from a mounted file and performs no outbound fetch, so an allowlist
        // entry here would be an unjustified widening of the egress guard.
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
     * Whether a descriptor is the refused-egress fixture, matched by its exact directory name so the
     * carve-out can never swallow a second descriptor.
     *
     * @param descriptor a committed {@code gateway.yaml}
     * @return {@code true} for {@value #MISMATCH_DIRECTORY}{@code /gateway.yaml} only
     */
    private static boolean isMismatchFixture(Path descriptor) {
        return MISMATCH_DIRECTORY.equals(descriptor.getParent().getFileName().toString());
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
     * halves partition on this value, so an issuer whose source is absent, non-textual or misspelled
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
     * The hosts an http issuer that DOES declare the {@code allowed_egress_hosts} key names, asserted
     * to be a non-empty list of non-blank {@link String}s. A key declared with no value or with an
     * empty list is refused here: omitting the key is the one spelling of the derived allowance.
     *
     * @param descriptor the descriptor being parsed, for the failure message
     * @param issuer the parsed issuer node
     * @param jwks the issuer's jwks block, known to contain the key
     * @return the declared hosts, in declaration order
     */
    private static List<String> declaredHosts(Path descriptor, Map<String, Object> issuer,
            Map<String, Object> jwks) {
        Object allowlist = jwks.get(ALLOWLIST_KEY);
        assertNotNull(allowlist, descriptor + " issuer '" + issuer.get("name") + "' declares the "
                + ALLOWLIST_KEY + " key with no value. Omit the key to derive the jwks.url host.");
        List<?> hosts = assertInstanceOf(List.class, allowlist, descriptor + " issuer '"
                + issuer.get("name") + "' declares " + ALLOWLIST_KEY + " as "
                + allowlist.getClass().getSimpleName() + ", expected a list");
        assertFalse(hosts.isEmpty(), descriptor + " issuer '" + issuer.get("name") + "' declares an EMPTY "
                + ALLOWLIST_KEY + ". Omit the key to derive the jwks.url host; an empty list is not a"
                + " spelling of that.");
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
        return declaredHosts;
    }

    /**
     * The host an http-sourced issuer actually fetches its key set from, derived from its own
     * {@code jwks.url}. That derived host — not a hard-coded name — is the host the derived form
     * allows and the single entry an explicit list is allowed to contain.
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
