/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.tls;

import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

/**
 * A framework-agnostic parser that reassembles the full TLS ClientHello (RFC 6066) across TCP
 * fragments and extracts the SNI {@code host_name}, without any decryption.
 * <p>
 * The accept-time SNI split (GW-06 fail-closed) must decide the route from bytes the client sends
 * <em>before</em> any handshake completes. The parser is fed the connection bytes accumulated so
 * far and returns one of two verdicts:
 * <ul>
 * <li>{@link Result#needMoreData()} — the ClientHello is not yet complete; the caller keeps
 * buffering. Bounded by {@link #MAX_CLIENT_HELLO_BYTES}: a client that never completes a valid
 * ClientHello within the bound is treated as complete-with-no-SNI, so buffering can never grow
 * unbounded.</li>
 * <li>{@link Result#parsed(String)} — a complete ClientHello was seen. The
 * {@link Result#serverName()} is present when a usable SNI {@code host_name} was extracted, and
 * <em>{@code null}</em> for a ClientHello that carries no SNI, is malformed, is not a TLS handshake,
 * or exceeds the size bound. An absent result is the caller's fail-closed signal: it takes the
 * terminated-strict path, never a passthrough.</li>
 * </ul>
 * The parser is pure (no I/O, no framework types) and therefore unit-testable byte-for-byte
 * independently of the Vert.x front listener.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ClientHelloSniParser {

    /** TLS {@code handshake} record content type (RFC 8446 §5.1). */
    private static final int RECORD_TYPE_HANDSHAKE = 0x16;
    /** {@code client_hello} handshake message type (RFC 8446 §4). */
    private static final int HANDSHAKE_TYPE_CLIENT_HELLO = 0x01;
    /** {@code server_name} extension type (RFC 6066 §3). */
    private static final int EXTENSION_TYPE_SERVER_NAME = 0x0000;
    /** {@code host_name} name type inside the {@code server_name} list (RFC 6066 §3). */
    private static final int NAME_TYPE_HOST_NAME = 0x00;

    private static final int RECORD_HEADER_LENGTH = 5;
    private static final int HANDSHAKE_HEADER_LENGTH = 4;
    private static final int CLIENT_HELLO_FIXED_PREFIX = 2 + 32; // legacy_version + random
    private static final int UINT8_MASK = 0xFF;

    /**
     * The hard upper bound on the bytes buffered while reassembling a single ClientHello. A TLS
     * record body is capped at 2^14 bytes; a legitimate ClientHello (even one spread across a few
     * records with many extensions) stays comfortably inside this bound. Anything larger is treated
     * as complete-with-no-SNI (fail closed) rather than buffered further.
     */
    public static final int MAX_CLIENT_HELLO_BYTES = 32 * 1024;

    /**
     * Parses the accumulated connection bytes.
     *
     * @param bytes the bytes received from the client so far, never {@code null}
     * @return {@link Result#needMoreData()} when the ClientHello is still incomplete (and within the
     *         size bound), otherwise a {@link Result#parsed(String)} verdict whose
     *         {@link Result#serverName()} is present only when a usable SNI was extracted
     */
    public Result parse(byte[] bytes) {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            Result headerVerdict = recordHeaderVerdict(bytes, pos);
            if (headerVerdict != null) {
                return headerVerdict;
            }
            int recordLength = uint16(bytes, pos + 3);
            int recordEnd = pos + RECORD_HEADER_LENGTH + recordLength;
            Result bodyVerdict = recordBodyVerdict(bytes, recordEnd);
            if (bodyVerdict != null) {
                return bodyVerdict;
            }
            handshake.write(bytes, pos + RECORD_HEADER_LENGTH, recordLength);
            pos = recordEnd;

            Result reassembledVerdict = reassembledVerdict(handshake.toByteArray());
            if (reassembledVerdict != null) {
                return reassembledVerdict;
            }
            // The handshake is still incomplete: loop back and consume the next record. The
            // "is another record header even present?" question is the loop head's own first check,
            // so it is asked there rather than repeated here.
        }
    }

    /**
     * The terminal verdict, if any, implied by the record header at {@code pos}: too few bytes for a
     * header (keep buffering, or fail closed once the bound is passed), or a record that is not a TLS
     * handshake at all.
     *
     * @param bytes the accumulated connection bytes
     * @param pos   the offset of the record header being examined
     * @return the verdict to return from {@code parse}, or {@code null} to consume this record
     */
    private static @Nullable Result recordHeaderVerdict(byte[] bytes, int pos) {
        if (bytes.length - pos < RECORD_HEADER_LENGTH) {
            return overBound(pos) ? Result.parsed(null) : Result.needMoreData();
        }
        if ((bytes[pos] & UINT8_MASK) != RECORD_TYPE_HANDSHAKE) {
            // Not a TLS handshake record — fail closed to the terminated-strict path.
            return Result.parsed(null);
        }
        return null;
    }

    /**
     * The terminal verdict, if any, implied by the declared record body: a record running past the
     * size bound fails closed, and a body that has not fully arrived means keep buffering.
     *
     * @param bytes     the accumulated connection bytes
     * @param recordEnd the offset one past the declared end of this record
     * @return the verdict to return from {@code parse}, or {@code null} to consume this record
     */
    private static @Nullable Result recordBodyVerdict(byte[] bytes, int recordEnd) {
        if (recordEnd > MAX_CLIENT_HELLO_BYTES) {
            return Result.parsed(null);
        }
        if (bytes.length < recordEnd) {
            return Result.needMoreData();
        }
        return null;
    }

    /**
     * The terminal verdict, if any, implied by the handshake bytes reassembled so far: a wrong
     * handshake type or an over-bound body fails closed, a complete {@code client_hello} yields the
     * extracted SNI.
     *
     * @param handshakeBytes the handshake bytes reassembled across the records consumed so far
     * @return the verdict to return from {@code parse}, or {@code null} while the body is incomplete
     */
    private static @Nullable Result reassembledVerdict(byte[] handshakeBytes) {
        HandshakeSpan span = completeHandshake(handshakeBytes);
        if (span == HandshakeSpan.MALFORMED) {
            return Result.parsed(null);
        }
        if (span == HandshakeSpan.COMPLETE) {
            return Result.parsed(extractServerName(handshakeBytes));
        }
        return null;
    }

    private static boolean overBound(int pos) {
        return pos >= MAX_CLIENT_HELLO_BYTES;
    }

    /**
     * Classifies the reassembled handshake bytes against the fixed handshake header: complete once
     * the full {@code client_hello} body is present, malformed when it is a different handshake type,
     * incomplete while the body has not fully arrived.
     */
    private static HandshakeSpan completeHandshake(byte[] handshake) {
        if (handshake.length < HANDSHAKE_HEADER_LENGTH) {
            return HandshakeSpan.INCOMPLETE;
        }
        if ((handshake[0] & UINT8_MASK) != HANDSHAKE_TYPE_CLIENT_HELLO) {
            return HandshakeSpan.MALFORMED;
        }
        int bodyLength = uint24(handshake, 1);
        int total = HANDSHAKE_HEADER_LENGTH + bodyLength;
        if (total > MAX_CLIENT_HELLO_BYTES) {
            return HandshakeSpan.MALFORMED;
        }
        return handshake.length >= total ? HandshakeSpan.COMPLETE : HandshakeSpan.INCOMPLETE;
    }

    /**
     * Walks the fully-reassembled {@code client_hello} body to the {@code server_name} extension and
     * returns the first {@code host_name}. Any structural inconsistency (a declared length that runs
     * past the buffer) yields {@code null} so the caller fails closed.
     */
    private static @Nullable String extractServerName(byte[] handshake) {
        Cursor cursor = new Cursor(handshake, HANDSHAKE_HEADER_LENGTH);
        try {
            cursor.skip(CLIENT_HELLO_FIXED_PREFIX);
            cursor.skip(cursor.readUint8());          // session_id
            cursor.skip(cursor.readUint16());         // cipher_suites
            cursor.skip(cursor.readUint8());          // compression_methods
            if (cursor.remaining() == 0) {
                return null;                           // no extensions block → no SNI
            }
            int extensionsLength = cursor.readUint16();
            int extensionsEnd = cursor.position() + extensionsLength;
            if (extensionsEnd > handshake.length) {
                return null;
            }
            while (cursor.position() < extensionsEnd) {
                int type = cursor.readUint16();
                int length = cursor.readUint16();
                int next = cursor.position() + length;
                if (type == EXTENSION_TYPE_SERVER_NAME) {
                    return readServerNameExtension(new Cursor(handshake, cursor.position()), length);
                }
                cursor.seek(next);
            }
            return null;
        } catch (MalformedHelloException _) {
            return null;
        }
    }

    /**
     * Reads the {@code server_name} extension body: a 2-byte list length, then entries each of
     * {@code name_type(1)} + {@code length(2)} + name. Returns the first {@code host_name}.
     */
    private static @Nullable String readServerNameExtension(Cursor cursor, int extensionLength) {
        int extensionEnd = cursor.position() + extensionLength;
        if (extensionLength < 2) {
            return null;
        }
        int listLength = cursor.readUint16();
        int listEnd = cursor.position() + listLength;
        if (listEnd > extensionEnd) {
            return null;
        }
        while (cursor.position() < listEnd) {
            int nameType = cursor.readUint8();
            int nameLength = cursor.readUint16();
            if (cursor.position() + nameLength > listEnd) {
                return null;
            }
            if (nameType == NAME_TYPE_HOST_NAME) {
                String host = cursor.readString(nameLength);
                return host.isEmpty() ? null : host;
            }
            cursor.skip(nameLength);
        }
        return null;
    }

    /**
     * Reads the big-endian {@code uint16} at {@code offset}.
     * <p>
     * <strong>Bounds contract.</strong> Both call sites establish {@code 0 <= offset} and
     * {@code offset + 1 < data.length} before calling, so neither read can run past the array:
     * <ul>
     * <li>{@link #parse(byte[])} evaluates {@code uint16(bytes, pos + 3)} only after
     * {@link #recordHeaderVerdict(byte[], int)} returned {@code null}, which happens solely when
     * {@code bytes.length - pos >= RECORD_HEADER_LENGTH} — so {@code pos + 4 <= bytes.length - 1}.
     * {@code pos} starts at {@code 0} and only ever advances to a {@code recordEnd} that
     * {@link #recordBodyVerdict(byte[], int)} already bounded by {@link #MAX_CLIENT_HELLO_BYTES}, so
     * it is never negative and never overflows.</li>
     * <li>{@link Cursor#readUint16()} evaluates {@code uint16(data, position)} only after
     * {@link Cursor#require(int)} asserted {@code position + 2 <= data.length}, raising
     * {@link MalformedHelloException} otherwise. {@code position} starts at
     * {@link #HANDSHAKE_HEADER_LENGTH} and never decreases ({@link Cursor#seek(int)} refuses a
     * backwards target), so it is never negative.</li>
     * </ul>
     * Both guards live in extracted helpers rather than inline in the reading method, which is why
     * the symbolic-execution engine cannot see them; {@code ClientHelloSniParserTest} pins the
     * {@code parse} guard at the exact one-byte-short-of-a-record-header boundary, on the first
     * record and on the loop-back to a later record.
     *
     * @param data   the buffer to read from
     * @param offset the offset of the high-order byte; the caller guarantees {@code offset + 1} is
     *               within {@code data}
     * @return the unsigned 16-bit big-endian value at {@code offset}
     */
    private static int uint16(byte[] data, int offset) {
        int high = data[offset] & UINT8_MASK; // NOSONAR javabugs:S6466 - caller-guarded, see Javadoc
        int low = data[offset + 1] & UINT8_MASK; // NOSONAR javabugs:S6466 - caller-guarded, see Javadoc
        return (high << 8) | low;
    }

    /**
     * Reads the big-endian {@code uint24} at {@code offset}. The sole caller
     * {@link #completeHandshake(byte[])} has already returned {@link HandshakeSpan#INCOMPLETE} unless
     * {@code handshake.length >= HANDSHAKE_HEADER_LENGTH}, so offsets {@code 1..3} are always within
     * the buffer.
     *
     * @param data   the buffer to read from
     * @param offset the offset of the most significant byte
     * @return the unsigned 24-bit big-endian value at {@code offset}
     */
    private static int uint24(byte[] data, int offset) {
        return ((data[offset] & UINT8_MASK) << 16)
                | ((data[offset + 1] & UINT8_MASK) << 8)
                | (data[offset + 2] & UINT8_MASK);
    }

    /** Whether the handshake body is complete, still arriving, or of the wrong handshake type. */
    private enum HandshakeSpan {
        COMPLETE, INCOMPLETE, MALFORMED
    }

    /** A bounds-checked forward cursor over the reassembled handshake bytes. */
    private static final class Cursor {

        private final byte[] data;
        private int position;

        Cursor(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        int position() {
            return position;
        }

        int remaining() {
            return data.length - position;
        }

        void require(int count) {
            if (count < 0 || position + count > data.length) {
                throw new MalformedHelloException();
            }
        }

        int readUint8() {
            require(1);
            return data[position++] & UINT8_MASK;
        }

        int readUint16() {
            require(2);
            int value = uint16(data, position);
            position += 2;
            return value;
        }

        String readString(int length) {
            require(length);
            String value = new String(data, position, length, StandardCharsets.US_ASCII);
            position += length;
            return value;
        }

        void skip(int count) {
            require(count);
            position += count;
        }

        void seek(int target) {
            if (target < position || target > data.length) {
                throw new MalformedHelloException();
            }
            position = target;
        }
    }

    /** Raised internally when a declared length runs past the reassembled handshake buffer. */
    private static final class MalformedHelloException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        MalformedHelloException() {
            super(null, null, false, false);
        }
    }

    /**
     * The verdict of a single {@link ClientHelloSniParser#parse(byte[])} call.
     *
     * @param complete   {@code true} once a full ClientHello has been observed; {@code false} means
     *                   more bytes are needed
     * @param serverName the extracted SNI {@code host_name} when {@code complete} and a usable SNI
     *                   was present, otherwise {@code null}
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record Result(boolean complete, @Nullable String serverName) {

        /**
         * The "keep buffering" verdict: the ClientHello is not yet complete.
         *
         * @return an incomplete result carrying no server name
         */
        public static Result needMoreData() {
            return new Result(false, null);
        }

        /**
         * The "ClientHello complete" verdict.
         *
         * @param serverName the extracted SNI, or {@code null} to fail closed to the terminated path
         * @return a complete result carrying the supplied server name
         */
        public static Result parsed(@Nullable String serverName) {
            return new Result(true, serverName);
        }
    }
}
