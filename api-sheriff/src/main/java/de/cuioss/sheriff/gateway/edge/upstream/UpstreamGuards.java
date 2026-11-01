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

import java.time.temporal.ChronoUnit;
import java.util.Objects;

import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.smallrye.faulttolerance.api.Guard;

/**
 * Builds the per-shape SmallRye Fault-Tolerance guard every proxied dispatch runs under, and owns the
 * two upstream bounds the edge states: the breaker's reset window and the per-attempt upstream timeout.
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
     * The upstream guard's timeout, in seconds ({@link #guardFor}). It bounds each upstream attempt, not
     * the whole dispatch: the timeout sits inside the retry, so a dispatch retried on a retry-enabled
     * route can run up to three attempts, about 90 s in all. The request-body idle
     * timeout ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyIdleTimeoutMillis()})
     * is shorter, so a dispatch whose body stopped arriving is ended as the request's before the guard
     * would end it as the upstream's. So is the request-body deadline
     * ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()}),
     * counted from the start of the attempt like this timeout, so a body still arriving when this timeout
     * would end the attempt is ended as the request's first.
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
     * Builds the per-shape SmallRye Fault-Tolerance guard: a circuit breaker plus an upstream
     * timeout, with retry added only for a route that enables it. Gateway rejections
     * ({@link GatewayException}) are skipped by the breaker, so they never count as an upstream
     * failure. Whether a failed attempt is retried is decided by the retry predicate
     * {@link DispatchStage#allowsRetryAfter(Throwable)}, after the attempt failed and before any
     * re-entry: it never retries a gateway rejection, and a retry it refuses is not recorded by the
     * breaker. A dispatch the client itself ended — a body-cap breach, an inbound request body that
     * failed after dispatch began, a body on a bodyless method, a body disagreeing with its declared
     * length, a body that made no progress for the request-body idle timeout, a body still arriving at
     * the request-body deadline — reaches the guard as such a skipped {@link GatewayException} (see
     * {@link DispatchStage}). SmallRye Fault Tolerance has no neutral outcome, so the breaker records a
     * skipped exception as a success; keeping these dispatches out of its window would need the guarded
     * call to be restructured. The timeout, {@value #UPSTREAM_TIMEOUT_SECONDS} seconds, bounds each
     * attempt: it is nested inside the retry, so a route that enables retry gives each of up to three
     * attempts (two retries, 100 ms apart) its own timeout, about 90 s for the whole dispatch. The
     * request-body idle timeout and the request-body deadline are shorter, so a body the gateway is still
     * waiting for ends as the request's, and the timeout ends an attempt the upstream holds up.
     *
     * @param shape the resilience shape the guard is built for
     * @return the new guard
     */
    public Guard guardFor(ResilienceShape shape) {
        // The name identifies the breaker in its description and its transition log lines only. It is
        // deliberately NOT registered as the breaker's SmallRye name: that registry is JVM-global and
        // refuses a name it already holds, so a second edge built in the same JVM for the same upstream
        // host:port — any edge whose upstream port happens to be reused — would fail every dispatch to
        // it with a 502. The name carries every dimension of the ResilienceShape that keys
        // RouteAssembler's guardCache — scheme, host, port, the forced-HTTP/2 flag and
        // retryEnabled — because each distinct shape holds its own breaker: a gRPC route and an HTTP/1.1
        // route to one host:port, or two routes differing only in retryEnabled, open and close
        // independently, and an operator reading a transition must be able to tell which one moved.
        UpstreamTarget target = shape.target();
        String name = target.scheme() + "://" + target.host() + ":" + target.port()
                + ":h2=" + target.forcedHttp2() + ":retry=" + shape.retryEnabled();
        Guard.Builder builder = Guard.create().withDescription("upstream-" + name);
        builder.withCircuitBreaker()
                .requestVolumeThreshold(20)
                .failureRatio(0.5)
                .delay(BREAKER_RESET_SECONDS, ChronoUnit.SECONDS)
                .successThreshold(2)
                .skipOn(GatewayException.class)
                .onStateChange(state -> upstreamFailureMapper.recordBreakerTransition(name, state))
                .done();
        builder.withTimeout().duration(UPSTREAM_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done();
        if (shape.retryEnabled()) {
            builder.withRetry().maxRetries(2).delay(100, ChronoUnit.MILLIS)
                    .whenException(DispatchStage::allowsRetryAfter).done();
        }
        return builder.build();
    }
}
