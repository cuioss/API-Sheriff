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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves IdP-initiated back-channel logout end to end: Keycloak terminates the SSO session, delivers
 * a logout token to the gateway's back-channel endpoint unprompted, and the <em>gateway-held server
 * session</em> is gone afterwards.
 * <p>
 * <strong>What this suite asserts, and why it is not the weaker thing.</strong> {@code BffLogoutIT}
 * already proves the receiver is wired and fail-closed by posting an empty body and reading the
 * {@code 400}. That is a reachability check: a {@code 200} from a receiver that found no session
 * looks exactly like a {@code 200} from one that destroyed a session, so an endpoint-shaped
 * assertion would pass against a gateway that honoured nothing. This suite therefore never inspects
 * the back-channel response at all — it is delivered container-to-container and the test never sees
 * it. It asserts the <em>effect</em>: an authenticated call that succeeded before the logout is
 * challenged {@code 401} after it.
 * <p>
 * <strong>Three pieces of realm configuration make the delivery happen</strong>, all declared in
 * {@code integration-realm.json} rather than assumed from a Keycloak built-in:
 * <ul>
 *   <li>{@code backchannel.logout.url} on {@code integration-client}, pointing at the gateway's
 *       container-internal {@code /auth/backchannel} — without it Keycloak notifies nobody;</li>
 *   <li>{@code backchannel.logout.session.required}, which puts {@code sid} on the logout token, the
 *       claim {@code BackchannelLogoutReceiver} routes to {@code SessionBinding.destroyBySid};</li>
 *   <li>a dedicated {@link BffKeycloakLoginFlow#BACKCHANNEL_USERNAME} identity, because the admin-API
 *       logout below is realm-wide for the user it names and would otherwise destroy the sessions
 *       every other {@code Bff*IT} suite holds.</li>
 * </ul>
 * A fourth piece lives in {@code docker-compose.yml}: Keycloak dials the gateway over TLS on this
 * leg, so {@code KC_TRUSTSTORE_PATHS} points it at the shared self-signed certificate. Each of the
 * four is load-bearing, and removing any one of them is the negative control for this suite: the
 * suite must go red, and a green run after such a removal means it has stopped testing anything.
 * <p>
 * <strong>A fifth piece is not configuration at all</strong>, and it is why this suite spent its
 * first CI run red with all four of the above correctly in place. The gateway has to actually SERVE
 * {@code /auth/backchannel} on the host Keycloak dials it by. Keycloak reaches it as
 * {@code api-sheriff} — its compose service name on the shared network — while the OIDC host is
 * {@code localhost}, the browser-facing host of {@code redirect_uri}. {@code ReservedPathRegistry}
 * gated <em>every</em> reserved path on the OIDC host, so the delivered logout token was answered
 * {@code 404} by the proxy route table and validated by nobody: the session survived and this
 * assertion read {@code 200}. The registry now exempts the back-channel receiver from that gate
 * (ADR-0018, back-channel-host amendment), and {@code ReservedPathRegistryTest} pins the exemption
 * with both arms — the receiver matches on a foreign host, the five browser-facing paths still do
 * not — so a regression fails in seconds instead of waiting for a container.
 * <p>
 * <strong>Why the {@code 401} cannot come from somewhere else.</strong> The gateway also destroys a
 * session when a near-expiry token refresh fails, and an admin-API logout does revoke the refresh
 * token — so that path would produce the same {@code 401} and prove nothing about the back channel.
 * It cannot fire inside this test's window: {@code integration-client} mints tokens with the realm's
 * 900-second {@code accessTokenLifespan} against a 30-second refresh leeway, so the earliest
 * refresh-driven destruction is roughly fourteen minutes away, while {@link #DELIVERY_BUDGET_SECONDS}
 * bounds the observation to a few seconds. The budget is therefore load-bearing rather than
 * defensive: it is what separates "the back channel destroyed the session" from "something else
 * eventually would have".
 */
class BffBackchannelLogoutIT extends BaseIntegrationTest {

    /** Host-published Keycloak origin (compose maps {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /** A {@code require: session} route on the primary server-mode gateway. */
    private static final String SESSION_ROUTE = "/bff-session/get";

    /**
     * How long the gateway is given to receive and apply the logout token. Back-channel delivery is
     * asynchronous — Keycloak POSTs it after answering the admin call — so the effect is polled for
     * rather than asserted on the next request.
     * <p>
     * Kept far below the ~14-minute earliest refresh-driven destruction described in the class
     * comment, so a pass here attributes the destruction to the back channel and nothing else.
     */
    private static final int DELIVERY_BUDGET_SECONDS = 15;

    /** Poll interval while waiting for the destruction to land. */
    private static final long POLL_INTERVAL_MILLIS = 250;

    private static final int OK = 200;
    private static final int UNAUTHORIZED = 401;

    @Test
    @DisplayName("an IdP-initiated back-channel logout destroys the gateway-held server session")
    void idpInitiatedBackchannelLogoutDestroysTheGatewayHeldSession() {
        // Arrange — a real login through Keycloak, so the gateway holds a server-side session bound to
        // the IdP session this test is about to terminate.
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, BffKeycloakLoginFlow.GATEWAY_ORIGIN,
                BffKeycloakLoginFlow.BACKCHANNEL_USERNAME, BffKeycloakLoginFlow.BACKCHANNEL_PASSWORD);
        assertEquals(OK, sessionProbe(session),
                "precondition: the freshly established session must authorize the require:session route,"
                        + " otherwise the 401 asserted below would prove nothing about the back channel");

        // Act — terminate the SSO session AT KEYCLOAK. The gateway is not called; Keycloak delivers the
        // logout token to the backchannel.logout.url the realm declares, container-to-container.
        logoutAtIdp(BffKeycloakLoginFlow.BACKCHANNEL_USERNAME);

        // Assert — the gateway-held session is gone. The browser did nothing but retry.
        assertEquals(UNAUTHORIZED, awaitSessionChallenged(session),
                () -> "the gateway-held session survived an IdP-initiated back-channel logout for "
                        + DELIVERY_BUDGET_SECONDS + "s. Three causes produce this identically, and the"
                        + " gateway log now separates them WITHOUT a DEBUG re-run: grep"
                        + " target/quarkus-logs/quarkus.log for ApiSheriff-13 and ApiSheriff-112. No line"
                        + " from either means nothing reached the receiver; ApiSheriff-112 names the check"
                        + " that refused; ApiSheriff-13 with '0 session(s) destroyed' means the token was"
                        + " accepted and its sid matched nothing. Either no logout token was delivered (check"
                        + " that integration-client declares backchannel.logout.url, and that Keycloak"
                        + " trusts the gateway certificate via KC_TRUSTSTORE_PATHS — a TLS failure on that"
                        + " leg is silent from here); or one was delivered to a path the gateway did not"
                        + " serve on the dialled host and was answered 404 by the proxy route table (the"
                        + " back-channel receiver must stay exempt from the reserved-path OIDC-host gate —"
                        + " see the class comment); or one was delivered, accepted, and matched nothing"
                        + " (the token's sid is keyed against the sid the gateway recorded from the ID"
                        + " token at login)");
    }

    /**
     * Replays the session cookie jar on the protected route and reports the status.
     *
     * @param session the established gateway session
     * @return the HTTP status the require:session route answers
     */
    private static int sessionProbe(Session session) {
        return BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .when()
                .get(SESSION_ROUTE)
                .then()
                .extract()
                .statusCode();
    }

    /**
     * Polls the protected route until it is challenged, or the delivery budget expires.
     * <p>
     * Returns the last observed status rather than asserting itself, so the caller owns the assertion
     * and its diagnostic — a helper that asserted would report "expected 401" without the context that
     * makes the failure actionable.
     *
     * @param session the established gateway session
     * @return {@code 401} once the session is gone, otherwise the last status observed within the budget
     */
    private static int awaitSessionChallenged(Session session) {
        long deadline = System.nanoTime() + DELIVERY_BUDGET_SECONDS * 1_000_000_000L;
        int status = sessionProbe(session);
        while (status != UNAUTHORIZED && System.nanoTime() < deadline) {
            sleep();
            status = sessionProbe(session);
        }
        return status;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting back-channel logout", interrupted);
        }
    }

    /**
     * Terminates every Keycloak session of {@code username} in the {@code integration} realm through
     * the admin API — the IdP-initiated logout this suite is about. Keycloak answers {@code 204} and
     * then notifies each client that declares a {@code backchannel.logout.url}.
     *
     * @param username the realm user whose sessions are terminated
     */
    private static void logoutAtIdp(String username) {
        String adminToken = given().relaxedHTTPSValidation()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "admin-cli")
                .formParam("username", "admin")
                .formParam("password", "admin")
                .when().post(KEYCLOAK_ORIGIN + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(OK)
                .extract().path("access_token");

        String userId = given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .queryParam("username", username)
                .queryParam("exact", true)
                .when().get(KEYCLOAK_ORIGIN + "/admin/realms/integration/users")
                .then().statusCode(OK)
                .extract().path("[0].id");
        assertNotNull(userId, "the admin API must resolve " + username + " in the integration realm");

        given().relaxedHTTPSValidation()
                .auth().oauth2(adminToken)
                .when().post(KEYCLOAK_ORIGIN + "/admin/realms/integration/users/" + userId + "/logout")
                .then().statusCode(204);
    }
}
