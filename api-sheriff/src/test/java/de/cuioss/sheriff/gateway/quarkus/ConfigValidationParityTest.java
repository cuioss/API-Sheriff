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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.ConfigValidationCommand;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.configuration.MemorySize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The single-sourcing enforcement between the gateway boot and the offline configuration check.
 * <p>
 * For every configuration set of the corpus it drives both paths with the same placeholder engine:
 * the boot path through {@link ConfigProducer} — capturing the {@code ApiSheriff-200} record it logs
 * per violation — and the offline path through {@link ConfigValidationCommand} — parsing its
 * {@code INVALID} lines. The two ordered {@code (file, pointer, message)} lists must be equal. The
 * corpus spans the shipped compose-sample and integration-test sets (both valid, read from their
 * sibling module directories), the shared classpath fixtures, and temporary sets failing at the
 * loader-schema, placeholder, topology and validator stages.
 * <p>
 * The one intended difference — the framework request-body ceiling, a runtime framework key the
 * offline check reports as not checked — is pinned by its own test as exactly that difference.
 */
@EnableTestLogger
class ConfigValidationParityTest {

    /** The repository root; surefire runs with the module root as the working directory. */
    private static final Path REPOSITORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

    /** The shipped {@code quarkus.http.limits.max-body-size} floor the boot path is wired with. */
    private static final long FRAMEWORK_LIMIT_BYTES = 67108864L;

    private static final String INVALID_PREFIX = "INVALID ";

