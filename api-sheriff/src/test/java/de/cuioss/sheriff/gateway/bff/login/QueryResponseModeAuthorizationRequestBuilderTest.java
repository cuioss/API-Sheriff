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
package de.cuioss.sheriff.gateway.bff.login;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link QueryResponseModeAuthorizationRequestBuilder}: the rewrite that switches the
 * engine-built authorization URL from {@code response_mode=form_post} to {@code response_mode=query}.
 * <p>
 * The rewrite is the reason the OIDC callback is a top-level GET navigation, which is in turn the
 * only request shape the browser sends the {@code SameSite=Lax} binding cookie on. Two properties
 * therefore matter and are pinned here: the mode really does become {@code query}, and
 * <em>nothing else</em> in the URL is disturbed — a rewrite that silently re-encoded
 * {@code redirect_uri}, dropped {@code code_challenge} or reordered the query would break the flow
 * in ways no response-mode assertion alone would catch.
 * <p>
 * The static {@link QueryResponseModeAuthorizationRequestBuilder#withQueryResponseMode(String)} is
 * exercised directly rather than through {@code build(..)}: the engine's superclass needs live
 * client configuration and provider metadata, while the rewrite — the part this class actually owns
 * — is a total function on the URL string. Testing it directly is what makes the parameter-survival
 * assertions expressible against a URL carrying every parameter at once.
 */
@DisplayName("QueryResponseModeAuthorizationRequestBuilder — response_mode rewrite")
class QueryResponseModeAuthorizationRequestBuilderTest {

    private static final String AUTHORIZE = "https://idp.example.com/realms/integration/protocol/openid-connect/auth";

    /**
     * A representative engine-built authorization URL: every parameter the engine emits, in the
     * engine's order, with {@code redirect_uri} and {@code scope} already form-encoded — the two
     * values a careless decode/re-encode round-trip would corrupt.
     */
    private static String engineUrl(String responseModePair) {
        List<String> pairs = new ArrayList<>(List.of(
                "response_type=code",
                "client_id=api-sheriff",
                "redirect_uri=https%3A%2F%2Fgw.example.com%2Fauth%2Fcallback",
                "scope=openid+profile+email",
                "state=Xy7-state_value",
                "nonce=Nn9-nonce_value",
                "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method=S256"));
        if (responseModePair != null) {
            pairs.add(responseModePair);
        }
        return AUTHORIZE + "?" + String.join("&", pairs);
    }

    /** Splits a URL's query into ordered name → value pairs, without decoding either side. */
    private static Map<String, String> queryPairs(String url) {
        Map<String, String> pairs = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null || query.isEmpty()) {
            return pairs;
        }
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                pairs.put(pair, "");
            } else {
                pairs.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return pairs;
    }

    @Nested
    @DisplayName("The response mode becomes query")
    class ResponseMode {

        @Test
        @DisplayName("Should rewrite response_mode=form_post to response_mode=query")
        void shouldRewriteFormPostToQuery() {
            String rewritten = QueryResponseModeAuthorizationRequestBuilder
                    .withQueryResponseMode(engineUrl("response_mode=form_post"));

            assertAll("the engine's built-in form_post is replaced, not merely appended to",
                    () -> assertEquals("query", queryPairs(rewritten).get("response_mode"),
                            "the gateway drives response_mode=query"),
                    () -> assertFalse(rewritten.contains("form_post"),
                            "no form_post value may survive anywhere in the URL: " + rewritten));
        }

        @Test
        @DisplayName("Should add response_mode=query when the engine emitted no response_mode at all")
        void shouldAddResponseModeWhenAbsent() {
            String rewritten = QueryResponseModeAuthorizationRequestBuilder
                    .withQueryResponseMode(engineUrl(null));

            assertEquals("query", queryPairs(rewritten).get("response_mode"),
                    "the mode is asserted explicitly rather than left to the response_type=code default");
        }

        @Test
        @DisplayName("Should be idempotent — applying the rewrite twice yields the same URL")
        void shouldBeIdempotent() {
            String once = QueryResponseModeAuthorizationRequestBuilder
                    .withQueryResponseMode(engineUrl("response_mode=form_post"));

            String twice = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(once);

            assertEquals(once, twice, "a URL already carrying response_mode=query comes back unchanged");
        }

        @Test
        @DisplayName("Should rewrite only the response_mode parameter, never a lookalike name")
        void shouldNotRewriteALookalikeParameterName() {
            String url = AUTHORIZE + "?response_mode_hint=form_post&response_mode=form_post";

            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(url);

            assertAll("the rewrite matches on the whole parameter NAME, not on a substring",
                    () -> assertEquals("form_post", queryPairs(rewritten).get("response_mode_hint"),
                            "a differently-named parameter that merely starts the same is untouched"),
                    () -> assertEquals("query", queryPairs(rewritten).get("response_mode")));
        }
    }

    @Nested
    @DisplayName("Every other authorization parameter survives verbatim")
    class ParameterSurvival {

        @ParameterizedTest(name = "{0} survives the rewrite byte-for-byte")
        @ValueSource(strings = {"response_type", "client_id", "redirect_uri", "scope", "state", "nonce",
                "code_challenge", "code_challenge_method"})
        @DisplayName("Should preserve each authorization parameter's value exactly")
        void shouldPreserveParameterValue(String parameter) {
            String original = engineUrl("response_mode=form_post");

            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(original);

            assertEquals(queryPairs(original).get(parameter), queryPairs(rewritten).get(parameter),
                    parameter + " must be copied through exactly as the engine emitted it — values are never "
                            + "decoded and re-encoded, so no round-trip can corrupt an already-encoded value");
        }

        @Test
        @DisplayName("Should preserve acr_values and max_age when the step-up leg supplies them")
        void shouldPreserveStepUpParameters() {
            String original = engineUrl("response_mode=form_post")
                    + "&acr_values=urn%3Amace%3Aincommon%3Aiap%3Asilver&max_age=0";

            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(original);

            assertAll("the RFC 9470 step-up re-drive shares this builder, so its parameters matter too",
                    () -> assertEquals("urn%3Amace%3Aincommon%3Aiap%3Asilver", queryPairs(rewritten).get("acr_values")),
                    () -> assertEquals("0", queryPairs(rewritten).get("max_age")),
                    () -> assertEquals("query", queryPairs(rewritten).get("response_mode")));
        }

        @Test
        @DisplayName("Should preserve the parameter order and the authorization endpoint itself")
        void shouldPreserveOrderAndEndpoint() {
            String original = engineUrl("response_mode=form_post");

            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(original);

            assertAll("only the response_mode VALUE changes; the URL's shape does not",
                    () -> assertTrue(rewritten.startsWith(AUTHORIZE + "?"),
                            "the authorization endpoint is untouched: " + rewritten),
                    () -> assertEquals(List.copyOf(queryPairs(original).keySet()),
                            List.copyOf(queryPairs(rewritten).keySet()),
                            "the parameter names keep their original order — the rewrite is in-place"));
        }
    }

    @Nested
    @DisplayName("Total over degenerate URL shapes")
    class DegenerateShapes {

        @Test
        @DisplayName("Should append the parameter to a URL carrying no query at all")
        void shouldAppendToUrlWithoutQuery() {
            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(AUTHORIZE);

            assertEquals(AUTHORIZE + "?response_mode=query", rewritten);
        }

        @Test
        @DisplayName("Should append the parameter to a URL whose query is empty")
        void shouldAppendToUrlWithEmptyQuery() {
            String rewritten = QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(AUTHORIZE + "?");

            assertEquals(AUTHORIZE + "?response_mode=query", rewritten);
        }

        @Test
        @DisplayName("Should reject a null authorization URL")
        void shouldRejectNullUrl() {
            assertThrows(NullPointerException.class,
                    () -> QueryResponseModeAuthorizationRequestBuilder.withQueryResponseMode(null));
        }
    }
}
