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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, through the live server-mode edge and the compose Keycloak, that a BFF login is granted the
 * scope set of the route it is for — {@code oidc.scopes} united with the endpoint's additive
 * {@code scopes} (ADR-0048) — by reading the token the IdP issues for it.
 * <p>
 * <strong>The fixture.</strong> {@code endpoints/bff-scoped.yaml} declares the session route
 * {@code /bff-session/scoped} with {@code scopes: ["sheriff_it_endpoint"]}, so its needed set is
 * exactly {@code openid profile email sheriff_it_endpoint}. {@code sheriff_it_endpoint} is an
 * <em>optional</em> client scope of {@code integration-client} in {@code integration-realm.json}:
 * Keycloak issues it only when the authorization request names it. Its presence in the mediated
 * token is therefore proof that the gateway asked for it, not an artefact of the realm's defaults.
 * <p>
 * <strong>How the requested scope set is observed.</strong> The gateway pushes its authorization
 * request (RFC 9126), so the login redirect carries {@code client_id} and {@code request_uri} and no
 * {@code scope} parameter: the request is not readable from the browser's side. Every case below
 * therefore completes the login and reads the {@code scope} claim of the token the gateway then
 * mediates to the echo origin. The optional scope is present in that token exactly when the pushed
 * request asked for it.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li>A login started by an unauthenticated navigation on the scoped route mediates a token that
 *       carries every member of the four-member set.</li>
 *   <li>{@code /auth/login?returnUrl=} resolves the scope set from the return target: a login for a
 *       target on a {@code require: none} asset route mediates a token <em>without</em>
 *       {@code sheriff_it_endpoint}, while a login for a target on the scoped session route mediates
 *       one <em>with</em> it. The second case is what keeps the first from passing vacuously — a
 *       resolver that always answered {@code oidc.scopes} would satisfy the asset case on its own.</li>
 *   <li>A login started on the plain {@code /bff-session} route yields a mediated bearer without the
 *       endpoint scope — the control that proves the realm does not issue the scope by default.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not prove that the gateway requests
 * <em>exactly</em> the needed set with nothing extra. A granted token also carries the realm's default
 * client scopes, whatever the request named, so "nothing extra" cannot be read from it. That property
 * is proven at unit level against the pushed request body a stub identity provider records
 * ({@code BffRuntimeProducerTest}, the scope-set cases of the produced runtime, and
 * {@code PushedAuthorizationRequestsTest}). Nor does this suite exercise step-up — a live session
 * lacking a scope navigating onto a route that needs it — or the scope set a refresh requests; the
 * latter is {@code BffTokenRefreshIT}'s, which needs the short-token-lifespan instance. Like every
 * {@code Bff*IT}, it replays a cookie map and asserts nothing about browser cookie policy (see
 * {@link BffKeycloakLoginFlow}).
 * <p>
 * The scope helpers are package-private so {@code BearerScopeIT}, {@code BffSessionFallbackIT} and
 * {@code BffTokenRefreshIT} read granted scopes the same way instead of carrying their own copies.
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
    @DisplayName("a login started by a navigation on the scoped route is granted oidc.scopes plus the endpoint scope")
    void navigationOnScopedRouteIsGrantedTheUnitedScopeSet() {
        Session session = BffKeycloakLoginFlow.login(SCOPED_SESSION_PATH);

        Set<String> granted = mediatedScopes(session, SCOPED_SESSION_PATH);

        assertTrue(granted.containsAll(SCOPED_ROUTE_SCOPES),
                "a navigation login on /bff-session/scoped must be granted oidc.scopes united with the "
                        + "endpoint's scopes; the mediated token carries " + granted);
    }

    @Test
    @DisplayName("/auth/login with a public asset return target is granted oidc.scopes without the endpoint scope")
    void loginInitiationForPublicTargetIsGrantedNoEndpointScope() {
        Session session = BffKeycloakLoginFlow.login("/auth/login?returnUrl=" + PUBLIC_ASSET_TARGET);

        Set<String> granted = mediatedScopes(session, PLAIN_SESSION_PATH);

        assertTrue(granted.containsAll(OIDC_SCOPES),
                "a login for a public return target must be granted oidc.scopes; the mediated token "
                        + "carries " + granted);
        assertFalse(granted.contains(ENDPOINT_SCOPE),
                "a return target on a require:none route contributes no scope, so the login must not "
                        + "ask for " + ENDPOINT_SCOPE + "; the mediated token carries " + granted);
    }

    @Test
    @DisplayName("/auth/login with a scoped-route return target is granted the endpoint scope")
    void loginInitiationForScopedTargetIsGrantedTheEndpointScope() {
        Session session = BffKeycloakLoginFlow.login("/auth/login?returnUrl=" + SCOPED_SESSION_PATH);

        Set<String> granted = mediatedScopes(session, SCOPED_SESSION_PATH);

        assertTrue(granted.containsAll(SCOPED_ROUTE_SCOPES),
                "a return target on the scoped session route must make the login ask for that route's "
                        + "needed scope set; the mediated token carries " + granted);
    }

    @Test
    @DisplayName("control: a login on the plain session route mediates a token without the endpoint scope")
    void plainLoginMediatesATokenWithoutTheEndpointScope() {
        Session session = BffKeycloakLoginFlow.login(PLAIN_SESSION_PATH);

        Set<String> granted = mediatedScopes(session, PLAIN_SESSION_PATH);

        assertFalse(granted.contains(ENDPOINT_SCOPE),
                "the realm must not issue " + ENDPOINT_SCOPE + " unless it is requested, otherwise its "
                        + "presence after a scoped login proves nothing; granted scopes were " + granted);
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * The scope members of the token a session mediates on {@code path}.
     *
     * @param session the established gateway session
     * @param path    a {@code require: session} path on the primary instance
     * @return the granted scope members of the mediated token
     */
    private static Set<String> mediatedScopes(Session session, String path) {
        Response echoed = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .redirects().follow(false)
                .when().get(path)
                .then().statusCode(200)
                .extract().response();
        return grantedScopes(mediatedAuthorization(echoed));
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
