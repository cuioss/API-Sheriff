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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import de.cuioss.sheriff.gateway.integration.MtlsHandshakeIT.TrustAllManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the opaque L4 passthrough relay's resilience contract under a mid-stream transport fault: a
 * connection reset injected into the relayed byte stream by Toxiproxy (D4 adds it to the compose
 * stack) propagates back to the client as a connection <em>abort</em>, not an indefinite hang. A relay
 * that swallowed the peer reset and left the client leg open would surface as a read timeout; a
 * correct relay tears the client leg down promptly, surfacing an {@link IOException} that is NOT a
 * {@link SocketTimeoutException}.
 * <p>
 * The fault is driven entirely through Toxiproxy's REST control API (JDK {@link HttpClient}, no
 * Toxiproxy client dependency): the test creates a proxy in front of {@code passthrough-backend:8443}
 * and adds a {@code reset_peer} toxic, then relays a passthrough connection through the gateway and
 * observes the abort.
 * <p>
 * <strong>Runtime preconditions</strong> (supplied by the {@code -Pintegration-tests} stack): the
 * Toxiproxy control API is reachable at {@code -Dtest.toxiproxy.url} (default
 * {@code http://localhost:8474}); the gateway maps the fault passthrough SNI {@link #faultSni()} to
 * the Toxiproxy listen endpoint {@link #proxyListen()}, which forwards to
 * {@code passthrough-backend:8443}.
 */
class PassthroughFaultIT extends BaseIntegrationTest {

    private static final String PROXY_NAME = "passthrough-fault";

    private final HttpClient control = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static String toxiproxyUrl() {
        return System.getProperty("test.toxiproxy.url", "http://localhost:8474");
    }

    private static String proxyListen() {
        // The address the gateway's passthrough target resolves to (inside the compose network).
        return System.getProperty("test.toxiproxy.listen", "0.0.0.0:8666");
    }

    private static String proxyUpstream() {
        return System.getProperty("test.toxiproxy.upstream", "passthrough-backend:8443");
    }

    private static String faultSni() {
        return System.getProperty("test.passthrough.fault.sni", "fault.test.example");
    }

    private static int httpsPort() {
        return Integer.parseInt(System.getProperty("test.https.port", "10443"));
    }

    @BeforeEach
    void createFaultProxy() throws Exception {
        deleteProxyQuietly();
        String body = "{\"name\":\"" + PROXY_NAME + "\",\"listen\":\"" + proxyListen()
                + "\",\"upstream\":\"" + proxyUpstream() + "\",\"enabled\":true}";
        send("POST", "/proxies", body);
        // A reset_peer toxic tears the connection down mid-stream after a short delay.
        String toxic = "{\"type\":\"reset_peer\",\"attributes\":{\"timeout\":200}}";
        send("POST", "/proxies/" + PROXY_NAME + "/toxics", toxic);
    }

    @AfterEach
    void removeFaultProxy() {
        deleteProxyQuietly();
    }

    @Test
    @DisplayName("a mid-stream reset on the passthrough relay surfaces as an abort, not a hang")
    void midStreamResetSurfacesAsAbort() {
        // A generous read timeout distinguishes an abort (fast IOException) from a hang (which would
        // instead trip the SocketTimeoutException at the timeout boundary).
        IOException failure = assertThrows(IOException.class, () -> relayThroughFault(faultSni()),
                "a mid-stream reset must propagate to the client as a connection abort");
        assertFalse(failure instanceof SocketTimeoutException,
                "the relay must tear the client leg down promptly (abort), not leave it hanging until timeout");
    }

    /**
     * Opens a passthrough TLS connection through the gateway (SNI = the fault hostname, relayed via
     * Toxiproxy), writes a probe byte, and reads — the injected reset must surface as an
     * {@link IOException} well within the socket read timeout.
     */
    private static void relayThroughFault(String sni) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", httpsPort())) {
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sni)));
            socket.setSSLParameters(params);
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: passthrough-backend\r\n\r\n".getBytes());
            socket.getOutputStream().flush();
            // Drain until the reset arrives; the read throws the abort we assert on.
            byte[] buffer = new byte[512];
            while (socket.getInputStream().read(buffer) != -1) {
                // keep reading until the peer reset tears the stream down
            }
        }
    }

    private void send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(toxiproxyUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = control.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("Toxiproxy " + method + " " + path + " failed: "
                    + response.statusCode() + " " + response.body());
        }
    }

    private void deleteProxyQuietly() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(toxiproxyUrl() + "/proxies/" + PROXY_NAME))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            control.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Best-effort teardown: a missing proxy (404) or transient control-plane error must not
            // fail the test body — the assertion under test is the relay abort, not proxy bookkeeping.
        }
    }
}
