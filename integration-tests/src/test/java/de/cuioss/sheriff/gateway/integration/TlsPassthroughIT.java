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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import de.cuioss.sheriff.gateway.integration.MtlsHandshakeIT.TrustAllManager;

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
 * <strong>The ClientHello deadline.</strong> The front holds a connection it has not routed yet for at
 * most its ClientHello deadline: a connection that sends nothing, and one whose ClientHello stops
 * halfway, are both closed without an answer at that deadline — not before it, less a stated slack, and
 * not after it plus a stated margin. The lower bound is what makes the close the deadline's: a connection
 * closed at once would pass an upper bound alone. The passthrough
 * handshake above is the positive control — a client that sends its ClientHello at once is routed long
 * before the deadline could end it.
 * <p>
 * <strong>Runtime preconditions</strong> (supplied by the {@code -Pintegration-tests} stack, not by
 * this black-box client): the gateway is configured with a {@code passthrough_sni} entry for
 * {@link #passthroughSni()} resolving to {@code passthrough-backend:8443}, and the terminated
 * listener serves the stack's {@code CN=localhost} certificate. This suite holds no key material —
 * it only observes the negotiated peer identity.
 */
class TlsPassthroughIT extends BaseIntegrationTest {

    /**
     * The ClientHello deadline of the SNI front: an accepted connection that has not completed its
     * ClientHello within it is closed. Stated here, not read from the gateway: a bound read from the
     * code under test would hold by construction.
     */
    private static final Duration CLIENT_HELLO_DEADLINE = Duration.ofSeconds(10);

    /**
     * How long past {@link #CLIENT_HELLO_DEADLINE} a close may arrive: room for a loaded machine and the
     * Docker port forward, and far below the front's relay idle timeout, so a close inside this bound is
     * the deadline's and not the idle timeout's.
     */
    private static final Duration CLOSE_MARGIN = Duration.ofSeconds(5);

    /**
     * How much earlier than {@link #CLIENT_HELLO_DEADLINE} a close may be observed and still be the
     * deadline's. The front arms the deadline when it accepts the connection, which can precede the
     * moment this client starts its clock — the end of its connect, or of its write — by the time a
     * connect or a write takes to complete. A close earlier than the deadline by more than this is not the
     * deadline's doing: a connection refused at accept, or closed for any other reason, is closed at once.
     */
    private static final Duration EARLY_CLOSE_SLACK = Duration.ofSeconds(1);

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /**
     * The first bytes of a TLS ClientHello whose record announces 512 bytes: the record header, the
     * handshake header announcing 508 bytes, the client version and the first four bytes of the client
     * random. The rest never follows, so the front can never complete the ClientHello.
     */
    private static final byte[] HALF_SENT_CLIENT_HELLO = {
            0x16, 0x03, 0x01, 0x02, 0x00,
            0x01, 0x00, 0x01, (byte) 0xFC,
            0x03, 0x03,
            0x5A, 0x5A, 0x5A, 0x5A
    };

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

    @Test
    @DisplayName("a connection that sends nothing is closed by the SNI front at its ClientHello deadline")
    void silentConnectionIsClosedAtTheClientHelloDeadline() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", httpsPort()), CONNECT_TIMEOUT_MILLIS);

            long closedMillis = awaitClosedByTheGateway(socket);

            assertClosedAtTheDeadline(closedMillis, "a silent connection");
        }
    }

    @Test
    @DisplayName("a connection whose ClientHello stops halfway is closed by the SNI front at its ClientHello deadline")
    void halfSentClientHelloIsClosedAtTheClientHelloDeadline() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", httpsPort()), CONNECT_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(HALF_SENT_CLIENT_HELLO);
            out.flush();

            long closedMillis = awaitClosedByTheGateway(socket);

            assertClosedAtTheDeadline(closedMillis, "a stalled ClientHello");
        }
    }

    /**
     * Asserts that a close observed after {@code closedMillis} is the ClientHello deadline's: not later
     * than the deadline plus {@link #CLOSE_MARGIN}, and not earlier than the deadline minus
     * {@link #EARLY_CLOSE_SLACK}. The lower bound is what tells the deadline from a connection closed at
     * once — refused at accept, or ended for another reason — which the upper bound alone would accept.
     */
    private static void assertClosedAtTheDeadline(long closedMillis, String connection) {
        long earliest = CLIENT_HELLO_DEADLINE.minus(EARLY_CLOSE_SLACK).toMillis();
        long latest = CLIENT_HELLO_DEADLINE.plus(CLOSE_MARGIN).toMillis();
        assertAll(connection + " must be closed by the ClientHello deadline, was closed after " + closedMillis + " ms",
                () -> assertTrue(closedMillis >= earliest,
                        "closed before the deadline could fire: not before " + earliest + " ms"),
                () -> assertTrue(closedMillis <= latest, "closed too late for the deadline: not after " + latest + " ms"));
    }

    /**
     * Reads from {@code socket} until the gateway closes it — an end of stream, or a reset — and returns
     * how long that took. Fails when the gateway sends a byte, or has not closed the connection within
     * {@link #CLIENT_HELLO_DEADLINE} plus {@link #CLOSE_MARGIN}.
     */
    private static long awaitClosedByTheGateway(Socket socket) throws IOException {
        socket.setSoTimeout((int) CLIENT_HELLO_DEADLINE.plus(CLOSE_MARGIN).toMillis());
        long startNanos = System.nanoTime();
        try {
            int read = socket.getInputStream().read();
            assertEquals(-1, read, "the front must close the connection without answering it");
        } catch (SocketTimeoutException stillOpen) {
            throw new AssertionError("the front did not close the connection within the ClientHello deadline plus "
                    + CLOSE_MARGIN.toSeconds() + " s", stillOpen);
        } catch (SocketException reset) {
            // A reset is a close by the gateway too.
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
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
}
