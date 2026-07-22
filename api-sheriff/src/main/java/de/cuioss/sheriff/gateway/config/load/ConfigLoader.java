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
package de.cuioss.sheriff.gateway.config.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Reads, validates, and binds the file-based API Sheriff configuration.
 * <p>
 * {@link #load()} runs the boot pipeline over the configuration directory: it reads
 * {@code gateway.yaml} and every {@code endpoints/*.yaml} file (sorted for
 * deterministic error output), verifies that secret-classified fields are bare
 * {@code ${VAR}} references, substitutes every {@code ${VAR}} / {@code ${VAR:-default}}
 * placeholder (D4) <em>before</em> validating each substituted document against the
 * bundled JSON Schemas, and binds the valid trees to the immutable
 * {@link de.cuioss.sheriff.gateway.config.model} records. Every problem is collected —
 * never fail on the first — and raised together as a {@link ConfigLoadException}.
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
    private static final String ANCHORS_FIELD = "anchors";
    private static final String NAME_FIELD = "name";
    private static final String YAML_EXTENSION = ".yaml";
    private static final String YML_EXTENSION = ".yml";
    private static final int MAX_YAML_NESTING_DEPTH = 100;
    private static final int MAX_YAML_ALIASES = 50;
    private static final int MAX_YAML_STRING_LENGTH = 1024 * 1024;
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final List<String> SECRET_POINTERS = List.of(
            "/oidc/client_secret", "/oidc/session/encryption_key", "/oidc/session/previous_key");

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
     * @param secretResolver the {@code ${VAR}} / {@code ${VAR:-default}} placeholder
     *                       substitution engine
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
        validateSecretReferences(node, errors);
        substitute(node, GATEWAY_FILE, "", errors);
        if (hasErrorsFor(GATEWAY_FILE, errors)) {
            return null;
        }
        validate(gatewaySchema, node, GATEWAY_FILE, errors);
        if (hasErrorsFor(GATEWAY_FILE, errors)) {
            return null;
        }
        injectAnchorNames(node);
        return bind(node, GatewayConfig.class, GATEWAY_FILE, errors);
    }

    /**
     * Verifies that each secret-classified field, when present, is written as a bare
     * {@code ${VAR}} reference on its <em>pre-substitution</em> value — never a
     * literal or a defaulted placeholder (D4 secrets rule).
     */
    private void validateSecretReferences(JsonNode gatewayNode, List<ConfigError> errors) {
        for (String pointer : SECRET_POINTERS) {
            JsonNode secret = gatewayNode.at(pointer);
            if (secret.isTextual() && !secretResolver.isBareReference(secret.asText())) {
                errors.add(new ConfigError(GATEWAY_FILE, pointer,
                        "secret field must be a bare ${VAR} reference, not a literal or defaulted value"));
            }
        }
    }

    /**
     * Injects each anchor's map key as its {@code name} field so the anchor block —
     * which does not carry a {@code name} property in the file — binds to an
     * {@link de.cuioss.sheriff.gateway.config.model.AnchorConfig} whose name is
     * populated. Runs after schema validation so the injected key is not rejected by
     * the anchor block's {@code additionalProperties: false}.
     */
    private static void injectAnchorNames(JsonNode gatewayNode) {
        if (!(gatewayNode.get(ANCHORS_FIELD) instanceof ObjectNode anchors)) {
            return;
        }
        List<String> names = new ArrayList<>();
        anchors.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (anchors.get(name) instanceof ObjectNode anchor) {
                anchor.put(NAME_FIELD, name);
            }
        }
    }

    private List<EndpointConfig> loadEndpoints(List<ConfigError> errors) {
        List<EndpointConfig> endpoints = new ArrayList<>();
        for (Path path : listEndpointFiles(errors)) {
            EndpointConfig endpoint = loadEndpoint(path, errors);
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }
        return endpoints;
    }

    private @Nullable EndpointConfig loadEndpoint(Path path, List<ConfigError> errors) {
        String file = ENDPOINTS_DIR + "/" + path.getFileName();
        JsonNode root = readYaml(path, file, errors);
        if (root == null) {
            return null;
        }
        substitute(root, file, "", errors);
        if (hasErrorsFor(file, errors)) {
            return null;
        }
        validate(endpointSchema, root, file, errors);
        if (hasErrorsFor(file, errors)) {
            return null;
        }
        JsonNode block = root.get(ENDPOINT_ROOT);
        applyEnabledDefault(block);
        return bind(block, EndpointConfig.class, file, errors);
    }

    private List<Path> listEndpointFiles(List<ConfigError> errors) {
        Path dir = configDir.resolve(ENDPOINTS_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> regularFiles = entries.filter(Files::isRegularFile).toList();
            for (Path file : regularFiles) {
                if (file.getFileName().toString().endsWith(YML_EXTENSION)) {
                    errors.add(new ConfigError(ENDPOINTS_DIR + "/" + file.getFileName(), "",
                            "endpoint file has a '.yml' extension; rename it to '.yaml'"));
                }
            }
            return regularFiles.stream()
                    .filter(p -> p.getFileName().toString().endsWith(YAML_EXTENSION))
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
        if (!withinExpansionLimits(path, file, errors)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return mapper.readTree(reader);
        } catch (IOException e) {
            errors.add(new ConfigError(file, "", "cannot read configuration file: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Rejects a YAML alias-expansion (billion-laughs) or nesting bomb <em>before</em>
     * Jackson binds the document, via a SnakeYAML-native compose-only pass under the
     * same {@link LoaderOptions} as {@link #hardenedYamlFactory()}.
     * <p>
     * This pass is load-bearing, not redundant: Jackson's {@code YAMLParser} consumes
     * SnakeYAML's event stream directly and never runs the {@code Composer} — the one
     * layer that counts collection aliases against
     * {@link LoaderOptions#setMaxAliasesForCollections(int)} — so a
     * {@code mapper.readTree} call silently ignores the alias cap no matter how many
     * times a collection anchor is dereferenced. Composing the node graph here (without
     * constructing any Java objects, so no deserialization-gadget surface is opened)
     * forces the {@code Composer} to run and makes the alias-count and nesting-depth
     * caps actually fire. A tripped limit is reported as a collected {@link ConfigError}
     * so the boot fails with the aggregate rather than throwing fail-fast.
     *
     * @return {@code true} when the document is within the expansion / nesting limits,
     *         {@code false} when a limit tripped or the file could not be read (an error
     *         was recorded in either failing case)
     */
    private static boolean withinExpansionLimits(Path path, String file, List<ConfigError> errors) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        loaderOptions.setNestingDepthLimit(MAX_YAML_NESTING_DEPTH);
        loaderOptions.setCodePointLimit(MAX_YAML_STRING_LENGTH);
        try (Reader reader = Files.newBufferedReader(path)) {
            // Advancing the iterator forces the Composer to walk the node graph; the
            // alias-count and nesting-depth caps throw a YAMLException from next() if the
            // document is a bomb. The composed nodes themselves carry no information this
            // pass needs.
            Iterator<Node> documents = new Yaml(loaderOptions).composeAll(reader).iterator();
            while (documents.hasNext()) {
                documents.next();
            }
            return true;
        } catch (YAMLException e) {
            errors.add(new ConfigError(file, "",
                    "YAML expansion/nesting bomb protection tripped: " + e.getMessage()));
            return false;
        } catch (IOException e) {
            errors.add(new ConfigError(file, "", "cannot read configuration file: " + e.getMessage()));
            return false;
        }
    }

    private static void validate(JsonSchema schema, JsonNode node, String file, List<ConfigError> errors) {
        Set<ValidationMessage> messages = schema.validate(node);
        for (ValidationMessage message : messages) {
            errors.add(new ConfigError(file, message.getInstanceLocation().toString(), message.getMessage()));
        }
    }

    private void substitute(JsonNode node, String file, String pointer, List<ConfigError> errors) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                substituteChild(object.get(name), file, pointer + "/" + name, errors,
                        resolved -> object.set(name, resolved));
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                int position = index;
                substituteChild(array.get(index), file, pointer + "/" + index, errors,
                        resolved -> array.set(position, resolved));
            }
        }
    }

    private void substituteChild(JsonNode child, String file, String pointer, List<ConfigError> errors,
            Consumer<JsonNode> replacer) {
        if (child.isTextual() && secretResolver.hasReference(child.asText())) {
            try {
                replacer.accept(coerce(secretResolver.resolve(child.asText())));
            } catch (EnvSecretResolver.MissingVariableException | EnvSecretResolver.MalformedPlaceholderException e) {
                errors.add(new ConfigError(file, pointer, e.getMessage()));
            }
        } else {
            substitute(child, file, pointer, errors);
        }
    }

    /**
     * Re-types a substituted scalar so schema validation sees the natural JSON type
     * the value would have carried if written literally: {@code true}/{@code false}
     * bind to a boolean, an integer literal to an integer, everything else stays a
     * string. Only substituted scalars pass through here — a literal (unquoted) value
     * was already typed by the YAML parser.
     */
    private static JsonNode coerce(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return BooleanNode.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return BooleanNode.FALSE;
        }
        if (INTEGER.matcher(value).matches()) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                    return IntNode.valueOf((int) parsed);
                }
                return LongNode.valueOf(parsed);
            } catch (NumberFormatException e) {
                return TextNode.valueOf(value);
            }
        }
        return TextNode.valueOf(value);
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
            errors.add(new ConfigError(file, bindingErrorPointer(e),
                    "binding failed for %s: a value could not be bound to the expected type"
                            .formatted(type.getSimpleName())));
            return null;
        }
    }

    /**
     * Derives a JSON pointer from a Jackson mapping error's field path — field names
     * and array indices only, never the offending value — so a binding error is
     * locatable without ever echoing a resolved scalar (which may hold a secret).
     */
    private static String bindingErrorPointer(Exception e) {
        if (!(e instanceof JsonMappingException mappingException)) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        for (JsonMappingException.Reference reference : mappingException.getPath()) {
            if (reference.getFieldName() != null) {
                pointer.append('/').append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                pointer.append('/').append(reference.getIndex());
            }
        }
        return pointer.toString();
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
        return YAMLMapper.builder(hardenedYamlFactory())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new Jdk8Module())
                .addModule(module)
                .build();
    }

    /**
     * Builds a YAML factory hardened against expansion / nesting bombs: SnakeYAML's
     * nesting-depth and code-point limits (both enforced at the event-parser level
     * Jackson drives) plus Jackson's {@link StreamReadConstraints} nesting-depth and
     * string-length caps (defence-in-depth over untrusted-but-local configuration
     * input).
     * <p>
     * The alias-expansion cap ({@link LoaderOptions#setMaxAliasesForCollections(int)})
     * is set here for forward-compatibility, but is <em>not</em> enforced on this path:
     * Jackson's {@code YAMLParser} consumes SnakeYAML's event stream directly and never
     * invokes the {@code Composer}, the only layer that counts collection aliases. The
     * effective alias-bomb guard is the separate compose-only pre-pass in
     * {@link #withinExpansionLimits}, which runs before {@code readTree}.
     */
    private static YAMLFactory hardenedYamlFactory() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        loaderOptions.setNestingDepthLimit(MAX_YAML_NESTING_DEPTH);
        loaderOptions.setCodePointLimit(MAX_YAML_STRING_LENGTH);
        return YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_YAML_NESTING_DEPTH)
                        .maxStringLength(MAX_YAML_STRING_LENGTH)
                        .build())
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
            JsonNode enabled = blockNode.get(ENABLED_FIELD);
            return enabled == null || enabled.asBoolean(true);
        }
    }
}
