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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientHelloSniParser}: full ClientHello reassembly across TCP fragments and
 * TLS records, SNI extraction, and the fragmented / empty / malformed fail-closed (GW-06) verdicts.
 * <p>
 * The tests construct real TLS ClientHello byte layouts (RFC 8446 §4 / RFC 6066 §3) with the
 * {@link ClientHelloFixture} helper, so the parser is exercised byte-for-byte with no framework
 * dependency.
 */
@DisplayName("ClientHelloSniParser")
class ClientHelloSniParserTest {

    private final ClientHelloSniParser parser = new ClientHelloSniParser();

    @Nested
    @DisplayName("SNI extraction from a complete ClientHello")
    class Complete {

        @Test
        @DisplayName("extracts the SNI host_name from a single-record ClientHello")
        void extractsServerName() {
            // Arrange
            byte[] hello = ClientHelloFixture.withSni("api.example.com");

            // Act
            ClientHelloSniParser.Result result = parser.parse(hello);

            // Assert
            assertTrue(result.complete(), "a full ClientHello is complete");
            assertEquals(Optional.of("api.example.com"), result.serverName());
        }

        @Test
        @DisplayName("preserves SNI case verbatim (normalization is the listener's concern)")
        void preservesCase() {
            byte[] hello = ClientHelloFixture.withSni("API.Example.COM");

            ClientHelloSniParser.Result result = parser.parse(hello);

            assertEquals(Optional.of("API.Example.COM"), result.serverName());
        }

        @Test
        @DisplayName("returns an empty server name for a ClientHello with no SNI extension")
        void noSniExtension() {
            byte[] hello = ClientHelloFixture.withoutSni();

            ClientHelloSniParser.Result result = parser.parse(hello);

            assertTrue(result.complete(), "a full ClientHello without SNI is still complete");
            assertTrue(result.serverName().isEmpty(), "no SNI extension → empty → fail closed");
        }

        @Test
        @DisplayName("returns an empty server name for an empty host_name (fail closed)")
        void emptyHostName() {
            byte[] hello = ClientHelloFixture.withSni("");

            ClientHelloSniParser.Result result = parser.parse(hello);

            assertTrue(result.complete());
            assertTrue(result.serverName().isEmpty());
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

            // Act
            ClientHelloSniParser.Result partial = parser.parse(prefix);
            ClientHelloSniParser.Result full = parser.parse(hello);

            // Assert
            assertFalse(partial.complete(), "a truncated ClientHello is incomplete");
            assertTrue(partial.serverName().isEmpty());
            assertTrue(full.complete());
            assertEquals(Optional.of("relay.internal"), full.serverName());
        }

        @Test
        @DisplayName("asks for more data when only the record header has arrived")
        void needsMoreDataForRecordHeaderOnly() {
            byte[] hello = ClientHelloFixture.withSni("relay.internal");
            byte[] justHeader = Arrays.copyOf(hello, 3);

            ClientHelloSniParser.Result result = parser.parse(justHeader);

            assertFalse(result.complete());
        }

        @Test
        @DisplayName("reassembles a ClientHello split across two TLS handshake records")
        void reassemblesAcrossTwoRecords() {
            // Arrange
            byte[] twoRecords = ClientHelloFixture.withSniSplitAcrossRecords("split.example.org", 40);

            // Act
            ClientHelloSniParser.Result result = parser.parse(twoRecords);

            // Assert
            assertTrue(result.complete(), "two handshake records reassemble into one ClientHello");
            assertEquals(Optional.of("split.example.org"), result.serverName());
        }
    }

    @Nested
    @DisplayName("fail-closed verdicts (GW-06)")
    class FailClosed {

        @Test
        @DisplayName("treats a non-handshake first byte as complete-with-no-SNI")
        void nonTlsFirstByte() {
            byte[] httpRequest = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

            ClientHelloSniParser.Result result = parser.parse(httpRequest);

            assertTrue(result.complete(), "a non-TLS stream is decided immediately, never buffered");
            assertTrue(result.serverName().isEmpty(), "not a TLS handshake → fail closed");
        }

        @Test
        @DisplayName("treats a non-ClientHello handshake type as complete-with-no-SNI")
        void wrongHandshakeType() {
            byte[] serverHello = ClientHelloFixture.handshakeRecord((byte) 0x02, new byte[16]);

            ClientHelloSniParser.Result result = parser.parse(serverHello);

            assertTrue(result.complete());
            assertTrue(result.serverName().isEmpty());
        }

        @Test
        @DisplayName("fails closed on an extension length that runs past the buffer")
        void malformedExtensionLength() {
            byte[] hello = ClientHelloFixture.withCorruptExtensionLength();

            ClientHelloSniParser.Result result = parser.parse(hello);

            assertTrue(result.complete());
            assertTrue(result.serverName().isEmpty(), "a structural inconsistency fails closed");
        }

        @Test
        @DisplayName("fails closed rather than buffering unboundedly past the size bound")
        void oversizeRecordFailsClosed() {
            byte[] oversize = ClientHelloFixture.oversizeRecordHeader();

            ClientHelloSniParser.Result result = parser.parse(oversize);

            assertTrue(result.complete(), "an oversize declared record is decided, never buffered");
            assertTrue(result.serverName().isEmpty());
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

        /** A ClientHello whose SNI extension declares a length running past the buffer. */
        static byte[] withCorruptExtensionLength() {
            byte[] hello = withSni("corrupt.example.com");
            // The extension length is the two bytes immediately after the server_name extension type
            // (0x00 0x00). Locate the first 0x00 0x00 pair inside the extensions area and inflate the
            // following length so it overruns the buffer.
            for (int i = 5 + 4 + 34; i < hello.length - 4; i++) {
                if (hello[i] == 0x00 && hello[i + 1] == 0x00) {
                    hello[i + 2] = (byte) 0x7F;
                    hello[i + 3] = (byte) 0xFF;
                    break;
                }
            }
            return hello;
        }

        /** A record header declaring a length beyond the parser's reassembly bound. */
        static byte[] oversizeRecordHeader() {
            int declared = ClientHelloSniParser.MAX_CLIENT_HELLO_BYTES + 1;
            return new byte[] {
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

        private static byte[] clientHelloBody(String sni) {
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
            byte[] extensions = sni == null ? new byte[0] : serverNameExtension(sni);
            out.write((extensions.length >> 8) & 0xFF);
            out.write(extensions.length & 0xFF);
            out.writeBytes(extensions);
            return out.toByteArray();
        }

        private static byte[] serverNameExtension(String sni) {
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
