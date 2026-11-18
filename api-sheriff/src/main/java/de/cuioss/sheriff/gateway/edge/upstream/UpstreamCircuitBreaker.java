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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.LongSupplier;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.tools.logging.CuiLogger;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * The circuit breaker of one upstream resilience shape: it judges an upstream only by the outcomes the
 * upstream decided, and refuses calls while the upstream is judged unhealthy.
 * <p>
 * <strong>Three outcomes.</strong> Every call it runs ends in exactly one of them:
 * <ul>
 *   <li><em>success</em> — the action returned a result: the upstream answered;</li>
 *   <li><em>neutral</em> — the action failed with a {@link GatewayException} in its cause chain, followed
 *       to a bounded depth by the rule the dispatch's retry predicate applies
 *       ({@link DispatchStage#extractGatewayException(Throwable)}): the gateway itself ended the call — the
 *       client left, its body broke a bound, the wait queue for an upstream connection was full — so the
 *       call says nothing about the upstream;</li>
 *   <li><em>failure</em> — anything else, the guard's timeout and an expired connection wait included.</li>
 * </ul>
 * A neutral outcome is neither charged nor credited. SmallRye Fault Tolerance's breaker records every call
 * as a success or a failure, so the gateway owns this one instead (ADR-0062, ADR-0064).
 * <p>
 * <strong>State machine.</strong> The policy is fixed at construction:
 * <ul>
 *   <li><em>closed</em> — every call is admitted. The window holds the last
 *       {@code requestVolumeThreshold} successes and failures; a neutral outcome records nothing. Once the
 *       window is full and the failures in it reach {@code failureRatio} of it, the breaker opens.</li>
 *   <li><em>open</em> — every call is refused, unrun, with a {@link CircuitBreakerOpenException} naming the
 *       breaker. The first call after {@code openDelay} has passed moves the breaker to half-open and is
 *       admitted there.</li>
 *   <li><em>half-open</em> — at most {@code successThreshold} trial calls are admitted; the rest are refused
 *       as in the open state. A failed trial reopens the breaker for another {@code openDelay}; once
 *       {@code successThreshold} trials succeeded it closes, with an empty window. A neutral trial gives its
 *       permit back without counting toward the close, so a further call is admitted in its place.</li>
 * </ul>
 * Each transition is reported once to the state-change consumer, with the state entered. A consumer that
 * throws loses only that report, logged as {@code ApiSheriff-151}: the call that caused the transition keeps
 * its own result or failure, and a call admitted as a half-open trial still runs.
 * <p>
 * <strong>Thread-safety.</strong> Safe for concurrent use; one instance serves every dispatch of its shape.
 * Admission and recording are two moments, and the state can move on between them, so every admission
 * yields a permit bound to the state generation it was taken under: an outcome recorded against a
 * superseded generation is ignored. The state is read and changed only under this breaker's monitor, and
 * transitions are reported after leaving it, so the consumer never runs while the monitor is held. Every
 * admitted call settles its permit exactly once, in a {@code finally}, so an {@link Error} or an interrupt
 * never leaks a half-open trial permit; an action that ends in an {@link Error} is recorded as a failure.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class UpstreamCircuitBreaker {

    private static final CuiLogger LOGGER = new CuiLogger(UpstreamCircuitBreaker.class);

    private final int requestVolumeThreshold;
    private final int failureThreshold;
    private final long openDelayNanos;
    private final int successThreshold;
    private final LongSupplier nanoClock;
    private final String name;
    private final Consumer<CircuitBreakerState> stateChanges;

    /** The closed window: {@code true} marks a failure, {@code false} a success. Guarded by {@code this}. */
    private final boolean[] window;

    // All fields below are guarded by this breaker's monitor.
    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private long generation;
    private int windowSize;
    private int windowNext;
    private int windowFailures;
    private long openedAtNanos;
    private int trialPermits;
    private int trialSuccesses;

    /**
     * @param requestVolumeThreshold the size of the closed window: how many successes and failures it holds
     *                               before the failure ratio is judged; positive
     * @param failureRatio           the share of failures in a full window that opens the breaker, greater
     *                               than {@code 0} and at most {@code 1}
     * @param openDelay              how long the breaker stays open before it admits a trial call; not
     *                               negative
     * @param successThreshold       how many successful trial calls close a half-open breaker, and how many
     *                               trial calls it admits at once; positive
     * @param nanoClock              the monotonic clock the open delay is measured on, in nanoseconds —
     *                               {@code System::nanoTime} in production
     * @param name                   the name the breaker is refused under
     * @param stateChanges           receives each state the breaker enters, once per transition
     * @throws IllegalArgumentException when a bound is outside its range
     */
    UpstreamCircuitBreaker(int requestVolumeThreshold, double failureRatio, Duration openDelay,
            int successThreshold, LongSupplier nanoClock, String name, Consumer<CircuitBreakerState> stateChanges) {
        if (requestVolumeThreshold < 1) {
            throw new IllegalArgumentException("requestVolumeThreshold must be positive: " + requestVolumeThreshold);
        }
        if (!(failureRatio > 0.0 && failureRatio <= 1.0)) {
            throw new IllegalArgumentException("failureRatio must be in (0, 1]: " + failureRatio);
        }
        Objects.requireNonNull(openDelay, "openDelay");
        if (openDelay.isNegative()) {
            throw new IllegalArgumentException("openDelay must not be negative: " + openDelay);
        }
        if (successThreshold < 1) {
            throw new IllegalArgumentException("successThreshold must be positive: " + successThreshold);
        }
        this.requestVolumeThreshold = requestVolumeThreshold;
        this.failureThreshold = (int) Math.ceil(failureRatio * requestVolumeThreshold);
        this.openDelayNanos = openDelay.toNanos();
        this.successThreshold = successThreshold;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.name = Objects.requireNonNull(name, "name");
        this.stateChanges = Objects.requireNonNull(stateChanges, "stateChanges");
        this.window = new boolean[requestVolumeThreshold];
    }

    /**
     * Runs {@code action} when the breaker admits it, and records its outcome.
     *
     * @param action the guarded call
     * @param <T>    the result type
     * @return the action's result, unchanged
     * @throws CircuitBreakerOpenException when the breaker refuses the call; the action is not run
     * @throws Exception                   whatever the action threw, unchanged
     */
    <T> T call(Callable<T> action) throws Exception {
        Objects.requireNonNull(action, "action");
        long permit = admit();
        Outcome outcome = Outcome.FAILURE;
        // A Callable may throw any Exception, and every one must be classified before it is rethrown
        // unchanged: the breaker judges the outcome, it never handles the failure.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            T result = action.call();
            outcome = Outcome.SUCCESS;
            return result;
        } catch (Exception failure) {
            outcome = DispatchStage.extractGatewayException(failure) != null ? Outcome.NEUTRAL : Outcome.FAILURE;
            throw failure;
        } finally {
            settle(permit, outcome);
        }
    }

    /**
     * Admits a call or refuses it.
     *
     * @return the generation the admitted call's permit is bound to
     * @throws CircuitBreakerOpenException when the call is refused
     */
    private long admit() {
        List<CircuitBreakerState> transitions = new ArrayList<>(1);
        long permit;
        synchronized (this) {
            if (state == CircuitBreakerState.OPEN && nanoClock.getAsLong() - openedAtNanos >= openDelayNanos) {
                enter(CircuitBreakerState.HALF_OPEN, transitions);
            }
            permit = switch (state) {
                case CLOSED -> generation;
                case HALF_OPEN -> trialPermits < successThreshold ? admitTrial() : -1L;
                case OPEN -> -1L;
            };
        }
        report(transitions);
        if (permit < 0) {
            throw new CircuitBreakerOpenException("Circuit breaker for upstream '" + name + "' is open");
        }
        return permit;
    }

    private long admitTrial() {
        trialPermits++;
        return generation;
    }

    /**
     * Records the outcome of an admitted call against the generation that admitted it.
     *
     * @param permit  the generation the call was admitted under
     * @param outcome how the call ended
     */
    private void settle(long permit, Outcome outcome) {
        List<CircuitBreakerState> transitions = new ArrayList<>(1);
        synchronized (this) {
            if (permit == generation) {
                if (state == CircuitBreakerState.CLOSED) {
                    recordClosed(outcome, transitions);
                } else if (state == CircuitBreakerState.HALF_OPEN) {
                    recordTrial(outcome, transitions);
                }
            }
        }
        report(transitions);
    }

    private void recordClosed(Outcome outcome, List<CircuitBreakerState> transitions) {
        if (outcome == Outcome.NEUTRAL) {
            return;
        }
        boolean failed = outcome == Outcome.FAILURE;
        if (windowSize == requestVolumeThreshold) {
            if (window[windowNext]) {
                windowFailures--;
            }
        } else {
            windowSize++;
        }
        window[windowNext] = failed;
        if (failed) {
            windowFailures++;
        }
        windowNext = (windowNext + 1) % requestVolumeThreshold;
        if (windowSize == requestVolumeThreshold && windowFailures >= failureThreshold) {
            enter(CircuitBreakerState.OPEN, transitions);
        }
    }

    private void recordTrial(Outcome outcome, List<CircuitBreakerState> transitions) {
        switch (outcome) {
            case NEUTRAL -> trialPermits--;
            case FAILURE -> enter(CircuitBreakerState.OPEN, transitions);
            case SUCCESS -> {
                trialSuccesses++;
                if (trialSuccesses >= successThreshold) {
                    enter(CircuitBreakerState.CLOSED, transitions);
                }
            }
        }
    }

    /**
     * Moves the breaker to {@code next} under its monitor, superseding every permit taken so far, and notes
     * the transition for reporting once the monitor is left.
     */
    private void enter(CircuitBreakerState next, List<CircuitBreakerState> transitions) {
        state = next;
        generation++;
        windowSize = 0;
        windowNext = 0;
        windowFailures = 0;
        trialPermits = 0;
        trialSuccesses = 0;
        if (next == CircuitBreakerState.OPEN) {
            openedAtNanos = nanoClock.getAsLong();
        }
        transitions.add(next);
    }

    /**
     * Delivers each noted transition to the state-change consumer. A consumer that throws loses only its own
     * report: the transition has already happened, so the failure is logged and the remaining transitions
     * are still delivered. It never reaches the caller — {@link #admit()} reports before the admitted call
     * runs, so a throw there would strand a half-open trial permit, and {@link #settle(long, Outcome)} reports
     * in a {@code finally}, so a throw there would replace the call's own result or failure.
     */
    private void report(List<CircuitBreakerState> transitions) {
        for (CircuitBreakerState entered : transitions) {
            try {
                stateChanges.accept(entered);
            } catch (RuntimeException reportFailure) {
                LOGGER.warn(reportFailure, ApiSheriffLogMessages.WARN.CIRCUIT_BREAKER_REPORT_FAILED, name, entered);
            }
        }
    }

    /** How a call ended, as the breaker judges it. */
    private enum Outcome {
        SUCCESS, NEUTRAL, FAILURE
    }
}
