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
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The native-image leg of the raw-query hand-off (ADR-0047, AS-13): the strict query acceptance and
 * rejection matrix, driven over the public HTTPS edge against the go-httpbin echo upstream.
 * <p>
 * The property under test is <strong>validated equals forwarded</strong>. The per-route filter judges
 * the query in its raw, still-percent-encoded wire form, and the upstream receives exactly those
 * bytes. Every request is sent with URL encoding disabled, so the gateway sees precisely the spelling
 * written here, and every accepted case asserts that go-httpbin's {@code url} echo ends with that same
 * spelling — never decoded, never re-encoded. Every rejected case asserts the gateway's RFC 9457
 * problem document and the absence of the go-httpbin {@code method} echo, which is the observable
 * proof that the refused request never reached the upstream.
 * <p>
 * <strong>Route.</strong> {@value #STRICT_ECHO_ROUTE} is the {@code httpbin-forward-all} route of
 * {@code endpoints/httpbin.yaml}: it declares no {@code security_filter.profile}, so it resolves the
 * gateway-wide {@code security_defaults.profile: strict}, and it declares no {@code forward} block, so
 * every query parameter crosses. The {@code /proxy} route itself cannot carry this matrix: its
 * {@code query_allow} positive-list would drop the {@code q}, {@code t} and {@code r} parameters
 * before the echo could show them.
 * <p>
 * <strong>Mirror contract.</strong> Each case list below is a case-for-case copy of the matching
 * {@code @ValueSource} in the api-sheriff unit test {@code GatewayEdgeQueryHandoffTest}, which drives
 * the same matrix over a live Vert.x edge. The two modules share no test sources, so the lists are
 * duplicated deliberately; a case added to or removed from one leg must be mirrored in the other, or
 * the native image and the JVM edge stop proving the same contract. The {@code minimal}-mode leg of
 * the matrix lives in {@link SecurityProfileModeIT}, whose {@code /proxy/minimal-mode} route is the
 * only {@code minimal} route of this stack.
 */
@DisplayName("Strict query acceptance matrix — the raw query is validated and forwarded as one form")
class StrictQueryAcceptanceIT extends BaseIntegrationTest {

    /** The strict, forward-all echo route; go-httpbin reaches it as {@code /anything/forward-all}. */
    private static final String STRICT_ECHO_ROUTE = "/proxy/forward-all";

    /** The upstream path go-httpbin reports in its {@code url} echo for {@link #STRICT_ECHO_ROUTE}. */
    private static final String UPSTREAM_PATH = "/anything/forward-all";

    /**
     * The strict preset's header-value cap (1024 characters, see {@code sheriff-config/gateway.yaml});
     * the header leg sends one character more.
     */
    private static final int STRICT_HEADER_VALUE_CAP = 1024;

    /**
     * Accepted and forwarded still encoded. Mirrors {@code strictAcceptsAndForwardsVerbatim}.
     *
     * @return the raw query strings strict must admit and forward byte for byte
     */
    static Stream<String> acceptedQueries() {
        return Stream.of(
                "q=Max%20M%C3%BCller",
                "q=2026-09-15T10%3A00%3A00Z",
                "q=a%2Fb",
                "q=%5B%22a%22%5D",
                "t=2026-09-15T10:00:00Z",
                "r=/a/b",
                "q=100%25",
                "q=a+b");
    }

    /**
     * Values decoding to NUL, a control character, a line break or a double encoding. Mirrors
     * {@code strictRejectsDangerousValues}.
     *
     * @return the raw query strings strict must refuse
     */
    static Stream<String> rejectedValueQueries() {
        return Stream.of(
                "q=%00",
                "q=%01",
                "q=a%0D%0Ab",
                "q=%0A",
                "q=%252F");
    }

    /**
     * Parameter names decoding to a pair delimiter. Mirrors {@code strictRejectsDecodedDelimiterInName}.
     *
     * @return the raw query strings whose name the parameter-name pipeline must refuse
     */
    static Stream<String> rejectedNameQueries() {
        return Stream.of("a%3Db=1", "a%26b=1");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("acceptedQueries")
    @DisplayName("strict accepts a legitimate query and forwards it still encoded, byte for byte")
    void strictAcceptsAndForwardsVerbatim(String query) {
        // Act
        var response = getStrict(query, 200);

        // Assert
        assertAll(query,
                () -> assertEquals("GET", response.path("method"), "the request reached the echo upstream"),
                () -> assertTrue(response.path("url").toString().endsWith(UPSTREAM_PATH + "?" + query),
                        "the upstream must receive the validated raw query unchanged — never decoded or re-encoded;"
                                + " echoed url: " + response.path("url")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejectedValueQueries")
    @DisplayName("strict rejects a value that decodes to NUL, a control character, a line break or a double encoding")
    void strictRejectsDangerousValues(String query) {
        assertRejectedAtGateway(query);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejectedNameQueries")
    @DisplayName("strict rejects a parameter name that decodes to a pair delimiter")
    void strictRejectsDecodedDelimiterInName(String query) {
        assertRejectedAtGateway(query);
    }

    @Test
    @DisplayName("strict rejects a value the injection patterns read as a protocol scheme (known false positive)")
    void strictRejectsProtocolSchemeFalsePositive() {
        // "data: x" is plain prose, but decoded it matches the data: scheme pattern. Pinned as rejected
        // so a change in that verdict is a deliberate, visible decision rather than a drift.
        assertRejectedAtGateway("q=data%3A%20x");
    }

    @Test
    @DisplayName("the upstream receives the raw value and decodes it itself — the gateway never decodes on its behalf")
    void upstreamDecodesTheRawValueItself() {
        // Arrange — the regression guard for a future convenience decode at the edge (ADR-0047 Risks)
        String rawValue = "Max%20M%C3%BCller";

        // Act
        var response = getStrict("q=" + rawValue, 200);

        // Assert
        assertAll(
                () -> assertTrue(response.path("url").toString().endsWith(UPSTREAM_PATH + "?q=" + rawValue),
                        "the forwarded request-target carries the raw value; echoed url: " + response.path("url")),
                () -> assertEquals("Max Müller", response.path("args.q[0]"),
                        "control: go-httpbin decodes the raw value exactly once, so the gateway neither decoded"
                                + " nor re-encoded it"));
    }

    @Test
    @DisplayName("strict forwards a raw ';' as %3B, so the upstream sees no separate 'token' (CWE-235)")
    void strictEncodesSemicolonInValue() {
        // Arrange — mirrors strictEncodesSemicolonInValue in GatewayEdgeQueryHandoffTest: the edge
        // splits on '&' only, so this is ONE pair 'x' whose value is '1;token=abc'

        // Act
        var response = getStrict("x=1;token=abc", 200);

        // Assert
        assertAll(
                () -> assertTrue(response.path("url").toString().endsWith(UPSTREAM_PATH + "?x=1%3Btoken=abc"),
                        "the ';' crosses as %3B, the single exception to verbatim forwarding; echoed url: "
                                + response.path("url")),
                () -> assertEquals("1;token=abc", response.path("args.x[0]"),
                        "the upstream decodes one pair 'x' carrying the ';' as data"),
                () -> assertNull(response.path("args.token"), "no smuggled 'token' parameter reaches the upstream"));
    }

    @Test
    @DisplayName("header validation still rejects a header value over the strict cap")
    void headerValidationStillRejectsOversizedValue() {
        // Act
        var response = given()
                .urlEncodingEnabled(false)
                .header("X-Custom", "a".repeat(STRICT_HEADER_VALUE_CAP + 1))
                .when()
                .get(STRICT_ECHO_ROUTE + "?q=ok")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .extract();

        // Assert
        assertNull(response.path("method"), "header-value validation is untouched by the query hand-off");
    }

    private static ExtractableResponse<Response> getStrict(String query, int expectedStatus) {
        return given()
                .urlEncodingEnabled(false)
                .when()
                .get(STRICT_ECHO_ROUTE + "?" + query)
                .then()
                .statusCode(expectedStatus)
                .extract();
    }

    private static void assertRejectedAtGateway(String query) {
        // Act
        var response = given()
                .urlEncodingEnabled(false)
                .when()
                .get(STRICT_ECHO_ROUTE + "?" + query)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .extract();

        // Assert
        assertAll(query,
                () -> assertEquals(Integer.valueOf(400), response.path("status"),
                        "the gateway renders the rejection as its own problem document"),
                () -> assertNull(response.path("method"),
                        "a refused query must never reach the go-httpbin upstream"));
    }
}
