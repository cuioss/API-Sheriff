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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code source: upstream} asset terminal action (ADR-0014) over the public HTTPS
 * edge against the {@code /assets/cdn} route.
 * <p>
 * The {@code assets-public} anchor's upstream asset route fetches from the secondary
 * {@code asset-origin} static server (topology alias {@code ASSET_ORIGIN}) over the same
 * fixed-topology, SSRF-controlled egress the proxy action uses — no parallel fetch stack — and
 * re-serves the response under the gateway-owned envelope. The envelope overrides the
 * {@code Content-Type} from the file extension (so a hostile or misconfigured origin cannot dictate
 * it), adds {@code X-Content-Type-Options: nosniff}, and serves only {@code GET}/{@code HEAD}.
 * <p>
 * <strong>The suite deliberately drives the route over both edge protocols.</strong> REST Assured
 * speaks HTTP/1.1, and issue #172 was invisible to every HTTP/1.1 assertion in this class: the
 * origin's connection-specific headers ({@code Connection}, {@code Keep-Alive},
 * {@code Transfer-Encoding}) were relayed verbatim, which HTTP/1.1 tolerates as no-ops but which
 * RFC 9113 §8.2.2 makes a malformed HTTP/2 response — the client discards the stream with
 * {@code PROTOCOL_ERROR} and no header ever reaches the application. Since {@code tls.alpn} lists
 * {@code h2} first, that is what every browser negotiates, so the route was unusable in a browser
 * while this suite stayed green. {@link #getOverHttp2ServesGovernedUpstreamAsset()} closes that
 * gap, with the directory-source control beside it holding the fault to the upstream source rather
 * than to h2 support at large.
 */
class UpstreamAssetServingIT extends BaseIntegrationTest {

    /**
     * The connection-specific response headers a stock origin answers with and the gateway must
     * never re-emit. Asserted on the wire rather than only in
     * {@code AssetResponseEnvelopeTest}: the unit test proves the envelope drops them, this proves
     * nothing downstream of it puts them back on the client's connection.
     */
    private static final List<String> HOP_BY_HOP_NAMES =
            List.of("Connection", "Keep-Alive", "Transfer-Encoding");

    private static HttpClient http2Client;

    /**
     * A JDK HTTP client pinned to HTTP/2, so the request negotiates {@code h2} through ALPN against
     * the edge's TLS listener. The JDK client validates the response header block and rejects a
     * connection-specific field with {@code ProtocolException: malformed response} — its analogue of
     * the {@code PROTOCOL_ERROR} curl reports — which is precisely what makes it a regression guard
     * here rather than merely a second transport.
     */
    @BeforeAll
    static void setUpHttp2Client() throws Exception {
        // Trust-all TLS for the stack's self-signed localhost certificate, matching GrpcProxyIT's
        // posture: scoped strictly to this black-box test against a throwaway local certificate,
        // never production trust. Hostname verification is left ON — the certificate names
        // localhost and the tests dial localhost, so nothing needs relaxing.
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
        http2Client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterAll
    static void tearDownHttp2Client() {
        if (http2Client != null) {
            http2Client.close();
        }
    }

    @Test
    @DisplayName("GET fetches the secondary origin and re-serves it under gateway governance")
    void getServesGovernedUpstreamAsset() {
        var response = given()
                .when()
                .get("/assets/cdn/logo.svg")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.contentType().contains("image/svg+xml"),
                "the gateway overrides the content type from the .svg extension, not the origin");
        assertTrue(response.asString().contains("<svg"), "the served body is the secondary origin's asset");
    }

    @Test
    @DisplayName("HEAD serves the governed headers with an empty body")
    void headServesEmptyBody() {
        var response = given()
                .when()
                .head("/assets/cdn/logo.svg")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.asString().isEmpty(), "a HEAD response carries the governed headers but no body");
    }

    @Test
    @DisplayName("GET over HTTP/2 serves the upstream asset — the origin's hop headers never relay (#172)")
    void getOverHttp2ServesGovernedUpstreamAsset() throws Exception {
        HttpResponse<String> response = fetchOverHttp2("/assets/cdn/logo.svg");

        assertAll(
                () -> assertEquals(HttpClient.Version.HTTP_2, response.version(),
                        "the edge advertises h2 first in tls.alpn, so the request must negotiate HTTP/2"),
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(response.body().contains("<svg"),
                        "the whole governed response must arrive over h2, not just its status"),
                () -> assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null)),
                () -> assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("image/svg+xml"),
                        "the gateway still overrides the content type from the .svg extension over h2"),
                () -> assertAll(HOP_BY_HOP_NAMES.stream().map(name -> () -> assertTrue(
                        response.headers().firstValue(name).isEmpty(),
                        () -> name + " describes the origin's connection and must never be relayed — "
                                + "over h2 it makes the response malformed (RFC 9113 §8.2.2)"))));
    }

    @Test
    @DisplayName("GET over HTTP/2 serves the directory-source control — the h2 edge itself is sound")
    void getOverHttp2ServesDirectoryAssetControl() throws Exception {
        // The control from the issue's table: the same anchor, the same edge, a directory source.
        // It proposes no headers of its own, so it was never affected — its passing beside the
        // upstream case is what attributes a failure there to the upstream source rather than to
        // the h2 listener, the ALPN negotiation, or this test's client.
        HttpResponse<String> response = fetchOverHttp2("/assets/static/app.css");

        assertAll(
                () -> assertEquals(HttpClient.Version.HTTP_2, response.version()),
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(response.body().contains("color"), "the mounted file's content arrives over h2"));
    }

    @Test
    @DisplayName("POST is rejected 405 — an asset action serves only GET and HEAD")
    void postRejected() {
        given()
                .when()
                .post("/assets/cdn/logo.svg")
                .then()
                .statusCode(405);
    }

    /**
     * Fetches {@code path} from the public HTTPS edge over a negotiated {@code h2} connection.
     *
     * @param path the edge path to GET
     * @return the response, body included
     * @throws java.net.ProtocolException when the edge answered a malformed HTTP/2 response — the
     *                                    failure mode issue #172 produced, surfaced as a test error
     *                                    rather than an assertion failure because the JDK client
     *                                    discards such a response before any of it is observable
     */
    private static HttpResponse<String> fetchOverHttp2(String path) throws Exception {
        String port = System.getProperty("test.https.port", "10443");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return http2Client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
