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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.jspecify.annotations.Nullable;

/**
 * A prior-knowledge HTTP/2 client connection that writes frames byte for byte, for the cases an HTTP/2
 * client library refuses to produce: connection-specific request headers, a header block left open
 * across any number of {@code CONTINUATION} frames, and a stream reset written straight behind the
 * {@code HEADERS} frame that opened it.
 * <p>
 * Every header block is HPACK-encoded as literal fields without indexing, so the encoder keeps no state
 * and any name or value — a connection-specific one included — reaches the wire as written. The frames
 * the edge sends back are parsed as they arrive: {@code SETTINGS} and {@code PING} are acknowledged,
 * response header blocks are decoded in order so each stream's {@code :status} is known, and a
 * {@code RST_STREAM} or {@code GOAWAY} is recorded with its error code.
 * <p>
 * Frames are parsed on the socket's event loop; the observations are safe to read from the test thread.
 */
final class RawHttp2Connection {

    static final int DATA = 0x0;
    static final int HEADERS = 0x1;
    static final int RST_STREAM = 0x3;
    static final int SETTINGS = 0x4;
    static final int PING = 0x6;
    static final int GOAWAY = 0x7;
    static final int CONTINUATION = 0x9;

    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_ACK = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int FLAG_PRIORITY = 0x20;

    static final long NO_ERROR = 0x0L;
    static final long CANCEL = 0x8L;
    static final long ENHANCE_YOUR_CALM = 0xbL;

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_HEADER_BYTES = 9;

    private final NetSocket socket;
    private final int port;
    /** Bytes received and not yet parsed into a whole frame; touched on the event loop only. */
    private Buffer pending = Buffer.buffer();
    /** The stream whose response header block is being received, with the fragments so far. */
    private int headerBlockStream;
    private Buffer headerBlock = Buffer.buffer();
    private final DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder(false);
    private final Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<Integer, Long> resets = new ConcurrentHashMap<>();
    private final CompletableFuture<Long> goAway = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicLong framesReceived = new AtomicLong();

    private RawHttp2Connection(NetSocket socket, int port) {
        this.socket = socket;
        this.port = port;
        socket.handler(this::received);
        socket.closeHandler(end -> closed.complete(null));
        // A reset after the edge ended the connection is an expected outcome; the close handler
        // above still settles.
        socket.exceptionHandler(cause -> {
        });
    }

    /**
     * Connects to the edge at {@code port} and sends the connection preface and an empty
     * {@code SETTINGS} frame.
     */
    static RawHttp2Connection open(NetClient netClient, int port) throws Exception {
        NetSocket socket = Awaits.connect(netClient.connect(port, LoopbackHost.ADDRESS),
                "the raw HTTP/2 client to connect to the edge");
        RawHttp2Connection connection = new RawHttp2Connection(socket, port);
        Awaits.connect(connection.write(Buffer.buffer(PREFACE).appendBuffer(frame(SETTINGS, 0, 0, new byte[0]))),
                "the HTTP/2 connection preface to be sent");
        return connection;
    }

    /** The request pseudo-headers of a {@code method} request for {@code path} on this connection. */
    List<Map.Entry<String, String>> requestHead(String method, String path) {
        return List.of(Map.entry(":method", method), Map.entry(":scheme", "http"), Map.entry(":path", path),
                Map.entry(":authority", "localhost:" + port));
    }

    /** One {@code HEADERS} frame carrying the whole header block {@code fields}. */
    static Buffer headers(int streamId, List<Map.Entry<String, String>> fields, boolean endStream) {
        return frame(HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, hpack(fields));
    }

    /** A {@code HEADERS} frame opening a header block it does not end. */
    static Buffer headersLeftOpen(int streamId, List<Map.Entry<String, String>> fields, boolean endStream) {
        return frame(HEADERS, endStream ? FLAG_END_STREAM : 0, streamId, hpack(fields));
    }

    /** A {@code CONTINUATION} frame carrying {@code fields}, ending the header block when {@code endHeaders}. */
    static Buffer continuation(int streamId, List<Map.Entry<String, String>> fields, boolean endHeaders) {
        return frame(CONTINUATION, endHeaders ? FLAG_END_HEADERS : 0, streamId, hpack(fields));
    }

    /** A {@code DATA} frame. */
    static Buffer data(int streamId, String body, boolean endStream) {
        return frame(DATA, endStream ? FLAG_END_STREAM : 0, streamId, body.getBytes(StandardCharsets.US_ASCII));
    }

