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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

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
 * over the route cap ({@code 413}), an upstream the gateway cannot dial ({@code 502}), an upstream that
 * does not answer in time ({@code 504}), an open circuit ({@code 503}), a directory-asset miss
 * ({@code 404}), a bearer token missing the endpoint scope ({@code 403}) and a failed OIDC callback.
 * <p>
 * <strong>The two upstream-fault tests.</strong> {@code 504} and {@code 503} are answers the gateway
 * gives about an upstream that misbehaves, so both tests misbehave one: the {@code upstream-fault}
 * route dials a Toxiproxy listen port, and each test creates the proxy behind it through Toxiproxy's
 * admin API, in front of the echo backend, and deletes it again. Each first drives the route through
 * the healthy proxy until it has answered {@code 200} as often as the circuit breaker's window is long,
 * so neither starts on failures the other, or an earlier run, left in that window, and neither depends
 * on the order the two run in. What the guard around an upstream is — a 30-second timeout and a breaker
 * that opens on failures and stays open for five seconds — is fixed in the gateway, not declared by the
 * route; {@code endpoints/upstream-fault.yaml} records it.
 * <p>
 * <strong>The open circuit's {@code Retry-After}.</strong> The {@code 503} of an open circuit carries
 * {@code Retry-After: 5} — the seconds the breaker stays open — on the HTML page and on the problem
 * document alike; the open-circuit test asserts it on both legs.
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

    /**
     * The {@code upstream-fault} route. Its upstream is the Toxiproxy listen port the
     * {@code FAULT_UPSTREAM} alias names, behind which {@link FaultProxy} places the echo backend.
     */
    private static final String FAULT_PATH = "/proxy/fault";

    /**
     * How many consecutive {@code 200} answers make the route healthy for a test: the length of the
     * circuit breaker's rolling window, so that window then holds no failure from before the test.
     */
    private static final int BREAKER_WINDOW = 20;

    /**
     * How many fast failures the open-circuit test sends at most before the circuit must have opened.
     * The breaker opens once half of its window has failed; twice the window is a bound, not the count.
     */
    private static final int FAST_FAILURE_LIMIT = 2 * BREAKER_WINDOW;

    /** The response header on which an open circuit's {@code 503} says when to try again. */
    private static final String RETRY_AFTER = "Retry-After";

    /**
     * The {@code Retry-After} value of an open circuit: the five seconds the breaker stays open, as the
     * error contract in {@code doc/architecture.adoc} states it.
     */
    private static final String BREAKER_OPEN_SECONDS = "5";

    /**
     * How long the route may take to become healthy. It covers the five seconds an open circuit stays
     * open and the trial requests that close it.
     */
    private static final Duration HEALTHY_TIMEOUT = Duration.ofSeconds(60);

    private static final long HEALTHY_POLL_INTERVAL_MILLIS = 250L;

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
    @DisplayName("an upstream the gateway cannot dial: HTML for a navigation, problem+json for JSON, same 502")
    void unreachableUpstream() {
        ExtractableResponse<Response> json = send(JSON_ACCEPT, spec -> spec.when().get(UNREACHABLE_UPSTREAM_PATH));
        assertEquals(502, json.statusCode(),
                () -> "the refused dial must be the gateway's own 502; body: " + json.asString());

        assertNegotiated(502, true, spec -> spec.when().get(UNREACHABLE_UPSTREAM_PATH));
    }

    @Test
    @DisplayName("an upstream that does not answer in time: HTML for a navigation, problem+json for JSON, same 504")
    void upstreamTimeout() {
        try (FaultProxy proxy = FaultProxy.create()) {
            awaitHealthyFaultRoute();
            proxy.holdAnswersBack();

            // A POST with a body, because the gateway never re-sends one: a bodyless GET would be
            // attempted three times, and each leg would take three timeouts instead of one.
            assertNegotiated(504, true,
                    spec -> spec.contentType("text/plain").body("upstream-timeout").when().post(FAULT_PATH));
        }
    }

    @Test
    @DisplayName("an open circuit: HTML for a navigation, problem+json for JSON, same 503 with Retry-After, and the upstream is not dialled")
    void openCircuit() {
        try (FaultProxy proxy = FaultProxy.create()) {
            awaitHealthyFaultRoute();
            proxy.setEnabled(false);

            int failedFast = 0;
            int status = faultRouteStatus();
            while (status == 502 && failedFast < FAST_FAILURE_LIMIT) {
                failedFast++;
                status = faultRouteStatus();
            }
            int firstOther = status;
            int countedFailures = failedFast;
            assertAll("requests through the disabled proxy",
                    () -> assertTrue(countedFailures > 0, "the first requests must fail fast with the gateway's "
                            + "own 502, the failures that open the circuit; the first answer was " + firstOther),
                    () -> assertEquals(503, firstOther, () -> "after " + countedFailures + " fast failures the "
                            + "circuit must open and the next request be answered 503"));

            // The upstream is reachable again from here on. A request the gateway dialled would be
            // answered 200, so a 503 below is the open circuit and nothing else.
            proxy.setEnabled(true);
            ExtractableResponse<Response> html = send(BROWSER_ACCEPT, spec -> spec.when().get(FAULT_PATH));
            ExtractableResponse<Response> json = send(JSON_ACCEPT, spec -> spec.when().get(FAULT_PATH));

            assertHtmlErrorPage(html, 503);
            assertCurrentShape(json, 503, true);
            assertAll("Retry-After of the open circuit",
                    () -> assertEquals(BREAKER_OPEN_SECONDS, html.header(RETRY_AFTER),
                            "the HTML error page names the seconds the breaker stays open"),
                    () -> assertEquals(BREAKER_OPEN_SECONDS, json.header(RETRY_AFTER),
                            "the problem document names the seconds the breaker stays open"));

            // Closing control: the circuit closes again on its own, so the 503 was the breaker's.
            awaitHealthyFaultRoute();
        }
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
                () -> assertFalse(hasCacheDirective(relayed.header("Cache-Control"), "no-store"),
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

    /**
     * Whether a {@code Cache-Control} value carries the given directive in any position, so a
     * multi-directive value such as {@code private, no-store} is recognised as well as a bare one.
     *
     * @param header    the {@code Cache-Control} value, {@code null} when the header is absent
     * @param directive the directive name to look for, matched case-insensitively
     * @return {@code false} for an absent header, otherwise whether any comma-separated directive
     *         equals {@code directive}
     */
    private static boolean hasCacheDirective(String header, String directive) {
        if (header == null) {
            return false;
        }
        for (String candidate : header.split(",")) {
            if (candidate.strip().equalsIgnoreCase(directive)) {
                return true;
            }
        }
        return false;
    }

    private static String contentType(ExtractableResponse<Response> response) {
        String contentType = response.contentType();
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    }

    private static Response bogusCallback(RequestSpecification spec) {
        return spec.queryParam("code", "bogus-code").queryParam("state", "bogus-state")
                .when().get(CALLBACK_PATH);
    }

    /** One bodyless request on the fault route, as a JSON client; the status it is answered with. */
    private static int faultRouteStatus() {
        return send(JSON_ACCEPT, spec -> spec.when().get(FAULT_PATH)).statusCode();
    }

    /**
     * Drives the fault route until it has answered {@code 200} {@value #BREAKER_WINDOW} times in a row.
     * A circuit an earlier test left open answers {@code 503} until it closes, which restarts the count.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll of a circuit breaker closing
    private static void awaitHealthyFaultRoute() {
        long deadline = System.nanoTime() + HEALTHY_TIMEOUT.toNanos();
        int consecutive = 0;
        int lastStatus = 0;
        while (consecutive < BREAKER_WINDOW && System.nanoTime() < deadline) {
            lastStatus = faultRouteStatus();
            if (lastStatus == 200) {
                consecutive++;
                continue;
            }
            consecutive = 0;
            try {
                Thread.sleep(HEALTHY_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the fault route", interrupted);
            }
        }
        int answered = consecutive;
        int last = lastStatus;
        assertEquals(BREAKER_WINDOW, answered, () -> "through a healthy proxy " + FAULT_PATH + " must answer 200 "
                + BREAKER_WINDOW + " times in a row within " + HEALTHY_TIMEOUT.toSeconds() + "s; the last answer was "
                + last);
    }

    /**
     * The Toxiproxy proxy behind the fault route: it listens on the port the {@code FAULT_UPSTREAM}
     * alias names and forwards to the echo backend. Created healthy; closing it deletes it, whatever
     * the outcome of the test, so the alias names a port nothing listens on again.
     * <p>
     * Driven through Toxiproxy's admin API with the JDK HTTP client, as {@code PassthroughFaultIT}
     * drives its own proxy. The two share neither a proxy name nor a listen port.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    private static final class FaultProxy implements AutoCloseable {

        private static final String NAME = "html-error-upstream-fault";

        /** The port of the {@code FAULT_UPSTREAM} alias in {@code topology.properties}. */
        private static final String LISTEN = "0.0.0.0:8667";

        /** The echo backend, as Toxiproxy reaches it on the compose network. */
        private static final String UPSTREAM = "go-httpbin:8080";

        private static final String HELD_BACK_TOXIC = "html-error-answers-held-back";

        /**
         * How long an answer is held back: longer than the 30 seconds the gateway waits for one. A
         * latency toxic, because it holds the connection for exactly that long; behind Toxiproxy's
         * timeout toxic the echo backend closes the connection after five seconds, and the gateway
         * answers a closed connection with a 502.
         */
        private static final long HELD_BACK_MILLIS = 45_000L;

        private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);

        private final HttpClient admin = HttpClient.newBuilder().connectTimeout(ADMIN_TIMEOUT).build();

        private FaultProxy() {
            // created through create()
        }

        /**
         * @return the proxy, enabled and without a toxic; a proxy an aborted earlier run left behind
         *         is replaced
         */
        static FaultProxy create() {
            FaultProxy proxy = new FaultProxy();
            proxy.close();
            proxy.call("POST", "/proxies", "{\"name\":\"" + NAME + "\",\"listen\":\"" + LISTEN + "\",\"upstream\":\""
                    + UPSTREAM + "\",\"enabled\":true}", 201);
            return proxy;
        }

        /** Holds every answer of the echo backend back for longer than the gateway waits for one. */
        void holdAnswersBack() {
            call("POST", "/proxies/" + NAME + "/toxics", "{\"name\":\"" + HELD_BACK_TOXIC + "\",\"type\":\"latency\","
                    + "\"stream\":\"downstream\",\"attributes\":{\"latency\":" + HELD_BACK_MILLIS + ",\"jitter\":0}}",
                    200);
        }

        /**
         * @param enabled whether the proxy listens; a disabled proxy refuses every connection at once
         */
        void setEnabled(boolean enabled) {
            call("POST", "/proxies/" + NAME, "{\"enabled\":" + enabled + "}", 200);
        }

        /** Deletes the proxy. An absent proxy is not an error, and no failure here fails the caller. */
        @Override
        public void close() {
            try {
                send("DELETE", "/proxies/" + NAME, "");
            } catch (IOException unreachable) {
                // Teardown must not mask the outcome of the test the proxy served.
            }
        }

        private void call(String method, String path, String body, int expectedStatus) {
            HttpResponse<String> response;
            try {
                response = send(method, path, body);
            } catch (IOException e) {
                throw new UncheckedIOException("Toxiproxy's admin API did not answer " + method + " " + path, e);
            }
            assertEquals(expectedStatus, response.statusCode(),
                    () -> "Toxiproxy refused " + method + " " + path + ": " + response.body());
        }

        private HttpResponse<String> send(String method, String path, String body) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(adminOrigin() + path))
                    .timeout(ADMIN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                return admin.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while calling Toxiproxy's admin API", e);
            }
        }

        /** The published admin origin, the one {@code PassthroughFaultIT} drives. */
        private static String adminOrigin() {
            return System.getProperty("test.toxiproxy.url", "http://localhost:8474");
        }
    }
}
