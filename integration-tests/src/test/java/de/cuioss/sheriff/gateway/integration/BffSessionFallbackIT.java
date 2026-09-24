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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the {@code auth.session_fallback} branch table against the native stack, one test per row,
 * on the {@code bff-session-fallback} route ({@code /bff-session/fallback}, go-httpbin echo).
 * <p>
 * <strong>The fixture.</strong> {@code endpoints/bff-scoped.yaml} declares the route with
 * {@code auth: {require: bearer, session_fallback: true}} under the {@code bff-scoped} endpoint, whose
 * {@code scopes: ["sheriff_it_endpoint"]} makes the route's needed set
 * {@code openid profile email sheriff_it_endpoint}. That endpoint scope is what makes the
 * {@code 403 insufficient_scope} row reachable at all: the {@code integration} realm issues
 * {@code openid profile email} as default client scopes on every token, while
 * {@code sheriff_it_endpoint} is optional and appears only when the grant requests it. Bearer tokens
 * are minted through the realm's password grant exactly as {@code BearerScopeIT} mints them; live
 * sessions are established through {@link BffKeycloakLoginFlow}, starting the login on the fallback
 * route itself so its SESSION-branch navigation is the one that enters the IdP.
 * <p>
 * <strong>The branch rule.</strong> The branch is chosen from one input only — whether the request
 * carries an {@code Authorization} header:
 * <ul>
 *   <li>{@code Authorization} present (any scheme, any value, empty included) — BEARER: a valid token
 *       carrying every needed scope is admitted and forwarded upstream as exactly the client's token,
 *       with no session lookup and no {@code Set-Cookie}; a token lacking a needed scope is
 *       {@code 403 insufficient_scope}; an invalid token, a {@code Basic} credential and an empty
 *       value are {@code 401 WWW-Authenticate: Bearer} with no login redirect. CSRF does not run.</li>
 *   <li>{@code Authorization} absent — SESSION, exactly as on a {@code require: session} route: a live
 *       session mediates its bearer upstream, a navigation without one is redirected into the IdP, an
 *       XHR without one is {@code 401 application/problem+json}, and an unsafe method is subject to the
 *       CSRF defence.</li>
 * </ul>
 * Every BEARER row that admits a session cookie is run with and without one, because the cookie is
 * exactly what an attacker would hope rescues a failed bearer: it never does, and on an admitted
 * bearer it is stripped rather than forwarded. The browser cookie is withheld upstream on every
 * admitted request by the gateway-owned never-forward set.
 */
class BffSessionFallbackIT extends BaseIntegrationTest {

    /** The config-fixed id of the {@code session_fallback} route, the {@code route} metric label. */
    static final String FALLBACK_ROUTE_ID = "bff-session-fallback";

    /** A safe-method path on the {@code session_fallback} route; the echo upstream answers any path. */
    static final String FALLBACK_PATH = "/bff-session/fallback/get";

    /** An unsafe-method path on the {@code session_fallback} route. */
    private static final String FALLBACK_UNSAFE_PATH = "/bff-session/fallback/post";

    /** An {@code Origin} that is neither the gateway origin nor a configured CSRF trusted origin. */
    private static final String FOREIGN_ORIGIN = "https://evil.example.com";

    /** The exact challenge RFC 6750 §3.1 prescribes for the endpoint scope the token lacks. */
    private static final String INSUFFICIENT_SCOPE_CHALLENGE =
            "Bearer error=\"insufficient_scope\", scope=\"" + BffEndpointScopesIT.ENDPOINT_SCOPE + "\"";

    @ParameterizedTest(name = "session cookie present: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a valid bearer is admitted and forwarded upstream as exactly the client's token, with no Set-Cookie")
    void validBearerForwardedAsTheClientToken(boolean withSession) {
        String token = mintFallbackRouteToken();

        Response response = BffKeycloakLoginFlow.gateway(cookieJar(withSession))
                .header("Authorization", "Bearer " + token)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("GET", response.path("method"), "an admitted bearer request must reach the upstream");
        assertEquals(List.of("Bearer " + token), response.jsonPath().getList("headers.Authorization", String.class),
                "the BEARER branch must forward exactly the validated client token, never a session's token");
        assertNull(response.path("headers.Cookie"), "the browser session cookie must never be forwarded upstream");
        assertNoSetCookie(response, "the BEARER branch looks up no session and must set no cookie");
    }

