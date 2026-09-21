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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the RFC 6750 §3.1 {@code insufficient_scope} rejection on a bearer route that needs a scope
 * beyond the gateway's {@code oidc.scopes}, over the public HTTPS edge with real Keycloak tokens.
 * <p>
 * <strong>The fixture.</strong> {@code endpoints/secure-scoped.yaml} declares the bearer route
 * {@code /secure/scoped} with {@code scopes: ["sheriff_it_endpoint"]}, so a presented token must
 * carry {@code openid profile email sheriff_it_endpoint}. Both tokens below come from the
 * {@code integration} realm's password grant for {@code integration-client}; they differ only in
 * the {@code scope} the grant requests. {@code sheriff_it_endpoint} is an <em>optional</em> client
 * scope, so a token carries it exactly when the grant asked for it — each test first asserts that on
 * the token itself, so a realm change that issued it by default would fail loudly here rather than
 * turn the rejection case vacuous.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li>A valid token lacking the endpoint scope is answered {@code 403} with
 *       {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="sheriff_it_endpoint"} —
 *       the {@code scope} attribute naming the missing scope — and the request never reaches the
 *       upstream: the go-httpbin echo (a non-null {@code method}) is absent.</li>
 *   <li>The same token is admitted on {@code /secure}, which declares no endpoint scope — the
 *       control that pins the {@code 403} to the missing scope rather than to token validation.</li>
 *   <li>A token whose grant requested the endpoint scope as well is admitted {@code 200} and
 *       forwarded on {@code /secure/scoped}.</li>
 * </ul>
 */
class BearerScopeIT extends BaseIntegrationTest {

    /** A path on the {@code secure-scoped} route; the echo upstream answers any path. */
    private static final String SCOPED_BEARER_PATH = "/secure/scoped/get";

    /** A path on the {@code secure-proxy} route, which declares no endpoint scope. */
    private static final String PLAIN_BEARER_PATH = "/secure/get";

    /** The exact challenge RFC 6750 §3.1 prescribes for the one missing scope. */
    private static final String INSUFFICIENT_SCOPE_CHALLENGE =
            "Bearer error=\"insufficient_scope\", scope=\"" + BffEndpointScopesIT.ENDPOINT_SCOPE + "\"";

    @Test
    @DisplayName("a token without the endpoint scope is rejected 403 insufficient_scope and never forwarded")
    void tokenWithoutEndpointScopeRejectedInsufficientScope() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE);
        Set<String> granted = BffEndpointScopesIT.grantedScopes(token);
        assertFalse(granted.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: a grant not requesting " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + " must not carry it, otherwise this case proves nothing; granted " + granted);

        var response = given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(SCOPED_BEARER_PATH)
                .then()
                .statusCode(403)
                .header("WWW-Authenticate", INSUFFICIENT_SCOPE_CHALLENGE)
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"),
                "an insufficient_scope rejection must render RFC 9457 problem+json");
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("control: the same oidc.scopes-only token is admitted on the route declaring no endpoint scope")
    void tokenWithoutEndpointScopeAdmittedOnPlainBearerRoute() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE);

        var response = given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(PLAIN_BEARER_PATH)
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"),
                "a token carrying oidc.scopes must be admitted where no endpoint scope is needed");
    }

    @Test
    @DisplayName("a token carrying the endpoint scope is admitted 200 and forwarded")
    void tokenWithEndpointScopeAdmitted() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(
                BearerValidationIT.OIDC_SCOPE + " " + BffEndpointScopesIT.ENDPOINT_SCOPE);
        Set<String> granted = BffEndpointScopesIT.grantedScopes(token);
        assertTrue(granted.containsAll(BffEndpointScopesIT.SCOPED_ROUTE_SCOPES),
                "precondition: the grant must issue every scope the route needs; granted " + granted);

        var response = given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get(SCOPED_BEARER_PATH)
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"),
                "a token carrying every needed scope must be admitted and reach the upstream");
    }
}
