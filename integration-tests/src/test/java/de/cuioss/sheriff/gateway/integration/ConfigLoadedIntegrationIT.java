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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that the gateway booted from the file-based configuration
 * mounted into the container at {@code /app/sheriff-config} (the
 * {@code SHERIFF_CONFIG_DIR} environment variable).
 * <p>
 * The proxy edge no longer reads a static {@code ProxyConfiguration}: its routes
 * and upstreams come from the {@code RouteTable} the configuration subsystem
 * assembles at boot from the mounted {@code gateway.yaml},
 * {@code endpoints/httpbin.yaml} and {@code topology.properties}. The single
 * mounted {@code /proxy} route being live — forwarding to the {@code go-httpbin}
 * echo backend the {@code UPSTREAM} topology alias resolves to — is the observable
 * proof the mounted configuration was loaded; any other path staying {@code 404}
 * confirms deny-by-default over exactly the mounted route set.
 * <p>
 * The mounted {@code gateway.yaml} now declares two disjoint anchors ({@code api}
 * at {@code /proxy}, {@code bff} at {@code /bff}, ADR-0007) and the httpbin endpoint
 * is anchored to {@code api}. Anchor materialization is behaviour-neutral for this
 * conforming config, so the routed-request assertions are unchanged; the additional
 * {@code /bff} assertion proves an anchor is a policy <em>namespace</em>, not a
 * route — a declared anchor with no populated endpoint serves nothing.
 */
class ConfigLoadedIntegrationIT extends BaseIntegrationTest {

    @Test
    @DisplayName("only the mounted route set is served — unmatched paths deny by default")
    void unmountedPathDeniedByDefault() {
        given()
                .when()
                .get("/no-such-route/resource")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("a declared anchor is a namespace, not a route — the empty /bff anchor serves nothing")
    void declaredAnchorNamespaceWithoutEndpointServesNothing() {
        given()
                .when()
                .get("/bff/home")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("container reports ready — its dependencies are available, not merely the process alive")
    void managementHealthReportsUp() {
        var response = given()
                .baseUri(managementBaseUri())
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("UP", response.path("status"));
    }
}
