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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.restassured.http.Header;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the RFC 9457 {@code application/problem+json} error contract the edge renders for
 * rejected requests, that every rejection is terminated at the gateway without reaching the upstream,
 * and that no gateway-originated error discloses what it runs on.
 * <p>
 * Each rejection carries the failing category's {@code urn:api-sheriff:problem:*} type, its title,
 * and the HTTP status. A forwarded request would carry the {@code go-httpbin} echo (a non-null
 * {@code method}); its absence on every rejection is the observable proof the upstream count stayed
 * {@code 0}.
 * <p>
 * A rejection raised after route selection and before the trust boundary — the verb gate, a missing
 * bearer — is answered to the untrusted caller as the unrouted {@code 404} routing problem on the
 * shipped default (ADR-0073), so the document does not disclose that the route exists. Its TRUE event
 * stays in {@code sheriff_errors_total}, which is what still tells the two causes apart.
 * <p>
 * <strong>No identity, no version, one shape.</strong> Every error answer exercised here — the
 * unrouted {@code 404}, the same {@code 404} a disallowed method and a missing bearer receive, and
 * one gateway-originated {@code 5xx} — carries no {@code Server} and no {@code X-Powered-By} header,
 * names no framework or product version in a header value or in the body, and renders the same
 * problem member-name set under {@code application/problem+json}. A fingerprinting client therefore
 * learns nothing about the runtime from the gateway's own errors.
 * <p>
 * <strong>The 5xx.</strong> The {@code untrusted-chain-control} route ({@code /proxy/untrusted}) dials
 * an upstream whose self-signed certificate no truststore of the primary instance anchors, so the
 * gateway originates the error. Its breaker counts the refusals of every suite that requests the
 * route, {@code HtmlErrorPageIT} included, so the answer is either the gateway's {@code 502} or the
 * open circuit identified by its full signature — {@code 503}, {@code Retry-After: 5} and the
 * upstream problem type. A bare {@code 503} fails. {@code /proxy/fault} is deliberately not used: its
 * Toxiproxy fixture belongs to {@code HtmlErrorPageIT}.
 */
class ErrorContractIT extends BaseIntegrationTest {

    /** The RFC 9457 media type of a gateway rejection. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** The {@code untrusted-chain-control} route, whose upstream dial is always refused. */
    private static final String REFUSED_DIAL_PATH = "/proxy/untrusted";

    /** The RFC 9457 problem type of an upstream failure. */
    private static final String UPSTREAM_PROBLEM_TYPE = "urn:api-sheriff:problem:upstream";

    /** The {@code Retry-After} value of an open circuit: the seconds the breaker stays open. */
    private static final String BREAKER_OPEN_SECONDS = "5";

    /**
     * A product or framework name that betrays the runtime, matched case-insensitively in header values
     * and in the body. {@code API-Sheriff/} and {@code api-sheriff/} are the product-token spellings;
     * the {@code urn:api-sheriff:problem:*} type URIs carry a colon, not a slash, and stay legal.
     */
    private static final Pattern RUNTIME_NAME = Pattern.compile("quarkus|vert\\.x|netty|api-sheriff/",
            Pattern.CASE_INSENSITIVE);

    /**
     * A bare dotted version token such as {@code 3.40.1}, matched in header values only: a problem body
     * carries no number with a dot in it, while a header value is where a {@code Server}-style product
     * version would land.
     */
    private static final Pattern DOTTED_VERSION = Pattern.compile("\\b\\d+(?:\\.\\d+)+\\b");

    /** The problem member names every gateway-originated error renders. */
    private static final Set<String> PROBLEM_MEMBERS = Set.of("type", "title", "status");

    @Test
    @DisplayName("an unmatched path is denied 404 as a routing problem, not forwarded")
    void unmatchedPathProblemJson() {
        var response = unmatchedPath();

        assertTrue(response.path("type").toString().contains("urn:api-sheriff:problem:routing"));
        assertEquals("Routing", response.path("title"));
        assertEquals(Integer.valueOf(404), response.path("status"));
        assertNull(response.path("method"), "a denied request must not reach the go-httpbin upstream");
        assertDisclosesNothing(response);
    }

    @Test
    @DisplayName("a disallowed method is refused as the unrouted 404 routing problem, metered METHOD_NOT_ALLOWED, not forwarded")
    void disallowedMethodProblemJson() {
        var response = UniformNotFoundAssertions.assertTrueEventMetered("httpbin-proxy", "METHOD_NOT_ALLOWED",
                ErrorContractIT::disallowedMethod);

        UniformNotFoundAssertions.assertUnroutedProblem(response, "a disallowed method");
        assertEquals(Integer.valueOf(404), response.path("status"));
        assertDisclosesNothing(response);
    }

    @Test
    @DisplayName("a bearer rejection is refused as the unrouted 404 routing problem, metered TOKEN_MISSING, not forwarded")
    void bearerRejectionProblemJson() {
        var response = UniformNotFoundAssertions.assertTrueEventMetered("secure-proxy", "TOKEN_MISSING",
                ErrorContractIT::bearerRejection);

        UniformNotFoundAssertions.assertUnroutedProblem(response, "a bearer rejection");
        assertEquals(Integer.valueOf(404), response.path("status"));
        assertDisclosesNothing(response);
    }

