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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import de.cuioss.sheriff.api.config.ApiGatewayConfig;

/**
 * Unit tests for {@link ApiSheriff}.
 * 
 * @author API Sheriff Team
 */
class ApiSheriffTest {

    private ApiGatewayConfig testConfig;
    private ApiSheriff apiSheriff;

    @BeforeEach
    void setUp() {
        testConfig = ApiGatewayConfig.builder()
            .rateLimit(10)
            .timeWindow(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(5))
            .corsEnabled(true)
            .build();
        
        apiSheriff = new ApiSheriff(testConfig);
    }

    @Test
    void shouldCreateApiSheriffWithValidConfig() {
        assertDoesNotThrow(() -> new ApiSheriff(testConfig));
        
        ApiSheriff sheriff = new ApiSheriff(testConfig);
        assertNotNull(sheriff);
        assertEquals(testConfig, sheriff.getConfiguration());
        assertNotNull(sheriff.getConfig());
        assertNotNull(sheriff.getRateLimiter());
    }

    @Test
    void shouldThrowExceptionForNullConfig() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> new ApiSheriff(null));
        assertEquals("ApiGatewayConfig must not be null", exception.getMessage());
    }

    @Test
    void shouldAllowValidRequest() {
        boolean allowed = apiSheriff.isRequestAllowed("client1", "/api/users");
        assertTrue(allowed);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void shouldThrowExceptionForInvalidClientId(String clientId) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> apiSheriff.isRequestAllowed(clientId, "/api/users"));
        assertEquals("ClientId must not be null or empty", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void shouldThrowExceptionForInvalidEndpoint(String endpoint) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> apiSheriff.isRequestAllowed("client1", endpoint));
        assertEquals("Endpoint must not be null or empty", exception.getMessage());
    }

    @Test
    void shouldRespectRateLimit() {
        String clientId = "client1";
        String endpoint = "/api/test";
        
        // First 10 requests should be allowed (rate limit is 10)
        for (int i = 0; i < 10; i++) {
            assertTrue(apiSheriff.isRequestAllowed(clientId, endpoint),
                "Request " + (i + 1) + " should be allowed");
        }
        
        // 11th request should be denied
        assertFalse(apiSheriff.isRequestAllowed(clientId, endpoint),
            "Request 11 should be denied due to rate limiting");
    }

    @Test
    void shouldAllowDifferentClientsIndependently() {
        String endpoint = "/api/test";
        
        // Exhaust rate limit for client1
        for (int i = 0; i < 10; i++) {
            assertTrue(apiSheriff.isRequestAllowed("client1", endpoint));
        }
        assertFalse(apiSheriff.isRequestAllowed("client1", endpoint));
        
        // client2 should still be allowed
        assertTrue(apiSheriff.isRequestAllowed("client2", endpoint));
    }

    @Test
    void shouldValidateConfigurationCorrectly() {
        Optional<String> validationResult = apiSheriff.validateConfiguration();
        assertTrue(validationResult.isEmpty(), "Valid configuration should pass validation");
    }

    @Test
    void shouldDetectInvalidRateLimit() {
        ApiGatewayConfig invalidConfig = ApiGatewayConfig.builder()
            .rateLimit(-1)
            .timeWindow(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(5))
            .build();
        
        ApiSheriff sheriff = new ApiSheriff(invalidConfig);
        Optional<String> validationResult = sheriff.validateConfiguration();
        
        assertTrue(validationResult.isPresent());
        assertEquals("Rate limit must be greater than 0", validationResult.get());
    }

    @Test
    void shouldDetectInvalidTimeWindow() {
        ApiGatewayConfig invalidConfig = ApiGatewayConfig.builder()
            .rateLimit(10)
            .timeWindow(-1L)
            .requestTimeout(Duration.ofSeconds(5))
            .build();
        
        ApiSheriff sheriff = new ApiSheriff(invalidConfig);
        Optional<String> validationResult = sheriff.validateConfiguration();
        
        assertTrue(validationResult.isPresent());
        assertEquals("Time window must be greater than 0", validationResult.get());
    }

    @Test
    void shouldResetClientState() {
        String clientId = "client1";
        String endpoint = "/api/test";
        
        // Exhaust rate limit
        for (int i = 0; i < 10; i++) {
            assertTrue(apiSheriff.isRequestAllowed(clientId, endpoint));
        }
        assertFalse(apiSheriff.isRequestAllowed(clientId, endpoint));
        
        // Reset client state
        assertDoesNotThrow(() -> apiSheriff.resetClientState(clientId));
        
        // Should be allowed again after reset
        assertTrue(apiSheriff.isRequestAllowed(clientId, endpoint));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void shouldThrowExceptionWhenResettingInvalidClientId(String clientId) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> apiSheriff.resetClientState(clientId));
        assertEquals("ClientId must not be null or empty", exception.getMessage());
    }

    @Test
    void shouldReturnCorrectConfiguration() {
        ApiGatewayConfig returnedConfig = apiSheriff.getConfiguration();
        
        assertNotNull(returnedConfig);
        assertEquals(testConfig, returnedConfig);
        assertEquals(10, returnedConfig.getRateLimit());
        assertEquals(Duration.ofSeconds(1).toMillis(), returnedConfig.getTimeWindow());
        assertEquals(Duration.ofSeconds(5).toMillis(), returnedConfig.getRequestTimeout());
        assertTrue(returnedConfig.isCorsEnabled());
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        String clientId = "concurrentClient";
        String endpoint = "/api/concurrent";
        int threadCount = 5;
        int requestsPerThread = 3;
        
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount * requestsPerThread];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    int resultIndex = threadIndex * requestsPerThread + j;
                    results[resultIndex] = apiSheriff.isRequestAllowed(clientId, endpoint);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Count allowed requests
        int allowedCount = 0;
        for (boolean result : results) {
            if (result) {
                allowedCount++;
            }
        }
        
        // Should respect rate limit even with concurrent access
        assertTrue(allowedCount <= 10, "Should not exceed rate limit even with concurrent requests");
    }
}