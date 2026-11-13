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
package de.cuioss.sheriff.gateway.tls;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A framework-agnostic, per-connection parser that reassembles the full TLS ClientHello (RFC 6066)
 * across TCP fragments and extracts the SNI {@code host_name}, without any decryption.
 * <p>
 * The accept-time SNI split (GW-06 fail-closed) must decide the route from bytes the client sends
 * <em>before</em> any handshake completes. One parser serves exactly one connection: the caller
 * {@linkplain #offer(byte[]) offers} each chunk it receives, once, and every offer returns one of two
 * verdicts:
 * <ul>
 * <li>{@link Result#needMoreData()} — the ClientHello is not yet complete; the caller keeps
 * offering. Bounded by {@link #MAX_CLIENT_HELLO_BYTES}: a client that never completes a valid
 * ClientHello within the bound is treated as complete-with-no-SNI, so reassembly can never grow
 * unbounded.</li>
 * <li>{@link Result#parsed(String)} — a complete ClientHello was seen. The
 * {@link Result#serverName()} is present when a usable SNI {@code host_name} was extracted, and
 * <em>{@code null}</em> for a ClientHello that carries no SNI, is malformed, is not a TLS handshake,
 * or exceeds the size bound. An absent result is the caller's fail-closed signal: it takes the
 * terminated-strict path, never a passthrough.</li>
 * </ul>
 * <strong>The work is linear in the bytes received.</strong> The parser retains the bytes offered so
 * far, the offset one past the last fully consumed TLS record and the handshake bytes reassembled from
 * the consumed records. Each offer consumes only the records the new bytes completed, reads the 4-byte
 * handshake header once, and walks the extensions for the SNI only once the {@code client_hello} is
 * complete. A ClientHello dripped one byte at a time, or split across thousands of tiny records,
 * therefore never causes a re-parse of what was already consumed. The verdict does not depend on how
 * the bytes were fragmented: the same bytes offered whole, byte by byte or record by record yield the
 * same verdict. Once a {@link Result#parsed(String) complete} verdict is reached it is final, and every
 * later offer returns it again without reading the new bytes.
 * <p>
 * The parser is pure (no I/O, no framework types) and therefore unit-testable byte-for-byte
 * independently of the Vert.x front listener.
 * <p>
 * <strong>Thread safety.</strong> Not thread-safe, by design: an instance holds the reassembly state
 * of one connection and is confined to that connection's event loop.
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

    /** The initial capacity of the two reassembly arrays; each grows by doubling up to the bound. */
    private static final int INITIAL_CAPACITY = 512;

    /** The declared handshake length while the 4-byte handshake header has not been read yet. */
    private static final int HANDSHAKE_LENGTH_UNKNOWN = -1;

    /**
     * The hard upper bound on the bytes buffered while reassembling a single ClientHello. A TLS
     * record body is capped at 2^14 bytes; a legitimate ClientHello (even one spread across a few
     * records with many extensions) stays comfortably inside this bound. Anything larger is treated
     * as complete-with-no-SNI (fail closed) rather than buffered further.
     */
    public static final int MAX_CLIENT_HELLO_BYTES = 32 * 1024;

    /** The connection bytes offered so far, retained up to {@link #MAX_CLIENT_HELLO_BYTES}. */
    private byte[] received = new byte[INITIAL_CAPACITY];
    private int receivedLength;
    /** The offset one past the last fully consumed TLS record within {@link #received}. */
    private int consumed;

    /** The handshake bytes reassembled from the bodies of the records consumed so far. */
    private byte[] handshake = new byte[INITIAL_CAPACITY];
    private int handshakeLength;
    /** The declared total handshake length (header included), read once from the handshake header. */
    private int declaredHandshakeLength = HANDSHAKE_LENGTH_UNKNOWN;

    /** The final verdict, once one was reached. */
    private @Nullable Result verdict;

    /**
     * Offers the next chunk of connection bytes and returns the verdict over everything offered so
     * far.
     * <p>
     * Only the bytes up to {@link #MAX_CLIENT_HELLO_BYTES} in total are retained: the bytes past the
     * bound can never change the verdict, because a reassembly that has not completed within the bound
     * fails closed.
     *
     * @param chunk the bytes received from the client since the previous offer, never {@code null}; the
     *              array is copied, not retained
     * @return {@link Result#needMoreData()} when the ClientHello is still incomplete (and within the
     *         size bound), otherwise a {@link Result#parsed(String)} verdict whose
     *         {@link Result#serverName()} is present only when a usable SNI was extracted
     */
    public Result offer(byte[] chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Result current = verdict;
        if (current != null) {
            return current;
        }
        appendReceived(chunk);
        Result reached = consumeCompletedRecords();
        if (reached.complete()) {
            verdict = reached;
        }
        return reached;
    }

    /**
     * Consumes every record the bytes received so far complete, appending each body to the reassembled
     * handshake, until a verdict is reached or the next record is still incomplete.
     *
     * @return the verdict over the bytes received so far
     */
    private Result consumeCompletedRecords() {
        while (true) {
            Result headerVerdict = recordHeaderVerdict();
            if (headerVerdict != null) {
                return headerVerdict;
            }
            int recordLength = uint16(received, consumed + 3);
            int recordEnd = consumed + RECORD_HEADER_LENGTH + recordLength;
            Result bodyVerdict = recordBodyVerdict(recordEnd);
            if (bodyVerdict != null) {
                return bodyVerdict;
            }
            appendHandshake(consumed + RECORD_HEADER_LENGTH, recordLength);
            consumed = recordEnd;

            Result reassembledVerdict = reassembledVerdict();
            if (reassembledVerdict != null) {
                return reassembledVerdict;
            }
            // The handshake is still incomplete: loop back and consume the next record. The
            // "is another record header even present?" question is the loop head's own first check,
            // so it is asked there rather than repeated here.
        }
    }

    /**
     * The terminal verdict, if any, implied by the record header at {@link #consumed}: too few bytes
     * for a header (keep buffering, or fail closed once the bound is passed), or a record that is not a
     * TLS handshake at all.
     * <p>
     * <strong>The give-up test is anchored on {@link #receivedLength}</strong> — the bytes already
     * buffered — rather than on {@link #consumed}, the bytes already consumed.
     * {@link #MAX_CLIENT_HELLO_BYTES} declares its bound on the accumulated buffer, and in this branch
     * {@code consumed} trails {@code receivedLength} by up to {@code RECORD_HEADER_LENGTH - 1}: a
     * position-anchored test would therefore still answer {@link Result#needMoreData()} for a buffer
     * holding up to {@code MAX_CLIENT_HELLO_BYTES + 3} bytes, telling the caller to keep buffering
     * something that has already passed the declared hard bound. Since
     * {@code consumed <= receivedLength} always holds ({@link #recordBodyVerdict(int)} lets
     * {@code consumed} advance only to a {@code recordEnd} it has confirmed is within the buffer), the
     * buffer-anchored test subsumes the position-anchored one.
     *
     * @return the verdict to return from {@link #offer(byte[])}, or {@code null} to consume this record
     */
    private @Nullable Result recordHeaderVerdict() {
        if (receivedLength - consumed < RECORD_HEADER_LENGTH) {
            return overBound(receivedLength) ? Result.parsed(null) : Result.needMoreData();
        }
        if ((received[consumed] & UINT8_MASK) != RECORD_TYPE_HANDSHAKE) {
            // Not a TLS handshake record — fail closed to the terminated-strict path.
            return Result.parsed(null);
        }
        return null;
    }

    /**
     * The terminal verdict, if any, implied by the declared record body: a record running past the
     * size bound fails closed, and a body that has not fully arrived means keep buffering.
     *
     * @param recordEnd the offset one past the declared end of this record
     * @return the verdict to return from {@link #offer(byte[])}, or {@code null} to consume this record
     */
    private @Nullable Result recordBodyVerdict(int recordEnd) {
        if (recordEnd > MAX_CLIENT_HELLO_BYTES) {
            return Result.parsed(null);
        }
        if (receivedLength < recordEnd) {
            return Result.needMoreData();
        }
        return null;
    }

    /**
     * The terminal verdict, if any, implied by the handshake bytes reassembled so far: a wrong
     * handshake type or an over-bound body fails closed, a complete {@code client_hello} yields the
     * extracted SNI.
     *
     * @return the verdict to return from {@link #offer(byte[])}, or {@code null} while the body is
     *         incomplete
     */
    private @Nullable Result reassembledVerdict() {
        HandshakeSpan span = handshakeSpan();
        if (span == HandshakeSpan.MALFORMED) {
            return Result.parsed(null);
        }
        if (span == HandshakeSpan.COMPLETE) {
            // Bound the walk to the ClientHello's declared length, never to the bytes reassembled: a
            // record may carry bytes past the ClientHello, and those must not supply the routed SNI.
            return Result.parsed(extractServerName(Arrays.copyOf(handshake, declaredHandshakeLength)));
        }
        return null;
    }

    /**
     * Classifies the reassembled handshake bytes against the fixed handshake header: complete once
     * the full {@code client_hello} body is present, malformed when it is a different handshake type
     * or declares a body past the bound, incomplete while the body has not fully arrived. The header
     * is read once, the first time at least {@link #HANDSHAKE_HEADER_LENGTH} bytes are reassembled.
     */
    private HandshakeSpan handshakeSpan() {
        if (declaredHandshakeLength == HANDSHAKE_LENGTH_UNKNOWN) {
            if (handshakeLength < HANDSHAKE_HEADER_LENGTH) {
                return HandshakeSpan.INCOMPLETE;
            }
            if ((handshake[0] & UINT8_MASK) != HANDSHAKE_TYPE_CLIENT_HELLO) {
                return HandshakeSpan.MALFORMED;
            }
            declaredHandshakeLength = HANDSHAKE_HEADER_LENGTH + uint24(handshake, 1);
        }
        if (declaredHandshakeLength > MAX_CLIENT_HELLO_BYTES) {
            return HandshakeSpan.MALFORMED;
        }
        return handshakeLength >= declaredHandshakeLength ? HandshakeSpan.COMPLETE : HandshakeSpan.INCOMPLETE;
    }

    /**
     * Whether {@code byteCount} has reached the reassembly bound. Callers pass the number of bytes
     * <em>buffered</em>, never the number consumed — see {@link #recordHeaderVerdict()} for why the
     * distinction is load-bearing.
     */
    private static boolean overBound(int byteCount) {
        return byteCount >= MAX_CLIENT_HELLO_BYTES;
    }

    /**
     * Appends the offered chunk to the received bytes, up to {@link #MAX_CLIENT_HELLO_BYTES} in total.
     * <p>
     * Truncating at the bound cannot change a verdict. Every record the parser consumes ends at or
     * below the bound ({@link #recordBodyVerdict(int)} fails closed otherwise), so the bytes past it
     * are only ever examined by the header test of {@link #recordHeaderVerdict()}: there a truncated
     * buffer either still holds the full header, or holds fewer than {@link #RECORD_HEADER_LENGTH}
     * bytes while {@code receivedLength} has reached the bound — which fails closed exactly as reading
     * the full header would, since a record starting that close to the bound ends past it. And a
     * "keep buffering" verdict is only ever returned while {@code receivedLength} is still below the
     * bound, so a truncated buffer never waits for bytes it has discarded.
     */
    private void appendReceived(byte[] chunk) {
        int accepted = Math.min(chunk.length, MAX_CLIENT_HELLO_BYTES - receivedLength);
        received = ensureCapacity(received, receivedLength + accepted);
        System.arraycopy(chunk, 0, received, receivedLength, accepted);
        receivedLength += accepted;
    }

    /** Appends one consumed record body, {@code length} bytes from {@code offset} of the received bytes. */
    private void appendHandshake(int offset, int length) {
        handshake = ensureCapacity(handshake, handshakeLength + length);
        System.arraycopy(received, offset, handshake, handshakeLength, length);
        handshakeLength += length;
    }

    /**
     * Grows {@code array} by doubling until it holds {@code required} bytes. Both callers keep
     * {@code required} at or below {@link #MAX_CLIENT_HELLO_BYTES}, so the doubling never overflows.
     */
    private static byte[] ensureCapacity(byte[] array, int required) {
        if (required <= array.length) {
            return array;
        }
        int capacity = array.length;
        while (capacity < required) {
            capacity *= 2;
        }
        return Arrays.copyOf(array, capacity);
    }

    /**
     * Walks the fully-reassembled {@code client_hello} body to the end of its extensions block and
     * returns the first {@code host_name} of the first {@code server_name} extension. {@code handshake}
     * holds exactly the declared ClientHello (header included) and nothing past it. Any structural
     * inconsistency (a declared length that runs past the buffer, or an extension that runs past the
     * declared extensions block) yields {@code null} so the caller fails closed.
     * <p>
     * <strong>The walk does not stop at the {@code server_name} extension.</strong> The host read from it
     * is kept, and every extension after it is checked against the block as well; the host is returned
     * only once the walk has reached the end of the block. A ClientHello whose later extension overruns
     * the block is malformed — a conforming TLS stack rejects it — so the host read before the overrun
     * must not route it.
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
            @Nullable String host = null;
            boolean serverNameRead = false;
            while (cursor.position() < extensionsEnd) {
                int type = cursor.readUint16();
                int length = cursor.readUint16();
                int next = cursor.position() + length;
                if (next > extensionsEnd) {
                    // An extension running past the declared extensions block is malformed — wherever it
                    // sits, before or after the server_name — and its body must not be read from the
                    // bytes that follow the block.
                    return null;
                }
                if (type == EXTENSION_TYPE_SERVER_NAME && !serverNameRead) {
                    serverNameRead = true;
                    host = readServerNameExtension(new Cursor(handshake, cursor.position()), length);
                }
                cursor.seek(next);
            }
            // The walk reached the end of the block, so every extension in it was within bounds.
            return host;
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
     * <li>{@link #consumeCompletedRecords()} evaluates {@code uint16(received, consumed + 3)} only after
     * {@link #recordHeaderVerdict()} returned {@code null}, which happens solely when
     * {@code receivedLength - consumed >= RECORD_HEADER_LENGTH} — so
     * {@code consumed + 4 <= receivedLength - 1}, and {@code receivedLength} never exceeds the length
     * of {@code received}. {@code consumed} starts at {@code 0} and only ever advances to a
     * {@code recordEnd} that {@link #recordBodyVerdict(int)} already bounded by
     * {@link #MAX_CLIENT_HELLO_BYTES}, so it is never negative and never overflows.</li>
     * <li>{@link Cursor#readUint16()} evaluates {@code uint16(data, position)} only after
     * {@link Cursor#require(int)} asserted {@code position + 2 <= data.length}, raising
     * {@link MalformedHelloException} otherwise. {@code position} starts at
     * {@link #HANDSHAKE_HEADER_LENGTH} and never decreases ({@link Cursor#seek(int)} refuses a
     * backwards target), so it is never negative.</li>
     * </ul>
     * Both guards live in extracted helpers rather than inline in the reading method, which is why
     * the symbolic-execution engine cannot see them; {@code ClientHelloSniParserTest} pins the
     * {@code offer} guard at the exact one-byte-short-of-a-record-header boundary, on the first
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
     * {@link #handshakeSpan()} has already returned {@link HandshakeSpan#INCOMPLETE} unless
     * {@code handshakeLength >= HANDSHAKE_HEADER_LENGTH}, so offsets {@code 1..3} are always within
     * the reassembled bytes.
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
     * The verdict of a single {@link ClientHelloSniParser#offer(byte[])} call.
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
