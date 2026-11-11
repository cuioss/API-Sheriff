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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Binding;

import io.restassured.http.Cookie;
import io.restassured.response.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, against a running gateway, that a login whose code exchange is answered with an access token
 * not bound to the gateway's DPoP proof key is refused (ADR-0058): the callback answers {@code 400} and
 * no session cookie is set. The refusal is the token engine's own, raised at its token-endpoint seam as a
 * {@code RedeemedResponseException}; it leaves no gateway log record of its own, so the suite asserts the
 * observable outcome of the callback and nothing about the log.
 * <p>
 * <strong>Why the stub.</strong> Keycloak binds every access token of the stack's clients to the
 * proof the gateway presents, so it cannot be made to answer an unbound token response.
 * {@code BffFapiControlsIT} therefore proves only the accepting side. A stub identity provider
 * ({@link StubIdentityProviderRig}) answers the code exchange with whatever binding a test scripts.
 * <p>
 * <strong>What this suite proves.</strong> Three logins on one gateway, each started on the session
 * route, each pushing one authorization request and each answered at the code exchange with a token
 * answer that differs from the others in the {@code cnf} claim of its access token alone:
 * <ol>
 *   <li>No {@code cnf} claim: the callback answers {@code 400} and sets no session cookie.</li>
 *   <li>{@code cnf.jkt} naming another committed key: the same.</li>
 *   <li>Control: {@code cnf.jkt} naming the gateway's own proof key: the callback completes the login
 *       ({@code 302}, a session cookie set). The refusals above are therefore attributable to the
 *       binding alone, not to anything else the stub's answer or the rig's login does.</li>
 * </ol>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not prove anything about a real identity
 * provider: the stub validates neither the client credential, nor the authorization code, nor the
 * PKCE verifier, nor the DPoP proof, and grants whatever it was scripted to grant. It drives the
 * code-exchange leg only: the refusal of an unbound answer to a refresh grant, which ends the session,
 * is proven at unit level ({@code BffRuntimeProducerTest}). It does not drive a {@code Bearer}
 * {@code token_type}. Like every {@code Bff*IT} it replays a cookie map and asserts nothing about
 * browser cookie policy.
 * <p>
 * <strong>One rig for the class.</strong> The stub and the gateway are started once and
 * {@linkplain StubIdentityProviderRig#reset() reset} before each login, so each finds an empty script.
 * The gateway is not restarted between them. The class carries no {@code sleep-bound} tag:
 * {@code FailsafeConcurrencyContractTest} keeps a class that uses the rig, whose container names,
 * network and port are fixed, out of the concurrent execution.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("a token response not bound to the gateway's proof key is refused at the code exchange")
class BffUnboundTokenResponseIT {

    /** The require:session route each login starts on. */
    private static final String START_PATH = "/app/get";

    /**
     * The session-cookie name of the rig's gateway, the gateway's default. Spelled out here rather than
     * imported: this black-box suite takes no constant from the gateway's own classes.
     */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** A token lifetime far above the descriptor's refresh leeway, so no refresh plays a part. */
    private static final Duration FAR_FROM_EXPIRY = Duration.ofMinutes(10);

    /** The one rig every login runs on; started before the first, closed after the last. */
    private static StubIdentityProviderRig sharedRig;

    @BeforeAll
    static void startRig() {
        sharedRig = StubIdentityProviderRig.start();
    }

    @AfterAll
    static void closeRig() {
        if (sharedRig != null) {
            sharedRig.close();
        }
    }

    /** Each login starts on an empty script and an empty journal, whichever login ran before it. */
    @BeforeEach
    void resetRig() {
        sharedRig.reset();
    }

    @Test
    @DisplayName("an access token without cnf: 400 and no session cookie")
    void accessTokenWithoutConfirmationIsRefused() {
        assertRefused(Binding.CNF_ABSENT);
    }

    @Test
    @DisplayName("an access token bound to another key: 400 and no session cookie")
    void accessTokenBoundToAnotherKeyIsRefused() {
        assertRefused(Binding.OTHER_KEY);
    }

    @Test
    @DisplayName("control: an access token bound to the gateway's proof key completes the login")
    void accessTokenBoundToTheProofKeyCompletesTheLogin() {
        Response callback = sharedRig.loginCallback(START_PATH, FAR_FROM_EXPIRY, Binding.BOUND);

        assertEquals(302, callback.statusCode(), () -> "a bound token answer must complete the login. "
                + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
        assertTrue(setsASessionCookie(callback), "the completed login must set a session cookie, so the "
                + "refusals of the other logins follow from the binding alone");
    }

    /**
     * One login whose code exchange is answered with an access token bound as {@code binding} says,
     * which the gateway must refuse.
     *
     * @param binding the binding of the code-exchange answer's access token
     */
    private static void assertRefused(Binding binding) {
        Response callback = sharedRig.loginCallback(START_PATH, FAR_FROM_EXPIRY, binding);

        assertEquals(400, callback.statusCode(), () -> "a code-exchange answer bound as " + binding
                + " must be refused at the callback. "
                + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
        assertFalse(setsASessionCookie(callback), "a refused login must set no session cookie");
    }

    /**
     * Whether a response sets the session cookie to a value. Reduced to a boolean, so the cookie value
     * reaches no failure message.
     */
    private static boolean setsASessionCookie(Response response) {
        Cookie sessionCookie = response.getDetailedCookies().get(SESSION_COOKIE);
        return sessionCookie != null && sessionCookie.getValue() != null && !sessionCookie.getValue().isEmpty();
    }
}
