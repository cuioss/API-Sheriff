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

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for API Sheriff Quarkus extension.
 * These tests verify that the API Sheriff components are properly
 * integrated and functional in a Quarkus application context.
 *
 * @author API Sheriff Team
 */
@QuarkusIntegrationTest
public class ApiSheriffIntegrationIT {

    /**
     * Test that the health endpoint returns a successful response,
     * indicating that API Sheriff is properly configured.
     */
    @Test
    public void testApiSheriffHealthEndpoint() {
        given()
            .when()
                .get("/api/test/health")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", is("UP"))
                .body("apiSheriff", is("available"));
    }

    /**
     * Test that the info endpoint returns expected information.
     */
    @Test
    public void testInfoEndpoint() {
        given()
            .when()
                .get("/api/test/info")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", containsString("API Sheriff Integration Test"))
                .body("version", containsString("1.0.0-SNAPSHOT"));
    }

    /**
     * Test that the Quarkus health check endpoint is available.
     */
    @Test
    public void testQuarkusHealthEndpoint() {
        given()
            .when()
                .get("/q/health")
            .then()
                .statusCode(200)
                .contentType("application/json");
    }

    /**
     * Test that metrics endpoint is available (from Micrometer integration).
     */
    @Test
    public void testMetricsEndpoint() {
        given()
            .when()
                .get("/q/metrics")
            .then()
                .statusCode(200);
    }
}