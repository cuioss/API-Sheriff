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
package de.cuioss.sheriff.gateway.edge;

import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpServerOptions;

/**
 * Edge hardening for the public data-plane HTTP server. It is BOTH the carrier of the
 * gateway's inbound-transport bounds AND the {@link HttpServerOptionsCustomizer} SPI bean that
 * applies them to every listener Quarkus opens.
 * <p>
 * The transport bounds fail fast on abusive framing before a request is ever admitted to the
 * pipeline: an over-long request line, an over-large header block, or an over-large chunk is
 * rejected by the Vert.x codec, and an idle connection is reaped so a slow-loris / h2 abuse load
 * cannot pin connection slots. Five further limits are consumed by {@link GatewayEdgeRoute} rather
 * than the transport: the {@linkplain #admissionCap() admission cap} bounds the number of requests
 * that may be in flight at once (rejected with {@code 503} <em>before</em> a virtual thread is
 * dispatched, so a flood cannot spawn unbounded virtual threads), the
 * {@linkplain #webSocketRelayCap() WebSocket relay cap} bounds how many of those in-flight requests
 * may be established WebSocket relays, the {@linkplain #drainTimeoutMillis() drain timeout} bounds
 * the graceful-shutdown wait for in-flight requests to complete on {@code SIGTERM}, the
 * {@linkplain #reservedBodyMaxBytes() reserved-body ceiling} bounds the cumulative bytes the edge
 * will buffer for a gateway-terminated reserved POST path (rejected with {@code 413}), so an
 * unauthenticated caller cannot exhaust heap through the pre-authentication back-channel logout
 * path that never reaches the per-route body cap, and the
 * {@linkplain #requestBodyIdleTimeoutMillis() request-body idle timeout} bounds how long a proxied
 * dispatch holding an upstream connection waits for request-body progress while it reads the body
 * (answered {@code 408}, its upstream connection released). The idle timeout is a fixed gateway
 * constant, like the upstream guard it sits under: it is shorter than the guard's whole-dispatch
 * timeout, so a dispatch whose body stopped arriving is ended and attributed to the request before
 * the guard would end it as an upstream timeout. A bodyless request and a gRPC route are not
 * watched.
 * <p>
 * <strong>The inbound HTTP/2 concurrent streams and connection window are transport bounds too.</strong>
 * A listener admits at most 100 concurrent streams per connection (the Vert.x listener default): a listener announcing more, or announcing no limit at all, is lowered to that bound, and
 * a lower value already set on the listener is kept. The connection window is then sized so every
 * stream the listener admits at once can fill its own window: the maximum concurrent streams times the
 * initial stream window, capped at {@code 2^31-1} and never below one stream window — 6,553,500 bytes
 * at the bound of 100 streams and the default stream window of 65,535 bytes. A request whose body the
 * gateway is not reading yet returns no window, so a connection window the size of one stream would
 * let such streams use it up and leave the ones being forwarded unable to send a byte. The window is
 * derived from the listener's stream settings after the stream bound is applied, not configured.
 * <p>
 * <strong>Transport bounds are fixed; the admission budget is operator-configurable.</strong> The
 * codec limits above are deliberate secure defaults chosen to keep the abuse surface bounded while
 * comfortably serving ordinary API traffic, and the drain timeout is kept below the Quarkus default
 * shutdown timeout so the drain completes within the shutdown window rather than being cut short.
 * The request-body idle timeout is fixed because the upstream guard it sits under is a fixed gateway
 * policy (ADR-0064); no configuration key or system property reaches it. The two admission caps instead come from the {@code edge_hardening} block of {@code gateway.yaml}
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
     * Ceiling in bytes for a gateway-terminated reserved-path request body — since the gateway drives
     * {@code response_mode=query} and its OIDC callback is a bodyless top-level GET, the back-channel
     * logout receiver is the one path this bounds.
     * <p>
     * <strong>Derivation — deliberately not a second independent number.</strong> That payload is a
     * small, gateway-terminated inbound unit of exactly the same class as the request header block
     * this file already bounds: a back-channel logout carries a single compact
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

    /**
     * How long a proxied dispatch holding an upstream connection may go without request-body progress
     * while it reads the body, in milliseconds. Below the upstream guard's whole-dispatch timeout
     * ({@code GatewayEdgeRoute.UPSTREAM_TIMEOUT_SECONDS}), so it ends such a dispatch first; long enough
     * that a sender pausing between chunks on a slow link is not affected.
     */
    private static final long REQUEST_BODY_IDLE_TIMEOUT_MILLIS = 10_000L;

    /**
     * Upper bound on the HTTP/2 streams one inbound connection may have open at once, announced as the
     * listener's initial {@code SETTINGS_MAX_CONCURRENT_STREAMS} — the Vert.x listener default. A
     * transport bound: it caps the work a single connection can hold in flight, and it is the factor
     * the {@linkplain #connectionWindowSize(Http2Settings) connection window} is derived from, so the
     * window stays a finite multiple of the stream window.
     */
    private static final long MAX_CONCURRENT_STREAMS_PER_CONNECTION = 100L;

    private final int admissionCap;
    private final int webSocketRelayCap;
    private final long requestBodyIdleTimeoutMillis;

    /**
     * Resolves the operator-configured admission budget, applying the documented defaults for any
     * member the {@code edge_hardening} block leaves out.
     *
     * @param hardening the bound {@code edge_hardening} block; never {@code null} — a gateway that
     *                  declares no block is supplied {@link EdgeHardeningConfig#defaults()}
     */
    public EdgeHardeningOptions(EdgeHardeningConfig hardening) {
        this(hardening, REQUEST_BODY_IDLE_TIMEOUT_MILLIS);
    }

    /**
     * <strong>Test-only.</strong> Resolves the admission budget exactly as the public constructor
     * does, but carries the given request-body idle timeout in place of the fixed 10,000 ms, so an
     * edge test can observe the bound without
     * waiting for it. No configuration key or system property reaches this constructor.
     *
     * @param hardening                    as for the public constructor
     * @param requestBodyIdleTimeoutMillis the request-body idle timeout in milliseconds, positive
     * @throws IllegalArgumentException when {@code requestBodyIdleTimeoutMillis} is not positive
     */
    EdgeHardeningOptions(EdgeHardeningConfig hardening, long requestBodyIdleTimeoutMillis) {
        if (requestBodyIdleTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "requestBodyIdleTimeoutMillis must be positive: " + requestBodyIdleTimeoutMillis);
        }
        this.admissionCap = hardening.effectiveAdmissionCap();
        this.webSocketRelayCap = hardening.effectiveWebsocketRelayCap();
        this.requestBodyIdleTimeoutMillis = requestBodyIdleTimeoutMillis;
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
        Http2Settings settings = options.getInitialSettings();
        boundConcurrentStreams(settings);
        options.setMaxHeaderSize(MAX_HEADER_SIZE_BYTES)
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH_BYTES)
                .setMaxChunkSize(MAX_CHUNK_SIZE_BYTES)
                .setIdleTimeout(IDLE_TIMEOUT_SECONDS)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setInitialSettings(settings)
                .setHttp2ConnectionWindowSize(connectionWindowSize(settings));
    }

    /**
     * Lowers the listener's announced concurrent streams to
     * {@link #MAX_CONCURRENT_STREAMS_PER_CONNECTION} when it is unbounded or above that bound; a lower
     * value is left as it is.
     *
     * @param settings the initial HTTP/2 settings of the listener, updated in place
     */
    private static void boundConcurrentStreams(Http2Settings settings) {
        if (settings.getMaxConcurrentStreams() > MAX_CONCURRENT_STREAMS_PER_CONNECTION) {
            settings.setMaxConcurrentStreams(MAX_CONCURRENT_STREAMS_PER_CONNECTION);
        }
    }

    /**
     * Derives the inbound HTTP/2 connection window from the stream settings the listener announces
     * once {@link #boundConcurrentStreams(Http2Settings)} has applied the stream bound: every stream it
     * admits at once can fill its whole window without drawing on a sibling's share. The product is
     * computed in {@code long}, capped at {@code 2^31-1} — the largest window RFC 9113 section 6.9.1
     * allows, reached only by a stream window above 21,474,836 bytes at the 100-stream bound — and
     * never set below one stream window.
     *
     * @param settings the initial HTTP/2 settings of the listener
     * @return the connection window in bytes
     */
    private static int connectionWindowSize(Http2Settings settings) {
        long streamWindow = settings.getInitialWindowSize();
        long everyStreamsWindow = settings.getMaxConcurrentStreams() * streamWindow;
        return (int) Math.max(streamWindow, Math.min(everyStreamsWindow, Integer.MAX_VALUE));
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
     * @return how long, in milliseconds, a proxied dispatch holding an upstream connection may go
     *         without request-body progress while it reads the body before it is answered
     *         {@code 408} and its upstream request is reset — a fixed gateway constant of 10,000 ms,
     *         below the upstream guard's timeout
     */
    public long requestBodyIdleTimeoutMillis() {
        return requestBodyIdleTimeoutMillis;
    }

    /**
     * @return the byte ceiling the edge enforces on a gateway-terminated reserved-path request body
     *         (the back-channel logout receiver). A request declaring more, or actually streaming
     *         more, is rejected {@code 413} — see {@link #RESERVED_BODY_MAX_BYTES} for the derivation
     */
    public long reservedBodyMaxBytes() {
        return RESERVED_BODY_MAX_BYTES;
    }
}
