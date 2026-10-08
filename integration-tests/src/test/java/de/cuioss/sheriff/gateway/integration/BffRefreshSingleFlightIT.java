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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Answer;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Endpoint;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the single-flight refresh against a running gateway: concurrent requests on one session whose
 * access token is near expiry cause <em>one</em> refresh grant, counted in the journal of a stub
 * identity provider ({@link StubIdentityProviderRig}), and every one of them relays the token that
 * grant returned.
 * <p>
 * <strong>Why the stub.</strong> A real identity provider enforcing strict refresh-token rotation
 * shows a second, concurrent grant only indirectly — as a session that ends — and one that does not
 * enforce it shows nothing at all. The stub counts the grants it received, and it can hold an answer
 * back, which is what keeps the exchange in flight while the other requests arrive.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li>The session is established with an access token already inside the refresh leeway. The stub's
 *       answer to the refresh grant is delayed by {@link #REFRESH_DELAY}.
 *       {@value #CONCURRENT_REQUESTS} threads, released together, each make one mediated call with
 *       the same session cookie.</li>
 *   <li>Every call is answered {@code 200}, every call relays the access token of the one scripted
 *       refresh answer, and the journal holds exactly one {@code grant_type=refresh_token}
 *       request.</li>
 *   <li>Every call took at least {@link #HELD_AT_LEAST}. A call that had arrived after the exchange
 *       completed would have been served the rotated token at once, and a count of one would then say
 *       nothing about coalescing; the duration shows each call was held by the exchange in
 *       flight.</li>
 *   <li>Matched control: once the rotated token itself is inside the leeway, one further, sequential
 *       call records a second grant and relays the second scripted token. The count of one is
 *       therefore the coalescing, and not a stub that stopped recording or a session that stopped
 *       refreshing.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not prove coalescing across gateway
 * instances — the single flight is per process — nor in cookie mode, nor between the near-expiry and
 * the scope-driven leg. It observes one burst of a fixed size; it is not a load test. Like every
 * {@code Bff*IT} it replays a cookie map and asserts nothing about browser cookie policy.
 * <p>
 * <strong>Timing.</strong> The control waits on the wall clock for the rotated token to near its
 * expiry: the property is defined against a token lifetime, so there is no state to poll for.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffRefreshSingleFlightIT {

    /** The plain session route, relaying a bearer to the echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    private static final int CONCURRENT_REQUESTS = 8;

    /** How long the stub holds the refresh answer back, so the exchange outlasts the burst's arrival. */
    private static final Duration REFRESH_DELAY = Duration.ofSeconds(3);

    /**
     * The least time a call that was held by the delayed exchange can take. Below {@link #REFRESH_DELAY}
     * by a margin for the threads that send a moment after the leader.
     */
    private static final Duration HELD_AT_LEAST = Duration.ofSeconds(2);

    /** The descriptor's {@code session.refresh.leeway_seconds}. */
    private static final Duration LEEWAY = Duration.ofSeconds(30);

    /** Below {@link #LEEWAY}: a session holding such a token is near expiry from the moment of login. */
    private static final Duration INSIDE_THE_LEEWAY = Duration.ofSeconds(20);

    /**
     * The lifetime of the rotated token: above {@link #LEEWAY}, so the burst cannot cause a second
     * refresh, and close enough to it that the control reaches the near-expiry window with a short
     * wait.
     */
    private static final Duration ROTATED_LIFETIME = Duration.ofSeconds(45);

    /** How far past the opening of the near-expiry window the control call is made. */
    private static final Duration INTO_THE_WINDOW = Duration.ofSeconds(5);

    /** A token lifetime far above the leeway, so no further refresh is due. */
    private static final Duration FAR_FROM_EXPIRY = Duration.ofMinutes(10);

    private static final List<String> SCOPES = List.of("openid", "profile", "email");

    private static final long BURST_TIMEOUT_SECONDS = 60L;

    @Test
    @DisplayName("concurrent requests on one near-expiry session cause one refresh grant and relay its token")
    void concurrentRequestsShareOneRefresh() throws Exception {
        try (StubIdentityProviderRig rig = StubIdentityProviderRig.start()) {
            Session session = rig.login(MEDIATED_PATH, INSIDE_THE_LEEWAY);
            assertEquals(0, rig.refreshGrants().size(), "the login itself must not have refreshed");
            Instant rotatedTokenMinted = Instant.now();
            Answer refreshAnswer = rig.tokenAnswer(SCOPES, ROTATED_LIFETIME);
            Instant rotatedTokenNearsExpiry = rotatedTokenMinted.plus(ROTATED_LIFETIME).minus(LEEWAY);
            rig.script(Endpoint.TOKEN, refreshAnswer.delayedBy(REFRESH_DELAY));

            List<Relayed> burst = fireTogether(session);

            assertTrue(Instant.now().isBefore(rotatedTokenNearsExpiry), "the burst must complete before the "
                    + "rotated token nears its own expiry, otherwise a late call refreshes a second time and "
                    + "the count below says nothing about coalescing");
            assertEquals(Collections.nCopies(CONCURRENT_REQUESTS, 200), burst.stream().map(Relayed::status).toList(),
                    () -> "every concurrent call must be served. "
                            + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
            assertEquals(Set.of(accessTokenOf(refreshAnswer)),
                    burst.stream().map(Relayed::accessToken).collect(Collectors.toSet()),
                    "every concurrent call must relay the access token of the one scripted refresh answer");
            Duration quickest = burst.stream().map(Relayed::elapsed).min(Duration::compareTo).orElseThrow();
            assertTrue(quickest.compareTo(HELD_AT_LEAST) >= 0, () -> "every call must have been held by the "
                    + "delayed exchange; the quickest took " + quickest.toMillis() + " ms, so it arrived after "
                    + "the refresh completed and the burst did not overlap it");
            assertEquals(1, rig.refreshGrants().size(), "the journal must hold exactly one refresh grant for "
                    + CONCURRENT_REQUESTS + " concurrent calls on one session");

            sleepUntil(rotatedTokenNearsExpiry.plus(INTO_THE_WINDOW));
            Answer secondAnswer = rig.tokenAnswer(SCOPES, FAR_FROM_EXPIRY);
            rig.script(Endpoint.TOKEN, secondAnswer);
            Relayed followUp = call(session);

            assertEquals(200, followUp.status(), () -> "the sequential follow-up call must be served. "
                    + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
            assertEquals(2, rig.refreshGrants().size(), "control: once the rotated token nears its expiry a "
                    + "sequential call must record a second refresh grant, so the count of one above is the "
                    + "coalescing and not a stub that stopped recording");
            assertEquals(accessTokenOf(secondAnswer), followUp.accessToken(),
                    "control: the follow-up call must relay the token of the second refresh answer");
        }
    }

    /**
     * Makes {@value #CONCURRENT_REQUESTS} mediated calls on one session from as many platform threads,
     * all released by one latch once every thread is waiting on it.
     */
    private static List<Relayed> fireTogether(Session session)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            CountDownLatch waiting = new CountDownLatch(CONCURRENT_REQUESTS);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Relayed>> pending = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                pending.add(pool.submit(() -> {
                    waiting.countDown();
                    release.await();
                    return call(session);
                }));
            }
            assertTrue(waiting.await(BURST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "every request thread must be waiting before the burst is released");
            release.countDown();
            List<Relayed> answered = new ArrayList<>();
            for (Future<Relayed> call : pending) {
                answered.add(call.get(BURST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return answered;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One mediated call: its status, the access token it relayed and how long it took. */
    private static Relayed call(Session session) {
        long sent = System.nanoTime();
        Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), StubIdentityProviderRig.ORIGIN)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - sent);
        return new Relayed(response.statusCode(), response.statusCode() == 200 ? relayedAccessToken(response) : "",
                elapsed);
    }

    /**
     * The access token the echo upstream reports it was sent, without the scheme; empty when the
     * gateway relayed none. The echo renders a header as a list, hence the brackets.
     */
    private static String relayedAccessToken(Response echoed) {
        Object authorization = echoed.path("headers.Authorization");
        if (authorization == null) {
            return "";
        }
        return authorization.toString().replaceFirst("(?i)^\\[?Bearer\\s+", "").replaceAll("]$", "");
    }

    /** The access token a scripted token answer grants. */
    private static String accessTokenOf(Answer tokenAnswer) {
        return new JsonPath(tokenAnswer.body()).getString("access_token");
    }

    /**
     * Bounded wall-clock wait. The control is defined against a token lifetime, so there is no
     * condition to poll for.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the token lifetime IS the clock under test
    private static void sleepUntil(Instant instant) {
        long millis = Duration.between(Instant.now(), instant).toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the rotated token to near expiry",
                    interrupted);
        }
    }

    /**
     * What one mediated call observed.
     *
     * @param status      the HTTP status
     * @param accessToken the relayed access token, empty when the call was not served
     * @param elapsed     the time from sending the request to holding its response
     */
    private record Relayed(int status, String accessToken, Duration elapsed) {
    }
}
