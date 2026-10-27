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
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/2 stream-abuse bounds of the inbound transport (threat {@code GW-08}) and the HTTP/2 framing
 * of a forwarded request, proven against the primary instance of the native image over TLS with ALPN
 * {@code h2} — the shape every real HTTP/2 client reaches the gateway in.
 * <p>
 * <strong>What each case proves.</strong>
 * <ul>
 *   <li><em>{@code HTTP2-Settings} does not cross</em> — a client {@code HTTP2-Settings} header is a
 *       hop-by-hop artefact of the HTTP/1.1 upgrade and is never forwarded, while a client header the
 *       route forwards does reach the echo upstream on the same request, which is the matched control
 *       that the echo shows client headers at all.</li>
 *   <li><em>A declared body keeps its length</em> — an {@code h2} request with a body reaches the
 *       HTTP/1.1 echo upstream with a {@code Content-Length} equal to the bytes sent, and the body
 *       whole.</li>
 *   <li><em>A Rapid-Reset flood ends its connection</em> — {@value #FLOOD_RESETS} streams, each reset
 *       by the client straight behind the {@code HEADERS} frame that opened it, go past the pinned limit
 *       of {@value #RESET_LIMIT} client resets per window: the connection is ended with
 *       {@code GOAWAY ENHANCE_YOUR_CALM}, and the gateway stays ready and serves a fresh
 *       connection.</li>
 *   <li><em>A {@code CONTINUATION} flood drops its connection</em> — a header block left open across
 *       {@code CONTINUATION} frames and grown well past the 16 KiB header-list bound is never answered:
 *       the connection is ended with an error {@code GOAWAY}, and the gateway stays ready.</li>
 * </ul>
 * The two floods are written frame by frame over a raw TLS socket ({@link RawTlsHttp2Connection}): an
 * HTTP/2 client library neither resets a stream straight behind its {@code HEADERS} frame nor leaves a
 * header block open. The unit-level matched controls — resets below the limit, gateway-sent
 * {@code NO_ERROR} resets, a header block within the bound spread over {@code CONTINUATION} frames —
 * are {@code GatewayEdgeHttp2AbuseTest}; {@link Http2EarlyResponseResetIT} is the wire-level control
 * that four hundred gateway-reset uploads on one connection never trip the guard.
 * <p>
 * The echo upstream is go-httpbin behind {@code /proxy/forward-all}, the route with no forward block,
 * so every client header the gateway does not own crosses it.
 * <p>
 * Runs in the sequential execution: the floods end only their own connections, but a concurrent suite
 * sharing the primary instance would see its readiness probed mid-flood.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("HTTP/2 over TLS: HTTP2-Settings stripped, body length kept, Rapid-Reset and CONTINUATION floods ended")
class Http2StreamAbuseIT extends BaseIntegrationTest {

    private static final String DEFAULT_TEST_PORT = "10443";

    /** The forward-all echo route: go-httpbin's {@code /anything} echo, every non-owned client header forwarded. */
    private static final String ECHO_PATH = "/proxy/forward-all";

    /** The pinned per-connection Rapid-Reset limit, restated so a change to it is noticed here. */
    private static final int RESET_LIMIT = 200;

    /** Client resets the flood sends on one connection: well past the limit. */
    private static final int FLOOD_RESETS = RESET_LIMIT + 50;

    /** The header-list bound in bytes, restated so a change to it is noticed here. */
    private static final int HEADER_LIST_BOUND = 16 * 1024;

    /** The size of the header value each {@code CONTINUATION} frame of the flood carries. */
    private static final int FLOOD_FIELD_BYTES = 1024;

    /** A syntactically valid {@code HTTP2-Settings} value: base64url of one SETTINGS parameter. */
    private static final String HTTP2_SETTINGS_VALUE = "AAMAAABkAAQAoAAAAAIAAAAA";

    /** HTTP/2 field names are lower case on the wire (RFC 9113 §8.2.1); the echo canonicalises them. */
    private static final String HTTP2_SETTINGS = "http2-settings";
    private static final String CONTROL_HEADER = "x-echo-control";

    private static final long ENHANCE_YOUR_CALM = 0xbL;
    private static final long NO_ERROR = 0x0L;

    private static final long CALL_TIMEOUT_SECONDS = 20L;

    private Vertx vertx;
    private int port;
    private String rootPath;

    @BeforeEach
    void setUpVertx() {
        vertx = Vertx.vertx();
        port = Integer.parseInt(System.getProperty("test.https.port", DEFAULT_TEST_PORT));
        rootPath = RootPaths.normalize(System.getProperty("test.http.root-path", "/"));
    }

    @AfterEach
    void tearDownVertx() throws Exception {
        await(vertx.close(), "Vert.x to close");
    }

    @Test
    @DisplayName("a client HTTP2-Settings header does not reach the echo upstream, a forwarded header does")
    void clientHttp2SettingsHeaderIsNotForwarded() throws Exception {
        String control = "control-" + UUID.randomUUID();
        RequestOptions request = new RequestOptions().setMethod(HttpMethod.GET).setHost("localhost").setPort(port)
                .setURI(rootPath + ECHO_PATH + "/http2-settings")
                .putHeader(HTTP2_SETTINGS, HTTP2_SETTINGS_VALUE)
                .putHeader(CONTROL_HEADER, control);

        Echo echo = exchange(request, null);

        assertAll("the forwarded header set of an h2 request",
                () -> assertEquals(HttpVersion.HTTP_2, echo.version(), "precondition: ALPN negotiated h2"),
                () -> assertEquals(200, echo.status(), () -> "the echo route answers: " + echo.body()),
                () -> assertEquals(Optional.of(control), echo.header(CONTROL_HEADER),
                        () -> "matched control: a forwarded client header reaches the echo upstream: " + echo.body()),
                () -> assertEquals(Optional.empty(), echo.header(HTTP2_SETTINGS),
                        () -> "HTTP2-Settings must never be forwarded: " + echo.body()));
    }

    @Test
    @DisplayName("an h2 request with a declared body reaches the HTTP/1.1 upstream with the matching Content-Length")
    void declaredBodyReachesTheUpstreamWithItsLength() throws Exception {
        String body = "h2-body-" + UUID.randomUUID() + "-" + "x".repeat(2048);
        int length = body.getBytes(StandardCharsets.US_ASCII).length;
        RequestOptions request = new RequestOptions().setMethod(HttpMethod.POST).setHost("localhost").setPort(port)
                .setURI(rootPath + ECHO_PATH + "/h2-body")
                .putHeader("content-type", "text/plain")
                .putHeader("content-length", Integer.toString(length));

        Echo echo = exchange(request, body);

        assertAll("the h2 -> HTTP/1.1 framing of a declared body",
                () -> assertEquals(HttpVersion.HTTP_2, echo.version(), "precondition: ALPN negotiated h2"),
                () -> assertEquals(200, echo.status(), () -> "the echo route answers: " + echo.body()),
                () -> assertEquals("POST", echo.body().getString("method"), () -> echo.body().encode()),
                () -> assertEquals(Optional.of(Integer.toString(length)), echo.header("Content-Length"),
                        () -> "the upstream request carries the length of the bytes sent: " + echo.body()),
                () -> assertEquals(body, echo.body().getString("data"), "the body arrives whole"));
    }

    @Test
    @DisplayName("a create-then-reset flood past 200 client resets ends its connection with GOAWAY ENHANCE_YOUR_CALM")
    void rapidResetFloodEndsTheConnectionWithEnhanceYourCalm() throws Exception {
        RawTlsHttp2Connection connection = RawTlsHttp2Connection.open(vertx, port);
        Buffer flood = Buffer.buffer();
        for (int index = 0; index < FLOOD_RESETS; index++) {
            int streamId = 2 * index + 1;
            flood.appendBuffer(RawTlsHttp2Connection.headers(streamId,
                    connection.requestHead("GET", rootPath + ECHO_PATH + "/rapid-reset-" + index), true))
                    .appendBuffer(RawTlsHttp2Connection.rstStream(streamId, RawTlsHttp2Connection.CANCEL));
        }

        connection.write(flood);
        boolean closed = connection.awaitClosed(CALL_TIMEOUT_SECONDS);

        assertAll("a Rapid-Reset flood over TLS",
                () -> assertEquals("h2", connection.negotiatedProtocol(), "precondition: ALPN negotiated h2"),
                () -> assertTrue(closed, "the gateway must end the flooding connection"),
                () -> assertEquals(Optional.of(ENHANCE_YOUR_CALM), connection.goAwayCode(),
                        "the connection past the pinned reset limit is ended with GOAWAY ENHANCE_YOUR_CALM"));
        assertGatewayStaysReady();
    }

    @Test
    @DisplayName("a CONTINUATION flood growing the header block past 16 KiB drops the connection unanswered")
    void continuationFloodPastTheHeaderListBoundDropsTheConnection() throws Exception {
        RawTlsHttp2Connection connection = RawTlsHttp2Connection.open(vertx, port);
        String filler = "f".repeat(FLOOD_FIELD_BYTES);
        int floodFrames = 4 * HEADER_LIST_BOUND / FLOOD_FIELD_BYTES;
        Buffer flood = RawTlsHttp2Connection.headersLeftOpen(1,
                connection.requestHead("GET", rootPath + ECHO_PATH + "/continuation-flood"));
        for (int index = 0; index < floodFrames; index++) {
            // No frame of the flood carries END_HEADERS: the header block is never ended.
            flood.appendBuffer(RawTlsHttp2Connection.continuation(1, List.of(Map.entry("x-flood-" + index, filler))));
        }

        connection.write(flood);
        boolean closed = connection.awaitClosed(CALL_TIMEOUT_SECONDS);

        assertAll("a CONTINUATION flood past the header-list bound over TLS",
                () -> assertEquals("h2", connection.negotiatedProtocol(), "precondition: ALPN negotiated h2"),
                () -> assertTrue(closed, "the gateway must drop the flooding connection"),
                () -> assertTrue(connection.goAwayCode().isPresent(), "the gateway announces the drop with GOAWAY"),
                () -> assertNotEquals(Optional.of(NO_ERROR), connection.goAwayCode(),
                        "the GOAWAY names an error, not a graceful shutdown"),
                () -> assertFalse(connection.answered(1), "the flooded stream is never answered"));
        assertGatewayStaysReady();
    }

    /** The gateway reports readiness and serves a fresh connection after a flood ended one of its connections. */
    private static void assertGatewayStaysReady() {
        int readiness = givenManagement().when().get("/health/ready").statusCode();
        int fresh = given().redirects().follow(false).when().get(ECHO_PATH + "/after-flood").statusCode();
        assertAll("the gateway after the flood",
                () -> assertEquals(200, readiness, "readiness stays UP"),
                () -> assertEquals(200, fresh, "a fresh connection is served"));
    }

    /** Sends one request over a fresh ALPN {@code h2} connection and reads the echo whole. */
    private Echo exchange(RequestOptions options, String body) throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setSsl(true)
                .setUseAlpn(true)
                // The stack serves its self-signed localhost certificate; trusting it blindly is scoped to
                // this black-box IT and is never a production trust decision.
                .setTrustAll(true)
                .setVerifyHost(false));
        try {
            return await(client.request(options)
                    .compose(request -> body == null ? request.send() : request.send(Buffer.buffer(body)))
                    .compose(response -> response.body().map(answer -> new Echo(response.statusCode(),
                            response.version(), answer.toJsonObject()))), "the echo of " + options.getURI());
        } finally {
            await(client.close(), "the HTTP/2 client to close");
        }
    }

    private static <T> T await(Future<T> future, String what) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("timed out after " + CALL_TIMEOUT_SECONDS + "s waiting for " + what, timedOut);
        }
    }

    /**
     * What the echo upstream reported for one request.
     *
     * @param status  the response status
     * @param version the protocol the gateway answered over
     * @param body    go-httpbin's echo document
     */
    private record Echo(int status, HttpVersion version, JsonObject body) {

        /** The forwarded value of a request header, matched case-insensitively, as go-httpbin echoes it. */
        Optional<String> header(String name) {
            JsonObject headers = body.getJsonObject("headers", new JsonObject());
            for (String echoed : headers.fieldNames()) {
                if (echoed.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    Object value = headers.getValue(echoed);
                    return Optional.of(value instanceof JsonArray values ? String.join(",", values.stream()
                            .map(String::valueOf).toList()) : String.valueOf(value));
                }
            }
            return Optional.empty();
        }
    }

    /**
     * An HTTP/2 client connection over TLS with ALPN {@code h2} that writes frames byte for byte, for the
     * shapes an HTTP/2 client library refuses to produce. Header blocks are HPACK-encoded as literal
     * fields without indexing, so the encoder keeps no state. The frames the gateway sends back are
     * parsed as they arrive: {@code SETTINGS} and {@code PING} are acknowledged, the streams a response
     * {@code HEADERS} frame arrived for are recorded, and a {@code GOAWAY} is recorded with its error
     * code. Frames are parsed on the socket's event loop; the observations are safe to read from the
     * test thread.
     */
    private static final class RawTlsHttp2Connection {

        static final long CANCEL = 0x8L;

        private static final int HEADERS = 0x1;
        private static final int RST_STREAM = 0x3;
        private static final int SETTINGS = 0x4;
        private static final int PING = 0x6;
        private static final int GOAWAY = 0x7;
        private static final int CONTINUATION = 0x9;
        private static final int FLAG_END_STREAM = 0x1;
        private static final int FLAG_ACK = 0x1;
        private static final int FLAG_END_HEADERS = 0x4;
        private static final int FRAME_HEADER_BYTES = 9;
        private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        private final NetSocket socket;
        private final int port;
        /** Bytes received and not yet parsed into a whole frame; touched on the event loop only. */
        private Buffer pending = Buffer.buffer();
        private final Set<Integer> answeredStreams = ConcurrentHashMap.newKeySet();
        private final CompletableFuture<Long> goAway = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();

        private RawTlsHttp2Connection(NetSocket socket, int port) {
            this.socket = socket;
            this.port = port;
            socket.handler(this::received);
            socket.closeHandler(end -> closed.complete(null));
            // A reset after the gateway ended the connection is an expected outcome; the close handler
            // above still settles.
            socket.exceptionHandler(cause -> {
            });
        }

        /** Connects over TLS, negotiating {@code h2}, and sends the connection preface and an empty SETTINGS. */
        static RawTlsHttp2Connection open(Vertx vertx, int port) throws Exception {
            NetClient client = vertx.createNetClient(new NetClientOptions()
                    .setSsl(true)
                    .setUseAlpn(true)
                    .setApplicationLayerProtocols(List.of("h2"))
                    // The stack's self-signed localhost certificate; scoped to this black-box IT.
                    .setTrustAll(true)
                    .setHostnameVerificationAlgorithm(""));
            NetSocket socket = await(client.connect(port, "localhost"), "the raw TLS HTTP/2 connection");
            RawTlsHttp2Connection connection = new RawTlsHttp2Connection(socket, port);
            await(connection.write(Buffer.buffer(PREFACE).appendBuffer(frame(SETTINGS, 0, 0, new byte[0]))),
                    "the HTTP/2 connection preface to be sent");
            return connection;
        }

        /** The request pseudo-headers of a {@code method} request for {@code path} on this connection. */
        List<Map.Entry<String, String>> requestHead(String method, String path) {
            return List.of(Map.entry(":method", method), Map.entry(":scheme", "https"), Map.entry(":path", path),
                    Map.entry(":authority", "localhost:" + port));
        }

        /** One {@code HEADERS} frame carrying the whole header block {@code fields}. */
        static Buffer headers(int streamId, List<Map.Entry<String, String>> fields, boolean endStream) {
            return frame(HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, hpack(fields));
        }

        /** A {@code HEADERS} frame ending its stream and opening a header block it does not end. */
        static Buffer headersLeftOpen(int streamId, List<Map.Entry<String, String>> fields) {
            return frame(HEADERS, FLAG_END_STREAM, streamId, hpack(fields));
        }

        /** A {@code CONTINUATION} frame carrying {@code fields} that does not end the header block. */
        static Buffer continuation(int streamId, List<Map.Entry<String, String>> fields) {
            return frame(CONTINUATION, 0, streamId, hpack(fields));
        }

        /** A {@code RST_STREAM} frame carrying {@code errorCode}. */
        static Buffer rstStream(int streamId, long errorCode) {
            return frame(RST_STREAM, 0, streamId, Buffer.buffer(4).appendUnsignedInt(errorCode).getBytes());
        }

        private static Buffer frame(int type, int flags, int streamId, byte[] payload) {
            return Buffer.buffer(FRAME_HEADER_BYTES + payload.length)
                    .appendUnsignedByte((short) (payload.length >>> 16 & 0xff))
                    .appendUnsignedByte((short) (payload.length >>> 8 & 0xff))
                    .appendUnsignedByte((short) (payload.length & 0xff))
                    .appendUnsignedByte((short) type)
                    .appendUnsignedByte((short) flags)
                    .appendInt(streamId & 0x7fffffff)
                    .appendBytes(payload);
        }

        /** Literal header fields without indexing, new name, no Huffman coding (RFC 7541 §6.2.2). */
        private static byte[] hpack(List<Map.Entry<String, String>> fields) {
            Buffer block = Buffer.buffer();
            for (Map.Entry<String, String> field : fields) {
                block.appendUnsignedByte((short) 0x00);
                appendString(block, field.getKey());
                appendString(block, field.getValue());
            }
            return block.getBytes();
        }

        private static void appendString(Buffer block, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            appendInteger(block, bytes.length);
            block.appendBytes(bytes);
        }

        /** RFC 7541 §5.1 integer with a 7-bit prefix and the Huffman bit clear. */
        private static void appendInteger(Buffer block, int value) {
            if (value < 0x7f) {
                block.appendUnsignedByte((short) value);
                return;
            }
            block.appendUnsignedByte((short) 0x7f);
            int rest = value - 0x7f;
            while (rest >= 0x80) {
                block.appendUnsignedByte((short) (rest & 0x7f | 0x80));
                rest >>>= 7;
            }
            block.appendUnsignedByte((short) rest);
        }

        Future<Void> write(Buffer bytes) {
            return socket.write(bytes);
        }

        /** The ALPN protocol the TLS handshake negotiated. */
        String negotiatedProtocol() {
            return socket.applicationLayerProtocol();
        }

        /** Whether a response {@code HEADERS} frame arrived for {@code streamId}. */
        boolean answered(int streamId) {
            return answeredStreams.contains(streamId);
        }

        /** The error code of the {@code GOAWAY} the gateway sent, when it sent one. */
        Optional<Long> goAwayCode() {
            return Optional.ofNullable(goAway.getNow(null));
        }

        /**
         * Waits for the gateway to close the connection.
         *
         * @return {@code true} once closed, {@code false} when the wait ran out first
         */
        boolean awaitClosed(long timeoutSeconds) throws Exception {
            try {
                closed.get(timeoutSeconds, TimeUnit.SECONDS);
                return true;
            } catch (TimeoutException stillOpen) {
                socket.close();
                return false;
            }
        }

        private void received(Buffer chunk) {
            pending.appendBuffer(chunk);
            int offset = 0;
            while (pending.length() - offset >= FRAME_HEADER_BYTES) {
                int length = pending.getUnsignedMedium(offset);
                if (pending.length() - offset < FRAME_HEADER_BYTES + length) {
                    break;
                }
                int type = pending.getUnsignedByte(offset + 3);
                int flags = pending.getUnsignedByte(offset + 4);
                int streamId = pending.getInt(offset + 5) & 0x7fffffff;
                Buffer payload = pending.getBuffer(offset + FRAME_HEADER_BYTES, offset + FRAME_HEADER_BYTES + length);
                offset += FRAME_HEADER_BYTES + length;
                onFrame(type, flags, streamId, payload);
            }
            pending = pending.getBuffer(offset, pending.length());
        }

        private void onFrame(int type, int flags, int streamId, Buffer payload) {
            switch (type) {
                case SETTINGS -> {
                    if ((flags & FLAG_ACK) == 0) {
                        socket.write(frame(SETTINGS, FLAG_ACK, 0, new byte[0]));
                    }
                }
                case PING -> {
                    if ((flags & FLAG_ACK) == 0) {
                        socket.write(frame(PING, FLAG_ACK, 0, payload.getBytes()));
                    }
                }
                case HEADERS -> answeredStreams.add(streamId);
                case GOAWAY -> goAway.complete(payload.getUnsignedInt(4));
                default -> {
                    // DATA, RST_STREAM, WINDOW_UPDATE and PRIORITY carry nothing the cases assert on.
                }
            }
        }
    }
}
