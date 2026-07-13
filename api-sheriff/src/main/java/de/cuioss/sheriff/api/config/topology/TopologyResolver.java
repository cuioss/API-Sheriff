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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import org.jspecify.annotations.Nullable;

/**
 * Resolves topology aliases to decomposed upstreams for enabled endpoints (pipeline
 * step 6).
 * <p>
 * Each alias (pattern {@code [A-Z][A-Z0-9_]*}) is resolved with the precedence
 * {@code TOPOLOGY_<ALIAS>} environment variable over the {@code topology.properties}
 * file value, validated as a well-formed absolute URL, and decomposed once into a
 * {@link ResolvedUpstream} (ADR-0004). Only aliases referenced by <em>enabled</em>
 * endpoints are resolved; an alias referenced by an enabled endpoint that resolves
 * to neither an environment nor a file value is a boot failure, while a disabled
 * endpoint's alias need not resolve.
 * <p>
 * Framework-agnostic (ADR-0005): the environment lookup is constructor-injected.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TopologyResolver {

    private static final Pattern ALIAS = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private final UnaryOperator<@Nullable String> environment;

    /**
     * Creates a resolver backed by the process environment
     * ({@link System#getenv(String)}).
     */
    public TopologyResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the supplied environment lookup.
     *
     * @param environment maps an environment-variable name to its value, or
     *                    {@code null} when undefined
     */
    public TopologyResolver(UnaryOperator<@Nullable String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Resolves and decomposes the topology aliases referenced by the enabled
     * endpoints.
     *
     * @param topologyFile     the {@code topology.properties} file (may be absent)
     * @param enabledEndpoints the endpoints already filtered to those enabled
     * @return the immutable resolved topology
     * @throws TopologyResolutionException when a referenced alias is unresolved or
     *                                     resolves to a malformed URL
     */
    public ResolvedTopology resolve(Path topologyFile, List<EndpointConfig> enabledEndpoints) {
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
        return new ResolvedTopology(resolved);
    }

    private @Nullable String resolveValue(String alias, Map<String, String> fileAliases) {
        String override = environment.apply("TOPOLOGY_" + alias);
        if (override != null) {
            return override;
        }
        return fileAliases.get(alias);
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
