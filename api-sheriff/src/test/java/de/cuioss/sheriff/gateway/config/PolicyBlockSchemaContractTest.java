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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.cuioss.sheriff.gateway.config.PolicyBlock.Scope;
import de.cuioss.sheriff.gateway.testsupport.RepositoryRoot;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds every {@link PolicyBlock} descriptor to the two bundled JSON Schemas and to the operator-facing
 * scope table: for every block of {@link PolicyBlock#ALL} and every {@link Scope}, the descriptor
 * declares the scope exactly when the schema declares the block's key at that scope's location, and
 * exactly when the scope table of {@code doc/user/anchors.adoc} lists it — in both directions.
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
 * <p>
 * <strong>The user documentation is the third declaration.</strong> {@code doc/user/anchors.adoc}
 * tells an operator where each block may be written, in the table anchored
 * {@code [[_policy_block_scopes]]}. The table is parsed structurally — one row per block, the block's
 * key in the first column and its comma-separated scopes in the second, resolved from the repository
 * root the way the other documentation contracts read {@code doc/} — and compared row by row with
 * the descriptors: a block without a row, a row without a block, a block listed twice and a row whose
 * scopes differ from the descriptor's each fail. A missing table, an empty table, a header that no
 * longer names the two parsed columns, or a scope word the {@link Scope} enum does not know fails
 * naming the document instead of comparing against less than it holds. A negative control feeds a
 * copy of the table with one scope added to a row and one row dropped back through the same parser
 * and asserts both drifts are reported.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Every policy-block descriptor agrees with both bundled schemas and the user-doc scope table")
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

    /** The user document carrying the scope table, relative to the repository root. */
    private static final String SCOPE_TABLE_DOCUMENT = "doc/user/anchors.adoc";

    /** The AsciiDoc anchor the scope table is declared under. */
    private static final String SCOPE_TABLE_ANCHOR = "[[_policy_block_scopes]]";

    /** The delimiter opening and closing an AsciiDoc table. */
    private static final String TABLE_DELIMITER = "|===";

    /** The cell separator of an AsciiDoc table row. */
    private static final String CELL_SEPARATOR = "|";

    /** The header of the column naming the block, and its position. */
    private static final String BLOCK_HEADER = "Block";
    private static final int BLOCK_COLUMN = 0;

    /** The header of the column listing the block's scopes, and its position. */
    private static final String SCOPES_HEADER = "Declarable at";
    private static final int SCOPES_COLUMN = 1;

    /** The block whose row the table control drops. */
    private static final String DROPPED_ROW_KEY = PolicyBlock.FORWARD.yamlKey();

    /** The block whose row the table control widens, and the scope it adds there. */
    private static final String WIDENED_ROW_KEY = PolicyBlock.WEBSOCKET.yamlKey();
    private static final Scope ADDED_SCOPE = Scope.ENDPOINT;

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

    @Test
    @DisplayName("the user-doc scope table lists every block exactly at its descriptor's scopes")
    void userDocScopeTableAgreesWithEveryDescriptor() throws Exception {
        ScopeTable table = parseScopeTable(scopeTableLines(Files.readAllLines(
                RepositoryRoot.holding(SCOPE_TABLE_DOCUMENT).resolve(SCOPE_TABLE_DOCUMENT), StandardCharsets.UTF_8)));

        List<String> mismatches = tableMismatches(table.rows());

        assertAll("scope table of " + SCOPE_TABLE_DOCUMENT,
                () -> assertEquals(List.of(BLOCK_HEADER, SCOPES_HEADER),
                        List.of(table.header().get(BLOCK_COLUMN), table.header().get(SCOPES_COLUMN)),
                        "the table no longer carries the block and scope columns this contract parses: "
                                + table.header()),
                () -> assertEquals(List.of(), mismatches, SCOPE_TABLE_DOCUMENT + " lists a policy block at"
                        + " scopes its PolicyBlock descriptor does not declare, or the other way round. Correct"
                        + " the table row together with the descriptor."));
    }

    @Test
    @DisplayName("a scope table with a widened row and a dropped row is refused")
    void tamperedScopeTableIsRefused() throws Exception {
        ScopeTable shipped = parseScopeTable(scopeTableLines(Files.readAllLines(
                RepositoryRoot.holding(SCOPE_TABLE_DOCUMENT).resolve(SCOPE_TABLE_DOCUMENT), StandardCharsets.UTF_8)));
        List<List<String>> tamperedRows = new ArrayList<>();
        for (List<String> row : shipped.rows()) {
            String key = blockKey(row);
            if (DROPPED_ROW_KEY.equals(key)) {
                continue;
            }
            List<String> copy = new ArrayList<>(row);
            if (WIDENED_ROW_KEY.equals(key)) {
                copy.set(SCOPES_COLUMN, ADDED_SCOPE.name().toLowerCase(Locale.ROOT) + ", " + row.get(SCOPES_COLUMN));
            }
            tamperedRows.add(copy);
        }
        assertEquals(shipped.rows().size() - 1, tamperedRows.size(), "the shipped table no longer has a '"
                + DROPPED_ROW_KEY + "' row, so this control would drop nothing");

        List<String> mismatches = tableMismatches(parseScopeTable(render(shipped.header(), tamperedRows)).rows());

        Set<Scope> widened = EnumSet.copyOf(PolicyBlock.WEBSOCKET.scopes());
        widened.add(ADDED_SCOPE);
        assertEquals(List.of(scopeSetMismatch(PolicyBlock.FORWARD, null),
                        scopeSetMismatch(PolicyBlock.WEBSOCKET, widened)), mismatches,
                "dropping the '" + DROPPED_ROW_KEY + "' row and widening the '" + WIDENED_ROW_KEY
                        + "' row must be exactly the two reported drifts");
    }

    /**
     * Every disagreement between the descriptors and the parsed rows of the scope table, in
     * {@link PolicyBlock#ALL} order followed by rows naming no block, empty when they agree.
     */
    private static List<String> tableMismatches(List<List<String>> rows) {
        Map<String, Set<Scope>> documented = new LinkedHashMap<>();
        List<String> mismatches = new ArrayList<>();
        for (List<String> row : rows) {
            String key = blockKey(row);
            if (documented.put(key, documentedScopes(row)) != null) {
                mismatches.add("'%s' is listed in more than one row".formatted(key));
            }
        }
        for (PolicyBlock<?> block : PolicyBlock.ALL) {
            Set<Scope> listed = documented.remove(block.yamlKey());
            if (!block.scopes().equals(listed)) {
                mismatches.add(scopeSetMismatch(block, listed));
            }
        }
        documented.keySet().forEach(key -> mismatches.add("'%s' has a row but no PolicyBlock descriptor".formatted(key)));
        return mismatches;
    }

    private static String scopeSetMismatch(PolicyBlock<?> block, @Nullable Set<Scope> listed) {
        return listed == null
                ? "'%s' has no row in the scope table".formatted(block.yamlKey())
                : "'%s' row lists %s but the descriptor declares %s".formatted(block.yamlKey(), listed, block.scopes());
    }

    /** The block key of a row: the first cell without its AsciiDoc code quotes. */
    private static String blockKey(List<String> row) {
        return row.get(BLOCK_COLUMN).replace("`", "").strip();
    }

    /** The scopes a row lists, read from its comma-separated scope cell. */
    private static Set<Scope> documentedScopes(List<String> row) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String word : row.get(SCOPES_COLUMN).split(",")) {
            String name = word.strip().toUpperCase(Locale.ROOT);
            try {
                scopes.add(Scope.valueOf(name));
            } catch (IllegalArgumentException e) {
                fail("the '" + blockKey(row) + "' row of " + SCOPE_TABLE_DOCUMENT + " names the scope '" + word.strip()
                        + "', which is no PolicyBlock.Scope", e);
            }
        }
        return scopes;
    }

    /** The parsed scope table: the header cells, and every body row as its list of cells. */
    private record ScopeTable(List<String> header, List<List<String>> rows) {
    }

    /**
     * The lines between the opening and closing delimiter of the table following the scope-table
     * anchor; fails naming the document when the anchor or either delimiter is missing.
     */
    private static List<String> scopeTableLines(List<String> document) {
        int anchor = document.indexOf(SCOPE_TABLE_ANCHOR);
        if (anchor < 0) {
            return fail(SCOPE_TABLE_DOCUMENT + " no longer carries the anchor " + SCOPE_TABLE_ANCHOR
                    + ", so this contract would compare the descriptors against nothing");
        }
        int open = -1;
        for (int index = anchor + 1; index < document.size() && open < 0; index++) {
            if (TABLE_DELIMITER.equals(document.get(index).strip())) {
                open = index;
            }
        }
        int close = open < 0 ? -1 : document.subList(open + 1, document.size()).stream()
                .map(String::strip).toList().indexOf(TABLE_DELIMITER);
        if (open < 0 || close < 0) {
            return fail(SCOPE_TABLE_DOCUMENT + ": no complete " + TABLE_DELIMITER + " table follows "
                    + SCOPE_TABLE_ANCHOR);
        }
        return document.subList(open + 1, open + 1 + close);
    }

    /**
     * Parses the lines of an AsciiDoc table whose first line is the header row: a line starting with
     * {@code |} opens one cell per separator on it, any other non-blank line continues the last cell,
     * and the cells after the header are grouped into rows of the header's width. Fails when the
     * table has no body row or when its cells do not fill whole rows.
     */
    private static ScopeTable parseScopeTable(List<String> lines) {
        List<String> header = null;
        List<String> cells = new ArrayList<>();
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (!text.startsWith(CELL_SEPARATOR)) {
                if (cells.isEmpty()) {
                    return fail(SCOPE_TABLE_DOCUMENT + ": table text '" + text + "' continues no cell");
                }
                cells.set(cells.size() - 1, cells.getLast() + " " + text);
                continue;
            }
            List<String> lineCells = splitCells(text);
            if (header == null) {
                header = lineCells;
            } else {
                cells.addAll(lineCells);
            }
        }
        if (header == null || cells.isEmpty() || cells.size() % header.size() != 0) {
            return fail(SCOPE_TABLE_DOCUMENT + ": the scope table has no body row, or its " + cells.size()
                    + " cells do not fill rows of the header's width " + header);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int start = 0; start < cells.size(); start += header.size()) {
            rows.add(List.copyOf(cells.subList(start, start + header.size())));
        }
        return new ScopeTable(header, rows);
    }

    private static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>();
        for (String cell : line.substring(CELL_SEPARATOR.length()).split("\\" + CELL_SEPARATOR, -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    /** Renders a header and rows back into table lines in the shipped one-cell-per-line layout. */
    private static List<String> render(List<String> header, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(header.stream().map(cell -> CELL_SEPARATOR + " " + cell).collect(Collectors.joining(" ")));
        for (List<String> row : rows) {
            lines.add("");
            row.forEach(cell -> lines.add(CELL_SEPARATOR + " " + cell));
        }
        return lines;
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
