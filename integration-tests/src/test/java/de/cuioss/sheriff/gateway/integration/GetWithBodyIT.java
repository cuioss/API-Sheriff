/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves end-to-end, against the running containerised edge, that
 * {@code security_defaults.allow_get_with_content_length_body} is <strong>actually active in the
 * shipped deployment</strong>: a {@code GET} carrying a {@code Content-Length}-framed body reaches
 * the upstream instead of meeting the framing gate's {@code 400}.
 * <p>
 * <strong>Why a live request, and why this test is not redundant.</strong> Deliverable 3 already has
 * two guards, and neither can observe this. {@code FramingGateTest} proves
 * {@code config -> behaviour} in-process against a synthetic {@code PipelineRequest}.
 * {@link GetWithBodyActivationWiringTest} proves {@code descriptor declares the key} by parsing the
 * committed YAML, and its own Javadoc names the residual gap: the opt-in could ship fully
 * unit-covered and entirely inert, with the containerised suite still meeting a {@code 400} on the
 * very request the feature exists to admit. The feature is a <em>request-path admission</em>, so only
 * a request can close that gap. (Deliverable 2's sibling guard is legitimately wiring-only because
 * its feature is a boot refusal, which the descriptor fully determines.)
 * <p>
 * <strong>Why a raw TLS socket rather than an HTTP client.</strong> A {@code GET} body is the exact
 * shape general-purpose clients normalise away — several silently drop the entity, or re-frame it,
 * which would leave this test passing vacuously on an empty request while asserting nothing about
 * the gate. Writing the request bytes directly makes the {@code Content-Length} frame an observable
 * property of the test rather than an assumption about a client, and
 * {@link #positiveRequestIsGenuinelyContentLengthFramed()} asserts it on the constructed bytes so a
 * future refactor cannot quietly empty the body out.
 * <p>
 * <strong>The negative control is load-bearing.</strong> Without
 * {@link #chunkedGetIsStillRejected()}, the positive case cannot distinguish "the opt-in works" from
 * "the framing gate is off entirely" — the failure mode that would matter most on a security
 * gateway. {@code Transfer-Encoding} on a bodyless method is relaxed by no configuration, so it must
 * still be refused on the very instance that admits the framed body.
 * <p>
 * Note that the third framing shape — a body signalled with <em>no</em> declared
 * {@code Content-Length} — has no HTTP/1.1 wire representation at all: a body is framed by
 * {@code Content-Length} or by {@code Transfer-Encoding}, and there is no third option. That is
 * precisely why narrowing the opt-in to the declared-{@code Content-Length} leg is behaviour-neutral
 * at this edge, and why that leg is asserted at the unit level (where a {@code PipelineRequest} can
 * express the shape) rather than here.
 * <p>
 * <strong>Runtime precondition</strong>: the base {@code -Pintegration-tests} instance is the only
 * one that activates the opt-in; its sibling instances deliberately keep the strict default.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("GET-body opt-in — live against the containerised gateway")
class GetWithBodyIT extends BaseIntegrationTest {

    /** The Elasticsearch {@code _search} shape the opt-in exists for. */
    private static final String BODY = "{\"query\":{\"match_all\":{}}}";

    /**
     * A token carried <em>only</em> by {@link #BODY}. Its presence in the upstream's echo is what
     * proves the entity actually crossed to the upstream, rather than being dropped once the framing
     * gate had admitted the request.
     */
    private static final String BODY_MARKER = "match_all";

    /**
     * A routed proxy path whose upstream is go-httpbin's {@code /anything/*} echo — the
     * {@code UPSTREAM} topology alias carries the {@code /anything} base path, so {@code /proxy/get}
     * reaches {@code /anything/get}. That endpoint reflects the received method <em>and body</em>,
     * which is what lets this test assert the body was forwarded and not merely admitted.
     */
    private static final String ROUTE = "/proxy/get";

    /** The echoed method. go-httpbin renders indented JSON, so the spacing is matched loosely. */
    private static final Pattern ECHOED_GET = Pattern.compile("\"method\"\\s*:\\s*\"GET\"");

    private static final int SOCKET_TIMEOUT_MILLIS = 20_000;

    @Test
    @DisplayName("a Content-Length-framed GET body is admitted and reaches the upstream")
    void contentLengthFramedGetBodyIsAdmitted() throws Exception {
        // Arrange
        String request = contentLengthFramedGet();

        // Act
        Response response = send(request);

        // Assert — the framing gate's rejection is a 400; anything else means the request got past it
        assertNotEquals(400, response.status(),
                "the base instance declares allow_get_with_content_length_body: true, so a"
                        + " Content-Length-framed GET body must not be refused by the framing gate."
                        + " Response was: " + response.body());
        assertEquals(200, response.status(),
                "the admitted GET must be forwarded to the upstream. Response was: " + response.body());
        assertTrue(ECHOED_GET.matcher(response.body()).find(),
                "the response must be go-httpbin's echo reporting that the upstream received a GET,"
                        + " proving the request reached the upstream rather than being answered by the"
                        + " gateway. Body was: " + response.body());
        assertTrue(response.body().contains(BODY_MARKER),
                "the echo must carry the forwarded GET body: admission alone does not prove the entity"
                        + " survived, and a regression that drops it after the framing gate passes would"
                        + " leave every other assertion here green. Body was: " + response.body());
    }

    @Test
    @DisplayName("Transfer-Encoding on GET is STILL rejected 400 on the same instance")
    void chunkedGetIsStillRejected() throws Exception {
        // Arrange — the negative control: no configuration relaxes chunked framing on a bodyless method
        String request = chunkedGet();

        // Act
        Response response = send(request);

        // Assert
        assertEquals(400, response.status(),
                "chunked framing on an otherwise-bodyless method is the smuggling shape the framing"
                        + " gate exists to constrain — the opt-in must never admit it, and without this"
                        + " control the positive case could not tell the opt-in from a disabled gate."
                        + " Response was: " + response.body());
    }

    @Test
    @DisplayName("the positive request really transmits a Content-Length-framed, non-empty body")
    void positiveRequestIsGenuinelyContentLengthFramed() {
        // Arrange + Act
        String request = contentLengthFramedGet();

        // Assert — a vacuous empty-body request would make the positive case prove nothing
        assertTrue(request.startsWith("GET " + ROUTE + " HTTP/1.1\r\n"),
                "the acceptance case must be a GET on the routed path");
        assertTrue(request.contains("\r\nContent-Length: " + BODY.length() + "\r\n"),
                "the request must carry an explicit Content-Length frame matching the body length");
        assertTrue(request.endsWith("\r\n\r\n" + BODY),
                "the request must actually carry the body after the header block, not an empty entity");
        assertTrue(BODY.length() > 0, "the transmitted body must be non-empty");
        assertTrue(BODY.contains(BODY_MARKER),
                "the marker the upstream-echo assertion looks for must occur in the transmitted body;"
                        + " editing BODY without it would make that assertion unobservable");
    }

    // --- request construction --------------------------------------------------------------------

    private static String contentLengthFramedGet() {
        return "GET " + ROUTE + " HTTP/1.1\r\n"
                + "Host: " + authority() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + BODY.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + BODY;
    }

    /**
     * The same body, chunk-framed. {@code Connection: close} names no framing or trust header, so it
     * is untouched by the gate's {@code Connection}-strip rule and cannot itself cause the rejection
     * this control asserts.
     */
    private static String chunkedGet() {
        return "GET " + ROUTE + " HTTP/1.1\r\n"
                + "Host: " + authority() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + Integer.toHexString(BODY.length()) + "\r\n"
                + BODY + "\r\n"
                + "0\r\n\r\n";
    }

    private static String authority() {
        return "localhost:" + port();
    }

    private static int port() {
        return Integer.parseInt(System.getProperty("test.https.port", "10443"));
    }

    // --- raw transport ---------------------------------------------------------------------------

    /**
     * Writes the request bytes verbatim to the terminated TLS listener and reads the whole response.
     * {@code Connection: close} makes the server close the stream after responding, so reading to EOF
     * is a complete read and needs no response-framing parser of its own.
     *
     * @param rawRequest the exact bytes to put on the wire
     * @return the parsed status line and body
     * @throws IOException              on a transport failure
     * @throws GeneralSecurityException when the TLS context cannot be built
     */
    private static Response send(String rawRequest) throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{relaxedTrustManager()}, new SecureRandom());
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port())) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            // Pin HTTP/1.1 explicitly: the assertions are about a hand-framed HTTP/1.1 message, and an
            // ALPN-negotiated HTTP/2 connection would carry different framing rules entirely.
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            try (InputStream in = socket.getInputStream()) {
                return parse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
    }

    private static Response parse(String raw) {
        int separator = raw.indexOf("\r\n\r\n");
        String head = separator < 0 ? raw : raw.substring(0, separator);
        String body = separator < 0 ? "" : raw.substring(separator + 4);
        int lineEnd = head.indexOf("\r\n");
        String statusLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            throw new IllegalStateException("unparseable status line from the gateway: " + statusLine);
        }
        return new Response(Integer.parseInt(parts[1]), body);
    }

    /**
     * Accepts the stack's self-signed localhost certificate — the raw-socket equivalent of the
     * {@code RestAssured.useRelaxedHTTPSValidation()} every other IT in this module relies on.
     */
    // java:S4830 — test-only trust of the integration stack's self-signed certificate, reaching
    // nothing but localhost. This is the established posture of the whole IT module (see
    // BaseIntegrationTest); no production code path constructs this trust manager.
    @SuppressWarnings("java:S4830")
    private static X509TrustManager relaxedTrustManager() {
        return new X509TrustManager() {

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // test-only: the integration stack's certificate is self-signed by design
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // test-only: the integration stack's certificate is self-signed by design
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /** A parsed HTTP/1.1 response: the status code and the body, which is all these assertions need. */
    private record Response(int status, String body) {
    }
}
