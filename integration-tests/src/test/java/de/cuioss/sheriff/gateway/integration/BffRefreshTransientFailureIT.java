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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Answer;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Endpoint;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, against a running gateway, what a refresh does when the identity provider fails before it
 * processed the grant (ADR-0046, {@code PRE_REDEMPTION}): the session is kept, the request is still
 * served from the token it has, and the next attempt waits out a fixed five-second back-off.
 * <p>
 * <strong>Why the stub.</strong> The failure has to come from the token endpoint and nowhere else,
 * for one request, and the suite has to count the grants that reached it. A stub identity provider
 * ({@link StubIdentityProviderRig}) answers one scripted failure and records every request; Keycloak
 * can be stopped, but not made to fail one grant and serve the next.
 * <p>
 * <strong>What this suite proves.</strong> Two legs, one per failure shape — the token endpoint
 * answers {@code 503}, and the token endpoint resets the connection — each on a session of its own.
 * In each leg the access token is inside the refresh leeway and not expired throughout:
 * <ol>
 *   <li>Before the first mediated call the journal holds no refresh grant.</li>
 *   <li>The first call attempts the refresh, which fails. The call is answered {@code 200} with a
 *       relayed bearer and sets no cookie; the journal holds one grant; the gateway log gains one
 *       {@code ApiSheriff-127} record.</li>
 *   <li>Two further calls inside the back-off are answered {@code 200} with the same bearer and set
 *       no cookie; the journal still holds one grant.</li>
 *   <li>After the back-off, with the stub answering again, one call records exactly one further
 *       grant and relays the token that grant returned — a different bearer. A call after that
 *       records nothing.</li>
 * </ol>
 * No response of either leg carries a {@code Set-Cookie}, so the session cookie is never cleared.
 * The log has gained exactly one {@code ApiSheriff-127} record by the end of the leg: the calls
 * inside the back-off made no attempt and recorded nothing.
 * <p>
 * <strong>One rig for both legs.</strong> The stub and the gateway are started once for the class and
 * {@linkplain StubIdentityProviderRig#reset() reset} before each leg, so each leg finds an empty
 * script and counts the grants from zero. The gateway is not restarted: the leg that runs second runs
 * against a gateway, and against connections to the stub, that the first leg has already used. The
 * session, the injected failure and every count a leg asserts are its own. What a run does not show
 * is that a leg's outcome is independent of the other leg having run first: JUnit runs the two in one
 * fixed order, so each run observes that order and never the reverse.
 * <p>
 * <strong>What "the same bearer" establishes.</strong> The token of the login answer is not visible to
 * this suite, so the first call's bearer is not compared with it. The failed grant returned no token
 * at all, so a call served {@code 200} with a bearer can only have relayed the one the session held;
 * the suite asserts that this bearer stays identical inside the back-off and is replaced only by the
 * token of the successful retry.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not exercise a failed refresh on a token
 * that has already expired (the request is then refused while the session is kept), a failure that
 * outlasts several back-off windows, the shared overflow window of a saturated back-off map, cookie
 * mode, or any other {@code 5xx}. Like every {@code Bff*IT} it replays a cookie map and asserts
 * nothing about browser cookie policy.
 * <p>
 * <strong>Timing.</strong> The wait is wall-clock and deliberate: the back-off is a fixed duration,
 * so there is no state to poll for.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffRefreshTransientFailureIT {

    /** The plain session route, relaying a bearer to the echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    /** WARN — a refresh failed before the identity provider processed it; the session is kept. */
    private static final String REFRESH_DEFERRED_RECORD = "ApiSheriff-127";

    /** The gateway's fixed pre-redemption back-off. Stated here: it is the contract under test. */
    private static final Duration BACK_OFF = Duration.ofSeconds(5);

    /** How far past the end of the back-off the retrying call is made. */
    private static final Duration PAST_THE_BACK_OFF = Duration.ofSeconds(1);

    /**
     * The lifetime of the login's access token: below the descriptor's
     * {@code session.refresh.leeway_seconds} of 30, so every call of a leg is inside the near-expiry
     * window, and above the length of a leg, so the token has not expired when the retry is made.
     */
    private static final Duration INSIDE_THE_LEEWAY = Duration.ofSeconds(25);

    /** A token lifetime far above the leeway, so no refresh is due after the retry. */
    private static final Duration FAR_FROM_EXPIRY = Duration.ofMinutes(10);

    private static final List<String> SCOPES = List.of("openid", "profile", "email");

    private static final long LOG_VISIBILITY_TIMEOUT_MILLIS = 5_000L;
    private static final long LOG_POLL_INTERVAL_MILLIS = 250L;

    /** The one rig both legs run on; started before the first, closed after the last. */
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

    /** Each leg starts on an empty script and an empty journal, whichever leg ran before it. */
    @BeforeEach
    void resetRig() {
        sharedRig.reset();
    }

    @Test
    @DisplayName("503 from the token endpoint: the session is kept, the back-off holds, the retry rotates the bearer")
    void serviceUnavailableKeepsTheSessionAndRetriesAfterTheBackOff() {
        assertSessionKeptAndRefreshRetried(Answer.of(503), "answered 503");
    }

    @Test
    @DisplayName("connection reset by the token endpoint: the session is kept, the back-off holds, the retry rotates the bearer")
    void connectionResetKeepsTheSessionAndRetriesAfterTheBackOff() {
        assertSessionKeptAndRefreshRetried(Answer.reset(), "reset the connection");
    }

    /**
     * One leg: a session near expiry whose first refresh attempt is answered with {@code failure}.
     *
     * @param failure the token endpoint's answer to the first refresh grant
     * @param shape   what the token endpoint did, for the failure messages
     */
    private static void assertSessionKeptAndRefreshRetried(Answer failure, String shape) {
        StubIdentityProviderRig rig = sharedRig;
        Instant loginTokenMinted = Instant.now();
        Session session = rig.login(MEDIATED_PATH, INSIDE_THE_LEEWAY);
        Instant loginTokenExpires = loginTokenMinted.plus(INSIDE_THE_LEEWAY);
        assertEquals(0, rig.refreshGrants().size(), "before the first call the journal must hold no refresh grant");
        long recordsBefore = deferredRecords(rig);

        rig.script(Endpoint.TOKEN, failure);
        Instant attemptSent = Instant.now();
        String keptBearer = servedBearer(rig, session, "the call whose refresh the token endpoint " + shape);
        Instant backOffOver = Instant.now().plus(BACK_OFF);
        assertEquals(1, rig.refreshGrants().size(),
                "the first call is near expiry and must have sent exactly one refresh grant");

        String insideFirst = servedBearer(rig, session, "the first call inside the back-off");
        String insideSecond = servedBearer(rig, session, "the second call inside the back-off");
        assertTrue(Instant.now().isBefore(attemptSent.plus(BACK_OFF)), "both follow-up calls must have "
                + "completed inside the five-second back-off, otherwise they do not test it");
        assertEquals(1, rig.refreshGrants().size(), "a call inside the back-off must send no refresh grant");
        assertEquals(List.of(keptBearer, keptBearer), List.of(insideFirst, insideSecond),
                "inside the back-off the session must go on relaying the bearer it held");
        awaitDeferredRecord(rig, recordsBefore, shape);

        sleepUntil(backOffOver.plus(PAST_THE_BACK_OFF));
        assertTrue(Instant.now().isBefore(loginTokenExpires), "the retry must be made while the login's "
                + "access token has not expired, otherwise this leg tests an expired token instead");
        Answer healthy = rig.tokenAnswer(SCOPES, FAR_FROM_EXPIRY);
        rig.script(Endpoint.TOKEN, healthy);
        String rotatedBearer = servedBearer(rig, session, "the first call after the back-off");
        assertEquals(2, rig.refreshGrants().size(),
                "after the back-off the next call must send exactly one further refresh grant");
        assertEquals(new JsonPath(healthy.body()).getString("access_token"), rotatedBearer,
                "the retry must relay the access token the healthy token endpoint returned");
        assertNotEquals(keptBearer, rotatedBearer, "the retry must rotate the bearer");

        assertEquals(rotatedBearer, servedBearer(rig, session, "the call after the successful retry"),
                "control: once refreshed the session must relay the rotated bearer unchanged");
        assertEquals(2, rig.refreshGrants().size(), "control: a call after the successful retry must "
                + "send no refresh grant");
        assertEquals(recordsBefore + 1, deferredRecords(rig), "the leg must have recorded exactly one "
                + REFRESH_DEFERRED_RECORD + ": the calls inside the back-off made no attempt");
    }

    /**
     * Makes one mediated call and asserts the session was served and left alone: {@code 200}, a
     * relayed bearer, and no {@code Set-Cookie} — a cleared or re-bound session cookie would show there.
     *
     * @return the relayed access token, without its scheme
     */
    private static String servedBearer(StubIdentityProviderRig rig, Session session, String which) {
        Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), StubIdentityProviderRig.ORIGIN)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
        assertEquals(200, response.statusCode(), () -> which + " must still be served: the session is kept. "
                + rig.gatewayOutput());
        List<String> setCookies = response.getHeaders().getValues("Set-Cookie");
        assertTrue(setCookies.isEmpty(), () -> which + " must neither clear nor re-bind the session cookie; "
                + "it carried " + setCookies.size() + " Set-Cookie header(s)");
        Object authorization = response.path("headers.Authorization");
        String token = authorization == null ? ""
                : authorization.toString().replaceFirst("(?i)^\\[?Bearer\\s+", "").replaceAll("]$", "");
        assertFalse(token.isEmpty(), () -> which + " must relay a bearer to the upstream");
        return token;
    }

    /** The number of {@value #REFRESH_DEFERRED_RECORD} records in the rig gateway's output so far. */
    private static long deferredRecords(StubIdentityProviderRig rig) {
        return rig.gatewayOutput().lines().filter(line -> line.contains(REFRESH_DEFERRED_RECORD)).count();
    }

    /**
     * Asserts the failed attempt recorded {@value #REFRESH_DEFERRED_RECORD}. The record is written
     * before the response is sent, but the container log can surface it a moment later, so the read is
     * retried briefly.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a container log line
    private static void awaitDeferredRecord(StubIdentityProviderRig rig, long recordsBefore, String shape) {
        long deadline = System.currentTimeMillis() + LOG_VISIBILITY_TIMEOUT_MILLIS;
        long observed = deferredRecords(rig);
        while (observed <= recordsBefore && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the gateway log record", interrupted);
            }
            observed = deferredRecords(rig);
        }
        long finalObserved = observed;
        assertTrue(finalObserved > recordsBefore, () -> "a token endpoint that " + shape + " must be disposed "
                + "as a pre-redemption failure: expected a new WARN " + REFRESH_DEFERRED_RECORD + " in the "
                + "gateway log (count before " + recordsBefore + ", after " + finalObserved + ")");
    }

    /**
     * Bounded wall-clock wait. The back-off is a fixed duration, so there is no condition to poll for.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the fixed back-off IS the clock under test
    private static void sleepUntil(Instant instant) {
        long millis = Duration.between(Instant.now(), instant).toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out the refresh back-off", interrupted);
        }
    }
}
