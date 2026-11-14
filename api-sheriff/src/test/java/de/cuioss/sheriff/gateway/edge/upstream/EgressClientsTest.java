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
package de.cuioss.sheriff.gateway.edge.upstream;

import static org.junit.jupiter.api.Assertions.*;


import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the pool and connect bounds {@link EgressClients} builds every upstream client with: the
 * queue of attempts waiting for a pooled connection is bounded by the admission cap, never below a
 * floor, and a TCP connect to an upstream is bounded on both client branches.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("EgressClients — the wait-queue bound and the dial timeout of every upstream client")
class EgressClientsTest {

    @ParameterizedTest(name = "admission_cap {0}")
    @ValueSource(ints = {EgressClients.WAIT_QUEUE_FLOOR, 2048, 4096})
    @DisplayName("bounds the wait queue by the admission cap")
    void boundsTheWaitQueueByTheAdmissionCap(int admissionCap) {
        PoolOptions options = EgressClients.poolOptionsFor(admissionCap);

        assertEquals(admissionCap, options.getMaxWaitQueueSize(),
                "no upstream client queues more attempts than the gateway can admit");
    }

    @ParameterizedTest(name = "admission_cap {0}")
    @ValueSource(ints = {1, EgressClients.WAIT_QUEUE_FLOOR - 1})
    @DisplayName("never bounds the wait queue below the floor")
    void neverBoundsTheWaitQueueBelowTheFloor(int admissionCap) {
        PoolOptions options = EgressClients.poolOptionsFor(admissionCap);

        assertEquals(EgressClients.WAIT_QUEUE_FLOOR, options.getMaxWaitQueueSize(),
                "a small admission cap still leaves room for attempts its departed clients left queued");
    }

    @Test
    @DisplayName("bounds the TLS handshake of the default and of the forced-HTTP/2 client, dial and handshake below the connection wait")
    void boundsTheTlsHandshakeOfBothBranches() {
        HttpClientOptions plain = EgressClients.clientOptionsFor(
                new UpstreamTarget("https", "upstream.example", 8443, false), true, null);
        HttpClientOptions forcedHttp2 = EgressClients.clientOptionsFor(
                new UpstreamTarget("https", "upstream.example", 8443, true), true, null);

        assertAll("the TLS handshake timeout of every upstream client",
                () -> assertEquals(EgressClients.UPSTREAM_TLS_HANDSHAKE_TIMEOUT_MILLIS,
                        plain.getSslHandshakeTimeoutUnit().toMillis(plain.getSslHandshakeTimeout()), "the default client"),
                () -> assertEquals(EgressClients.UPSTREAM_TLS_HANDSHAKE_TIMEOUT_MILLIS,
                        forcedHttp2.getSslHandshakeTimeoutUnit().toMillis(forcedHttp2.getSslHandshakeTimeout()),
                        "the forced-HTTP/2 client"),
                () -> assertTrue(EgressClients.UPSTREAM_DIAL_TIMEOUT_MILLIS + EgressClients.UPSTREAM_TLS_HANDSHAKE_TIMEOUT_MILLIS
                        < DispatchStage.UPSTREAM_CONNECTION_WAIT_MILLIS,
                        "a connection the upstream does not establish fails as the upstream's before the connection "
                                + "wait bound ends the attempt as local saturation"));
    }

    @Test
    @DisplayName("bounds the TCP connect of the default and of the forced-HTTP/2 client")
    void boundsTheDialOfBothBranches() {
        HttpClientOptions plain = EgressClients.clientOptionsFor(
                new UpstreamTarget("http", "upstream.example", 8080, false), true, null);
        HttpClientOptions forcedHttp2 = EgressClients.clientOptionsFor(
                new UpstreamTarget("https", "upstream.example", 8443, true), true, null);

        assertAll("the dial timeout of every upstream client",
                () -> assertEquals(EgressClients.UPSTREAM_DIAL_TIMEOUT_MILLIS, plain.getConnectTimeout(),
                        "the default client"),
                () -> assertEquals(EgressClients.UPSTREAM_DIAL_TIMEOUT_MILLIS, forcedHttp2.getConnectTimeout(),
                        "the forced-HTTP/2 client"),
                () -> assertTrue(EgressClients.UPSTREAM_DIAL_TIMEOUT_MILLIS < DispatchStage.UPSTREAM_CONNECTION_WAIT_MILLIS,
                        "a dial that does not complete fails as the upstream's before the connection wait bound "
                                + "ends the attempt as local saturation"));
    }
}
