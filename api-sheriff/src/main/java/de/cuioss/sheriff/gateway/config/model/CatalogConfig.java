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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Objects;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * An endpoint's optional {@code catalog} block: its entry on the application portal's overview.
 * <p>
 * The entry is active exactly when its endpoint is enabled after placeholder resolution, so
 * {@code ENDPOINT_<ID>_ENABLED} toggles the entry together with the endpoint's routes. Every value
 * is HTML-escaped when rendered. {@code entry} must be an origin-relative absolute path on the
 * gateway's own origin; the configuration validator refuses anything else at boot, because a
 * scheme, an authority or a scheme-relative {@code //host} value would turn the portal into an
 * open redirect.
 *
 * @param title       the entry's display title (mandatory)
 * @param description an optional one-line description, {@code null} when omitted
 * @param entry       the origin-relative link target (mandatory)
 * @param order       the optional sort key, {@code null} when omitted — such an entry sorts after
 *                    every entry that declares one
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record CatalogConfig(
String title,
@Nullable String description,
String entry,
@Nullable Integer order) {

    /**
     * Canonical constructor requiring the two mandatory members.
     */
    public CatalogConfig {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entry, "entry");
    }
}