    private static final String VALID_GATEWAY = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            """;

    private static final String BFF_ANCHOR_DECLARED_PUBLIC = """
            version: 1
            anchors:
              portal:
                path_prefix: /portal
                type: bff
                access: public
            """;

    private static final String PROXY_ENDPOINT = """
            endpoint:
              id: %1$s
              base_url: %2$s
              auth:
                require: none
              routes:
                - id: %1$s-proxied
                  match:
                    path_prefix: /%1$s
            """;

    @TempDir
    Path tempDir;

    /** Supplies the configuration directory of one corpus set. */
    @FunctionalInterface
    interface ConfigSetSource {

        /**
         * @param tempDir a fresh temporary directory the set may be written into
         * @return the configuration directory to validate
         * @throws IOException when the set cannot be written
         */
        Path materialize(Path tempDir) throws IOException;
    }

    /**
     * One configuration set of the corpus.
     *
     * @param name        the display name
     * @param source      where the set lives, or how it is written
     * @param environment the fixed placeholder lookup both paths resolve through
     * @param violating   whether the set is expected to carry at least one violation
     */
    record CorpusSet(String name, ConfigSetSource source, Map<String, String> environment, boolean violating) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** The outcome of one boot attempt: whether it refused, and its violation records in order. */
    private record BootRun(boolean refused, List<String> violationRecords) {
    }

    /** The outcome of one offline run: its exit code and every report line. */
    private record OfflineRun(int exitCode, List<String> lines) {

        List<ConfigError> violations() {
            return lines.stream().filter(line -> line.startsWith(INVALID_PREFIX))
                    .map(ConfigValidationParityTest::parseInvalidLine).toList();
        }
    }

    static Stream<CorpusSet> corpus() {
        return Stream.of(
                new CorpusSet("shipped compose-sample set",
                        ignored -> REPOSITORY.resolve("deployment/compose-sample/docker/sheriff-config"),
                        Map.of("SHERIFF_TRUSTED_PROXIES", "172.30.0.0/16"), false),
                new CorpusSet("shipped integration-test set",
                        ignored -> REPOSITORY.resolve("integration-tests/src/main/docker/sheriff-config"),
                        Map.of("OIDC_DPOP_KEY_FILE", "/app/signing-keys/dpop-ec.pem"), false),
                new CorpusSet("classpath /config/broken", ignored -> classpathSet("/config/broken"), Map.of(), true),
                new CorpusSet("classpath /config/anchored", ignored -> classpathSet("/config/anchored"), Map.of(),
                        false),
                new CorpusSet("classpath /config/anchor-squatter",
                        ignored -> classpathSet("/config/anchor-squatter"), Map.of(), true),
                new CorpusSet("schema stage", dir -> writeSet(dir, "version: 1\nbogus_unknown_key: x\n"), Map.of(),
                        true),
                new CorpusSet("unset placeholder", dir -> writeSet(dir, """
                        version: 1
                        metadata:
                          config_version: "${SHERIFF_TEST_ABSENT_VERSION}"
                        """), Map.of(), true),
                new CorpusSet("topology stage", ConfigValidationParityTest::writeTopologyFailureSet, Map.of(), true),
                new CorpusSet("validator stage", dir -> writeSet(dir, BFF_ANCHOR_DECLARED_PUBLIC), Map.of(), true),
                new CorpusSet("route without effective auth", ConfigValidationParityTest::writeRouteWithoutAuthSet,
                        Map.of(), true));
    }

    private static Path classpathSet(String resourceDir) throws IOException {
        URL resource = ConfigValidationParityTest.class.getResource(resourceDir);
        assertNotNull(resource, resourceDir + " fixture must be on the test classpath");
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static Path writeSet(Path dir, String gatewayYaml) throws IOException {
        Files.writeString(dir.resolve("gateway.yaml"), gatewayYaml);
        return dir;
    }

    private static void writeProxyEndpoint(Path dir, String id, String alias) throws IOException {
        Files.createDirectories(dir.resolve("endpoints"));
        Files.writeString(dir.resolve("endpoints/" + id + ".yaml"), PROXY_ENDPOINT.formatted(id, alias));
    }

    private static Path writeTopologyFailureSet(Path dir) throws IOException {
        writeSet(dir, VALID_GATEWAY);
        writeProxyEndpoint(dir, "alpha", "ALPHA");
        writeProxyEndpoint(dir, "beta", "BETA");
        Files.writeString(dir.resolve("topology.properties"), "ALPHA=${SHERIFF_TEST_ABSENT_ALPHA_URL}\n");
        return dir;
    }

    private static Path writeRouteWithoutAuthSet(Path dir) throws IOException {
        writeSet(dir, VALID_GATEWAY);
        Files.createDirectories(dir.resolve("endpoints"));
        Files.writeString(dir.resolve("endpoints/noauth.yaml"), """
                endpoint:
                  id: noauth
                  base_url: WEB_BACKEND
                  routes:
                    - id: noauth-proxied
                      match:
                        path_prefix: /noauth
                """);
        Files.writeString(dir.resolve("topology.properties"), "WEB_BACKEND=https://web.internal:8443\n");
        return dir;
    }

    private static ConfigError parseInvalidLine(String line) {
        String body = line.substring(INVALID_PREFIX.length());
        int open = body.indexOf(" [");
        int close = body.indexOf("]: ", open);
        return new ConfigError(body.substring(0, open), body.substring(open + 2, close), body.substring(close + 3));
    }

    private static String asBootRecord(ConfigError violation) {
        return ConfigLogMessages.ERROR.CONFIG_VALIDATION_FAILED.format(violation.file(), violation.pointer(),
                violation.message());
    }

    private static OfflineRun offline(Path configDir, EnvSecretResolver resolver) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            exitCode = ConfigValidationCommand.run(new String[]{"--validate-config", configDir.toString()}, out, err,
                    resolver);
        }
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8), "a readable set writes nothing to err");
        return new OfflineRun(exitCode, outBytes.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private static BootRun boot(Path configDir, EnvSecretResolver resolver, long frameworkLimitBytes) {
        ConfigProducer producer = new ConfigProducer();
        producer.configDir = configDir.toString();
        producer.frameworkBodyLimit = MemorySize.of(frameworkLimitBytes);
        producer.secretResolver = resolver;
        boolean refused;
        try {
            producer.onStartup(null);
            refused = false;
        } catch (IllegalStateException refusal) {
            refused = refusal.getMessage().startsWith("Refusing to start");
        }
        String identifier = ConfigLogMessages.ERROR.CONFIG_VALIDATION_FAILED.resolveIdentifierString();
        List<String> violationRecords = SheriffDebugCapture.capturedRecords().stream()
                .filter(captured -> Level.SEVERE.equals(captured.getLevel()))
                .map(LogRecord::getMessage)
                .filter(message -> message.startsWith(identifier))
                .toList();
        return new BootRun(refused, violationRecords);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("The boot and the offline check report the same ordered violations")
    void bootAndOfflineReportTheSameViolations(CorpusSet set) throws Exception {
        Path configDir = set.source().materialize(tempDir);
        EnvSecretResolver resolver = new EnvSecretResolver(set.environment()::get);

        OfflineRun offline = offline(configDir, resolver);
        BootRun boot = boot(configDir, resolver, FRAMEWORK_LIMIT_BYTES);

        List<String> offlineAsBootRecords = offline.violations().stream()
                .map(ConfigValidationParityTest::asBootRecord).toList();
        assertAll(set.name(),
                () -> assertEquals(set.violating(), !offlineAsBootRecords.isEmpty(),
                        () -> "unexpected offline verdict: " + offline.lines()),
                () -> assertEquals(offlineAsBootRecords, boot.violationRecords(),
                        "the boot and the offline check must report the same ordered violations"),
                () -> assertEquals(set.violating(), boot.refused(), "the boot refuses exactly a violating set"),
                () -> assertEquals(set.violating() ? 1 : 0, offline.exitCode(),
                        "the offline exit code follows the violation verdict"));
    }

    @Test
    @DisplayName("The framework body limit is the one intended difference, reported offline as not checked")
    void frameworkBodyLimitIsTheOnlyIntendedDifference() throws Exception {
        Files.writeString(tempDir.resolve("gateway.yaml"), """
                version: 1
                anchors:
                  portal:
                    path_prefix: /portal
                    type: bff
                    access: public
                  uploads:
                    path_prefix: /uploads
                    type: proxy
                    access: public
                    security_filter:
                      max_body_bytes: 2048
                """);
        EnvSecretResolver resolver = new EnvSecretResolver(Map.<String, String>of()::get);

        OfflineRun offline = offline(tempDir, resolver);
        BootRun boot = boot(tempDir, resolver, 1024L);

        List<String> expectedBoot = new ArrayList<>(offline.violations().stream()
                .map(ConfigValidationParityTest::asBootRecord).toList());
        expectedBoot.add(asBootRecord(new ConfigError("application.properties",
                ConfigBootPipeline.FRAMEWORK_BODY_LIMIT_KEY,
                "2048 exceeds framework limit 1024; raise quarkus.http.limits.max-body-size to at least 2048")));
        assertAll("body limit difference",
                () -> assertFalse(offline.violations().isEmpty(), "the set must also carry a shared violation"),
                () -> assertEquals(expectedBoot, boot.violationRecords(),
                        "the boot reports the offline violations plus exactly the body-limit entry"),
                () -> assertTrue(offline.lines().stream().anyMatch(line -> line
                                .startsWith("NOT CHECKED: " + ConfigBootPipeline.FRAMEWORK_BODY_LIMIT_KEY + " - ")),
                        () -> "the offline report names the body limit as not checked: " + offline.lines()));
    }
}
