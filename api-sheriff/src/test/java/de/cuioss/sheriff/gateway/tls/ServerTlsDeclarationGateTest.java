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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import io.smallrye.config.EnvConfigSource;
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
 * Tests for {@link ServerTlsDeclarationGate}: the two certificate-absence refusals, the explicit
 * plain-HTTP opt-in that stands both of those down, the three incoherence refusals that same opt-in
 * selects instead, and the hook every refusal actually travels through.
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
 * <p>
 * <strong>Cases about the environment door use a real {@link io.smallrye.config.EnvConfigSource},
 * never {@link StandInSource}.</strong> The stand-in returns its map keys verbatim, so it would
 * report whatever spelling the test handed it and could never observe SmallRye's actual
 * property-name-to-environment mapping — a case built on it can pass while no boot resolves the
 * variable. Those cases go through {@link #environmentConfig(Map, Map)} instead, and each asserts
 * the RESOLVED property alongside the gate's verdict so the mapping is pinned by SmallRye rather
 * than by this harness.
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

    /**
     * The environment spelling of {@link #DEFAULT_BUCKET_MATERIAL}: EACH non-alphanumeric character
     * becomes EXACTLY ONE {@code _}, the dash of {@code key-store} included.
     */
    private static final String ENV_DEFAULT_BUCKET_MATERIAL = "QUARKUS_TLS_KEY_STORE_P12_PATH";

    /**
     * The spelling an operator reaches for when they assume a dash doubles the underscore. It
     * resolves to nothing: {@code __} encodes a quote, so this decodes to the malformed
     * {@code quarkus.tls.key."store.p12.path}.
     */
    private static final String ENV_DOUBLED_UNDERSCORE_MATERIAL = "QUARKUS_TLS_KEY__STORE_P12_PATH";

    /** A bucket name containing a {@code .}, which is therefore a map key that needs quoting. */
    private static final String DOTTED_BUCKET = "my.tls";
    private static final String DOTTED_BUCKET_MATERIAL =
            "quarkus.tls.\"" + DOTTED_BUCKET + "\".key-store.p12.path";

    /**
     * The environment spelling of {@link #DOTTED_BUCKET_MATERIAL}. The two {@code __} pairs are the
     * opening and closing quote around the bucket name — the one place a doubled underscore is
     * correct, and the case a blanket ban on {@code __} would wrongly refuse.
     */
    private static final String ENV_DOTTED_BUCKET_MATERIAL =
            "QUARKUS_TLS__MY_TLS__KEY_STORE_P12_PATH";

    private static final String CERTIFICATE_PATH = "/etc/certs/localhost.crt";
    private static final String KEYSTORE_PATH = "/etc/certs/keystore.p12";

    /** The plain-HTTP opt-in as an operator types it, and as every incoherence refusal names it. */
    private static final String OPT_IN = DeclaredKeyMaterialKeys.INSECURE_REQUESTS + "="
            + DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED;

    private static final String PASSTHROUGH_HOST = "relayed.example.com";
    private static final String PASSTHROUGH_TARGET = "relay-upstream";

    /**
     * A gateway document with no {@code tls} block at all — the default every case that names only
     * Quarkus keys is decided against, so those cases keep deciding on the keys they name.
     */
    private static final GatewayConfig TLS_LESS_GATEWAY = GatewayConfig.builder().build();

    @Test
    @DisplayName("The refusal travels through the customizer hook the recorder actually calls")
    void refusalTravelsThroughTheCustomizerHook() {
        ServerTlsDeclarationGate gate = gateOver(Map.of(), SHIPPED_DEFAULT);
        HttpServerOptions options = new HttpServerOptions();

        assertThrows(IllegalStateException.class,
                () -> gate.customizeHttpServer(options),
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
            Config config = environmentConfig(Map.of(ENV_DEFAULT_BUCKET_MATERIAL, KEYSTORE_PATH));

            assertAll("the spelling SmallRye resolves must be the spelling the gate accepts",
                    () -> assertEquals(KEYSTORE_PATH,
                            config.getConfigValue(DEFAULT_BUCKET_MATERIAL).getValue(),
                            ENV_DEFAULT_BUCKET_MATERIAL + " is the environment spelling of "
                                    + DEFAULT_BUCKET_MATERIAL + " — one underscore per "
                                    + "non-alphanumeric character. Asserting the resolved property "
                                    + "first is what makes the gate verdict below meaningful: it "
                                    + "pins that SmallRye, not this harness, decides the mapping"),
                    () -> assertDoesNotThrow(
                            () -> gateOver(config).assertServerTlsDeclarationIsCoherent(),
                            "the bucket is detected by enumerating names rather than by direct "
                                    + "lookup, so the canonicalisation is the only thing that lets "
                                    + "a bucket declared through the environment door be seen — "
                                    + "without it this gate would refuse a deployment that really "
                                    + "does carry key material"));
        }

        @Test
        @DisplayName("The doubled-underscore spelling resolves to nothing, so it is REFUSED")
        void doubledUnderscoreSpellingIsRefused() {
            Config config = environmentConfig(Map.of(ENV_DOUBLED_UNDERSCORE_MATERIAL, KEYSTORE_PATH));

            assertAll("the matched negative control for the acceptance above",
                    () -> assertNull(config.getConfigValue(DEFAULT_BUCKET_MATERIAL).getValue(),
                            ENV_DOUBLED_UNDERSCORE_MATERIAL + " does NOT resolve to "
                                    + DEFAULT_BUCKET_MATERIAL + ": a doubled __ encodes a quote, "
                                    + "never a dash, so EnvConfigSource decodes it to the malformed "
                                    + "quarkus.tls.key.\"store.p12.path and EnvName.equals rejects "
                                    + "it. If this assertion ever fails, SmallRye changed its "
                                    + "mapping rule and the gate may widen to match"),
                    () -> assertTrue(messageOfRefusal(gateOver(config))
                                    .contains("No TLS key material is declared"),
                            "a declaration the runtime cannot resolve IS invalid configuration, so "
                                    + "it must reach this gate's remedy-bearing refusal. Accepting "
                                    + "it would let the boot die on the framework's generic "
                                    + "'Cannot set quarkus.http.insecure-requests without enabling "
                                    + "SSL.' — the confusing message this gate exists to replace — "
                                    + "and would be a silent pass on invalid config"));
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
        @DisplayName("A quoted map-key bucket IS seen through the environment door — the __ that is legitimate")
        void quotedBucketIsSeenThroughTheEnvironmentDoor() {
            Config config = environmentConfig(
                    Map.of(DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, DOTTED_BUCKET),
                    Map.of(ENV_DOTTED_BUCKET_MATERIAL, KEYSTORE_PATH));

            assertAll("the doubled underscore is a QUOTE, and here both quotes are present",
                    () -> assertEquals(KEYSTORE_PATH,
                            config.getConfigValue(DOTTED_BUCKET_MATERIAL).getValue(),
                            "a bucket name containing a '.' must be quoted, and a quote is spelled "
                                    + "__ in the environment — so " + ENV_DOTTED_BUCKET_MATERIAL
                                    + " really does resolve to " + DOTTED_BUCKET_MATERIAL),
                    () -> assertDoesNotThrow(
                            () -> gateOver(config).assertServerTlsDeclarationIsCoherent(),
                            "this is the case that forbids closing the over-permissive match by "
                                    + "simply refusing every doubled underscore. These __ pairs are "
                                    + "legitimate quoting, the deployment is correctly configured, "
                                    + "and refusing it would trade a false accept for a false "
                                    + "REFUSAL — strictly worse, because it breaks a working "
                                    + "gateway rather than an unresolvable one"));
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

    @Nested
    @DisplayName("Refusal case (c) — the plain-HTTP opt-in is combined with declared key material")
    class PlainHttpWithKeyMaterial {

        @ParameterizedTest
        @DisplayName("Every certificate spelling refuses, naming ITS key and BOTH remedies")
        @ValueSource(strings = {
                "quarkus.http.ssl.certificate.files",
                "quarkus.http.ssl.certificate.key-files",
                "quarkus.http.ssl.certificate.key-store-file",
                "quarkus.http.ssl.certificate.credentials-provider"
        })
        void everyCertificateSpellingRefusesNamingItsKey(String declaredKey) {
            String message = messageOfIncoherentPlainHttp(Map.of(declaredKey, CERTIFICATE_PATH));

            assertAll("one listener cannot both serve cleartext and terminate TLS",
                    () -> assertTrue(message.contains(declaredKey),
                            "the offending key must be named, and it must be the one the deployment "
                                    + "actually set rather than a representative spelling: " + message),
                    () -> assertTrue(message.contains("Either remove " + declaredKey),
                            "remedy one is 'drop the certificate', spelled out for THIS key: " + message),
                    () -> assertTrue(message.contains("or remove " + OPT_IN),
                            "remedy two is 'drop the opt-in, the declared certificate then terminates "
                                    + "TLS' — a refusal naming only one way out leaves the operator "
                                    + "to guess which declaration this gateway wanted: " + message));
        }

        @Test
        @DisplayName("A default quarkus.tls.key-store.* bucket carrying material refuses too")
        void defaultBucketMaterialRefuses() {
            String message = messageOfIncoherentPlainHttp(Map.of(DEFAULT_BUCKET_MATERIAL, KEYSTORE_PATH));

            assertTrue(message.contains("quarkus.tls.key-store."),
                    "the registry bucket is a second route to the same key material, so refusing "
                            + "only the ssl.certificate.* spellings would leave a bucket-configured "
                            + "deployment serving cleartext with a certificate mounted: " + message);
        }

        @Test
        @DisplayName("A SELECTED bucket carrying material refuses, naming the bucket and its selector")
        void selectedBucketWithMaterialRefuses() {
            String message = messageOfIncoherentPlainHttp(Map.of(
                    DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                    NAMED_BUCKET_MATERIAL, KEYSTORE_PATH));

            assertAll("the third route to declared material is a named bucket the operator selected",
                    () -> assertTrue(message.contains(NAMED_BUCKET),
                            "the bucket actually carrying the material must be named: " + message),
                    () -> assertTrue(message.contains(DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME),
                            "so must the key that selected it — that key is what the operator edits: "
                                    + message));
        }

        @Test
        @DisplayName("The same opt-in over a bucket with no MATERIAL still passes in silence")
        void theSameOptInWithoutKeyMaterialPasses() {
            assertDoesNotThrow(() -> gateOver(Map.of("quarkus.tls.key-store.sni", "false"),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED)
                            .assertServerTlsDeclarationIsCoherent(),
                    "the matched negative control for the refusals above: this configuration touches "
                            + "the very same bucket and differs only in declaring no material, so a "
                            + "refusal here would mean the gate fires on the bucket's presence rather "
                            + "than on the contradiction. That leaf is one Quarkus contributes to "
                            + "every boot, so refusing it would refuse every plain-HTTP deployment");
        }

        @Test
        @DisplayName("A key-less SELECTED bucket plus the opt-in is still accepted — the boundary case")
        void keyLessSelectedBucketWithTheOptInIsAccepted() {
            assertDoesNotThrow(() -> gateOver(Map.of(
                                    DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME, NAMED_BUCKET,
                                    "quarkus.tls." + NAMED_BUCKET + ".reload-period", "24H"),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED)
                            .assertServerTlsDeclarationIsCoherent(),
                    "selecting a bucket is not declaring material, and a bucket with nothing in it "
                            + "contradicts nothing. Refusing here would satisfy this refusal by "
                            + "refusing the SELECTION rather than the material, and would take the "
                            + "already-shipped stand-down of the key-less-bucket refusal with it");
        }
    }

    @Nested
    @DisplayName("Refusal case (d) — the plain-HTTP opt-in is combined with tls.mtls.enabled")
    class PlainHttpWithMtls {

        @Test
        @DisplayName("Refuses, naming tls.mtls.enabled and BOTH remedies")
        void refusesNamingMtlsAndBothRemedies() {
            String message = messageOfIncoherentPlainHttp(Map.of(), gatewayWithMtls(true));

            assertAll("a gateway that terminates no TLS can verify no client certificate",
                    () -> assertTrue(message.contains("tls.mtls.enabled"),
                            "the offending key must be named in the gateway.yaml spelling the "
                                    + "operator wrote it in: " + message),
                    () -> assertTrue(message.contains("remove the tls.mtls block"),
                            "remedy one is 'drop the mTLS requirement': " + message),
                    () -> assertTrue(message.contains("remove " + OPT_IN)
                            && message.contains(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES),
                            "remedy two is 'terminate TLS instead', which is worthless unless it "
                                    + "names how a certificate is declared: " + message),
                    () -> assertTrue(message.contains("silently inert"),
                            "the message must say WHY the combination is refused rather than "
                                    + "ignored — the requirement does not weaken anything today, it "
                                    + "simply never runs, and that is the whole point: " + message));
        }

        @Test
        @DisplayName("The same opt-in with tls.mtls.enabled=false passes in silence")
        void theSameOptInWithMtlsDisabledPasses() {
            assertDoesNotThrow(() -> gateOver(Map.of(),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED, gatewayWithMtls(false))
                            .assertServerTlsDeclarationIsCoherent(),
                    "the matched negative control: the same tls.mtls block, differing only in the "
                            + "one flag that expresses the requirement. A refusal here would mean the "
                            + "gate fires on the block's presence rather than on what it requires");
        }

        @Test
        @DisplayName("mTLS on a TLS-TERMINATING listener is untouched — the refusal is opt-in-scoped")
        void mtlsOnATerminatingListenerIsUntouched() {
            assertDoesNotThrow(() -> gateOver(
                            Map.of(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES, CERTIFICATE_PATH),
                            SHIPPED_DEFAULT, gatewayWithMtls(true))
                            .assertServerTlsDeclarationIsCoherent(),
                    "requiring client certificates on a listener that really does terminate TLS is "
                            + "the mTLS feature working as designed. This refusal exists to name a "
                            + "combination that cannot mean anything, so breaking every working mTLS "
                            + "gateway would be the one outcome worse than the silence it replaces");
        }
    }

    @Nested
    @DisplayName("Refusal case (e) — the plain-HTTP opt-in is combined with tls.passthrough_sni")
    class PlainHttpWithPassthroughSni {

        @Test
        @DisplayName("Refuses, naming tls.passthrough_sni and BOTH remedies")
        void refusesNamingPassthroughSniAndBothRemedies() {
            String message = messageOfIncoherentPlainHttp(Map.of(),
                    gatewayWithPassthroughSni(Map.of(PASSTHROUGH_HOST, PASSTHROUGH_TARGET)));

            assertAll("the SNI front listener claims the public TLS port a plain port contradicts",
                    () -> assertTrue(message.contains("tls.passthrough_sni"),
                            "the offending key must be named in the gateway.yaml spelling: " + message),
                    () -> assertTrue(message.contains("remove the tls.passthrough_sni block"),
                            "remedy one is 'drop the passthrough topology': " + message),
                    () -> assertTrue(message.contains("remove " + OPT_IN)
                            && message.contains(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES),
                            "remedy two is 'terminate TLS instead', naming how a certificate is "
                                    + "declared: " + message));
        }

        @Test
        @DisplayName("The same opt-in with an EMPTY passthrough_sni map passes in silence")
        void theSameOptInWithAnEmptyPassthroughMapPasses() {
            assertDoesNotThrow(() -> gateOver(Map.of(),
                            DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED,
                            gatewayWithPassthroughSni(Map.of()))
                            .assertServerTlsDeclarationIsCoherent(),
                    "the matched negative control: the same tls block, differing only in whether any "
                            + "hostname is actually relayed. An omitted passthrough_sni binds to the "
                            + "empty map, so refusing on the block's presence would refuse every "
                            + "gateway.yaml carrying a tls section at all");
        }

        @Test
        @DisplayName("Passthrough on a TLS-TERMINATING listener is untouched — the refusal is opt-in-scoped")
        void passthroughOnATerminatingListenerIsUntouched() {
            assertDoesNotThrow(() -> gateOver(
                            Map.of(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_FILES, CERTIFICATE_PATH),
                            SHIPPED_DEFAULT,
                            gatewayWithPassthroughSni(Map.of(PASSTHROUGH_HOST, PASSTHROUGH_TARGET)))
                            .assertServerTlsDeclarationIsCoherent(),
                    "the ADR-0017 topology pairs an L4 relay with a terminated listener, which is "
                            + "the shipped passthrough deployment. Only the plain-HTTP opt-in makes "
                            + "the pairing incoherent");
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
     * deployment declarations, against a gateway document declaring no {@code tls} block.
     *
     * @param declared the configuration a deployment declares
     * @param exposure the value of {@code quarkus.http.insecure-requests}
     * @return the gate, ready to be asked its question
     */
    private static ServerTlsDeclarationGate gateOver(Map<String, String> declared, String exposure) {
        return gateOver(declared, exposure, TLS_LESS_GATEWAY);
    }

    /**
     * Builds the gate over both of its inputs: the Quarkus-key configuration and the bound gateway
     * document the {@code tls.mtls} and {@code tls.passthrough_sni} refusals read.
     *
     * @param declared the configuration a deployment declares
     * @param exposure the value of {@code quarkus.http.insecure-requests}
     * @param gateway  the bound gateway document
     * @return the gate, ready to be asked its question
     */
    private static ServerTlsDeclarationGate gateOver(Map<String, String> declared, String exposure,
            GatewayConfig gateway) {
        Map<String, String> deployment = new LinkedHashMap<>(declared);
        Config config = new SmallRyeConfigBuilder()
                .withSources(new StandInSource("StandInApplicationProperties",
                        APPLICATION_PROPERTIES_ORDINAL,
                        Map.of(DeclaredKeyMaterialKeys.INSECURE_REQUESTS, exposure)))
                .withSources(new StandInSource("StandInEnvironmentSource", ENVIRONMENT_ORDINAL,
                        deployment))
                .build();
        return new ServerTlsDeclarationGate(config, gateway);
    }

    /**
     * @param config an already-assembled configuration, for the cases that build their own
     * @return the gate over it, against a gateway document declaring no {@code tls} block
     */
    private static ServerTlsDeclarationGate gateOver(Config config) {
        return new ServerTlsDeclarationGate(config, TLS_LESS_GATEWAY);
    }

    /**
     * @param enabled whether client-certificate verification is required
     * @return a gateway document whose {@code tls} block carries only that {@code mtls} setting
     */
    private static GatewayConfig gatewayWithMtls(boolean enabled) {
        return GatewayConfig.builder()
                .tls(TlsConfig.builder()
                        .mtls(TlsConfig.Mtls.builder().enabled(enabled).build())
                        .build())
                .build();
    }

    /**
     * @param passthroughSni the SNI hostnames relayed at L4, keyed to their topology alias
     * @return a gateway document whose {@code tls} block carries only that passthrough map
     */
    private static GatewayConfig gatewayWithPassthroughSni(Map<String, String> passthroughSni) {
        return GatewayConfig.builder()
                .tls(TlsConfig.builder().passthroughSni(passthroughSni).build())
                .build();
    }

    /**
     * Builds a configuration whose environment door is a REAL {@link EnvConfigSource}.
     * <p>
     * {@link StandInSource} returns its map keys verbatim, so a case assembled on it asserts only
     * what the harness was handed and structurally cannot observe the property-name-to-environment
     * mapping. Every case about that mapping is therefore decided by SmallRye itself, through the
     * same class a real boot reads its environment with. The source is constructed over a supplied
     * map rather than over {@code System.getenv()}, so no case depends on the machine it runs on.
     *
     * @param properties the deployment's {@code application.properties}-tier declarations
     * @param environment the deployment's environment variables, in environment spelling
     * @return the assembled configuration
     */
    private static Config environmentConfig(Map<String, String> properties,
            Map<String, String> environment) {
        Map<String, String> propertiesTier = new LinkedHashMap<>(properties);
        propertiesTier.putIfAbsent(DeclaredKeyMaterialKeys.INSECURE_REQUESTS, SHIPPED_DEFAULT);
        return new SmallRyeConfigBuilder()
                .withSources(new StandInSource("StandInApplicationProperties",
                        APPLICATION_PROPERTIES_ORDINAL, propertiesTier))
                .withSources(new EnvConfigSource(environment, ENVIRONMENT_ORDINAL))
                .build();
    }

    /**
     * @param environment the deployment's environment variables, in environment spelling
     * @return the assembled configuration, carrying only the shipped exposure default besides
     */
    private static Config environmentConfig(Map<String, String> environment) {
        return environmentConfig(Map.of(), environment);
    }

    /**
     * Runs the gate over a configuration that must be refused and returns the refusal's message.
     *
     * @param declared the configuration a deployment declares
     * @return the thrown message
     */
    private static String messageOfRefusal(Map<String, String> declared) {
        return messageOfRefusal(gateOver(declared, SHIPPED_DEFAULT));
    }

    /**
     * @param declared the configuration a deployment declares alongside the plain-HTTP opt-in
     * @return the thrown message
     */
    private static String messageOfIncoherentPlainHttp(Map<String, String> declared) {
        return messageOfIncoherentPlainHttp(declared, TLS_LESS_GATEWAY);
    }

    /**
     * Runs the gate over a plain-HTTP opt-in contradicted by a second declaration, and returns the
     * refusal's message.
     *
     * @param declared the configuration a deployment declares alongside the opt-in
     * @param gateway  the bound gateway document
     * @return the thrown message
     */
    private static String messageOfIncoherentPlainHttp(Map<String, String> declared,
            GatewayConfig gateway) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> gateOver(declared, DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED, gateway)
                        .assertServerTlsDeclarationIsCoherent(),
                "the plain-HTTP opt-in is declared alongside a declaration that can only mean "
                        + "something on a listener that terminates TLS. Resolving that toward either "
                        + "one silently would ship a posture nobody chose, so the boot must be "
                        + "refused and the operator asked which they meant");
        return String.valueOf(thrown.getMessage());
    }

    /**
     * Runs a gate that must refuse and returns the refusal's message.
     *
     * @param gate the gate over a configuration that must be refused
     * @return the thrown message
     */
    private static String messageOfRefusal(ServerTlsDeclarationGate gate) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                gate::assertServerTlsDeclarationIsCoherent,
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
