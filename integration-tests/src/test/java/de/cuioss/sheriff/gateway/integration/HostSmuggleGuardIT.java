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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the runtime Host-vs-SNI smuggle guard end-to-end against the running edge: a
 * <em>terminated</em> request whose {@code Host} header names a {@code tls.passthrough_sni} hostname
 * is rejected {@code 404} ({@code PASSTHROUGH_HOST_SMUGGLED}) before route selection, so the
 * passthrough backend's reserved identity cannot be reached through the terminated listener. A benign
 * {@code Host} is still routed normally, confirming the guard is a targeted pre-check, not a blanket
 * denial.
 * <p>
 * This is the running-edge complement to the unit-level {@code PassthroughHostGuardStageTest}: the
 * unit test proves the stage logic in isolation, this IT proves the stage is actually wired into the
 * terminated pipeline ahead of routing.
 * <p>
 * <strong>Runtime precondition</strong>: the {@code -Pintegration-tests} stack configures a
 * {@code passthrough_sni} entry for {@link #passthroughSni()}.
 */
class HostSmuggleGuardIT extends BaseIntegrationTest {

    private static String passthroughSni() {
        return System.getProperty("test.passthrough.sni", "passthrough.test.example");
    }

    @Test
    @DisplayName("a terminated request whose Host names a passthrough SNI is rejected 404, not forwarded")
    void smuggledHostRejected() {
        var response = given()
                .header("Host", passthroughSni())
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .extract();

        assertTrue(response.path("type").toString().contains("urn:api-sheriff:problem:input-validation"),
                "a Host-smuggle rejection must render the input-validation problem type");
        assertNull(response.path("method"),
                "a smuggled request must be rejected before route selection — it must not reach the upstream");
    }

    @Test
    @DisplayName("a benign Host on the same route is routed normally, proving a targeted guard")
    void benignHostStillRouted() {
        var response = given()
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(200)
                .extract();
        assertTrue(response.path("method") != null,
                "a benign terminated request must still be forwarded to the upstream");
    }
}
