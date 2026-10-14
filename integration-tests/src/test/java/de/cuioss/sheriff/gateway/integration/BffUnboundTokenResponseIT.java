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
import java.util.function.Predicate;

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
 * not bound to the gateway's DPoP proof key is refused (ADR-0058): the callback answers {@code 400},
 * no session cookie is set, and the gateway records {@code ApiSheriff-134} with the reason.
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
 *   <li>No {@code cnf} claim: the callback answers {@code 400}, sets no session cookie, and the
 *       gateway output gains exactly one {@code ApiSheriff-134} record, which names the
 *       {@code code-exchange} leg and the reason {@code cnf-absent}.</li>
 *   <li>{@code cnf.jkt} naming another committed key: the same, with the reason
 *       {@code cnf-mismatch}.</li>
 *   <li>Control: {@code cnf.jkt} naming the gateway's own proof key: the callback completes the login
 *       ({@code 302}, a session cookie set) and the gateway output gains no {@code ApiSheriff-134}
 *       record. The refusals above are therefore attributable to the binding alone, not to anything
 *       else the stub's answer or the rig's login does.</li>
 * </ol>
 * Records are counted before and after each login rather than matched anywhere in the output, so a
 * record an earlier login caused cannot satisfy a later assertion.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not prove anything about a real identity
 * provider: the stub validates neither the client credential, nor the authorization code, nor the
 * PKCE verifier, nor the DPoP proof, and grants whatever it was scripted to grant. It drives the
 * code-exchange leg only: the refusal of an unbound answer to a refresh grant, which ends the session,
 * is proven at unit level ({@code BoundTokenEndpointClientTest}, {@code BffRuntimeProducerTest}). It
 * does not drive the {@code token-type} or {@code unreadable-access-token} reasons. Like every
 * {@code Bff*IT} it replays a cookie map and asserts nothing about browser cookie policy.
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

    /**
     * WARN — a token response was refused because it is not bound to the gateway's proof key. The
     * trailing colon is part of the match, so the value is no prefix of another identifier.
     */
    private static final String TOKEN_NOT_BOUND_RECORD = "ApiSheriff-134:";

    /** The leg a refusal on the authorization-code exchange is recorded under. */
    private static final String CODE_EXCHANGE_LEG = "code-exchange";

    /** A token lifetime far above the descriptor's refresh leeway, so no refresh plays a part. */
    private static final Duration FAR_FROM_EXPIRY = Duration.ofMinutes(10);

    private static final long LOG_VISIBILITY_TIMEOUT_MILLIS = 5_000L;
    private static final long LOG_POLL_INTERVAL_MILLIS = 250L;

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
    @DisplayName("an access token without cnf: 400, no session cookie, one ApiSheriff-134 record naming cnf-absent")
    void accessTokenWithoutConfirmationIsRefused() {
        assertRefused(Binding.CNF_ABSENT, "cnf-absent");
    }

    @Test
    @DisplayName("an access token bound to another key: 400, no session cookie, one ApiSheriff-134 record naming cnf-mismatch")
    void accessTokenBoundToAnotherKeyIsRefused() {
        assertRefused(Binding.OTHER_KEY, "cnf-mismatch");
    }

    @Test
    @DisplayName("control: an access token bound to the gateway's proof key completes the login and records no refusal")
    void accessTokenBoundToTheProofKeyCompletesTheLogin() {
        StubIdentityProviderRig rig = sharedRig;
        long refusalsBefore = records(rig, line -> line.contains(TOKEN_NOT_BOUND_RECORD));

        Response callback = rig.loginCallback(START_PATH, FAR_FROM_EXPIRY, Binding.BOUND);

        assertEquals(302, callback.statusCode(), () -> "a bound token answer must complete the login. "
                + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
        assertTrue(setsASessionCookie(callback), "the completed login must set a session cookie");
        assertEquals(refusalsBefore, records(rig, line -> line.contains(TOKEN_NOT_BOUND_RECORD)),
                "a bound token answer must record no " + TOKEN_NOT_BOUND_RECORD
                        + " refusal, so the refusals of the other logins follow from the binding alone");
    }

    /**
     * One login whose code exchange is answered with an access token bound as {@code binding} says,
     * which the gateway must refuse for {@code reason}.
     *
     * @param binding the binding of the code-exchange answer's access token
     * @param reason  the reason token the refusal record must carry
     */
    private static void assertRefused(Binding binding, String reason) {
        StubIdentityProviderRig rig = sharedRig;
        Predicate<String> anyRefusal = line -> line.contains(TOKEN_NOT_BOUND_RECORD);
        Predicate<String> thisRefusal = anyRefusal
                .and(line -> line.contains(CODE_EXCHANGE_LEG))
                .and(line -> line.contains(reason));
        long refusalsBefore = records(rig, anyRefusal);
        long matchingBefore = records(rig, thisRefusal);

        Response callback = rig.loginCallback(START_PATH, FAR_FROM_EXPIRY, binding);

        assertEquals(400, callback.statusCode(), () -> "a code-exchange answer bound as " + binding
                + " must be refused at the callback. "
                + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
        assertFalse(setsASessionCookie(callback), "a refused login must set no session cookie");
        awaitRecordCountAbove(rig, anyRefusal, refusalsBefore);
        assertEquals(refusalsBefore + 1, records(rig, anyRefusal),
                "the refused login must have recorded exactly one " + TOKEN_NOT_BOUND_RECORD + " record");
        assertEquals(matchingBefore + 1, records(rig, thisRefusal), "the " + TOKEN_NOT_BOUND_RECORD
                + " record must name the " + CODE_EXCHANGE_LEG + " leg and the reason " + reason);
    }

    /**
     * Whether a response sets the session cookie to a value. Reduced to a boolean, so the cookie value
     * reaches no failure message.
     */
    private static boolean setsASessionCookie(Response response) {
        Cookie sessionCookie = response.getDetailedCookies().get(SESSION_COOKIE);
        return sessionCookie != null && sessionCookie.getValue() != null && !sessionCookie.getValue().isEmpty();
    }

    /** The number of lines of the rig gateway's output so far that {@code record} accepts. */
    private static long records(StubIdentityProviderRig rig, Predicate<String> record) {
        return rig.gatewayOutput().lines().filter(record).count();
    }

    /**
     * Waits until the rig gateway's output holds more lines {@code record} accepts than
     * {@code countBefore}. The record is written before the response is sent, but the container log can
     * surface it a moment later, so the read is retried briefly; the caller asserts the count.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a container log line
    private static void awaitRecordCountAbove(StubIdentityProviderRig rig, Predicate<String> record,
            long countBefore) {
        long deadline = System.currentTimeMillis() + LOG_VISIBILITY_TIMEOUT_MILLIS;
        while (records(rig, record) <= countBefore && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the gateway log record", interrupted);
            }
        }
    }
}
