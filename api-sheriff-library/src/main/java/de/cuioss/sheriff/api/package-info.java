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

/**
 * API Sheriff - A lightweight API Gateway focused on Security and Performance.
 * 
 * <p>This package provides the core API Sheriff functionality for creating secure 
 * API gateways with comprehensive security features including rate limiting, 
 * authentication, and authorization capabilities.</p>
 * 
 * <h2>Core Components:</h2>
 * <ul>
 *   <li>{@link de.cuioss.sheriff.api.ApiSheriff} - Main gateway class</li>
 *   <li>{@link de.cuioss.sheriff.api.config.ApiGatewayConfig} - Configuration management</li>
 *   <li>{@link de.cuioss.sheriff.api.security.RateLimiter} - Rate limiting functionality</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>
 * {@code
 * // Create configuration
 * ApiGatewayConfig config = ApiGatewayConfig.builder()
 *     .rateLimit(100)
 *     .timeWindow(Duration.ofMinutes(1))
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .corsEnabled(true)
 *     .build();
 * 
 * // Initialize API Sheriff
 * ApiSheriff sheriff = new ApiSheriff(config);
 * 
 * // Check if request is allowed
 * if (sheriff.isRequestAllowed("client-123", "/api/users")) {
 *     // Process request
 * } else {
 *     // Reject request
 * }
 * }
 * </pre>
 * 
 * <h2>Features:</h2>
 * <ul>
 *   <li>Thread-safe rate limiting with configurable windows</li>
 *   <li>Flexible configuration system with builder pattern</li>
 *   <li>Comprehensive logging and monitoring capabilities</li>
 *   <li>Multi-tenant support with per-client rate limiting</li>
 *   <li>Lightweight and high-performance design</li>
 * </ul>
 * 
 * <h2>Integration:</h2>
 * <p>API Sheriff can be integrated into various frameworks:</p>
 * <ul>
 *   <li>Standalone Java applications</li>
 *   <li>Spring Boot applications</li>
 *   <li>Quarkus applications (with extension)</li>
 *   <li>Jakarta EE applications</li>
 * </ul>
 * 
 * @author API Sheriff Team
 * @version 1.0.0
 * @since 1.0.0
 */
package de.cuioss.sheriff.api;