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
package de.cuioss.sheriff.gateway.edge.terminal;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import org.jspecify.annotations.Nullable;

/**
 * Judges whether a request on a {@code protocol: websocket} route is a valid WebSocket opening
 * handshake (RFC 6455 §4.2.1) before the edge spends anything on it — no session-relay tracking, no
 * relay sub-budget permit and no upstream dial happens for a request this gate refuses.
 * <p>
 * The rules are the gateway's own, read from the request's public version and headers; no Vert.x
 * internal API (such as {@code HttpUtils.canUpgradeToWebSocket}) is consulted. They are checked in
 * this order, and the first one broken decides the answer:
 * <ol>
 *   <li>the request is <strong>HTTP/1.1</strong> — a WebSocket is opened by the HTTP/1.1 upgrade
 *       mechanism (RFC 9110 §7.8), so any other version is refused {@code 426};</li>
 *   <li>{@code Connection} carries the token {@code upgrade} — refused {@code 426} otherwise;</li>
 *   <li>{@code Upgrade} carries the token {@code websocket} — refused {@code 426} otherwise;</li>
 *   <li>{@code Sec-WebSocket-Version} is present — refused {@code 400} otherwise — and is
 *       {@code 13} — refused {@code 426} with {@code Sec-WebSocket-Version: 13} otherwise
 *       (RFC 6455 §4.4);</li>
 *   <li>{@code Sec-WebSocket-Key} is present and decodes from base64 to 16 bytes — refused
 *       {@code 400} otherwise (RFC 6455 §4.1, item 7).</li>
 * </ol>
 * Token matching is case-insensitive and reads every value of the header as a comma-separated list,
 * across repeated header lines (RFC 9110 §5.3). The request method is not judged here: a
 * {@code protocol: websocket} route serves {@code GET} only, which the router has already enforced.
 * <p>
 * <strong>The headers of a {@code 426}.</strong> A {@code 426} names the protocol the client has to
 * switch to (RFC 9110 §15.5.22), so on HTTP/1.x it carries {@code Upgrade: websocket} together with
 * the {@code Connection: Upgrade} option every sender of {@code Upgrade} must add (RFC 9110 §7.8). On
 * HTTP/2 neither is written: both are connection-specific fields HTTP/2 forbids (RFC 9113 §8.2.2).
 * <p>
 * Stateless and therefore thread-safe; it only reads the request it is handed.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class WebSocketUpgradeGate {

    /** The status of a request that has to switch to the WebSocket protocol first. */
    static final int UPGRADE_REQUIRED = 426;
    /** The status of a WebSocket handshake that is malformed. */
    static final int BAD_REQUEST = 400;
    /** The one WebSocket protocol version this gateway speaks (RFC 6455 §4.1). */
    static final String SUPPORTED_VERSION = "13";
    static final String CONNECTION_HEADER = "Connection";
    static final String UPGRADE_HEADER = "Upgrade";
    static final String VERSION_HEADER = "Sec-WebSocket-Version";
    static final String KEY_HEADER = "Sec-WebSocket-Key";
    private static final String UPGRADE_TOKEN = "upgrade";
    private static final String WEBSOCKET_TOKEN = "websocket";
    /** The value {@code Connection} carries on a {@code 426} alongside {@code Upgrade}. */
    private static final String CONNECTION_UPGRADE = "Upgrade";
    /** The decoded length of a valid {@code Sec-WebSocket-Key}: a 16-byte nonce. */
    private static final int KEY_LENGTH = 16;
    private static final String TOKEN_SEPARATOR = ",";

    private WebSocketUpgradeGate() {
        // static judge only
    }

    /**
     * Judges the opening handshake of a request on a {@code protocol: websocket} route.
     *
     * @param request the request to judge; only its version and headers are read
     * @return {@link Verdict.Accepted} for a valid handshake, otherwise the {@link Verdict.Refused}
     * answer to send
     */
    static Verdict judge(HttpServerRequest request) {
        Objects.requireNonNull(request, "request");
        return judge(request.version(), request.headers());
    }

    /**
     * Judges an opening handshake given as its HTTP version and its headers — the body of
     * {@link #judge(HttpServerRequest)}.
     *
     * @param version the negotiated HTTP version of the request
     * @param headers the request headers
     * @return {@link Verdict.Accepted} for a valid handshake, otherwise the {@link Verdict.Refused}
     * answer to send
     */
    static Verdict judge(HttpVersion version, MultiMap headers) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headers, "headers");
        if (version != HttpVersion.HTTP_1_1) {
            return upgradeRequired(version, Map.of());
        }
        if (!carriesToken(headers.getAll(CONNECTION_HEADER), UPGRADE_TOKEN)
                || !carriesToken(headers.getAll(UPGRADE_HEADER), WEBSOCKET_TOKEN)) {
            return upgradeRequired(version, Map.of());
        }
        String protocolVersion = trimmedOrNull(headers.get(VERSION_HEADER));
        if (protocolVersion == null) {
            return new Verdict.Refused(BAD_REQUEST, Map.of());
        }
        if (!SUPPORTED_VERSION.equals(protocolVersion)) {
            return upgradeRequired(version, Map.of(VERSION_HEADER, SUPPORTED_VERSION));
        }
        if (!isValidKey(trimmedOrNull(headers.get(KEY_HEADER)))) {
            return new Verdict.Refused(BAD_REQUEST, Map.of());
        }
        return Verdict.Accepted.INSTANCE;
    }

    /**
     * A {@code 426} carrying {@code extraHeaders}, and on HTTP/1.x the {@code Upgrade: websocket} and
     * {@code Connection: Upgrade} pair naming the protocol to switch to.
     */
    private static Verdict upgradeRequired(HttpVersion version, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ResponseEnds.isHttp1(version)) {
            headers.put(UPGRADE_HEADER, WEBSOCKET_TOKEN);
            headers.put(CONNECTION_HEADER, CONNECTION_UPGRADE);
        }
        headers.putAll(extraHeaders);
        return new Verdict.Refused(UPGRADE_REQUIRED, headers);
    }

    /**
     * @return whether any of {@code values}, each read as a comma-separated list, carries
     * {@code token}, compared case-insensitively
     */
    private static boolean carriesToken(List<String> values, String token) {
        for (String value : values) {
            for (String element : value.split(TOKEN_SEPARATOR)) {
                if (token.equalsIgnoreCase(element.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return whether {@code key} is present and decodes from base64 to the 16-byte nonce */
    private static boolean isValidKey(@Nullable String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == KEY_LENGTH;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static @Nullable String trimmedOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The gate's answer to one request: accepted, or refused with the status and the response headers
     * to answer with.
     *
     * @since 1.0
     */
    sealed interface Verdict permits Verdict.Accepted, Verdict.Refused {

        /**
         * A valid opening handshake: the request proceeds to the relay.
         *
         * @since 1.0
         */
        enum Accepted implements Verdict {

            /** The single accepted verdict. */
            INSTANCE
        }

        /**
         * A request that is no valid opening handshake.
         *
         * @param status  the status to answer with, {@code 426} or {@code 400}
         * @param headers the response headers to add before the answer, in the order to write them;
         *                empty when there are none
         * @since 1.0
         */
        record Refused(int status, Map<String, String> headers) implements Verdict {

            /**
             * Copies {@code headers}, keeping their order.
             *
             * @param status  the status to answer with
             * @param headers the response headers to add
             */
            public Refused {
                headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            }
        }
    }
}
