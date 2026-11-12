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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@code quarkus.http.limits.max-connections} acts on the terminated main listener: the
 * Quarkus HTTPS listener this gateway serves its data plane on. The shipped
 * {@code application.properties} declares the key ({@code ShippedApplicationPropertiesTest} guards that
 * it stays declared); this class proves that a declared cap really bounds the connections the
 * listener holds open, so the declaration is not a key that parses and does nothing.
 * <p>
 * The profile lowers the cap to {@value #CAP} so the bound can be reached with a handful of sockets.
 * The test opens {@value #CAP} connections, has each one served, and holds them open; the connection
 * beyond the cap is then closed without being served. The matched controls show the refusal is the
 * cap and nothing else: the first {@value #CAP} connections are served on the same listener, and once
 * one of them closes a new connection is served again. That served connection is then re-asserted
 * after the wait, and the cap is shown to hold again: with the freed slot taken, the next connection
 * is closed without being served.
 * <p>
 * Each connection is a TLS socket to the listener's HTTPS port, trusting the integration-test
 * certificate the test boot terminates with, and sends one HTTP/1.1 request. A connection counts as
 * served when the listener answers it with a status line, whatever the status.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@TestProfile(MainListenerConnectionCapTest.SmallConnectionCapProfile.class)
@DisplayName("Terminated main listener — quarkus.http.limits.max-connections bounds the open connections")
@Tag("quarkus-fork")
class MainListenerConnectionCapTest {

    /** The connection cap the profile declares. */
    private static final int CAP = 4;

    /** The certificate the test boot's HTTPS listener presents, relative to the module directory. */
    private static final Path LISTENER_CERTIFICATE =
            Path.of("../integration-tests/src/main/docker/certificates/localhost.crt");

    /** How long one exchange may wait on the listener before it counts as neither served nor closed. */
    private static final int EXCHANGE_TIMEOUT_MILLIS = 10_000;

    private static final String REQUEST =
            "GET /q/health/live HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";

    @TestHTTPResource(tls = true)
    URL httpsUrl;

    private final List<SSLSocket> openSockets = new ArrayList<>();

    /** Lowers the connection cap of the terminated listener so the bound can be reached in a test. */
    public static final class SmallConnectionCapProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.limits.max-connections", String.valueOf(CAP));
        }
    }

    /** What happened to one request on one connection. */
    private enum Outcome {
        /** The listener answered with a status line. */
        SERVED,
        /** The listener closed the connection without answering. */
        CLOSED,
        /** The listener neither answered nor closed within the exchange timeout. */
        TIMED_OUT
    }

    @AfterEach
    void closeSockets() throws IOException {
        for (SSLSocket socket : openSockets) {
            socket.close();
        }
        openSockets.clear();
    }

    /**
     * One method rather than several: the listener releases a closed connection's slot
     * asynchronously, so a second method opening connections right after the first closed its own
     * could find the cap still occupied by them.
     */
    @Test
    @DisplayName("serves up to the cap, closes the connection beyond it, serves again once one closes, then caps again")
    void closesTheConnectionBeyondTheCap() throws Exception {
        SSLContext trustingTheListener = trustingTheListener();
        List<Outcome> withinTheCap = new ArrayList<>();
        for (int index = 0; index < CAP; index++) {
            withinTheCap.add(exchangeOnNewConnection(trustingTheListener));
        }

        Outcome beyondTheCap = exchangeOnNewConnection(trustingTheListener);

        assertAll("a listener capped at " + CAP + " connections",
                () -> assertEquals(List.of(Outcome.SERVED, Outcome.SERVED, Outcome.SERVED, Outcome.SERVED),
                        withinTheCap, "the matched control: every connection within the cap is served, so the "
                                + "listener, the TLS trust and the request are sound"),
                () -> assertEquals(Outcome.CLOSED, beyondTheCap,
                        "the connection beyond the cap is closed without being served while the first "
                                + CAP + " stay open"));

        openSockets.getFirst().close();

        AtomicReference<Outcome> afterTheRelease = new AtomicReference<>();
        Awaits.until(() -> {
            afterTheRelease.set(exchangeOnNewConnection(trustingTheListener));
            return afterTheRelease.get() == Outcome.SERVED;
        }, "a new connection is served once a connection within the cap has closed",
                Awaits.CONNECT_CEILING_SECONDS);

        Outcome onceTheFreedSlotIsTaken = exchangeOnNewConnection(trustingTheListener);

        assertAll("the same listener after one connection within the cap has closed",
                () -> assertEquals(Outcome.SERVED, afterTheRelease.get(),
                        "the second matched control: the connection the wait accepted was served, so the "
                                + "refusal above was the cap and not a listener that stopped serving"),
                () -> assertEquals(Outcome.CLOSED, onceTheFreedSlotIsTaken,
                        "once that connection has taken the freed slot the listener again holds " + CAP
                                + " open connections, so the next one is closed without being served"));
    }

    /**
     * Opens a TLS connection to the listener, sends one request and reads the status line. The socket
     * is kept open, so a served connection keeps counting against the cap.
     */
    private Outcome exchangeOnNewConnection(SSLContext context) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        openSockets.add(socket);
        try {
            socket.connect(new InetSocketAddress(httpsUrl.getHost(), httpsUrl.getPort()), EXCHANGE_TIMEOUT_MILLIS);
            socket.setSoTimeout(EXCHANGE_TIMEOUT_MILLIS);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            out.write(REQUEST.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            return statusLine != null && statusLine.startsWith("HTTP/1.1 ") ? Outcome.SERVED : Outcome.CLOSED;
        } catch (SocketTimeoutException timeout) {
            return Outcome.TIMED_OUT;
        } catch (IOException closed) {
            return Outcome.CLOSED;
        }
    }

    /**
     * @return a TLS context trusting exactly the certificate the test boot's HTTPS listener presents
     */
    private static SSLContext trustingTheListener() throws IOException, GeneralSecurityException {
        Certificate certificate;
        try (InputStream pem = Files.newInputStream(LISTENER_CERTIFICATE)) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("listener", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
