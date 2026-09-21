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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, through the live server-mode edge and the compose Keycloak, that a BFF login requests the
 * scope set of the route it is for — {@code oidc.scopes} united with the endpoint's additive
 * {@code scopes} (ADR-0048) — and that the IdP then issues a token carrying that set.
 * <p>
 * <strong>The fixture.</strong> {@code endpoints/bff-scoped.yaml} declares the session route
 * {@code /bff-session/scoped} with {@code scopes: ["sheriff_it_endpoint"]}, so its needed set is
 * exactly {@code openid profile email sheriff_it_endpoint}. {@code sheriff_it_endpoint} is an
 * <em>optional</em> client scope of {@code integration-client} in {@code integration-realm.json}:
 * Keycloak issues it only when the authorization request names it. Its presence in the mediated
 * token is therefore proof that the gateway asked for it, not an artefact of the realm's defaults.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li>An unauthenticated navigation on the scoped route is redirected into the IdP with a
 *       {@code scope} parameter equal to exactly that four-member set — no member missing and none
 *       extra.</li>
 *   <li>{@code /auth/login?returnUrl=} resolves the scope set from the return target: a target on a
 *       {@code require: none} asset route requests exactly {@code oidc.scopes}, while a target on the
 *       scoped session route requests the four-member set. The second case is what keeps the first
 *       from passing vacuously — a resolver that always answered {@code oidc.scopes} would satisfy
 *       the asset case on its own.</li>
 *   <li>After a login started on the scoped route, the echo origin receives a mediated bearer whose
 *       {@code scope} claim contains {@code sheriff_it_endpoint}, while a login started on the plain
 *       {@code /bff-session} route yields a mediated bearer without it — the control that proves the
 *       realm does not issue the scope by default.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not exercise step-up — a live session
 * lacking a scope navigating onto a route that needs it — nor the scope set a refresh requests; the
 * latter is {@code BffTokenRefreshIT}'s, which needs the short-token-lifespan instance. Like every
 * {@code Bff*IT}, it replays a cookie map and asserts nothing about browser cookie policy (see
 * {@link BffKeycloakLoginFlow}).
 * <p>
 * The scope helpers are package-private so {@code BearerScopeIT} and {@code BffTokenRefreshIT}
 * read requested and granted scopes the same way instead of carrying their own copies.
 */
class BffEndpointScopesIT {

    /** The endpoint-specific scope the {@code bff-scoped} and {@code secure-scoped} routes add. */
    static final String ENDPOINT_SCOPE = "sheriff_it_endpoint";

    /** The gateway's configured {@code oidc.scopes}. */
    static final Set<String> OIDC_SCOPES = Set.of("openid", "profile", "email");

    /** {@code oidc.scopes} united with the scoped routes' additive {@code scopes}. */
    static final Set<String> SCOPED_ROUTE_SCOPES = Set.of("openid", "profile", "email", ENDPOINT_SCOPE);

    /** A path on the scoped session route; the echo upstream answers any path. */
    static final String SCOPED_SESSION_PATH = "/bff-session/scoped/get";

    /** A path on the plain session route, which declares no endpoint scope. */
    private static final String PLAIN_SESSION_PATH = "/bff-session/get";

    /** A return target on the {@code require: none} {@code assets-directory} route. */
    private static final String PUBLIC_ASSET_TARGET = "/assets/static/index.html";

    @Test
    @DisplayName("a navigation on the scoped route requests exactly oidc.scopes plus the endpoint scope")
    void navigationOnScopedRouteRequestsTheUnitedScopeSet() {
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(SCOPED_SESSION_PATH)
                .then().statusCode(302)
                .extract().response();

        assertEquals(SCOPED_ROUTE_SCOPES, requestedScopeSet(initiation),
                "a navigation login on /bff-session/scoped must request oidc.scopes united with the "
                        + "endpoint's scopes — exactly, with nothing missing and nothing extra");
    }

    @Test
    @DisplayName("/auth/login with a public asset return target requests exactly oidc.scopes")
    void loginInitiationForPublicTargetRequestsOidcScopesOnly() {
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of())
                .redirects().follow(false)
                .when().get("/auth/login?returnUrl=" + PUBLIC_ASSET_TARGET)
                .then().statusCode(302)
                .extract().response();

