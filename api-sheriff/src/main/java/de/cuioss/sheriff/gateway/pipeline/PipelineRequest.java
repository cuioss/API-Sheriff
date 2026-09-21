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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import org.jspecify.annotations.Nullable;

/**
 * The framework-agnostic request carrier threaded through the fixed pipeline. The edge builds
 * one instance per inbound request from its framework-specific {@code RoutingContext} (no
 * Vert.x / Quarkus type leaks in here), then hands it to stages 0-7 in order.
 * <p>
 * <strong>Inbound fields are immutable</strong> (the raw method, path, query, headers, host,
 * peer address, and body framing, all supplied by the edge). <strong>Derived fields are
 * populated by the stages</strong> as the request flows: the {@linkplain #canonicalPath()
 * single canonical path} (stage 1), the {@linkplain #selectedRoute() selected route} (stage 2),
 * the accumulating {@linkplain #responseHeaders() response-header map} (stage 0 onward, applied
 * to every response including rejections), the multi-valued {@linkplain #responseSetCookies()
 * Set-Cookie accumulator}, and an optional {@linkplain #shortCircuitStatus() short-circuit status}
 * (e.g. a CORS preflight answered at stage 0 before auth).
 * <p>
 * Header lookups are case-insensitive: header names are normalized to lower case when the
 * instance is built, matching RFC 7230 field-name semantics.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PipelineRequest {

    private final HttpMethod method;
    private final String requestPath;
    private final List<QueryParameter> queryParameters;
    private final Map<String, List<String>> headers;
    private final @Nullable String host;
    private final @Nullable String peerAddress;
    private final long declaredContentLength;
    private final boolean bodyPresent;

    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final Map<String, String> responseDefaultHeaders = new LinkedHashMap<>();
    private final List<String> responseSetCookies = new ArrayList<>();
    private @Nullable String canonicalPath;
    private @Nullable RouteRuntime selectedRoute;
    private @Nullable Integer shortCircuitStatus;
    private @Nullable String mediatedBearer;

    private PipelineRequest(Builder builder) {
        this.method = Objects.requireNonNull(builder.method, "method");
        this.requestPath = Objects.requireNonNull(builder.requestPath, "requestPath");
        // An ordered pair sequence (never a name-keyed map) so the forwarded query keeps the inbound
        // pair order, interleaved repeated names included.
        this.queryParameters = List.copyOf(builder.queryParameters);
        this.headers = normalizeHeaders(builder.headers);
        this.host = builder.host;
        this.peerAddress = builder.peerAddress;
        this.declaredContentLength = builder.declaredContentLength;
        this.bodyPresent = builder.bodyPresent;
    }

    private static Map<String, List<String>> normalizeHeaders(Map<String, List<String>> raw) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        raw.forEach((name, values) -> normalized.merge(
                name.toLowerCase(Locale.ROOT), List.copyOf(values),
                (existing, added) -> {
                    List<String> merged = new ArrayList<>(existing);
                    merged.addAll(added);
                    return List.copyOf(merged);
                }));
        return Map.copyOf(normalized);
    }

    /**
     * @return a new builder for a {@link PipelineRequest}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the inbound request method
     */
    public HttpMethod method() {
        return method;
    }

    /**
     * @return the raw inbound request path, exactly as received (before canonicalization)
     */
    public String requestPath() {
        return requestPath;
    }

    /**
     * The inbound query parameters in their <strong>raw, still-percent-encoded wire form</strong>
     * (ADR-0047): names and values are exactly the bytes of the request-target query, split on
     * {@code &} and on each pair's first {@code =}, with no percent-decoding and no {@code +}-to-space
     * translation. This is the form the security filter validates and the forward path emits
     * verbatim, so what was validated is exactly what is forwarded. A pair without {@code =} carries a
     * {@code null} value, so its bare form survives forwarding.
     * <p>
     * The pairs form an <strong>ordered sequence in wire order</strong>, never a map grouped by name:
     * {@code a=1&b=2&a=3} stays three pairs in exactly that order, so the upstream receives the
     * request-target the gateway validated rather than a regrouped {@code a=1&a=3&b=2}.
     * <p>
     * A consumer that needs a <em>decoded</em> value (the reserved BFF parameters, for example)
     * reads it from the transport, never from this sequence.
     *
     * @return the raw inbound query pairs in wire order, immutable; empty when there is no query
     */
    public List<QueryParameter> queryParameters() {
        return queryParameters;
    }

    /**
     * @return the inbound headers, lower-case-keyed, values in inbound order, empty when none
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * @param name the header name (case-insensitive)
     * @return the first value for {@code name}, or empty when the header is absent
     */
    public Optional<String> firstHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * @param name the header name (case-insensitive)
     * @return every value for {@code name}, empty when the header is absent
     */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * @param name the header name (case-insensitive)
     * @return {@code true} when the header is present with at least one value
     */
    public boolean hasHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values != null && !values.isEmpty();
    }

    /**
     * A single-valued view of the headers (first value per name) for the compiled
     * {@link RouteRuntime#matcher() route matcher}, which matches on scalar header values.
     *
     * @return the first value per header name, lower-case-keyed
     */
    public Map<String, String> singleValueHeaders() {
        Map<String, String> single = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                single.put(name, values.getFirst());
            }
        });
        return Map.copyOf(single);
    }

    /**
     * @return the request host authority, or {@code null} when absent
     */
    public @Nullable String host() {
        return host;
    }

    /**
     * @return the immediate TCP peer address, or {@code null} when the edge did not supply one
     */
    public @Nullable String peerAddress() {
        return peerAddress;
    }

    /**
     * @return the declared {@code Content-Length} in bytes, or {@code -1} when none was declared
     */
    public long declaredContentLength() {
        return declaredContentLength;
    }

    /**
     * @return {@code true} when the inbound request carries (or announces) a body
     */
    public boolean bodyPresent() {
        return bodyPresent;
    }

    /**
     * @return the single canonical path resolved by stage 1, or {@code null} before stage 1 runs
     */
    public @Nullable String canonicalPath() {
        return canonicalPath;
    }

    /**
     * Records the single canonical path every later stage consumes (GW-01 single-path invariant).
     *
     * @param canonicalPath the validated, normalized path
     */
    public void canonicalPath(String canonicalPath) {
        this.canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath");
    }

    /**
     * @return the route selected by stage 2, or {@code null} before selection
     */
    public @Nullable RouteRuntime selectedRoute() {
        return selectedRoute;
    }

    /**
     * Records the route selected by stage 2.
     *
     * @param selectedRoute the matched route runtime
     */
    public void selectedRoute(RouteRuntime selectedRoute) {
        this.selectedRoute = Objects.requireNonNull(selectedRoute, "selectedRoute");
    }

    /**
     * The mutable <em>set</em>-header map stage 0 seeds and later stages append to; the edge applies
     * it to every response, including rejections, overwriting any value an upstream response carried
     * for the same name.
     * <p>
     * A header name lives in exactly one of this map and {@link #responseDefaultHeaders()}: the
     * security-headers stage seeds a gateway-owned header into one or the other according to its
     * {@code header_modes} entry, and removes the name from both before re-seeding.
     *
     * @return the accumulating set-mode response headers
     */
    public Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    /**
     * The mutable <em>default</em>-header map: gateway-owned security headers whose
     * {@code header_modes} entry is {@code default}. The proxy relay applies an entry only when the
     * upstream response did not carry that name; a response the gateway authors itself has no origin
     * header to defer to, so it applies every entry. A name lives in exactly one of this map and
     * {@link #responseHeaders()}.
     *
     * @return the accumulating default-mode response headers, empty when no header is in default mode
     */
    public Map<String, String> responseDefaultHeaders() {
        return responseDefaultHeaders;
    }

    /**
     * The headers a response the gateway authors itself carries: the default-mode entries overlaid
     * by the set-mode entries. The two maps hold disjoint names, so the overlay order only matters
     * as a guard, and it is the one that keeps a set-mode value authoritative.
     *
     * @return an immutable snapshot of both header maps merged, empty when both are empty
     */
    public Map<String, String> gatewayAuthoredResponseHeaders() {
        Map<String, String> merged = new LinkedHashMap<>(responseDefaultHeaders);
        merged.putAll(responseHeaders);
        return Map.copyOf(merged);
    }

    /**
     * The accumulated {@code Set-Cookie} values, in emission order. {@code Set-Cookie} is the one
     * legitimately multi-valued response header (RFC 6265 §3), so it is carried here rather than in
     * the single-valued {@link #responseHeaders() header map}: the session runtime can emit a
     * clearing cookie and a login-binding cookie on the very same response, and a single-valued slot
     * would silently drop one of them — leaving a revoked session cookie in the browser. The edge
     * writes each value as its own header line; it never comma-joins them.
     *
     * @return an immutable snapshot of the accumulated {@code Set-Cookie} values, empty when none
     */
    public List<String> responseSetCookies() {
        return List.copyOf(responseSetCookies);
    }

    /**
     * Appends one {@code Set-Cookie} value to the response. Every appended value reaches the client
     * as its own header line — appending never replaces a previously appended value.
     *
     * @param setCookieHeader the fully-rendered {@code Set-Cookie} header value
     */
    public void addResponseSetCookie(String setCookieHeader) {
        responseSetCookies.add(Objects.requireNonNull(setCookieHeader, "setCookieHeader"));
    }

    /**
     * @return the short-circuit status the edge must return immediately (e.g. a CORS preflight
     *         answered at stage 0), or empty when the request should flow through the full pipeline
     */
    public Optional<Integer> shortCircuitStatus() {
        return Optional.ofNullable(shortCircuitStatus);
    }

    /**
     * Marks the request as answered before reaching the upstream — the edge returns {@code status}
     * with the accumulated {@link #responseHeaders()} and no upstream call.
     *
     * @param status the HTTP status to return
     */
    public void shortCircuit(int status) {
        this.shortCircuitStatus = status;
    }

    /**
     * @return the mediated access token the {@code require: session} stage-4 runtime resolved for
     *         automatic upstream injection, or empty when no session mediation applied. The forward
     *         policy stage renders it as the outbound {@code Authorization: Bearer} header so the
     *         upstream sees only the mediated bearer, never the opaque session cookie.
     */
    public Optional<String> mediatedBearer() {
        return Optional.ofNullable(mediatedBearer);
    }

    /**
     * Records the mediated access token the session stage-4 runtime injects automatically as the
     * upstream {@code Authorization: Bearer} (never operator-configured). The token material stays
     * server-side up to this point; the forward stage is its sole consumer.
     *
     * @param mediatedBearer the raw mediated access token
     */
    public void mediatedBearer(String mediatedBearer) {
        this.mediatedBearer = Objects.requireNonNull(mediatedBearer, "mediatedBearer");
    }

    /**
     * Builder collecting the immutable inbound components of a {@link PipelineRequest}.
     */
    public static final class Builder {

        private @Nullable HttpMethod method;
        private @Nullable String requestPath;
        private List<QueryParameter> queryParameters = List.of();
        private Map<String, List<String>> headers = Map.of();
        private @Nullable String host;
        private @Nullable String peerAddress;
        private long declaredContentLength = -1L;
        private boolean bodyPresent;

        private Builder() {
        }

        /**
         * @param method the inbound method
         * @return this builder
         */
        public Builder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        /**
         * @param requestPath the raw inbound path
         * @return this builder
         */
        public Builder requestPath(String requestPath) {
            this.requestPath = requestPath;
            return this;
        }

        /**
         * @param queryParameters the inbound query pairs in their raw, still-percent-encoded wire
         *                        form, in wire order (interleaved repeated names kept where they
         *                        arrived); a {@code null} value marks a bare pair without {@code =}.
         *                        The security filter validates exactly these pairs and the forward
         *                        path emits them verbatim, in this order (ADR-0047) — never pass
         *                        decoded values here
         * @return this builder
         */
        public Builder queryParameters(List<QueryParameter> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        /**
         * @param headers the inbound headers, keyed by name (normalized to lower case at build)
         * @return this builder
         */
        public Builder headers(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * @param host the request host authority
         * @return this builder
         */
        public Builder host(@Nullable String host) {
            this.host = host;
            return this;
        }

        /**
         * @param peerAddress the immediate TCP peer address
         * @return this builder
         */
        public Builder peerAddress(@Nullable String peerAddress) {
            this.peerAddress = peerAddress;
            return this;
        }

        /**
         * @param declaredContentLength the declared {@code Content-Length}, or {@code -1} when absent
         * @return this builder
         */
        public Builder declaredContentLength(long declaredContentLength) {
            this.declaredContentLength = declaredContentLength;
            return this;
        }

        /**
         * @param bodyPresent whether the request carries or announces a body
         * @return this builder
         */
        public Builder bodyPresent(boolean bodyPresent) {
            this.bodyPresent = bodyPresent;
            return this;
        }

        /**
         * @return the assembled immutable-inbound {@link PipelineRequest}
         */
        public PipelineRequest build() {
            return new PipelineRequest(this);
        }
    }
}
