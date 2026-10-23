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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code cookies.allow} mode: which browser cookies a route forwards to its upstream and which
 * upstream {@code Set-Cookie} lines it relays back. One value governs both directions.
 * <p>
 * The mode is typed by its YAML shape, never by a string convention:
 * <ul>
 *   <li>the scalar {@code none} is {@link #NONE} — no cookie crosses in either direction;</li>
 *   <li>the scalar {@code all} is {@link #ALL} — every cookie crosses;</li>
 *   <li>a sequence is a {@link Named} list of cookie names, kept in order and compared exactly and
 *       case-sensitively. Because the shape decides, {@code [all]} and {@code [none]} are the cookies
 *       named {@code all} and {@code none}, not the two modes.</li>
 * </ul>
 * Any other scalar is refused when the document is bound. The gateway's own cookies are never
 * admitted by any mode: they are removed before this type is consulted, so {@link #admits} answers
 * for the cookies that remain and says nothing about the gateway's own.
 * <p>
 * Usage:
 * {@snippet :
 * CookieAllow allow = new CookieAllow.Named(List.of("app_session", "theme"));
 * boolean forwarded = allow.admits("theme");   // true
 * boolean other = allow.admits("Theme");       // false: names are case-sensitive
 * }
 * <p>
 * Every variant is immutable and safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public sealed interface CookieAllow permits CookieAllow.None, CookieAllow.All, CookieAllow.Named {

    /** The scalar {@code none}: no cookie crosses. */
    String NONE_KEYWORD = "none";

    /** The scalar {@code all}: every cookie crosses. */
    String ALL_KEYWORD = "all";

    /** The {@code none} mode. */
    CookieAllow NONE = new None();

    /** The {@code all} mode. */
    CookieAllow ALL = new All();

    /**
     * Binds the YAML value of {@code cookies.allow}: the scalar {@code none} or {@code all} to its
     * mode, a sequence of strings to a {@link Named} list.
     *
     * @param node the bound YAML value
     * @return the mode the value declares
     * @throws IllegalArgumentException when the value is any other scalar, a sequence holding a
     *                                  non-string entry, or neither a scalar nor a sequence
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static CookieAllow fromYaml(JsonNode node) {
        Objects.requireNonNull(node, "node");
        if (node.isTextual()) {
            return switch (node.textValue()) {
                case NONE_KEYWORD -> NONE;
                case ALL_KEYWORD -> ALL;
                default -> throw new IllegalArgumentException(
                        "cookies.allow must be none, all or a list of cookie names");
            };
        }
        if (node.isArray()) {
            List<String> names = new ArrayList<>(node.size());
            for (JsonNode entry : node) {
                if (!entry.isTextual()) {
                    throw new IllegalArgumentException("every cookies.allow entry must be a cookie name");
                }
                names.add(entry.textValue());
            }
            return new Named(names);
        }
        throw new IllegalArgumentException("cookies.allow must be none, all or a list of cookie names");
    }

    /**
     * Whether a cookie other than one of the gateway's own crosses under this mode.
     *
     * @param cookieName the cookie name, read off its pair
     * @return {@code true} when the mode admits the cookie
     */
    boolean admits(String cookieName);

    /**
     * The null check every mode's {@link #admits} opens with, so the three variants refuse a
     * {@code null} name with one message.
     *
     * @param cookieName the cookie name handed to {@link #admits}
     * @return {@code cookieName}, never {@code null}
     */
    private static String requireCookieName(String cookieName) {
        return Objects.requireNonNull(cookieName, "cookieName");
    }

    /**
     * The {@code none} mode: no cookie crosses.
     *
     * @since 1.0
     */
    record None() implements CookieAllow {

        @Override
        public boolean admits(String cookieName) {
            CookieAllow.requireCookieName(cookieName);
            return false;
        }
    }

    /**
     * The {@code all} mode: every cookie crosses.
     *
     * @since 1.0
     */
    record All() implements CookieAllow {

        @Override
        public boolean admits(String cookieName) {
            CookieAllow.requireCookieName(cookieName);
            return true;
        }
    }

    /**
     * A list of cookie names: exactly the listed cookies cross.
     *
     * @param names the listed cookie names in declaration order, compared exactly and
     *              case-sensitively
     * @since 1.0
     */
    record Named(List<String> names) implements CookieAllow {

        /**
         * Canonical constructor defensively copying {@code names}.
         */
        public Named {
            names = List.copyOf(Objects.requireNonNull(names, "names"));
        }

        @Override
        public boolean admits(String cookieName) {
            return names.contains(CookieAllow.requireCookieName(cookieName));
        }
    }
}
