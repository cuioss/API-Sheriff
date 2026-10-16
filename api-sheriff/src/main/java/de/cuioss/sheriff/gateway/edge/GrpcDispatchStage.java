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

import java.util.Map;
import java.util.Objects;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
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
 * ({@link RouteAssembler.UpstreamTarget}), so a gRPC route to {@code host:port} holds a
 * distinct client from an HTTP/1.1 route to the same {@code host:port}. This stage streams the
 * request/response bodies as <strong>opaque length-prefixed frames</strong> — the gateway never
 * inspects the protobuf payload — reusing the byte-capped streaming dispatch of {@link DispatchStage}
 * (which also enforces the route's body ceiling and the stream-aware retry gate). An h2-negotiation
 * failure at dispatch (the forced-h2 dial could not establish {@code h2}) surfaces as an upstream
 * connection failure, mapped to {@link EventType#UPSTREAM_ERROR} by {@link UpstreamFailureMapper} and
 * rendered as gRPC {@code UNAVAILABLE} by {@link GrpcStatusMapper}. The Plan-04 GW-08 HTTP/2 abuse
 * bounds (Rapid-Reset / CONTINUATION-flood) hold on the gRPC path because they are enforced by the
 * shared inbound transport ({@link EdgeHardeningOptions}) rather than per-protocol, so legitimate
 * multi-stream gRPC traffic is never misfired.
 * <p>
 * <strong>No request-body idle timeout.</strong> The dispatch is built with
 * {@link DispatchStage#NO_BODY_IDLE_TIMEOUT}, so a gRPC request body is never ended for making no
 * progress. Client-streaming and bidirectional RPCs legitimately pause between messages, and a
 * forced-HTTP/2 upstream multiplexes the call on a shared connection instead of holding a pooled
 * connection of its own, so there is no connection for such a bound to release. The guard's
 * whole-dispatch timeout still applies.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GrpcDispatchStage {

    private final DispatchStage dispatchStage;

    /**
     * @param upstreamClients the boot-built lookup the route's shared forced-h2 client and guard are
     *                        resolved from
     * @param maxBodyBytes    the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper   the mapper turning a guarded-dispatch (including h2-negotiation) failure
     *                        into the error contract
     */
    public GrpcDispatchStage(RouteAssembler.UpstreamClients upstreamClients, long maxBodyBytes,
            UpstreamFailureMapper failureMapper) {
        this.dispatchStage = new DispatchStage(upstreamClients, maxBodyBytes, DispatchStage.NO_BODY_IDLE_TIMEOUT,
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
