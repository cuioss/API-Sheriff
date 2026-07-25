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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Map;
import java.util.Optional;


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
 * An unresolved passthrough alias is defensively skipped rather than aborting boot (ADR-0009). The
 * front binds an ephemeral port so the test never contends for a fixed public port.
 */
@DisplayName("TlsEdgeProducer — accept-time front listener boot wiring")
class TlsEdgeProducerTest {

    private static final int EPHEMERAL_PORT = 0;
    private static final int INTERNAL_HTTPS_PORT = 8444;
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
        void startsAndStopsFrontListener() {
            // Arrange — one SNI maps to a resolvable alias, one to an alias absent from the topology.
            // The resolvable entry makes the relay map non-empty (front started); the unresolved entry
            // exercises the defensive skip branch.
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of(
                            "sni.resolved.example", RESOLVED_ALIAS,
                            "sni.unresolved.example", UNRESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(Optional.of(tls)).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of(
                    RESOLVED_ALIAS, new ResolvedUpstream("https", "backend.local", 9443, "")));
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, EPHEMERAL_PORT,
                    INTERNAL_HTTPS_PORT);

            // Act + Assert — the front binds the ephemeral port on startup, then shutdown stops the
            // started listener and closes the backend client without error.
            assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                    "a resolvable passthrough SNI starts the front listener on an ephemeral port");
            assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                    "shutdown stops the started listener and closes the backend client");
        }
    }

    @Nested
    @DisplayName("Passthrough unconfigured")
    class PassthroughUnconfigured {

        @Test
        @DisplayName("never starts the front listener when passthrough_sni is empty")
        void noFrontListenerWhenPassthroughEmpty() {
            // Arrange — no tls block at all, so the relay map is empty.
            GatewayConfig config = GatewayConfig.builder().version(1).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of());
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, EPHEMERAL_PORT,
                    INTERNAL_HTTPS_PORT);

            // Act + Assert — an empty relay map short-circuits startup; the later shutdown is a clean
            // no-op because neither the listener nor the backend client was ever created.
            assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                    "an empty passthrough map never starts the front listener");
            assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                    "shutdown is a no-op when nothing was started");
        }

        @Test
        @DisplayName("skips a passthrough SNI whose alias does not resolve, leaving the map empty")
        void skipsUnresolvedAlias() {
            // Arrange — the only passthrough SNI maps to an alias absent from the resolved topology, so
            // the defensive skip leaves the relay map empty and no front listener is started.
            TlsConfig tls = TlsConfig.builder()
                    .passthroughSni(Map.of("sni.unresolved.example", UNRESOLVED_ALIAS))
                    .build();
            GatewayConfig config = GatewayConfig.builder().version(1)
                    .tls(Optional.of(tls)).build();
            ResolvedTopology topology = new ResolvedTopology(Map.of());
            TlsEdgeProducer producer = new TlsEdgeProducer(vertx, config, topology, EPHEMERAL_PORT,
                    INTERNAL_HTTPS_PORT);

            // Act + Assert
            assertDoesNotThrow(() -> producer.onStartup(new StartupEvent()),
                    "an unresolved alias is skipped, so no front listener is started");
            assertDoesNotThrow(() -> producer.onShutdown(new ShutdownEvent()),
                    "shutdown is a no-op when the only alias was skipped");
        }
    }
}
