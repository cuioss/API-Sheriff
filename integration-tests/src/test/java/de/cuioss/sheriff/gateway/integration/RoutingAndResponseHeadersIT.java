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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Acceptance of the route-table and response-path behaviour against the native image over the public
 * HTTPS edge: exact routes and the redirect terminal action (AS-3), asset-only endpoints without a
 * {@code base_url} (AS-4), anchor-scoped security headers with their per-header precedence (AS-8), the
 * appended {@code upstream.path} (AS-9) and the opt-in {@code Location} rewrite (AS-11).
 * <p>
 * The stack runs the native binary, so every configuration record these features introduced — the
 * redirect block, the header modes, the asset index and fallback, the rewrite toggle — is bound by the
 * native image at boot: a missing reflection registration would stop the stack before any test here ran.
 * <p>
 * Every assertion is driven by the mounted descriptors: {@code sheriff-config/gateway.yaml} (the global
 * {@code security_headers} block and the {@code api} anchor block), {@code endpoints/httpbin.yaml} (the
 * exact redirect route), {@code endpoints/origin-headers.yaml} (the precedence and rewrite routes) and
 * {@code endpoints/assets.yaml}. Redirects are never followed, so the gateway's own answer is observed.
 */
class RoutingAndResponseHeadersIT extends BaseIntegrationTest {

    private static final String HSTS = "Strict-Transport-Security";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String CSP = "Content-Security-Policy";
    private static final String LOCATION = "Location";

    /** The global block's HSTS value, as {@code sheriff-config/gateway.yaml} declares it. */
    private static final String GLOBAL_HSTS = "max-age=31536000; includeSubDomains";
    /** The {@code api} anchor block's policy, as {@code sheriff-config/gateway.yaml} declares it. */
    private static final String ANCHOR_POLICY = "default-src 'self'";
    /** The only origin the global CORS policy allows. */
    private static final String CORS_ORIGIN = "https://sheriff.test";

    @Nested
    @DisplayName("AS-3 — an exact redirect route inside a prefix route's namespace")
    class ExactRedirectRoute {

        @Test
        @DisplayName("the exact address is answered 308 at the gateway with the query kept and no upstream echo")
        void exactAddressIsRedirectedAtTheGateway() {
            ExtractableResponse<Response> response = given()
                    .redirects().follow(false)
                    .when()
                    .get("/proxy/redirect-entry?probe=kept")
                    .then()
                    .extract();

            assertAll("the redirect terminal action, not the /proxy prefix route, answered",
                    () -> assertEquals(308, response.statusCode()),
                    () -> assertEquals("/proxy/redirect-entry/?probe=kept", response.header(LOCATION),
                            "keep_query appends the raw inbound query to the configured location"),
                    () -> assertTrue(response.asString().isEmpty(),
                            "a redirect answer carries no body, so no upstream echo: " + response.asString()),
                    () -> assertEquals("DENY", response.header(FRAME_OPTIONS),
                            "the redirect is a routed response under the api anchor and carries its block"));
        }

        @Test
        @DisplayName("the trailing-slash address is a different address that the prefix route proxies")
        void trailingSlashAddressFallsThroughToThePrefixRoute() {
            ExtractableResponse<Response> response = given()
                    .redirects().follow(false)
                    .when()
                    .get("/proxy/redirect-entry/?probe=control")
                    .then()
                    .extract();

            assertAll("THE CONTROL: an exact route never matches a different spelling of its path",
                    () -> assertEquals(200, response.statusCode()),
                    () -> assertTrue(response.path("url").toString().contains("/anything/redirect-entry"),
                            "the prefix route forwarded the request to the echo upstream: " + response.asString()));
        }
    }

    @Nested
    @DisplayName("AS-4 — asset-only endpoints boot and serve without a base_url")
    class AssetOnlyEndpoints {

        @Test
        @DisplayName("the base_url-free public asset endpoint serves a file")
        void baseUrlFreeAssetEndpointServes() {
            // endpoints/assets.yaml and endpoints/assets-secure.yaml declare no base_url. Had either been
            // refused, the whole gateway would have failed to boot, so a served asset is the proof.
            given()
                    .when()
                    .get("/assets/static/app.css")
                    .then()
                    .statusCode(200);
        }
    }

    @Nested
    @DisplayName("AS-8 — security_headers resolve per route, wholesale, with per-header precedence")
    class SecurityHeaders {

        @Test
        @DisplayName("a routed /proxy response carries the api anchor block and not the global HSTS")
        void proxyResponseCarriesAnchorBlockWholesale() {
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/proxy/get?probe=anchor-block")
                    .then()
                    .statusCode(200)
                    .extract();

            assertAll("the anchor block replaces the global block wholesale",
                    () -> assertEquals("DENY", response.header(FRAME_OPTIONS),
                            "the echo sends no X-Frame-Options, so the default-mode anchor value is emitted"),
                    () -> assertEquals(ANCHOR_POLICY, response.header(CSP)),
                    () -> assertNull(response.header(HSTS),
                            "the global HSTS does not survive an anchor block that omits it"));
        }

        @Test
        @DisplayName("a /assets response carries the global block and never the api anchor's frame_deny")
        void assetResponseCarriesGlobalBlock() {
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/assets/static/app.css")
                    .then()
                    .statusCode(200)
                    .extract();

            assertAll("the assets-public anchor declares no block, so the global block applies",
                    () -> assertEquals(GLOBAL_HSTS, response.header(HSTS)),
                    () -> assertNull(response.header(FRAME_OPTIONS),
                            "the api anchor's frame_deny applies only under that anchor"),
                    () -> assertNull(response.header(CSP)));
        }

