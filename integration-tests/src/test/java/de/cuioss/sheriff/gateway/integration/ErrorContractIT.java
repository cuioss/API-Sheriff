/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the RFC 9457 {@code application/problem+json} error contract the edge renders for
 * rejected requests, and that every rejection is terminated at the gateway without reaching the
 * upstream.
 * <p>
 * Each rejection carries the failing category's {@code urn:api-sheriff:problem:*} type, its title,
 * and the HTTP status. A forwarded request would carry the {@code go-httpbin} echo (a non-null
 * {@code method}); its absence on every rejection is the observable proof the upstream count stayed
 * {@code 0}.
 */
class ErrorContractIT extends BaseIntegrationTest {

    @Test
    @DisplayName("an unmatched path is denied 404 as an input-validation problem, not forwarded")
    void unmatchedPathProblemJson() {
        var response = given()
                .when()
                .get("/no-such-route/resource")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .extract();

        assertTrue(response.path("type").toString().contains("urn:api-sheriff:problem:input-validation"));
        assertEquals("Input Validation", response.path("title"));
        assertEquals(Integer.valueOf(404), response.path("status"));
        assertNull(response.path("method"), "a denied request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a disallowed method is rejected 405 as an input-validation problem, not forwarded")
    void disallowedMethodProblemJson() {
        var response = given()
                .when()
                .patch("/proxy/get")
                .then()
                .statusCode(405)
                .contentType("application/problem+json")
                .extract();

        assertTrue(response.path("type").toString().contains("urn:api-sheriff:problem:input-validation"));
        assertEquals(Integer.valueOf(405), response.path("status"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a bearer rejection renders an authentication problem, not forwarded")
    void bearerRejectionProblemJson() {
        var response = given()
                .when()
                .get("/secure/get")
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .extract();

        assertTrue(response.path("type").toString().contains("urn:api-sheriff:problem:authentication"));
        assertEquals("Authentication", response.path("title"));
        assertEquals(Integer.valueOf(401), response.path("status"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }
}
