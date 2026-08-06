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
package de.cuioss.sheriff.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConnectionHeaders}: the one response-direction connection-header policy both
 * client-facing paths read — the proxy relay and the asset envelope.
 * <p>
 * The membership assertion is deliberately exhaustive rather than sampled. The set is a protocol
 * fact, not a tuning knob: RFC 9113 §8.2.2 makes a response carrying one of these malformed, and
 * an HTTP/2 client discards the stream outright, so a name silently dropped from the set does not
 * degrade a response — it deletes it (issue #172). An exhaustive expectation is what makes such an
 * edit fail here instead of in a browser.
 */
class ConnectionHeadersTest {

    @Test
    @DisplayName("Should carry exactly the RFC 9113 connection-specific set plus the hop and framing names")
    void shouldCarryTheExactSet() {
        assertEquals(
                ConnectionHeaders.RESPONSE_STRIP, Set.of(
                        // RFC 9113 §8.2.2 — a response carrying any of these is malformed over HTTP/2.
                        "connection", "proxy-connection", "keep-alive", "transfer-encoding", "upgrade", "te",
                        // RFC 7230 §6.1 — hop-by-hop, describing the hop the gateway terminates.
                        "trailer", "proxy-authenticate", "proxy-authorization",
                        // Framing: a claim about the source's body, which neither relay writes verbatim.
                        "content-length"),
                "the strip set is a protocol fact — a name removed here silently reopens issue #172");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Connection", "CONNECTION", "connection", "Transfer-Encoding", "TE"})
    @DisplayName("Should match a connection-specific name whatever case the source sent it in")
    void shouldMatchCaseInsensitively(String name) {
        assertTrue(ConnectionHeaders.isConnectionSpecific(name),
                () -> name + " must be recognised — an origin sends header names in its own casing");
    }

    @ParameterizedTest
    @ValueSource(strings = {":status", ":authority", ":path"})
    @DisplayName("Should reject any HTTP/2 pseudo-header, which is malformed in a normal header block")
    void shouldRejectPseudoHeaders(String name) {
        assertTrue(ConnectionHeaders.isConnectionSpecific(name),
                () -> name + " is a pseudo-header the gateway may never re-emit as a real field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Type", "Cache-Control", "ETag", "Server", "Set-Cookie", "Location"})
    @DisplayName("Should pass an end-to-end header through — the policy strips connection state only")
    void shouldPassEndToEndHeaders(String name) {
        assertFalse(ConnectionHeaders.isConnectionSpecific(name),
                () -> name + " describes the message, not the connection, and must survive the strip");
    }

    @Test
    @DisplayName("Should reject a null header name rather than silently treating it as forwardable")
    void shouldRejectNullName() {
        assertThrows(NullPointerException.class, () -> ConnectionHeaders.isConnectionSpecific(null));
    }
}
