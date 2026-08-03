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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;

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
 * Every case is asserted against the <em>public port itself</em> rather than against the absence of an
 * exception: each test allocates a currently-free port, hands it to the producer, and probes whether
 * anything accepts a connection on it. A quiet {@code onStartup} is not evidence that a front listener
 * started, and is equally not evidence that one was deliberately skipped — the port is. The port is
 * allocated per test rather than fixed, so the suite never contends for a well-known public port.
 */
@DisplayName("TlsEdgeProducer — accept-time front listener boot wiring")
class TlsEdgeProducerTest {

    private static final int INTERNAL_HTTPS_PORT = 8444;
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
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
        void startsAndStopsFrontListener() throws IOException {
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
    }

    @Nested
    @DisplayName("Passthrough unconfigured")
    class PassthroughUnconfigured {

        @Test
        @DisplayName("never starts the front listener when passthrough_sni is empty")
        void noFrontListenerWhenPassthroughEmpty() throws IOException {
            // Arrange — no tls block at all, so the relay map is empty.
            int port = freePort();
            GatewayConfig config = GatewayConfig.builder().version(1).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of());
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                    INTERNAL_HTTPS_PORT);

            // Act
            producer.onStartup(new StartupEvent());

            // Assert — the public port is left untouched. The matched control for this negative claim
            // is startsAndStopsFrontListener above, which binds the same kind of port from a resolvable
            // configuration: without it, "no exception" could not distinguish a deliberate short-circuit
            // from a front that started perfectly well.
            assertFalse(isListening(port),
                    "an empty passthrough map never starts the front listener");

            // Act + Assert — shutdown is a clean no-op because nothing was ever created
            producer.onShutdown(new ShutdownEvent());
            assertFalse(isListening(port), "shutdown leaves the unbound port unbound");
        }

        @Test
        @DisplayName("skips a passthrough SNI whose alias does not resolve, leaving the map empty")
        void skipsUnresolvedAlias() throws IOException {
            // Arrange — the only passthrough SNI maps to an alias absent from the resolved topology, so
            // the defensive skip leaves the relay map empty and no front listener is started. This
            // differs from noFrontListenerWhenPassthroughEmpty in the arrange that matters: a
            // passthrough entry IS declared here, and only the alias lookup empties the map.
            int port = freePort();
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of("sni.unresolved.example", UNRESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(tls).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of());
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, port,
                    INTERNAL_HTTPS_PORT);

            // Act
            producer.onStartup(new StartupEvent());

            // Assert — the declared-but-unresolvable entry contributed no relay target, so the map
            // stayed empty and the front was never started
            assertFalse(isListening(port),
                    "an unresolved alias is skipped, so no front listener is started");

            // Act + Assert
            producer.onShutdown(new ShutdownEvent());
            assertFalse(isListening(port), "shutdown is a no-op when the only alias was skipped");
        }
    }

    /**
     * A port no process owns at the moment of the call. The socket is closed before the port is
     * handed back, which is what makes the pre-startup {@code assertFalse(isListening(port))} control
     * meaningful.
     *
     * @return a currently-free localhost port
     * @throws IOException when no ephemeral port can be allocated
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
            probe.connect(new InetSocketAddress("localhost", port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    // NOSONAR java:S2925 - Thread.sleep is load-bearing: SniFrontListener.stop() completes on the
    // Vert.x event loop, so the unbind is a real asynchronous release with no virtual clock to advance.
    @SuppressWarnings("java:S2925")
    private static void awaitNotListening(int port, String message) {
        for (int attempt = 0; attempt < 100 && isListening(port); attempt++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(isListening(port), message);
    }
}
