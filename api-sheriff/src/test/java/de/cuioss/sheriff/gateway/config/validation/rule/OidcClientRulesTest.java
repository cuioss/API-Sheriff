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
package de.cuioss.sheriff.gateway.config.validation.rule;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the OIDC client rules the {@link ConfigValidator} runs through {@link OidcClientRules}: a
 * declared {@code oidc.login.default_return_url} must be same-origin with {@code redirect_uri}, and
 * {@code oidc.client_secret} and {@code oidc.client_authentication.key_file} select at most one client
 * authentication, with a declared secret that resolves blank refused as well; in the key-based mode
 * {@code oidc.client_authentication.key_file} and {@code oidc.sender_constraint.key_file} must name two
 * files. The {@code oidc.back_channel_origin} rule is tested in
 * {@code OidcClientRulesBackChannelOriginTest}.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — OIDC client rules")
class OidcClientRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static GatewayConfig.GatewayConfigBuilder validGateway() {
        return GatewayConfig.builder().version(1);
    }

    private static ResolvedTopology topologyWith(String... aliases) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        for (String alias : aliases) {
            map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        }
        return new ResolvedTopology(map);
    }

    private static void assertHasError(List<ConfigError> errors, String pointerContains, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(e -> e.pointer().contains(pointerContains) && e.message().contains(messageContains)),
                () -> "expected an error whose pointer contains '" + pointerContains + "' and message contains '"
                        + messageContains + "', but got: " + errors);
    }

    @Nested
    @DisplayName("oidc.login.default_return_url must be same-origin with redirect_uri")
    class DefaultReturnUrl {

        private static final String REDIRECT_URI = "https://gateway.example.com/callback";
        private static final String POINTER = "/oidc/login/default_return_url";

        private List<ConfigError> validateDefault(@Nullable String redirectUri, String defaultReturnUrl) {
            OidcConfig oidc = OidcConfig.builder().redirectUri(redirectUri)
                    .login(new OidcConfig.Login(null, defaultReturnUrl)).build();
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith());
        }

        private boolean refused(List<ConfigError> errors) {
            return errors.stream().anyMatch(error -> POINTER.equals(error.pointer()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "/app/home?tab=a&b", "https://gateway.example.com/app",
                "https://GATEWAY.example.com:443/app"})
        @DisplayName("Should accept a same-origin relative path or absolute URL")
        void shouldAcceptSameOrigin(String value) {
            List<ConfigError> errors = validateDefault(REDIRECT_URI, value);

            assertFalse(refused(errors), () -> value + " is same-origin, got: " + errors);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://evil.example/app", "//evil.example/app", "/\\evil.example",
                "http://gateway.example.com/app", "https://gateway.example.com:8443/app", ""})
        @DisplayName("Should refuse a cross-origin, scheme-relative, backslash or blank value")
        void shouldRefuseCrossOrigin(String value) {
            List<ConfigError> errors = validateDefault(REDIRECT_URI, value);

            assertHasError(errors, POINTER, "is not same-origin with redirect_uri");
        }

        @Test
        @DisplayName("Should accept a relative path but refuse an absolute URL when no redirect_uri is configured")
        void shouldJudgeAgainstAbsentRedirectUri() {
            assertAll(
                    () -> assertFalse(refused(validateDefault(null, "/home"))),
                    () -> assertTrue(refused(validateDefault(null, "https://gateway.example.com/home"))));
        }

        @Test
        @DisplayName("Should not refuse an omitted default_return_url")
        void shouldAcceptOmittedValue() {
            OidcConfig oidc = OidcConfig.builder().redirectUri(REDIRECT_URI)
                    .login(new OidcConfig.Login("/auth/login", null)).build();

            List<ConfigError> errors = validator.validate(validGateway().oidc(oidc).build(), List.of(),
                    topologyWith());

            assertFalse(refused(errors));
        }
    }

    /**
     * {@code oidc.client_secret} and {@code oidc.client_authentication.key_file} select different
     * client authentications. The three documents that name at most one of them are admitted; the
     * document naming both, and a declared secret that resolves blank, are refused at
     * {@code /oidc/client_secret} with a fixed text that carries no configured value.
     */
    @Nested
    @DisplayName("oidc.client_secret and oidc.client_authentication.key_file select one client authentication")
    class ClientAuthenticationMode {

        private static final String POINTER = "/oidc/client_secret";
        private static final String SECRET_KEY = "oidc.client_secret";
        private static final String KEY_FILE_KEY = "oidc.client_authentication.key_file";
        /** Distinctive values, so an echo of either into a refusal is detectable. */
        private static final String SECRET = "configured-secret-value-7f3a";
        private static final String KEY_FILE = "/etc/sheriff/keys/configured-key-file-91c2.pem";

        private List<ConfigError> clientAuthenticationErrors(@Nullable String clientSecret,
                OidcConfig.@Nullable ClientAuthenticationSettings clientAuthentication) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer("https://idp.example.com/realms/main")
                    .clientId("api-sheriff")
                    .clientSecret(clientSecret)
                    .clientAuthentication(clientAuthentication)
                    .build();
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        private static OidcConfig.ClientAuthenticationSettings keyFile() {
            return OidcConfig.ClientAuthenticationSettings.builder().keyFile(KEY_FILE).build();
        }

        /** A declared {@code client_authentication} block that names no key file. */
        private static OidcConfig.ClientAuthenticationSettings emptyBlock() {
            return OidcConfig.ClientAuthenticationSettings.builder().build();
        }

        @Test
        @DisplayName("Should admit a document declaring only the client secret")
        void shouldAdmitSecretOnly() {
            assertEquals(List.of(), clientAuthenticationErrors(SECRET, null),
                    "client-secret authentication is a supported mode");
        }

        @Test
        @DisplayName("Should admit a document declaring only the key file")
        void shouldAdmitKeyFileOnly() {
            assertEquals(List.of(), clientAuthenticationErrors(null, keyFile()),
                    "private_key_jwt with a provided key is the default mode");
        }

        @Test
        @DisplayName("Should admit a document declaring neither, with or without an empty client_authentication block")
        void shouldAdmitNeither() {
            assertAll("private_key_jwt with a generated key",
                    () -> assertEquals(List.of(), clientAuthenticationErrors(null, null),
                            "no secret and no block selects a generated key"),
                    () -> assertEquals(List.of(), clientAuthenticationErrors(null, emptyBlock()),
                            "a block that names no key file selects a generated key too"));
        }

        @Test
        @DisplayName("Should admit a client secret beside a client_authentication block that names no key file")
        void shouldAdmitSecretBesideAnEmptyBlock() {
            assertEquals(List.of(), clientAuthenticationErrors(SECRET, emptyBlock()),
                    "the refusal is about two credentials, and an empty block names none");
        }

        @Test
        @DisplayName("Should refuse both keys together, naming both and echoing neither value")
        void shouldRefuseSecretTogetherWithKeyFile() {
            List<ConfigError> errors = clientAuthenticationErrors(SECRET, keyFile());

            assertEquals(1, errors.size(), () -> "exactly one refusal at " + POINTER + ", got: " + errors);
            ConfigError refusal = errors.getFirst();
            assertAll("the refusal names both keys and neither value",
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(SECRET_KEY),
                            () -> "the refusal must name " + SECRET_KEY + ": " + refusal.message()),
                    () -> assertTrue(refusal.message().contains(KEY_FILE_KEY),
                            () -> "the refusal must name " + KEY_FILE_KEY + ": " + refusal.message()),
                    () -> assertTrue(refusal.message().contains("at most one of them may be configured"),
                            () -> "declaring neither key is admitted (shouldAdmitNeither), so the refusal "
                                    + "must not demand exactly one: " + refusal.message()),
                    () -> assertFalse(refusal.message().contains(SECRET),
                            "the secret value must never reach the boot log"),
                    () -> assertFalse(refusal.message().contains(KEY_FILE),
                            "the configured path must not be echoed either"));
        }

        @ParameterizedTest(name = "a declared secret of ''{0}''")
        @ValueSource(strings = {"", " ", "\t "})
        @DisplayName("Should refuse a declared client secret that resolves to a blank value")
        void shouldRefuseBlankSecret(String blank) {
            List<ConfigError> errors = clientAuthenticationErrors(blank, null);

            assertEquals(1, errors.size(), () -> "exactly one refusal at " + POINTER + ", got: " + errors);
            assertAll("the refusal names the secret key and the defect",
                    () -> assertTrue(errors.getFirst().message().contains(SECRET_KEY),
                            () -> "the refusal must name " + SECRET_KEY + ": " + errors.getFirst().message()),
                    () -> assertTrue(errors.getFirst().message().contains("blank"),
                            () -> "the refusal must name the defect: " + errors.getFirst().message()));
        }

        @Test
        @DisplayName("Should collect the blank-secret and the both-keys refusal together rather than stopping at the first")
        void shouldCollectBothRefusalsTogether() {
            List<ConfigError> errors = clientAuthenticationErrors("", keyFile());

            assertAll("both violations of one document are reported in one pass",
                    () -> assertEquals(2, errors.size(), () -> "two refusals at " + POINTER + ", got: " + errors),
                    () -> assertTrue(errors.stream().anyMatch(error -> error.message().contains("blank")),
                            () -> "the blank-secret refusal is reported, got: " + errors),
                    () -> assertTrue(errors.stream().anyMatch(error -> error.message().contains(KEY_FILE_KEY)),
                            () -> "the both-keys refusal is reported, got: " + errors));
        }

        @Test
        @DisplayName("Should hold the rule without any session route — it is a property of the document")
        void shouldRefuseOnABearerOnlyGateway() {
            OidcConfig oidc = OidcConfig.builder().clientSecret(SECRET).clientAuthentication(keyFile()).build();

            List<ConfigError> errors = validator.validate(validGateway().oidc(oidc).build(), List.of(),
                    topologyWith());

            assertHasError(errors, POINTER, KEY_FILE_KEY);
        }
    }

    /**
     * FAPI 2.0 section 6.8 requires a key to serve one purpose, so in the key-based mode the
     * client-authentication key file and the sender-constraint key file must be two files. A document
     * naming one file for both — compared after path normalization — is refused at
     * {@code /oidc/sender_constraint/key_file} with a fixed text that names both keys and neither path.
     */
    @Nested
    @DisplayName("oidc.client_authentication.key_file and oidc.sender_constraint.key_file must name two files")
    class SigningKeyFilesDistinct {

        private static final String POINTER = "/oidc/sender_constraint/key_file";
        private static final String CLIENT_KEY_FILE_KEY = "oidc.client_authentication.key_file";
        private static final String DPOP_KEY_FILE_KEY = "oidc.sender_constraint.key_file";
        /** Distinctive paths, so an echo of either into a refusal is detectable. */
        private static final String SHARED_KEY_FILE = "/etc/sheriff/keys/shared-signing-key-5d1e.pem";
        private static final String CLIENT_KEY_FILE = "/etc/sheriff/keys/client-key-0b7c.pem";
        private static final String DPOP_KEY_FILE = "/etc/sheriff/keys/dpop-key-a24f.pem";

        private List<ConfigError> signingKeyErrors(@Nullable String clientSecret, @Nullable String clientKeyFile,
                @Nullable String dpopKeyFile) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer("https://idp.example.com/realms/main")
                    .clientId("api-sheriff")
                    .clientSecret(clientSecret)
                    .clientAuthentication(clientKeyFile == null ? null
                            : OidcConfig.ClientAuthenticationSettings.builder().keyFile(clientKeyFile).build())
                    .senderConstraint(dpopKeyFile == null ? null
                            : new OidcConfig.SenderConstraintSettings(dpopKeyFile))
                    .build();
            return validator.validate(validGateway().oidc(oidc).build(), List.of(), topologyWith()).stream()
                    .filter(error -> POINTER.equals(error.pointer()))
                    .toList();
        }

        @Test
        @DisplayName("Should refuse one file named for both keys, naming both keys and echoing neither path")
        void shouldRefuseTheSameFileForBothKeys() {
            List<ConfigError> errors = signingKeyErrors(null, SHARED_KEY_FILE, SHARED_KEY_FILE);

            assertEquals(1, errors.size(), () -> "exactly one refusal at " + POINTER + ", got: " + errors);
            ConfigError refusal = errors.getFirst();
            assertAll("the refusal names both keys and the requirement, and no path",
                    () -> assertEquals("gateway.yaml", refusal.file()),
                    () -> assertTrue(refusal.message().contains(CLIENT_KEY_FILE_KEY),
                            () -> "the refusal must name " + CLIENT_KEY_FILE_KEY + ": " + refusal.message()),
                    () -> assertTrue(refusal.message().contains(DPOP_KEY_FILE_KEY),
                            () -> "the refusal must name " + DPOP_KEY_FILE_KEY + ": " + refusal.message()),
                    () -> assertTrue(refusal.message().contains("FAPI 2.0 section 6.8"),
                            () -> "the refusal must name the requirement it enforces: " + refusal.message()),
                    () -> assertFalse(refusal.message().contains(SHARED_KEY_FILE),
                            "the configured path must not be echoed"),
                    () -> assertFalse(refusal.message().contains("shared-signing-key"),
                            "no fragment of the configured path may be echoed either"));
        }

        @ParameterizedTest(name = "''{0}'' and ''{1}''")
        @CsvSource({
                "keys/../shared-signing-key.pem, shared-signing-key.pem",
                "./keys/dpop.pem, keys/dpop.pem",
                "/etc/sheriff/keys/./a.pem, /etc/sheriff/./keys/a.pem",
                "/etc/sheriff/keys/x/../a.pem, /etc/sheriff/keys/a.pem"
        })
        @DisplayName("Should refuse two spellings of one file, compared after normalization")
        void shouldRefuseTwoSpellingsOfOneFile(String clientKeyFile, String dpopKeyFile) {
            List<ConfigError> errors = signingKeyErrors(null, clientKeyFile, dpopKeyFile);

            assertAll("one refusal echoing neither spelling",
                    () -> assertEquals(1, errors.size(),
                            () -> "exactly one refusal at " + POINTER + ", got: " + errors),
                    () -> assertFalse(errors.getFirst().message().contains(clientKeyFile),
                            "the client-authentication path must not be echoed"),
                    () -> assertFalse(errors.getFirst().message().contains(dpopKeyFile),
                            "the sender-constraint path must not be echoed"));
        }

        @Test
        @DisplayName("Should admit two different files")
        void shouldAdmitTwoDifferentFiles() {
            assertEquals(List.of(), signingKeyErrors(null, CLIENT_KEY_FILE, DPOP_KEY_FILE),
                    "two key files are the configuration the rule asks for");
        }

        @Test
        @DisplayName("Should admit a document declaring only one of the two key files, or neither")
        void shouldAdmitOneOrNoKeyFile() {
            assertAll("a generated key is fresh per boot and never equals a provided one",
                    () -> assertEquals(List.of(), signingKeyErrors(null, CLIENT_KEY_FILE, null),
                            "only the client-authentication key file"),
                    () -> assertEquals(List.of(), signingKeyErrors(null, null, DPOP_KEY_FILE),
                            "only the sender-constraint key file"),
                    () -> assertEquals(List.of(), signingKeyErrors(null, null, null), "neither key file"));
        }

        @Test
        @DisplayName("Should not judge the key files in client-secret mode, where no client assertion is signed")
        void shouldNotJudgeClientSecretMode() {
            List<ConfigError> errors = signingKeyErrors("configured-secret-value-7f3a", SHARED_KEY_FILE,
                    SHARED_KEY_FILE);

            assertEquals(List.of(), errors, "the secret selects client_secret_basic, so the client-authentication"
                    + " key file is never read; the both-credentials rule refuses that document on its own pointer");
        }
    }
}
