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

import java.util.List;
import java.util.Objects;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The per-route {@code match} block. Matchers compose with AND semantics: a
 * route matches only if every declared matcher holds.
 * <p>
 * The path matcher is declared in exactly one of two forms:
 * <ul>
 *   <li>{@code pathPrefix} — a literal prefix matched on a segment boundary; among
 *       prefix routes the longest prefix wins.</li>
 *   <li>{@code path} — an exact path compared as an un-normalized string against the
 *       canonical request path, so {@code /a} and {@code /a/} are distinct
 *       addresses.</li>
 * </ul>
 * Precedence: for the same address an exact route is always selected before any
 * prefix route; prefix routes then follow the longest-prefix rule. Both forms share
 * one precedence key, exposed as {@link #matchKey()}.
 *
 * @param pathPrefix the literal path prefix, {@code null} when the route declares an
 *                   exact {@code path}
 * @param path       the exact path, {@code null} when the route declares a
 *                   {@code pathPrefix}
 * @param methods    the matched HTTP methods, empty meaning all methods
 * @param host       the exact host match, {@code null} when omitted
 * @param headers    the header matchers, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record MatchConfig(
@Nullable String pathPrefix,
@Nullable String path,
List<HttpMethod> methods,
@Nullable String host,
List<HeaderMatcher> headers) {

    /**
     * Canonical constructor requiring exactly one of {@code pathPrefix} and
     * {@code path}, and defensively copying the collections.
     *
     * @throws IllegalArgumentException if both or neither of {@code pathPrefix} and
     *                                  {@code path} are declared
     */
    public MatchConfig {
        if ((pathPrefix == null) == (path == null)) {
            throw new IllegalArgumentException("exactly one of pathPrefix or path must be declared");
        }
        methods = methods == null ? List.of() : List.copyOf(methods);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /**
     * Returns the declared path matcher value — the exact {@code path} for an exact
     * route, the {@code pathPrefix} otherwise. This is the single precedence and
     * namespace key every consumer uses, independent of the matcher form.
     *
     * @return the declared exact path or path prefix, never {@code null}
     */
    public String matchKey() {
        return path != null ? path : Objects.requireNonNull(pathPrefix, "pathPrefix");
    }

    /**
     * Returns whether this matcher is an exact-path matcher.
     *
     * @return {@code true} when {@code path} is declared, {@code false} for a
     * {@code pathPrefix} matcher
     */
    public boolean isExact() {
        return path != null;
    }

    /**
     * A single header matcher: presence or exact value.
     *
     * @param name    the header name (mandatory)
     * @param present whether the header must merely be present, {@code null} when omitted
     * @param value   the exact required value, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record HeaderMatcher(String name, @Nullable Boolean present, @Nullable String value) {

        /**
         * Canonical constructor requiring {@code name}.
         */
        public HeaderMatcher {
            Objects.requireNonNull(name, "name");
        }
    }
}
