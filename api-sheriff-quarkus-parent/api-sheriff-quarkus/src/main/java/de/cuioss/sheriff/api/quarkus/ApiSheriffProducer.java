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
package de.cuioss.sheriff.api.quarkus;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.cuioss.sheriff.api.ApiSheriff;
import de.cuioss.sheriff.api.config.ApiGatewayConfig;
import de.cuioss.tools.logging.CuiLogger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * CDI producer for ApiSheriff components in Quarkus applications.
 * This class automatically creates and configures ApiSheriff instances using
 * Quarkus configuration properties.
 * 
 * <h2>Configuration Properties:</h2>
 * <ul>
 *   <li>{@code api-sheriff.rate-limit} - Maximum requests per client (default: 100)</li>
 *   <li>{@code api-sheriff.time-window} - Time window in seconds (default: 60)</li>
 *   <li>{@code api-sheriff.request-timeout} - Request timeout in seconds (default: 30)</li>
 *   <li>{@code api-sheriff.cors-enabled} - Enable CORS support (default: false)</li>
 * </ul>
 * 
 * <h2>Usage Example (application.properties):</h2>
 * <pre>
 * api-sheriff.rate-limit=200
 * api-sheriff.time-window=60
 * api-sheriff.request-timeout=30
 * api-sheriff.cors-enabled=true
 * </pre>
 * 
 * <h2>Injection Example:</h2>
 * <pre>
 * {@code
 * @ApplicationScoped
 * public class ApiGatewayService {
 *     
 *     @Inject
 *     ApiSheriff apiSheriff;
 *     
 *     public boolean checkRequest(String clientId, String endpoint) {
 *         return apiSheriff.isRequestAllowed(clientId, endpoint);
 *     }
 * }
 * }
 * </pre>
 *
 * @author API Sheriff Team
 */
@ApplicationScoped
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ApiSheriffProducer {

    private static final CuiLogger log = new CuiLogger(ApiSheriffProducer.class);

    @ConfigProperty(name = "api-sheriff.rate-limit", defaultValue = "100")
    int rateLimit;

    @ConfigProperty(name = "api-sheriff.time-window", defaultValue = "60")
    long timeWindowSeconds;

    @ConfigProperty(name = "api-sheriff.request-timeout", defaultValue = "30")
    long requestTimeoutSeconds;

    @ConfigProperty(name = "api-sheriff.cors-enabled", defaultValue = "false")
    boolean corsEnabled;

    /**
     * Produces a singleton ApiGatewayConfig instance based on Quarkus configuration.
     * This configuration will be automatically injected into the ApiSheriff producer method.
     *
     * @return configured ApiGatewayConfig instance
     */
    @Produces
    @Singleton
    public ApiGatewayConfig produceApiGatewayConfig() {
        log.debug("Creating ApiGatewayConfig with rateLimit={}, timeWindow={}s, requestTimeout={}s, corsEnabled={}",
                rateLimit, timeWindowSeconds, requestTimeoutSeconds, corsEnabled);

        ApiGatewayConfig config = ApiGatewayConfig.builder()
                .rateLimit(rateLimit)
                .timeWindow(Duration.ofSeconds(timeWindowSeconds))
                .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .corsEnabled(corsEnabled)
                .build();

        log.info("ApiGatewayConfig created successfully with rate limit {} requests per {} seconds",
                rateLimit, timeWindowSeconds);

        return config;
    }

    /**
     * Produces a singleton ApiSheriff instance using the configured ApiGatewayConfig.
     * The ApiSheriff will be available for injection throughout the Quarkus application.
     *
     * @param config the ApiGatewayConfig to use for creating the ApiSheriff
     * @return configured ApiSheriff instance
     */
    @Produces
    @Singleton
    public ApiSheriff produceApiSheriff(ApiGatewayConfig config) {
        log.debug("Creating ApiSheriff instance with config: {}", config);

        ApiSheriff apiSheriff = new ApiSheriff(config);

        // Validate configuration at startup
        apiSheriff.validateConfiguration().ifPresent(error -> {
            log.error("ApiSheriff configuration validation failed: {}", error);
            throw new IllegalStateException("Invalid ApiSheriff configuration: " + error);
        });

        log.info("ApiSheriff instance created successfully and validation passed");

        return apiSheriff;
    }
}