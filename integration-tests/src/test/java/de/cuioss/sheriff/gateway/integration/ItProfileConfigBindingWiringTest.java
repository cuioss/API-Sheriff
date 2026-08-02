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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Fast, no-Docker <em>surefire</em> guard that every {@code QUARKUS_PROFILE=it} gateway instance
 * actually <strong>binds</strong> the mounted {@code benchmark-idp} trust file — the deployment-side
 * half of the relocation that removed that bucket from the shipped artifact.
 * <p>
 * The bucket used to live in {@code application.properties} under a {@code %it} profile branch,
 * carrying a truststore path <em>and</em> its password. It now lives with the deployment, in
 * {@code src/main/docker/certificates/benchmark-idp-trust.properties}, and each gateway instance
 * pulls it in with {@code QUARKUS_CONFIG_LOCATIONS}. That makes the binding an <em>opt-in per
 * service</em>, and an unwired instance is exactly the failure this guard exists to catch.
 * <p>
 * The failure is not quiet. {@code gateway.yaml} names {@code jwks.tls_profile: benchmark-idp}, and
 * {@code TokenValidatorProducer.onStartup} forces eager validator assembly, during which
 * {@code JwksTrustProfileResolver.resolve()} runs — so an unresolved bucket <em>aborts boot</em>
 * rather than degrading. Catching it here, in seconds, beats discovering it as a container that never
 * reaches readiness after a five-minute native build.
 * <p>
 * The instance set is <strong>derived from the parsed compose model</strong>, never enumerated. A
 * hand-maintained list of six could not fire for the very case the guard exists for: a seventh
 * gateway instance added later that sets {@code QUARKUS_PROFILE=it} and forgets the config location.
 * Because a derived set can also silently become empty, the derivation asserts its own non-emptiness
 * before looping — otherwise every assertion below would pass vacuously.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Mounted benchmark-idp trust binding — deployment activation")
class ItProfileConfigBindingWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path MOUNTED_TRUST_FILE =
            MODULE.resolve("src/main/docker/certificates/benchmark-idp-trust.properties");
    private static final Path APPLICATION_PROPERTIES =
            MODULE.resolve("../api-sheriff/src/main/resources/application.properties");

    private static final String PROFILE_VARIABLE = "QUARKUS_PROFILE";
    private static final String IT_PROFILE = PROFILE_VARIABLE + "=it";
    private static final String LOCATIONS_VARIABLE = "QUARKUS_CONFIG_LOCATIONS";
    /** The container-side path of the mounted file, as the already-mounted certificates/ volume exposes it. */
    private static final String MOUNTED_PATH = "/app/certificates/benchmark-idp-trust.properties";
    private static final String LOCATIONS_BINDING = LOCATIONS_VARIABLE + "=" + MOUNTED_PATH;

    private static final String BUCKET_PREFIX = "quarkus.tls.benchmark-idp.trust-store.p12.";

    @Test
    @DisplayName("every it-profile gateway instance binds the mounted trust file")
    void everyItProfileInstanceBindsTheMountedTrustFile() throws Exception {
        // Arrange — derived from the compose file itself, so a seventh instance added there is
        // covered without a manual edit here.
        List<String> itServices = itProfileServices();

        // Guard the derivation: an empty set would turn every assertion below into a vacuous pass.
        assertFalse(itServices.isEmpty(),
                "the derived it-profile gateway-instance set must not be empty — an empty set turns "
                        + "this guard into a vacuous pass");

        // Act + Assert
        for (String service : itServices) {
            assertTrue(environment(service).contains(LOCATIONS_BINDING),
                    () -> service + " sets " + IT_PROFILE + " but must also set " + LOCATIONS_BINDING
                            + " — gateway.yaml names jwks.tls_profile: benchmark-idp, and an "
                            + "unresolved bucket aborts boot in JwksTrustProfileResolver.resolve()");
        }
    }

    @Test
    @DisplayName("no instance selects the it profile through a bare compose entry")
    void noInstanceSelectsTheItProfileThroughABareEntry() throws Exception {
        // A BARE `QUARKUS_PROFILE` entry resolves its value from the host shell at compose-up time,
        // so such a service could run the it profile while escaping the derived set above entirely —
        // unwired, and invisible to the guard. Forbid the form outright.
        for (String service : gatewayServices()) {
            assertFalse(environment(service).contains(PROFILE_VARIABLE),
                    () -> service + " must declare " + PROFILE_VARIABLE + " in KEY=value form — a bare "
                            + "entry takes its value from the host shell and would escape the derived "
                            + "it-profile set that gates the trust-file binding");
        }
    }

    @Test
    @DisplayName("the mounted file declares both trust-store keys")
    void theMountedFileDeclaresBothTrustKeys() throws Exception {
        List<String> keys = mountedPropertyLines();

        assertTrue(keys.stream().anyMatch(line -> line.startsWith(BUCKET_PREFIX + "path=")),
                "the mounted file must declare " + BUCKET_PREFIX + "path — without it the named "
                        + "bucket resolves to no trust material and the JWKS fetch fails PKIX");
        assertTrue(keys.stream().anyMatch(line -> line.startsWith(BUCKET_PREFIX + "password=")),
                "the mounted file must declare " + BUCKET_PREFIX + "password — the PKCS12 store is "
                        + "password-protected and an unreadable store is the same failure as an absent one");
    }

    @Test
    @DisplayName("the mounted file declares no profile-scoped key")
    void theMountedFileDeclaresNoProfileScopedKey() throws Exception {
        // The whole point of the relocation is that the deployment supplies the bucket unconditionally.
        // A %-prefixed key here would reintroduce the profile branch one directory over.
        assertTrue(mountedPropertyLines().stream().noneMatch(line -> line.startsWith("%")),
                "no key in the mounted file may be profile-scoped — the deployment supplies this "
                        + "bucket unconditionally; a % prefix would rebuild the branch that was removed");
    }

    @Test
    @DisplayName("the shipped artifact carries no benchmark-idp key and no trust password")
    void theShippedArtifactCarriesNoBenchmarkIdpKey() throws Exception {
        List<String> shipped = shippedPropertyLines();

        assertTrue(shipped.stream().noneMatch(line -> line.contains("benchmark-idp")),
                "application.properties must carry no benchmark-idp key at all — no anchor, no % "
                        + "variant, nothing. An unprefixed anchor would bake a benchmark/IT-specific "
                        + "profile name into product configuration where no %-prefix guard can catch it");
        assertTrue(shipped.stream().noneMatch(line -> line.contains("localhost-trust")),
                "the localhost-trust password literal must not appear in the shipped artifact — "
                        + "relocating the bucket to the deployment is what removes it");
    }

    /** @return the non-comment, non-blank lines of the mounted deployment trust file */
    private static List<String> mountedPropertyLines() throws IOException {
        return propertyLines(MOUNTED_TRUST_FILE);
    }

    /** @return the non-comment, non-blank lines of the shipped application.properties */
    private static List<String> shippedPropertyLines() throws IOException {
        return propertyLines(APPLICATION_PROPERTIES);
    }

    private static List<String> propertyLines(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /**
     * Every gateway instance that selects the {@code it} profile. Derived from the compose file so it
     * cannot drift from the deployment.
     */
    private static List<String> itProfileServices() throws IOException {
        List<String> matches = new java.util.ArrayList<>();
        for (String service : gatewayServices()) {
            if (environment(service).contains(IT_PROFILE)) {
                matches.add(service);
            }
        }
        return List.copyOf(matches);
    }

    /** Every gateway instance in the compose topology — each is named after the application. */
    private static List<String> gatewayServices() throws IOException {
        return services().keySet().stream()
                .filter(name -> name.startsWith("api-sheriff"))
                .sorted()
                .toList();
    }

    /** @return the service's {@code environment} list, or an empty list when it declares none */
    private static List<String> environment(String service) throws IOException {
        Object environment = serviceNode(service).get("environment");
        if (environment == null) {
            return List.of();
        }
        assertInstanceOf(List.class, environment, "the '" + service + "' service environment must be a list");
        return ((List<?>) environment).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws IOException {
        Map<String, Object> compose;
        try (InputStream in = Files.newInputStream(MODULE.resolve("docker-compose.yml"))) {
            compose = new Yaml().loadAs(in, Map.class);
        }
        Object services = compose.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serviceNode(String service) throws IOException {
        Object node = services().get(service);
        assertNotNull(node, "docker-compose.yml must declare the '" + service + "' service");
        assertInstanceOf(Map.class, node, "the '" + service + "' service must be a mapping");
        return (Map<String, Object>) node;
    }
}