    @ParameterizedTest(name = "session cookie present: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a valid bearer lacking the endpoint scope is rejected 403 insufficient_scope and never forwarded")
    void bearerLackingNeededScopeRejectedInsufficientScope(boolean withSession) {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE);
        Set<String> granted = BffEndpointScopesIT.grantedScopes(token);
        assertFalse(granted.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: a grant not requesting " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + " must not carry it, otherwise this case proves nothing; granted " + granted);

        Response response = BffKeycloakLoginFlow.gateway(cookieJar(withSession))
                .header("Authorization", "Bearer " + token)
                .redirects().follow(false)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(403)
                .header("WWW-Authenticate", INSUFFICIENT_SCOPE_CHALLENGE)
                .extract().response();

        assertTrue(response.contentType().contains("application/problem+json"),
                "an insufficient_scope rejection must render RFC 9457 problem+json");
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
        assertNull(response.getHeader("Location"), "a BEARER-branch rejection must never redirect into a login");
        assertNoSetCookie(response, "a BEARER-branch rejection must set no cookie");
    }

    @ParameterizedTest(name = "{0}, session cookie present: {2}")
    @MethodSource("rejectedAuthorizationValues")
    @DisplayName("an invalid, non-Bearer or empty Authorization is 401 Bearer with no redirect and no session fallback")
    void rejectedAuthorizationAnswers401WithoutSessionFallback(String description, String authorization,
            boolean withSession) {
        // Sent as a navigation (Accept: text/html) on purpose: that is the request shape the SESSION branch
        // answers with a 302 into the IdP, so a 401 without a Location here proves the BEARER branch was
        // taken rather than the session one. A bearer 401 keeps its problem+json shape even for a
        // navigation — the HTML error page is not offered for a token rejection.
        Response response = BffKeycloakLoginFlow.gateway(cookieJar(withSession))
                .header("Authorization", authorization)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract().response();

        assertTrue(response.contentType().contains("application/problem+json"),
                () -> description + " must be rejected with RFC 9457 problem+json");
        assertNull(response.path("method"), () -> description + " must not reach the go-httpbin upstream");
        assertNull(response.getHeader("Location"),
                () -> description + " selects the BEARER branch and must never redirect into a login, even "
                        + "for a navigation");
        assertNoSetCookie(response, description + " must set no cookie");
    }

    @Test
    @DisplayName("no Authorization with a live session takes the SESSION branch and mediates the session token")
    void sessionWithoutAuthorizationMediatesTheSessionToken() {
        Map<String, String> sessionCookies = cookieJar(true);

        Response response = BffKeycloakLoginFlow.gateway(sessionCookies)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("GET", response.path("method"), "a live session must authorize the request upstream");
        Set<String> granted = BffEndpointScopesIT.grantedScopes(BffEndpointScopesIT.mediatedAuthorization(response));
        assertTrue(granted.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "the mediated session token must carry " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + ", the endpoint scope the SESSION-branch login requested; granted " + granted);
        assertNull(response.path("headers.Cookie"), "the browser session cookie must never be forwarded upstream");
    }

    @Test
    @DisplayName("a navigation without Authorization and without a session is redirected 302 into the IdP")
    void navigationWithoutSessionRedirectsIntoTheIdp() {
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(302)
                .extract().response();

        String location = initiation.getHeader("Location");
        assertNotNull(location, "a SESSION-branch navigation challenge must carry a Location redirect");
        assertTrue(location.contains("/protocol/openid-connect/auth"),
                "the SESSION-branch navigation challenge must redirect into the OIDC authorization endpoint");
        assertEquals(BffEndpointScopesIT.SCOPED_ROUTE_SCOPES, BffEndpointScopesIT.requestedScopeSet(initiation),
                "the SESSION branch must request the route's needed set — oidc.scopes united with the "
                        + "endpoint's scopes — exactly as a require: session route does");
    }

    @Test
    @DisplayName("an XHR without Authorization and without a session is rejected 401 problem+json")
    void xhrWithoutSessionRejected() {
        Response response = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when()
                .get(FALLBACK_PATH)
                .then()
                .statusCode(401)
                .extract().response();

        assertTrue(response.contentType().contains("application/problem+json"),
                "an unauthenticated SESSION-branch XHR must render RFC 9457 problem+json");
        assertNull(response.getHeader("Location"), "a non-navigation SESSION-branch challenge must not redirect");
        assertNull(response.path("method"), "a challenged request must never reach the upstream");
    }

