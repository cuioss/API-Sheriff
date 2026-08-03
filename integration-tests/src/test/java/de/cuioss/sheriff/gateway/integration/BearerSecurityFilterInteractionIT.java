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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves how the two request gates <em>interact</em> on a route that carries both — an inherited
 * bearer auth floor and its own explicit {@code security_filter} — rather than proving either gate in
 * isolation.
 * <p>
 * The {@code secure-filtered} route ({@code endpoints/secure.yaml}) inherits {@code auth.require:
 * bearer} from the {@code secure} anchor and declares a {@code security_filter} with
 * {@code profile: strict} and a one-wildcard-segment {@code allowed_paths} allowlist. Before it
 * existed, the bearer-protected surface declared no explicit filter anywhere, so no request could
 * violate both gates at once and their ordering was never observable.
 * <p>
 * <strong>Which gate wins, and why that is the load-bearing assertion.</strong> The filter does. The
 * pipeline runs {@code ThoroughChecksStage} (stage 3, per-route thorough checks — the
 * {@code allowed_paths} allowlist) <em>before</em> {@code AuthenticationStage} (stage 4, offline
 * bearer validation), so a request that violates both is rejected {@code 400 PATH_NOT_ALLOWED} and
 * bearer validation never runs. The two tokenless cases below differ in <em>nothing but the path</em>
 * and land on different statuses — {@code 401} when the path is allow-listed and the request survives
 * to auth, {@code 400} when it does not — and that delta is what makes the ordering observable rather
 * than assumed.
 * <p>
 * The {@code WWW-Authenticate: Bearer} header is the second, independent discriminator: stage 4
 * attaches it to every auth rejection, so its <em>absence</em> on the 400s proves those rejections
 * came from the filter and not from a differently-shaped auth failure. Status alone could not
 * separate them.
 * <p>
 * The authenticated-but-disallowed case is what proves the filter is genuinely <em>active</em> on an
 * authenticated route rather than shadowed by the auth gate, and the fully-valid case is the matched
 * positive control proving the route works at all — without it every rejection above would be
 * consistent with a route that is simply broken.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BearerSecurityFilterInteractionIT extends BaseIntegrationTest {

    /**
     * Matches the route's {@code allowed_paths} pattern {@code /secure/filtered/{action}} — exactly
     * one wildcard segment.
     */
    private static final String ALLOWED_PATH = "/secure/filtered/echo";

    /**
     * One segment deeper than the allowlist pattern admits. The matcher compares segment counts
     * exactly, so this is a {@code PATH_NOT_ALLOWED} miss and not a route-selection miss: it still
     * resolves to the {@code secure-filtered} route via its {@code /secure/filtered} prefix.
     */
    private static final String DENIED_PATH = "/secure/filtered/deep/path";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    @Test
    @DisplayName("no token on an allow-listed path is rejected 401 — the request survives the filter and reaches auth")
    void tokenlessRequestOnAllowedPathIsRejectedByAuth() {
        // Act
        var response = given()
                .when()
                .get(ALLOWED_PATH)
                .then()
                .statusCode(401)
                .header(WWW_AUTHENTICATE, "Bearer")
                .extract();

        // Assert — the path cleared stage 3, so stage 4 is what rejected this. Paired with the case
        // below (identical request, deeper path) this is half of the ordering proof.
        assertTrue(response.contentType().contains(PROBLEM_JSON));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("no token on a path outside allowed_paths is rejected 400 — the filter wins over auth")
    void tokenlessRequestOnDeniedPathIsRejectedByTheFilter() {
        // Act — identical to the case above in every respect except the path.
        var response = given()
                .when()
                .get(DENIED_PATH)
                .then()
                .statusCode(400)
                .extract();

        // Assert — THE ordering assertion. This request violates BOTH gates, and the 400 (not 401)
        // shows the per-route filter rejected it at stage 3 before bearer validation ran at stage 4.
        // The absent WWW-Authenticate is the independent confirmation: stage 4 attaches that header to
        // every auth rejection, so its absence means stage 4 was never reached.
        assertTrue(response.contentType().contains(PROBLEM_JSON));
        assertNull(response.header(WWW_AUTHENTICATE),
                "a filter rejection must not carry WWW-Authenticate — its presence would mean the auth"
                        + " stage ran and the gate ordering is the reverse of what this asserts");
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a valid token on a path outside allowed_paths is still rejected 400 by the filter")
    void authenticatedRequestOnDeniedPathIsRejectedByTheFilter() {
        // Arrange — a real token from the trusted integration realm, so auth would admit this request.
        String authorization = "Bearer " + BearerValidationIT.mintIntegrationRealmAccessToken();

        // Act
        var response = given()
                .header("Authorization", authorization)
                .when()
                .get(DENIED_PATH)
                .then()
                .statusCode(400)
                .extract();

        // Assert — proves the filter is genuinely ACTIVE on an authenticated route and not shadowed by
        // the auth gate. Without this case, the 400 above would be equally consistent with a filter
        // that only ever fires on requests auth would have rejected anyway.
        assertTrue(response.contentType().contains(PROBLEM_JSON));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a valid token on an allow-listed path is admitted and forwarded to the upstream")
    void authenticatedRequestOnAllowedPathIsForwarded() {
        // Arrange
        String authorization = "Bearer " + BearerValidationIT.mintIntegrationRealmAccessToken();

        // Act
        var response = given()
                .header("Authorization", authorization)
                .when()
                .get(ALLOWED_PATH)
                .then()
                .statusCode(200)
                .extract();

        // Assert — the matched positive control for the whole matrix: both gates pass and the request
        // actually reaches go-httpbin. Every rejection above is therefore a decision by a specific
        // gate, not evidence of a route that never worked.
        assertEquals("GET", response.path("method"),
                "an admitted request must reach the upstream and echo its method");
    }
}
