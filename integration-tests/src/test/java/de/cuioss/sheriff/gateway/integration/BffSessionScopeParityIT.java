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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves, through the live server-mode edge and the compose Keycloak, that a session route obtains
 * the scopes it declares instead of relaying a token that lacks one (ADR-0057): a session short of a
 * needed scope is widened when the identity provider grants the scope, and refused — never relayed
 * — when it does not.
 * <p>
 * <strong>The fixtures.</strong> Two endpoints add a scope to the gateway's {@code oidc.scopes}, and
 * each declares a {@code require: session} route and a {@code require: bearer} route with
 * {@code auth.session_fallback: true}:
 * <ul>
 *   <li>{@code endpoints/bff-scoped.yaml} adds {@code sheriff_it_endpoint}, an <em>optional</em>
 *       client scope of {@code integration-client}: Keycloak grants it as soon as an authorization
 *       request names it. Its routes are {@code /bff-session/scoped} and
 *       {@code /bff-session/fallback}.</li>
 *   <li>{@code endpoints/bff-unassigned-scope.yaml} adds {@code sheriff_it_unassigned}, which exists
 *       in the realm but is assigned to no client. Its routes are {@code /bff-session/unassigned} and
 *       {@code /bff-session/unassigned/fallback}.</li>
 * </ul>
 * Every session starts through {@code /auth/login?returnUrl=} a target that is not an authenticated
 * route, so the login requests {@code oidc.scopes} and nothing else, and the session then meets a
 * route that needs more. Each row is run on both {@link Surface surfaces}: the
 * {@code require: session} routes, and the SESSION branch of the {@code session_fallback} routes —
 * reached with no {@code Authorization} header, by a session established through the portal.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li><em>A grantable scope, navigation.</em> The navigation is redirected into the identity
 *       provider with a pushed authorization request — {@code client_id} and {@code request_uri} on
 *       the redirect, {@code prompt=none} and the scope set inside the pushed request, where this
 *       suite cannot read them — the realm SSO session answers without a login form, and the browser
 *       lands back on the route. The session cookie the login set — unchanged — then mediates a token
 *       carrying the scope.</li>
 *   <li><em>A grantable scope, XHR.</em> The request is refused {@code 403 application/problem+json}
 *       naming the missing scope and a same-origin {@code step_up_url}; a navigation to that URL
 *       widens the session, and the retried XHR is relayed with a token carrying the scope.</li>
 *   <li><em>A scope the identity provider will not grant.</em> The navigation's widening is answered
 *       {@code invalid_scope} and ends in a terminal {@code 403} with no redirect and no cookie; the
 *       XHR is refused {@code 403}; and in both cases the same session is still served on
 *       {@code /bff-session/get} with the scopes it had.</li>
 *   <li><em>A satisfied route.</em> Two consecutive calls relay a byte-identical bearer and set no
 *       cookie, so the comparison alone costs no identity-provider round trip.</li>
 *   <li><em>The step-up path.</em> An off-origin {@code returnUrl} falls back to the default return
 *       target, a same-origin one is honoured, and without a session the path answers {@code 401}
 *       and never redirects into the identity provider.</li>
 * </ul>
 * <p>
 * <strong>The refresh-restores probe.</strong> The remaining case — a scope inside the session's
 * granted set but missing from its active set, obtained by a refresh — cannot be produced through
 * the gateway's public surface, because the two sets are equal after a login and after a widening.
 * That branch is exercised at unit level, by {@code SessionAuthenticationStageScopeEnforcementTest}
 * and {@code TokenRefreshCoordinatorTest}. What it rests on is a property of the identity provider:
 * that a refresh grant may ask again for a scope of the original grant after an earlier refresh
 * narrowed it away. {@link #refreshRestoresAScopeOfTheGrantAfterANarrowedRefresh()} asserts exactly
 * that against the realm's token endpoint. The probe asserts the hypothesis rather than reporting on
 * it, so its verdict is the test's own: it passes only when the integration realm restores the
 * scope, and fails naming what the realm answered instead.
 * <p>
 * <strong>The probe's recorded outcome: confirmed.</strong> On Keycloak 26.5.7, the narrowed refresh
 * drops {@code sheriff_it_endpoint}, and the refresh that requests it again is issued an access
 * token carrying it. That is the whole of the observation: one optional client scope, restored
 * once, on this realm. It does not show that every scope of a grant can be restored, and it is no
 * evidence about another identity provider. The version is the one the outcome was first recorded
 * on; for whichever image {@code integration-tests/docker-compose.yml} pins, the verdict is this
 * test's own result.
 * <p>
 * <strong>What this suite does NOT prove.</strong> The single interactive re-drive a silent widening
 * is owed when the identity provider needs interaction is not exercised: the browser that just
 * logged in holds a live SSO session, so the silent attempt always succeeds here. Like every
 * {@code Bff*IT}, the suite replays cookie maps and asserts nothing about browser cookie policy (see
 * {@link BffKeycloakLoginFlow}).
 */
class BffSessionScopeParityIT {

    /**
     * The config-fixed id of the {@code session_fallback} route under the unassigned-scope endpoint,
     * the {@code route} label its {@code sheriff_auth_branch_total} series carry.
     */
    static final String UNASSIGNED_FALLBACK_ROUTE_ID = "bff-unassigned-scope-fallback";

    /** The endpoint scope the realm defines but assigns to no client. */
    private static final String UNASSIGNED_SCOPE = "sheriff_it_unassigned";

    /** A path on the plain session route, which needs {@code oidc.scopes} only. */
    private static final String PLAIN_SESSION_PATH = "/bff-session/get";

    /** The configured {@code oidc.step_up.path}. */
    private static final String STEP_UP_PATH = "/auth/step-up";

    /** The gateway callback every authorization response is redirected to. */
    private static final String CALLBACK_URL_PREFIX = BffKeycloakLoginFlow.GATEWAY_ORIGIN + "/auth/callback?";

    private static final String ACCEPT = "Accept";
    private static final String NAVIGATION = "text/html";
    private static final String XHR = "application/json";

    /** The seeded confidential client and its fixture secret, as in {@code integration-realm.json}. */
    private static final String CLIENT_ID = "integration-client";
    private static final String CLIENT_SECRET = "integration-secret";

    private static final String TOKEN_ENDPOINT = "https://" + BffKeycloakLoginFlow.KEYCLOAK_HOST_AUTHORITY
            + "/realms/integration/protocol/openid-connect/token";

    /** The scope of a grant covering the scoped routes: {@code oidc.scopes} plus the endpoint scope. */
    private static final String SCOPED_GRANT = BearerValidationIT.OIDC_SCOPE + " " + BffEndpointScopesIT.ENDPOINT_SCOPE;

    /**
     * The two kinds of session route a row is shown on, each with the login that establishes a
     * session carrying {@code oidc.scopes} only, the route whose extra scope the realm grants, and the
     * route whose extra scope it refuses.
     */
    enum Surface {

        /** The {@code require: session} routes; the login returns to a {@code require: none} asset. */
        SESSION_ROUTE("require: session route",
            "/assets/static/index.html",
            "/bff-session/scoped/get",
            "/auth/step-up?returnUrl=%2Fbff-session%2Fscoped%2Fget",
            "/bff-session/unassigned/get",
            "/auth/step-up?returnUrl=%2Fbff-session%2Funassigned%2Fget"),

        /** The SESSION branch of the {@code session_fallback} routes; the login returns to the portal. */
        SESSION_FALLBACK_ROUTE("session_fallback route, session established through the portal",
                "/portal",
                "/bff-session/fallback/get",
                "/auth/step-up?returnUrl=%2Fbff-session%2Ffallback%2Fget",
                "/bff-session/unassigned/fallback/get",
                "/auth/step-up?returnUrl=%2Fbff-session%2Funassigned%2Ffallback%2Fget");

        private final String label;
        private final String loginTarget;
        private final String grantablePath;
        private final String grantableStepUpUrl;
        private final String refusedPath;
        private final String refusedStepUpUrl;

        Surface(String label, String loginTarget, String grantablePath, String grantableStepUpUrl,
                String refusedPath, String refusedStepUpUrl) {
            this.label = label;
            this.loginTarget = loginTarget;
            this.grantablePath = grantablePath;
            this.grantableStepUpUrl = grantableStepUpUrl;
            this.refusedPath = refusedPath;
            this.refusedStepUpUrl = refusedStepUpUrl;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One widening round trip as the browser sees it after the gateway's redirect: the redirect the
     * identity provider answered the authorization request with, and the gateway's answer to the
     * callback that redirect leads to.
     *
     * @param idpRedirect the {@code Location} the identity provider sent the browser to
     * @param callback    the gateway's response to that callback navigation
     */
    private record WideningRoundTrip(String idpRedirect, Response callback) {

        List<String> idpParameter(String name) {
            return rawQueryValues(idpRedirect, name);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("a navigation needing a grantable scope is widened silently and lands back on the route")
    void navigationIsWidenedSilently(Surface surface) {
        Session session = loginWithOidcScopesOnly(surface);
        Set<String> before = activeScopes(session);
        Map<String, String> browser = new HashMap<>(session.gatewayCookies());

        Response initiation = navigate(browser, surface.grantablePath);
        WideningRoundTrip widening = followWidening(initiation, browser, session.keycloakCookies());

        assertPushedWideningRequest(initiation);
        assertGranted(widening);
        assertEquals(302, widening.callback().statusCode(),
                "a granted widening must redirect the browser back to the route it was navigating to");
        assertEquals(surface.grantablePath, BffKeycloakLoginFlow.location(widening.callback()),
                "the widening must return to the URL the navigation asked for");
        // Sent with the cookies exactly as the login left them: the widening merges into the live
        // session, so the cookie that identified the session before it still does.
        Set<String> after = activeScopesOn(session.gatewayCookies(), surface.grantablePath);
        assertTrue(after.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "the widened session must mediate a token carrying " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + "; granted scopes were " + after);
        assertTrue(after.containsAll(before),
                "a widening must not narrow the session: it had " + before + " and now has " + after);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("an XHR needing a grantable scope is refused 403 with a step_up_url, and is relayed once that URL was followed")
    void xhrIsRefusedWithAStepUpUrlAndRelayedAfterFollowingIt(Surface surface) {
        Session session = loginWithOidcScopesOnly(surface);
        // The precondition only: the session must lack the endpoint scope before the refusal is read.
        activeScopes(session);
        Map<String, String> browser = new HashMap<>(session.gatewayCookies());

        Response refused = xhr(browser, surface.grantablePath);

        assertScopeRefusal(refused, BffEndpointScopesIT.ENDPOINT_SCOPE);
        assertEquals(surface.grantableStepUpUrl, refused.jsonPath().getString("step_up_url"),
                "the refusal must name the same-origin step-up URL for the refused request");

        Response stepUp = navigateVerbatim(browser, refused.jsonPath().getString("step_up_url"));
        WideningRoundTrip widening = followWidening(stepUp, browser, session.keycloakCookies());

        assertPushedWideningRequest(stepUp);
        assertGranted(widening);
        assertEquals(302, widening.callback().statusCode(),
                "a granted widening started on the step-up path must redirect the browser back");
        assertEquals(surface.grantablePath, BffKeycloakLoginFlow.location(widening.callback()),
                "the step-up path must return to the URL its returnUrl named");
        Response retried = xhr(session.gatewayCookies(), surface.grantablePath);
        assertEquals(200, retried.statusCode(), "the retried XHR must be relayed once the session was widened");
        Set<String> after = BffEndpointScopesIT.grantedScopes(BffEndpointScopesIT.mediatedAuthorization(retried));
        assertTrue(after.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "the retried XHR must relay a token carrying " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + "; granted scopes were " + after);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("a navigation needing a scope the IdP refuses ends in a terminal 403 and leaves the session unchanged")
    void navigationNeedingARefusedScopeEndsInATerminal403(Surface surface) {
        Session session = loginWithOidcScopesOnly(surface);
        Set<String> before = activeScopes(session);
        Map<String, String> browser = new HashMap<>(session.gatewayCookies());

        Response initiation = navigate(browser, surface.refusedPath);
        WideningRoundTrip widening = followWidening(initiation, browser, session.keycloakCookies());

        assertPushedWideningRequest(initiation);
        assertEquals(List.of("invalid_scope"), widening.idpParameter("error"),
                "the realm assigns " + UNASSIGNED_SCOPE + " to no client, so it must refuse the authorization "
                        + "request with invalid_scope");
        Response callback = widening.callback();
        assertEquals(403, callback.statusCode(), "an IdP refusal of a widening is terminal");
        assertNull(callback.getHeader("Location"),
                "a refused widening must not redirect again — not into a second attempt, not back to the route");
        assertNoSetCookie(callback, "a refused widening must set no cookie, so the session is left as it was");
        assertEquals(before, activeScopes(session),
                "the session must still be served on " + PLAIN_SESSION_PATH + " with the scopes it had");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("an XHR needing a scope the IdP refuses is answered 403 and leaves the session unchanged")
    void xhrNeedingARefusedScopeIsRefusedAndTheSessionSurvives(Surface surface) {
        Session session = loginWithOidcScopesOnly(surface);
        Set<String> before = activeScopes(session);

        Response refused = xhr(session.gatewayCookies(), surface.refusedPath);

        assertScopeRefusal(refused, UNASSIGNED_SCOPE);
        assertEquals(surface.refusedStepUpUrl, refused.jsonPath().getString("step_up_url"),
                "the refusal names the step-up URL whether or not the identity provider would grant the scope");
        assertEquals(before, activeScopes(session),
                "the session must still be served on " + PLAIN_SESSION_PATH + " with the scopes it had");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("a satisfied route relays a byte-identical bearer twice and sets no cookie")
    void satisfiedRouteRelaysTheSameBearerAndSetsNoCookie(Surface surface) {
        // The login is started on the route itself, so it requests the route's needed scopes and the
        // session is satisfied from its first request.
        Session session = BffKeycloakLoginFlow.login(surface.grantablePath);

        Response first = xhr(session.gatewayCookies(), surface.grantablePath);
        Response second = xhr(session.gatewayCookies(), surface.grantablePath);

        assertEquals(200, first.statusCode(), "a satisfied session must be relayed");
        assertEquals(200, second.statusCode(), "a satisfied session must be relayed again");
        String firstBearer = BffEndpointScopesIT.mediatedAuthorization(first);
        assertTrue(BffEndpointScopesIT.grantedScopes(firstBearer).contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: the session must carry the scope the route needs, otherwise this is not the "
                        + "satisfied case");
        assertEquals(firstBearer, BffEndpointScopesIT.mediatedAuthorization(second),
                "a satisfied route must relay the same token on consecutive calls: no refresh and no widening "
                        + "happened between them");
        assertNoSetCookie(first, "a satisfied route must set no cookie");
        assertNoSetCookie(second, "a satisfied route must set no cookie on a repeated call either");
    }

    @Test
    @DisplayName("probe: a refresh asking again for a scope of the grant restores it after a narrowed refresh")
    void refreshRestoresAScopeOfTheGrantAfterANarrowedRefresh() {
        Response grant = tokenRequest(Map.of(
                "grant_type", "password",
                "username", BffKeycloakLoginFlow.USERNAME,
                "password", BffKeycloakLoginFlow.PASSWORD,
                "scope", SCOPED_GRANT));
        Set<String> granted = BffEndpointScopesIT.grantedScopes(accessToken(grant));
        assertTrue(granted.containsAll(BffEndpointScopesIT.SCOPED_ROUTE_SCOPES),
                "precondition: the grant must carry " + BffEndpointScopesIT.SCOPED_ROUTE_SCOPES + "; it carried "
                        + granted);
        Response narrowed = refresh(refreshToken(grant), BearerValidationIT.OIDC_SCOPE);
        Set<String> narrowedScopes = BffEndpointScopesIT.grantedScopes(accessToken(narrowed));
        assertTrue(narrowedScopes.containsAll(BffEndpointScopesIT.OIDC_SCOPES),
                "precondition: the narrowed refresh must keep oidc.scopes; it carried " + narrowedScopes);
        assertFalse(narrowedScopes.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: the narrowed refresh must drop " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + ", otherwise there is nothing to restore; it carried " + narrowedScopes);

        // The refresh token of the narrowed response, not of the original grant: the realm rotates
        // refresh tokens, and it is the token a session holds after a refresh narrowed its scopes.
        Response restored = refresh(refreshToken(narrowed), SCOPED_GRANT);

        Set<String> restoredScopes = BffEndpointScopesIT.grantedScopes(accessToken(restored));
        assertTrue(restoredScopes.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "a refresh requesting a scope of the original grant must restore it; the realm issued "
                        + restoredScopes);
    }

    @ParameterizedTest(name = "returnUrl={0}")
    @ValueSource(strings = {"https://evil.example.com/steal", "//evil.example.com/steal"})
    @DisplayName("the step-up path answers an off-origin returnUrl with the default return target")
    void stepUpWithAnOffOriginReturnUrlFallsBackToTheDefault(String offOriginTarget) {
        Session session = BffKeycloakLoginFlow.login(PLAIN_SESSION_PATH);

        Response response = stepUp(session.gatewayCookies(), offOriginTarget);

        assertEquals(302, response.statusCode(), "a step-up request with a live session is answered by a redirect");
        // The primary stack declares no oidc.login.default_return_url, so the fallback is '/'.
        assertEquals("/", response.getHeader("Location"),
                "an off-origin returnUrl must be replaced by the default return target, never followed");
        assertNoSetCookie(response, "the fallback target needs no widening, so no binding cookie is set");
    }

    @Test
    @DisplayName("control: the step-up path honours a same-origin returnUrl whose route needs nothing more")
    void stepUpWithASameOriginReturnUrlRedirectsStraightBack() {
        Session session = BffKeycloakLoginFlow.login(PLAIN_SESSION_PATH);

        Response response = stepUp(session.gatewayCookies(), PLAIN_SESSION_PATH);

        assertEquals(302, response.statusCode(), "a step-up request with a live session is answered by a redirect");
        // The control for the fallback above: the parameter IS read and honoured when it is same-origin,
        // so a '/' answer to an off-origin target is the fallback and not a parameter that never arrived.
        assertEquals("/bff-session/get", response.getHeader("Location"),
                "a same-origin returnUrl whose route the session already satisfies is redirected to directly");
        assertNoSetCookie(response, "a satisfied target needs no widening, so no binding cookie is set");
    }

    @Test
    @DisplayName("the step-up path without a session answers 401 and never redirects into the IdP")
    void stepUpWithoutASessionAnswers401WithoutAnIdpRedirect() {
        // Sent as a navigation on purpose: that is the request shape a session route answers with a 302
        // into the identity provider, so a 401 without a Location shows the step-up path starts no login.
        Response response = stepUp(Map.of(), BffEndpointScopesIT.SCOPED_SESSION_PATH);

        assertEquals(401, response.statusCode(), "the step-up path widens a live session; it never starts one");
        assertNull(response.getHeader("Location"), "a step-up request without a session must not be redirected");
        assertNoSetCookie(response, "a step-up request without a session must persist no pending authorization");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Logs in through {@code /auth/login?returnUrl=} the surface's login target. That target is not
     * an authenticated route, so the login requests {@code oidc.scopes} and nothing else.
     *
     * @param surface the surface whose login target the session is established for
     * @return the established session
     */
    private static Session loginWithOidcScopesOnly(Surface surface) {
        Session session = BffKeycloakLoginFlow.login("/auth/login?returnUrl=" + surface.loginTarget);
        assertEquals(surface.loginTarget, session.callbackLocation(),
                "precondition: the login must land on the target it was started for");
        return session;
    }

    /**
     * The scopes of the token the session mediates on the plain session route, which needs
     * {@code oidc.scopes} only and is therefore served to every session of this suite. Doubles as the
     * precondition that the session lacks both endpoint scopes.
     *
     * @param session the session to read
     * @return the scope members of the token the session mediates
     */
    private static Set<String> activeScopes(Session session) {
        Set<String> scopes = activeScopesOn(session.gatewayCookies(), PLAIN_SESSION_PATH);
        assertFalse(scopes.contains(BffEndpointScopesIT.ENDPOINT_SCOPE),
                "precondition: a session established without requesting " + BffEndpointScopesIT.ENDPOINT_SCOPE
                        + " must not carry it; granted scopes were " + scopes);
        assertFalse(scopes.contains(UNASSIGNED_SCOPE),
                "no session can carry " + UNASSIGNED_SCOPE + "; granted scopes were " + scopes);
        return scopes;
    }

    private static Set<String> activeScopesOn(Map<String, String> cookies, String path) {
        Response echoed = xhr(cookies, path);
        assertEquals(200, echoed.statusCode(), () -> "the session must be served on " + path);
        return BffEndpointScopesIT.grantedScopes(BffEndpointScopesIT.mediatedAuthorization(echoed));
    }

    private static Response navigate(Map<String, String> cookies, String path) {
        return BffKeycloakLoginFlow.gateway(cookies)
                .header(ACCEPT, NAVIGATION)
                .redirects().follow(false)
                .when().get(path)
                .then().statusCode(302)
                .extract().response();
    }

    /**
     * Navigates to a URL the gateway handed out, byte for byte: a {@code step_up_url} is already
     * percent-encoded, and re-encoding it would send a {@code returnUrl} the gateway never named.
     *
     * @param cookies the browser's gateway cookie jar
     * @param url     the gateway-issued URL, sent as it was received
     * @return the gateway's {@code 302}
     */
    private static Response navigateVerbatim(Map<String, String> cookies, String url) {
        return BffKeycloakLoginFlow.gateway(cookies)
                .urlEncodingEnabled(false)
                .header(ACCEPT, NAVIGATION)
                .redirects().follow(false)
                .when().get(url)
                .then().statusCode(302)
                .extract().response();
    }

    private static Response xhr(Map<String, String> cookies, String path) {
        return BffKeycloakLoginFlow.gateway(cookies)
                .header(ACCEPT, XHR)
                .redirects().follow(false)
                .when().get(path)
                .then().extract().response();
    }

    private static Response stepUp(Map<String, String> cookies, String returnUrl) {
        return BffKeycloakLoginFlow.gateway(cookies)
                .queryParam("returnUrl", returnUrl)
                .header(ACCEPT, NAVIGATION)
                .redirects().follow(false)
                .when().get(STEP_UP_PATH)
                .then().extract().response();
    }

    /**
     * Follows a widening redirect the way the browser does: to the identity provider with the realm
     * SSO cookies of the login, then to the gateway callback the identity provider answers with.
     * <p>
     * The authorization request is not expected to render anything. It carries {@code prompt=none},
     * so the identity provider answers it with a redirect either way — carrying a code when the SSO
     * session grants the request, an error when it does not.
     *
     * @param initiation      the gateway's {@code 302} into the identity provider
     * @param gatewayCookies  the browser's gateway cookie jar; the binding cookie the redirect set is
     *                        added to it
     * @param keycloakCookies the realm SSO cookies the login left
     * @return the identity provider's redirect and the gateway's answer to the callback
     */
    private static WideningRoundTrip followWidening(Response initiation, Map<String, String> gatewayCookies,
            Map<String, String> keycloakCookies) {
        gatewayCookies.putAll(initiation.getCookies());
        String authorizationUrl = BffKeycloakLoginFlow.rewriteToHost(BffKeycloakLoginFlow.location(initiation));

        Response idp = BffKeycloakLoginFlow.keycloak(keycloakCookies)
                .redirects().follow(false)
                .when().get(authorizationUrl)
                .then().extract().response();
        assertEquals(302, idp.statusCode(),
                "the identity provider must answer a prompt=none authorization request with a redirect back to "
                        + "the gateway, not with a page of its own");
        String callbackUrl = BffKeycloakLoginFlow.rewriteToHost(BffKeycloakLoginFlow.location(idp));
        // The URL itself stays out of the message: on the granted path it carries the authorization code.
        assertTrue(callbackUrl.startsWith(CALLBACK_URL_PREFIX),
                "the identity provider must redirect the browser to the gateway callback");

        Response callback = BffKeycloakLoginFlow.gateway(gatewayCookies)
                .urlEncodingEnabled(false)
                .header(ACCEPT, NAVIGATION)
                .redirects().follow(false)
                .when().get(callbackUrl)
                .then().extract().response();
        return new WideningRoundTrip(callbackUrl, callback);
    }

    /**
     * Asserts that the gateway started the widening with a pushed authorization request (RFC 9126):
     * the redirect carries {@code client_id} and {@code request_uri} and neither {@code prompt} nor
     * {@code scope}.
     * <p>
     * The parameters of the request itself — {@code prompt=none} and the scope set, the session's
     * scopes united with the route's — travel in the pushed request and are not readable from the
     * browser's side. That the pushed body carries them is asserted at unit level, on the request a
     * stub identity provider records ({@code BffRuntimeProducerTest}). What this suite observes of the
     * silent attempt is its outcome: {@link #followWidening} requires the identity provider to answer
     * with a redirect and never with a page of its own, and the scopes of the token the widened
     * session mediates are read afterwards.
     *
     * @param initiation the gateway's {@code 302} into the identity provider
     */
    private static void assertPushedWideningRequest(Response initiation) {
        String location = BffKeycloakLoginFlow.location(initiation);
        assertEquals(1, rawQueryValues(location, "request_uri").size(),
                "a widening must be started with a pushed authorization request: exactly one request_uri");
        assertEquals(1, rawQueryValues(location, "client_id").size(),
                "the pushed-request redirect names the client");
        assertEquals(List.of(), rawQueryValues(location, "prompt"),
                "prompt=none travels in the pushed request, never on the redirect");
        assertEquals(List.of(), rawQueryValues(location, "scope"),
                "the scope set travels in the pushed request, never on the redirect");
    }

    private static void assertGranted(WideningRoundTrip widening) {
        assertEquals(List.of(), widening.idpParameter("error"),
                "the realm SSO session must answer the silent attempt without an error");
        assertEquals(1, widening.idpParameter("code").size(),
                "a granted authorization request must return exactly one authorization code");
    }

    private static void assertScopeRefusal(Response refused, String missingScope) {
        assertEquals(403, refused.statusCode(), "a session lacking a needed scope must be refused, never relayed");
        assertTrue(refused.contentType().contains("application/problem+json"),
                "the refusal must render RFC 9457 problem+json");
        assertEquals(List.of(missingScope), refused.jsonPath().getList("missing_scopes", String.class),
                "the refusal must name exactly the scopes the session lacks");
        assertNull(refused.path("method"), "a refused request must never reach the go-httpbin upstream");
        assertNoSetCookie(refused, "a refusal must set no cookie");
    }

    private static void assertNoSetCookie(Response response, String message) {
        assertTrue(response.getHeaders().getValues("Set-Cookie").isEmpty(), message);
    }

    /**
     * The raw values of one query parameter of a URL, in wire order.
     *
     * @param url  the URL to read
     * @param name the parameter name
     * @return the still-percent-encoded values; empty when the parameter is absent
     */
    private static List<String> rawQueryValues(String url, String name) {
        String rawQuery = URI.create(url).getRawQuery();
        assertNotNull(rawQuery, "the redirect must carry a query");
        String prefix = name + "=";
        return Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> parameter.startsWith(prefix))
                .map(parameter -> parameter.substring(prefix.length()))
                .toList();
    }

    private static Response refresh(String refreshToken, String scope) {
        return tokenRequest(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "scope", scope));
    }

    private static Response tokenRequest(Map<String, String> parameters) {
        return given()
                .relaxedHTTPSValidation()
                .contentType(ContentType.URLENC)
                .formParam("client_id", CLIENT_ID)
                .formParam("client_secret", CLIENT_SECRET)
                .formParams(parameters)
                .when().post(TOKEN_ENDPOINT)
                .then().extract().response();
    }

    private static String accessToken(Response tokenResponse) {
        return tokenField(tokenResponse, "access_token");
    }

    private static String refreshToken(Response tokenResponse) {
        return tokenField(tokenResponse, "refresh_token");
    }

    /**
     * Reads one token of a token-endpoint response, failing with the endpoint's own error when it
     * issued none — which for the probe's last request is the refutation, named.
     *
     * @param tokenResponse the token-endpoint response
     * @param field         the response member holding the token
     * @return the token
     */
    private static String tokenField(Response tokenResponse, String field) {
        String token = tokenResponse.path(field);
        assertNotNull(token, () -> "the integration realm issued no %s (HTTP %d, error %s: %s)".formatted(field,
                tokenResponse.statusCode(), tokenResponse.path("error"), tokenResponse.path("error_description")));
        return token;
    }
}
