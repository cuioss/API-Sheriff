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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;


import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.jspecify.annotations.Nullable;

/**
 * Refuses the boot when the terminated main listener would serve neither HTTPS nor a
 * deliberately-declared plain HTTP.
 *
 * <h2>Plain HTTP is declared, never inferred</h2>
 *
 * {@code quarkus.http.insecure-requests=enabled} already <em>is</em> the explicit plain-HTTP opt-in,
 * and it already wins where it matters: the environment-variable source outranks
 * {@code application.properties}, so a deployment that means to serve cleartext says so and is
 * obeyed. The only thing any further mechanism could add on top of that is <em>inference</em> —
 * treating the ABSENCE of a certificate as a request for cleartext — and that inference is precisely
 * what must not exist. Absence cannot express intent: "deliberately behind a TLS-terminating
 * ingress" and "the certificate failed to mount" are the same observation, and resolving that
 * ambiguity toward cleartext turns a mounting mistake into a silently unencrypted gateway.
 * <p>
 * So the missing certificate is answered with a refusal that names both ways out, and the operator
 * chooses. An invalid or incoherent declaration fails loudly; a deliberate one is obeyed without
 * comment.
 *
 * <h2>What it refuses, and what it deliberately does not</h2>
 *
 * <ul>
 *   <li>No key material is declared by any route, and {@code quarkus.http.insecure-requests} is not
 *       {@code enabled}.</li>
 *   <li>{@code quarkus.http.tls-configuration-name} selects a bucket for which no
 *       {@code quarkus.tls.<name>.key-store.*} material is declared — almost always a typo in the
 *       name, or a bucket nobody populated.</li>
 * </ul>
 *
 * An explicit {@code enabled} stands <em>both</em> refusals down, in silence. That key is the
 * operator stating the posture in the one key that means it, and this gate exists to make plain HTTP
 * explicit rather than to second-guess an operator who has been explicit. A bucket name that
 * resolves to nothing is still worth nothing on such a deployment, but it costs no confidentiality:
 * the listener is serving the cleartext that was asked for, not cleartext nobody chose.
 *
 * <h2>Why this seam, and not a configuration source</h2>
 *
 * The runtime already refuses the no-certificate state, but from too deep and with the wrong words.
 * {@code VertxHttpRecorder.initializeMainHttpServer} drops the SSL options when no key/certificate
 * options survived the customizer hooks and then throws
 * {@code IllegalStateException("Cannot set quarkus.http.insecure-requests without enabling SSL.")}
 * — a sentence that names {@code enabled} to an operator who set {@code redirect}, says nothing
 * about the certificate that is actually missing, and offers no remedy. The refusal therefore has to
 * be raised <em>before</em> that guard.
 * <p>
 * <strong>An {@link HttpServerOptionsCustomizer} is the earliest hook that qualifies.</strong> In
 * {@code initializeMainHttpServer} the customizer loop —
 * {@code customizeHttpServer} / {@code customizeHttpsServer} / {@code customizeDomainSocketServer} —
 * runs strictly before the {@code getKeyCertOptions() == null} test and the throw that follows it,
 * so throwing from {@link #customizeHttpServer(HttpServerOptions)} pre-empts the framework message
 * with this one. {@link TlsServerCustomizer} already fails the boot from this same seam for an
 * unusable cipher allowlist, so the pattern is the project's, not a new one.
 * <p>
 * <strong>A {@code ConfigSourceFactory} is NOT usable for this, and that is a hard constraint rather
 * than a preference.</strong> A factory runs wherever the configuration system is assembled — which
 * includes the build: the Quarkus code-generation and augmentation phases initialize application
 * configuration from the module's own {@code application.properties}, where no certificate is
 * declared and none should be (the shipped artifact declares no deployment material, ADR-0032).
 * A factory that threw would therefore fail the <em>build</em> of every module carrying this jar,
 * for a state that is correct at build time and only meaningful at boot. This seam runs in a real
 * boot and nowhere else.
 *
 * <h2>Declared, never resolved</h2>
 *
 * The verdict is reached from <strong>declared</strong> keys. That is a real limit and it cuts in
 * exactly one direction: a declaration that later resolves to nothing usable is accepted here and is
 * caught downstream, where {@link TerminatedListenerTlsAudit} reports the <em>resolved</em> state as
 * {@code ApiSheriff-121}. This gate never converts an absent declaration into a permissive posture,
 * so the limit costs strictness, never confidentiality.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ServerTlsDeclarationGate implements HttpServerOptionsCustomizer {

    /**
     * Route 2 — the DEFAULT TLS registry bucket. Named buckets are reached through
     * {@link #namedKeyStorePrefix(String)} instead: only the default bucket is consulted by
     * {@code HttpServerOptionsUtils} when no configuration name is selected.
     */
    private static final String DEFAULT_KEY_STORE_PREFIX = "quarkus.tls.key-store.";

    /** The PEM sub-bucket suffix of any key-store bucket. */
    private static final String PEM_SEGMENT = "pem.";

    /**
     * The material-bearing leaves of the PEM sub-bucket. It is a MAP keyed by an arbitrary name, so
     * its material can only be found by enumerating names — there is no fixed leaf to probe.
     */
    private static final List<String> PEM_MATERIAL_SUFFIXES = List.of(".cert", ".key");

    /** The material-bearing leaves of the keystore-file sub-buckets. */
    private static final List<String> KEY_STORE_MATERIAL_SUFFIXES = List.of(".p12.path", ".jks.path");

    /**
     * Folds every run of non-alphanumeric characters into a single {@code .} so a property name and
     * its environment-variable spelling compare equal. {@code quarkus.tls.key-store.p12.path} and
     * {@code QUARKUS_TLS_KEY__STORE_P12_PATH} both canonicalize to
     * {@code quarkus.tls.key.store.p12.path}, which is what lets the bucket tests see a bucket
     * declared through either door. Direct lookups do not need this — the configuration maps a
     * requested name onto its environment spelling itself — so it is used only where names are
     * enumerated.
     */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private static final String SUPPORTED_SPELLINGS =
            "quarkus.http.ssl.certificate.files (with .key-files), "
                    + "quarkus.http.ssl.certificate.key-store-file, or "
                    + "quarkus.http.ssl.certificate.credentials-provider";

    private static final String PLAIN_HTTP_REMEDY =
            "to serve plain HTTP deliberately — behind a TLS-terminating boundary — set "
                    + "quarkus.http.insecure-requests=enabled, which is the explicit opt-in and is "
                    + "never inferred from a missing certificate";

    private final Config config;

    /**
     * @param config the resolved configuration, read for the declared server-TLS keys and for the
     *               enumerated TLS-registry bucket names
     */
    @Inject
    public ServerTlsDeclarationGate(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Runs the gate on the plain-listener customization hook.
     * <p>
     * The plain hook is chosen deliberately over {@code customizeHttpsServer}: the HTTPS options do
     * not exist in the very state this gate is about, so a check hung off them could never fire in
     * it. The plain options always exist, and the hook is invoked before the framework's own guard.
     *
     * @param options the plain listener's options, untouched by this gate
     */
    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        assertServerTlsDeclarationIsCoherent();
    }

    /**
     * Applies the two refusal cases, in the order their preconditions nest.
     *
     * @throws IllegalStateException when the terminated main listener would serve neither HTTPS nor
     *                               a deliberately-declared plain HTTP
     */
    public void assertServerTlsDeclarationIsCoherent() {
        if (DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED.equals(
                declaredValue(DeclaredKeyMaterialKeys.INSECURE_REQUESTS))) {
            return;
        }
        String bucket = declaredValue(DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME);
        if (bucket != null) {
            if (!declaresKeyMaterialUnder(namedKeyStorePrefix(bucket))) {
                throw new IllegalStateException(namedBucketWithoutKeyMaterial(bucket));
            }
            return;
        }
        if (declaresLegacyCertificate() || declaresKeyMaterialUnder(DEFAULT_KEY_STORE_PREFIX)) {
            return;
        }
        throw new IllegalStateException(noKeyMaterialDeclared());
    }

    private boolean declaresLegacyCertificate() {
        for (String key : DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS) {
            if (declaredValue(key) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a key and reduces "present but blank" to "absent".
     * <p>
     * A key cleared to the empty string — the shape a compose file's bare
     * {@code QUARKUS_HTTP_SSL_CERTIFICATE_FILES=} or a test profile override produces — supplies no
     * material and selects no bucket, so treating it as a declaration would refuse the boot over a
     * certificate nobody configured, or accept a bucket nobody named.
     *
     * @param name the key to read
     * @return the non-blank value, or {@code null} when the key is absent or blank
     */
    private @Nullable String declaredValue(String name) {
        ConfigValue candidate = config.getConfigValue(name);
        if (candidate == null || candidate.getValue() == null || candidate.getValue().isBlank()) {
            return null;
        }
        return candidate.getValue();
    }

    /**
     * @param bucket the selected TLS registry bucket name
     * @return the key-store prefix that bucket's material lives under
     */
    private static String namedKeyStorePrefix(String bucket) {
        return "quarkus.tls." + bucket + ".key-store.";
    }

    /**
     * Answers whether a TLS registry bucket carries key <em>material</em>.
     * <p>
     * The bucket has to be found by enumerating names rather than by probing a fixed key list,
     * because its PEM entry is a map keyed by an arbitrary name — {@code …key-store.pem.<name>.cert}
     * — that no fixed probe could reach. What is enumerated is then narrowed to the material-bearing
     * leaves, and that narrowing is the whole correctness of this method.
     * <p>
     * <strong>A bare prefix test cannot work, and fails in the one direction that matters.</strong>
     * Quarkus contributes its own {@code @WithDefault} leaves of a key-store bucket —
     * {@code …key-store.sni}, {@code …key-store.credentials-provider.password-key} and
     * {@code …alias-password-key} are present in every boot, whether or not a bucket is declared. A
     * prefix test therefore reports "material is declared" in every deployment, which would make
     * this gate permanently silent and hand the no-certificate boot back the framework's own
     * confusing refusal.
     *
     * @param keyStorePrefix the bucket's key-store prefix, in property spelling
     * @return {@code true} when a material-bearing leaf of that bucket carries a non-blank value
     */
    private boolean declaresKeyMaterialUnder(String keyStorePrefix) {
        String bucket = canonical(keyStorePrefix);
        String pem = canonical(keyStorePrefix + PEM_SEGMENT);
        for (String name : config.getPropertyNames()) {
            String canonical = canonical(name);
            if (!canonical.startsWith(bucket)) {
                continue;
            }
            List<String> materialLeaves = canonical.startsWith(pem)
                    ? PEM_MATERIAL_SUFFIXES
                    : KEY_STORE_MATERIAL_SUFFIXES;
            if (endsWithAny(canonical, materialLeaves) && declaredValue(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String canonicalName, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (canonicalName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(String name) {
        return NON_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(".");
    }

    /**
     * @return the refusal raised when nothing declares key material and plain HTTP was never asked
     *         for
     */
    private static String noKeyMaterialDeclared() {
        return "No TLS key material is declared for the terminated main listener, and "
                + DeclaredKeyMaterialKeys.INSECURE_REQUESTS + " is not '"
                + DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED
                + "', so this gateway would serve neither HTTPS nor plain HTTP. Either declare a "
                + "server certificate — through " + DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME
                + ", a default quarkus.tls.key-store.* bucket, or one of " + SUPPORTED_SPELLINGS
                + " — or " + PLAIN_HTTP_REMEDY + ". A missing certificate cannot distinguish a "
                + "gateway deliberately placed behind a TLS-terminating ingress from one whose "
                + "certificate failed to mount, so cleartext is never chosen on its behalf.";
    }

    /**
     * @param bucket the selected but key-less TLS registry bucket
     * @return the refusal raised when the selected bucket carries no key material
     */
    private static String namedBucketWithoutKeyMaterial(String bucket) {
        return DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME + " selects the TLS registry "
                + "bucket '" + bucket + "', but no key material is declared for it: no "
                + "quarkus.tls." + bucket + ".key-store.* key carrying a certificate or a private "
                + "key was found, so the terminated main listener would have nothing to terminate "
                + "with. Either declare key material for that bucket — quarkus.tls." + bucket
                + ".key-store.p12.path, .jks.path, or a quarkus.tls." + bucket
                + ".key-store.pem.<id>.cert and .key pair — or point "
                + DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME + " at a bucket that has "
                + "some, or remove that key and declare the certificate through " + SUPPORTED_SPELLINGS
                + ". Alternatively, " + PLAIN_HTTP_REMEDY + ".";
    }
}
