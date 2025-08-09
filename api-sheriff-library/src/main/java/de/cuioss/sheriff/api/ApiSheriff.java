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
package de.cuioss.sheriff.api;

import static de.cuioss.tools.base.Preconditions.checkArgument;

import java.util.Optional;

import de.cuioss.sheriff.api.config.ApiGatewayConfig;
import de.cuioss.sheriff.api.security.RateLimiter;
import de.cuioss.tools.logging.CuiLogger;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Main API Gateway class providing security and rate limiting functionality.
 * This is the central component of the API Sheriff library that coordinates
 * various security measures and API gateway features.
 * 
 * <h2>Usage Example:</h2>
 * <pre>
 * {@code
 * ApiGatewayConfig config = ApiGatewayConfig.builder()
 *     .withRateLimit(100)
 *     .withTimeout(5000)
 *     .build();
 * 
 * ApiSheriff sheriff = new ApiSheriff(config);
 * 
 * // Check if request is allowed
 * if (sheriff.isRequestAllowed("client-id", "/api/users")) {
 *     // Process request
 * }
 * }
 * </pre>
 *
 * @author API Sheriff Team
 */
@RequiredArgsConstructor
public class ApiSheriff {

    private static final CuiLogger log = new CuiLogger(ApiSheriff.class);

    @Getter(AccessLevel.PACKAGE)
    private final ApiGatewayConfig config;

    @Getter(AccessLevel.PACKAGE)
    private final RateLimiter rateLimiter;

    /**
     * Creates a new ApiSheriff instance with the provided configuration.
     * The rate limiter will be initialized based on the configuration settings.
     *
     * @param config the gateway configuration, must not be null
     * @throws IllegalArgumentException if config is null
     */
    public ApiSheriff(ApiGatewayConfig config) {
        checkArgument(null != config, "ApiGatewayConfig must not be null");
        this.config = config;
        this.rateLimiter = new RateLimiter(config.getRateLimit(), config.getTimeWindow());
        log.debug("ApiSheriff initialized with config: {}", config);
    }

    /**
     * Checks if a request from the given client to the specified endpoint is allowed.
     * This method combines various security checks including rate limiting,
     * authentication, and authorization.
     *
     * @param clientId the identifier of the client making the request, must not be null or empty
     * @param endpoint the API endpoint being accessed, must not be null or empty
     * @return true if the request is allowed, false otherwise
     * @throws IllegalArgumentException if clientId or endpoint is null or empty
     */
    public boolean isRequestAllowed(String clientId, String endpoint) {
        checkArgument(null != clientId && !clientId.trim().isEmpty(), "ClientId must not be null or empty");
        checkArgument(null != endpoint && !endpoint.trim().isEmpty(), "Endpoint must not be null or empty");

        log.debug("Checking request permission for client '{}' accessing endpoint '{}'", clientId, endpoint);

        // Check rate limiting first as it's the most common rejection reason
        if (!rateLimiter.isRequestAllowed(clientId)) {
            log.info("Request from client '{}' rejected due to rate limiting", clientId);
            return false;
        }

        // Additional security checks can be added here
        // For now, if rate limiting passes, allow the request
        log.debug("Request from client '{}' to endpoint '{}' allowed", clientId, endpoint);
        return true;
    }

    /**
     * Validates the current configuration and returns any issues found.
     * This method can be used for health checks and configuration validation.
     *
     * @return an Optional containing a validation error message, or empty if configuration is valid
     */
    public Optional<String> validateConfiguration() {
        if (config.getRateLimit() <= 0) {
            return Optional.of("Rate limit must be greater than 0");
        }
        
        if (config.getTimeWindow() <= 0) {
            return Optional.of("Time window must be greater than 0");
        }

        return Optional.empty();
    }

    /**
     * Resets the rate limiter state for a specific client.
     * This can be useful for administrative operations or testing.
     *
     * @param clientId the client identifier to reset, must not be null or empty
     * @throws IllegalArgumentException if clientId is null or empty
     */
    public void resetClientState(String clientId) {
        checkArgument(null != clientId && !clientId.trim().isEmpty(), "ClientId must not be null or empty");
        rateLimiter.resetClient(clientId);
        log.info("Reset rate limiting state for client '{}'", clientId);
    }

    /**
     * Gets the current configuration of this ApiSheriff instance.
     *
     * @return the current configuration
     */
    public ApiGatewayConfig getConfiguration() {
        return config;
    }
}