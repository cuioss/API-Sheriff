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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the two server-mode BFF logout legs.
 * <p>
 * <strong>RP-initiated logout (caveat a).</strong> The gateway destroys the session locally and revokes
 * its refresh token at the identity provider (RFC 7009) before it builds the end-session redirect. The
 * suite asserts both effects, each where it can actually be observed:
 * <ul>
 *   <li><em>Local destruction.</em> The old cookie jar no longer authorizes the protected route: it is
 *       challenged {@code 401}.</li>
 *   <li><em>Revocation at Keycloak.</em> Keycloak answers a refresh-token revocation by detaching the
 *       revoking client from the user session the token belongs to, so the effect is read off the realm
 *       through the admin API. Before the logout the user session whose id is the {@code sid} of the
 *       session's ID token names {@value #CLIENT_ID} among its clients; after it, no session of the user
 *       with that id does.</li>
 * </ul>
 * <strong>Why the detachment can only come from the revocation.</strong> The suite does not follow the
 * end-session redirect, so Keycloak never sees the browser's end-session request and nothing else in the
 * logout reaches the realm. Bound to a revocation that sent nothing, the gateway would leave the user
 * session naming {@value #CLIENT_ID} and this assertion would go red. The realm is read once, right
 * after the {@code 302}, because the revocation is made synchronously before the redirect is built: a
 * revocation that landed only later would be a defect of that contract, not a timing margin to wait out.
 * <p>
 * <strong>What it does not assert.</strong> That the access token is revoked: it is not, and stays valid
 * at a resource server until it expires.
 * <p>
 * The {@code /bff/logout} entry redirects {@code 302} into the RP-initiated round-trip that ends at the
 * configured {@code final_redirect}. That redirect targets the realm's end-session endpoint and its query
 * carries the {@code id_token_hint}, so the suite also asserts the two headers the gateway answers it
 * with: {@code Referrer-Policy: no-referrer} and {@code Cache-Control: no-store}. Neither the location
 * nor the token it carries appears in any message of this suite.
 * <p>
 * <strong>Why the admin-API calls live in this class.</strong> {@code FailsafeConcurrencyContractTest}
 * detects admin-API use by reading the source text of each class, so a helper elsewhere would hide this
 * suite's admin-API use from it. This class carries no {@code sleep-bound} tag and runs in the
 * sequential execution: it logs in as the default user, whose sessions other untagged suites hold too,
 * and the admin API is only read here, never used to end a session.
 * <p>
 * <strong>Back-channel logout (caveat b).</strong> The back-channel {@code LogoutTokenVerifier} is
 * bound to the id-token JWKS validation bridge. This suite proves the refusing side only: the receiver
 * is wired at the live edge (reachable, not {@code NO_ROUTE_MATCHED}) and fail-closed, so an empty
 * back-channel post is rejected {@code 400} and marked uncacheable. The accepting side — a real
 * Keycloak-issued logout token ending the session — is driven by {@link BffBackchannelLogoutIT}, which
 * triggers an IdP-initiated logout through the Keycloak admin API.
 */
class BffLogoutIT extends BaseIntegrationTest {

    /**
     * The end-session endpoint the integration realm publishes. Keycloak runs with the static hostname
     * {@code https://localhost:1443}, so the discovery document names that published identity — even
     * though the gateway fetched it over its back channel, {@code keycloak:8443} — and that is what the
     * gateway redirects the browser to. A back-channel address on this redirect would strand the browser.
     */
    private static final String END_SESSION_ENDPOINT = "https://" + BffKeycloakLoginFlow.KEYCLOAK_HOST_AUTHORITY
            + "/realms/integration/protocol/openid-connect/logout";

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://" + BffKeycloakLoginFlow.KEYCLOAK_HOST_AUTHORITY;

    /** The realm client the primary server-mode gateway authenticates as, and revokes the token as. */
    private static final String CLIENT_ID = "integration-client";

    /** The query parameter of the end-session redirect that carries the session's ID token. */
    private static final String ID_TOKEN_HINT = "id_token_hint";

    /** The {@code sid} claim of a decoded JWT payload. */
    private static final Pattern SID_CLAIM = Pattern.compile("\"sid\"\\s*:\\s*\"([^\"]+)\"");

    private static final int OK = 200;

    @Test
    @DisplayName("RP-initiated logout destroys the local session and revokes its refresh token at Keycloak")
    void rpInitiatedLogoutDestroysLocalSessionAndRevokesTheRefreshToken() {
        // Arrange — a real login, then the Keycloak sessions of the user that integration-client is
        // attached to, read before anything is logged out.
        Session session = BffKeycloakLoginFlow.login("/app/get");
        String adminToken = adminToken();
        String userId = userIdOf(adminToken);
        Set<String> attachedBeforeLogout = sessionIdsNamingTheClient(adminToken, userId);

        // Act — log out without following the redirect, so the end-session request never reaches Keycloak.
        var logout = BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .redirects().follow(false)
                .when()
                .get("/bff/logout")
                .then()
                .statusCode(302)
                .extract();
        String location = logout.header("Location");
        assertNotNull(location, "RP-initiated logout must redirect into the logout round-trip");
        // The redirect goes to the identity provider's end-session endpoint and its query carries the
        // session's ID token, so the answer that hands it out is neither cacheable nor a Referer source.
        // The location itself stays out of every message for the same reason.
        assertTrue(location.startsWith(END_SESSION_ENDPOINT + "?"),
                "RP-initiated logout with a live session must redirect to the realm's end-session endpoint");
        assertTrue(location.contains(ID_TOKEN_HINT + "="),
                "the end-session redirect must carry the id_token_hint of the session it ends");
        assertEquals("no-referrer", logout.header("Referrer-Policy"),
                "the redirect that carries the ID token must be answered with Referrer-Policy: no-referrer");
        assertEquals("no-store", logout.header("Cache-Control"),
                "the redirect that carries the ID token must not be cacheable");

        // Assert — the local session is destroyed: replaying the (now stale) cookie jar on the
        // require:session route is challenged 401, the session no longer resolves.
        BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .when()
                .get("/app/get")
                .then()
                .statusCode(401);

        // Assert — the refresh token is revoked at Keycloak: the user session the logged-out session
        // belonged to no longer names integration-client. The precondition keeps this from passing on a
        // sid that never named the client in the first place.
        String sid = sidOf(location);
        assertTrue(attachedBeforeLogout.contains(sid),
                () -> "precondition: before the logout, the Keycloak session named by the sid of the session's"
                        + " ID token must have " + CLIENT_ID + " attached (" + attachedBeforeLogout.size()
                        + " session(s) of the user had it); otherwise its absence afterwards proves nothing"
                        + " about the revocation");
        Set<String> attachedAfterLogout = sessionIdsNamingTheClient(adminToken, userId);
        assertFalse(attachedAfterLogout.contains(sid),
                () -> "the logout must revoke the session's refresh token at Keycloak, which detaches "
                        + CLIENT_ID + " from the user session; it is still attached. The end-session redirect"
                        + " was not followed, so only the revocation could have detached it: check that the"
                        + " gateway log carries no ApiSheriff-146 (a failed revocation) and that the realm's"
                        + " discovery document declares a revocation_endpoint");
    }

    @Test
    @DisplayName("the back-channel logout receiver is wired and fail-closed: an empty post is rejected 400 no-store")
    void backChannelReceiverIsWiredAndFailClosed() {
        var response = given()
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/bff/backchannel")
                .then()
                .statusCode(400)
                .extract();

        assertEquals("no-store", response.header("Cache-Control"),
                "a back-channel logout response must be uncacheable");
    }

    /**
     * Reads the {@code sid} claim of the ID token the end-session redirect carries as its
     * {@code id_token_hint}. The payload is decoded, not verified: the token came from the gateway the
     * suite is testing, and only the claim naming the Keycloak user session is needed.
     * <p>
     * No message names the location, the token or the claim value.
     *
     * @param location the end-session redirect's {@code Location}
     * @return the {@code sid} claim of the ID token
     */
    private static String sidOf(String location) {
        String rawQuery = URI.create(location).getRawQuery();
        assertNotNull(rawQuery, "the end-session redirect must carry a query");
        Optional<String> idToken = List.of(rawQuery.split("&")).stream()
                .filter(parameter -> parameter.startsWith(ID_TOKEN_HINT + "="))
                .map(parameter -> URLDecoder.decode(parameter.substring(ID_TOKEN_HINT.length() + 1),
                        StandardCharsets.UTF_8))
                .findFirst();
        assertTrue(idToken.isPresent(), "the end-session redirect must carry an id_token_hint parameter");
        String[] segments = idToken.get().split("\\.");
        assertEquals(3, segments.length, "the id_token_hint must be a compact-serialized JWT of three segments");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        Matcher sid = SID_CLAIM.matcher(payload);
        assertTrue(sid.find(), "the ID token must carry a sid claim naming the Keycloak user session");
        return sid.group(1);
    }

    /**
     * The ids of the Keycloak user sessions of {@code userId} that {@value #CLIENT_ID} is attached to:
     * the sessions whose {@code clients} map, client UUID to client id, names it.
     *
     * @param adminToken a master-realm admin token
     * @param userId     the Keycloak id of the user
     * @return the session ids, empty when there are none
     */
    private static Set<String> sessionIdsNamingTheClient(String adminToken, String userId) {
        List<Map<String, Object>> sessions = admin(adminToken)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users/" + userId + "/sessions")
                .then().statusCode(OK)
                .extract().jsonPath().getList("$");
        return sessions.stream()
                .filter(candidate -> candidate.get("clients") instanceof Map<?, ?> clients
                        && clients.containsValue(CLIENT_ID))
                .map(candidate -> Objects.toString(candidate.get("id"), ""))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Resolves the default login user of {@link BffKeycloakLoginFlow} in the {@code integration} realm.
     *
     * @param adminToken a master-realm admin token
     * @return the user's Keycloak id
     */
    private static String userIdOf(String adminToken) {
        String userId = admin(adminToken)
                .queryParam("username", BffKeycloakLoginFlow.USERNAME)
                .queryParam("exact", true)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users")
                .then().statusCode(OK)
                .extract().path("[0].id");
        assertNotNull(userId, "the admin API must resolve " + BffKeycloakLoginFlow.USERNAME
                + " in the integration realm");
        return userId;
    }

    private static String adminToken() {
        String token = keycloak()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", "admin")
                .formParam("password", "admin")
                .when().post(KEYCLOAK_ORIGIN + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(OK)
                .extract().path("access_token");
        assertNotNull(token, "the Keycloak master realm must issue an admin token");
        return token;
    }

    private static RequestSpecification admin(String adminToken) {
        return keycloak().auth().oauth2(adminToken);
    }

    private static RequestSpecification keycloak() {
        return given().config(BffKeycloakLoginFlow.clientDeadlines(BffKeycloakLoginFlow.CONNECT_TIMEOUT_SECONDS,
                BffKeycloakLoginFlow.RESPONSE_TIMEOUT_SECONDS)).relaxedHTTPSValidation();
    }
}
