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
package de.cuioss.sheriff.api.gateway;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

/**
 * Tests for {@link GatewayResource}.
 */
@QuarkusTest
class GatewayResourceTest {

    @BeforeAll
    static void setup() {
        RestAssured.useRelaxedHTTPSValidation();
    }

    @Test
    void shouldReturnHealthUp() {
        var response = given()
                .when().get("/api/health")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("UP", response.path("status"));
        assertEquals("API Sheriff is operational", response.path("apiSheriff"));
    }

    @Test
    void shouldReturnInfo() {
        var response = given()
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("API Sheriff Gateway", response.path("message"));
        assertNotNull(response.path("version"));
    }
}
