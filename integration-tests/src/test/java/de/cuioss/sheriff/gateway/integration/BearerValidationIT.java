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
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises stage 4 (offline bearer-token validation) over the public HTTPS edge against the
 * bearer-protected {@code /secure} route.
 * <p>
 * The mounted {@code secure} anchor declares {@code require: bearer}, and the gateway's own
 * {@code token_validation} issuer loads its key set from the static JWKS file mounted at
 * {@code /app/certificates/test-jwks.json} — so the validator is ready offline with no Keycloak
 * dependency. Only <em>rejection</em> scenarios are driven here (a valid signed token would need
 * the private key, out of scope for the black-box suite): a missing token and a malformed token
 * must both be rejected {@code 401} at the gateway and the upstream must never be reached. A
 * forwarded request would carry the {@code go-httpbin} echo (a non-null {@code method}); its
 * absence is the observable proof the upstream count stayed {@code 0} on every bearer rejection.
 */
class BearerValidationIT extends BaseIntegrationTest {

    @Test
    @DisplayName("a request with no bearer token is rejected 401 and never forwarded")
    void missingTokenRejected() {
        var response = given()
                .when()
                .get("/secure/get")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a request with a malformed bearer token is rejected 401 and never forwarded")
    void malformedTokenRejected() {
        var response = given()
                .header("Authorization", "Bearer not-a-real-jwt")
                .when()
                .get("/secure/get")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("the public require:none proxy route does not demand a bearer token")
    void publicRouteDoesNotRequireBearer() {
        var response = given()
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"));
    }
}
