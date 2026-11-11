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

import java.io.Serial;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;


import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.http.PathSlashes;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.tools.logging.CuiLogger;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;

/**
 * Stage 6 — upstream dispatch over the route's shared Vert.x {@code HttpClient}, invoked through
 * that route's SmallRye Fault-Tolerance guard. Neither is a component of the route: both are
 * resolved from the boot-built {@link UpstreamClients} lookup, by the route's
 * {@linkplain UpstreamTarget upstream target} and
 * {@linkplain ResilienceShape resilience shape} respectively.
 * <p>
 * The request body is <strong>streamed, never buffered</strong>: it flows through a
 * {@link ByteCappedBodyStream} that forwards each chunk to the upstream request as it arrives and
 * enforces the {@code max_body_bytes} ceiling with a running counter. A mid-stream breach ABORTS
 * the in-flight upstream call (Vert.x {@link HttpClientRequest#reset()}) and surfaces
 * {@link EventType#CONTENT_TOO_LARGE} (413). An inbound stream that fails after dispatch has begun
 * aborts the upstream call the same way and surfaces {@link EventType#INBOUND_BODY_ABORTED} (400), so
 * a request body that did not arrive whole is never completed towards the upstream and its HTTP/1.x
 * upstream connection is never pooled again: it is closed abortively, so it frees the gateway's local
 * port at once instead of holding it in {@code TIME_WAIT} (see {@code EgressClients#clientFor}).
 * The upstream body is <strong>never materialized</strong> into an {@code HttpResult<byte[]>}
 * (ADR-0006/0008): the returned
 * {@link HttpClientResponse} is a live {@link ReadStream} whose body
 * {@link de.cuioss.sheriff.gateway.edge.relay.ResponseStage} streams
 * back with backpressure.
 * <p>
 * <strong>A bodyless method never carries a body upstream, on any protocol.</strong> A {@code GET} or
 * {@code HEAD} without a positive declared {@code Content-Length} is sent to the upstream with no
 * body at all: the inbound stream is never handed to the upstream request. It is watched instead by a
 * {@link BodylessMethodWatch}, and its first body byte, whatever its size, refuses the request as a
 * {@link EventType#SECURITY_FILTER_VIOLATION} and resets the upstream request. The framing gate
 * decides on headers; this rule is where a body that no header declares — HTTP/2 DATA frames without
 * {@code content-length} — is refused. While the dispatch is still awaited the refusal is the
 * dispatch's own failure, answered {@code 400}; once the response has been handed over for relay it
 * reaches the edge through the {@linkplain BodyFraming#lateViolation() late-violation callback}.
 * <p>
 * <strong>A declared length frames the forwarded body.</strong> Every other request that declares a
 * checked {@code Content-Length} — whatever its method — is forwarded with that length as the
 * upstream {@code Content-Length}, so the transport frames it by length and never re-frames it as
 * chunked. The client's own header is never relayed; the value written is the one the framing gate
 * and the {@code max_body_bytes} pre-check admitted. The forwarded body is held to exactly that many
 * bytes by the {@link ByteCappedBodyStream}: a byte beyond it is never forwarded and refuses the
 * request as a {@link EventType#SECURITY_FILTER_VIOLATION}, and a body that ends short of it resets
 * the upstream request as {@link EventType#INBOUND_BODY_ABORTED}, so the upstream never waits on or
 * completes a message of another length. A request that declares no length keeps the framing the
 * transport chooses.
 * <p>
 * <strong>A body that stops arriving ends the dispatch's hold on its upstream connection.</strong> A
 * dispatch constructed with a request-body idle timeout watches the body it forwards: once the attempt
 * holds its upstream connection and is reading the body, a period of that length without a forwarded
 * body byte ends the dispatch as {@link EventType#REQUEST_BODY_TIMEOUT} ({@code 408}) and resets the
 * upstream request, so the dispatch stops holding its pooled upstream connection at once instead of
 * keeping it until the guard's timeout. What becomes of that connection depends on the protocol. On
 * HTTP/1.1 the request head is written together with the first forwarded body chunk, so the connection
 * itself goes back to the pool only when no body chunk had been forwarded; a partly sent request closes
 * its connection (abortively, per the abortive upstream close of ADR-0066), and the next dispatch
 * obtains a new one. On HTTP/2 the reset ends one stream and the connection carries on. What the clock
 * does and does not measure is described on {@link ByteCappedBodyStream}. A dispatch constructed with
 * {@link #NO_BODY_IDLE_TIMEOUT} never arms the clock.
 * <p>
 * <strong>A body that does not end in time is ended at the request-body deadline.</strong> A dispatch
 * constructed with a request-body deadline and a request-body idle timeout also bounds how long it waits
 * for the whole body. This whole-body deadline is counted from the start of the attempt — the moment the guard's timeout, which bounds each
 * attempt rather than the whole dispatch, starts too — and is shorter than that timeout. When it passes
 * while the gateway is still waiting for body bytes, the attempt's upstream request is reset and the body
 * is refused as a {@link RequestBodyDeadlineExceeded} ({@link EventType#REQUEST_BODY_TIMEOUT},
 * {@code 408}). Before the response was handed over this is the dispatch's own failure, so a body the
 * gateway is still reading when the guard would end the attempt is ended as the request's instead; a
 * body the upstream has stopped reading, or an attempt still waiting for its pooled connection, can
 * reach the guard's timeout first and then ends as an upstream timeout. After the hand-over —
 * an upstream that answered at once and keeps reading — the refusal goes to the
 * {@linkplain BodyFraming#lateViolation() late-violation callback}, and the reset upstream request fails
 * the relay, which ends the client response with the status the upstream sent. Once it has passed, the
 * deadline stays in force while the body
 * is not being read: a body the upstream is not taking data for when it passes is ended the moment the
 * upstream reads it again, and an attempt that obtained its pooled connection only after the deadline —
 * the wait for the connection counts against it — is ended as soon as its body starts to be read; either
 * way on the same path, before or after the hand-over, as a body still arriving when it passes. An attempt
 * the dispatch no longer waits for stops the deadline and every other clock of its body (see
 * {@link UpstreamAttempt}), so none of them reports anything afterwards. A dispatch constructed without a
 * deadline ({@link #NO_BODY_DEADLINE}) never arms it.
 * <p>
 * <strong>A body whose next complete message does not arrive in time is ended at the message
 * bound.</strong> A dispatch constructed with a message bound — the gRPC dispatch, whose upstream request
 * head is written only with the first body chunk — bounds how long an attempt waits for each complete gRPC
 * message of its request: the 5-byte length prefix and every payload byte it declares, read as the chunks
 * cross, however they split the messages, without holding any of them. The clock starts when the attempt
 * hands its body to the upstream request, so the wait for a pooled connection is never counted, and every
 * complete message forwarded starts the wait for the next one again; a pause while the upstream is not
 * taking data is not counted either. The bound is shorter than the guard's timeout. When it passes before the next complete message was forwarded, the body ended or the attempt's
 * send completed, the attempt's upstream request is reset and the dispatch is ended as a
 * {@link RequestMessageTimeout} ({@link EventType#INBOUND_BODY_ABORTED}): a client that opens a call and
 * sends nothing, only part of a message, or stops between two messages without ending its request stream,
 * ends the call as its own abort, never as the guard's timeout. Once the send completed — the upstream
 * answered — the bound no longer applies.
 * <p>
 * <strong>A request stream still open before the guard would fire is ended at the pre-response
 * deadline — as the client's, unless the upstream had stalled it for at least the stall threshold.</strong> A
 * dispatch with a message bound and a request-body deadline arms that deadline as a pre-response deadline,
 * held to a pre-response cap. Unlike the whole-body deadline it is counted from the moment the attempt hands
 * its body to the upstream request, where the message bound starts too, so the wait for an upstream
 * connection, which the guard's timeout counts, is never charged to the client. It passes at the earlier of
 * two moments: the deadline after that hand-over, and the cap after the start of the attempt — the cap is
 * shorter than the guard's timeout, so the deadline always passes before the guard could fire. It ends a
 * request stream that is still open when it passes — a client sending complete messages steadily, each
 * within the message bound, without ever ending its stream — as a {@link RequestBodyDeadlineExceeded}
 * ({@link EventType#REQUEST_BODY_TIMEOUT}) and resets the upstream request. A deadline that passes while the
 * upstream is not taking data, so its backpressure has paused the stream, ends the stream the moment the
 * upstream takes data again — no later than a client's deadline end would — and only a sustained stall makes
 * that the upstream's failure: when the pause has lasted, without a break, at least the upstream stall threshold
 * at that read, the stream is ended as an {@link UpstreamStalledRequest}, a timeout the breaker counts and the
 * failure mapper reports as {@link EventType#UPSTREAM_TIMEOUT}. A shorter pause — ordinary flow control, as when
 * a client sends faster than a healthy upstream reads — leaves the deadline the client's, and so does a deadline
 * that passes while the stream is being read, however long the upstream paused it before. It stops when the
 * attempt's send
 * completes, so it never runs after the hand-over and a stream the upstream has answered is never ended by
 * it. An attempt that obtains its upstream connection with less than a hand-over grace left before the cap,
 * or only once the cap has passed — the connection wait used up the guard's timeout but for the margin the
 * cap leaves and less than the grace of it — gives its client too little time to send, and too little for an
 * end the client already sent to cross from the inbound connection before the deadline passes: it is refused
 * at once, before its request is sent or a byte of its body is read, as an {@link UpstreamConnectionTooLate},
 * a timeout the breaker counts and the failure mapper reports as {@link EventType#UPSTREAM_TIMEOUT}. Every
 * attempt that is dispatched therefore has at least the grace between its hand-over and its deadline. The
 * deadline ends only a request stream whose end has not reached the dispatch when it passes: a client that
 * had ended its stream by the hand-over is ended by it only if the gateway takes longer than the grace to
 * deliver the end it already holds. A call whose end did reach the dispatch, and which the upstream then does
 * not answer, is ended by the guard's timeout as the upstream's. A dispatch
 * constructed with {@link #NO_FIRST_MESSAGE_TIMEOUT} — every HTTP dispatch — arms neither the message bound
 * nor the pre-response deadline.
 * <p>
 * The dispatch is awaited synchronously on the caller's virtual thread inside the guard so the
 * breaker observes each call's success / failure / timeout. Guard failures are mapped to the error
 * contract by {@link UpstreamFailureMapper}; a body-cap breach propagates its own
 * {@link GatewayException} unchanged.
 * <p>
 * <strong>What the breaker sees of a client-ended dispatch.</strong> A dispatch the client itself
 * ended — the body-cap breach, an inbound body stream that failed after dispatch began, a body byte
 * on a bodyless method, a body that disagreed with its declared length, a body that made no progress
 * for the request-body idle timeout, a body still arriving at the request-body deadline, a body whose
 * next complete message did not arrive within the message bound, or a gRPC request stream its client still
 * held open at its pre-response deadline — reaches the guard as the {@link GatewayException} the body stream recorded for
 * it ({@link EventType#CONTENT_TOO_LARGE}, {@link EventType#INBOUND_BODY_ABORTED} for the failed or short
 * inbound body and for the body whose next complete message did not arrive,
 * {@link EventType#SECURITY_FILTER_VIOLATION}, or {@link EventType#REQUEST_BODY_TIMEOUT} for the idle body
 * and the body past its deadline), never as the transport error the aborted upstream
 * request produced. The breaker skips a {@link GatewayException}, so such a dispatch is not counted
 * as an upstream failure, and the retry predicate refuses to retry it. It is not invisible to the
 * breaker, though: SmallRye Fault Tolerance has no neutral outcome, so a skipped exception is
 * recorded as a success, in the closed and in the half-open state alike. Keeping client-ended
 * dispatches out of the breaker's window altogether would need the guarded call to be restructured,
 * which the gateway does not do.
 * <p>
 * The client abort is honoured only when it preceded the transport failure: it is read once, at
 * the moment the attempt's send fails, so a client that goes away only after the upstream already
 * failed leaves that failure attributed to the upstream. A failure with no client-side abort behind
 * it — a refused connection, an upstream reset, a timeout — is counted and mapped as an upstream
 * failure. One timing edge is attributed by what happened first: the edge's idle timeout can reap an
 * inbound connection before the upstream guard's fixed per-attempt timeout fires, and the dispatch then
 * ends as the client-ended dispatch it is.
 * <p>
 * <strong>An attempt the dispatch stops waiting for is released.</strong> Every attempt's upstream
 * exchange is tracked from before its request is obtained, whatever the body framing. Once the
 * dispatch no longer waits for an attempt — the attempt ends in any way other than handing its
 * response back, the next attempt begins, or the dispatch itself fails — that attempt's upstream
 * request is reset: one still waiting for a pooled connection is reset as soon as it obtains one and
 * is never sent late, one in flight is reset, and a response it already received and paused is
 * released with it. Only a response actually handed to the caller is exempt. A bodyless request already
 * sent is reset on the context it was sent from, after the send, so the reset never overtakes its head
 * write and never returns the upstream connection to the pool with the request still to be written (see
 * {@link UpstreamAttempt}).
 * <p>
 * <strong>A client that leaves releases the attempt it was waiting on.</strong> When the client resets
 * its stream or closes its connection before the response is handed over for relay, the edge reports
 * the departure through the {@link InboundBody} the request body arrives in, whatever the body framing
 * and whether or not the body has ended. The attempt is then released exactly as above: an upstream
 * request in flight is reset at once instead of being held until the guard's timeout, and one still
 * waiting for a pooled connection is never sent once the pool hands it one. The dispatch reports an
 * {@link EventType#INBOUND_BODY_ABORTED} as the client's abort, so the breaker skips it: a body that was
 * still arriving as one that did not arrive whole, any other request as a {@link ClientDeparted}. The
 * departure does not wake a dispatching thread that is still waiting for a pooled connection: that wait
 * ends when the pool hands the connection over, which reports the client's abort, or when the guard's
 * timeout fires, which is an ordinary upstream timeout. A client that left before the dispatch began has
 * no upstream request asked for at all. From the hand-over on, the relay owns the exchange and the
 * departure is left to it.
 * <p>
 * <strong>The gateway's own upstream reset is not logged as an error.</strong> Every attempt's
 * upstream request carries an exception handler from the moment it is obtained. Without one, Vert.x
 * reports the {@code StreamResetException} each {@link HttpClientRequest#reset()} delivers as an
 * unhandled exception at {@code ERROR} — once per early answer, cap breach, failed inbound body,
 * abandoned attempt or relay that failed to start. On an HTTP/1.x request that exception can only be
 * the gateway's own reset, so the handler drops it; any other failure it sees also reaches the send
 * future or the response stream and is mapped there, so it is logged at {@code DEBUG} only.
 * <p>
 * <strong>Stream-aware retry gating.</strong> When the route enables SmallRye retry, a failed attempt
 * may be retried. A streamed request cannot be safely replayed once any body byte has crossed to the
 * upstream, a non-idempotent verb must never be re-sent, and the one-shot inbound body stream cannot be
 * re-attached once an attempt subscribed it — so whether a failed attempt is retried is decided by
 * {@link #allowsRetryAfter(Throwable)}, the guard's retry predicate, which vets the request method and
 * the running body-bytes-sent count through a {@link StreamAwareRetryGate} and refuses once the body
 * stream was subscribed. The decision is taken when an attempt fails, before any re-entry: a refused
 * retry is never re-entered, so it is not recorded by the circuit breaker, and no duplicate upstream
 * request is ever issued. A {@link GatewayException} is never retried. A bodyless request never
 * subscribes the inbound stream to an attempt, so it stays retryable for as long as no body byte has
 * arrived.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DispatchStage {

    private static final CuiLogger LOGGER = new CuiLogger(DispatchStage.class);

    /** Bounds the cause-chain walk so a self-referential cause cycle cannot spin forever. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The upstream request header the checked declared length is written to. */
    private static final String CONTENT_LENGTH = "Content-Length";

    /** The parameter name the null checks report for a missing request method. */
    private static final String METHOD_PARAMETER = "method";

    /** Fixed disposition of an attempt released before its request was sent. */
    private static final String ATTEMPT_ABANDONED =
            "The dispatch no longer waits for this attempt; its request was not sent";

    /** Fixed disposition of a dispatch the client left before it was answered. */
    private static final String CLIENT_DEPARTED =
            "The client left before the request was answered; the upstream request was aborted";

    /** The methods that carry no body upstream unless they declare a positive length. */
    private static final Set<HttpMethod> BODYLESS_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);

    /** The declared length of a request that declares none. */
    static final long NO_DECLARED_LENGTH = -1L;

    /**
     * The request-body idle timeout of a dispatch whose body is not watched for progress: the idle
     * clock is never armed.
     */
    public static final long NO_BODY_IDLE_TIMEOUT = 0L;

    /** The request-body deadline of a dispatch whose whole body is not bounded in time: none is armed. */
    public static final long NO_BODY_DEADLINE = 0L;

    /**
     * The message bound of a dispatch that does not bound the wait for its complete request messages: the
     * message clock, and with it the pre-response deadline, is never armed. Every HTTP dispatch is
     * constructed with it; only the gRPC dispatch ({@link GrpcDispatchStage}) arms the clock. It is derived
     * from {@link #NO_BODY_IDLE_TIMEOUT} because both clocks are {@link IdleClock}s, whose armed test
     * compares against that one value.
     */
    static final long NO_FIRST_MESSAGE_TIMEOUT = NO_BODY_IDLE_TIMEOUT;

    /**
     * The pre-response cap of a dispatch that arms no pre-response deadline — every dispatch but the gRPC
     * one ({@link GrpcDispatchStage}), whose request-body deadline, if any, is the whole-body deadline.
     */
    static final long NO_PRE_RESPONSE_CAP = 0L;

    /**
     * The hand-over grace of a dispatch that arms no pre-response deadline — every dispatch but the gRPC one
     * ({@link GrpcDispatchStage}): no attempt is refused for handing its body over too close to a cap.
     */
    static final long NO_HAND_OVER_GRACE = 0L;

    /**
     * The upstream stall threshold of a dispatch that arms no pre-response deadline — every dispatch but the gRPC
     * one ({@link GrpcDispatchStage}): no deadline is ever charged to the upstream, so no stall is measured.
     */
    static final long NO_UPSTREAM_STALL = 0L;

    /**
     * Fixed disposition of an attempt whose upstream connection came too close to, or past, its pre-response
     * cap.
     */
    private static final String CONNECTION_TOO_LATE =
            "The upstream connection was obtained too close to the pre-response cap; the request was not sent";

    /**
     * Fixed disposition of an attempt whose pre-response deadline passed while the upstream had not taken its
     * request stream for at least the upstream stall threshold.
     */
    private static final String STALLED_BY_UPSTREAM =
            "The upstream had stopped taking the request stream for at least the stall threshold when its pre-response deadline passed; the upstream request was aborted";

    /**
     * The retry state of the dispatch the guard is running, bound on the dispatching thread for the
     * duration of its guard call. The guard is shared by every route of one resilience shape, so this
     * is how its retry predicate learns which dispatch failed.
     */
    private static final ScopedValue<RetryState> RETRY_STATE = ScopedValue.newInstance();

    private final UpstreamClients upstreamClients;
    private final long maxBodyBytes;
    private final long bodyIdleTimeoutMillis;
    private final long bodyDeadlineMillis;
    private final long messageTimeoutMillis;
    private final long preResponseCapMillis;
    private final long handOverGraceMillis;
    private final long upstreamStallMillis;
    private final UpstreamFailureMapper failureMapper;

    /**
     * A dispatch whose whole body is not bounded in time ({@link #NO_BODY_DEADLINE}).
     *
     * @param upstreamClients       the boot-built lookup the route's shared client and guard are
     *                              resolved from
     * @param maxBodyBytes          the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param bodyIdleTimeoutMillis how long, in milliseconds, a body-carrying attempt holding its
     *                              upstream connection may go without request-body progress before it
     *                              is ended as {@link EventType#REQUEST_BODY_TIMEOUT}, or
     *                              {@link #NO_BODY_IDLE_TIMEOUT} when the body is not watched
     * @param failureMapper         the mapper turning guarded-dispatch failures into the error contract
     * @throws IllegalArgumentException when {@code bodyIdleTimeoutMillis} is negative
     */
    public DispatchStage(UpstreamClients upstreamClients, long maxBodyBytes,
            long bodyIdleTimeoutMillis, UpstreamFailureMapper failureMapper) {
        this(upstreamClients, maxBodyBytes, bodyIdleTimeoutMillis, NO_BODY_DEADLINE, failureMapper);
    }

    /**
     * A dispatch that does not bound the wait for its complete request messages
     * ({@link #NO_FIRST_MESSAGE_TIMEOUT}) — every HTTP dispatch.
     *
     * @param upstreamClients       the boot-built lookup the route's shared client and guard are
     *                              resolved from
     * @param maxBodyBytes          the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param bodyIdleTimeoutMillis how long, in milliseconds, a body-carrying attempt holding its
     *                              upstream connection may go without request-body progress before it
     *                              is ended as {@link EventType#REQUEST_BODY_TIMEOUT}, or
     *                              {@link #NO_BODY_IDLE_TIMEOUT} when the body is not watched
     * @param bodyDeadlineMillis    how long, in milliseconds from the start of its attempt, a
     *                              body-carrying dispatch may wait for the whole body before it is ended
     *                              as a {@link RequestBodyDeadlineExceeded}, or {@link #NO_BODY_DEADLINE}
     *                              when the whole body is not bounded in time; a deadline needs an armed
     *                              idle timeout, whose timer seam it shares
     * @param failureMapper         the mapper turning guarded-dispatch failures into the error contract
     * @throws IllegalArgumentException when {@code bodyIdleTimeoutMillis} or {@code bodyDeadlineMillis}
     *                                  is negative, or a deadline is given without an idle timeout
     */
    public DispatchStage(UpstreamClients upstreamClients, long maxBodyBytes,
            long bodyIdleTimeoutMillis, long bodyDeadlineMillis, UpstreamFailureMapper failureMapper) {
        this(upstreamClients, maxBodyBytes, bodyIdleTimeoutMillis, bodyDeadlineMillis, NO_FIRST_MESSAGE_TIMEOUT,
                NO_PRE_RESPONSE_CAP, NO_HAND_OVER_GRACE, NO_UPSTREAM_STALL, failureMapper);
    }

    /**
     * @param upstreamClients       the boot-built lookup the route's shared client and guard are resolved
     *                              from
     * @param maxBodyBytes          the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param bodyIdleTimeoutMillis how long, in milliseconds, a body-carrying attempt holding its upstream
     *                              connection may go without request-body progress before it is ended as
     *                              {@link EventType#REQUEST_BODY_TIMEOUT}, or {@link #NO_BODY_IDLE_TIMEOUT}
     *                              when the body is not watched
     * @param bodyDeadlineMillis    how long, in milliseconds, a body-carrying dispatch may wait before it is
     *                              ended as a {@link RequestBodyDeadlineExceeded}, or
     *                              {@link #NO_BODY_DEADLINE} when it is not bounded in time. With an armed
     *                              idle timeout it is the whole-body deadline, counted from the start of the
     *                              attempt, which keeps running after the response head until the body ends;
     *                              with an armed message bound and no idle timeout it is the pre-response
     *                              deadline, counted from the moment the attempt hands its body to the
     *                              upstream request, held to {@code preResponseCapMillis}, and stopped when
     *                              the attempt's send completes. It shares the timer seam of the clock it is
     *                              armed with, so it needs one of the two
     * @param messageTimeoutMillis  how long, in milliseconds, a body-carrying attempt may wait for each
     *                              complete gRPC request message — the first counted from the moment the
     *                              attempt hands its body to the upstream request, every further one from
     *                              the message before it — before it is ended as a
     *                              {@link RequestMessageTimeout}, or {@link #NO_FIRST_MESSAGE_TIMEOUT} when
     *                              that wait is not bounded
     * @param preResponseCapMillis  the latest the pre-response deadline passes, in milliseconds from the
     *                              start of the attempt; shorter than the guard's timeout and no shorter than
     *                              {@code bodyDeadlineMillis}. {@link #NO_PRE_RESPONSE_CAP} exactly when no
     *                              pre-response deadline is armed
     * @param handOverGraceMillis   the least time, in milliseconds, the pre-response deadline leaves after the
     *                              hand-over: an attempt that hands its body over with less than this left
     *                              before the cap is refused, unsent, as an {@link UpstreamConnectionTooLate};
     *                              positive and shorter than {@code bodyDeadlineMillis} when a pre-response
     *                              deadline is armed, {@link #NO_HAND_OVER_GRACE} exactly when none is
     * @param upstreamStallMillis   how long, in milliseconds, the upstream must have held the request stream
     *                              without a break — its backpressure pausing the stream — when a passed
     *                              pre-response deadline is acted on, for the deadline to be charged to the
     *                              upstream as an {@link UpstreamStalledRequest} rather than to the client as a
     *                              {@link RequestBodyDeadlineExceeded}; positive and shorter than
     *                              {@code bodyDeadlineMillis} when a pre-response deadline is armed,
     *                              {@link #NO_UPSTREAM_STALL} exactly when none is
     * @param failureMapper         the mapper turning guarded-dispatch failures into the error contract
     * @throws IllegalArgumentException when {@code bodyIdleTimeoutMillis}, {@code bodyDeadlineMillis} or
     *                                  {@code messageTimeoutMillis} is negative, a deadline is given with
     *                                  neither an idle timeout nor a message bound, a pre-response deadline
     *                                  is given a cap shorter than itself, or a grace or a stall threshold
     *                                  that is not positive or not shorter than itself, or a cap, a grace or
     *                                  a stall threshold is given with no pre-response deadline to hold
     */
    @SuppressWarnings("java:S107") // package-private test seam; every bound is a distinct fixed value the gRPC stage derives, and a holder type would only re-bundle them for this one call
    DispatchStage(UpstreamClients upstreamClients, long maxBodyBytes, long bodyIdleTimeoutMillis,
            long bodyDeadlineMillis, long messageTimeoutMillis, long preResponseCapMillis, long handOverGraceMillis,
            long upstreamStallMillis, UpstreamFailureMapper failureMapper) {
        if (bodyIdleTimeoutMillis < NO_BODY_IDLE_TIMEOUT) {
            throw new IllegalArgumentException("bodyIdleTimeoutMillis must not be negative: " + bodyIdleTimeoutMillis);
        }
        if (bodyDeadlineMillis < NO_BODY_DEADLINE) {
            throw new IllegalArgumentException("bodyDeadlineMillis must not be negative: " + bodyDeadlineMillis);
        }
        if (messageTimeoutMillis < NO_FIRST_MESSAGE_TIMEOUT) {
            throw new IllegalArgumentException("messageTimeoutMillis must not be negative: " + messageTimeoutMillis);
        }
        if (bodyDeadlineMillis != NO_BODY_DEADLINE && bodyIdleTimeoutMillis == NO_BODY_IDLE_TIMEOUT
                && messageTimeoutMillis == NO_FIRST_MESSAGE_TIMEOUT) {
            throw new IllegalArgumentException(
                    "a request-body deadline needs a request-body idle timeout or a message bound");
        }
        boolean preResponseDeadline = bodyDeadlineMillis != NO_BODY_DEADLINE
                && bodyIdleTimeoutMillis == NO_BODY_IDLE_TIMEOUT;
        if (preResponseDeadline && preResponseCapMillis < bodyDeadlineMillis) {
            throw new IllegalArgumentException(
                    "a pre-response deadline needs a cap no shorter than itself: " + preResponseCapMillis);
        }
        if (preResponseDeadline && (handOverGraceMillis <= NO_HAND_OVER_GRACE
                || handOverGraceMillis >= bodyDeadlineMillis)) {
            throw new IllegalArgumentException(
                    "a pre-response deadline needs a positive hand-over grace shorter than itself: "
                            + handOverGraceMillis);
        }
        if (!preResponseDeadline && preResponseCapMillis != NO_PRE_RESPONSE_CAP) {
            throw new IllegalArgumentException(
                    "a pre-response cap needs a pre-response deadline to hold: " + preResponseCapMillis);
        }
        if (!preResponseDeadline && handOverGraceMillis != NO_HAND_OVER_GRACE) {
            throw new IllegalArgumentException(
                    "a hand-over grace needs a pre-response deadline to hold: " + handOverGraceMillis);
        }
        if (preResponseDeadline && (upstreamStallMillis <= NO_UPSTREAM_STALL
                || upstreamStallMillis >= bodyDeadlineMillis)) {
            throw new IllegalArgumentException(
                    "a pre-response deadline needs a positive upstream stall threshold shorter than itself: "
                            + upstreamStallMillis);
        }
        if (!preResponseDeadline && upstreamStallMillis != NO_UPSTREAM_STALL) {
            throw new IllegalArgumentException(
                    "an upstream stall threshold needs a pre-response deadline to hold: " + upstreamStallMillis);
        }
        this.upstreamClients = Objects.requireNonNull(upstreamClients, "upstreamClients");
        this.maxBodyBytes = maxBodyBytes;
        this.bodyIdleTimeoutMillis = bodyIdleTimeoutMillis;
        this.bodyDeadlineMillis = bodyDeadlineMillis;
        this.messageTimeoutMillis = messageTimeoutMillis;
        this.preResponseCapMillis = preResponseCapMillis;
        this.handOverGraceMillis = handOverGraceMillis;
        this.upstreamStallMillis = upstreamStallMillis;
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
    }

    /**
     * Builds the upstream request URI (path + query) — the resolved upstream base path, the request
     * path remainder, and the mode-filtered raw query appended verbatim.
     *
     * @param upstream      the resolved upstream target
     * @param pathRemainder the request path remainder after the route prefix is stripped
     * @param rawQuery      the raw query string including its leading {@code ?}, or empty when none
     * @return the upstream request URI
     */
    public static String upstreamRequestUri(ResolvedUpstream upstream, String pathRemainder, String rawQuery) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(pathRemainder, "pathRemainder");
        Objects.requireNonNull(rawQuery, "rawQuery");
        // The whole trailing run, as LocationRewriter strips it from this same base path, so the
        // rewrite_location round trip cannot split. A base path reaches here from two places and only
        // one is normalized (RouteTableBuilder joins a configured upstream.path; an alias base path is
        // whatever URI.getPath() returned), so the agreement is enforced here rather than assumed.
        String path = PathSlashes.stripTrailing(upstream.basePath()) + pathRemainder;
        return rawQuery.isEmpty() ? path : path + rawQuery;
    }

    /**
     * Dispatches the request to the route's upstream, sending its body as {@code framing} directs —
     * none for a bodyless request, otherwise streamed byte-capped and, when a length is declared,
     * framed by it — and returning the response whose body is not yet consumed. Whether a failed
     * attempt is retried is decided by the guard's retry predicate, {@link #allowsRetryAfter(Throwable)},
     * so a non-idempotent method or a request that has already streamed a body byte is never re-sent
     * (see the class javadoc).
     *
     * @param method         the request method — used both to build the upstream request and to
     *                       decide whether a failed attempt may be retried
     * @param route          the selected proxy route; its upstream target and resilience shape select
     *                       the shared client and guard from the {@link UpstreamClients}
     * @param requestUri     the upstream request URI (see {@link #upstreamRequestUri})
     * @param forwardHeaders the mode-filtered header set computed by stage 5
     * @param requestBody    the inbound request body as a live read stream
     * @param framing        how the body is framed towards the upstream (see {@link BodyFraming})
     * @return the upstream response (body still streaming)
     * @throws GatewayException      carrying the mapped error-contract event on any dispatch failure
     * @throws IllegalStateException when the route resolves no upstream, or the lookup holds no client
     *                               or guard for it — a boot-assembly defect, never a request's fault
     */
    public HttpClientResponse dispatch(Route route, HttpMethod method, String requestUri,
            Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody, BodyFraming framing) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(method, METHOD_PARAMETER);
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(forwardHeaders, "forwardHeaders");
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(framing, "framing");
        StreamAwareRetryGate retryGate = new StreamAwareRetryGate(route.retryEnabled());
        io.vertx.core.http.HttpMethod upstreamMethod = io.vertx.core.http.HttpMethod.valueOf(method.name());
        ResolvedUpstream upstream = route.upstream();
        if (upstream == null) {
            throw new IllegalStateException("proxy dispatch requires a resolved upstream");
        }
        // The one shape derivation the assembler keyed the client and guard under, so the lookup can
        // never miss because the two sides derived the key differently.
        ResilienceShape shape = ResilienceShape.of(route);
        HttpClient httpClient = upstreamClients.client(shape.target())
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires an upstream client"));
        Guard guard = upstreamClients.guard(shape)
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires a resilience guard"));
        Throwable departedEarly = requestBody instanceof InboundBody armed ? armed.departure() : null;
        if (departedEarly != null) {
            // The client left before the dispatch began: no upstream request is asked for at all.
            throw departureAbort(departedEarly, !framing.bodyless());
        }
        DispatchBody body = new DispatchBody(requestBody, framing);
        boolean handedToCaller = false;
        try {
            HttpClientResponse response = guardedDispatch(guard, retryGate, method, body.bytesSent::get,
                    body.subscribed::get,
                    () -> awaitDispatch(upstream, httpClient, upstreamMethod, requestUri, forwardHeaders, body));
            handedToCaller = true;
            return response;
        } finally {
            // An attempt's response reaches the caller only when the guard passes it on. Whatever the last
            // attempt still holds when the dispatch fails is released here; only a response actually
            // handed to the caller is exempt.
            if (!handedToCaller) {
                body.abandonCurrentAttempt();
            }
        }
    }

    /**
     * Serves an asset route's terminal action.
     * <p>
     * The {@link AssetSource} implementation applies its own gateway-owned
     * {@code PathConfinement} and {@code AssetResponseEnvelope} governance before returning, so
     * this method only forwards to the source. The auth-before-source-resolution ordering is
     * guaranteed by the caller: the edge pipeline authenticates and authorizes the request
     * (stage 4) before this method is reached, so an unauthorized request never triggers a
     * directory read or an upstream fetch.
     *
     * @param source  the route's live asset source (directory or upstream)
     * @param method  the request verb; only {@code GET} and {@code HEAD} are served
     * @param subPath the request path remainder after the route prefix is stripped
     * @return the gateway-governed, buffered asset response
     */
    public static AssetSource.Served serveAsset(AssetSource source, HttpMethod method, String subPath) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(method, METHOD_PARAMETER);
        Objects.requireNonNull(subPath, "subPath");
        return source.serve(method, subPath);
    }

    /**
     * The guard's retry predicate: whether the attempt that just failed with {@code failure} may be
     * retried. The route's guard evaluates it after an attempt failed and before any re-entry, so a
     * retry it refuses is never re-entered and is not recorded by the circuit breaker.
     * <p>
     * A {@link GatewayException} in the failure's cause chain (followed to a bounded depth) is never
     * retried: a dispatch the client ended, and a failure already mapped to the error contract, end
     * the dispatch. Any other failure is retried only while the dispatch's own state permits it — the
     * {@link StreamAwareRetryGate} allows a retry for the request method at the current
     * body-bytes-sent count, and the one-shot request-body stream was not subscribed by an attempt.
     * The inbound body {@link ReadStream} is single-use: re-attaching an already-subscribed stream to
     * a fresh upstream request would silently stall waiting for events that already fired, so such a
     * retry is refused even when no body byte was counted, since the stream is subscribed the instant
     * an attempt reaches {@code request.send(...)}, before any byte crosses.
     * <p>
     * The dispatch's state is read from the scoped value {@link #guardedDispatch} binds around the
     * guard call. Evaluated outside such a call, the predicate fails closed and refuses the retry.
     *
     * @param failure the failure the attempt ended with
     * @return {@code true} only when the attempt may be retried
     */
    static boolean allowsRetryAfter(Throwable failure) {
        if (extractGatewayException(failure) != null || !RETRY_STATE.isBound()) {
            return false;
        }
        return RETRY_STATE.get().allowsRetry();
    }

    /**
     * Runs {@code attempt} through the route's resilience {@code guard}, with this dispatch's retry
     * state — {@code method}, {@code retryGate}, the running {@code bytesSent} count and whether the
     * body stream was consumed ({@code bodyStreamConsumed}) — bound for the guard's retry predicate,
     * {@link #allowsRetryAfter(Throwable)}, which decides whether a failed attempt is retried. Every
     * failure leaves as a mapped {@link GatewayException}. Package-private so the retry decision can
     * be exercised without a live upstream.
     */
    HttpClientResponse guardedDispatch(Guard guard, StreamAwareRetryGate retryGate, HttpMethod method,
            LongSupplier bytesSent, BooleanSupplier bodyStreamConsumed, Callable<HttpClientResponse> attempt) {
        RetryState retryState = new RetryState(method, retryGate, bytesSent, bodyStreamConsumed);
        // The trailing catch below is a deliberate catch-all, and the only shape that compiles:
        // Guard#call propagates the checked Exception declared by the Callable it wraps, while
        // guardedDispatch itself declares no throws clause — so a narrower catch would leave that
        // checked exception unhandled. It is also the correct boundary semantically: this is the
        // single translation point where any upstream/guard failure becomes a mapped GatewayException,
        // so one unexpected exception can neither escape onto the request path nor leak a stack trace
        // to the client.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            // A synchronous guarded call runs on this thread, retries included, so the binding is
            // visible wherever the guard evaluates its retry predicate.
            return ScopedValue.where(RETRY_STATE, retryState).call(() -> guard.call(() -> {
                try {
                    return attempt.call();
                } catch (ExecutionException executionFailure) {
                    // awaitDispatch blocks on CompletableFuture#get, which wraps the real cause (e.g. a
                    // client-side body-cap GatewayException from ByteCappedBodyStream) in an
                    // ExecutionException. Unwrap it so the breaker's skipOn(GatewayException.class) rule
                    // and the retry predicate see the actual GatewayException rather than the wrapper —
                    // otherwise a client-caused rejection is miscounted as an upstream failure and can
                    // trip the circuit breaker for reasons unrelated to upstream health.
                    Throwable cause = executionFailure.getCause();
                    throw cause instanceof Exception exception ? exception : executionFailure;
                }
            }, HttpClientResponse.class));
        } catch (GatewayException direct) {
            throw direct;
        } catch (Exception guarded) {
            GatewayException breach = extractGatewayException(guarded);
            if (breach != null) {
                throw breach;
            }
            throw failureMapper.toGatewayException(guarded);
        }
    }

    private static HttpClientResponse awaitDispatch(ResolvedUpstream upstream, HttpClient httpClient,
            io.vertx.core.http.HttpMethod method, String requestUri, Map<String, String> forwardHeaders,
            DispatchBody body) throws InterruptedException, ExecutionException {
        RequestOptions options = new RequestOptions()
                .setMethod(method)
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl("https".equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        // Begun before the request is asked for, so an attempt the dispatch stops waiting for is
        // released whatever state its upstream exchange has reached by then.
        UpstreamAttempt attempt = body.beginAttempt();
        boolean handedBack = false;
        try {
            GatewayException departed = body.departure();
            if (departed != null) {
                // The client left before this attempt asked for its request: none is asked for.
                throw departed;
            }
            HttpClientResponse received = awaitResponse(httpClient, options, forwardHeaders, body, attempt);
            handedBack = true;
            return received;
        } finally {
            // Any exit other than handing the response back — an interrupt from the guard's timeout, a
            // failed send, a refusal — releases this attempt's upstream exchange before the exit
            // propagates unchanged: a request still waiting for its connection is never sent, one in
            // flight is reset, and a response received and paused is released with it.
            if (!handedBack) {
                attempt.abandon();
            }
        }
    }

    private static HttpClientResponse awaitResponse(HttpClient httpClient, RequestOptions options,
            Map<String, String> forwardHeaders, DispatchBody body, UpstreamAttempt attempt)
            throws InterruptedException, ExecutionException {
        AtomicReference<@Nullable ByteCappedBodyStream> cappedBody = new AtomicReference<>();
        // The client-caused abort as it stood the moment this attempt's send failed — never re-read
        // afterwards (see the catch below).
        AtomicReference<@Nullable GatewayException> clientAbortAtFailure = new AtomicReference<>();
        // The upstream's stall the body stream ended the request for, read at the same moment.
        AtomicReference<@Nullable UpstreamStalledRequest> upstreamStallAtFailure = new AtomicReference<>();
        Future<HttpClientResponse> response = httpClient.request(options)
                .compose(request -> {
                    // Registered before the request is published, so every reset of it the gateway
                    // makes — the unsent release below included — finds a handler (see the class javadoc).
                    request.exceptionHandler(DispatchStage::onUpstreamRequestFailure);
                    // A bodyless request's head is written by the send below, from this callback; a body is
                    // written by the inbound side as it arrives (see UpstreamAttempt).
                    if (!attempt.publish(request, body.bodyless())) {
                        // The dispatch stopped waiting for this attempt before its request obtained a
                        // connection: the request was reset unsent, and no late upstream execution follows.
                        // When the client's departure released it, that is what the dispatch reports.
                        clientAbortAtFailure.set(body.clientAbort(cappedBody.get()));
                        return Future.failedFuture(new AttemptReleasedUnsent());
                    }
                    forwardHeaders.forEach(request::putHeader);
                    // ResponseStage#relay is deferred onto the server request's event loop
                    // (GatewayEdgeRoute), so the upstream response must be paused before any of its
                    // body or its end is processed — otherwise a small response ends before the deferred
                    // pipeTo subscribes, its body is dropped, and the pipe fails with "Response already
                    // ended". The pause is attached HERE, to the send future, from inside this callback:
                    // the callback runs on the request's event-loop context before the request is
                    // written, so the pause is registered before the response head can arrive, and the
                    // head is delivered on that same context, so the pause runs synchronously with head
                    // handling. Attaching it to the composed future instead would register it from the
                    // dispatching (virtual) thread, which may only get there after the whole exchange
                    // completed; Vert.x then defers the listener onto the event loop, after the end. The
                    // pipe re-enables the stream when it subscribes.
                    Future<HttpClientResponse> sent = body.send(attempt, request, cappedBody);
                    // Registered before the pause below, so it runs first when the send fails, on the
                    // context reporting that failure — before the failure can reach the dispatching thread.
                    sent.onFailure(failure -> {
                        ByteCappedBodyStream sentBody = cappedBody.get();
                        upstreamStallAtFailure.set(sentBody == null ? null : sentBody.upstreamStall());
                        clientAbortAtFailure.set(body.clientAbort(sentBody));
                    });
                    return sent.map(received -> {
                        received.pause();
                        return received;
                    });
                });
        HttpClientResponse received;
        try {
            received = response.toCompletionStage().toCompletableFuture().get();
        } catch (ExecutionException dispatchFailure) {
            // The body stream aborts the upstream request itself when the client ends the dispatch, and
            // the transport then reports that abort as a reset. Surface the recorded client-side cause
            // in its place, so the guard sees a GatewayException — which it skips. Only the snapshot
            // taken when the send failed counts: every body stream records its client abort before it
            // runs the abort action, so an abort that caused the failure is always in the snapshot,
            // while a client that went away only after the upstream had already failed is not — that
            // failure stays the upstream's. A send that never started (the connection itself failed)
            // leaves no snapshot and is the upstream's failure as well. An attempt released before its
            // request was sent arrives here too, as an AttemptReleasedUnsent: never a cancellation,
            // which get() would throw past this catch (see AttemptReleasedUnsent). NOSONAR java:S125 - explanatory prose, not commented-out code
            // A reset the body stream made because the upstream had stalled the request stream for at least the
            // stall threshold when its pre-response deadline was acted on is the upstream's failure instead: it is
            // surfaced as the upstream timeout it is,
            // which the guard counts. It took the stream's single abort claim, so no client abort of the body
            // stream can stand beside it, and a departure after it came too late to have caused the failure.
            UpstreamStalledRequest upstreamStall = upstreamStallAtFailure.get();
            if (upstreamStall != null) {
                throw new ExecutionException(upstreamStall.getMessage(), upstreamStall);
            }
            GatewayException clientAbort = clientAbortAtFailure.get();
            if (clientAbort != null) {
                throw clientAbort;
            }
            throw dispatchFailure;
        }
        // From here on a body byte on a bodyless method, and a body still arriving at the request-body
        // deadline, reach the edge through the late-violation callback. One that came while the response
        // was still being received is this dispatch's own refusal instead: the response is not relayed,
        // and the edge answers 400 or 408. So is a message bound or a pre-response deadline that passed in
        // that window; both stop with the send, so neither fires after the hand-over. A pre-response deadline
        // that ended the body in that window as the upstream's stall is the upstream's timeout, read before the
        // hand-over for the same reason: nothing can claim it once the send has completed. That window is real: the
        // read that claims the stall may run on the inbound connection's event loop — a chunk crossing — while the
        // answer completes the send on the upstream request's, before the reset the claim sent reaches the stream.
        // The response is then never relayed: the attempt is released and the call ends as the upstream's timeout.
        ByteCappedBodyStream sentBody = cappedBody.get();
        UpstreamStalledRequest stalledBeforeHandOver = sentBody == null ? null : sentBody.upstreamStall();
        if (stalledBeforeHandOver != null) {
            throw new ExecutionException(stalledBeforeHandOver.getMessage(), stalledBeforeHandOver);
        }
        GatewayException refusedBeforeHandOver = body.handOver(sentBody);
        if (refusedBeforeHandOver != null) {
            throw refusedBeforeHandOver;
        }
        return received;
    }

    /**
     * The exception handler of every attempt's upstream request. A {@link StreamResetException} on
     * an HTTP/1.x request can only come from the gateway's own {@link HttpClientRequest#reset()} —
     * HTTP/1.x has no peer stream reset — so it is dropped: the reset is how the gateway stops
     * forwarding, not a failure. The handler is also installed on the forced-HTTP/2 (gRPC) path, where
     * the same exception type can instead carry the upstream's own {@code RST_STREAM}. Dropping it hides
     * no upstream failure on either path. While the send is pending, or a handed-back response is still
     * streaming, Vert.x fails the send future or the response stream with the same exception, and it is
     * mapped, metered or answered there. A reset that arrives only after the response has completed — an
     * upstream ending the stream once it has answered, as HTTP/2 permits — finds the send future already
     * completed and the response already relayed: it fails nothing, so dropping it hides nothing either.
     * Any other failure takes the send-future or response-stream route, so it is logged here at
     * {@code DEBUG} only.
     */
    private static void onUpstreamRequestFailure(Throwable failure) {
        if (failure instanceof StreamResetException) {
            return;
        }
        LOGGER.debug(failure, "Upstream request reported a failure, handled where it is mapped: %s",
                failure.getClass().getName());
    }

    /**
     * The client abort a dispatch reports for a client that left before it was answered: a request body
     * that was still arriving did not arrive whole, exactly as when the body stream itself reports the
     * failure; any other request — a bodyless one, or one whose whole body arrived — is a
     * {@link ClientDeparted}.
     *
     * @param cause             how the client's response ended
     * @param bodyStillArriving whether the request carries a body whose end had not arrived
     * @return the client abort
     */
    private static GatewayException departureAbort(Throwable cause, boolean bodyStillArriving) {
        return bodyStillArriving
                ? new GatewayException(EventType.INBOUND_BODY_ABORTED, ByteCappedBodyStream.INBOUND_BODY_FAILED, cause)
                : new ClientDeparted(cause);
    }

    private static @Nullable GatewayException extractGatewayException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof GatewayException gatewayException) {
                return gatewayException;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    /**
     * How a request's body is framed towards the upstream — the facts the edge hands the dispatch
     * about the request the framing gate admitted.
     *
     * @param bodyless       whether the request is a {@code GET} or {@code HEAD} without a positive
     *                       declared {@code Content-Length}: it is sent upstream with no body, and its
     *                       first inbound body byte refuses it (see {@link BodylessMethodWatch})
     * @param declaredLength the request's checked declared {@code Content-Length} — the value the
     *                       framing gate and the {@code max_body_bytes} pre-check admitted — or any
     *                       negative value, conventionally {@link #NO_DECLARED_LENGTH}, when it
     *                       declares none. A value {@code >= 0} on a request that is not bodyless
     *                       becomes the upstream {@code Content-Length}, and the forwarded body is
     *                       held to exactly that many bytes
     * @param lateViolation  receives a refusal that arrives only after the dispatch returned its
     *                       response: the {@link EventType#SECURITY_FILTER_VIOLATION} of a bodyless
     *                       request's first body byte, invoked on the inbound request's event loop, or
     *                       the {@link RequestBodyDeadlineExceeded} of a body still arriving at the
     *                       request-body deadline or read again after it passed, invoked on the event
     *                       loop that observed it — the upstream request's for the deadline's timer and
     *                       a read the upstream resumes; either way once, before the upstream request is
     *                       reset
     * @since 1.0
     */
    public record BodyFraming(boolean bodyless, long declaredLength, Consumer<GatewayException> lateViolation) {

        /**
         * @throws NullPointerException when {@code lateViolation} is {@code null}
         */
        public BodyFraming {
            Objects.requireNonNull(lateViolation, "lateViolation");
        }

        /**
         * The framing of a proxied request: bodyless exactly when {@code method} is {@code GET} or
         * {@code HEAD} and it declares no positive length.
         *
         * @param method         the request method
         * @param declaredLength the checked declared {@code Content-Length}, negative when none
         * @param lateViolation  receives a refusal that arrives after the dispatch returned
         * @return the framing
         */
        public static BodyFraming of(HttpMethod method, long declaredLength,
                Consumer<GatewayException> lateViolation) {
            Objects.requireNonNull(method, METHOD_PARAMETER);
            return new BodyFraming(BODYLESS_METHODS.contains(method) && declaredLength <= 0, declaredLength,
                    lateViolation);
        }

        /**
         * The framing of a request whose body is always streamed — never bodyless — dispatched without
         * a request-body deadline, so no late refusal can arrive and none is received.
         *
         * @param declaredLength the checked declared {@code Content-Length}, negative when none
         * @return the framing
         */
        public static BodyFraming streamed(long declaredLength) {
            return new BodyFraming(false, declaredLength, violation -> {
                // A streamed body is never refused late: there is no callback to run.
            });
        }
    }

    /**
     * What the guard's retry predicate needs to know about one dispatch.
     *
     * @param method             the request method
     * @param retryGate          the route's retry gate
     * @param bytesSent          the running count of body bytes forwarded to the upstream
     * @param bodyStreamConsumed whether an attempt subscribed the one-shot inbound body stream
     */
    private record RetryState(HttpMethod method, StreamAwareRetryGate retryGate, LongSupplier bytesSent,
    BooleanSupplier bodyStreamConsumed) {

        /**
         * @return {@code true} when the body stream was not subscribed and the retry gate allows a
         *         retry at the current body-bytes-sent count
         */
        boolean allowsRetry() {
            return !bodyStreamConsumed.getAsBoolean() && retryGate.allowsRetry(method, bytesSent.getAsLong());
        }
    }

    /**
     * The request-body side of one dispatch, shared by every attempt the guard makes: how the body
     * is sent with an attempt's upstream request, and the client-caused abort an attempt reports in
     * place of the transport failure its own abort produced.
     */
    private final class DispatchBody {

        private final ReadStream<Buffer> inbound;
        private final long declaredLength;
        /** Running count of body bytes forwarded, read by the retry predicate. */
        private final AtomicLong bytesSent = new AtomicLong();
        /** Whether an attempt subscribed the one-shot inbound stream, read by the retry predicate. */
        private final AtomicBoolean subscribed = new AtomicBoolean();
        /**
         * The latest attempt's upstream exchange: reset by a bodyless refusal, released when the next
         * attempt begins, and released when the dispatch ends without handing its response back.
         */
        private final AtomicReference<@Nullable UpstreamAttempt> currentAttempt = new AtomicReference<>();
        /** Present exactly for a bodyless request; armed once, for every attempt. */
        private final @Nullable BodylessMethodWatch bodylessWatch;
        /** Receives a refusal that arrives after the response was handed over. */
        private final Consumer<GatewayException> lateViolation;
        /** Guards {@code departure} and {@code handedOver}: a departure takes exactly one side of the hand-over. */
        private final Object departureLock = new Object();
        /** The client abort recorded when the client left before the response was handed over. */
        private @Nullable GatewayException departure;
        /** Whether the response was handed over for relay: from then on the relay owns the exchange. */
        private boolean handedOver;

        DispatchBody(ReadStream<Buffer> inbound, BodyFraming framing) {
            this.inbound = inbound;
            this.declaredLength = framing.declaredLength();
            this.lateViolation = framing.lateViolation();
            this.bodylessWatch = framing.bodyless()
                    ? BodylessMethodWatch.arm(inbound, this::resetCurrentRequest, lateViolation)
                    : null;
            // Registered last: a departure replayed here classifies the request by its framing.
            if (inbound instanceof InboundBody armed) {
                armed.departureHandler(this::clientDeparted);
            }
        }

        /**
         * The client left before its response ended. Until the response is handed over, the dispatch
         * stops waiting: the client abort is recorded first, so the transport failure the release causes
         * is reported as the client's, then the current attempt is released — a request still waiting for
         * its pooled connection is never sent, one in flight is reset. Once the response was handed over
         * the relay owns the exchange and the departure is left to it. Idempotent.
         */
        private void clientDeparted(Throwable cause) {
            synchronized (departureLock) {
                if (handedOver || departure != null) {
                    return;
                }
                departure = departureAbort(cause, bodyStillArriving());
            }
            abandonCurrentAttempt();
        }

        /** @return {@code true} for a bodyless request: it is sent upstream with no body */
        boolean bodyless() {
            return bodylessWatch != null;
        }

        /**
         * @return {@code true} when the request carries a body whose end has not reached the current
         *         attempt's body stream — or no attempt has subscribed the body yet
         */
        private boolean bodyStillArriving() {
            if (bodylessWatch != null) {
                return false;
            }
            UpstreamAttempt current = currentAttempt.get();
            ByteCappedBodyStream sent = current == null ? null : current.body();
            return sent == null || !sent.inboundEnded();
        }

        /**
         * @return the client abort recorded when the client left before the hand-over, or {@code null}
         */
        @Nullable
        GatewayException departure() {
            synchronized (departureLock) {
                return departure;
            }
        }

        /**
         * Begins the upstream exchange of the next attempt. The previous attempt, if any, is no longer
         * waited for — its response, if it returned one, never reached the caller — so it is released.
         *
         * @return the attempt that is now current
         */
        UpstreamAttempt beginAttempt() {
            UpstreamAttempt next = new UpstreamAttempt();
            UpstreamAttempt previous = currentAttempt.getAndSet(next);
            if (previous != null) {
                previous.abandon();
            }
            return next;
        }

        /** Releases the upstream exchange of the latest attempt, when there is one. */
        void abandonCurrentAttempt() {
            UpstreamAttempt current = currentAttempt.get();
            if (current != null) {
                current.abandon();
            }
        }

        /**
         * Sends {@code request} with this dispatch's body: none for a bodyless request, otherwise the
         * byte-capped inbound stream, framed by the declared length when there is one. The request is
         * already published to the current attempt, so a bodyless refusal from here on resets it.
         * <p>
         * A body-carrying attempt arms the stream's request-body idle clock here, the moment it hands
         * the body to its upstream request — so the time spent waiting for a pooled connection is never
         * counted — and stops it as soon as the attempt's send completes, whether its response arrived
         * or the send failed. The clock therefore never runs once the response is handed over. The
         * message clock is armed and stopped at the same two moments. The request-body deadline is armed
         * at the same moment. Armed with the idle clock — the whole-body deadline — it is counted from the
         * start of {@code attempt} and keeps running after the send completed, until the body ends. Armed
         * with the message clock — the gRPC pre-response deadline — it is counted from now, the hand-over,
         * passes no later than the pre-response cap after the start of {@code attempt}, and stops with
         * that clock when the send completes; when less than the hand-over grace is left before the cap, or
         * the cap has already passed, the request is reset unsent and the send fails as an
         * {@link UpstreamConnectionTooLate} instead, before the inbound stream is subscribed, so an armed
         * pre-response deadline always passes at least the hand-over grace after the hand-over. The body is attached to {@code attempt} before any clock is armed, so an attempt the
         * dispatch no longer waits for stops them all. Every timer is scheduled through the Vert.x instance
         * owning the context this callback runs on, the upstream request's own.
         *
         * @throws IllegalStateException when the idle clock or the message clock is to be armed and no
         *                               Vert.x context is current — the send callback always runs on one
         */
        Future<HttpClientResponse> send(UpstreamAttempt attempt, HttpClientRequest request,
                AtomicReference<@Nullable ByteCappedBodyStream> cappedBody) {
            if (bodylessWatch != null) {
                GatewayException refused = bodylessWatch.clientAbort();
                if (refused != null) {
                    // Refused before this attempt's request was sent: release it unsent.
                    request.reset();
                    return Future.failedFuture(refused);
                }
                // No body at all, and the inbound stream stays unsubscribed, so the retry predicate
                // keeps treating the attempt as bodyless.
                return request.send();
            }
            long handOverNanos = System.nanoTime();
            OptionalLong deadline = bodyDeadlineOf(attempt, handOverNanos);
            if (preResponseCapMillis != NO_PRE_RESPONSE_CAP
                    && deadline.orElseThrow() - handOverNanos < TimeUnit.MILLISECONDS.toNanos(handOverGraceMillis)) {
                // The connection wait left less than the hand-over grace before the pre-response cap: too
                // little time for the client to send, or for an end it already sent to be delivered before
                // the deadline, so the attempt ends as the upstream's timeout, its request unsent and its
                // body unread.
                request.reset();
                return Future.failedFuture(new UpstreamConnectionTooLate());
            }
            if (declaredLength >= 0) {
                request.putHeader(CONTENT_LENGTH, Long.toString(declaredLength));
            }
            // The one-shot inbound body stream is subscribed the instant it is handed to
            // request.send(...); mark it so a retry never re-attaches the consumed stream (which would
            // stall) — see allowsRetryAfter.
            subscribed.set(true);
            ByteCappedBodyStream body = new ByteCappedBodyStream(inbound, maxBodyBytes, declaredLength,
                    request::reset, bytesSent::addAndGet, idleClock(), deadline, messageClock(), upstreamStallMillis);
            cappedBody.set(body);
            attempt.attachBody(body);
            body.startIdleClock();
            body.startMessageClock();
            Future<HttpClientResponse> sent = request.send(body);
            sent.onComplete(_ -> {
                body.stopIdleClock();
                body.stopMessageClock();
            });
            return sent;
        }

        /**
         * @return the idle clock of a body-carrying attempt: unarmed for a dispatch constructed with
         *         {@link #NO_BODY_IDLE_TIMEOUT}, otherwise timed on the current Vert.x context
         */
        private IdleClock idleClock() {
            if (bodyIdleTimeoutMillis == NO_BODY_IDLE_TIMEOUT) {
                return IdleClock.UNARMED;
            }
            return new IdleClock(bodyIdleTimeoutMillis, currentContextScheduler("request-body idle clock"),
                    System::nanoTime);
        }

        /**
         * @return the message clock of a body-carrying attempt: unarmed for a dispatch constructed with
         *         {@link #NO_FIRST_MESSAGE_TIMEOUT}, otherwise timed on the current Vert.x context
         */
        private IdleClock messageClock() {
            if (messageTimeoutMillis == NO_FIRST_MESSAGE_TIMEOUT) {
                return IdleClock.UNARMED;
            }
            return new IdleClock(messageTimeoutMillis, currentContextScheduler("message clock"), System::nanoTime);
        }

        /**
         * @param clockName names the clock in the failure message
         * @return a scheduler on the Vert.x instance owning the current context, the upstream request's
         * @throws IllegalStateException when no Vert.x context is current
         */
        private static IdleScheduler currentContextScheduler(String clockName) {
            Context context = Vertx.currentContext();
            if (context == null) {
                throw new IllegalStateException("the " + clockName + " is armed on the upstream request's "
                        + "Vert.x context, and none is current");
            }
            return new VertxIdleScheduler(context.owner());
        }

        /**
         * @param attempt       the attempt handing its body over
         * @param handOverNanos the {@link System#nanoTime()} reading of the hand-over
         * @return the {@link System#nanoTime()} reading at which {@code attempt}'s request-body deadline
         *         passes — the whole-body deadline counted from the start of {@code attempt}, or the
         *         pre-response deadline counted from {@code handOverNanos} and held to the pre-response cap
         *         counted from the start of {@code attempt} — or empty for a dispatch constructed with
         *         {@link #NO_BODY_DEADLINE}
         */
        private OptionalLong bodyDeadlineOf(UpstreamAttempt attempt, long handOverNanos) {
            if (bodyDeadlineMillis == NO_BODY_DEADLINE) {
                return OptionalLong.empty();
            }
            long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(bodyDeadlineMillis);
            if (preResponseCapMillis == NO_PRE_RESPONSE_CAP) {
                return OptionalLong.of(attempt.startNanos() + deadlineNanos);
            }
            // Compared as differences, never as absolute readings: System.nanoTime() may wrap.
            long sinceStartNanos = handOverNanos - attempt.startNanos();
            long untilCapNanos = TimeUnit.MILLISECONDS.toNanos(preResponseCapMillis) - sinceStartNanos;
            return OptionalLong.of(handOverNanos + Math.min(deadlineNanos, untilCapNanos));
        }

        /**
         * @return the client-caused reason the attempt's upstream request was aborted — the body's own
         *         refusal, or else the client's departure — or {@code null} when it was not
         */
        @Nullable
        GatewayException clientAbort(@Nullable ByteCappedBodyStream cappedBody) {
            GatewayException bodyAbort;
            if (bodylessWatch != null) {
                bodyAbort = bodylessWatch.clientAbort();
            } else {
                bodyAbort = cappedBody == null ? null : cappedBody.clientAbort();
            }
            return bodyAbort != null ? bodyAbort : departure();
        }

        /**
         * Hands the received response over for relay: a refusal from now on goes to the late-violation
         * callback, and a client departure is left to the relay. A client that already left is reported
         * instead, and nothing is handed over.
         *
         * @param cappedBody the attempt's body stream, {@code null} for a bodyless request
         * @return the refusal that came before the hand-over — the client's departure, a bodyless
         *         request's body byte, a body still arriving at the request-body deadline or the
         *         pre-response deadline, or a body whose next complete message did not arrive within the
         *         message bound — or {@code null} when there was none
         */
        @Nullable
        GatewayException handOver(@Nullable ByteCappedBodyStream cappedBody) {
            synchronized (departureLock) {
                if (departure != null) {
                    return departure;
                }
                handedOver = true;
            }
            if (bodylessWatch != null) {
                return bodylessWatch.handOver();
            }
            return cappedBody == null ? null : cappedBody.handOver(lateViolation);
        }

        private void resetCurrentRequest() {
            UpstreamAttempt current = currentAttempt.get();
            if (current != null) {
                current.resetRequest();
            }
        }
    }

    /**
     * The upstream exchange of one attempt: the request it obtained, the body stream it sends, and
     * whether the dispatch has stopped waiting for it.
     * <p>
     * <strong>Released exactly when it is no longer waited for.</strong> {@link #abandon()} marks the
     * attempt and resets its request; a request published only after that is reset at once and never
     * sent. Resetting the request also releases a response the attempt already received and paused.
     * <p>
     * <strong>Its body's clocks stop with it.</strong> {@link #abandon()} also stops the request-body idle
     * clock, the deadline and the message clock of the body stream the attempt sends, and a body
     * attached only after that has them stopped at once, before they could start. A stopped clock has no timer pending and reports
     * nothing: it neither refuses the body, nor resets the request, nor reaches the late-violation callback.
     * <p>
     * <strong>A bodyless request is reset after its own head write.</strong> A bodyless attempt sends its
     * request from the callback that publishes it, on the request's own context, and Vert.x may queue that
     * head write on the event loop of the upstream connection. A reset run on that event loop while the
     * write is still queued is applied first: the HTTP/1.1 connection goes back to the pool, and the queued
     * write then puts the request on it all the same, so the next request handed that connection waits
     * behind a request nobody reads the answer to, until the guard's timeout. The client's departure is
     * reported on the inbound connection's event loop, which can be that very loop. A reset of a published
     * bodyless request asked for from any thread other than the one that published it is therefore handed
     * to the publishing context and runs there, after the send. A body-carrying request has its head
     * written with the first body chunk, on the inbound side, and is reset on the thread that asks for it.
     * <p>
     * Thread-safe: the request and the body are published on the request's event loop while the
     * dispatching thread may abandon the attempt at the same moment. Each side writes its own field before
     * it reads the other's — the publisher sets the request or the body, then reads the flag;
     * {@link #abandon()} sets the flag, then reads the request and the body — so whatever the interleaving,
     * at least one of them sees both and performs the reset and the stop. A second reset of the same
     * request, and a second stop of the same clocks, are harmless.
     */
    static final class UpstreamAttempt {

        /** When the attempt began, the moment the guard's timeout for it starts too. */
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicReference<@Nullable PublishedRequest> request = new AtomicReference<>();
        private final AtomicReference<@Nullable ByteCappedBodyStream> body = new AtomicReference<>();

        /**
         * Records the body stream this attempt sends, so abandoning the attempt stops its clocks. A body
         * attached to an attempt already abandoned has its clocks stopped at once.
         *
         * @param attached the attempt's body stream, attached before its clocks are started
         */
        void attachBody(ByteCappedBodyStream attached) {
            body.set(attached);
            if (abandoned.get()) {
                attached.stopClocks();
            }
        }

        /** @return the {@link System#nanoTime()} reading at which this attempt began */
        long startNanos() {
            return startNanos;
        }

        /** @return the body stream this attempt sends, or {@code null} while none is attached */
        @Nullable
        ByteCappedBodyStream body() {
            return body.get();
        }

        /**
         * Publishes the request this attempt obtained.
         *
         * @param obtained          the attempt's upstream request, not yet sent
         * @param sentFromPublisher whether the request's head is written by a send made from the calling
         *                          context once this returns, as a bodyless request's is: a reset asked
         *                          for from another thread then runs on this context, after that send
         * @return {@code true} when the attempt is still waited for and the request may be sent;
         *         {@code false} when it was already abandoned — the request has then been reset
         */
        boolean publish(HttpClientRequest obtained, boolean sentFromPublisher) {
            @Nullable Context publisher = sentFromPublisher ? Vertx.currentContext() : null;
            request.set(new PublishedRequest(obtained, publisher, Thread.currentThread()));
            if (abandoned.get()) {
                obtained.reset();
                return false;
            }
            return true;
        }

        /**
         * Stops waiting for this attempt: resets its request, if it obtained one, and stops the clocks of
         * its body, if it sends one.
         */
        void abandon() {
            abandoned.set(true);
            resetRequest();
            ByteCappedBodyStream attached = body.get();
            if (attached != null) {
                attached.stopClocks();
            }
        }

        /**
         * Resets this attempt's request, if it obtained one: at once, or — for a request sent from its
         * publishing context, asked for from another thread — on that context, after the send.
         */
        void resetRequest() {
            PublishedRequest published = request.get();
            if (published != null) {
                published.reset();
            }
        }
    }

    /**
     * An attempt's upstream request together with where its head is written from.
     *
     * @param request         the upstream request
     * @param publisher       the context the request's head is sent from, after publication, or
     *                        {@code null} when it is written elsewhere
     * @param publisherThread the thread that published the request
     */
    private record PublishedRequest(HttpClientRequest request, @Nullable Context publisher, Thread publisherThread) {

        /**
         * Resets the request. On the publishing thread, and for a request whose head is written elsewhere,
         * the reset is made at once; otherwise it is handed to the publishing context, so it runs after the
         * send made there and can never overtake that send's queued head write (see {@link UpstreamAttempt}).
         */
        void reset() {
            if (publisher == null || Thread.currentThread() == publisherThread) {
                request.reset();
            } else {
                publisher.runOnContext(_ -> request.reset());
            }
        }
    }

    /**
     * Holds a bodyless request to carrying no body: it consumes the inbound stream in place of the
     * upstream request, which is sent with none, and refuses the request on its first body byte.
     * <p>
     * A {@code GET} or {@code HEAD} without a positive declared {@code Content-Length} has no body by
     * its framing on HTTP/1.x, so no body byte ever arrives there. On HTTP/2 a body travels in DATA
     * frames no request header has to announce; the framing gate, which decides on headers, cannot
     * see it, and this watch is where it is refused — on any protocol, whatever its size. A chunk of
     * length zero (an empty final DATA frame) and the end of the stream are not a body.
     * <p>
     * <strong>One refusal, reported once.</strong> The first body byte records a
     * {@link EventType#SECURITY_FILTER_VIOLATION} as the client abort, set before the abort action
     * resets the upstream request — the same ordering {@link ByteCappedBodyStream} keeps. While the
     * dispatch is still awaited, the dispatch reports that exception and the edge answers
     * {@code 400}. Once the dispatch has {@linkplain #handOver() handed its response over} for relay,
     * the refusal goes to the late-violation callback instead, which runs before the upstream request
     * is reset, so the edge ends the client response before the aborted relay could end it cleanly.
     * The choice between the two is made under this watch's monitor, so a refusal takes exactly one of
     * them.
     * <p>
     * <strong>Armed once per dispatch.</strong> The inbound stream is single-use and no retry attempt
     * sends a body, so one watch serves every attempt; its abort action resets whichever attempt is
     * in flight.
     * <p>
     * Thread-safe: chunks arrive on the inbound connection's event loop while the dispatching thread
     * reads the refusal and hands the response over; both go through the same monitor.
     */
    static final class BodylessMethodWatch {

        /** Fixed disposition of the refusal; carries nothing the client supplied. */
        private static final String BODY_ON_BODYLESS_METHOD =
                "Framing rejected: body bytes on a bodyless method; the upstream request was aborted";

        private final Runnable abortAction;
        private final Consumer<GatewayException> lateViolation;
        private @Nullable GatewayException refusal;
        private boolean handedOver;

        private BodylessMethodWatch(Runnable abortAction, Consumer<GatewayException> lateViolation) {
            this.abortAction = abortAction;
            this.lateViolation = lateViolation;
        }

        /**
         * Starts consuming {@code inbound} and watching it for a body byte.
         *
         * @param inbound       the inbound request body, never handed to an upstream request
         * @param abortAction   resets the upstream request of the attempt in flight
         * @param lateViolation receives a refusal that arrives after the response was handed over
         * @return the armed watch
         */
        static BodylessMethodWatch arm(ReadStream<Buffer> inbound, Runnable abortAction,
                Consumer<GatewayException> lateViolation) {
            BodylessMethodWatch watch = new BodylessMethodWatch(Objects.requireNonNull(abortAction, "abortAction"),
                    Objects.requireNonNull(lateViolation, "lateViolation"));
            inbound.handler(watch::onChunk);
            inbound.resume();
            return watch;
        }

        private void onChunk(Buffer chunk) {
            if (chunk.length() == 0) {
                return;
            }
            GatewayException violation;
            boolean late;
            synchronized (this) {
                if (refusal != null) {
                    return;
                }
                violation = new GatewayException(EventType.SECURITY_FILTER_VIOLATION, BODY_ON_BODYLESS_METHOD);
                refusal = violation;
                late = handedOver;
            }
            if (late) {
                lateViolation.accept(violation);
            }
            abortAction.run();
        }

        /**
         * @return the refusal this watch recorded, or {@code null} while no body byte has arrived
         */
        synchronized @Nullable GatewayException clientAbort() {
            return refusal;
        }

        /**
         * Marks the dispatch's response as handed over for relay: a refusal from now on goes to the
         * late-violation callback.
         *
         * @return the refusal recorded before the hand-over, or {@code null} when there was none
         */
        synchronized @Nullable GatewayException handOver() {
            handedOver = true;
            return refusal;
        }
    }

    /**
     * The inbound request body together with the memory of its failure.
     * <p>
     * A stream reports a failure only to the exception handler registered at that moment. The body is
     * handed to the upstream request on a virtual thread, some time after the request arrived, so a
     * failure in between would reach no one and the dispatch would then wait on a body that can never
     * end. This decorator is therefore armed on the inbound stream's own event loop, before the
     * virtual-thread hop: it records the first failure and replays it to whichever handler is
     * registered later, so a consumer that subscribes late still learns that the body failed — and
     * {@link ByteCappedBodyStream} then aborts the upstream request instead of leaving it open.
     * <p>
     * <strong>The single place a gateway-made reset is recorded.</strong> When the edge has decided the
     * answer to an HTTP/2 request whose body is still open, it marks this decorator
     * {@linkplain #resetByGateway() reset by the gateway} before it ends the response and then resets
     * the stream. The decorator then delivers the fixed {@link GatewayStreamReset} cause — to the
     * failure handler registered at that moment, or to one registered later — in place of a client
     * failure, and from then on swallows every further failure of the stream, so the transport error the
     * reset itself may surface is never replayed as a client failure. {@link ByteCappedBodyStream}
     * answers that cause by stopping the forwarding without recording a client abort.
     * <p>
     * <strong>A body the gateway reset is never read again.</strong> The mark also pauses the inbound
     * stream and keeps it paused: every later {@link #resume()} or {@link #fetch(long)} — the forwarding
     * pipe's among them — is ignored. A paused HTTP/2 request returns no flow-control window to the
     * client, so from the mark on the client can send no more than the window it already holds; that
     * bound is what lets the edge's wait before the reset end once the client can send nothing more;
     * failing that, the wait ends once request data stops arriving or a time cap passes.
     * <p>
     * <strong>A client that leaves is recorded too.</strong> The request stream reports a client reset
     * or a closed connection only while its body is still open; once the body has ended — a bodyless
     * {@code GET} at once — the request stream is silent about it, and only the response learns that it
     * can no longer be answered. The edge root therefore hands a response that ended without being
     * answered to {@link #clientDeparted(Throwable)}, which delivers it once — to the departure handler
     * registered at that moment, or to the one registered later — so a dispatch holding an upstream
     * request releases it (see {@link DispatchStage}, which also states when a wait for a pooled
     * connection ends). A stream the gateway reset itself records no departure.
     * <p>
     * Thread-safe: the failure arrives on the inbound connection's event loop while the handler may be
     * registered from another thread; both go through the same monitor, as do the departure and its
     * handler. The pause is pinned without holding that monitor: a resume racing the mark is followed by
     * a pause again.
     */
    public static final class InboundBody implements ReadStream<Buffer> {

        private final ReadStream<Buffer> delegate;
        private @Nullable Throwable failure;
        private @Nullable Handler<Throwable> failureHandler;
        private volatile boolean resetByGateway;
        private @Nullable Throwable departure;
        private @Nullable Handler<Throwable> departureHandler;

        private InboundBody(ReadStream<Buffer> delegate) {
            this.delegate = delegate;
        }

        /**
         * Wraps {@code delegate} and claims its exception handler, so every failure from now on is
         * recorded.
         *
         * @param delegate the inbound request body stream
         * @return the armed decorator, to be handed to {@link DispatchStage#dispatch} in its place
         */
        public static InboundBody arm(ReadStream<Buffer> delegate) {
            InboundBody body = new InboundBody(Objects.requireNonNull(delegate, "delegate"));
            delegate.exceptionHandler(body::onFailure);
            return body;
        }

        private synchronized void onFailure(Throwable cause) {
            if (resetByGateway) {
                // The gateway ended this stream itself: whatever the transport surfaces for it now is
                // the echo of that reset, not a client failure.
                return;
            }
            if (failure == null) {
                failure = cause;
            }
            if (failureHandler != null) {
                failureHandler.handle(cause);
            }
        }

        /**
         * Records that the gateway itself ends this stream: its answer is decided while the body is
         * still open. The inbound stream is paused for good, so no further body is read and no further
         * flow-control window is returned to the client. When no failure was recorded before, the
         * fixed {@link GatewayStreamReset#INSTANCE} is delivered — now to the registered failure
         * handler, or later to the one registered next — so the consumer stops forwarding the body.
         * Every failure of the stream from now on is swallowed. Idempotent; a failure recorded before
         * the mark stays the one a later handler is replayed.
         */
        public void resetByGateway() {
            resetByGateway = true;
            delegate.pause();
            deliverGatewayReset();
        }

        private synchronized void deliverGatewayReset() {
            if (failure != null) {
                return;
            }
            failure = GatewayStreamReset.INSTANCE;
            if (failureHandler != null) {
                failureHandler.handle(GatewayStreamReset.INSTANCE);
            }
        }

        /**
         * Records that the client left before its response ended: it reset the HTTP/2 stream or closed
         * the connection, so the response can no longer be answered. The edge root calls this from the
         * request's end handler when the response ended without being written. The departure is
         * delivered once — now to the registered departure handler, or later to the one registered next.
         * Ignored on a stream the gateway reset itself ({@link #resetByGateway()}), whose answer was
         * decided by the gateway, and after a first departure: idempotent.
         *
         * @param cause how the response ended, as the end handler reported it
         */
        public void clientDeparted(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            Handler<Throwable> handler;
            synchronized (this) {
                if (resetByGateway || departure != null) {
                    return;
                }
                departure = cause;
                handler = departureHandler;
            }
            if (handler != null) {
                handler.handle(cause);
            }
        }

        /**
         * @return how the client left, as {@link #clientDeparted(Throwable)} recorded it, or {@code null}
         *         while it has not left
         */
        synchronized @Nullable Throwable departure() {
            return departure;
        }

        /**
         * Registers the one handler a client departure is delivered to; a departure recorded before is
         * replayed to it at once. The departure and the handler are exchanged under one monitor, so the
         * handler receives the departure exactly once, whichever of the two comes first.
         *
         * @param handler receives how the client left
         */
        void departureHandler(Handler<Throwable> handler) {
            Objects.requireNonNull(handler, "handler");
            Throwable recorded;
            synchronized (this) {
                departureHandler = handler;
                recorded = departure;
            }
            if (recorded != null) {
                handler.handle(recorded);
            }
        }

        @Override
        public synchronized ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            this.failureHandler = handler;
            if (handler != null && failure != null) {
                handler.handle(failure);
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            delegate.handler(handler);
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            delegate.pause();
            return this;
        }

        /** Resumes the inbound stream, unless the gateway reset it — then it stays paused. */
        @Override
        public ReadStream<Buffer> resume() {
            if (!resetByGateway) {
                delegate.resume();
                repinPause();
            }
            return this;
        }

        /** Fetches from the inbound stream, unless the gateway reset it — then it stays paused. */
        @Override
        public ReadStream<Buffer> fetch(long amount) {
            if (!resetByGateway) {
                delegate.fetch(amount);
                repinPause();
            }
            return this;
        }

        /** Pauses the stream again when the gateway's mark landed while it was being resumed. */
        private void repinPause() {
            if (resetByGateway) {
                delegate.pause();
            }
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            delegate.endHandler(endHandler);
            return this;
        }
    }

    /**
     * The fixed cause {@link InboundBody#resetByGateway()} delivers when the gateway itself ends an
     * HTTP/2 stream it answered while the request body was still open. It is not a
     * failure of the client: {@link ByteCappedBodyStream} stops forwarding on it without recording a
     * client abort, so it never becomes an {@link EventType#INBOUND_BODY_ABORTED}.
     * <p>
     * One shared, immutable instance: the cause carries no request-specific state, no stack trace and
     * no suppressed exceptions, and is never thrown.
     */
    static final class GatewayStreamReset extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** The one instance every gateway-made reset delivers. */
        static final GatewayStreamReset INSTANCE = new GatewayStreamReset();

        private GatewayStreamReset() {
            super("The gateway ended the request stream it answered before the body ended", null, false, false);
        }
    }

    /**
     * The failure of an attempt the dispatch stopped waiting for before its request obtained a
     * connection: the request was reset unsent (see {@link UpstreamAttempt#publish}), which happens when
     * the pool hands the released attempt a connection. When the client's departure released the
     * attempt, the dispatch reports that departure in its place, so the breaker skips it and the retry
     * predicate refuses it.
     * <p>
     * <strong>Deliberately not a {@link java.util.concurrent.CancellationException}.</strong> The
     * dispatching thread awaits the attempt through {@link java.util.concurrent.CompletableFuture#get()},
     * which reports a future that failed with a {@code CancellationException} as cancelled: it throws the
     * cancellation itself instead of wrapping it in an {@link ExecutionException}. Such a failure would
     * bypass the client-abort attribution of {@code awaitResponse} and reach the guard as an upstream
     * failure — the breaker would count every client that left while its attempt waited for a pooled
     * connection, enough of them would open it for every other caller of the upstream, and the retry
     * predicate would let the released attempt retry.
     * <p>
     * Carries no request-specific state, no stack trace and no suppressed exceptions.
     */
    static final class AttemptReleasedUnsent extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        AttemptReleasedUnsent() {
            super(ATTEMPT_ABANDONED, null, false, false);
        }
    }

    /**
     * The failure of an attempt that obtained its upstream connection with less than its hand-over grace
     * left before its pre-response cap, or only once the cap had passed: the wait for the connection used up
     * the guard's timeout but for the margin the cap leaves and less than the grace of it, so too little time
     * is left in which the client could send its request — or in which an end the client already sent could
     * be delivered before the deadline. The attempt's request is reset unsent and
     * its body never read, so nothing the client did or did not send contributes to the failure — it is the
     * upstream's. It is a {@link TimeoutException}, not a {@link GatewayException}: the breaker counts it as
     * an upstream failure, and the failure mapper reports it as {@link EventType#UPSTREAM_TIMEOUT}, exactly as
     * the guard's own timeout a moment later would have been reported. Only a dispatch that arms a
     * pre-response deadline — the gRPC dispatch — raises it.
     * <p>
     * Carries no request-specific state.
     */
    static final class UpstreamConnectionTooLate extends TimeoutException {

        @Serial
        private static final long serialVersionUID = 1L;

        UpstreamConnectionTooLate() {
            super(CONNECTION_TOO_LATE);
        }
    }

    /**
     * The failure of an attempt whose pre-response deadline passed while the upstream had stalled its request
     * stream: the upstream had stopped taking data, so its backpressure had paused the stream, and had not taken
     * any for at least the upstream stall threshold when the deadline was acted on. The stream is ended the
     * moment the upstream takes data again, exactly when a client's {@link RequestBodyDeadlineExceeded} would have
     * ended it, and the attempt's upstream request is reset. The time the call ran past its deadline was spent
     * waiting on the upstream, not on the client, so the failure is the upstream's. It is a
     * {@link TimeoutException}, not a {@link GatewayException}: the breaker counts it as an upstream failure, and
     * the failure mapper reports it as {@link EventType#UPSTREAM_TIMEOUT}, as the guard's own timeout would have
     * been had the upstream taken no more data until it fired.
     * <p>
     * <strong>Attributed to the upstream only for a sustained stall.</strong> The deadline is acted on when the
     * gateway's deadline timer finds it passed while the stream is being read, or, when it passed during a pause,
     * at the read that ends the pause. It is this failure exactly when, at that moment, the stream has been paused
     * without a break for at least the stall threshold — however slowly the client sent before the pause. A
     * deadline that passes while the stream is being read is the client's {@link RequestBodyDeadlineExceeded},
     * however long the upstream had paused the stream earlier, and so is one that passed during a pause shorter
     * than the threshold. Ordinary HTTP/2 flow control pauses the stream for a few round-trips at a time, so a
     * client that sends faster than a healthy upstream reads, and never ends its stream, keeps the deadline its
     * own: it cannot have its own deadline counted against the upstream's breaker.
     * <p>
     * Only the pre-response deadline, which only the gRPC dispatch arms, raises it. The whole-body deadline of an
     * HTTP dispatch keeps ending a body read again after it passed as the client's
     * {@link RequestBodyDeadlineExceeded}.
     * <p>
     * Carries no request-specific state.
     */
    static final class UpstreamStalledRequest extends TimeoutException {

        @Serial
        private static final long serialVersionUID = 1L;

        UpstreamStalledRequest() {
            super(STALLED_BY_UPSTREAM);
        }
    }

    /**
     * The client abort of a dispatch whose client left — reset its stream or closed its connection —
     * before the response was handed over for relay (see {@link InboundBody#clientDeparted(Throwable)}).
     * It is an {@link EventType#INBOUND_BODY_ABORTED} like a request body that did not arrive whole, told
     * apart from it by its type so it is reported with its own disposition. Like every client-ended
     * dispatch it is a gateway rejection: the breaker skips it and the retry predicate refuses it.
     *
     * @since 1.0
     */
    public static final class ClientDeparted extends GatewayException {

        @Serial
        private static final long serialVersionUID = 1L;

        ClientDeparted(Throwable cause) {
            super(EventType.INBOUND_BODY_ABORTED, CLIENT_DEPARTED, cause);
        }
    }

    /**
     * The refusal of a request body that did not end within the request-body deadline. It is raised in
     * every case the deadline ends a body:
     * <ul>
     *   <li>the deadline passes while the gateway is reading the body;</li>
     *   <li>the deadline passed while the body was not being read, because the upstream was not taking
     *       data, and the body is read again afterwards;</li>
     *   <li>the deadline passed before the body was read at all, because the attempt obtained its pooled
     *       upstream connection only after it — the wait for the connection counts against the deadline —
     *       and the body starts to be read.</li>
     * </ul>
     * In the last two cases the body is ended by that read itself, which never reaches the inbound stream.
     * On a gRPC dispatch the same refusal ends a request stream still open at its pre-response deadline as
     * long as the upstream has not answered, in the first case and in the second when the pause the deadline
     * passed in is shorter than the upstream stall threshold at the read that ends it. A pause that has lasted the
     * threshold by then was the upstream's stall, and that read ends the stream as an
     * {@link UpstreamStalledRequest}, the upstream's timeout, instead. The third does not arise
     * there: the pre-response deadline is counted from the moment the attempt hands its body over, so the wait
     * for the connection is not counted against it, and an attempt whose connection came only after its
     * pre-response cap is ended as an {@link UpstreamConnectionTooLate}, the upstream's timeout, instead.
     * The refusal is an {@link EventType#REQUEST_BODY_TIMEOUT} ({@code 408}) like the idle timeout's,
     * told apart from it by its type so it is reported with its own record: the body did not end within the
     * deadline, whether or not it was still making progress. Like every client-ended dispatch it is a
     * gateway rejection: the breaker skips it and the retry predicate refuses it.
     *
     * @since 1.0
     */
    public static final class RequestBodyDeadlineExceeded extends GatewayException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Fixed disposition of the refusal; carries nothing the client supplied. */
        private static final String BODY_PAST_DEADLINE =
                "Request body did not end within the request-body deadline; the upstream request was aborted";

        RequestBodyDeadlineExceeded() {
            super(EventType.REQUEST_BODY_TIMEOUT, BODY_PAST_DEADLINE);
        }
    }

    /**
     * The client abort of a dispatch whose next complete request message did not arrive within the message
     * bound — on a gRPC call, a client that opened the call and sent no request message, only part of one,
     * or stopped between two messages without ending its request stream. The attempt's upstream request is
     * reset when it is raised. It is an {@link EventType#INBOUND_BODY_ABORTED} like a request body that did
     * not arrive whole, told apart from it by its type so it is reported with its own disposition. Like
     * every client-ended dispatch it is a gateway rejection: the breaker skips it and the retry predicate
     * refuses it, so an idle client is never counted against a healthy upstream.
     *
     * @since 1.0
     */
    public static final class RequestMessageTimeout extends GatewayException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Fixed disposition of the abort; carries nothing the client supplied. */
        private static final String NO_COMPLETE_MESSAGE =
                "Request body sent no complete message within the message bound; the upstream request was aborted";

        RequestMessageTimeout() {
            super(EventType.INBOUND_BODY_ABORTED, NO_COMPLETE_MESSAGE);
        }
    }

    /**
     * A {@link ReadStream} decorator that forwards each request-body chunk to the upstream as it
     * arrives — never accumulating the body — while counting bytes against a ceiling. On breach it
     * aborts the in-flight upstream request and fails the stream with a
     * {@link EventType#CONTENT_TOO_LARGE} {@link GatewayException}.
     * <p>
     * <strong>An inbound failure aborts the upstream request too.</strong> When the inbound stream
     * itself fails after dispatch has begun — its chunk framing is malformed, or the client connection
     * drops mid-body — the same abort action runs before the failure is propagated. A request body
     * that did not arrive whole is therefore never completed towards the upstream: the upstream
     * request is reset at once, which on HTTP/1.x closes that upstream connection instead of returning
     * it to the pool — abortively, so the gateway's local port is freed at once rather than held in
     * {@code TIME_WAIT} — and on HTTP/2 resets the one stream. The abort action runs at most once per
     * stream, whichever trigger fires first.
     * <p>
     * <strong>A client-caused abort is recorded as such.</strong> When the trigger that fires first is
     * the client's, it leaves a {@link GatewayException} behind — {@link EventType#CONTENT_TOO_LARGE}
     * for the cap breach, {@link EventType#INBOUND_BODY_ABORTED} for the failed inbound stream —
     * readable through {@link #clientAbort()} from any thread, and set before the abort action runs.
     * The dispatch that owns this stream reports that exception in place of the transport error its
     * own abort caused. A failed inbound stream is a client-caused termination, not a security filter
     * violation: it is reported under its own event so an ordinary dropped upload raises no security
     * warning.
     * <p>
     * <strong>A gateway-made reset is not.</strong> An inbound failure carrying the
     * {@link GatewayStreamReset} cause — the edge ends the stream itself, its answer decided
     * (see {@link InboundBody#resetByGateway()}) — claims the same single abort and runs the abort
     * action, so the upstream request is reset and forwarding stops, but it records no client abort
     * ({@link #clientAbort()} stays {@code null}) and is not propagated to the consumer as a failure.
     * <p>
     * <strong>A declared length is held exactly.</strong> When the request declares a length, the
     * upstream frames the message by it, so the same running count also bounds the body to exactly
     * that many bytes. A chunk that would carry the count past the declared length is not forwarded:
     * it records a {@link EventType#SECURITY_FILTER_VIOLATION} and runs the abort action. An inbound
     * end that arrives short of the declared length is not passed on as an end: it records
     * {@link EventType#INBOUND_BODY_ABORTED} — the body did not arrive whole — and runs the abort
     * action, so the upstream never waits on, or completes, a message shorter than its framing. With
     * no declared length ({@link DispatchStage#NO_DECLARED_LENGTH}) only the {@code max_body_bytes}
     * ceiling applies.
     * <p>
     * <strong>A body that makes no progress is ended after the idle timeout.</strong> With an armed
     * {@link IdleClock}, one clock per stream measures how long the body goes without progress while the
     * gateway is reading it. It starts at {@link #startIdleClock()}, which the dispatch calls the moment
     * it hands the stream to an upstream request that has obtained its connection, and every forwarded
     * chunk restarts it — so a single value covers both the time to the first body byte and every gap
     * between chunks. It does not run:
     * <ul>
     *   <li>before it is started — waiting for a pooled upstream connection is the gateway's own
     *       queueing, not request-body progress;</li>
     *   <li>while the consumer has {@linkplain #pause() paused} the stream because the upstream is not
     *       taking data; {@link #resume()} and {@link #fetch(long)} restart the gap from that moment, so
     *       the upstream's backpressure is never charged to the request;</li>
     *   <li>after the inbound body has ended, after any abort claim — a cap breach, a framing refusal,
     *       an inbound failure or the gateway's own reset — or once {@link #stopIdleClock()} was called,
     *       which the dispatch does when the attempt's send completes.</li>
     * </ul>
     * When the timeout passes without progress, the expiry takes the stream's single abort claim with
     * an {@link EventType#REQUEST_BODY_TIMEOUT} {@link GatewayException}, pauses the inbound stream, runs
     * the abort action and propagates the failure, exactly like the other client-caused aborts. A
     * forwarded chunk only records when it arrived, so no timer is created or cancelled per chunk: one
     * timer is pending at a time, and when it fires early relative to the last progress it is re-armed
     * for the remainder instead of expiring.
     * <p>
     * <strong>A body that does not end by the deadline is ended there.</strong> A stream constructed
     * with a request-body deadline and an armed idle clock arms one more timer when
     * {@link #startIdleClock()} is called, for the deadline's moment. Unlike the idle clock it keeps running once {@link #stopIdleClock()} was called
     * — after the response head — and stops only when the inbound body ends or an abort is claimed. When
     * it fires while the gateway is reading the body, the expiry takes the single abort claim with a
     * {@link RequestBodyDeadlineExceeded}, pauses the inbound stream, hands the refusal to the
     * late-violation callback when the response was already {@linkplain #handOver handed over}, runs the
     * abort action and propagates the failure. A deadline that passes while the body is not being read
     * stays in force and ends the body the same way the moment it is read again — by a {@link #resume()},
     * a {@link #fetch(long)} or a forwarded chunk, none of which then reaches the inbound stream. That
     * covers a stream the consumer had paused because the upstream was not taking data, and a deadline
     * that had already passed at {@link #startIdleClock()} because the connection was obtained only after
     * it. {@link #stopClocks()} — the end of the body, an abort, or an attempt the dispatch no longer waits
     * for — cancels every timer and drops a passed deadline: a stopped clock reports nothing.
     * <p>
     * <strong>A body whose next complete message does not arrive is ended at the message bound.</strong> A
     * stream constructed with an armed message clock arms one more timer at {@link #startMessageClock()},
     * for the bound's length. While the clock runs, a {@link GrpcMessageFrames} reads the gRPC message
     * frames from each chunk as it crosses — each 5-byte prefix one byte at a time, then a count of the
     * payload bytes the prefix declares — with no buffer and no copy of the payload; a prefix and a payload
     * may straddle any number of chunks, and one chunk may carry the end of one message and the start of
     * the next. A forwarded chunk that completes at least one message starts the gap to the next complete
     * message (a message of declared length zero is complete at its prefix); a chunk that completes none,
     * however many bytes it carries, does not. Like the idle clock, the message clock does not count a pause
     * by the consumer — the upstream not taking data is never charged to the client — and a resume starts
     * the gap again. Only the gap's start is recorded: one timer is pending at a time, and when it fires
     * early relative to the start of the current gap it is re-armed for the remainder — so no timer is
     * created or cancelled per chunk. The inbound end, any abort claim, {@link #stopMessageClock()} — which
     * the dispatch calls when the attempt's send completes — and {@link #stopClocks()} stop the clock for
     * good. A declared length the body cannot carry within {@code max_body_bytes} is not refused by the
     * tracker: the body's cumulative cap refuses it as the bytes arrive. When a gap lasts the whole bound,
     * the expiry takes the single abort claim with a {@link RequestMessageTimeout}
     * ({@link EventType#INBOUND_BODY_ABORTED}), pauses the inbound stream, runs the abort action and
     * propagates the failure, exactly like the other client-caused aborts. The claim is taken under the
     * clocks' monitor, the same monitor the completing chunk starts the next gap under and the inbound end
     * stops the clock under, and the completion is checked before that chunk is forwarded, so either the
     * expiry wins and the chunk or the end is dropped, or the chunk or the end wins and the expiry reports
     * nothing.
     * <p>
     * <strong>With the message clock, the deadline is a pre-response deadline.</strong> A stream whose idle
     * clock is unarmed and whose message clock is armed arms its request-body deadline at
     * {@link #startMessageClock()}, through the message clock's scheduler. It ends the body when the
     * whole-body deadline would — at once while the body is read, or at the next read when it passed while
     * the consumer had paused the stream — but {@link #stopMessageClock()} stops it as well, so it never
     * fires once the attempt's send has completed, and therefore never after the hand-over. Unlike the
     * whole-body deadline, it can be charged to the upstream, but only for a sustained stall: when it is acted
     * on — at once when it passes while the stream is being read, at the next read when it passed during a pause
     * — it is the upstream's {@link UpstreamStalledRequest}, readable through {@link #upstreamStall()} and never a
     * client abort, exactly when the consumer has had the stream paused without a break, because the upstream
     * was not taking data, for at least the upstream stall threshold at that moment. Every other passed
     * pre-response deadline is the client's {@link RequestBodyDeadlineExceeded}: one that passes while the stream
     * is being read, whatever pauses came before, and one that passed during a pause shorter than the threshold —
     * the brief flow-control waits of a client sending faster than a healthy upstream reads. A resume ends the
     * pause, so the next pause measures its stall from its own start.
     * <p>
     * Thread-safe: chunks arrive on the inbound connection's event loop, the consumer pauses and
     * resumes from either loop, and the timers fire on the loop they were scheduled from. The last
     * progress time is a volatile field; the rest of the clocks' state is held under one monitor, and
     * each expiry competes with every other abort through the atomic claim. The deadline's claim and its
     * choice between failing the dispatch and the late-violation callback are made under that monitor,
     * as is the hand-over, so a deadline refusal takes exactly one of them.
     */
    static final class ByteCappedBodyStream implements ReadStream<Buffer> {

        /** Fixed disposition of a failed inbound body; carries nothing the client supplied. */
        static final String INBOUND_BODY_FAILED =
                "Request body failed before it arrived whole; the upstream request was aborted";

        /** Fixed disposition of a body ending short of its declared length. */
        private static final String INBOUND_BODY_SHORT =
                "Request body ended before its declared length; the upstream request was aborted";

        /** Fixed disposition of a body running past its declared length. */
        private static final String BODY_BEYOND_DECLARED_LENGTH =
                "Framing rejected: request body exceeded its declared length; the upstream request was aborted";

        /** Fixed disposition of a body that made no progress for the idle timeout. */
        private static final String BODY_IDLE =
                "Request body made no progress within the request-body idle timeout; the upstream request was aborted";

        /** The pending-timer value while no idle timer is pending. */
        private static final long NO_TIMER = -1L;

        /** Added before a nanosecond remainder is converted, so the re-armed delay rounds up. */
        private static final long MILLIS_ROUNDING_NANOS = TimeUnit.MILLISECONDS.toNanos(1) - 1;

        /** What {@link #resume()} and {@link #fetch(long)} run inside a read's window: nothing. */
        private static final Runnable NOTHING_IN_THE_READ_WINDOW = () -> {
            // Only a test places something between the first passed-deadline check and the clock's resume.
        };

        private final ReadStream<Buffer> delegate;
        private final long maxBytes;
        private final long declaredLength;
        private final Runnable abortAction;
        private final LongConsumer bytesForwarded;
        private final IdleClock idleClock;
        /** The {@link IdleClock#nanoTime()} reading the request-body deadline passes at; empty for none. */
        private final OptionalLong deadlineNanos;
        private long bytesSeen;
        /** When the body last made progress: the clock started, a chunk was forwarded, or it resumed. */
        private volatile long lastProgressNanos;
        /** Guards the idle clock's state below. */
        private final Object clockLock = new Object();
        private boolean clockStarted;
        private boolean clockStopped;
        private boolean pausedByConsumer;
        private long pendingTimer = NO_TIMER;
        /**
         * Whether the deadline can no longer fire: the body ended, an abort was claimed, the clocks were
         * stopped, or the deadline ended the body.
         */
        private boolean deadlineEnded;
        /**
         * Whether the deadline passed while the body was not being read — paused by the consumer, or not
         * read yet because the attempt obtained its connection only after the deadline — so the body is
         * ended the moment it is read again. Written holding {@link #clockLock}; volatile so a forwarded
         * chunk can read it without the monitor.
         */
        private volatile boolean deadlinePassed;
        /**
         * Whether a pre-response deadline that passed during a pause by the consumer is the upstream's, decided by
         * the read that ends that pause from the pause's length at that moment: the read's second passed-deadline
         * check, which runs once the pause has ended, then ends the body as the upstream's
         * {@link UpstreamStalledRequest} rather than the client's {@link RequestBodyDeadlineExceeded}. Never set
         * for the whole-body deadline. Written and read holding {@link #clockLock}.
         */
        private boolean deadlinePassedHeldByUpstream;
        /**
         * How long, in nanoseconds, the consumer must have paused the stream without a break — the upstream not
         * taking data — when a passed pre-response deadline is acted on, for it to be the upstream's; zero for a
         * stream that arms no pre-response deadline.
         */
        private final long upstreamStallNanos;
        /**
         * The {@link #deadlineClock}'s reading when the current pause by the consumer began; meaningful only while
         * {@link #pausedByConsumer} holds, and recorded only for a pre-response deadline. A pause that is already
         * in force is not restarted by another pause; a resume ends it. Written and read holding
         * {@link #clockLock}.
         */
        private long pauseStartNanos;
        private long deadlineTimer = NO_TIMER;
        /** The message clock, {@link IdleClock#UNARMED} for a body whose messages are not awaited. */
        private final IdleClock messageClock;
        /**
         * The clock the request-body deadline is scheduled and measured with: the idle clock when it is
         * armed — the whole-body deadline — otherwise the message clock — the pre-response deadline.
         */
        private final IdleClock deadlineClock;
        /** Whether {@link #startMessageClock()} ran; written holding {@link #clockLock}. */
        private boolean messageClockStarted;
        /**
         * Whether the message clock can no longer fire: the body ended, an abort was claimed, the clocks
         * were stopped, the attempt's send completed, or the bound expired. Written holding
         * {@link #clockLock}; volatile so chunk delivery can skip the frame tracker without the monitor.
         */
        private volatile boolean messageClockEnded;
        private long messageTimer = NO_TIMER;
        /**
         * When the current gap began: the message clock started, a complete message was forwarded, or the
         * consumer resumed the stream. Written holding {@link #clockLock}.
         */
        private long messageGapStartNanos;
        /**
         * Reads the gRPC message frames as their chunks cross. Used by chunk delivery only, which runs on
         * the inbound connection's event loop.
         */
        private final GrpcMessageFrames messageFrames = new GrpcMessageFrames();
        /** Receives a deadline refusal once the response was handed over; {@code null} until then. */
        private @Nullable Consumer<GatewayException> lateViolation;
        /** Whether the inbound body's end arrived, whatever was made of it. */
        private volatile boolean inboundEnded;
        private @Nullable Handler<Buffer> dataHandler;
        private @Nullable Handler<Throwable> failureHandler;
        private @Nullable Handler<Void> endHandler;
        /**
         * The single abort claim: the trigger whose {@code compareAndSet} from {@code null} succeeds owns
         * the abort and leaves its reason behind — a {@link GatewayException} for a client-caused abort,
         * the {@link GatewayStreamReset} cause for a gateway-made reset. The inbound failure may be
         * replayed on the thread that registers the failure handler while chunks arrive on the event
         * loop, so the claim must be atomic across both callbacks.
         */
        private final AtomicReference<@Nullable Throwable> abortReason = new AtomicReference<>();

        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, Runnable abortAction) {
            this(delegate, maxBytes, NO_DECLARED_LENGTH, abortAction, length -> {
            });
        }

        /**
         * A stream whose idle clock is never armed.
         *
         * @param delegate       the inbound request body
         * @param maxBytes       the {@code max_body_bytes} ceiling
         * @param declaredLength the checked declared length the body is held to exactly, or a
         *                       negative value when the request declares none
         * @param abortAction    resets the upstream request
         * @param bytesForwarded receives the length of every chunk forwarded to the upstream
         */
        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, long declaredLength, Runnable abortAction,
                LongConsumer bytesForwarded) {
            this(delegate, maxBytes, declaredLength, abortAction, bytesForwarded, IdleClock.UNARMED);
        }

        /**
         * A stream without a request-body deadline.
         *
         * @param delegate       the inbound request body
         * @param maxBytes       the {@code max_body_bytes} ceiling
         * @param declaredLength the checked declared length the body is held to exactly, or a
         *                       negative value when the request declares none
         * @param abortAction    resets the upstream request
         * @param bytesForwarded receives the length of every chunk forwarded to the upstream
         * @param idleClock      the request-body idle clock, {@link IdleClock#UNARMED} for none; it
         *                       runs only once {@link #startIdleClock()} is called
         */
        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, long declaredLength, Runnable abortAction,
                LongConsumer bytesForwarded, IdleClock idleClock) {
            this(delegate, maxBytes, declaredLength, abortAction, bytesForwarded, idleClock, OptionalLong.empty());
        }

        /**
         * A stream whose messages are not awaited: the message clock is never armed.
         *
         * @param delegate       the inbound request body
         * @param maxBytes       the {@code max_body_bytes} ceiling
         * @param declaredLength the checked declared length the body is held to exactly, or a
         *                       negative value when the request declares none
         * @param abortAction    resets the upstream request
         * @param bytesForwarded receives the length of every chunk forwarded to the upstream
         * @param idleClock      the request-body idle clock, {@link IdleClock#UNARMED} for none; it
         *                       runs only once {@link #startIdleClock()} is called
         * @param deadlineNanos  the {@code idleClock}'s {@link IdleClock#nanoTime()} reading at which the
         *                       request-body deadline passes, or empty for none; its timer is scheduled
         *                       through the idle clock's scheduler once {@link #startIdleClock()} is
         *                       called; a deadline needs an armed {@code idleClock}, which the
         *                       dispatch's constructor guarantees
         */
        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, long declaredLength, Runnable abortAction,
                LongConsumer bytesForwarded, IdleClock idleClock, OptionalLong deadlineNanos) {
            this(delegate, maxBytes, declaredLength, abortAction, bytesForwarded, idleClock, deadlineNanos,
                    IdleClock.UNARMED, NO_UPSTREAM_STALL);
        }

        /**
         * @param delegate          the inbound request body
         * @param maxBytes          the {@code max_body_bytes} ceiling
         * @param declaredLength    the checked declared length the body is held to exactly, or a
         *                          negative value when the request declares none
         * @param abortAction       resets the upstream request
         * @param bytesForwarded    receives the length of every chunk forwarded to the upstream
         * @param idleClock         the request-body idle clock, {@link IdleClock#UNARMED} for none; it
         *                          runs only once {@link #startIdleClock()} is called
         * @param deadlineNanos     the {@link IdleClock#nanoTime()} reading, of the clock it is armed with,
         *                          at which the request-body deadline passes, or empty for none. With an
         *                          armed {@code idleClock} it is the whole-body deadline, scheduled through
         *                          the idle clock's scheduler once {@link #startIdleClock()} is called;
         *                          otherwise it is the pre-response deadline, scheduled through the message
         *                          clock's scheduler once {@link #startMessageClock()} is called and
         *                          stopped by {@link #stopMessageClock()}
         * @param messageClock      the message clock, {@link IdleClock#UNARMED} for none; its one pending
         *                          timer, for the clock's timeout, is scheduled through its own scheduler
         *                          once {@link #startMessageClock()} is called and again after every
         *                          complete message
         * @param upstreamStallMillis how long, in milliseconds, the consumer must have paused the stream without
         *                          a break when a passed pre-response deadline is acted on, for the deadline to
         *                          be the upstream's {@link UpstreamStalledRequest}; positive when the deadline
         *                          is a pre-response deadline, otherwise {@link #NO_UPSTREAM_STALL}, and never
         *                          read
         * @throws IllegalArgumentException when a deadline is given and neither clock is armed, or a pre-response
         *                                  deadline is given without a positive upstream stall threshold
         */
        @SuppressWarnings("java:S107") // internal stream; the three clocks and the stall threshold are distinct per-attempt bounds built separately by the dispatch, and a holder type would only re-bundle them for this one call
        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, long declaredLength, Runnable abortAction,
                LongConsumer bytesForwarded, IdleClock idleClock, OptionalLong deadlineNanos,
                IdleClock messageClock, long upstreamStallMillis) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.declaredLength = declaredLength;
            this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
            this.bytesForwarded = Objects.requireNonNull(bytesForwarded, "bytesForwarded");
            this.idleClock = Objects.requireNonNull(idleClock, "idleClock");
            this.deadlineNanos = Objects.requireNonNull(deadlineNanos, "deadlineNanos");
            this.messageClock = Objects.requireNonNull(messageClock, "messageClock");
            this.deadlineClock = idleClock.armed() ? idleClock : messageClock;
            if (deadlineNanos.isPresent() && !deadlineClock.armed()) {
                throw new IllegalArgumentException("a request-body deadline needs an armed idle or message clock");
            }
            if (preResponseDeadline() && upstreamStallMillis <= NO_UPSTREAM_STALL) {
                throw new IllegalArgumentException(
                        "a pre-response deadline needs a positive upstream stall threshold: " + upstreamStallMillis);
            }
            this.upstreamStallNanos = TimeUnit.MILLISECONDS.toNanos(upstreamStallMillis);
            delegate.exceptionHandler(this::onInboundFailure);
            delegate.handler(this::onChunk);
        }

        /**
         * The inbound stream failed: abort the upstream request first, then report the failure, so the
         * consumer never sees a failed body whose upstream request is still open. The
         * {@link GatewayStreamReset} cause is the gateway's own reset, not a failure: it aborts the
         * upstream request through the same single claim, records no client abort and is not reported.
         */
        private void onInboundFailure(Throwable failure) {
            if (failure instanceof GatewayStreamReset gatewayReset) {
                if (claimAbort(gatewayReset)) {
                    abortAction.run();
                }
                return;
            }
            if (claimAbort(new GatewayException(EventType.INBOUND_BODY_ABORTED, INBOUND_BODY_FAILED, failure))) {
                abortAction.run();
            }
            propagateFailure(failure);
        }

        /**
         * Takes the single abort claim for {@code reason}; the trigger that wins it also stops the idle
         * clock, the deadline and the message clock, so nothing expires after an abort.
         *
         * @return {@code true} when this call won the claim
         */
        private boolean claimAbort(Throwable reason) {
            if (abortReason.compareAndSet(null, reason)) {
                stopClocks();
                return true;
            }
            return false;
        }

        /**
         * @return the client-caused reason this stream aborted the upstream request, or {@code null}
         *         while it has not aborted it or when the gateway's own reset aborted it
         */
        @Nullable
        GatewayException clientAbort() {
            return abortReason.get() instanceof GatewayException clientCaused ? clientCaused : null;
        }

        /**
         * @return the upstream's stall this stream aborted the upstream request for — a pre-response deadline
         *         acted on while the upstream had held the stream paused for at least the stall threshold — or
         *         {@code null} when it did not abort it for that
         */
        @Nullable
        UpstreamStalledRequest upstreamStall() {
            return abortReason.get() instanceof UpstreamStalledRequest stalled ? stalled : null;
        }

        /** @return {@code true} once the inbound body's end has arrived, whatever was made of it */
        boolean inboundEnded() {
            return inboundEnded;
        }

        private void onChunk(Buffer chunk) {
            if (abortReason.get() != null || endAtPassedDeadline()) {
                return;
            }
            bytesSeen += chunk.length();
            if (declaredLength >= 0 && bytesSeen > declaredLength) {
                refuse(new GatewayException(EventType.SECURITY_FILTER_VIOLATION, BODY_BEYOND_DECLARED_LENGTH));
                return;
            }
            if (bytesSeen > maxBytes) {
                refuse(new GatewayException(EventType.CONTENT_TOO_LARGE,
                        "Request body exceeded max_body_bytes=" + maxBytes));
                return;
            }
            if (messageBoundExpired(chunk)) {
                // The message bound or another abort ended the body before this chunk.
                return;
            }
            // The chunk cleared every bound and is about to cross to the upstream — record it so the
            // stream-aware retry gate can see that a body byte has been sent on this attempt.
            bytesForwarded.accept(chunk.length());
            if (idleClock.armed()) {
                // Only the arrival is recorded; the pending timer re-checks it when it fires.
                lastProgressNanos = idleClock.nanoTime().getAsLong();
            }
            if (dataHandler != null) {
                dataHandler.handle(chunk);
            }
        }

        /**
         * The inbound stream ended, which stops the idle clock, the deadline and the message clock.
         * A body short of its declared length did not arrive whole: the upstream request is aborted
         * instead of being ended on a message shorter than its framing. After an abort the end is not
         * passed on — the consumer has already been told the body failed, or the gateway reset the stream
         * itself. The clocks are stopped before the abort is read, so an expiry the stop did not prevent
         * has already claimed the abort and is seen here.
         */
        private void onInboundEnd(@Nullable Void end) {
            inboundEnded = true;
            stopClocks();
            if (abortReason.get() != null) {
                return;
            }
            if (declaredLength >= 0 && bytesSeen < declaredLength) {
                GatewayException shortBody = new GatewayException(EventType.INBOUND_BODY_ABORTED, INBOUND_BODY_SHORT);
                if (claimAbort(shortBody)) {
                    abortAction.run();
                    propagateFailure(shortBody);
                }
                return;
            }
            Handler<Void> handler = endHandler;
            if (handler != null) {
                handler.handle(end);
            }
        }

        /** Stops the inbound stream, and — when this is the first abort — resets the upstream request. */
        private void refuse(GatewayException reason) {
            delegate.pause();
            if (claimAbort(reason)) {
                abortAction.run();
                propagateFailure(reason);
            }
        }

        /**
         * Starts the idle clock: from now on, a gap of the clock's timeout without progress ends the
         * stream as {@link EventType#REQUEST_BODY_TIMEOUT}. Arms the request-body deadline at the same
         * moment; when it has already passed, the body is ended as soon as the consumer starts to read it.
         * A no-op for an unarmed clock, a clock already started, and one already stopped.
         */
        void startIdleClock() {
            if (!idleClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                if (clockStarted || clockStopped) {
                    return;
                }
                clockStarted = true;
                lastProgressNanos = idleClock.nanoTime().getAsLong();
                if (!pausedByConsumer) {
                    armTimer(idleClock.timeoutMillis());
                }
                armDeadline(lastProgressNanos);
            }
        }

        /**
         * Arms the request-body deadline, when there is one and it has not ended; called holding
         * {@link #clockLock} by whichever clock it is armed with. When it has already passed — the
         * connection was obtained only after it — the body is ended the moment the consumer starts to read
         * it.
         *
         * @param nowNanos the {@link #deadlineClock}'s current reading
         */
        private void armDeadline(long nowNanos) {
            if (deadlineNanos.isEmpty() || deadlineEnded) {
                return;
            }
            long remainingNanos = deadlineNanos.getAsLong() - nowNanos;
            if (remainingNanos > 0) {
                armDeadlineTimer(remainingNanos);
            } else {
                deadlinePassed = true;
            }
        }

        /**
         * Stops the idle clock for good and cancels its pending timer; the request-body deadline keeps
         * running. Idempotent; a no-op for an unarmed clock.
         */
        void stopIdleClock() {
            if (!idleClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                clockStopped = true;
                if (pendingTimer != NO_TIMER) {
                    idleClock.scheduler().cancel(pendingTimer);
                    pendingTimer = NO_TIMER;
                }
            }
        }

        /**
         * Stops the idle clock, the request-body deadline and the message clock for good and cancels their
         * timers: the body ended or was aborted, or its attempt is no longer waited for. A stopped clock
         * reports nothing — a deadline that had already passed unread is dropped with it. Idempotent.
         */
        private void stopClocks() {
            stopIdleClock();
            stopMessageClock();
            if (deadlineNanos.isEmpty()) {
                return;
            }
            synchronized (clockLock) {
                endDeadline();
            }
        }

        /**
         * Ends the request-body deadline for good: cancels its timer and drops a passed deadline; called
         * holding {@link #clockLock}. Idempotent.
         */
        private void endDeadline() {
            deadlineEnded = true;
            deadlinePassed = false;
            deadlinePassedHeldByUpstream = false;
            if (deadlineTimer != NO_TIMER) {
                deadlineClock.scheduler().cancel(deadlineTimer);
                deadlineTimer = NO_TIMER;
            }
        }

        /**
         * Hands the response over for relay: a deadline refusal from now on goes to
         * {@code lateViolation} before the upstream request is reset, instead of failing the dispatch. The
         * upstream's {@link UpstreamStalledRequest} is not a refusal and is never returned here: it is read
         * through {@link #upstreamStall()}, and it cannot arise after the hand-over.
         *
         * @param lateViolation receives a deadline refusal that comes after the hand-over
         * @return the deadline refusal or the message timeout that came before the hand-over, or
         *         {@code null} when there was none
         */
        @Nullable
        GatewayException handOver(Consumer<GatewayException> lateViolation) {
            synchronized (clockLock) {
                this.lateViolation = lateViolation;
                return switch (abortReason.get()) {
                    case RequestBodyDeadlineExceeded exceeded -> exceeded;
                    case RequestMessageTimeout idleClient -> idleClient;
                    case null, default -> null;
                };
            }
        }

        /**
         * Starts the message clock: from now on, the clock's timeout passing before the next complete
         * message was forwarded ends the stream as a {@link RequestMessageTimeout}. On a stream whose idle
         * clock is unarmed, arms the request-body deadline — the pre-response deadline — at the same moment.
         * A no-op for an unarmed clock, a clock already started, and one already stopped.
         */
        void startMessageClock() {
            if (!messageClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                if (messageClockStarted || messageClockEnded) {
                    return;
                }
                messageClockStarted = true;
                messageGapStartNanos = messageClock.nanoTime().getAsLong();
                if (!pausedByConsumer) {
                    armMessageTimer(messageClock.timeoutMillis());
                }
                if (!idleClock.armed()) {
                    armDeadline(messageGapStartNanos);
                }
            }
        }

        /**
         * Stops the message clock for good and cancels its timer; on a stream whose idle clock is unarmed it
         * ends the pre-response deadline as well. The dispatch calls it when the attempt's send completes —
         * the upstream answered or the send failed. Idempotent; a no-op for an unarmed clock.
         */
        void stopMessageClock() {
            if (!messageClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                messageClockEnded = true;
                cancelMessageTimer();
                if (!idleClock.armed()) {
                    endDeadline();
                }
            }
        }

        /**
         * A chunk is about to be forwarded while the message clock runs: the frame tracker reads the
         * chunk's bytes, and a chunk that completes at least one message re-arms the clock for the next one
         * — unless an expiry came first.
         *
         * @param chunk the chunk about to be forwarded
         * @return {@code true} when an abort had already ended the body — the chunk must not be forwarded
         */
        private boolean messageBoundExpired(Buffer chunk) {
            if (!messageClock.armed()) {
                return false;
            }
            if (!messageClockEnded && messageFrames.completedBy(chunk) > 0) {
                // The expiry claims the abort under the same monitor the re-arm takes, so once the re-arm
                // returns, an expiry that came first has already recorded its claim.
                restartMessageClock();
            }
            return abortReason.get() != null;
        }

        /**
         * A complete message was forwarded: the gap to the next complete message starts now. Only the start
         * is recorded; the pending timer re-checks it when it fires. A no-op for a clock not started yet or
         * already ended.
         */
        private void restartMessageClock() {
            synchronized (clockLock) {
                if (!messageClockStarted || messageClockEnded) {
                    return;
                }
                messageGapStartNanos = messageClock.nanoTime().getAsLong();
                if (messageTimer == NO_TIMER && !pausedByConsumer) {
                    armMessageTimer(messageClock.timeoutMillis());
                }
            }
        }

        /**
         * The consumer resumed the stream: the upstream is taking data again, so the gap restarts from now
         * and a timer is pending again; called holding {@link #clockLock}. A no-op for a clock not started
         * yet or already ended.
         */
        private void resumeMessageClock() {
            if (!messageClock.armed() || !messageClockStarted || messageClockEnded) {
                return;
            }
            messageGapStartNanos = messageClock.nanoTime().getAsLong();
            if (messageTimer == NO_TIMER) {
                armMessageTimer(messageClock.timeoutMillis());
            }
        }

        /** Schedules the one pending message timer; called holding {@link #clockLock}. */
        private void armMessageTimer(long delayMillis) {
            messageTimer = messageClock.scheduler().schedule(delayMillis, this::onMessageTimer);
        }

        /** Cancels the pending message timer, if any; called holding {@link #clockLock}. */
        private void cancelMessageTimer() {
            if (messageTimer != NO_TIMER) {
                messageClock.scheduler().cancel(messageTimer);
                messageTimer = NO_TIMER;
            }
        }

        /**
         * The pending message timer fired. A stopped clock, and one whose stream the consumer has paused
         * because the upstream is not taking data, does nothing — a resume arms a new timer. A gap that began
         * less than the bound ago — a complete message was forwarded, or the stream resumed, since the timer
         * was armed — re-arms for the remainder. Otherwise the next complete message did not arrive within the
         * bound: the stream is refused as a {@link RequestMessageTimeout} through the single abort claim,
         * taken under the clocks' monitor. Outside it, the inbound stream is paused, the upstream request
         * reset and the failure propagated.
         */
        private void onMessageTimer() {
            RequestMessageTimeout idleClient;
            synchronized (clockLock) {
                messageTimer = NO_TIMER;
                if (messageClockEnded || pausedByConsumer) {
                    return;
                }
                long gapNanos = messageClock.nanoTime().getAsLong() - messageGapStartNanos;
                long remainingNanos = TimeUnit.MILLISECONDS.toNanos(messageClock.timeoutMillis()) - gapNanos;
                if (remainingNanos > 0) {
                    armMessageTimer(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + MILLIS_ROUNDING_NANOS)));
                    return;
                }
                messageClockEnded = true;
                idleClient = new RequestMessageTimeout();
                if (!claimAbort(idleClient)) {
                    return;
                }
            }
            delegate.pause();
            abortAction.run();
            propagateFailure(idleClient);
        }

        /** Schedules the one pending deadline timer; called holding {@link #clockLock}. */
        private void armDeadlineTimer(long remainingNanos) {
            deadlineTimer = deadlineClock.scheduler().schedule(
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + MILLIS_ROUNDING_NANOS)),
                    this::onDeadlineTimer);
        }

        /**
         * The deadline timer fired. One that fired early re-arms for the remainder. Once the deadline has
         * passed it ends the body: at once, by refusing it as a {@link RequestBodyDeadlineExceeded}
         * through the single abort claim — handed to the late-violation callback first when the response
         * was already handed over — or, while the consumer has paused the stream because the upstream is
         * not taking data, the moment the body is read again. Whose failure a pre-response deadline that
         * passed during such a pause is, that read decides (see {@link #claimDeadlineExpiry()}); a whole-body
         * deadline is ended as the client's either way.
         */
        private void onDeadlineTimer() {
            @Nullable DeadlineExpiry expiry;
            synchronized (clockLock) {
                deadlineTimer = NO_TIMER;
                if (deadlineEnded) {
                    return;
                }
                long remainingNanos = deadlineNanos.orElseThrow() - deadlineClock.nanoTime().getAsLong();
                if (remainingNanos > 0) {
                    armDeadlineTimer(remainingNanos);
                    return;
                }
                if (pausedByConsumer) {
                    // The upstream is not taking data: the deadline stays in force, and the body is ended
                    // the moment the consumer reads it again — by the length the pause has reached then.
                    deadlinePassed = true;
                    return;
                }
                expiry = claimDeadlineExpiry();
            }
            if (expiry != null) {
                expire(expiry);
            }
        }

        /**
         * Ends the body at a deadline that passed while it was not being read, now that it is read again —
         * on the same path as an expiry on time, as the upstream's {@link UpstreamStalledRequest} when it was a
         * pre-response deadline and the upstream had held the stream for at least the stall threshold.
         *
         * @return {@code true} when this call ended the body: the read must not reach the inbound stream
         */
        private boolean endAtPassedDeadline() {
            if (!deadlinePassed) {
                return false;
            }
            @Nullable DeadlineExpiry expiry;
            synchronized (clockLock) {
                if (!deadlinePassed) {
                    return false;
                }
                expiry = claimDeadlineExpiry();
            }
            if (expiry == null) {
                return false;
            }
            expire(expiry);
            return true;
        }

        /**
         * Ends the deadline and takes the single abort claim for its expiry — the upstream's
         * {@link UpstreamStalledRequest} when it is a pre-response deadline and the upstream has held the stream
         * without a break for at least the stall threshold at this moment, the moment the deadline is acted on;
         * the client's {@link RequestBodyDeadlineExceeded} otherwise. A pause shorter than the threshold —
         * ordinary flow control, a client sending faster than a healthy upstream reads — leaves the deadline the
         * client's, as does a deadline that passes while the stream is being read. Called holding
         * {@link #clockLock}, so the claim and the choice of the late-violation callback are made together with
         * the hand-over.
         *
         * @return the expiry to carry out, or {@code null} when another abort was claimed first
         */
        private @Nullable DeadlineExpiry claimDeadlineExpiry() {
            Exception reason = deadlinePassedHeldByUpstream || upstreamStalledPastThreshold()
                    ? new UpstreamStalledRequest()
                    : new RequestBodyDeadlineExceeded();
            deadlineEnded = true;
            deadlinePassed = false;
            deadlinePassedHeldByUpstream = false;
            if (!claimAbort(reason)) {
                return null;
            }
            return new DeadlineExpiry(reason, lateViolation);
        }

        /**
         * Carries out a claimed deadline expiry, outside the monitor: pauses the inbound stream, tells the
         * edge when the response is already being relayed, resets the upstream request and propagates the
         * failure. Only the client's refusal can meet a late-violation callback: the upstream's stall arises
         * on the pre-response deadline alone, which ends when the attempt's send completes, before the
         * hand-over.
         */
        private void expire(DeadlineExpiry expiry) {
            delegate.pause();
            Consumer<GatewayException> late = expiry.lateViolation();
            if (late != null && expiry.reason() instanceof GatewayException refusal) {
                late.accept(refusal);
            }
            abortAction.run();
            propagateFailure(expiry.reason());
        }

        /**
         * A deadline expiry whose abort claim was won.
         *
         * @param reason        the reason recorded as the stream's abort: the client's
         *                      {@link RequestBodyDeadlineExceeded} or the upstream's {@link UpstreamStalledRequest}
         * @param lateViolation the late-violation callback when the response was already handed over, or
         *                      {@code null} when the expiry is the dispatch's own failure
         */
        private record DeadlineExpiry(Exception reason, @Nullable Consumer<GatewayException> lateViolation) {
        }

        /**
         * @return {@code true} when a pause by the consumer matters to a clock: neither the idle clock nor
         *         the message clock counts it, and a deadline passing during it ends the body only at the
         *         next read
         */
        private boolean tracksPause() {
            return idleClock.armed() || messageClock.armed();
        }

        /**
         * @return {@code true} when the request-body deadline is a pre-response deadline: armed with the message
         *         clock because no idle clock is — the only deadline that can be charged to the upstream
         */
        private boolean preResponseDeadline() {
            return deadlineNanos.isPresent() && !idleClock.armed();
        }

        /**
         * @return {@code true} when the stream's pre-response deadline is the upstream's to answer for: the
         *         consumer has the stream paused — the upstream is not taking data — and has had it paused without
         *         a break for at least the stall threshold; called holding {@link #clockLock}
         */
        private boolean upstreamStalledPastThreshold() {
            return pausedByConsumer && preResponseDeadline()
                    && deadlineClock.nanoTime().getAsLong() - pauseStartNanos >= upstreamStallNanos;
        }

        /**
         * The consumer paused the stream: no gap is counted until it resumes. A pause already in force keeps the
         * moment it began, so the stall it measures is the whole unbroken pause.
         */
        private void suspendClocks() {
            if (!tracksPause()) {
                return;
            }
            synchronized (clockLock) {
                if (!pausedByConsumer && preResponseDeadline()) {
                    pauseStartNanos = deadlineClock.nanoTime().getAsLong();
                }
                pausedByConsumer = true;
            }
        }

        /**
         * The consumer resumed the stream: every gap restarts from now, and its timer is pending again. A deadline
         * that passed during the pause now ending is decided by the pause's length first, as the pause ends, for
         * the read's second passed-deadline check to act on; the next pause measures its stall from its own start.
         */
        private void resumeClocks() {
            if (!tracksPause()) {
                return;
            }
            synchronized (clockLock) {
                if (deadlinePassed) {
                    deadlinePassedHeldByUpstream = upstreamStalledPastThreshold();
                }
                pausedByConsumer = false;
                resumeMessageClock();
                if (!idleClock.armed() || !clockStarted || clockStopped) {
                    return;
                }
                lastProgressNanos = idleClock.nanoTime().getAsLong();
                if (pendingTimer == NO_TIMER) {
                    armTimer(idleClock.timeoutMillis());
                }
            }
        }

        /** Schedules the one pending idle timer; called holding {@link #clockLock}. */
        private void armTimer(long delayMillis) {
            pendingTimer = idleClock.scheduler().schedule(delayMillis, this::onIdleTimer);
        }

        /**
         * The pending idle timer fired. A stopped or paused clock does nothing — a resume arms a new
         * timer. A clock whose last progress lies less than the timeout back re-arms for the remainder;
         * otherwise the body has made no progress for the whole timeout and the stream is refused.
         */
        private void onIdleTimer() {
            synchronized (clockLock) {
                pendingTimer = NO_TIMER;
                if (clockStopped || pausedByConsumer) {
                    return;
                }
                long idleNanos = idleClock.nanoTime().getAsLong() - lastProgressNanos;
                long remainingNanos = TimeUnit.MILLISECONDS.toNanos(idleClock.timeoutMillis()) - idleNanos;
                if (remainingNanos > 0) {
                    armTimer(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos + MILLIS_ROUNDING_NANOS)));
                    return;
                }
                clockStopped = true;
            }
            // Outside the monitor: the refusal pauses the inbound stream, resets the upstream request and
            // propagates the failure, all through the single abort claim.
            refuse(new GatewayException(EventType.REQUEST_BODY_TIMEOUT, BODY_IDLE));
        }

        private void propagateFailure(Throwable failure) {
            if (failureHandler != null) {
                failureHandler.handle(failure);
            }
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            this.dataHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            this.failureHandler = handler;
            return this;
        }

        /** Pauses the inbound stream; neither the idle clock nor the message clock counts the pause. */
        @Override
        public ReadStream<Buffer> pause() {
            suspendClocks();
            delegate.pause();
            return this;
        }

        /**
         * Resumes the inbound stream; the idle clock and the message clock restart their gaps from now.
         * When the request-body deadline passed while the body was not being read, the body is ended instead
         * and the inbound stream stays paused.
         */
        @Override
        public ReadStream<Buffer> resume() {
            return resume(NOTHING_IN_THE_READ_WINDOW);
        }

        /**
         * {@link #resume()}, running {@code inReadWindow} after the read's first passed-deadline check and
         * before the idle clock resumes (see {@link #readMayProceed}). Package-private so a test can place a
         * deadline timer in that window; {@link #resume()} runs nothing there.
         */
        ReadStream<Buffer> resume(Runnable inReadWindow) {
            if (readMayProceed(inReadWindow)) {
                delegate.resume();
            }
            return this;
        }

        /**
         * Fetches from the inbound stream; the idle clock and the message clock restart their gaps from now.
         * When the request-body deadline passed while the body was not being read, the body is ended instead
         * and the inbound stream stays paused.
         */
        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return fetch(amount, NOTHING_IN_THE_READ_WINDOW);
        }

        /**
         * {@link #fetch(long)}, running {@code inReadWindow} after the read's first passed-deadline check
         * and before the idle clock resumes (see {@link #readMayProceed}). Package-private so a test can
         * place a deadline timer in that window; {@link #fetch(long)} runs nothing there.
         */
        ReadStream<Buffer> fetch(long amount, Runnable inReadWindow) {
            if (readMayProceed(inReadWindow)) {
                delegate.fetch(amount);
            }
            return this;
        }

        /**
         * Restarts the idle clock and the message clock for a read of the body, unless the request-body
         * deadline has passed — then the body is ended instead.
         * <p>
         * The passed deadline is checked before the clock resumes and again once it has. Until the clock
         * has resumed, the stream is still marked paused, so a deadline timer firing in between only records
         * that the deadline passed; the second check ends the body. A timer firing after the clock resumed
         * finds the stream no longer paused and ends the body itself.
         *
         * @param inReadWindow runs between the first passed-deadline check and the clock's resume
         * @return {@code true} when the read may reach the inbound stream
         */
        private boolean readMayProceed(Runnable inReadWindow) {
            if (endAtPassedDeadline()) {
                return false;
            }
            inReadWindow.run();
            resumeClocks();
            return !endAtPassedDeadline();
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            delegate.endHandler(endHandler == null ? null : this::onInboundEnd);
            return this;
        }
    }

    /**
     * Reads the frames of a body's gRPC length-prefixed messages as their bytes cross, without holding any
     * of them: each 5-byte prefix — a compressed-flag byte and the payload length as an unsigned 32-bit
     * big-endian integer — is read one byte at a time, and the payload bytes it declares are then only
     * counted, after which the next byte starts the next message's prefix. A prefix and a payload may each
     * straddle any number of chunks, and one chunk may end one message and carry any number of further
     * messages, complete or not; a message whose declared length is zero is complete at its prefix. The
     * tracker says how many messages each chunk completes. It never refuses a length: a declared length past
     * {@code max_body_bytes} is refused by the body's cumulative cap as the bytes arrive.
     * <p>
     * Not thread-safe: it is used by chunk delivery only, which runs on the inbound connection's event loop.
     */
    static final class GrpcMessageFrames {

        /** The length of a gRPC message prefix: the compressed flag and the 4-byte payload length. */
        static final int PREFIX_LENGTH = 5;

        /** How many bytes of the current message's prefix have been read. */
        private int prefixBytesRead;
        /** The current message's declared payload length, assembled from its prefix's length bytes. */
        private long declaredLength;
        /** How many of the current message's payload bytes have not crossed yet, once its prefix is read. */
        private long payloadBytesOwed;

        /**
         * Reads {@code chunk}'s bytes of the body's messages.
         *
         * @param chunk the next chunk of the body, read in place
         * @return how many messages {@code chunk} completes; {@code 0} for a chunk carrying only part of a
         *         message, or nothing
         */
        int completedBy(Buffer chunk) {
            int completed = 0;
            int index = 0;
            int length = chunk.length();
            while (index < length) {
                if (prefixBytesRead < PREFIX_LENGTH) {
                    byte next = chunk.getByte(index++);
                    if (prefixBytesRead > 0) {
                        // Bytes 2-5 carry the length, most significant first; the flag byte carries none.
                        declaredLength = (declaredLength << Byte.SIZE) | (next & 0xFF);
                    }
                    prefixBytesRead++;
                    if (prefixBytesRead == PREFIX_LENGTH) {
                        payloadBytesOwed = declaredLength;
                        if (payloadBytesOwed == 0) {
                            completed++;
                            nextMessage();
                        }
                    }
                } else {
                    long crossing = Math.min(payloadBytesOwed, (long) length - index);
                    payloadBytesOwed -= crossing;
                    index += (int) crossing;
                    if (payloadBytesOwed == 0) {
                        completed++;
                        nextMessage();
                    }
                }
            }
            return completed;
        }

        /** The current message is complete: the next byte starts the next message's prefix. */
        private void nextMessage() {
            prefixBytesRead = 0;
            declaredLength = 0;
            payloadBytesOwed = 0;
        }
    }

    /**
     * The scheduling seam of the request-body clocks — the idle clock, the deadline and the message clock:
     * schedules a task after a delay and cancels it. Production schedules on Vert.x
     * ({@link VertxIdleScheduler}); a unit test drives it by hand.
     */
    interface IdleScheduler {

        /**
         * Schedules {@code task} to run once after {@code delayMillis}.
         *
         * @param delayMillis the delay in milliseconds, positive
         * @param task        the task to run
         * @return the id that cancels the task
         */
        long schedule(long delayMillis, Runnable task);

        /**
         * Cancels a scheduled task; a no-op for a task that already ran or was cancelled.
         *
         * @param timerId the id {@link #schedule} returned
         */
        void cancel(long timerId);
    }

    /**
     * A request-body clock of one dispatch — the idle clock, or the message clock: its timeout, the
     * scheduler its timer runs on, and the nano-time source it measures progress with.
     *
     * @param timeoutMillis the clock's timeout in milliseconds, or {@link #NO_BODY_IDLE_TIMEOUT} (equal to
     *                      {@link #NO_FIRST_MESSAGE_TIMEOUT}) for a clock that is never armed
     * @param scheduler     schedules and cancels the clock's one pending timer
     * @param nanoTime      the monotonic time source, in nanoseconds
     */
    record IdleClock(long timeoutMillis, IdleScheduler scheduler, LongSupplier nanoTime) {

        /** The clock of a body that is not watched for progress: nothing is ever scheduled. */
        static final IdleClock UNARMED = new IdleClock(NO_BODY_IDLE_TIMEOUT, new IdleScheduler() {

            @Override
            public long schedule(long delayMillis, Runnable task) {
                throw new IllegalStateException("an unarmed idle clock schedules nothing");
            }

            @Override
            public void cancel(long timerId) {
                // Nothing was ever scheduled, so there is nothing to cancel.
            }
        }, System::nanoTime);

        IdleClock {
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(nanoTime, "nanoTime");
        }

        /**
         * @return {@code true} when the clock watches the body, i.e. its timeout is positive
         */
        boolean armed() {
            return timeoutMillis > NO_BODY_IDLE_TIMEOUT;
        }
    }

    /**
     * The production {@link IdleScheduler}: Vert.x timers of the instance owning the upstream request's
     * context.
     *
     * @param vertx the Vert.x instance the timers are set on
     */
    private record VertxIdleScheduler(Vertx vertx) implements IdleScheduler {

        @Override
        public long schedule(long delayMillis, Runnable task) {
            return vertx.setTimer(delayMillis, _ -> task.run());
        }

        @Override
        public void cancel(long timerId) {
            vertx.cancelTimer(timerId);
        }
    }
}
