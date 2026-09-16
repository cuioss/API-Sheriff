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

import java.util.List;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import org.jspecify.annotations.Nullable;

/**
 * The {@code redirect} terminal action (ADR-0014 Amendment A1): computes the answer a redirect
 * route sends instead of contacting an upstream.
 * <p>
 * The stage is framework-light by design — it turns a boot-resolved {@link RedirectConfig}, the raw
 * inbound query and the request's accumulated response state into a status, a {@code Location}
 * value and a cacheability verdict, and leaves writing them to the edge. The rules are fixed:
 * <ul>
 *   <li>the status is the configured one, verbatim;</li>
 *   <li>the {@code Location} is the configured {@code location}, written verbatim. It is a raw
 *       gateway path — the same convention {@code match.path_prefix} follows — so it is never
 *       prefixed with the HTTP context path, and no request placeholder is expanded;</li>
 *   <li>when {@code keep_query} is set and the request carries a query, the raw query string is
 *       appended, joined with {@code ?} or, when {@code location} already carries a query, with
 *       {@code &};</li>
 *   <li>the answer is marked {@linkplain Answer#noStore() uncacheable} when the route is
 *       effectively authenticated or the request accumulated any {@code Set-Cookie};</li>
 *   <li>the answer carries the {@linkplain Answer#vary() Vary} names of the selected route's
 *       {@code match.headers} matchers, so a shared cache keys the stored redirect on the request
 *       headers that chose it.</li>
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
     * @param redirect      the route's resolved redirect action
     * @param rawQuery      the raw inbound query string without its leading {@code ?}, or
     *                      {@code null} when the request carries none
     * @param effectiveAuth the route's materialized effective auth posture, deciding the
     *                      authenticated half of the cacheability verdict
     * @param setCookies    the {@code Set-Cookie} values the pipeline accumulated for this
     *                      response, empty when none
     * @param varyHeaderNames the request-header names the selected route's {@code match.headers}
     *                      matchers read ({@code RouteMatcher.matchHeaderNames()}), empty when the
     *                      route declares no header matcher
     * @return the status, {@code Location} value, cacheability verdict and {@code Vary} names to
     * answer with
     */
    public Answer answer(RedirectConfig redirect, @Nullable String rawQuery, AuthConfig effectiveAuth,
            List<String> setCookies, List<String> varyHeaderNames) {
        Objects.requireNonNull(redirect, "redirect");
        Objects.requireNonNull(effectiveAuth, "effectiveAuth");
        Objects.requireNonNull(setCookies, "setCookies");
        Objects.requireNonNull(varyHeaderNames, "varyHeaderNames");
        return new Answer(redirect.status(), location(redirect, rawQuery),
                requiresNoStore(effectiveAuth, setCookies), varyHeaderNames);
    }

    /**
     * Whether the redirect answer must be served uncacheable, mirroring the {@code no-store} the
     * asset terminal action already forces for {@code AccessLevel.AUTHENTICATED} content
     * ({@code AssetResponseEnvelope.governedHeaders}).
     * <p>
     * A redirect answer is a normal cacheable response: {@code 301} and {@code 308} are cacheable
     * by default under RFC 9111 section 4.2.2 heuristic freshness, so a shared cache between the
     * client and the gateway may store one and replay it. Two conditions make that unacceptable,
     * and either alone is enough:
     * <ul>
     *   <li><strong>an authenticated route</strong> — the answer is the product of one caller's
     *       credentials, so replaying it to another is a cross-user disclosure. The condition is
     *       {@code require} not {@code none}, which is exactly what the shared
     *       {@code RouteTableBuilder.effectiveAccessLevel} seam turns into
     *       {@code AccessLevel.AUTHENTICATED}: an {@code access: authenticated} anchor cannot
     *       resolve a {@code none} floor (the boot refuses both an unbacked floor and a route that
     *       weakens one), so the anchor's static {@code access} can add nothing here;</li>
     *   <li><strong>any accumulated {@code Set-Cookie}</strong> — the redirect is answered after
     *       authentication, so a re-sealed or rotated cookie-mode session rides on it. A cached
     *       copy would hand that session cookie to the next client (CWE-524 / CWE-525).</li>
     * </ul>
     * {@code Cache-Control} is not one of the gateway-owned security headers, so the
     * {@code header_modes} set/default precedence does not reach it: the verdict is the gateway's
     * alone, and a redirect answer has no origin response to defer to in any case.
     *
     * @param effectiveAuth the route's materialized effective auth posture
     * @param setCookies    the accumulated {@code Set-Cookie} values
     * @return {@code true} when the answer must carry {@code Cache-Control: no-store}
     */
    private static boolean requiresNoStore(AuthConfig effectiveAuth, List<String> setCookies) {
        return effectiveAuth.require() != Require.NONE || !setCookies.isEmpty();
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
     * @param noStore  whether the answer must carry {@code Cache-Control: no-store} because the
     *                 route is effectively authenticated or the response carries a
     *                 {@code Set-Cookie}
     * @param vary     the request-header names the edge must emit as {@code Vary}, empty when there
     *                 are none. {@code match.headers} participates in route selection, so two
     *                 redirect routes at the same address can differ only by a request header. A
     *                 public redirect carrying no {@code Set-Cookie} is a normal cacheable response
     *                 ({@code 301} and {@code 308} are heuristically cacheable under RFC 9111
     *                 section 4.2.2) and a shared cache keys it on the method and URI alone, so
     *                 without {@code Vary} it would answer a request carrying one header variant
     *                 with the redirect chosen for another (CWE-524). The names are carried whenever
     *                 the route declares header matchers, {@code noStore} or not: on a
     *                 {@code no-store} answer the header is merely inert, and making the rule
     *                 conditional would buy nothing while giving a reader a second case to reason
     *                 about
     */
    public record Answer(int status, String location, boolean noStore, List<String> vary) {

        /**
         * Canonical constructor requiring {@code location} and defensively copying {@code vary}.
         */
        public Answer {
            Objects.requireNonNull(location, "location");
            vary = vary == null ? List.of() : List.copyOf(vary);
        }
    }
}
