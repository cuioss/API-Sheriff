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

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Drives a scripted, browser-less OIDC authorization-code flow against the compose Keycloak
 * {@code integration} realm and the server-mode BFF gateway, following the {@code 302} chain with a
 * cookie jar exactly as a browser would.
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
 * This helper is a test-support class (no {@code *IT} suffix), so Failsafe does not run it as a
 * suite; the six {@code Bff*IT} classes call {@link #login(String)} to establish a live session.
 */
final class BffKeycloakLoginFlow {

    /** Browser-facing gateway origin: published host port {@code 10443 -> } container {@code 8443}. */
    static final String GATEWAY_ORIGIN = "https://localhost:10443";

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
     * protected requests (the session cookie plus any residual gateway cookies).
     *
     * @param gatewayCookies the gateway cookie jar carrying the live session cookie
     */
    record Session(Map<String, String> gatewayCookies) {
    }

    /**
     * Runs the full auth-code flow starting from a require:session navigation on {@code startPath}
     * and returns the gateway session cookies established by the callback.
     *
     * @param startPath the gateway path to navigate to (a require:session route such as
     *                  {@code /bff-session/get}); the unauthenticated navigation triggers the login
     *                  redirect into the IdP
     * @return the established gateway {@link Session}
     */
    static Session login(String startPath) {
        Map<String, String> gatewayCookies = new HashMap<>();
        Map<String, String> keycloakCookies = new HashMap<>();

        // Step 1 — navigate onto the require:session route: the gateway sets the pending-auth binding
        // cookie and 302s the browser to the IdP authorization endpoint.
        Response initiation = gateway(gatewayCookies)
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

        // Step 3 — POST the credentials; Keycloak 302s to the gateway callback with code + state.
        Response formPost = keycloak(keycloakCookies)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", USERNAME)
                .formParam("password", PASSWORD)
                .redirects().follow(false)
                .when().post(formAction)
                .then().statusCode(302).extract().response();
        String callbackUrl = location(formPost);

        // Step 4 — follow the callback on the gateway: the code is exchanged for tokens, the server-side
        // session is created, and the session cookie is set on a 302 back to the original path.
        Response callback = gateway(gatewayCookies)
                .redirects().follow(false)
                .when().get(callbackUrl)
                .then().statusCode(302).extract().response();
        gatewayCookies.putAll(callback.getCookies());

        return new Session(gatewayCookies);
    }

    /**
     * A request spec bound to the gateway origin with relaxed HTTPS and the supplied cookie jar.
     *
     * @param cookies the gateway cookie jar
     * @return the configured request specification
     */
    static RequestSpecification gateway(Map<String, String> cookies) {
        return given().relaxedHTTPSValidation().baseUri(GATEWAY_ORIGIN).cookies(cookies);
    }

    /**
     * A request spec with relaxed HTTPS and the supplied Keycloak cookie jar; Keycloak calls always
     * use absolute (host-rewritten) URLs, so no base URI is bound.
     *
     * @param cookies the Keycloak cookie jar
     * @return the configured request specification
     */
    static RequestSpecification keycloak(Map<String, String> cookies) {
        return given().relaxedHTTPSValidation().cookies(cookies);
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
            throw new IllegalStateException("Keycloak login form action not found in login page");
        }
        return matcher.group(1).replace("&amp;", "&");
    }
}
