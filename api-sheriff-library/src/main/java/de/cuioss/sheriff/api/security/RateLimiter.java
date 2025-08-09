/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.api.security;

import static de.cuioss.tools.base.Preconditions.checkArgument;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.tools.logging.CuiLogger;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import lombok.ToString;

/**
 * Thread-safe rate limiter implementation using a sliding window approach.
 * This class limits the number of requests per client within a specified time window.
 *
 * <p>The rate limiter uses a token bucket-like approach where each client has a bucket
 * that gets refilled periodically. When a request comes in, it consumes a token from
 * the bucket. If no tokens are available, the request is rejected.</p>
 *
 * <h2>Usage Example:</h2>
 * <pre>
 * {@code
 * RateLimiter limiter = new RateLimiter(100, Duration.ofMinutes(1).toMillis());
 *
 * if (limiter.isRequestAllowed("client-123")) {
 *     // Process the request
 * } else {
 *     // Reject the request - rate limit exceeded
 * }
 * }
 * </pre>
 *
 * @author API Sheriff Team
 */
@Getter(AccessLevel.PACKAGE)
@EqualsAndHashCode
@ToString
public class RateLimiter {

    private static final CuiLogger log = new CuiLogger(RateLimiter.class);

    /**
     * Maximum number of requests allowed per client within the time window.
     */
    private final int maxRequests;

    /**
     * Time window in milliseconds for rate limiting calculations.
     */
    private final long timeWindowMillis;

    /**
     * Thread-safe map storing rate limiting information for each client.
     */
    private final ConcurrentMap<String, ClientRateInfo> clientRateMap = new ConcurrentHashMap<>();

    /**
     * Creates a new RateLimiter with validation.
     *
     * @param maxRequests      the maximum number of requests allowed per client, must be greater than 0
     * @param timeWindowMillis the time window in milliseconds, must be greater than 0
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public RateLimiter(int maxRequests, long timeWindowMillis) {
        checkArgument(maxRequests > 0, "Max requests must be greater than 0, but was: %s", maxRequests);
        checkArgument(timeWindowMillis > 0, "Time window must be greater than 0, but was: %s", timeWindowMillis);

        this.maxRequests = maxRequests;
        this.timeWindowMillis = timeWindowMillis;

        log.debug("RateLimiter created with maxRequests={}, timeWindowMillis={}", maxRequests, timeWindowMillis);
    }

    /**
     * Checks if a request from the specified client is allowed within the rate limit.
     * This method is thread-safe and can be called concurrently.
     *
     * @param clientId the client identifier, must not be null or empty
     * @return true if the request is allowed, false if rate limit is exceeded
     * @throws IllegalArgumentException if clientId is null or empty
     */
    public boolean isRequestAllowed(String clientId) {
        checkArgument(null != clientId && !clientId.trim().isEmpty(), "ClientId must not be null or empty");

        long currentTime = System.currentTimeMillis();

        ClientRateInfo rateInfo = clientRateMap.computeIfAbsent(clientId,
            k -> new ClientRateInfo(currentTime, new AtomicInteger(0)));

        return rateInfo.tryConsumeToken(currentTime, maxRequests, timeWindowMillis);
    }

    /**
     * Resets the rate limiting state for a specific client.
     * This can be used for administrative purposes or testing.
     *
     * @param clientId the client identifier to reset, must not be null or empty
     * @throws IllegalArgumentException if clientId is null or empty
     */
    public void resetClient(String clientId) {
        checkArgument(null != clientId && !clientId.trim().isEmpty(), "ClientId must not be null or empty");

        ClientRateInfo removed = clientRateMap.remove(clientId);
        if (removed != null) {
            log.debug("Reset rate limiting state for client '{}'", clientId);
        }
    }

    /**
     * Resets all client rate limiting states.
     * This method clears all tracking information and starts fresh.
     */
    public void resetAllClients() {
        int clientsReset = clientRateMap.size();
        clientRateMap.clear();
        log.debug("Reset rate limiting state for {} clients", clientsReset);
    }

    /**
     * Gets the current number of clients being tracked.
     *
     * @return the number of clients currently being tracked
     */
    public int getTrackedClientCount() {
        return clientRateMap.size();
    }

    /**
     * Gets the remaining requests for a specific client within the current time window.
     *
     * @param clientId the client identifier, must not be null or empty
     * @return the number of remaining requests, or the maximum if client is not tracked
     * @throws IllegalArgumentException if clientId is null or empty
     */
    public int getRemainingRequests(String clientId) {
        checkArgument(null != clientId && !clientId.trim().isEmpty(), "ClientId must not be null or empty");

        ClientRateInfo rateInfo = clientRateMap.get(clientId);
        if (rateInfo == null) {
            return maxRequests; // Client not tracked yet, full limit available
        }

        long currentTime = System.currentTimeMillis();
        return rateInfo.getRemainingRequests(currentTime, maxRequests, timeWindowMillis);
    }

    /**
     * Internal class to track rate limiting information for a single client.
     * This class is thread-safe and handles the sliding window logic.
     */
    @EqualsAndHashCode
    @ToString
    private static class ClientRateInfo {

        /**
         * Timestamp of the start of the current time window.
         */
        private volatile long windowStartTime;

        /**
         * Number of requests made in the current time window.
         */
        private final AtomicInteger requestCount;

        /**
         * Creates a new ClientRateInfo instance.
         *
         * @param windowStartTime the start time of the current window
         * @param requestCount the atomic counter for request tracking
         */
        public ClientRateInfo(long windowStartTime, AtomicInteger requestCount) {
            this.windowStartTime = windowStartTime;
            this.requestCount = requestCount;
        }

        /**
         * Attempts to consume a token (allow a request) if within rate limits.
         *
         * @param currentTime      the current timestamp
         * @param maxRequests      the maximum allowed requests
         * @param timeWindowMillis the time window duration in milliseconds
         * @return true if token was consumed (request allowed), false otherwise
         */
        public synchronized boolean tryConsumeToken(long currentTime, int maxRequests, long timeWindowMillis) {
            // Check if we need to reset the window
            if (currentTime - windowStartTime >= timeWindowMillis) {
                windowStartTime = currentTime;
                requestCount.set(0);
            }

            int currentRequests = requestCount.get();
            if (currentRequests < maxRequests) {
                requestCount.incrementAndGet();
                return true;
            }

            return false; // Rate limit exceeded
        }

        /**
         * Gets the number of remaining requests in the current time window.
         *
         * @param currentTime      the current timestamp
         * @param maxRequests      the maximum allowed requests
         * @param timeWindowMillis the time window duration in milliseconds
         * @return the number of remaining requests
         */
        public synchronized int getRemainingRequests(long currentTime, int maxRequests, long timeWindowMillis) {
            // Check if we need to reset the window
            if (currentTime - windowStartTime >= timeWindowMillis) {
                return maxRequests; // Fresh window, full limit available
            }

            return Math.max(0, maxRequests - requestCount.get());
        }
    }
}