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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import org.jspecify.annotations.Nullable;

/**
 * Judges whether a request on a {@code protocol: websocket} route is a valid WebSocket opening
 * handshake (RFC 6455 §4.2.1) before the edge spends anything on it — no session-relay tracking, no
 * relay sub-budget permit and no upstream dial happens for a request this gate refuses.
 * <p>
 * The rules are the gateway's own, read from the request's public method, version, request URI and
 * headers; no Vert.x internal API (such as {@code HttpUtils.canUpgradeToWebSocket} or
 * {@code HttpUtils.getWebSocketLocation}) is consulted. Together they cover every refusal the HTTP/1.1
 * upgrade path of vertx-core 4.5.34 itself answers ({@code Http1xServerRequest.webSocket()},
 * {@code Http1xServerConnection.createHandshaker}, and the Netty {@code WebSocketServerHandshaker13}
 * handshake whose failure {@code ServerWebSocketImpl.doHandshake} answers {@code 400}), so no request
 * the gate admits is refused by the HTTP stack after the edge has spent a relay permit and an upstream
 * dial on it. They are checked in this order, and the first one broken decides the answer:
 * <ol>
 *   <li>the method is <strong>{@code GET}</strong> — an opening handshake is a {@code GET}
 *       (RFC 6455 §4.1, item 2), so any other method is refused {@code 405} with {@code Allow: GET}.
 *       The gate is the only place this is enforced: a {@code protocol: websocket} route's effective
 *       {@code allowed_methods} falls back to every standard method like any other route's;</li>
 *   <li>the request is <strong>HTTP/1.1</strong> — a WebSocket is opened by the HTTP/1.1 upgrade
 *       mechanism (RFC 9110 §7.8), so any other version is refused {@code 426};</li>
 *   <li>the <strong>first</strong> {@code Connection} header line carries the token {@code upgrade} —
 *       refused {@code 426} otherwise. {@code Http1xServerConnection.createHandshaker} reads
 *       {@code Connection} through {@code getHeader}, its first value, and answers {@code 400} when that
 *       lacks {@code upgrade}, so the token on a later line alone is not enough;</li>
 *   <li>an {@code Upgrade} header line has the <strong>whole value</strong> {@code websocket} — refused
 *       {@code 426} otherwise. The Netty handshake accepts only a value equal to {@code websocket}, so
 *       {@code websocket} as one entry of a list such as {@code chat, websocket} is not enough;</li>
 *   <li>neither {@code Sec-WebSocket-Version} nor {@code Sec-WebSocket-Key} occurs on more than one
 *       header line — refused {@code 400} otherwise, before either value is read (RFC 6455 §11.3.1,
 *       §11.3.5);</li>
 *   <li>{@code Sec-WebSocket-Version} is present — refused {@code 400} otherwise — and is
 *       {@code 13} — refused {@code 426} with {@code Sec-WebSocket-Version: 13} otherwise
 *       (RFC 6455 §4.4);</li>
 *   <li>{@code Sec-WebSocket-Key} is present, is padded base64 (RFC 4648 §4) of exactly 24
 *       characters — 22 base64-alphabet characters followed by {@code ==} — and decodes to 16 bytes —
 *       refused {@code 400} otherwise (RFC 6455 §4.1, item 7);</li>
 *   <li>a declared {@code Content-Length} is at most 8192 bytes — refused {@code 413} otherwise, which
 *       retires the connection (see below). Vert.x buffers at most 8192 bytes of a handshake request
 *       body and answers a larger one {@code 413} and closes the connection; the gate refuses the same
 *       body from its declared length, before a byte of it is read. Through the pipeline the {@code 413}
 *       is reached only by a {@code GET} whose single {@code Content-Length} is a decimal number above
 *       8192, and only under the {@code security_defaults.allow_get_with_content_length_body} opt-in:
 *       the framing gate refuses a declared length on {@code GET} without that opt-in, and
 *       {@code Transfer-Encoding} on {@code GET} always, before route selection. The framing gate also
 *       refuses, on every method and whatever the opt-in, a {@code Content-Length} that is no decimal
 *       number and a second {@code Content-Length} line, so neither reaches this gate. The gate refuses
 *       them {@code 413} as well, since neither can be shown to stay within the bound; behind the
 *       framing gate that branch is defence in depth;</li>
 *   <li>the request URI parses as a {@link URI} — refused {@code 400} otherwise. Vert.x composes the
 *       handshake's WebSocket location from it and answers {@code 400} when it cannot be parsed. The
 *       location's other part, the {@code Host} header, is concatenated as it is and never refused
 *       there, so the gate adds no {@code Host} rule.</li>
 * </ol>
 * Both header rules compare case-insensitively and read the headers as the upgrade path does: the
 * first {@code Connection} line as a comma-separated list of options (RFC 9110 §7.6.1), and every
 * {@code Upgrade} line as one value, trimmed.
 * <p>
 * <strong>A declared body above the bound retires the connection, whichever rule refuses.</strong> The
 * gate never reads a request body, so on HTTP/1.x the bytes behind a declared length above 8192 bytes,
 * or one that is no decimal number, would frame the next request on the keep-alive connection. Every
 * refusal of such a request therefore closes the connection once the answer has been written, and the
 * answer carries the {@code close} connection option (see {@link Verdict.Refused#retiresConnection()}):
 * the {@code 413}, and equally an earlier {@code 405}, {@code 426} or {@code 400}, which keeps its own
 * status — the rules decide the answer in their order, the declared length decides the retirement on its
 * own. A retiring {@code 426} carries {@code Connection: Upgrade, close}. On HTTP/2 a refusal ends only
 * its own stream ({@link ResponseEnds#endResponse}), so nothing is retired there.
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
    /** The status of a request sent with another method than {@code GET}. */
    static final int METHOD_NOT_ALLOWED = 405;
    /** The status of a handshake request declaring a body above {@link #MAX_HANDSHAKE_BODY}. */
    static final int CONTENT_TOO_LARGE = 413;
    /**
     * The largest handshake request body, in bytes, vertx-core 4.5.34 buffers before it answers
     * {@code 413} ({@code Http1xServerRequest.webSocket()}).
     */
    static final long MAX_HANDSHAKE_BODY = 8192;
    static final String ALLOW_HEADER = "Allow";
    static final String CONTENT_LENGTH_HEADER = "Content-Length";
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
    /** The {@code Connection} option of an answer after which the connection is closed (RFC 9112 §9.6). */
    private static final String CONNECTION_CLOSE = "close";
    /** The separator between two options of one {@code Connection} value (RFC 9110 §7.6.1). */
    private static final String OPTION_SEPARATOR = ", ";
    /** The decoded length of a valid {@code Sec-WebSocket-Key}: a 16-byte nonce. */
    private static final int KEY_LENGTH = 16;
    /**
     * The encoded form of a valid {@code Sec-WebSocket-Key}: a 16-byte nonce in padded base64
     * (RFC 4648 §4) is 22 base64-alphabet characters followed by {@code ==}.
     */
    private static final Pattern KEY_SYNTAX = Pattern.compile("[A-Za-z0-9+/]{22}==");
    /** A declared {@code Content-Length}: one or more ASCII digits, no sign (RFC 9110 §8.6). */
    private static final Pattern DECIMAL_LENGTH = Pattern.compile("\\d+");
    private static final String TOKEN_SEPARATOR = ",";

    private WebSocketUpgradeGate() {
        // static judge only
    }

    /**
     * Judges the opening handshake of a request on a {@code protocol: websocket} route.
     *
     * @param request the request to judge; only its method, version, request URI and headers are read
     * @return {@link Verdict.Accepted} for a valid handshake, otherwise the {@link Verdict.Refused}
     * answer to send
     */
    static Verdict judge(HttpServerRequest request) {
        Objects.requireNonNull(request, "request");
        return judge(request.method(), request.version(), request.uri(), request.headers());
    }

    /**
     * Judges an opening handshake given as its method, its HTTP version, its request URI and its
     * headers — the body of {@link #judge(HttpServerRequest)}.
     *
     * @param method     the request method
     * @param version    the negotiated HTTP version of the request
     * @param requestUri the request URI as received, the request-target of the request line
     * @param headers    the request headers
     * @return {@link Verdict.Accepted} for a valid handshake, otherwise the {@link Verdict.Refused}
     * answer to send
     */
    static Verdict judge(HttpMethod method, HttpVersion version, String requestUri, MultiMap headers) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(headers, "headers");
        boolean bodyAboveBound = declaresBodyAboveBound(headers.getAll(CONTENT_LENGTH_HEADER));
        Verdict verdict = firstBrokenRule(method, version, requestUri, headers, bodyAboveBound);
        // The body behind an over-bound declared length is never read, whichever rule refused the
        // request: on HTTP/1.x it would frame the next request on this keep-alive connection, so the
        // connection is retired with the answer, as Vert.x closes it. An HTTP/2 refusal ends only its
        // own stream, through the one end rule.
        if (bodyAboveBound && ResponseEnds.isHttp1(version) && verdict instanceof Verdict.Refused refused) {
            return refused.retiringConnection();
        }
        return verdict;
    }

    /**
     * The rules of {@link #judge(HttpMethod, HttpVersion, String, MultiMap)} in their order.
     *
     * @return {@link Verdict.Accepted} when no rule is broken, otherwise the refusal of the first rule
     * broken, leaving the connection open — {@link #judge(HttpMethod, HttpVersion, String, MultiMap)}
     * decides the retirement
     */
    private static Verdict firstBrokenRule(HttpMethod method, HttpVersion version, String requestUri,
            MultiMap headers, boolean bodyAboveBound) {
        if (!HttpMethod.GET.equals(method)) {
            return new Verdict.Refused(METHOD_NOT_ALLOWED, Map.of(ALLOW_HEADER, HttpMethod.GET.name()));
        }
        if (version != HttpVersion.HTTP_1_1) {
            return upgradeRequired(version, Map.of());
        }
        // Read as the upgrade path reads them: Vert.x checks the first Connection line alone (getHeader),
        // and the Netty handshake accepts an Upgrade line only when its whole value is websocket.
        if (!carriesToken(headers.get(CONNECTION_HEADER), UPGRADE_TOKEN)
                || !hasWholeValue(headers.getAll(UPGRADE_HEADER), WEBSOCKET_TOKEN)) {
            return upgradeRequired(version, Map.of());
        }
        // Neither field may appear more than once (RFC 6455 §11.3.1, §11.3.5); headers.get() would read
        // only the first line, so a repeat is refused before either value is looked at.
        if (headers.getAll(VERSION_HEADER).size() > 1 || headers.getAll(KEY_HEADER).size() > 1) {
            return new Verdict.Refused(BAD_REQUEST, Map.of());
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
        if (bodyAboveBound) {
            return new Verdict.Refused(CONTENT_TOO_LARGE, Map.of());
        }
        if (!isParseableUri(requestUri)) {
            return new Verdict.Refused(BAD_REQUEST, Map.of());
        }
        return Verdict.Accepted.INSTANCE;
    }

    /**
     * @return whether any of {@code contentLengths} declares more than {@link #MAX_HANDSHAKE_BODY}
     * bytes, or is no decimal number ({@code 1*DIGIT}, RFC 9110 §8.6) and so cannot be shown to stay
     * within that bound
     */
    private static boolean declaresBodyAboveBound(List<String> contentLengths) {
        for (String contentLength : contentLengths) {
            String declared = contentLength.trim();
            if (!DECIMAL_LENGTH.matcher(declared).matches()) {
                return true;
            }
            try {
                if (Long.parseLong(declared) > MAX_HANDSHAKE_BODY) {
                    return true;
                }
            } catch (NumberFormatException _) {
                // 1*DIGIT beyond the range of a long: far above the bound
                return true;
            }
        }
        return false;
    }

    /** @return whether {@code requestUri} parses as a {@link URI}, as Vert.x parses it */
    private static boolean isParseableUri(String requestUri) {
        try {
            new URI(requestUri); // NOSONAR java:S1848 parsing is the check, the parsed URI is not needed
            return true;
        } catch (URISyntaxException _) {
            return false;
        }
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
     * @return whether {@code value}, read as a comma-separated list, carries {@code token}, compared
     * case-insensitively; {@code false} for an absent value. An element is trimmed of spaces only, as
     * the Netty handshake's list match trims it: an element padded with a tab is not the token there,
     * so it is not the token here either.
     */
    private static boolean carriesToken(@Nullable String value, String token) {
        if (value == null) {
            return false;
        }
        for (String element : value.split(TOKEN_SEPARATOR)) {
            if (token.equalsIgnoreCase(trimSpaces(element))) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code element} without leading and trailing space characters; a tab is kept */
    private static String trimSpaces(String element) {
        int start = 0;
        int end = element.length();
        while (start < end && element.charAt(start) == ' ') {
            start++;
        }
        while (end > start && element.charAt(end - 1) == ' ') {
            end--;
        }
        return element.substring(start, end);
    }

    /**
     * @return whether any of {@code values}, trimmed, is {@code expected} as a whole, compared
     * case-insensitively
     */
    private static boolean hasWholeValue(List<String> values, String expected) {
        for (String value : values) {
            if (expected.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether {@code key} is present, is the padded base64 form of a 16-byte value and decodes
     * to the 16-byte nonce
     */
    private static boolean isValidKey(@Nullable String key) {
        if (key == null || !KEY_SYNTAX.matcher(key).matches()) {
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
         * @param status            the status to answer with, {@code 405}, {@code 426}, {@code 400} or
         *                          {@code 413}
         * @param headers           the response headers to add before the answer, in the order to write
         *                          them; empty when there are none
         * @param retiresConnection whether the connection is closed once the answer has been written —
         *                          the answer then carries the {@code close} connection option. Every
         *                          refusal of an HTTP/1.x request declaring a body above
         *                          {@link WebSocketUpgradeGate#MAX_HANDSHAKE_BODY} retires it — the
         *                          {@code 413} and an earlier {@code 405}, {@code 426} or {@code 400}
         *                          alike: the declared body is never read and would otherwise frame the
         *                          next request on the connection. No other refusal retires it
         * @since 1.0
         */
        record Refused(int status, Map<String, String> headers, boolean retiresConnection) implements Verdict {

            /**
             * Copies {@code headers}, keeping their order.
             *
             * @param status            the status to answer with
             * @param headers           the response headers to add
             * @param retiresConnection whether the connection is closed after the answer
             */
            public Refused {
                headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            }

            /**
             * A refusal that leaves the connection open.
             *
             * @param status  the status to answer with
             * @param headers the response headers to add
             */
            public Refused(int status, Map<String, String> headers) {
                this(status, headers, false);
            }

            /**
             * This refusal, retiring its connection: the same status, and headers that add the
             * {@code close} option to {@code Connection}. A {@code Connection} value the refusal already
             * carries — the {@code Upgrade} option of an HTTP/1.x {@code 426}, which every sender of
             * {@code Upgrade} must add (RFC 9110 §7.8) — is kept in front of it.
             *
             * @return the retiring refusal
             */
            Refused retiringConnection() {
                Map<String, String> retiringHeaders = new LinkedHashMap<>(headers);
                retiringHeaders.merge(CONNECTION_HEADER, CONNECTION_CLOSE,
                        (options, close) -> options + OPTION_SEPARATOR + close);
                return new Refused(status, retiringHeaders, true);
            }
        }
    }
}
