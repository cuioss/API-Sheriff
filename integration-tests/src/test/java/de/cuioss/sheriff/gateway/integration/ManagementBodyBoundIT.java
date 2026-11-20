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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the management interface's request-body bound on the shipped native image: the
 * {@code quarkus.management.limits.max-body-size=32K} the shipped {@code application.properties}
 * declares is the bound the running container's management routes enforce.
 * <p>
 * {@code ManagementListenerTransportTest} observes the same bound on a JVM-mode listener. This IT
 * closes the gap that leaves open: the key is a runtime property baked into the native executable, and
 * only a request to the container built from this branch shows the image carries it. A body one byte
 * past 32 KiB is refused {@code 413} on its declared {@code Content-Length}, before any body byte is
 * sent; a body of exactly 32 KiB is served, which is the matched control that keeps the refusal from
 * passing on a listener that refuses every body. Under the 10 MiB Quarkus default the refused request
 * would be served, so the first assertion fails on an image without the key.
 * <p>
 * Every probe route the management router serves answers a body-less {@code GET} with {@code 200} on
 * the same container, which shows the bound costs none of the probes a deployment relies on.
 * <p>
 * The exchange runs over a raw TLS socket pinned to HTTP/1.1, so the declared {@code Content-Length}
 * is under the test's control rather than a client's: an HTTP client would refuse to declare a length
 * it then does not send. The management port and context path come from {@link BaseIntegrationTest},
 * the stack's self-signed certificate is accepted through {@link LocalStackTls}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Management body bound — live on the native image")
class ManagementBodyBoundIT extends BaseIntegrationTest {

    /** The body bound the shipped file sets on every management route. */
    private static final int BODY_BOUND = 32 * 1024;

    private static final int OK = 200;
    private static final int PAYLOAD_TOO_LARGE = 413;

    /** How long one exchange with the management listener may take. */
    private static final int EXCHANGE_TIMEOUT_MILLIS = 10_000;

    /** The liveness probe, relative to the management root path. */
    private static final String LIVENESS_ROUTE = "/health/live";

    @Test
    @DisplayName("refuses a declared body past the shipped 32 KiB with 413, and serves a body of exactly 32 KiB")
    void boundsTheBodyAtTheShippedBound() throws Exception {
        int refused = statusOf(requestDeclaringBodyOf(BODY_BOUND + 1));
        int served = statusOf(requestWithBodyOf(BODY_BOUND));

        assertAll("the native image's management body bound",
                () -> assertEquals(PAYLOAD_TOO_LARGE, refused,
                        "a declared body one byte past 32 KiB is refused before a body byte is sent"),
                () -> assertEquals(OK, served, "control: a body of exactly 32 KiB is served"));
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/health", "/health/live", "/health/ready", "/health/started", "/metrics"})
    @DisplayName("answers a body-less GET on every probe route with 200 under the body bound")
    void servesEveryProbeRouteABodylessGet(String route) throws Exception {
        assertEquals(OK, statusOf(bodylessGetOf(route)),
                "the probe route " + route + " is served: no probe needs a body the bound could refuse");
    }

    /**
     * @param route the probe route, relative to the management root path
     * @return a body-less GET of that route
     */
    private static String bodylessGetOf(String route) {
        return "GET " + managementRootPath() + route + " HTTP/1.1\r\nHost: " + authority()
                + "\r\nConnection: close\r\n\r\n";
    }

    /**
     * @param bodyBytes the size of the body
     * @return a liveness POST carrying a body of exactly that size
     */
    private static String requestWithBodyOf(int bodyBytes) {
        return requestDeclaringBodyOf(bodyBytes) + "o".repeat(bodyBytes);
    }

    /**
     * @param bodyBytes the body size the request declares
     * @return the head of a liveness POST declaring a body of that size, without the body itself
     */
    private static String requestDeclaringBodyOf(int bodyBytes) {
        return "POST " + managementRootPath() + LIVENESS_ROUTE + " HTTP/1.1\r\nHost: " + authority() + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + bodyBytes + "\r\nConnection: close\r\n\r\n";
    }

    /** @return the management origin {@link BaseIntegrationTest} resolves, parsed for host and port */
    private static URI managementOrigin() {
        return URI.create(managementPortBaseUri());
    }

    /** @return the {@code host:port} of the management listener, for the {@code Host} header */
    private static String authority() {
        return managementOrigin().getAuthority();
    }

    /**
     * Sends one raw HTTP/1.1 request over TLS to the management listener and reads the status of its
     * answer.
     *
     * @param request the complete request, head, blank line and any body
     * @return the status code of the answer
     * @throws IOException              when the exchange fails or the listener closes without answering
     * @throws GeneralSecurityException when the TLS context cannot be built
     */
    private static int statusOf(String request) throws IOException, GeneralSecurityException {
        URI origin = managementOrigin();
        try (SSLSocket socket = (SSLSocket) LocalStackTls.trustAllContext().getSocketFactory()
                .createSocket(origin.getHost(), origin.getPort())) {
            socket.setSoTimeout(EXCHANGE_TIMEOUT_MILLIS);
            // Pin HTTP/1.1: the request is hand-framed HTTP/1.1, which an ALPN-negotiated HTTP/2
            // connection would not parse.
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                throw new IOException("the management listener closed the connection without answering");
            }
            return Integer.parseInt(statusLine.split(" ", 3)[1]);
        }
    }
}
