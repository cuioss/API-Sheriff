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
package de.cuioss.sheriff.gateway.edge.relay;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnd;
import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.http.UpstreamIdentityHeaders;
import de.cuioss.sheriff.gateway.routing.LocationRewriter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;

/**
 * Stage 7 — the streamed response relay.
 * <p>
 * The upstream status and (hop-by-hop-stripped) headers are copied to the client response, then the
 * upstream body is <strong>streamed back with backpressure</strong> via a Vert.x
 * {@link HttpClientResponse#pipe() pipe} — the body is never materialized — and the client response is
 * then ended through the edge's {@link ResponseEnd}, the one rule every response end passes. Header
 * rules:
 * <ul>
 *   <li><strong>Connection-specific headers</strong> plus the length/framing headers Vert.x
 *       recomputes are stripped in the response direction, per the shared
 *       {@link ConnectionHeaders} policy the asset envelope reads too.</li>
 *   <li><strong>Upstream identity headers</strong> — {@code Server}, {@code X-Powered-By} and the
 *       other {@link UpstreamIdentityHeaders} members — are removed, never rewritten to a neutral value,
 *       unless the route sets {@code keep_identity_headers: true}. The rule holds on the HTTP relay, on
 *       the gRPC leading headers and on the gRPC trailers alike; the asset envelope reads the same
 *       set.</li>
 *   <li><strong>Conditional-response headers</strong> ({@code ETag} / {@code Last-Modified}) pass
 *       through only when the route enables {@code not_modified}; on a disabled route they are
 *       stripped so no validator ever reaches the client. A {@code 304} on an enabled route relays
 *       untouched.</li>
 *   <li><strong>{@code Set-Cookie}</strong> lines of an upstream are relayed by the route's
 *       {@code cookies} policy, each as its own line (see {@link UpstreamSetCookieFilter}). A line
 *       naming one of the gateway's own cookies — every cookie under the reserved
 *       {@value GatewayCookies#RESERVED_PREFIX} prefix, the configured session cookie and, in every
 *       session mode, its activity cookie, as {@link GatewayCookies} defines them — is dropped under every
 *       mode; {@code allow: none}, the default, drops every line; a list relays only the lines setting
 *       a listed cookie; {@code allow: all} relays the rest. Every relayed line has its {@code Domain}
 *       attribute removed and keeps everything else as sent. The rule holds on the HTTP relay, on the
 *       gRPC leading headers and on the gRPC trailers alike.</li>
 *   <li><strong>{@code Location}</strong> is relayed unchanged unless the route opts into
 *       {@code upstream.rewrite_location}: {@link #relay} then maps a value pointing inside the route
 *       upstream back onto the route's match key through the route's {@link LocationRewriter}, and
 *       leaves every other value — a foreign origin above all — untouched. {@link #relayWithTrailers}
 *       applies no rewrite: the gRPC path carries no redirect.</li>
 *   <li><strong>Gateway headers</strong> accumulated on the request are applied last, in two maps whose
 *       names never overlap: a <em>set</em>-header replaces any upstream value of that name, a
 *       <em>default</em>-header (a gateway-owned security header whose {@code header_modes} entry is
 *       {@code default}) is added only when the upstream response carried no value for that name, so an
 *       origin-set header is relayed untouched.</li>
 * </ul>
 * <strong>One predicate judges every upstream header.</strong> The leading headers of {@link #relay},
 * the leading headers of {@link #relayWithTrailers} and the trailers it copies all pass
 * {@link #relayedUpstreamHeader}, which answers the value to relay or {@code null} for a header that
 * does not cross. It is the single seam every response-header rule lives in — the identity-header
 * strip among them: a rule added there holds on every relay path at once, and no path keeps a copy
 * of its own.
 * <p>
 * The gRPC relay ({@link #relayWithTrailers}) additionally ends a call that carried no frame in either
 * direction for its {@linkplain GrpcStreamIdleBound stream-idle bound}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ResponseStage {

    /** Conditional-response validators stripped unless the route enables {@code not_modified}. */
    private static final Set<String> CONDITIONAL_RESPONSE_HEADERS = Set.of("etag", "last-modified");

    private static final String LOCATION_HEADER = "Location";

    /** The trailer carrying the status a gRPC call ends with. */
    private static final String GRPC_STATUS_TRAILER = "grpc-status";
    /** The trailer carrying the message a gRPC call ends with. */
    private static final String GRPC_MESSAGE_TRAILER = "grpc-message";
    /** The fixed {@code grpc-message} of a call the stream-idle bound ended; carries nothing either side sent. */
    private static final String STREAM_IDLE_MESSAGE = "stream-idle-timeout";

    private final UpstreamSetCookieFilter upstreamSetCookies;

    /**
     * @param gatewayCookies the gateway's own cookies — an upstream {@code Set-Cookie} line naming one
     *                       of them is not relayed under any {@code cookies} policy
     */
    public ResponseStage(GatewayCookies gatewayCookies) {
        this.upstreamSetCookies = new UpstreamSetCookieFilter(gatewayCookies);
    }

    /**
     * The one judgement every upstream response header — leading header or trailer, on the HTTP relay
     * and on the gRPC relay — passes before it crosses back to the client: it must be
     * {@linkplain #isForwardableResponseHeader forwardable}, it must not be an
     * {@linkplain UpstreamIdentityHeaders identity header} unless the route keeps them, and a
     * {@code Set-Cookie} line must be one the route's {@code cookies} policy relays, which then crosses
     * with its {@code Domain} attribute removed.
     *
     * @param name               the upstream header name
     * @param value              the upstream header value
     * @param notModifiedEnabled  whether the route honours conditional requests / responses
     * @param cookies             the route's effective {@code cookies} block
     * @param keepIdentityHeaders whether the route opts out of the identity-header strip
     *                            ({@code keep_identity_headers: true}); with {@code false} an
     *                            {@link UpstreamIdentityHeaders} member does not cross
     * @return the value to relay, or {@code null} when the header does not cross
     */
    @Nullable
    String relayedUpstreamHeader(String name, String value, boolean notModifiedEnabled, CookiesConfig cookies,
            boolean keepIdentityHeaders) {
        if (!isForwardableResponseHeader(name, notModifiedEnabled)) {
            return null;
        }
        if (!keepIdentityHeaders && UpstreamIdentityHeaders.isIdentityHeader(name)) {
            return null;
        }
        return upstreamSetCookies.relayedValue(name, value, cookies);
    }

    /**
     * @param name               the upstream response-header name
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @return {@code true} when the header crosses back to the client
     */
    public static boolean isForwardableResponseHeader(String name, boolean notModifiedEnabled) {
        Objects.requireNonNull(name, "name");
        // The hop-by-hop / framing judgement is ConnectionHeaders', shared with the asset
        // envelope: one protocol rule, read by both paths that answer a client. Only the
        // conditional-validator gate below is this stage's own, since it turns on a route flag.
        if (ConnectionHeaders.isConnectionSpecific(name)) {
            return false;
        }
        return notModifiedEnabled || !CONDITIONAL_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Relays the upstream response to the client: status, filtered headers (with an upstream
     * {@code Location} mapped through the route's rewriter when it opts in), the gateway headers, then
     * the streamed body.
     *
     * @param upstream           the upstream response (body still streaming)
     * @param client             the client response write stream
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @param locationRewriter   the route's {@code upstream.rewrite_location} mapping, or
     *                           {@code null} to relay {@code Location} unchanged
     * @param cookies            the route's effective {@code cookies} block, deciding which upstream
     *                           {@code Set-Cookie} lines are relayed
     * @param keepIdentityHeaders whether the route keeps the upstream's {@link UpstreamIdentityHeaders}
     *                           ({@code keep_identity_headers: true}); with {@code false} they are
     *                           stripped
     * @param setHeaders         the set-mode gateway headers, overwriting an upstream value of the same name
     * @param defaultHeaders     the default-mode gateway headers, added only for a name the upstream
     *                           response did not carry
     * @param end                ends the client response once the upstream body has been streamed
     * @return a future completing when the body has been fully streamed and the response ended
     */
    @SuppressWarnings("java:S107") // the relay's route inputs, passed through by the terminal dispatch
    public Future<Void> relay(HttpClientResponse upstream, HttpServerResponse client,
            boolean notModifiedEnabled, @Nullable LocationRewriter locationRewriter, CookiesConfig cookies,
            boolean keepIdentityHeaders, Map<String, String> setHeaders, Map<String, String> defaultHeaders,
            ResponseEnd end) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(cookies, "cookies");
        Objects.requireNonNull(setHeaders, "setHeaders");
        Objects.requireNonNull(defaultHeaders, "defaultHeaders");
        Objects.requireNonNull(end, "end");

        client.setStatusCode(upstream.statusCode());
        for (Map.Entry<String, String> header : upstream.headers()) {
            String name = header.getKey();
            String relayed = relayedUpstreamHeader(name, header.getValue(), notModifiedEnabled, cookies,
                    keepIdentityHeaders);
            if (relayed != null) {
                // add (not set) so multi-valued headers such as Set-Cookie are all preserved.
                client.headers().add(name, relayedHeaderValue(name, relayed, locationRewriter));
            }
        }
        applyGatewayHeaders(upstream, client, setHeaders, defaultHeaders);
        applyResponseFraming(upstream, client);
        return pipeThenEnd(upstream, client, end);
    }

    /**
     * Streams the upstream body to the client with the client response held open, then ends it
     * through {@code end}. A failure of the stream fails the returned future and leaves the client
     * response open: the pipe is told not to end it on either outcome, since a Vert.x pipe ends its
     * destination on a failure by default, so the caller's failure path ends it through the same end
     * rule instead.
     */
    private static Future<Void> pipeThenEnd(HttpClientResponse upstream, HttpServerResponse client,
            ResponseEnd end) {
        Promise<Void> relayed = Promise.promise();
        upstream.pipe().endOnComplete(false).to(client).onComplete(piped -> {
            if (piped.failed()) {
                relayed.fail(piped.cause());
                return;
            }
            end.end(client::end).onComplete(relayed);
        });
        return relayed.future();
    }

    /**
     * Applies the gateway headers to a relayed response: every set-header overwrites, every
     * default-header is added only when the upstream response carried no value for that name. The
     * upstream check is case-insensitive, as header names are.
     */
    private static void applyGatewayHeaders(HttpClientResponse upstream, HttpServerResponse client,
            Map<String, String> setHeaders, Map<String, String> defaultHeaders) {
        setHeaders.forEach((name, value) -> client.headers().set(name, value));
        defaultHeaders.forEach((name, value) -> {
            if (!upstream.headers().contains(name)) {
                client.headers().set(name, value);
            }
        });
    }

    /**
     * The value a forwardable upstream response header carries to the client: an upstream
     * {@code Location} is mapped through the route's rewriter when the route opts into
     * {@code upstream.rewrite_location}; every other header — and {@code Location} on a route that
     * does not opt in — keeps its upstream value.
     *
     * @param name             the upstream response-header name
     * @param value            the upstream response-header value
     * @param locationRewriter the route's {@code upstream.rewrite_location} mapping, or {@code null}
     * @return the value relayed to the client
     */
    static String relayedHeaderValue(String name, String value, @Nullable LocationRewriter locationRewriter) {
        if (locationRewriter != null && LOCATION_HEADER.equalsIgnoreCase(name)) {
            return locationRewriter.rewrite(value);
        }
        return value;
    }

    /**
     * Relays the upstream response to the client <strong>including its trailing headers</strong> —
     * the gRPC path, where {@code grpc-status} / {@code grpc-message} are carried in the HTTP/2
     * response trailers (or, in the trailers-only case, the leading headers). The status and filtered
     * headers are copied exactly as {@link #relay}, then the body is streamed with the client response
     * held open ({@code endOnComplete(false)}); once the upstream body — and therefore its trailers —
     * has fully arrived, the upstream trailers are copied onto the client response and it is ended
     * through {@code end}, so the client observes the gRPC status. Each trailer passes the same
     * {@link #relayedUpstreamHeader} judgement as a leading header, so a {@code Set-Cookie} sent as a
     * trailer is relayed only when the route's {@code cookies} policy relays it. The response is set
     * chunked so trailers are framed on an HTTP/1.1 client (HTTP/2 ignores the flag and frames trailers
     * natively).
     * <p>
     * <strong>A call that carries no frame for the stream-idle bound is ended.</strong> The relay starts
     * {@code idleBound} once the body stream is set up and stops it when the relay settles. Every
     * upstream frame it relays counts as activity, as does every request frame of the stream
     * {@link GrpcStreamIdleBound#watch watched} by the same bound. When the bound expires — no frame in
     * either direction for its whole timeout — the relay resets the upstream request, which fails the
     * body stream, and ends the client response through {@code end} with the trailers
     * {@code grpc-status: 4} ({@code DEADLINE_EXCEEDED}) and {@code grpc-message: stream-idle-timeout}
     * in place of the upstream's. Any other failure of the body stream fails the returned future and
     * leaves the client response open, as before.
     *
     * @param upstream           the upstream response (body + trailers still streaming)
     * @param client             the client response write stream
     * @param notModifiedEnabled whether the route honours conditional requests / responses
     * @param cookies            the route's effective {@code cookies} block, deciding which upstream
     *                           {@code Set-Cookie} lines — leading header or trailer — are relayed
     * @param keepIdentityHeaders whether the route keeps the upstream's {@link UpstreamIdentityHeaders}
     *                           — leading header or trailer; with {@code false} they are stripped
     * @param setHeaders         the set-mode gateway headers, overwriting an upstream value of the same name
     * @param defaultHeaders     the default-mode gateway headers, added only for a name the upstream
     *                           response did not carry in its leading headers
     * @param end                ends the client response once the trailers have been copied onto it
     * @param idleBound          the call's stream-idle bound, started and stopped by this relay
     * @return a future completing when the body and trailers have been fully relayed — or the call was
     *         ended by its stream-idle bound — and the response ended
     */
    @SuppressWarnings("java:S107") // the relay's route inputs, passed through by the terminal dispatch
    public Future<Void> relayWithTrailers(HttpClientResponse upstream, HttpServerResponse client,
            boolean notModifiedEnabled, CookiesConfig cookies, boolean keepIdentityHeaders,
            Map<String, String> setHeaders, Map<String, String> defaultHeaders, ResponseEnd end,
            GrpcStreamIdleBound idleBound) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(cookies, "cookies");
        Objects.requireNonNull(setHeaders, "setHeaders");
        Objects.requireNonNull(defaultHeaders, "defaultHeaders");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(idleBound, "idleBound");

        client.setStatusCode(upstream.statusCode());
        for (Map.Entry<String, String> header : upstream.headers()) {
            String relayed = relayedUpstreamHeader(header.getKey(), header.getValue(), notModifiedEnabled, cookies,
                    keepIdentityHeaders);
            if (relayed != null) {
                client.headers().add(header.getKey(), relayed);
            }
        }
        applyGatewayHeaders(upstream, client, setHeaders, defaultHeaders);
        // gRPC trailers require a chunked (HTTP/1.1) or HTTP/2 response frame.
        client.setChunked(true);

        Promise<Void> relayed = Promise.promise();
        AtomicBoolean idleExpired = new AtomicBoolean();
        idleBound.expiry().onSuccess(expired -> {
            // Set before the reset, so the failure the reset causes below is read as this expiry.
            idleExpired.set(true);
            upstream.request().reset();
        });
        new WatchedStream(upstream, idleBound::recordActivity).pipe().endOnComplete(false).to(client)
                .onComplete(piped -> {
                    idleBound.stop();
                    if (piped.succeeded()) {
                        relayTrailers(upstream, client, notModifiedEnabled, cookies, keepIdentityHeaders);
                    } else if (idleExpired.get()) {
                        client.putTrailer(GRPC_STATUS_TRAILER, Integer.toString(GrpcStatusMapper.DEADLINE_EXCEEDED));
                        client.putTrailer(GRPC_MESSAGE_TRAILER, STREAM_IDLE_MESSAGE);
                    } else {
                        relayed.fail(piped.cause());
                        return;
                    }
                    end.end(client::end).onComplete(relayed);
                });
        // Started only once the body stream is set up, so a relay that fails to start arms nothing.
        idleBound.start();
        return relayed.future();
    }

    /**
     * Copies the upstream trailers that pass {@link #relayedUpstreamHeader} onto the client response,
     * each added as its own trailer so several {@code Set-Cookie} trailers are all kept.
     */
    private void relayTrailers(HttpClientResponse upstream, HttpServerResponse client, boolean notModifiedEnabled,
            CookiesConfig cookies, boolean keepIdentityHeaders) {
        for (Map.Entry<String, String> trailer : upstream.trailers()) {
            String relayed = relayedUpstreamHeader(trailer.getKey(), trailer.getValue(), notModifiedEnabled, cookies,
                    keepIdentityHeaders);
            if (relayed != null) {
                client.trailers().add(trailer.getKey(), relayed);
            }
        }
    }

    /**
     * Re-establishes the client response body framing after {@link #isForwardableResponseHeader
     * hop-by-hop stripping} removed the upstream framing headers. Only {@code Transfer-Encoding} is
     * hop-by-hop and must be recomputed by the client protocol; {@code Content-Length} is end-to-end
     * and — because the body is relayed byte-for-byte — remains accurate. Preserve a declared
     * upstream {@code Content-Length} so an HTTP/1.1 client receives a well-framed fixed-length body;
     * when the upstream framed the body as chunked (no {@code Content-Length}), stream the client
     * response chunked. Without this an HTTP/1.1 response defaults to {@code Content-Length: 0} and
     * the streamed body is silently dropped (HTTP/2 ignores both signals and is unaffected).
     */
    private static void applyResponseFraming(HttpClientResponse upstream, HttpServerResponse client) {
        String contentLength = upstream.getHeader("Content-Length");
        if (contentLength != null) {
            client.putHeader("Content-Length", contentLength);
        } else if (mayCarryBody(upstream.statusCode())) {
            client.setChunked(true);
        }
    }

    /**
     * @param status the relayed response status
     * @return {@code true} unless the status forbids a message body (1xx, 204, 304)
     */
    static boolean mayCarryBody(int status) {
        return status != 204 && status != 304 && (status < 100 || status >= 200);
    }

    /**
     * The stream-idle bound of one gRPC call: a fixed {@value #STREAM_IDLE_TIMEOUT_MILLIS} ms — five
     * minutes, Envoy's default stream idle timeout — during which the call must carry at least one frame
     * in either direction.
     * <p>
     * A gRPC dispatch arms neither the request-body idle timeout nor the request-body deadline:
     * client-streaming and bidirectional calls legitimately pause between messages. Before the upstream
     * sends its response headers the call is bounded by the upstream guard's whole-dispatch timeout; once
     * they have arrived the guarded call has returned, and this bound is what ends a call that went quiet.
     * A call's {@code grpc-timeout} header is not honoured, so the bound stays one fixed constant; no
     * configuration key or system property reaches it.
     * <p>
     * <strong>What counts as activity.</strong> Every request frame of the stream
     * {@linkplain #watch watched} by the bound, and every upstream frame
     * {@link ResponseStage#relayWithTrailers} relays. A client that stays silent while the upstream keeps
     * sending, and an upstream that stays silent while the client keeps sending, both keep the call open.
     * <p>
     * <strong>Lifecycle.</strong> The relay starts the bound, which arms its clock from that moment, and
     * stops it when the relay settles. When the timeout passes with no frame in either direction, the
     * {@linkplain #expiry() expiry} completes, exactly once; the relay then ends the call. A frame only
     * records when it arrived, so no timer is created or cancelled per frame: one timer is pending at a
     * time, and when it fires early relative to the last activity it is re-armed for the remainder.
     * <p>
     * Thread-safe: frames arrive on the inbound and the upstream connection's event loops, the timer fires
     * on the Vert.x instance's event loop, and the relay starts and stops the bound from either. The last
     * activity time is a volatile field; the timer state is held under one monitor.
     *
     * @since 1.0
     */
    public static final class GrpcStreamIdleBound {

        /** The fixed stream-idle timeout of a gRPC call: five minutes. */
        public static final long STREAM_IDLE_TIMEOUT_MILLIS = 300_000L;

        /** The pending-timer value while no timer is pending. */
        private static final long NO_TIMER = -1L;

        /** Added before a nanosecond remainder is converted, so the re-armed delay rounds up. */
        private static final long MILLIS_ROUNDING_NANOS = TimeUnit.MILLISECONDS.toNanos(1) - 1;

        private final Vertx vertx;
        private final long timeoutMillis;
        private final Promise<Void> expired = Promise.promise();
        /** Guards the timer state below. */
        private final Object timerLock = new Object();
        /** When the call last carried a frame in either direction, or when the bound was started. */
        private volatile long lastActivityNanos = System.nanoTime();
        private boolean stopped;
        private long pendingTimer = NO_TIMER;

        private GrpcStreamIdleBound(Vertx vertx, long timeoutMillis) {
            this.vertx = Objects.requireNonNull(vertx, "vertx");
            this.timeoutMillis = timeoutMillis;
        }

        /**
         * @param vertx the Vert.x instance the bound's timer is set on
         * @return the bound of one gRPC call, with the fixed {@link #STREAM_IDLE_TIMEOUT_MILLIS}
         */
        public static GrpcStreamIdleBound on(Vertx vertx) {
            return new GrpcStreamIdleBound(vertx, STREAM_IDLE_TIMEOUT_MILLIS);
        }

        /**
         * <strong>Test-only.</strong> The bound of one gRPC call with the given timeout in place of the
         * fixed five minutes, so a test can observe it without waiting for it. Public only because those
         * tests live outside this package; no configuration key or system property reaches it.
         *
         * @param vertx         the Vert.x instance the bound's timer is set on
         * @param timeoutMillis the stream-idle timeout in milliseconds, positive
         * @return the bound
         * @throws IllegalArgumentException when {@code timeoutMillis} is not positive
         */
        public static GrpcStreamIdleBound withTimeout(Vertx vertx, long timeoutMillis) {
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeoutMillis must be positive: " + timeoutMillis);
            }
            return new GrpcStreamIdleBound(vertx, timeoutMillis);
        }

        /**
         * The timeout this bound ends a call after: {@link #STREAM_IDLE_TIMEOUT_MILLIS} for a bound built
         * by {@link #on}. Public so a test outside this package can assert the bound the gRPC dispatch
         * builds.
         *
         * @return the stream-idle timeout in milliseconds
         * @since 1.0
         */
        public long timeoutMillis() {
            return timeoutMillis;
        }

        /**
         * Watches the call's request stream: every frame it delivers counts as activity.
         *
         * @param inbound the request body stream to be dispatched upstream
         * @return the stream to dispatch in its place; it delegates everything else to {@code inbound}
         */
        public ReadStream<Buffer> watch(ReadStream<Buffer> inbound) {
            return new WatchedStream(inbound, this::recordActivity);
        }

        /**
         * @return a future completing once — when the call carried no frame in either direction for the
         *         whole timeout after the bound was started; it never fails, and it never completes for a
         *         bound stopped in time
         */
        public Future<Void> expiry() {
            return expired.future();
        }

        /** The call carried a frame: the idle gap restarts from now. */
        void recordActivity() {
            lastActivityNanos = System.nanoTime();
        }

        /**
         * Arms the clock from now; the relay calls it once. A no-op for a bound already stopped — a relay
         * that settled before it started.
         */
        void start() {
            synchronized (timerLock) {
                if (stopped) {
                    return;
                }
                lastActivityNanos = System.nanoTime();
                armTimer(timeoutMillis);
            }
        }

        /** Stops the bound for good and cancels its pending timer. Idempotent. */
        void stop() {
            synchronized (timerLock) {
                stopped = true;
                if (pendingTimer != NO_TIMER) {
                    vertx.cancelTimer(pendingTimer);
                    pendingTimer = NO_TIMER;
                }
            }
        }

        /** Schedules the one pending timer; called holding {@link #timerLock}. */
        private void armTimer(long delayMillis) {
            pendingTimer = vertx.setTimer(delayMillis, fired -> onTimer());
        }

        /**
         * The pending timer fired. A stopped bound does nothing. A bound whose last activity lies less
         * than the timeout back re-arms for the remainder; otherwise the call carried no frame for the
         * whole timeout and the bound expires.
         */
        private void onTimer() {
            synchronized (timerLock) {
                pendingTimer = NO_TIMER;
                if (stopped) {
                    return;
                }
                long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                        - (System.nanoTime() - lastActivityNanos);
                if (remainingNanos > 0) {
                    armTimer(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + MILLIS_ROUNDING_NANOS)));
                    return;
                }
                stopped = true;
            }
            // Outside the monitor: the relay's reaction resets the upstream request.
            expired.tryComplete();
        }
    }

    /**
     * A {@link ReadStream} decorator that runs {@code onFrame} for every frame the stream delivers, before
     * handing the frame on; everything else is delegated unchanged.
     */
    private static final class WatchedStream implements ReadStream<Buffer> {

        private final ReadStream<Buffer> delegate;
        private final Runnable onFrame;

        WatchedStream(ReadStream<Buffer> delegate, Runnable onFrame) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.onFrame = Objects.requireNonNull(onFrame, "onFrame");
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            if (handler == null) {
                delegate.handler(null);
                return this;
            }
            delegate.handler(frame -> {
                onFrame.run();
                handler.handle(frame);
            });
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            delegate.exceptionHandler(handler);
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            delegate.pause();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            delegate.resume();
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            delegate.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            delegate.endHandler(endHandler);
            return this;
        }
    }
}
