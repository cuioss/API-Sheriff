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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The <strong>JVM half</strong> of the two-runtime proof that the {@code benchmark-idp} TLS bucket
 * still resolves after being relocated out of the shipped artifact into deployment-supplied mounted
 * configuration.
 * <p>
 * The bucket used to sit in {@code application.properties} under a {@code %it} profile branch,
 * carrying a truststore path <em>and</em> its password. It now lives in
 * {@code integration-tests/src/main/docker/certificates/benchmark-idp-trust.properties}, which the
 * six {@code QUARKUS_PROFILE=it} gateway services pull in via {@code QUARKUS_CONFIG_LOCATIONS}.
 * <p>
 * The genuine risk in that shape is <strong>map-key discovery</strong>: Quarkus discovers
 * {@code quarkus.tls.<name>.*} buckets as configuration MAP KEYS, and the shipped file's own comment
 * records that this project does not rely on map-key discovery from a bare environment variable.
 * Whether a mounted <em>file</em> supplies the key is therefore a real question, answered here by
 * observation. The failure would not be quiet: {@code TokenValidatorProducer.onStartup} forces eager
 * validator assembly and {@code JwksTrustProfileResolver.resolve()} runs during it, so an unresolved
 * bucket aborts boot outright.
 * <p>
 * <strong>The real deployed file is read, never a parallel fixture.</strong> Every value asserted
 * below — and every value the boot profile feeds Quarkus — is read out of that one file at test time,
 * so a change to it moves this test with it. A copied fixture could drift from the file the
 * containers mount, and this test would then pass while the deployment broke.
 * <p>
 * <strong>The one deliberate translation.</strong> The deployed file names its truststore at the
 * CONTAINER-absolute path {@code /app/certificates/localhost-truststore.p12}, which does not exist on
 * a developer machine — docker-compose bind-mounts the module's own {@code certificates/} directory
 * there. The boot profile therefore redirects that ONE key to the host-side mount SOURCE, computed
 * from the file's own declared value rather than hard-coded. That is a host/container path
 * translation of a single key, not a second copy of the bucket: the password, which is what actually
 * opens the store, still comes from the deployed file.
 * <p>
 * The three guards divide the work deliberately, and no one of them is sufficient alone. This test
 * proves the bucket RESOLVES AT BOOT in the JVM and that the file's material is real;
 * {@code ItProfileConfigBindingWiringTest} proves every {@code QUARKUS_PROFILE=it} service actually
 * WIRES the file in through {@code QUARKUS_CONFIG_LOCATIONS}; the containerised suite proves the two
 * halves meet in the native runtime, where all six containers boot the {@code api-sheriff:distroless}
 * image against this same file and the BFF login suite verifies Keycloak's self-signed certificate
 * through this very bucket.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@TestProfile(MountedTlsMapKeyTest.MountedTrustProfile.class)
@DisplayName("benchmark-idp map-key resolution from the mounted deployment file (JVM)")
class MountedTlsMapKeyTest {

    /** The logical trust-profile name {@code gateway.yaml} declares as {@code jwks.tls_profile}. */
    private static final String BUCKET = "benchmark-idp";

    /** The real deployment file, reached by the cross-module relative path idiom this module already uses. */
    private static final Path MOUNTED_TRUST_FILE =
            Path.of("../integration-tests/src/main/docker/certificates/benchmark-idp-trust.properties");

    /** The certificates directory as the containers see it; the mount source is the module-local one. */
    private static final String CONTAINER_CERTIFICATES_DIR = "/app/certificates/";
    private static final Path HOST_CERTIFICATES_DIR =
            Path.of("../integration-tests/src/main/docker/certificates");

    private static final String PREFIX = "quarkus.tls." + BUCKET + ".trust-store.p12.";
    private static final String PATH_KEY = PREFIX + "path";
    private static final String PASSWORD_KEY = PREFIX + "password";

    /**
     * The ordinal {@link PropertiesConfigSource} requires. It ranks nothing here — the config built
     * in {@link #configFromMountedFile()} deliberately carries this source and no other — and the
     * value mirrors what a real mounted config location scores (above the shipped
     * application.properties at 250) purely so it reads as the same thing the deployment does.
     */
    private static final int MOUNTED_SOURCE_ORDINAL = 275;

    @Inject
    TlsConfigurationRegistry registry;

    @Test
    @DisplayName("the bucket resolves at boot and carries trust material")
    void shouldResolveBucketAtBootWithTrustMaterial() {
        // Act — the registry was populated during the boot this test class already survived.
        Optional<TlsConfiguration> bucket = registry.get(BUCKET);

        // Assert — the named bucket materialized; gateway.yaml's jwks.tls_profile can bind to it.
        assertTrue(bucket.isPresent(),
                "the '" + BUCKET + "' bucket must resolve at boot — gateway.yaml names it as "
                        + "jwks.tls_profile, and JwksTrustProfileResolver.resolve() runs during the "
                        + "eager validator assembly TokenValidatorProducer.onStartup forces");

        // Assert — and it carries real trust material, not merely a registered name.
        assertNotNull(bucket.get().getTrustStoreOptions(),
                "the resolved '" + BUCKET + "' bucket must carry trust options — a name that resolves "
                        + "to no trust material fails the JWKS fetch with PKIX path building");
        assertNotNull(bucket.get().getTrustStore(),
                "the resolved '" + BUCKET + "' bucket must expose a loaded truststore — it was opened "
                        + "with the password the deployed file declares");
    }

    @Test
    @DisplayName("a bucket the deployment never declares does not resolve at boot")
    void shouldNotResolveABucketTheDeploymentNeverDeclares() {
        // Anti-vacuity control: a registry that answered for any name would make the assertion above
        // meaningless.
        assertTrue(registry.get("no-such-bucket").isEmpty(),
                "a bucket no configuration declares must not resolve — otherwise the boot assertion "
                        + "above would be vacuous");
    }

