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
package de.cuioss.sheriff.api.edge;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerResponse;

/**
 * Stage 7 — the streamed response relay.
 * <p>
 * The upstream status and (hop-by-hop-stripped) headers are copied to the client response, then the
 * upstream body is <strong>streamed back with backpressure</strong> via Vert.x
 * {@link HttpClientResponse#pipeTo(io.vertx.core.streams.WriteStream) pipeTo} — the body is never
 * materialized. Header rules:
 * <ul>
 *   <li><strong>Hop-by-hop headers</strong> (RFC 7230 §6.1) plus length/framing headers Vert.x
 *       recomputes are stripped in the response direction.</li>
 *   <li><strong>Conditional-response headers</strong> ({@code ETag} / {@code Last-Modified}) pass
 *       through only when the route enables {@code not_modified}; on a disabled route they are
 *       stripped so no validator ever reaches the client. A {@code 304} on an enabled route relays
 *       untouched.</li>
 *   <li><strong>Stage-0 security headers</strong> accumulated on the response are applied last, so
 *       gateway-controlled headers win over any upstream value.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ResponseStage {

    /** Hop-by-hop (RFC 7230 §6.1) plus framing headers Vert.x recomputes. All lower case. */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    /** Conditional-response validators stripped unless the route enables {@code not_modified}. */
    private static final Set<String> CONDITIONAL_RESPONSE_HEADERS = Set.of("etag", "last-modified");

    /**
     * @param name               the upstream response-header name
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @return {@code true} when the header crosses back to the client
     */
    public static boolean isForwardableResponseHeader(String name, boolean notModifiedEnabled) {
        String lower = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP_HEADERS.contains(lower)) {
            return false;
        }
        return notModifiedEnabled || !CONDITIONAL_RESPONSE_HEADERS.contains(lower);
    }

    /**
     * Relays the upstream response to the client: status, filtered headers, stage-0 security
     * headers, then the streamed body.
     *
     * @param upstream                the upstream response (body still streaming)
     * @param client                  the client response write stream
     * @param notModifiedEnabled      whether the route honours conditional requests / responses
     * @param stageZeroSecurityHeaders the stage-0 security headers accumulated on the response
     * @return a future completing when the body has been fully streamed
     */
    public Future<Void> relay(HttpClientResponse upstream, HttpServerResponse client,
            boolean notModifiedEnabled, Map<String, String> stageZeroSecurityHeaders) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(stageZeroSecurityHeaders, "stageZeroSecurityHeaders");

        client.setStatusCode(upstream.statusCode());
        for (Map.Entry<String, String> header : upstream.headers()) {
            if (isForwardableResponseHeader(header.getKey(), notModifiedEnabled)) {
                // add (not set) so multi-valued headers such as Set-Cookie are all preserved.
                client.headers().add(header.getKey(), header.getValue());
            }
        }
        stageZeroSecurityHeaders.forEach((name, value) -> client.headers().set(name, value));
        applyResponseFraming(upstream, client);
        return upstream.pipeTo(client);
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
