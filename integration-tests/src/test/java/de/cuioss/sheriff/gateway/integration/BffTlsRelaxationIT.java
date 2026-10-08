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

import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.MEDIATED_PATH;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.TOKEN_REFRESHED_RECORD;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.WAIT_INTO_REFRESH_WINDOW_SECONDS;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.bearerToken;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.mediatedCall;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.sleepSeconds;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.CERTIFICATES;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.RETRY_SCHEDULED_RECORD;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertApplicationPortAnswers;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitLogRecord;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.connectToNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.createNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.disconnectFromNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.mintIntegrationRealmToken;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.readinessData;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.securedAssetStatus;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startAuxiliaryContainerOnNetworks;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.ReadOnlyMount;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, on the native image and against a real identity provider, what the two hostname relaxations
 * of the back-channel to the identity provider reach and what neither of them reaches:
 * {@code egress_tls.oidc_verify_hostname} (discovery, the pushed authorization request, the code
 * exchange, refresh) and {@code egress_tls.jwks_verify_hostname} (the key-set fetch).
 * <p>
 * <strong>The rig.</strong> Each test starts its own, and removes it on every exit path:
 * <ul>
 *   <li>a dedicated docker network;</li>
 *   <li>a TLS proxy of the compose Keycloak ({@value #PROXY_IMAGE}, rendered from
 *       {@code tls-relaxation-idp/default.conf.template}), which answers on that network under the
 *       name {@value #IDENTITY_PROVIDER_ALIAS} — the host every identity-provider URL of the fixture
 *       descriptor names — and presents the certificate the test chooses;</li>
 *   <li>a one-off gateway on that network, mounting the fixture descriptor
 *       {@code sheriff-config-tls-relaxation/gateway.yaml} over the shared configuration directory.
 *       The descriptor names no trust profile, so both legs verify the proxy's chain against the JVM
 *       default trust store, and the gateway process is given that store on its command line: the
 *       committed {@code upstream-truststore.p12}, which holds one certificate.</li>
 * </ul>
 * The compose echo container is connected to the network for the life of a rig, so the session route
 * has its upstream.
 * <p>
 * <strong>The two certificates, and why the premises are asserted.</strong> A leg proves something
 * about hostname matching only if the proxy's certificate is trusted and does not name the host, and
 * something about chain trust only if the certificate names the host and is not trusted. Each test
 * therefore first asserts those two facts on the committed certificate it hands the proxy, against the
 * committed trust store, and then asserts that the proxy serves the realm's metadata to a client that
 * verifies nothing — so a refusal that follows is the gateway's verdict on the certificate, not a
 * proxy that is not serving. That client is a one-shot container on the rig's network, which dials the
 * proxy under the name the gateway dials it under: the proxy is attached to two networks and therefore
 * publishes no host port (see {@link OneOffGatewayContainers#assertLeavesNoDeadHostPort(String, List, int)}).
 * <p>
 * <strong>What the three tests prove.</strong>
 * <ol>
 *   <li><em>Both relaxations, a trusted certificate that does not name the host.</em> Readiness is
 *       {@code UP} with every key set loaded, and a login completes through the pushed request and the
 *       code exchange, a mediated call relays a bearer, and a refresh rotates that bearer. Every one of
 *       those calls dials a host the certificate does not name.</li>
 *   <li><em>The key-set relaxation alone, the same certificate.</em> The descriptor is the fixture with
 *       {@code oidc_verify_hostname} removed — asserted to be the only difference. Readiness is still
 *       {@code UP} and a bearer token of the realm is accepted, while a login is refused before the
 *       browser is redirected anywhere. The two legs differ in one key, so the login of the first leg
 *       is attributed to that key, and the two keys are shown to govern different legs.</li>
 *   <li><em>Both relaxations, a certificate that names the host and is not trusted.</em> No key set
 *       of the identity provider loads, readiness stays {@code DOWN}, the realm's token is rejected and
 *       a login is refused. Neither relaxation reached chain trust.</li>
 * </ol>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not exercise
 * {@code egress_tls.upstream_verify_hostname}, which {@code UpstreamHostnameVerificationIT} covers on
 * the compose pair, nor the refusal of a relaxation beside a named trust profile, which is a boot
 * refusal covered at unit level. It does not prove the opposite single-variable leg — the back-channel
 * relaxed and the key-set fetch not. The refused login is asserted as a server-error answer that
 * carries no redirect; the suite does not pin which {@code 5xx} it is. Like every {@code Bff*IT} it
 * replays a cookie map and asserts nothing about browser cookie policy.
 * <p>
 * <strong>The JSSE arguments.</strong> This is the one launch site of the module that passes JSSE
 * trust-store system properties to a gateway; see {@link #trustStoreArguments()}.
 * {@code ItProfileConfigBindingWiringTest} exempts exactly this file from its launch-site guard, and
 * fails if the arguments are dropped while the exemption stays. That guard scans this source for the
 * property prefix, which is why the prefix is spelled nowhere in this file but in those arguments.
 * <p>
 * <strong>Timing.</strong> The first test waits on the wall clock for the session's access token to
 * enter the refresh leeway: the property is defined against a token lifetime, so there is no state to
 * poll for. One rig at a time — the container names, the network name and the gateway port are fixed.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffTlsRelaxationIT {

    /**
     * The proxy's image, pinned by release tag <em>and</em> by the digest of its multi-platform index.
     * The container it names is handed the certificates directory, fronts every identity-provider call
     * of the gateway under test and runs the serving control's client, so a tag that is moved to
     * another image must not change what runs. {@code StubIdentityProviderWiringTest} holds the shape
     * of the reference, which is why the constant is visible to the package.
     */
    static final String PROXY_IMAGE =
            "nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10";

    /**
     * The fixed host port of the gateway's application listener, published on every interface (see
     * {@link OneOffGatewayContainers#applicationPortPublication(int)}); the port of the origin the
     * fixture descriptor names. The compose stack publishes {@code 10443}–{@code 10455}; the other
     * one-off gateways take {@code 10459}, {@code 10462} and {@code 10464}–{@code 10468}.
     */
    private static final int APPLICATION_PORT = 10463;

    private static final String ORIGIN = "https://localhost:" + APPLICATION_PORT;

    private static final String NETWORK = "sheriff-tls-relaxation";
    private static final String PROXY = "sheriff-tls-relaxation-idp";
    private static final String GATEWAY = "sheriff-tls-relaxation-gateway";

    /** The name the fixture descriptor's issuer, endpoints and every {@code jwks.url} dial. */
    private static final String IDENTITY_PROVIDER_ALIAS = "keycloak";
    private static final String ISSUER = "https://" + IDENTITY_PROVIDER_ALIAS + ":8443/realms/integration";
    private static final String METADATA_URL = ISSUER + "/.well-known/openid-configuration";

    /** The longest one metadata request of the serving control may take, in seconds. */
    private static final String METADATA_REQUEST_SECONDS = "10";

    /** What the serving control's client prints after the body: a line of its own holding the status. */
    private static final String STATUS_LINE_FORMAT = "\n%{http_code}";

    private static final String KEYCLOAK_SERVICE = "keycloak";

    /** The compose service the session route relays to, and the name it dials it under. */
    private static final String ECHO_SERVICE = "go-httpbin";

    private static final Path FIXTURE_DESCRIPTOR =
            DOCKER.resolve(Path.of("sheriff-config-tls-relaxation", "gateway.yaml"));
    private static final Path PROXY_TEMPLATE = DOCKER.resolve(Path.of("tls-relaxation-idp", "default.conf.template"));
    private static final String PROXY_TEMPLATE_MOUNT = "/etc/nginx/templates/default.conf.template";
    private static final String PROXY_CERTIFICATES_MOUNT = "/etc/nginx/certificates";

    /** Where the single-variable descriptor is written; a build output, never committed. */
    private static final Path DERIVED_DESCRIPTOR = Path.of("target", "tls-relaxation", "gateway.yaml");

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    private static final String EGRESS_TLS_BLOCK = "egress_tls";
    private static final String OIDC_VERIFY_HOSTNAME_KEY = "oidc_verify_hostname";
    private static final String JWKS_VERIFY_HOSTNAME_KEY = "jwks_verify_hostname";

    /** The fixture's line the single-variable descriptor drops, restoring the key to its default. */
    private static final String OIDC_RELAXATION_LINE = "  " + OIDC_VERIFY_HOSTNAME_KEY + ": false\n";

    /** In the gateway's trust store, and names {@code upstream-mismatch} only. */
    private static final String TRUSTED_NOT_NAMING_THE_HOST = "upstream-mismatch";

    /** Names {@code keycloak}, and is not in the gateway's trust store. */
    private static final String NAMING_THE_HOST_NOT_TRUSTED = "localhost";

    /** The deployment file that binds the committed trust store; read for its path and its secret. */
    private static final Path TRUST_STORE_BINDING = CERTIFICATES.resolve("it-upstream-trust.properties");
    private static final String TRUST_STORE_PATH_KEY = "quarkus.tls.it-upstream.trust-store.p12.path";
    private static final String TRUST_STORE_SECRET_KEY = "quarkus.tls.it-upstream.trust-store.p12.password";
    private static final String CERTIFICATES_IN_CONTAINER = "/app/certificates/";

    private static final Path REALM_IMPORT = DOCKER.resolve(Path.of("keycloak", "integration-realm.json"));
    private static final String CLIENT_ID = "refresh-client";

    private static final String SIGNING_KEYS_MOUNT = "/app/signing-keys";
    private static final String PROOF_KEY_FILE = "dpop-rsa.pem";

    /** WARN — hostname verification of the key-set fetch is switched off. */
    private static final String JWKS_RELAXED_RECORD = "ApiSheriff-120:";

    /** WARN — hostname verification of the BFF OIDC back-channel is switched off. */
    private static final String OIDC_RELAXED_RECORD = "ApiSheriff-125:";

    /** The {@code GeneralName} tag of a DNS subject alternative name (RFC 5280). */
    private static final int DNS_NAME = 2;

    private static final long BOOT_TIMEOUT_SECONDS = 90L;
    private static final long PROXY_TIMEOUT_SECONDS = 60L;
    private static final long POLL_INTERVAL_MILLIS = 250L;

    /**
     * Upper bound for the first key-set load attempt to give up and schedule its retry: the HTTP
     * client's own retries take about seventeen seconds before the gateway records the failure.
     */
    private static final long RETRY_SCHEDULED_TIMEOUT_SECONDS = 60L;

    @Test
    @DisplayName("both relaxations: a trusted certificate that does not name the host serves the key sets, a login, a mediated call and a refresh")
    void bothRelaxationsAdmitATrustedCertificateThatDoesNotNameTheHost() {
        assertTrustedButNotNamingTheHost(TRUSTED_NOT_NAMING_THE_HOST);
        try (Rig rig = Rig.start(TRUSTED_NOT_NAMING_THE_HOST, FIXTURE_DESCRIPTOR)) {
            Response ready = rig.awaitReady();
            String bootLog = rig.gatewayOutput();
            assertAll("the gateway booted on both relaxations and loaded every key set through the proxy",
                    () -> assertEquals("UP", ready.path("status"), () -> ready.asString()),
                    () -> assertEquals(issuers(ready), issuersLoaded(ready), () -> ready.asString()),
                    () -> assertTrue(bootLog.contains(JWKS_RELAXED_RECORD),
                            () -> "the boot log must announce the key-set relaxation. " + gatewayLog(GATEWAY)),
                    () -> assertTrue(bootLog.contains(OIDC_RELAXED_RECORD),
                            () -> "the boot log must announce the back-channel relaxation. " + gatewayLog(GATEWAY)));

            Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH, ORIGIN,
                    BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
            long loggedInAt = System.nanoTime();
            String bearerBefore = bearerToken(mediatedCall(session.gatewayCookies(), ORIGIN));

            long elapsed = Duration.ofNanos(System.nanoTime() - loggedInAt).toSeconds();
            sleepSeconds(Math.max(0, WAIT_INTO_REFRESH_WINDOW_SECONDS - elapsed));
            String bearerAfter = bearerToken(mediatedCall(session.gatewayCookies(), ORIGIN));

            assertAll("the refresh went through the proxy as well",
                    () -> assertNotEquals(bearerBefore, bearerAfter,
                            "a call inside the refresh leeway must relay a rotated bearer"),
                    () -> assertTrue(rig.gatewayOutput().contains(TOKEN_REFRESHED_RECORD),
                            () -> "the refresh must be recorded. " + gatewayLog(GATEWAY)));
        }
    }

    @Test
    @DisplayName("the key-set relaxation alone: the key sets load and a bearer token is accepted, a login is refused before any redirect")
    void theKeySetRelaxationAloneDoesNotAdmitTheBackChannel() {
        assertTrustedButNotNamingTheHost(TRUSTED_NOT_NAMING_THE_HOST);
        Path descriptor = writeDescriptorWithoutTheBackChannelRelaxation();
        try (Rig rig = Rig.start(TRUSTED_NOT_NAMING_THE_HOST, descriptor)) {
            Response ready = rig.awaitReady();
            String bootLog = rig.gatewayOutput();
            assertAll("the gateway booted on the key-set relaxation alone and loaded every key set",
                    () -> assertEquals("UP", ready.path("status"), () -> ready.asString()),
                    () -> assertEquals(issuers(ready), issuersLoaded(ready), () -> ready.asString()),
                    () -> assertTrue(bootLog.contains(JWKS_RELAXED_RECORD),
                            () -> "the boot log must announce the key-set relaxation. " + gatewayLog(GATEWAY)),
                    () -> assertFalse(bootLog.contains(OIDC_RELAXED_RECORD),
                            () -> "the back-channel relaxation must not be in effect. " + gatewayLog(GATEWAY)));

            String bearer = mintIntegrationRealmToken();
            assertAll("the key set fetched through the mismatching certificate validates the realm's token",
                    () -> assertEquals(200, securedAssetStatus(ORIGIN, bearer),
                            () -> "a bearer token of the realm must be accepted. " + gatewayLog(GATEWAY)),
                    () -> assertEquals(401, securedAssetStatus(ORIGIN, null),
                            "control: the route still refuses a request without a token"));

            assertLoginRefusedBeforeAnyRedirect("with the back-channel verifying the host name, a certificate "
                    + "that does not name the identity provider must refuse the login");
        }
    }

    @Test
    @DisplayName("both relaxations: a certificate that names the host and is not trusted loads no key set and serves no login")
    void neitherRelaxationReachesChainTrust() {
        assertNamingTheHostButNotTrusted(NAMING_THE_HOST_NOT_TRUSTED);
        try (Rig rig = Rig.start(NAMING_THE_HOST_NOT_TRUSTED, FIXTURE_DESCRIPTOR)) {
            Response booted = rig.awaitAnswer();
            String bootLog = rig.gatewayOutput();
            assertAll("the gateway booted on both relaxations",
                    () -> assertEquals(503, booted.statusCode(), () -> booted.asString()),
                    () -> assertTrue(bootLog.contains(JWKS_RELAXED_RECORD),
                            () -> "the boot log must announce the key-set relaxation. " + gatewayLog(GATEWAY)),
                    () -> assertTrue(bootLog.contains(OIDC_RELAXED_RECORD),
                            () -> "the boot log must announce the back-channel relaxation. " + gatewayLog(GATEWAY)));

            awaitLogRecord(GATEWAY, RETRY_SCHEDULED_RECORD, RETRY_SCHEDULED_TIMEOUT_SECONDS,
                    "a key-set load against an untrusted certificate must fail and schedule its retry, "
                            + "announced by WARN " + RETRY_SCHEDULED_RECORD);
            assertApplicationPortAnswers(GATEWAY, ORIGIN);
            Response down = rig.awaitAnswer();
            String bearer = mintIntegrationRealmToken();
            assertAll("no key set of the identity provider loaded",
                    () -> assertEquals(503, down.statusCode(), () -> down.asString()),
                    () -> assertEquals("DOWN", down.path("status"), () -> down.asString()),
                    () -> assertTrue(issuersLoaded(down) < issuers(down), () -> down.asString()),
                    () -> assertEquals(401, securedAssetStatus(ORIGIN, bearer),
                            () -> "without its key set the realm's token must be rejected. " + gatewayLog(GATEWAY)));

            assertLoginRefusedBeforeAnyRedirect("a certificate the trust store does not hold must refuse the "
                    + "login, whatever the two hostname keys say");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    /**
     * A navigation onto the session route, which would start a login: it must be answered with a
     * server error that redirects the browser nowhere.
     */
    private static void assertLoginRefusedBeforeAnyRedirect(String why) {
        Response navigation = BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
        int status = navigation.statusCode();
        assertAll(why,
                () -> assertTrue(status >= 500 && status < 600,
                        () -> "expected a server-error answer, was " + status + ". " + gatewayLog(GATEWAY)),
                () -> assertNull(navigation.getHeader("Location"),
                        "a refused login must not redirect the browser"));
    }

    private static int issuers(Response readiness) {
        return ((Number) readinessData(readiness, "issuers")).intValue();
    }

    private static int issuersLoaded(Response readiness) {
        return ((Number) readinessData(readiness, "issuers_loaded")).intValue();
    }

    /** The premise of a hostname leg: the trust store holds the certificate, and it does not name the host. */
    private static void assertTrustedButNotNamingTheHost(String certificateName) {
        X509Certificate certificate = certificate(certificateName);
        assertAll("the premises of a hostname-matching leg, on " + certificateName + ".crt",
                () -> assertTrue(trustStoreHolds(certificate),
                        "the gateway's trust store must hold the certificate, or the leg tests chain trust"),
                () -> assertFalse(dnsNames(certificate).contains(IDENTITY_PROVIDER_ALIAS),
                        () -> "the certificate must not name " + IDENTITY_PROVIDER_ALIAS + ", or there is no "
                                + "hostname mismatch to relax; it names " + dnsNames(certificate)));
    }

    /** The premise of the chain-trust leg: the certificate names the host, and the trust store does not hold it. */
    private static void assertNamingTheHostButNotTrusted(String certificateName) {
        X509Certificate certificate = certificate(certificateName);
        assertAll("the premises of the chain-trust leg, on " + certificateName + ".crt",
                () -> assertTrue(dnsNames(certificate).contains(IDENTITY_PROVIDER_ALIAS),
                        () -> "the certificate must name " + IDENTITY_PROVIDER_ALIAS + ", or the leg tests hostname "
                                + "matching; it names " + dnsNames(certificate)),
                () -> assertFalse(trustStoreHolds(certificate),
                        "the gateway's trust store must not hold the certificate, or the chain is trusted"));
    }

    // ---------------------------------------------------------------------------------------------
    // Committed material
    // ---------------------------------------------------------------------------------------------

    private static X509Certificate certificate(String name) {
        Path file = CERTIFICATES.resolve(name + ".crt");
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the certificate " + file, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot parse the certificate " + file, e);
        }
    }

    private static List<String> dnsNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            assertNotNull(names, "the certificate carries no subject alternative name");
            return names.stream().filter(name -> Integer.valueOf(DNS_NAME).equals(name.get(0)))
                    .map(name -> String.valueOf(name.get(1))).toList();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot read the subject alternative names", e);
        }
    }

    /** Whether the committed trust store the gateway is started on holds exactly this certificate. */
    private static boolean trustStoreHolds(X509Certificate certificate) {
        Properties binding = trustStoreBinding();
        Path store = CERTIFICATES.resolve(trustStoreFileName(binding));
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            trustStore.load(in, binding.getProperty(TRUST_STORE_SECRET_KEY).toCharArray());
            return trustStore.getCertificateAlias(certificate) != null;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the trust store " + store, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot open the trust store " + store, e);
        }
    }

    private static Properties trustStoreBinding() {
        Properties binding = new Properties();
        try (InputStream in = Files.newInputStream(TRUST_STORE_BINDING)) {
            binding.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + TRUST_STORE_BINDING, e);
        }
        assertAll(TRUST_STORE_BINDING + " must bind the trust store this suite starts the gateway on",
                () -> assertNotNull(binding.getProperty(TRUST_STORE_PATH_KEY), TRUST_STORE_PATH_KEY),
                () -> assertNotNull(binding.getProperty(TRUST_STORE_SECRET_KEY), TRUST_STORE_SECRET_KEY));
        return binding;
    }

    private static String trustStoreFileName(Properties binding) {
        String path = binding.getProperty(TRUST_STORE_PATH_KEY);
        assertTrue(path.startsWith(CERTIFICATES_IN_CONTAINER),
                () -> TRUST_STORE_PATH_KEY + " must name a file of the mounted certificates directory, was " + path);
        return path.substring(CERTIFICATES_IN_CONTAINER.length());
    }

    /**
     * The arguments that put the gateway process on the committed trust store: the store, its secret
     * and its type, as JSSE system properties. They follow the image name, where the native executable
     * reads them as runtime properties.
     */
    private static List<String> trustStoreArguments() {
        Properties binding = trustStoreBinding();
        return List.of(
                "-Djavax.net.ssl.trustStore=" + binding.getProperty(TRUST_STORE_PATH_KEY),
                "-Djavax.net.ssl.trustStorePassword=" + binding.getProperty(TRUST_STORE_SECRET_KEY),
                "-Djavax.net.ssl.trustStoreType=PKCS12");
    }

    /**
     * The secret the realm import registers for {@code refresh-client}, the client the fixture
     * descriptor authenticates as. Package-private: {@code BffSessionAbsoluteTtlIT} starts a one-off
     * gateway as the same client and reads the secret the same way.
     *
     * @return the client secret
     */
    static String refreshClientSecret() {
        Object clients = loadYaml(REALM_IMPORT).get("clients");
        for (Object entry : assertInstanceOf(List.class, clients, REALM_IMPORT + " must declare a clients array")) {
            Map<?, ?> client = assertInstanceOf(Map.class, entry, "a realm client must be a mapping");
            if (CLIENT_ID.equals(client.get("clientId"))) {
                return assertInstanceOf(String.class, client.get("secret"),
                        "realm client " + CLIENT_ID + " must declare the secret the gateway presents");
            }
        }
        return fail("the realm import declares no client " + CLIENT_ID + ": " + REALM_IMPORT);
    }

    /**
     * Writes the single-variable descriptor: the committed fixture without its
     * {@code oidc_verify_hostname} line, so that key resolves to its default, {@code true}. The result
     * is asserted to differ from the fixture in that one key and in nothing else.
     *
     * @return the derived descriptor
     */
    private static Path writeDescriptorWithoutTheBackChannelRelaxation() {
        String fixture;
        try {
            fixture = Files.readString(FIXTURE_DESCRIPTOR);
            assertEquals(fixture.indexOf(OIDC_RELAXATION_LINE), fixture.lastIndexOf(OIDC_RELAXATION_LINE),
                    () -> FIXTURE_DESCRIPTOR + " must declare " + OIDC_VERIFY_HOSTNAME_KEY + ": false exactly once");
            assertTrue(fixture.contains(OIDC_RELAXATION_LINE),
                    () -> FIXTURE_DESCRIPTOR + " must declare " + OIDC_VERIFY_HOSTNAME_KEY + ": false, the line the "
                            + "derivation removes");
            Files.createDirectories(DERIVED_DESCRIPTOR.getParent());
            Files.writeString(DERIVED_DESCRIPTOR, fixture.replace(OIDC_RELAXATION_LINE, ""));
            Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(DESCRIPTOR_MODE));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot derive a descriptor from " + FIXTURE_DESCRIPTOR, e);
        }

        Map<String, Object> expected = loadYaml(FIXTURE_DESCRIPTOR);
        Map<String, Object> expectedEgressTls = new LinkedHashMap<>(mapping(expected.get(EGRESS_TLS_BLOCK)));
        assertEquals(Boolean.FALSE, expectedEgressTls.remove(OIDC_VERIFY_HOSTNAME_KEY),
                () -> FIXTURE_DESCRIPTOR + " must declare " + EGRESS_TLS_BLOCK + "." + OIDC_VERIFY_HOSTNAME_KEY + ": false");
        expected.put(EGRESS_TLS_BLOCK, expectedEgressTls);
        Map<String, Object> derived = loadYaml(DERIVED_DESCRIPTOR);
        assertAll("the derived descriptor " + DERIVED_DESCRIPTOR,
                () -> assertEquals(expected, derived, "it must be the fixture without the one key, and nothing else"),
                () -> assertEquals(Map.of(JWKS_VERIFY_HOSTNAME_KEY, Boolean.FALSE), derived.get(EGRESS_TLS_BLOCK),
                        "its " + EGRESS_TLS_BLOCK + " block must keep the key-set relaxation alone"));
        return DERIVED_DESCRIPTOR;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object node) {
        return (Map<String, Object>) assertInstanceOf(Map.class, node, "expected a mapping");
    }

    private static Map<String, Object> loadYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return mapping(new Yaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The rig
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@code docker create} options of the identity-provider proxy: the rig's network, the
     * identity provider's alias, the certificate it presents, its upstream and its two mounts — and no
     * published port, because the proxy is attached to the compose network as well. Package-private so
     * the harness test can hold them to the publication rule without a docker daemon.
     *
     * @param certificate       the base name of the certificate pair the proxy presents
     * @param keycloakContainer the name the proxy dials its upstream under
     * @return the options placed before the proxy image
     */
    static List<String> proxyRunOptions(String certificate, String keycloakContainer) {
        return List.of("--network", NETWORK,
                "--network-alias", IDENTITY_PROVIDER_ALIAS,
                "-e", "TLS_RELAXATION_CERTIFICATE=" + certificate,
                "-e", "TLS_RELAXATION_UPSTREAM=" + keycloakContainer,
                "-v", PROXY_TEMPLATE.toAbsolutePath() + ":" + PROXY_TEMPLATE_MOUNT + ":ro",
                "-v", CERTIFICATES.toAbsolutePath() + ":" + PROXY_CERTIFICATES_MOUNT + ":ro");
    }

    /**
     * One network, one proxy and one gateway. Closing it removes all three and disconnects the echo
     * container, whatever the outcome of the test it served.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    private static final class Rig implements AutoCloseable {

        /** The compose echo container this rig connected to its network; empty until it is resolved. */
        private String echoContainer = "";

        /** The published management origin of the gateway; empty until the gateway is started. */
        private String managementOrigin = "";

        private Rig() {
            // started through start()
        }

        /**
         * Starts the network, the proxy and the gateway. Whatever a failed start had already created is
         * removed before the failure propagates.
         *
         * @param proxyCertificate the base name of the certificate pair the proxy presents
         * @param descriptor       the gateway descriptor mounted over the shared configuration
         * @return the running rig; close it to remove everything it started
         */
        static Rig start(String proxyCertificate, Path descriptor) {
            Rig rig = new Rig();
            boolean started = false;
            try {
                rig.boot(proxyCertificate, descriptor);
                started = true;
                return rig;
            } finally {
                if (!started) {
                    rig.close();
                }
            }
        }

        private void boot(String proxyCertificate, Path descriptor) {
            Map<String, String> compose = ContainerHealthInspector.composeContainers();
            String echo = compose.get(ECHO_SERVICE);
            String keycloak = compose.get(KEYCLOAK_SERVICE);
            assertAll("the compose project must run the services this rig builds on: " + compose,
                    () -> assertNotNull(echo, ECHO_SERVICE),
                    () -> assertNotNull(keycloak, KEYCLOAK_SERVICE));
            echoContainer = echo;
            // An aborted earlier run may have left its containers and its network behind.
            close();

            createNetwork(NETWORK);
            startProxy(proxyCertificate, containerName(keycloak));
            connectToNetwork(NETWORK, echo, ECHO_SERVICE);
            startBffGateway(
                    new BffGateway(GATEWAY, NETWORK, GATEWAY, APPLICATION_PORT, descriptor, "localhost.crt",
                            "localhost.key",
                            List.of("OIDC_CLIENT_SECRET=" + refreshClientSecret(),
                                    "OIDC_DPOP_KEY_FILE=" + SIGNING_KEYS_MOUNT + "/" + PROOF_KEY_FILE)),
                    List.of(new ReadOnlyMount(DOCKER.resolve("signing-keys"), SIGNING_KEYS_MOUNT)),
                    trustStoreArguments());
            managementOrigin = "https://localhost:" + publishedPort(GATEWAY, 9000);
        }

        /**
         * Creates the proxy on the rig's network under the identity provider's name, attaches it to
         * the compose network as well, and only then starts it: nginx resolves the name of its upstream
         * when it starts, and that name lives on the compose network. A container on two networks may
         * publish no port on loopback, and the proxy publishes none at all; the serving control reaches
         * it from inside the rig's network.
         */
        private static void startProxy(String certificate, String keycloakContainer) {
            startAuxiliaryContainerOnNetworks(PROXY, PROXY_IMAGE, proxyRunOptions(certificate, keycloakContainer),
                    List.of(), List.of(composeNetwork()));
            awaitProxyServing();
        }

        /** The name of a container, which is what another container of its network resolves. */
        private static String containerName(String container) {
            String name = docker("read the name of " + container, "inspect", "--format", "{{.Name}}", container);
            return name.startsWith("/") ? name.substring(1) : name;
        }

        /**
         * The serving control: to a client that verifies neither chain nor host name, the proxy must
         * serve the realm's own metadata. A gateway that is refused afterwards was refused on the
         * certificate.
         * <p>
         * The client is {@code curl} in a one-shot container of the proxy's own image, on the rig's
         * network and on no other, so it resolves {@value #IDENTITY_PROVIDER_ALIAS} to the proxy as the
         * gateway does. It publishes nothing and removes itself.
         */
        @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll of an external container's state
        private static void awaitProxyServing() {
            long deadline = System.nanoTime() + Duration.ofSeconds(PROXY_TIMEOUT_SECONDS).toNanos();
            String lastObservation = "no answer yet";
            while (System.nanoTime() < deadline) {
                String observed = dockerQuietly("run", "--rm", "--network", NETWORK, PROXY_IMAGE,
                        "curl", "--silent", "--show-error", "--insecure",
                        "--max-time", METADATA_REQUEST_SECONDS,
                        "--write-out", STATUS_LINE_FORMAT, METADATA_URL);
                int statusLine = observed.lastIndexOf('\n');
                if (statusLine > 0 && "200".equals(observed.substring(statusLine + 1).strip())) {
                    assertEquals(ISSUER, JsonPath.from(observed.substring(0, statusLine)).getString("issuer"),
                            "the proxy must serve the metadata of the realm the fixture descriptor names");
                    return;
                }
                lastObservation = observed;
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the identity-provider proxy",
                            interrupted);
                }
            }
            fail("timed out after " + PROXY_TIMEOUT_SECONDS + "s waiting for the identity-provider proxy to serve "
                    + "the realm metadata. Last observation: " + lastObservation + " Proxy log:\n"
                    + dockerQuietly("logs", PROXY));
        }

        /**
         * @return the readiness answer once the gateway reports {@code UP}
         */
        Response awaitReady() {
            Response ready = awaitReadiness(GATEWAY, managementOrigin, response -> response.statusCode() == 200,
                    BOOT_TIMEOUT_SECONDS, "the TLS-relaxation gateway to report readiness UP");
            assertApplicationPortAnswers(GATEWAY, ORIGIN);
            return ready;
        }

        /**
         * @return the first readiness answer the gateway gives, whatever its status
         */
        Response awaitAnswer() {
            return awaitReadiness(GATEWAY, managementOrigin, response -> true, BOOT_TIMEOUT_SECONDS,
                    "the TLS-relaxation gateway's management interface to answer");
        }

        /**
         * @return the gateway's merged container output so far
         */
        String gatewayOutput() {
            return docker("read the log of " + GATEWAY + ", a container of " + OneOffGatewayContainers.IMAGE,
                    "logs", GATEWAY);
        }

        /**
         * Removes the gateway, the proxy and the network, and disconnects the echo container. Every
         * step is attempted whatever the outcome of the one before, and none of them fails the caller.
         * Idempotent.
         */
        @Override
        public void close() {
            removeContainer(GATEWAY);
            removeContainer(PROXY);
            if (!echoContainer.isEmpty()) {
                disconnectFromNetwork(NETWORK, echoContainer);
            }
            removeNetwork(NETWORK);
        }
    }
}
