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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the reserved {@code /auth/login} login-initiation fold end-to-end.
 * <p>
 * Without a session the fold starts a fresh auth-code flow. The gateway pushes the authorization
 * request to the identity provider over the back channel (RFC 9126) and answers a {@code 302} into the
 * IdP authorization endpoint that carries {@code client_id} and the {@code request_uri} Keycloak
 * issued for the pushed request, and nothing else: the scope, the {@code state}, the {@code nonce},
 * the {@code redirect_uri} and the PKCE challenge travel in the pushed request and are never shown to
 * the browser. What the pushed request contains is therefore not observable from this suite; it is
 * asserted at unit level against the request body a stub identity provider records
 * ({@code BffRuntimeProducerTest}, {@code PushedAuthorizationRequestsTest}).
 * <p>
 * With a live session the fold short-circuits {@code 302} to the same-origin-validated
 * {@code returnUrl} target rather than re-driving the IdP, so an already-authenticated browser is not
 * bounced through Keycloak again.
 */
class BffLoginInitiationIT extends BaseIntegrationTest {

    /** The client the primary gateway instance authenticates as (see {@code sheriff-config/gateway.yaml}). */
    private static final String CLIENT_ID = "integration-client";

    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_REQUEST_URI = "request_uri";

    /** The path of the realm's authorization endpoint, below the realm base. */
    private static final String AUTHORIZATION_ENDPOINT_PATH = "/protocol/openid-connect/auth";

    /**
     * The authorization parameters a pushed-request redirect must not carry. Each of them is a
     * parameter of the pushed request; in the redirect it would be readable and changeable by the
     * browser.
     */
    private static final List<String> PUSHED_ONLY_PARAMETERS = List.of("scope", "state", "nonce",
            "redirect_uri", "code_challenge", "code_challenge_method", "response_mode", "response_type");

    @Test
    @DisplayName("login initiation without a session redirects 302 into the IdP with client_id and request_uri only")
    void loginInitiationStartsAuthCodeFlow() {
        Response response = given()
                .redirects().follow(false)
                .when()
                .get("/auth/login")
                .then()
                .statusCode(302)
                .extract().response();

        assertPushedRequestRedirect(response, CLIENT_ID);
    }

    @Test
    @DisplayName("login initiation with a live session short-circuits to the validated return target, not the IdP")
    void loginInitiationShortCircuitsForLiveSession() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .redirects().follow(false)
                .when()
                .get("/auth/login?returnUrl=/home")
                .then()
                .statusCode(302)
                .extract();

        String location = response.header("Location");
        assertNotNull(location, "a live-session login initiation must still redirect");
        assertFalse(location.contains(AUTHORIZATION_ENDPOINT_PATH),
                "an already-authenticated session must not be re-driven through the IdP");
        // Asserting the exact target — not merely "not the IdP" — is what keeps this test honest: a
        // returnUrl the gateway failed to read degrades to the default "/", which would still satisfy
        // the assertion above and leave the short-circuit's return-target propagation unexercised.
        assertEquals("/home", location,
                "the short-circuit must redirect to the returnUrl the browser asked for");
    }

    @Test
    @DisplayName("a login started without returnUrl lands on / when oidc.login.default_return_url is unset")
    void loginWithoutReturnUrlLandsOnRootFallback() {
        // The primary (server-mode) stack declares no oidc.login.default_return_url, so the post-login
        // fallback is '/'. /auth/login is itself the start path: the flow's first navigation is the
        // initiation, which 302s into Keycloak exactly as a session-route navigation does.
        Session session = BffKeycloakLoginFlow.login("/auth/login");

        assertEquals("/", session.callbackLocation(),
                "a login with no return target must land on the '/' fallback on the server stack");
    }

    /**
     * Asserts that a {@code 302} which starts an authorization-code flow is the redirect of a
     * <em>pushed</em> authorization request: it targets the realm's authorization endpoint, and its
     * query is exactly {@code client_id} and {@code request_uri}, each named once.
     * <p>
     * Package-private so every suite that inspects a login redirect — of any gateway instance, whose
     * client the caller names — states the same contract.
     *
     * @param initiation the {@code 302} that starts the authorization-code flow
     * @param clientId   the client the gateway instance under test authenticates as
     */
    static void assertPushedRequestRedirect(Response initiation, String clientId) {
        String location = BffKeycloakLoginFlow.location(initiation);
        URI target = URI.create(location);
        assertNotNull(target.getPath(), () -> "the login redirect names no path: " + location);
        assertTrue(target.getPath().endsWith(AUTHORIZATION_ENDPOINT_PATH),
                () -> "the login must redirect into the OIDC authorization endpoint, got " + location);
        Map<String, String> parameters = queryParameters(target);

        assertAll("the redirect of a pushed authorization request",
                () -> assertEquals(Set.of(PARAM_CLIENT_ID, PARAM_REQUEST_URI), parameters.keySet(),
                        () -> "the redirect must carry exactly client_id and request_uri: " + location),
                () -> assertEquals(clientId, parameters.get(PARAM_CLIENT_ID),
                        "the redirect must name the client that pushed the request"),
                () -> assertFalse(String.valueOf(parameters.get(PARAM_REQUEST_URI)).isBlank(),
                        "the redirect must carry the request_uri the identity provider issued"),
                () -> assertAll(PUSHED_ONLY_PARAMETERS.stream().map(parameter -> () -> assertFalse(
                        parameters.containsKey(parameter),
                        () -> parameter + " belongs to the pushed request and must not appear in the redirect: "
                                + location))));
    }

    /**
     * The decoded query parameters of a redirect target, in URL order. A name that occurs twice is
     * refused: reading only one occurrence would let a second, different value pass unnoticed.
     */
    private static Map<String, String> queryParameters(URI target) {
        String rawQuery = target.getRawQuery();
        assertNotNull(rawQuery, () -> "the login redirect carries no query: " + target);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] nameValue = pair.split("=", 2);
            String name = URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8);
            String value = nameValue.length == 2 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "";
            assertNull(parameters.put(name, value),
                    () -> "the login redirect names the parameter " + name + " twice: " + target);
        }
        return parameters;
    }
}
