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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpServerOptions;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ServerTlsDeclarationGate}: the two refusal cases, the explicit plain-HTTP opt-in
 * that stands both down, and the hook the refusal actually travels through.
 * <p>
 * <strong>Every case is decided against a real assembled {@link Config}.</strong> The gate reads
 * declared keys and enumerates TLS-registry bucket names, so a hand-assembled configuration is what
 * makes each case explicit: nothing here is inherited from {@code application.properties}, and a
 * case that passes does so on the keys it names.
 * <p>
 * <strong>The refusals are asserted on their content, not merely on their type.</strong> The whole
 * reason this gate acts before the framework's own guard is that the framework's message —
 * {@code "Cannot set quarkus.http.insecure-requests without enabling SSL."} — names a value the
 * operator did not set and no remedy at all. A test asserting only "something was thrown" would pass
 * just as happily against that message, so each case pins the offending key AND both ways out.
 */
@DisplayName("Server-TLS declaration gate")
class ServerTlsDeclarationGateTest {

    /** The shipped exposure default: present in every case, so no case is decided by its absence. */
    private static final String SHIPPED_DEFAULT = "redirect";

    private static final int APPLICATION_PROPERTIES_ORDINAL = 250;
    private static final int ENVIRONMENT_ORDINAL = 300;

    private static final String NAMED_BUCKET = "named-server-tls";
    private static final String NAMED_BUCKET_MATERIAL =
            "quarkus.tls." + NAMED_BUCKET + ".key-store.p12.path";
    private static final String DEFAULT_BUCKET_MATERIAL = "quarkus.tls.key-store.p12.path";

    private static final String CERTIFICATE_PATH = "/etc/certs/localhost.crt";
    private static final String KEYSTORE_PATH = "/etc/certs/keystore.p12";

    @Test
    @DisplayName("The refusal travels through the customizer hook the recorder actually calls")
    void refusalTravelsThroughTheCustomizerHook() {
        ServerTlsDeclarationGate gate = gateOver(Map.of(), SHIPPED_DEFAULT);

        assertThrows(IllegalStateException.class,
                () -> gate.customizeHttpServer(new HttpServerOptions()),
                "the gate is only worth anything if it fires from the hook "
                        + "VertxHttpRecorder.initializeMainHttpServer invokes before its own "
                        + "getKeyCertOptions() guard. Asserting only the direct method would leave "
                        + "the wiring — customizeHttpServer rather than customizeHttpsServer, which "
                        + "does not exist in the very state under test — unproven");
    }

    @Nested
    @DisplayName("Refusal case (a) — nothing declares key material and plain HTTP was never asked for")
    class NoKeyMaterialDeclared {

        @Test
        @DisplayName("Refuses, naming the missing material and BOTH remedies")
        void refusesWithBothRemedies() {
            String message = messageOfRefusal(Map.of());

            assertAll("the message replaces the framework's own, so it must carry what that one lacks",
                    () -> assertTrue(message.contains("No TLS key material is declared"),
                            "it must name what is actually missing, rather than naming "
                                    + "insecure-requests as the framework's message does: " + message),
                    () -> assertTrue(message.contains(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES)
                            && message.contains(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEY_STORE_FILE)
                            && message.contains(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_CREDENTIALS_PROVIDER),
                            "remedy one is 'declare a certificate', which is worthless unless the "
                                    + "supported spellings are named: " + message),
                    () -> assertTrue(message.contains(DeclaredKeyMaterialKeys.INSECURE_REQUESTS + "="
                                    + DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED),
                            "remedy two is the explicit plain-HTTP opt-in, spelled out as the "
                                    + "operator would type it: " + message),
                    () -> assertTrue(message.contains("cannot distinguish"),
                            "the message must say WHY plain HTTP is not inferred — absence cannot "
                                    + "express intent — so the refusal reads as a design decision "
                                    + "rather than as arbitrary strictness: " + message));
        }

        @ParameterizedTest
        @DisplayName("Any single certificate spelling satisfies it — all four are honoured")
        @ValueSource(strings = {
                "quarkus.http.ssl.certificate.files",
                "quarkus.http.ssl.certificate.key-files",
                "quarkus.http.ssl.certificate.key-store-file",
                "quarkus.http.ssl.certificate.credentials-provider"
        })
        void anySingleCertificateSpellingSatisfiesIt(String declaredKey) {
            assertDoesNotThrow(() -> validate(Map.of(declaredKey, CERTIFICATE_PATH)),
                    declaredKey + " is a spelling TlsUtils.computeKeyStoreOptions accepts, so a "
                            + "deployment using it terminates TLS and must not be refused. Reading "
                            + "only .files is the defect that made a keystore deployment look "
                            + "certificate-less");
        }

        @Test
        @DisplayName("A default quarkus.tls.key-store.* bucket satisfies it")
        void defaultBucketSatisfiesIt() {
            assertDoesNotThrow(() -> validate(Map.of(DEFAULT_BUCKET_MATERIAL, KEYSTORE_PATH)),
                    "the recorder falls back to the DEFAULT registry bucket whenever it carries key "
                            + "material, so a deployment supplying it has something to terminate with");
        }