    @Test
    @DisplayName("the bucket's map key is discovered from the mounted file")
    void shouldDiscoverMapKeyFromMountedFile() throws Exception {
        // Arrange — the real deployed file, loaded as a config source above the shipped properties.
        SmallRyeConfig config = configFromMountedFile();

        // Act
        List<String> discovered = discoveredNames(config);

        // Assert — the file's keys form the map key Quarkus materializes the bucket from.
        assertTrue(discovered.contains(PATH_KEY),
                "the '" + BUCKET + "' map key must be discovered from the mounted file. Discovered "
                        + "quarkus.tls.* names: " + discovered);
        assertTrue(discovered.contains(PASSWORD_KEY),
                "the bucket's password key must be discovered too — a store that cannot be opened is "
                        + "the same failure as one that is absent. Discovered quarkus.tls.* names: "
                        + discovered);
    }

    @Test
    @DisplayName("the discovered values are the ones the deployed file declares")
    void shouldResolveTheValuesTheDeployedFileDeclares() throws Exception {
        // Arrange — the expectation comes out of the deployed file itself, so this test tracks that
        // file rather than restating literals that could drift from it.
        Properties deployed = deployedProperties();
        SmallRyeConfig config = configFromMountedFile();

        // Act + Assert
        assertEquals(deployed.getProperty(PATH_KEY), config.getOptionalValue(PATH_KEY, String.class).orElse(null),
                "the resolved truststore path must be the value the deployed file declares");
        assertEquals(deployed.getProperty(PASSWORD_KEY),
                config.getOptionalValue(PASSWORD_KEY, String.class).orElse(null),
                "the resolved truststore password must be the value the deployed file declares");
    }

    @Test
    @DisplayName("the declared password genuinely opens the declared truststore")
    void shouldOpenTheDeclaredTrustStoreWithTheDeclaredPassword() throws Exception {
        // Arrange
        Properties deployed = deployedProperties();
        Path hostStore = hostSideTrustStore(deployed);
        assertTrue(Files.isRegularFile(hostStore),
                "the mount source for " + deployed.getProperty(PATH_KEY) + " must exist at " + hostStore
                        + " — the containers mount this directory, so a missing file here means the "
                        + "bucket resolves to material that is not actually shipped with the deployment");

        // Act — open it with the password the deployed file declares, nothing else.
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(hostStore)) {
            store.load(in, deployed.getProperty(PASSWORD_KEY).toCharArray());
        }

        // Assert — the bucket carries real anchors, not merely a resolvable name.
        assertFalse(Collections.list(store.aliases()).isEmpty(),
                "the truststore must contain at least one anchor — an empty store resolves fine and "
                        + "still fails every JWKS fetch with PKIX path building");
    }

    /**
     * Translates the container-absolute truststore path the deployed file declares to the host-side
     * mount SOURCE. The translation is the mount itself: docker-compose bind-mounts this directory at
     * {@value #CONTAINER_CERTIFICATES_DIR}, so the two paths name one file.
     */
    private static Path hostSideTrustStore(Properties deployed) {
        String declared = deployed.getProperty(PATH_KEY);
        if (declared == null || !declared.startsWith(CONTAINER_CERTIFICATES_DIR)) {
            throw new IllegalStateException("the deployed file must name its truststore under "
                    + CONTAINER_CERTIFICATES_DIR + " — that is the directory docker-compose "
                    + "bind-mounts; got: " + declared);
        }
        return HOST_CERTIFICATES_DIR.resolve(declared.substring(CONTAINER_CERTIFICATES_DIR.length()));
    }

    /**
     * Builds a config whose SOLE source is the real deployed file, loaded straight from disk.
     * <p>
     * The default sources are deliberately left out. They would drag in system properties — which
     * carry this class's own boot profile overrides, including the host-side path redirect — and the
     * assertions below would then be reading the test's own scaffolding back rather than the file.
     * Isolating the file is what makes "these keys came from the deployment" mean something.
     */
    private static SmallRyeConfig configFromMountedFile() throws IOException {
        URL url = MOUNTED_TRUST_FILE.toAbsolutePath().normalize().toUri().toURL();
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(url, MOUNTED_SOURCE_ORDINAL))
                .build();
    }

    /** @return every discovered configuration name under the {@code quarkus.tls.} prefix */
    private static List<String> discoveredNames(SmallRyeConfig config) {
        List<String> names = new ArrayList<>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("quarkus.tls.")) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static Properties deployedProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(MOUNTED_TRUST_FILE)) {
            properties.load(in);
        }
        return properties;
    }

    /**
     * Boots the gateway with the {@code benchmark-idp} bucket supplied from the REAL deployed file,
     * with only the container-absolute truststore path translated to its host-side mount source.
     * <p>
     * The values are read from the file here rather than restated, so this profile cannot drift from
     * the deployment. The redirect is applied as a profile override rather than by pointing
     * {@code quarkus.config.locations} at the file, because a file pulled in through that mechanism
     * outranks the test profile — the redirect would lose to the very key it must correct, and the
     * boot would die on {@code /app/certificates/localhost-truststore.p12}.
     */
    public static final class MountedTrustProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Properties deployed = deployedProperties();
                return Map.of(
                        PATH_KEY, hostSideTrustStore(deployed).toString(),
                        PASSWORD_KEY, deployed.getProperty(PASSWORD_KEY));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "the deployed benchmark-idp trust file must be readable at " + MOUNTED_TRUST_FILE, e);
            }
        }
    }
}
