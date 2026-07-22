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

import java.util.concurrent.TimeUnit;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Edge hardening for the public data-plane HTTP server. It is BOTH the carrier of the
 * gateway's inbound-transport bounds AND the {@link HttpServerOptionsCustomizer} SPI bean that
 * applies them to every listener Quarkus opens.
 * <p>
 * The transport bounds fail fast on abusive framing before a request is ever admitted to the
 * pipeline: an over-long request line, an over-large header block, or an over-large chunk is
 * rejected by the Vert.x codec, and an idle connection is reaped so a slow-loris / h2 abuse load
 * cannot pin connection slots. Two further limits are consumed by {@link GatewayEdgeRoute} rather
 * than the transport: the {@linkplain #admissionCap() admission cap} bounds the number of requests
 * that may be in flight at once (rejected with {@code 503} <em>before</em> a virtual thread is
 * dispatched, so a flood cannot spawn unbounded virtual threads), and the
 * {@linkplain #drainTimeoutMillis() drain timeout} bounds the graceful-shutdown wait for in-flight
 * requests to complete on {@code SIGTERM}.
 * <p>
 * The values are deliberate secure defaults chosen to keep the abuse surface bounded while
 * comfortably serving ordinary API traffic; the drain timeout is kept below the Quarkus default
 * shutdown timeout so the drain completes within the shutdown window rather than being cut short.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class EdgeHardeningOptions implements HttpServerOptionsCustomizer {

    /** Maximum size of the whole request header block in bytes (default 16 KiB). */
    private static final int MAX_HEADER_SIZE_BYTES = 16 * 1024;

    /** Maximum length of the HTTP/1.x request line ({@code METHOD SP request-target SP HTTP/x}). */
    private static final int MAX_INITIAL_LINE_LENGTH_BYTES = 8 * 1024;

    /** Maximum size of a single chunked-transfer chunk in bytes. */
    private static final int MAX_CHUNK_SIZE_BYTES = 16 * 1024;

    /** Idle-connection reap threshold in seconds — an idle connection is closed after this. */
    private static final int IDLE_TIMEOUT_SECONDS = 60;

    /** Maximum number of requests permitted in flight at once before a virtual thread is dispatched. */
    private static final int ADMISSION_CAP = 2048;

    /** Bounded graceful-drain wait for in-flight requests on shutdown (below the Quarkus default). */
    private static final long DRAIN_TIMEOUT_MILLIS = 25_000L;

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        apply(options);
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        apply(options);
    }

    private static void apply(HttpServerOptions options) {
        options.setMaxHeaderSize(MAX_HEADER_SIZE_BYTES)
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH_BYTES)
                .setMaxChunkSize(MAX_CHUNK_SIZE_BYTES)
                .setIdleTimeout(IDLE_TIMEOUT_SECONDS)
                .setIdleTimeoutUnit(TimeUnit.SECONDS);
    }

    /**
     * @return the maximum number of concurrently in-flight requests the edge admits before a
     *         virtual thread is dispatched; a request beyond the cap is rejected {@code 503}
     */
    public int admissionCap() {
        return ADMISSION_CAP;
    }

    /**
     * @return the bounded graceful-drain wait in milliseconds the edge honours on shutdown
     */
    public long drainTimeoutMillis() {
        return DRAIN_TIMEOUT_MILLIS;
    }
}
