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
package de.cuioss.sheriff.gateway.edge;

import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;

/**
 * Edge hardening for the public data-plane HTTP server. It is BOTH the carrier of the
 * gateway's inbound-transport bounds AND the {@link HttpServerOptionsCustomizer} SPI bean that
 * applies them to every listener Quarkus opens.
 * <p>
 * The transport bounds fail fast on abusive framing before a request is ever admitted to the
 * pipeline: an over-long request line, an over-large header block, or an over-large chunk is
 * rejected by the Vert.x codec, and an idle connection is reaped so a slow-loris / h2 abuse load
 * cannot pin connection slots. Four further limits are consumed by {@link GatewayEdgeRoute} rather
 * than the transport: the {@linkplain #admissionCap() admission cap} bounds the number of requests
 * that may be in flight at once (rejected with {@code 503} <em>before</em> a virtual thread is
 * dispatched, so a flood cannot spawn unbounded virtual threads), the
 * {@linkplain #webSocketRelayCap() WebSocket relay cap} bounds how many of those in-flight requests
 * may be established WebSocket relays, the {@linkplain #drainTimeoutMillis() drain timeout} bounds
 * the graceful-shutdown wait for in-flight requests to complete on {@code SIGTERM}, and the
 * {@linkplain #reservedBodyMaxBytes() reserved-body ceiling} bounds the cumulative bytes the edge
 * will buffer for a gateway-terminated reserved POST path (rejected with {@code 413}), so an
 * unauthenticated caller cannot exhaust heap through the pre-authentication callback / back-channel
 * logout paths that never reach the per-route body cap.
 * <p>
 * <strong>Transport bounds are fixed; the admission budget is operator-configurable.</strong> The
 * codec limits above are deliberate secure defaults chosen to keep the abuse surface bounded while
 * comfortably serving ordinary API traffic, and the drain timeout is kept below the Quarkus default
 * shutdown timeout so the drain completes within the shutdown window rather than being cut short.
 * The two admission caps instead come from the {@code edge_hardening} block of {@code gateway.yaml}
 * ({@link EdgeHardeningConfig}), because a deployment's viable concurrency depends on its container
 * memory limit and its traffic mix — and, for the relay cap, on how long its WebSocket connections
 * live. An omitted block resolves to {@link EdgeHardeningConfig#defaults()}, preserving the
 * historical behaviour. The bean is produced (and its caps resolved) by {@code ConfigProducer}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public class EdgeHardeningOptions implements HttpServerOptionsCustomizer {

    /** Maximum size of the whole request header block in bytes (default 16 KiB). */
    private static final int MAX_HEADER_SIZE_BYTES = 16 * 1024;

    /** Maximum length of the HTTP/1.x request line ({@code METHOD SP request-target SP HTTP/x}). */
    private static final int MAX_INITIAL_LINE_LENGTH_BYTES = 8 * 1024;

    /** Maximum size of a single chunked-transfer chunk in bytes. */
    private static final int MAX_CHUNK_SIZE_BYTES = 16 * 1024;

    /** Idle-connection reap threshold in seconds — an idle connection is closed after this. */
    private static final int IDLE_TIMEOUT_SECONDS = 60;

    /**
     * Ceiling in bytes for a gateway-terminated reserved-path request body (the OIDC
     * {@code response_mode=form_post} callback and the back-channel logout receiver).
     * <p>
     * <strong>Derivation — deliberately not a second independent number.</strong> Both payloads are
     * small, gateway-terminated inbound units of exactly the same class as the request header block
     * this file already bounds: a form_post callback carries a urlencoded {@code code}/{@code state}
     * pair (hundreds of bytes in practice) and a back-channel logout carries a single compact
     * {@code logout_token} JWT (low single-digit KiB even with a large signing certificate chain).
     * The ceiling is therefore <em>defined as</em> {@link #MAX_HEADER_SIZE_BYTES} rather than
     * restated as its own literal, so the gateway's single 16 KiB inbound-unit bound cannot drift
     * into two contradicting constants. It leaves roughly an order of magnitude of headroom over a
     * real payload while bounding the worst case at the {@linkplain #admissionCap() configured
     * admission cap} to that cap times 16 KiB — ~32 MiB at the default cap of
     * {@link EdgeHardeningConfig#DEFAULT_ADMISSION_CAP}, comfortably inside the deployed container
     * memory limit. An operator who raises the admission cap raises that worst case proportionally.
     */
    private static final int RESERVED_BODY_MAX_BYTES = MAX_HEADER_SIZE_BYTES;

    /** Bounded graceful-drain wait for in-flight requests on shutdown (below the Quarkus default). */
    private static final long DRAIN_TIMEOUT_MILLIS = 25_000L;

    private final int admissionCap;
    private final int webSocketRelayCap;

    /**
     * Resolves the operator-configured admission budget, applying the documented defaults for any
     * member the {@code edge_hardening} block leaves out.
     *
     * @param hardening the bound {@code edge_hardening} block; never {@code null} — a gateway that
     *                  declares no block is supplied {@link EdgeHardeningConfig#defaults()}
     */
    public EdgeHardeningOptions(EdgeHardeningConfig hardening) {
        this.admissionCap = hardening.effectiveAdmissionCap();
        this.webSocketRelayCap = hardening.effectiveWebsocketRelayCap();
    }

    /** Creates the options carrying the documented default admission budget. */
    public EdgeHardeningOptions() {
        this(EdgeHardeningConfig.defaults());
    }

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
        return admissionCap;
    }

    /**
     * @return the maximum number of concurrently established WebSocket relays the edge admits. This
     *         is a sub-budget acquired <em>in addition to</em> the general admission permit, never
     *         instead of it: a relay holds its admission permit for the connection's whole lifetime,
     *         so without a separate bound a handful of long-lived relays would consume the general
     *         pool and starve ordinary HTTP traffic. An upgrade beyond the cap is rejected
     *         {@code 503}
     */
    public int webSocketRelayCap() {
        return webSocketRelayCap;
    }

    /**
     * @return the bounded graceful-drain wait in milliseconds the edge honours on shutdown
     */
    public long drainTimeoutMillis() {
        return DRAIN_TIMEOUT_MILLIS;
    }

    /**
     * @return the byte ceiling the edge enforces on a gateway-terminated reserved-path request body
     *         (form_post callback / back-channel logout). A request declaring more, or actually
     *         streaming more, is rejected {@code 413} — see {@link #RESERVED_BODY_MAX_BYTES} for the
     *         derivation
     */
    public long reservedBodyMaxBytes() {
        return RESERVED_BODY_MAX_BYTES;
    }
}
