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
package de.cuioss.sheriff.gateway.forward;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.edge.ResponseStage;
import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ForwardPolicyStage — stage 5 zero-trust forward policy (three modes per dimension)")
class ForwardPolicyStageTest {

    private static final String EMIT_XFORWARDED = "x-forwarded";
    private static final String EMIT_BOTH = "both";

    /** CIDR range trusted both as the immediate TCP peer and as an intermediate proxy. */
    private static final String TRUSTED_CIDR = "127.0.0.0/8";
    /** An immediate TCP peer inside {@link #TRUSTED_CIDR}. */
    private static final String TRUSTED_PEER = "127.0.0.1";
    /** An immediate TCP peer outside every trusted range. */
    private static final String UNTRUSTED_PEER = "203.0.113.9";
    /** The real client the forwarding chain resolves to (never a trusted proxy). */
    private static final String CLIENT_IP = "6.6.6.6";
    private static final String XFF = "X-Forwarded-For";
    private static final String FORWARDED = "Forwarded";
    /** The one TE value RFC 9110 admits end-to-end, and the only one gRPC sends. */
    private static final String TRAILERS_TOKEN = "trailers";

    @Nested
    @DisplayName("drop-and-regenerate forwarding headers")
    class DropAndRegenerate {

        @Test
        @DisplayName("regenerates X-Forwarded-For from a trusted peer chain, stripping the proxy hop")
        void regeneratesClientIpStrippingProxy() {
            // Arrange — a two-hop chain: real client 6.6.6.6 behind trusted proxy 127.0.0.1
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(TRUSTED_PEER,
                    Map.of(XFF, List.of(CLIENT_IP + ", " + TRUSTED_PEER)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), false);

            // Assert — the regenerated value is the client only, never the verbatim inbound chain
            assertTrue(result.headers().containsValue(CLIENT_IP),
                    "regenerated X-Forwarded-For must carry the resolved client IP");
            assertFalse(result.headers().containsValue(CLIENT_IP + ", " + TRUSTED_PEER),
                    "inbound forwarding chain must never be propagated verbatim");
        }

        @Test
        @DisplayName("emit:both additionally regenerates the RFC 7239 Forwarded header")
        void emitBothAddsForwardedHeader() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_BOTH, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(TRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), false);

