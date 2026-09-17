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
package de.cuioss.sheriff.gateway.routing;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
import org.jspecify.annotations.Nullable;

/**
 * The compiled form of a route's {@code match} block, assembled once at boot. Matchers
 * compose with AND semantics: a request matches only when the path matcher holds AND (when
 * constrained) the method, host, and every header matcher hold.
 * <p>
 * The path matcher is either an exact path or a prefix. An exact path matches by string
 * equality against the canonical request path — no normalization, so a trailing slash is
 * significant ({@code /a} does not match {@code /a/}). A prefix matches a path at or below it
 * on a segment boundary ({@code /proxy} matches {@code /proxy} and {@code /proxy/x} but not
 * {@code /proxy-helper}).
 * <p>
 * This is the match test only; the effective {@code allowed_methods} verb gate (405) is
 * carried separately on {@link RouteRuntime}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteMatcher {

    private final String matchKey;
    private final boolean exact;
    private final Set<HttpMethod> matchMethods;
    private final @Nullable String host;
    private final List<HeaderMatcher> headers;
    private final List<String> matchHeaderNames;

    private RouteMatcher(String matchKey, boolean exact, Set<HttpMethod> matchMethods, @Nullable String host,
            List<HeaderMatcher> headers) {
        this.matchKey = matchKey;
        this.exact = exact;
        this.matchMethods = matchMethods;
        this.host = host;
        this.headers = headers;
        this.matchHeaderNames = headers.stream().map(HeaderMatcher::name).distinct().toList();
    }

    /**
     * Compiles a matcher from the resolved {@code match} block.
     *
     * @param match the resolved match configuration
     * @return the compiled matcher
     */
    public static RouteMatcher from(MatchConfig match) {
        Objects.requireNonNull(match, "match");
        Set<HttpMethod> methods = match.methods().isEmpty()
                ? EnumSet.noneOf(HttpMethod.class)
                : EnumSet.copyOf(match.methods());
        return new RouteMatcher(match.matchKey(), match.isExact(), methods, match.host(),
                List.copyOf(match.headers()));
    }

    /**
     * Returns the route's declared path matcher value — the exact {@code path} for an exact
     * route, the {@code path_prefix} otherwise. The dispatch paths strip it from the request
     * path to obtain the upstream remainder, which is empty for an exact route.
     *
     * @return the route's match key
     */
    public String matchKey() {
        return matchKey;
    }

    /**
     * Returns whether this matcher is an exact-path matcher.
     *
     * @return {@code true} for an exact {@code path} matcher, {@code false} for a prefix matcher
     */
    public boolean isExact() {
        return exact;
    }

    /**
     * Returns the request-header names this route's {@code match.headers} matchers read, in
     * declaration order with duplicates collapsed. Empty when the route declares no header matcher.
     * <p>
     * A header matcher makes <em>route selection</em> depend on a request header, so a cacheable
     * response served by such a route genuinely varies by that header. A shared cache keys a stored
     * response on the method and the request URI, never on an arbitrary request header, so without a
     * matching {@code Vary} it would replay one variant's answer to a request carrying a different
     * one (CWE-524). Emitting the response side of that contract needs the names, and this is the
     * only place they survive route compilation — {@link #matches} consumes the matchers themselves
     * and reports a boolean. Computed once at boot; the request path only reads it.
     *
     * @return the declared matcher header names, never {@code null}
     */
    public List<String> matchHeaderNames() {
        return matchHeaderNames;
    }

    /**
     * Tests whether {@code path} is covered by this route's path matcher: string equality for an
     * exact matcher (no normalization, trailing slash significant), the segment-boundary prefix
     * rule for a prefix matcher.
     *
     * @param path the canonical request path
     * @return {@code true} when the path equals the exact path, or is at or below the prefix
     */
    public boolean matchesPath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.equals(matchKey)) {
            return true;
        }
        if (exact) {
            return false;
        }
        return path.startsWith(matchKey.endsWith("/") ? matchKey : matchKey + "/");
    }

    /**
     * Applies the full matcher set (path AND method AND host AND headers).
     *
     * @param path           the request path
     * @param method         the request method
     * @param requestHost    the request host, {@code null} when absent
     * @param requestHeaders the request headers, keyed by name
     * @return {@code true} when every declared matcher holds
     */
    public boolean matches(String path, HttpMethod method, @Nullable String requestHost,
            Map<String, String> requestHeaders) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestHeaders, "requestHeaders");
        if (!matchesPath(path)) {
            return false;
        }
        if (!matchMethods.isEmpty() && !matchMethods.contains(method)) {
            return false;
        }
        if (host != null && !host.equals(requestHost)) {
            return false;
        }
        return headersMatch(requestHeaders);
    }

    private boolean headersMatch(Map<String, String> requestHeaders) {
        for (HeaderMatcher header : headers) {
            String actual = requestHeaders.get(header.name());
            String expectedValue = header.value();
            if (expectedValue != null) {
                if (!expectedValue.equals(actual)) {
                    return false;
                }
            } else if (Boolean.TRUE.equals(header.present()) && actual == null) {
                return false;
            }
        }
        return true;
    }
}
