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

import java.util.List;
import java.util.function.Supplier;

import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.events.EventType;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;

/**
 * The one rule every HTTP response end of the edge passes through ({@link #endResponse}), and the
 * small response-writing helpers every writer shares: the bare-status {@linkplain #reject refusal},
 * the multi-line {@linkplain #applyStageSetCookies Set-Cookie} write and the
 * {@linkplain #isHttp1 HTTP/1.x} test.
 * <p>
 * Stateless; every method runs on the event loop of the request it is handed, except where noted.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ResponseEnds {

    /** Holds the request body armed by the edge root as a {@link DispatchStage.InboundBody}, so the
     * upstream dispatch — which runs after the virtual-thread hop — streams the body through the
     * decorator that remembers an inbound failure, and so that {@link #endResponse} can mark the
     * stream as reset by the gateway before it ends the response. Absent on the reserved-body path,
     * which reads its body itself and never dispatches upstream. */
    public static final String INBOUND_BODY_KEY = "sheriff.inboundbody";
    /** The failure an end deferred by {@link #endResponse} reports when another writer ended the
     * response while it waited. */
    private static final String RESPONSE_ALREADY_ENDED = "The response was ended by another writer while its end waited";
    /** The HTTP/2 error code {@code NO_ERROR}: the stream is ended without signalling a fault. */
    private static final long HTTP2_NO_ERROR = 0L;
    private static final String SET_COOKIE_HEADER = "Set-Cookie";

    private ResponseEnds() {
        // static helpers only
    }

    /**
     * The one rule every HTTP response end passes through: <strong>an HTTP/2 response answered while
     * its request body is still open is ended only once request data stops arriving or a time cap
     * passes, and its stream is then reset with {@code NO_ERROR}</strong>; the connection stays open.
     * Every writer that ends a response hands the call that ends it here instead of making it — the relay
     * ({@link de.cuioss.sheriff.gateway.edge.terminal.TerminalDispatch#relayOnEventLoop}, through the
     * {@link ResponseEnd} it gives {@link de.cuioss.sheriff.gateway.edge.relay.ResponseStage}) and its
     * failure answer ({@code TerminalDispatch.failRelay}), the problem document, the HTML error page and
     * the portal page, the reserved-path answer, the buffered asset, the redirect, the short-circuit, the
     * gRPC trailers-only status (through {@link de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper}),
     * the WebSocket relay's answer to a failed upstream dial and its relay of an upstream's upgrade
     * refusal, both written before any upgrade (through
     * {@link de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage}), and the admission {@code 503}.
     * <ul>
     *   <li><strong>HTTP/1.x, or a request body that has ended</strong> — the response is ended at once
     *       and nothing else happens. An HTTP/1.x request is never reset; where the bytes behind a
     *       rejected request cannot be trusted, its connection is retired instead
     *       ({@link ConnectionRetirement#retireMarkedConnection}).</li>
     *   <li><strong>HTTP/2, request body still open</strong> — an upstream that answered before reading
     *       the whole body, and every gateway-written answer to an upload, a framing rejection and a
     *       reserved-body {@code 413} among them. The gateway stops receiving the body at once; the end
     *       of the response and the reset then follow together, once request data stops arriving or
     *       the time cap passes (see {@code EarlyResponseEnd}).</li>
     * </ul>
     * <p>
     * <strong>Stopping the body.</strong> The armed {@link DispatchStage.InboundBody} is marked
     * {@linkplain DispatchStage.InboundBody#resetByGateway() reset by the gateway}: the upstream request
     * still receiving the body is reset — its forwarding stops — the inbound stream is paused for good,
     * and neither the reset nor the transport error it may surface is ever recorded as an
     * {@link EventType#INBOUND_BODY_ABORTED}. A request with no armed body is paused directly. A paused
     * HTTP/2 request returns no flow-control window, so from here on the client can send no more than
     * the window it already holds.
     * <p>
     * <strong>Why the end waits for the data in flight.</strong> The reset tells the client to stop
     * sending the rest of the request (RFC 9113 §8.1) before it sees a complete response and resets the
     * stream itself — a client reset the Rapid-Reset guard counts. A reset written while request data is
     * still on the way would not help: the transport answers each data frame arriving after it with a
     * {@code STREAM_CLOSED} reset of its own, counted against the same limit. Holding the final frame
     * back until request data stops arriving avoids both. The wait is capped, and at the cap request
     * data may still be in flight (see {@code EarlyResponseEnd}).
     * <p>
     * <strong>The reset waits for the write, not for the end.</strong> A response is <em>ended</em> the
     * moment its last frame is handed to the transport, which may still hold that frame behind HTTP/2
     * flow control; a reset written then would discard it. The reset is decided once the end has been
     * written, and only a stream the client has not ended in the meantime is reset. Every other stream
     * multiplexed on the connection is untouched. No {@code Connection} header is written — it is a
     * connection-specific field HTTP/2 forbids.
     * <p>
     * Not routed through here: the late body refusal of a bodyless request
     * ({@code TerminalDispatch.lateBodyRefusal}, which resets with {@code CANCEL} before any end), the
     * WebSocket upgrade and the L4 passthrough relay.
     *
     * @param ctx    the request whose response is to be ended
     * @param ending the call that ends the response, returning the future of that end
     * @return the future of the end — on the deferred path completing once the reset has been decided
     */
    public static Future<Void> endResponse(RoutingContext ctx, Supplier<Future<Void>> ending) {
        HttpServerRequest request = ctx.request();
        if (isHttp1(request.version()) || request.isEnded()) {
            return ending.get();
        }
        DispatchStage.InboundBody armed = ctx.get(INBOUND_BODY_KEY);
        if (armed != null) {
            armed.resetByGateway();
        } else {
            request.pause();
        }
        HttpServerResponse response = ctx.response();
        // The response may have been ended by another writer while this end waited; it is then left as
        // it is.
        return EarlyResponseEnd.endWhenQuiet(request,
                () -> response.ended() ? Future.failedFuture(RESPONSE_ALREADY_ENDED) : ending.get(),
                () -> response.reset(HTTP2_NO_ERROR));
    }

    /**
     * @param version the negotiated HTTP version of a request
     * @return {@code true} for HTTP/1.0 and HTTP/1.1
     */
    public static boolean isHttp1(HttpVersion version) {
        return version == HttpVersion.HTTP_1_0 || version == HttpVersion.HTTP_1_1;
    }

    /**
     * Answers a request the edge refuses before or instead of processing it — the admission or
     * draining {@code 503}, an executor rejection, an exhausted WebSocket relay budget — with a bare
     * status. Called on the event loop; the end passes the one end rule ({@link #endResponse}), so an
     * HTTP/2 upload refused here has its open stream reset with {@code NO_ERROR} once its body stops
     * arriving or the time cap passes and the answer has been written.
     *
     * @param ctx    the refused request
     * @param status the status to answer with
     */
    public static void reject(RoutingContext ctx, int status) {
        HttpServerResponse response = ctx.response();
        if (!response.ended()) {
            response.setStatusCode(status);
            endResponse(ctx, response::end);
        }
    }

    /**
     * Writes the pipeline's accumulated {@code Set-Cookie} values as distinct header lines.
     * {@code Set-Cookie} is legitimately multi-valued (RFC 6265 §3) — the session runtime can emit a
     * clearing cookie and a login-binding cookie on the same response — so each value gets its own
     * line and is never comma-joined into, or overwritten in, the single-valued stage-header map.
     *
     * @param response         the response under construction
     * @param setCookieHeaders the {@code Set-Cookie} values, each written as its own line
     */
    public static void applyStageSetCookies(HttpServerResponse response, List<String> setCookieHeaders) {
        setCookieHeaders.forEach(cookie -> response.headers().add(SET_COOKIE_HEADER, cookie));
    }
}
