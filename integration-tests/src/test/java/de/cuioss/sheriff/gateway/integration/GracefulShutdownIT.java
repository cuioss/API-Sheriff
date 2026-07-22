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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the runtime health and in-flight-completion properties the edge's graceful-shutdown
 * drain depends on. A black-box suite cannot signal {@code SIGTERM} to the container mid-request
 * (the failsafe harness owns the container lifecycle and stops it at teardown), so this IT asserts
 * the observable preconditions of a clean drain: the liveness and readiness probes report the
 * process healthy, and a burst of concurrent in-flight requests all complete without a dropped or
 * refused connection — exactly the requests the {@code SIGTERM} handler must be able to drain.
 */
class GracefulShutdownIT extends BaseIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 16;

    @Test
    @DisplayName("the liveness probe reports the process alive")
    void livenessUp() {
        var response = given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("UP", response.path("status"));
    }

    @Test
    @DisplayName("the readiness probe reports the gateway ready")
    void readinessUp() {
        var response = given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("UP", response.path("status"));
    }

    @Test
    @DisplayName("a burst of concurrent in-flight requests all complete cleanly")
    void concurrentRequestsAllComplete() {
        var statuses = new ConcurrentLinkedQueue<Integer>();

        IntStream.range(0, CONCURRENT_REQUESTS).parallel().forEach(index ->
                statuses.add(given()
                        .when()
                        .get("/proxy/get?req=" + index)
                        .then()
                        .extract()
                        .statusCode()));

        assertEquals(CONCURRENT_REQUESTS, statuses.size());
        assertTrue(statuses.stream().allMatch(status -> status == 200),
                "every in-flight request must complete 200 — none dropped or refused");
    }
}
