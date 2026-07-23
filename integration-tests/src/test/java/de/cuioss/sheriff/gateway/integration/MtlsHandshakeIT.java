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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code tls.mtls} client-certificate verification at the handshake level (GW-06 behaviour,
 * not a flag-read): with {@code mtls.enabled} and a {@code client_ca} trust anchor, the terminated
 * listener requires and verifies a client certificate. A client presenting a cert the {@code
 * client_ca} trusts completes the handshake; a client presenting no certificate, or one signed by a
 * foreign CA, is rejected at the TLS layer — the failure is a handshake abort, never an
 * application-level {@code 4xx}, because verification happens before any HTTP is exchanged.
 * <p>
 * This is the running-edge complement to the unit-level {@code MtlsServerCustomizerTest}, which
 * proves the config→client-auth mapping in isolation; here the accept/reject decision is proven
 * against the live TLS stack.
 * <p>
 * <strong>Runtime preconditions</strong> (supplied by the {@code -Pintegration-tests} stack, not by
 * this black-box client): the mTLS-terminated listener is reachable on {@link #mtlsPort()}, its
 * {@code client_ca} trusts the identity in the PKCS#12 keystore named by
 * {@code -Dtest.mtls.client.keystore} (password {@code -Dtest.mtls.client.password}), and the foreign
 * identity in {@code -Dtest.mtls.wrong.keystore} is signed by a CA the {@code client_ca} does NOT
 * trust. The keystores follow the same provisioning convention as {@code certificates/}.
 */
class MtlsHandshakeIT extends BaseIntegrationTest {

    private static int mtlsPort() {
        return Integer.parseInt(System.getProperty("test.mtls.port", "10443"));
    }

    @Test
    @DisplayName("a client cert the client_ca trusts completes the mTLS handshake")
    void trustedClientCertAccepted() throws Exception {
        SSLContext context = clientContext(
                System.getProperty("test.mtls.client.keystore"),
                System.getProperty("test.mtls.client.password", "localhost-trust"));
        assertDoesNotThrow(() -> handshake(context),
                "a client certificate trusted by client_ca must complete the mTLS handshake");
    }

    @Test
    @DisplayName("a client presenting no certificate is rejected at the handshake")
    void noClientCertRejected() throws Exception {
        // No KeyManager → the client offers no certificate; a require-and-verify listener aborts the
        // handshake rather than serving the request.
        SSLContext context = clientContext(null, null);
        assertThrows(SSLException.class, () -> handshake(context),
                "a missing client certificate must be rejected at the TLS handshake, not as an HTTP status");
    }

    @Test
    @DisplayName("a client cert signed by a foreign CA is rejected at the handshake")
    void wrongCaClientCertRejected() throws Exception {
        SSLContext context = clientContext(
                System.getProperty("test.mtls.wrong.keystore"),
                System.getProperty("test.mtls.wrong.password", "wrong-trust"));
        assertThrows(SSLException.class, () -> handshake(context),
                "a client certificate signed by a CA the client_ca does not trust must be rejected");
    }

    /**
     * Opens a TLS connection to the mTLS listener and drives the handshake to completion. The server
     * certificate is trust-all (the stack's self-signed material); only the CLIENT-auth outcome is
     * under test.
     */
    private static void handshake(SSLContext context) throws IOException {
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", mtlsPort())) {
            socket.setSoTimeout(15_000);
            socket.startHandshake();
        }
    }

    /**
     * Builds an SSL context whose client identity is loaded from {@code keystorePath} (a PKCS#12), or
     * that offers no client certificate when {@code keystorePath} is {@code null}. The server trust is
     * always trust-all — this suite asserts the client-auth decision, not server-cert validation.
     */
    private static SSLContext clientContext(String keystorePath, String password) throws Exception {
        KeyManager[] keyManagers = null;
        if (keystorePath != null) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
                keyStore.load(in, password == null ? new char[0] : password.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password == null ? new char[0] : password.toCharArray());
            keyManagers = kmf.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        return context;
    }

    /**
     * A trust-all {@link X509TrustManager} for the stack's self-signed server certificate. Scoped
     * strictly to this black-box IT — never a production trust decision. Only the client-auth outcome
     * is under test here.
     */
    private static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: not exercised on the client side.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the stack's self-signed server certificate is intentionally accepted.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
