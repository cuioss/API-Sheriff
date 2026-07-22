/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
 * {@link EventType#PARAMETER_LIMIT_EXCEEDED} (400). The upstream body is <strong>never
 * materialized</strong> into an {@code HttpResult<byte[]>} (ADR-0006/0008): the returned
 * {@link HttpClientResponse} is a live {@link ReadStream} whose body {@link ResponseStage} streams
 * back with backpressure.
 * <p>
 * The dispatch is awaited synchronously on the caller's virtual thread inside the guard so the
 * breaker observes each call's success / failure / timeout. Guard failures are mapped to the error
 * contract by {@link UpstreamFailureMapper}; a body-cap breach propagates its own
 * {@link GatewayException} unchanged.
 * <p>
 * <strong>Stream-aware retry gating.</strong> When the route enables SmallRye retry, the guarded
 * lambda may be re-invoked after a failure. A streamed request cannot be safely replayed once any
 * body byte has crossed to the upstream, and a non-idempotent verb must never be re-sent — so every
 * retry <em>re-entry</em> (never the first attempt) is vetted by a {@link StreamAwareRetryGate}
 * against the request method and the running body-bytes-sent count. A disallowed re-entry is aborted
 * by re-raising the first attempt's failure as a mapped {@link GatewayException}, which the guard's
 * {@code abortOn(GatewayException.class)} contract turns into an immediate abort — no duplicate
 * upstream request is ever issued.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DispatchStage {

    /** Bounds the cause-chain walk so a self-referential cause cycle cannot spin forever. */
    private static final int MAX_CAUSE_DEPTH = 16;

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
     * path remainder, and the allow-listed raw query appended verbatim.
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
     * Dispatches the request to the route's upstream, streaming the (byte-capped) request body and
     * returning the response whose body is not yet consumed. Every retry re-entry is vetted by a
     * {@link StreamAwareRetryGate} so a non-idempotent method or a request that has already streamed
     * a body byte is never re-sent (see the class javadoc).
     *
     * @param method         the request method — used both to build the upstream request and to
     *                       gate retry re-entries for idempotency
     * @param route          the resolved route runtime holding the shared client and guard
     * @param requestUri     the upstream request URI (see {@link #upstreamRequestUri})
     * @param forwardHeaders the deny-by-default header set computed by stage 5
     * @param requestBody    the inbound request body as a live read stream
     * @return the upstream response (body still streaming)
     * @throws GatewayException carrying the mapped error-contract event on any dispatch failure
     */
    public HttpClientResponse dispatch(RouteRuntime route, HttpMethod method, String requestUri,
            Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(forwardHeaders, "forwardHeaders");
        Objects.requireNonNull(requestBody, "requestBody");
        AtomicLong bytesSent = new AtomicLong();
        AtomicBoolean bodyStreamSubscribed = new AtomicBoolean();
        StreamAwareRetryGate retryGate = new StreamAwareRetryGate(route.isRetryEnabled());
        io.vertx.core.http.HttpMethod upstreamMethod = io.vertx.core.http.HttpMethod.valueOf(method.name());
        Guard guard = route.getResilienceGuard()
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires a resilience guard"));
        return guardedDispatch(guard, retryGate, method, bytesSent::get,
                bodyStreamSubscribed::get,
                () -> awaitDispatch(route, upstreamMethod, requestUri, forwardHeaders, requestBody, bytesSent,
                        bodyStreamSubscribed));
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
        Objects.requireNonNull(method, "method");
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
            String requestUri, Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody,
            AtomicLong bytesSent, AtomicBoolean bodyStreamSubscribed) throws InterruptedException, ExecutionException {
        ResolvedUpstream upstream = route.getUpstream()
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires a resolved upstream"));
        HttpClient httpClient = route.getHttpClient()
                .orElseThrow(() -> new IllegalStateException("proxy dispatch requires an upstream client"));
        RequestOptions options = new RequestOptions()
                .setMethod(method)
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl("https".equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        Future<HttpClientResponse> response = httpClient.request(options)
                .compose(request -> {
                    forwardHeaders.forEach(request::putHeader);
                    // The one-shot inbound body stream is subscribed the instant it is handed to
                    // request.send(...); mark it so a retry re-entry never re-attaches the consumed
                    // stream (which would stall) — see guardedDispatch's bodyStreamConsumed gate.
                    bodyStreamSubscribed.set(true);
                    return request.send(new ByteCappedBodyStream(requestBody, maxBodyBytes, request::reset,
                            bytesSent::addAndGet));
                })
                .map(received -> {
                    // Pause the upstream body the instant its head arrives, on the client's own
                    // event-loop context and before any body chunk is dispatched. ResponseStage#relay
                    // is deferred onto the server request's event loop (GatewayEdgeRoute), so without
                    // this pause a small upstream response can fully arrive and end before the deferred
                    // pipeTo subscribes — Vert.x then fails the pipe with "Response already ended". The
                    // pipe re-enables the stream when it subscribes.
                    received.pause();
                    return received;
                });
        return response.toCompletionStage().toCompletableFuture().get();
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

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * A {@link ReadStream} decorator that forwards each request-body chunk to the upstream as it
     * arrives — never accumulating the body — while counting bytes against a ceiling. On breach it
     * aborts the in-flight upstream request and fails the stream with a
     * {@link EventType#PARAMETER_LIMIT_EXCEEDED} {@link GatewayException}.
     */
    static final class ByteCappedBodyStream implements ReadStream<Buffer> {

        private final ReadStream<Buffer> delegate;
        private final long maxBytes;
        private final Runnable abortAction;
        private final LongConsumer bytesForwarded;
        private long bytesSeen;
        private @Nullable Handler<Buffer> dataHandler;
        private @Nullable Handler<Throwable> failureHandler;
        private boolean aborted;

        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, Runnable abortAction) {
            this(delegate, maxBytes, abortAction, length -> {
            });
        }

        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, Runnable abortAction,
                LongConsumer bytesForwarded) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
            this.bytesForwarded = Objects.requireNonNull(bytesForwarded, "bytesForwarded");
            delegate.exceptionHandler(this::propagateFailure);
            delegate.handler(this::onChunk);
        }

        private void onChunk(Buffer chunk) {
            if (aborted) {
                return;
            }
            bytesSeen += chunk.length();
            if (bytesSeen > maxBytes) {
                aborted = true;
                delegate.pause();
                abortAction.run();
                propagateFailure(new GatewayException(EventType.PARAMETER_LIMIT_EXCEEDED,
                        "Request body exceeded max_body_bytes=" + maxBytes));
                return;
            }
            // The chunk cleared the cap and is about to cross to the upstream — record it so the
            // stream-aware retry gate can see that a body byte has been sent on this attempt.
            bytesForwarded.accept(chunk.length());
            if (dataHandler != null) {
                dataHandler.handle(chunk);
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
            delegate.endHandler(endHandler);
            return this;
        }
    }
}
