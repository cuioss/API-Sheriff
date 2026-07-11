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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

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
        given()
                .when()
                .get("/proxy/orders/42?page=2&size=10")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body("method", is("GET"))
                .body("url", containsString("/anything/orders/42"))
                .body("args.page[0]", is("2"))
                .body("args.size[0]", is("10"));
    }

    @Test
    void shouldForwardPostBody() {
        given()
                .contentType("text/plain")
                .body("hello-upstream")
                .when()
                .post("/proxy/submit")
                .then()
                .statusCode(200)
                .body("method", is("POST"))
                .body("url", containsString("/anything/submit"))
                .body("data", is("hello-upstream"));
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
