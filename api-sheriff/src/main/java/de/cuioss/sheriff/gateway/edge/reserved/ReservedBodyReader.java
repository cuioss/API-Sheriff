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
package de.cuioss.sheriff.gateway.edge.reserved;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.RejectionReporter;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Reads the body of the one body-carrying reserved POST path — back-channel logout — on the event
 * loop, before the virtual-thread dispatch, under a byte ceiling and a wall-clock deadline, so its
 * handler never has to drain a paused stream from a virtual thread.
 * <p>
 * Thread-safe: each read keeps its state on the request; the collaborators are thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ReservedBodyReader {

    private static final CuiLogger LOGGER = new CuiLogger(ReservedBodyReader.class);

    // Fail-closed deadline for reading a tiny reserved-POST form body (a back-channel logout_token —
    // the only such body left). It bounds a genuinely slow/stalled body so it cannot
    // pin admission indefinitely; it is deliberately generous because the body is read on a shared event
    // loop that can be scheduling-starved under CPU contention, and the deadline handler still honours a
    // body that has already fully arrived (see readReservedBodyThenDispatch), so a legitimate body is
    // never falsely rejected — the deadline only fires for a body that truly never completes.
    private static final long RESERVED_BODY_READ_TIMEOUT_SECONDS = 20L;
    /** Holds the fully-read {@code application/x-www-form-urlencoded} body of the one body-carrying
     * reserved POST path (back-channel logout), buffered on the event loop by
     * {@link #readReservedBodyThenDispatch} before the virtual-thread dispatch so the handler never has
     * to re-arm a paused stream. Left unset on a read failure or timeout, so {@link #readFormBody}
     * reads {@code null} and the receiver fails closed to {@code 400}. */
    private static final String RESERVED_BODY_KEY = "sheriff.reservedbody";

    private final ReservedPathRegistry reservedPathRegistry;
    private final long reservedBodyMaxBytes;
    private final GatewayEventCounter gatewayEventCounter;
    private final RejectionReporter rejectionReporter;
    private final ErrorResponseWriter errorResponseWriter;

    /**
     * @param reservedPathRegistry the OIDC reserved-path registry the body-carrying path is matched
     *                             against
     * @param reservedBodyMaxBytes the reserved-body byte ceiling
     *                             ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#reservedBodyMaxBytes()})
     * @param gatewayEventCounter  the shared in-process event counter
     * @param rejectionReporter    the reporter an over-ceiling body is metered through
     * @param errorResponseWriter  the writer an over-ceiling body is answered by
     */
    public ReservedBodyReader(ReservedPathRegistry reservedPathRegistry, long reservedBodyMaxBytes,
            GatewayEventCounter gatewayEventCounter, RejectionReporter rejectionReporter,
            ErrorResponseWriter errorResponseWriter) {
        this.reservedPathRegistry = Objects.requireNonNull(reservedPathRegistry, "reservedPathRegistry");
        this.reservedBodyMaxBytes = reservedBodyMaxBytes;
        this.gatewayEventCounter = Objects.requireNonNull(gatewayEventCounter, "gatewayEventCounter");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
    }

    /**
     * @param ctx the admitted request, on its event loop
     * @return {@code true} when the request is a reserved POST path whose {@code x-www-form-urlencoded}
     *         body a handler consumes. Matched on the raw path against the reserved registry's
     *         exact-match set, so only an exact clean reserved path (raw == canonical) triggers the
     *         eager body read; every other request pauses as before.
     *         <p>
     *         <strong>Allowlist decision — {@code CALLBACK} was removed, deliberately.</strong> The
     *         gateway now drives the authorization request with {@code response_mode=query}, so the
     *         OIDC callback is a top-level GET carrying its {@code code}/{@code state} in the query
     *         string and consuming no body at all. Leaving {@code ReservedEndpoint.CALLBACK} in this
     *         allowlist would not have been inert: it would keep granting <em>any unauthenticated</em>
     *         {@code POST} to the callback path a pre-authentication, pre-pipeline body read of up to
     *         the reserved-body ceiling before the handler could reject it —
     *         a retained surface with no remaining purpose. The tighter posture was chosen. A stray
     *         {@code POST} to the callback path now takes the ordinary paused-stream path, reaches the
     *         callback with no body, and is rejected {@code 400} for a missing {@code state} (see
     *         {@code BffRuntime.callbackParameters}) — an honest rejection, never a {@code 500}. An
     *         IdP still configured to {@code form_post} to this gateway is therefore no longer
     *         supported, which is intended: the gateway itself selects the mode.
     *         <p>
     *         <strong>Back-channel logout is unaffected.</strong> It remains a genuinely
     *         body-carrying reserved POST — it stays in this allowlist and its ceiling is unchanged.
     */
    public boolean needsReservedBodyRead(RoutingContext ctx) {
        if (!"POST".equalsIgnoreCase(ctx.request().method().name())) {
            return false;
        }
        String host = EdgeRequests.authorityHost(ctx.request());
        return reservedPathRegistry.match(host, ctx.request().path())
                .filter(kind -> kind == ReservedEndpoint.BACKCHANNEL_LOGOUT)
                .isPresent();
    }

    /**
     * Reads the reserved-POST body on the event loop under a byte ceiling, stashes it under
     * {@link #RESERVED_BODY_KEY}, then runs {@code dispatch} exactly once. The request is NOT paused:
     * the stream drains on its own event loop, fully asynchronously — no virtual-thread {@code .get()}
     * blocks on a contended event loop.
     * <p>
     * <strong>Two bounds, both mandatory.</strong> The back-channel logout path is read
     * <em>pre-authentication</em> and <em>before</em> {@code basicChecksStage} /
     * {@code thoroughChecksStage} run, so the per-route {@link SecurityConfiguration#maxBodySize()} cap
     * that bounds every ordinary proxied route can never apply here, and the transport's
     * {@code EdgeHardeningOptions} chunk bound caps only one chunk, not the cumulative body:
     * <ol>
     *   <li><strong>Byte ceiling</strong>
     *       ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#reservedBodyMaxBytes()}),
     *       enforced twice — a {@code Content-Length} pre-check that refuses before a single body byte
     *       is buffered, and a streaming cumulative counter that aborts mid-read. The pre-check alone
     *       is not sufficient: {@code Content-Length} is attacker-controlled and is absent entirely
     *       under chunked transfer-encoding. Either breach is rejected {@code 413}
     *       ({@link EventType#RESERVED_BODY_TOO_LARGE}) — never a {@code 500}, and never by silently
     *       truncating the body into the handler.</li>
     *   <li><strong>Wall-clock deadline</strong> ({@value #RESERVED_BODY_READ_TIMEOUT_SECONDS}s),
     *       guarding a body that truly never completes. The deadline handler still honours a body that
     *       has already fully arrived (it inspects {@link HttpServerRequest#isEnded()}), so a
     *       legitimate body delayed only by event-loop scheduling under CPU contention is never falsely
     *       rejected.</li>
     * </ol>
     *
     * @param ctx      the admitted reserved-POST request, on its event loop
     * @param dispatch hands the request to the virtual-thread pipeline once the body has been read, or
     *                 has failed or timed out; run at most once, and not at all for an over-ceiling
     *                 body
     */
    public void readReservedBodyThenDispatch(RoutingContext ctx, Runnable dispatch) {
        HttpServerRequest request = ctx.request();
        long ceiling = reservedBodyMaxBytes;
        // Bound 1a — declared size. Refuse before the stream is ever resumed, so an oversized declared
        // body costs the gateway zero heap.
        if (EdgeRequests.parseContentLength(request) > ceiling) {
            rejectOversizedReservedBody(ctx, ceiling, "declared-content-length");
            return;
        }
        AtomicBoolean bodyDone = new AtomicBoolean();
        Buffer accumulated = Buffer.buffer();
        long timer = ctx.vertx().setTimer(TimeUnit.SECONDS.toMillis(RESERVED_BODY_READ_TIMEOUT_SECONDS), id -> {
            if (bodyDone.compareAndSet(false, true)) {
                if (request.isEnded()) {
                    // The body actually arrived; only the end callback had not yet drained from this
                    // (contention-starved) event loop's queue. Honour it rather than falsely reject.
                    ctx.put(RESERVED_BODY_KEY, accumulated);
                } else {
                    // A body that truly never completed within the deadline — leave RESERVED_BODY_KEY
                    // unset so the receiver fails closed to 400 rather than pinning on it indefinitely.
                    LOGGER.debug("Reserved form body read did not complete within %s s — failing closed",
                            RESERVED_BODY_READ_TIMEOUT_SECONDS);
                }
                dispatch.run();
            }
        });
        // Bound 1b — actual size. Count cumulatively as the chunks land and abort the moment the next
        // chunk would cross the ceiling, so a chunked (or lying-Content-Length) body is bounded by what
        // it really sends rather than by what it claimed.
        request.handler(chunk -> {
            if (bodyDone.get()) {
                return;
            }
            if (accumulated.length() + (long) chunk.length() > ceiling) {
                bodyDone.set(true);
                ctx.vertx().cancelTimer(timer);
                rejectOversizedReservedBody(ctx, ceiling, "streamed-body");
                return;
            }
            accumulated.appendBuffer(chunk);
        });
        request.exceptionHandler(cause -> {
            if (bodyDone.compareAndSet(false, true)) {
                ctx.vertx().cancelTimer(timer);
                // Read failure: leave RESERVED_BODY_KEY unset (fail closed to 400).
                LOGGER.debug(cause, "Reserved form body read failed — failing closed");
                dispatch.run();
            }
        });
        request.endHandler(end -> {
            if (bodyDone.compareAndSet(false, true)) {
                ctx.vertx().cancelTimer(timer);
                ctx.put(RESERVED_BODY_KEY, accumulated);
                dispatch.run();
            }
        });
        // The inbound request stream arrives in fetch/paused mode; resume() (on this event loop)
        // re-arms it so the body is delivered to the handlers registered above.
        request.resume();
    }

    /**
     * Returns the raw {@code application/x-www-form-urlencoded} body of the one reserved POST path that
     * still carries one — the back-channel logout receiver — buffered on the event loop by
     * {@link #readReservedBodyThenDispatch} before the virtual-thread dispatch. A read failure or
     * timeout leaves {@link #RESERVED_BODY_KEY} unset, so this returns {@code null} — the fail-closed
     * default a receiver rejects {@code 400} (a body the gateway could not read is not an accepted
     * token). An over-ceiling body never reaches here at all: it is refused {@code 413} at the read
     * itself.
     */
    static @Nullable String readFormBody(RoutingContext ctx) {
        Buffer body = ctx.get(RESERVED_BODY_KEY);
        return body == null ? null : body.toString(StandardCharsets.UTF_8);
    }

    /**
     * Fails an over-ceiling reserved-path body closed with {@code 413}, the honest status for a payload
     * the gateway refuses on size. The pipeline never runs for such a request, so this meters and
     * renders directly rather than raising a {@link GatewayException}; the WARN records the ceiling and
     * a fixed disposition only — never the offending body.
     * <p>
     * The request body is left unconsumed, so how much of the inbound transport the rejection ends
     * follows the negotiated HTTP version of the request, on both paths (the pre-read
     * {@code Content-Length} refusal and the mid-stream ceiling abort):
     * <ul>
     *   <li><strong>HTTP/1.x — the connection.</strong> The {@code 413} advertises
     *       {@code Connection: close} and the connection is closed once it has been written (see
     *       {@link ConnectionRetirement#retireConnectionAfterResponse}), because unread body bytes
     *       would otherwise frame the next request on the same keep-alive connection.</li>
     *   <li><strong>HTTP/2 — the stream.</strong> The offending stream, its body still open, passes the
     *       one end rule (see {@link ResponseEnds#endResponse}): the gateway stops receiving the body,
     *       and once its data stops arriving or the wait's time cap passes the {@code 413} is ended and
     *       the stream reset with {@code NO_ERROR}. The connection and every other stream on it stay
     *       open. No {@code Connection} header is written.</li>
     * </ul>
     */
    private void rejectOversizedReservedBody(RoutingContext ctx, long ceiling, String disposition) {
        LOGGER.warn(ApiSheriffLogMessages.WARN.RESERVED_BODY_TOO_LARGE, ceiling, disposition);
        gatewayEventCounter.increment(EventType.RESERVED_BODY_TOO_LARGE);
        rejectionReporter.recordError(ctx, EventType.RESERVED_BODY_TOO_LARGE);
        if (ResponseEnds.isHttp1(ctx.request().version())) {
            ConnectionRetirement.retireConnectionAfterResponse(ctx);
        }
        // Ending the response releases the admission permit through the end handler the edge root
        // registered, exactly like every other terminal path.
        errorResponseWriter.renderProblem(ctx, null, EventType.RESERVED_BODY_TOO_LARGE);
    }
}
