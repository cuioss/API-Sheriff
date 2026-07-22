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
package de.cuioss.sheriff.gateway.routing;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;

import org.jspecify.annotations.Nullable;

/**
 * The compiled form of a route's {@code match} block, assembled once at boot. Matchers
 * compose with AND semantics: a request matches only when the path is at or below the
 * prefix on a segment boundary AND (when constrained) the method, host, and every header
 * matcher hold.
 * <p>
 * This is the match test only; the effective {@code allowed_methods} verb gate (405) is
 * carried separately on {@link RouteRuntime}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteMatcher {

    private final String pathPrefix;
    private final Set<HttpMethod> matchMethods;
    private final Optional<String> host;
    private final List<HeaderMatcher> headers;

    private RouteMatcher(String pathPrefix, Set<HttpMethod> matchMethods, Optional<String> host,
            List<HeaderMatcher> headers) {
        this.pathPrefix = pathPrefix;
        this.matchMethods = matchMethods;
        this.host = host;
        this.headers = headers;
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
        return new RouteMatcher(match.pathPrefix(), methods, match.host(), List.copyOf(match.headers()));
    }

    /**
     * @return the route's literal {@code path_prefix}
     */
    public String pathPrefix() {
        return pathPrefix;
    }

    /**
     * Tests whether {@code path} is covered by this route's prefix on a segment boundary.
     *
     * @param path the request path
     * @return {@code true} when the path is at or below the prefix
     */
    public boolean matchesPrefix(String path) {
        Objects.requireNonNull(path, "path");
        if (path.equals(pathPrefix)) {
            return true;
        }
        return pathPrefix.endsWith("/") ? path.startsWith(pathPrefix) : path.startsWith(pathPrefix + "/");
    }

    /**
     * Applies the full matcher set (prefix AND method AND host AND headers).
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
        if (!matchesPrefix(path)) {
            return false;
        }
        if (!matchMethods.isEmpty() && !matchMethods.contains(method)) {
            return false;
        }
        if (host.isPresent() && !host.get().equals(requestHost)) {
            return false;
        }
        return headersMatch(requestHeaders);
    }

    private boolean headersMatch(Map<String, String> requestHeaders) {
        for (HeaderMatcher header : headers) {
            String actual = requestHeaders.get(header.name());
            Optional<String> expectedValue = header.value();
            if (expectedValue.isPresent()) {
                if (!expectedValue.get().equals(actual)) {
                    return false;
                }
            } else if (header.present().orElse(Boolean.FALSE) && actual == null) {
                return false;
            }
        }
        return true;
    }
}
