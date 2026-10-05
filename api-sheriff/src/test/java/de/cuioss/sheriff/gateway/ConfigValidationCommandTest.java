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
package de.cuioss.sheriff.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigValidationCommand}: the exact-token flag match, the exit-code contract
 * ({@code 0} valid, {@code 1} violations, {@code 2} usage or unreadable input), the report lines and
 * their order, the not-checked catalogue printed on every run, and the guarantee that neither a
 * secret nor an in-file default literal ever reaches the output.
 */
@EnableGeneratorController
class ConfigValidationCommandTest {

    private static final String VALID_GATEWAY = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            """;

    private static final String PROXY_ENDPOINT = """
            endpoint:
              id: web
              base_url: WEB_BACKEND
              auth:
                require: none
              routes:
                - id: web-proxied
                  match:
                    path_prefix: /web
            """;

    private static final String VALID_TOPOLOGY = "WEB_BACKEND=https://web.internal:8443\n";

    /** The number of not-checked entries of a run: the framework body limit plus the eight families. */
    private static final int NOT_CHECKED_COUNT = 9;

    /**
     * The four out-of-pipeline refusals that depend on {@code gateway.yaml} alone and must therefore
     * be named individually on every run.
     */
    private static final List<String> PURE_GATEWAY_YAML_REFUSALS = List.of(
            "jwks.tls_profile with jwks_verify_hostname: false",
            "tls.mtls.enabled without client_ca",
            "the JWKS source shape",
            "oidc_tls_profile with oidc_verify_hostname: false");

    @TempDir
    Path configDir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(Map<String, String> environment, String... args) {
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            return ConfigValidationCommand.run(args, out, err, new EnvSecretResolver(environment::get));
        }
    }

    private int runOverConfigDir(Map<String, String> environment) {
        return run(environment, ConfigValidationCommand.FLAG, configDir.toString());
    }

    private List<String> outLines() {
        return outBytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> errLines() {
        return errBytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> outLinesStartingWith(String prefix) {
        return outLines().stream().filter(line -> line.startsWith(prefix)).toList();
    }

    private void writeGateway(String yaml) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), yaml);
    }

    private void writeProxyEndpointAndTopology(String topology) throws IOException {
        Files.createDirectories(configDir.resolve("endpoints"));
        Files.writeString(configDir.resolve("endpoints/web.yaml"), PROXY_ENDPOINT);
        Files.writeString(configDir.resolve("topology.properties"), topology);
    }

    private static String lowercaseSentinel() {
        return Generators.letterStrings(24, 32).next().toLowerCase(Locale.ROOT);
    }

    @Nested
    @DisplayName("Flag recognition")
    class FlagRecognition {

        @Test
        @DisplayName("The exact token is recognised wherever it appears on the command line")
        void recognisesTheExactToken() {
            assertAll("exact token",
                    () -> assertTrue(ConfigValidationCommand.isRequested(new String[]{"--validate-config", "dir"})),
                    () -> assertTrue(ConfigValidationCommand.isRequested(new String[]{"other", "--validate-config"})));
        }

        @ParameterizedTest(name = "not a request: [{0}]")
        @ValueSource(strings = {"--validate-config=dir", "--validate", "validate-config", "--VALIDATE-CONFIG",
                "--validate-configs", ""})
        @DisplayName("A prefix, suffix, variant spelling or empty token is not a request")
        void rejectsEverythingButTheExactToken(String token) {
            assertFalse(ConfigValidationCommand.isRequested(new String[]{token, "dir"}));
        }

        @Test
        @DisplayName("An empty command line is not a request")
        void rejectsAnEmptyCommandLine() {
            assertFalse(ConfigValidationCommand.isRequested(new String[0]));
        }
    }

    @Nested
    @DisplayName("Valid configuration")
    class ValidConfiguration {

        @Test
        @DisplayName("A valid set exits 0 with the not-checked catalogue, the policy line and the result line")
        void validSetReportsCleanly() throws Exception {
            writeGateway(VALID_GATEWAY);
            writeProxyEndpointAndTopology(VALID_TOPOLOGY);

            int exitCode = runOverConfigDir(Map.of());

            List<String> notChecked = outLinesStartingWith("NOT CHECKED: ");
            assertAll("valid report",
                    () -> assertEquals(ConfigValidationCommand.EXIT_VALID, exitCode),
                    () -> assertTrue(outLinesStartingWith("INVALID ").isEmpty(), () -> "unexpected " + outLines()),
                    () -> assertTrue(outLinesStartingWith("DEFAULTED ").isEmpty(), () -> "unexpected " + outLines()),
                    () -> assertEquals(NOT_CHECKED_COUNT, notChecked.size(), () -> "not-checked lines " + notChecked),
                    () -> assertTrue(notChecked.getFirst()
                                    .startsWith("NOT CHECKED: " + ConfigBootPipeline.FRAMEWORK_BODY_LIMIT_KEY + " - "),
                            () -> "the pipeline's own not-checked key comes first: " + notChecked),
                    () -> assertEquals(1, outLinesStartingWith("POLICY: ").size(), () -> "policy line " + outLines()),
                    () -> assertEquals("RESULT: violations=0 defaulted=0 not-checked=" + NOT_CHECKED_COUNT,
                            outLines().getLast()),
                    () -> assertTrue(errLines().isEmpty(), () -> "unexpected err " + errLines()));
        }

        @Test
        @DisplayName("Each of the four pure gateway.yaml refusals is named in a not-checked line")
        void namesEachPureGatewayYamlRefusal() throws Exception {
            writeGateway(VALID_GATEWAY);

            runOverConfigDir(Map.of());

            List<String> notChecked = outLinesStartingWith("NOT CHECKED: ");
            for (String refusal : PURE_GATEWAY_YAML_REFUSALS) {
                assertTrue(notChecked.stream().anyMatch(line -> line.contains(refusal)),
                        () -> "'" + refusal + "' is not named in " + notChecked);
            }
        }

        @Test
        @DisplayName("The report sections appear in order: not checked, policy, result")
        void printsTheSectionsInOrder() throws Exception {
            writeGateway(VALID_GATEWAY);

            runOverConfigDir(Map.of());

            List<String> lines = outLines();
            int lastNotChecked = lines.lastIndexOf(outLinesStartingWith("NOT CHECKED: ").getLast());
            int policy = lines.indexOf(outLinesStartingWith("POLICY: ").getFirst());
            assertAll("section order",
                    () -> assertTrue(lines.getFirst().startsWith("NOT CHECKED: "), () -> "first line " + lines),
                    () -> assertEquals(lastNotChecked + 1, policy, () -> "policy follows the catalogue " + lines),
                    () -> assertEquals(policy + 1, lines.size() - 1, () -> "result follows the policy " + lines));
        }
    }

    @Nested
    @DisplayName("Violations")
    class Violations {

        @Test
        @DisplayName("A validator violation exits 1 and renders exactly the pipeline's violation")
        void validatorViolationExitsOne() throws Exception {
            writeGateway("""
                    version: 1
                    anchors:
                      portal:
                        path_prefix: /portal
                        type: bff
                        access: public
                    """);
            List<ConfigError> expected = new ConfigBootPipeline(new EnvSecretResolver(Map.<String, String>of()::get))
                    .run(configDir, null).violations();

            int exitCode = runOverConfigDir(Map.of());

            List<String> invalid = outLinesStartingWith("INVALID ");
            assertAll("validator violation",
                    () -> assertEquals(ConfigValidationCommand.EXIT_INVALID, exitCode),
                    () -> assertFalse(expected.isEmpty(), "the fixture must violate a validator rule"),
                    () -> assertEquals(expected.stream()
                                    .map(v -> "INVALID " + v.file() + " [" + v.pointer() + "]: " + v.message()).toList(),
                            invalid),
                    () -> assertTrue(invalid.stream()
                                    .anyMatch(line -> line.contains("is type 'bff' and must declare access: authenticated")),
                            () -> "the access/auth matrix violation is reported: " + invalid),
                    () -> assertEquals("RESULT: violations=" + expected.size() + " defaulted=0 not-checked="
                            + NOT_CHECKED_COUNT, outLines().getLast()));
        }

        @Test
        @DisplayName("A topology violation is rendered with its file, alias and message")
        void topologyViolationIsRenderedExactly() throws Exception {
            writeGateway(VALID_GATEWAY);
            writeProxyEndpointAndTopology("OTHER=https://other.internal:8443\n");

            int exitCode = runOverConfigDir(Map.of());

            assertAll("topology violation",
                    () -> assertEquals(ConfigValidationCommand.EXIT_INVALID, exitCode),
                    () -> assertEquals(List.of("INVALID topology.properties [WEB_BACKEND]: "
                                    + "Unresolved topology alias 'WEB_BACKEND' referenced by enabled endpoint 'web'"),
                            outLinesStartingWith("INVALID ")));
        }

        @Test
        @DisplayName("An unset bare placeholder exits 1 naming the variable at its pointer")
        void unsetBarePlaceholderIsAViolation() throws Exception {
            writeGateway("""
                    version: 1
                    metadata:
                      config_version: "${SHERIFF_TEST_ABSENT_VERSION}"
                    """);

            int exitCode = runOverConfigDir(Map.of());

            List<String> invalid = outLinesStartingWith("INVALID ");
            assertAll("unset placeholder",
                    () -> assertEquals(ConfigValidationCommand.EXIT_INVALID, exitCode),
                    () -> assertEquals(1, invalid.size(), () -> "violations " + invalid),
                    () -> assertTrue(invalid.getFirst().startsWith("INVALID gateway.yaml [/metadata/config_version]: "),
                            () -> "the violation carries the scalar's pointer: " + invalid),
                    () -> assertTrue(invalid.getFirst().contains("SHERIFF_TEST_ABSENT_VERSION"),
                            () -> "the violation names the unset variable: " + invalid));
        }

        @Test
        @DisplayName("A resolved secret appears in no output line")
        void neverEchoesASecret() throws Exception {
            String secret = lowercaseSentinel();
            writeGateway("""
                    version: 1
                    anchors:
                      portal:
                        path_prefix: /portal
                        type: bff
                        access: public
                    oidc:
                      issuer: https://idp.example.com/realms/test
                      client_id: test-client
                      client_secret: "${SHERIFF_TEST_CLIENT_SECRET}"
                      redirect_uri: https://localhost:8443/auth/callback
                    """);

            runOverConfigDir(Map.of("SHERIFF_TEST_CLIENT_SECRET", secret));

            assertAll("no secret in the output",
                    () -> assertFalse(outLines().isEmpty(), "the run must have produced a report"),
                    () -> assertTrue(outLines().stream().noneMatch(line -> line.contains(secret)),
                            () -> "the secret leaked into " + outLines()),
                    () -> assertTrue(errLines().stream().noneMatch(line -> line.contains(secret)),
                            () -> "the secret leaked into " + errLines()));
        }
    }

    @Nested
    @DisplayName("Defaulted placeholders")
    class DefaultedPlaceholders {

        @Test
        @DisplayName("Each applied in-file default is listed by variable and location, never its value")
        void listsAppliedDefaultsWithoutTheirValues() throws Exception {
            String versionDefault = lowercaseSentinel();
            String hostDefault = lowercaseSentinel();
            writeGateway("""
                    version: 1
                    metadata:
                      config_version: "${SHERIFF_TEST_CONFIG_VERSION:-%s}"
                    """.formatted(versionDefault));
            writeProxyEndpointAndTopology("WEB_BACKEND=${SHERIFF_TEST_WEB_URL:-https://%s.internal:8443}\n"
                    .formatted(hostDefault));

            int exitCode = runOverConfigDir(Map.of());

            assertAll("defaulted report",
                    () -> assertEquals(ConfigValidationCommand.EXIT_VALID, exitCode,
                            "an applied default never changes the exit code"),
                    () -> assertEquals(List.of(
                                    "DEFAULTED gateway.yaml [/metadata/config_version]: ${SHERIFF_TEST_CONFIG_VERSION} unset,"
                                            + " in-file default applied",
                                    "DEFAULTED topology.properties [WEB_BACKEND]: ${SHERIFF_TEST_WEB_URL} unset,"
                                            + " in-file default applied"),
                            outLinesStartingWith("DEFAULTED ")),
                    () -> assertEquals("RESULT: violations=0 defaulted=2 not-checked=" + NOT_CHECKED_COUNT,
                            outLines().getLast()),
                    () -> assertTrue(outLines().stream()
                                    .noneMatch(line -> line.contains(versionDefault) || line.contains(hostDefault)),
                            () -> "a default literal leaked into " + outLines()));
        }

        @Test
        @DisplayName("A defaulted placeholder whose variable is set is not listed")
        void setVariableIsNotListed() throws Exception {
            writeGateway("""
                    version: 1
                    metadata:
                      config_version: "${SHERIFF_TEST_CONFIG_VERSION:-fallback}"
                    """);
            writeProxyEndpointAndTopology("WEB_BACKEND=${SHERIFF_TEST_WEB_URL:-https://fallback.internal:8443}\n");

            int exitCode = runOverConfigDir(Map.of("SHERIFF_TEST_CONFIG_VERSION", "from-environment",
                    "SHERIFF_TEST_WEB_URL", "https://web.internal:8443"));

            assertAll("nothing defaulted",
                    () -> assertEquals(ConfigValidationCommand.EXIT_VALID, exitCode),
                    () -> assertTrue(outLinesStartingWith("DEFAULTED ").isEmpty(), () -> "unexpected " + outLines()),
                    () -> assertEquals("RESULT: violations=0 defaulted=0 not-checked=" + NOT_CHECKED_COUNT,
                            outLines().getLast()));
        }
    }

    @Nested
    @DisplayName("Usage errors and unreadable input")
    class UsageErrors {

        private void assertUsageError(int exitCode, String expectedFragment) {
            assertAll("usage error",
                    () -> assertEquals(ConfigValidationCommand.EXIT_USAGE, exitCode),
                    () -> assertTrue(outLines().isEmpty(), () -> "nothing is written to out: " + outLines()),
                    () -> assertEquals(1, errLines().size(), () -> "exactly one err line: " + errLines()),
                    () -> assertTrue(errLines().getFirst().contains(expectedFragment),
                            () -> "err names the problem: " + errLines()));
        }

        @Test
        @DisplayName("A missing directory argument exits 2")
        void missingDirectoryArgument() {
            int exitCode = run(Map.of(), ConfigValidationCommand.FLAG);

            assertUsageError(exitCode, "missing configuration directory");
        }

        @Test
        @DisplayName("A command line without the flag exits 2")
        void missingFlag() {
            int exitCode = run(Map.of(), configDir.toString());

            assertUsageError(exitCode, "missing configuration directory");
        }

        @Test
        @DisplayName("An absent directory exits 2")
        void absentDirectory() {
            Path absent = configDir.resolve("absent");

            int exitCode = run(Map.of(), ConfigValidationCommand.FLAG, absent.toString());

            assertUsageError(exitCode, "does not exist");
        }

        @Test
        @DisplayName("A path naming a regular file exits 2")
        void fileInsteadOfDirectory() throws Exception {
            Path file = Files.writeString(configDir.resolve("gateway.yaml"), VALID_GATEWAY);

            int exitCode = run(Map.of(), ConfigValidationCommand.FLAG, file.toString());

            assertUsageError(exitCode, "is not a directory");
        }

        @Test
        @DisplayName("A directory without gateway.yaml exits 2")
        void directoryWithoutGatewayYaml() throws Exception {
            writeProxyEndpointAndTopology(VALID_TOPOLOGY);

            int exitCode = runOverConfigDir(Map.of());

            assertUsageError(exitCode, "contains no gateway.yaml");
        }

        @Test
        @DisplayName("A gateway.yaml that is a directory exits 2")
        void gatewayYamlIsADirectory() throws Exception {
            Files.createDirectories(configDir.resolve("gateway.yaml"));

            int exitCode = runOverConfigDir(Map.of());

            assertUsageError(exitCode, "contains no gateway.yaml");
        }

        @Test
        @DisplayName("An argument that is not a valid path exits 2")
        void invalidPath() {
            String pathWithNul = "bad" + (char) 0 + "path";

            int exitCode = run(Map.of(), ConfigValidationCommand.FLAG, pathWithNul);

            assertUsageError(exitCode, "is not a valid path");
        }
    }
}
