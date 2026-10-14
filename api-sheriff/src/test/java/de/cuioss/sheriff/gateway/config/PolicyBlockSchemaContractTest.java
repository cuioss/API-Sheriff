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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.cuioss.sheriff.gateway.config.PolicyBlock.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds every {@link PolicyBlock} descriptor to the two bundled JSON Schemas: for every block of
 * {@link PolicyBlock#ALL} and every {@link Scope}, the descriptor declares the scope exactly when the
 * schema declares the block's key at that scope's location — in both directions.
 * <p>
 * A block is declared once in Java and once more in each schema it can appear in, and nothing else
 * ties the two together: a key added to a schema location without a descriptor scope parses at load
 * and is then never resolved, and a descriptor scope with no schema key reads a declaration the load
 * refuses. Either drift fails this test. The four scope locations are read structurally from the
 * bundled schema trees, following a local {@code $ref}:
 * <ul>
 *   <li>{@link Scope#GATEWAY} — the {@code gateway.schema.json} root;</li>
 *   <li>{@link Scope#ANCHOR} — the single pattern schema under {@code anchors};</li>
 *   <li>{@link Scope#ENDPOINT} — {@code endpoint.schema.json}'s {@code endpoint} object;</li>
 *   <li>{@link Scope#ROUTE} — the items of {@code endpoint.routes}.</li>
 * </ul>
 * A location that no longer resolves to a non-empty {@code properties} object fails naming the
 * location rather than comparing against nothing. Two negative controls — a block key injected at an
 * undeclared scope, and a declared one removed — prove each direction of the comparison can still
 * fail.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Every policy-block descriptor agrees with both bundled schemas")
class PolicyBlockSchemaContractTest {

    private static final String GATEWAY_SCHEMA_RESOURCE = "/schema/gateway.schema.json";
    private static final String ENDPOINT_SCHEMA_RESOURCE = "/schema/endpoint.schema.json";

    /** The object whose single pattern schema is the anchor scope. */
    private static final String ANCHOR_PATTERNS_POINTER = "/properties/anchors/patternProperties";

    /** The endpoint scope: the {@code endpoint} object of an endpoint document. */
    private static final String ENDPOINT_POINTER = "/properties/endpoint";

    /** The route scope: the items of the endpoint's {@code routes} array. */
    private static final String ROUTE_POINTER = ENDPOINT_POINTER + "/properties/routes/items";

    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String SCHEMA_REF = "$ref";

    /** A block key no descriptor declares at the endpoint scope, injected there by the first control. */
    private static final String INJECTED_ENDPOINT_KEY = PolicyBlock.SECURITY_HEADERS.yamlKey();

    /** A block key the route scope declares, removed there by the second control. */
    private static final String REMOVED_ROUTE_KEY = PolicyBlock.FORWARD.yamlKey();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the shipped schemas declare every block exactly at its descriptor's scopes")
    void shippedSchemasAgreeWithEveryDescriptor() throws Exception {
        JsonNode gateway = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode endpoint = schemaTree(ENDPOINT_SCHEMA_RESOURCE);

        List<String> mismatches = mismatches(gateway, endpoint);

        assertEquals(List.of(), mismatches, "a policy block is declared at a schema scope its PolicyBlock"
                + " descriptor does not resolve, or the other way round. Move the schema key and the descriptor"
                + " scope together.");
    }

    @Test
    @DisplayName("every scope location resolves to the declared keys of a schema object")
    void everyScopeLocationResolves() throws Exception {
        JsonNode gateway = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode endpoint = schemaTree(ENDPOINT_SCHEMA_RESOURCE);

        Map<Scope, Set<String>> keys = Map.of(
                Scope.GATEWAY, keysAt(Scope.GATEWAY, gateway, endpoint),
                Scope.ANCHOR, keysAt(Scope.ANCHOR, gateway, endpoint),
                Scope.ENDPOINT, keysAt(Scope.ENDPOINT, gateway, endpoint),
                Scope.ROUTE, keysAt(Scope.ROUTE, gateway, endpoint));

        assertAll("scope locations",
                () -> assertTrue(keys.get(Scope.GATEWAY).contains("version"), "gateway root: " + keys),
                () -> assertTrue(keys.get(Scope.ANCHOR).contains("path_prefix"), "anchor: " + keys),
                () -> assertTrue(keys.get(Scope.ENDPOINT).contains("routes"), "endpoint: " + keys),
                () -> assertTrue(keys.get(Scope.ROUTE).contains("match"), "route: " + keys));
    }

    @Test
    @DisplayName("a block key added at a scope its descriptor does not declare is refused")
    void blockKeyAtAnUndeclaredScopeIsRefused() throws Exception {
        JsonNode gateway = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode endpoint = schemaTree(ENDPOINT_SCHEMA_RESOURCE).deepCopy();
        ObjectNode properties = propertiesAt(endpoint, ENDPOINT_POINTER);
        properties.set(INJECTED_ENDPOINT_KEY, properties.objectNode());

        List<String> mismatches = mismatches(gateway, endpoint);

        assertEquals(List.of(mismatch(INJECTED_ENDPOINT_KEY, Scope.ENDPOINT, false)), mismatches,
                "injecting '" + INJECTED_ENDPOINT_KEY + "' at the endpoint scope must be the one mismatch");
    }

    @Test
    @DisplayName("a block key removed from a scope its descriptor declares is refused")
    void descriptorScopeWithoutASchemaKeyIsRefused() throws Exception {
        JsonNode gateway = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode endpoint = schemaTree(ENDPOINT_SCHEMA_RESOURCE).deepCopy();
        ObjectNode properties = propertiesAt(endpoint, ROUTE_POINTER);
        assertTrue(properties.has(REMOVED_ROUTE_KEY), "the route scope no longer declares '" + REMOVED_ROUTE_KEY
                + "', so this control would remove nothing");
        properties.remove(REMOVED_ROUTE_KEY);

        List<String> mismatches = mismatches(gateway, endpoint);

        assertEquals(List.of(mismatch(REMOVED_ROUTE_KEY, Scope.ROUTE, true)), mismatches,
                "removing '" + REMOVED_ROUTE_KEY + "' from the route scope must be the one mismatch");
    }

    /**
     * Every disagreement between the descriptors and the two schema trees, one entry per block and
     * scope, empty when they agree.
     */
    private static List<String> mismatches(JsonNode gateway, JsonNode endpoint) {
        List<String> mismatches = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            Set<String> schemaKeys = keysAt(scope, gateway, endpoint);
            for (PolicyBlock<?> block : PolicyBlock.ALL) {
                boolean descriptorDeclares = block.scopes().contains(scope);
                if (descriptorDeclares != schemaKeys.contains(block.yamlKey())) {
                    mismatches.add(mismatch(block.yamlKey(), scope, descriptorDeclares));
                }
            }
        }
        return mismatches;
    }

    private static String mismatch(String yamlKey, Scope scope, boolean descriptorDeclares) {
        return descriptorDeclares
                ? "'%s' is declarable at %s by its descriptor but not by the schema".formatted(yamlKey, scope)
                : "'%s' is declared at %s by the schema but not by its descriptor".formatted(yamlKey, scope);
    }

    /** The keys the schema declares at one scope's location. */
    private static Set<String> keysAt(Scope scope, JsonNode gateway, JsonNode endpoint) {
        ObjectNode properties = switch (scope) {
            case GATEWAY -> propertiesAt(gateway, "");
            case ANCHOR -> propertiesOf(gateway, singlePatternSchema(gateway), ANCHOR_PATTERNS_POINTER);
            case ENDPOINT -> propertiesAt(endpoint, ENDPOINT_POINTER);
            case ROUTE -> propertiesAt(endpoint, ROUTE_POINTER);
        };
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            keys.add(property.getKey());
        }
        return keys;
    }

    /** The one schema the {@code anchors} object applies to every anchor name. */
    private static JsonNode singlePatternSchema(JsonNode gateway) {
        JsonNode patterns = gateway.at(ANCHOR_PATTERNS_POINTER);
        if (patterns.size() != 1) {
            fail(GATEWAY_SCHEMA_RESOURCE + ": " + ANCHOR_PATTERNS_POINTER + " no longer holds exactly one anchor"
                    + " pattern schema, so the anchor scope is not one location any more: " + patterns);
        }
        return patterns.properties().iterator().next().getValue();
    }

    private static ObjectNode propertiesAt(JsonNode root, String pointer) {
        return propertiesOf(root, root.at(pointer), pointer);
    }

    /**
     * The {@code properties} object of a schema node, following a local {@code $ref}; fails rather than
     * answering with an empty set when the location no longer resolves to one.
     */
    private static ObjectNode propertiesOf(JsonNode root, JsonNode node, String location) {
        JsonNode resolved = node;
        JsonNode reference = node.get(SCHEMA_REF);
        if (reference != null && reference.asText().startsWith("#")) {
            resolved = root.at(reference.asText().substring(1));
        }
        if (resolved.get(SCHEMA_PROPERTIES) instanceof ObjectNode properties && !properties.isEmpty()) {
            return properties;
        }
        return fail("no non-empty '" + SCHEMA_PROPERTIES + "' object resolves at '" + location
                + "', so this contract would compare the descriptors against nothing");
    }

    private static JsonNode schemaTree(String resource) throws IOException {
        try (InputStream stream = PolicyBlockSchemaContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return fail("bundled schema " + resource + " is not on the test classpath");
            }
            return MAPPER.readTree(stream);
        }
    }
}
