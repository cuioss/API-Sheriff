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
package de.cuioss.sheriff.gateway.edge;

import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import org.jspecify.annotations.Nullable;

/**
 * The {@code redirect} terminal action (ADR-0014 Amendment A1): computes the answer a redirect
 * route sends instead of contacting an upstream.
 * <p>
 * The stage is framework-light by design — it turns a boot-resolved {@link RedirectConfig} and the
 * raw inbound query into a status and a {@code Location} value, and leaves writing them to the
 * edge. The rules are fixed:
 * <ul>
 *   <li>the status is the configured one, verbatim;</li>
 *   <li>the {@code Location} is the configured {@code location}, written verbatim. It is a raw
 *       gateway path — the same convention {@code match.path_prefix} follows — so it is never
 *       prefixed with the HTTP context path, and no request placeholder is expanded;</li>
 *   <li>when {@code keep_query} is set and the request carries a query, the raw query string is
 *       appended, joined with {@code ?} or, when {@code location} already carries a query, with
 *       {@code &}.</li>
 * </ul>
 * The stage performs no URI policy of its own: the open-redirect review of {@code location} runs
 * once, at boot, in the configuration validator, so every value reaching this stage is already an
 * admitted one.
 * <p>
 * Stateless and therefore thread-safe; one instance serves every redirect route.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RedirectStage {

    private static final char QUERY_DELIMITER = '?';
    private static final char PARAMETER_DELIMITER = '&';

    /**
     * Computes the redirect answer for one request.
     *
     * @param redirect the route's resolved redirect action
     * @param rawQuery the raw inbound query string without its leading {@code ?}, or {@code null}
     *                 when the request carries none
     * @return the status and {@code Location} value to answer with
     */
    public Answer answer(RedirectConfig redirect, @Nullable String rawQuery) {
        Objects.requireNonNull(redirect, "redirect");
        return new Answer(redirect.status(), location(redirect, rawQuery));
    }

    private static String location(RedirectConfig redirect, @Nullable String rawQuery) {
        String location = redirect.location();
        if (!redirect.keepQuery() || rawQuery == null || rawQuery.isEmpty()) {
            return location;
        }
        if (location.indexOf(QUERY_DELIMITER) < 0) {
            return location + QUERY_DELIMITER + rawQuery;
        }
        char last = location.charAt(location.length() - 1);
        if (last == QUERY_DELIMITER || last == PARAMETER_DELIMITER) {
            return location + rawQuery;
        }
        return location + PARAMETER_DELIMITER + rawQuery;
    }

    /**
     * The computed redirect answer.
     *
     * @param status   the redirect status code, taken verbatim from the configuration
     * @param location the {@code Location} header value
     */
    public record Answer(int status, String location) {

        /**
         * Canonical constructor requiring {@code location}.
         */
        public Answer {
            Objects.requireNonNull(location, "location");
        }
    }
}