            // Assert — both X-Forwarded-* and Forwarded are emitted
            assertTrue(result.headers().containsValue(CLIENT_IP),
                    "emit:both must still regenerate the X-Forwarded-* set");
            assertTrue(result.headers().containsKey(FORWARDED),
                    "emit:both must add the RFC 7239 Forwarded header");
            assertTrue(result.headers().get(FORWARDED).contains(CLIENT_IP),
                    "the regenerated Forwarded header must reference the resolved client");
        }

        @Test
        @DisplayName("emit:x-forwarded omits the Forwarded header but keeps X-Forwarded-*")
        void emitXForwardedOmitsForwardedHeader() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(TRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), false);

            // Assert
            assertTrue(result.headers().containsValue(CLIENT_IP),
                    "emit:x-forwarded must regenerate the X-Forwarded-* set");
            assertFalse(result.headers().containsKey(FORWARDED),
                    "emit:x-forwarded must never add the RFC 7239 Forwarded header");
        }
    }

    @Nested
    @DisplayName("spoofed headers from untrusted peers are ignored")
    class UntrustedPeer {

        @Test
        @DisplayName("an untrusted immediate peer's spoofed X-Forwarded-For never influences the regenerated set")
        void untrustedPeerSpoofIgnored() {
            // Arrange — untrusted peer sends a spoofed forwarding header, even allow-listed
            ForwardPolicyStage stage = stage(EMIT_BOTH, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), false);

            // Assert — the spoofed value crosses neither as X-Forwarded-For nor as Forwarded
            assertFalse(result.headers().containsValue(CLIENT_IP),
                    "a spoofed forwarding header from an untrusted peer must be ignored");
            assertFalse(result.headers().containsKey(FORWARDED),
                    "no Forwarded header may be regenerated from ignored spoofed input");
        }

        @Test
        @DisplayName("a null immediate peer is never trusted, so inbound forwarding headers are ignored")
        void nullPeerNeverTrusted() {
            // Arrange — the edge supplied no peer address
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(null, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), false);

            // Assert
            assertFalse(result.headers().containsValue(CLIENT_IP),
                    "an absent peer address is never trusted");
        }
    }

    @Nested
    @DisplayName("positive-list mode (headers_allow / query_allow declared)")
    class PositiveListMode {

        @Test
        @DisplayName("forwards only allow-listed request headers")
        void forwardsOnlyAllowListedHeaders() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(
                    "X-Api-Version", List.of("v2"),
                    "X-Secret", List.of("leak")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("X-Api-Version")), false);

            // Assert
            assertEquals("v2", result.headers().get("X-Api-Version"), "allow-listed header must cross");
            assertFalse(result.headers().containsKey("X-Secret"), "non-allow-listed header must be dropped");
        }

        @Test
        @DisplayName("Authorization crosses only when explicitly allow-listed")
        void authorizationCrossesOnlyWhenAllowListed() {
            // Arrange
            Map<String, List<String>> headers = Map.of("Authorization", List.of("Bearer token-xyz"));

            // Act — dropped when not listed, crosses when listed
            ForwardPolicyStage.Result dropped = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), false);
            ForwardPolicyStage.Result crossed = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("Authorization")), false);

            // Assert
            assertFalse(dropped.headers().containsKey("Authorization"),
                    "inbound Authorization is dropped by default");
            assertEquals("Bearer token-xyz", crossed.headers().get("Authorization"),
                    "inbound Authorization crosses only when allow-listed");
        }

        @Test
        @DisplayName("an inbound forwarding header can never be smuggled through the header allowlist")
        void forwardingHeaderNeverAllowListed() {
            // Arrange — X-Forwarded-For is allow-listed AND the peer is untrusted
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), false);

            // Assert — the verbatim inbound value is never copied through the allowlist path
            assertFalse(result.headers().containsValue(CLIENT_IP),
                    "a forwarding header is excluded from the allowlist copy and never propagated verbatim");
        }

        @Test
        @DisplayName("forwards only allow-listed query parameters")
        void forwardsOnlyAllowListedQuery() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET)
                    .requestPath("/api/orders")
                    .peerAddress(UNTRUSTED_PEER)
                    .queryParameters(Map.of("page", List.of("2"), "secret", List.of("x")))
                    .build();

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of("page")).build(), false);

            // Assert
            assertEquals(List.of("2"), result.query().get("page"), "allow-listed query parameter must cross");
            assertFalse(result.query().containsKey("secret"), "non-allow-listed query parameter must be dropped");
        }

        @Test
        @DisplayName("a DECLARED EMPTY query_allow is a positive-list naming nothing, so nothing crosses")
        void declaredEmptyQueryAllowCrossesNothing() {
            // Arrange — the distinction this deliverable exists to preserve: declared-empty is a
            // positive-list that admits nothing, NOT the absent state (which is forward-all)
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(Map.of("page", List.of("2"), "secret", List.of("x")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of()).build(), false);

            // Assert
            assertTrue(result.query().isEmpty(),
                    "query_allow: [] is a declared positive-list naming nothing, so no query parameter"
                            + " crosses — it must NOT be read as the absent, forward-all state");
        }

        @Test
        @DisplayName("a DECLARED EMPTY headers_allow crosses no client header")
        void declaredEmptyHeadersAllowCrossesNothing() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("X-Api-Version", List.of("v2")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), false);

            // Assert
            assertFalse(result.headers().containsKey("x-api-version"),
                    "headers_allow: [] admits no client header");
        }
    }

    @Nested
    @DisplayName("negative-list mode (headers_deny / query_deny declared)")
    class NegativeListMode {

        @Test
        @DisplayName("crosses every client header except the denied one")
        void crossesEverythingButTheDeniedHeader() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(
                    "X-Api-Version", List.of("v2"),
                    "X-Secret", List.of("leak")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("X-Secret")), false);

            // Assert — the inbound names are lower-case-keyed by PipelineRequest, and the negative-list
            // copy carries them through under that key
            assertEquals("v2", result.headers().get("x-api-version"),
                    "an undenied client header crosses under a negative-list");
            assertFalse(result.headers().containsKey("x-secret"),
                    "the denied header must not cross");
        }

        @Test
        @DisplayName("denied-name matching is case-insensitive, as RFC 9110 field names require")
        void deniedHeaderMatchingIsCaseInsensitive() {
            // Arrange — the operator writes the name in one case, the client sends another
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("X-SECRET", List.of("leak")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("x-secret")), false);

            // Assert
            assertFalse(result.headers().containsKey("x-secret"),
                    "a case-differing denied name must still be withheld — otherwise the deny list could"
                            + " be bypassed by changing the case of a header name");
        }

        @Test
        @DisplayName("crosses every query parameter except the denied one")
        void crossesEveryQueryParameterButTheDeniedOne() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(Map.of("page", List.of("2"), "secret", List.of("x")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("secret")).build(), false);

            // Assert
            assertEquals(List.of("2"), result.query().get("page"), "an undenied parameter crosses");
            assertFalse(result.query().containsKey("secret"), "the denied parameter must not cross");
        }

        @Test
        @DisplayName("a forwarding header is never smuggled in through a negative-list")
        void forwardingHeaderNeverCrossesUnderNegativeList() {
            // Arrange — the deny list names something else entirely, so nothing withholds XFF except
            // the gateway-owned skip this assertion exists to pin
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("X-Unrelated")), false);

            // Assert
            assertFalse(result.headers().containsValue(CLIENT_IP),
                    "the FORWARDING_HEADERS skip is retained on the negative-list mode: a regenerated"
                            + " header is never copied from the client, whatever the mode");
        }
    }

    @Nested
    @DisplayName("forward-all mode (neither list declared)")
    class ForwardAllMode {

        @Test
        @DisplayName("crosses a client header that is named by no list at all")
        void crossesAnUnlistedHeader() {
            // Arrange — the absent-state flip: no forward block at all
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("X-Api-Version", List.of("v2")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            // Assert
            assertEquals("v2", result.headers().get("x-api-version"),
                    "with neither list declared every client header crosses — this is the baseline the"
                            + " absent state now means");
        }

        @Test
        @DisplayName("crosses a query parameter that is named by no list at all")
        void crossesAnUnlistedQueryParameter() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(Map.of("page", List.of("2")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            // Assert
            assertEquals(List.of("2"), result.query().get("page"),
                    "with neither query list declared every client parameter crosses");
        }

        @Test
        @DisplayName("a forwarding header is never smuggled in through forward-all")
        void forwardingHeaderNeverCrossesUnderForwardAll() {
            // Arrange — the most permissive mode is still bounded by the gateway-owned skip
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            // Assert
            assertFalse(result.headers().containsValue(CLIENT_IP),
                    "forward-all is not a way to smuggle a regenerated forwarding header through — the"
                            + " FORWARDING_HEADERS skip applies on every mode");
        }

        @Test
        @DisplayName("the two dimensions resolve their modes independently")
        void headerAndQueryModesResolveIndependently() {
            // Arrange — a positive-list on headers and forward-all on query, in one block
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET)
                    .requestPath("/api/orders")
                    .peerAddress(UNTRUSTED_PEER)
                    .headers(Map.of("X-Api-Version", List.of("v2"), "X-Secret", List.of("leak")))
                    .queryParameters(Map.of("page", List.of("2")))
                    .build();

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("X-Api-Version")), false);

            // Assert
            assertAll("the header dimension is a positive-list while the query dimension is forward-all",
                    () -> assertEquals("v2", result.headers().get("X-Api-Version"),
                            "the allow-listed header crosses under its declared name"),
                    () -> assertFalse(result.headers().containsKey("x-secret"),
                            "the header positive-list still excludes the unlisted header"),
                    () -> assertEquals(List.of("2"), result.query().get("page"),
                            "the query dimension declares no list, so it is forward-all"));
        }
    }

    @Nested
    @DisplayName("static set_headers — operator configuration beats client input")
    class StaticSetHeaders {

        @Test
        @DisplayName("appends static set_headers verbatim")
        void appendsStaticSetHeaders() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of());

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().setHeaders(Map.of("X-Gateway", "sheriff")).build(), false);

            // Assert
            assertEquals("sheriff", result.headers().get("X-Gateway"), "static set_headers must be appended verbatim");
        }

        @Test
        @DisplayName("set_headers: Content-Type REPLACES an inbound client Content-Type rather than riding alongside it")
        void setHeadersContentTypeReplacesTheInboundOne() {
            // Arrange — the regression the reorder exists to fix. The client's name arrives
            // lower-cased (PipelineRequest normalizes), the operator writes the canonical spelling.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Content-Type", List.of("text/plain")));

            // Act — forward-all, so the mode copy really does carry the client's value in first
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().setHeaders(Map.of("Content-Type", "application/json")).build(), false);

            // Assert — exactly ONE Content-Type reaches the upstream, carrying the operator's value.
            // Asserting the cardinality is the point: a case-sensitive outbound map would send the
            // client's `content-type: text/plain` alongside the operator's `Content-Type`, and a
            // get()-only assertion would pass while a duplicate header crossed.
            assertEquals(List.of("application/json"), valuesNamed(result.headers(), "Content-Type"),
                    "an operator's set_headers value must REPLACE the inbound one, however either side"
                            + " spelled the field name — RFC 9110 field names are case-insensitive, so"
                            + " two spellings of one field must never both cross");
        }

        @Test
        @DisplayName("the protocol set does not duplicate a client header the mode copy already carried")
        void protocolSetDoesNotDuplicateTheModeCopy() {
            // Arrange — under forward-all the mode copy inserts the lower-cased inbound `accept`,
            // then the protocol set re-admits it under the canonical `Accept`
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Accept", List.of("application/json")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            // Assert
            assertEquals(List.of("application/json"), valuesNamed(result.headers(), "Accept"),
                    "the two insertion paths spell the field differently, so only a case-insensitive"
                            + " outbound map collapses them into the single header the upstream must see");
        }

        @Test
        @DisplayName("an operator's set_headers survives a headers_deny entry naming the same field")
        void setHeadersSurvivesAMatchingDenyEntry() {
            // Arrange — headers_deny governs CLIENT input; set_headers is operator configuration
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Content-Type", List.of("text/plain")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, ForwardConfig.builder()
                    .headersDeny(List.of("Content-Type"))
                    .setHeaders(Map.of("Content-Type", "application/json"))
                    .build(), false);

            // Assert
            assertEquals(List.of("application/json"), valuesNamed(result.headers(), "Content-Type"),
                    "the deny entry withholds the client's value; the operator's static value is merged"
                            + " afterwards and is not client input");
        }
    }

    @Nested
    @DisplayName("the gateway-understood protocol set (crosses without an operator entry)")
    class ProtocolHeaderSet {

        @ParameterizedTest
        @ValueSource(strings = {"Accept", "Accept-Encoding", "Accept-Language", "Content-Type", "Range"})
        @DisplayName("an unconditional protocol header crosses a route that never names it, either way of the toggle")
        void unconditionalProtocolHeaderCrossesUnnamed(String name) {
            // Arrange — headers_allow: [] admits no CLIENT header at all, which is exactly what makes
            // this the discriminating case: anything that crosses, crosses because of the protocol set
            Map<String, List<String>> headers = Map.of(name, List.of("probe-value"));

            // Act — the same route under both not_modified states
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), true);

            // Assert — this is the matched control for the conditional tier: only the conditional half
            // rides not_modified, so a change that accidentally gated the WHOLE set behind the toggle
            // would pass the conditional test above and fail here
            assertAll("the unconditional tier is toggle-independent",
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOff.headers(), name),
                            () -> name + " is HTTP mechanics the gateway understands, so it crosses"
                                    + " without the operator enumerating it"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), name),
                            () -> name + " must not become conditional just because the route honours"
                                    + " conditional requests — the two tiers are gated independently"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since", "If-Range"})
        @DisplayName("a conditional validator crosses only when the route enables not_modified")
        void conditionalValidatorGatedByNotModified(String name) {
            // Arrange — all five RFC 9110 §13 validators ride the one toggle, because the response
            // direction gates ETag / Last-Modified on the same flag
            Map<String, List<String>> headers = Map.of(name, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result disabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), false);
            ForwardPolicyStage.Result enabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), true);

            // Assert
            assertAll("the conditional tier is the protocol set's gated half",
                    () -> assertEquals(List.of(), valuesNamed(disabled.headers(), name),
                            () -> name + " must not cross while not_modified is disabled — the gateway"
                                    + " would be asking a question whose answer it then discards"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(enabled.headers(), name),
                            () -> name + " crosses once the route honours conditional requests"));
        }

        @Test
        @DisplayName("a headers_deny entry naming a protocol header beats the set")
        void denyEntryBeatsTheProtocolSet() {
            // Arrange — the set is applied only to names the mode copy did not already refuse
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Accept", List.of("application/json")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("Accept")), false);

            // Assert
            assertEquals(List.of(), valuesNamed(result.headers(), "Accept"),
                    "an operator who wants a protocol header withheld says so with a deny entry, and"
                            + " that entry must not be undone by the set re-admitting it");
        }

        @Test
        @DisplayName("the deny entry beating the set is matched case-insensitively")
        void denyEntryBeatsTheProtocolSetCaseInsensitively() {
            // Arrange — the operator writes one case, the set literal carries another
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Content-Type", List.of("text/plain")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("content-type")), false);

            // Assert
            assertEquals(List.of(), valuesNamed(result.headers(), "Content-Type"),
                    "otherwise the deny entry could be bypassed by the case the set literal happens to"
                            + " use, which is not a policy the operator chose");
        }

        @Test
        @DisplayName("a conditional validator denied by name stays withheld even with not_modified enabled")
        void deniedConditionalValidatorStaysWithheld() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("If-None-Match", List.of("\"etag-1\"")));

            // Act — the toggle is ON, so only the deny entry can withhold it
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("If-None-Match")), true);

            // Assert
            assertEquals(List.of(), valuesNamed(result.headers(), "If-None-Match"),
                    "the deny entry outranks both tiers of the set, not just the unconditional one");
        }

        @Test
        @DisplayName("the protocol set never re-admits a gateway-owned name, because the two sets are disjoint")
        void protocolSetIsDisjointFromTheRequestStrip() {
            // Arrange — applyProtocolHeaders reads the RAW client request and runs AFTER the strip, so
            // it bypasses ConnectionHeaders.REQUEST_STRIP entirely. That is safe ONLY while the two
            // sets share no name; this assertion is what catches a future addition that breaks it.
            List<String> overlap = ForwardPolicyStage.PROTOCOL_HEADERS.stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(ConnectionHeaders.REQUEST_STRIP::contains)
                    .toList();

            // Assert
            assertEquals(List.of(), overlap,
                    "a name in both sets would be re-admitted by the protocol set after the strip"
                            + " withheld it — silently handing the upstream a header the gateway owns."
                            + " Adding such a name requires consulting the strip in applyProtocolHeaders,"
                            + " not just extending the literal");
        }

        @ParameterizedTest
        @ValueSource(strings = {"If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since", "If-Range"})
        @DisplayName("one flag gates both directions: no validator crosses either way while not_modified is off")
        void notModifiedGatesBothDirectionsTogether(String requestValidator) {
            // Arrange — the coupling the toggle exists to preserve, asserted across the two stages that
            // implement its halves. Keeping it in ONE test is the point: the request half (this stage's
            // conditional tier) and the response half (ResponseStage's ETag / Last-Modified strip) are
            // gated by the same flag, so dropping either half orphans the other. Two same-file tests
            // could not catch that; this one fails the moment the halves disagree.
            //
            // The route declares a positive-list, which is where the toggle actually governs: the
            // conditional tier of the protocol set is then the ONLY path admitting a validator. Under
            // forward-all the mode copy carries the client's headers wholesale and the gate never sees
            // them — pinned separately by forwardAllCarriesValidatorsPastTheToggle below.
            Map<String, List<String>> headers = Map.of(requestValidator, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result disabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), false);
            ForwardPolicyStage.Result enabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), true);

            // Assert
            assertAll("the request question and the response answer are gated as one",
                    () -> assertEquals(List.of(), valuesNamed(disabled.headers(), requestValidator),
                            () -> requestValidator + " must not be asked on a not_modified: false route"),
                    () -> assertFalse(ResponseStage.isForwardableResponseHeader("ETag", false),
                            "and the answer must not come back either — a route that never forwards a"
                                    + " validator has no use for the ETag that would answer it"),
                    () -> assertFalse(ResponseStage.isForwardableResponseHeader("Last-Modified", false),
                            "same for Last-Modified: the response half is stripped on a disabled route"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(enabled.headers(), requestValidator),
                            () -> requestValidator + " crosses once the route enables not_modified"),
                    () -> assertTrue(ResponseStage.isForwardableResponseHeader("ETag", true),
                            "and ETag comes back, so the gateway keeps the answer it asked for"),
                    () -> assertTrue(ResponseStage.isForwardableResponseHeader("Last-Modified", true),
                            "and Last-Modified with it"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since", "If-Range"})
        @DisplayName("under forward-all a validator crosses even with not_modified off — the mode copy, not the set")
        void forwardAllCarriesValidatorsPastTheToggle(String requestValidator) {
            // Arrange — characterization of where the toggle's reach ENDS, pinned so the boundary is
            // visible rather than discovered later. not_modified gates the protocol set's conditional
            // tier; it is not a withholding rule applied to the mode copy. A forward-all route has
            // declared that client headers cross, and this validator crosses on that posture alone.
            //
            // Note the asymmetry this leaves: the response half strips ETag / Last-Modified on the very
            // same route, so a forward-all + not_modified:false route forwards the precondition and then
            // discards the validator answering it. Resolving that is a change to forward-all semantics,
            // which is outside this deliverable's declared edit (widening the tier's membership) — it is
            // recorded here as behaviour, not endorsed as intent.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER,
                    Map.of(requestValidator, List.of("probe-value")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            // Assert
            assertEquals(List.of("probe-value"), valuesNamed(result.headers(), requestValidator),
                    () -> requestValidator + " crosses under forward-all because the mode copy carried"
                            + " it, not because the protocol set admitted it — the not_modified gate"
                            + " governs the set's admission path only");
        }

        @Test
        @DisplayName("the mediated bearer still runs last, beating a re-admitted inbound Authorization")
        void mediatedBearerStillRunsLast() {
            // Arrange — the pinned order's tail: nothing the reorder introduced may displace it
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(
                    "Authorization", List.of("Bearer inbound-xyz"),
                    "Content-Type", List.of("text/plain")));
            request.mediatedBearer("mediated-abc");

            // Act — a positive-list re-admits the inbound credential AND set_headers touches another field
            ForwardPolicyStage.Result result = stage.process(request, ForwardConfig.builder()
                    .headersAllow(List.of("Authorization"))
                    .setHeaders(Map.of("Content-Type", "application/json"))
                    .build(), false);

            // Assert
            assertAll("applyMediatedBearer is the last merge in the pinned order",
                    () -> assertEquals(List.of("Bearer mediated-abc"),
                            valuesNamed(result.headers(), "Authorization"),
                            "the upstream sees only the mediated bearer, never the inbound one the"
                                    + " positive-list re-admitted"),
                    () -> assertEquals(List.of("application/json"),
                            valuesNamed(result.headers(), "Content-Type"),
                            "and set_headers still beats the client's value alongside it"));
        }
    }

    private static ForwardPolicyStage stage(String emitMode, List<String> tcpTrusted, Set<String> resolverTrusted) {
        ForwardedResolverConfig config = ForwardedResolverConfig.builder()
                .trustedProxies(resolverTrusted)
                .build();
        ForwardedHeaderResolver resolver = new ForwardedHeaderResolver(config, new SecurityEventCounter());
        return new ForwardPolicyStage(resolver, new TcpPeerGate(tcpTrusted), emitMode);
    }

    @Nested
    @DisplayName("the gateway-owned never-forward set (no mode re-admits it)")
    class RequestStrip {

        @Test
        @DisplayName("Cookie never crosses under forward-all")
        void cookieNeverCrossesUnderForwardAll() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Cookie", List.of("__Host-sid=sealed")));

            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            assertFalse(result.headers().containsKey("cookie"),
                    "the BFF's sealed session cookie is the gateway's, never the backend's — the"
                            + " permissive baseline must not leak it");
        }

        @Test
        @DisplayName("Cookie never crosses under a negative-list that does not name it")
        void cookieNeverCrossesUnderNegativeList() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Cookie", List.of("__Host-sid=sealed")));

            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("X-Unrelated")), false);

            assertFalse(result.headers().containsKey("cookie"),
                    "no negative-list is needed to withhold Cookie — the gateway owns it");
        }

        @Test
        @DisplayName("Cookie never crosses even when a positive-list explicitly names it")
        void cookieNeverCrossesEvenWhenAllowListed() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Cookie", List.of("__Host-sid=sealed")));

            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("Cookie")), false);

            assertFalse(result.headers().containsKey("Cookie"),
                    "Cookie is one of the 15 absolute members: an operator naming it does not"
                            + " re-admit it, because doing so is a security regression rather than a"
                            + " policy choice");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Host", "Content-Length", "Connection", "Referer", "Origin", "Proxy-Authorization"})
        @DisplayName("a gateway-owned name is withheld under forward-all whatever it is")
        void gatewayOwnedNamesAreWithheldUnderForwardAll(String name) {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(name, List.of("value")));

            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), false);

            assertTrue(result.headers().isEmpty(),
                    () -> name + " is gateway-owned and must not reach the upstream under forward-all");
        }

        /** A provenance value belonging to no fixture peer, so a hit can only be the client's claim. */
        private static final String SPOOFED_CLIENT = "9.9.9.9";

        @ParameterizedTest
        @ValueSource(strings = {
                "X_Forwarded_For", "X_Forwarded_Host", "X_Forwarded_Proto", "X_Forwarded_Port",
                "X_Forwarded_Prefix", "X-Real-IP", "X-Client-IP", "True-Client-IP", "CF-Connecting-IP"})
        @DisplayName("a spoofable provenance variant crosses under neither forward-all nor a negative-list")
        void provenanceVariantsNeverCrossUnderPermissiveModes(String name) {
            // Arrange — the two permissive modes are the ones that lost the old allow-list's
            // incidental protection, so both are asserted for every name. The variant rides
            // alongside a header the gateway does NOT own: that companion is the matched positive
            // control, proving the mode copy actually ran and the variant's absence is a
            // withholding DECISION rather than an empty result.
            Map<String, List<String>> headers = Map.of(
                    name, List.of(SPOOFED_CLIENT),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result forwardAllResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers), forwardAll(), false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    deny(List.of("X-Unrelated")), false);

            // Assert — containsValue rather than a name lookup, so a variant that crossed under any
            // spelling at all still fails. The gateway never emits this value itself, so a hit can
            // only be the client's own claim reaching the upstream.
            assertAll("a provenance claim is the gateway's alone to make, under every permissive mode",
                    () -> assertFalse(forwardAllResult.headers().containsValue(SPOOFED_CLIENT),
                            () -> name + " must not reach the upstream under forward-all — a backend"
                                    + " folding '-' and '_' into one CGI variable would read it as"
                                    + " the real forwarding header"),
                    () -> assertEquals("v2", forwardAllResult.headers().get("x-api-version"),
                            "control: forward-all really did copy the client's unowned header, so the"
                                    + " absence above is a decision and not an empty mode copy"),
                    () -> assertFalse(negativeListResult.headers().containsValue(SPOOFED_CLIENT),
                            () -> name + " must not reach the upstream under a negative-list that"
                                    + " does not name it — no deny entry should be needed"),
                    () -> assertEquals("v2", negativeListResult.headers().get("x-api-version"),
                            "control: the negative-list copy carried the undenied header through"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "X-Forwarded_For", "X_Forwarded-For", "X-Forwarded_Host", "X_Forwarded-Host",
                "X-Forwarded_Proto", "X_Forwarded-Proto", "X-Forwarded_Port", "X_Forwarded-Port",
                "X-Forwarded_Prefix", "X_Forwarded-Prefix",
                "X_Real_IP", "X-Real_IP", "X_Client_IP", "True_Client_IP", "CF_Connecting_IP"})
        @DisplayName("a MIXED '-'/'_' spelling of a provenance name crosses under no mode either")
        void mixedSeparatorProvenanceSpellingsNeverCross(String name) {
            // Arrange — a name with two separators has FOUR spellings and a CGI-folding backend
            // (CGI/FastCGI/WSGI/Rack) resolves all four to one variable, so closing only the fully
            // hyphenated and fully underscored ends leaves the two mixed spellings landing on the
            // very variable the gateway's regenerated header feeds. Enumerating spellings one fix
            // at a time is the Traefik forwarded-header fix-chain; both sets therefore fold the
            // separator before the membership test, and this asserts the class is closed rather
            // than sampled. Three modes are exercised because each reaches the class by a different
            // route: forward-all and the negative-list through the mode copy, the positive-list
            // through its own skip.
            Map<String, List<String>> headers = Map.of(
                    name, List.of(SPOOFED_CLIENT),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result forwardAllResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers), forwardAll(), false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    deny(List.of("X-Unrelated")), false);
            ForwardPolicyStage.Result positiveListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    allow(List.of(name, "X-Api-Version")), false);

            // Assert — containsValue rather than a name lookup, so the spoof failing to cross under
            // ANY spelling still fails the test. The gateway never emits this value itself.
            assertAll("every '-'/'_' spelling of a provenance name is closed, under every mode",
                    () -> assertFalse(forwardAllResult.headers().containsValue(SPOOFED_CLIENT),
                            () -> name + " must not reach the upstream under forward-all"),
                    () -> assertEquals("v2", forwardAllResult.headers().get("x-api-version"),
                            "control: forward-all really did copy the client's unowned header"),
                    () -> assertFalse(negativeListResult.headers().containsValue(SPOOFED_CLIENT),
                            () -> name + " must not reach the upstream under a negative-list"),
                    () -> assertEquals("v2", negativeListResult.headers().get("x-api-version"),
                            "control: the negative-list copy carried the undenied header through"),
                    () -> assertFalse(positiveListResult.headers().containsValue(SPOOFED_CLIENT),
                            () -> name + " must not cross even when a positive-list names it — a"
                                    + " gateway-owned provenance claim is not an operator's to"
                                    + " re-admit from client input"),
                    () -> assertEquals("v2", positiveListResult.headers().get("X-Api-Version"),
                            "control: the positive-list really did admit its other entry"));
        }

        @Test
        @DisplayName("an operator's set_headers still places a provenance variant deliberately")
        void setHeadersStillPlacesAProvenanceVariant() {
            // Arrange — the strip governs CLIENT input. An operator fronting a backend that reads
            // X-Real-IP must still be able to set it, and this is the bound on the widening above:
            // it withholds a claim the client made, never one the operator configured.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("X-Real-IP", List.of(SPOOFED_CLIENT)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().setHeaders(Map.of("X-Real-IP", "10.1.2.3")).build(), false);

            // Assert
            assertEquals(List.of("10.1.2.3"), valuesNamed(result.headers(), "X-Real-IP"),
                    "the operator's static value crosses and the client's spoof does not — exactly"
                            + " one X-Real-IP reaches the upstream, and it is the configured one");
        }

        @Test
        @DisplayName("Authorization is the single re-admittable member, and only under a positive-list")
        void authorizationIsTheSingleReadmittableMember() {
            Map<String, List<String>> headers = Map.of("Authorization", List.of("Bearer inbound-xyz"));

            ForwardPolicyStage.Result allowListed = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("Authorization")), false);
            ForwardPolicyStage.Result forwardAllResult = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), deny(List.of("X-Unrelated")), false);

            assertAll("re-admission is a positive-list-only carve-out",
                    () -> assertEquals("Bearer inbound-xyz", allowListed.headers().get("Authorization"),
                            "a positive-list naming Authorization re-admits it"),
                    () -> assertFalse(forwardAllResult.headers().containsKey("authorization"),
                            "forward-all declares no positive-list, so it never re-admits a credential"),
                    () -> assertFalse(negativeListResult.headers().containsKey("authorization"),
                            "a negative-list declares no positive-list either"));
        }

        @Test
        @DisplayName("the mediated bearer still overwrites a re-admitted inbound Authorization")
        void mediatedBearerBeatsReadmittedAuthorization() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER,
                    Map.of("Authorization", List.of("Bearer inbound-xyz")));
            request.mediatedBearer("mediated-abc");

            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("Authorization")), false);

            assertEquals("Bearer mediated-abc", result.headers().get("Authorization"),
                    "applyMediatedBearer runs last, so the upstream sees only the mediated bearer even"
                            + " when the route re-admitted the inbound one");
        }

        @Test
        @DisplayName("TE crosses as the trailers token and is withheld for any other value")
        void teCrossesOnlyAsTheTrailersToken() {
            ForwardPolicyStage trailersStage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            ForwardPolicyStage gzipStage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result trailers = trailersStage.process(
                    request(UNTRUSTED_PEER, Map.of("TE", List.of("trailers"))), forwardAll(), false);
            ForwardPolicyStage.Result gzip = gzipStage.process(
                    request(UNTRUSTED_PEER, Map.of("TE", List.of("gzip"))), forwardAll(), false);

            assertAll("the TE carve-out is value-dependent, which is why it is not in the set literal",
                    () -> assertEquals("trailers", trailers.headers().get("te"),
                            "RFC 9110 admits TE: trailers end-to-end"),
                    () -> assertFalse(gzip.headers().containsKey("te"),
                            "any other TE value negotiates a transfer coding for the hop the gateway"
                                    + " terminates and is withheld"));
        }

        @Test
        @DisplayName("the TE carve-out admits a copied header; it does not copy one a positive-list omitted")
        void teCarveOutDoesNotSubstituteForThePositiveListEntry() {
            // Arrange — the asymmetry a reader is most likely to get wrong, and the reason grpc.yaml
            // keeps its `te` entry rather than deleting it as redundant. The carve-out decides
            // whether a copied TE SURVIVES the strip; it never puts one into the map. Under
            // forward-all the mode copy supplies it, so the entry would indeed be redundant — under
            // a positive-list nothing copies an unlisted name, so the entry is load-bearing and
            // removing it withholds TE: trailers from a gRPC upstream that requires it.
            Map<String, List<String>> headers = Map.of("TE", List.of(TRAILERS_TOKEN));

            // Act
            ForwardPolicyStage.Result named = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("te")), false);
            ForwardPolicyStage.Result omitted = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("X-Unrelated")), false);
            ForwardPolicyStage.Result permissive = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), false);

            // Assert
            assertAll("the carve-out is a survival rule, not an admission path",
                    () -> assertEquals(List.of(TRAILERS_TOKEN), valuesNamed(named.headers(), "TE"),
                            "a positive-list naming te copies it in, and the trailers value survives"),
                    () -> assertEquals(List.of(), valuesNamed(omitted.headers(), "TE"),
                            "a positive-list that omits te never copies it, so the carve-out has"
                                    + " nothing to admit — this is why the grpc routes keep the entry"),
                    () -> assertEquals(List.of(TRAILERS_TOKEN), valuesNamed(permissive.headers(), "TE"),
                            "under forward-all the mode copy supplies it, so there the entry WOULD be"
                                    + " redundant — the two modes differ and the difference matters"));
        }

        @Test
        @DisplayName("an operator's set_headers survives the strip — it is not client input")
        void setHeadersSurvivesTheStrip() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("Host", List.of("client.example")));

            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().setHeaders(Map.of("Host", "upstream.internal")).build(), false);

            assertEquals("upstream.internal", result.headers().get("Host"),
                    "the strip governs CLIENT input; an operator's static set_headers is gateway"
                            + " configuration and is merged after it");
        }
    }

    /** A header positive-list. Leaves every other list absent, so the query dimension is forward-all. */
    private static ForwardConfig allow(List<String> headersAllow) {
        return ForwardConfig.builder().headersAllow(headersAllow).build();
    }

    /** A header negative-list. Leaves every other list absent. */
    private static ForwardConfig deny(List<String> headersDeny) {
        return ForwardConfig.builder().headersDeny(headersDeny).build();
    }

    /** The all-absent block: forward-all on both dimensions — what a route declaring no forward means. */
    private static ForwardConfig forwardAll() {
        return ForwardConfig.builder().build();
    }

    private static PipelineRequest request(@Nullable String peer, Map<String, List<String>> headers) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .peerAddress(peer)
                .headers(headers)
                .build();
    }

    /**
     * Every outbound value whose field name equals {@code name} ignoring case.
     * <p>
     * Assertions use this rather than {@code headers().get(name)} because the interesting failures are
     * about <em>cardinality</em>: RFC 9110 field names are case-insensitive, so an outbound map that
     * distinguishes {@code content-type} from {@code Content-Type} sends the upstream two headers where
     * the precedence rule promises one. A {@code get()}-based assertion cannot see that — it finds the
     * spelling it asked for and reports success while the duplicate crosses.
     */
    private static List<String> valuesNamed(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static PipelineRequest queryRequest(Map<String, List<String>> queryParameters) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .peerAddress(UNTRUSTED_PEER)
                .queryParameters(queryParameters)
                .build();
    }
}
