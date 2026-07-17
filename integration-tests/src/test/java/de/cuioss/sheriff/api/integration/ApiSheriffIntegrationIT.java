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
package de.cuioss.sheriff.api.integration;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for API Sheriff Quarkus extension.
 * These tests verify that the API Sheriff components are properly
 * integrated and functional in a Quarkus application context.
 *
 * @author API Sheriff Team
 */
class ApiSheriffIntegrationIT extends BaseIntegrationTest {

    /**
     * Test that the health endpoint returns a successful response,
     * indicating that API Sheriff is properly configured.
     */
    @Test
    void apiSheriffHealthEndpoint() {
        var response = given()
                .when()
                .get("/api/health")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .extract();

        assertEquals("UP", response.path("status"));
        assertTrue(response.path("apiSheriff").toString().contains("API Sheriff is operational"));
    }

    /**
     * Test that the info endpoint returns expected information.
     */
    @Test
    void infoEndpoint() {
        var response = given()
                .when()
                .get("/api/info")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .extract();

        assertTrue(response.path("message").toString().contains("API Sheriff Gateway"));
        assertTrue(response.path("version").toString().contains("1.0.0-SNAPSHOT"));
    }

    /**
     * Test that the Quarkus health check endpoint is available on the management interface.
     */
    @Test
    void quarkusHealthEndpoint() {
        given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health")
                .then()
                .statusCode(200)
                .contentType("application/json");
    }

    /**
     * Test that metrics endpoint is available on the management interface.
     */
    @Test
    void metricsEndpoint() {
        given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200);
    }
}