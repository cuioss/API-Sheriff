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
package de.cuioss.sheriff.gateway.edge.end;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.FramingGate;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * Retires an HTTP/1.x connection whose request boundaries can no longer be trusted: after a framing
 * rejection — the framing gate's, or a body the dispatch could not finish reading. A connection so
 * marked is closed once its answer has been written, and a request that still arrives on it is never
 * processed (see {@link #isRetired}). A reserved-body {@code 413} closes its connection after the
 * answer too, but only through {@link #retireConnectionAfterResponse}: it is not recorded in the
 * retired set. An HTTP/2 rejection ends only its stream instead, through the one end rule
 * ({@link ResponseEnds#endResponse}).
 * <p>
 * Thread-safe: the retired-connection set is synchronized, and a mark is recorded on the virtual
 * thread while it is read on the connection's event loop. A retirement recorded from another event
 * loop — a request body that outlived its deadline after the hand-over — goes through
 * {@link #recordRetirement} only, never through the context-data mark, which only the virtual thread
 * and the client connection's loop touch.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConnectionRetirement {

    /** Set once a framing rejection — the framing gate's, or a body the dispatch could not finish
     * reading — has fallen on an HTTP/1.x request: the writer that answers it then retires the
     * connection the answer is written on (see {@link #markFramingRejection}). The virtual-thread hop
     * carries only the {@link RoutingContext}, so the marker rides on it to the event-loop writer. */
    private static final String RETIRE_CONNECTION_KEY = "sheriff.retireconnection";
    private static final String CONNECTION_HEADER = "Connection";
    private static final String CONNECTION_CLOSE = "close";

    private final FramingGate framingGate;
    /**
     * The HTTP/1.x connections retired by a framing-gate rejection. A request that arrives on one of
     * them is never processed (see {@link #isRetired}). An HTTP/2 connection is never recorded here: a
     * framing rejection there ends only the rejected stream. Held weakly and by identity —
     * {@link HttpConnection} implementations do not override {@code equals} — so an entry disappears
     * with its connection and no close handler has to be claimed on a connection the edge does not own.
     */
    private final Set<HttpConnection> retiredConnections =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * @param framingGate the stage-1 framing gate whose rejections retire a connection
     */
    public ConnectionRetirement(FramingGate framingGate) {
        this.framingGate = Objects.requireNonNull(framingGate, "framingGate");
    }

    /**
     * @param connection the connection a request arrived on
     * @return {@code true} when the connection was retired by a framing rejection, so nothing that
     *         arrives on it may be processed, answered or forwarded
     */
    public boolean isRetired(HttpConnection connection) {
        return retiredConnections.contains(connection);
    }

    /**
     * Runs the framing gate and, when it rejects, marks how much of the inbound transport the
     * rejection ends before it is rendered. The scope follows the negotiated HTTP version of the
     * request, because that is what decides how far a framing ambiguity reaches.
     * <p>
     * <strong>HTTP/1.x — the connection.</strong> Requests share one byte stream there, so a request
     * whose message boundaries two parsers can read differently leaves the bytes behind it on the same
     * connection without trustworthy framing either. Nothing behind a framing rejection is processed:
     * the connection is recorded as retired at once — on this virtual thread, before any response is
     * written, so a request already queued behind the rejected one is refused by the edge root (see
     * {@link #isRetired}) — and the writer that answers the rejection advertises
     * {@code Connection: close} and closes the connection once the {@code 400} has been written.
     * <p>
     * <strong>HTTP/2 — the stream.</strong> Each request is framed by the transport on its own stream,
     * so a rejected request says nothing about its siblings. Only the rejected stream is ended, by the
     * one end rule every response passes ({@link ResponseEnds#endResponse}): the {@code 400} is written
     * and, while the rejected body is still open, the stream is then reset with {@code NO_ERROR};
     * nothing further arriving on it is processed. No mark is needed for that. The connection stays
     * open, it is never recorded as retired, and every other stream on it completes normally.
     * <p>
     * The connection mark is acted on by {@link #retireMarkedConnection}. The gate itself is unchanged: the
     * rejection is rethrown as it was raised and is metered, logged and rendered like every other
     * {@link EventType#SECURITY_FILTER_VIOLATION}.
     *
     * @param ctx     the request the pipeline is processing
     * @param request the pipeline request the framing gate judges
     * @throws GatewayException the gate's own rejection, unchanged
     */
    public void rejectFramingAndRetire(RoutingContext ctx, PipelineRequest request) {
        try {
            framingGate.process(request);
        } catch (GatewayException framingRejection) {
            markFramingRejection(ctx);
            throw framingRejection;
        }
    }

    /**
     * Marks how much of the inbound transport a framing rejection ends, by the negotiated HTTP
     * version, exactly as {@link #rejectFramingAndRetire} describes: an HTTP/1.x connection is
     * recorded as retired at once and the answer retires it; an HTTP/2 request needs no mark, its
     * stream being ended by the one end rule (see {@link ResponseEnds#endResponse}). Shared by the
     * framing gate and by the dispatch, which leaves behind a body it could not finish reading — one it
     * refused on its framing, or one that stopped arriving (see
     * {@code TerminalDispatch.dispatchMarkingUnreadBody}).
     *
     * @param ctx the rejected request
     */
    public void markFramingRejection(RoutingContext ctx) {
        if (ResponseEnds.isHttp1(ctx.request().version())) {
            retiredConnections.add(ctx.request().connection());
            ctx.put(RETIRE_CONNECTION_KEY, Boolean.TRUE);
        }
    }

    /**
     * Records an HTTP/1.x connection as retired in the synchronized retired set only, leaving the
     * request's context data untouched; a no-op for an HTTP/2 request. For a caller that runs on an
     * event loop other than the client connection's: the context data map is a plain map owned by the
     * client connection's loop, so a mark written to it from a foreign loop is a data race. The
     * synchronized set carries the retirement across loops instead, and the answer that retires the
     * connection is told by the caller, not by a mark on the context (see
     * {@link #retireConnectionAfterResponse}). Used for a request body that outlived the request-body
     * deadline after the hand-over (see {@code TerminalDispatch.lateBodyRefusal}).
     *
     * @param ctx the request whose connection is retired
     */
    public void recordRetirement(RoutingContext ctx) {
        if (ResponseEnds.isHttp1(ctx.request().version())) {
            retiredConnections.add(ctx.request().connection());
        }
    }

    /**
     * Retires the connection the response under construction is written on: the response advertises
     * {@code Connection: close} (HTTP/1.x only — the header is forbidden in HTTP/2), and the
     * connection is closed once the response has been written. Must be called on the event loop,
     * before the response is ended.
     * <p>
     * It has two callers, both for an HTTP/1.x request only — an HTTP/2 rejection ends just its
     * stream instead (see {@link ResponseEnds#endResponse}) — and both retire the connection because
     * the bytes behind the rejected request cannot be trusted to frame the next one:
     * <ul>
     *   <li>a {@code 413} reserved-body rejection, on BOTH of its paths (the pre-read
     *       {@code Content-Length} refusal and the mid-stream ceiling abort). The request body is
     *       deliberately left <em>unconsumed</em> — reading an attacker-supplied oversized body to
     *       completion is exactly the work the ceiling exists to avoid, so draining it is not an
     *       option. But unread bytes left pending on a reused HTTP/1.1 keep-alive connection desync
     *       the next request framed on that connection, or pin the connection until the idle timeout —
     *       which would leave the reserved-body DoS guard only half-effective, since an attacker could
     *       still tie up connections by repeatedly tripping the {@code 413};</li>
     *   <li>a framing rejection of an HTTP/1.x request — the framing gate's, or a body the dispatch
     *       could not finish reading (see {@link #markFramingRejection}).</li>
     * </ul>
     *
     * @param ctx the request whose connection is retired
     */
    public static void retireConnectionAfterResponse(RoutingContext ctx) {
        HttpServerResponse response = ctx.response();
        if (!response.headWritten() && ResponseEnds.isHttp1(ctx.request().version())) {
            response.putHeader(CONNECTION_HEADER, CONNECTION_CLOSE);
        }
        // addEndHandler is additive, so this composes with the admission-release handler the edge root
        // registered rather than replacing it.
        ctx.addEndHandler(result -> ctx.request().connection().close());
    }

    /**
     * Applies the connection mark {@link #rejectFramingAndRetire} left on the request: retires the
     * HTTP/1.x connection; a no-op for every other request. Called by the gateway-originated error
     * writers on the event loop, immediately before they end the response. A request body that outlived
     * the request-body deadline after the hand-over carries no such mark: it is recorded by
     * {@link #recordRetirement} and the failed relay's end retires its connection on the late refusal's
     * event instead (see {@code TerminalDispatch.retireFailedRelayConnection}).
     *
     * @param ctx the request being answered
     */
    public static void retireMarkedConnection(RoutingContext ctx) {
        if (ctx.get(RETIRE_CONNECTION_KEY) != null) {
            retireConnectionAfterResponse(ctx);
        }
    }
}
