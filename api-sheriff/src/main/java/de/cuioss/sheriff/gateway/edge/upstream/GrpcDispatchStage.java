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
 * <strong>Time the client holds the request open is the client's; time spent reaching the upstream is
 * the upstream's.</strong> A gRPC call ends as an upstream failure only when the upstream had everything the
 * client was going to send, never gave the client the time to send it, or stopped taking what the client sent.
 * The guard's timeout, which the breaker records as an upstream failure, is reached in three ways: by an
 * upstream holding a complete, half-closed request it does not answer; by a wait for an upstream connection or
 * HTTP/2 stream slot that itself lasts the guard's whole timeout, before any body is handed over; and by an
 * upstream that stops taking data mid-request and does not take any more before the guard's timeout. While
 * that backpressure pauses the request stream the message bound is not counted, and the pre-response
 * deadline, which keeps counting, does not end the call at once: a deadline that passes during the pause ends
 * it the moment the upstream takes data again — no later than the deadline would have ended a client holding
 * the stream open — and when the upstream had by then stalled the stream without a break for at least the
 * upstream stall threshold below, as the upstream's {@link DispatchStage.UpstreamStalledRequest}, a timeout the
 * breaker counts and the failure mapper reports as {@link EventType#UPSTREAM_TIMEOUT}. An attempt whose
 * connection wait leaves less than the hand-over grace below before the pre-response cap is ended as the
 * upstream's timeout earlier still, before a byte of its body is read. Every way a call can wait on its client is
 * ended first, as the client's own abort, which the breaker skips.
 * <p>
 * <strong>Only a sustained upstream stall makes the deadline the upstream's.</strong> The pre-response deadline
 * is acted on when it passes while the request stream is being read, or, when it passes while the upstream's
 * backpressure has the stream paused, at the read that ends that pause. It is the upstream's
 * {@link DispatchStage.UpstreamStalledRequest} exactly when, at that moment, the stream has been paused without a
 * break for at least the upstream stall threshold, {@value #GRPC_UPSTREAM_STALL_MILLIS} ms — however slowly the
 * client sent before the pause. Every other passed deadline is the client's
 * {@link DispatchStage.RequestBodyDeadlineExceeded}: one that passes while the stream is being read, however long
 * the upstream had paused it earlier, and one that passes during a shorter pause. HTTP/2 flow control pauses a
 * stream whenever its sender gets ahead of its reader, so a client that sends faster than a healthy upstream
 * reads, and never ends its stream, sits paused for brief waits most of the time; were any pause at the deadline
 * charged to the upstream, that client could open the route's breaker for every caller by repeating such calls.
 * The threshold keeps those calls the client's, as the breaker skips them. Either way the call ends when it ended
 * before the deadline was ever charged to the upstream: at once when the deadline passes during a read, at the
 * upstream's next read when it passes during a pause, and at the guard's timeout when the upstream takes no data
 * again before it; only who answers for it differs. The forced-HTTP/2 upstream request head is
 * written only with the first body chunk, and an upstream cannot act on a request message it holds only part
 * of, so the request side of a call is bounded twice, before the upstream answers. Both bounds are counted
 * from the moment the attempt hands its body to the upstream request, so neither counts the wait for an
 * upstream connection:
 * <ul>
 *   <li><strong>The message bound, {@value #GRPC_MESSAGE_TIMEOUT_MILLIS} ms per message.</strong> Counted
 *       from the moment the attempt hands its body to the upstream request, and again from every complete
 *       message forwarded — its 5-byte length prefix and every payload byte the prefix declares, read as the
 *       bytes cross without holding them — until the client ends its request stream. A pause while the
 *       upstream is not taking data is not counted. When the next complete message does not arrive within
 *       it, the gateway resets the upstream request and ends the call as a
 *       {@link DispatchStage.RequestMessageTimeout} ({@link EventType#INBOUND_BODY_ABORTED}) — whether the
 *       client sent nothing, only part of a message, or stopped between two messages.</li>
 *   <li><strong>The pre-response deadline, {@value #GRPC_PRE_RESPONSE_DEADLINE_MILLIS} ms.</strong> Equal to
 *       the HTTP request-body deadline
 *       ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}),
 *       and counted, like the message bound, from the moment the attempt hands its body to the upstream
 *       request. A client that keeps sending complete messages, each within the message bound, without ever
 *       ending its request stream, is ended there as a {@link DispatchStage.RequestBodyDeadlineExceeded}
 *       ({@link EventType#REQUEST_BODY_TIMEOUT}), rendered to the client as gRPC {@code DEADLINE_EXCEEDED}.
 *       A client stream the upstream answers only after the client ends it must therefore end within that
 *       deadline. A deadline that passes while the upstream's backpressure has the stream paused is the
 *       upstream's instead when, at the read that ends the pause, the pause has lasted at least the upstream
 *       stall threshold (see above), rendered as {@code DEADLINE_EXCEEDED} too.</li>
 *   <li><strong>The upstream stall threshold, {@value #GRPC_UPSTREAM_STALL_MILLIS} ms.</strong> Equal to the
 *       message bound: the span the gateway already treats as no progress on the client's side is the span it
 *       treats as no progress on the upstream's. It is not a bound and ends nothing; it only decides whose failure
 *       a passed pre-response deadline is.</li>
 * </ul>
 * <strong>The pre-response cap, {@value #GRPC_PRE_RESPONSE_CAP_MILLIS} ms from the start of the
 * attempt.</strong> The guard's timeout ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS} seconds) is counted
 * from the start of the attempt, so it counts the connection wait as well; the cap is one second shorter, and
 * the pre-response deadline never passes later than it. <strong>The hand-over grace,
 * {@value #GRPC_HAND_OVER_GRACE_MILLIS} ms,</strong> is the least time the deadline ever leaves after the
 * hand-over. The rule, in full:
 * <ul>
 *   <li>An attempt that hands its body over no later than the cap less the deadline — four seconds — after
 *       its start has the whole pre-response deadline.</li>
 *   <li>An attempt that hands it over later than that, but no later than the cap less the grace — 28
 *       seconds — after its start, has its deadline at the cap, so the deadline never runs past it: a client
 *       still holding its stream open is ended there as its own abort, a second before the guard could end
 *       the call as the upstream's, so no client stall in that window is ever charged to the upstream. The
 *       deadline ends only a request stream whose end has not reached the dispatch when it passes, and it
 *       passes at least the grace after the hand-over: a client that had ended its stream by the hand-over is
 *       ended by it only if the gateway takes longer than the grace to deliver the end it already holds. A
 *       call whose end reached the dispatch and which the upstream then does not answer is ended by the
 *       guard's timeout as {@link EventType#UPSTREAM_TIMEOUT}, counted by the breaker.</li>
 *   <li>An attempt whose connection wait leaves less than the grace before the cap, or reaches the cap, is
 *       refused when it obtains the connection, before its request is sent or a byte of its body is read, as
 *       {@link DispatchStage.UpstreamConnectionTooLate}: the client was given too little time to send, or
 *       for an end it already sent to be delivered, so the call is the upstream's
 *       {@link EventType#UPSTREAM_TIMEOUT}, counted by the breaker — before the guard would have ended it the
 *       same way. A connection or stream-slot wait that lasts the guard's whole timeout is ended by the guard
 *       itself, also as the upstream's {@link EventType#UPSTREAM_TIMEOUT}.</li>
 * </ul>
 * Both stop when the upstream answers — the attempt's send completes — so a bidirectional stream the
 * upstream has answered is bounded by neither. A request stream whose end reached the dispatch is not ended by
 * either. The bound, the deadline, the cap, the grace and the stall threshold are all fixed and derived, never
 * configured. No
 * request-body idle timeout is armed: client-streaming and bidirectional RPCs legitimately pause between
 * messages, the message bound is what bounds those pauses, and a forced-HTTP/2 upstream multiplexes the call
 * on a shared connection instead of holding a pooled connection of its own.
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
     * How long, in milliseconds from the moment its attempt hands the body to the upstream request, a gRPC
     * call's request stream may stay open before the upstream answers; the deadline then passes, never later
     * than {@link #GRPC_PRE_RESPONSE_CAP_MILLIS} after the start of the attempt. A stream being read when it
     * passes is ended at once as the client's {@link DispatchStage.RequestBodyDeadlineExceeded}. A stream the
     * upstream's backpressure has paused then is ended at the upstream's next read: as the upstream's
     * {@link DispatchStage.UpstreamStalledRequest} when the pause has by then lasted, without a break, at least
     * {@link #GRPC_UPSTREAM_STALL_MILLIS}, as the client's {@link DispatchStage.RequestBodyDeadlineExceeded}
     * otherwise; when the upstream reads no more before the guard's timeout, that timeout ends it. Fixed and
     * derived, never configured: equal to the HTTP request-body deadline
     * ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}).
     */
    public static final long GRPC_PRE_RESPONSE_DEADLINE_MILLIS = 25_000;

    /**
     * The latest moment, in milliseconds from the start of its attempt, a gRPC call's pre-response deadline
     * passes: one second shorter than the guard's timeout ({@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS}
     * seconds), which starts at the same moment, so the deadline always passes before the guard would fire. A
     * request stream being read when it passes is ended there, as the client's; one the upstream's backpressure
     * has paused is ended at the upstream's next read, as the client's unless the pause has lasted at least
     * {@link #GRPC_UPSTREAM_STALL_MILLIS} by then; and one the upstream holds paused from before the deadline
     * until the guard fires is ended by the guard's timeout, as the upstream's. Fixed and derived, never
     * configured.
     */
    public static final long GRPC_PRE_RESPONSE_CAP_MILLIS = UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS * 1_000L - 1_000L;

    /**
     * The least time, in milliseconds, a gRPC call's pre-response deadline leaves after its attempt hands the
     * body over. An attempt whose connection wait leaves less than this before {@link #GRPC_PRE_RESPONSE_CAP_MILLIS}
     * — a hand-over later than {@code GRPC_PRE_RESPONSE_CAP_MILLIS - GRPC_HAND_OVER_GRACE_MILLIS} after the
     * start of the attempt — is ended as the upstream's timeout ({@link DispatchStage.UpstreamConnectionTooLate})
     * without its request being sent or its body read, instead of being given a deadline too short for the
     * client to send in, or for an end the client already sent to cross from the inbound connection to the
     * dispatch. Fixed, never configured; positive and shorter than {@link #GRPC_PRE_RESPONSE_DEADLINE_MILLIS}.
     */
    public static final long GRPC_HAND_OVER_GRACE_MILLIS = 1_000;

    /**
     * How long, in milliseconds, the upstream must have held a gRPC call's request stream without a break — its
     * HTTP/2 backpressure pausing the stream — when a passed pre-response deadline is acted on, for the deadline to
     * be charged to the upstream as an {@link DispatchStage.UpstreamStalledRequest} ({@link EventType#UPSTREAM_TIMEOUT},
     * counted by the breaker) instead of to the client as a {@link DispatchStage.RequestBodyDeadlineExceeded}
     * ({@link EventType#REQUEST_BODY_TIMEOUT}, skipped by the breaker).
     * <p>
     * Equal to {@link #GRPC_MESSAGE_TIMEOUT_MILLIS}: ten seconds without a complete message is what the gateway
     * already treats as a client making no progress, so ten seconds without taking any data is what it treats as
     * an upstream making none. A healthy upstream's flow control returns window within a few round-trips — a
     * client that sends faster than it reads is paused for milliseconds at a time, never for seconds — so the
     * threshold sits far above any flow-control pause, and a client cannot get its own deadline counted against
     * the upstream by out-sending it. It sits well below {@link #GRPC_PRE_RESPONSE_DEADLINE_MILLIS}, so a stall
     * that starts before the deadline can still reach it within the call. Fixed, never configured; positive and
     * shorter than {@link #GRPC_PRE_RESPONSE_DEADLINE_MILLIS}.
     */
    public static final long GRPC_UPSTREAM_STALL_MILLIS = GRPC_MESSAGE_TIMEOUT_MILLIS;

    private final DispatchStage dispatchStage;

    /**
     * A gRPC dispatch with the production message bound, {@value #GRPC_MESSAGE_TIMEOUT_MILLIS} ms,
     * pre-response deadline, {@value #GRPC_PRE_RESPONSE_DEADLINE_MILLIS} ms, pre-response cap,
     * {@value #GRPC_PRE_RESPONSE_CAP_MILLIS} ms, hand-over grace, {@value #GRPC_HAND_OVER_GRACE_MILLIS} ms, and
     * upstream stall threshold, {@value #GRPC_UPSTREAM_STALL_MILLIS} ms.
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
                GRPC_PRE_RESPONSE_DEADLINE_MILLIS, GRPC_PRE_RESPONSE_CAP_MILLIS, GRPC_HAND_OVER_GRACE_MILLIS,
                GRPC_UPSTREAM_STALL_MILLIS);
    }

    /**
     * A gRPC dispatch with the given message bound, pre-response deadline, pre-response cap, hand-over grace and
     * upstream stall threshold — the seam a test shortens all five through.
     *
     * @param upstreamClients           the boot-built lookup the route's shared forced-h2 client and guard
     *                                  are resolved from
     * @param maxBodyBytes              the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper             the mapper turning a guarded-dispatch (including h2-negotiation)
     *                                  failure into the error contract
     * @param messageTimeoutMillis      how long, in milliseconds, the call may wait for each complete
     *                                  request message; positive
     * @param preResponseDeadlineMillis how long, in milliseconds from the moment its attempt hands the body
     *                                  over, the call's request stream may stay open before the upstream
     *                                  answers; longer than {@code messageTimeoutMillis}
     * @param preResponseCapMillis      the latest the pre-response deadline passes, in milliseconds from the
     *                                  start of the attempt; no shorter than {@code preResponseDeadlineMillis},
     *                                  and shorter than the timeout of the route's guard
     * @param handOverGraceMillis       the least time, in milliseconds, the pre-response deadline leaves after
     *                                  the hand-over: an attempt handing its body over with less than this
     *                                  left before the cap is ended as the upstream's timeout, unsent;
     *                                  positive and shorter than {@code preResponseDeadlineMillis}
     * @param upstreamStallMillis       how long, in milliseconds, the upstream must have held the request stream
     *                                  without a break when a passed pre-response deadline is acted on, for the
     *                                  deadline to be the upstream's timeout rather than the client's; positive
     *                                  and shorter than {@code preResponseDeadlineMillis}
     * @throws IllegalArgumentException when {@code messageTimeoutMillis} is not positive,
     *                                  {@code preResponseDeadlineMillis} is not longer than it,
     *                                  {@code preResponseCapMillis} is shorter than the deadline, or
     *                                  {@code handOverGraceMillis} or {@code upstreamStallMillis} is not positive
     *                                  or not shorter than the deadline
     */
    @SuppressWarnings("java:S107") // package-private test seam; each bound is a distinct fixed value the public constructor derives
    GrpcDispatchStage(UpstreamClients upstreamClients, long maxBodyBytes, UpstreamFailureMapper failureMapper,
            long messageTimeoutMillis, long preResponseDeadlineMillis, long preResponseCapMillis,
            long handOverGraceMillis, long upstreamStallMillis) {
        if (messageTimeoutMillis <= DispatchStage.NO_FIRST_MESSAGE_TIMEOUT) {
            throw new IllegalArgumentException("messageTimeoutMillis must be positive: " + messageTimeoutMillis);
        }
        if (preResponseDeadlineMillis <= messageTimeoutMillis) {
            throw new IllegalArgumentException(
                    "preResponseDeadlineMillis must be longer than the message bound: " + preResponseDeadlineMillis);
        }
        if (preResponseCapMillis < preResponseDeadlineMillis) {
            throw new IllegalArgumentException(
                    "preResponseCapMillis must not be shorter than the pre-response deadline: " + preResponseCapMillis);
        }
        if (handOverGraceMillis <= DispatchStage.NO_HAND_OVER_GRACE || handOverGraceMillis >= preResponseDeadlineMillis) {
            throw new IllegalArgumentException(
                    "handOverGraceMillis must be positive and shorter than the pre-response deadline: "
                            + handOverGraceMillis);
        }
        if (upstreamStallMillis <= DispatchStage.NO_UPSTREAM_STALL || upstreamStallMillis >= preResponseDeadlineMillis) {
            throw new IllegalArgumentException(
                    "upstreamStallMillis must be positive and shorter than the pre-response deadline: "
                            + upstreamStallMillis);
        }
        this.dispatchStage = new DispatchStage(upstreamClients, maxBodyBytes, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                preResponseDeadlineMillis, messageTimeoutMillis, preResponseCapMillis, handOverGraceMillis,
                upstreamStallMillis, Objects.requireNonNull(failureMapper, "failureMapper"));
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
