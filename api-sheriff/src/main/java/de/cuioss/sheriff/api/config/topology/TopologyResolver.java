/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.topology;

import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import de.cuioss.sheriff.api.config.load.EnvSecretResolver;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
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
 * Two alias sources are resolved, and they fail <em>asymmetrically</em>:
 * <ul>
 * <li>An <em>enabled</em> endpoint's {@code base_url} alias must resolve or the boot
 * fails: an unresolved one throws {@link TopologyResolutionException}.</li>
 * <li>An {@code additionalAliases} entry (the {@code tls.passthrough_sni} targets)
 * resolves regardless of endpoint enablement, because passthrough is a TLS-level
 * concern — but an unresolved one is <em>skipped silently</em> (omitted from the
 * result), never thrown. This asymmetry is deliberate: this resolver runs
 * <em>before</em>
 * {@link de.cuioss.sheriff.api.config.validation.ConfigValidator} in the boot
 * pipeline, so throwing here would abort the boot at step 6 and make the validator's
 * unresolved-passthrough-alias rule unreachable. Skipping leaves that validator rule
 * the sole reporter of the failure and keeps violation collection to a single pass.
 * A <em>malformed</em> (as opposed to unresolved) URL still throws from either
 * source.</li>
 * <li>A <em>disabled</em> endpoint's {@code base_url} alias remains exempt from
 * resolution entirely and need not resolve.</li>
 * </ul>
 * <p>
 * Framework-agnostic (ADR-0005): the substitution engine is constructor-injected.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TopologyResolver {

    private static final Pattern ALIAS = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

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
     * @param enabledEndpoints  the endpoints already filtered to those enabled
     * @param additionalAliases aliases to resolve independently of endpoint
     *                          enablement (the {@code tls.passthrough_sni} targets);
     *                          an entry that resolves to no value is skipped rather
     *                          than thrown, leaving
     *                          {@link de.cuioss.sheriff.api.config.validation.ConfigValidator}
     *                          to report it
     * @return the immutable resolved topology
     * @throws TopologyResolutionException when an alias referenced by an enabled
     *                                     endpoint is unresolved, or when any
     *                                     resolved alias carries a malformed URL
     */
    public ResolvedTopology resolve(Path topologyFile, List<EndpointConfig> enabledEndpoints,
            Collection<String> additionalAliases) {
        Map<String, String> fileAliases = readProperties(topologyFile);
        Map<String, ResolvedUpstream> resolved = new LinkedHashMap<>();
        for (EndpointConfig endpoint : enabledEndpoints) {
            String alias = endpoint.baseUrl();
            if (resolved.containsKey(alias)) {
                continue;
            }
            String value = resolveValue(alias, fileAliases);
            if (value == null) {
                throw new TopologyResolutionException(
                        "Unresolved topology alias '%s' referenced by enabled endpoint '%s'"
                                .formatted(alias, endpoint.id()));
            }
            resolved.put(alias, decompose(alias, value.trim()));
        }
        for (String alias : additionalAliases) {
            if (!resolved.containsKey(alias)) {
                String value = resolveValue(alias, fileAliases);
                if (value != null) {
                    resolved.put(alias, decompose(alias, value.trim()));
                }
            }
        }
        return new ResolvedTopology(resolved);
    }

    private @Nullable String resolveValue(String alias, Map<String, String> fileAliases) {
        String value = fileAliases.get(alias);
        if (value == null) {
            return null;
        }
        try {
            return resolver.resolve(value);
        } catch (EnvSecretResolver.MissingVariableException | EnvSecretResolver.MalformedPlaceholderException e) {
            throw new TopologyResolutionException(
                    "Cannot resolve placeholder in topology alias '%s': %s".formatted(alias, e.getMessage()), e);
        }
    }

    private static Map<String, String> readProperties(Path topologyFile) {
        if (!Files.isRegularFile(topologyFile)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(topologyFile)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new TopologyResolutionException("Cannot read topology file: " + topologyFile, e);
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (ALIAS.matcher(name).matches()) {
                aliases.put(name, properties.getProperty(name));
            }
        }
        return aliases;
    }

    private static ResolvedUpstream decompose(String alias, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new TopologyResolutionException("Malformed topology URL for alias '%s': %s".formatted(alias, url), e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new TopologyResolutionException(
                    "Topology URL for alias '%s' must be absolute with scheme and host: %s".formatted(alias, url));
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new TopologyResolutionException(
                    "Topology URL for alias '%s' must use an http or https scheme, but was '%s': %s"
                            .formatted(alias, scheme, url));
        }
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
        String basePath = uri.getPath() == null ? "" : uri.getPath();
        return new ResolvedUpstream(scheme, uri.getHost(), port, basePath);
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http" -> HTTP_PORT;
            case "https" -> HTTPS_PORT;
            default -> -1;
        };
    }

    /**
     * Signals that a topology alias could not be resolved or decomposed.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class TopologyResolutionException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        TopologyResolutionException(String message) {
            super(message);
        }

        TopologyResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
