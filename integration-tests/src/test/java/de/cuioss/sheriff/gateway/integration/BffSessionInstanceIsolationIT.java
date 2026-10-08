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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, on the running stack, that a server-mode session belongs to the gateway instance that created
 * it: the session cookie of one instance is not a session on another instance that keeps its sessions
 * in a memory store of its own.
 * <p>
 * <strong>The two instances.</strong> The primary instance ({@link BffKeycloakLoginFlow#GATEWAY_ORIGIN})
 * and {@code api-sheriff-refresh} ({@link BffKeycloakLoginFlow#REFRESH_GATEWAY_ORIGIN}). Both run
 * {@code session.mode: server} with {@code store: memory}, both serve the session route
 * {@value #MEDIATED_PATH}, and they share no store.
 * <p>
 * <strong>What this suite proves.</strong> A session is established on the primary instance, and its
 * session cookie — that one cookie, nothing else of the jar — is presented to the other instance on
 * the session route:
 * <ul>
 *   <li>a non-navigation request is answered {@code 401}, and the answer is not the echo of a mediated
 *       request: it carries no relayed bearer;</li>
 *   <li>a navigation is redirected to the identity provider's authorization endpoint, as a navigation
 *       without a session is.</li>
 * </ul>
 * <strong>Why the refusal cannot pass for the wrong reason.</strong> Two preconditions are asserted
 * first. A login on the other instance sets a session cookie of the <em>same name</em>, so the
 * presented cookie is the one that instance reads, not a cookie it ignores for its name. And the other
 * instance serves the route to a session of its own, relaying a bearer, so the refusal is not a route
 * that instance does not serve. The two cookies of that name carry different values, so the two
 * sessions are told apart.
 * <p>
 * <strong>Closing control.</strong> After the two refused requests the original cookie is still served
 * on the primary instance. Presenting it elsewhere ended nothing there.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It says nothing about a shared session store — the
 * gateway has none in this stack — nor about cookie mode, where the sealed cookie is the session and
 * is portable between instances that share the sealing key ({@code BffCookieStatelessnessIT}). It does
 * not assert what the refusing instance does to the cookie. Like every {@code Bff*IT} it replays a
 * cookie map and asserts nothing about browser cookie policy; a browser sends a {@code localhost}
 * cookie to every port, which is the situation this suite reproduces by hand.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: its requests name their origin themselves.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffSessionInstanceIsolationIT {

    /** The session route both instances serve, relaying a bearer to the echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    /** The instance the session under test is created on. */
    private static final String HOME_ORIGIN = BffKeycloakLoginFlow.GATEWAY_ORIGIN;

    /** The instance the session cookie is presented to. */
    private static final String OTHER_ORIGIN = BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN;

    /**
     * The session cookie neither descriptor renames. Spelled out, not read from the product: the name
     * is part of the wire contract this suite observes.
     */
    private static final String SESSION_COOKIE_NAME = "__Host-sheriff-session";

    private static final String JSON = "application/json";
    private static final String HTML = "text/html";

    @Test
    @DisplayName("the session cookie of one memory-store instance is refused on the other, and still served at home")
    void aSessionCookieIsNotASessionOnAnotherInstance() {
        Session home = BffKeycloakLoginFlow.login(MEDIATED_PATH, HOME_ORIGIN);
        Session other = BffKeycloakLoginFlow.login(MEDIATED_PATH, OTHER_ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
        String homeCookie = home.gatewayCookies().get(SESSION_COOKIE_NAME);
        String otherCookie = other.gatewayCookies().get(SESSION_COOKIE_NAME);
        Response ownSessionOnOther = call(OTHER_ORIGIN, other.gatewayCookies(), JSON);
        assertAll("preconditions: the other instance reads a cookie of the same name and serves the route",
                () -> assertNotNull(homeCookie, () -> "the login on " + HOME_ORIGIN + " must set "
                        + SESSION_COOKIE_NAME + "; it set " + home.gatewayCookies().keySet()),
                () -> assertNotNull(otherCookie, () -> "the login on " + OTHER_ORIGIN + " must set a cookie of "
                        + "the same name, " + SESSION_COOKIE_NAME + "; it set " + other.gatewayCookies().keySet()),
                () -> assertNotEquals(homeCookie, otherCookie, "the two instances must have issued different sessions"),
                () -> assertEquals(200, ownSessionOnOther.statusCode(),
                        () -> OTHER_ORIGIN + " must serve " + MEDIATED_PATH + " to a session of its own"),
                () -> assertTrue(relaysABearer(ownSessionOnOther),
                        () -> OTHER_ORIGIN + " must relay a bearer for a session of its own"));
        Map<String, String> foreignCookie = Map.of(SESSION_COOKIE_NAME, homeCookie);

        Response refusedCall = call(OTHER_ORIGIN, foreignCookie, JSON);
        Response refusedNavigation = call(OTHER_ORIGIN, foreignCookie, HTML);
        Response stillServedAtHome = call(HOME_ORIGIN, home.gatewayCookies(), JSON);

        assertAll("the cookie of " + HOME_ORIGIN + " presented to " + OTHER_ORIGIN,
                () -> assertEquals(401, refusedCall.statusCode(),
                        "a non-navigation request carrying another instance's session cookie must be refused 401"),
                () -> assertFalse(relaysABearer(refusedCall),
                        "the refused request must not have been mediated: its answer must carry no relayed bearer"),
                () -> assertEquals(302, refusedNavigation.statusCode(),
                        "a navigation carrying another instance's session cookie must be redirected into a login"),
                () -> assertTrue(String.valueOf(refusedNavigation.getHeader("Location"))
                                .contains("/protocol/openid-connect/auth"),
                        () -> "the navigation must be redirected to the identity provider's authorization "
                                + "endpoint, was sent to " + refusedNavigation.getHeader("Location")),
                () -> assertEquals(200, stillServedAtHome.statusCode(), () -> "control: the cookie must still be "
                        + "served on " + HOME_ORIGIN + "; presenting it elsewhere must have ended nothing there"),
                () -> assertTrue(relaysABearer(stillServedAtHome),
                        "control: the home instance must still relay a bearer for its session"));
    }

    private static Response call(String origin, Map<String, String> cookies, String accept) {
        return BffKeycloakLoginFlow.gateway(cookies, origin)
                .header("Accept", accept)
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
    }

    /**
     * Whether an answer is the echo of a request the gateway mediated: a JSON body in which the echo
     * upstream reports the {@code Authorization} header it received as a bearer.
     */
    private static boolean relaysABearer(Response response) {
        String contentType = response.contentType();
        if (contentType == null || !contentType.contains("json")) {
            return false;
        }
        Object authorization = response.path("headers.Authorization");
        return authorization != null && authorization.toString().contains("Bearer");
    }
}
