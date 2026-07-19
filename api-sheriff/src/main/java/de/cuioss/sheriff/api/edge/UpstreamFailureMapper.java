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

import java.util.Objects;

import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayEventCounter;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;

import io.smallrye.faulttolerance.api.CircuitBreakerState;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Maps an upstream dispatch failure to the gateway error contract (stage 6/7).
 * <p>
 * The stage-6 dispatch runs through the route's SmallRye Fault-Tolerance guard, so a failure
 * surfaces as one of three shapes, each with a distinct HTTP status per the error contract in
 * {@code architecture.adoc}:
 * <ul>
 *   <li>{@link CircuitBreakerOpenException} — the breaker is open, the upstream was never called →
 *       {@link EventType#UPSTREAM_CIRCUIT_OPEN} (503, {@code Retry-After} added by the edge);</li>
 *   <li>a timeout ({@link TimeoutException}, {@link java.util.concurrent.TimeoutException}, or a
 *       Vert.x timeout) — the upstream exceeded its configured deadline →
 *       {@link EventType#UPSTREAM_TIMEOUT} (504);</li>
 *   <li>any other connection / protocol failure → {@link EventType#UPSTREAM_ERROR} (502).</li>
 * </ul>
 * Every mapped failure is metered on the shared {@link GatewayEventCounter}. Breaker state
 * transitions are metered and logged through {@link #recordBreakerTransition(String, CircuitBreakerState)},
 * which the boot-time edge registers with SmallRye's {@code CircuitBreakerMaintenance.onStateChange}.
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
     * Meters and logs a circuit-breaker state transition. Registered by the edge with SmallRye's
     * {@code CircuitBreakerMaintenance.onStateChange(routeId, mapper::recordBreakerTransition)}.
     *
     * @param routeId the route id owning the breaker
     * @param state   the new breaker state
     */
    public void recordBreakerTransition(String routeId, CircuitBreakerState state) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(state, "state");
        if (state == CircuitBreakerState.OPEN) {
            eventCounter.increment(EventType.UPSTREAM_CIRCUIT_OPEN);
        }
        LOGGER.debug("Circuit breaker for route '%s' transitioned to %s", routeId, state);
    }

    private static boolean isTimeout(Throwable candidate) {
        return candidate instanceof TimeoutException
                || candidate instanceof java.util.concurrent.TimeoutException
                || candidate.getClass().getSimpleName().contains("Timeout");
    }
}
