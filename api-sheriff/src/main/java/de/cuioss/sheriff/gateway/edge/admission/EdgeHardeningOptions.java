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
package de.cuioss.sheriff.gateway.edge.admission;

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
 * cannot pin connection slots. Eight further limits are consumed by
 * {@code de.cuioss.sheriff.gateway.edge} and its sub-packages rather
 * than the transport: the {@linkplain #admissionCap() admission cap} bounds the number of requests
 * that may be in flight at once (rejected with {@code 503} <em>before</em> a virtual thread is
 * dispatched, so a flood cannot spawn unbounded virtual threads), the
 * {@linkplain #webSocketRelayCap() WebSocket relay cap} bounds how many of those in-flight requests
 * may be established WebSocket relays, the {@linkplain #heldAnswerCap() held-answer cap} bounds how
 * many answers may wait on a timer at once (refused with {@code 503} on the floor's release time,
 * without a held permit), and one tripped source's {@linkplain #heldAnswerSourceShare() share} of it,
 * the
 * {@linkplain #drainTimeoutMillis() drain timeout} bounds
 * the graceful-shutdown wait for in-flight requests to complete on {@code SIGTERM}, the
 * {@linkplain #reservedBodyMaxBytes() reserved-body ceiling} bounds the cumulative bytes the edge
 * will buffer for a gateway-terminated reserved POST path (rejected with {@code 413}), so an
 * unauthenticated caller cannot exhaust heap through the pre-authentication back-channel logout
 * path that never reaches the per-route body cap, the
 * {@linkplain #requestBodyIdleTimeoutMillis() request-body idle timeout} bounds how long a proxied
 * dispatch holding an upstream connection waits for request-body progress while it reads the body
 * (answered {@code 408}, and the reset ends the dispatch's hold on its upstream connection; on
 * HTTP/1.1 that connection returns to the pool only when no body chunk had been forwarded, and a
 * partly sent request closes it), and the
 * {@linkplain #requestBodyDeadlineMillis() request-body deadline} bounds how long, from the start of
 * its upstream attempt, a proxied dispatch waits for the whole body. Both are fixed gateway constants,
 * like the upstream guard they sit under: each is shorter than the guard's timeout, which bounds each
 * upstream attempt rather than the whole dispatch (a retried dispatch on a retry-enabled route can run
 * up to three attempts, about 90 s), so a dispatch whose body stopped arriving, or is still arriving
 * when the guard would end the attempt, is ended and attributed to the request before the guard would
 * end it as an upstream timeout. A bodyless request
 * and a gRPC route are watched by neither.
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
 * <strong>The inbound HTTP/2 header block and stream-reset rate are transport bounds as well.</strong>
 * The listener announces a {@code SETTINGS_MAX_HEADER_LIST_SIZE} of at most 16 KiB — the same bound
 * the HTTP/1.1 header block has: a listener announcing more, or announcing no limit, is lowered to it,
 * and a lower value is kept. The bound covers the whole decoded header block, {@code CONTINUATION}
 * frames included, so a header block spread over many frames is refused by the codec once it passes
 * that size, and the codec ends the connection with a {@code GOAWAY} naming an error — a complete block
 * of 17 KiB does, as {@code GatewayEdgeHttp2AbuseTest} pins. The per-connection Rapid-Reset guard is pinned at 200 {@code RST_STREAM} frames per
 * 30-second window, the transport's own default: a connection whose client resets more streams than
 * that inside one window is ended with {@code GOAWAY ENHANCE_YOUR_CALM}. The values are set explicitly
 * so a change of the transport default cannot move them, and they are never raised (ADR-0066). The
 * guard counts only the resets a client sends on its connection, so the gateway's own
 * {@code RST_STREAM(NO_ERROR)} — the early-response reset of ADR-0066 and the request-body and
 * stream-idle resets of ADR-0060 — can never feed it. The per-source detection substrate
 * ({@code de.cuioss.sheriff.gateway.detection}) is deliberately not used for these bounds: its key is a
 * source address, while stream creation and reset state belong to one connection, so per-source
 * keying would neither see a single abusive connection sooner nor bound its memory.
 * <p>
 * <strong>Transport bounds are fixed; the admission budget is operator-configurable.</strong> The
 * codec limits above are deliberate secure defaults chosen to keep the abuse surface bounded while
 * comfortably serving ordinary API traffic, and the drain timeout is kept below the Quarkus default
 * shutdown timeout so the drain completes within the shutdown window rather than being cut short.
 * The request-body idle timeout and deadline are fixed because the upstream guard they sit under is a
 * fixed gateway policy (ADR-0064); no configuration key or system property reaches them. The two admission caps instead come from the {@code edge_hardening} block of {@code gateway.yaml}
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
     * while it reads the body, in milliseconds. Below the upstream guard's per-attempt timeout
     * ({@code UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS}), so it ends such a dispatch first; long enough
     * that a sender pausing between chunks on a slow link is not affected.
     */
    private static final long REQUEST_BODY_IDLE_TIMEOUT_MILLIS = 10_000L;

    /**
     * How long, in milliseconds from the start of its upstream attempt, a proxied dispatch may wait for
     * the whole request body before it is answered {@code 408}. The attempt start is where the upstream
     * guard's per-attempt timeout ({@code UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS}) starts too, and
     * this bound is 5 s shorter, so a body still arriving when the guard would end the attempt as an
     * upstream timeout is ended as the request's first.
     */
    private static final long REQUEST_BODY_DEADLINE_MILLIS = 25_000L;

    /**
     * Upper bound on the HTTP/2 streams one inbound connection may have open at once, announced as the
     * listener's initial {@code SETTINGS_MAX_CONCURRENT_STREAMS} — the Vert.x listener default. A
     * transport bound: it caps the work a single connection can hold in flight, and it is the factor
     * the {@linkplain #connectionWindowSize(Http2Settings) connection window} is derived from, so the
     * window stays a finite multiple of the stream window.
     */
    private static final long MAX_CONCURRENT_STREAMS_PER_CONNECTION = 100L;

    /**
     * Upper bound on the decoded HTTP/2 header block of one request, announced as the listener's
     * initial {@code SETTINGS_MAX_HEADER_LIST_SIZE}. Defined as {@link #MAX_HEADER_SIZE_BYTES} so the
     * HTTP/2 header block, {@code CONTINUATION} frames included, has the same 16 KiB bound as the
     * HTTP/1.1 one.
     */
    private static final long MAX_HEADER_LIST_SIZE_BYTES = MAX_HEADER_SIZE_BYTES;

    /**
     * Number of {@code RST_STREAM} frames a client may send on one HTTP/2 connection within
     * {@link #RST_FLOOD_WINDOW_SECONDS} before the transport ends the connection with
     * {@code GOAWAY ENHANCE_YOUR_CALM} — pinned at the transport's own default, never raised
     * (ADR-0066).
     */
    private static final int RST_FLOOD_MAX_RST_FRAMES_PER_WINDOW = 200;

    /** Length of the Rapid-Reset counting window in seconds — pinned at the transport's own default. */
    private static final int RST_FLOOD_WINDOW_SECONDS = 30;

    private final int admissionCap;
    private final int webSocketRelayCap;
    private final long requestBodyIdleTimeoutMillis;
    private final long requestBodyDeadlineMillis;

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
     * waiting for it. The request-body deadline stays the fixed 25,000 ms, which the idle timeout must
     * stay below. Public only because those edge tests live in the parent package; no configuration key
     * or system property reaches this constructor.
     *
     * @param hardening                    as for the public constructor
     * @param requestBodyIdleTimeoutMillis the request-body idle timeout in milliseconds, positive and
     *                                     below 25,000
     * @throws IllegalArgumentException when {@code requestBodyIdleTimeoutMillis} is not positive, or is
     *                                  25,000 ms or more — not shorter than the fixed request-body deadline
     */
    public EdgeHardeningOptions(EdgeHardeningConfig hardening, long requestBodyIdleTimeoutMillis) {
        this(hardening, requestBodyIdleTimeoutMillis, REQUEST_BODY_DEADLINE_MILLIS);
    }

    /**
     * <strong>Test-only.</strong> Resolves the admission budget exactly as the public constructor
     * does, but carries the given request-body idle timeout and request-body deadline in place of the
     * fixed 10,000 ms and 25,000 ms, so an edge test can observe both bounds without waiting for them.
     * Public only because those edge tests live in the parent package; no configuration key or system
     * property reaches this constructor.
     *
     * @param hardening                    as for the public constructor
     * @param requestBodyIdleTimeoutMillis the request-body idle timeout in milliseconds, positive
     * @param requestBodyDeadlineMillis    the request-body deadline in milliseconds, longer than the
     *                                     idle timeout
     * @throws IllegalArgumentException when {@code requestBodyIdleTimeoutMillis} is not positive, or
     *                                  {@code requestBodyDeadlineMillis} is not longer than it
     */
    public EdgeHardeningOptions(EdgeHardeningConfig hardening, long requestBodyIdleTimeoutMillis,
            long requestBodyDeadlineMillis) {
        if (requestBodyIdleTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "requestBodyIdleTimeoutMillis must be positive: " + requestBodyIdleTimeoutMillis);
        }
        if (requestBodyDeadlineMillis <= requestBodyIdleTimeoutMillis) {
            throw new IllegalArgumentException("requestBodyDeadlineMillis must be longer than the idle timeout: "
                    + requestBodyDeadlineMillis);
        }
        this.admissionCap = hardening.effectiveAdmissionCap();
        this.webSocketRelayCap = hardening.effectiveWebsocketRelayCap();
        this.requestBodyIdleTimeoutMillis = requestBodyIdleTimeoutMillis;
        this.requestBodyDeadlineMillis = requestBodyDeadlineMillis;
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
        boundHeaderListSize(settings);
        options.setMaxHeaderSize(MAX_HEADER_SIZE_BYTES)
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH_BYTES)
                .setMaxChunkSize(MAX_CHUNK_SIZE_BYTES)
                .setIdleTimeout(IDLE_TIMEOUT_SECONDS)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setInitialSettings(settings)
                .setHttp2ConnectionWindowSize(connectionWindowSize(settings))
                .setHttp2RstFloodMaxRstFramePerWindow(RST_FLOOD_MAX_RST_FRAMES_PER_WINDOW)
                .setHttp2RstFloodWindowDuration(RST_FLOOD_WINDOW_SECONDS)
                .setHttp2RstFloodWindowDurationTimeUnit(TimeUnit.SECONDS);
    }

    /**
     * Lowers the listener's announced header-list size to {@link #MAX_HEADER_LIST_SIZE_BYTES} when it
     * is unbounded or above that bound; a lower value is left as it is.
     *
     * @param settings the initial HTTP/2 settings of the listener, updated in place
     */
    private static void boundHeaderListSize(Http2Settings settings) {
        if (settings.getMaxHeaderListSize() > MAX_HEADER_LIST_SIZE_BYTES) {
            settings.setMaxHeaderListSize(MAX_HEADER_LIST_SIZE_BYTES);
        }
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
        return (int) Math.clamp(everyStreamsWindow, streamWindow, Integer.MAX_VALUE);
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
     * The held-answer sub-budget: how many answers the edge may hold back at once on a timer — the
     * uniform {@code 404} waiting for its response-timing floor, and the rejection answer of a source
     * tripped under the {@code delay} action of {@code enumeration_detection}.
     * <p>
     * A held answer hands its general admission permit back when it starts waiting and takes one of
     * these instead (see {@link EdgeAdmission#tryEnterHold}), so a caller producing answers that are
     * only held — a scan of unrouted addresses — cannot occupy the general pool for the length of the
     * floor and starve the traffic the gateway exists to serve. An answer refused a held permit is
     * answered {@code 503} at the release time the uniform {@code 404}'s floor gives it (at once when
     * {@code uniform_not_found} is off), under the general permit it was admitted with (see
     * {@code edge.end.ResponseDelay}).
     * <p>
     * Derived, not configured: a quarter of the {@linkplain #admissionCap() admission cap}, and never
     * below one — the proportion an undeclared WebSocket relay sub-budget is derived with (ADR-0020).
     * Held answers follow the pool they leave, so {@code edge_hardening.admission_cap} scales the
     * sub-budget, and unlike the relay sub-budget it has no key of its own.
     *
     * @return {@code max(1, admissionCap() / 4)}
     */
    public int heldAnswerCap() {
        return Math.max(1, admissionCap / 4);
    }

    /**
     * One source's share of the {@linkplain #heldAnswerCap() held-answer sub-budget} under the
     * {@code delay} action of {@code enumeration_detection}: how many answers held for a tripped source
     * may wait at once. A tripped source's answer beyond its share is refused before admission with the
     * block-style {@code 429} (see {@link SourceGate}), so one delayed source cannot occupy the whole
     * sub-budget, once that has more than one permit, and turn every other caller's held answer into a
     * refusal. Answers held for the uniform {@code 404}'s floor alone are not counted against any share.
     * <p>
     * Derived, not configured: an eighth of the held-answer sub-budget, and never below one — so once the
     * sub-budget has at least eight permits (an {@code edge_hardening.admission_cap} of {@code 32} or
     * more) at least eight tripped sources are needed to fill it; below that the share is one, and as
     * many tripped sources as the sub-budget has permits fill it. {@code edge_hardening.admission_cap}
     * scales the share with the sub-budget.
     *
     * @return {@code max(1, heldAnswerCap() / 8)}
     */
    public int heldAnswerSourceShare() {
        return Math.max(1, heldAnswerCap() / 8);
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
     * @return how long, in milliseconds from the start of its upstream attempt, a proxied dispatch may
     *         wait for the whole request body before it is answered {@code 408} and its upstream
     *         request is reset — a fixed gateway constant of 25,000 ms, below the upstream guard's
     *         timeout, which starts at the same moment
     */
    public long requestBodyDeadlineMillis() {
        return requestBodyDeadlineMillis;
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
