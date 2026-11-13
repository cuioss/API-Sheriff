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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.testsupport.RepositoryRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the route-field inventory {@code doc/configuration.adoc} enumerates by hand in its schema table
 * to the two places that define a route: the {@code properties} of a {@code routes[]} item in the
 * bundled {@code endpoint.schema.json}, and the record components of {@link RouteConfig}, each read in
 * its {@code snake_case} YAML spelling. A field added to or removed from either source, or from the
 * document, turns this test red until all three agree.
 * <p>
 * <strong>No vacuous pass.</strong> The documented list is extracted between two literal sentence
 * fragments of the reference, as {@code DocumentedHeaderSetsContractTest} extracts the header sets; a
 * missing anchor or a span naming no field fails naming the anchor, and the schema location must resolve
 * to a non-empty {@code properties} object. The listed count is asserted beside the set, so a document
 * naming one field twice does not collapse into the correct set unnoticed. Two negative controls feed
 * copies of the reference whose span gained or lost one field back through the same extraction and
 * assert the comparison fails.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The documented route-field inventory stays bound to the endpoint schema and RouteConfig")
class RouteFieldInventoryContractTest {

    private static final String CONFIGURATION_ADOC = "doc/configuration.adoc";

    private static final String ENDPOINT_SCHEMA_RESOURCE = "/schema/endpoint.schema.json";

    /** The schema object whose {@code properties} are the fields of one route. */
    private static final String ROUTE_ITEM_POINTER = "/properties/endpoint/properties/routes/items";

    /** Opens the route-field list of the schema table. */
    private static final String ROUTE_FIELDS_START = "and its `routes[]`, each route's";

    /** Closes the route-field list, ahead of the nested keys of the {@code websocket} block. */
    private static final String ROUTE_FIELDS_END = ". The WebSocket-route `websocket` block holds";

    /** A backticked YAML key: lower case letters, digits and underscores, starting with a letter. */
    private static final Pattern BACKTICKED_FIELD = Pattern.compile("`([a-z][a-z0-9_]*)`");

    /** A documented field the first negative control removes. */
    private static final String REMOVED_FIELD = "keep_identity_headers";

    /** A field the second negative control adds; neither source declares it. */
    private static final String ADDED_FIELD = "diverged_field";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the documented route fields equal the route properties of endpoint.schema.json")
    void documentedRouteFieldsMatchTheSchema() throws Exception {
        // Arrange
        Set<String> schemaFields = schemaRouteFields();

        // Act
        Set<String> documented = new TreeSet<>(fieldsBetween(configurationAdoc()));

        // Assert
        assertEquals(schemaFields, documented, "the route fields documented in " + CONFIGURATION_ADOC
                + " must match the routes[] item properties of endpoint.schema.json");
    }

    @Test
    @DisplayName("the documented route fields equal the RouteConfig record components in snake_case")
    void documentedRouteFieldsMatchTheRecordComponents() throws Exception {
        // Arrange
        Set<String> recordFields = recordFields();

        // Act
        Set<String> documented = new TreeSet<>(fieldsBetween(configurationAdoc()));

        // Assert
        assertEquals(recordFields, documented, "the route fields documented in " + CONFIGURATION_ADOC
                + " must match the record components of RouteConfig");
    }

    @Test
    @DisplayName("the documented route-field list names each field once")
    void documentedRouteFieldsAreListedOnce() throws Exception {
        // Arrange
        List<String> listed = fieldsBetween(configurationAdoc());

        // Act
        Set<String> distinct = new TreeSet<>(listed);

        // Assert
        assertEquals(distinct.size(), listed.size(),
                () -> "a route field is listed more than once in " + CONFIGURATION_ADOC + ": " + listed);
    }

