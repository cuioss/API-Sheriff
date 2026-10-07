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

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Builds JDK {@link HttpClient}s that accept the self-signed {@code localhost} certificate the
 * integration stack serves — the JDK-client counterpart of the relaxed HTTPS validation
 * {@link BaseIntegrationTest} switches on for REST Assured.
 * <p>
 * The JDK client is what a suite reaches for when REST Assured cannot express the request: a
 * WebSocket handshake, or an HTTP/2 request whose header fields must reach the wire one by one. It
 * offers no one-line relaxation, so the trust-all context is built here once.
 * <p>
 * Scoped strictly to black-box integration tests against a throwaway local certificate — never a
 * production trust decision. This is a test-support class (no {@code *IT} suffix), so Failsafe does
 * not run it as a suite.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class LocalStackTls {

    /**
     * How long a client of this class waits for a connection to be established. The JDK client waits
     * without end otherwise; a request's own answer is bounded by the caller, on the request.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private LocalStackTls() {
        // static helpers only
    }

    /**
     * A client builder whose TLS context accepts the stack's self-signed certificate.
     *
     * @return the builder, for the caller to pick a protocol version on
     * @throws GeneralSecurityException when the JDK cannot provide a TLS context
     */
    static HttpClient.Builder clientBuilder() throws GeneralSecurityException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(sslContext).connectTimeout(CONNECT_TIMEOUT);
    }

    /** Accepts every certificate chain; see the class documentation for the scope. */
    private static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the local stack's self-signed certificate is intentionally accepted.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the local stack's self-signed certificate is intentionally accepted.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
