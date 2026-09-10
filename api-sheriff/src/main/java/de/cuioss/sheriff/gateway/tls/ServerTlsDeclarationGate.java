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
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
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
 *   <li>{@code quarkus.http.insecure-requests=enabled} is combined with declared server key material
 *       for that same listener: a listener cannot both serve cleartext and terminate TLS.</li>
 *   <li>{@code quarkus.http.insecure-requests=enabled} is combined with {@code tls.mtls.enabled}: a
 *       gateway that terminates no TLS participates in no handshake and can verify no client
 *       certificate, so the declared requirement would be silently inert.</li>
 *   <li>{@code quarkus.http.insecure-requests=enabled} is combined with a non-empty
 *       {@code tls.passthrough_sni}: the ADR-0017 front listener claims the public TLS port, which a
 *       plain-HTTP application port contradicts.</li>
 * </ul>
 *
 * An explicit {@code enabled} stands the first <em>two</em> refusals down, in silence. Those two are
 * the certificate-<em>absence</em> cases, and absence is exactly what the opt-in is entitled to
 * declare: the key is the operator stating the posture in the one key that means it, and this gate
 * exists to make plain HTTP explicit rather than to second-guess an operator who has been explicit.
 * A bucket name that resolves to nothing is still worth nothing on such a deployment, but it costs
 * no confidentiality: the listener is serving the cleartext that was asked for, not cleartext nobody
 * chose.
 * <p>
 * What the opt-in does <strong>not</strong> stand down is a declaration that contradicts it. The
 * last three refusals are incoherence rather than absence — each names a second declaration that
 * can only mean something on a listener that terminates TLS — so the opt-in makes them louder rather
 * than quieter, and each names the offending key and both ways out.
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
     * Folds <strong>one</strong> non-alphanumeric character into a single {@code .} so a property
     * name and its environment-variable spelling compare equal. {@code quarkus.tls.key-store.p12.path}
     * and {@code QUARKUS_TLS_KEY_STORE_P12_PATH} both canonicalize to
     * {@code quarkus.tls.key.store.p12.path}, which is what lets the bucket tests see a bucket
     * declared through either door. Direct lookups do not need this — the configuration maps a
     * requested name onto its environment spelling itself — so it is used only where names are
     * enumerated.
     * <p>
     * <strong>The absent {@code +} quantifier is the correctness of this pattern, not an oversight.</strong>
     * {@code EnvConfigSource} maps EACH non-alphanumeric character of a property name to EXACTLY ONE
     * {@code _}; a doubled {@code __} encodes a quote, never a dash. Folding <em>runs</em> would
     * therefore canonicalize {@code QUARKUS_TLS_KEY__STORE_P12_PATH} onto the default bucket and
     * report material that no boot can resolve — the gate would fall silent and hand the
     * no-certificate boot back the framework's own confusing refusal, which is the one outcome it
     * exists to prevent.
     */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

    private static final String SUPPORTED_SPELLINGS =
            "quarkus.http.ssl.certificate.files (with .key-files), "
                    + "quarkus.http.ssl.certificate.key-store-file, or "
                    + "quarkus.http.ssl.certificate.credentials-provider";

    private static final String PLAIN_HTTP_REMEDY =
            "to serve plain HTTP deliberately — behind a TLS-terminating boundary — set "
                    + "quarkus.http.insecure-requests=enabled, which is the explicit opt-in and is "
                    + "never inferred from a missing certificate";

    /** The plain-HTTP opt-in as an operator writes it, for the incoherence refusals to name. */
    private static final String PLAIN_HTTP_OPT_IN = DeclaredKeyMaterialKeys.INSECURE_REQUESTS + "="
            + DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED;

    /** The opening every incoherence refusal shares: the opt-in is declared, and something contradicts it. */
    private static final String PLAIN_HTTP_OPT_IN_DECLARED =
            PLAIN_HTTP_OPT_IN + " opts the terminated main listener into plain HTTP, but ";

    /** The second way out of every incoherence refusal: drop the opt-in and terminate TLS instead. */
    private static final String TERMINATE_TLS_REMEDY = "remove " + PLAIN_HTTP_OPT_IN
            + " and declare a server certificate through " + SUPPORTED_SPELLINGS;

    private final Config config;

    private final GatewayConfig gatewayConfig;

    /**
     * @param config        the resolved configuration, read for the declared server-TLS keys and for
     *                      the enumerated TLS-registry bucket names
     * @param gatewayConfig the bound global gateway document, read for the {@code tls.mtls} and
     *                      {@code tls.passthrough_sni} blocks the plain-HTTP opt-in contradicts
     */
    @Inject
    public ServerTlsDeclarationGate(Config config, GatewayConfig gatewayConfig) {
        this.config = Objects.requireNonNull(config, "config");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
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
     * Applies the five refusal cases, in the order their preconditions nest: the plain-HTTP opt-in
     * selects the three incoherence refusals, its absence the two certificate-absence refusals.
     *
     * @throws IllegalStateException when the terminated main listener would serve neither HTTPS nor
     *                               a deliberately-declared plain HTTP, or when the declared
     *                               plain-HTTP posture is contradicted by a second declaration that
     *                               only means something on a TLS-terminating listener
     */
    public void assertServerTlsDeclarationIsCoherent() {
        if (DeclaredKeyMaterialKeys.INSECURE_REQUESTS_ENABLED.equals(
                declaredValue(DeclaredKeyMaterialKeys.INSECURE_REQUESTS))) {
            assertPlainHttpDeclarationIsCoherent();
            return;
        }
        String bucket = declaredValue(DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME);
        if (bucket != null) {
            if (!declaresKeyMaterialUnder(namedKeyStorePrefix(bucket))) {
                throw new IllegalStateException(namedBucketWithoutKeyMaterial(bucket));
            }
            return;
        }
        if (declaredLegacyCertificateKey() != null || declaresKeyMaterialUnder(DEFAULT_KEY_STORE_PREFIX)) {
            return;
        }
        throw new IllegalStateException(noKeyMaterialDeclared());
    }

    /**
     * Applies the three refusals a declared plain-HTTP posture selects, in the order the deliverable
     * states them: declared server key material, inbound mTLS, then an SNI passthrough topology.
     * <p>
     * What is deliberately <em>not</em> refused here is a selected but key-less TLS registry bucket:
     * it declares no material, so it contradicts nothing, and the opt-in stands its own refusal down
     * exactly as it did before these three existed.
     *
     * @throws IllegalStateException when a second declaration contradicts the plain-HTTP opt-in
     */
    private void assertPlainHttpDeclarationIsCoherent() {
        String certificate = declaredServerKeyMaterial();
        if (certificate != null) {
            throw new IllegalStateException(plainHttpWithKeyMaterial(certificate));
        }
        TlsConfig tls = gatewayConfig.tls();
        if (tls == null) {
            return;
        }
        TlsConfig.Mtls mtls = tls.mtls();
        if (mtls != null && mtls.enabled()) {
            throw new IllegalStateException(plainHttpWithMtls());
        }
        if (!tls.passthroughSni().isEmpty()) {
            throw new IllegalStateException(plainHttpWithPassthroughSni());
        }
    }

    /**
     * Names the declaration that supplies server key material for the terminated main listener,
     * through any of the three routes this class already owns.
     *
     * @return an operator-facing name for the declaring key or bucket, or {@code null} when no route
     *         declares material
     */
    private @Nullable String declaredServerKeyMaterial() {
        String legacyKey = declaredLegacyCertificateKey();
        if (legacyKey != null) {
            return legacyKey;
        }
        if (declaresKeyMaterialUnder(DEFAULT_KEY_STORE_PREFIX)) {
            return "the default " + DEFAULT_KEY_STORE_PREFIX + "* bucket";
        }
        String bucket = declaredValue(DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME);
        if (bucket != null && declaresKeyMaterialUnder(namedKeyStorePrefix(bucket))) {
            return "the TLS registry bucket '" + bucket + "' selected by "
                    + DeclaredKeyMaterialKeys.HTTP_TLS_CONFIGURATION_NAME;
        }
        return null;
    }

    private @Nullable String declaredLegacyCertificateKey() {
        for (String key : DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS) {
            if (declaredValue(key) != null) {
                return key;
            }
        }
        return null;
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
     * <p>
     * <strong>A name that no spelling could resolve is skipped rather than matched.</strong>
     * {@link #canonical(String)} declines such a name — see its own note on the unpaired quote an
     * environment variable like {@code QUARKUS_TLS_KEY__STORE_P12_PATH} decodes to — and this
     * enumeration then passes over it, so an unresolvable declaration reaches the refusal it
     * deserves instead of standing the gate down.
     *
     * @param keyStorePrefix the bucket's key-store prefix, in property spelling
     * @return {@code true} when a material-bearing leaf of that bucket carries a non-blank value;
     *         {@code false} when the bucket name itself carries an unpaired quote, since no such
     *         bucket can resolve
     */
    private boolean declaresKeyMaterialUnder(String keyStorePrefix) {
        String bucket = canonical(keyStorePrefix);
        String pem = canonical(keyStorePrefix + PEM_SEGMENT);
        if (bucket == null || pem == null) {
            return false;
        }
        for (String name : config.getPropertyNames()) {
            String canonical = canonical(name);
            if (canonical == null || !canonical.startsWith(bucket)) {
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

    /**
     * Reduces a name to the spelling-independent form the bucket tests compare on, or reports that
     * no spelling could resolve it.
     *
     * <h4>Why quotes appear here at all</h4>
     *
     * {@code EnvConfigSource} publishes TWO names for every environment variable: the raw
     * {@code QUARKUS_TLS_..._PATH} spelling and a decoded dotted candidate produced by
     * {@code StringUtil.toLowerCaseAndDotted}. That decoder treats a doubled {@code __} as a
     * <em>quote</em> around a map key that needs quoting — a TLS bucket name containing a {@code .}
     * — so {@code QUARKUS_TLS__MY_BUCKET__KEY_STORE_P12_PATH} decodes to the well-formed
     * {@code quarkus.tls."my.bucket".key.store.p12.path}. Those quotes are erased here, which is
     * what lets a correctly quoted named bucket be seen through the environment door.
     *
     * <h4>Why an odd quote count is a refusal rather than a fold</h4>
     *
     * The same decoder emits an <em>unclosed</em> quote when a {@code __} was never a quote pair:
     * {@code QUARKUS_TLS_KEY__STORE_P12_PATH} decodes to {@code quarkus.tls.key."store.p12.path}.
     * That is precisely the spelling an operator reaches for when they assume a dash doubles the
     * underscore, and {@code EnvName.equals} rejects it — no such property resolves at boot.
     * Erasing the stray quote would fold it back onto the default bucket and re-open the false
     * accept; counting quotes and declining the name keeps the declaration invalid, so the gate
     * reaches its remedy-bearing refusal instead of passing silently.
     *
     * @param name a property name, an environment-variable spelling, or a decoded dotted candidate
     * @return the canonical form, or {@code null} when the name carries an unpaired quote and can
     *         therefore never resolve to a TLS property
     */
    private static @Nullable String canonical(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int quotes = 0;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == '"') {
                quotes++;
            }
        }
        if (quotes % 2 != 0) {
            return null;
        }
        String unquoted = quotes == 0 ? lower : lower.replace("\"", "");
        return NON_ALPHANUMERIC.matcher(unquoted).replaceAll(".");
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
     * @param declaration the operator-facing name of the declaration supplying the key material
     * @return the refusal raised when the plain-HTTP opt-in is combined with declared key material
     */
    private static String plainHttpWithKeyMaterial(String declaration) {
        return PLAIN_HTTP_OPT_IN_DECLARED + declaration + " declares server key material for that "
                + "same listener. One listener cannot both serve cleartext and terminate TLS, so the "
                + "two declarations contradict each other and this gateway will not guess which one "
                + "was meant. Either remove " + declaration + ", leaving the plain-HTTP opt-in as the "
                + "only declared posture — the listener then serves cleartext behind a "
                + "TLS-terminating boundary — or remove " + PLAIN_HTTP_OPT_IN + ", so the key "
                + "material that is already declared terminates TLS on this listener.";
    }

    /**
     * @return the refusal raised when the plain-HTTP opt-in is combined with inbound mTLS
     */
    private static String plainHttpWithMtls() {
        return PLAIN_HTTP_OPT_IN_DECLARED + "tls.mtls.enabled requires every terminated connection "
                + "to present a client certificate. A gateway that terminates no TLS participates in "
                + "no handshake and can verify no client certificate, so the declared requirement "
                + "would be silently inert rather than enforced. Either remove the tls.mtls block "
                + "from gateway.yaml — or set tls.mtls.enabled: false — leaving the plain-HTTP "
                + "opt-in as the only declared posture, or " + TERMINATE_TLS_REMEDY + ", so the "
                + "listener terminates TLS and can verify client certificates.";
    }

    /**
     * @return the refusal raised when the plain-HTTP opt-in is combined with an SNI passthrough
     *         topology
     */
    private static String plainHttpWithPassthroughSni() {
        return PLAIN_HTTP_OPT_IN_DECLARED + "tls.passthrough_sni declares an SNI passthrough "
                + "topology, which gives the public TLS port to the L4 front listener that relays "
                + "those hostnames undecrypted. A plain-HTTP application port on the same gateway "
                + "contradicts that topology. Either remove the tls.passthrough_sni block from "
                + "gateway.yaml, leaving the plain-HTTP opt-in as the only declared posture, or "
                + TERMINATE_TLS_REMEDY + ", so the terminated listener terminates TLS alongside the "
                + "passthrough relay.";
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
