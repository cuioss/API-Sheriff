/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EdgeHardeningOptions — inbound transport bounds and admission/drain limits")
class EdgeHardeningTest {

    private static final int MAX_HEADER_SIZE_BYTES = 16 * 1024;
    private static final int MAX_INITIAL_LINE_LENGTH_BYTES = 8 * 1024;
    private static final int MAX_CHUNK_SIZE_BYTES = 16 * 1024;
    private static final int IDLE_TIMEOUT_SECONDS = 60;
    private static final int ADMISSION_CAP = 2048;
    private static final long DRAIN_TIMEOUT_MILLIS = 25_000L;

    @Test
    @DisplayName("clamps the plaintext listener framing to bounded header, line, and chunk sizes")
    void clampsPlaintextListenerFraming() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpServer(options);

        // Assert — an over-long line, over-large header block, or over-large chunk is rejected by
        // the Vert.x codec before a request is ever admitted to the pipeline.
        assertEquals(MAX_HEADER_SIZE_BYTES, options.getMaxHeaderSize(),
                "The whole request header block is bounded to 16 KiB");
        assertEquals(MAX_INITIAL_LINE_LENGTH_BYTES, options.getMaxInitialLineLength(),
                "The HTTP/1.x request line is bounded to 8 KiB");
        assertEquals(MAX_CHUNK_SIZE_BYTES, options.getMaxChunkSize(),
                "A single chunked-transfer chunk is bounded to 16 KiB");
    }

    @Test
    @DisplayName("reaps an idle connection after a bounded idle window (slow-loris / h2 abuse guard)")
    void reapsIdleConnection() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpServer(options);

        // Assert — an idle connection is reaped so a slow-loris / h2 abuse load cannot pin slots.
        assertEquals(IDLE_TIMEOUT_SECONDS, options.getIdleTimeout(),
                "An idle connection is reaped after 60 seconds");
        assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit(),
                "The idle-timeout unit is seconds");
    }

    @Test
    @DisplayName("applies the same transport bounds to the TLS listener as to the plaintext listener")
    void appliesSameBoundsToHttpsListener() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpsServer(options);

        // Assert — the customizer applies to every listener Quarkus opens, TLS included.
        assertEquals(MAX_HEADER_SIZE_BYTES, options.getMaxHeaderSize(),
                "The TLS listener bounds the header block identically");
        assertEquals(MAX_INITIAL_LINE_LENGTH_BYTES, options.getMaxInitialLineLength(),
                "The TLS listener bounds the request line identically");
        assertEquals(MAX_CHUNK_SIZE_BYTES, options.getMaxChunkSize(),
                "The TLS listener bounds the chunk size identically");
        assertEquals(IDLE_TIMEOUT_SECONDS, options.getIdleTimeout(),
                "The TLS listener reaps idle connections identically");
        assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit(),
                "The TLS listener idle-timeout unit is seconds");
    }

    @Test
    @DisplayName("bounds in-flight requests with a positive admission cap")
    void exposesPositiveAdmissionCap() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();

        // Act
        int cap = hardening.admissionCap();

        // Assert — a request beyond the cap is rejected 503 before a virtual thread is dispatched,
        // so a flood cannot spawn unbounded virtual threads.
        assertEquals(ADMISSION_CAP, cap, "The admission cap is the documented secure default");
        assertTrue(cap > 0, "The admission cap must be a positive bound");
    }

    @Test
    @DisplayName("bounds the graceful-drain wait below the Quarkus shutdown window")
    void exposesBoundedDrainTimeout() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();

        // Act
        long drainMillis = hardening.drainTimeoutMillis();

        // Assert — the drain timeout is a positive, bounded wait kept below the Quarkus default
        // 30-second shutdown timeout so the drain completes within the shutdown window.
        assertEquals(DRAIN_TIMEOUT_MILLIS, drainMillis, "The drain timeout is the documented secure default");
        assertTrue(drainMillis > 0L, "The drain timeout must be a positive bound");
        assertTrue(drainMillis < 30_000L, "The drain timeout must stay below the 30s Quarkus shutdown window");
    }
}
