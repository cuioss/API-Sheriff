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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.CatalogConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import org.jspecify.annotations.Nullable;

/**
 * The application portal's catalog: the ordered overview entries of every enabled endpoint that
 * declares an {@code endpoint.catalog} block.
 * <p>
 * The catalog is resolved once at boot from the <em>enabled</em> endpoint list — the same
 * post-placeholder list the route table is built from — so an endpoint switched off through
 * {@code ENDPOINT_<ID>_ENABLED} disappears from the page together with its routes. Entries are
 * ordered by {@code order} ascending, an entry without {@code order} sorting after every entry that
 * declares one, and then by {@code title}.
 * <p>
 * Immutable and framework-agnostic; thread-safe.
 *
 * @param entries the ordered catalog entries, empty when no enabled endpoint declares a catalog block
 * @author API Sheriff Team
 * @since 1.0
 */
public record PortalCatalog(List<Entry> entries) {

    /** The {@code order} ascending, absent-last, then {@code title} ordering of the catalog. */
    private static final Comparator<Entry> ORDERING = Comparator
            .comparing(Entry::order, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Entry::title);

    /**
     * Canonical constructor defensively copying {@code entries}.
     */
    public PortalCatalog {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * @return the catalog with no entries — the page then renders an empty overview
     */
    public static PortalCatalog empty() {
        return new PortalCatalog(List.of());
    }

    /**
     * Resolves the catalog from the enabled endpoints: keeps only those declaring a catalog block
     * and orders them by {@code order} (absent last), then by {@code title}.
     *
     * @param enabledEndpoints the endpoints filtered to those enabled after placeholder resolution
     * @return the resolved catalog
     */
    public static PortalCatalog from(List<EndpointConfig> enabledEndpoints) {
        return new PortalCatalog(enabledEndpoints.stream()
                .map(EndpointConfig::catalog)
                .filter(Objects::nonNull)
                .map(Entry::of)
                .sorted(ORDERING)
                .toList());
    }

    /**
     * One overview entry.
     *
     * @param title       the display title
     * @param description the optional one-line description, {@code null} when omitted
     * @param entry       the origin-relative link target
     * @param order       the optional sort key, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Entry(String title, @Nullable String description, String entry, @Nullable Integer order) {

        /**
         * Canonical constructor requiring the title and the link target.
         */
        public Entry {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(entry, "entry");
        }

        static Entry of(CatalogConfig catalog) {
            return new Entry(catalog.title(), catalog.description(), catalog.entry(), catalog.order());
        }
    }
}