    /** A {@code RST_STREAM} frame carrying {@code errorCode}. */
    static Buffer rstStream(int streamId, long errorCode) {
        return frame(RST_STREAM, 0, streamId, uint32(errorCode));
    }

    /** One frame: the 9-byte frame header followed by {@code payload}. */
    static Buffer frame(int type, int flags, int streamId, byte[] payload) {
        return Buffer.buffer(FRAME_HEADER_BYTES + payload.length)
                .appendUnsignedByte((short) (payload.length >>> 16 & 0xff))
                .appendUnsignedByte((short) (payload.length >>> 8 & 0xff))
                .appendUnsignedByte((short) (payload.length & 0xff))
                .appendUnsignedByte((short) type)
                .appendUnsignedByte((short) flags)
                .appendInt(streamId & 0x7fffffff)
                .appendBytes(payload);
    }

    /**
     * HPACK-encodes {@code fields} as literal header fields without indexing, new name, no Huffman
     * coding (RFC 7541 §6.2.2), so the block depends on no table state of either side.
     */
    static byte[] hpack(List<Map.Entry<String, String>> fields) {
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

    private static byte[] uint32(long value) {
        return Buffer.buffer(4).appendUnsignedInt(value).getBytes();
    }

    /** Writes {@code bytes} as they are. */
    Future<Void> write(Buffer bytes) {
        return socket.write(bytes);
    }

    /** The {@code :status} the edge answered {@code streamId} with, when a response head arrived. */
    Optional<Integer> status(int streamId) {
        return Optional.ofNullable(statuses.get(streamId));
    }

    /** The error code the edge reset {@code streamId} with, when it reset it. */
    Optional<Long> reset(int streamId) {
        return Optional.ofNullable(resets.get(streamId));
    }

    /** Whether the edge refused {@code streamId}: reset it, or answered it with a status of 400 or above. */
    boolean refused(int streamId) {
        return reset(streamId).isPresent() || status(streamId).filter(status -> status >= 400).isPresent();
    }

    /** The error code of the {@code GOAWAY} the edge sent, when it sent one. */
    Optional<Long> goAwayCode() {
        return Optional.ofNullable(goAway.getNow(null));
    }

    /** Whether the edge has closed the connection. */
    boolean isClosed() {
        return closed.isDone();
    }

    /** Completes once the edge has closed the connection. */
    CompletableFuture<Void> closed() {
        return closed;
    }

    /** How many whole frames the edge has sent on this connection. */
    long framesReceived() {
        return framesReceived.get();
    }

    /** Closes the client side of the connection. */
    Future<Void> close() {
        return socket.close();
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
            framesReceived.incrementAndGet();
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
            case HEADERS -> {
                headerBlockStream = streamId;
                headerBlock = Buffer.buffer().appendBuffer(fragment(flags, payload));
                endHeaderBlockIfComplete(flags);
            }
            case CONTINUATION -> {
                headerBlock.appendBuffer(payload);
                endHeaderBlockIfComplete(flags);
            }
            case RST_STREAM -> resets.put(streamId, payload.getUnsignedInt(0));
            case GOAWAY -> goAway.complete(payload.getUnsignedInt(4));
            default -> {
                // DATA, WINDOW_UPDATE and PRIORITY carry nothing the cases assert on.
            }
        }
    }

    /** The header block fragment of a {@code HEADERS} frame: its padding and priority fields removed. */
    private static Buffer fragment(int flags, Buffer payload) {
        int start = 0;
        int end = payload.length();
        if ((flags & FLAG_PADDED) != 0) {
            end -= payload.getUnsignedByte(0);
            start = 1;
        }
        if ((flags & FLAG_PRIORITY) != 0) {
            start += 5;
        }
        return payload.getBuffer(start, end);
    }

    private void endHeaderBlockIfComplete(int flags) {
        if ((flags & FLAG_END_HEADERS) == 0) {
            return;
        }
        @Nullable Http2Headers decoded = decode(headerBlockStream, headerBlock);
        if (decoded != null && decoded.status() != null) {
            statuses.putIfAbsent(headerBlockStream, Integer.valueOf(decoded.status().toString()));
        }
        headerBlock = Buffer.buffer();
    }

    private @Nullable Http2Headers decode(int streamId, Buffer block) {
        try {
            return decoder.decodeHeaders(streamId, Unpooled.wrappedBuffer(block.getBytes()));
        } catch (Http2Exception e) {
            // A response block this client cannot decode leaves its stream without a status; the
            // cases then fail on the missing status rather than here.
            return null;
        }
    }
}
