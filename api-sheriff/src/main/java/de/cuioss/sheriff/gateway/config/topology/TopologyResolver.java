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
package de.cuioss.sheriff.gateway.config.topology;

import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.DefaultedPlaceholder;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import org.jspecify.annotations.Nullable;

/**
 * Resolves topology aliases to decomposed upstreams for enabled endpoints and for the
 * supplied additional aliases (pipeline step 6).
 * <p>
 * Each alias (pattern {@code [A-Z][A-Z0-9_]*}) is read from the
 * {@code topology.properties} file, run through the same {@code ${VAR}} /
 * {@code ${VAR:-default}} substitution engine (D4) as the YAML documents, validated as
 * a well-formed absolute URL, and decomposed once into a {@link ResolvedUpstream}
 * (ADR-0004). There is no convention-named {@code TOPOLOGY_<ALIAS>} environment
 * precedence path — environment values reach a topology value only through an explicit
 * in-file {@code ${VAR}} placeholder.
 * <p>
 * Every alias is resolved in <em>one pass</em>: each failure is collected as a
 * {@link ConfigError} with file {@code topology.properties} and the alias as pointer, and
 * the pass ends by throwing one {@link TopologyResolutionException} carrying every
 * collected failure, in resolution order — so an operator sees every topology problem in
 * one attempt rather than one per restart. Two alias sources are resolved, and they fail
 * <em>asymmetrically</em> (ADR-0009):
 * <ul>
 * <li>An <em>enabled</em> endpoint's declared {@code base_url} alias must resolve or the
 * boot fails: an unresolved one is collected as a failure. An endpoint that declares no
 * {@code base_url} is skipped — {@code base_url} is mandatory only for an endpoint
 * carrying a proxy route, and that conditional rule is reported by
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator}, not here (an
 * asset-only or redirect-only endpoint legitimately has no alias to resolve).</li>
 * <li>An {@code additionalAliases} entry (the {@code tls.passthrough_sni} targets)
 * resolves regardless of endpoint enablement, because passthrough is a TLS-level
 * concern — but an unresolved one is <em>skipped silently</em> (omitted from the
 * result), never collected. This asymmetry is deliberate: this resolver runs
 * <em>before</em>
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} in the boot
 * pipeline, so failing here would abort the boot at step 6 and make the validator's
 * unresolved-passthrough-alias rule unreachable. Skipping leaves that validator rule
 * the sole reporter of the failure and keeps violation collection to a single pass.
 * An unresolvable placeholder or a <em>malformed</em> (as opposed to unresolved) URL is
 * still collected from either source.</li>
 * <li>A <em>disabled</em> endpoint's {@code base_url} alias remains exempt from
 * resolution entirely and need not resolve.</li>
 * </ul>
 * <p>
 * {@link #resolve(Path, List, Collection, Consumer)} additionally reports every
 * {@code ${VAR:-default}} placeholder that fell back to its in-file default, as a
 * {@link DefaultedPlaceholder} naming {@code topology.properties}, the alias and the
 * variable — never a value — and only for aliases it actually resolves.
 * <p>
 * Framework-agnostic (ADR-0005): the substitution engine is constructor-injected.
 * Instances are stateless between calls and safe to reuse.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TopologyResolver {

    private static final Pattern ALIAS = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final String TOPOLOGY_FILE = "topology.properties";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final Consumer<DefaultedPlaceholder> IGNORE_DEFAULTED = defaulted -> {
        // The three-argument resolve overload does not report applied in-file defaults.
    };

    private final EnvSecretResolver resolver;

    /**
     * Creates a resolver backed by a {@link EnvSecretResolver} over the process
     * environment ({@link System#getenv(String)}).
     */
    public TopologyResolver() {
        this(new EnvSecretResolver());
    }

    /**
     * Creates a resolver backed by the supplied substitution engine.
     *
     * @param resolver the {@code ${VAR}} / {@code ${VAR:-default}} substitution engine
     *                 applied to each topology value before decomposition
     */
    public TopologyResolver(EnvSecretResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Resolves and decomposes the topology aliases referenced by the enabled
     * endpoints together with the supplied additional aliases.
     *
     * @param topologyFile      the {@code topology.properties} file (may be absent)
     * @param enabledEndpoints  the endpoints already filtered to those enabled; an endpoint
     *                          declaring no {@code base_url} contributes no alias
     * @param additionalAliases aliases to resolve independently of endpoint
     *                          enablement (the {@code tls.passthrough_sni} targets);
     *                          an entry that resolves to no value is skipped rather
     *                          than collected, leaving
     *                          {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator}
     *                          to report it
     * @return the immutable resolved topology
     * @throws TopologyResolutionException carrying every failure of the pass: each alias
     *                                     referenced by an enabled endpoint that is
     *                                     unresolved, each resolved alias whose placeholder
     *                                     cannot be resolved or whose URL is malformed, and
     *                                     an unreadable topology file
     */
    public ResolvedTopology resolve(Path topologyFile, List<EndpointConfig> enabledEndpoints,
            Collection<String> additionalAliases) {
        return resolve(topologyFile, enabledEndpoints, additionalAliases, IGNORE_DEFAULTED);
    }

    /**
     * Resolves and decomposes the topology aliases exactly as
     * {@link #resolve(Path, List, Collection)} does, and reports every
     * {@code ${VAR:-default}} placeholder that fell back to its in-file default.
     * <p>
     * Each fallback reaches {@code defaultedSink} as one
     * {@code DefaultedPlaceholder("topology.properties", <ALIAS>, <VAR>)} — never the default
     * or any value — only for an alias this pass actually resolves, so a disabled endpoint's
     * alias and an absent additional alias are never reported. Entries are emitted in
     * resolution order and whether or not the pass later fails.
     *
     * @param topologyFile      the {@code topology.properties} file (may be absent)
     * @param enabledEndpoints  the endpoints already filtered to those enabled
     * @param additionalAliases aliases to resolve independently of endpoint enablement
     * @param defaultedSink     receives one entry per variable whose in-file default was
     *                          applied, per alias
     * @return the immutable resolved topology
     * @throws TopologyResolutionException carrying every failure of the pass, as for
     *                                     {@link #resolve(Path, List, Collection)}
     * @since 1.0
     */
    public ResolvedTopology resolve(Path topologyFile, List<EndpointConfig> enabledEndpoints,
            Collection<String> additionalAliases, Consumer<DefaultedPlaceholder> defaultedSink) {
        Objects.requireNonNull(defaultedSink, "defaultedSink");
        ResolutionPass pass = new ResolutionPass(readProperties(topologyFile), defaultedSink);
        for (EndpointConfig endpoint : enabledEndpoints) {
            pass.resolveEnabled(endpoint);
        }
        for (String alias : additionalAliases) {
            pass.resolveAdditional(alias);
        }
        if (!pass.errors.isEmpty()) {
            throw new TopologyResolutionException(pass.errors);
        }
        return new ResolvedTopology(pass.resolved);
    }

    private static Map<String, String> readProperties(Path topologyFile) {
        if (!Files.isRegularFile(topologyFile)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(topologyFile)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new TopologyResolutionException(
                    List.of(new ConfigError(TOPOLOGY_FILE, "", "Cannot read topology file: " + topologyFile)), e);
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (ALIAS.matcher(name).matches()) {
                aliases.put(name, properties.getProperty(name));
            }
        }
        return aliases;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> HTTP_PORT;
            case "https" -> HTTPS_PORT;
            default -> -1;
        };
    }

    /**
     * The state of one {@link #resolve(Path, List, Collection, Consumer)} call: the aliases
     * read from the file, the upstreams resolved so far, every collected failure, and the
     * sink receiving each applied in-file default. Each alias is attempted at most once per
     * pass, so an alias shared by several endpoints — or by an endpoint and an additional
     * alias — is resolved, reported and refused only once.
     */
    private final class ResolutionPass {

        private final Map<String, String> fileAliases;
        private final Consumer<DefaultedPlaceholder> defaultedSink;
        private final Map<String, ResolvedUpstream> resolved = new LinkedHashMap<>();
        private final Set<String> attempted = new HashSet<>();
        private final List<ConfigError> errors = new ArrayList<>();

        ResolutionPass(Map<String, String> fileAliases, Consumer<DefaultedPlaceholder> defaultedSink) {
            this.fileAliases = fileAliases;
            this.defaultedSink = defaultedSink;
        }

        void resolveEnabled(EndpointConfig endpoint) {
            String alias = endpoint.baseUrl();
            if (alias == null || !attempted.add(alias)) {
                return;
            }
            String value = fileAliases.get(alias);
            if (value == null) {
                fail(alias, "Unresolved topology alias '%s' referenced by enabled endpoint '%s'"
                        .formatted(alias, endpoint.id()));
                return;
            }
            resolveAndDecompose(alias, value);
        }

        void resolveAdditional(String alias) {
            if (!attempted.add(alias)) {
                return;
            }
            String value = fileAliases.get(alias);
            if (value != null) {
                resolveAndDecompose(alias, value);
            }
        }

        private void resolveAndDecompose(String alias, String rawValue) {
            String value;
            try {
                value = resolver.resolve(rawValue,
                        name -> defaultedSink.accept(new DefaultedPlaceholder(TOPOLOGY_FILE, alias, name)));
            } catch (EnvSecretResolver.MissingVariableException
                    | EnvSecretResolver.MalformedPlaceholderException e) {
                fail(alias, "Cannot resolve placeholder in topology alias '%s': %s".formatted(alias, e.getMessage()));
                return;
            }
            ResolvedUpstream upstream = decompose(alias, value.trim());
            if (upstream != null) {
                resolved.put(alias, upstream);
            }
        }

        private @Nullable ResolvedUpstream decompose(String alias, String url) {
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException _) {
                fail(alias, "Malformed topology URL for alias '%s'".formatted(alias));
                return null;
            }
            if (uri.getScheme() == null || uri.getHost() == null) {
                fail(alias, "Topology URL for alias '%s' must be absolute with scheme and host".formatted(alias));
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                fail(alias, "Topology URL for alias '%s' must use an http or https scheme, but was '%s'"
                        .formatted(alias, scheme));
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
            String basePath = uri.getPath() == null ? "" : uri.getPath();
            return new ResolvedUpstream(scheme, uri.getHost(), port, basePath);
        }

        private void fail(String alias, String message) {
            errors.add(new ConfigError(TOPOLOGY_FILE, alias, message));
        }
    }

    /**
     * Signals that one or more topology aliases could not be resolved or decomposed, or
     * that the topology file could not be read.
     * <p>
     * It carries every failure of one resolution pass as {@link ConfigError}s with file
     * {@code topology.properties} and the alias as pointer (an empty pointer for an
     * unreadable file), in resolution order.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class TopologyResolutionException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final List<ConfigError> errors;

        TopologyResolutionException(List<ConfigError> errors) {
            this(errors, null);
        }

        TopologyResolutionException(List<ConfigError> errors, @Nullable Throwable cause) {
            super(buildMessage(errors), cause);
            this.errors = List.copyOf(errors);
        }

        /**
         * Returns every topology failure of the resolution pass.
         *
         * @return the non-empty, unmodifiable list of failures in resolution order
         */
        public List<ConfigError> errors() {
            return errors;
        }

        private static String buildMessage(List<ConfigError> errors) {
            return "Topology resolution failed with %d error(s):%n%s".formatted(errors.size(),
                    errors.stream()
                            .map(e -> "  - %s [%s]: %s".formatted(e.file(), e.pointer(), e.message()))
                            .collect(Collectors.joining(System.lineSeparator())));
        }
    }
}
