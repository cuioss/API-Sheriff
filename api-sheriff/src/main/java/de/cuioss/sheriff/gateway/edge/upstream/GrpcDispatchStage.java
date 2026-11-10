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

import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.Route;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.streams.ReadStream;

/**
 * Stage 6 for a {@code protocol: grpc} route — the forced-HTTP/2 upstream dispatch.
 * <p>
 * gRPC requires HTTP/2 end-to-end, so a gRPC route's upstream client is a <strong>forced-h2</strong>
 * client: the h2 protocol version is part of the client-sharing tuple key
 * ({@link UpstreamTarget}), so a gRPC route to {@code host:port} holds a
 * distinct client from an HTTP/1.1 route to the same {@code host:port}. This stage streams the
 * request/response bodies as <strong>opaque length-prefixed frames</strong> — the gateway never
 * inspects the protobuf payload — reusing the byte-capped streaming dispatch of {@link DispatchStage}
 * (which also enforces the route's body ceiling and the stream-aware retry gate). An h2-negotiation
 * failure at dispatch (the forced-h2 dial could not establish {@code h2}) surfaces as an upstream
 * connection failure, mapped to {@link EventType#UPSTREAM_ERROR} by {@link UpstreamFailureMapper} and
 * rendered as gRPC {@code UNAVAILABLE} by {@link de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper}.
 * <strong>The HTTP/2 stream-abuse bounds (threat {@code GW-08}) hold on the gRPC path</strong> because
 * they are bounds of the shared inbound transport, applied by
 * {@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions} to every listener rather than
 * per protocol: at most 100 concurrent streams per connection, a decoded header block of at most
 * 16 KiB ({@code CONTINUATION} frames included), and the per-connection Rapid-Reset guard pinned at 200
 * client-sent {@code RST_STREAM} frames per 30-second window. Ordinary multi-stream gRPC traffic stays
 * inside all three. The forced-h2 upstream leg opens at most one upstream stream per admitted inbound
 * request, so the stream and reset rates it can produce upstream inherit the inbound bounds.
 * <p>
 * <strong>Time the client holds the request open is the client's.</strong> A gRPC call ends as an upstream
 * failure only when the upstream had everything the client was going to send: the guard's timeout, which
 * the breaker records as an upstream failure, is reached only by an upstream holding a complete, half-closed
 * request — or by an attempt whose wait for an upstream connection, which the guard counts and the request
 * bounds below do not, consumed the margin between them. Every other way a call can wait on its client is
 * ended first, as the client's own abort, which the breaker skips. The forced-HTTP/2 upstream request head is
 * written only with the first body chunk, and an upstream cannot act on a request message it holds only part
 * of, so the request side of a call is bounded twice, before the upstream answers:
 * <ul>
 *   <li><strong>The message bound, {@value #GRPC_MESSAGE_TIMEOUT_MILLIS} ms per message.</strong> Counted
 *       from the moment the attempt hands its body to the upstream request, and again from every complete
 *       message forwarded — its 5-byte length prefix and every payload byte the prefix declares, read as the
 *       bytes cross without holding them — until the client ends its request stream. A pause while the
 *       upstream is not taking data is not counted. When the next complete message does not arrive within
 *       it, the gateway resets the upstream request and ends the call as a
 *       {@link DispatchStage.RequestMessageTimeout} ({@link EventType#INBOUND_BODY_ABORTED}) — whether the
 *       client sent nothing, only part of a message, or stopped between two messages.</li>
 *   <li><strong>The pre-response deadline, {@value #GRPC_PRE_RESPONSE_DEADLINE_MILLIS} ms.</strong> Counted
 *       from the start of the attempt, like the guard's timeout, and equal to the HTTP request-body deadline
 *       ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}),
 *       5 s shorter than the guard's timeout ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS} seconds). A
 *       client that keeps sending
 *       complete messages, each within the message bound, without ever ending its request stream, is ended
 *       there as a {@link DispatchStage.RequestBodyDeadlineExceeded} ({@link EventType#REQUEST_BODY_TIMEOUT}),
 *       rendered to the client as gRPC {@code DEADLINE_EXCEEDED}. A client stream the upstream answers only
 *       after the client ends it must therefore end within that deadline.</li>
 * </ul>
 * Both stop when the upstream answers — the attempt's send completes — so a bidirectional stream the
 * upstream has answered is bounded by neither. A request stream that ended is not ended by either. Both are
 * fixed and derived, never configured. No request-body idle timeout is armed: client-streaming and
 * bidirectional RPCs legitimately pause between messages, the message bound is what bounds those pauses,
 * and a forced-HTTP/2 upstream multiplexes the call on a shared connection instead of holding a pooled
 * connection of its own.
 * <p>
 * <strong>After the response headers.</strong> The guard's timeout bounds each attempt, and a gRPC call is a
 * {@code POST}, which the retry predicate never retries, so its one attempt is the whole call. Once the
 * headers have arrived the guarded call has returned, and the call's stream-idle bound takes over: the edge
 * hands this stage the request body
 * {@linkplain de.cuioss.sheriff.gateway.edge.relay.ResponseStage.GrpcStreamIdleBound#watch watched} by
 * that bound, so every request frame counts as activity, and the trailer relay ends a call that carried
 * no frame in either direction for the bound's fixed five minutes with a gRPC status.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GrpcDispatchStage {

    /**
     * How long, in milliseconds, a gRPC call may wait for each complete request message — the length prefix
     * and every payload byte it declares — before it is ended as the client's abort
     * ({@link DispatchStage.RequestMessageTimeout}): the first counted from the moment its attempt hands the
     * body to the upstream request, every further one from the complete message before it, until the client
     * ends its request stream or the upstream answers. A forwarded byte of an incomplete message does not
     * end the wait. Fixed, and shorter than {@link #GRPC_PRE_RESPONSE_DEADLINE_MILLIS}.
     */
    public static final long GRPC_MESSAGE_TIMEOUT_MILLIS = 10_000;

    /**
     * How long, in milliseconds from the start of its attempt, a gRPC call's request stream may stay open
     * before the upstream answers, before it is ended as a {@link DispatchStage.RequestBodyDeadlineExceeded}.
     * Fixed and derived, never configured: equal to the HTTP request-body deadline
     * ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}),
     * 5 s shorter than the guard's timeout ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS} seconds), which
     * starts at the same moment, so a request stream still open when the guard would fire is ended as the
     * client's first.
     */
    public static final long GRPC_PRE_RESPONSE_DEADLINE_MILLIS = 25_000;

    private final DispatchStage dispatchStage;

    /**
     * A gRPC dispatch with the production message bound, {@value #GRPC_MESSAGE_TIMEOUT_MILLIS} ms, and
     * pre-response deadline, {@value #GRPC_PRE_RESPONSE_DEADLINE_MILLIS} ms.
     *
     * @param upstreamClients the boot-built lookup the route's shared forced-h2 client and guard are
     *                        resolved from
     * @param maxBodyBytes    the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper   the mapper turning a guarded-dispatch (including h2-negotiation) failure
     *                        into the error contract
     */
    public GrpcDispatchStage(UpstreamClients upstreamClients, long maxBodyBytes,
            UpstreamFailureMapper failureMapper) {
        this(upstreamClients, maxBodyBytes, failureMapper, GRPC_MESSAGE_TIMEOUT_MILLIS,
                GRPC_PRE_RESPONSE_DEADLINE_MILLIS);
    }

    /**
     * A gRPC dispatch with the given message bound and pre-response deadline — the seam a test shortens
     * both through.
     *
     * @param upstreamClients           the boot-built lookup the route's shared forced-h2 client and guard
     *                                  are resolved from
     * @param maxBodyBytes              the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper             the mapper turning a guarded-dispatch (including h2-negotiation)
     *                                  failure into the error contract
     * @param messageTimeoutMillis      how long, in milliseconds, the call may wait for each complete
     *                                  request message; positive
     * @param preResponseDeadlineMillis how long, in milliseconds from the start of its attempt, the call's
     *                                  request stream may stay open before the upstream answers; longer
     *                                  than {@code messageTimeoutMillis}
     * @throws IllegalArgumentException when {@code messageTimeoutMillis} is not positive, or
     *                                  {@code preResponseDeadlineMillis} is not longer than it
     */
    GrpcDispatchStage(UpstreamClients upstreamClients, long maxBodyBytes, UpstreamFailureMapper failureMapper,
            long messageTimeoutMillis, long preResponseDeadlineMillis) {
        if (messageTimeoutMillis <= DispatchStage.NO_FIRST_MESSAGE_TIMEOUT) {
            throw new IllegalArgumentException("messageTimeoutMillis must be positive: " + messageTimeoutMillis);
        }
        if (preResponseDeadlineMillis <= messageTimeoutMillis) {
            throw new IllegalArgumentException(
                    "preResponseDeadlineMillis must be longer than the message bound: " + preResponseDeadlineMillis);
        }
        this.dispatchStage = new DispatchStage(upstreamClients, maxBodyBytes, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                preResponseDeadlineMillis, messageTimeoutMillis,
                Objects.requireNonNull(failureMapper, "failureMapper"));
    }

    /**
     * Dispatches the gRPC request to the route's forced-h2 upstream, streaming the (byte-capped)
     * request body opaquely and returning the response whose body and trailers are not yet consumed.
     * <p>
     * The body is always streamed: gRPC is {@code POST}-only, so a gRPC request is never bodyless
     * and no late body refusal can arrive. A request that declares a length is framed by it like any
     * other dispatched request — the declared value becomes the upstream {@code Content-Length} and
     * the forwarded body is held to exactly that many bytes (see {@link DispatchStage}).
     *
     * @param route          the selected gRPC route; its forced-h2 upstream target and resilience shape
     *                       select the shared client and guard
     * @param method         the request method (gRPC is always {@code POST})
     * @param requestUri     the upstream request URI
     * @param forwardHeaders the mode-filtered header set computed by stage 5
     * @param requestBody    the inbound request body as a live read stream
     * @param declaredLength the request's checked declared {@code Content-Length}, or a negative
     *                       value when it declares none
     * @return the upstream response (body and trailers still streaming)
     * @throws GatewayException      carrying the mapped error-contract event on any dispatch failure
     * @throws IllegalStateException when the route resolves no upstream, or the upstream-clients lookup
     *                               holds no client or guard for it — a boot-assembly defect, never a
     *                               request's fault (raised by the delegate {@link DispatchStage})
     */
    public HttpClientResponse dispatch(Route route, HttpMethod method, String requestUri,
            Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody, long declaredLength) {
        return dispatchStage.dispatch(route, method, requestUri, forwardHeaders, requestBody,
                DispatchStage.BodyFraming.streamed(declaredLength));
    }
}
