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
package de.cuioss.sheriff.gateway.forward;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    @DisplayName("static and conditional headers")
    class StaticAndConditional {

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
        @DisplayName("conditional-request headers cross only when the route enables not_modified")
        void conditionalHeadersGatedByNotModified() {
            // Arrange
            Map<String, List<String>> headers = Map.of("If-None-Match", List.of("\"etag-1\""));

            // Act
            ForwardPolicyStage.Result dropped = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), false);
            ForwardPolicyStage.Result crossed = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), true);

            // Assert
            assertFalse(dropped.headers().containsKey("If-None-Match"),
                    "conditional headers are dropped when not_modified is disabled");
            assertEquals("\"etag-1\"", crossed.headers().get("If-None-Match"),
                    "conditional headers cross when not_modified is enabled");
        }
    }

    private static ForwardPolicyStage stage(String emitMode, List<String> tcpTrusted, Set<String> resolverTrusted) {
        ForwardedResolverConfig config = ForwardedResolverConfig.builder()
                .trustedProxies(resolverTrusted)
                .build();
        ForwardedHeaderResolver resolver = new ForwardedHeaderResolver(config, new SecurityEventCounter());
        return new ForwardPolicyStage(resolver, new TcpPeerGate(tcpTrusted), emitMode);
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

    private static PipelineRequest queryRequest(Map<String, List<String>> queryParameters) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .peerAddress(UNTRUSTED_PEER)
                .queryParameters(queryParameters)
                .build();
    }
}
