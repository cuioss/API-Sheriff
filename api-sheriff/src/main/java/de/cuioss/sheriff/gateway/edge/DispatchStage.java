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

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;

/**
 * Stage 6 — upstream dispatch over the route's shared Vert.x {@code HttpClient}, invoked through
 * that route's SmallRye Fault-Tolerance guard.
 * <p>
 * The request body is <strong>streamed, never buffered</strong>: it flows through a
 * {@link ByteCappedBodyStream} that forwards each chunk to the upstream request as it arrives and
 * enforces the {@code max_body_bytes} ceiling with a running counter. A mid-stream breach ABORTS
 * the in-flight upstream call (Vert.x {@link HttpClientRequest#reset()}) and surfaces
 * {@link EventType#CONTENT_TOO_LARGE} (413). An inbound stream that fails after dispatch has begun
 * aborts the upstream call the same way and surfaces {@link EventType#INBOUND_BODY_ABORTED} (400), so
 * a request body that did not arrive whole is never completed towards the upstream and its HTTP/1.x
 * upstream connection is never pooled again. The
 * upstream body is <strong>never
 * materialized</strong> into an {@code HttpResult<byte[]>} (ADR-0006/0008): the returned
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
 * The dispatch is awaited synchronously on the caller's virtual thread inside the guard so the
 * breaker observes each call's success / failure / timeout. Guard failures are mapped to the error
 * contract by {@link UpstreamFailureMapper}; a body-cap breach propagates its own
 * {@link GatewayException} unchanged.
 * <p>
 * <strong>What the breaker sees of a client-ended dispatch.</strong> A dispatch the client itself
 * ended — the body-cap breach, an inbound body stream that failed after dispatch began, a body byte
 * on a bodyless method, or a body that disagreed with its declared length — reaches the guard as the
 * {@link GatewayException} the body stream recorded for it ({@link EventType#CONTENT_TOO_LARGE},
 * {@link EventType#INBOUND_BODY_ABORTED} for the failed or short inbound body, or
 * {@link EventType#SECURITY_FILTER_VIOLATION}), never as the transport error the aborted upstream
 * request produced. The guard skips a {@link GatewayException}, so such a dispatch is neither
 * counted as an upstream failure nor retried. It is not invisible to the breaker, though: SmallRye
 * Fault Tolerance has no neutral outcome, so a skipped exception is recorded as a success, in the
 * closed and in the half-open state alike. Keeping client-ended dispatches out of the breaker's
 * window altogether would need the guarded call to be restructured, which the gateway does not do.
 * <p>
 * The client abort is honoured only when it preceded the transport failure: it is read once, at
 * the moment the attempt's send fails, so a client that goes away only after the upstream already
 * failed leaves that failure attributed to the upstream. A failure with no client-side abort behind
 * it — a refused connection, an upstream reset, a timeout — is counted and mapped as an upstream
 * failure. One timing edge is attributed by what happened first: the edge's idle timeout can reap an
 * inbound connection before the route's read timeout fires, and the dispatch then ends as the
 * client-ended dispatch it is.
 * <p>
 * <strong>Stream-aware retry gating.</strong> When the route enables SmallRye retry, the guarded
 * lambda may be re-invoked after a failure. A streamed request cannot be safely replayed once any
 * body byte has crossed to the upstream, and a non-idempotent verb must never be re-sent — so every
 * retry <em>re-entry</em> (never the first attempt) is vetted by a {@link StreamAwareRetryGate}
 * against the request method and the running body-bytes-sent count. A disallowed re-entry is aborted
 * by re-raising the first attempt's failure as a mapped {@link GatewayException}, which the guard's
 * {@code abortOn(GatewayException.class)} contract turns into an immediate abort — no duplicate
 * upstream request is ever issued. A bodyless request never subscribes the inbound stream to an
 * attempt, so it stays retryable for as long as no body byte has arrived.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DispatchStage {

    /** Bounds the cause-chain walk so a self-referential cause cycle cannot spin forever. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The upstream request header the checked declared length is written to. */
    private static final String CONTENT_LENGTH = "Content-Length";

    /** The parameter name the null checks report for a missing request method. */
    private static final String METHOD_PARAMETER = "method";

    /** The methods that carry no body upstream unless they declare a positive length. */
    private static final Set<HttpMethod> BODYLESS_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);

    /** The declared length of a request that declares none. */
    static final long NO_DECLARED_LENGTH = -1L;

    private final long maxBodyBytes;
    private final UpstreamFailureMapper failureMapper;

    /**
     * @param maxBodyBytes  the streaming request-body ceiling in bytes ({@code max_body_bytes})
     * @param failureMapper the mapper turning guarded-dispatch failures into the error contract
     */
    public DispatchStage(long maxBodyBytes, UpstreamFailureMapper failureMapper) {
        this.maxBodyBytes = maxBodyBytes;
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
     * framed by it — and returning the response whose body is not yet consumed. Every retry re-entry
     * is vetted by a {@link StreamAwareRetryGate} so a non-idempotent method or a request that has
     * already streamed a body byte is never re-sent (see the class javadoc).
     *
     * @param method         the request method — used both to build the upstream request and to
     *                       gate retry re-entries for idempotency
     * @param route          the resolved route runtime holding the shared client and guard
     * @param requestUri     the upstream request URI (see {@link #upstreamRequestUri})
     * @param forwardHeaders the mode-filtered header set computed by stage 5
     * @param requestBody    the inbound request body as a live read stream
     * @param framing        how the body is framed towards the upstream (see {@link BodyFraming})
     * @return the upstream response (body still streaming)
     * @throws GatewayException carrying the mapped error-contract event on any dispatch failure
     */
    public HttpClientResponse dispatch(RouteRuntime route, HttpMethod method, String requestUri,
            Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody, BodyFraming framing) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(method, METHOD_PARAMETER);
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(forwardHeaders, "forwardHeaders");
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(framing, "framing");
        StreamAwareRetryGate retryGate = new StreamAwareRetryGate(route.isRetryEnabled());
        io.vertx.core.http.HttpMethod upstreamMethod = io.vertx.core.http.HttpMethod.valueOf(method.name());
        Guard guard = route.getResilienceGuard();
        if (guard == null) {
            throw new IllegalStateException("proxy dispatch requires a resilience guard");
        }
        DispatchBody body = new DispatchBody(requestBody, framing);
        return guardedDispatch(guard, retryGate, method, body.bytesSent::get, body.subscribed::get,
                () -> awaitDispatch(route, upstreamMethod, requestUri, forwardHeaders, body));
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
     * Runs {@code attempt} through the route's resilience {@code guard}, vetting every retry
     * re-entry: the first attempt always proceeds, but each subsequent re-entry is aborted — by
     * re-raising the prior failure as a mapped {@link GatewayException} (which the guard's
     * {@code abortOn(GatewayException.class)} contract honours) — whenever <em>either</em> the
     * {@code retryGate} refuses a retry for {@code method} at the current {@code bytesSent} count,
     * <em>or</em> {@code bodyStreamConsumed} reports that the one-shot request-body stream was
     * already subscribed on a prior attempt. The inbound body {@link ReadStream} is single-use:
     * re-attaching an already-subscribed stream to a fresh upstream request would silently stall
     * waiting for events that already fired on the first attempt, so such a re-entry must fail
     * explicitly rather than hang — even in the {@code bytesSent == 0} case, since the stream is
     * subscribed the instant the first attempt reaches {@code request.send(...)}, before any byte
     * crosses. Package-private so the retry-gating decision can be exercised without a live upstream.
     */
    HttpClientResponse guardedDispatch(Guard guard, StreamAwareRetryGate retryGate, HttpMethod method,
            LongSupplier bytesSent, BooleanSupplier bodyStreamConsumed, Callable<HttpClientResponse> attempt) {
        AtomicInteger attemptIndex = new AtomicInteger();
        AtomicReference<Throwable> priorFailure = new AtomicReference<>();
        // The trailing catch below is a deliberate catch-all, and the only shape that compiles:
        // Guard#call propagates the checked Exception declared by the Callable it wraps, while
        // guardedDispatch itself declares no throws clause — so a narrower catch would leave that
        // checked exception unhandled. It is also the correct boundary semantically: this is the
        // single translation point where any upstream/guard failure becomes a mapped GatewayException,
        // so one unexpected exception can neither escape onto the request path nor leak a stack trace
        // to the client.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            return guard.call(() -> {
                if (attemptIndex.getAndIncrement() > 0
                        && (bodyStreamConsumed.getAsBoolean()
                        || !retryGate.allowsRetry(method, bytesSent.getAsLong()))) {
                    throw failureMapper.toGatewayException(priorFailure.get());
                }
                try {
                    return attempt.call();
                } catch (ExecutionException executionFailure) {
                    // awaitDispatch blocks on CompletableFuture#get, which wraps the real cause (e.g. a
                    // client-side body-cap GatewayException from ByteCappedBodyStream) in an
                    // ExecutionException. Unwrap it so the guard's skipOn(GatewayException.class) and
                    // abortOn(GatewayException.class) rules see the actual GatewayException rather than
                    // the wrapper — otherwise a client-caused rejection is miscounted as an upstream
                    // failure and can trip the circuit breaker for reasons unrelated to upstream health.
                    Throwable cause = executionFailure.getCause();
                    Exception unwrapped = cause instanceof Exception exception ? exception : executionFailure;
                    priorFailure.set(unwrapped);
                    throw unwrapped;
                }
            }, HttpClientResponse.class);
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

    private HttpClientResponse awaitDispatch(RouteRuntime route, io.vertx.core.http.HttpMethod method,
            String requestUri, Map<String, String> forwardHeaders, DispatchBody body)
            throws InterruptedException, ExecutionException {
        ResolvedUpstream upstream = route.getUpstream();
        if (upstream == null) {
            throw new IllegalStateException("proxy dispatch requires a resolved upstream");
        }
        HttpClient httpClient = route.getHttpClient();
        if (httpClient == null) {
            throw new IllegalStateException("proxy dispatch requires an upstream client");
        }
        RequestOptions options = new RequestOptions()
                .setMethod(method)
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl("https".equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        AtomicReference<@Nullable ByteCappedBodyStream> cappedBody = new AtomicReference<>();
        // The client-caused abort as it stood the moment this attempt's send failed — never re-read
        // afterwards (see the catch below).
        AtomicReference<@Nullable GatewayException> clientAbortAtFailure = new AtomicReference<>();
        Future<HttpClientResponse> response = httpClient.request(options)
                .compose(request -> {
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
     * The request-body side of one dispatch, shared by every attempt the guard makes: how the body
     * is sent with an attempt's upstream request, and the client-caused abort an attempt reports in
     * place of the transport failure its own abort produced.
     */
    private final class DispatchBody {

        private final ReadStream<Buffer> inbound;
        private final long declaredLength;
        /** Running count of body bytes forwarded, read by the retry gate. */
        private final AtomicLong bytesSent = new AtomicLong();
        /** Whether an attempt subscribed the one-shot inbound stream, read by the retry gate. */
        private final AtomicBoolean subscribed = new AtomicBoolean();
        /** The upstream request of the attempt in flight, which a bodyless refusal resets. */
        private final AtomicReference<@Nullable HttpClientRequest> currentRequest = new AtomicReference<>();
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
         * Sends {@code request} with this dispatch's body: none for a bodyless request, otherwise the
         * byte-capped inbound stream, framed by the declared length when there is one.
         */
        Future<HttpClientResponse> send(HttpClientRequest request,
                AtomicReference<@Nullable ByteCappedBodyStream> cappedBody) {
            if (bodylessWatch != null) {
                currentRequest.set(request);
                GatewayException refused = bodylessWatch.clientAbort();
                if (refused != null) {
                    // Refused before this attempt's request was sent: release it unsent.
                    request.reset();
                    return Future.failedFuture(refused);
                }
                // No body at all, and the inbound stream stays unsubscribed, so the retry gate keeps
                // treating the attempt as bodyless.
                return request.send();
            }
            if (declaredLength >= 0) {
                request.putHeader(CONTENT_LENGTH, Long.toString(declaredLength));
            }
            // The one-shot inbound body stream is subscribed the instant it is handed to
            // request.send(...); mark it so a retry re-entry never re-attaches the consumed stream
            // (which would stall) — see guardedDispatch's bodyStreamConsumed gate.
            subscribed.set(true);
            ByteCappedBodyStream body = new ByteCappedBodyStream(inbound, maxBodyBytes, declaredLength,
                    request::reset, bytesSent::addAndGet);
            cappedBody.set(body);
            return request.send(body);
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
            HttpClientRequest request = currentRequest.get();
            if (request != null) {
                request.reset();
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
     * a {@code 400} can no longer be written; the refusal goes to the late-violation callback instead,
     * which runs before the upstream request is reset, so the edge ends the client response before the
     * aborted relay could end it cleanly. The choice between the two is made under this watch's
     * monitor, so a refusal takes exactly one of them.
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
     * Thread-safe: the failure arrives on the inbound connection's event loop while the handler may be
     * registered from another thread; both go through the same monitor.
     */
    static final class InboundBody implements ReadStream<Buffer> {

        private final ReadStream<Buffer> delegate;
        private @Nullable Throwable failure;
        private @Nullable Handler<Throwable> failureHandler;

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
            if (failure == null) {
                failure = cause;
            }
            if (failureHandler != null) {
                failureHandler.handle(cause);
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
     * it to the pool, and on HTTP/2 resets the one stream. The abort action runs at most once per
     * stream, whichever of the two triggers fires first.
     * <p>
     * <strong>Either abort is recorded as client-caused.</strong> The trigger that fires first leaves a
     * {@link GatewayException} behind — {@link EventType#CONTENT_TOO_LARGE} for the cap breach,
     * {@link EventType#INBOUND_BODY_ABORTED} for the failed inbound stream — readable through
     * {@link #clientAbort()} from any thread, and set before the abort action runs. The dispatch that
     * owns this stream reports that exception in place of the transport error its own abort caused.
     * A failed inbound stream is a client-caused termination, not a security filter violation: it is
     * reported under its own event so an ordinary dropped upload raises no security warning.
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

        private final ReadStream<Buffer> delegate;
        private final long maxBytes;
        private final long declaredLength;
        private final Runnable abortAction;
        private final LongConsumer bytesForwarded;
        private long bytesSeen;
        private @Nullable Handler<Buffer> dataHandler;
        private @Nullable Handler<Throwable> failureHandler;
        private @Nullable Handler<Void> endHandler;
        /**
         * The single abort claim: the trigger whose {@code compareAndSet} from {@code null} succeeds owns
         * the abort. The inbound failure may be replayed on the thread that registers the failure handler
         * while chunks arrive on the event loop, so the claim must be atomic across both callbacks.
         */
        private final AtomicReference<@Nullable GatewayException> clientAbort = new AtomicReference<>();

        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, Runnable abortAction) {
            this(delegate, maxBytes, NO_DECLARED_LENGTH, abortAction, length -> {
            });
        }

        /**
         * @param delegate       the inbound request body
         * @param maxBytes       the {@code max_body_bytes} ceiling
         * @param declaredLength the checked declared length the body is held to exactly, or a
         *                       negative value when the request declares none
         * @param abortAction    resets the upstream request
         * @param bytesForwarded receives the length of every chunk forwarded to the upstream
         */
        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, long declaredLength, Runnable abortAction,
                LongConsumer bytesForwarded) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.declaredLength = declaredLength;
            this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
            this.bytesForwarded = Objects.requireNonNull(bytesForwarded, "bytesForwarded");
            delegate.exceptionHandler(this::onInboundFailure);
            delegate.handler(this::onChunk);
        }

        /**
         * The inbound stream failed: abort the upstream request first, then report the failure, so the
         * consumer never sees a failed body whose upstream request is still open.
         */
        private void onInboundFailure(Throwable failure) {
            if (clientAbort.compareAndSet(null,
                    new GatewayException(EventType.INBOUND_BODY_ABORTED, INBOUND_BODY_FAILED, failure))) {
                abortAction.run();
            }
            propagateFailure(failure);
        }

        /**
         * @return the client-caused reason this stream aborted the upstream request, or {@code null}
         *         while it has not aborted it
         */
        @Nullable
        GatewayException clientAbort() {
            return clientAbort.get();
        }

        private void onChunk(Buffer chunk) {
            if (clientAbort.get() != null) {
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
            if (dataHandler != null) {
                dataHandler.handle(chunk);
            }
        }

        /**
         * The inbound stream ended. A body short of its declared length did not arrive whole: the
         * upstream request is aborted instead of being ended on a message shorter than its framing.
         * After an abort the end is not passed on — the consumer has already been told the body failed.
         */
        private void onInboundEnd(@Nullable Void end) {
            if (clientAbort.get() != null) {
                return;
            }
            if (declaredLength >= 0 && bytesSeen < declaredLength) {
                GatewayException shortBody = new GatewayException(EventType.INBOUND_BODY_ABORTED, INBOUND_BODY_SHORT);
                if (clientAbort.compareAndSet(null, shortBody)) {
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
            if (clientAbort.compareAndSet(null, reason)) {
                abortAction.run();
                propagateFailure(reason);
            }
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
            this.endHandler = endHandler;
            delegate.endHandler(endHandler == null ? null : this::onInboundEnd);
            return this;
        }
    }
}