    @Test
    @DisplayName("negative control: a route-field span that lost a field no longer matches either source")
    void routeFieldSpanMissingAFieldIsUnequal() throws Exception {
        // Arrange
        String document = configurationAdoc();
        String token = "`" + REMOVED_FIELD + "`";
        int spanStart = document.indexOf(ROUTE_FIELDS_START) + ROUTE_FIELDS_START.length();
        int fieldAt = document.indexOf(token, spanStart);
        assertTrue(fieldAt > spanStart && fieldAt < document.indexOf(ROUTE_FIELDS_END, spanStart),
                () -> "precondition: the span names " + token);
        String diverged = document.substring(0, fieldAt) + document.substring(fieldAt + token.length());

        // Act
        Set<String> documented = new TreeSet<>(fieldsBetween(diverged));

        // Assert
        assertAll("a removed field is seen by both comparisons",
                () -> assertNotEquals(schemaRouteFields(), documented, "against the schema"),
                () -> assertNotEquals(recordFields(), documented, "against RouteConfig"));
    }

    @Test
    @DisplayName("negative control: a route-field span that gained a field no longer matches either source")
    void routeFieldSpanWithAnExtraFieldIsUnequal() throws Exception {
        // Arrange
        String diverged = configurationAdoc().replaceFirst(Pattern.quote(ROUTE_FIELDS_START),
                Matcher.quoteReplacement(ROUTE_FIELDS_START + " `" + ADDED_FIELD + "`,"));

        // Act
        Set<String> documented = new TreeSet<>(fieldsBetween(diverged));

        // Assert
        assertTrue(documented.contains(ADDED_FIELD), "precondition: the diverged span names the added field");
        assertAll("an added field is seen by both comparisons",
                () -> assertNotEquals(schemaRouteFields(), documented, "against the schema"),
                () -> assertNotEquals(recordFields(), documented, "against RouteConfig"));
    }

    /** The fields of the route-field span, in document order and before de-duplication. */
    private static List<String> fieldsBetween(String document) {
        int from = document.indexOf(ROUTE_FIELDS_START);
        if (from < 0) {
            return fail("anchor not found in " + CONFIGURATION_ADOC + ": " + ROUTE_FIELDS_START);
        }
        int to = document.indexOf(ROUTE_FIELDS_END, from + ROUTE_FIELDS_START.length());
        if (to < 0) {
            return fail("closing anchor not found after '" + ROUTE_FIELDS_START + "' in " + CONFIGURATION_ADOC
                    + ": " + ROUTE_FIELDS_END);
        }
        Matcher matcher = BACKTICKED_FIELD.matcher(document.substring(from + ROUTE_FIELDS_START.length(), to));
        List<String> fields = new ArrayList<>();
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        assertFalse(fields.isEmpty(),
                () -> "no field named between '" + ROUTE_FIELDS_START + "' and '" + ROUTE_FIELDS_END + "'");
        return fields;
    }

    private static Set<String> schemaRouteFields() throws IOException {
        JsonNode properties = schemaTree().at(ROUTE_ITEM_POINTER).path("properties");
        assertTrue(properties.isObject() && !properties.isEmpty(),
                () -> ROUTE_ITEM_POINTER + "/properties no longer resolves to a non-empty object in "
                        + ENDPOINT_SCHEMA_RESOURCE);
        return properties.properties().stream().map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> recordFields() {
        return Arrays.stream(RouteConfig.class.getRecordComponents())
                .map(component -> snakeCase(component.getName()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Spells a record component name the way the YAML key is written: {@code rateLimit} → {@code rate_limit}. */
    private static String snakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static JsonNode schemaTree() throws IOException {
        try (InputStream schema = RouteFieldInventoryContractTest.class.getResourceAsStream(ENDPOINT_SCHEMA_RESOURCE)) {
            assertNotNull(schema, () -> ENDPOINT_SCHEMA_RESOURCE + " is not on the test classpath");
            return MAPPER.readTree(schema);
        }
    }

    private static String configurationAdoc() throws IOException {
        return Files.readString(RepositoryRoot.holding(CONFIGURATION_ADOC).resolve(CONFIGURATION_ADOC),
                StandardCharsets.UTF_8);
    }
}
