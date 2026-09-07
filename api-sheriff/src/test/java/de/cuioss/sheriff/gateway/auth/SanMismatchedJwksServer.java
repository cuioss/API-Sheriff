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
package de.cuioss.sheriff.gateway.auth;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;


import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import okhttp3.tls.HeldCertificate;

/**
 * A local HTTPS server that serves a JWKS document under a certificate whose subject alternative
 * name deliberately does <em>not</em> name the address it is dialled on — the one fixture shape that
 * can tell {@code egress_tls.jwks_verify_hostname} apart from every neighbouring control.
 *
 * <h2>Why the SAN mismatch has to be the ONLY defect</h2>
 *
 * The flag relaxes hostname <em>matching</em> and nothing else, so a fixture proves it acts only if
 * the negative leg fails for that reason alone. A plain self-signed certificate would not do: the
 * default-posture dial would fail on <em>chain trust</em>, the relaxed dial would fail on chain
 * trust too, and both legs would be red for a reason the flag does not govern — a test that passes
 * its negative leg while proving nothing about the key.
 * <p>
 * This fixture therefore issues a real two-element chain — a throwaway root CA and a leaf signed by
 * it — and installs the <em>root</em> as a trust anchor. Chain validation then succeeds on both
 * legs, and the single remaining discriminator between them is whether the dialled
 * {@value LoopbackHost#ADDRESS} is compared against the leaf's SAN
 * ({@value #MISMATCHED_SUBJECT_ALTERNATIVE_NAME}, which it can never match).
 *
 * <h2>Why the anchor is installed as the JVM default trust store</h2>
 *
 * <strong>The obvious seams are both unavailable, and not by oversight.</strong> Handing the
 * trusting context to {@code HttpJwksLoaderConfigBuilder.sslContext(...)} is refused by
 * token-sheriff's own builder guard whenever {@code verifyHostname(false)} is set — the two are
 * mutually exclusive, which is exactly the collision the production reader refuses at boot. And
 * {@code SSLContext.setDefault(...)} does not reach it either: cui-http derives BOTH contexts
 * ({@code SecureSSLContextProvider.createSecureSSLContext} and
 * {@code createHostnameRelaxedSSLContext}) from
 * {@code TrustManagerFactory.init((KeyStore) null)}, which reads the <em>default trust store</em>
 * and never consults {@code SSLContext.getDefault()}. Setting the default context would leave both
 * legs failing on chain trust while looking like it had worked.
 * <p>
 * The seam those two paths genuinely read is the {@code javax.net.ssl.trustStore*} system-property
 * family, so that is what {@link #start} sets and {@link #close} restores.
 *
 * <h2>The containment this depends on, stated because it is not local</h2>
 *
 * Replacing the default trust store is a <strong>JVM-global</strong> mutation: for as long as it is
 * installed, nothing in this JVM trusts a public certificate authority. It is safe here only because
 * the ROOT {@code pom.xml} sets {@code <reuseForks>false</reuseForks>} on
 * {@code maven-surefire-plugin} and {@code maven-failsafe-plugin}, in {@code pluginManagement} and
 * therefore reactor-wide — so every test class gets its own forked JVM and the blast radius of this
 * mutation is one class. That setting lives in the root {@code pom.xml} rather than in
 * {@code api-sheriff/pom.xml}: the containment is inherited, not local, and this fixture would
 * become a cross-class hazard the moment fork reuse were switched on. {@link #close} restoring the
 * previous property values is the second line of defence, not the first.
 *
 * <h2>Binding</h2>
 *
 * The listener binds {@link LoopbackHost#ADDRESS} explicitly rather than the wildcard, for the
 * measured reason {@code LoopbackEphemeralBindArchTest} guards: a wildcard ephemeral bind can
 * coexist with a foreign loopback listener on the same port, and the kernel then routes this
 * fixture's own client to that other process.
 * <p>
 * Hand-written rather than driven by a mocking framework, per this project's testing policy.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class SanMismatchedJwksServer implements AutoCloseable {

    /**
     * The only name the served certificate vouches for. Under the reserved {@code .invalid} TLD
     * (RFC 2606) so it can never resolve, and deliberately unrelated to
     * {@link LoopbackHost#ADDRESS} — the dialled address is an IP literal, which is matched against
     * {@code iPAddress} SANs only, so a {@code dNSName} SAN cannot match it under any resolver
     * behaviour.
     */
    static final String MISMATCHED_SUBJECT_ALTERNATIVE_NAME = "idp.san-mismatch.invalid";

    private static final String JWKS_PATH = "/jwks";
    private static final String TRUST_STORE_PROPERTY = "javax.net.ssl.trustStore";
    private static final String TRUST_STORE_PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";
    private static final String TRUST_STORE_TYPE_PROPERTY = "javax.net.ssl.trustStoreType";
    private static final String PKCS12 = "PKCS12";
    private static final char[] STORE_PASSWORD = "san-mismatch-fixture".toCharArray();

    private final HttpsServer server;
    private final String jwksUrl;
    private final String previousTrustStore;
    private final String previousTrustStorePassword;
    private final String previousTrustStoreType;

    private SanMismatchedJwksServer(HttpsServer server, String jwksUrl, String previousTrustStore,
            String previousTrustStorePassword, String previousTrustStoreType) {
        this.server = server;
        this.jwksUrl = jwksUrl;
        this.previousTrustStore = previousTrustStore;
        this.previousTrustStorePassword = previousTrustStorePassword;
        this.previousTrustStoreType = previousTrustStoreType;
    }

    /**
     * Issues the chain, starts the listener, and installs the root as the JVM default trust anchor.
     *
     * @param workDir       a writable directory the generated trust store is written into; a
     *                      {@code @TempDir} in practice
     * @param jwksDocument  the JWKS JSON to serve on every request to the JWKS path
     * @return the running fixture; close it to stop the listener and restore the trust store
     * @throws Exception when the certificate chain, the key material or the listener cannot be built
     */
    static SanMismatchedJwksServer start(Path workDir, String jwksDocument) throws Exception {
        HeldCertificate root = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName("API Sheriff SAN-mismatch test root")
                .duration(1, TimeUnit.HOURS)
                .build();
        // The leaf names ONLY the mismatched SAN. Naming the dialled address as well would make both
        // legs succeed and the negative control would silently stop controlling anything.
        HeldCertificate leaf = new HeldCertificate.Builder()
                .signedBy(root)
                .commonName(MISMATCHED_SUBJECT_ALTERNATIVE_NAME)
                .addSubjectAlternativeName(MISMATCHED_SUBJECT_ALTERNATIVE_NAME)
                .duration(1, TimeUnit.HOURS)
                .build();

        Path trustStorePath = writeTrustStore(workDir, root.certificate());
        HttpsServer server = startListener(serverContext(root, leaf), jwksDocument);
        String jwksUrl = "https://" + LoopbackHost.ADDRESS + ":" + server.getAddress().getPort() + JWKS_PATH;

        String previousTrustStore = System.getProperty(TRUST_STORE_PROPERTY);
        String previousTrustStorePassword = System.getProperty(TRUST_STORE_PASSWORD_PROPERTY);
        String previousTrustStoreType = System.getProperty(TRUST_STORE_TYPE_PROPERTY);
        System.setProperty(TRUST_STORE_PROPERTY, trustStorePath.toString());
        System.setProperty(TRUST_STORE_PASSWORD_PROPERTY, new String(STORE_PASSWORD));
        System.setProperty(TRUST_STORE_TYPE_PROPERTY, PKCS12);

        return new SanMismatchedJwksServer(server, jwksUrl, previousTrustStore,
                previousTrustStorePassword, previousTrustStoreType);
    }

    /**
     * @return the {@code https} URL an issuer's {@code jwks.url} must name to reach this server. Its
     *         host is {@link LoopbackHost#ADDRESS}, which the served certificate does not vouch for
     */
    String jwksUrl() {
        return jwksUrl;
    }

    /**
     * @return the host the {@link #jwksUrl()} dials, for the issuer's {@code allowed_egress_hosts}.
     *         Loopback is refused by token-sheriff's SSRF egress guard unless it is named, so an
     *         issuer pointing here must allowlist it or the dial never reaches the TLS handshake at
     *         all — which would fail both legs for a reason unrelated to hostname verification
     */
    static String dialledHost() {
        return LoopbackHost.ADDRESS;
    }

    @Override
    public void close() {
        server.stop(0);
        restore(TRUST_STORE_PROPERTY, previousTrustStore);
        restore(TRUST_STORE_PASSWORD_PROPERTY, previousTrustStorePassword);
        restore(TRUST_STORE_TYPE_PROPERTY, previousTrustStoreType);
    }

    private static void restore(String property, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previousValue);
        }
    }

    /**
     * Writes a PKCS12 store holding {@code anchor} as its single trusted certificate entry.
     *
     * @param workDir the directory to write into
     * @param anchor  the root certificate to trust
     * @return the written store's path
     * @throws Exception when the store cannot be created or written
     */
    private static Path writeTrustStore(Path workDir, X509Certificate anchor) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(PKCS12);
        trustStore.load(null, null);
        trustStore.setCertificateEntry("san-mismatch-root", anchor);
        Path path = workDir.resolve("san-mismatch-truststore.p12");
        try (OutputStream out = Files.newOutputStream(path)) {
            trustStore.store(out, STORE_PASSWORD);
        }
        return path;
    }

    /**
     * @param root the issuing CA, sent alongside the leaf so the client can build the chain up to
     *             the anchor it holds
     * @param leaf the server certificate carrying the mismatched SAN
     * @return the server-side context presenting {@code leaf} plus {@code root}
     * @throws Exception when the key material cannot be loaded into a context
     */
    private static SSLContext serverContext(HeldCertificate root, HeldCertificate leaf) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PKCS12);
        keyStore.load(null, null);
        keyStore.setKeyEntry("san-mismatch-leaf", leaf.keyPair().getPrivate(), STORE_PASSWORD,
                new X509Certificate[]{leaf.certificate(), root.certificate()});
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, STORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    /**
     * @param context      the server-side TLS context
     * @param jwksDocument the body every JWKS request is answered with
     * @return the started listener, bound to {@link LoopbackHost#ADDRESS} on an ephemeral port
     * @throws Exception when the listener cannot be created
     */
    private static HttpsServer startListener(SSLContext context, String jwksDocument) throws Exception {
        HttpsServer server = HttpsServer.create(
                new InetSocketAddress(InetAddress.getByName(LoopbackHost.ADDRESS), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        byte[] body = jwksDocument.getBytes(StandardCharsets.UTF_8);
        server.createContext(JWKS_PATH, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