    @ParameterizedTest(name = "session cookie present: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an unsafe method without Authorization and with a foreign Origin is rejected 403 by the CSRF defence")
    void unsafeMethodWithoutAuthorizationForeignOriginCsrfRejected(boolean withSession) {
        Response response = BffKeycloakLoginFlow.gateway(cookieJar(withSession))
                .header("Origin", FOREIGN_ORIGIN)
                .header("Accept", "application/json")
                .when()
                .post(FALLBACK_UNSAFE_PATH)
                .then()
                .statusCode(403)
                .extract().response();

        assertTrue(response.contentType().contains("application/problem+json"),
                "a CSRF rejection must render RFC 9457 problem+json");
        assertNull(response.path("method"), "a CSRF-rejected request must never reach the upstream");
    }

    @Test
    @DisplayName("control: an unsafe method without Authorization, with a session and the trusted Origin, is served")
    void unsafeMethodWithoutAuthorizationTrustedOriginServed() {
        Map<String, String> sessionCookies = cookieJar(true);

        Response response = BffKeycloakLoginFlow.gateway(sessionCookies)
                .header("Origin", BffKeycloakLoginFlow.GATEWAY_ORIGIN)
                .header("Accept", "application/json")
                .when()
                .post(FALLBACK_UNSAFE_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("POST", response.path("method"),
                "the trusted Origin passes the CSRF gate, so the 403 above is the CSRF defence and not the "
                        + "session stage or the verb gate");
    }

    @ParameterizedTest(name = "session cookie present: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an unsafe method with a valid bearer and no Origin is admitted — CSRF does not run on the BEARER branch")
    void unsafeMethodWithValidBearerAndNoOriginAdmitted(boolean withSession) {
        String token = mintFallbackRouteToken();

        Response response = BffKeycloakLoginFlow.gateway(cookieJar(withSession))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .when()
                .post(FALLBACK_UNSAFE_PATH)
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals("POST", response.path("method"),
                "an unsafe method without any Origin proof is admitted on the BEARER branch, where the CSRF "
                        + "defence does not run; the same request without Authorization is refused 403");
        assertEquals(List.of("Bearer " + token), response.jsonPath().getList("headers.Authorization", String.class),
                "the BEARER branch must forward exactly the validated client token");
        assertNull(response.path("headers.Cookie"), "the browser session cookie must never be forwarded upstream");
    }

    /**
     * The {@code Authorization} values the BEARER branch must refuse with {@code 401}, each crossed
     * with the absence and the presence of a live session cookie.
     *
     * @return {@code (description, Authorization value, session cookie present)} triples
     */
    static Stream<Arguments> rejectedAuthorizationValues() {
        List<Arguments> rejected = List.of(
                Arguments.of("an invalid bearer token", "Bearer not-a-real-jwt"),
                Arguments.of("a Basic credential", "Basic c2hlcmlmZjpub3QtYS1wYXNzd29yZA=="),
                Arguments.of("an empty Authorization value", ""));
        return rejected.stream().flatMap(arguments -> Stream.of(false, true)
                .map(withSession -> Arguments.of(arguments.get()[0], arguments.get()[1], withSession)));
    }

    /**
     * Mints an access token carrying every scope the {@code session_fallback} route needs:
     * {@code oidc.scopes} and the {@code bff-scoped} endpoint's {@code sheriff_it_endpoint}.
     *
     * @return the raw access token
     */
    static String mintFallbackRouteToken() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(
                BearerValidationIT.OIDC_SCOPE + " " + BffEndpointScopesIT.ENDPOINT_SCOPE);
        Set<String> granted = BffEndpointScopesIT.grantedScopes(token);
        assertTrue(granted.containsAll(BffEndpointScopesIT.SCOPED_ROUTE_SCOPES),
                "precondition: the grant must issue every scope the fallback route needs; granted " + granted);
        return token;
    }

    /**
     * The gateway cookie jar a request is sent with: a live session established by a login started on
     * the fallback route itself, or none.
     *
     * @param withSession whether to establish a live session first
     * @return the gateway cookies to replay
     */
    private static Map<String, String> cookieJar(boolean withSession) {
        return withSession ? BffKeycloakLoginFlow.login(FALLBACK_PATH).gatewayCookies() : Map.of();
    }

    private static void assertNoSetCookie(Response response, String message) {
        assertTrue(response.getHeaders().getValues("Set-Cookie").isEmpty(), message);
    }
}
