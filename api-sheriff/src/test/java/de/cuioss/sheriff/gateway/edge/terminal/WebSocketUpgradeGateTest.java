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
    /** A valid origin-form request URI, the one every case sends unless it breaks the URI rule. */
    private static final String REQUEST_URI = "/ws/room";
    /** The {@code 413} of an over-bound declared body: only {@code Connection: close}, and the connection retired. */
    private static final WebSocketUpgradeGate.Verdict CONTENT_TOO_LARGE =
            new WebSocketUpgradeGate.Verdict.Refused(413, Map.of(WebSocketUpgradeGate.CONNECTION_HEADER, "close"), true);
    /** One byte above the 8192 handshake body bytes vertx-core 4.5.34 buffers. */
    private static final String OVER_BOUND_LENGTH = "8193";

    @Nested
    @DisplayName("a valid handshake is accepted")
    class Accepted {

        @Test
        @DisplayName("the canonical HTTP/1.1 handshake with a generated 16-byte key is accepted")
        void acceptsCanonicalHandshake() {
            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, validHandshake()));
        }

        @Test
        @DisplayName("the Connection and Upgrade tokens match in any letter case")
        void acceptsTokensInAnyCase() {
            MultiMap headers = validHandshake()
                    .set(WebSocketUpgradeGate.CONNECTION_HEADER, mixedCase("upgrade"))
                    .set(WebSocketUpgradeGate.UPGRADE_HEADER, mixedCase(WEBSOCKET));

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the upgrade token is found anywhere in a comma-separated Connection value, with surrounding whitespace")
        void acceptsUpgradeTokenInsideCommaList() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive ,  Upgrade");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the upgrade token on the first of several Connection lines is accepted")
        void acceptsUpgradeTokenOnTheFirstConnectionLine() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.CONNECTION_HEADER);
            headers.add(WebSocketUpgradeGate.CONNECTION_HEADER, "Upgrade")
                    .add(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("websocket as the whole value of any of several Upgrade lines is accepted")
        void acceptsWebsocketAsTheWholeValueOfAnyUpgradeLine() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.UPGRADE_HEADER);
            headers.add(WebSocketUpgradeGate.UPGRADE_HEADER, "chat")
                    .add(WebSocketUpgradeGate.UPGRADE_HEADER, WEBSOCKET);

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
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
                    judge(HttpMethod.valueOf(method), HttpVersion.HTTP_1_1, validHandshake()));
        }
    }

    @Nested
    @DisplayName("a request that has to switch protocols first is refused 426")
    class UpgradeRequired {

        @Test
        @DisplayName("HTTP/2 is refused 426 without Upgrade or Connection, both forbidden on HTTP/2")
        void refusesHttp2WithoutConnectionSpecificHeaders() {
            assertEquals(refused(426, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_2, validHandshake()));
        }

        @Test
        @DisplayName("HTTP/1.0 is refused 426 naming the websocket protocol")
        void refusesHttp10NamingTheProtocol() {
            assertEquals(refused(426, upgradeHeaders()),
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_0, validHandshake()));
        }

        @Test
        @DisplayName("a request without a Connection header is refused 426 naming the websocket protocol")
        void refusesMissingConnection() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.CONNECTION_HEADER);

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Connection header that only contains the letters of the token is no upgrade token")
        void refusesConnectionWithoutWholeToken() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive, upgraded");

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("an upgrade token on a later Connection line only is refused 426: Vert.x reads the first line alone")
        void refusesUpgradeTokenOnALaterConnectionLineOnly() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.CONNECTION_HEADER);
            headers.add(WebSocketUpgradeGate.CONNECTION_HEADER, "keep-alive")
                    .add(WebSocketUpgradeGate.CONNECTION_HEADER, "Upgrade");

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "Vert.x would answer 400 to this handshake after the upstream was dialled");
        }

        @Test
        @DisplayName("websocket only inside an Upgrade list is refused 426: the handshake accepts the whole value alone")
        void refusesWebsocketInsideAnUpgradeList() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.UPGRADE_HEADER, "chat, websocket");

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "the handshake would answer 400 to this request after the upstream was dialled");
        }

        @Test
        @DisplayName("a request without an Upgrade header is refused 426 naming the websocket protocol")
        void refusesMissingUpgrade() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.UPGRADE_HEADER);

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("an Upgrade header naming another protocol is refused 426")
        void refusesUpgradeToAnotherProtocol() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.UPGRADE_HEADER,
                    Generators.letterStrings(3, 10).next() + "/2");

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a plain GET carrying none of the handshake headers is refused 426")
        void refusesPlainGet() {
            assertEquals(refused(426, upgradeHeaders()),
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, MultiMap.caseInsensitiveMultiMap()));
        }

        @ParameterizedTest(name = "Sec-WebSocket-Version: {0}")
        @ValueSource(strings = {"8", "12", "14", "13, 8", "thirteen"})
        @DisplayName("a Sec-WebSocket-Version other than 13 is refused 426 naming version 13")
        void refusesUnsupportedVersion(String version) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, version);

            Map<String, String> expected = new LinkedHashMap<>(upgradeHeaders());
            expected.put(WebSocketUpgradeGate.VERSION_HEADER, "13");
            assertEquals(refused(426, expected), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
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

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a blank Sec-WebSocket-Version is refused 400 like a missing one")
        void refusesBlankVersion() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "   ");

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a missing Sec-WebSocket-Key is refused 400")
        void refusesMissingKey() {
            MultiMap headers = validHandshake();
            headers.remove(WebSocketUpgradeGate.KEY_HEADER);

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "{0} decoded bytes")
        @ValueSource(ints = {1, 8, 15, 17, 24, 32})
        @DisplayName("a Sec-WebSocket-Key that decodes to anything but 16 bytes is refused 400")
        void refusesKeyOfWrongDecodedLength(int length) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, keyOfLength(length));

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "Sec-WebSocket-Key: {0}")
        @ValueSource(strings = {"dGhlIHNhbXBsZSBub25jZQ=!", "not base64 at all ****", "dGhlIHNhbXBsZSBub25j-Q==",
                "dGhlIHNhbXBsZSBub25j_Q=="})
        @DisplayName("a Sec-WebSocket-Key that is no valid base64 is refused 400")
        void refusesNonBase64Key(String key) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, key);

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Sec-WebSocket-Version repeated on a second header line is refused 400, whatever the first says")
        void refusesRepeatedVersionLine() {
            MultiMap headers = validHandshake().add(WebSocketUpgradeGate.VERSION_HEADER, "8");

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a Sec-WebSocket-Key repeated on a second header line is refused 400, whatever the first says")
        void refusesRepeatedKeyLine() {
            MultiMap headers = validHandshake().add(WebSocketUpgradeGate.KEY_HEADER, keyOfLength(16));

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "the RFC 6455 sample key with {0} of its two padding characters cut off")
        @ValueSource(ints = {1, 2})
        @DisplayName("a Sec-WebSocket-Key that decodes to 16 bytes but is not padded base64 is refused 400")
        void refusesUnpaddedKey(int paddingCutOff) {
            String rfcSampleKey = "dGhlIHNhbXBsZSBub25jZQ==";
            String key = rfcSampleKey.substring(0, rfcSampleKey.length() - paddingCutOff);
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, key);

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("control: the RFC 6455 sample key, which these malformed keys are derived from, is accepted")
        void acceptsTheRfcSampleKey() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, "dGhlIHNhbXBsZSBub25jZQ==");

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }
    }

    /**
     * vertx-core 4.5.34 buffers at most 8192 bytes of a handshake request body and answers a larger one
     * {@code 413} with a connection close ({@code Http1xServerRequest.webSocket()}); the gate refuses
     * the same body from its declared length.
     * <p>
     * The gate judges every declared {@code Content-Length} on its own. Through the pipeline a
     * {@code Content-Length} that is no decimal number, and a second {@code Content-Length} line, are
     * refused by the framing gate before route selection, so the cases declaring them pin the gate's
     * defence in depth; a single decimal length above 8192 on a {@code GET} is the one that reaches it.
     */
    @Nested
    @DisplayName("a handshake declaring a body above 8192 bytes is refused 413 and retires its connection")
    class ContentTooLarge {

        @ParameterizedTest(name = "Content-Length: {0}")
        @ValueSource(strings = {"8193", "65536", "9223372036854775807"})
        @DisplayName("a declared Content-Length above 8192 is refused 413, closing the connection")
        void refusesDeclaredLengthAboveTheBound(String contentLength) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, contentLength);

            assertEquals(CONTENT_TOO_LARGE, judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "a declared length above 8192 is refused 413 and retires the connection");
        }

        @Test
        @DisplayName("a generated declared length above the bound is refused 413")
        void refusesGeneratedLengthAboveTheBound() {
            long declared = Generators.longs(WebSocketUpgradeGate.MAX_HANDSHAKE_BODY + 1, Long.MAX_VALUE).next();
            MultiMap headers = validHandshake()
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, Long.toString(declared));

            assertEquals(CONTENT_TOO_LARGE, judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @ParameterizedTest(name = "Content-Length: {0}")
        @ValueSource(strings = {"-1", "+1", "1e3", "abc", "", "99999999999999999999"})
        @DisplayName("a declared Content-Length that is no decimal number within a long is refused 413 like an over-bound one")
        void refusesNonDecimalDeclaredLength(String contentLength) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, contentLength);

            assertEquals(CONTENT_TOO_LARGE, judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "a declared length that is no decimal number within a long is refused like an over-bound one");
        }

        @ParameterizedTest(name = "Content-Length: {0}")
        @ValueSource(strings = {"8192", "0", "1"})
        @DisplayName("boundary: a declared Content-Length of at most 8192 is accepted, 8192 itself included")
        void acceptsDeclaredLengthWithinTheBound(String contentLength) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, contentLength);

            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("an over-bound length on any of several Content-Length lines is refused 413")
        void refusesOverBoundLengthOnAnyLine() {
            MultiMap headers = validHandshake().add(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, "0")
                    .add(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, "8193");

            assertEquals(CONTENT_TOO_LARGE, judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }
    }

    /**
     * The gate never reads a body, so on HTTP/1.x a declared body above the bound would frame the next
     * request on the connection whichever rule refused the request first: every such refusal keeps its
     * own status and retires the connection. On HTTP/2 a refusal ends only its own stream.
     */
    @Nested
    @DisplayName("an earlier refusal of a request declaring a body above 8192 bytes keeps its status and retires the HTTP/1.x connection")
    class EarlierRefusalOfAnOverBoundBody {

        @ParameterizedTest(name = "Content-Length: {0}")
        @ValueSource(strings = {OVER_BOUND_LENGTH, "abc"})
        @DisplayName("a malformed key with an over-bound or non-decimal declared length is refused 400, retiring the connection")
        void badKeyWithOverBoundBodyRetiresTheConnection(String contentLength) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, "short")
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, contentLength);

            assertEquals(retiring(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "the key decides the status, the unread body decides the retirement");
        }

        @ParameterizedTest(name = "Content-Length: {0}")
        @ValueSource(strings = {"8192", "0"})
        @DisplayName("control: a malformed key with a declared length within the bound is refused 400, keeping the connection")
        void badKeyWithinTheBoundKeepsTheConnection(String contentLength) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, "short")
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, contentLength);

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a POST declaring an over-bound body is refused 405 Allow: GET, retiring the connection")
        void nonGetWithOverBoundBodyRetiresTheConnection() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, OVER_BOUND_LENGTH);

            assertEquals(retiring(405, Map.of("Allow", "GET")), judge(HttpMethod.POST, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("a plain GET declaring an over-bound body is refused 426 with Connection: Upgrade, close")
        void upgradeRequiredWithOverBoundBodyKeepsTheUpgradeOption() {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, OVER_BOUND_LENGTH);
            Map<String, String> expected = Map.of(WebSocketUpgradeGate.UPGRADE_HEADER, WEBSOCKET,
                    WebSocketUpgradeGate.CONNECTION_HEADER, "Upgrade, close");

            assertEquals(new WebSocketUpgradeGate.Verdict.Refused(426, expected, true),
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers),
                    "every sender of Upgrade must keep the upgrade option beside close (RFC 9110 §7.8)");
        }

        @Test
        @DisplayName("an unsupported version declaring an over-bound body is refused 426 naming version 13, retiring the connection")
        void unsupportedVersionWithOverBoundBodyRetiresTheConnection() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "8")
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, OVER_BOUND_LENGTH);

            WebSocketUpgradeGate.Verdict.Refused verdict = assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
            assertAll("the 426 keeps its headers and retires the connection",
                    () -> assertEquals(426, verdict.status()),
                    () -> assertEquals("13", verdict.headers().get(WebSocketUpgradeGate.VERSION_HEADER)),
                    () -> assertEquals("Upgrade, close", verdict.headers().get(WebSocketUpgradeGate.CONNECTION_HEADER)),
                    () -> assertTrue(verdict.retiresConnection()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"GET", "POST"})
        @DisplayName("on HTTP/2 a refusal of an over-bound body retires nothing and names no Connection option")
        void http2RefusalRetiresNothing(String method) {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, OVER_BOUND_LENGTH);

            WebSocketUpgradeGate.Verdict.Refused verdict = assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                    judge(HttpMethod.valueOf(method), HttpVersion.HTTP_2, headers));
            assertAll("an HTTP/2 refusal ends only its own stream",
                    () -> assertFalse(verdict.retiresConnection(), "closing the connection would end every sibling stream"),
                    () -> assertNull(verdict.headers().get(WebSocketUpgradeGate.CONNECTION_HEADER),
                            "Connection is a connection-specific field HTTP/2 forbids"));
        }
    }

    /**
     * vertx-core 4.5.34 composes the handshake's WebSocket location from the request URI and answers
     * {@code 400} when {@code new URI(request.uri())} fails ({@code HttpUtils.getWebSocketLocation},
     * {@code Http1xServerConnection.createHandshaker}).
     */
    @Nested
    @DisplayName("a handshake whose request URI does not parse is refused 400")
    class UnparseableRequestUri {

        @ParameterizedTest(name = "request URI: {0}")
        @ValueSource(strings = {"/ws/room?a|b", "/ws/room?q={x}", "/ws/room?a^b", "/ws/ro om", "/ws/%zz",
                "/ws/room#a#b"})
        @DisplayName("a request URI java.net.URI cannot parse is refused 400, keeping the connection")
        void refusesUnparseableRequestUri(String requestUri) {
            assertEquals(refused(400, Map.of()),
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, requestUri, validHandshake()));
        }

        @ParameterizedTest(name = "request URI: {0}")
        @ValueSource(strings = {"/ws/room?a=b&c=%20d", "http://gw.example.com/ws/room", "/ws/room#top", "/"})
        @DisplayName("control: an origin-form, absolute-form or fragment-carrying URI that parses is accepted")
        void acceptsParseableRequestUri(String requestUri) {
            assertSame(WebSocketUpgradeGate.Verdict.Accepted.INSTANCE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, requestUri, validHandshake()));
        }
    }

    @Nested
    @DisplayName("the order the rules are checked in")
    class RuleOrder {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HttpVersion.class, names = {"HTTP_1_0", "HTTP_2"})
        @DisplayName("the HTTP version is judged first: a request that breaks every rule is refused for its version")
        void versionIsJudgedFirst(HttpVersion version) {
            WebSocketUpgradeGate.Verdict verdict = judge(HttpMethod.GET, version, MultiMap.caseInsensitiveMultiMap());

            assertEquals(426, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class, verdict).status());
        }

        @Test
        @DisplayName("the method is judged first: an HTTP/2 POST that breaks every other rule is refused 405")
        void methodIsJudgedFirst() {
            WebSocketUpgradeGate.Verdict verdict = judge(HttpMethod.POST, HttpVersion.HTTP_2,
                    MultiMap.caseInsensitiveMultiMap());

            assertEquals(405, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class, verdict).status());
        }

        @Test
        @DisplayName("repeated handshake fields are judged before their values: a repeated unsupported version is refused 400")
        void repeatedFieldsAreJudgedBeforeTheirValues() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "8")
                    .add(WebSocketUpgradeGate.VERSION_HEADER, "8");

            assertEquals(refused(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the upgrade tokens are judged before the version and key: a plain GET with a bad key is refused 426")
        void tokensAreJudgedBeforeTheKey() {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap().set(WebSocketUpgradeGate.KEY_HEADER, "short");

            assertEquals(refused(426, upgradeHeaders()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the version is judged before the key: an unsupported version with a bad key is refused 426")
        void versionIsJudgedBeforeTheKey() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.VERSION_HEADER, "8")
                    .set(WebSocketUpgradeGate.KEY_HEADER, "short");

            assertEquals(426, assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                    judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers)).status());
        }

        @Test
        @DisplayName("the key is judged before the declared length: a bad key with an over-bound body is refused 400, still retiring the connection")
        void keyIsJudgedBeforeTheDeclaredLength() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.KEY_HEADER, "short")
                    .set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, OVER_BOUND_LENGTH);

            assertEquals(retiring(400, Map.of()), judge(HttpMethod.GET, HttpVersion.HTTP_1_1, headers));
        }

        @Test
        @DisplayName("the declared length is judged before the request URI: both broken is refused 413")
        void declaredLengthIsJudgedBeforeTheRequestUri() {
            MultiMap headers = validHandshake().set(WebSocketUpgradeGate.CONTENT_LENGTH_HEADER, "8193");

            assertEquals(CONTENT_TOO_LARGE,
                    WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_1_1, "/ws/room?a|b", headers));
        }

        @Test
        @DisplayName("the version is judged before the request URI: an HTTP/2 request with an unparseable URI is refused 426")
        void versionIsJudgedBeforeTheRequestUri() {
            WebSocketUpgradeGate.Verdict verdict = WebSocketUpgradeGate.judge(HttpMethod.GET, HttpVersion.HTTP_2,
                    "/ws/room?a|b", validHandshake());

            assertEquals(refused(426, Map.of()), verdict);
        }
    }

    @Test
    @DisplayName("a refusal's headers cannot be changed after the verdict was made")
    void refusalHeadersAreUnmodifiable() {
        WebSocketUpgradeGate.Verdict.Refused refused = assertInstanceOf(WebSocketUpgradeGate.Verdict.Refused.class,
                judge(HttpMethod.GET, HttpVersion.HTTP_1_1, MultiMap.caseInsensitiveMultiMap()));
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

    /** Judges a handshake sent to the valid request URI {@link #REQUEST_URI}. */
    private static WebSocketUpgradeGate.Verdict judge(HttpMethod method, HttpVersion version, MultiMap headers) {
        return WebSocketUpgradeGate.judge(method, version, REQUEST_URI, headers);
    }

    /** A refusal that leaves the connection open, as every refusal of a request declaring no over-bound body does. */
    private static WebSocketUpgradeGate.Verdict refused(int status, Map<String, String> headers) {
        return new WebSocketUpgradeGate.Verdict.Refused(status, headers, false);
    }

    /**
     * A refusal of an HTTP/1.x request declaring an over-bound body: its own {@code headers}, which carry
     * no {@code Connection} value, plus {@code Connection: close}, and the connection retired.
     */
    private static WebSocketUpgradeGate.Verdict retiring(int status, Map<String, String> headers) {
        Map<String, String> retiringHeaders = new LinkedHashMap<>(headers);
        retiringHeaders.put(WebSocketUpgradeGate.CONNECTION_HEADER, "close");
        return new WebSocketUpgradeGate.Verdict.Refused(status, retiringHeaders, true);
    }
}
