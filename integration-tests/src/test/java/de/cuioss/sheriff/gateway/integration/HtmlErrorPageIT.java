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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Locale;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the negotiated HTML error pages against the <strong>native image</strong> on the primary
 * instance (10443), whose {@code sheriff-config/gateway.yaml} declares {@code portal.error_pages: true}.
 * <p>
 * <strong>The contract.</strong> For every reachable gateway-originated error class below, the SAME
 * request is sent twice, differing only in {@code Accept}:
 * <ul>
 *   <li>{@code Accept: text/html} — a browser navigation — is answered with the portal's HTML error
 *       page: {@code text/html}, the fixed portal {@code Content-Security-Policy},
 *       {@code Cache-Control: no-store}, {@code nosniff}, and the operator template's marker;</li>
 *   <li>{@code Accept: application/json} keeps the exit's current shape —
 *       {@code application/problem+json} where the exit renders one — and never the page.</li>
 * </ul>
 * The status is identical on both legs: negotiation changes the body, never the status. The error
 * classes exercised are the ones the IT stack can reach: an unrouted address ({@code 404}), a body
 * over the route cap ({@code 413}), an upstream the gateway cannot dial ({@code 5xx}), a directory-asset
 * miss ({@code 404}), a bearer token missing the endpoint scope ({@code 403}) and a failed OIDC callback.
 * <p>
 * <strong>The two controls.</strong> A wildcard {@code Accept: *}{@code /*} — what {@code fetch()},
 * XHR and REST clients send — never qualifies for HTML; and a response <em>relayed from an origin</em>
 * with {@code Accept: text/html} passes through unchanged, because the gateway never replaces what an
 * upstream said.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class HtmlErrorPageIT extends BaseIntegrationTest {

    /** A browser navigation's {@code Accept}, listing {@code text/html} explicitly. */
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    /** A JSON client's {@code Accept}. */
    private static final String JSON_ACCEPT = "application/json";

    /** The RFC 9457 media type of a gateway rejection. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** An address no anchor prefix and no reserved path covers. */
    private static final String UNROUTED_PATH = "/no-such-route/resource";

    /** The {@code httpbin-minimal-mode} route, whose {@code max_body_bytes} is {@value #MINIMAL_ROUTE_BODY_CAP}. */
    private static final String BODY_CAPPED_PATH = "/proxy/minimal-mode/echo";

    /** The declared {@code max_body_bytes} of {@link #BODY_CAPPED_PATH}. */
    private static final int MINIMAL_ROUTE_BODY_CAP = 1024;

    /**
     * The {@code untrusted-chain-control} route: its upstream presents a self-signed certificate no
     * truststore of this instance anchors, so the dial is refused and the gateway originates the error.
     */
    private static final String UNREACHABLE_UPSTREAM_PATH = "/proxy/untrusted";

    /** A file the {@code assets-directory} route's root does not hold. */
    private static final String MISSING_DIRECTORY_ASSET = "/assets/static/does-not-exist.css";

    /** The {@code secure-scoped} route, which needs a scope the token below does not carry. */
    private static final String SCOPED_BEARER_PATH = "/secure/scoped/get";

    /** The reserved OIDC callback, reached without the browser-binding cookie a real login sets. */
    private static final String CALLBACK_PATH = "/auth/callback";

    /**
     * The {@code origin-status} route, forwarding to go-httpbin's {@code /status/503} through the
     * base-path-free {@code HTTPBIN_ROOT} alias — so the origin itself answers 503, an origin-relayed
     * error. (The {@code /proxy} prefix route's {@code UPSTREAM} alias carries the {@code /anything}
     * echo base and would answer 200.)
     */
    private static final String RELAYED_ORIGIN_ERROR_PATH = "/proxy/origin-status/503";

    /** The {@code api} anchor's own policy, which a relayed {@code /proxy} response carries. */
    private static final String API_ANCHOR_CSP = "default-src 'self'";

    @Test
    @DisplayName("an unrouted address: HTML for a navigation, problem+json for JSON, same 404")
    void unroutedAddress() {
        assertNegotiated(404, true, spec -> spec.when().get(UNROUTED_PATH));
    }

    @Test
    @DisplayName("control: a wildcard Accept never qualifies — an XHR keeps problem+json")
    void wildcardAcceptKeepsProblemJson() {
        ExtractableResponse<Response> response = given()
                .header("Accept", "*/*")
                .when()
                .get(UNROUTED_PATH)
                .then()
                .extract();

        assertCurrentShape(response, 404, true);
    }

    @Test
    @DisplayName("a body over the route cap: HTML for a navigation, problem+json for JSON, same 413")
    void bodyOverRouteCap() {
        String overCapBody = "x".repeat(MINIMAL_ROUTE_BODY_CAP + 1);

        assertNegotiated(413, true,
                spec -> spec.contentType("text/plain").body(overCapBody).when().post(BODY_CAPPED_PATH));
    }

    @Test
    @DisplayName("an upstream the gateway cannot dial: HTML for a navigation, problem+json for JSON, same 5xx")
    void unreachableUpstream() {
        ExtractableResponse<Response> json = send(JSON_ACCEPT, spec -> spec.when().get(UNREACHABLE_UPSTREAM_PATH));
        int status = json.statusCode();
        assertTrue(status == 502 || status == 503 || status == 504,
                "the refused dial must be a gateway-originated 502/503/504; was " + status + ", body: "
                        + json.asString());

        assertNegotiated(status, true, spec -> spec.when().get(UNREACHABLE_UPSTREAM_PATH));
    }

    @Test
    @DisplayName("a directory-asset miss: HTML for a navigation, the current shape for JSON, same 404")
    void directoryAssetMiss() {
        assertNegotiated(404, false, spec -> spec.when().get(MISSING_DIRECTORY_ASSET));
    }

    @Test
    @DisplayName("a bearer token missing the endpoint scope: HTML for a navigation, problem+json for JSON, same 403")
    void bearerScopeMissing() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE);
        assertFalse(BffEndpointScopesIT.grantedScopes(token).contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: the token must lack the endpoint scope, otherwise no 403 is provoked");

        assertNegotiated(403, true,
                spec -> spec.header("Authorization", "Bearer " + token).when().get(SCOPED_BEARER_PATH));
    }

    @Test
    @DisplayName("a failed OIDC callback: HTML for a navigation, the current shape for JSON, same status")
    void failedCallback() {
        ExtractableResponse<Response> json = send(JSON_ACCEPT, HtmlErrorPageIT::bogusCallback);
        int status = json.statusCode();
        assertTrue(status == 400 || status == 403,
                "a callback without the browser-binding cookie must be rejected 400/403; was " + status);
        assertNull(json.header("Location"), "a failed callback redirects nowhere");

        assertNegotiated(status, false, HtmlErrorPageIT::bogusCallback);
    }

    @Test
    @DisplayName("control: an error relayed from the origin passes through unchanged, even to a navigation")
    void relayedOriginErrorPassesThrough() {
        ExtractableResponse<Response> relayed = send(BROWSER_ACCEPT,
                spec -> spec.when().get(RELAYED_ORIGIN_ERROR_PATH));

        assertAll("relayed origin error",
                () -> assertEquals(503, relayed.statusCode(), "the origin's status is relayed verbatim"),
                () -> assertFalse(relayed.asString().contains(PortalIT.OPERATOR_TEMPLATE_MARKER),
                        "the gateway must never replace an origin's body with the portal page"),
                () -> assertEquals(API_ANCHOR_CSP, relayed.header("Content-Security-Policy"),
                        "a relayed /proxy response carries the api anchor's policy, not the portal's"),
                () -> assertNotEquals("no-store", relayed.header("Cache-Control"),
                        "the error page's no-store must not be imposed on a relayed response"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Sends the request twice — as a navigation and as a JSON client — and asserts the HTML page on
     * the first, the current shape on the second, and the identical {@code status} on both.
     *
     * @param status           the status both legs must answer
     * @param problemJson      whether the exit's current shape is {@code application/problem+json}
     * @param request          completes a specification into the request under test
     */
    private static void assertNegotiated(int status, boolean problemJson,
            Function<RequestSpecification, Response> request) {
        ExtractableResponse<Response> html = send(BROWSER_ACCEPT, request);
        ExtractableResponse<Response> json = send(JSON_ACCEPT, request);

        assertHtmlErrorPage(html, status);
        assertCurrentShape(json, status, problemJson);
    }

    private static ExtractableResponse<Response> send(String accept, Function<RequestSpecification, Response> request) {
        RequestSpecification spec = given()
                .header("Accept", accept)
                .redirects().follow(false);
        return request.apply(spec).then().extract();
    }

    private static void assertHtmlErrorPage(ExtractableResponse<Response> response, int status) {
        String body = response.asString();
        assertAll("HTML error page for status " + status,
                () -> assertEquals(status, response.statusCode(), "the status never changes; body: " + body),
                () -> assertTrue(contentType(response).startsWith("text/html"),
                        "a navigation gets HTML; content type was " + response.contentType()),
                () -> assertEquals(PortalIT.PORTAL_CSP, response.header("Content-Security-Policy"),
                        "the HTML error page carries the fixed portal policy verbatim"),
                () -> assertEquals("no-store", response.header("Cache-Control"), "an error page is never stored"),
                () -> assertEquals("nosniff", response.header("X-Content-Type-Options")),
                () -> assertTrue(body.contains(PortalIT.OPERATOR_TEMPLATE_MARKER),
                        "the page renders through the operator template; body: " + body),
                () -> assertTrue(body.contains("data-status=\"" + status + "\""),
                        "the page names the preserved status in its error block; body: " + body));
    }

    private static void assertCurrentShape(ExtractableResponse<Response> response, int status, boolean problemJson) {
        String body = response.asString();
        assertAll("current shape for status " + status,
                () -> assertEquals(status, response.statusCode(), "the status never changes; body: " + body),
                () -> assertFalse(contentType(response).startsWith("text/html"),
                        "a non-navigation never gets the HTML page; content type was " + response.contentType()),
                () -> assertFalse(body.contains(PortalIT.OPERATOR_TEMPLATE_MARKER),
                        "a non-navigation never gets the portal page"),
                () -> {
                    if (problemJson) {
                        assertTrue(contentType(response).startsWith(PROBLEM_JSON),
                                "this exit renders RFC 9457 problem+json; content type was " + response.contentType());
                    }
                });
    }

    private static String contentType(ExtractableResponse<Response> response) {
        String contentType = response.contentType();
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    }

    private static Response bogusCallback(RequestSpecification spec) {
        return spec.queryParam("code", "bogus-code").queryParam("state", "bogus-state")
                .when().get(CALLBACK_PATH);
    }
}
