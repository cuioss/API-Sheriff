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
package de.cuioss.sheriff.gateway.edge.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.QueryParameter;
import de.cuioss.sheriff.gateway.routing.Route;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.jspecify.annotations.Nullable;

/**
 * Turns the Vert.x request into the pipeline's {@link PipelineRequest}, and reads back the pieces of
 * the request every later edge step needs: the authority host, the declared body length, the parsed
 * method, the forwarded query, the selected route, the canonical path and the path remainder below a
 * route's match key.
 * <p>
 * Stateless; every method is a pure function of its arguments.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EdgeRequests {

    private static final String CONTENT_LENGTH_HEADER = "Content-Length";

    private EdgeRequests() {
        // static helpers only
    }

    /**
     * Builds the pipeline request from the Vert.x request: the raw path, the raw query pairs in wire
     * order (see {@link #rawQueryPairs}), every header, the authority host, the peer address, the
     * declared body length and whether a body is present at all.
     *
     * @param raw    the Vert.x request
     * @param method the parsed request method
     * @return the pipeline request
     */
    public static PipelineRequest buildPipelineRequest(HttpServerRequest raw, HttpMethod method) {
        String rawUri = raw.uri();
        int queryStart = rawUri.indexOf('?');
        String rawPath = queryStart < 0 ? rawUri : rawUri.substring(0, queryStart);
        long contentLength = parseContentLength(raw);
        boolean bodyPresent = contentLength > 0 || raw.headers().contains("Transfer-Encoding");
        return PipelineRequest.builder()
                .method(method)
                .requestPath(rawPath)
                .queryParameters(rawQueryPairs(raw.query()))
                .headers(toListMap(raw.headers()))
                .host(authorityHost(raw))
                .peerAddress(raw.remoteAddress() != null ? raw.remoteAddress().hostAddress() : null)
                .declaredContentLength(contentLength)
                .bodyPresent(bodyPresent)
                .build();
    }

    /**
     * The request's authority host, or {@code null} when the request declares no authority.
     * <p>
     * Reads {@link HttpServerRequest#authority()} directly instead of the deprecated
     * {@code HttpServerRequest#host()}. The two are <em>not</em> interchangeable: {@code host()}
     * returns the raw {@code Host} header, so it carries the port ({@code example.com:8443}) where
     * {@code authority().host()} does not ({@code example.com}). Substituting {@code host()} back
     * would leak the port into the reserved-path match and the security-validated request host.
     * <p>
     * Dropping the former {@code host()} fallback is nonetheless behaviour-preserving: it applied
     * only when {@code authority()} was {@code null}, which happens only when the {@code Host}
     * header is absent — and there {@code host()} is {@code null} too. A malformed header makes
     * authority parsing throw rather than return {@code null}, so that path never reached the
     * fallback either. Both host-reading sites share this one seam so the two cannot drift apart.
     *
     * @param raw the Vert.x request
     * @return the authority host, or {@code null} when the request declares no authority
     */
    public static @Nullable String authorityHost(HttpServerRequest raw) {
        var authority = raw.authority();
        return authority == null ? null : authority.host();
    }

    /**
     * @param raw the Vert.x request
     * @return the declared {@code Content-Length}, or {@code -1} when the header is absent or not a
     *         number
     */
    public static long parseContentLength(HttpServerRequest raw) {
        String value = raw.getHeader(CONTENT_LENGTH_HEADER);
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException _) {
            return -1L;
        }
    }

    /**
     * @param name the raw method token of the request
     * @return the parsed method, or empty for a method the gateway does not parse
     */
    public static Optional<HttpMethod> parseMethod(String name) {
        try {
            return Optional.of(HttpMethod.valueOf(name));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    /**
     * Renders the forwarded query from the validated raw pairs, appending each raw name and raw value
     * as it arrived and in the order the sequence carries them, so the upstream receives the pairs the
     * security filter validated, in the order it validated them (ADR-0047). A {@code null} value is a
     * bare pair and is rendered as its bare name.
     * <p>
     * <strong>The one exception to verbatim: a raw {@code ;} is written as {@code %3B}.</strong> The
     * edge splits pairs on {@code &} only, and {@code query_allow} / {@code query_deny} judge the
     * decoded name of each {@code &}-split pair, while cui-http admits a raw {@code ;} under every
     * profile. An upstream that also splits on {@code ;} would otherwise read {@code x=1;token=abc} as
     * a second parameter {@code token} the gateway never judged — HTTP parameter smuggling (CWE-235).
     * Encoding it keeps the pair one pair on the upstream too. Every route takes this path, whatever
     * its forward mode; nothing else in the pair is touched, and the decoded meaning is unchanged
     * ({@code %3B} decodes to {@code ;}), so validated still equals forwarded at the decoded level.
     *
     * @param query the validated raw pairs the forward policy kept
     * @return the rendered query with its leading {@code ?}, or the empty string for no pairs
     */
    public static String renderQuery(List<QueryParameter> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder("?");
        boolean first = true;
        for (QueryParameter parameter : query) {
            if (!first) {
                rendered.append('&');
            }
            rendered.append(encodeSemicolons(parameter.name()));
            String value = parameter.value();
            if (value != null) {
                rendered.append('=').append(encodeSemicolons(value));
            }
            first = false;
        }
        return rendered.toString();
    }

    /**
     * @param request the pipeline request
     * @return the route selected at stage 2
     * @throws IllegalStateException when no route was selected
     */
    public static Route requireSelectedRoute(PipelineRequest request) {
        Route route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Route dispatch requires the route selected at stage 2");
        }
        return route;
    }

    /**
     * @param request the pipeline request
     * @return the canonical path resolved at stage 1
     * @throws IllegalStateException when no canonical path was resolved
     */
    public static String requireCanonicalPath(PipelineRequest request) {
        String canonical = request.canonicalPath();
        if (canonical == null) {
            throw new IllegalStateException("Route dispatch requires the canonical path resolved at stage 1");
        }
        return canonical;
    }

    /**
     * The part of the canonical path below the selected route's match key — the remainder every
     * dispatch path appends to the upstream base path. An exact route matched the whole address, so
     * its remainder is empty; a prefix route strips its (trailing-slash-free) prefix.
     *
     * @param route     the selected route
     * @param canonical the request's canonical path
     * @return the remainder below the route's match key, empty for an exact route
     */
    public static String remainderAfterMatchKey(Route route, String canonical) {
        if (route.matcher().isExact()) {
            return "";
        }
        String prefix = stripTrailingSlash(route.matcher().matchKey());
        return canonical.length() >= prefix.length() ? canonical.substring(prefix.length()) : "";
    }

    /**
     * The raw, still-encoded value of the first query pair named exactly {@code name}, or {@code null}
     * when no such pair exists or it is bare (no {@code =}).
     *
     * @param request the pipeline request
     * @param name    the exact pair name
     * @return the raw value, or {@code null}
     */
    public static @Nullable String firstRawQueryValue(PipelineRequest request, String name) {
        for (QueryParameter parameter : request.queryParameters()) {
            if (name.equals(parameter.name())) {
                return parameter.value();
            }
        }
        return null;
    }

    /**
     * Splits the raw request-target query into its still-percent-encoded pairs — the form the
     * security filter validates and the forward path emits verbatim (ADR-0047).
     * <p>
     * The query is split on {@code &} and each pair on its <em>first</em> {@code =}; empty segments
     * are skipped, so {@code a=1&&b=2} yields the same pair count Vert.x reports. Names and values are
     * left exactly as they arrived: no percent-decoding and no {@code +}-to-space translation, because
     * decoding here would hand the cui-http pipelines — which decode themselves — an already-decoded
     * value and would make the forwarded bytes differ from the validated ones. The pairs are kept as
     * an ordered sequence in wire order and are <em>never grouped by name</em>: {@code a=1&b=2&a=3}
     * stays three pairs in that order, so the forward path emits the request-target the filter
     * validated rather than a regrouped {@code a=1&a=3&b=2}. A pair without {@code =} is carried as a
     * {@code null} value so the forward path can emit its bare form ({@code flag}, not
     * {@code flag=}) verbatim.
     *
     * @param rawQuery the raw query without its leading {@code ?}, or {@code null} when the request
     *                 carries none
     * @return the raw pairs in wire order, immutable; empty when there is no query
     */
    private static List<QueryParameter> rawQueryPairs(@Nullable String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return List.of();
        }
        List<QueryParameter> pairs = new ArrayList<>();
        for (String segment : rawQuery.split("&", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            int separator = segment.indexOf('=');
            String name = separator < 0 ? segment : segment.substring(0, separator);
            String value = separator < 0 ? null : segment.substring(separator + 1);
            pairs.add(new QueryParameter(name, value));
        }
        return List.copyOf(pairs);
    }

    private static Map<String, List<String>> toListMap(MultiMap multiMap) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : multiMap.names()) {
            map.put(name, List.copyOf(multiMap.getAll(name)));
        }
        return map;
    }

    /**
     * Rewrites every raw {@code ;} of a forwarded query name or value as {@code %3B}, leaving every
     * other byte untouched (see {@link #renderQuery}).
     */
    private static String encodeSemicolons(String raw) {
        return raw.replace(";", "%3B");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
