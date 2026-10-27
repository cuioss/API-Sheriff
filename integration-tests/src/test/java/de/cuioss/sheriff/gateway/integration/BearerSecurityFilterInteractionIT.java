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
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * <strong>Which gate wins, and how that is observed.</strong> The filter does. The pipeline runs
 * {@code ThoroughChecksStage} (stage 3, per-route thorough checks — the {@code allowed_paths}
 * allowlist) <em>before</em> {@code AuthenticationStage} (stage 4, offline bearer validation), so a
 * request that violates both is refused {@code PATH_NOT_ALLOWED} and bearer validation never runs.
 * <p>
 * Both refusals are raised before the trust boundary, so the shipped default answers both to the
 * caller as the same unrouted {@code 404} (ADR-0073) — the status no longer separates them, by
 * design. The TRUE event does: each refusal moves {@code sheriff_errors_total} on the route under its
 * own event. The two tokenless cases below differ in <em>nothing but the path</em> and are metered
 * under different events — {@code TOKEN_MISSING} when the path is allow-listed and the request
 * survives to auth, {@code PATH_NOT_ALLOWED} when it does not — and that delta is what makes the
 * ordering observable rather than assumed.
 * <p>
 * The authenticated-but-disallowed case is what proves the filter is genuinely <em>active</em> on an
 * authenticated route rather than shadowed by the auth gate: the valid token has not been validated
 * when stage 3 refuses the path, so the refusal is {@code PATH_NOT_ALLOWED} and the answer is the
 * unrouted one even for a caller who would have passed. The fully-valid case is the matched positive
 * control proving the route works at all — without it every refusal above would be consistent with a
 * route that is simply broken.
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

    /** The config-fixed id of the route, the {@code route} label of its error series. */
    private static final String FILTERED_ROUTE_ID = "secure-filtered";

    @Test
    @DisplayName("no token on an allow-listed path is refused by auth — the request survives the filter and is metered TOKEN_MISSING")
    void tokenlessRequestOnAllowedPathIsRejectedByAuth() {
        // Act — the path clears stage 3, so stage 4 is what refuses this. Paired with the case below
        // (identical request, deeper path) this is half of the ordering proof.
        var response = UniformNotFoundAssertions.assertTrueEventMetered(FILTERED_ROUTE_ID, "TOKEN_MISSING",
                () -> given().when().get(ALLOWED_PATH).then().extract());

        // Assert
        UniformNotFoundAssertions.assertUnroutedProblem(response, "a tokenless request on an allow-listed path");
    }

    @Test
    @DisplayName("no token on a path outside allowed_paths is refused by the filter — metered PATH_NOT_ALLOWED, not TOKEN_MISSING")
    void tokenlessRequestOnDeniedPathIsRejectedByTheFilter() {
        // Act — identical to the case above in every respect except the path. THE ordering assertion:
        // this request violates BOTH gates, and PATH_NOT_ALLOWED (not TOKEN_MISSING) is the event it is
        // metered under, so the per-route filter refused it at stage 3 before bearer validation ran.
        var response = UniformNotFoundAssertions.assertTrueEventMetered(FILTERED_ROUTE_ID, "PATH_NOT_ALLOWED",
                () -> given().when().get(DENIED_PATH).then().extract());

        // Assert
        UniformNotFoundAssertions.assertUnroutedProblem(response, "a tokenless request on a denied path");
    }

    @Test
    @DisplayName("a valid token on a path outside allowed_paths is still refused by the filter")
    void authenticatedRequestOnDeniedPathIsRejectedByTheFilter() {
        // Arrange — a real token from the trusted integration realm, so auth would admit this request.
        String authorization = "Bearer " + BearerValidationIT.mintIntegrationRealmAccessToken();

        // Act — proves the filter is genuinely ACTIVE on an authenticated route and not shadowed by
        // the auth gate. Without this case, the refusal above would be equally consistent with a filter
        // that only ever fires on requests auth would have rejected anyway.
        var response = UniformNotFoundAssertions.assertTrueEventMetered(FILTERED_ROUTE_ID, "PATH_NOT_ALLOWED",
                () -> given().header("Authorization", authorization).when().get(DENIED_PATH).then().extract());

        // Assert
        UniformNotFoundAssertions.assertUnroutedProblem(response, "an authenticated request on a denied path");
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
        // actually reaches go-httpbin. Every refusal above is therefore a decision by a specific gate,
        // not evidence of a route that never worked.
        assertEquals("GET", response.path("method"),
                "an admitted request must reach the upstream and echo its method");
    }
}