        assertEquals(OIDC_SCOPES, requestedScopeSet(initiation),
                "a return target on a require:none route contributes no scope, so the login must "
                        + "request oidc.scopes and nothing else");
    }

    @Test
    @DisplayName("/auth/login with a scoped-route return target requests the united scope set")
    void loginInitiationForScopedTargetRequestsTheUnitedScopeSet() {
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of())
                .redirects().follow(false)
                .when().get("/auth/login?returnUrl=" + SCOPED_SESSION_PATH)
                .then().statusCode(302)
                .extract().response();

        assertEquals(SCOPED_ROUTE_SCOPES, requestedScopeSet(initiation),
                "a return target on the scoped session route must make the login request that "
                        + "route's needed scope set");
    }

    @Test
    @DisplayName("after a login on the scoped route the echo origin sees a mediated token carrying the endpoint scope")
    void scopedLoginMediatesATokenCarryingTheEndpointScope() {
        Session session = BffKeycloakLoginFlow.login(SCOPED_SESSION_PATH);

        Response echoed = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .when().get(SCOPED_SESSION_PATH)
                .then().statusCode(200)
                .extract().response();

        Set<String> granted = grantedScopes(mediatedAuthorization(echoed));
        assertTrue(granted.contains(ENDPOINT_SCOPE),
                "the mediated token must carry " + ENDPOINT_SCOPE + ", the scope the login requested "
                        + "for this route; granted scopes were " + granted);
    }

    @Test
    @DisplayName("control: a login on the plain session route mediates a token without the endpoint scope")
    void plainLoginMediatesATokenWithoutTheEndpointScope() {
        Session session = BffKeycloakLoginFlow.login(PLAIN_SESSION_PATH);

        Response echoed = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .when().get(PLAIN_SESSION_PATH)
                .then().statusCode(200)
                .extract().response();

        Set<String> granted = grantedScopes(mediatedAuthorization(echoed));
        assertFalse(granted.contains(ENDPOINT_SCOPE),
                "the realm must not issue " + ENDPOINT_SCOPE + " unless it is requested, otherwise its "
                        + "presence after a scoped login proves nothing; granted scopes were " + granted);
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Reads the {@code scope} parameter of the authorization request the gateway redirected the
     * browser to, and returns its members as a set.
     * <p>
     * The gateway holds the needed scope set as an unordered set, so the order of the members in the
     * parameter carries no meaning and only membership is compared. A duplicated member would be
     * hidden by that set conversion, so it is rejected explicitly first.
     *
     * @param initiation the {@code 302} that starts the authorization-code flow
     * @return the requested scope members
     */
    static Set<String> requestedScopeSet(Response initiation) {
        String location = BffKeycloakLoginFlow.location(initiation);
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                () -> "expected a redirect into the OIDC authorization endpoint, got " + location);
        String rawQuery = URI.create(location).getRawQuery();
        assertNotNull(rawQuery, () -> "the authorization redirect carries no query: " + location);
        String scope = Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> parameter.startsWith("scope="))
                .map(parameter -> URLDecoder.decode(parameter.substring("scope=".length()), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the authorization redirect carries no scope parameter: "
                        + location));
        List<String> members = Arrays.asList(scope.trim().split(" +"));
        Set<String> unique = new HashSet<>(members);
        assertEquals(members.size(), unique.size(),
                () -> "the requested scope must name each member once, got '" + scope + "'");
        return unique;
    }

    /**
     * Reads the bearer the gateway injected upstream, as echoed back by go-httpbin.
     *
     * @param echoed the echo response of a mediated session call
     * @return the echoed {@code Authorization} value
     */
    static String mediatedAuthorization(Response echoed) {
        Object authorization = echoed.path("headers.Authorization");
        assertNotNull(authorization, "the session must mediate a bearer to the upstream");
        String value = authorization.toString();
        assertTrue(value.contains("Bearer"), "the mediated upstream credential must be a bearer token");
        return value;
    }

    /**
     * Decodes the {@code scope} claim of an access token.
     *
     * @param tokenOrAuthorization the raw token, or an {@code Authorization} value carrying it — also
     *                             in go-httpbin's echoed {@code [Bearer ...]} list rendering
     * @return the granted scope members
     */
    static Set<String> grantedScopes(String tokenOrAuthorization) {
        String token = tokenOrAuthorization.replaceFirst("(?i)^\\[?Bearer\\s+", "").replaceAll("]$", "");
        String[] segments = token.split("\\.");
        assertEquals(3, segments.length, "an access token must be a three-segment JWS");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        String scope = new JsonPath(payload).getString("scope");
        assertNotNull(scope, "the access token must carry a scope claim");
        return Set.copyOf(Arrays.asList(scope.trim().split(" +")));
    }
}
