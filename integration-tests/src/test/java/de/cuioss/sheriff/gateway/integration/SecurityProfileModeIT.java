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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the inbound-filter mode set (ADR-0023) end-to-end over the public HTTPS edge — the runtime
 * half of the deliverable whose boot half lives in {@code verify-invalid-config-fails.sh}.
 * <p>
 * Two routes on the mounted configuration carry the contrast. {@code /proxy} declares no
 * {@code security_filter.profile}, so it is governed by the gateway-wide
 * {@code security_defaults.profile: strict}; {@code /proxy/none-mode} declares
 * {@code profile: none} together with a {@code max_body_bytes} cap and an
 * {@code allowed_paths} allowlist. Both ride the same public, effectively-unauthenticated
 * {@code api} anchor, so the boot refusal does not apply to either.
 * <p>
 * <strong>What the suite is really asserting.</strong> That {@code none} is a
 * <em>partial</em> disable and nothing more. It turns off exactly two things — the relocated
 * url-parameter name/value validation and the per-route pipeline re-run — while the pre-route floor,
 * the body cap and the path allowlist all keep rejecting. A test that only proved "the none route
 * accepts what strict rejects" would pass equally well against a total bypass, which is why the
 * still-rejects cases below are the load-bearing ones.
 */
class SecurityProfileModeIT extends BaseIntegrationTest {

    /**
     * A url-parameter value the strict url-parameter pipeline rejects: a path separator inside a
     * value. Sent with URL encoding disabled so the gateway sees exactly these bytes.
     */
    private static final String REJECTED_PARAMETER_VALUE = "/home";

    /** The strict preset's query-parameter count cap; the pre-route floor enforces it for every route. */
    private static final int STRICT_PARAMETER_COUNT_CAP = 20;

    /** The {@code none} route's declared {@code max_body_bytes}. */
    private static final int NONE_ROUTE_BODY_CAP = 1024;

    private static final String NONE_ROUTE_PATH = "/proxy/none-mode/echo";

    @Nested
    @DisplayName("the gateway-wide security_defaults profile governs a route that omits one")
    class GlobalProfileFallback {

        @Test
        @DisplayName("a benign request on the inheriting route is forwarded and echoed")
        void benignRequestPassesOnInheritingRoute() {
            var response = given()
                    .when()
                    .get("/proxy/get?probe=profile-fallback")
                    .then()
                    .statusCode(200)
                    .extract();

            assertEquals("GET", response.path("method"));
            assertEquals("profile-fallback", response.path("args.probe[0]"));
        }

        @Test
        @DisplayName("a parameter value the resolved strict preset refuses is rejected 400")
        void rejectedParameterValueIsRejectedOnInheritingRoute() {
            // The /proxy route declares no profile, so it resolves security_defaults.profile: strict
            // and the relocated url-parameter validation runs under the strict preset.
            given()
                    .urlEncodingEnabled(false)
                    .when()
                    .get("/proxy/get?return_to=" + REJECTED_PARAMETER_VALUE)
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("profile: none disables the relocated url-parameter validation")
    class NoneModeDisables {

        @Test
        @DisplayName("the none route accepts and forwards a parameter value the strict route rejects")
        void noneRouteAcceptsRejectedParameterValue() {
            // The control for this assertion is rejectedParameterValueIsRejectedOnInheritingRoute
            // above: the SAME parameter value on the strict route returns 400. The echo proves the
            // request was forwarded, not merely un-rejected.
            var response = given()
                    .urlEncodingEnabled(false)
                    .when()
                    .get(NONE_ROUTE_PATH + "?return_to=" + REJECTED_PARAMETER_VALUE)
                    .then()
                    .statusCode(200)
                    .extract();

            assertEquals("GET", response.path("method"));
            assertTrue(response.path("url").toString().contains("/anything/none-mode"),
                    "the none route must reach its own upstream path");
            assertEquals(REJECTED_PARAMETER_VALUE, response.path("args.return_to[0]"),
                    "the value strict rejects must be forwarded verbatim under 'none'");
        }
    }

    @Nested
    @DisplayName("profile: none is a PARTIAL disable — the floor, the body cap and the allowlist still reject")
    class NoneModeStillRejects {

        @Test
        @DisplayName("the pre-route path filter still rejects an encoded path separator")
        void preRoutePathFilterStillRejectsEncodedSeparator() {
            // %2F inside the PATH is rejected by the non-skippable pre-route floor, which runs before
            // route selection and therefore before the route's profile is even known.
            given()
                    .urlEncodingEnabled(false)
                    .when()
                    .get("/proxy/none-mode%2Fecho")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("the pre-route collection cap still rejects an over-cap query-parameter count")
        void preRouteCollectionCapStillRejectsParameterCount() {
            // One parameter above the strict cap. The parameter COUNT cap is part of the floor even
            // though the values it bounds are validated post-route under the profile gate.
            String overCapQuery = IntStream.rangeClosed(0, STRICT_PARAMETER_COUNT_CAP)
                    .mapToObj(index -> "p" + index + "=v")
                    .reduce((left, right) -> left + "&" + right)
                    .orElseThrow();

            given()
                    .urlEncodingEnabled(false)
                    .when()
                    .get(NONE_ROUTE_PATH + "?" + overCapQuery)
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("the route body cap still rejects a declared body above max_body_bytes")
        void bodyCapStillRejectsOverCapBody() {
            // A resource guard is not an injection defence, so it does not ride the mode switch. The
            // declared Content-Length is fast-rejected before the body is read.
            String overCapBody = "x".repeat(NONE_ROUTE_BODY_CAP + 1);

            given()
                    .contentType("text/plain")
                    .body(overCapBody)
                    .when()
                    .post(NONE_ROUTE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("a body within the cap is still forwarded on the none route")
        void bodyWithinCapIsForwarded() {
            // The control for the cap assertion: the cap rejects because of the SIZE, not because
            // POST or a body is refused outright on a none route.
            var response = given()
                    .contentType("text/plain")
                    .body("none-mode-small-body")
                    .when()
                    .post(NONE_ROUTE_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            assertEquals("POST", response.path("method"));
            assertEquals("none-mode-small-body", response.path("data"));
        }

        @Test
        @DisplayName("the route allowed_paths allowlist still rejects a path outside it")
        void allowedPathsStillRejectsPathOutsideAllowlist() {
            // The allowlist pattern admits exactly one wildcard segment, so a deeper path is a
            // segment-count mismatch and is rejected 400 — under 'none' exactly as under 'strict'.
            given()
                    .when()
                    .get("/proxy/none-mode/deep/path")
                    .then()
                    .statusCode(400);
        }
    }
}
