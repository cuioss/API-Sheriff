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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeoutException;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Boot-wiring contract of {@link TlsEdgeProducer}: the relay map is built from {@code
 * tls.passthrough_sni} against the resolved topology, the accept-time front listener is started only
 * when at least one passthrough SNI resolves, and shutdown is a clean no-op when nothing was started.
 * An unresolved passthrough alias is defensively skipped rather than aborting boot (ADR-0009).
 * <p>
 * The positive case is asserted against the <em>public port itself</em>: it allocates a free port,
 * hands it to the producer, and probes whether anything accepts a connection there. A quiet
 * {@code onStartup} would not say this — a startup that silently skipped the front completes just as
 * quietly.
 * <p>
 * The two negative cases use the opposite discriminator, because "the port never became listening" is
 * unfalsifiable evidence for them: a port that was never bound looks exactly like a port some other
 * process happens not to be using. They instead <em>hold</em> a {@link ServerSocket} open on the port
 * for the whole test and assert that {@code onStartup} does not throw. The question they answer is
 * therefore "did {@code onStartup} refuse to bind a held port?", not "did the port become listening?".
 * Because {@code SniFrontListener.start()} binds through a bare {@code vertx.createNetServer()} with no
 * {@code setReusePort}, a producer that attempted the bind would fail and
 * {@code TlsEdgeProducer.onStartup} would surface that as an {@link IllegalStateException} — so a quiet
 * return proves no bind was attempted.
 * <p>
 * {@link PassthroughConfigured#failsWhenThePublicPortIsHeld()} is the matched control that keeps this
 * honest. It points a <em>resolvable</em> configuration at an equally-held port and asserts the
 * {@link IllegalStateException} does arrive, proving the negative cases' silence is a real observation
 * about the producer rather than a tautology about held sockets. If {@code SniFrontListener} ever set
 * {@code setReusePort}, that control fails and the negative cases lose their discriminator together.
 */
@DisplayName("TlsEdgeProducer — accept-time front listener boot wiring")
class TlsEdgeProducerTest {

    private static final int INTERNAL_HTTPS_PORT = 8444;
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int FREE_PORT_ATTEMPTS = 20;
    private static final String RESOLVED_ALIAS = "backend-alias";
    private static final String UNRESOLVED_ALIAS = "missing-alias";

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Nested
    @DisplayName("Passthrough configured")
    class PassthroughConfigured {

        @Test
        @DisplayName("starts the front listener and shuts it down cleanly when a passthrough SNI resolves")
        void startsAndStopsFrontListener() throws Exception {
            // Arrange — one SNI maps to a resolvable alias, one to an alias absent from the topology.
            // The resolvable entry makes the relay map non-empty (front started); the unresolved entry
            // exercises the defensive skip branch. A concrete free port is used rather than the
            // ephemeral 0 so that "is the front actually bound?" is an observable fact.
            int port = freePort();
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of(
                            "sni.resolved.example", RESOLVED_ALIAS,
                            "sni.unresolved.example", UNRESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(tls).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                    INTERNAL_HTTPS_PORT);
            assertFalse(isListening(port), "control precondition: nothing owns the port before startup");

            // Act
            producer.onStartup(new StartupEvent());

            // Assert — the front is genuinely accepting connections on the public port. Absence of an
            // exception would not say this: a startup that silently skipped the front (an empty relay
            // map, a swallowed bind failure) completes just as quietly.
            assertTrue(isListening(port),
                    "a resolvable passthrough SNI starts the front listener on the public port");

            // Act — and shutdown releases it
            producer.onShutdown(new ShutdownEvent());

            // Assert
            awaitNotListening(port, "shutdown stops the started listener and releases the public port");
        }

        /**
         * The matched control for both negative cases. They read "onStartup returned quietly" as
         * "no bind was attempted"; that inference is only sound if a bind attempt against a held
         * port would in fact have been noisy. This pins that it is.
         */
        @Test
        @DisplayName("refuses to boot when the public port is already held by another socket")
        void failsWhenThePublicPortIsHeld() throws Exception {
            // Arrange — a resolvable passthrough SNI, so the front listener genuinely attempts a bind,
            // aimed at a port this test holds open for the duration.
            //
            // The wildcard bind here is deliberate and is the only place in the module's test tree
            // that stays wildcard-bound. Every OTHER ephemeral listener in this tree is loopback-bound
            // through LoopbackHost.ADDRESS, because it is dialled on loopback and a wildcard bind would
            // leave it reachable from any interface. This socket is never dialled at all — it exists
            // solely to OCCUPY the port — and what it must collide with is production's own bind:
            // SniFrontListener.start() binds the wildcard via netServer.listen(publicPort)
            // (api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java:98).
            // Narrowing this holder to loopback would leave the wildcard free, the producer's bind
            // would succeed, and the control would silently stop controlling anything.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                TlsConfig tls = TlsConfig.builder()
                        .passthroughSni(Map.of("sni.resolved.example", RESOLVED_ALIAS))
                        .build();
                GatewayConfig config = GatewayConfig.builder().version(1)
                        .tls(tls).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of(
                        RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                        INTERNAL_HTTPS_PORT);

                // Act + Assert — the bind cannot succeed against a held port, and the producer refuses
                // to boot rather than continuing without its front listener
                StartupEvent startup = new StartupEvent();
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> producer.onStartup(startup),
                        "a bind against a held public port must fail the boot, which is what makes the "
                                + "negative cases' quiet onStartup meaningful");

                // Assert — and specifically down the bind-failure branch. Accepting any
                // IllegalStateException would let the interrupted-while-binding branch, or an
                // unrelated wiring failure, masquerade as the bind refusal this control depends on.
                assertTrue(refused.getMessage().contains("bind failed"),
                        "the refusal must be the bind-failure branch, not merely some IllegalStateException: "
                                + refused.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Passthrough unconfigured")
    class PassthroughUnconfigured {

        @Test
        @DisplayName("never starts the front listener when passthrough_sni is empty")
        void noFrontListenerWhenPassthroughEmpty() throws Exception {
            // Arrange — no tls block at all, so the relay map is empty. The port is HELD open for the
            // whole test: a producer that tried to start the front here would be refused the bind and
            // would fail the boot, so a quiet onStartup is positive evidence that it never tried.
            // Wildcard-bound for the same reason as failsWhenThePublicPortIsHeld — the holder has to
            // collide with production's wildcard listen(publicPort), and it is never dialled.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                GatewayConfig config = GatewayConfig.builder().version(1).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of());
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                        INTERNAL_HTTPS_PORT);

                // Act + Assert
                assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                        "an empty passthrough map short-circuits before any bind is attempted");

                // Act + Assert — shutdown is a clean no-op because nothing was ever created
                assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                        "shutdown is a no-op when no front listener was started");
            }
        }

        @Test
        @DisplayName("skips a passthrough SNI whose alias does not resolve, leaving the map empty")
        void skipsUnresolvedAlias() throws Exception {
            // Arrange — the only passthrough SNI maps to an alias absent from the resolved topology, so
            // the defensive skip leaves the relay map empty and no front listener is started. This
            // differs from noFrontListenerWhenPassthroughEmpty in the arrange that matters: a
            // passthrough entry IS declared here, and only the alias lookup empties the map. As there,
            // the port is held open so that an attempted bind would be refused and surface loudly.
            // Wildcard-bound for the same reason as failsWhenThePublicPortIsHeld — the holder has to
            // collide with production's wildcard listen(publicPort), and it is never dialled.
            try (ServerSocket held = new ServerSocket(0)) {
                int port = held.getLocalPort();
                TlsConfig tls = TlsConfig.builder()
                        .passthroughSni(Map.of("sni.unresolved.example", UNRESOLVED_ALIAS))
                        .build();
                GatewayConfig config = GatewayConfig.builder().version(1)
                        .tls(tls).build();
                ResolvedTopology topology = new ResolvedTopology(Map.of());
                TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                        INTERNAL_HTTPS_PORT);

                // Act + Assert — the declared-but-unresolvable entry contributed no relay target, so
                // the map stayed empty and no bind was ever attempted
                assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                        "an unresolved alias is skipped, so no bind is attempted");

                // Act + Assert
                assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                        "shutdown is a no-op when the only alias was skipped");
            }
        }
    }

    /**
     * A port no process owns at the moment of the call. The socket is closed before the port is
     * handed back, which is what makes the pre-startup {@code assertFalse(isListening(port))} control
     * meaningful — and is also what makes this inherently a check-then-act: another process may take
     * the released port before the producer binds it.
     * <p>
     * That window cannot be closed for the positive case, which needs a port that is genuinely
     * <em>free</em> so the front listener can really bind it; holding the socket, as the negative
     * cases do, would defeat the very bind being asserted. It is instead narrowed by re-probing the
     * released port and retrying a bounded number of times, so a port that was taken inside the
     * window is discarded rather than handed out.
     * <p>
     * The probe socket is <em>wildcard</em>-bound, and deliberately so — the fourth and last such
     * site in this module's test tree. The port it hands back is the one
     * {@code SniFrontListener.start()} will bind, and that bind is a wildcard one
     * ({@code netServer.listen(publicPort)} at
     * {@code api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/SniFrontListener.java:98}).
     * Probing loopback alone would answer a narrower question than the one being asked — a port free
     * on the loopback address but already held on another interface would be reported free and then
     * fail the producer's bind. Nothing dials this socket; it is bound and immediately closed.
     *
     * @return a currently-free localhost port
     * @throws IOException when no free ephemeral port can be allocated within the retry budget
     */
    private static int freePort() throws IOException {
        for (int attempt = 0; attempt < FREE_PORT_ATTEMPTS; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket(0)) {
                candidate = socket.getLocalPort();
            }
            if (!isListening(candidate)) {
                return candidate;
            }
        }
        throw new IOException(
                "no free localhost port after " + FREE_PORT_ATTEMPTS + " attempts");
    }

    /**
     * Whether something accepts a TCP connection on {@code port}. A refused connection is the
     * observation, not an error, so it is reported as {@code false} rather than raised.
     *
     * @param port the localhost port to probe
     * @return {@code true} when the connection is accepted
     */
    private static boolean isListening(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException _) {
            // A refused connection IS the answer: nothing is listening on the probed port.
            return false;
        }
    }

    /**
     * Waits for the public port to stop accepting connections. {@code SniFrontListener.stop()}
     * completes on the Vert.x event loop, so the unbind is a real asynchronous release.
     *
     * @param port    the localhost port that must fall silent
     * @param message asserted as the post-condition once the wait settles
     * @throws TimeoutException when the port is still listening at the teardown ceiling
     */
    private static void awaitNotListening(int port, String message) throws TimeoutException {
        Awaits.until(() -> !isListening(port), message, Awaits.TEARDOWN_CEILING_SECONDS);
        assertFalse(isListening(port), message);
    }
}
