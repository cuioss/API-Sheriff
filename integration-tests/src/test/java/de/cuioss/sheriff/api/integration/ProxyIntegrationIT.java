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
 * End-to-end integration tests for the minimal proxy, exercising the native
 * container over its public HTTPS port with the proxy route pointed at the
 * {@code go-httpbin} echo backend ({@code http://go-httpbin:8080/anything}).
 * <p>
 * go-httpbin's {@code /anything/*} endpoint echoes the complete received request
 * back as JSON, so these tests assert exactly what the gateway forwarded (method,
 * path remainder, query and body) and that unmatched paths are denied by default.
 */
class ProxyIntegrationIT extends BaseIntegrationTest {

    @Test
    void shouldForwardGetWithPathAndQuery() {
        var response = given()
                .when()
                .get("/proxy/orders/42?page=2&size=10")
                .then()
                .statusCode(200)
                .extract();

        assertTrue(response.contentType().contains("application/json"));
        assertEquals("GET", response.path("method"));
        assertTrue(response.path("url").toString().contains("/anything/orders/42"));
        assertEquals("2", response.path("args.page[0]"));
        assertEquals("10", response.path("args.size[0]"));
    }

    @Test
    void shouldForwardPostBody() {
        var response = given()
                .contentType("text/plain")
                .body("hello-upstream")
                .when()
                .post("/proxy/submit")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("POST", response.path("method"));
        assertTrue(response.path("url").toString().contains("/anything/submit"));
        assertEquals("hello-upstream", response.path("data"));
    }

    @Test
    void shouldReturn404ForUnmatchedPath() {
        given()
                .when()
                .get("/not-proxied/resource")
                .then()
                .statusCode(404);
    }
}