        @Test
        @DisplayName("a pre-route 404 carries the global block")
        void preRouteRejectionCarriesGlobalBlock() {
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/no-route-is-declared-here")
                    .then()
                    .statusCode(404)
                    .extract();

            assertAll("no route was selected, so only the global block can apply",
                    () -> assertEquals(GLOBAL_HSTS, response.header(HSTS)),
                    () -> assertNull(response.header(FRAME_OPTIONS)));
        }

        @Test
        @DisplayName("default mode keeps the origin's X-Frame-Options; set mode overwrites the origin's policy")
        void defaultModeKeepsOriginValueAndSetModeOverwrites() {
            // go-httpbin's /response-headers echoes each query parameter back as a response header, so
            // the origin itself sends both headers here.
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/proxy/origin-headers?X-Frame-Options=SAMEORIGIN&Content-Security-Policy=origin-policy")
                    .then()
                    .statusCode(200)
                    .extract();

            assertAll("precedence is decided per header by the anchor's header_modes",
                    () -> assertEquals("SAMEORIGIN", response.header(FRAME_OPTIONS),
                            "frame_deny is in default mode: the origin value is relayed untouched"),
                    () -> assertEquals(ANCHOR_POLICY, response.header(CSP),
                            "content_security_policy is in set mode: the origin value is overwritten"));
        }

        @Test
        @DisplayName("default mode emits the gateway value when the origin sends none")
        void defaultModeFillsAnAbsentOriginValue() {
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/proxy/origin-headers")
                    .then()
                    .statusCode(200)
                    .extract();

            assertEquals("DENY", response.header(FRAME_OPTIONS),
                    "THE CONTROL: the same route and mode emit DENY once the origin stops sending the header");
        }

        @Test
        @DisplayName("a CORS preflight on a bearer route is still answered 204 before authentication")
        void corsPreflightStaysPreAuthentication() {
            ExtractableResponse<Response> response = given()
                    .header("Origin", CORS_ORIGIN)
                    .header("Access-Control-Request-Method", "GET")
                    .when()
                    .options("/secure/anything")
                    .then()
                    .extract();

            assertAll("the global CORS policy answers the preflight at stage 0",
                    () -> assertEquals(204, response.statusCode(),
                            "a preflight on the bearer-gated /secure namespace is not challenged"),
                    () -> assertEquals(CORS_ORIGIN, response.header("Access-Control-Allow-Origin")),
                    () -> assertNull(response.header("WWW-Authenticate"),
                            "the preflight never reaches the authentication stage"),
                    () -> assertEquals(GLOBAL_HSTS, response.header(HSTS),
                            "no route is selected for a preflight, so it carries the global block"));
        }
    }

    @Nested
    @DisplayName("AS-9 — upstream.path appends to the alias base path")
    class AppendedUpstreamPath {

        @Test
        @DisplayName("the migrated minimal-mode route still reaches /anything/minimal-mode on the echo upstream")
        void migratedRouteKeepsItsEffectiveUpstreamUri() {
            // The UPSTREAM alias carries /anything and the route declares upstream.path: /minimal-mode,
            // so the effective URI is byte-identical to the value the route declared before the migration.
            ExtractableResponse<Response> response = given()
                    .when()
                    .get("/proxy/minimal-mode/echo")
                    .then()
                    .statusCode(200)
                    .extract();

            assertTrue(response.path("url").toString().contains("/anything/minimal-mode/echo"),
                    "alias base + upstream.path + remainder: " + response.asString());
        }
    }

    @Nested
    @DisplayName("AS-11 — rewrite_location maps an in-base Location onto the route and leaves every other untouched")
    class LocationRewrite {

        private ExtractableResponse<Response> redirectTo(String url) {
            // URL encoding is disabled so the gateway receives exactly this query; the route opts into
            // profile: minimal precisely because a URL is a parameter value the strict pipeline refuses.
            return given()
                    .urlEncodingEnabled(false)
                    .redirects().follow(false)
                    .when()
                    .get("/proxy/location-rewrite?url=" + url)
                    .then()
                    .extract();
        }

        @Test
        @DisplayName("an absolute Location inside the upstream base path is rewritten onto the route prefix")
        void absoluteInBaseLocationIsRewritten() {
            ExtractableResponse<Response> response = redirectTo("http://go-httpbin:8080/redirect-to/landing");

            assertAll("the upstream origin and base path are mapped onto the gateway route",
                    () -> assertEquals(302, response.statusCode()),
                    () -> assertEquals("/proxy/location-rewrite/landing", response.header(LOCATION)));
        }

        @Test
        @DisplayName("a relative Location inside the upstream base path is rewritten onto the route prefix")
        void relativeInBaseLocationIsRewritten() {
            ExtractableResponse<Response> response = redirectTo("/redirect-to/next");

            assertEquals("/proxy/location-rewrite/next", response.header(LOCATION));
        }

        @Test
        @DisplayName("a foreign absolute Location is relayed untouched")
        void foreignLocationIsUntouched() {
            ExtractableResponse<Response> response = redirectTo("https://elsewhere.example/landing");

            assertAll("THE CONTROL: the rewrite never touches a location outside the route upstream",
                    () -> assertEquals(302, response.statusCode()),
                    () -> assertEquals("https://elsewhere.example/landing", response.header(LOCATION)));
        }
    }
}
