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
package de.cuioss.sheriff.api.config.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The per-route {@code match} block. Matchers compose with AND semantics: a
 * route matches only if every declared matcher holds.
 *
 * @param pathPrefix the literal path prefix and precedence key (mandatory)
 * @param methods    the matched HTTP methods, empty meaning all methods
 * @param host       the exact host match, empty when omitted
 * @param headers    the header matchers, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record MatchConfig(String pathPrefix, List<HttpMethod> methods, Optional<String> host,
List<HeaderMatcher> headers) {

    /**
     * Canonical constructor requiring {@code pathPrefix}, defensively copying the
     * collections, and normalizing absent components.
     */
    public MatchConfig {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        methods = methods == null ? List.of() : List.copyOf(methods);
        host = Objects.requireNonNullElse(host, Optional.empty());
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /**
     * A single header matcher: presence or exact value.
     *
     * @param name    the header name (mandatory)
     * @param present whether the header must merely be present, empty when omitted
     * @param value   the exact required value, empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record HeaderMatcher(String name, Optional<Boolean> present, Optional<String> value) {

        /**
         * Canonical constructor requiring {@code name} and normalizing absent
         * optionals.
         */
        public HeaderMatcher {
            Objects.requireNonNull(name, "name");
            present = Objects.requireNonNullElse(present, Optional.empty());
            value = Objects.requireNonNullElse(value, Optional.empty());
        }
    }
}
