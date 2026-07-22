/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

@DisplayName("ForwardPolicyStage — stage 5 zero-trust forward policy")
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
    @DisplayName("deny-by-default allowlists")
    class Allowlists {

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

    private static ForwardConfig allow(List<String> headersAllow) {
        return ForwardConfig.builder().headersAllow(headersAllow).build();
    }

    private static PipelineRequest request(@Nullable String peer, Map<String, List<String>> headers) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .peerAddress(peer)
                .headers(headers)
                .build();
    }
}
