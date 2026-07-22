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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code source: upstream} asset terminal action (ADR-0014) over the public HTTPS
 * edge against the {@code /assets/cdn} route.
 * <p>
 * The {@code assets-public} anchor's upstream asset route fetches from the secondary
 * {@code asset-origin} static server (topology alias {@code ASSET_ORIGIN}) over the same
 * fixed-topology, SSRF-controlled egress the proxy action uses — no parallel fetch stack — and
 * re-serves the response under the gateway-owned envelope. The envelope overrides the
 * {@code Content-Type} from the file extension (so a hostile or misconfigured origin cannot dictate
 * it), adds {@code X-Content-Type-Options: nosniff}, and serves only {@code GET}/{@code HEAD}.
 */
class UpstreamAssetServingIT extends BaseIntegrationTest {

    @Test
    @DisplayName("GET fetches the secondary origin and re-serves it under gateway governance")
    void getServesGovernedUpstreamAsset() {
        var response = given()
                .when()
                .get("/assets/cdn/logo.svg")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.contentType().contains("image/svg+xml"),
                "the gateway overrides the content type from the .svg extension, not the origin");
        assertTrue(response.asString().contains("<svg"), "the served body is the secondary origin's asset");
    }

    @Test
    @DisplayName("HEAD serves the governed headers with an empty body")
    void headServesEmptyBody() {
        var response = given()
                .when()
                .head("/assets/cdn/logo.svg")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.asString().isEmpty(), "a HEAD response carries the governed headers but no body");
    }

    @Test
    @DisplayName("POST is rejected 405 — an asset action serves only GET and HEAD")
    void postRejected() {
        given()
                .when()
                .post("/assets/cdn/logo.svg")
                .then()
                .statusCode(405);
    }
}
