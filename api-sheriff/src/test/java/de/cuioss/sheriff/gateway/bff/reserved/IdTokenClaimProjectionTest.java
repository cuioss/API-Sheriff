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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IdTokenClaimProjection}: every claim of a validated ID token keeps its native JSON
 * type, and a raw token that is not a well-formed, bounded compact JWS is refused rather than
 * partially projected.
 * <p>
 * The {@link IdTokenContent} is built directly over a hand-composed compact token, because the
 * projection reads only its raw form — no signing, no live IdP and no test double framework.
 */
class IdTokenClaimProjectionTest {

    private static final String HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
    private static final String SIGNATURE = "c2lnbmF0dXJl";

    private static final String PAYLOAD = """
            {"sub":"user-sub-1",
             "groups":["test-group","admins"],
             "address":{"country":"DE","locality":"Berlin"},
             "exp":1790000000,
             "score":1.5,
             "email_verified":true,
             "entitlements":[{"id":7,"name":"reports"}],
             "nickname":null}
            """;

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static IdTokenContent tokenWithPayload(String payloadSegment) {
        return new IdTokenContent(Map.of(), encode(HEADER) + "." + payloadSegment + "." + SIGNATURE);
    }

    private static IdTokenContent tokenWithJson(String payloadJson) {
        return tokenWithPayload(encode(payloadJson));
    }

    @Nested
    @DisplayName("Native JSON types")
    class NativeTypes {

        @Test
        @DisplayName("An array claim is projected to a List of its elements")
        void shouldProjectArrayAsList() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            List<?> groups = assertInstanceOf(List.class, claims.get("groups"));
            assertIterableEquals(List.of("test-group", "admins"), groups);
        }

        @Test
        @DisplayName("An object claim is projected to a Map of its members")
        void shouldProjectObjectAsMap() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            Map<?, ?> address = assertInstanceOf(Map.class, claims.get("address"));
            assertEquals("DE", address.get("country"));
            assertEquals("Berlin", address.get("locality"));
        }

        @Test
        @DisplayName("An integer claim is projected to a Number, never its string spelling")
        void shouldProjectIntegerAsNumber() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            Number exp = assertInstanceOf(Number.class, claims.get("exp"));
            assertEquals(1_790_000_000L, exp.longValue());
        }

        @Test
        @DisplayName("A fractional claim is projected to a Number carrying its fraction")
        void shouldProjectFractionAsNumber() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            Number score = assertInstanceOf(Number.class, claims.get("score"));
            assertEquals(1.5d, score.doubleValue());
        }

        @Test
        @DisplayName("A boolean claim is projected to a Boolean")
        void shouldProjectBooleanAsBoolean() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            assertEquals(Boolean.TRUE, claims.get("email_verified"));
        }

        @Test
        @DisplayName("A string claim stays a String")
        void shouldKeepStringAsString() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            assertEquals("user-sub-1", claims.get("sub"));
        }

        @Test
        @DisplayName("An object nested inside an array keeps its native member types")
        void shouldProjectNestedObjectInsideArray() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            List<?> entitlements = assertInstanceOf(List.class, claims.get("entitlements"));
            Map<?, ?> entitlement = assertInstanceOf(Map.class, entitlements.getFirst());
            assertEquals(7L, assertInstanceOf(Number.class, entitlement.get("id")).longValue());
            assertEquals("reports", entitlement.get("name"));
        }

        @Test
        @DisplayName("A null claim is omitted, exactly as an absent claim")
        void shouldOmitNullClaim() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));

            assertFalse(claims.containsKey("nickname"), "a null-valued claim is not disclosed");
        }

        @Test
        @DisplayName("The projection and every nested structure are unmodifiable")
        void shouldReturnImmutableStructures() {
            Map<String, Object> claims = IdTokenClaimProjection.project(tokenWithJson(PAYLOAD));
            List<?> groups = (List<?>) claims.get("groups");
            Map<?, ?> address = (Map<?, ?>) claims.get("address");

            assertThrows(UnsupportedOperationException.class, () -> claims.put("injected", "x"));
            assertThrows(UnsupportedOperationException.class, groups::clear);
            assertThrows(UnsupportedOperationException.class, address::clear);
        }
    }

    @Nested
    @DisplayName("Fail-closed")
    class FailClosed {

        @Test
        @DisplayName("A token that is not a three-segment compact JWS is refused")
        void shouldRejectMalformedTokenShape() {
            IdTokenContent twoSegments = new IdTokenContent(Map.of(), encode(HEADER) + "." + encode(PAYLOAD));

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(twoSegments));
        }

        @Test
        @DisplayName("A payload that is not JSON is refused")
        void shouldRejectNonJsonPayload() {
            IdTokenContent token = tokenWithJson("this is not json");

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }

        @Test
        @DisplayName("A payload segment that is not base64url is refused")
        void shouldRejectNonBase64UrlPayload() {
            IdTokenContent token = tokenWithPayload("not*base64url");

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }

        @Test
        @DisplayName("An empty payload segment is refused")
        void shouldRejectEmptyPayload() {
            IdTokenContent token = tokenWithPayload("");

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }

        @Test
        @DisplayName("A payload beyond the parser payload-size limit is refused")
        void shouldRejectOversizedPayload() {
            String oversized = "{\"blob\":\"" + "a".repeat(9_000) + "\"}";
            IdTokenContent token = tokenWithJson(oversized);

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }

        @Test
        @DisplayName("A payload nesting beyond the parser nesting limit is refused")
        void shouldRejectExcessiveNesting() {
            String deep = "{\"deep\":" + "[".repeat(12) + "1" + "]".repeat(12) + "}";
            IdTokenContent token = tokenWithJson(deep);

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }

        @Test
        @DisplayName("An array beyond the parser array-size limit is refused")
        void shouldRejectOversizedArray() {
            String elements = IntStream.range(0, 1_001).mapToObj(i -> "1").collect(Collectors.joining(","));
            IdTokenContent token = tokenWithJson("{\"many\":[" + elements + "]}");

            assertThrows(IllegalArgumentException.class, () -> IdTokenClaimProjection.project(token));
        }
    }
}
