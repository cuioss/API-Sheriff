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
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
 * <li>{@link Result#parsed(Optional)} — a complete ClientHello was seen. The
 * {@link Result#serverName()} is present when a usable SNI {@code host_name} was extracted, and
 * <em>empty</em> for a ClientHello that carries no SNI, is malformed, is not a TLS handshake, or
 * exceeds the size bound. An empty result is the caller's fail-closed signal: it takes the
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
     *         size bound), otherwise a {@link Result#parsed(Optional)} verdict whose
     *         {@link Result#serverName()} is present only when a usable SNI was extracted
     */
    public Result parse(byte[] bytes) {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            if (bytes.length - pos < RECORD_HEADER_LENGTH) {
                return overBound(pos) ? Result.parsed(Optional.empty()) : Result.needMoreData();
            }
            if ((bytes[pos] & UINT8_MASK) != RECORD_TYPE_HANDSHAKE) {
                // Not a TLS handshake record — fail closed to the terminated-strict path.
                return Result.parsed(Optional.empty());
            }
            int recordLength = uint16(bytes, pos + 3);
            int recordEnd = pos + RECORD_HEADER_LENGTH + recordLength;
            if (recordEnd > MAX_CLIENT_HELLO_BYTES) {
                return Result.parsed(Optional.empty());
            }
            if (bytes.length < recordEnd) {
                return Result.needMoreData();
            }
            handshake.write(bytes, pos + RECORD_HEADER_LENGTH, recordLength);
            pos = recordEnd;

            byte[] handshakeBytes = handshake.toByteArray();
            HandshakeSpan span = completeHandshake(handshakeBytes);
            if (span == HandshakeSpan.MALFORMED) {
                return Result.parsed(Optional.empty());
            }
            if (span == HandshakeSpan.COMPLETE) {
                return Result.parsed(extractServerName(handshakeBytes));
            }
            // span == INCOMPLETE: keep reading records if any remain, else ask for more bytes.
            if (bytes.length - pos < RECORD_HEADER_LENGTH) {
                return overBound(pos) ? Result.parsed(Optional.empty()) : Result.needMoreData();
            }
        }
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
     * past the buffer) yields an empty result so the caller fails closed.
     */
    private static Optional<String> extractServerName(byte[] handshake) {
        Cursor cursor = new Cursor(handshake, HANDSHAKE_HEADER_LENGTH);
        try {
            cursor.skip(CLIENT_HELLO_FIXED_PREFIX);
            cursor.skip(cursor.readUint8());          // session_id
            cursor.skip(cursor.readUint16());         // cipher_suites
            cursor.skip(cursor.readUint8());          // compression_methods
            if (cursor.remaining() == 0) {
                return Optional.empty();               // no extensions block → no SNI
            }
            int extensionsLength = cursor.readUint16();
            int extensionsEnd = cursor.position() + extensionsLength;
            if (extensionsEnd > handshake.length) {
                return Optional.empty();
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
            return Optional.empty();
        } catch (MalformedHelloException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads the {@code server_name} extension body: a 2-byte list length, then entries each of
     * {@code name_type(1)} + {@code length(2)} + name. Returns the first {@code host_name}.
     */
    private static Optional<String> readServerNameExtension(Cursor cursor, int extensionLength) {
        int extensionEnd = cursor.position() + extensionLength;
        if (extensionLength < 2) {
            return Optional.empty();
        }
        int listLength = cursor.readUint16();
        int listEnd = cursor.position() + listLength;
        if (listEnd > extensionEnd) {
            return Optional.empty();
        }
        while (cursor.position() < listEnd) {
            int nameType = cursor.readUint8();
            int nameLength = cursor.readUint16();
            if (cursor.position() + nameLength > listEnd) {
                return Optional.empty();
            }
            if (nameType == NAME_TYPE_HOST_NAME) {
                String host = cursor.readString(nameLength);
                return host.isEmpty() ? Optional.empty() : Optional.of(host);
            }
            cursor.skip(nameLength);
        }
        return Optional.empty();
    }

    private static int uint16(byte[] data, int offset) {
        return ((data[offset] & UINT8_MASK) << 8) | (data[offset + 1] & UINT8_MASK);
    }

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

        @java.io.Serial
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
     *                   was present, otherwise empty
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Result(boolean complete, Optional<String> serverName) {

        /** Canonical constructor normalizing an absent {@code serverName} to {@link Optional#empty()}. */
        public Result {
            serverName = serverName == null ? Optional.empty() : serverName;
        }

        /**
         * The "keep buffering" verdict: the ClientHello is not yet complete.
         *
         * @return an incomplete result carrying no server name
         */
        public static Result needMoreData() {
            return new Result(false, Optional.empty());
        }

        /**
         * The "ClientHello complete" verdict.
         *
         * @param serverName the extracted SNI, or empty to fail closed to the terminated path
         * @return a complete result carrying the supplied server name
         */
        public static Result parsed(Optional<String> serverName) {
            return new Result(true, serverName);
        }
    }
}
