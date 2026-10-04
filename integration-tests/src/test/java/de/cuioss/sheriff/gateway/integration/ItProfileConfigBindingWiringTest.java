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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
 * <strong>The profile binds two legs</strong>, and {@code gateway.yaml} selects it for each through
 * its own key:
 * <ul>
 *   <li>{@code jwks.tls_profile: benchmark-idp} — the JWKS fetch of a {@code token_validation}
 *       issuer;</li>
 *   <li>{@code egress_tls.oidc_tls_profile: benchmark-idp} — the BFF OIDC back-channel, the calls the
 *       {@code oidc} block makes to the identity provider.</li>
 * </ul>
 * Both legs dial the same Keycloak, which serves the stack's self-signed certificate, so both need
 * the same store.
 * <p>
 * The failure is not quiet on the first leg. {@code TokenValidatorProducer.onStartup} forces eager
 * validator assembly, during which {@code JwksTrustProfileResolver.resolve()} runs — so an unresolved
 * bucket <em>aborts boot</em> rather than degrading. Catching it here, in seconds, beats discovering
 * it as a container that never reaches readiness after a five-minute native build.
 * <p>
 * The second leg has a failure this class is the only fast guard for. A descriptor that declares an
 * {@code oidc} block and does not name the profile boots, reports ready, and then fails every login
 * on PKIX path building, because its back-channel is left on a trust store that does not hold the
 * Keycloak certificate. {@link #everyBffDescriptorNamesTheOidcTrustProfile()} pins the key on every
 * such descriptor. Two guards pin that no gateway reaches that trust through a process-global
 * command-line setting instead: {@link #noGatewayInstancePassesAJsseSystemProperty()} for the compose
 * gateway services, whose process arguments it reads from both their {@code entrypoint} and their
 * {@code command}, and {@link #noOneOffLaunchSitePassesAJsseSystemProperty()} for the one-off launch
 * sites — the test sources and scripts of this module that start the gateway image with a bare
 * {@code docker run} rather than as a compose service.
 * <p>
 * The instance set is <strong>derived from the parsed compose model</strong>, never enumerated. A
 * hand-maintained list could not fire for the very case the guard exists for: a gateway instance
 * added later that sets {@code QUARKUS_PROFILE=it} and forgets the config location. That case is not
 * hypothetical — the set has grown to twelve since this guard was written, and it covered each new
 * instance without an edit here. Because a derived set can also silently become empty, the derivation
 * asserts its own non-emptiness before looping — otherwise every assertion below would pass vacuously.
 * The descriptor set of the second leg is derived the same way, by directory glob, and asserts a
 * floor for the same reason. So is the one-off launch-site set: every file under {@code src/test/java}
 * and {@code scripts/} whose text names the gateway image, by its literal or through the harness
 * constant that holds it, which asserts a floor and names its two
 * known launch sites, so a scan that stopped matching them cannot pass vacuously. That set is a
 * <em>source-text</em> scan, and its limit is stated where it is defined — see
 * {@link #oneOffLaunchSites()}.
 * <p>
 * The binding is asserted as LIST MEMBERSHIP rather than whole-value equality, because
 * {@code QUARKUS_CONFIG_LOCATIONS} is comma-separated and two instances legitimately load a second
 * location beside this one — see {@link #configLocations(String)}.
 * <p>
 * It reads the committed descriptors and sources only — it starts no container and reaches no network.
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
    private static final String LOCATIONS_PREFIX = LOCATIONS_VARIABLE + "=";

    private static final String BUCKET_PREFIX = "quarkus.tls.benchmark-idp.trust-store.p12.";

    /** The deployment switch for file logging, now that the shipped artifact defaults it to off. */
    private static final String LOG_FILE_ENABLED = "QUARKUS_LOG_FILE_ENABLED=true";

    /** The docker fixture tree holding one directory per gateway descriptor. */
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** Every descriptor directory, the base one and each overlay, matches this glob. */
    private static final String DESCRIPTOR_DIRECTORY_GLOB = "sheriff-config*";
    private static final String DESCRIPTOR_FILE = "gateway.yaml";

    /**
     * The number of committed descriptors that declare an {@code oidc} block. A floor rather than an
     * exact count, so a descriptor added later is swept without an edit here, while a glob or a filter
     * that stopped matching cannot leave the sweep iterating nothing.
     */
    private static final int MINIMUM_BFF_DESCRIPTORS = 9;

    private static final String OIDC_BLOCK = "oidc";
    private static final String EGRESS_TLS_BLOCK = "egress_tls";
    private static final String OIDC_TLS_PROFILE_KEY = "oidc_tls_profile";
    private static final String OIDC_VERIFY_HOSTNAME_KEY = "oidc_verify_hostname";
    private static final String TRUST_PROFILE = "benchmark-idp";

    /**
     * The prefix of every JSSE system property passed on a command line. Such a property sets a
     * default for the whole process — every TLS client in it, not one leg — which is what a named
     * profile exists to avoid.
     */
    private static final String JSSE_SYSTEM_PROPERTY_PREFIX = "-Djavax.net.ssl.";

    /**
     * The compose keys that carry the process arguments of a service, in the order compose assembles
     * them: the {@code entrypoint} first, then the {@code command} appended to it. A guard that read
     * only one of them would pass for an argument moved into the other.
     */
    private static final List<String> PROCESS_ARGUMENT_KEYS = List.of("entrypoint", "command");

    /**
     * The gateway image a one-off launch site names. Taken from the harness rather than restated as a
     * literal, so the image name keeps a single home.
     */
    private static final String GATEWAY_IMAGE = OneOffGatewayContainers.IMAGE;

    /** The harness class that holds the image constant. */
    private static final String HARNESS_CLASS = OneOffGatewayContainers.class.getSimpleName();

    /**
     * The spellings by which a file reaches the image through the harness constant instead of the
     * literal: the qualified {@code OneOffGatewayContainers.IMAGE}, which is also the tail of a single
     * static import of it, and the wildcard static import of the harness, after which {@code IMAGE}
     * appears unqualified.
     */
    private static final List<String> GATEWAY_IMAGE_CONSTANT_REFERENCES =
            List.of(HARNESS_CLASS + ".IMAGE", HARNESS_CLASS + ".*");

    /** The test-source tree of this module. */
    private static final Path TEST_SOURCES = MODULE.resolve("src/test/java");

    /** The module trees a one-off launch site lives in: the test sources and the shell scripts. */
    private static final List<Path> LAUNCH_SITE_ROOTS = List.of(TEST_SOURCES, MODULE.resolve("scripts"));

    /**
     * The source file of this guard, excluded from the launch-site population by path. It names the
     * harness constant and carries {@link #JSSE_SYSTEM_PROPERTY_PREFIX}, both as data the sweep looks
     * for rather than as a launch, so admitting it would fail the sweep on its own constants.
     */
    private static final Path GUARD_SOURCE = TEST_SOURCES
            .resolve(ItProfileConfigBindingWiringTest.class.getName().replace('.', '/') + ".java")
            .toAbsolutePath()
            .normalize();

    /**
     * The two one-off launch sites that used to pass a JSSE trust-store argument after the image,
     * pinned by file name so the derived set provably still reaches them.
     */
    private static final List<String> KNOWN_LAUNCH_SITES =
            List.of("OneOffGatewayContainers.java", "NoCertificatePlainHttpOptInIT.java");

    /**
     * The number of module files that name the gateway image, by its literal or through the harness
     * constant. A floor rather than an exact count, so a
     * launch site added later is swept without an edit here, while a scan that stopped matching cannot
     * leave the sweep iterating nothing.
     */
    private static final int MINIMUM_ONE_OFF_LAUNCH_SITES = 8;

    @Test
    @DisplayName("every it-profile gateway instance binds the mounted trust file")
    void everyItProfileInstanceBindsTheMountedTrustFile() throws Exception {
        // Arrange — derived from the compose file itself, so a new instance added there is covered
        // without a manual edit here. The derivation asserts its own non-emptiness.
        List<String> itServices = itProfileServices();

        // Act + Assert
        for (String service : itServices) {
            List<String> locations = configLocations(service);
            assertTrue(locations.contains(MOUNTED_PATH),
                    () -> service + " sets " + IT_PROFILE + " but must also load " + MOUNTED_PATH
                            + " via " + LOCATIONS_VARIABLE + " (it loads " + locations
                            + ") — gateway.yaml names jwks.tls_profile: benchmark-idp, and an "
                            + "unresolved bucket aborts boot in JwksTrustProfileResolver.resolve()");
        }
    }

    @Test
    @DisplayName("every descriptor with an oidc block names the benchmark-idp profile for the back-channel")
    void everyBffDescriptorNamesTheOidcTrustProfile() throws Exception {
        List<Path> descriptors = bffDescriptors();

        for (Path descriptor : descriptors) {
            Object egressTls = loadYaml(descriptor).get(EGRESS_TLS_BLOCK);
            assertInstanceOf(Map.class, egressTls,
                    () -> descriptor + " declares an " + OIDC_BLOCK + " block, so it must declare an "
                            + EGRESS_TLS_BLOCK + " block naming the trust profile of the back-channel");
            Map<?, ?> block = (Map<?, ?>) egressTls;
            assertEquals(TRUST_PROFILE, block.get(OIDC_TLS_PROFILE_KEY),
                    () -> descriptor + " must name " + EGRESS_TLS_BLOCK + "." + OIDC_TLS_PROFILE_KEY + ": "
                            + TRUST_PROFILE + " — without it the BFF OIDC back-channel does not trust the "
                            + "self-signed Keycloak certificate and every login fails PKIX path building, "
                            + "on an instance that booted and reported ready");
            assertFalse(block.containsKey(OIDC_VERIFY_HOSTNAME_KEY),
                    () -> descriptor + " must not declare " + EGRESS_TLS_BLOCK + "." + OIDC_VERIFY_HOSTNAME_KEY
                            + " — the gateway refuses that key together with a named "
                            + OIDC_TLS_PROFILE_KEY + " at boot");
        }
    }

    @Test
    @DisplayName("no gateway instance passes a JSSE system property on its command line")
    void noGatewayInstancePassesAJsseSystemProperty() throws Exception {
        List<String> gatewayServices = gatewayServices();
        assertFalse(gatewayServices.isEmpty(),
                "the derived gateway-instance set must not be empty — an empty set makes the sweep below "
                        + "a vacuous pass");

        for (String service : gatewayServices) {
            for (String key : PROCESS_ARGUMENT_KEYS) {
                List<String> arguments = processArguments(service, key);
                assertTrue(arguments.stream().noneMatch(entry -> entry.contains(JSSE_SYSTEM_PROPERTY_PREFIX)),
                        () -> service + " passes a " + JSSE_SYSTEM_PROPERTY_PREFIX + "* argument in its compose "
                                + key + " (" + arguments + "). That sets a JSSE default for the whole process; "
                                + "the trust of each leg is named in gateway.yaml as a profile instead "
                                + "(jwks.tls_profile, " + EGRESS_TLS_BLOCK + "." + OIDC_TLS_PROFILE_KEY + ")");
            }
        }
    }

    @Test
    @DisplayName("no one-off gateway launch site passes a JSSE system property")
    void noOneOffLaunchSitePassesAJsseSystemProperty() throws Exception {
        List<Path> launchSites = oneOffLaunchSites();

        for (Path launchSite : launchSites) {
            assertFalse(readText(launchSite).contains(JSSE_SYSTEM_PROPERTY_PREFIX),
                    () -> MODULE.relativize(launchSite) + " names the " + GATEWAY_IMAGE + " image and carries a "
                            + JSSE_SYSTEM_PROPERTY_PREFIX + "* argument. That sets a JSSE default for the whole "
                            + "process; the trust of each leg is named in gateway.yaml as a profile instead "
                            + "(jwks.tls_profile, " + EGRESS_TLS_BLOCK + "." + OIDC_TLS_PROFILE_KEY + ")");
        }
    }

    @Test
    @DisplayName("every it-profile gateway instance switches file logging on")
    void everyItProfileInstanceEnablesFileLogging() throws Exception {
        // Arrange — same derived set, same reason: a new instance is covered without an edit here.
        List<String> itServices = itProfileServices();

        // Act + Assert — the shipped artifact now defaults quarkus.log.file.enabled to false, so a
        // LOG_FILE_PATH on its own produces no file at all. The IT suite reads those files
        // (ManagementPlainHttpOptOutIT asserts on the ApiSheriff-115 downgrade warning inside the
        // plain-management container's log), and a missing switch would surface as a puzzling
        // assertion failure rather than as the wiring gap it is.
        for (String service : itServices) {
            assertTrue(environment(service).contains(LOG_FILE_ENABLED),
                    () -> service + " sets " + IT_PROFILE + " but must also set " + LOG_FILE_ENABLED
                            + " — file logging ships off by default, so LOG_FILE_PATH alone writes "
                            + "nothing and the log-reading ITs lose their evidence");
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
     * <p>
     * The derivation asserts its own non-emptiness rather than leaving that to each caller: a
     * silently-empty set would turn every per-service loop in this class into a vacuous pass, and
     * pinning it here means a new per-service guard inherits the anti-vacuity check by construction
     * instead of having to remember to restate it.
     */
    private static List<String> itProfileServices() throws IOException {
        List<String> matches = new ArrayList<>();
        for (String service : gatewayServices()) {
            if (environment(service).contains(IT_PROFILE)) {
                matches.add(service);
            }
        }
        assertFalse(matches.isEmpty(),
                "the derived it-profile gateway-instance set must not be empty — an empty set turns "
                        + "every per-service guard in this class into a vacuous pass");
        return List.copyOf(matches);
    }

    /** Every gateway instance in the compose topology — each is named after the application. */
    private static List<String> gatewayServices() throws IOException {
        return services().keySet().stream()
                .filter(name -> name.startsWith("api-sheriff"))
                .sorted()
                .toList();
    }

    /**
     * The config locations a service loads, as a list of individual paths.
     * <p>
     * {@code QUARKUS_CONFIG_LOCATIONS} is a comma-separated LIST, not a single path, so membership is
     * the only faithful question to ask of it. Ten instances load exactly one location; the two
     * {@code api-sheriff-egress-verify-*} instances load a second one beside it
     * ({@code it-upstream-trust.properties}, which binds the {@code it-upstream} egress trust profile
     * their overlays name). An exact whole-value equality check would read that legitimate second
     * entry as a missing binding.
     * <p>
     * Splitting and asking for MEMBERSHIP is what keeps the guard honest in both directions. It admits
     * any number of additional locations, and it still fails an instance that drops the benchmark-idp
     * file — which a substring search over the raw environment would not, since such a search passes
     * on any entry that merely mentions the path.
     *
     * @param service the compose service name
     * @return the declared locations in order, or an empty list when the service declares none
     */
    private static List<String> configLocations(String service) throws IOException {
        return environment(service).stream()
                .filter(entry -> entry.startsWith(LOCATIONS_PREFIX))
                .flatMap(entry -> Arrays.stream(
                        entry.substring(LOCATIONS_PREFIX.length()).split(",")))
                .map(String::strip)
                .filter(location -> !location.isEmpty())
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

    /**
     * The entries of one of a service's process-argument keys — {@code entrypoint} or {@code command},
     * see {@link #PROCESS_ARGUMENT_KEYS} — accepting both compose forms each key takes: the list form
     * and the single string. Reading one key only, or the list form only, would let a key or a form
     * switch turn the absence assertion vacuously green.
     *
     * @param service the compose service name
     * @param key     the compose key to read, {@code entrypoint} or {@code command}
     * @return the declared entries, or an empty list when the service does not declare the key
     */
    private static List<String> processArguments(String service, String key) throws IOException {
        Object arguments = serviceNode(service).get(key);
        return switch (arguments) {
            case null -> List.of();
            case List<?> listForm -> listForm.stream().map(String::valueOf).toList();
            default -> List.of(String.valueOf(arguments));
        };
    }

    /**
     * Every file of this module that can start the gateway outside the compose topology: each regular
     * file under {@code src/test/java} or {@code scripts/} whose text names {@link #GATEWAY_IMAGE} —
     * by the image literal, or through the harness constant that holds it (see
     * {@link #GATEWAY_IMAGE_CONSTANT_REFERENCES}). Derived by scan, so a launch site added later is
     * swept without an edit here. The set also holds files that only inspect the image and launch
     * nothing; they carry no argument and pass trivially. This guard's own source is excluded by path
     * (see {@link #GUARD_SOURCE}).
     * <p>
     * The derivation asserts a floor and the presence of {@link #KNOWN_LAUNCH_SITES}, for the reason
     * {@link #itProfileServices()} asserts non-emptiness: a scan that stopped matching would otherwise
     * turn the sweep into a vacuous pass.
     * <p>
     * <strong>Limit:</strong> this is a source-text scan. It sees an argument written as one literal in
     * a file that names the image, whether through the image literal, the qualified
     * {@code OneOffGatewayContainers.IMAGE}, or a static import of that constant. It does not see an
     * argument assembled at run time by string concatenation, nor a file that reaches the image only
     * through an alias or re-export of the constant declared under another name.
     *
     * @return the launch-site files, sorted
     */
    private static List<Path> oneOffLaunchSites() throws IOException {
        List<Path> launchSites = new ArrayList<>();
        for (Path root : LAUNCH_SITE_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!GUARD_SOURCE.equals(file.toAbsolutePath().normalize()) && namesTheGatewayImage(file)) {
                        launchSites.add(file);
                    }
                }
            }
        }
        launchSites.sort(Comparator.naturalOrder());
        List<String> fileNames = launchSites.stream().map(file -> file.getFileName().toString()).toList();
        assertTrue(launchSites.size() >= MINIMUM_ONE_OFF_LAUNCH_SITES,
                () -> "expected at least " + MINIMUM_ONE_OFF_LAUNCH_SITES + " files under " + LAUNCH_SITE_ROOTS
                        + " to name the " + GATEWAY_IMAGE + " image, found " + launchSites.size() + ": "
                        + fileNames + " — a scan that stopped matching would turn the launch-site guard "
                        + "into a vacuous pass");
        assertTrue(fileNames.containsAll(KNOWN_LAUNCH_SITES),
                () -> "the derived launch-site set " + fileNames + " must contain " + KNOWN_LAUNCH_SITES
                        + " — the two one-off launch sites the JSSE trust-store argument was removed from");
        return List.copyOf(launchSites);
    }

    /**
     * Whether a file names the gateway image, by the literal or through the harness constant.
     *
     * @param file the candidate file
     * @return {@code true} when its text carries the image literal or a reference to the constant
     */
    private static boolean namesTheGatewayImage(Path file) throws IOException {
        String text = readText(file);
        return text.contains(GATEWAY_IMAGE) || GATEWAY_IMAGE_CONSTANT_REFERENCES.stream().anyMatch(text::contains);
    }

    /**
     * Reads a file as text without failing on bytes that are not UTF-8; a malformed sequence is
     * replaced, which cannot turn an ASCII match into a miss.
     */
    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Every committed gateway descriptor that declares an {@code oidc} block, derived by directory glob
     * so that an overlay added later is swept without an edit here.
     * <p>
     * A descriptor without an {@code oidc} block has no back-channel and is left out: the two
     * bearer-only one-off descriptors are the committed examples. The derivation asserts its own floor,
     * for the reason {@link #itProfileServices()} asserts non-emptiness.
     */
    private static List<Path> bffDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, DESCRIPTOR_DIRECTORY_GLOB)) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve(DESCRIPTOR_FILE);
                if (Files.isRegularFile(descriptor) && loadYaml(descriptor).containsKey(OIDC_BLOCK)) {
                    descriptors.add(descriptor);
                }
            }
        }
        descriptors.sort(Comparator.naturalOrder());
        assertTrue(descriptors.size() >= MINIMUM_BFF_DESCRIPTORS,
                () -> "expected at least " + MINIMUM_BFF_DESCRIPTORS + " descriptors under " + DOCKER
                        + " matching " + DESCRIPTOR_DIRECTORY_GLOB + "/" + DESCRIPTOR_FILE + " to declare an "
                        + OIDC_BLOCK + " block, found " + descriptors.size() + ": " + descriptors
                        + " — a glob or a filter that stopped matching would turn the per-descriptor "
                        + "guard into a vacuous pass");
        return List.copyOf(descriptors);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws IOException {
        Map<String, Object> compose = loadYaml(MODULE.resolve("docker-compose.yml"));
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
