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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises stage 4 (offline bearer-token validation) over the public HTTPS edge against the
 * bearer-protected {@code /secure} route.
 * <p>
 * The mounted {@code secure} anchor declares {@code require: bearer}, and the gateway trusts
 * <em>two</em> issuers that matter here. The {@code it-static} issuer loads its key set from the
 * static JWKS file mounted at {@code /app/certificates/test-jwks.json}, so the validator is ready
 * offline with no Keycloak dependency — but the suite holds no private key for it and therefore
 * cannot mint a token it would accept. The {@code integration-keycloak} issuer, by contrast, is the
 * live compose Keycloak {@code integration} realm, which <em>can</em> mint one. The
 * admitted-and-forwarded path is consequently in scope, and is driven below.
 * <p>
 * <strong>Rejection scenarios.</strong> A missing token and a malformed token must both be rejected
 * {@code 401} at the gateway and the upstream must never be reached. A forwarded request would carry
 * the {@code go-httpbin} echo (a non-null {@code method}); its absence is the observable proof the
 * upstream count stayed {@code 0} on every bearer rejection.
 * <p>
 * <strong>The admitted scenario is a regression control.</strong> Before the {@code Authorization}
 * header-value carve-out, a real Keycloak access token plus its {@code Bearer } prefix exceeded the
 * strict preset's 1024-character pre-route header-value cap, so the request was rejected {@code 400}
 * by {@code BasicChecksStage} — the non-skippable floor that runs <em>before</em> route selection —
 * and stage-4 validation never ran at all. Every test in this class passed regardless, because none
 * of them carried a full-size token. That gap is what this scenario closes.
 */
class BearerValidationIT extends BaseIntegrationTest {

    /** The seeded confidential client of the {@code integration} realm (direct grant enabled). */
    private static final String CLIENT_ID = "integration-client";

    /** The seeded client secret (see {@code integration-realm.json}); a test-fixture value only. */
    private static final String CLIENT_SECRET = "integration-secret";

    private static final String TOKEN_ENDPOINT =
            "https://" + BffKeycloakLoginFlow.KEYCLOAK_HOST_AUTHORITY
                    + "/realms/integration/protocol/openid-connect/token";

    @Test
    @DisplayName("a request with no bearer token is rejected 401 and never forwarded")
    void missingTokenRejected() {
        var response = given()
                .when()
                .get("/secure/get")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("a request with a malformed bearer token is rejected 401 and never forwarded")
    void malformedTokenRejected() {
        var response = given()
                .header("Authorization", "Bearer not-a-real-jwt")
                .when()
                .get("/secure/get")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .extract();

        assertTrue(response.contentType().contains("application/problem+json"));
        assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
    }

    @Test
    @DisplayName("the public require:none proxy route does not demand a bearer token")
    void publicRouteDoesNotRequireBearer() {
        var response = given()
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"));
    }

    @Test
    @DisplayName("a valid Keycloak bearer token is admitted and forwarded to the upstream")
    void validBearerTokenAdmittedAndForwarded() {
        // Arrange — a real, full-size access token from the trusted integration realm. The length
        // assertion is what keeps this scenario a genuine regression control: the carve-out is only
        // exercised when the header value actually exceeds the strict preset's 1024-character
        // pre-route cap, so without it the test would pass vacuously — green while never reaching
        // the code path it exists to prove — should the IdP ever mint a token short enough to be
        // admitted by the baseline outright.
        String authorization = "Bearer " + mintIntegrationRealmAccessToken();
        assertTrue(authorization.length() > 1024,
                () -> "the integration token must exceed the strict 1024-character baseline to exercise "
                        + "the Authorization carve-out, but measured %d".formatted(authorization.length()));

        // Act
        var response = given()
                .header("Authorization", authorization)
                .when()
                .get("/secure/get")
                .then()
                .statusCode(200)
                .extract();

        // Assert — a non-null echoed method is the observable proof the request was not merely
        // accepted but actually forwarded to the go-httpbin upstream.
        assertEquals("GET", response.path("method"),
                "an admitted bearer request must reach the upstream and echo its method");
    }

    /**
     * Mints a real access token through the {@code integration} realm's direct-grant
     * ({@code grant_type=password}) endpoint, reusing the seeded client and user the browser-driven
     * BFF suite already relies on. The realm pins {@code frontendUrl https://keycloak:8443}, so the
     * minted token carries the container-internal {@code iss} the gateway's {@code integration-keycloak}
     * issuer declares — the host-published mint port does not change the issuer claim.
     *
     * @return the raw access token
     */
    private static String mintIntegrationRealmAccessToken() {
        Response response = given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "password")
                .formParam("client_id", CLIENT_ID)
                .formParam("client_secret", CLIENT_SECRET)
                .formParam("username", BffKeycloakLoginFlow.USERNAME)
                .formParam("password", BffKeycloakLoginFlow.PASSWORD)
                .when()
                .post(TOKEN_ENDPOINT)
                .then()
                .extract().response();

        String accessToken = response.path("access_token");
        // Fail loudly rather than driving the gateway with a null token, which would surface as a
        // confusing 401 and mis-attribute an IdP/realm problem to the bearer-validation stage.
        assertNotNull(accessToken,
                () -> "the integration realm minted no access_token (HTTP %d): %s"
                        .formatted(response.statusCode(), response.asString()));
        return accessToken;
    }
}
