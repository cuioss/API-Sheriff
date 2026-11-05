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
package de.cuioss.sheriff.gateway.http;

import java.util.Objects;


import lombok.experimental.UtilityClass;

/**
 * The slash trimming every gateway path join and path split is built from, declared once so two
 * call sites cannot quietly disagree about how many slashes they remove.
 * <p>
 * That disagreement is not hypothetical. The upstream base path is stripped by the dispatch, by the
 * {@code Location} rewriter and by the route-table join, and the {@code rewrite_location} round trip
 * only holds while all three remove the <em>whole</em> trailing run: stripping one slash in one place
 * and all of them in another maps an upstream {@code /upload/next} onto the gateway and then
 * dispatches the client's follow-up to {@code /upload//next}.
 * <p>
 * Three operations, each a pure function of its argument:
 * <ul>
 *   <li>{@link #stripLeading(String)} — removes the whole leading run of {@code /}.</li>
 *   <li>{@link #stripTrailing(String)} — removes the whole trailing run of {@code /}.</li>
 *   <li>{@link #stripOneTrailing(String)} — removes at most one trailing {@code /}. It is the
 *       segment-boundary inverse of the prefix matcher's test, which admits a path at or below a
 *       {@code path_prefix} by appending exactly one {@code /} to a key that does not already end in
 *       one: dropping exactly that one slash from the match key leaves a prefix the remainder
 *       continues with its own leading {@code /}. Stripping the whole run instead would move a
 *       {@code //} that belongs to the request into the remainder.</li>
 * </ul>
 * Interior slashes are never touched, the empty string maps to itself, and a value consisting only of
 * slashes strips to the empty string under the whole-run operations.
 * <p>
 * Framework-free, so the pre-boot {@code config} package may use it as well as the request path.
 * Stateless and therefore thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class PathSlashes {

    private static final char SLASH = '/';

    /**
     * Removes every leading {@code /}.
     *
     * @param value the path to trim; never {@code null}
     * @return {@code value} without its leading run of slashes — {@code //a/b} becomes {@code a/b}
     */
    public static String stripLeading(String value) {
        Objects.requireNonNull(value, "value");
        int start = 0;
        while (start < value.length() && value.charAt(start) == SLASH) {
            start++;
        }
        return value.substring(start);
    }

    /**
     * Removes every trailing {@code /}.
     *
     * @param value the path to trim; never {@code null}
     * @return {@code value} without its trailing run of slashes — {@code /a/b//} becomes {@code /a/b}
     */
    public static String stripTrailing(String value) {
        Objects.requireNonNull(value, "value");
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == SLASH) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Removes at most one trailing {@code /}.
     *
     * @param value the path to trim; never {@code null}
     * @return {@code value} without its last character when that is a slash, otherwise {@code value}
     *         unchanged — {@code /a//} becomes {@code /a/}, where {@link #stripTrailing} yields
     *         {@code /a}
     */
    public static String stripOneTrailing(String value) {
        Objects.requireNonNull(value, "value");
        return !value.isEmpty() && value.charAt(value.length() - 1) == SLASH
                ? value.substring(0, value.length() - 1)
                : value;
    }
}