    @Test
    @DisplayName("a refused upstream dial is a gateway-originated 502, or the identified open circuit, and discloses nothing")
    void refusedDialProblemJson() {
        var response = refusedDial();

        if (response.statusCode() == 503) {
            assertAll("only the open circuit's 503 is tolerated, identified by its full signature",
                    () -> assertEquals(BREAKER_OPEN_SECONDS, response.header("Retry-After"),
                            "an open circuit names the seconds the breaker stays open; body: " + response.asString()),
                    () -> assertEquals(UPSTREAM_PROBLEM_TYPE, response.path("type"),
                            "an open circuit carries the upstream problem type; body: " + response.asString()));
        } else {
            assertEquals(502, response.statusCode(), () -> REFUSED_DIAL_PATH + " must answer the gateway's own 502"
                    + " for a refused dial, or an identified open circuit; body: " + response.asString());
        }
        assertNull(response.path("method"), "a refused dial never reached the upstream");
        assertDisclosesNothing(response);
    }

    @Test
    @DisplayName("the 4xx and 5xx problem documents share one member-name set under application/problem+json")
    void errorClassesShareOneProblemShape() {
        var notFound = unmatchedPath();
        var disallowed = disallowedMethod();
        var missingBearer = bearerRejection();
        var upstreamFailure = refusedDial();

        assertAll("one shape for every gateway-originated error class",
                () -> assertEquals(PROBLEM_MEMBERS, memberNames(notFound), "404 routing"),
                () -> assertEquals(PROBLEM_MEMBERS, memberNames(disallowed), "uniform 404, disallowed method"),
                () -> assertEquals(PROBLEM_MEMBERS, memberNames(missingBearer), "uniform 404, missing bearer"),
                () -> assertEquals(PROBLEM_MEMBERS, memberNames(upstreamFailure),
                        () -> upstreamFailure.statusCode() + " upstream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Quarkus", "quarkus-vertx-http", "Vert.x", "vertx/Vert.x 4", "Netty", "netty/4.1.118.Final",
            "API-Sheriff/0.2.0", "api-sheriff/1.0"})
    @DisplayName("positive control: the runtime-name pattern matches every name it exists to catch")
    void runtimeNamePatternMatchesKnownSamples(String sample) {
        assertTrue(RUNTIME_NAME.matcher(sample).find(),
                () -> "the pattern must match '" + sample + "', otherwise a leak of it would pass vacuously");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.40.1", "Quarkus/3.40.1", "4.1", "netty/4.1.118.Final"})
    @DisplayName("positive control: the dotted-version pattern matches a version token in a header value")
    void dottedVersionPatternMatchesKnownSamples(String sample) {
        assertTrue(DOTTED_VERSION.matcher(sample).find(),
                () -> "the pattern must match '" + sample + "', otherwise a leak of it would pass vacuously");
    }

    @Test
    @DisplayName("negative control: the patterns leave the gateway's own type URI and an HSTS value alone")
    void patternsLeaveLegalValuesAlone() {
        assertAll("values the gateway legitimately emits",
                () -> assertFalse(RUNTIME_NAME.matcher("urn:api-sheriff:problem:routing").find(),
                        "the problem type URI names the product with a colon, never as a product token"),
                () -> assertFalse(DOTTED_VERSION.matcher("max-age=31536000; includeSubDomains").find(),
                        "an HSTS value carries no dotted version"),
                () -> assertFalse(DOTTED_VERSION.matcher("5").find(), "a Retry-After delay is no version"));
    }

    // ---------------------------------------------------------------- requests

    private static ExtractableResponse<Response> unmatchedPath() {
        return given()
                .when()
                .get("/no-such-route/resource")
                .then()
                .statusCode(404)
                .contentType(PROBLEM_JSON)
                .extract();
    }

    private static ExtractableResponse<Response> disallowedMethod() {
        return given()
                .when()
                .patch("/proxy/get")
                .then()
                .statusCode(404)
                .contentType(PROBLEM_JSON)
                .extract();
    }

    private static ExtractableResponse<Response> bearerRejection() {
        return given()
                .when()
                .get("/secure/get")
                .then()
                .statusCode(404)
                .contentType(PROBLEM_JSON)
                .extract();
    }

    private static ExtractableResponse<Response> refusedDial() {
        return given()
                .header("Accept", "application/json")
                .when()
                .get(REFUSED_DIAL_PATH)
                .then()
                .contentType(PROBLEM_JSON)
                .extract();
    }

    // ---------------------------------------------------------------- assertions

    /**
     * Asserts that a gateway-originated error names nothing of the runtime: no {@code Server} and no
     * {@code X-Powered-By} header, no {@link #RUNTIME_NAME} and no {@link #DOTTED_VERSION} in any
     * header value, and no {@link #RUNTIME_NAME} in the body.
     */
    private static void assertDisclosesNothing(ExtractableResponse<Response> response) {
        String body = response.asString();
        assertAll("status " + response.statusCode() + " discloses no server identity and no version",
                () -> assertNull(response.header("Server"), "no Server header on a gateway-originated error"),
                () -> assertNull(response.header("X-Powered-By"), "no X-Powered-By header on a gateway-originated error"),
                () -> {
                    for (Header header : response.headers()) {
                        String value = header.getValue();
                        assertFalse(RUNTIME_NAME.matcher(value).find(),
                                () -> header.getName() + " names the runtime: " + value);
                        assertFalse(DOTTED_VERSION.matcher(value).find(),
                                () -> header.getName() + " carries a version token: " + value);
                    }
                },
                () -> assertFalse(RUNTIME_NAME.matcher(body).find(), () -> "the body names the runtime: " + body));
    }

    /**
     * The top-level member names of a problem document. The media type is already asserted by the
     * request helper that produced {@code response}.
     */
    private static Set<String> memberNames(ExtractableResponse<Response> response) {
        return new TreeSet<>(response.jsonPath().getMap("$").keySet().stream().map(String::valueOf).toList());
    }
}
