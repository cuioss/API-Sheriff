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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
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

    /** Read timeout on every handshake socket; bounds both the handshake and the post-refusal read. */
    private static final int SO_TIMEOUT_MILLIS = 15_000;

    /**
     * How long a refused handshake waits for a completion notification that must never arrive. The JDK
     * delivers {@link javax.net.ssl.HandshakeCompletedListener} events on a separate notifier thread, so
     * "the listener never fired" is only a verdict once that thread has had time to run; a completed
     * handshake waits the full socket timeout instead.
     */
    private static final int LISTENER_GRACE_MILLIS = 2_000;

    /** The cipher suite of the JDK's placeholder session, reported when no handshake completed. */
    private static final String NULL_CIPHER_SUITE = "SSL_NULL_WITH_NULL_NULL";

    /**
     * Positive control for the refusal evidence: the same {@link #attemptHandshake(SSLContext)} helper
     * the rejection tests use must observe the completion listener firing and a valid, negotiated
     * session here. Without this control "the listener never fired" and "the session is not valid"
     * could be signals that are simply unable to go true.
     */
    @Test
    @DisplayName("a client cert the client_ca trusts completes the mTLS handshake")
    void trustedClientCertAccepted() throws Exception {
        SSLContext context = clientContext(
                System.getProperty("test.mtls.client.keystore"),
                System.getProperty("test.mtls.client.password", "localhost-trust"));

        HandshakeOutcome outcome = attemptHandshake(context);

        assertAll("a client certificate trusted by client_ca must complete the mTLS handshake",
                () -> assertNull(outcome.failure(),
                        () -> "the trusted handshake failed: " + outcome.failure()),
                () -> assertTrue(outcome.listenerFired(),
                        "the handshake-completed listener must fire for an accepted handshake"),
                () -> assertTrue(outcome.sessionValid(),
                        "an accepted handshake must leave a valid session"),
                () -> assertNotEquals(NULL_CIPHER_SUITE, outcome.cipherSuite(),
                        "an accepted handshake must negotiate a real cipher suite"));
    }

    /**
     * A client with no key manager offers no certificate, and a require-and-verify listener aborts the
     * handshake rather than serving the request. The abort races the client's own flight writes exactly
     * as described on {@link #wrongCaClientCertRejected()}, so the same typed-exception plus
     * refusal-evidence rule applies.
     */
    @Test
    @DisplayName("a client presenting no certificate is rejected at the handshake")
    void noClientCertRejected() throws Exception {
        SSLContext context = clientContext(null, null);

        HandshakeOutcome outcome = attemptHandshake(context);

        assertHandshakeRefused(outcome,
                "a missing client certificate must be rejected at the TLS handshake, not as an HTTP status");
    }

    /**
     * The foreign identity must genuinely be offered before its rejection means anything, and two
     * separate things could keep it from being offered.
     * <p>
     * <strong>The keystore could be absent.</strong> With {@code test.mtls.wrong.keystore} unset,
     * {@link #clientContext(String, String)} builds a context with no key manager — byte-identical to
     * {@link #noClientCertRejected()} — and this test would pass for that sibling's reason. So the
     * keystore is first proven to exist, be readable and hold a key entry.
     * <p>
     * <strong>The key manager could decline to offer it.</strong> The JDK's X509 key managers filter
     * candidate aliases by the certificate authorities the server advertises in its
     * {@code CertificateRequest}. The mTLS listener advertises only {@code mtls-client-ca}, so an
     * unwrapped key manager finds no identity issued by it, returns no alias, and the client sends no
     * certificate at all — the rejection then comes from the missing-certificate path again. The
     * identity is therefore handed to the handshake through a {@link ForcedAliasKeyManager}, which
     * answers every client-alias request with the foreign key entry's alias whatever issuers the
     * server advertised, and records that the request happened and which alias it answered.
     * <p>
     * After the refusal, the recording is asserted: the handshake asked the client for a certificate,
     * the forced alias was the one offered, and the chain behind that alias is the leaf issued by
     * {@code mtls-wrong-ca} (the CA {@code generate-mtls-certificates.sh} signs the foreign identity
     * with). Only with all three in place can the rejection be the foreign-CA verdict rather than the
     * no-certificate one.
     * <p>
     * <strong>The refusal races the client's flight.</strong> Since {@code c74f5d2} (#308) introduced
     * the forced alias, the client genuinely sends its TLS 1.2 flight — the foreign chain,
     * {@code CertificateVerify} and {@code Finished} — and the server aborts once it has verified the
     * chain. Which side wins is timing: the client either reads the server's fatal alert and throws an
     * {@link SSLException}, or its write of the rest of the flight hits the already-closed connection
     * and throws a {@link SocketException} ({@code Broken pipe}). Both are the same server verdict.
     * <p>
     * A {@link SocketException} is nevertheless only admitted together with proof that the server
     * refused, because a bare socket failure could equally be a dropped connection that says nothing
     * about client-certificate verification. {@link #assertHandshakeRefused(HandshakeOutcome, String)}
     * therefore requires, for either exception type, that the handshake-completed listener never
     * fired, that no valid negotiated session exists, and that no application data can be read. A
     * timeout — on the handshake or on that read — is never admitted: it means the server did not
     * refuse. {@link #trustedClientCertAccepted()} proves each of those signals can go the other way.
     */
    @Test
    @DisplayName("a client cert signed by a foreign CA is offered and rejected at the handshake")
    void wrongCaClientCertRejected() throws Exception {
        String keystorePath = System.getProperty("test.mtls.wrong.keystore");
        String password = System.getProperty("test.mtls.wrong.password", "wrong-trust");
        assertNotNull(keystorePath, "test.mtls.wrong.keystore must be set — without it the client offers"
                + " no certificate and this test would only repeat noClientCertRejected");
        Path keystore = Path.of(keystorePath);
        assertTrue(Files.isRegularFile(keystore) && Files.isReadable(keystore),
                "test.mtls.wrong.keystore must name a readable file, was: " + keystore.toAbsolutePath());
        KeyStore loaded = loadPkcs12(keystore, password);
        Optional<String> keyEntryAlias = keyEntryAlias(loaded);
        assertTrue(keyEntryAlias.isPresent(),
                "the wrong-CA keystore must hold at least one key entry, or no client identity is offered");
        String foreignAlias = keyEntryAlias.get();
        X509Certificate foreignLeaf = (X509Certificate) loaded.getCertificate(foreignAlias);
        ForcedAliasKeyManager keyManager = new ForcedAliasKeyManager(
                sunX509KeyManager(loaded, password), foreignAlias);
        SSLContext context = contextWith(new KeyManager[]{keyManager});

        HandshakeOutcome outcome = attemptHandshake(context);

        assertHandshakeRefused(outcome,
                "a client certificate signed by a CA the client_ca does not trust must be rejected");

        String offered = keyManager.offeredAlias();
        assertAll("the foreign identity was genuinely offered, so the rejection is the foreign-CA verdict",
                () -> assertTrue(keyManager.clientAliasRequested(),
                        "the handshake never asked the client for a certificate, so the rejection cannot "
                                + "be a verdict on the foreign identity"),
                () -> assertNotNull(offered,
                        "the key manager offered no client alias, so the client sent no certificate and the "
                                + "rejection is the no-certificate path noClientCertRejected already covers"),
                () -> assertEquals(foreignAlias, offered,
                        "the offered alias must be the forced foreign key entry"),
                () -> {
                    X509Certificate[] chain = offered == null ? null : keyManager.getCertificateChain(offered);
                    assertNotNull(chain, "no certificate chain stands behind the offered alias " + offered);
                    assertTrue(chain.length > 0, "the chain behind the offered alias is empty");
                    assertEquals(foreignLeaf, chain[0],
                            "the offered leaf must be the wrong-CA keystore's own certificate");
                    assertTrue(chain[0].getIssuerX500Principal().getName().contains("CN=mtls-wrong-ca"),
                            "the offered leaf must be issued by mtls-wrong-ca, was issued by "
                                    + chain[0].getIssuerX500Principal().getName());
                });
    }

    private static KeyStore loadPkcs12(Path keystore, String password)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keyStore.load(in, password == null ? new char[0] : password.toCharArray());
        }
        return keyStore;
    }

    private static Optional<String> keyEntryAlias(KeyStore keyStore) throws KeyStoreException {
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)) {
                return Optional.of(alias);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code SunX509} key manager over {@code keyStore}, pinned by name rather than taken from
     * {@link KeyManagerFactory#getDefaultAlgorithm()}. {@code SunX509} resolves certificate chains and
     * private keys by the keystore's own alias, which is what {@link ForcedAliasKeyManager} hands
     * back; the {@code PKIX} manager would expect its own prefixed alias form and find nothing.
     */
    private static X509KeyManager sunX509KeyManager(KeyStore keyStore, String password)
            throws GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password.toCharArray());
        return (X509KeyManager) kmf.getKeyManagers()[0];
    }

    /**
     * Opens a TLS connection to the mTLS listener, drives the handshake and records what happened. The
     * server certificate is trust-all (the stack's self-signed material); only the CLIENT-auth outcome
     * is under test.
     * <p>
     * A failure to connect at all propagates as a test error — an unreachable listener is not a
     * handshake refusal. When the handshake throws, the socket is probed for application data before it
     * is closed; the client itself never writes any.
     */
    private static HandshakeOutcome attemptHandshake(SSLContext context)
            throws IOException, InterruptedException {
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", mtlsPort())) {
            socket.setSoTimeout(SO_TIMEOUT_MILLIS);
            CountDownLatch completed = new CountDownLatch(1);
            socket.addHandshakeCompletedListener(event -> completed.countDown());
            IOException failure = null;
            try {
                socket.startHandshake();
            } catch (IOException e) {
                failure = e;
            }
            boolean listenerFired = completed.await(
                    failure == null ? SO_TIMEOUT_MILLIS : LISTENER_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            SSLSession session = socket.getSession();
            ApplicationRead applicationRead = failure == null
                    ? ApplicationRead.NOT_ATTEMPTED
                    : readApplicationData(socket);
            return new HandshakeOutcome(failure, listenerFired, session.isValid(), session.getCipherSuite(),
                    applicationRead);
        }
    }

    /**
     * Attempts one application-data read on a socket whose handshake threw. The read is bounded by the
     * socket timeout; a closed socket is not read at all.
     */
    private static ApplicationRead readApplicationData(SSLSocket socket) {
        if (socket.isClosed()) {
            return ApplicationRead.SOCKET_CLOSED;
        }
        try {
            return socket.getInputStream().read() == -1 ? ApplicationRead.END_OF_STREAM : ApplicationRead.DATA;
        } catch (SocketTimeoutException e) {
            return ApplicationRead.TIMED_OUT;
        } catch (IOException e) {
            return ApplicationRead.IO_FAILURE;
        }
    }

    /**
     * Asserts that the server refused the handshake. Exactly two exception types are admitted —
     * {@link SSLException} and {@link SocketException} — and neither may stem from a timeout. For both,
     * the refusal must be evidenced: the completion listener never fired, no valid negotiated session
     * exists, and the post-failure read yields no application data and does not time out. All checks
     * are reported together, so a handshake that completed shows every piece of missing evidence.
     */
    private static void assertHandshakeRefused(HandshakeOutcome outcome, String expectation) {
        IOException failure = outcome.failure();
        assertAll(expectation,
                () -> assertNotNull(failure, "the handshake completed without any exception"),
                () -> assertTrue(failure == null || isAdmissibleRefusal(failure),
                        () -> "only SSLException or a non-timeout SocketException is a handshake refusal, got: "
                                + failure),
                () -> assertFalse(outcome.listenerFired(),
                        "the handshake-completed listener fired, so the handshake was not refused"),
                () -> assertFalse(outcome.sessionValid() && !NULL_CIPHER_SUITE.equals(outcome.cipherSuite()),
                        () -> "a valid negotiated session exists (cipher suite " + outcome.cipherSuite()
                                + "), so the handshake was not refused"),
                () -> assertTrue(outcome.applicationRead().isRefusalEvidence(),
                        () -> "after the failed handshake the application-data read ended in "
                                + outcome.applicationRead() + ", which is not evidence of a refusal"));
    }

    private static boolean isAdmissibleRefusal(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return false;
            }
        }
        return failure instanceof SSLException || failure instanceof SocketException;
    }

    /** The recorded result of one handshake attempt, see {@link #attemptHandshake(SSLContext)}. */
    private record HandshakeOutcome(IOException failure, boolean listenerFired, boolean sessionValid,
    String cipherSuite, ApplicationRead applicationRead) {
    }

    /** How the application-data probe after a failed handshake ended. */
    private enum ApplicationRead {

        /** The handshake did not throw, so no probe ran. */
        NOT_ATTEMPTED(false),
        /** The failed handshake already closed the socket; nothing can be exchanged. */
        SOCKET_CLOSED(true),
        /** The peer closed the connection without sending application data. */
        END_OF_STREAM(true),
        /** The read failed without a timeout; the broken connection carries no data. */
        IO_FAILURE(true),
        /** The read timed out: the connection stayed open, which is not a refusal. */
        TIMED_OUT(false),
        /** Application bytes arrived, which a refused handshake can never produce. */
        DATA(false);

        private final boolean refusalEvidence;

        ApplicationRead(boolean refusalEvidence) {
            this.refusalEvidence = refusalEvidence;
        }

        boolean isRefusalEvidence() {
            return refusalEvidence;
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
            KeyStore keyStore = loadPkcs12(Path.of(keystorePath), password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password == null ? new char[0] : password.toCharArray());
            keyManagers = kmf.getKeyManagers();
        }
        return contextWith(keyManagers);
    }

    /**
     * Builds an SSL context over the given client key managers ({@code null} offers no client
     * certificate) with the suite's trust-all server trust.
     */
    private static SSLContext contextWith(KeyManager[] keyManagers) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        return context;
    }

    /**
     * A test-local key manager that offers one fixed client alias regardless of the certificate
     * authorities the server advertises, and records what it was asked and what it answered.
     * <p>
     * The JDK's own X509 key managers filter client aliases by the advertised issuers, so a client
     * identity from a CA the server does not name is silently withheld and the client sends no
     * certificate. Forcing the alias is what lets {@link #wrongCaClientCertRejected()} put a foreign
     * certificate in front of the server at all. Everything except client-alias selection — the
     * certificate chain, the private key and the abstract server-side methods — is delegated unchanged.
     * <p>
     * Thread-safety: the recording fields are atomics, so the handshake thread's writes are visible to
     * the asserting test thread.
     */
    static final class ForcedAliasKeyManager extends X509ExtendedKeyManager {

        private final X509KeyManager delegate;
        private final String forcedAlias;
        private final AtomicBoolean clientAliasRequested = new AtomicBoolean();
        private final AtomicReference<String> offeredAlias = new AtomicReference<>();

        ForcedAliasKeyManager(X509KeyManager delegate, String forcedAlias) {
            this.delegate = delegate;
            this.forcedAlias = forcedAlias;
        }

        boolean clientAliasRequested() {
            return clientAliasRequested.get();
        }

        String offeredAlias() {
            return offeredAlias.get();
        }

        /**
         * Answers a client-alias request with the forced alias. The answer is recorded as offered only
         * when the request's key types admit the alias's private key: the JDK asks once per
         * signature-scheme key type and discards an alias whose key algorithm does not match, so an
         * answer to a mismatched key type is not a certificate the client actually sends.
         */
        private String offer(String[] keyTypes) {
            clientAliasRequested.set(true);
            if (keyTypeAdmits(keyTypes)) {
                offeredAlias.set(forcedAlias);
            }
            return forcedAlias;
        }

        private boolean keyTypeAdmits(String[] keyTypes) {
            PrivateKey key = delegate.getPrivateKey(forcedAlias);
            return key != null && keyTypes != null && Arrays.asList(keyTypes).contains(key.getAlgorithm());
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return offer(keyType);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return offer(keyType);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
    }

    /**
     * A trust-all {@link X509TrustManager} for a stack's self-signed server certificate. Scoped
     * strictly to black-box ITs — never a production trust decision. Package-visible and shared with
     * {@code PassthroughFaultIT} and {@code TlsPassthroughIT} so the three TLS-edge ITs do not each
     * carry their own copy.
     */
    static final class TrustAllManager implements X509TrustManager {

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
