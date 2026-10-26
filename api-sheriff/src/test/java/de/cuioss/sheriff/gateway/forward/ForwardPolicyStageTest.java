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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.QueryParameter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    /**
     * A second, untrusted hop appended by a proxy as a <em>repeated</em> {@code X-Forwarded-For}
     * header rather than by extending the first instance's comma-separated value.
     */
    private static final String APPENDED_HOP = "198.51.100.7";
    private static final String XFF = "X-Forwarded-For";
    private static final String FORWARDED = "Forwarded";
    /** The one TE value RFC 9110 admits end-to-end, and the only one gRPC sends. */
    private static final String TRAILERS_TOKEN = "trailers";
    /** A custom session cookie name outside the reserved prefix, so the configured name is exercised. */
    private static final String SESSION_COOKIE = "__Host-app-session";
    /** The gateway's own cookies: the reserved prefix plus the configured {@link #SESSION_COOKIE}. */
    private static final GatewayCookies GATEWAY_COOKIES = GatewayCookies.of(GatewayConfig.builder()
            .version(1)
            .oidc(OidcConfig.builder()
                    .redirectUri("https://gw.example.com/auth/callback")
                    .session(OidcConfig.Session.builder()
                            .mode(OidcConfig.Session.MODE_SERVER)
                            .cookieName(SESSION_COOKIE)
                            .build())
                    .build())
            .build());

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), CookiesConfig.NONE, false);

            // Assert — the regenerated value is the client only, never the verbatim inbound chain
            assertTrue(result.headers().containsValue(CLIENT_IP),
                    "regenerated X-Forwarded-For must carry the resolved client IP");
            assertFalse(result.headers().containsValue(CLIENT_IP + ", " + TRUSTED_PEER),
                    "inbound forwarding chain must never be propagated verbatim");
        }

        @Test
        @DisplayName("a repeated X-Forwarded-For header contributes its appended hop to the chain")
        void repeatedForwardingHeaderInstancesAreAllSeen() {
            // Arrange — the chain arrives as TWO X-Forwarded-For headers, not one comma-joined value.
            // RFC 9110 makes the two spellings equivalent, so the appended hop must count: resolving
            // only the first instance would silently walk a truncated chain and attribute the request
            // to the wrong address.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(TRUSTED_PEER,
                    Map.of(XFF, List.of(CLIENT_IP, APPENDED_HOP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), CookiesConfig.NONE, false);

            // Assert — nearest hop wins across the joined chain, so the SECOND instance is the client.
            // Asserting the first instance is absent is what makes this fail against a single-valued
            // accessor, which would resolve CLIENT_IP and never see APPENDED_HOP at all.
            assertEquals(List.of(APPENDED_HOP), valuesNamed(result.headers(), XFF),
                    "the appended repeated instance must win the nearest-hop selection");
        }

        @Test
        @DisplayName("emit:both additionally regenerates the RFC 7239 Forwarded header")
        void emitBothAddsForwardedHeader() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_BOTH, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(TRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("X-Api-Version")), CookiesConfig.NONE, false);

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
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result crossed = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("Authorization")), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of(XFF)), CookiesConfig.NONE, false);

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
                    .queryParameters(List.of(pair("page", "2"), pair("secret", "x")))
                    .build();

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of("page")).build(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("2"), valuesOf(result, "page"), "allow-listed query parameter must cross");
            assertFalse(namesOf(result).contains("secret"), "non-allow-listed query parameter must be dropped");
        }

        @Test
        @DisplayName("a DECLARED EMPTY query_allow is a positive-list naming nothing, so nothing crosses")
        void declaredEmptyQueryAllowCrossesNothing() {
            // Arrange — the distinction this deliverable exists to preserve: declared-empty is a
            // positive-list that admits nothing, NOT the absent state (which is forward-all)
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("page", "2"), pair("secret", "x"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of()).build(), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of()), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("X-Secret")), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("x-secret")), CookiesConfig.NONE, false);

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
            PipelineRequest request = queryRequest(pair("page", "2"), pair("secret", "x"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("secret")).build(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("2"), valuesOf(result, "page"), "an undenied parameter crosses");
            assertFalse(namesOf(result).contains("secret"), "the denied parameter must not cross");
        }

        @Test
        @DisplayName("a forwarding header is never smuggled in through a negative-list")
        void forwardingHeaderNeverCrossesUnderNegativeList() {
            // Arrange — the deny list names something else entirely, so nothing withholds XFF except
            // the gateway-owned skip this assertion exists to pin
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("X-Unrelated")), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

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
            PipelineRequest request = queryRequest(pair("page", "2"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("2"), valuesOf(result, "page"),
                    "with neither query list declared every client parameter crosses");
        }

        @Test
        @DisplayName("a forwarding header is never smuggled in through forward-all")
        void forwardingHeaderNeverCrossesUnderForwardAll() {
            // Arrange — the most permissive mode is still bounded by the gateway-owned skip
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR), Set.of(TRUSTED_CIDR));
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(XFF, List.of(CLIENT_IP)));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

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
                    .queryParameters(List.of(pair("page", "2")))
                    .build();

            // Act
            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("X-Api-Version")), CookiesConfig.NONE, false);

            // Assert
            assertAll("the header dimension is a positive-list while the query dimension is forward-all",
                    () -> assertEquals("v2", result.headers().get("X-Api-Version"),
                            "the allow-listed header crosses under its declared name"),
                    () -> assertFalse(result.headers().containsKey("x-secret"),
                            "the header positive-list still excludes the unlisted header"),
                    () -> assertEquals(List.of("2"), valuesOf(result, "page"),
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
                    ForwardConfig.builder().setHeaders(Map.of("X-Gateway", "sheriff")).build(), CookiesConfig.NONE, false);

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
                    ForwardConfig.builder().setHeaders(Map.of("Content-Type", "application/json")).build(), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

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
                    .build(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("application/json"), valuesNamed(result.headers(), "Content-Type"),
                    "the deny entry withholds the client's value; the operator's static value is merged"
                            + " afterwards and is not client input");
        }

        @Test
        @DisplayName("a static set_headers Authorization yields to a recorded mediated bearer and never rides alongside it")
        void staticAuthorizationYieldsToTheMediatedBearer() {
            // Arrange — stage 4 records a mediated bearer on a session path with token_relay: true and
            // on the bearer branch of a session_fallback route, and none on a session path with
            // token_relay: false. The operator spells the field lower-case while the mediated bearer is
            // written as Authorization, so only a case-insensitive merge keeps it to ONE header.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            ForwardConfig forward = ForwardConfig.builder()
                    .setHeaders(Map.of("authorization", "Bearer static-operator-value"))
                    .build();
            PipelineRequest mediated = request(UNTRUSTED_PEER, Map.of());
            mediated.mediatedBearer("mediated-abc");
            PipelineRequest unmediated = request(UNTRUSTED_PEER, Map.of());

            // Act
            ForwardPolicyStage.Result withBearer = stage.process(mediated, forward, CookiesConfig.NONE, false);
            ForwardPolicyStage.Result withoutBearer = stage.process(unmediated, forward, CookiesConfig.NONE, false);

            // Assert
            assertAll("gateway-owned credential beats operator configuration; operator configuration is not filtered",
                    () -> assertEquals(List.of("Bearer mediated-abc"),
                            valuesNamed(withBearer.headers(), "Authorization"),
                            "the mediated bearer replaces the static value — the upstream never receives two"
                                    + " Authorization headers to choose between"),
                    () -> assertEquals(List.of("Bearer static-operator-value"),
                            valuesNamed(withoutBearer.headers(), "Authorization"),
                            "with no mediated bearer recorded the operator's static Authorization crosses,"
                                    + " as documented for token_relay: false"));
        }
    }

    @Nested
    @DisplayName("the gateway-understood protocol set (crosses without an operator entry)")
    class ProtocolHeaderSet {

        /**
         * The conditional validators every not_modified case runs over, streamed from the production
         * list itself — so a validator added to {@link ForwardPolicyStage#CONDITIONAL_HEADERS} is
         * exercised by every mode test without anyone remembering to extend a literal here.
         */
        static Stream<String> conditionalHeaders() {
            return ForwardPolicyStage.CONDITIONAL_HEADERS.stream();
        }

        /**
         * The three RFC 9110 §13 validators the not_modified gate deliberately leaves alone. They sit in
         * the unconditional tier because withholding them would change what the request means rather
         * than discard an answer, so every mode must carry them whatever the toggle says.
         */
        static Stream<String> preconditionValidators() {
            return Stream.of("If-Match", "If-Unmodified-Since", "If-Range");
        }

        @Test
        @DisplayName("the conditional tier is exactly the two RFC 9110 §13 validators a 304 answers")
        void conditionalHeadersAreTheTwo304Validators() {
            List<String> expected = List.of("If-None-Match", "If-Modified-Since");

            List<String> actual = ForwardPolicyStage.CONDITIONAL_HEADERS;

            assertEquals(expected, actual,
                    "the parameterized not_modified tests derive their cases from CONDITIONAL_HEADERS,"
                            + " so the two-name contract is pinned here on its own: a list that lost a"
                            + " validator would otherwise shrink those tests along with it and stay green."
                            + " The other three RFC 9110 §13 validators are excluded on purpose: withholding"
                            + " If-Match or If-Unmodified-Since turns a conditional write into a lost update,"
                            + " and withholding If-Range leaves Range to fetch a 206 slice of a newer"
                            + " representation");
        }

        @ParameterizedTest
        @MethodSource("preconditionValidators")
        @DisplayName("a precondition validator crosses with not_modified off under forward-all, a negative-list and an empty positive-list")
        void preconditionValidatorCrossesWhenToggleOff(String validator) {
            // Arrange — the toggle is OFF throughout, so a validator the gate still covered would be
            // absent from all three results. The empty positive-list admits no client header, so a hit
            // there can only be the unconditional protocol tier.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            Map<String, List<String>> headers = Map.of(validator, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result underForwardAll = stage.process(
                    request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underNegativeList = stage.process(
                    request(UNTRUSTED_PEER, headers), deny(List.of("X-Unrelated")), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underEmptyPositiveList = stage.process(
                    request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);

            // Assert
            assertAll("the not_modified gate leaves the precondition validators alone",
                    () -> assertEquals(List.of("probe-value"), valuesNamed(underForwardAll.headers(), validator),
                            () -> validator + " must cross a forward-all route with not_modified off"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(underNegativeList.headers(), validator),
                            () -> validator + " must cross a negative-list route that does not name it"),
                    () -> assertEquals(List.of("probe-value"),
                            valuesNamed(underEmptyPositiveList.headers(), validator),
                            () -> validator + " must cross an empty positive-list as a protocol header"));
        }

        @Test
        @DisplayName("a conditional PUT keeps its If-Match with not_modified off, so the origin can still answer 412")
        void conditionalWriteKeepsItsPreconditionWhenToggleOff() {
            // Arrange — the lost-update scenario: a client that read version "v7" writes back only if
            // the resource is still "v7". Stripping If-Match would make the write unconditional and
            // silently overwrite a concurrent change.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.PUT)
                    .requestPath("/api/orders/42")
                    .peerAddress(UNTRUSTED_PEER)
                    .headers(Map.of("If-Match", List.of("\"v7\""), "Content-Type", List.of("application/json")))
                    .build();

            // Act
            ForwardPolicyStage.Result underForwardAll = stage.process(request, forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underEmptyPositiveList = stage.process(request, allow(List.of()),
                    CookiesConfig.NONE, false);

            // Assert
            assertAll("the write stays conditional on a not_modified: false route",
                    () -> assertEquals(List.of("\"v7\""), valuesNamed(underForwardAll.headers(), "If-Match"),
                            "forward-all must carry the write precondition"),
                    () -> assertEquals(List.of("\"v7\""), valuesNamed(underEmptyPositiveList.headers(), "If-Match"),
                            "an empty positive-list must carry it as a protocol header"));
        }

        @Test
        @DisplayName("Range and If-Range cross together with not_modified off, so a changed representation yields a full 200")
        void rangeAndIfRangeCrossTogetherWhenToggleOff() {
            // Arrange — a resumed download: the client asks for the rest of the bytes only if the
            // representation is still "v7". Range without its If-Range would let the origin answer
            // with a 206 slice of a newer version spliced onto the old prefix.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            Map<String, List<String>> headers = Map.of(
                    "Range", List.of("bytes=1000-"),
                    "If-Range", List.of("\"v7\""));

            // Act
            ForwardPolicyStage.Result underForwardAll = stage.process(
                    request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underEmptyPositiveList = stage.process(
                    request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);

            // Assert
            assertAll("Range never crosses without the If-Range that qualifies it",
                    () -> assertEquals(List.of("bytes=1000-"), valuesNamed(underForwardAll.headers(), "Range"),
                            "forward-all carries Range"),
                    () -> assertEquals(List.of("\"v7\""), valuesNamed(underForwardAll.headers(), "If-Range"),
                            "and its If-Range alongside it"),
                    () -> assertEquals(List.of("bytes=1000-"), valuesNamed(underEmptyPositiveList.headers(), "Range"),
                            "an empty positive-list carries Range as a protocol header"),
                    () -> assertEquals(List.of("\"v7\""), valuesNamed(underEmptyPositiveList.headers(), "If-Range"),
                            "and If-Range with it, from the same unconditional tier"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Accept", "Accept-Encoding", "Accept-Language", "Content-Type", "Range"})
        @DisplayName("an unconditional protocol header crosses a route that never names it, either way of the toggle")
        void unconditionalProtocolHeaderCrossesUnnamed(String name) {
            // Arrange — headers_allow: [] admits no CLIENT header at all, which is exactly what makes
            // this the discriminating case: anything that crosses, crosses because of the protocol set
            Map<String, List<String>> headers = Map.of(name, List.of("probe-value"));

            // Act — the same route under both not_modified states
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, true);

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
        @MethodSource("conditionalHeaders")
        @DisplayName("a conditional validator crosses only when the route enables not_modified")
        void conditionalValidatorGatedByNotModified(String name) {
            // Arrange — both 304-producing validators ride the one toggle, because the response
            // direction gates ETag / Last-Modified on the same flag
            Map<String, List<String>> headers = Map.of(name, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result disabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result enabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, true);

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
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("Accept")), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("content-type")), CookiesConfig.NONE, false);

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
            ForwardPolicyStage.Result result = stage.process(request, deny(List.of("If-None-Match")), CookiesConfig.NONE, true);

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
        @MethodSource("conditionalHeaders")
        @DisplayName("one flag gates both directions: a 304 validator crosses neither way while not_modified is off")
        void notModifiedGatesBothDirectionsTogether(String requestValidator) {
            // Arrange — the coupling the toggle exists to preserve, asserted across the two stages that
            // implement its halves. Keeping it in ONE test is the point: the request half (this stage's
            // conditional tier) and the response half (ResponseStage's ETag / Last-Modified strip) are
            // gated by the same flag, so dropping either half orphans the other. Two same-file tests
            // could not catch that; this one fails the moment the halves disagree.
            //
            // The route declares an empty positive-list, so the conditional tier of the protocol set is
            // the ONLY admission path a validator has here. The mode-copy paths — forward-all, a
            // negative-list that does not name the validator, and a positive-list that does — are
            // withheld by the same toggle and pinned separately by forwardAllWithholdsValidatorsWhenToggleOff,
            // negativeListWithholdsUnnamedValidatorWhenToggleOff and
            // positiveListNamingValidatorWithholdsItWhenToggleOff below.
            Map<String, List<String>> headers = Map.of(requestValidator, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result disabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result enabled = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of()), CookiesConfig.NONE, true);

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
        @MethodSource("conditionalHeaders")
        @DisplayName("under forward-all a validator is withheld while not_modified is off, and crosses once it is on")
        void forwardAllWithholdsValidatorsWhenToggleOff(String requestValidator) {
            // Arrange — with not_modified off the response half strips the ETag / Last-Modified that
            // would answer a 304 validator on this route, so the toggle withholds the validator from
            // the client copy under every mode, forward-all included: carrying it would ask a question
            // whose answer is then discarded.
            // The unowned companion header is the matched control proving the mode copy really ran.
            Map<String, List<String>> headers = Map.of(
                    requestValidator, List.of("probe-value"),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, true);

            // Assert
            assertAll("forward-all is bounded by the not_modified gate",
                    () -> assertEquals(List.of(), valuesNamed(toggleOff.headers(), requestValidator),
                            () -> requestValidator + " must not cross a forward-all route with not_modified"
                                    + " off — the response half would discard the answer"),
                    () -> assertEquals(List.of("v2"), valuesNamed(toggleOff.headers(), "X-Api-Version"),
                            "control: a non-validator header still crosses forward-all with the toggle off"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), requestValidator),
                            () -> requestValidator + " crosses forward-all once the route enables"
                                    + " not_modified"));
        }

        @ParameterizedTest
        @MethodSource("conditionalHeaders")
        @DisplayName("under a negative-list that does not name it a validator is withheld while not_modified is off")
        void negativeListWithholdsUnnamedValidatorWhenToggleOff(String requestValidator) {
            // Arrange — the deny list names something else, so only the toggle can withhold the validator
            Map<String, List<String>> headers = Map.of(
                    requestValidator, List.of("probe-value"),
                    "X-Api-Version", List.of("v2"));
            ForwardConfig negativeList = deny(List.of("X-Unrelated"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), negativeList, CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), negativeList, CookiesConfig.NONE, true);

            // Assert
            assertAll("a negative-list is bounded by the not_modified gate",
                    () -> assertEquals(List.of(), valuesNamed(toggleOff.headers(), requestValidator),
                            () -> requestValidator + " must not cross a negative-list route with"
                                    + " not_modified off, although no deny entry names it"),
                    () -> assertEquals(List.of("v2"), valuesNamed(toggleOff.headers(), "X-Api-Version"),
                            "control: the negative-list copy carried the undenied non-validator header"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), requestValidator),
                            () -> requestValidator + " crosses the negative-list route once not_modified"
                                    + " is on"));
        }

        @ParameterizedTest
        @MethodSource("conditionalHeaders")
        @DisplayName("a positive-list naming a validator in headers_allow does not carry it while not_modified is off")
        void positiveListNamingValidatorWithholdsItWhenToggleOff(String requestValidator) {
            // Arrange — the operator names the validator explicitly, once canonically and once in the
            // lower-cased spelling, so the withholding is shown to be case-insensitive. An explicit
            // headers_allow entry is still client input; the toggle decides whether the question is
            // asked, because the response half decides on the same flag whether the answer returns.
            Map<String, List<String>> headers = Map.of(
                    requestValidator, List.of("probe-value"),
                    "X-Api-Version", List.of("v2"));
            ForwardConfig canonical = allow(List.of(requestValidator, "X-Api-Version"));
            ForwardConfig lowerCased = allow(List.of(requestValidator.toLowerCase(Locale.ROOT), "X-Api-Version"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), canonical, CookiesConfig.NONE, false);
            ForwardPolicyStage.Result lowerCasedToggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), lowerCased, CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), canonical, CookiesConfig.NONE, true);

            // Assert
            assertAll("a headers_allow entry does not outrank the not_modified gate",
                    () -> assertEquals(List.of(), valuesNamed(toggleOff.headers(), requestValidator),
                            () -> requestValidator + " must not cross with not_modified off, although"
                                    + " headers_allow names it"),
                    () -> assertEquals(List.of(), valuesNamed(lowerCasedToggleOff.headers(), requestValidator),
                            () -> requestValidator + " must not cross under a lower-cased headers_allow"
                                    + " entry either"),
                    () -> assertEquals(List.of("v2"), valuesNamed(toggleOff.headers(), "X-Api-Version"),
                            "control: the positive-list really did admit its non-validator entry"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), requestValidator),
                            () -> requestValidator + " crosses the positive-list route once not_modified"
                                    + " is on"));
        }

        @Test
        @DisplayName("an operator's set_headers validator is not withheld by the toggle — it is not client input")
        void setHeadersValidatorSurvivesTheToggle() {
            // Arrange — the toggle governs CLIENT input, exactly like the never-forward strip; an
            // operator's static header is configuration and is merged afterwards
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of("If-None-Match", List.of("\"client\"")));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().setHeaders(Map.of("If-None-Match", "\"operator\"")).build(),
                    CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("\"operator\""), valuesNamed(result.headers(), "If-None-Match"),
                    "the client's validator is withheld and the operator's static value crosses alone");
        }

        @ParameterizedTest
        @ValueSource(strings = {"If_None_Match", "If-None_Match", "If_Modified-Since", "If_Modified_Since"})
        @DisplayName("an underscore spelling of a 304 validator is withheld while not_modified is off")
        void underscoreSpelledValidatorIsWithheldWhenToggleOff(String spelling) {
            // Arrange — a CGI-folding backend resolves every -/_ spelling of a validator to one
            // HTTP_IF_* variable, so the toggle must close the whole equivalence class, exactly as the
            // never-forward strip does. The toggle-on run is the matched control: it proves the
            // spelling really survives the forward-all mode copy, so its absence below is the gate's.
            Map<String, List<String>> headers = Map.of(spelling, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, true);

            // Assert
            String folded = ConnectionHeaders.separatorFolded(spelling);
            assertAll("the not_modified gate closes the separator equivalence class",
                    () -> assertTrue(toggleOff.headers().keySet().stream()
                                    .noneMatch(name -> ConnectionHeaders.separatorFolded(name).equals(folded)),
                            () -> spelling + " must not cross with not_modified off — a folding backend"
                                    + " reads it as the canonical validator"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), spelling),
                            () -> "control: " + spelling + " crosses forward-all once not_modified is on"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"If_Match", "If_Unmodified_Since", "If_Range"})
        @DisplayName("an underscore spelling of a precondition validator still crosses forward-all while not_modified is off")
        void underscoreSpelledPreconditionCrossesWhenToggleOff(String spelling) {
            // Arrange — the separator folding only closes the class of the two 304 validators; the
            // precondition validators are not gated, so no spelling of them is withheld by the toggle
            Map<String, List<String>> headers = Map.of(spelling, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("probe-value"), valuesNamed(toggleOff.headers(), spelling),
                    () -> spelling + " is not a 304 validator, so the not_modified gate must leave it alone");
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
                    .build(), CookiesConfig.NONE, false);

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

    /**
     * A {@code headers_deny} entry withholds every {@code -}/{@code _} spelling of the name it lists,
     * through both paths a client header can take: the negative-list mode copy and the protocol set.
     * {@code If-Match} is the protocol-set representative (it would otherwise be re-admitted by the
     * unconditional tier), {@code X-Debug} the ordinary-header one.
     */
    @Nested
    @DisplayName("headers_deny matches every '-'/'_' spelling of a denied name")
    class DenyListSpellingFolding {

        private static final ForwardConfig DENY_IF_MATCH_AND_DEBUG = deny(List.of("If-Match", "X-Debug"));

        @ParameterizedTest
        @ValueSource(strings = {"If_Match", "if_match", "IF-MATCH", "If-Match", "X_Debug", "x_debug", "X-DEBUG"})
        @DisplayName("a denied name is withheld under every spelling, while an undenied header still crosses")
        void deniedNameIsWithheldUnderEverySpelling(String spelling) {
            // Arrange — the client spells the denied name its own way; the unowned companion header is
            // the matched control proving the negative-list copy ran
            Map<String, List<String>> headers = Map.of(
                    spelling, List.of("probe-value"),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), DENY_IF_MATCH_AND_DEBUG, CookiesConfig.NONE, false);

            // Assert
            String folded = ConnectionHeaders.separatorFolded(spelling);
            assertAll("the deny entry closes the separator equivalence class",
                    () -> assertTrue(result.headers().keySet().stream()
                                    .noneMatch(name -> ConnectionHeaders.separatorFolded(name).equals(folded)),
                            () -> spelling + " must not cross — a CGI-folding backend reads it as the"
                                    + " denied header"),
                    () -> assertFalse(result.headers().containsValue("probe-value"),
                            () -> "the value sent as " + spelling + " must not reach the upstream under"
                                    + " any name"),
                    () -> assertEquals(List.of("v2"), valuesNamed(result.headers(), "X-Api-Version"),
                            "control: an unrelated header still crosses the negative-list"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"If_Match", "X_Debug"})
        @DisplayName("control: the same spelling crosses a negative-list that denies something else")
        void spellingCrossesWhenNotDenied(String spelling) {
            // Arrange — proves the absence above is the deny entry's doing: without it the underscore
            // spelling survives the mode copy (If_Match is not the protocol set's If-Match)
            Map<String, List<String>> headers = Map.of(spelling, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), deny(List.of("X-Unrelated")), CookiesConfig.NONE,
                            false);

            // Assert
            assertEquals(List.of("probe-value"), valuesNamed(result.headers(), spelling),
                    () -> spelling + " crosses a negative-list that does not name it");
        }

        @Test
        @DisplayName("an underscore deny entry withholds the canonical spelling, including from the protocol set")
        void underscoreDenyEntryWithholdsTheCanonicalSpelling() {
            // Arrange — the comparison is symmetric: the operator may write the entry either way
            Map<String, List<String>> headers = Map.of(
                    "If-Match", List.of("\"v7\""),
                    "X-Debug", List.of("on"),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), deny(List.of("if_match", "X_Debug")),
                            CookiesConfig.NONE, false);

            // Assert
            assertAll("an underscore entry denies the hyphenated name",
                    () -> assertEquals(List.of(), valuesNamed(result.headers(), "If-Match"),
                            "the protocol set must not re-admit a name an underscore entry denies"),
                    () -> assertEquals(List.of(), valuesNamed(result.headers(), "X-Debug"),
                            "the mode copy must withhold the hyphenated spelling of an underscore entry"),
                    () -> assertEquals(List.of("v2"), valuesNamed(result.headers(), "X-Api-Version"),
                            "control: an undenied header still crosses"));
        }

        @Test
        @DisplayName("a deny entry withholds a 304 validator on an unsafe method too")
        void denyEntryWithholdsValidatorOnUnsafeMethod() {
            // Arrange — on a PUT the validators cross as write preconditions, so only the deny entry
            // can withhold one; the underscore spelling must be caught as well
            Map<String, List<String>> headers = Map.of("If_None_Match", List.of("*"));

            // Act
            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(HttpMethod.PUT, headers), deny(List.of("If-None-Match")),
                            CookiesConfig.NONE, false);

            // Assert
            assertFalse(result.headers().containsValue("*"),
                    "the deny entry outranks the method-aware gate under every spelling");
        }
    }

    /**
     * The {@code not_modified} gate withholds the two 304 validators only on {@code GET} and
     * {@code HEAD}. On every other method they are write preconditions (RFC 9110 §13.1.2, §13.1.3 —
     * answered {@code 412}, never {@code 304}) and cross whatever the toggle says.
     */
    @Nested
    @DisplayName("the not_modified gate is method-aware")
    class MethodAwareNotModifiedGate {

        /** Every 304 validator in its canonical spelling and in its fully underscored spelling. */
        static Stream<String> validatorSpellings() {
            return ForwardPolicyStage.CONDITIONAL_HEADERS.stream()
                    .flatMap(name -> Stream.of(name, name.replace('-', '_')));
        }

        /** Every 304 validator spelling on each of the two methods the gate applies to. */
        static Stream<Arguments> gatedMethodValidators() {
            return ForwardPolicyStage.NOT_MODIFIED_METHODS.stream()
                    .flatMap(method -> validatorSpellings().map(spelling -> Arguments.of(method, spelling)));
        }

        /** Every method on which the gate does not withhold the 304 validators. */
        static Stream<HttpMethod> ungatedMethods() {
            return Stream.of(HttpMethod.values())
                    .filter(method -> !ForwardPolicyStage.NOT_MODIFIED_METHODS.contains(method));
        }

        /** Every 304 validator spelling on every method the gate does not apply to. */
        static Stream<Arguments> ungatedMethodValidators() {
            return ungatedMethods()
                    .flatMap(method -> validatorSpellings().map(spelling -> Arguments.of(method, spelling)));
        }

        /** Every 304 validator spelling on every method. */
        static Stream<Arguments> allMethodValidators() {
            return Stream.of(HttpMethod.values())
                    .flatMap(method -> validatorSpellings().map(spelling -> Arguments.of(method, spelling)));
        }

        @Test
        @DisplayName("the gate applies to exactly GET and HEAD, the methods a 304 answers")
        void notModifiedMethodsAreGetAndHead() {
            Set<HttpMethod> expected = Set.of(HttpMethod.GET, HttpMethod.HEAD);

            Set<HttpMethod> actual = ForwardPolicyStage.NOT_MODIFIED_METHODS;

            assertEquals(expected, actual,
                    "the parameterized cases derive their methods from NOT_MODIFIED_METHODS, so the"
                            + " two-method contract is pinned here on its own");
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("gatedMethodValidators")
        @DisplayName("on GET and HEAD a 304 validator is withheld while not_modified is off, under every spelling")
        void gatedMethodWithholdsValidatorWhenToggleOff(HttpMethod method, String spelling) {
            // Arrange — the companion header is the matched control proving the mode copy ran
            Map<String, List<String>> headers = Map.of(
                    spelling, List.of("probe-value"),
                    "X-Api-Version", List.of("v2"));

            // Act
            ForwardPolicyStage.Result toggleOff = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result toggleOn = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), forwardAll(), CookiesConfig.NONE, true);

            // Assert
            assertAll("the gate holds on the methods a 304 answers",
                    () -> assertFalse(toggleOff.headers().containsValue("probe-value"),
                            () -> spelling + " must not cross a " + method + " with not_modified off"),
                    () -> assertEquals(List.of("v2"), valuesNamed(toggleOff.headers(), "X-Api-Version"),
                            "control: the forward-all copy ran"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(toggleOn.headers(), spelling),
                            () -> spelling + " crosses a " + method + " once not_modified is on"));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("ungatedMethodValidators")
        @DisplayName("on every other method a 304 validator crosses as a precondition while not_modified is off")
        void ungatedMethodCarriesValidatorWhenToggleOff(HttpMethod method, String spelling) {
            // Arrange — no 304 can answer this request, so the validator is a write precondition and
            // the mode copy carries it under either permissive mode
            Map<String, List<String>> headers = Map.of(spelling, List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result underForwardAll = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underNegativeList = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), deny(List.of("X-Unrelated")), CookiesConfig.NONE,
                            false);

            // Assert
            assertAll("the not_modified gate leaves a precondition alone",
                    () -> assertEquals(List.of("probe-value"), valuesNamed(underForwardAll.headers(), spelling),
                            () -> spelling + " must cross a " + method + " under forward-all"),
                    () -> assertEquals(List.of("probe-value"), valuesNamed(underNegativeList.headers(), spelling),
                            () -> spelling + " must cross a " + method + " under a negative-list"));
        }

        @ParameterizedTest
        @MethodSource("ungatedMethods")
        @DisplayName("on every other method the protocol set carries a 304 validator a positive-list does not name")
        void protocolSetCarriesValidatorOnUngatedMethod(HttpMethod method) {
            // Arrange — headers_allow: [] admits no client header at all
            Map<String, List<String>> headers = Map.of("If-Modified-Since", List.of("probe-value"));

            // Act
            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), allow(List.of()), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("probe-value"), valuesNamed(result.headers(), "If-Modified-Since"),
                    () -> "a " + method + " precondition crosses as a protocol header, like If-Match");
        }

        @Test
        @DisplayName("a create-only PUT keeps If-None-Match: * with not_modified off, so the origin can still answer 412")
        void createOnlyPutKeepsItsGuard() {
            // Arrange — If-None-Match: * on a PUT means "create, never overwrite"; withholding it would
            // turn the create into an unconditional overwrite of an existing resource
            Map<String, List<String>> headers = Map.of(
                    "If-None-Match", List.of("*"),
                    "Content-Type", List.of("application/json"));

            // Act
            ForwardPolicyStage.Result underForwardAll = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(HttpMethod.PUT, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result underEmptyPositiveList = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(HttpMethod.PUT, headers), allow(List.of()), CookiesConfig.NONE, false);

            // Assert
            assertAll("the create-only guard crosses on a not_modified: false route",
                    () -> assertEquals(List.of("*"), valuesNamed(underForwardAll.headers(), "If-None-Match"),
                            "forward-all must carry the create-only precondition"),
                    () -> assertEquals(List.of("*"), valuesNamed(underEmptyPositiveList.headers(), "If-None-Match"),
                            "an empty positive-list must carry it as a protocol header"));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("allMethodValidators")
        @DisplayName("with not_modified on a 304 validator crosses on every method")
        void everyMethodCarriesValidatorWhenToggleOn(HttpMethod method, String spelling) {
            Map<String, List<String>> headers = Map.of(spelling, List.of("probe-value"));

            ForwardPolicyStage.Result result = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(methodRequest(method, headers), forwardAll(), CookiesConfig.NONE, true);

            assertEquals(List.of("probe-value"), valuesNamed(result.headers(), spelling),
                    () -> spelling + " crosses a " + method + " once the route enables not_modified");
        }
    }

    private static PipelineRequest methodRequest(HttpMethod method, Map<String, List<String>> headers) {
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/api/orders/42")
                .peerAddress(UNTRUSTED_PEER)
                .headers(headers)
                .build();
    }

    private static ForwardPolicyStage stage(String emitMode, List<String> tcpTrusted, Set<String> resolverTrusted) {
        ForwardedResolverConfig config = ForwardedResolverConfig.builder()
                .trustedProxies(resolverTrusted)
                .build();
        ForwardedHeaderResolver resolver = new ForwardedHeaderResolver(config, new SecurityEventCounter());
        return new ForwardPolicyStage(resolver, new TcpPeerGate(tcpTrusted), emitMode, GATEWAY_COOKIES);
    }

    @Nested
    @DisplayName("the gateway-owned never-forward set (no mode re-admits it)")
    class RequestStrip {

        @ParameterizedTest
        @ValueSource(strings = {"Host", "Content-Length", "Connection", "Referer", "Origin", "Proxy-Authorization"})
        @DisplayName("a gateway-owned name is withheld under forward-all whatever it is")
        void gatewayOwnedNamesAreWithheldUnderForwardAll(String name) {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(name, List.of("value")));

            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

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
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    deny(List.of("X-Unrelated")), CookiesConfig.NONE, false);

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
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    deny(List.of("X-Unrelated")), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result positiveListResult = stage(EMIT_XFORWARDED, List.of(TRUSTED_CIDR),
                    Set.of(TRUSTED_CIDR)).process(request(UNTRUSTED_PEER, headers),
                    allow(List.of(name, "X-Api-Version")), CookiesConfig.NONE, false);

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
                    ForwardConfig.builder().setHeaders(Map.of("X-Real-IP", "10.1.2.3")).build(), CookiesConfig.NONE, false);

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
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("Authorization")), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result forwardAllResult = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result negativeListResult = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), deny(List.of("X-Unrelated")), CookiesConfig.NONE, false);

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

            ForwardPolicyStage.Result result = stage.process(request, allow(List.of("Authorization")), CookiesConfig.NONE, false);

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
                    request(UNTRUSTED_PEER, Map.of("TE", List.of("trailers"))), forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result gzip = gzipStage.process(
                    request(UNTRUSTED_PEER, Map.of("TE", List.of("gzip"))), forwardAll(), CookiesConfig.NONE, false);

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
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("te")), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result omitted = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), allow(List.of("X-Unrelated")), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result permissive = stage(EMIT_XFORWARDED, List.of(), Set.of())
                    .process(request(UNTRUSTED_PEER, headers), forwardAll(), CookiesConfig.NONE, false);

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
                    ForwardConfig.builder().setHeaders(Map.of("Host", "upstream.internal")).build(), CookiesConfig.NONE, false);

            assertEquals("upstream.internal", result.headers().get("Host"),
                    "the strip governs CLIENT input; an operator's static set_headers is gateway"
                            + " configuration and is merged after it");
        }
    }

    /**
     * The {@code Cookie} header is rebuilt from the route's {@code cookies} block: the gateway's own
     * cookies are dropped under every mode, then {@code none} sends nothing, a list keeps the listed
     * pairs and {@code all} keeps the rest. Every case carries an unowned companion header
     * ({@code X-Api-Version}) under forward-all where the mode copy runs, so an absent {@code Cookie}
     * is a decision of the policy and not an empty mode copy.
     */
    @Nested
    @DisplayName("the request Cookie header, rebuilt by the route's cookies policy")
    class CookiePolicy {

        private static final String MIXED_HEADER = "theme=dark; __Host-sheriff-binding=b; app=1; "
                + SESSION_COOKIE + "=s; =__Host-sheriff-session=forged; lang=de";

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"positive-list", "negative-list", "forward-all"})
        @DisplayName("none sends no Cookie whatever the header mode")
        void noneSendsNoCookie(String headerMode) {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(cookieRequest("theme=dark; app=1"),
                    headerMode(headerMode), CookiesConfig.NONE, false);

            assertAll("allow: none withholds every cookie",
                    () -> assertEquals(List.of(), valuesNamed(result.headers(), COOKIE),
                            "no Cookie header reaches the upstream under none"),
                    () -> assertEquals(List.of("v2"), valuesNamed(result.headers(), "X-Api-Version"),
                            "control: the unowned companion header still crossed"));
        }

        @Test
        @DisplayName("a list sends exactly the listed pairs, in inbound order, duplicates kept")
        void listSendsExactlyTheListedPairs() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(cookieRequest("a=1; theme=dark; b=2; a=3; Theme=x"),
                    forwardAll(), cookies(named("a", "b")), false);

            assertEquals(List.of("a=1; b=2; a=3"), valuesNamed(result.headers(), COOKIE),
                    "only the listed names cross, both a pairs in the order they arrived, and the"
                            + " case-differing Theme is a different cookie the list does not name");
        }

        @Test
        @DisplayName("all sends every pair except the gateway's own cookies")
        void allSendsEverythingButGatewayCookies() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(
                    cookieRequest(MIXED_HEADER + "; __HOST-sheriff-x=1; " + SESSION_COOKIE), forwardAll(),
                    cookies(CookieAllow.ALL), false);

            assertEquals(List.of("theme=dark; app=1; lang=de"), valuesNamed(result.headers(), COOKIE),
                    "the reserved prefix (in any case of its __Host- part), the configured session"
                            + " cookie and a nameless crumb naming a gateway cookie — with or without"
                            + " a value — are dropped; every other pair crosses in order");
        }

        @Test
        @DisplayName("a list drops the gateway's own cookies, even one the list names")
        void listDropsGatewayCookies() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(cookieRequest(MIXED_HEADER), forwardAll(),
                    cookies(named("theme", SESSION_COOKIE, "__Host-sheriff-binding", "lang")), false);

            assertEquals(List.of("theme=dark; lang=de"), valuesNamed(result.headers(), COOKIE),
                    "boot refuses a list naming a gateway cookie; the stage drops it regardless, so"
                            + " the gateway's cookies never depend on the boot rule alone");
        }

        @Test
        @DisplayName("an empty result omits the Cookie header rather than sending it empty")
        void emptyResultOmitsTheHeader() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result unlisted = stage.process(cookieRequest("other=1"), forwardAll(),
                    cookies(named("theme")), false);
            ForwardPolicyStage.Result gatewayOnly = stage.process(cookieRequest(SESSION_COOKIE + "=s"),
                    forwardAll(), cookies(CookieAllow.ALL), false);

            assertAll("nothing kept, nothing sent",
                    () -> assertEquals(List.of(), valuesNamed(unlisted.headers(), COOKIE),
                            "only unlisted names under a list"),
                    () -> assertEquals(List.of(), valuesNamed(gatewayOnly.headers(), COOKIE),
                            "only gateway cookies under all"));
        }

        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"positive-list", "negative-list", "forward-all"})
        @DisplayName("the header forward mode neither copies nor withholds the Cookie the policy rebuilds")
        void headerModeIsIndependentOfThePolicy(String headerMode) {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(cookieRequest(MIXED_HEADER), headerMode(headerMode),
                    cookies(named("theme", "app")), false);

            assertAll("every header mode yields the policy's Cookie and nothing else",
                    () -> assertEquals(List.of("theme=dark; app=1"), valuesNamed(result.headers(), COOKIE),
                            "the rebuilt header is the same under every mode"),
                    () -> assertEquals(List.of("v2"), valuesNamed(result.headers(), "X-Api-Version"),
                            "control: the mode copy ran"));
        }

        @Test
        @DisplayName("a positive-list naming Cookie never copies the client's Cookie; only the policy decides")
        void positiveListNamingCookieNeverCopiesIt() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            ForwardConfig namingCookie = allow(List.of("Cookie", "cookie", "X-Api-Version"));

            ForwardPolicyStage.Result underNone = stage.process(cookieRequest(MIXED_HEADER), namingCookie,
                    CookiesConfig.NONE, false);
            ForwardPolicyStage.Result gatewayOnlyUnderAll = stage.process(
                    cookieRequest(SESSION_COOKIE + "=s; __Host-sheriff-binding=b"), namingCookie,
                    cookies(CookieAllow.ALL), false);
            ForwardPolicyStage.Result underList = stage.process(cookieRequest(MIXED_HEADER), namingCookie,
                    cookies(named("theme")), false);

            assertAll("the mode copy skips Cookie in every spelling the list names",
                    () -> assertEquals(List.of(), valuesNamed(underNone.headers(), COOKIE),
                            "under none no Cookie crosses, although the positive-list names it"),
                    () -> assertEquals(List.of(), valuesNamed(gatewayOnlyUnderAll.headers(), COOKIE),
                            "a header holding only gateway cookies is never copied verbatim"),
                    () -> assertEquals(List.of("theme=dark"), valuesNamed(underList.headers(), COOKIE),
                            "under a list exactly the policy's rebuilt header crosses"),
                    () -> assertEquals(List.of("v2"), valuesNamed(underNone.headers(), "X-Api-Version"),
                            "control: the positive-list copy ran for the other name it lists"));
        }

        @Test
        @DisplayName("pairs are trimmed of spaces and tabs, and empty pairs are discarded")
        void pairsAreTrimmedAndEmptyPairsDiscarded() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result result = stage.process(cookieRequest("  theme=dark ;\tapp = 1\t;; ;"),
                    forwardAll(), cookies(named("theme", "app")), false);

            assertEquals(List.of("theme=dark; app = 1"), valuesNamed(result.headers(), COOKIE),
                    "the name is read trimmed, each kept pair crosses as its trimmed inbound text, and"
                            + " the rebuilt header joins them with a semicolon and one space");
        }

        @Test
        @DisplayName("a nameless crumb crosses under all and is never listed")
        void namelessCrumbCrossesOnlyUnderAll() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());

            ForwardPolicyStage.Result underAll = stage.process(cookieRequest("bare; theme=dark"), forwardAll(),
                    cookies(CookieAllow.ALL), false);
            ForwardPolicyStage.Result underList = stage.process(cookieRequest("bare; theme=dark"), forwardAll(),
                    cookies(named("bare", "theme")), false);

            assertAll("a pair without = has no name to list",
                    () -> assertEquals(List.of("bare; theme=dark"), valuesNamed(underAll.headers(), COOKIE),
                            "all keeps every pair that is not a gateway cookie"),
                    () -> assertEquals(List.of("theme=dark"), valuesNamed(underList.headers(), COOKIE),
                            "a list matches names, and a crumb's text is a value, not the name bare"));
        }

        @Test
        @DisplayName("only the first Cookie value is read")
        void onlyTheFirstCookieValueIsRead() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = request(UNTRUSTED_PEER, Map.of(COOKIE, List.of("theme=dark", "app=1")));

            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), cookies(CookieAllow.ALL), false);

            assertEquals(List.of("theme=dark"), valuesNamed(result.headers(), COOKIE),
                    "a repeated Cookie header is read by its first value, the first-value rule the"
                            + " mode copy applies to every header");
        }

        @Test
        @DisplayName("admittedCookiePairs returns exactly the pairs process forwards")
        void admittedPairsAreWhatProcessForwards() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = cookieRequest(MIXED_HEADER + "; theme=light");
            CookiesConfig all = cookies(CookieAllow.ALL);
            CookiesConfig list = cookies(named("lang", "theme"));

            List<String> admittedUnderAll = stage.admittedCookiePairs(request, all);
            List<String> admittedUnderList = stage.admittedCookiePairs(request, list);

            assertAll("the guard and the rebuild read the header the same way",
                    () -> assertEquals(List.of("theme=dark", "app=1", "lang=de", "theme=light"), admittedUnderAll),
                    () -> assertEquals(List.of(String.join("; ", admittedUnderAll)),
                            valuesNamed(stage.process(request, forwardAll(), all, false).headers(), COOKIE)),
                    () -> assertEquals(List.of("theme=dark", "lang=de", "theme=light"), admittedUnderList),
                    () -> assertEquals(List.of(String.join("; ", admittedUnderList)),
                            valuesNamed(stage.process(request, forwardAll(), list, false).headers(), COOKIE)));
        }

        @Test
        @DisplayName("admittedCookiePairs is empty when nothing in the request would be forwarded")
        void admittedPairsAreEmptyWhenNothingIsForwarded() {
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            CookiesConfig all = cookies(CookieAllow.ALL);

            assertAll("no forwardable cookie",
                    () -> assertEquals(List.of(),
                            stage.admittedCookiePairs(request(UNTRUSTED_PEER, Map.of()), all),
                            "no Cookie header at all"),
                    () -> assertEquals(List.of(),
                            stage.admittedCookiePairs(cookieRequest(SESSION_COOKIE + "=s; __Host-sheriff-binding=b"),
                                    all),
                            "a header holding only gateway cookies"),
                    () -> assertEquals(List.of(),
                            stage.admittedCookiePairs(cookieRequest("other=1; more=2"), cookies(named("theme"))),
                            "a header holding only unlisted names under a list"),
                    () -> assertEquals(List.of(),
                            stage.admittedCookiePairs(cookieRequest(MIXED_HEADER), CookiesConfig.NONE),
                            "any header under none"));
        }

        private static final String COOKIE = "Cookie";

        private static PipelineRequest cookieRequest(String cookieHeader) {
            return request(UNTRUSTED_PEER, Map.of(COOKIE, List.of(cookieHeader), "X-Api-Version", List.of("v2")));
        }

        private static CookiesConfig cookies(CookieAllow allow) {
            return CookiesConfig.builder().allow(allow).build();
        }

        private static CookieAllow named(String... names) {
            return new CookieAllow.Named(List.of(names));
        }

        /** One forward block per header mode, each letting the unowned companion header cross. */
        private static ForwardConfig headerMode(String headerMode) {
            return switch (headerMode) {
                case "positive-list" -> allow(List.of("X-Api-Version"));
                case "negative-list" -> deny(List.of("X-Unrelated"));
                default -> forwardAll();
            };
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
     * The query lists match the percent-decoded name while the raw pair crosses unchanged (ADR-0047).
     * Matching the raw spelling would be fail-open: the upstream decodes {@code %74oken} to
     * {@code token}, so a deny that compared raw names would be evaded by encoding one character.
     */
    @Nested
    @DisplayName("query lists match the decoded name, the raw pair crosses")
    class DecodedNameQueryMatching {

        private static final String ENCODED_TOKEN = "%74oken";
        /**
         * {@code %} followed by FULLWIDTH DIGIT SEVEN (U+FF17) and FULLWIDTH DIGIT FOUR (U+FF14), then
         * {@code oken}. Built from code points rather than spelled as a source escape.
         */
        private static final String FULL_WIDTH_TOKEN = "%" + (char) 0xFF17 + (char) 0xFF14 + "oken";

        @Test
        @DisplayName("query_deny: [token] withholds the percent-encoded spelling %74oken")
        void denyMatchesEncodedSpelling() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair(ENCODED_TOKEN, "secret"), pair("page", "2"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("token")).build(), CookiesConfig.NONE, false);

            // Assert
            assertFalse(namesOf(result).contains(ENCODED_TOKEN), "an encoded spelling cannot evade the deny");
            assertEquals(List.of("2"), valuesOf(result, "page"), "an undenied parameter still crosses");
        }

        @Test
        @DisplayName("query_allow: [token] admits the percent-encoded spelling %74oken under its raw spelling")
        void allowMatchesEncodedSpellingAndKeepsRawPair() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair(ENCODED_TOKEN, "a%2Fb"), pair("other", "x"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of("token")).build(), CookiesConfig.NONE, false);

            // Assert — the pair crosses byte-for-byte as validated: raw name, raw value
            assertEquals(List.of("a%2Fb"), valuesOf(result, ENCODED_TOKEN),
                    "the admitted pair keeps its raw spelling on both sides of the '='");
            assertFalse(namesOf(result).contains("token"), "the decoded spelling is never forwarded");
            assertFalse(namesOf(result).contains("other"), "a non-allow-listed parameter is dropped");
        }

        @Test
        @DisplayName("a '+' in a raw name is read as a space when matched")
        void plusIsReadAsSpace() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("session+id", "1"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("session id")).build(), CookiesConfig.NONE, false);

            // Assert
            assertTrue(result.query().isEmpty(), "form semantics: '+' is a space in the decoded name");
        }

        @ParameterizedTest(name = "withholds the undecodable name {0} under a deny list")
        @ValueSource(strings = {"bad%zz", "trunc%7", "%C3%28", "café"})
        @DisplayName("a name without a well-formed UTF-8 decoding is withheld under a list")
        void undecodableNameIsWithheldUnderList(String rawName) {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair(rawName, "1"));

            // Act
            ForwardPolicyStage.Result denyResult = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("unrelated")).build(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result forwardAllResult = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

            // Assert
            assertTrue(denyResult.query().isEmpty(), "no decoded name can be proven absent from the deny list");
            assertEquals(List.of("1"), valuesOf(forwardAllResult, rawName), "forward-all still forwards it");
        }

        @Test
        @DisplayName("a bare pair crosses as a bare pair and pair order is preserved")
        void barePairAndOrderSurvive() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("z", "1"), pair("flag", null), pair("a", "2"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("z", "flag", "a"), namesOf(result), "inbound order is kept");
            assertEquals(pair("flag", null), result.query().get(1), "the bare pair keeps its null value");
        }

        @ParameterizedTest(name = "a list of {0} withholds the full-width-digit escape")
        @ValueSource(strings = {"allow", "deny"})
        @DisplayName("an escape spelled with non-ASCII (full-width) hex digits has no decoded name")
        void fullWidthDigitEscapeIsWithheldUnderList(String mode) {
            // Arrange — '%' followed by FULLWIDTH DIGIT SEVEN and FULLWIDTH DIGIT FOUR, then 'oken'.
            // Character.digit would read the two as hex 7 and 4 and decode the name to 'token'; RFC 3986
            // HEXDIG is ASCII only, so the escape is malformed and the name has no decoded form at all.
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair(FULL_WIDTH_TOKEN, "secret"), pair("page", "2"));
            ForwardConfig forward = "allow".equals(mode)
                    ? ForwardConfig.builder().queryAllow(List.of("token", "page")).build()
                    : ForwardConfig.builder().queryDeny(List.of("token")).build();

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forward, CookiesConfig.NONE, false);

            // Assert
            assertAll(mode,
                    () -> assertFalse(namesOf(result).contains(FULL_WIDTH_TOKEN),
                            "a name with no decoded form is withheld under an allow AND a deny list"),
                    () -> assertEquals(List.of("page"), namesOf(result),
                            "the well-formed pair is still judged normally"));
        }

        @Test
        @DisplayName("an escape spelled with non-ASCII hex digits still crosses under forward-all")
        void fullWidthDigitEscapeCrossesUnderForwardAll() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair(FULL_WIDTH_TOKEN, "secret"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request, forwardAll(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of(pair(FULL_WIDTH_TOKEN, "secret")), result.query(),
                    "forward-all compares no names, so the raw pair crosses unchanged");
        }

        @Test
        @DisplayName("interleaved repeated names cross in their inbound order, never regrouped")
        void interleavedRepeatedNamesKeepInboundOrder() {
            // Arrange — a=1&b=2&a=3: a name-keyed map would forward a=1&a=3&b=2
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("a", "1"), pair("b", "2"), pair("a", "3"));
            List<QueryParameter> inbound = request.queryParameters();

            // Act
            ForwardPolicyStage.Result forwardAll = stage.process(request, forwardAll(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result allowBoth = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of("a", "b")).build(), CookiesConfig.NONE, false);
            ForwardPolicyStage.Result denyOther = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("c")).build(), CookiesConfig.NONE, false);

            // Assert
            assertAll("the forwarded sequence equals the validated sequence, pair for pair",
                    () -> assertEquals(inbound, forwardAll.query(), "forward-all"),
                    () -> assertEquals(inbound, allowBoth.query(), "an allow list keeping both names"),
                    () -> assertEquals(inbound, denyOther.query(), "a deny list keeping both names"));
        }

        @Test
        @DisplayName("a dropped pair leaves the surviving pairs in their inbound order")
        void droppedPairKeepsSurvivorsInOrder() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("a", "1"), pair("b", "2"), pair("a", "3"), pair("c", null));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("b")).build(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of(pair("a", "1"), pair("a", "3"), pair("c", null)), result.query(),
                    "each pair is judged on its own and the survivors keep their positions' order");
        }

        @Test
        @DisplayName("query_deny: [token] never yields a 'token' parameter out of x=1;token=abc")
        void denyNeverYieldsTokenOutOfSemicolonPair() {
            // Arrange — the edge splits on '&' only, so the whole pair arrives as one name 'x'
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("x", "1;token=abc"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryDeny(List.of("token")).build(), CookiesConfig.NONE, false);

            // Assert — the ';' stays inside the one judged pair; the edge renders it as %3B
            assertAll("the stage judges '&'-split pairs only",
                    () -> assertFalse(namesOf(result).contains("token"), "no separate 'token' parameter exists"),
                    () -> assertEquals(List.of("1;token=abc"), valuesOf(result, "x"),
                            "the ';' remains part of the value of the one pair 'x'"));
        }

        @Test
        @DisplayName("query_allow: [x] keeps x=1;token=abc as the single pair 'x'")
        void allowKeepsSemicolonPairAsOnePair() {
            // Arrange
            ForwardPolicyStage stage = stage(EMIT_XFORWARDED, List.of(), Set.of());
            PipelineRequest request = queryRequest(pair("x", "1;token=abc"));

            // Act
            ForwardPolicyStage.Result result = stage.process(request,
                    ForwardConfig.builder().queryAllow(List.of("x")).build(), CookiesConfig.NONE, false);

            // Assert
            assertEquals(List.of("x"), namesOf(result), "only the allow-listed pair crosses");
        }
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

    private static PipelineRequest queryRequest(QueryParameter... queryParameters) {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/api/orders")
                .peerAddress(UNTRUSTED_PEER)
                .queryParameters(List.of(queryParameters))
                .build();
    }

    /** One raw query pair; a {@code null} value is a bare pair without {@code =}. */
    private static QueryParameter pair(String name, @Nullable String value) {
        return new QueryParameter(name, value);
    }

    /** The raw names of the forwarded pairs, in forwarded order, one entry per pair. */
    private static List<String> namesOf(ForwardPolicyStage.Result result) {
        return result.query().stream().map(QueryParameter::name).toList();
    }

    /**
     * The raw values of every forwarded pair named {@code name}, in forwarded order; a bare pair
     * contributes a {@code null} entry, so the list is built mutable rather than via {@code toList()}.
     */
    private static List<@Nullable String> valuesOf(ForwardPolicyStage.Result result, String name) {
        List<@Nullable String> values = new ArrayList<>();
        for (QueryParameter parameter : result.query()) {
            if (parameter.name().equals(name)) {
                values.add(parameter.value());
            }
        }
        return values;
    }
}
