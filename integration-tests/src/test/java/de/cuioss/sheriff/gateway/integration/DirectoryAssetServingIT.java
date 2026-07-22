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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code source: directory} asset terminal action (ADR-0014) over the public HTTPS
 * edge against the {@code /assets/static} route, and the auth-before-source ordering against the
 * bearer-gated {@code /secure-assets} route.
 * <p>
 * The {@code assets-public} anchor ({@code type: asset}, {@code access: public}) serves files from
 * the read-only {@code /app/assets} volume mount through the gateway-owned response envelope: the
 * {@code Content-Type} is set from the file extension (never from the source), {@code nosniff} is
 * added, and only {@code GET}/{@code HEAD} are served. The {@code assets-secure} anchor
 * ({@code access: authenticated}) proves auth precedes source resolution — an unauthenticated
 * request is rejected {@code 401} at stage 4 and no file is ever read.
 */
class DirectoryAssetServingIT extends BaseIntegrationTest {

    @Test
    @DisplayName("GET serves a mounted file with the gateway-governed content type and nosniff")
    void getServesGovernedFile() {
        var response = given()
                .when()
                .get("/assets/static/app.css")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.contentType().contains("text/css"),
                "the gateway sets Content-Type from the .css extension, not the source");
        assertTrue(response.asString().contains("color"), "the served body is the mounted file's content");
    }

    @Test
    @DisplayName("HEAD serves the governed headers with an empty body")
    void headServesEmptyBody() {
        var response = given()
                .when()
                .head("/assets/static/app.css")
                .then()
                .statusCode(200)
                .header("X-Content-Type-Options", "nosniff")
                .extract();

        assertTrue(response.asString().isEmpty(), "a HEAD response carries the governed headers but no body");
    }

    @Test
    @DisplayName("GET for a missing file is a confinement-clean 404")
    void getMissingFileIsNotFound() {
        given()
                .when()
                .get("/assets/static/does-not-exist.css")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("POST is rejected 405 — an asset action serves only GET and HEAD")
    void postRejected() {
        given()
                .when()
                .post("/assets/static/app.css")
                .then()
                .statusCode(405);
    }

    @Test
    @DisplayName("an unauthenticated request to a bearer-gated asset is rejected 401 before any file is read")
    void authenticatedAssetRejectsWithoutToken() {
        var response = given()
                .when()
                .get("/secure-assets/app.css")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertEquals("application/problem+json", problemType(response.contentType()),
                "the rejection is rendered as an RFC 9457 problem document");
    }

    private static String problemType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
    }
}
