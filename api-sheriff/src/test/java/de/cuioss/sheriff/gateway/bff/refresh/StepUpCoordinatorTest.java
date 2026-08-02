/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.bff.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator.SilentSatisfaction;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator.StepUpInitiation;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator.StepUpOutcome;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser.StepUpChallenge;
import de.cuioss.sheriff.token.client.flow.StepUpHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StepUpCoordinator}: RFC 9470 step-up orchestration — challenge detection, silent
 * satisfaction, and the auth-code re-drive with a same-origin-validated replay target.
 * <p>
 * The engine step-up authorization and the silent-satisfaction attempt are driven through the
 * {@link StepUpInitiation} and {@link SilentSatisfaction} seams, so every path is exercised with
 * hand-built engine objects and no test-double framework. The step-up challenge parsing is the
 * engine's own ({@code StepUpChallengeParser}) and is exercised through {@code detectChallenge}.
 */
class StepUpCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String STEP_UP_URL = "https://idp.example.com/authorize?acr_values=urn:example:gold";
    private static final String REPLAY_URL = "/orders/42";
    private static final String ACR = "urn:example:gold";

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
    }

    private static SessionRecord session(String acr) {
        return SessionRecord.builder()
                .sessionId("session-1")
                .accessToken("access-current")
                .refreshToken("refresh-current")
                .idToken("id-current")
                .sub("sub-1")
                .sid(null)
                .expiresAt(NOW.plusSeconds(28800))
                .acr(acr)
                .authTime(null)
                .build();
    }

    private static StepUpChallenge challenge() {
        return new StepUpChallenge(ACR, null);
    }

    private StepUpCoordinator coordinator(SilentSatisfaction silent, StepUpInitiation initiation) {
        return new StepUpCoordinator(silent, initiation, pendingStore, bindingCodec, GATEWAY_ORIGIN);
    }

    private StepUpCoordinator reDrivingCoordinator() {
        StepUpHandler.StepUpRequest request = new StepUpHandler.StepUpRequest(STEP_UP_URL,
                FlowContext.create(GATEWAY_ORIGIN + "/auth/callback"));
        return coordinator((s, c, now) -> Optional.empty(), c -> request);
    }

    private String recordIdFrom(StepUpOutcome outcome) {
        String cookieHeader = outcome.setCookieHeaders().getFirst().split(";", 2)[0];
        return bindingCodec.readRecordId(cookieHeader).orElseThrow();
    }

    @Nested
    @DisplayName("Challenge detection")
    class Detection {

        @Test
        @DisplayName("Should detect an RFC 9470 insufficient_user_authentication challenge")
        void shouldDetectChallenge() {
            StepUpCoordinator coordinator = reDrivingCoordinator();

            Optional<StepUpChallenge> detected = coordinator.detectChallenge(
                    "Bearer error=\"insufficient_user_authentication\", acr_values=\"" + ACR + "\"");

            assertTrue(detected.isPresent(), "the upstream step-up challenge is parsed");
            assertEquals(Optional.of(ACR), detected.get().getAcrValues());
        }

        @Test
        @DisplayName("Should return empty for a null, blank, or non-step-up WWW-Authenticate header")
        void shouldReturnEmptyForNonChallenge() {
            StepUpCoordinator coordinator = reDrivingCoordinator();

            assertTrue(coordinator.detectChallenge(null).isEmpty());
            assertTrue(coordinator.detectChallenge("   ").isEmpty());
            assertTrue(coordinator.detectChallenge("Bearer realm=\"api\"").isEmpty(),
                    "an ordinary bearer challenge is not a step-up challenge");
        }
    }

    @Nested
    @DisplayName("Silent satisfaction")
    class Silent {

        @Test
        @DisplayName("Should replay with the elevated session when the challenge is satisfied silently")
        void shouldReplayOnSilentSatisfaction() {
            SessionRecord elevated = session(ACR);
            StepUpCoordinator coordinator = coordinator((s, c, now) -> Optional.of(elevated),
                    c -> {
                        throw new IllegalStateException("re-drive must not be attempted when silent satisfaction works");
                    });

            StepUpOutcome outcome = coordinator.coordinate(session("urn:example:silver"), challenge(), REPLAY_URL, NOW);

            assertTrue(outcome.isSatisfied());
            assertEquals(StepUpOutcome.Kind.SATISFIED, outcome.kind());
            assertSame(elevated, outcome.session(), "the elevated session is used for the replay");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a silent satisfaction sets no browser-binding cookie");
        }
    }

    @Nested
    @DisplayName("Auth-code re-drive")
    class ReDrive {

        @Test
        @DisplayName("Should re-drive to the engine step-up URL and set the browser-binding cookie")
        void shouldReDriveWhenSilentFails() {
            StepUpCoordinator coordinator = reDrivingCoordinator();

            StepUpOutcome outcome = coordinator.coordinate(session("urn:example:silver"), challenge(), REPLAY_URL, NOW);

            assertFalse(outcome.isSatisfied());
            assertEquals(StepUpOutcome.Kind.RE_DRIVE, outcome.kind());
            assertEquals(STEP_UP_URL, outcome.location());
            assertEquals(1, outcome.setCookieHeaders().size(), "the browser-binding cookie is minted for the re-drive");
        }

        @Test
        @DisplayName("Should persist a single-use pending record carrying the same-origin replay URL")
        void shouldRecordSameOriginReplayUrl() {
            StepUpCoordinator coordinator = reDrivingCoordinator();

            StepUpOutcome outcome = coordinator.coordinate(session("urn:example:silver"), challenge(), REPLAY_URL, NOW);

            PendingAuthorizationRecord pending = pendingStore.consume(recordIdFrom(outcome), NOW).orElseThrow();
            assertEquals(REPLAY_URL, pending.returnUrl(), "the validated replay URL is recorded as the return target");
        }

        @Test
        @DisplayName("Should fall back to the default return URL for an off-origin replay target")
        void shouldRejectOffOriginReplayUrl() {
            StepUpCoordinator coordinator = reDrivingCoordinator();

            StepUpOutcome outcome = coordinator.coordinate(session("urn:example:silver"), challenge(),
                    "https://evil.example.com/steal", NOW);

            PendingAuthorizationRecord pending = pendingStore.consume(recordIdFrom(outcome), NOW).orElseThrow();
            assertEquals(StepUpCoordinator.DEFAULT_RETURN_URL, pending.returnUrl(),
                    "an off-origin replay target is never an open redirect");
        }
    }

    @Nested
    @DisplayName("Argument and outcome contracts")
    class Contracts {

        @Test
        @DisplayName("Should reject null session, challenge, and instant")
        void shouldRejectNullArguments() {
            StepUpCoordinator coordinator = reDrivingCoordinator();
            var validSession = session(ACR);
            var validChallenge = challenge();

            assertThrows(NullPointerException.class,
                    () -> coordinator.coordinate(null, validChallenge, REPLAY_URL, NOW));
            assertThrows(NullPointerException.class,
                    () -> coordinator.coordinate(validSession, null, REPLAY_URL, NOW));
            assertThrows(NullPointerException.class,
                    () -> coordinator.coordinate(validSession, validChallenge, REPLAY_URL, null));
        }

        @Test
        @DisplayName("Should defensively copy the re-drive cookies in the outcome")
        void shouldExposeSatisfiedFactory() {
            StepUpOutcome satisfied = StepUpOutcome.satisfied(session(ACR));

            assertTrue(satisfied.isSatisfied());
            assertNull(satisfied.location());
            assertTrue(satisfied.setCookieHeaders().isEmpty());
        }
    }
}
