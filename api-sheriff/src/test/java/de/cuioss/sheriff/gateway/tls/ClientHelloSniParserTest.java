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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientHelloSniParser}: full ClientHello reassembly across TCP fragments and
 * TLS records, SNI extraction, the fragmented / empty / malformed fail-closed (GW-06) verdicts, and
 * fragment invariance of the incremental API — the same bytes offered whole, byte by byte, record by
 * record or split across many tiny records yield the same verdict.
 * <p>
 * The tests construct real TLS ClientHello byte layouts (RFC 8446 §4 / RFC 6066 §3) with the
 * {@link ClientHelloFixture} helper, so the parser is exercised byte-for-byte with no framework
 * dependency. A parser holds the state of one connection, so every case uses a fresh one: JUnit
 * creates a new test instance, and with it a new {@link #parser}, per test method.
 */
@DisplayName("ClientHelloSniParser")
class ClientHelloSniParserTest {

    private final ClientHelloSniParser parser = new ClientHelloSniParser();

    /**
     * Offers {@code bytes} to a fresh parser in chunks of {@code chunkSize}, asserting that every
     * offer before the last answers {@link ClientHelloSniParser.Result#needMoreData()}.
     *
     * @return the verdict of the last offer
     */
    private static ClientHelloSniParser.Result offerInChunks(byte[] bytes, int chunkSize) {
        ClientHelloSniParser fresh = new ClientHelloSniParser();
        ClientHelloSniParser.Result result = ClientHelloSniParser.Result.needMoreData();
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            assertFalse(result.complete(), "no verdict before the final byte, yet one arrived before offset " + offset);
            result = fresh.offer(Arrays.copyOfRange(bytes, offset, Math.min(offset + chunkSize, bytes.length)));
        }
        return result;
    }

    @Nested
    @DisplayName("fragment invariance of the incremental API")
    class FragmentInvariance {

        @Test
        @DisplayName("yields the same verdict for a ClientHello offered byte by byte as offered whole")
        void byteByByteMatchesWhole() {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni("bytewise.example.com");
            ClientHelloSniParser.Result whole = parser.offer(hello);

            // Act
            ClientHelloSniParser.Result byteByByte = offerInChunks(hello, 1);

            // Assert
            assertEquals(whole, byteByByte, "a ClientHello offered byte by byte yields the whole-offer verdict");
            assertEquals("bytewise.example.com", byteByByte.serverName());
        }

        @Test
        @DisplayName("yields the same verdict for a ClientHello offered record by record as offered whole")
        void recordByRecordMatchesWhole() {
            // Arrange — two records, the first carrying 40 handshake bytes behind its 5-byte header
            byte[] twoRecords = ClientHelloFixture.withSniSplitAcrossRecords("records.example.org", 40);
            byte[] firstRecord = Arrays.copyOf(twoRecords, 5 + 40);
            byte[] secondRecord = Arrays.copyOfRange(twoRecords, 5 + 40, twoRecords.length);
            ClientHelloSniParser.Result whole = new ClientHelloSniParser().offer(twoRecords);

            // Act
            ClientHelloSniParser.Result afterFirst = parser.offer(firstRecord);
            ClientHelloSniParser.Result afterSecond = parser.offer(secondRecord);

            // Assert
            assertFalse(afterFirst.complete(), "the first record alone leaves the ClientHello incomplete");
            assertEquals(whole, afterSecond, "record-by-record offers yield the whole-offer verdict");
            assertEquals("records.example.org", afterSecond.serverName());
        }

        @Test
        @DisplayName("yields the same verdict for a ClientHello split across many one-byte records")
        void manyTinyRecordsMatchWhole() {
            // Arrange — every handshake byte in a record of its own: the shape that made the whole-buffer
            // re-parse quadratic
            byte[] tinyRecords = ClientHelloFixture.withSniSplitIntoRecordsOf("tiny.example.net", 1);

            // Act
            ClientHelloSniParser.Result whole = parser.offer(tinyRecords);
            ClientHelloSniParser.Result byteByByte = offerInChunks(tinyRecords, 1);
            ClientHelloSniParser.Result oddChunks = offerInChunks(tinyRecords, 7);

            // Assert
            assertEquals("tiny.example.net", whole.serverName(), "many tiny records reassemble into one ClientHello");
            assertEquals(whole, byteByByte, "tiny records offered byte by byte yield the whole-offer verdict");
            assertEquals(whole, oddChunks, "tiny records offered in odd chunks yield the whole-offer verdict");
        }

        @Test
        @DisplayName("keeps a reached verdict for every later offer without reading the new bytes")
        void verdictIsFinal() {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni("final.example.com");
            ClientHelloSniParser.Result reached = parser.offer(hello);

            // Act — bytes that would fail closed if they were ever parsed as a record
            ClientHelloSniParser.Result later = parser.offer("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));

            // Assert
            assertEquals(reached, later, "a reached verdict is final");
        }

        @Test
        @DisplayName("fails closed at the bound whether the buffer arrives whole or in two chunks")
        void boundVerdictIsFragmentInvariant() {
            // Arrange — the at-bound shape of FailClosed#bufferedBytesAtBoundFailClosed
            byte[] atBound = ClientHelloFixture.incompleteHelloWithPartialNextHeader(
                    ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES, 1);

            // Act
            ClientHelloSniParser.Result twoChunks = offerInChunks(atBound, atBound.length - 1);

            // Assert
            assertTrue(twoChunks.complete(), "the buffer reaching the bound in its second chunk is decided");
            assertNull(twoChunks.serverName(), "reaching the buffering bound fails closed");
        }

        @Test
        @DisplayName("fails closed on a chunk that runs past the bound, keeping only the bytes up to it")
        void chunkPastBoundFailsClosed() {
            // Arrange — the at-bound shape followed by bytes past the bound in the same chunk
            byte[] atBound = ClientHelloFixture.incompleteHelloWithPartialNextHeader(
                    ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES, 1);
            byte[] pastBound = ClientHelloFixture.concat(atBound, new byte[1024]);

            // Act
            ClientHelloSniParser.Result result = parser.offer(pastBound);

            // Assert
            assertTrue(result.complete(), "a chunk running past the bound is decided");
            assertNull(result.serverName(), "a chunk running past the bound fails closed");
        }
    }

    @Nested
    @DisplayName("SNI extraction from a complete ClientHello")
    class Complete {

        @Test
        @DisplayName("extracts the SNI host_name from a single-record ClientHello")
        void extractsServerName() {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni("api.example.com");

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete(), "a full ClientHello is complete");
            assertEquals("api.example.com", result.serverName());
        }

        @Test
        @DisplayName("preserves SNI case verbatim (normalization is the listener's concern)")
        void preservesCase() {
            byte[] hello = ClientHelloFixture.withSni("API.Example.COM");

            ClientHelloSniParser.Result result = parser.offer(hello);

            assertEquals("API.Example.COM", result.serverName());
        }

        @Test
        @DisplayName("returns an empty server name for a ClientHello with no SNI extension")
        void noSniExtension() {
            byte[] hello = ClientHelloFixture.withoutSni();

            ClientHelloSniParser.Result result = parser.offer(hello);

            assertTrue(result.complete(), "a full ClientHello without SNI is still complete");
            assertNull(result.serverName(), "no SNI extension → null → fail closed");
        }

        @Test
        @DisplayName("returns an empty server name for an empty host_name (fail closed)")
        void emptyHostName() {
            byte[] hello = ClientHelloFixture.withSni("");

            ClientHelloSniParser.Result result = parser.offer(hello);

            assertTrue(result.complete());
            assertNull(result.serverName());
        }
    }

    @Nested
    @DisplayName("reassembly across fragments")
    class Reassembly {

        @Test
        @DisplayName("asks for more data until the full ClientHello has arrived")
        void needsMoreDataForAPrefix() {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni("relay.internal");
            byte[] prefix = Arrays.copyOf(hello, hello.length - 4);
            byte[] rest = Arrays.copyOfRange(hello, hello.length - 4, hello.length);

            // Act
            ClientHelloSniParser.Result partial = parser.offer(prefix);
            ClientHelloSniParser.Result full = parser.offer(rest);

            // Assert
            assertFalse(partial.complete(), "a truncated ClientHello is incomplete");
            assertNull(partial.serverName());
            assertTrue(full.complete());
            assertEquals("relay.internal", full.serverName());
        }

        @Test
        @DisplayName("asks for more data when only the record header has arrived")
        void needsMoreDataForRecordHeaderOnly() {
            byte[] hello = ClientHelloFixture.withSni("relay.internal");
            byte[] justHeader = Arrays.copyOf(hello, 3);

            ClientHelloSniParser.Result result = parser.offer(justHeader);

            assertFalse(result.complete());
        }

        @Test
        @DisplayName("asks for more data when the first record header is one byte short")
        void needsMoreDataForOneByteShortRecordHeader() {
            // Arrange — exactly RECORD_HEADER_LENGTH - 1 bytes. The low byte of the record-length
            // field (index 4) has not arrived, so reading it is precisely the out-of-bounds access
            // the loop-head length guard exists to prevent.
            byte[] hello = ClientHelloFixture.withSni("relay.internal");
            byte[] oneByteShortHeader = Arrays.copyOf(hello, 4);

            // Act
            ClientHelloSniParser.Result result = parser.offer(oneByteShortHeader);

            // Assert
            assertFalse(result.complete(), "a header one byte short is buffered, never read past");
            assertNull(result.serverName());
        }

        @Test
        @DisplayName("asks for more data when a later record header is one byte short")
        void needsMoreDataForOneByteShortLaterRecordHeader() {
            // Arrange — a first handshake record that leaves the ClientHello incomplete, followed by
            // only 4 of the 5 bytes of the second record header. The loop-back re-enters the header
            // guard at pos > 0, which is the tail check the cognitive-complexity split delegated to
            // the loop head.
            byte[] twoRecords = ClientHelloFixture.withSniSplitAcrossRecords("split.example.org", 40);
            byte[] truncated = Arrays.copyOf(twoRecords, 5 + 40 + 4);

            // Act
            ClientHelloSniParser.Result result = parser.offer(truncated);

            // Assert
            assertFalse(result.complete(),
                    "a later header one byte short is buffered, never read past");
            assertNull(result.serverName());
        }

        @Test
        @DisplayName("reassembles a ClientHello split across two TLS handshake records")
        void reassemblesAcrossTwoRecords() {
            // Arrange
            byte[] twoRecords = ClientHelloFixture.withSniSplitAcrossRecords("split.example.org", 40);

            // Act
            ClientHelloSniParser.Result result = parser.offer(twoRecords);

            // Assert
            assertTrue(result.complete(), "two handshake records reassemble into one ClientHello");
            assertEquals("split.example.org", result.serverName());
        }
    }

    @Nested
    @DisplayName("fail-closed verdicts (GW-06)")
    class FailClosed {

        @Test
        @DisplayName("treats a non-handshake first byte as complete-with-no-SNI")
        void nonTlsFirstByte() {
            byte[] httpRequest = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

            ClientHelloSniParser.Result result = parser.offer(httpRequest);

            assertTrue(result.complete(), "a non-TLS stream is decided immediately, never buffered");
            assertNull(result.serverName(), "not a TLS handshake → fail closed");
        }

        @Test
        @DisplayName("treats a non-ClientHello handshake type as complete-with-no-SNI")
        void wrongHandshakeType() {
            byte[] serverHello = ClientHelloFixture.handshakeRecord((byte) 0x02, new byte[16]);

            ClientHelloSniParser.Result result = parser.offer(serverHello);

            assertTrue(result.complete());
            assertNull(result.serverName());
        }

        @Test
        @DisplayName("fails closed on an extension length that runs past the buffer")
        void malformedExtensionLength() {
            byte[] hello = ClientHelloFixture.withCorruptExtensionLength();

            ClientHelloSniParser.Result result = parser.offer(hello);

            assertTrue(result.complete());
            assertNull(result.serverName(), "a structural inconsistency fails closed");
        }

        @Test
        @DisplayName("fails closed rather than buffering unboundedly past the size bound")
        void oversizeRecordFailsClosed() {
            byte[] oversize = ClientHelloFixture.oversizeRecordHeader();

            ClientHelloSniParser.Result result = parser.offer(oversize);

            assertTrue(result.complete(), "an oversize declared record is decided, never buffered");
            assertNull(result.serverName());
        }

        @Test
        @DisplayName("fails closed once the buffered bytes reach the bound, not once the consumed bytes do")
        void bufferedBytesAtBoundFailClosed() {
            // Arrange — MAX_CLIENT_HELLO_BYTES bytes buffered, of which only MAX - 1 have been
            // consumed: the trailing byte opens a record header that cannot be read yet. A give-up
            // test anchored on the consumed position answers needMoreData here, so the caller keeps
            // buffering a buffer that has already reached the hard bound the constant declares.
            byte[] atBound = ClientHelloFixture.incompleteHelloWithPartialNextHeader(
                    ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES, 1);

            // Act
            ClientHelloSniParser.Result result = parser.offer(atBound);

            // Assert
            assertTrue(result.complete(),
                    "a buffer at the hard bound is decided, never handed back for more buffering");
            assertNull(result.serverName(), "reaching the buffering bound fails closed");
        }

        @Test
        @DisplayName("still asks for more data one byte below the bound")
        void bufferedBytesBelowBoundKeepBuffering() {
            // Arrange — the matched control for the test above: the identical shape one byte smaller.
            // It pins the give-up test to MAX_CLIENT_HELLO_BYTES exactly, so the fail-closed verdict
            // above cannot be satisfied by a guard that gives up early on every fragmented ClientHello.
            byte[] belowBound = ClientHelloFixture.incompleteHelloWithPartialNextHeader(
                    ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES - 1, 1);

            // Act
            ClientHelloSniParser.Result result = parser.offer(belowBound);

            // Assert
            assertFalse(result.complete(), "a buffer below the bound is still reassembling");
            assertNull(result.serverName(), "an incomplete ClientHello carries no server name");
        }

        @Test
        @DisplayName("never reads an SNI from bytes trailing the declared ClientHello in the same record")
        void trailingBytesInRecordCannotSupplySni() {
            // Arrange — a ClientHello that ends after compression_methods (no extensions block, so no
            // SNI), followed inside the same handshake record by bytes shaped as an extensions block
            // carrying a server_name. Only the declared ClientHello length is the ClientHello; the
            // trailing bytes must never be walked as its extensions, or a hello without an SNI could be
            // routed to a passthrough backend instead of failing closed.
            byte[] smuggledExtensions = ClientHelloFixture.extensionsBlock(
                    ClientHelloFixture.serverNameExtension("smuggled.example.com"));
            byte[] hello = ClientHelloFixture.withoutExtensionsFollowedInRecordBy(smuggledExtensions);

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete(), "the declared ClientHello is complete");
            assertNull(result.serverName(),
                    "bytes past the declared ClientHello length never supply the routed SNI");
        }
    }

    @Nested
    @DisplayName("extension-walk and name-entry edge cases")
    class ExtensionWalk {

        @Test
        @DisplayName("skips a preceding non-SNI extension and still extracts the SNI host_name")
        void skipsPrecedingExtension() {
            // Arrange — a benign supported_groups-shaped extension precedes the server_name extension,
            // so the walk must seek past it before reaching the SNI.
            byte[] preceding = ClientHelloFixture.rawExtension(0x000A, new byte[]{0x00, 0x02, 0x00, 0x17});
            byte[] sni = ClientHelloFixture.serverNameExtension("host.after.example");
            byte[] hello = ClientHelloFixture.withRawExtensions(ClientHelloFixture.concat(preceding, sni));

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete());
            assertEquals("host.after.example", result.serverName(),
                    "the walk seeks past a leading non-SNI extension");
        }

        @Test
        @DisplayName("skips a non-host_name server_name entry and returns a following host_name")
        void skipsNonHostNameEntry() {
            // Arrange — the server_name list carries a non-host_name entry (name_type 0x01) ahead of
            // the real host_name entry.
            byte[] entries = ClientHelloFixture.concat(
                    ClientHelloFixture.nameEntry(0x01, "ignored".getBytes(StandardCharsets.US_ASCII)),
                    ClientHelloFixture.nameEntry(0x00, "host.example".getBytes(StandardCharsets.US_ASCII)));
            byte[] hello = ClientHelloFixture.withRawExtensions(
                    ClientHelloFixture.serverNameExtensionFromEntries(entries));

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertEquals("host.example", result.serverName(),
                    "a non-host_name entry is skipped, the host_name entry wins");
        }

        @Test
        @DisplayName("fails closed on a server_name extension body shorter than its list-length prefix")
        void truncatedServerNameExtension() {
            // Arrange — a server_name extension whose body is a single byte cannot hold the 2-byte
            // list-length prefix.
            byte[] sni = ClientHelloFixture.rawExtension(0x0000, new byte[]{0x00});
            byte[] hello = ClientHelloFixture.withRawExtensions(sni);

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete());
            assertNull(result.serverName(), "a body too short for the list prefix fails closed");
        }

        @Test
        @DisplayName("fails closed when the server_name list length overruns the extension body")
        void overlongServerNameList() {
            // Arrange — the extension declares a 2-byte body carrying a list length (0x007F) that runs
            // well past the extension end.
            byte[] sni = ClientHelloFixture.rawExtension(0x0000, new byte[]{0x00, 0x7F});
            byte[] hello = ClientHelloFixture.withRawExtensions(sni);

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete());
            assertNull(result.serverName(), "a list length past the extension body fails closed");
        }

        @Test
        @DisplayName("fails closed when a name entry declares a length past the list end")
        void nameEntryLengthPastList() {
            // Arrange — the single name entry declares a 0x007F-byte name but carries no name bytes.
            byte[] entry = {0x00, 0x00, 0x7F};
            byte[] hello = ClientHelloFixture.withRawExtensions(
                    ClientHelloFixture.serverNameExtensionFromEntries(entry));

            // Act
            ClientHelloSniParser.Result result = parser.offer(hello);

            // Assert
            assertTrue(result.complete());
            assertNull(result.serverName(), "a name length past the list end fails closed");
        }
    }

    /**
     * Builds real TLS ClientHello byte layouts for the parser tests. Kept package-visible and static
     * so both this test and {@code SniFrontListenerTest} / {@code PassthroughRelayTest} can craft the
     * same fixtures.
     */
    static final class ClientHelloFixture {

        private static final byte RECORD_HANDSHAKE = 0x16;
        private static final byte HANDSHAKE_CLIENT_HELLO = 0x01;
        private static final int EXTENSION_TYPE_SERVER_NAME = 0x0000;
        private static final int RECORD_HEADER_LENGTH = 5;
        private static final int HANDSHAKE_HEADER_LENGTH = 4;

        private ClientHelloFixture() {
        }

        /** A complete single-record ClientHello carrying the given SNI {@code host_name}. */
        static byte[] withSni(String sni) {
            return handshakeRecord(HANDSHAKE_CLIENT_HELLO, clientHelloBody(sni));
        }

        /** A complete single-record ClientHello with no {@code server_name} extension. */
        static byte[] withoutSni() {
            return handshakeRecord(HANDSHAKE_CLIENT_HELLO, clientHelloBody(null));
        }

        /** A ClientHello whose handshake message is split across two handshake records. */
        static byte[] withSniSplitAcrossRecords(String sni, int firstRecordPayload) {
            byte[] handshake = handshakeMessage(HANDSHAKE_CLIENT_HELLO, clientHelloBody(sni));
            byte[] first = Arrays.copyOfRange(handshake, 0, firstRecordPayload);
            byte[] second = Arrays.copyOfRange(handshake, firstRecordPayload, handshake.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRecord(out, first);
            writeRecord(out, second);
            return out.toByteArray();
        }

        /**
         * A ClientHello whose handshake message is split into consecutive handshake records of
         * {@code recordPayload} bytes each (the last one possibly shorter).
         */
        static byte[] withSniSplitIntoRecordsOf(String sni, int recordPayload) {
            byte[] handshake = handshakeMessage(HANDSHAKE_CLIENT_HELLO, clientHelloBody(sni));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int offset = 0; offset < handshake.length; offset += recordPayload) {
                writeRecord(out, Arrays.copyOfRange(handshake, offset, Math.min(offset + recordPayload,
                        handshake.length)));
            }
            return out.toByteArray();
        }

        /**
         * A ClientHello carrying a well-formed {@code server_name} extension whose declared
         * extensions-block length is inflated past the actual bytes, so the extensions region
         * overruns the reassembled handshake buffer. The {@code session_id} and {@code cipher_suites}
         * length fields stay intact, so the parser reaches the extension walk and fails closed on the
         * {@code extensionsEnd > handshake.length} bound check.
         */
        static byte[] withCorruptExtensionLength() {
            byte[] extensions = serverNameExtension("corrupt.example.com");
            return withRawExtensionsDeclaring(extensions, extensions.length + 0x0100);
        }

        /** A complete single-record ClientHello whose extensions block is exactly the given bytes. */
        static byte[] withRawExtensions(byte[] extensionsBlock) {
            return handshakeRecord(HANDSHAKE_CLIENT_HELLO,
                    clientHelloBodyRaw(extensionsBlock, extensionsBlock.length));
        }

        /**
         * A complete single-record ClientHello whose extensions block is {@code extensionsBlock} but
         * whose declared extensions-block length is {@code declaredLength}. Passing a
         * {@code declaredLength} larger than {@code extensionsBlock.length} inflates the extensions
         * region past the buffer to exercise the parser's extensions-length overrun (fail-closed) path.
         */
        static byte[] withRawExtensionsDeclaring(byte[] extensionsBlock, int declaredLength) {
            return handshakeRecord(HANDSHAKE_CLIENT_HELLO,
                    clientHelloBodyRaw(extensionsBlock, declaredLength));
        }

        /**
         * A single handshake record holding a complete ClientHello that ends after
         * {@code compression_methods} — no extensions block, hence no SNI — followed inside the same
         * record by {@code trailing}, which lies past the ClientHello's declared handshake length.
         */
        static byte[] withoutExtensionsFollowedInRecordBy(byte[] trailing) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRecord(out, concat(handshakeMessage(HANDSHAKE_CLIENT_HELLO, clientHelloFixedFields()), trailing));
            return out.toByteArray();
        }

        /** An extensions block: its 2-byte length followed by the given extension bytes. */
        static byte[] extensionsBlock(byte[] extensions) {
            return concat(new byte[]{(byte) ((extensions.length >> 8) & 0xFF), (byte) (extensions.length & 0xFF)},
                    extensions);
        }

        /** A raw TLS extension: {@code type(2) + length(2) + body}. */
        static byte[] rawExtension(int type, byte[] body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write((type >> 8) & 0xFF);
            out.write(type & 0xFF);
            out.write((body.length >> 8) & 0xFF);
            out.write(body.length & 0xFF);
            out.writeBytes(body);
            return out.toByteArray();
        }

        /** A server_name extension whose {@code server_name_list} is exactly the given entry bytes. */
        static byte[] serverNameExtensionFromEntries(byte[] entries) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write((entries.length >> 8) & 0xFF);
            body.write(entries.length & 0xFF);
            body.writeBytes(entries);
            return rawExtension(EXTENSION_TYPE_SERVER_NAME, body.toByteArray());
        }

        /** A single {@code server_name} list entry: {@code name_type(1) + length(2) + name}. */
        static byte[] nameEntry(int nameType, byte[] name) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(nameType);
            out.write((name.length >> 8) & 0xFF);
            out.write(name.length & 0xFF);
            out.writeBytes(name);
            return out.toByteArray();
        }

        /** Concatenates the given byte arrays in order. */
        static byte[] concat(byte[]... arrays) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] array : arrays) {
                out.writeBytes(array);
            }
            return out.toByteArray();
        }

        /**
         * A buffer of exactly {@code totalLength} bytes made of one handshake record that leaves the
         * ClientHello incomplete, followed by {@code partialHeaderBytes} bytes of the next record
         * header — fewer than the {@value #RECORD_HEADER_LENGTH} a full header needs, so the parser
         * stops at the loop-head length guard.
         * <p>
         * This is the shape that separates the two candidate anchors of the give-up test: the bytes
         * <em>consumed</em> stop at the first record's end, while the bytes <em>buffered</em> run on to
         * {@code totalLength}. The first record's declared handshake body reaches the bound exactly, so
         * the reassembled handshake is incomplete without being malformed.
         */
        static byte[] incompleteHelloWithPartialNextHeader(int totalLength, int partialHeaderBytes) {
            int recordEnd = totalLength - partialHeaderBytes;
            byte[] payload = new byte[recordEnd - RECORD_HEADER_LENGTH];
            int declaredBody = ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES - HANDSHAKE_HEADER_LENGTH;
            payload[0] = HANDSHAKE_CLIENT_HELLO;
            payload[1] = (byte) ((declaredBody >> 16) & 0xFF);
            payload[2] = (byte) ((declaredBody >> 8) & 0xFF);
            payload[3] = (byte) (declaredBody & 0xFF);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRecord(out, payload);
            out.write(new byte[]{RECORD_HANDSHAKE, 0x03, 0x01, 0x00}, 0, partialHeaderBytes);
            return out.toByteArray();
        }

        /** A record header declaring a length beyond the parser's reassembly bound. */
        static byte[] oversizeRecordHeader() {
            int declared = ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES + 1;
            return new byte[]{
                    RECORD_HANDSHAKE, 0x03, 0x01,
                    (byte) ((declared >> 8) & 0xFF), (byte) (declared & 0xFF)
            };
        }

        /** Wraps a handshake message in a single TLS handshake record. */
        static byte[] handshakeRecord(byte handshakeType, byte[] body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeRecord(out, handshakeMessage(handshakeType, body));
            return out.toByteArray();
        }

        private static byte[] handshakeMessage(byte handshakeType, byte[] body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(handshakeType);
            out.write((body.length >> 16) & 0xFF);
            out.write((body.length >> 8) & 0xFF);
            out.write(body.length & 0xFF);
            out.writeBytes(body);
            return out.toByteArray();
        }

        private static void writeRecord(ByteArrayOutputStream out, byte[] payload) {
            out.write(RECORD_HANDSHAKE);
            out.write(0x03);
            out.write(0x01);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
            out.writeBytes(payload);
        }

        /**
         * The ClientHello body fields up to and including {@code compression_methods}, i.e. a body
         * without any extensions block.
         */
        private static byte[] clientHelloFixedFields() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x03);
            out.write(0x03);                       // legacy_version TLS 1.2
            out.writeBytes(new byte[32]);          // random
            out.write(0x00);                       // session_id length 0
            out.write(0x00);
            out.write(0x02);                       // cipher_suites length 2
            out.write(0x13);
            out.write(0x01);                       // TLS_AES_128_GCM_SHA256
            out.write(0x01);                       // compression_methods length 1
            out.write(0x00);                       // null compression
            return out.toByteArray();
        }

        private static byte[] clientHelloBody(String sni) {
            byte[] extensions = sni == null ? new byte[0] : serverNameExtension(sni);
            return clientHelloBodyRaw(extensions, extensions.length);
        }

        private static byte[] clientHelloBodyRaw(byte[] extensions, int declaredExtensionsLength) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(clientHelloFixedFields());
            out.write((declaredExtensionsLength >> 8) & 0xFF);
            out.write(declaredExtensionsLength & 0xFF);
            out.writeBytes(extensions);
            return out.toByteArray();
        }

        /** A complete {@code server_name} extension carrying a single {@code host_name} entry. */
        static byte[] serverNameExtension(String sni) {
            byte[] host = sni.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            entry.write(0x00);                     // name_type host_name
            entry.write((host.length >> 8) & 0xFF);
            entry.write(host.length & 0xFF);
            entry.writeBytes(host);
            byte[] list = entry.toByteArray();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write((list.length >> 8) & 0xFF);
            body.write(list.length & 0xFF);
            body.writeBytes(list);
            byte[] extensionBody = body.toByteArray();

            ByteArrayOutputStream extension = new ByteArrayOutputStream();
            extension.write(0x00);
            extension.write(0x00);                 // extension type server_name
            extension.write((extensionBody.length >> 8) & 0xFF);
            extension.write(extensionBody.length & 0xFF);
            extension.writeBytes(extensionBody);
            return extension.toByteArray();
        }
    }
}
