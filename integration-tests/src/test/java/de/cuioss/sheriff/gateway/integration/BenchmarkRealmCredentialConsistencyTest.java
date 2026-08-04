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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the k6 bearer benchmark and the Keycloak realm import
 * that provisions its account still agree on the same throwaway credentials.
 * <p>
 * The two files define the client id, client secret, username and password independently:
 * {@code bearer_proxied.js} carries them as operative literals (the compose harness passes no
 * {@code KEYCLOAK_*} through to the k6 service, so every {@code ||} fallback is taken), and
 * {@code benchmark-realm.json} is what actually creates the client and the user. Change one side and
 * the benchmark mints no token — every request comes back {@code 401}, and the run reads as a fast
 * result rather than a broken one until the {@code checks} threshold fails it a long way from the
 * cause.
 * <p>
 * The literals are deliberately not generated from the realm at build time: that would couple the
 * benchmarks module to an integration-tests Docker resource for an ephemeral credential pair that
 * never leaves a local bridge network. This guard is the alternative that was taken instead — the
 * mirror stays hand-written, but it is no longer unchecked.
 * <p>
 * <strong>This test must never pass quietly.</strong> A consistency guard that cannot find its inputs
 * and reports green is worse than no guard, because it reports a guarantee it never evaluated. Every
 * lookup below therefore fails with the path or pattern it could not resolve, and the extracted
 * values are asserted present and non-empty before any comparison is made — a skip or a silent pass
 * is not an available outcome. That holds on BOTH sides of every comparison: the script side through
 * {@link #scriptLiteral(String)}, and the realm side through {@link #requiredRealmString(Map, String,
 * String)}, which refuses an absent field rather than letting {@code String.valueOf} turn it into the
 * ordinary-looking literal {@code "null"}.
 * <p>
 * It reads two committed files only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("k6 benchmark credentials vs benchmark-realm.json — deployment wiring")
class BenchmarkRealmCredentialConsistencyTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path REALM_IMPORT =
            MODULE.resolve("src/main/docker/keycloak/benchmark-realm.json");
    private static final Path K6_SCRIPT =
            MODULE.resolve("../benchmarks/src/main/resources/k6-scripts/bearer_proxied.js").normalize();

    private static final String CLIENT_ID_CONST = "KEYCLOAK_CLIENT_ID";
    private static final String CLIENT_SECRET_CONST = "KEYCLOAK_CLIENT_SECRET";
    private static final String USERNAME_CONST = "KEYCLOAK_USERNAME";
    private static final String PASSWORD_CONST = "KEYCLOAK_PASSWORD";

    @Test
    @DisplayName("the k6 script's client id and secret match the client the realm provisions")
    void theScriptClientMatchesTheProvisionedClient() throws Exception {
        String scriptClientId = scriptLiteral(CLIENT_ID_CONST);
        String scriptClientSecret = scriptLiteral(CLIENT_SECRET_CONST);
        Map<String, Object> realm = realmImport();

        Map<String, Object> client = entryWith(realm, "clients", "clientId", scriptClientId);

        assertNotNull(client,
                () -> "the realm import declares no client with clientId '" + scriptClientId
                        + "' — the benchmark would authenticate against a client that does not exist "
                        + "and every request would come back 401. Realm: " + REALM_IMPORT);
        assertEquals(scriptClientSecret, requiredRealmString(client, "secret", "client '" + scriptClientId + "'"),
                () -> "the client secret in " + K6_SCRIPT.getFileName() + " no longer matches the "
                        + "secret the realm import provisions for '" + scriptClientId
                        + "'; the token request would be refused and the whole run would 401");
    }

    @Test
    @DisplayName("the k6 script's username and password match the user the realm provisions")
    void theScriptUserMatchesTheProvisionedUser() throws Exception {
        String scriptUsername = scriptLiteral(USERNAME_CONST);
        String scriptPassword = scriptLiteral(PASSWORD_CONST);
        Map<String, Object> realm = realmImport();

        Map<String, Object> user = entryWith(realm, "users", "username", scriptUsername);

        assertNotNull(user,
                () -> "the realm import declares no user '" + scriptUsername + "' — the password "
                        + "grant would fail and every benchmark request would come back 401. Realm: "
                        + REALM_IMPORT);
        assertEquals(scriptPassword, passwordOf(user),
                () -> "the password in " + K6_SCRIPT.getFileName() + " no longer matches the password "
                        + "the realm import provisions for '" + scriptUsername + "'; the password "
                        + "grant would be refused and the whole run would 401");
    }

    @Test
    @DisplayName("the k6 token URL names the realm the import actually creates")
    void theScriptTokenUrlNamesTheImportedRealm() throws Exception {
        String tokenUrl = scriptLiteral("KEYCLOAK_TOKEN_URL");
        Matcher realmInUrl = Pattern.compile("/realms/([^/]+)/").matcher(tokenUrl);
        assertTrue(realmInUrl.find(),
                () -> "the token URL literal carries no /realms/<name>/ segment, so the realm it "
                        + "targets cannot be checked against the import: " + tokenUrl);

        assertEquals(requiredRealmString(realmImport(), "realm", "the realm import"), realmInUrl.group(1),
                "the k6 token URL targets a different realm than the import creates — the client and "
                        + "user assertions would then compare credentials the benchmark never uses");
    }

    @Test
    @DisplayName("both source files resolve and every mirrored value is extracted non-empty")
    void bothSourcesResolveAndEveryValueIsExtracted() throws Exception {
        // This is what stops the three tests above from passing vacuously. If a file moves or a
        // constant is renamed, the extraction below fails by NAME rather than leaving a comparison
        // that quietly has nothing to compare.
        assertAll(
                () -> assertTrue(Files.isRegularFile(REALM_IMPORT),
                        () -> "the realm import is not where this guard looks for it: " + REALM_IMPORT),
                () -> assertTrue(Files.isRegularFile(K6_SCRIPT),
                        () -> "the k6 benchmark script is not where this guard looks for it: " + K6_SCRIPT));

        List<String> mirrored = List.of(
                scriptLiteral(CLIENT_ID_CONST),
                scriptLiteral(CLIENT_SECRET_CONST),
                scriptLiteral(USERNAME_CONST),
                scriptLiteral(PASSWORD_CONST));

        assertAll(
                () -> assertEquals(4, mirrored.size(), "all four mirrored credentials must be extracted"),
                () -> assertTrue(mirrored.stream().noneMatch(String::isBlank),
                        () -> "every extracted credential must be non-empty, got: " + mirrored.size()
                                + " values with at least one blank"),
                () -> assertFalse(realmImport().isEmpty(),
                        () -> "the realm import parsed to an empty document: " + REALM_IMPORT));
    }

    /**
     * Reads the {@code || '<literal>'} fallback of a top-level {@code KEYCLOAK_*} constant in the k6
     * script — the value that is operative under the compose harness, since nothing sets the
     * environment variable there.
     *
     * @param constantName the JavaScript constant to read
     * @return the literal
     * @throws IOException when the script cannot be read
     */
    private static String scriptLiteral(String constantName) throws IOException {
        assertTrue(Files.isRegularFile(K6_SCRIPT),
                () -> "the k6 benchmark script is not where this guard looks for it: " + K6_SCRIPT);
        String source = Files.readString(K6_SCRIPT);
        Pattern pattern = Pattern.compile(
                "const\\s+" + Pattern.quote(constantName) + "\\s*=[^;]*?\\|\\|\\s*'([^']*)'",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(),
                () -> "no `const " + constantName + " = … || '<literal>'` declaration found in "
                        + K6_SCRIPT + " — the constant was renamed or its fallback removed, so this "
                        + "guard can no longer see the value the benchmark actually uses");
        return matcher.group(1);
    }

    /**
     * @return the Keycloak realm import, parsed as a document. JSON is a subset of YAML, so the
     *         parser already on this module's test path reads it without a further dependency.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> realmImport() throws IOException {
        assertTrue(Files.isRegularFile(REALM_IMPORT),
                () -> "the realm import is not where this guard looks for it: " + REALM_IMPORT);
        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(REALM_IMPORT)) {
            document = new Yaml().loadAs(in, Map.class);
        }
        assertNotNull(document, () -> "the realm import must parse into a mapping: " + REALM_IMPORT);
        return document;
    }

    /**
     * @param realm     the parsed realm import
     * @param arrayName the top-level array to search ({@code clients} / {@code users})
     * @param keyField  the field identifying an entry
     * @param keyValue  the identifying value taken from the k6 script
     * @return the matching entry, or {@code null} when the realm declares none
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryWith(Map<String, Object> realm, String arrayName,
            String keyField, String keyValue) {
        Object entries = realm.get(arrayName);
        assertInstanceOf(List.class, entries, () -> "the realm import declares no '" + arrayName + "' array, so the benchmark's "
                + keyField + " cannot be checked against it: " + REALM_IMPORT);
        return ((List<?>) entries).stream()
                .filter(Map.class::isInstance)
                .map(entry -> (Map<String, Object>) entry)
                // Compared against the raw value, never String.valueOf(...): an entry that declares
                // no such field would otherwise be matched on the four-character literal "null",
                // which is a real string a mirrored k6 literal could be spelled as.
                .filter(entry -> keyValue.equals(entry.get(keyField)))
                .findFirst()
                .orElse(null);
    }

    /**
     * Reads a realm-import field that must be present as a non-blank string, failing by NAME when it
     * is not.
     * <p>
     * {@code String.valueOf(null)} yields the four-character literal {@code "null"}, so a field the
     * import never declares would otherwise reach a comparison looking like an ordinary value — and
     * would pass outright whenever the mirrored k6 literal happened to be spelled the same way. This
     * guard's stated contract is that every compared value is proven present first, so the absence is
     * asserted here rather than silently converted into a string.
     *
     * @param entry     the parsed realm entry to read
     * @param field     the field the comparison needs
     * @param described what the entry is, for the failure message
     * @return the field's non-blank string value
     */
    private static String requiredRealmString(Map<String, Object> entry, String field, String described) {
        String value = assertInstanceOf(String.class, entry.get(field),
                () -> "the realm import declares no string '" + field + "' for " + described
                        + ", so the benchmark's mirrored value has nothing to be checked against: "
                        + REALM_IMPORT);
        assertFalse(value.isBlank(),
                () -> "the realm import's '" + field + "' for " + described + " is blank, so the "
                        + "mirrored benchmark value cannot be meaningfully compared: " + REALM_IMPORT);
        return value;
    }

    /**
     * @param user a realm user entry
     * @return the user's declared password credential value
     */
    @SuppressWarnings("unchecked")
    private static String passwordOf(Map<String, Object> user) {
        Object credentials = user.get("credentials");
        assertInstanceOf(List.class, credentials, () -> "the realm user declares no credentials array, so its password cannot be "
                + "checked against the benchmark: " + REALM_IMPORT);
        return ((List<?>) credentials).stream()
                .filter(Map.class::isInstance)
                .map(entry -> (Map<String, Object>) entry)
                .filter(entry -> "password".equals(entry.get("type")))
                .map(entry -> requiredRealmString(entry, "value", "the user's password credential"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the realm user declares no credential of type 'password', so the benchmark's "
                                + "password grant has nothing to match: " + REALM_IMPORT));
    }
}
