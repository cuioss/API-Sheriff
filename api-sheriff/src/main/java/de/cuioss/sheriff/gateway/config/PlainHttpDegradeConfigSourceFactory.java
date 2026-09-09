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
package de.cuioss.sheriff.gateway.config;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jspecify.annotations.Nullable;

/**
 * Enters <em>no-certificate mode</em> — the <em>plain-HTTP degrade</em> — when the deployment
 * declares no server key material for the terminated main listener.
 * <p>
 * The whole contribution is one key: {@code quarkus.http.insecure-requests=enabled}, projected at
 * ordinal {@value #ORDINAL} and only in that state. In every other state this factory contributes
 * nothing at all, so the shipped {@code quarkus.http.insecure-requests=redirect} default stands
 * untouched and the secure posture is expressed by silence.
 *
 * <h2>Why the seam exists, and why it can only be a configuration source</h2>
 *
 * Without it the gateway does not boot at all in the no-certificate state.
 * {@code VertxHttpRecorder.initializeMainHttpServer} (quarkus-vertx-http 3.39.2) drops the SSL
 * options when no key/certificate options survived the customizer hooks, and then refuses:
 *
 * <pre>{@code
 * // Disable TLS if certificate options are still missing after customize hooks.
 * if (tmpSslConfig != null && tmpSslConfig.getKeyCertOptions() == null) {
 *     tmpSslConfig = null;
 * }
 * httpMainSslServerOptions = tmpSslConfig;
 *
 * if (insecureRequestStrategy != InsecureRequests.ENABLED
 *         && httpMainSslServerOptions == null) {
 *     throw new IllegalStateException("Cannot set quarkus.http.insecure-requests without enabling SSL.");
 * }
 * }</pre>
 *
 * That guard sits <em>upstream</em> of every other lever, which rules out the two seams this project
 * already uses for server TLS. An {@code HttpServerOptionsCustomizer} — the
 * {@link de.cuioss.sheriff.gateway.tls.TlsServerCustomizer} seam — runs before the guard, but
 * {@code insecureRequestStrategy} is a method parameter computed earlier by
 * {@code HttpServerOptionsUtils.getInsecureRequestStrategy}, so no customizer can change it; a
 * customizer could avert the throw only by <em>supplying key material</em>, which the no-certificate
 * state by definition does not have. A {@code @Observes StartupEvent} audit — the seam
 * {@link de.cuioss.sheriff.gateway.tls.TerminatedListenerTlsAudit} uses — runs later still, after
 * the throw has already failed the boot. {@code quarkus.http.insecure-requests} is a runtime key
 * read from MicroProfile Config, so a higher-ordinal configuration source is the only lever left.
 * The mechanism is therefore forced, not chosen.
 * <p>
 * It must be a {@link ConfigSourceFactory} rather than a bare {@link ConfigSource} or the
 * {@link org.eclipse.microprofile.config.spi.ConfigSourceProvider} that
 * {@link NeutralTlsConfigSource} registers through: only a factory receives a
 * {@link ConfigSourceContext}, and reading the certificate keys while the configuration system is
 * still being built is the entire discriminator. A plain source has no way to ask.
 *
 * <h2>Why the ordinal is BELOW the environment source</h2>
 *
 * {@value #ORDINAL} sits above {@code application.properties} (250), so the shipped
 * {@code redirect} can be overridden, and deliberately below the environment-variable source (300),
 * so a deployment's {@code QUARKUS_HTTP_INSECURE_REQUESTS} still wins over the degrade.
 * <p>
 * That is the exact inverse of {@link NeutralTlsConfigSource#ORDINAL}, and it follows from the same
 * underlying rule rather than contradicting it. ADR-0025 classifies every server-TLS knob as policy,
 * deployment-bound, or build-time, and lets a source outrank the environment only for <em>policy</em>
 * — which is why {@link NeutralTlsConfigSource} may sit at 350 while projecting policy keys only.
 * {@code quarkus.http.insecure-requests} is classified <strong>deployment-bound exposure</strong>,
 * by {@code application.properties}, by
 * {@code SingleSourceTlsContractTest.SANCTIONED_EXCEPTIONS} and by {@code doc/configuration.adoc}'s
 * per-key verdict table. Outranking the deployment on a deployment-bound knob is precisely the
 * operator surprise the ordinal rule exists to prevent, so this source must lose to the environment.
 *
 * <h2>Declared versus resolved key material</h2>
 *
 * This source keys on <strong>declared key material</strong> — key material visible as configuration
 * keys at {@code ConfigSource} time, across the three routes a deployment can use. It cannot key on
 * anything else: {@code TlsConfigurationRegistry} is a CDI bean that does not exist yet while the
 * configuration system is being built.
 * <p>
 * {@link de.cuioss.sheriff.gateway.tls.TerminatedListenerTlsAudit} keys on <strong>resolved key
 * material</strong> — what the registry actually produced for the listener — and that is the
 * authoritative view. The two can disagree: a declared path that resolves to no usable key, or an
 * {@code HttpServerOptionsCustomizer} that supplies material the configuration never declared. Where
 * they do, {@code ApiSheriff-121} reports what the listener is really doing, and
 * {@code ApiSheriff-123} says so in its own text. The gap is bounded and observable rather than
 * silent; this source does not pretend to observe resolved material.
 *
 * <h2>Why it does not log</h2>
 *
 * A configuration source is constructed while the configuration system itself is being built, so
 * logging from here is unsafe — the same reason {@link NeutralTlsConfigSource} stays silent. The
 * degrade is reported once at startup as {@code ApiSheriff-123}, from the audit's startup path,
 * where a logger is safe to use and where the resolved view is available alongside the declared one.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PlainHttpDegradeConfigSourceFactory implements ConfigSourceFactory {

    /**
     * The ordinal of the projected source: above {@code application.properties} (250) so the shipped
     * {@code redirect} can be overridden, below the environment-variable source (300) so a
     * deployment keeps the last word on a deployment-bound knob. See the class Javadoc for why the
     * placement is the inverse of {@link NeutralTlsConfigSource#ORDINAL} and why that is consistent.
     */
    public static final int ORDINAL = 275;

    /**
     * The name the projected source reports. It is the discriminator
     * {@link de.cuioss.sheriff.gateway.tls.TerminatedListenerTlsAudit} matches against
     * {@link org.eclipse.microprofile.config.ConfigValue#getSourceName()} to decide whether the
     * degrade — rather than the deployment or the shipped default — is what supplied
     * {@value #INSECURE_REQUESTS}.
     */
    public static final String SOURCE_NAME = "PlainHttpDegradeConfigSource[no-certificate mode]";

    /** The single key this source ever projects. */
    public static final String INSECURE_REQUESTS = "quarkus.http.insecure-requests";

    /** The value projected onto {@value #INSECURE_REQUESTS} in no-certificate mode. */
    public static final String DEGRADED_VALUE = "enabled";

    /** Route 1 — the deployment selects a named TLS registry bucket for the main listener. */
    private static final String TLS_CONFIGURATION_NAME = "quarkus.http.tls-configuration-name";

    /** Route 3 — the legacy HTTP-SSL certificate keys, the route this gateway ships with. */
    private static final List<String> CERTIFICATE_KEYS = List.of(
            "quarkus.http.ssl.certificate.files",
            "quarkus.http.ssl.certificate.key-files",
            "quarkus.http.ssl.certificate.key-store-file",
            "quarkus.http.ssl.certificate.credentials-provider");

    /**
     * Route 2 — the DEFAULT TLS registry bucket. Named buckets
     * ({@code quarkus.tls.<name>.key-store.*}) deliberately do not match: only the default bucket is
     * consulted by {@code HttpServerOptionsUtils} when no configuration name is selected.
     */
    private static final String DEFAULT_KEY_STORE_PREFIX = "quarkus.tls.key-store.";

    /**
     * The PEM sub-bucket of {@value #DEFAULT_KEY_STORE_PREFIX}. It is a MAP keyed by an arbitrary
     * name, so its material can only be found by enumerating names — there is no fixed leaf to probe.
     */
    private static final String DEFAULT_PEM_PREFIX = "quarkus.tls.key-store.pem.";

    /** The material-bearing leaves of the PEM sub-bucket. */
    private static final List<String> PEM_MATERIAL_SUFFIXES = List.of(".cert", ".key");

    /** The material-bearing leaves of the keystore-file sub-buckets. */
    private static final List<String> KEY_STORE_MATERIAL_SUFFIXES = List.of(".p12.path", ".jks.path");

    /**
     * Folds every run of non-alphanumeric characters into a single {@code .} so a property name and
     * its environment-variable spelling compare equal. {@code quarkus.tls.key-store.p12.path} and
     * {@code QUARKUS_TLS_KEY__STORE_P12_PATH} both canonicalize to
     * {@code quarkus.tls.key.store.p12.path}, which is what lets the route-2 prefix test see a bucket
     * declared through either door. Direct lookups do not need this — SmallRye maps a requested name
     * onto its environment spelling itself — so it is used only where names are enumerated.
     */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    /**
     * Creates the factory. Invoked reflectively by the {@link java.util.ServiceLoader}.
     */
    public PlainHttpDegradeConfigSourceFactory() {
        // ServiceLoader requires an accessible no-argument constructor.
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        return declaresKeyMaterial(context) ? List.of() : List.of(new DegradeSource());
    }

    /**
     * Answers whether the deployment declares server key material by any of the three routes.
     * <p>
     * A blank value counts as no declaration: a key cleared to the empty string — the shape a test
     * profile or a {@code QUARKUS_HTTP_SSL_CERTIFICATE_FILES=} in a compose file produces — supplies
     * no material, and treating it as a declaration would leave the boot refusing for a certificate
     * nobody configured.
     */
    private static boolean declaresKeyMaterial(ConfigSourceContext context) {
        if (isDeclared(context, TLS_CONFIGURATION_NAME)) {
            return true;
        }
        for (String key : CERTIFICATE_KEYS) {
            if (isDeclared(context, key)) {
                return true;
            }
        }
        return declaresDefaultKeyStoreBucket(context);
    }

    private static boolean isDeclared(ConfigSourceContext context, String name) {
        ConfigValue candidate = context.getValue(name);
        return candidate != null && candidate.getValue() != null && !candidate.getValue().isBlank();
    }

    /**
     * Answers whether the DEFAULT TLS registry bucket carries key <em>material</em>.
     * <p>
     * The bucket has to be found by enumerating names rather than by probing a fixed key list,
     * because its PEM entry is a map keyed by an arbitrary name — {@code quarkus.tls.key-store.pem
     * .<name>.cert} — that no fixed probe could reach. What is enumerated is then narrowed to the
     * material-bearing leaves, and that narrowing is the whole correctness of this leg.
     * <p>
     * <strong>A bare prefix test over the enumerated names cannot work, and fails in the one
     * direction that matters.</strong> Quarkus contributes its own {@code @WithDefault} leaves of
     * this bucket to every boot — {@code quarkus.tls.key-store.sni},
     * {@code quarkus.tls.key-store.credentials-provider.password-key} and
     * {@code …alias-password-key} were all observed present in a boot that declared no bucket at
     * all. A prefix test therefore reports "material is declared" in <em>every</em> deployment,
     * which silences the degrade permanently and hands the no-certificate boot back the
     * {@code IllegalStateException} this seam exists to remove. The failure is invisible to a
     * hand-assembled {@code SmallRyeConfig}, because those framework defaults are not there — only a
     * real boot exhibits it.
     * <p>
     * A non-blank value is required on top of the leaf match, for the same reason
     * {@link #isDeclared(ConfigSourceContext, String)} requires one: a leaf cleared to the empty
     * string supplies no material.
     */
    private static boolean declaresDefaultKeyStoreBucket(ConfigSourceContext context) {
        String bucket = canonical(DEFAULT_KEY_STORE_PREFIX);
        String pem = canonical(DEFAULT_PEM_PREFIX);
        Iterator<String> names = context.iterateNames();
        while (names.hasNext()) {
            String name = names.next();
            String canonical = canonical(name);
            if (!canonical.startsWith(bucket)) {
                continue;
            }
            List<String> materialLeaves = canonical.startsWith(pem)
                    ? PEM_MATERIAL_SUFFIXES
                    : KEY_STORE_MATERIAL_SUFFIXES;
            if (endsWithAny(canonical, materialLeaves) && isDeclared(context, name)) {
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
     * The single-key source the factory contributes in no-certificate mode.
     */
    private static final class DegradeSource implements ConfigSource {

        private static final Map<String, String> PROJECTED =
                Map.of(INSECURE_REQUESTS, DEGRADED_VALUE);

        @Override
        public int getOrdinal() {
            return ORDINAL;
        }

        @Override
        public String getName() {
            return SOURCE_NAME;
        }

        @Override
        public Set<String> getPropertyNames() {
            return PROJECTED.keySet();
        }

        @Override
        public @Nullable String getValue(String propertyName) {
            return PROJECTED.get(propertyName);
        }

        @Override
        public Map<String, String> getProperties() {
            return PROJECTED;
        }
    }
}
