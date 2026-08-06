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
 * Tests for {@link ConnectionHeaders}: the gateway-owned connection-header policy for both
 * directions — the response set both client-facing paths read (the proxy relay and the asset
 * envelope), and the request set every forward mode is bounded by.
 * <p>
 * Both membership assertions are deliberately exhaustive rather than sampled. The response set is a
 * protocol fact, not a tuning knob: RFC 9113 §8.2.2 makes a response carrying one of these
 * malformed, and an HTTP/2 client discards the stream outright, so a name silently dropped from the
 * set does not degrade a response — it deletes it (issue #172). The request set carries the same
 * weight in the other direction: it is what makes the permissive forward-all baseline safe, so a
 * name quietly dropped from it becomes a credential reaching an upstream. An exhaustive expectation
 * is what makes either edit fail here instead of in production.
 * <p>
 * The two sets are asserted <em>independently</em>, never one derived from the other. Their current
 * superset relation is an observation about two hand-maintained lists; deriving one expectation
 * from the other here would encode that coincidence as a rule and would stop noticing the day a
 * name is added to one direction only.
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

    @Test
    @DisplayName("Should carry exactly the 25 request-direction never-forward names")
    void shouldCarryTheExactRequestSet() {
        assertEquals(
                ConnectionHeaders.REQUEST_STRIP, Set.of(
                        // Credentials the gateway terminates or mediates.
                        "authorization", "cookie",
                        // Hop-by-hop / connection-specific: the hop the gateway terminates.
                        "connection", "keep-alive", "proxy-connection", "te", "trailer",
                        "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization",
                        // Framing the dispatch path re-establishes.
                        "content-length",
                        // Names the gateway itself owns, consumes, or regenerates.
                        "host", "origin", "referer", "date",
                        // Spoofable provenance claims only the gateway is entitled to make: the
                        // underscore spellings a CGI-style backend folds onto the canonical name,
                        // and the vendor client-IP aliases such a backend may trust directly.
                        "x_forwarded_for", "x_forwarded_host", "x_forwarded_proto",
                        "x_forwarded_port", "x_forwarded_prefix",
                        "x-real-ip", "x-client-ip", "true-client-ip", "cf-connecting-ip"),
                "the request-direction set is what bounds the forward-all baseline — a name removed"
                        + " here silently starts relaying it to every upstream");
    }

    @Test
    @DisplayName("Should keep the two direction sets independently enumerated, not derived")
    void shouldKeepTheTwoSetsIndependent() {
        assertEquals(10, ConnectionHeaders.RESPONSE_STRIP.size(),
                "the response set stays at its 10 names; the provenance-variant widening is"
                        + " request-direction only and must not propagate here — a response"
                        + " carrying X-Real-IP is the source's business, not the gateway's");
        assertEquals(25, ConnectionHeaders.REQUEST_STRIP.size(), "the request set carries 25 names");
        assertTrue(ConnectionHeaders.REQUEST_STRIP.containsAll(ConnectionHeaders.RESPONSE_STRIP),
                "the request set currently contains every response name — asserted as the OBSERVATION"
                        + " it is, with both sets pinned exhaustively above, so a name added to one"
                        + " direction alone breaks that set's own expectation rather than being"
                        + " silently derived into the other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cookie", "COOKIE", "cookie", "Authorization", "Host", "TE", "Proxy-Authenticate"})
    @DisplayName("Should withhold a request-direction name whatever case the client sent it in")
    void shouldMatchRequestNamesCaseInsensitively(String name) {
        assertTrue(ConnectionHeaders.isRequestStripped(name),
                () -> name + " must be recognised — a client sends header names in its own casing, and"
                        + " a case-sensitive check would be bypassable by changing one letter");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Type", "Accept", "X-Api-Version", "If-None-Match", "User-Agent"})
    @DisplayName("Should pass a client header the gateway does not own")
    void shouldPassNonGatewayOwnedRequestHeaders(String name) {
        assertFalse(ConnectionHeaders.isRequestStripped(name),
                () -> name + " is the client's to send and the route's mode to govern, not a"
                        + " gateway-owned name");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "X_Forwarded_For", "x_forwarded_for", "X_FORWARDED_FOR",
            "X_Forwarded_Host", "X_Forwarded_Proto", "X_Forwarded_Port", "X_Forwarded_Prefix",
            "X-Real-IP", "x-real-ip", "X-Client-IP", "True-Client-IP", "CF-Connecting-IP"})
    @DisplayName("Should withhold a spoofable provenance variant a CGI-folding backend would trust")
    void shouldWithholdProvenanceVariants(String name) {
        assertTrue(ConnectionHeaders.isRequestStripped(name),
                () -> name + " is a claim about the connection's provenance that only the gateway is"
                        + " entitled to make. Under the old allow-list-only forward model it was"
                        + " dropped incidentally; under forward-all it is an ordinary client header,"
                        + " and a backend folding '-' and '_' into one CGI variable reads it as the"
                        + " canonical name");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
            "X-Forwarded-Prefix", "Forwarded"})
    @DisplayName("Should leave the CANONICAL forwarding names out of this set — they are regenerated, not stripped")
    void shouldNotClaimTheCanonicalForwardingNames(String name) {
        assertFalse(ConnectionHeaders.isRequestStripped(name),
                () -> name + " is a name the gateway REGENERATES, so it belongs to the forwarding set"
                        + " in ForwardPolicyStage — which is read both as the client-copy skip and as"
                        + " the untrusted-peer resolver mask. Duplicating it here would create two"
                        + " lists that can disagree; this assertion is what keeps the placement"
                        + " decision (variants here, canonical names there) from eroding");
    }

    @Test
    @DisplayName("Should reject a null request-header name rather than treating it as forwardable")
    void shouldRejectNullRequestName() {
        assertThrows(NullPointerException.class, () -> ConnectionHeaders.isRequestStripped(null));
    }
}
