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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the verb gate of the request pipeline end-to-end over the public HTTPS edge.
 * <p>
 * The mounted {@code gateway.yaml} allows {@code GET, POST, PUT, DELETE}; the {@code /proxy}
 * route forwards to the {@code go-httpbin} echo backend ({@code /anything/*}), which reflects the
 * received method and body. Each allowed verb must forward and echo; a method outside the allowed
 * set must be rejected {@code 405} at the gateway <em>without</em> reaching the upstream (the
 * response is the gateway's rejection, never the echo).
 */
class PipelineVerbIT extends BaseIntegrationTest {

    @Test
    @DisplayName("GET is forwarded and echoed by the upstream")
    void getIsForwarded() {
        var response = given()
                .when()
                .get("/proxy/get?probe=verb")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"));
        assertTrue(response.path("url").toString().contains("/anything/get"));
        assertEquals("verb", response.path("args.probe[0]"));
    }

    @Test
    @DisplayName("GET forwards the multi-segment path remainder and multi-value query verbatim")
    void getForwardsPathRemainderAndMultiValueQuery() {
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
    @DisplayName("POST forwards the request body to the upstream")
    void postForwardsBody() {
        var response = given()
                .contentType("text/plain")
                .body("verb-post-body")
                .when()
                .post("/proxy/post")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("POST", response.path("method"));
        assertEquals("verb-post-body", response.path("data"));
    }

    @Test
    @DisplayName("PUT is forwarded and echoed by the upstream")
    void putIsForwarded() {
        var response = given()
                .contentType("text/plain")
                .body("verb-put-body")
                .when()
                .put("/proxy/put")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("PUT", response.path("method"));
        assertEquals("verb-put-body", response.path("data"));
    }

    @Test
    @DisplayName("DELETE is forwarded and echoed by the upstream")
    void deleteIsForwarded() {
        var response = given()
                .when()
                .delete("/proxy/delete")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("DELETE", response.path("method"));
    }

    @Test
    @DisplayName("a method outside allowed_methods is rejected 405 and never forwarded")
    void disallowedMethodRejectedWithoutForward() {
        var response = given()
                .when()
                .patch("/proxy/get")
                .then()
                .statusCode(405)
                .extract();

        // A forwarded request would carry the go-httpbin echo (a non-null "method"); its absence
        // is the observable proof the upstream count stayed 0 for this rejection.
        assertNull(response.path("method"));
    }
}
