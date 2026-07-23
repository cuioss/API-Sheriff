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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the accept-time SNI split's opaque L4 passthrough relay end-to-end: a TLS ClientHello whose
 * SNI matches a {@code tls.passthrough_sni} entry is split off at L4 and relayed, byte-for-byte, to
 * the TLS-enabled {@code passthrough-backend} the compose stack adds (D4) — the gateway never
 * terminates it. The observable proof is the peer certificate the client negotiates through the
 * relay: it is the <em>backend's</em> ({@code CN=passthrough-backend}), not the gateway's terminated
 * {@code CN=localhost} identity. A terminated request on the same public listener is still fully
 * served, proving the split is per-connection and does not disturb the L7 path.
 * <p>
 * <strong>Runtime preconditions</strong> (supplied by the {@code -Pintegration-tests} stack, not by
 * this black-box client): the gateway is configured with a {@code passthrough_sni} entry for
 * {@link #passthroughSni()} resolving to {@code passthrough-backend:8443}, and the terminated
 * listener serves the stack's {@code CN=localhost} certificate. This suite holds no key material —
 * it only observes the negotiated peer identity.
 */
class TlsPassthroughIT extends BaseIntegrationTest {

    /** The passthrough SNI the gateway splits off at L4; overridable for a differently-named stack. */
    private static String passthroughSni() {
        return System.getProperty("test.passthrough.sni", "passthrough.test.example");
    }

    private static int httpsPort() {
        return Integer.parseInt(System.getProperty("test.https.port", "10443"));
    }

    @Test
    @DisplayName("a passthrough-SNI connection negotiates the backend's certificate, proving opaque relay")
    void passthroughNegotiatesBackendCertificate() throws Exception {
        // Act — open a TLS connection to the public listener with SNI set to the passthrough hostname.
        X509Certificate peerLeaf = handshakePeerLeaf("localhost", httpsPort(), passthroughSni());

        // Assert — the negotiated identity is the BACKEND's, so the gateway relayed opaquely and never
        // terminated: a terminated split would have surfaced the gateway's own CN=localhost identity.
        String subject = peerLeaf.getSubjectX500Principal().getName();
        assertTrue(subject.contains("CN=passthrough-backend"),
                "the passthrough relay must surface the backend's certificate identity, was: " + subject);
    }

    @Test
    @DisplayName("terminated traffic on the same public listener is still fully served")
    void terminatedTrafficStillServed() {
        // A terminated request (default SNI=localhost) flows through the full L7 pipeline to go-httpbin;
        // the echoed method proves the request reached the upstream through the terminated path.
        var response = given()
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(200)
                .extract();
        assertNotNull(response.path("method"), "terminated traffic must still reach the go-httpbin upstream");
    }

    /**
     * Completes a TLS handshake to {@code host:port} with the given SNI (trust-all, mirroring
     * {@link BaseIntegrationTest}'s relaxed validation for the stack's self-signed material) and
     * returns the negotiated leaf certificate.
     */
    private static X509Certificate handshakePeerLeaf(String host, int port, String sni) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sni)));
            socket.setSSLParameters(params);
            socket.setSoTimeout(15_000);
            socket.startHandshake();
            Certificate[] chain = socket.getSession().getPeerCertificates();
            assertTrue(chain.length > 0, "the handshake must yield a peer certificate chain");
            return (X509Certificate) chain[0];
        }
    }

    /**
     * A trust-all {@link X509TrustManager} scoped strictly to this black-box IT — it inspects the
     * negotiated identity itself, so it must accept whatever certificate the peer presents.
     */
    private static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the local stack's self-signed certificates are intentionally accepted.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the negotiated identity is asserted by the test body, not rejected here.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
