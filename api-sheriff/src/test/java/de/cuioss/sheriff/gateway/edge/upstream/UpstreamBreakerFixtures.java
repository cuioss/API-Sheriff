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
import java.util.function.Consumer;


import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;

/**
 * Guards for tests that run a dispatch under the gateway's own {@link UpstreamCircuitBreaker}, with the
 * production composition ({@link UpstreamGuards#guardOver}) but a breaker that opens sooner — typically on the
 * first failure it counts. No test builds a SmallRye breaker: its {@code skipOn} would record a gateway rejection
 * as a success, where the gateway's breaker records it as neutral. A breaker that stays closed is then a verdict, not a breaker unable to open, and a gateway rejection
 * is accounted exactly as in production: neutral, neither a failure nor a success.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamBreakerFixtures {

    private UpstreamBreakerFixtures() {
        // Static fixtures only.
    }

    /**
     * @param name        the breaker's name
     * @param transitions receives each state the breaker enters
     * @return a guard whose breaker has a window of one call, opens on its first counted failure and stays
     *         open for 30 s, around the production upstream timeout, with no retry
     */
    public static Guard breakerOnFirstFailure(String name, Consumer<CircuitBreakerState> transitions) {
        return breaker(1, 1.0, Duration.ofSeconds(30), name, transitions);
    }

    /**
     * @param requestVolumeThreshold the size of the breaker's closed window
     * @param failureRatio           the share of failures in a full window that opens it
     * @param openDelay              how long it stays open
     * @param name                   the breaker's name
     * @param transitions            receives each state the breaker enters
     * @return a guard over a breaker of that policy, closed by a single successful trial, around the production
     *         upstream timeout, with no retry
     */
    public static Guard breaker(int requestVolumeThreshold, double failureRatio, Duration openDelay, String name,
            Consumer<CircuitBreakerState> transitions) {
        return breaker(requestVolumeThreshold, failureRatio, openDelay,
                Duration.ofSeconds(UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS), name, transitions);
    }

    /**
     * @param requestVolumeThreshold the size of the breaker's closed window
     * @param failureRatio           the share of failures in a full window that opens it
     * @param openDelay              how long it stays open
     * @param attemptTimeout         the timeout bounding each attempt
     * @param name                   the breaker's name
     * @param transitions            receives each state the breaker enters
     * @return a guard over a breaker of that policy, closed by a single successful trial, around that timeout,
     *         with no retry
     */
    public static Guard breaker(int requestVolumeThreshold, double failureRatio, Duration openDelay,
            Duration attemptTimeout, String name, Consumer<CircuitBreakerState> transitions) {
        return UpstreamGuards.guardOver(null, upstreamBreaker(requestVolumeThreshold, failureRatio, openDelay, name,
                transitions), timeout(attemptTimeout));
    }

    /**
     * @param retryEnabled whether the guard retries, as the production retry does: twice, deciding through
     *                     {@link DispatchStage#allowsRetryAfter(Throwable)}
     * @param attemptTimeout the timeout bounding each attempt
     * @param name           the breaker's name
     * @param transitions    receives each state the breaker enters
     * @return a guard whose breaker opens on its first counted failure and stays open for 30 s, in the
     *         production composition
     */
    public static Guard breakerOnFirstFailure(boolean retryEnabled, Duration attemptTimeout, String name,
            Consumer<CircuitBreakerState> transitions) {
        Guard retry = retryEnabled
                ? Guard.create().withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                .whenException(DispatchStage::allowsRetryAfter).done().build()
                : null;
        return UpstreamGuards.guardOver(retry, upstreamBreaker(1, 1.0, Duration.ofSeconds(30), name, transitions),
                timeout(attemptTimeout));
    }

    private static UpstreamCircuitBreaker upstreamBreaker(int requestVolumeThreshold, double failureRatio,
            Duration openDelay, String name, Consumer<CircuitBreakerState> transitions) {
        return new UpstreamCircuitBreaker(requestVolumeThreshold, failureRatio, openDelay, 1, System::nanoTime, name,
                transitions);
    }

    private static Guard timeout(Duration attemptTimeout) {
        return Guard.create().withTimeout().duration(attemptTimeout.toMillis(), ChronoUnit.MILLIS).done().build();
    }
}
