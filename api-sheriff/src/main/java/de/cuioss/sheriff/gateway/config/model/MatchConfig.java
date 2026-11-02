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
     * Returns whether this matcher narrows the match on any dimension beyond the path —
     * that is, whether there are requests inside its path namespace that it does
     * <em>not</em> match.
     * <p>
     * The dimensions are exactly the non-path components of this record, which is what
     * {@code RouteMatcher.matches} ANDs with the path test: {@code methods} (empty meaning
     * every method), {@code host} ({@code null} meaning any host) and {@code headers}
     * (empty meaning no header constraint). The predicate lives here, beside the data,
     * rather than in a consumer, so that adding a dimension to this record puts the
     * decision in front of whoever adds it; {@code MatchConfigTest} pins the component set
     * so a new one cannot be added while this method silently keeps ignoring it.
     * <p>
     * Security-relevant: a route that narrows covers only what it matches, so it cannot
     * stand for its whole path namespace. {@code ConfigValidator}'s anchor-coverage rule
     * consults this before accepting a route as closing an anchor namespace — without it,
     * a {@code methods: [GET]} member would mark the namespace covered while every other
     * verb fell through to a broader route carrying a different auth posture.
     *
     * @return {@code true} when any non-path dimension constrains the match,
     * {@code false} when the path is the only thing this matcher tests
     */
    public boolean narrowsBeyondPath() {
        return !methods.isEmpty() || host != null || !headers.isEmpty();
    }

    /**
     * A single header matcher: a presence constraint, an exact value, or both.
     * <p>
     * This record binds the operator's spelling of {@code name} verbatim; the compiled route matcher
     * lower-cases it once with {@code toLowerCase(Locale.ROOT)} and looks it up in the lower-case-keyed
     * request header map. For ASCII names — the characters an RFC 9110 field name is made of — that is
     * case-insensitive matching. Outside ASCII it is not per-character case folding: a name spelled
     * with U+0130 (capital I with dot above) and its plain-{@code I} twin lower-case to two different
     * names, so they read two different headers. The declared fields compose with AND: {@code present: true} requires the header,
     * {@code present: false} requires its absence, and {@code value} requires that exact,
     * case-sensitive value and therefore the header's presence. A matcher declaring
     * {@code present: false} together with {@code value} can never match and is refused at boot.
     *
     * @param name    the header name (mandatory), matched after {@code Locale.ROOT} lower-casing, so
     *                ASCII names match case-insensitively
     * @param present {@code true} when the header must be present, {@code false} when it must be
     *                absent, {@code null} when omitted
     * @param value   the exact, case-sensitive required value, {@code null} when omitted; refused
     *                at boot together with {@code present: false}
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
