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
package de.cuioss.sheriff.gateway.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;


import de.cuioss.sheriff.gateway.config.model.CatalogConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Tests for {@link PortalCatalog}: only catalog-bearing endpoints are listed, the input list is taken
 * as the enabled set, and entries are ordered by {@code order} (absent last), then by {@code title}.
 */
@EnableGeneratorController
@DisplayName("PortalCatalog")
class PortalCatalogTest {

    private static int endpointCounter;

    private static EndpointConfig endpoint(@Nullable CatalogConfig catalog) {
        endpointCounter++;
        return EndpointConfig.builder().id("endpoint-" + endpointCounter).enabled(true).catalog(catalog).build();
    }

    private static CatalogConfig catalog(String title, @Nullable Integer order) {
        return CatalogConfig.builder().title(title).entry("/" + title.length() + "/").order(order).build();
    }

    @Test
    @DisplayName("Lists only the endpoints that declare a catalog block")
    void listsOnlyCatalogBearingEndpoints() {
        CatalogConfig orders = CatalogConfig.builder().title("Orders").description("Order management")
                .entry("/orders/").order(1).build();

        PortalCatalog catalog = PortalCatalog.from(List.of(endpoint(null), endpoint(orders), endpoint(null)));

        assertEquals(List.of(new PortalCatalog.Entry("Orders", "Order management", "/orders/", 1)),
                catalog.entries());
    }

    @Test
    @DisplayName("Orders by order ascending, absent order last, then by title")
    void ordersByOrderThenTitle() {
        List<EndpointConfig> endpoints = List.of(
                endpoint(catalog("Zeta", null)),
                endpoint(catalog("Beta", 20)),
                endpoint(catalog("Alpha", null)),
                endpoint(catalog("Gamma", 10)),
                endpoint(catalog("Delta", 20)),
                endpoint(catalog("Epsilon", -5)));

        List<String> titles = PortalCatalog.from(endpoints).entries().stream().map(PortalCatalog.Entry::title)
                .toList();

        assertEquals(List.of("Epsilon", "Gamma", "Beta", "Delta", "Alpha", "Zeta"), titles);
    }

    @ParameterizedTest
    @GeneratorsSource(generator = GeneratorType.NON_BLANK_STRINGS, minSize = 1, maxSize = 40, count = 10)
    @DisplayName("Keeps every generated title verbatim — escaping is the renderer's job")
    void keepsGeneratedTitlesVerbatim(String title) {
        PortalCatalog catalog = PortalCatalog.from(List.of(endpoint(catalog(title, null))));

        assertEquals(title, catalog.entries().getFirst().title());
    }

    @Test
    @DisplayName("Titles with equal order sort by title regardless of input order")
    void sortsGeneratedTitlesDeterministically() {
        List<String> titles = new ArrayList<>();
        List<EndpointConfig> endpoints = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String title = Generators.letterStrings(3, 12).next() + index;
            titles.add(title);
            endpoints.add(endpoint(catalog(title, 7)));
        }

        List<String> sorted = PortalCatalog.from(endpoints).entries().stream().map(PortalCatalog.Entry::title)
                .toList();

        assertEquals(titles.stream().sorted().toList(), sorted);
    }

    @Test
    @DisplayName("An empty input yields an empty catalog, equal to PortalCatalog.empty()")
    void emptyInputYieldsEmptyCatalog() {
        assertTrue(PortalCatalog.from(List.of()).entries().isEmpty());
        assertEquals(PortalCatalog.empty(), PortalCatalog.from(List.of(endpoint(null))));
    }

    @Test
    @DisplayName("The entry list is unmodifiable")
    void entriesAreUnmodifiable() {
        List<PortalCatalog.Entry> entries = PortalCatalog.from(List.of(endpoint(catalog("Orders", 1)))).entries();

        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test
    @DisplayName("An entry requires a title and a link target")
    void entryRequiresTitleAndEntry() {
        assertThrows(NullPointerException.class, () -> newEntry(null, "/x/"));
        assertThrows(NullPointerException.class, () -> newEntry("X", null));
    }

    private static PortalCatalog.Entry newEntry(String title, String entry) {
        return new PortalCatalog.Entry(title, null, entry, null);
    }
}
