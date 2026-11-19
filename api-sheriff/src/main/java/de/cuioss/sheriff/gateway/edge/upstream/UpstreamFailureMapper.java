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

import java.util.Objects;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Maps an upstream dispatch failure to the gateway error contract (stage 6/7).
 * <p>
 * The stage-6 dispatch runs through the route's resilience guard ({@link UpstreamGuards}), so a failure
 * surfaces as one of three shapes, each with a distinct HTTP status per the error contract in
 * {@code doc/architecture/observability-and-errors.adoc}:
 * <ul>
 *   <li>{@link CircuitBreakerOpenException} — the breaker is open, the upstream was never called →
 *       {@link EventType#UPSTREAM_CIRCUIT_OPEN} (503, {@code Retry-After} added by the edge);</li>
 *   <li>a timeout ({@link TimeoutException}, {@link java.util.concurrent.TimeoutException}, or a
 *       Vert.x timeout) — the upstream exceeded its configured deadline, including an attempt whose wait for a
 *       pooled connection outlasted its bound ({@link DispatchStage.UpstreamConnectionWaitExpired}) →
 *       {@link EventType#UPSTREAM_TIMEOUT} (504);</li>
 *   <li>any other connection / protocol failure → {@link EventType#UPSTREAM_ERROR} (502).</li>
 * </ul>
 * An attempt that obtained no pooled connection because the client's wait queue was full — the gateway's own
 * saturation — never reaches that classification: the dispatch maps it inside the guard, through
 * {@link #toPoolSaturation(Throwable)}, to {@link EventType#UPSTREAM_POOL_SATURATED} (503), a gateway rejection
 * the breaker records as neutral: neither a failure nor a success. An expired wait is, as a rule, not saturation:
 * it is the upstream's timeout above, which the breaker counts — except when, at the moment of expiry, a request
 * body on the same upstream client is still arriving, not yet ended, and forwarded a chunk within the wait bound:
 * the wait then expired behind a client's body and is mapped to the same saturation
 * ({@link DispatchStage#expiredWaitOf}). A body that has ended, however recently, does not count. Every mapped
 * failure is metered on the shared
 * {@link GatewayEventCounter}.
 * Breaker state transitions are metered and logged through
 * {@link #recordBreakerTransition(String, CircuitBreakerState)}, which {@link UpstreamGuards#guardFor} installs
 * as the state-change consumer of each breaker it builds ({@link UpstreamCircuitBreaker}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamFailureMapper {

    private static final CuiLogger LOGGER = new CuiLogger(UpstreamFailureMapper.class);

    /** Bounds the cause-chain walk so a self-referential cause cycle cannot spin forever. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private final GatewayEventCounter eventCounter;

    /**
     * @param eventCounter the shared in-process event counter feeding the metrics edge
     */
    public UpstreamFailureMapper(GatewayEventCounter eventCounter) {
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
    }

    /**
     * Classifies an upstream failure into its error-contract {@link EventType} without metering.
     *
     * @param failure the failure thrown by the guarded upstream dispatch
     * @return the mapped upstream event type (502 / 503 / 504)
     */
    public EventType classify(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof CircuitBreakerOpenException) {
                return EventType.UPSTREAM_CIRCUIT_OPEN;
            }
            if (isTimeout(current)) {
                return EventType.UPSTREAM_TIMEOUT;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return EventType.UPSTREAM_ERROR;
    }

    /**
     * Classifies and meters an upstream failure, producing the typed {@link GatewayException} the
     * edge renders (never leaking the internal cause to the client body).
     *
     * @param failure the failure thrown by the guarded upstream dispatch
     * @return the gateway exception carrying the mapped {@link EventType}
     */
    public GatewayException toGatewayException(Throwable failure) {
        EventType type = classify(failure);
        eventCounter.increment(type);
        return new GatewayException(type, "Upstream dispatch failed: " + type.name(), failure);
    }

    /**
     * Meters an attempt that obtained no pooled upstream connection because the gateway itself is saturated —
     * the upstream client's wait queue was full — and produces the {@link EventType#UPSTREAM_POOL_SATURATED}
     * rejection the edge answers {@code 503}. The dispatch raises it inside the guard, so the breaker records it
     * as neutral like every {@link GatewayException}: neither a failure nor a success. A wait that outlasted the
     * connection wait bound reaches this method only when, at its expiry, a request body on its upstream client is
     * still arriving, not yet ended, and forwarded a chunk within that bound — the wait expired behind a client's
     * body; otherwise, a body that has ended included, it is the upstream's timeout
     * ({@link EventType#UPSTREAM_TIMEOUT}), which the breaker counts.
     *
     * @param failure the pool's refusal or the expired wait, kept as the cause and never shown to the client
     * @return the gateway exception carrying {@link EventType#UPSTREAM_POOL_SATURATED}
     */
    public GatewayException toPoolSaturation(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        eventCounter.increment(EventType.UPSTREAM_POOL_SATURATED);
        return new GatewayException(EventType.UPSTREAM_POOL_SATURATED,
                "Upstream dispatch failed: " + EventType.UPSTREAM_POOL_SATURATED.name(), failure);
    }

    /**
     * Meters and logs a circuit-breaker state transition. Installed by {@link UpstreamGuards#guardFor} as the
     * state-change consumer of each breaker it builds.
     *
     * @param routeId the name of the breaker's upstream shape,
     *                {@code scheme://host:port:h2=<flag>:retry=<flag>}
     * @param state   the new breaker state
     */
    public void recordBreakerTransition(String routeId, CircuitBreakerState state) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(state, "state");
        switch (state) {
            case OPEN -> {
                eventCounter.increment(EventType.UPSTREAM_CIRCUIT_OPEN);
                LOGGER.warn(ApiSheriffLogMessages.WARN.CIRCUIT_BREAKER_OPEN, routeId);
            }
            case CLOSED -> LOGGER.warn(ApiSheriffLogMessages.WARN.CIRCUIT_BREAKER_CLOSED, routeId);
            default -> LOGGER.debug("Circuit breaker for route '%s' transitioned to %s", routeId, state);
        }
    }

    private static boolean isTimeout(Throwable candidate) {
        return candidate instanceof TimeoutException
                || candidate instanceof java.util.concurrent.TimeoutException
                || candidate.getClass().getSimpleName().contains("Timeout");
    }
}
