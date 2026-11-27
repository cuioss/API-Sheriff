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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.smallrye.faulttolerance.api.Guard;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;

/**
 * Builds the per-shape guard every proxied dispatch runs under, and owns the upstream bounds the edge
 * states: the circuit breaker's fixed policy and reset window, and the per-attempt upstream timeout.
 * <p>
 * Each guard joins SmallRye Fault-Tolerance's retry (on a route that enables it) and timeout with the
 * gateway's own {@link UpstreamCircuitBreaker}, in the order retry, breaker, timeout. The breaker is the
 * gateway's because SmallRye's records every call as a success or a failure, while a gateway rejection
 * ({@link GatewayException}) — a call the client ended, one refused because the wait queue for an
 * upstream connection was full, or one whose connection wait expired while a request body on that upstream
 * client was still arriving, not yet ended ({@link DispatchStage#expiredWaitOf}) — says nothing about the upstream
 * and is neutral: neither counted as a failure nor recorded as a success.
 * <p>
 * Handed to the {@link de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler} as its
 * {@link de.cuioss.sheriff.gateway.edge.assembly.ResilienceGuardFactory}, which builds one guard per
 * distinct {@link ResilienceShape} at boot. Immutable; safe to share.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamGuards {

    /**
     * How long an upstream circuit breaker stays open before it lets a trial request through, in
     * seconds. It is both the breaker's delay ({@link #guardFor}) and the value of the
     * {@code Retry-After} header on the {@code 503} an open circuit answers
     * ({@link de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter#seedRetryAfter}), so the hint a
     * client is given can never differ from the window the breaker applies.
     */
    public static final int BREAKER_RESET_SECONDS = 5;
    /**
     * The size of an upstream circuit breaker's rolling window: the most recent calls whose outcome the
     * upstream decided, successes and failures, that the failure ratio is judged over.
     */
    static final int BREAKER_REQUEST_VOLUME_THRESHOLD = 20;
    /** The share of failures in a full window that opens an upstream circuit breaker. */
    static final double BREAKER_FAILURE_RATIO = 0.5;
    /**
     * How many successful trial calls close a half-open upstream circuit breaker — and how many trials it
     * admits at once.
     */
    static final int BREAKER_SUCCESS_THRESHOLD = 2;
    /**
     * The nominal delay, in milliseconds, before each retry of a retry-enabled route. It is not a fixed gap:
     * {@link #guardFor} sets this delay and no jitter, so SmallRye Fault Tolerance randomises each wait with its
     * default jitter of 200 ms, and the wait before a retry is seldom exactly this long.
     */
    private static final long RETRY_DELAY_MILLIS = 100L;
    /** How many times a retry-enabled route retries a failed attempt. */
    private static final int MAX_RETRIES = 2;
    /**
     * The upstream guard's timeout, in seconds ({@link #guardFor}). It bounds each upstream attempt, not
     * the whole dispatch: the timeout sits inside the retry, so a dispatch retried on a retry-enabled
     * route can run up to three attempts, about 90 s in all. The request-body idle
     * timeout ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyIdleTimeoutMillis()})
     * is shorter, so a dispatch whose body stopped arriving is ended as the request's before the guard
     * would end it as the upstream's. So is the request-body deadline
     * ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}),
     * counted from the start of the attempt like this timeout, so a body still arriving when this timeout
     * would end the attempt is ended as the request's first. So are the two request bounds of a gRPC call,
     * the message bound ({@link GrpcDispatchStage#GRPC_MESSAGE_TIMEOUT_MILLIS}) and the pre-response
     * deadline ({@link GrpcDispatchStage#GRPC_PRE_RESPONSE_DEADLINE_MILLIS}), both counted from the moment the
     * attempt hands its body over and the deadline held to the pre-response cap
     * ({@link GrpcDispatchStage#GRPC_PRE_RESPONSE_CAP_MILLIS}), one second short of this timeout: a gRPC call
     * whose client does not send its next complete request message in time, or still holds its request
     * stream open at the deadline, is ended as the client's abort ({@link DispatchStage.RequestMessageTimeout}
     * or {@link DispatchStage.RequestBodyDeadlineExceeded}) before this timeout could end it as the upstream's.
     * The wait for an upstream connection or HTTP/2 stream slot has its own, shorter bound
     * ({@link DispatchStage#UPSTREAM_CONNECTION_WAIT_MILLIS}): an attempt whose wait reaches it is ended, without
     * its request being sent or its body read, as the upstream's timeout
     * ({@link DispatchStage.UpstreamConnectionWaitExpired}), which the breaker counts and the retry predicate
     * refuses — or, when a request body on that upstream client was still arriving at the expiry, not yet ended,
     * and had forwarded a chunk within the bound, as the gateway's neutral pool saturation — so no connection wait
     * runs into this timeout. On a gRPC call this timeout is therefore reached in
     * two ways, both the upstream's: by an upstream holding a complete, half-closed request it does not answer;
     * and by an upstream that stops taking data mid-request and does not take any more before this timeout.
     * While that backpressure pauses the request stream the message bound is not counted, and the
     * pre-response deadline, which keeps counting, ends the call only once the upstream takes data again — then as
     * the upstream's timeout ({@link DispatchStage.UpstreamStalledRequest}) when the upstream had held the stream
     * without a break for at least the upstream stall threshold
     * ({@link GrpcDispatchStage#GRPC_UPSTREAM_STALL_MILLIS}), as the client's otherwise. A deadline that passes
     * while the stream is being read is the client's.
     */
    public static final int UPSTREAM_TIMEOUT_SECONDS = 30;

    private final UpstreamFailureMapper upstreamFailureMapper;

    /**
     * @param upstreamFailureMapper the mapper every guard's breaker reports its state transitions to
     */
    public UpstreamGuards(UpstreamFailureMapper upstreamFailureMapper) {
        this.upstreamFailureMapper = Objects.requireNonNull(upstreamFailureMapper, "upstreamFailureMapper");
    }

    /**
     * Builds the per-shape guard: SmallRye's retry, only for a route that enables it, around the gateway's
     * {@link UpstreamCircuitBreaker}, around SmallRye's upstream timeout. The breaker opens once the failures
     * among its last {@link #BREAKER_REQUEST_VOLUME_THRESHOLD upstream-decided calls} reach the
     * {@link #BREAKER_FAILURE_RATIO failure ratio}, stays open for {@value #BREAKER_RESET_SECONDS} seconds and
     * closes after {@link #BREAKER_SUCCESS_THRESHOLD successful trials}. A gateway rejection ({@link GatewayException}) is
     * neutral to it: never counted as an upstream failure, never recorded as a success, and a half-open trial
     * that ends in one gives its trial back. Whether a failed attempt is retried is decided by the retry
     * predicate {@link DispatchStage#allowsRetryAfter(Throwable)}, after the attempt failed and before any
     * re-entry: it never retries a gateway rejection, and a retry it refuses is not recorded by the
     * breaker. A dispatch the client itself ended — a body-cap breach, an inbound request body that
     * failed after dispatch began, a body on a bodyless method, a body disagreeing with its declared
     * length, a body that made no progress for the request-body idle timeout, a body still arriving at
     * the request-body deadline, a gRPC call whose client did not send its next complete request message
     * within the message bound ({@link DispatchStage.RequestMessageTimeout}), a gRPC request stream its client
     * still held open at its pre-response deadline, a client that left — reaches the guard as such a neutral
     * {@link GatewayException} (see {@link DispatchStage}), so it neither dilutes the breaker's window nor
     * closes a half-open breaker. The timeout, {@value #UPSTREAM_TIMEOUT_SECONDS} seconds, bounds each attempt: it is
     * nested inside the retry, so a route that enables retry gives each of up to three attempts (two
     * retries, each after a nominal 100 ms delay that SmallRye randomises with its default jitter) its own
     * timeout, about 90 s for the whole dispatch. The request-body idle
     * timeout, the request-body deadline and the gRPC message bound and pre-response deadline are shorter,
     * so a body the gateway is still waiting for ends as the request's, and the timeout ends an attempt the
     * upstream holds up — on a gRPC call, one whose request stream has ended or one whose upstream stopped
     * taking data mid-request. An attempt whose wait for an upstream connection or HTTP/2 stream slot reaches
     * the connection wait bound ({@link DispatchStage#UPSTREAM_CONNECTION_WAIT_MILLIS}) is ended well before
     * then, unsent, as {@link DispatchStage.UpstreamConnectionWaitExpired}, which the retry predicate never
     * retries. A gRPC call whose pre-response deadline passed while its upstream's backpressure had the request stream
     * paused, and had kept it paused without a break for at least the upstream stall threshold
     * ({@link GrpcDispatchStage#GRPC_UPSTREAM_STALL_MILLIS}) when the upstream takes data again, is ended then, as
     * {@link DispatchStage.UpstreamStalledRequest}; a shorter pause leaves the deadline the client's. All three are
     * {@link de.cuioss.sheriff.gateway.events.EventType#UPSTREAM_TIMEOUT}, counted by the breaker.
     *
     * @param shape the resilience shape the guard is built for
     * @return the new guard
     */
    public Guard guardFor(ResilienceShape shape) {
        // The name identifies the breaker in its refusal, the SmallRye guards' descriptions and its
        // transition log lines; it is registered nowhere, so two edges built in one JVM for the same
        // upstream host:port never collide over it. The name carries every dimension of the
        // ResilienceShape that keys RouteAssembler's guardCache — scheme, host, port, the forced-HTTP/2
        // flag and retryEnabled — because each distinct shape holds its own breaker: a gRPC route and an
        // HTTP/1.1 route to one host:port, or two routes differing only in retryEnabled, open and close
        // independently, and an operator reading a transition must be able to tell which one moved.
        UpstreamTarget target = shape.target();
        String name = target.scheme() + "://" + target.host() + ":" + target.port()
                + ":h2=" + target.forcedHttp2() + ":retry=" + shape.retryEnabled();
        String description = "upstream-" + name;
        Guard timeout = Guard.create().withDescription(description)
                .withTimeout().duration(UPSTREAM_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                .build();
        Guard retry = shape.retryEnabled()
                ? Guard.create().withDescription(description)
                .withRetry().maxRetries(MAX_RETRIES).delay(RETRY_DELAY_MILLIS, ChronoUnit.MILLIS)
                .whenException(DispatchStage::allowsRetryAfter).done()
                .build()
                : null;
        UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(BREAKER_REQUEST_VOLUME_THRESHOLD,
                BREAKER_FAILURE_RATIO, Duration.ofSeconds(BREAKER_RESET_SECONDS), BREAKER_SUCCESS_THRESHOLD,
                System::nanoTime, name, state -> upstreamFailureMapper.recordBreakerTransition(name, state));
        return new UpstreamGuard(retry, breaker, timeout);
    }

    /**
     * A guard of the production composition — {@code retry}, when given, around the gateway's {@code breaker},
     * around {@code timeout} — over parts of the caller's policy. The seam a test uses to run a dispatch under a
     * breaker that opens sooner, or a timeout that ends sooner, than the production ones, with the same neutral
     * accounting of a gateway rejection.
     *
     * @param retry   the retry around the breaker, or {@code null} for none
     * @param breaker the breaker the guard records each attempt with
     * @param timeout the timeout bounding each attempt
     * @return the new guard
     */
    static Guard guardOver(@Nullable Guard retry, UpstreamCircuitBreaker breaker, Guard timeout) {
        return new UpstreamGuard(retry, Objects.requireNonNull(breaker, "breaker"),
                Objects.requireNonNull(timeout, "timeout"));
    }

    /**
     * One shape's guard: SmallRye's retry, when the route enables it, around the gateway's breaker, around
     * SmallRye's timeout — the order SmallRye applies its own strategies in, so the timeout bounds each attempt,
     * each attempt is recorded by the breaker once, and an open breaker refuses each attempt.
     * <p>
     * Only the synchronous {@link #call(Callable, Class)} the dispatch uses is served. The other entry points,
     * and an asynchronous result type ({@link CompletionStage}), are refused with an
     * {@link UnsupportedOperationException}: the breaker records an outcome when the action returns, which for
     * an asynchronous result is before the upstream decided anything. Immutable; safe to share.
     *
     * @since 1.0
     */
    private static final class UpstreamGuard implements Guard {

        private static final String SYNCHRONOUS_ONLY =
                "The upstream guard serves only the synchronous call(Callable, Class)";

        private final @Nullable Guard retry;
        private final UpstreamCircuitBreaker breaker;
        private final Guard timeout;

        private UpstreamGuard(@Nullable Guard retry, UpstreamCircuitBreaker breaker, Guard timeout) {
            this.retry = retry;
            this.breaker = breaker;
            this.timeout = timeout;
        }

        @Override
        public <T> T call(Callable<T> action, Class<T> type) throws Exception {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(type, "type");
            if (CompletionStage.class.isAssignableFrom(type)) {
                throw new UnsupportedOperationException(SYNCHRONOUS_ONLY + ", not an asynchronous " + type.getName());
            }
            Callable<T> attempt = () -> breaker.call(() -> timeout.call(action, type));
            return retry == null ? attempt.call() : retry.call(attempt, type);
        }

        @Override
        public <T> T call(Callable<T> action, TypeLiteral<T> type) {
            throw new UnsupportedOperationException(SYNCHRONOUS_ONLY);
        }

        @Override
        public <T> T get(Supplier<T> action, Class<T> type) {
            throw new UnsupportedOperationException(SYNCHRONOUS_ONLY);
        }

        @Override
        public <T> T get(Supplier<T> action, TypeLiteral<T> type) {
            throw new UnsupportedOperationException(SYNCHRONOUS_ONLY);
        }
    }
}
