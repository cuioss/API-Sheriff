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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.path.json.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the reserved {@code /auth/userinfo} session/user-info fold end-to-end.
 * <p>
 * Without a session the fold is closed ({@code 401}); with a live session it serves the curated
 * default view, capped by the operator claim allowlist. The mounted {@code oidc.user_info} config sets
 * {@code default_view: [sub, preferred_username]}, so the response discloses exactly those claims — the
 * seeded {@code integration-user}'s {@code preferred_username} is the observable identity proof.
 * <p>
 * <strong>The body is asserted byte for byte.</strong> The fold renders its answers through the
 * gateway's own JSON serializer, and this suite runs against the native image, where a serializer
 * that needs reflection it was not registered for fails or silently drops members. Parsing the
 * response and reading single members would pass against a body with different member order,
 * insignificant whitespace, escaped solidi or a second rendering of the same value; comparing the raw
 * bytes does not. The refusal body is a constant. A disclosure carries the session's own instants, so
 * its expected text is assembled around the values the same response states — every member name, the
 * member order, the separators and the claim values are the test's own
 * ({@link #expectedDisclosure(String, io.restassured.path.json.JsonPath)}).
 */
class BffUserInfoIT extends BaseIntegrationTest {

    /** The RFC 9457 body of the no-live-session refusal, exactly as the fold renders it. */
    private static final String NO_SESSION_BODY = "{\"type\":\"about:blank\",\"title\":\"No live session\",\"status\":401}";

    @Test
    @DisplayName("the user-info fold is closed without a session (401) and answers the exact problem body")
    void userInfoRequiresSession() {
        var response = given()
                .header("Accept", "application/json")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(401)
                .extract();

        assertEquals(NO_SESSION_BODY, response.body().asString(),
                "the refusal body must be the compact problem document, byte for byte");
        assertEquals("no-store", response.header("Cache-Control"), "an identity answer is never cacheable");
    }

    @Test
    @DisplayName("the user-info fold renders a single string claim and the session metadata byte for byte")
    void userInfoRendersAStringClaimByteForByte() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .queryParam("claims", "preferred_username")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(200)
                .extract();

        assertEquals(expectedDisclosure("\"preferred_username\":\"integration-user\"", response.jsonPath()),
                response.body().asString(),
                "the disclosure must be the compact document with its members in the fold's order, byte for byte");
    }

    @Test
    @DisplayName("the user-info fold serves the curated default view for a live session")
    void userInfoServesCuratedDefaultView() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("integration-user", response.path("claims.preferred_username"),
                "the curated default view must disclose the session user's preferred_username");
        assertNull(response.path("claims.client_secret"),
                "the fold must never disclose a claim outside the operator allowlist");
    }

    /**
     * Regression pin for AS-7: a list-valued ID-token claim is disclosed as a native JSON array.
     * <p>
     * {@code groups} is allowlisted in the mounted {@code oidc.user_info} config and emitted into the ID
     * token by the integration realm's group-membership mapper as a list ({@code ["test-group"]}). Before
     * the fix the claim projection rendered it through {@code toString()}, so the fold answered the
     * string {@code "[test-group]"} — a value no JSON consumer can iterate. This test runs against the
     * native image, where that defect was observed, and fails on the string form.
     */
    @Test
    @DisplayName("the user-info fold discloses a list-valued claim as a native JSON array (AS-7)")
    void userInfoDisclosesGroupsAsJsonArray() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        var response = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .queryParam("claims", "groups")
                .when()
                .get("/auth/userinfo")
                .then()
                .statusCode(200)
                .extract();

        Object groups = response.path("claims.groups");
        List<?> groupList = assertInstanceOf(List.class, groups,
                "claims.groups must be a JSON array, not the toString() projection \"[test-group]\"");
        assertEquals(List.of("test-group"), groupList,
                "the array must carry the realm group the integration-user is a member of");
        assertEquals(Set.of("groups"), response.jsonPath().getMap("claims").keySet(),
                "an explicit claims=groups selection must disclose exactly that claim");
        assertEquals(expectedDisclosure("\"groups\":[\"test-group\"]", response.jsonPath()),
                response.body().asString(),
                "the disclosure must render the array natively and compactly, byte for byte");
    }

    /**
     * The exact text of a disclosure: the {@code claims} member holding {@code claimsMembers}, then the
     * {@code session} member holding {@code expires_at}, {@code auth_time} and {@code acr} in that
     * order, the last two only when the session has them.
     * <p>
     * The three session values are taken from the parsed response, because they are this session's
     * own and no test can know them beforehand. Everything else — the member names, their order, the
     * separators, the absence of whitespace — is stated here, so a serializer that renders any of it
     * differently fails the comparison.
     *
     * @param claimsMembers the expected content of the {@code claims} object, without its braces
     * @param parsed        the parsed response the session values are read from
     * @return the expected response body
     */
    private static String expectedDisclosure(String claimsMembers, JsonPath parsed) {
        String expiresAt = parsed.getString("session.expires_at");
        assertNotNull(expiresAt, "the session metadata must state expires_at");
        StringBuilder expected = new StringBuilder("{\"claims\":{").append(claimsMembers)
                .append("},\"session\":{\"expires_at\":\"").append(expiresAt).append('"');
        String authTime = parsed.getString("session.auth_time");
        if (authTime != null) {
            expected.append(",\"auth_time\":\"").append(authTime).append('"');
        }
        String acr = parsed.getString("session.acr");
        if (acr != null) {
            expected.append(",\"acr\":\"").append(acr).append('"');
        }
        return expected.append("}}").toString();
    }
}
