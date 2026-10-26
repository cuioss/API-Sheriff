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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.securedAssetStatus;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, end to end in the shipped native image, that a gateway survives its identity provider
 * replacing the signing key abruptly: a token signed with a key the gateway had not loaded is accepted
 * within seconds and without a restart, a flood of invented key ids costs the identity provider a
 * bounded number of key-set requests and displaces nothing, and a BFF login recovers the same way.
 * <p>
 * <strong>The topology.</strong> The key-rotation variant of {@link StubIdentityProviderRig}: a stub
 * identity provider whose signing key the test replaces, and one gateway on a dedicated network whose
 * issuer {@value StubIdentityProviderRig#ROTATING_ISSUER} declares
 * {@code unknown_kid_refresh_window_seconds: 5} and {@code key_rotation_grace_period_seconds: 300}. The
 * issuer's background refresh interval is left at its 600-second default, so no leg below can be
 * explained by the background refresh: the whole test is over long before the first one is due.
 * <p>
 * <strong>What each leg proves, and the control that makes it mean something.</strong>
 * <ol>
 *   <li><em>A rotated key is accepted.</em> Control first: a token signed with the stub's first key is
 *       answered {@code 200} on the bearer-gated asset route and a request without a token is refused,
 *       so the route accepts such a token and admits nothing else. A refused bearer is raised before
 *       the trust boundary, so the shipped default answers it as the unrouted {@code 404} (ADR-0072);
 *       every refusal below is that {@value #REFUSED}. The stub's key is then replaced; the key set it
 *       serves holds the new key alone. The first request carrying a token signed with the new key is
 *       refused — the gateway does not hold that key and
 *       never fetches on a request thread — and a later one {@code 200} within twice the window plus
 *       {@value #MARGIN_SECONDS} seconds. In between, the stub recorded at least one further key-set
 *       request of the issuer, and the gateway logged {@value #UNKNOWN_KEY_ID_RECORD} naming the
 *       issuer's configured name and neither a key id nor the key-set address. Directly afterwards a
 *       token signed with the <em>replaced</em> key is still answered {@code 200}: the stub no longer
 *       publishes that key, so the gateway answers from the key set it retired — the grace period
 *       acts.</li>
 *   <li><em>Invented key ids are bounded.</em> {@value #FLOOD_REQUESTS} requests, each carrying a token
 *       that names a key id of its own which the stub never published, are all refused.
 *       They are spread over more than one window, so at least one of them finds the window free.
 *       Over the flood the stub records at least one and at most {@code floor(elapsed / window) + 1}
 *       further key-set requests of the issuer, and the gateway logs
 *       {@value #UNKNOWN_KEY_ID_RECORD} no more often than that. Afterwards a token signed with the
 *       current key and one signed with the replaced key are both still answered {@code 200}: a
 *       refresh that does not hold the key id that triggered it displaces neither the loaded key set
 *       nor the retired one.</li>
 *   <li><em>A BFF login recovers.</em> The key is replaced a second time. Logins whose ID token is
 *       signed with the new key are attempted until one completes at the callback with {@code 302},
 *       which must happen within the same bound; an attempt before that may be refused. The stub
 *       must have recorded a further key-set request of the issuer during the leg, so the completed
 *       login is one the gateway had to refresh for.</li>
 * </ol>
 * The gateway container's start time is read before the first leg and after the last and must be the
 * same: nothing above was achieved by a restart.
 * <p>
 * <strong>Tokens that were never presented before.</strong> Every token whose acceptance a leg asserts
 * after a replacement is one the gateway has not seen: the two tokens signed with the replaced key are
 * minted before the replacement and presented only after it. An acceptance can therefore not be an
 * answer the gateway kept from an earlier validation of the same token.
 * <p>
 * <strong>What this suite does NOT prove.</strong> That the replaced key stops being accepted once the
 * grace period has passed, the 30-second default window, the background refresh, an issuer whose key
 * set is a file, or a refresh whose fetch fails. It observes one gateway process and one issuer.
 * <p>
 * The test is one method on purpose: the legs are successive states of the same gateway and the same
 * stub, so splitting them would make each depend on the previous one's side effects through test
 * ordering.
 * <p>
 * <strong>Timing.</strong> The waits are wall-clock: the window is a fixed duration the gateway counts
 * itself, so there is no state to poll for other than the answer a leg waits for.
 * <p>
 * <strong>Run sequentially.</strong> The class uses the stub rig, whose container names and network are
 * fixed, so it carries no concurrency tag and runs in the sequential Failsafe execution. It does not
 * extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("JWKS key rotation: a rotated key is accepted without a restart, invented key ids are bounded, a login recovers")
class JwksKeyRotationIT {

    /** WARN — a token named a key id that is in no key set the gateway holds. */
    private static final String UNKNOWN_KEY_ID_RECORD = "ApiSheriff-136";

    /**
     * The status a refused bearer is answered with on the shipped default: the unrouted {@code 404},
     * because the refusal is raised before the trust boundary (ADR-0072).
     */
    private static final int REFUSED = UniformNotFoundAssertions.UNROUTED_STATUS;

    /** The issuer's unknown-key-id refresh window, as the rig's descriptor declares it. */
    private static final Duration WINDOW = Duration.ofSeconds(StubIdentityProviderRig.KEY_ROTATION_WINDOW_SECONDS);

    /** What the acceptance bound allows beyond twice the window, for a loaded runner. */
    private static final int MARGIN_SECONDS = 10;

    /**
     * How long a token signed with a replaced key may take to be accepted: a request that finds the
     * window claimed waits out at most one window, the refresh the next request triggers is over
     * within a moment, and the bound allows a second window and the margin on top.
     */
    private static final Duration ACCEPTANCE_BOUND = WINDOW.multipliedBy(2).plusSeconds(MARGIN_SECONDS);

    /** The scopes the bearer route needs: the descriptor's {@code oidc.scopes}. */
    private static final List<String> SCOPES = List.of("openid", "profile", "email");

    /** Far longer than the test runs, so no token of it expires. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    /** A {@code require: session} route of the descriptor, on which a login starts. */
    private static final String SESSION_PATH = "/app/get";

    private static final int FLOOD_REQUESTS = 200;

    /**
     * The pause after each request of the flood. It makes the flood outlast one window by two seconds,
     * so that at least one of its requests arrives while the window is free, whenever the window was
     * last claimed before the flood began.
     */
    private static final Duration FLOOD_PACE = WINDOW.plusSeconds(2).dividedBy(FLOOD_REQUESTS);

    /** How long a refresh the last request of the flood triggered is given to reach the stub. */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /** How long the container log is given to surface a record the gateway has already written. */
    private static final Duration LOG_VISIBILITY = Duration.ofSeconds(5);

    @Test
    @DisplayName("a rotated key is accepted within the bound, a flood of key ids is bounded, and a login recovers")
    void rotatedKeyIsAcceptedTheFloodIsBoundedAndALoginRecovers() {
        try (StubIdentityProviderRig rig = StubIdentityProviderRig.startForKeyRotation()) {
            String startedAt = rig.gatewayStartedAt();
            Set<String> publishedKeyIds = new LinkedHashSet<>();
            publishedKeyIds.add(rig.signingKeyId());

            String replacedKeyTokenForTheFlood = rotatedKeyIsAccepted(rig, publishedKeyIds);
            inventedKeyIdsAreBounded(rig, replacedKeyTokenForTheFlood);
            loginRecovers(rig, publishedKeyIds);

            assertEquals(startedAt, rig.gatewayStartedAt(), "the gateway container must be the one that was "
                    + "started before the first key replacement: no leg may have been achieved by a restart");
            assertRecordsNameTheIssuerOnly(rig, publishedKeyIds);
        }
    }

    /**
     * Leg 1.
     *
     * @return a token signed with the replaced key that no request has presented yet
     */
    private static String rotatedKeyIsAccepted(StubIdentityProviderRig rig, Set<String> publishedKeyIds) {
        String origin = rig.origin();
        assertAll("matched control: the route accepts a token signed with a key the gateway loaded at boot",
                () -> assertEquals(200, securedAssetStatus(origin, rig.accessToken(SCOPES, TOKEN_LIFETIME)),
                        () -> "a token signed with the stub's first key. " + rig.gatewayOutput()),
                () -> assertEquals(REFUSED, securedAssetStatus(origin, null), "a request without a token"));
        String replacedKeyToken = rig.accessToken(SCOPES, TOKEN_LIFETIME);
        String replacedKeyTokenForTheFlood = rig.accessToken(SCOPES, TOKEN_LIFETIME);
        int keySetRequestsBefore = keySetRequests(rig);
        assertEquals(0, unknownKeyIdRecords(rig).size(),
                "before the first key replacement no token named an unknown key id");

        publishedKeyIds.add(rig.replaceSigningKey());

        assertEquals(REFUSED, securedAssetStatus(origin, rig.accessToken(SCOPES, TOKEN_LIFETIME)), () -> "the "
                + "first token signed with the new key names a key the gateway does not hold yet, and a lookup "
                + "never fetches: it must be rejected. " + rig.gatewayOutput());
        awaitWithinTheBound(rig, "a token signed with the new key to be answered 200",
                () -> securedAssetStatus(origin, rig.accessToken(SCOPES, TOKEN_LIFETIME)) == 200);

        assertAll("after the gateway accepted the new key",
                () -> assertTrue(keySetRequests(rig) > keySetRequestsBefore, "the acceptance must be caused by "
                        + "a key-set request the stub recorded after the replacement"),
                () -> assertEquals(200, securedAssetStatus(origin, replacedKeyToken), () -> "a token signed with "
                        + "the replaced key must still be accepted: the stub publishes the new key alone, so "
                        + "only the key set the gateway retired can answer for it. " + rig.gatewayOutput()));
        awaitUnknownKeyIdRecord(rig);
        return replacedKeyTokenForTheFlood;
    }

    /** Leg 2. */
    private static void inventedKeyIdsAreBounded(StubIdentityProviderRig rig, String replacedKeyToken) {
        String origin = rig.origin();
        int keySetRequestsBefore = keySetRequests(rig);
        int recordsBefore = unknownKeyIdRecords(rig).size();
        Set<String> inventedKeyIds = new LinkedHashSet<>();
        int notRejected = 0;

        long floodStarted = System.nanoTime();
        for (int request = 0; request < FLOOD_REQUESTS; request++) {
            String keyId = UUID.randomUUID().toString();
            inventedKeyIds.add(keyId);
            if (securedAssetStatus(origin, rig.accessTokenNamingKeyId(SCOPES, TOKEN_LIFETIME, keyId)) != REFUSED) {
                notRejected++;
            }
            pause(FLOOD_PACE);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - floodStarted);
        pause(SETTLE);

        long bound = elapsed.toMillis() / WINDOW.toMillis() + 1;
        int keySetRequestsDuring = keySetRequests(rig) - keySetRequestsBefore;
        int recordsDuring = unknownKeyIdRecords(rig).size() - recordsBefore;
        int notRejectedInTotal = notRejected;
        assertAll("a flood of " + FLOOD_REQUESTS + " tokens naming " + inventedKeyIds.size()
                + " distinct invented key ids, over " + elapsed.toMillis() + " ms",
                () -> assertEquals(FLOOD_REQUESTS, inventedKeyIds.size(), "every request names a key id of its own"),
                () -> assertEquals(0, notRejectedInTotal, "every token naming an invented key id must be "
                        + "refused with " + REFUSED),
                () -> assertTrue(elapsed.compareTo(WINDOW) > 0, "the flood must outlast one window, otherwise "
                        + "it may have run entirely inside a claimed one and triggered nothing"),
                () -> assertTrue(keySetRequestsDuring >= 1, "the flood must have caused at least one key-set "
                        + "request, otherwise the bound below is met by a gateway that never refreshes"),
                () -> assertTrue(keySetRequestsDuring <= bound, () -> "the stub recorded " + keySetRequestsDuring
                        + " key-set requests of the issuer during the flood; the window admits at most " + bound),
                () -> assertTrue(recordsDuring <= bound, () -> "the gateway logged " + UNKNOWN_KEY_ID_RECORD + " "
                        + recordsDuring + " times during the flood; the window admits at most " + bound));

        assertAll("the flood displaced nothing",
                () -> assertEquals(200, securedAssetStatus(origin, rig.accessToken(SCOPES, TOKEN_LIFETIME)),
                        () -> "a token signed with the current key must still be accepted. " + rig.gatewayOutput()),
                () -> assertEquals(200, securedAssetStatus(origin, replacedKeyToken), () -> "a token signed with "
                        + "the replaced key must still be accepted: a refresh that does not hold the key id "
                        + "that triggered it must not end the grace period. " + rig.gatewayOutput()));
    }

    /** Leg 3. */
    private static void loginRecovers(StubIdentityProviderRig rig, Set<String> publishedKeyIds) {
        int keySetRequestsBefore = keySetRequests(rig);

        publishedKeyIds.add(rig.replaceSigningKey());

        awaitWithinTheBound(rig, "a login whose ID token is signed with the new key to complete at the callback",
                () -> rig.loginStatus(SESSION_PATH, TOKEN_LIFETIME) == 302);
        assertTrue(keySetRequests(rig) > keySetRequestsBefore, "the completed login must be one the gateway "
                + "refreshed its key set for: the stub must have recorded a key-set request after the second "
                + "replacement");
    }

    /**
     * Asserts every {@value #UNKNOWN_KEY_ID_RECORD} record of the run names the issuer's configured
     * name and nothing that identifies a key or the key-set address.
     */
    private static void assertRecordsNameTheIssuerOnly(StubIdentityProviderRig rig, Set<String> publishedKeyIds) {
        List<String> records = unknownKeyIdRecords(rig);
        assertFalse(records.isEmpty(), "the run must have logged " + UNKNOWN_KEY_ID_RECORD);
        for (String line : records) {
            assertAll(UNKNOWN_KEY_ID_RECORD + " record: " + line,
                    () -> assertTrue(line.contains("'" + StubIdentityProviderRig.ROTATING_ISSUER + "'"),
                            "it names the issuer's configured name"),
                    () -> assertTrue(publishedKeyIds.stream().noneMatch(line::contains),
                            "it names no key id the stub published"),
                    () -> assertFalse(line.contains("keycloak:8443") || line.contains("openid-connect"),
                            "it names neither the host nor the path the key set is fetched from"));
        }
    }

    /** The number of key-set requests of the rotating issuer the stub has recorded so far. */
    private static int keySetRequests(StubIdentityProviderRig rig) {
        return rig.keySetRequests(StubIdentityProviderRig.ROTATING_ISSUER).size();
    }

    /** The {@value #UNKNOWN_KEY_ID_RECORD} records in the gateway's output so far, in order. */
    private static List<String> unknownKeyIdRecords(StubIdentityProviderRig rig) {
        return rig.gatewayOutput().lines().filter(line -> line.contains(UNKNOWN_KEY_ID_RECORD)).toList();
    }

    /**
     * Asserts the gateway logged {@value #UNKNOWN_KEY_ID_RECORD}. The record is written before the
     * rejected request is answered, but the container log can surface it a moment later, so the read is
     * retried briefly.
     */
    private static void awaitUnknownKeyIdRecord(StubIdentityProviderRig rig) {
        long deadline = System.nanoTime() + LOG_VISIBILITY.toNanos();
        while (unknownKeyIdRecords(rig).isEmpty()) {
            if (System.nanoTime() >= deadline) {
                fail("a token naming a key id the gateway does not hold must be reported by WARN "
                        + UNKNOWN_KEY_ID_RECORD + ". " + rig.gatewayOutput());
            }
            pause(POLL_INTERVAL);
        }
    }

    /**
     * Repeats {@code accepted} until it holds, and fails when it does not within
     * {@link #ACCEPTANCE_BOUND}. Every evaluation is a request of its own, so an evaluation that finds
     * the window free is what triggers the refresh a later one succeeds by.
     */
    private static void awaitWithinTheBound(StubIdentityProviderRig rig, String what, BooleanSupplier accepted) {
        long deadline = System.nanoTime() + ACCEPTANCE_BOUND.toNanos();
        int evaluations = 0;
        while (System.nanoTime() < deadline) {
            evaluations++;
            if (accepted.getAsBoolean()) {
                return;
            }
            pause(POLL_INTERVAL);
        }
        fail("waited " + ACCEPTANCE_BOUND.toSeconds() + "s — twice the " + WINDOW.toSeconds() + "s window plus "
                + MARGIN_SECONDS + "s — for " + what + ", over " + evaluations + " attempts. " + rig.gatewayOutput());
    }

    /** Bounded wall-clock wait: the window is a fixed duration, so there is no condition to poll for. */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the fixed refresh window IS the clock under test
    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out the key-set refresh window", interrupted);
        }
    }
}
