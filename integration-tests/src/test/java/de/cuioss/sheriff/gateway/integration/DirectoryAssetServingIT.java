/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("AS-12 — asset.index and asset.fallback on the /assets/spa route")
    class IndexAndFallback {

        /** A marker only the mounted assets/index.html carries. */
        private static final String SPA_SHELL_MARKER = "api-sheriff-spa-shell";

        @Test
        @DisplayName("a directory address serves the configured index")
        void directoryAddressServesIndex() {
            var response = given()
                    .when()
                    .get("/assets/spa/")
                    .then()
                    .statusCode(200)
                    .extract();

            assertAll("the index is served through the governed envelope",
                    () -> assertTrue(response.contentType().contains("text/html"),
                            "the content type follows the served index file's name"),
                    () -> assertTrue(response.asString().contains(SPA_SHELL_MARKER),
                            "the body is the mounted index.html"));
        }

        @Test
        @DisplayName("an unknown extensionless path serves the root-level fallback")
        void unknownExtensionlessPathServesFallback() {
            var response = given()
                    .when()
                    .get("/assets/spa/deep/link")
                    .then()
                    .statusCode(200)
                    .extract();

            assertTrue(response.asString().contains(SPA_SHELL_MARKER),
                    "a client-side route with no file behind it is answered with the SPA shell");
        }

        @Test
        @DisplayName("an unknown path with an extension stays 404 — the fallback never masks a missing asset")
        void unknownPathWithExtensionIsNotFound() {
            given()
                    .when()
                    .get("/assets/spa/missing.js")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("a traversal attempt is refused 400 before it can reach index or fallback")
        void traversalIsRefusedBeforeTheAssetSource() {
            // 400, not 404: the strict inbound filter refuses an encoded dot segment at stage 0,
            // before route selection, so a traversal attempt never reaches the directory source and
            // the index/fallback substitution is never consulted. That is the stronger refusal — the
            // fallback cannot mask what the request never reaches. The source-level guarantee (index
            // and fallback never substitute for a traversal) is pinned over seven traversal
            // spellings, this one included, by DirectoryAssetSourceTest.
            given()
                    .urlEncodingEnabled(false)
                    .when()
                    .get("/assets/spa/%2e%2e/%2e%2e/etc/passwd")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("the same root without an index still answers a directory address 404")
        void routeWithoutIndexAnswersDirectoryAddressNotFound() {
            // THE CONTROL: /assets/static serves the same /app/assets root but declares no index, so the
            // index above is proven to come from the route's configuration rather than from the root.
            given()
                    .when()
                    .get("/assets/static/")
                    .then()
                    .statusCode(404);
        }
    }

    /**
     * The ordering "authentication before source resolution" cannot be observed directly from
     * outside the gateway, so it is asserted by contrast: the existing file and a file that does not
     * exist under {@code /secure-assets} must be rejected identically with {@code 401}. A gateway
     * that resolved the directory source first would answer the missing file {@code 404} — as the
     * public {@code /assets/static} route does in {@link #getMissingFileIsNotFound()} — and leak
     * which files exist to an unauthenticated caller.
     */
    @Test
    @DisplayName("an unauthenticated request to a bearer-gated asset is rejected 401 before any file is read")
    void authenticatedAssetRejectsWithoutToken() {
        var existing = given()
                .when()
                .get("/secure-assets/app.css")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertEquals("application/problem+json", problemType(existing.contentType()),
                "the rejection is rendered as an RFC 9457 problem document");

        given()
                .when()
                .get("/secure-assets/does-not-exist.css")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer");
    }

    private static String problemType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
    }
}
