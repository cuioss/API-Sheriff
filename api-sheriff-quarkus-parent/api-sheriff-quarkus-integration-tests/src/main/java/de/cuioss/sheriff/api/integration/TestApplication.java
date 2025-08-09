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
package de.cuioss.sheriff.api.integration;

import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ApplicationPath;

/**
 * JAX-RS application for API Sheriff integration tests.
 * This class configures the REST application context for integration testing
 * of the API Sheriff Quarkus extension.
 *
 * @author API Sheriff Team
 */
@ApplicationPath("/api")
public class TestApplication extends Application {
    // JAX-RS application configuration
}