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
 * The operator-facing {@code portal} block of {@code gateway.yaml}: the application portal.
 * <p>
 * A declared block reserves one exact, host-independent request path ({@code path}) that is
 * answered ahead of the route table with an HTML overview of every enabled endpoint declaring an
 * {@link CatalogConfig endpoint.catalog} block. With {@code error_pages} the same template also
 * renders an HTML page for a gateway-originated error when the request explicitly accepts
 * {@code text/html}; the status never changes and a response relayed from an origin is never
 * replaced. An omitted block keeps the portal off.
 * <p>
 * The record only carries the declared values. The boot-time refusals — a non-canonical
 * {@code path}, a {@code path} equal to a reserved OIDC path or to an enabled endpoint's exact
 * route — are enforced by the configuration validator, and the template refusals by the portal
 * renderer. {@code cache_seconds} and {@code error_pages} are optional; the
 * {@link #effectiveCacheSeconds()} and {@link #effectiveErrorPages()} accessors resolve an omitted
 * member to its documented default.
 *
 * @param path         the exact canonical request path the portal answers on any host (mandatory)
 * @param title        the page title rendered into the template (mandatory)
 * @param templateDir  the directory holding an operator-authored {@code portal.html}, {@code null}
 *                     when the built-in template renders
 * @param cacheSeconds the {@code Cache-Control: max-age} for a session-free page, {@code null} when
 *                     omitted (resolves to {@link #DEFAULT_CACHE_SECONDS})
 * @param errorPages   whether eligible gateway-originated errors render as HTML on a page
 *                     navigation, {@code null} when omitted (resolves to {@code false})
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record PortalConfig(
String path,
String title,
@Nullable String templateDir,
@Nullable Integer cacheSeconds,
@Nullable Boolean errorPages) {

    /** The {@code cache_seconds} applied when the operator declares none: no caching. */
    public static final int DEFAULT_CACHE_SECONDS = 0;

    /**
     * Canonical constructor requiring the two mandatory members.
     */
    public PortalConfig {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(title, "title");
    }

    /**
     * @return the declared {@code cache_seconds}, or {@link #DEFAULT_CACHE_SECONDS} when omitted
     */
    public int effectiveCacheSeconds() {
        return cacheSeconds != null ? cacheSeconds : DEFAULT_CACHE_SECONDS;
    }

    /**
     * @return the declared {@code error_pages}, or {@code false} when omitted
     */
    public boolean effectiveErrorPages() {
        return Boolean.TRUE.equals(errorPages);
    }
}
