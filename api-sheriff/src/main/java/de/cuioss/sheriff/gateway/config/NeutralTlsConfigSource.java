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
package de.cuioss.sheriff.gateway.config;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.tools.logging.CuiLogger;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Projects the neutral {@code tls} and {@code management} policy blocks of {@code gateway.yaml} onto
 * the {@code quarkus.*} keys that no other seam can reach.
 * <p>
 * <strong>Why a {@code ConfigSource} at all.</strong> The terminated main HTTPS listener is
 * configured through {@code HttpServerOptionsCustomizer}, which Quarkus enumerates from the CDI
 * container after building the SSL options — that is the seam
 * {@link de.cuioss.sheriff.gateway.tls.TlsServerCustomizer} uses. The <em>management</em> interface
 * never enumerates customizers, so that seam is unavailable there; the only remaining lever on the
 * management listener is the configuration the recorder reads. Hence this source.
 * <p>
 * <strong>Why the ordinal must beat the environment.</strong> The ordinal is deliberately above the
 * environment-variable source's 300, because a policy this source projects has to win over the same
 * knob supplied as a deployment environment variable — otherwise a policy named in
 * {@code gateway.yaml} would silently not take effect. That is safe only because of the
 * policy-keys-only constraint below: no deployment-bound knob is ever projected, so nothing an
 * operator sets in the environment can be outranked by surprise.
 * <p>
 * <strong>Hard constraint — never project {@code quarkus.tls.key-store.*} (or any other key
 * material into the DEFAULT TLS registry bucket).</strong> {@code HttpServerOptionsUtils}'
 * management-interface lookup falls back to {@code registry.getDefault()} when
 * {@code quarkus.management.tls-configuration-name} is absent, and it takes that fallback only when
 * the default bucket actually carries key material (upstream quarkus issue 43380). This project's
 * default bucket is empty — the server certificate arrives through
 * {@code quarkus.http.ssl.certificate.*} — so management TLS today is exactly what
 * {@code quarkus.management.ssl.certificate.*} says it is. Populating the default bucket from here
 * would silently switch the management interface to HTTPS through a path no configuration file
 * mentions.
 * <p>
 * <strong>Why a second, lower-ordinal source is forbidden.</strong> Outranking the environment for
 * <em>every</em> key would be an operator surprise, and the obvious fix — a second source at a lower
 * ordinal for the keys the environment should still win — is explicitly rejected by ADR-0025. The
 * constraint that makes the split unnecessary is that this source projects <em>policy keys only</em>:
 * it never projects a port, never projects trust material, and never projects key material, so no
 * deployment-bound knob can be outranked in the first place. A single ordinal is therefore both
 * sufficient and safer.
 * <p>
 * <strong>Why it cannot read {@link de.cuioss.sheriff.gateway.config.model.GatewayConfig}.</strong> A
 * {@code ConfigSource} is instantiated while the configuration system itself is being built, long
 * before CDI exists — so the bound model, its loader, and its validator are all unavailable. This
 * class therefore re-reads {@code gateway.yaml} directly with SnakeYAML and parses only the two
 * policy blocks it projects from. That is a deliberate, bounded duplication of a small slice of
 * parsing, not a second configuration pipeline: the authoritative parse, schema validation and
 * semantic validation still happen once, later, in {@code ConfigLoader} and {@code ConfigValidator}.
 * A malformed or absent document is silently ignored here, because failing the boot is that pipeline's
 * job and doing it twice would produce a worse, earlier, context-free error.
 * <p>
 * At this stage the projected key set is <em>empty for every well-formed document</em>: this class
 * delivers the seam, the parse and the ordinal.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class NeutralTlsConfigSource implements ConfigSource {

    /**
     * The ordinal of this source. Above the environment-variable source's 300 so a policy named in
     * {@code gateway.yaml} is not silently overridden by the same knob supplied as a deployment
     * environment variable; see the class Javadoc for why a single ordinal above environment is both
     * required and safe.
     */
    public static final int ORDINAL = 350;

    private static final String NAME = "NeutralTlsConfigSource[gateway.yaml]";
    private static final String CONFIG_DIR_PROPERTY = "sheriff.config.dir";
    private static final String CONFIG_DIR_ENV = "SHERIFF_CONFIG_DIR";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String GATEWAY_FILE = "gateway.yaml";
    /** The only two blocks this source ever reads — the neutral TLS and management policy blocks. */
    private static final List<String> POLICY_BLOCKS = List.of("tls", "management");
    private static final int MAX_YAML_NESTING_DEPTH = 100;
    private static final int MAX_YAML_ALIASES = 50;
    private static final int MAX_YAML_STRING_LENGTH = 1024 * 1024;

    private static final CuiLogger LOGGER = new CuiLogger(NeutralTlsConfigSource.class);

    private final Map<String, String> projected;

    /**
     * Reads {@code gateway.yaml} from the resolved configuration directory and computes the projected
     * key set once. The projection is immutable for the lifetime of the source, matching the
     * gateway's read-once-at-startup configuration model.
     */
    public NeutralTlsConfigSource() {
        this(resolveConfigDir());
    }

    /**
     * Creates a source reading {@code gateway.yaml} from an explicit directory.
     *
     * @param configDir the directory holding {@code gateway.yaml}
     */
    public NeutralTlsConfigSource(Path configDir) {
        this.projected = project(readPolicyBlocks(configDir));
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Set<String> getPropertyNames() {
        return projected.keySet();
    }

    @Override
    public @Nullable String getValue(String propertyName) {
        return projected.get(propertyName);
    }

    @Override
    public Map<String, String> getProperties() {
        return projected;
    }

    /**
     * Computes the keys this source projects from the parsed policy blocks.
     * <p>
     * No key is projected for any well-formed document at this stage. The method exists now so the
     * seam, the ordinal and the parse are in place and independently testable.
     */
    private static Map<String, String> project(@Nullable Map<String, Object> policyBlocks) {
        if (policyBlocks == null || policyBlocks.isEmpty()) {
            return Map.of();
        }
        return Map.of();
    }

    /**
     * Parses {@code gateway.yaml} and returns only the {@code tls} and {@code management} blocks.
     * Returns {@code null} when the document is absent or cannot be parsed — the authoritative
     * loader reports those failures with full context, so this early pass stays silent.
     */
    private static @Nullable Map<String, Object> readPolicyBlocks(Path configDir) {
        Path gateway = configDir.resolve(GATEWAY_FILE);
        if (!Files.isRegularFile(gateway)) {
            LOGGER.debug("No %s under %s — projecting no key", GATEWAY_FILE, configDir);
            return null;
        }
        try (Reader reader = Files.newBufferedReader(gateway)) {
            Object document = new Yaml(hardenedLoaderOptions()).load(reader);
            if (!(document instanceof Map<?, ?> root)) {
                return null;
            }
            return policyBlocksOf(root);
        }
        /*TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
        catch (Exception e) {
            // Deliberately broad and deliberately silent at DEBUG: any parse or I/O failure here is
            // re-encountered by ConfigLoader moments later, which reports it as a collected,
            // located ConfigError and fails the boot. Rethrowing would replace that precise
            // diagnostic with a context-free failure from inside config-system construction.
            LOGGER.debug(e, "Cannot pre-read %s — projecting no key", gateway);
            return null;
        }
    }

    private static Map<String, Object> policyBlocksOf(Map<?, ?> root) {
        Map<String, Object> blocks = new HashMap<>(POLICY_BLOCKS.size());
        for (String block : POLICY_BLOCKS) {
            if (root.get(block) instanceof Map<?, ?> value) {
                blocks.put(block, value);
            }
        }
        return blocks;
    }

    /**
     * Resolves the configuration directory the same way the boot pipeline does, but without the
     * configuration system — which is still under construction when this source is created. The
     * system property and the environment variable are read directly; the default matches
     * {@code application.properties}' {@code sheriff.config.dir=config}.
     */
    private static Path resolveConfigDir() {
        String configured = System.getProperty(CONFIG_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CONFIG_DIR_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_CONFIG_DIR;
        }
        return Path.of(configured);
    }

    /**
     * The same YAML expansion / nesting bomb caps the authoritative loader applies, so this earlier
     * pass cannot be turned into a denial-of-service vector by a document the loader would reject.
     */
    private static LoaderOptions hardenedLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        options.setNestingDepthLimit(MAX_YAML_NESTING_DEPTH);
        options.setCodePointLimit(MAX_YAML_STRING_LENGTH);
        return options;
    }

    /**
     * Discovers {@link NeutralTlsConfigSource} for both the JVM and the native image.
     * <p>
     * Registration goes through a provider rather than listing the source directly under
     * {@code META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource} so the source is
     * constructed when the configuration system asks for it — at which point the
     * {@code sheriff.config.dir} system property or environment variable it resolves is already
     * observable.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class Provider implements ConfigSourceProvider {

        /**
         * Creates the provider. Invoked reflectively by the {@link java.util.ServiceLoader}.
         */
        public Provider() {
            // ServiceLoader requires an accessible no-argument constructor.
        }

        @Override
        public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
            return List.of(new NeutralTlsConfigSource());
        }
    }
}
