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
package de.cuioss.sheriff.api.config.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;
import org.jspecify.annotations.Nullable;

/**
 * Reads, validates, and binds the file-based API Sheriff configuration.
 * <p>
 * {@link #load()} runs the boot pipeline over the configuration directory: it reads
 * {@code gateway.yaml} and every {@code endpoints/*.yaml} file (sorted for
 * deterministic error output), validates each document against the bundled D2 JSON
 * Schemas, resolves {@code ${ENV_VAR}} secret references, and binds the valid trees
 * to the immutable {@link de.cuioss.sheriff.api.config.model} records. Every problem
 * is collected — never fail on the first — and raised together as a
 * {@link ConfigLoadException}.
 * <p>
 * The endpoint-enablement resolution, topology-alias resolution, cross-cutting
 * semantic validation, and route-table assembly steps of the full boot sequence are
 * layered on by later deliverables; this loader owns steps 1-4 (read,
 * schema-validate, secret-resolve, bind) and produces the bound model.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> The configuration directory
 * and the {@link EnvSecretResolver} are constructor-injected; the loader carries no
 * framework imports. Instances are stateless between {@link #load()} calls and safe
 * to reuse.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigLoader {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String ENDPOINTS_DIR = "endpoints";
    private static final String ENDPOINT_ROOT = "endpoint";
    private static final String ENABLED_FIELD = "enabled";

    private final Path configDir;
    private final EnvSecretResolver secretResolver;
    private final ObjectMapper mapper;
    private final JsonSchema gatewaySchema;
    private final JsonSchema endpointSchema;

    /**
     * Creates a loader for the given configuration directory.
     *
     * @param configDir      the directory holding {@code gateway.yaml} and the
     *                       {@code endpoints/} subdirectory
     * @param secretResolver the resolver for {@code ${ENV_VAR}} secret references
     */
    public ConfigLoader(Path configDir, EnvSecretResolver secretResolver) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.mapper = buildMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.gatewaySchema = loadSchema(factory, "/schema/gateway.schema.json");
        this.endpointSchema = loadSchema(factory, "/schema/endpoint.schema.json");
    }

    /**
     * Loads and binds the configuration.
     *
     * @return the bound gateway document and endpoints
     * @throws ConfigLoadException aggregating every schema, secret, and binding
     *                             error discovered in this pass
     */
    public LoadedConfig load() throws ConfigLoadException {
        List<ConfigError> errors = new ArrayList<>();

        GatewayConfig gateway = loadGateway(errors);
        List<EndpointConfig> endpoints = loadEndpoints(errors);

        if (!errors.isEmpty()) {
            throw new ConfigLoadException(errors);
        }
        return new LoadedConfig(Objects.requireNonNull(gateway, "gateway"), List.copyOf(endpoints));
    }

    private @Nullable GatewayConfig loadGateway(List<ConfigError> errors) {
        JsonNode node = readYaml(configDir.resolve(GATEWAY_FILE), GATEWAY_FILE, errors);
        if (node == null) {
            return null;
        }
        validate(gatewaySchema, node, GATEWAY_FILE, errors);
        resolveSecrets(node, GATEWAY_FILE, "", errors);
        if (hasErrorsFor(GATEWAY_FILE, errors)) {
            return null;
        }
        return bind(node, GatewayConfig.class, GATEWAY_FILE, errors);
    }

    private List<EndpointConfig> loadEndpoints(List<ConfigError> errors) {
        List<EndpointConfig> endpoints = new ArrayList<>();
        for (Path path : listEndpointFiles(errors)) {
            String file = ENDPOINTS_DIR + "/" + path.getFileName();
            JsonNode root = readYaml(path, file, errors);
            if (root == null) {
                continue;
            }
            validate(endpointSchema, root, file, errors);
            resolveSecrets(root, file, "", errors);
            if (hasErrorsFor(file, errors)) {
                continue;
            }
            JsonNode block = root.get(ENDPOINT_ROOT);
            applyEnabledDefault(block);
            EndpointConfig endpoint = bind(block, EndpointConfig.class, file, errors);
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }
        return endpoints;
    }

    private List<Path> listEndpointFiles(List<ConfigError> errors) {
        Path dir = configDir.resolve(ENDPOINTS_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            errors.add(new ConfigError(ENDPOINTS_DIR, "", "cannot list endpoint files: " + e.getMessage()));
            return List.of();
        }
    }

    private @Nullable JsonNode readYaml(Path path, String file, List<ConfigError> errors) {
        if (!Files.isRegularFile(path)) {
            errors.add(new ConfigError(file, "", "configuration file not found"));
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return mapper.readTree(reader);
        } catch (IOException e) {
            errors.add(new ConfigError(file, "", "cannot read configuration file: " + e.getMessage()));
            return null;
        }
    }

    private static void validate(JsonSchema schema, JsonNode node, String file, List<ConfigError> errors) {
        Set<ValidationMessage> messages = schema.validate(node);
        for (ValidationMessage message : messages) {
            errors.add(new ConfigError(file, message.getInstanceLocation().toString(), message.getMessage()));
        }
    }

    private void resolveSecrets(JsonNode node, String file, String pointer, List<ConfigError> errors) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                resolveChild(object.get(name), file, pointer + "/" + name, errors,
                        resolved -> object.put(name, resolved));
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                int position = index;
                resolveChild(array.get(index), file, pointer + "/" + index, errors,
                        resolved -> array.set(position, TextNode.valueOf(resolved)));
            }
        }
    }

    private void resolveChild(JsonNode child, String file, String pointer, List<ConfigError> errors,
            Consumer<String> replacer) {
        if (child.isTextual() && secretResolver.hasReference(child.asText())) {
            try {
                replacer.accept(secretResolver.resolve(child.asText()));
            } catch (EnvSecretResolver.MissingVariableException e) {
                errors.add(new ConfigError(file, pointer, e.getMessage()));
            }
        } else {
            resolveSecrets(child, file, pointer, errors);
        }
    }

    private static void applyEnabledDefault(JsonNode endpointBlock) {
        if (endpointBlock instanceof ObjectNode object && !object.has(ENABLED_FIELD)) {
            object.put(ENABLED_FIELD, true);
        }
    }

    private <T> @Nullable T bind(JsonNode node, Class<T> type, String file, List<ConfigError> errors) {
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException | IllegalArgumentException e) {
            errors.add(new ConfigError(file, "", "binding failed: " + e.getMessage()));
            return null;
        }
    }

    private static boolean hasErrorsFor(String file, List<ConfigError> errors) {
        return errors.stream().anyMatch(error -> error.file().equals(file));
    }

    private static ObjectMapper buildMapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(UpstreamDefaultsConfig.class, new UpstreamDefaultsDeserializer());
        // Register the Jdk8 module explicitly (Optional support) rather than via
        // findAndAddModules()'s ServiceLoader discovery, which does not resolve in a
        // native image and leaves Optional-typed record components unbindable.
        return YAMLMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new Jdk8Module())
                .addModule(module)
                .build();
    }

    private static JsonSchema loadSchema(JsonSchemaFactory factory, String resource) {
        try (InputStream stream = ConfigLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled schema resource: " + resource);
            }
            return factory.getSchema(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled schema resource: " + resource, e);
        }
    }

    /**
     * The bound configuration produced by {@link #load()}.
     *
     * @param gateway   the bound global {@code gateway.yaml} document
     * @param endpoints the bound endpoint documents in deterministic (file-sorted)
     *                  order, empty when none are declared
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LoadedConfig(GatewayConfig gateway, List<EndpointConfig> endpoints) {

        /**
         * Canonical constructor requiring {@code gateway} and defensively copying
         * {@code endpoints}.
         */
        public LoadedConfig {
            Objects.requireNonNull(gateway, "gateway");
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }
    }

    /**
     * Binds the nested {@code upstream_defaults} YAML block
     * ({@code {retry:{enabled}, not_modified:{enabled}}}) to the flat
     * {@link UpstreamDefaultsConfig} record, defaulting each toggle to {@code true}
     * when absent.
     */
    private static final class UpstreamDefaultsDeserializer extends JsonDeserializer<UpstreamDefaultsConfig>
            implements Serializable {

        private static final long serialVersionUID = 1L;

        @Override
        public UpstreamDefaultsConfig deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.readValueAsTree();
            return new UpstreamDefaultsConfig(readEnabled(node, "retry"), readEnabled(node, "not_modified"));
        }

        private static boolean readEnabled(JsonNode node, String block) {
            JsonNode blockNode = node.get(block);
            if (blockNode == null) {
                return true;
            }
            JsonNode enabled = blockNode.get("enabled");
            return enabled == null || enabled.asBoolean(true);
        }
    }
}
