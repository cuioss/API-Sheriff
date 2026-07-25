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
 * <strong>response_mode=form_post.</strong> The engine drives the authorization request with
 * {@code response_mode=form_post}, so the final IdP leg is not a {@code 302} carrying the code in the
 * query: after a successful credential POST Keycloak returns a {@code 200} auto-submit HTML form that
 * POSTs the {@code code}/{@code state} to the gateway {@code redirect_uri}. This helper scrapes that
 * form's action and hidden inputs and replays them as an {@code application/x-www-form-urlencoded}
 * POST to the gateway callback — exactly the browser auto-submit — to complete the login.
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

    /**
     * Matches the {@code action} of the form_post auto-submit {@code <form>} (the gateway
     * redirect_uri). Tolerant of attribute ordering — {@code [^>]*} skips any attributes (e.g.
     * {@code method="post"}) that precede {@code action} inside the opening tag.
     */
    private static final Pattern FORM_POST_ACTION =
            Pattern.compile("<form[^>]*\\baction=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** Matches each {@code <input ...>} tag of the form_post auto-submit form. */
    private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Extracts the {@code name} attribute from an {@code <input>} tag, regardless of attribute order. */
    private static final Pattern INPUT_NAME = Pattern.compile("\\bname=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** Extracts the {@code value} attribute from an {@code <input>} tag, regardless of attribute order. */
    private static final Pattern INPUT_VALUE = Pattern.compile("\\bvalue=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

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

        // Step 3 — POST the credentials. The engine drives the authorization request with
        // response_mode=form_post, so Keycloak does NOT 302 with the code in the query: on a successful
        // login it returns a 200 auto-submit HTML form whose action is the gateway redirect_uri
        // (/auth/callback) and whose hidden inputs carry the authorization code + state (+ any others).
        Response formPost = keycloak(keycloakCookies)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", USERNAME)
                .formParam("password", PASSWORD)
                .redirects().follow(false)
                .when().post(formAction)
                .then().statusCode(200).extract().response();
        String formPostHtml = formPost.asString();
        String callbackAction = rewriteToHost(extractFormPostAction(formPostHtml));
        Map<String, String> callbackFields = extractHiddenInputs(formPostHtml);

        // Step 4 — replay the form_post to the gateway callback exactly as the browser auto-submit would:
        // an application/x-www-form-urlencoded POST carrying code + state (+ any other hidden fields). The
        // gateway parses the code from the BODY, exchanges it for tokens, creates the server-side session,
        // and sets the session cookie on a 302 back to the original path. The binding cookie from Step 1
        // rides the gateway jar.
        Response callback = gateway(gatewayCookies)
                .contentType("application/x-www-form-urlencoded")
                .formParams(callbackFields)
                .redirects().follow(false)
                .when().post(callbackAction)
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
            String body = html == null ? "" : html;
            String snippet = body.substring(0, Math.min(body.length(), 1500));
            throw new IllegalStateException("Keycloak login form action not found in login page (length="
                    + body.length() + "). Page head:\n" + snippet);
        }
        return unescapeHtml(matcher.group(1));
    }

    /**
     * Extracts the {@code action} of the form_post auto-submit form — the gateway redirect_uri the
     * browser would POST the code/state to.
     *
     * @param html the 200 form_post document Keycloak returned after a successful credential POST
     * @return the (HTML-unescaped) form action URL
     */
    private static String extractFormPostAction(String html) {
        Matcher matcher = FORM_POST_ACTION.matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("form_post auto-submit form action not found in Keycloak response");
        }
        return unescapeHtml(matcher.group(1));
    }

    /**
     * Extracts every hidden input {@code name -> value} of the form_post auto-submit form. Parses each
     * {@code <input>} tag independently and reads {@code name}/{@code value} in either order, so the
     * result is insensitive to Keycloak's attribute ordering; every value is HTML-unescaped.
     *
     * @param html the 200 form_post document
     * @return the form fields to replay to the gateway callback (must include {@code code} and {@code state})
     * @throws IllegalStateException when {@code code} or {@code state} is absent (the login did not complete)
     */
    private static Map<String, String> extractHiddenInputs(String html) {
        Map<String, String> fields = new HashMap<>();
        Matcher tags = INPUT_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher name = INPUT_NAME.matcher(tag);
            Matcher value = INPUT_VALUE.matcher(tag);
            if (name.find() && value.find()) {
                fields.put(unescapeHtml(name.group(1)), unescapeHtml(value.group(1)));
            }
        }
        if (!fields.containsKey("code") || !fields.containsKey("state")) {
            throw new IllegalStateException(
                    "form_post auto-submit form missing code/state hidden inputs; found " + fields.keySet());
        }
        return fields;
    }

    /**
     * Decodes the small set of HTML entities Keycloak emits in form action URLs and hidden-input
     * values (predominantly {@code &amp;} in URL-encoded {@code code}/{@code state} values).
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
