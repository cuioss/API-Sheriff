/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.edge;

import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
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
        String path = stripTrailingSlash(upstream.basePath()) + pathRemainder;
        return rawQuery == null || rawQuery.isEmpty() ? path : path + rawQuery;
    }

    /**
     * Dispatches the request to the route's upstream, streaming the (byte-capped) request body and
     * returning the response whose body is not yet consumed.
     *
     * @param route          the resolved route runtime holding the shared client and guard
     * @param method         the upstream HTTP method
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
        try {
            return route.getResilienceGuard().call(
                    () -> awaitDispatch(route, method, requestUri, forwardHeaders, requestBody),
                    HttpClientResponse.class);
        } catch (GatewayException direct) {
            throw direct;
        } /*~~(TODO: Catch specific not Exception. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe)~~>*/ catch (Exception guarded) {
            GatewayException breach = extractGatewayException(guarded);
            if (breach != null) {
                throw breach;
            }
            throw failureMapper.toGatewayException(guarded);
        }
    }

    private HttpClientResponse awaitDispatch(RouteRuntime route, HttpMethod method, String requestUri,
            Map<String, String> forwardHeaders, ReadStream<Buffer> requestBody) throws Exception {
        ResolvedUpstream upstream = route.getUpstream();
        RequestOptions options = new RequestOptions()
                .setMethod(method)
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl("https".equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        Future<HttpClientResponse> response = route.getHttpClient().request(options)
                .compose(request -> {
                    forwardHeaders.forEach(request::putHeader);
                    return request.send(new ByteCappedBodyStream(requestBody, maxBodyBytes, request::reset));
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
        private long bytesSeen;
        private @Nullable Handler<Buffer> dataHandler;
        private @Nullable Handler<Throwable> failureHandler;
        private boolean aborted;

        ByteCappedBodyStream(ReadStream<Buffer> delegate, long maxBytes, Runnable abortAction) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
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
