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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerResponse;
import org.jspecify.annotations.Nullable;

/**
 * Stage 7 — the streamed response relay.
 * <p>
 * The upstream status and (hop-by-hop-stripped) headers are copied to the client response, then the
 * upstream body is <strong>streamed back with backpressure</strong> via Vert.x
 * {@link HttpClientResponse#pipeTo(io.vertx.core.streams.WriteStream) pipeTo} — the body is never
 * materialized. Header rules:
 * <ul>
 *   <li><strong>Connection-specific headers</strong> plus the length/framing headers Vert.x
 *       recomputes are stripped in the response direction, per the shared
 *       {@link ConnectionHeaders} policy the asset envelope reads too.</li>
 *   <li><strong>Conditional-response headers</strong> ({@code ETag} / {@code Last-Modified}) pass
 *       through only when the route enables {@code not_modified}; on a disabled route they are
 *       stripped so no validator ever reaches the client. A {@code 304} on an enabled route relays
 *       untouched.</li>
 *   <li><strong>{@code Location}</strong> is relayed unchanged unless the route opts into
 *       {@code upstream.rewrite_location}: {@link #relay} then maps a value pointing inside the route
 *       upstream back onto the route's match key through the route's {@link LocationRewriter}, and
 *       leaves every other value — a foreign origin above all — untouched. {@link #relayWithTrailers}
 *       applies no rewrite: the gRPC path carries no redirect.</li>
 *   <li><strong>Gateway headers</strong> accumulated on the request are applied last, in two maps whose
 *       names never overlap: a <em>set</em>-header replaces any upstream value of that name, a
 *       <em>default</em>-header (a gateway-owned security header whose {@code header_modes} entry is
 *       {@code default}) is added only when the upstream response carried no value for that name, so an
 *       origin-set header is relayed untouched.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ResponseStage {

    /** Conditional-response validators stripped unless the route enables {@code not_modified}. */
    private static final Set<String> CONDITIONAL_RESPONSE_HEADERS = Set.of("etag", "last-modified");

    private static final String LOCATION_HEADER = "Location";

    /**
     * @param name               the upstream response-header name
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @return {@code true} when the header crosses back to the client
     */
    public static boolean isForwardableResponseHeader(String name, boolean notModifiedEnabled) {
        Objects.requireNonNull(name, "name");
        // The hop-by-hop / framing judgement is ConnectionHeaders', shared with the asset
        // envelope: one protocol rule, read by both paths that answer a client. Only the
        // conditional-validator gate below is this stage's own, since it turns on a route flag.
        if (ConnectionHeaders.isConnectionSpecific(name)) {
            return false;
        }
        return notModifiedEnabled || !CONDITIONAL_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Relays the upstream response to the client: status, filtered headers (with an upstream
     * {@code Location} mapped through the route's rewriter when it opts in), the gateway headers, then
     * the streamed body.
     *
     * @param upstream           the upstream response (body still streaming)
     * @param client             the client response write stream
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @param locationRewriter   the route's {@code upstream.rewrite_location} mapping, or
     *                           {@code null} to relay {@code Location} unchanged
     * @param setHeaders         the set-mode gateway headers, overwriting an upstream value of the same name
     * @param defaultHeaders     the default-mode gateway headers, added only for a name the upstream
     *                           response did not carry
     * @return a future completing when the body has been fully streamed
     */
    public Future<Void> relay(HttpClientResponse upstream, HttpServerResponse client,
            boolean notModifiedEnabled, @Nullable LocationRewriter locationRewriter,
            Map<String, String> setHeaders, Map<String, String> defaultHeaders) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(setHeaders, "setHeaders");
        Objects.requireNonNull(defaultHeaders, "defaultHeaders");

        client.setStatusCode(upstream.statusCode());
        for (Map.Entry<String, String> header : upstream.headers()) {
            String name = header.getKey();
            if (isForwardableResponseHeader(name, notModifiedEnabled)) {
                // add (not set) so multi-valued headers such as Set-Cookie are all preserved.
                client.headers().add(name, relayedHeaderValue(name, header.getValue(), locationRewriter));
            }
        }
        applyGatewayHeaders(upstream, client, setHeaders, defaultHeaders);
        applyResponseFraming(upstream, client);
        return upstream.pipeTo(client);
    }

    /**
     * Applies the gateway headers to a relayed response: every set-header overwrites, every
     * default-header is added only when the upstream response carried no value for that name. The
     * upstream check is case-insensitive, as header names are.
     */
    private static void applyGatewayHeaders(HttpClientResponse upstream, HttpServerResponse client,
            Map<String, String> setHeaders, Map<String, String> defaultHeaders) {
        setHeaders.forEach((name, value) -> client.headers().set(name, value));
        defaultHeaders.forEach((name, value) -> {
            if (!upstream.headers().contains(name)) {
                client.headers().set(name, value);
            }
        });
    }

    /**
     * The value a forwardable upstream response header carries to the client: an upstream
     * {@code Location} is mapped through the route's rewriter when the route opts into
     * {@code upstream.rewrite_location}; every other header — and {@code Location} on a route that
     * does not opt in — keeps its upstream value.
     *
     * @param name             the upstream response-header name
     * @param value            the upstream response-header value
     * @param locationRewriter the route's {@code upstream.rewrite_location} mapping, or {@code null}
     * @return the value relayed to the client
     */
    static String relayedHeaderValue(String name, String value, @Nullable LocationRewriter locationRewriter) {
        if (locationRewriter != null && LOCATION_HEADER.equalsIgnoreCase(name)) {
            return locationRewriter.rewrite(value);
        }
        return value;
    }

    /**
     * Relays the upstream response to the client <strong>including its trailing headers</strong> —
     * the gRPC path, where {@code grpc-status} / {@code grpc-message} are carried in the HTTP/2
     * response trailers (or, in the trailers-only case, the leading headers). The status and filtered
     * headers are copied exactly as {@link #relay}, then the body is streamed with the client response
     * held open ({@code endOnComplete(false)}); once the upstream body — and therefore its trailers —
     * has fully arrived, the upstream trailers are copied onto the client response and it is ended, so
     * the client observes the gRPC status. The response is set chunked so trailers are framed on an
     * HTTP/1.1 client (HTTP/2 ignores the flag and frames trailers natively).
     *
     * @param upstream           the upstream response (body + trailers still streaming)
     * @param client             the client response write stream
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @param setHeaders         the set-mode gateway headers, overwriting an upstream value of the same name
     * @param defaultHeaders     the default-mode gateway headers, added only for a name the upstream
     *                           response did not carry in its leading headers
     * @return a future completing when the body and trailers have been fully relayed
     */
    public Future<Void> relayWithTrailers(HttpClientResponse upstream, HttpServerResponse client,
            boolean notModifiedEnabled, Map<String, String> setHeaders, Map<String, String> defaultHeaders) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(setHeaders, "setHeaders");
        Objects.requireNonNull(defaultHeaders, "defaultHeaders");

        client.setStatusCode(upstream.statusCode());
        for (Map.Entry<String, String> header : upstream.headers()) {
            if (isForwardableResponseHeader(header.getKey(), notModifiedEnabled)) {
                client.headers().add(header.getKey(), header.getValue());
            }
        }
        applyGatewayHeaders(upstream, client, setHeaders, defaultHeaders);
        // gRPC trailers require a chunked (HTTP/1.1) or HTTP/2 response frame.
        client.setChunked(true);

        Promise<Void> relayed = Promise.promise();
        upstream.pipe().endOnComplete(false).to(client).onComplete(piped -> {
            if (piped.failed()) {
                relayed.fail(piped.cause());
                return;
            }
            for (Map.Entry<String, String> trailer : upstream.trailers()) {
                client.putTrailer(trailer.getKey(), trailer.getValue());
            }
            client.end().onComplete(ended -> {
                if (ended.succeeded()) {
                    relayed.complete();
                } else {
                    relayed.fail(ended.cause());
                }
            });
        });
        return relayed.future();
    }

    /**
     * Re-establishes the client response body framing after {@link #isForwardableResponseHeader
     * hop-by-hop stripping} removed the upstream framing headers. Only {@code Transfer-Encoding} is
     * hop-by-hop and must be recomputed by the client protocol; {@code Content-Length} is end-to-end
     * and — because the body is relayed byte-for-byte — remains accurate. Preserve a declared
     * upstream {@code Content-Length} so an HTTP/1.1 client receives a well-framed fixed-length body;
     * when the upstream framed the body as chunked (no {@code Content-Length}), stream the client
     * response chunked. Without this an HTTP/1.1 response defaults to {@code Content-Length: 0} and
     * the streamed body is silently dropped (HTTP/2 ignores both signals and is unaffected).
     */
    private static void applyResponseFraming(HttpClientResponse upstream, HttpServerResponse client) {
        String contentLength = upstream.getHeader("Content-Length");
        if (contentLength != null) {
            client.putHeader("Content-Length", contentLength);
        } else if (mayCarryBody(upstream.statusCode())) {
            client.setChunked(true);
        }
    }

    /**
     * @param status the relayed response status
     * @return {@code true} unless the status forbids a message body (1xx, 204, 304)
     */
    static boolean mayCarryBody(int status) {
        return status != 204 && status != 304 && (status < 100 || status >= 200);
    }
}
