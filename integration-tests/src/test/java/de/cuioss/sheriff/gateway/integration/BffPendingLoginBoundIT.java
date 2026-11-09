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

import static de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.CONNECT_TIMEOUT_SECONDS;
import static de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.GATEWAY_ORIGIN;
import static de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.PASSWORD;
import static de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.RESPONSE_TIMEOUT_SECONDS;
import static de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.USERNAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.CompletedLogin;
import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.OpenedLogin;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the per-source cap of the gateway's store of started, not yet completed logins against the
 * running primary instance and the real identity provider.
 * <p>
 * <strong>The cap.</strong> Every login a browser starts leaves a pending record in the gateway until
 * its callback consumes it. Unauthenticated callers create these records, so the store is bounded per
 * requester source: one source holds at most {@value #PER_SOURCE_CAP} live records and, beyond that,
 * every new login of that source drops the source's own oldest record. A flood therefore costs only
 * the flooding source its own logins and never evicts another source's live login.
 * {@code PendingAuthorizationStoreTest} proves the cross-source fairness, the expired-first purge and
 * the refusal of a store full of live records on the store itself; this suite proves the per-source
 * cap from both sides through the gateway's own login path, with every login sent from the test
 * client's one source:
 * <ul>
 * <li>{@link #firstOfCapSameSourceLoginsIsKept()} — one login is opened up to the identity provider's
 * login form, then {@value #FLOOD_KEEPING_THE_FIRST} further same-source logins are started and left
 * unfinished. The source now holds {@value #PER_SOURCE_CAP} logins started in this phase, the first
 * among them. The first login is completed and must yield a session.</li>
 * <li>{@link #firstLoginIsDroppedOnceCapSameSourceLoginsFollowIt()} — one login is opened, then
 * {@value #FLOOD_DROPPING_THE_FIRST} further same-source logins are started, the newest of them opened
 * up to the login form. The first login is now its source's 257th-newest record and has been dropped.
 * It is completed at the identity provider, and the gateway's callback must refuse it: no session
 * cookie, and the session route answers {@code 401}. The newest login is completed as well and must
 * yield a session, so the refusal is the first login's alone and not a gateway that refuses every
 * login under load.</li>
 * </ul>
 * Both phases end with the gateway still answering its readiness probe.
 * <p>
 * <strong>What the second phase catches.</strong> A store bounded only globally — which drops the
 * oldest record of all sources once its global capacity is reached — still holds the first login
 * after {@value #FLOOD_DROPPING_THE_FIRST} further logins, because the phase stores far fewer records
 * than that capacity. Its callback then completes into a session and the second phase fails.
 * <p>
 * <strong>Why the phases are exact.</strong> Whether a login is dropped depends only on how many
 * same-source logins were started after it, not on what the store held before: every record the
 * source holds from an earlier class of the run, or from the other phase, is older than the phase's
 * first login and is dropped before it. So each phase is exact whatever an earlier class of the run
 * left behind, and the two test methods do not depend on their order: the second phase is exact with
 * the {@value #FLOOD_KEEPING_THE_FIRST} records the first one leaves, and the first is exact after the
 * second.
 * <p>
 * <strong>Why each phase has to finish inside five minutes.</strong> A pending record lives five
 * minutes. A phase that takes longer lets its first login expire instead of being kept or dropped.
 * The first phase then fails on its own expectation. The second would pass for the wrong reason, so it
 * measures the age of its first login from before the request that starts that login until the
 * completion of that login has returned, and asserts that this age is below
 * {@value #MAX_FIRST_LOGIN_AGE_MS} ms. The measured span contains the whole life of the pending record
 * up to the callback that reads it. Each test method carries a JUnit timeout of six minutes.
 * <p>
 * <strong>Why the suite runs alone.</strong> Both counts are exact only while no other class starts a
 * login from the same source on the primary instance at the same time: a login started beside a phase
 * would push its first login out early, or make the second phase drop a login that is not its own.
 * Every class of the run reaches the gateway from the same host and so shares this source. The class
 * therefore carries no tag and runs in the sequential failsafe execution, one class at a time. It must
 * never be moved into the concurrent one.
 * <p>
 * <strong>How the flood is sent.</strong> A login of a flood is one {@code GET} on the login path
 * with no cookies, not followed. It counts as started only when the gateway answers {@code 302} into
 * the identity provider's authorization endpoint; any other answer fails the test at once, naming the
 * status and never the query of the redirect, which identifies a pending login.
 * {@value #FLOOD_BATCH} logins of a flood are started at a time.
 * <p>
 * The cap, the counts, the batch and the age limit are this test's own constants, not values read
 * from the product: a bound read from the product under test would hold by construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffPendingLoginBoundIT extends BaseIntegrationTest {

    /**
     * The most live pending logins one source may hold in the gateway's store. Stated here: it is the
     * bound under proof.
     */
    private static final int PER_SOURCE_CAP = 256;

    /** With this many same-source logins started after it, the first login is still stored. */
    private static final int FLOOD_KEEPING_THE_FIRST = PER_SOURCE_CAP - 1;

    /** With this many same-source logins started after it, the first login has been dropped. */
    private static final int FLOOD_DROPPING_THE_FIRST = PER_SOURCE_CAP;

    /** How many logins of a flood are started at a time. */
    private static final int FLOOD_BATCH = 25;

    /**
     * The oldest the first login of the second phase may be once its completion has returned, in
     * milliseconds, counted from before the request that started it. The span contains the completion,
     * so the limit only has to be below the five-minute lifetime of a pending record.
     */
    private static final long MAX_FIRST_LOGIN_AGE_MS = 270_000L;

    /** The gateway's session cookie. */
    private static final String SESSION_COOKIE_NAME = "__Host-sheriff-session";

    /** The gateway-owned login-initiation path; each request to it starts one login. */
    private static final String LOGIN_PATH = "/bff/login";

    /** The session route: 200 with a session, 401 for a non-navigation request without one. */
    private static final String SESSION_ROUTE = "/app/get";

    /** Where the identity provider sends a completed login: the gateway's {@code redirect_uri}. */
    private static final String CALLBACK_PREFIX = "https://localhost:10443/bff/callback";

    /** The path of the identity provider's authorization endpoint, below the realm. */
    private static final String AUTHORIZATION_ENDPOINT_PATH = "/protocol/openid-connect/auth";

    /** The readiness probe below the management base URI. */
    private static final String READINESS_PATH = "/health/ready";

    /**
     * Opens one login, leaves it pending while {@value #FLOOD_KEEPING_THE_FIRST} further same-source
     * logins are started, and completes it: with exactly as many logins of its source started as the
     * source may hold, the first one is still there and yields a session.
     *
     * @throws InterruptedException when the flood is interrupted
     * @throws ExecutionException   when a request of the flood fails
     * @since 1.0
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("the first of 256 same-source logins is still pending and completes into a session")
    void firstOfCapSameSourceLoginsIsKept() throws Exception {
        OpenedLogin first = BffKeycloakLoginFlow.open(LOGIN_PATH, GATEWAY_ORIGIN);
        int started = flood(FLOOD_KEEPING_THE_FIRST);
        assertEquals(FLOOD_KEEPING_THE_FIRST, started, "the flood must start exactly the logins it counts");

        BffKeycloakLoginFlow.complete(first, USERNAME, PASSWORD);

        assertAll("the first of " + PER_SOURCE_CAP + " same-source logins",
                () -> assertTrue(holdsSessionCookie(first), "the gateway must set the session cookie"),
                () -> assertEquals(200, sessionRouteStatus(first), "the session route must serve the session"),
                () -> assertEquals(200, readinessStatus(), "the gateway must still answer its readiness probe"));
    }

    /**
     * Opens one login, starts {@value #FLOOD_DROPPING_THE_FIRST} further same-source logins — the newest
     * of them opened up to the login form — and completes the first one: its source's own newer logins
     * have dropped it, so the gateway refuses its callback. The newest login still completes into a
     * session.
     *
     * @throws InterruptedException when the flood is interrupted
     * @throws ExecutionException   when a request of the flood fails
     * @since 1.0
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    @DisplayName("the first login is dropped once 256 same-source logins were started after it, the newest one is kept")
    void firstLoginIsDroppedOnceCapSameSourceLoginsFollowIt() throws Exception {
        OpenedLogin first = BffKeycloakLoginFlow.open(LOGIN_PATH, GATEWAY_ORIGIN);
        int started = flood(FLOOD_DROPPING_THE_FIRST - 1);
        OpenedLogin newest = BffKeycloakLoginFlow.open(LOGIN_PATH, GATEWAY_ORIGIN);
        assertEquals(FLOOD_DROPPING_THE_FIRST, started + 1,
                "the flood and the newest login must start exactly the logins they count");

        CompletedLogin refused = BffKeycloakLoginFlow.complete(first, USERNAME, PASSWORD);
        long ageMillis = Duration.between(first.startedAt(), Instant.now()).toMillis();

        int refusedStatus = refused.callback().statusCode();
        assertAll("the first login, with " + FLOOD_DROPPING_THE_FIRST + " same-source logins started after it",
                () -> assertTrue(ageMillis < MAX_FIRST_LOGIN_AGE_MS, () -> "the login was completed " + ageMillis
                        + " ms after it was started, not inside the lifetime of a pending record, so its refusal"
                        + " could be an expiry"),
                () -> assertTrue(refused.callbackUrl().startsWith(CALLBACK_PREFIX),
                        "the identity provider must have accepted the credentials and redirected to the callback"),
                () -> assertTrue(refusedStatus >= 400 && refusedStatus < 500,
                        () -> "the callback must refuse the dropped login, but answered " + refusedStatus),
                () -> assertFalse(holdsSessionCookie(first), "the gateway must set no session cookie"),
                () -> assertEquals(401, sessionRouteStatus(first), "the session route must find no session"));

        BffKeycloakLoginFlow.complete(newest, USERNAME, PASSWORD);

        assertAll("the newest of " + (PER_SOURCE_CAP + 1) + " same-source logins",
                () -> assertTrue(holdsSessionCookie(newest), "the gateway must set the session cookie"),
                () -> assertEquals(200, sessionRouteStatus(newest), "the session route must serve the session"),
                () -> assertEquals(200, readinessStatus(), "the gateway must still answer its readiness probe"));
    }

    /**
     * Starts {@code count} logins and leaves them unfinished, {@value #FLOOD_BATCH} at a time. Fails at
     * the first answer that does not start a login.
     *
     * @param count how many logins to start
     * @return how many logins were started
     * @throws InterruptedException when the flood is interrupted
     * @throws ExecutionException   when a request of the flood fails
     */
    private static int flood(int count) throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(FLOOD_BATCH);
        try {
            CompletionService<FloodAnswer> answers = new ExecutorCompletionService<>(executor);
            for (int index = 0; index < count; index++) {
                answers.submit(BffPendingLoginBoundIT::startLogin);
            }
            int started = 0;
            for (int index = 0; index < count; index++) {
                FloodAnswer answer = answers.take().get();
                assertTrue(answer.startedALogin(), () -> "a login of the flood was not started: HTTP "
                        + answer.status() + " from " + LOGIN_PATH);
                started++;
            }
            return started;
        } finally {
            // Every request has been answered when the flood succeeds; when it fails, the logins not yet
            // sent are cancelled, and the ones in flight end on the deadlines of their request.
            executor.shutdownNow();
        }
    }

    /**
     * One request of a flood: a {@code GET} on the login path with no cookies, not followed.
     *
     * @return the status and whether the answer started a login
     */
    private static FloodAnswer startLogin() {
        Response response = BffKeycloakLoginFlow.gateway(Map.of(), GATEWAY_ORIGIN)
                .redirects().follow(false)
                .when().get(LOGIN_PATH)
                .then().extract().response();
        String location = response.getHeader("Location");
        boolean started = response.statusCode() == 302 && location != null
                && location.contains(AUTHORIZATION_ENDPOINT_PATH);
        return new FloodAnswer(response.statusCode(), started);
    }

    /**
     * Whether the gateway jar of a login holds a session cookie with a value.
     *
     * @param login the login, after its completion
     * @return {@code true} when the gateway set the session cookie
     */
    private static boolean holdsSessionCookie(OpenedLogin login) {
        String value = login.gatewayCookies().get(SESSION_COOKIE_NAME);
        return value != null && !value.isEmpty();
    }

    /**
     * One non-navigation request on the session route with the cookies of a login, not followed.
     *
     * @param login the login, after its completion
     * @return the status: 200 with a session, 401 without one
     */
    private static int sessionRouteStatus(OpenedLogin login) {
        return BffKeycloakLoginFlow.gateway(login.gatewayCookies(), GATEWAY_ORIGIN)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(SESSION_ROUTE)
                .then().extract().statusCode();
    }

    /**
     * The status of the gateway's readiness probe on the management interface.
     *
     * @return the status of {@code managementBaseUri() + "/health/ready"}
     */
    private static int readinessStatus() {
        return RestAssured.given()
                .config(BffKeycloakLoginFlow.clientDeadlines(CONNECT_TIMEOUT_SECONDS, RESPONSE_TIMEOUT_SECONDS))
                .relaxedHTTPSValidation()
                .baseUri(managementBaseUri()).basePath("")
                .when().get(READINESS_PATH)
                .then().extract().statusCode();
    }

    /**
     * The answer to one request of a flood.
     *
     * @param status        the HTTP status
     * @param startedALogin whether the answer was a {@code 302} into the authorization endpoint
     */
    private record FloodAnswer(int status, boolean startedALogin) {
    }
}
