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

import java.io.Serial;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
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
 * resolved from the boot-built {@link RouteAssembler.UpstreamClients} lookup, by the route's
 * {@linkplain RouteAssembler.UpstreamTarget upstream target} and
 * {@linkplain RouteAssembler.ResilienceShape resilience shape} respectively.
 * <p>
 * The request body is <strong>streamed, never buffered</strong>: it flows through a
 * {@link ByteCappedBodyStream} that forwards each chunk to the upstream request as it arrives and
 * enforces the {@code max_body_bytes} ceiling with a running counter. A mid-stream breach ABORTS
 * the in-flight upstream call (Vert.x {@link HttpClientRequest#reset()}) and surfaces
 * {@link EventType#CONTENT_TOO_LARGE} (413). An inbound stream that fails after dispatch has begun
 * aborts the upstream call the same way and surfaces {@link EventType#INBOUND_BODY_ABORTED} (400), so
 * a request body that did not arrive whole is never completed towards the upstream and its HTTP/1.x
 * upstream connection is never pooled again: it is closed abortively, so it frees the gateway's local
 * port at once instead of holding it in {@code TIME_WAIT} (see {@code GatewayEdgeRoute#clientFor}).
 * The upstream body is <strong>never materialized</strong> into an {@code HttpResult<byte[]>}
 * (ADR-0006/0008): the returned
 * {@link HttpClientResponse} is a live {@link ReadStream} whose body {@link ResponseStage} streams
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
 * <strong>A body that stops arriving releases its upstream connection.</strong> A dispatch constructed
 * with a request-body idle timeout watches the body it forwards: once the attempt holds its upstream
 * connection and is reading the body, a period of that length without a forwarded body byte ends the
 * dispatch as {@link EventType#REQUEST_BODY_TIMEOUT} ({@code 408}) and resets the upstream request, so
 * the pooled connection is free for the next dispatch at once instead of being held until the guard's
 * timeout. What the clock does and does not measure is described on {@link ByteCappedBodyStream}. A
 * dispatch constructed with {@link #NO_BODY_IDLE_TIMEOUT} never arms the clock.
 * <p>
 * The dispatch is awaited synchronously on the caller's virtual thread inside the guard so the
 * breaker observes each call's success / failure / timeout. Guard failures are mapped to the error
 * contract by {@link UpstreamFailureMapper}; a body-cap breach propagates its own
 * {@link GatewayException} unchanged.
 * <p>
 * <strong>What the breaker sees of a client-ended dispatch.</strong> A dispatch the client itself
 * ended — the body-cap breach, an inbound body stream that failed after dispatch began, a body byte
 * on a bodyless method, a body that disagreed with its declared length, or a body that made no
 * progress for the request-body idle timeout — reaches the guard as the {@link GatewayException} the
 * body stream recorded for it ({@link EventType#CONTENT_TOO_LARGE},
 * {@link EventType#INBOUND_BODY_ABORTED} for the failed or short inbound body,
 * {@link EventType#SECURITY_FILTER_VIOLATION}, or {@link EventType#REQUEST_BODY_TIMEOUT} for the idle
 * body), never as the transport error the aborted upstream
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
 * inbound connection before the route's read timeout fires, and the dispatch then ends as the
 * client-ended dispatch it is.
 * <p>
 * <strong>An attempt the dispatch stops waiting for is released.</strong> Every attempt's upstream
 * exchange is tracked from before its request is obtained, whatever the body framing. Once the
 * dispatch no longer waits for an attempt — the attempt ends in any way other than handing its
 * response back, the next attempt begins, or the dispatch itself fails — that attempt's upstream
 * request is reset: one still waiting for a pooled connection is reset as soon as it obtains one and
 * is never sent late, one in flight is reset, and a response it already received and paused is
 * released with it. Only a response actually handed to the caller is exempt.
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

    /** The methods that carry no body upstream unless they declare a positive length. */
    private static final Set<HttpMethod> BODYLESS_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);

    /** The declared length of a request that declares none. */
    static final long NO_DECLARED_LENGTH = -1L;

    /**
     * The request-body idle timeout of a dispatch whose body is not watched for progress: the idle
     * clock is never armed.
     */
    public static final long NO_BODY_IDLE_TIMEOUT = 0L;

    /**
     * The retry state of the dispatch the guard is running, bound on the dispatching thread for the
     * duration of its guard call. The guard is shared by every route of one resilience shape, so this
     * is how its retry predicate learns which dispatch failed.
     */
    private static final ScopedValue<RetryState> RETRY_STATE = ScopedValue.newInstance();

    private final RouteAssembler.UpstreamClients upstreamClients;
    private final long maxBodyBytes;
    private final long bodyIdleTimeoutMillis;
    private final UpstreamFailureMapper failureMapper;

    /**
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
    public DispatchStage(RouteAssembler.UpstreamClients upstreamClients, long maxBodyBytes,
            long bodyIdleTimeoutMillis, UpstreamFailureMapper failureMapper) {
        if (bodyIdleTimeoutMillis < NO_BODY_IDLE_TIMEOUT) {
            throw new IllegalArgumentException("bodyIdleTimeoutMillis must not be negative: " + bodyIdleTimeoutMillis);
        }
        this.upstreamClients = Objects.requireNonNull(upstreamClients, "upstreamClients");
        this.maxBodyBytes = maxBodyBytes;
        this.bodyIdleTimeoutMillis = bodyIdleTimeoutMillis;
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
        String path = stripTrailingSlash(upstream.basePath()) + pathRemainder;
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
     *                       the shared client and guard from the {@link RouteAssembler.UpstreamClients}
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
        RouteAssembler.UpstreamTarget target = RouteAssembler.UpstreamTarget.of(route);
        HttpClient httpClient = upstreamClients.client(target)
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires an upstream client"));
        Guard guard = upstreamClients.guard(new RouteAssembler.ResilienceShape(target, route.retryEnabled()))
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires a resilience guard"));
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
        Future<HttpClientResponse> response = httpClient.request(options)
                .compose(request -> {
                    // Registered before the request is published, so every reset of it the gateway
                    // makes — the unsent release below included — finds a handler (see the class javadoc).
                    request.exceptionHandler(DispatchStage::onUpstreamRequestFailure);
                    if (!attempt.publish(request)) {
                        // The dispatch stopped waiting for this attempt before its request obtained a
                        // connection: the request was reset unsent, and no late upstream execution follows.
                        return Future.failedFuture(new CancellationException(ATTEMPT_ABANDONED));
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
                    Future<HttpClientResponse> sent = body.send(request, cappedBody);
                    // Registered before the pause below, so it runs first when the send fails, on the
                    // context reporting that failure — before the failure can reach the dispatching thread.
                    sent.onFailure(failure -> clientAbortAtFailure.set(body.clientAbort(cappedBody.get())));
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
            // leaves no snapshot and is the upstream's failure as well.
            GatewayException clientAbort = clientAbortAtFailure.get();
            if (clientAbort != null) {
                throw clientAbort;
            }
            throw dispatchFailure;
        }
        // From here on a body byte on a bodyless method reaches the edge through the late-violation
        // callback. One that arrived while the response was still being received is this dispatch's
        // own refusal instead: the response is not relayed, and the edge answers 400.
        GatewayException refusedBeforeHandOver = body.handOver();
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
     * Removes <em>every</em> trailing slash, not just one.
     * <p>
     * The count matters because {@code LocationRewriter} strips all of them before matching an
     * upstream {@code Location} against this same base path. Removing one here and all of them there
     * splits the {@code rewrite_location} round trip whenever a base path ends in a run: the rewriter
     * maps an upstream {@code /upload/next} onto the gateway, and the client's follow-up is then
     * dispatched to {@code /upload//next} — not the path the mapping was derived from. A base path
     * reaches here from two places and only one of them is normalized ({@code RouteTableBuilder}
     * normalizes a configured {@code upstream.path}; an alias base path is whatever
     * {@code URI.getPath()} returned), so the agreement is enforced here rather than assumed.
     */
    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
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
     * @param lateViolation  receives the refusal of a bodyless request whose first body byte arrived
     *                       only after the dispatch returned its response; invoked once, on the inbound
     *                       request's event loop, before the upstream request is reset
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
         * The framing of a request whose body is always streamed — never bodyless, so no late
         * refusal can arrive and none is received.
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

        DispatchBody(ReadStream<Buffer> inbound, BodyFraming framing) {
            this.inbound = inbound;
            this.declaredLength = framing.declaredLength();
            this.bodylessWatch = framing.bodyless()
                    ? BodylessMethodWatch.arm(inbound, this::resetCurrentRequest, framing.lateViolation())
                    : null;
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
         * or the send failed. The clock therefore never runs once the response is handed over. Its
         * timer is scheduled through the Vert.x instance owning the context this callback runs on, the
         * upstream request's own.
         *
         * @throws IllegalStateException when the idle clock is to be armed and no Vert.x context is
         *                               current — the send callback always runs on one
         */
        Future<HttpClientResponse> send(HttpClientRequest request,
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
            if (declaredLength >= 0) {
                request.putHeader(CONTENT_LENGTH, Long.toString(declaredLength));
            }
            // The one-shot inbound body stream is subscribed the instant it is handed to
            // request.send(...); mark it so a retry never re-attaches the consumed stream (which would
            // stall) — see allowsRetryAfter.
            subscribed.set(true);
            ByteCappedBodyStream body = new ByteCappedBodyStream(inbound, maxBodyBytes, declaredLength,
                    request::reset, bytesSent::addAndGet, idleClock());
            cappedBody.set(body);
            body.startIdleClock();
            Future<HttpClientResponse> sent = request.send(body);
            sent.onComplete(_ -> body.stopIdleClock());
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
            Context context = Vertx.currentContext();
            if (context == null) {
                throw new IllegalStateException("the request-body idle clock is armed on the upstream request's "
                        + "Vert.x context, and none is current");
            }
            return new IdleClock(bodyIdleTimeoutMillis, new VertxIdleScheduler(context.owner()), System::nanoTime);
        }

        /**
         * @return the client-caused reason the body aborted the attempt's upstream request, or
         *         {@code null} when it did not
         */
        @Nullable
        GatewayException clientAbort(@Nullable ByteCappedBodyStream cappedBody) {
            if (bodylessWatch != null) {
                return bodylessWatch.clientAbort();
            }
            return cappedBody == null ? null : cappedBody.clientAbort();
        }

        /**
         * Hands the received response over for relay.
         *
         * @return the refusal of a bodyless request that arrived before the hand-over, or
         *         {@code null} when there was none
         */
        @Nullable
        GatewayException handOver() {
            return bodylessWatch == null ? null : bodylessWatch.handOver();
        }

        private void resetCurrentRequest() {
            UpstreamAttempt current = currentAttempt.get();
            if (current != null) {
                current.resetRequest();
            }
        }
    }

    /**
     * The upstream exchange of one attempt: the request it obtained, and whether the dispatch has
     * stopped waiting for it.
     * <p>
     * <strong>Released exactly when it is no longer waited for.</strong> {@link #abandon()} marks the
     * attempt and resets its request; a request published only after that is reset at once and never
     * sent. Resetting the request also releases a response the attempt already received and paused.
     * <p>
     * Thread-safe: the request is published on its event loop while the dispatching thread may abandon
     * the attempt at the same moment. Each side writes its own field before it reads the other's — the
     * publisher sets the request, then reads the flag; {@link #abandon()} sets the flag, then reads the
     * request — so whatever the interleaving, at least one of them sees both and performs the reset. A
     * second reset of the same request is harmless.
     */
    private static final class UpstreamAttempt {

        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicReference<@Nullable HttpClientRequest> request = new AtomicReference<>();

        /**
         * Publishes the request this attempt obtained.
         *
         * @param obtained the attempt's upstream request, not yet sent
         * @return {@code true} when the attempt is still waited for and the request may be sent;
         *         {@code false} when it was already abandoned — the request has then been reset
         */
        boolean publish(HttpClientRequest obtained) {
            request.set(obtained);
            if (abandoned.get()) {
                obtained.reset();
                return false;
            }
            return true;
        }

        /** Stops waiting for this attempt and resets its request, if it obtained one. */
        void abandon() {
            abandoned.set(true);
            resetRequest();
        }

        /** Resets this attempt's request, if it obtained one. */
        void resetRequest() {
            HttpClientRequest published = request.get();
            if (published != null) {
                published.reset();
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
     * Thread-safe: the failure arrives on the inbound connection's event loop while the handler may be
     * registered from another thread; both go through the same monitor. The pause is pinned without
     * holding that monitor: a resume racing the mark is followed by a pause again.
     */
    static final class InboundBody implements ReadStream<Buffer> {

        private final ReadStream<Buffer> delegate;
        private @Nullable Throwable failure;
        private @Nullable Handler<Throwable> failureHandler;
        private volatile boolean resetByGateway;

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
        static InboundBody arm(ReadStream<Buffer> delegate) {
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
        void resetByGateway() {
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
     * Thread-safe: chunks arrive on the inbound connection's event loop, the consumer pauses and
     * resumes from either loop, and the timer fires on the loop it was scheduled from. The last
     * progress time is a volatile field; the rest of the clock's state is held under one monitor, and
     * the expiry competes with every other abort through the atomic claim.
     */
    static final class ByteCappedBodyStream implements ReadStream<Buffer> {

        /** Fixed disposition of a failed inbound body; carries nothing the client supplied. */
        private static final String INBOUND_BODY_FAILED =
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

        private final ReadStream<Buffer> delegate;
        private final long maxBytes;
        private final long declaredLength;
        private final Runnable abortAction;
        private final LongConsumer bytesForwarded;
        private final IdleClock idleClock;
        private long bytesSeen;
        /** When the body last made progress: the clock started, a chunk was forwarded, or it resumed. */
        private volatile long lastProgressNanos;
        /** Guards the idle clock's state below. */
        private final Object clockLock = new Object();
        private boolean clockStarted;
        private boolean clockStopped;
        private boolean pausedByConsumer;
        private long pendingTimer = NO_TIMER;
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
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.declaredLength = declaredLength;
            this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
            this.bytesForwarded = Objects.requireNonNull(bytesForwarded, "bytesForwarded");
            this.idleClock = Objects.requireNonNull(idleClock, "idleClock");
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
         * clock, so nothing expires after an abort.
         *
         * @return {@code true} when this call won the claim
         */
        private boolean claimAbort(Throwable reason) {
            if (abortReason.compareAndSet(null, reason)) {
                stopIdleClock();
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

        private void onChunk(Buffer chunk) {
            if (abortReason.get() != null) {
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
         * The inbound stream ended, which stops the idle clock. A body short of its declared length did
         * not arrive whole: the upstream request is aborted instead of being ended on a message shorter
         * than its framing. After an abort the end is not passed on — the consumer has already been told
         * the body failed, or the gateway reset the stream itself.
         */
        private void onInboundEnd(@Nullable Void end) {
            if (abortReason.get() != null) {
                return;
            }
            stopIdleClock();
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
         * stream as {@link EventType#REQUEST_BODY_TIMEOUT}. A no-op for an unarmed clock, a clock
         * already started, and one already stopped.
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
            }
        }

        /**
         * Stops the idle clock for good and cancels its pending timer. Idempotent; a no-op for an
         * unarmed clock.
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

        /** The consumer paused the stream: the gap is not counted until it resumes. */
        private void suspendIdleClock() {
            if (!idleClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                pausedByConsumer = true;
            }
        }

        /** The consumer resumed the stream: the gap restarts from now, and a timer is pending again. */
        private void resumeIdleClock() {
            if (!idleClock.armed()) {
                return;
            }
            synchronized (clockLock) {
                pausedByConsumer = false;
                if (!clockStarted || clockStopped) {
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

        /** Pauses the inbound stream; the idle clock does not count the pause. */
        @Override
        public ReadStream<Buffer> pause() {
            suspendIdleClock();
            delegate.pause();
            return this;
        }

        /** Resumes the inbound stream; the idle clock restarts its gap from now. */
        @Override
        public ReadStream<Buffer> resume() {
            resumeIdleClock();
            delegate.resume();
            return this;
        }

        /** Fetches from the inbound stream; the idle clock restarts its gap from now. */
        @Override
        public ReadStream<Buffer> fetch(long amount) {
            resumeIdleClock();
            delegate.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            delegate.endHandler(endHandler == null ? null : this::onInboundEnd);
            return this;
        }
    }

    /**
     * The scheduling seam of the request-body idle clock: schedules a task after a delay and cancels
     * it. Production schedules on Vert.x ({@link VertxIdleScheduler}); a unit test drives it by hand.
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
     * The request-body idle clock of one dispatch: its timeout, the scheduler its timer runs on, and
     * the nano-time source it measures progress with.
     *
     * @param timeoutMillis the idle timeout in milliseconds, or {@link #NO_BODY_IDLE_TIMEOUT} for a
     *                      clock that is never armed
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
