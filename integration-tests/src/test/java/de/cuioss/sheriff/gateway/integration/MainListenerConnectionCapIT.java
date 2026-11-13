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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.CERTIFICATES;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.SECURE_ASSET;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.loadDescriptor;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.mintIntegrationRealmToken;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, end to end in the shipped native image, that {@code quarkus.http.limits.max-connections}
 * acts on the terminated main listener. {@code MainListenerConnectionCapTest} proves the same in JVM
 * mode; this suite proves that the key, supplied the way a deployment supplies it — as the environment
 * variable {@value #CAP_ENVIRONMENT} — still bounds the open connections once the gateway is a native
 * executable, so the declaration is not a key that parses and does nothing in the artifact that ships.
 * <p>
 * <strong>The topology.</strong> One one-off bearer-only gateway on the compose network, started
 * through {@link OneOffGatewayContainers#startGateway(String, String, Path, int, List)} with
 * {@value #CAP_ENVIRONMENT}{@code =}{@value #CAP}. It declares no {@code passthrough_sni}, so the Quarkus
 * HTTPS listener terminates TLS on {@code 8443} itself, and that listener is the one the cap bounds. The
 * descriptor is derived at test time from the committed {@code sheriff-config-threat/gateway.yaml}
 * with its {@code threat_classification} block removed, which leaves the plain bearer-gated
 * asset-route document every bearer-only one-off gateway runs; it is written below {@code target/}.
 * <p>
 * <strong>What the test proves, on one gateway.</strong> It opens {@value #CAP} connections, has each
 * one served, and holds them open; the connection beyond the cap is then closed without being served.
 * The matched controls show the refusal is the cap and nothing else: the first {@value #CAP}
 * connections are served on the same listener, and once one of them closes a new connection is
 * served again. That served connection is re-asserted after the wait, and the cap is shown to hold
 * again: with the freed slot taken, the next connection is closed without being served.
 * <p>
 * Each connection is a TLS socket to the published application port, trusting the stack certificate
 * the listener presents, and sends one HTTP/1.1 request for the bearer-gated asset with a token the
 * compose Keycloak minted. A connection counts as served when the listener answers it {@code 200}, so
 * no request of the run is a refusal that a ledger of the gateway could count.
 * <p>
 * <strong>Why the harness's start check is not made.</strong>
 * {@link OneOffGatewayContainers#assertApplicationPortAnswers(String, String)} opens a connection to
 * the application port that this suite does not hold and cannot see closed; it would occupy one of the
 * {@value #CAP} slots until the listener released it. The first exchange within the cap stands in for
 * that check instead, and fails with the same dead-host-port diagnosis when it is not served.
 * <p>
 * Runs in the sequential Failsafe execution: it takes a fixed host port and counts on no other client
 * connecting to its gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Terminated main listener in the native image: quarkus.http.limits.max-connections bounds the open connections")
class MainListenerConnectionCapIT {

    /** The connection cap the gateway is started with. */
    private static final int CAP = 4;

    /** The environment spelling of {@code quarkus.http.limits.max-connections}. */
    private static final String CAP_ENVIRONMENT = "QUARKUS_HTTP_LIMITS_MAX_CONNECTIONS";

    /**
     * The fixed host port of the one-off gateway's application listener, published on every interface
     * (see {@link OneOffGatewayContainers#applicationPortPublication(int)}); used by no other suite.
     */
    private static final int APPLICATION_PORT = 10477;

    private static final Path SOURCE_DESCRIPTOR = DOCKER.resolve(Path.of("sheriff-config-threat", "gateway.yaml"));

    /** The block the derivation removes from the source descriptor. */
    private static final String THREAT_CLASSIFICATION = "threat_classification";

    /** Where the derived descriptor is written; a build output, never committed. */
    private static final Path DERIVED_DESCRIPTOR = Path.of("target", "connection-cap", "gateway.yaml");

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    /** The certificate the gateway's application listener presents. */
    private static final Path LISTENER_CERTIFICATE = CERTIFICATES.resolve("localhost.crt");

    /** Upper bound for the one-off gateway to report readiness {@code UP} after {@code docker run}. */
    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /** How long one exchange may wait on the listener before it counts as neither served nor closed. */
    private static final int EXCHANGE_TIMEOUT_MILLIS = 10_000;

    /** How long the listener may take to release the slot of a connection that closed. */
    private static final Duration RELEASE_BOUND = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** What happened to one request on one connection. */
    private enum Outcome {
        /** The listener answered {@code 200}. */
        SERVED,
        /** The listener answered with a status line other than {@code 200}. */
        ANSWERED_OTHERWISE,
        /** The listener closed the connection without answering. */
        CLOSED,
        /** The listener neither answered nor closed within the exchange timeout. */
        TIMED_OUT
    }

    private final List<SSLSocket> openSockets = new ArrayList<>();

    /**
     * One method rather than several: the listener releases a closed connection's slot
     * asynchronously, so a second method opening connections right after the first closed its own
     * could find the cap still occupied by them.
     */
    @Test
    @DisplayName("serves up to the cap, closes the connection beyond it, serves again once one closes, then caps again")
    void closesTheConnectionBeyondTheCap() throws Exception {
        String gateway = "connection-cap-gateway-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            startGateway(gateway, composeNetwork(), deriveDescriptor(), APPLICATION_PORT,
                    List.of(CAP_ENVIRONMENT + "=" + CAP));
            awaitReadiness(gateway, "https://localhost:" + publishedPort(gateway, 9000),
                    response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                    "the connection-cap gateway's readiness to report UP");
            Exchange exchange = new Exchange(trustingTheListener(), requestFor(mintIntegrationRealmToken()));

            List<Outcome> withinTheCap = new ArrayList<>();
            for (int index = 0; index < CAP; index++) {
                withinTheCap.add(exchangeOnNewConnection(exchange));
                if (index == 0) {
                    assertListenerAnswers(gateway, withinTheCap.getFirst());
                }
            }
            Outcome beyondTheCap = exchangeOnNewConnection(exchange);

            assertAll("a native listener capped at " + CAP + " connections",
                    () -> assertEquals(List.of(Outcome.SERVED, Outcome.SERVED, Outcome.SERVED, Outcome.SERVED),
                            withinTheCap, () -> "the matched control: every connection within the cap is served, "
                                    + "so the listener, the TLS trust and the request are sound. " + gatewayLog(gateway)),
                    () -> assertEquals(Outcome.CLOSED, beyondTheCap, () -> "the connection beyond the cap is closed "
                            + "without being served while the first " + CAP + " stay open; a served connection "
                            + "means " + CAP_ENVIRONMENT + " did not act in the native image. " + gatewayLog(gateway)));

            openSockets.getFirst().close();

            Outcome afterTheRelease = awaitServedOnNewConnection(gateway, exchange);
            Outcome onceTheFreedSlotIsTaken = exchangeOnNewConnection(exchange);

            assertAll("the same listener after one connection within the cap has closed",
                    () -> assertEquals(Outcome.SERVED, afterTheRelease, "the second matched control: the "
                            + "connection the wait accepted was served, so the refusal above was the cap and not a "
                            + "listener that stopped serving"),
                    () -> assertEquals(Outcome.CLOSED, onceTheFreedSlotIsTaken, () -> "once that connection has "
                            + "taken the freed slot the listener again holds " + CAP + " open connections, so the "
                            + "next one is closed without being served. " + gatewayLog(gateway)));
        } finally {
            closeSockets();
            removeContainer(gateway);
        }
    }

    /**
     * Opens new connections until one is served, and returns that connection's outcome, re-read after
     * the wait. Fails when none is served within {@link #RELEASE_BOUND}.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the asynchronous slot release is what this waits for
    private Outcome awaitServedOnNewConnection(String gateway, Exchange exchange) throws IOException {
        long deadline = System.nanoTime() + RELEASE_BOUND.toNanos();
        Outcome last = null;
        while (System.nanoTime() < deadline) {
            last = exchangeOnNewConnection(exchange);
            if (last == Outcome.SERVED) {
                return last;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a released connection slot",
                        interrupted);
            }
        }
        return fail("no new connection was served within " + RELEASE_BOUND.toSeconds() + "s of closing one of the "
                + CAP + " held connections; the last one was " + last + ". " + gatewayLog(gateway));
    }

    /**
     * The start check: the first exchange within the cap must be served. A connection the listener
     * closed or never answered on a gateway whose readiness is {@code UP} is a dead host port.
     */
    private static void assertListenerAnswers(String gateway, Outcome first) {
        if (first == Outcome.CLOSED || first == Outcome.TIMED_OUT) {
            fail("the gateway " + gateway + " reports readiness on its management port, but its application "
                    + "listener did not answer the first connection on port " + APPLICATION_PORT + " (" + first
                    + "). Published ports: " + dockerQuietly("port", gateway) + ". With no other connection open "
                    + "this is not the cap: the container is up and the published port does not answer, which is "
                    + "a dead host port — see doc/development/integration-test-topology.adoc. " + gatewayLog(gateway));
        }
    }

    private void closeSockets() throws IOException {
        for (SSLSocket socket : openSockets) {
            socket.close();
        }
        openSockets.clear();
    }

    /**
     * Writes the descriptor of the gateway: the committed threat-classification descriptor without its
     * {@code threat_classification} block, readable by others.
     *
     * @return the derived descriptor
     */
    private static Path deriveDescriptor() {
        Map<String, Object> document = loadDescriptor(SOURCE_DESCRIPTOR);
        assertNotNull(document.remove(THREAT_CLASSIFICATION), () -> SOURCE_DESCRIPTOR + " must declare the "
                + THREAT_CLASSIFICATION + " block this derivation removes");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Files.createDirectories(DERIVED_DESCRIPTOR.getParent());
            Files.writeString(DERIVED_DESCRIPTOR, new Yaml(options).dump(document), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(DESCRIPTOR_MODE));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the derived descriptor " + DERIVED_DESCRIPTOR.toAbsolutePath(),
                    e);
        }
        return DERIVED_DESCRIPTOR;
    }

    /** The one request every connection sends: the bearer-gated asset, keeping the connection open. */
    private static String requestFor(String bearer) {
        return "GET " + SECURE_ASSET + " HTTP/1.1\r\n"
                + "Host: localhost:" + APPLICATION_PORT + "\r\n"
                + "Authorization: Bearer " + bearer + "\r\n"
                + "Connection: keep-alive\r\n\r\n";
    }

    /**
     * @return a TLS context trusting exactly the certificate the gateway's application listener presents
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

    /**
     * What every connection of the run is opened and sent with.
     *
     * @param context the TLS context trusting the listener
     * @param request the request sent on every connection
     */
    private record Exchange(SSLContext context, String request) {
    }

    /**
     * Opens a TLS connection to the listener, sends the request and reads the status line. The socket
     * is kept open, so a served connection keeps counting against the cap.
     */
    private Outcome exchangeOnNewConnection(Exchange exchange) throws IOException {
        SSLSocket socket = (SSLSocket) exchange.context().getSocketFactory().createSocket();
        openSockets.add(socket);
        try {
            socket.connect(new InetSocketAddress("localhost", APPLICATION_PORT), EXCHANGE_TIMEOUT_MILLIS);
            socket.setSoTimeout(EXCHANGE_TIMEOUT_MILLIS);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            out.write(exchange.request().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            if (statusLine == null || !statusLine.startsWith("HTTP/1.1 ")) {
                return Outcome.CLOSED;
            }
            return statusLine.startsWith("HTTP/1.1 200 ") ? Outcome.SERVED : Outcome.ANSWERED_OTHERWISE;
        } catch (SocketTimeoutException timeout) {
            return Outcome.TIMED_OUT;
        } catch (IOException closed) {
            return Outcome.CLOSED;
        }
    }
}
