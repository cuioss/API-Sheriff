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

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.restassured.http.Cookies;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Drives a scripted, browser-less OIDC authorization-code flow against the compose Keycloak
 * {@code integration} realm and the server-mode BFF gateway, following the {@code 302} chain with a
 * cookie jar exactly as a browser would.
 * <p>
 * <strong>response_mode=query.</strong> The gateway drives the authorization request with
 * {@code response_mode=query}, so after a successful credential POST Keycloak answers a {@code 302}
 * whose {@code Location} is the gateway {@code redirect_uri} with the {@code code}/{@code state} in
 * the query string. This helper follows that redirect as a plain GET to the gateway callback —
 * exactly the top-level navigation a browser performs — to complete the login.
 * <p>
 * <strong>LIMITATION — this helper cannot prove the browser-facing flow works.</strong> It replays
 * cookies from a {@link Map} it manages itself, so it applies no cookie policy: {@code SameSite},
 * {@code Secure} and {@code __Host-} are attributes it records but never enforces. It would
 * therefore pass just as green against {@code response_mode=form_post} — under which a real browser
 * drops the {@code SameSite=Lax} binding cookie on the cross-site POST callback and every login
 * dead-ends on the {@code 403} "no browser-binding cookie" branch. That is precisely the defect a
 * green run of this suite failed to reveal. <em>Do not read a green IT suite as proof that the
 * browser flow works.</em> The browser-level proof lives in the demo-client Playwright suite, which
 * drives a real Chromium with a real cookie jar; see {@code doc/development/demo-client.adoc}.
 * <p>
 * <strong>Container-network rewrite.</strong> The {@code integration} realm pins
 * {@code frontendUrl https://keycloak:8443}, so every authorization / login-form URL the gateway or
 * Keycloak hands the "browser" carries the container-internal authority {@code keycloak:8443}. The
 * host-driven test JVM cannot resolve that name, so this helper rewrites the authority to the
 * host-published {@code localhost:1443} (compose maps {@code 1443 -> 8443}) before following each
 * redirect. The gateway itself keeps reaching Keycloak container-internally at {@code keycloak:8443}
 * over the shared {@code api-sheriff} network — only the browser leg is rewritten.
 * <p>
 * <strong>Two disjoint cookie jars.</strong> RFC 6265 cookies are port-agnostic, so a single
 * {@code localhost} jar would mix the gateway session cookie ({@code localhost:10443}) with the
 * Keycloak {@code AUTH_SESSION_ID} ({@code localhost:1443}). The helper therefore keeps the gateway
 * jar and the Keycloak jar separate and replays only the gateway jar on the returned session.
 * <p>
 * <strong>Origin-parameterized.</strong> The flow is identical for every gateway instance, so the
 * browser-facing origin is a parameter: {@link #login(String)} drives the primary server-mode
 * instance ({@link #GATEWAY_ORIGIN}) and {@link #login(String, String)} drives any other — notably
 * the dedicated cookie-mode instance ({@link #COOKIE_GATEWAY_ORIGIN}), since the session mode is a
 * property of the whole gateway and cannot be flipped per request.
 * <p>
 * This helper is a test-support class (no {@code *IT} suffix), so Failsafe does not run it as a
 * suite; the {@code Bff*IT} classes call {@link #login(String)} / {@link #login(String, String)} to
 * establish a live session.
 */
final class BffKeycloakLoginFlow {

    /** Browser-facing gateway origin: published host port {@code 10443 -> } container {@code 8443}. */
    static final String GATEWAY_ORIGIN = "https://localhost:10443";

    /**
     * Browser-facing origin of the dedicated <em>cookie-mode</em> gateway instance
     * ({@code api-sheriff-cookie}, published host port {@code 10445 -> } container {@code 8443}).
     * <p>
     * The session mode is a property of the whole gateway, so Variant 3 cannot share the primary
     * instance: {@link #GATEWAY_ORIGIN} stays server-mode for the landed {@code Bff*IT} suite while
     * the {@code Bff*Cookie*IT} suite drives this origin.
     */
    static final String COOKIE_GATEWAY_ORIGIN = "https://localhost:10445";

    /**
     * Browser-facing origin of the <em>second</em> cookie-mode gateway instance
     * ({@code api-sheriff-cookie-2}, published host port {@code 10446 -> } container {@code 8443}).
     * <p>
     * A separate process sharing nothing with {@link #COOKIE_GATEWAY_ORIGIN} but the AES sealing key
     * — no session store, no volume, no sticky routing. It is never logged into: it exists so
     * {@code BffCookieStatelessnessIT} can replay a cookie sealed by the first instance against it
     * and prove portability as an observed two-instance fact rather than an inference.
     */
    static final String COOKIE_GATEWAY_PEER_ORIGIN = "https://localhost:10446";

    /** The container-internal Keycloak authority the {@code integration} realm frontendUrl pins. */
    static final String KEYCLOAK_INTERNAL_AUTHORITY = "keycloak:8443";

    /** The host-published Keycloak authority the test JVM can actually reach (compose {@code 1443 -> 8443}). */
    static final String KEYCLOAK_HOST_AUTHORITY = "localhost:1443";

    /** The seeded confidential-realm test user (see {@code integration-realm.json}). */
    static final String USERNAME = "integration-user";

    /** The seeded test user's password. */
    static final String PASSWORD = "integration-password";

    /** Matches the Keycloak username/password form's {@code login-actions/authenticate} action URL. */
    private static final Pattern FORM_ACTION =
            Pattern.compile("action=\"([^\"]*login-actions/authenticate[^\"]*)\"");

    private BffKeycloakLoginFlow() {
        // static helper
    }

    /**
     * The gateway session established by a completed login: the cookies to replay on subsequent
     * protected requests (the session cookie plus any residual gateway cookies), plus the callback
     * response's cookies with their attributes intact.
     *
     * @param gatewayCookies the gateway cookie jar carrying the live session cookie
     * @param callbackCookies the cookies the callback response set, with {@code Secure} /
     *                        {@code HttpOnly} / {@code SameSite} / {@code Path} / {@code Domain}
     *                        attributes preserved — the flat {@code gatewayCookies} map drops them,
     *                        and the sealed-cookie hardening contract is asserted on these
     */
    record Session(Map<String, String> gatewayCookies, Cookies callbackCookies) {
    }

    /**
     * Runs the full auth-code flow against the primary (server-mode) gateway origin.
     *
     * @param startPath the gateway path to navigate to (a require:session route such as
     *                  {@code /bff-session/get}); the unauthenticated navigation triggers the login
     *                  redirect into the IdP
     * @return the established gateway {@link Session}
     */
    static Session login(String startPath) {
        return login(startPath, GATEWAY_ORIGIN);
    }

    /**
     * Runs the full auth-code flow starting from a require:session navigation on {@code startPath}
     * against the given gateway origin, and returns the gateway session cookies established by the
     * callback.
     * <p>
     * The origin is a parameter because the session mode is a property of the whole gateway: the
     * cookie-mode variant runs on its own instance ({@link #COOKIE_GATEWAY_ORIGIN}), and the flow
     * itself — the {@code 302} chain and the query-mode callback navigation — is identical for both.
     * Only the browser-facing origin differs; the callback {@code Location} is an absolute URL
     * emitted by Keycloak from the instance's own {@code redirect_uri}, so it already targets the
     * right instance.
     *
     * @param startPath     the gateway path to navigate to (a require:session route)
     * @param gatewayOrigin the browser-facing gateway origin to drive
     * @return the established gateway {@link Session}
     */
    static Session login(String startPath, String gatewayOrigin) {
        return login(startPath, gatewayOrigin, Map.of());
    }

    /**
     * Runs the full auth-code flow with the browser already holding {@code initialGatewayCookies} at
     * the moment it navigates onto {@code startPath}.
     * <p>
     * This is the re-authentication shape: a browser whose session cookie the gateway can no longer
     * authenticate still <em>sends</em> that cookie on the navigation, and the flow must complete a
     * fresh login from there. Starting from an empty jar would exercise a first-ever login instead
     * and would never observe the stale cookie on the wire, so the seam the re-authentication
     * actually depends on would go unproven.
     *
     * @param startPath             the gateway path to navigate to (a require:session route)
     * @param gatewayOrigin         the browser-facing gateway origin to drive
     * @param initialGatewayCookies the gateway cookies the browser already holds; entries the flow
     *                              re-sets (the binding cookie, the session cookie) are overwritten
     *                              as the round trip progresses
     * @return the established gateway {@link Session}
     */
    static Session login(String startPath, String gatewayOrigin, Map<String, String> initialGatewayCookies) {
        Map<String, String> gatewayCookies = new HashMap<>(initialGatewayCookies);
        Map<String, String> keycloakCookies = new HashMap<>();

        // Step 1 — navigate onto the require:session route: the gateway sets the pending-auth binding
        // cookie and 302s the browser to the IdP authorization endpoint.
        Response initiation = gateway(gatewayCookies, gatewayOrigin)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(startPath)
                .then().statusCode(302).extract().response();
        gatewayCookies.putAll(initiation.getCookies());
        String authorizationUrl = rewriteToHost(location(initiation));

        // Step 2 — GET the Keycloak login page and scrape the form action.
        Response loginPage = keycloak(keycloakCookies)
                .redirects().follow(false)
                .when().get(authorizationUrl)
                .then().statusCode(200).extract().response();
        keycloakCookies.putAll(loginPage.getCookies());
        String formAction = rewriteToHost(extractFormAction(loginPage.asString()));

        // Step 3 — POST the credentials. The gateway drives the authorization request with
        // response_mode=query, so on a successful login Keycloak answers a 302 whose Location is the
        // gateway redirect_uri (/auth/callback) with the authorization code + state in the QUERY STRING.
        Response credentials = keycloak(keycloakCookies)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", USERNAME)
                .formParam("password", PASSWORD)
                .redirects().follow(false)
                .when().post(formAction)
                .then().statusCode(302).extract().response();
        String callbackUrl = rewriteToHost(location(credentials));

        // Step 4 — follow that redirect to the gateway callback exactly as a browser would: a plain
        // top-level GET navigation carrying code + state in the query. The gateway parses the code from
        // the RAW QUERY (never a collapsed parameter map — the BFF-13 duplicate-parameter defence),
        // exchanges it for tokens, creates the session, and sets the session cookie on a 302 back to the
        // original path. The binding cookie from Step 1 rides the gateway jar.
        //
        // urlEncodingEnabled(false) is load-bearing here, as it is on the keycloak() spec: the Location
        // Keycloak emitted is already percent-encoded, and REST Assured's default re-encoding would
        // double-encode the code/state/iss values and the gateway would reject the callback.
        Response callback = gateway(gatewayCookies, gatewayOrigin)
                .urlEncodingEnabled(false)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(callbackUrl)
                .then().statusCode(302).extract().response();
        gatewayCookies.putAll(callback.getCookies());

        return new Session(gatewayCookies, callback.getDetailedCookies());
    }

    /**
     * A request spec bound to the primary (server-mode) gateway origin.
     *
     * @param cookies the gateway cookie jar
     * @return the configured request specification
     */
    static RequestSpecification gateway(Map<String, String> cookies) {
        return gateway(cookies, GATEWAY_ORIGIN);
    }

    /**
     * A request spec bound to the given gateway origin with relaxed HTTPS and the supplied cookie jar.
     *
     * @param cookies       the gateway cookie jar
     * @param gatewayOrigin the browser-facing gateway origin to bind
     * @return the configured request specification
     */
    static RequestSpecification gateway(Map<String, String> cookies, String gatewayOrigin) {
        // Default URL encoding stays ON in this spec: its other callers issue plain paths and rely on
        // REST Assured to encode them. The ONE call that must NOT re-encode is the Step 4 callback
        // navigation, which replays a Location Keycloak already percent-encoded; that call opts out
        // locally with urlEncodingEnabled(false) rather than flipping the default for every caller.
        return given().relaxedHTTPSValidation().baseUri(gatewayOrigin).cookies(cookies);
    }

    /**
     * A request spec with relaxed HTTPS and the supplied Keycloak cookie jar; Keycloak calls always
     * use absolute (host-rewritten) URLs, so no base URI is bound.
     *
     * @param cookies the Keycloak cookie jar
     * @return the configured request specification
     */
    static RequestSpecification keycloak(Map<String, String> cookies) {
        // urlEncodingEnabled(false): the authorization URL (and the login-form action) are already
        // percent-encoded by the gateway/Keycloak. REST Assured's default re-encoding rewrites the
        // scope separator '+' to %2B, which Keycloak reads as a single literal scope
        // "openid+profile+email" -> invalid_scope. Disabling it sends the URL verbatim.
        return given().relaxedHTTPSValidation().urlEncodingEnabled(false).cookies(cookies);
    }

    /**
     * Rewrites the container-internal Keycloak authority to the host-published authority so the
     * host-driven test JVM can follow a redirect the gateway/IdP emitted with the internal authority.
     *
     * @param url the URL to rewrite
     * @return the URL with {@code keycloak:8443} replaced by {@code localhost:1443}
     */
    static String rewriteToHost(String url) {
        return url.replace(KEYCLOAK_INTERNAL_AUTHORITY, KEYCLOAK_HOST_AUTHORITY);
    }

    /**
     * Extracts the mandatory {@code Location} header from a redirect response.
     *
     * @param response the redirect response
     * @return the {@code Location} value
     */
    static String location(Response response) {
        String value = response.getHeader("Location");
        if (value == null) {
            throw new IllegalStateException("expected a Location header on a redirect response");
        }
        return value;
    }

    private static String extractFormAction(String html) {
        Matcher matcher = FORM_ACTION.matcher(html);
        if (!matcher.find()) {
            String body = html == null ? "" : html;
            String snippet = body.substring(0, Math.min(body.length(), 1500));
            throw new IllegalStateException("Keycloak login form action not found in login page (length="
                    + body.length() + "). Page head:\n" + snippet);
        }
        return unescapeHtml(matcher.group(1));
    }

    /**
     * Decodes the small set of HTML entities Keycloak emits in the login-form action URL
     * (predominantly {@code &amp;} between its query parameters).
     *
     * @param value the raw attribute value
     * @return the decoded value
     */
    private static String unescapeHtml(String value) {
        return value.replace("&amp;", "&")
                .replace("&#x3D;", "=").replace("&#61;", "=")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
    }
}