        @Test
        @DisplayName("A key cleared to blank is no declaration, so the refusal still fires")
        void blankValueIsNoDeclaration() {
            String message = messageOfRefusal(Map.of(
                    DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES, "",
                    DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEY_FILES, ""));

            assertTrue(message.contains("No TLS key material is declared"),
                    "a compose file's bare QUARKUS_HTTP_SSL_CERTIFICATE_FILES= supplies no material, "
                            + "so reading it as a declaration would let a certificate-less "
                            + "deployment through the gate: " + message);
        }

        @ParameterizedTest
        @DisplayName("A non-material leaf of the default bucket is not key material, defaults included")
        @ValueSource(strings = {
                "quarkus.tls.key-store.sni",
                "quarkus.tls.key-store.credentials-provider.password-key",
                "quarkus.tls.key-store.credentials-provider.alias-password-key"
        })
        void nonMaterialLeavesOfTheDefaultBucketAreNotKeyMaterial(String nonMaterialLeaf) {
            String message = messageOfRefusal(Map.of(nonMaterialLeaf, "false"));

            assertTrue(message.contains("No TLS key material is declared"),
                    "Quarkus contributes exactly these three @WithDefault leaves of the default "
                            + "bucket to EVERY boot, whether or not a bucket is declared. A bare "
                            + "prefix test would therefore see 'material is declared' in every "
                            + "deployment and this gate would never fire again — invisibly, since a "
                            + "hand-assembled configuration carries no such defaults: " + message);
        }

        @Test
        @DisplayName("A NAMED bucket is not the default bucket, so it does not satisfy the default leg")
        void namedBucketIsNotTheDefaultBucket() {
            String message = messageOfRefusal(Map.of(NAMED_BUCKET_MATERIAL, KEYSTORE_PATH));

            assertTrue(message.contains("No TLS key material is declared"),
                    "HttpServerOptionsUtils consults the DEFAULT bucket only when no configuration "
                            + "name is selected, so a named bucket nobody selected supplies the main "
                            + "listener with nothing: " + message);
        }

        @Test
        @DisplayName("The default bucket is seen through its environment-variable spelling too")
        void defaultBucketIsSeenThroughItsEnvironmentSpelling() {
            assertDoesNotThrow(
                    () -> validate(Map.of("QUARKUS_TLS_KEY__STORE_P12_PATH", KEYSTORE_PATH)),
                    "the bucket is detected by enumerating names rather than by direct lookup, so "
                            + "the canonicalisation is the only thing that lets a bucket declared "
                            + "through the environment door be seen — without it this gate would "
                            + "refuse a deployment that really does carry key material");
        }

        @Test
        @DisplayName("A PEM entry of the default bucket counts, whatever arbitrary name it is filed under")
        void pemEntryOfTheDefaultBucketCounts() {
            assertAll("the PEM sub-bucket is a map, so its material can only be found by enumeration",
                    () -> assertDoesNotThrow(() -> validate(
                                    Map.of("quarkus.tls.key-store.pem.a.cert", CERTIFICATE_PATH)),
                            "the certificate leaf"),
                    () -> assertDoesNotThrow(() -> validate(
                                    Map.of("quarkus.tls.key-store.pem.a.key", "/etc/certs/localhost.key")),
                            "the private-key leaf"));
        }
    }

    @Nested
    @DisplayName("Refusal case (b) — the selected TLS bucket carries no key material")
    class NamedBucketWithoutKeyMaterial {

        @Test
        @DisplayName("Refuses, naming the bucket and BOTH remedies")
        void refusesNamingTheBucket() {
            String message = messageOfRefusal(Map.of(
                    DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                    "quarkus.tls." + NAMED_BUCKET + ".reload-period", "24H"));

            assertAll("the operator asked for TLS through a bucket that has nothing in it",
                    () -> assertTrue(message.contains(NAMED_BUCKET),
                            "the offending bucket must be named — a typo in it is the likeliest "
                                    + "cause and is unfindable otherwise: " + message),
                    () -> assertTrue(message.contains("no key material is declared for it"),
                            "it must say what is wrong with the bucket, not merely that something "
                                    + "is: " + message),
                    () -> assertTrue(message.contains("quarkus.tls." + NAMED_BUCKET + ".key-store.p12.path"),
                            "remedy one is 'give the bucket material', spelled out for THIS bucket: "
                                    + message),
                    () -> assertTrue(message.contains(DeclaredKeyMaterialKeys.INSECURE_REQUESTS + "="
                                    + DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED),
                            "remedy two is the explicit plain-HTTP opt-in: " + message));
        }

        @Test
        @DisplayName("A bucket carrying key material is accepted")
        void bucketWithKeyMaterialIsAccepted() {
            assertDoesNotThrow(() -> validate(Map.of(
                            DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                            NAMED_BUCKET_MATERIAL, KEYSTORE_PATH)),
                    "the positive control for the refusal above — the same selected bucket, with "
                            + "material, must pass, or the refusal could be firing on the selection "
                            + "itself rather than on the emptiness");
        }

