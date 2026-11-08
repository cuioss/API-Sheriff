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
package de.cuioss.sheriff.gateway.edge.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rules of the WebSocket opening handshake the edge judges before it spends anything on a request:
 * each valid header set is accepted, and each single violation is refused with its own status and the
 * response headers that status carries. Every case starts from a valid handshake and breaks exactly one
 * rule, so a refusal can only come from the rule the case names.
 */
@EnableGeneratorController
@DisplayName("WebSocketUpgradeGate — the opening-handshake rules judged before any upstream dial")
class WebSocketUpgradeGateTest {

    private static final String UPGRADE_REQUIRED_CONNECTION = "Upgrade";
    private static final String WEBSOCKET = "websocket";

    @Nested
    @DisplayName("a valid handshake is accepted")
    class Accepted {

        @Test
        @DisplayName("the canonical HTTP/1.1 handshake with a generated 16-byte key is accepted")
        void acceptsCanonicalHandshake() {
            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, validHandshake()));
        }

        @Test
        @DisplayName("the Connection and Upgrade tokens match in any letter case")
        void acceptsTokensInAnyCase() {
            MultiMap headers = validHandshake()
                    .set(WebSocketUpgradeGate.CONNECTION_HEADER, mixedCase("upgrade"))
                    .set(WebSocketUpgradeGate.UPGRADE_HEADER, mixedCase(WEBSOCKET));

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a token is found anywhere in a comma-separated list, with surrounding whitespace")
        void acceptsTokenInsideCommaList() {
            MultiMap headers = validHandshake()
                    .set(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive ,  Upgrade")
                    .set(WebSocketUpgradeGate.UPGRADE_HEADER, "foo/1, websocket");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a token is found on any of several repeated header lines")
        void acceptsTokenOnRepeatedHeaderLine() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.CONNECTION_HEADER);
            headers.add(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive")
                    .add(WebSocketUpgradeGate.CONNECTION_HEADER, "Upgrade");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }
    }

    @Nested
    @DisplayName("a request whose method is not GET is refused 405")
    class MethodNotAllowed {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"})
        @DisplayName("a valid handshake sent with another method is refused 405 naming GET as the only allowed one")
        void refusesNonGetMethod(String method) {
            assertEquals(refused(405, Map.of("Allow", "GET")),
                    WebSocketUpgradeGate.judge(HttpMethod.valueOf(method), HttpVersion.HTTP_1_1, validHandshake()));
        }
    }

    @Nested
    @DisplayName("a request that has to switch protocols first is refused 426")
    class UpgradeRequired {

        @Test
        @DisplayName("HTTP/2 is refused 426 without Upgrade or Connection, both forbidden on HTTP/2")
        void refusesHttp2WithoutConnectionSpecificHeaders() {
            assertEquals(refused(426, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_2, validHandshake()));
        }

        @Test
        @DisplayName("HTTP/1.0 is refused 426 naming the websocket protocol")
        void refusesHttp10NamingTheProtocol() {
            assertEquals(refused(426, upgradeHeaders()),
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_0, validHandshake()));
        }

        @Test
        @DisplayName("a request without a Connection header is refused 426 naming the websocket protocol")
        void refusesMissingConnection() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.CONNECTION_HEADER);

            assertEquals(refused(426, upgradeHeaders()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Connection header that only contains the letters of the token is no upgrade token")
        void refusesConnectionWithoutWholeToken() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive, upgraded");

            assertEquals(refused(426, upgradeHeaders()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a request without an Upgrade header is refused 426 naming the websocket protocol")
        void refusesMissingUpgrade() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.UPGRADE_HEADER);

            assertEquals(refused(426, upgradeHeaders()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("an Upgrade header naming another protocol is refused 426")
        void refusesUpgradeToAnotherProtocol() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.UPGRADE_HEADER,
                    Generators.letterStrings(3, 10).next() + "/2");

            assertEquals(refused(426, upgradeHeaders()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a plain GET carrying none of the handshake headers is refused 426")
        void refusesPlainGet() {
            assertEquals(refused(426, upgradeHeaders()),
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, MultiMap.caseInsensitiveMultiMap()));
        }

        @ParameterizedTest(name = "Sec-WebSocket-Version: {0}")
        @ValueSource(strings = {"8", "12", "14", "13, 8", "thirteen"})
        @DisplayName("a Sec-WebSocket-Version other than 13 is refused 426 naming version 13")
        void refusesUnsupportedVersion(String version) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, version);

            Map<String, String> expected = new LinkedHashMap<>(upgradeHeaders());
            expected.put(WebSocketUpgradeGate.VERSION_HEADER, "13");
            assertEquals(refused(426, expected), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }
    }

    @Nested
    @DisplayName("a malformed handshake is refused 400")
    class BadRequest {

        @Test
        @DisplayName("a missing Sec-WebSocket-Version is refused 400")
        void refusesMissingVersion() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.VERSION_HEADER);

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a blank Sec-WebSocket-Version is refused 400 like a missing one")
        void refusesBlankVersion() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "   ");

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a missing Sec-WebSocket-Key is refused 400")
        void refusesMissingKey() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.KEY_HEADER);

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "{0} decoded bytes")
        @ValueSource(ints = {1, 8, 15, 17, 24, 32})
        @DisplayName("a Sec-WebSocket-Key that decodes to anything but 16 bytes is refused 400")
        void refusesKeyOfWrongDecodedLength(int length) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, keyOfLength(length));

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "Sec-WebSocket-Key: {0}")
        @ValueSource(strings = {"dGhlIHNhbXBsZSBub25jZQ=!", "not base64 at all ****", "dGhlIHNhbXBsZSBub25j-Q==",
                "dGhlIHNhbXBsZSBub25j_Q=="})
        @DisplayName("a Sec-WebSocket-Key that is no valid base64 is refused 400")
        void refusesNonBase64Key(String key) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, key);

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Sec-WebSocket-Version repeated on a second header line is refused 400, whatever the first says")
        void refusesRepeatedVersionLine() {
            MultiMap headers = validHandshake().add(WebSocketUpgradeGate.VERSION_HEADER, "8");

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Sec-WebSocket-Key repeated on a second header line is refused 400, whatever the first says")
        void refusesRepeatedKeyLine() {
            MultiMap headers = validHandshake().add(WebSocketUpgradeGate.KEY_HEADER, keyOfLength(16));

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "the RFC 6455 sample key with {0} of its two padding characters cut off")
        @ValueSource(ints = {1, 2})
        @DisplayName("a Sec-WebSocket-Key that decodes to 16 bytes but is not padded base64 is refused 400")
        void refusesUnpaddedKey(int paddingCutOff) {
            String rfcSampleKey = "dGhlIHNhbXBsZSBub25jZQ==";
            String key = rfcSampleKey.substring(0, rfcSampleKey.length() - paddingCutOff);
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, key);

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("control: the RFC 6455 sample key, which these malformed keys are derived from, is accepted")
        void acceptsTheRfcSampleKey() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, "dGhlIHNhbXBsZSBub25jZQ==");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }
    }

    @Nested
    @DisplayName("the order the rules are checked in")
    class RuleOrder {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HttpVersion.class, names = {"HTTP_1_0", "HTTP_2"})
        @DisplayName("the HTTP version is judged first: a request that breaks every rule is refused for its version")
        void versionIsJudgedFirst(HttpVersion version) {
            WebSocketUpgradeGate.Verdict verdict = WebSocketUpgradeGate.judge(HttpMethod.GET, version, MultiMap.caseInsensitiveMultiMap());

            assertEquals(426, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class, verdict).status());
        }

        @Test
        @DisplayName("the method is judged first: an HTTP/2 POST that breaks every other rule is refused 405")
        void methodIsJudgedFirst() {
            WebSocketUpgradeGate.Verdict verdict = WebSocketUpgradeGate.judge(HttpMethod.POST, HttpVersion.HTTP_2,
                    MultiMap.caseInsensitiveMultiMap());

            assertEquals(405, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class, verdict).status());
        }

        @Test
        @DisplayName("repeated handshake fields are judged before their values: a repeated unsupported version is refused 400")
        void repeatedFieldsAreJudgedBeforeTheirValues() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "8")
                    .add(WebSocketUpgradeGate.VERSION_HEADER, "8");

            assertEquals(refused(400, Map.of()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the upgrade tokens are judged before the version and key: a plain GET with a bad key is refused 426")
        void tokensAreJudgedBeforeTheKey() {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap().set(WebSocketUpgradeGate.KEY_HEADER, "short");

            assertEquals(refused(426, upgradeHeaders()), WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the version is judged before the key: an unsupported version with a bad key is refused 426")
        void versionIsJudgedBeforeTheKey() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "8")
                    .set(WebSocketUpgradeGate.KEY_HEADER, "short");

            assertEquals(426, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers)).status());
        }
    }

    @Test
    @DisplayName("a refusal's headers cannot be changed after the verdict was made")
    void refusalHeadersAreUnmodifiable() {
        WebSocketUpgradeGate.Verdict.Refused refused = assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, MultiMap.caseInsensitiveMultiMap()));
        Map<String, String> headers = refused.headers();

        assertThrows(UnsupportedOperationException.class, () -> headers.put("X-Injected", "value"));
    }

    /** A valid HTTP/1.1 opening handshake carrying a freshly generated 16-byte key. */
    private static MultiMap validHandshake() {
        return MultiMap.caseInsensitiveMultiMap()
                .set(WebSocketUpgradeGate.CONNECTION_HEADER, "Upgrade")
                .set(WebSocketUpgradeGate.UPGRADE_HEADER, WEBSOCKET)
                .set(WebSocketUpgradeGate.VERSION_HEADER, "13")
                .set(WebSocketUpgradeGate.KEY_HEADER, keyOfLength(16));
    }

    /** A base64 key decoding to {@code length} generated bytes. */
    private static String keyOfLength(int length) {
        byte[] nonce = new byte[length];
        for (int index = 0; index < length; index++) {
            nonce[index] = Generators.integers(Byte.MIN_VALUE, Byte.MAX_VALUE).next().byteValue();
        }
        return Base64.getEncoder().encodeToString(nonce);
    }

    /** {@code token} with each letter's case chosen by the generator. */
    private static String mixedCase(String token) {
        StringBuilder mixed = new StringBuilder(token.length());
        for (char letter : token.toCharArray()) {
            String single = String.valueOf(letter);
            mixed.append(Generators.booleans().next() ? single.toUpperCase(Locale.ROOT) : single.toLowerCase(Locale.ROOT));
        }
        return mixed.toString();
    }

    /** The headers an HTTP/1.x {@code 426} carries: the protocol to switch to and its connection option. */
    private static Map<String, String> upgradeHeaders() {
        return Map.of(WebSocketUpgradeGate.UPGRADE_HEADER, WEBSOCKET,
                WebSocketUpgradeGate.CONNECTION_HEADER, UPGRADE_REQUIRED_CONNECTION);
    }

    private static WebSocketUpgradeGate.Verdict refused(int status, Map<String, String> headers) {
        return new WebSocketUpgradeGate.Verdict.Refused(status, headers);
    }
}
