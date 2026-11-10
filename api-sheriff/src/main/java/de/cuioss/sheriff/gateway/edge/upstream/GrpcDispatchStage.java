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
 * <strong>No request-body idle timeout and no request-body deadline.</strong> The dispatch is built
 * with {@link DispatchStage#NO_BODY_IDLE_TIMEOUT} and without a request-body deadline, so a gRPC request
 * body is never ended for pausing between messages, nor for not having ended in time. Client-streaming and
 * bidirectional RPCs legitimately pause between messages, and a forced-HTTP/2 upstream multiplexes the
 * call on a shared connection instead of holding a pooled connection of its own, so there is no
 * connection for such a bound to release.
 * <p>
 * <strong>The wait for the first complete request message is bounded.</strong> The forced-HTTP/2 upstream
 * request head is written only with the first body chunk, and an upstream cannot act on a request message
 * it holds only part of, so a client that opens a call and sends no complete request message would leave
 * the dispatch waiting until the guard's timeout — and the breaker would count that timeout as an upstream
 * failure, so idle clients could open the breaker for every caller of a healthy upstream. The dispatch is
 * therefore built with a fixed first-message bound, {@value #GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS} ms, counted
 * from the moment the attempt hands its body to the upstream request. The bound ends only once a complete
 * first message — its 5-byte length prefix and every payload byte the prefix declares, read as the bytes
 * cross without holding them — has been forwarded. When none has been by then, the gateway resets the
 * upstream request and ends the call as the client's own abort, a {@link DispatchStage.FirstMessageTimeout}
 * ({@link EventType#INBOUND_BODY_ABORTED}), which the breaker skips — whether the client sent nothing or only
 * part of its first message. The bound is shorter than the guard's timeout
 * ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS} seconds), so it fires first unless waiting for an
 * upstream connection — which the guard counts and the bound does not — consumed the difference. A body that
 * ends before a complete message is not ended by it, and the bound no longer applies once the upstream has
 * answered.
 * <p>
 * <strong>The rest of the call is bounded too.</strong> The first-message bound ends with a complete first
 * request message. From then until the upstream sends its response headers, the guard's timeout alone bounds
 * the call, and a call that timeout ends — an upstream that never answers, or a client that stops sending
 * after its first complete message — is counted as an upstream failure. That timeout bounds each
 * attempt, and a gRPC call is a {@code POST}, which the retry predicate never retries, so its one attempt
 * is the whole call. Once the headers have arrived the guarded call has returned, and the call's
 * stream-idle bound takes over: the edge hands this stage the request body
 * {@linkplain de.cuioss.sheriff.gateway.edge.relay.ResponseStage.GrpcStreamIdleBound#watch watched} by
 * that bound, so every request frame counts as activity, and the trailer relay ends a call that carried
 * no frame in either direction for the bound's fixed five minutes with a gRPC status.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GrpcDispatchStage {

    /**
     * How long, in milliseconds, a gRPC call may wait for its first complete request message — the length
     * prefix and every payload byte it declares — once its attempt has handed the body to the upstream
     * request, before it is ended as the client's abort ({@link DispatchStage.FirstMessageTimeout}). A
     * forwarded byte of an incomplete message does not end the bound. Fixed, and shorter than the guard's
     * timeout ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS} seconds).
     */
    public static final long GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS = 10_000;

    private final DispatchStage dispatchStage;

    /**
     * A gRPC dispatch with the production first-message bound,
     * {@value #GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS} ms.
     *
     * @param upstreamClients the boot-built lookup the route's shared forced-h2 client and guard are
     *                        resolved from
     * @param maxBodyBytes    the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper   the mapper turning a guarded-dispatch (including h2-negotiation) failure
     *                        into the error contract
     */
    public GrpcDispatchStage(UpstreamClients upstreamClients, long maxBodyBytes,
            UpstreamFailureMapper failureMapper) {
        this(upstreamClients, maxBodyBytes, failureMapper, GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS);
    }

    /**
     * A gRPC dispatch with the given first-message bound — the seam a test shortens the bound through.
     *
     * @param upstreamClients           the boot-built lookup the route's shared forced-h2 client and guard
     *                                  are resolved from
     * @param maxBodyBytes              the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper             the mapper turning a guarded-dispatch (including h2-negotiation)
     *                                  failure into the error contract
     * @param firstMessageTimeoutMillis how long, in milliseconds, the call may wait for its first complete
     *                                  request message; positive
     * @throws IllegalArgumentException when {@code firstMessageTimeoutMillis} is not positive
     */
    GrpcDispatchStage(UpstreamClients upstreamClients, long maxBodyBytes, UpstreamFailureMapper failureMapper,
            long firstMessageTimeoutMillis) {
        if (firstMessageTimeoutMillis <= DispatchStage.NO_FIRST_MESSAGE_TIMEOUT) {
            throw new IllegalArgumentException(
                    "firstMessageTimeoutMillis must be positive: " + firstMessageTimeoutMillis);
        }
        this.dispatchStage = new DispatchStage(upstreamClients, maxBodyBytes, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                DispatchStage.NO_BODY_DEADLINE, firstMessageTimeoutMillis,
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