        @Test
        @DisplayName("A PEM pair in the selected bucket is key material too")
        void bucketWithPemMaterialIsAccepted() {
            assertDoesNotThrow(() -> validate(Map.of(
                            DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                            "quarkus.tls." + NAMED_BUCKET + ".key-store.pem.a.cert", CERTIFICATE_PATH)),
                    "the PEM sub-bucket is a map keyed by an arbitrary name, so a bucket populated "
                            + "that way is only found by enumeration — a fixed probe would miss it "
                            + "and refuse a correctly configured deployment");
        }

        @Test
        @DisplayName("Legacy certificate keys do not rescue a selected key-less bucket")
        void legacyCertificateDoesNotRescueASelectedKeyLessBucket() {
            String message = messageOfRefusal(Map.of(
                    DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                    DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES, CERTIFICATE_PATH));

            assertTrue(message.contains(NAMED_BUCKET),
                    "selecting a bucket REPLACES the legacy certificate rather than adding to it, so "
                            + "a key-less selection strands the listener even with the certificate "
                            + "keys set — accepting this would be exactly the silent plain-HTTP "
                            + "outcome the gate exists to refuse: " + message);
        }
    }

    @Nested
    @DisplayName("The explicit plain-HTTP opt-in")
    class ExplicitPlainHttpOptIn {

        @Test
        @DisplayName("insecure-requests=enabled with no key material at all is accepted in silence")
        void enabledWithNoKeyMaterialIsAccepted() {
            assertDoesNotThrow(() -> gateOver(Map.of(),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED)
                            .assertServerTlsDeclarationIsCoherent(),
                    "this is the deliberate plain-HTTP deployment — behind a TLS-terminating "
                            + "ingress — and it is the operator's to make. The gate refuses inferred "
                            + "cleartext, never declared cleartext");
        }

        @Test
        @DisplayName("insecure-requests=enabled also stands down the key-less-bucket refusal")
        void enabledAlsoStandsDownTheBucketRefusal() {
            assertDoesNotThrow(() -> gateOver(Map.of(
                                    DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                                    "quarkus.tls." + NAMED_BUCKET + ".reload-period", "24H"),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED)
                            .assertServerTlsDeclarationIsCoherent(),
                    "an operator who has explicitly declared plain HTTP has already settled the "
                            + "posture; the empty bucket then buys nothing but costs no "
                            + "confidentiality, because the cleartext being served is the cleartext "
                            + "that was asked for");
        }

        @Test
        @DisplayName("The shipped 'redirect' is NOT the opt-in — the negative control")
        void shippedRedirectIsNotTheOptIn() {
            String message = messageOfRefusal(Map.of());

            assertTrue(message.contains("No TLS key material is declared"),
                    "every other case here is decided against a configuration carrying "
                            + "insecure-requests=redirect, so this control is what proves the "
                            + "acceptances above come from 'enabled' specifically rather than from "
                            + "the key being present at all: " + message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private static void validate(Map<String, String> declared) {
        gateOver(declared, SHIPPED_DEFAULT).assertServerTlsDeclarationIsCoherent();
    }

    /**
     * Builds the gate over a configuration carrying the shipped exposure default and the supplied
     * deployment declarations.
     *
     * @param declared the configuration a deployment declares
     * @param exposure the value of {@code quarkus.http.insecure-requests}
     * @return the gate, ready to be asked its question
     */
    private static ServerTlsDeclarationGate gateOver(Map<String, String> declared, String exposure) {
        Map<String, String> deployment = new LinkedHashMap<>(declared);
        Config config = new SmallRyeConfigBuilder()
                .withSources(new StandInSource("StandInApplicationProperties",
                        APPLICATION_PROPERTIES_ORDINAL,
                        Map.of(DeclaredKeyMaterialKeys.INSECURE_REQUESTS, exposure)))
                .withSources(new StandInSource("StandInEnvironmentSource", ENVIRONMENT_ORDINAL,
                        deployment))
                .build();
        return new ServerTlsDeclarationGate(config);
    }

    /**
     * Runs the gate over a configuration that must be refused and returns the refusal's message.
     *
     * @param declared the configuration a deployment declares
     * @return the thrown message
     */
    private static String messageOfRefusal(Map<String, String> declared) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> validate(declared),
                "this configuration declares no usable key material and no plain-HTTP opt-in, so "
                        + "the boot must be refused rather than quietly producing a gateway that "
                        + "serves cleartext nobody asked for");
        return String.valueOf(thrown.getMessage());
    }

    /**
     * Stands in for a real configuration source at a real ordinal, so the shipped default is present
     * at the ordinal {@code application.properties} really has rather than being absent.
     */
    private record StandInSource(String name, int ordinal, Map<String, String> properties)
            implements ConfigSource {

        @Override
        public int getOrdinal() {
            return ordinal;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Set<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public @Nullable String getValue(String propertyName) {
            return properties.get(propertyName);
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }
    }
}
