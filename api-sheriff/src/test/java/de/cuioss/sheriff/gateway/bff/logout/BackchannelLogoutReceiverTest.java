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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver.BackchannelResult;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BackchannelLogoutReceiver}: which destruction a validated logout token drives, and —
 * the point of this suite — what the path says about itself at the <em>default</em> log level.
 * <p>
 * Before this coverage existed, every branch of the receiver logged at {@code DEBUG}, so a logout that
 * was never delivered, one that was delivered and refused, and one that was accepted but matched no
 * session produced byte-identical silence in a production log. The observability assertions below are
 * what keep those three apart: an acceptance raises {@code ApiSheriff-13} carrying the destroyed
 * count, and each refusal raises {@code ApiSheriff-112} naming the check that refused.
 * <p>
 * The signature seam is bound to a hand-built token (or a stub that throws), so every case runs
 * without a live IdP; the session binding is a recording stub, so the sid-vs-sub decision is
 * observable directly rather than inferred from a store's side effects.
 */
@EnableTestLogger
class BackchannelLogoutReceiverTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "bff-client";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-sid-9";
    private static final String RAW = "raw.logout.token";

    private final LogoutTokenValidator validator =
            new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2));

    /**
     * A spec-shaped back-channel logout token: no {@code exp}, no {@code azp}, and — with
     * {@code backchannel.logout.session.required=true} at the identity provider — no {@code sub}
     * either. Exactly the shape the ID-token validation pipeline used to reject before it could reach
     * {@link LogoutTokenValidator}.
     */
    private static Map<String, ClaimValue> logoutClaims() {
        return new HashMap<>(Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "events", ClaimValue.forPlainString(
                        "{" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "={}}"),
                "sid", ClaimValue.forPlainString(SID)));
    }

    private BackchannelLogoutReceiver receiver(Map<String, ClaimValue> claims, RecordingBinding binding) {
        return new BackchannelLogoutReceiver(raw -> new IdTokenContent(claims, raw), validator, binding);
    }

    private static int warningsFor(LogoutRejection reason) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, reason.token()).size();
    }

    @Nested
    @DisplayName("Accepted logout tokens")
    class Accepted {

        @Test
        @DisplayName("Should destroy by sid for a sid-only, exp-less, azp-less token and report the count")
        void shouldDestroyBySid() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 2);

            BackchannelResult result = receiver(logoutClaims(), binding).receive(RAW, NOW);

            assertTrue(result.accepted(), "a spec-valid sid-only logout token is accepted");
            assertEquals(2, result.destroyed());
            assertEquals(SID, binding.destroyedSid, "a token carrying a sid destroys exactly that IdP session");
            assertNull(binding.destroyedSub, "the sub path must not also fire");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "2 session(s) destroyed");
        }

        @Test
        @DisplayName("Should destroy by sub when the token carries only a sub")
        void shouldDestroyBySub() {
            Map<String, ClaimValue> claims = logoutClaims();
            claims.remove("sid");
            claims.put("sub", ClaimValue.forPlainString(SUB));
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);

            BackchannelResult result = receiver(claims, binding).receive(RAW, NOW);

            assertTrue(result.accepted());
            assertEquals(SUB, binding.destroyedSub);
            assertNull(binding.destroyedSid);
        }

        /**
         * The discriminator the whole INFO record exists for: a {@code destroyed=0} acceptance says
         * delivery, signature verification and the full claim residual all succeeded and the sid
         * simply matched nothing — a session-binding question, not a delivery or validation one. It
         * must stay an accepted outcome AND stay visible, or that distinction is unreadable in a log.
         */
        @Test
        @DisplayName("Should report a zero-destruction acceptance as an accepted, visible outcome")
        void shouldReportZeroDestructionAcceptance() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 0);

            BackchannelResult result = receiver(logoutClaims(), binding).receive(RAW, NOW);

            assertTrue(result.accepted(), "a sid that matches no session is still a valid logout token");
            assertEquals(0, result.destroyed());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "0 session(s) destroyed");
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, BackchannelLogoutReceiver.class);
        }
    }

    @Nested
    @DisplayName("Rejected logout tokens")
    class Rejected {

        @Test
        @DisplayName("Should report the specific claim check that refused, not a bare rejection")
        void shouldReportClaimRejectionReason() {
            Map<String, ClaimValue> claims = logoutClaims();
            claims.put("nonce", ClaimValue.forPlainString("n-0S6_WzA2Mj"));
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);

            BackchannelResult result = receiver(claims, binding).receive(RAW, NOW);

            assertFalse(result.accepted());
            assertEquals(0, result.destroyed(), "a rejected token destroys nothing");
            assertNull(binding.destroyedSid);
            assertEquals(1, warningsFor(LogoutRejection.NONCE_PRESENT));
        }

        @Test
        @DisplayName("Should report a signature failure without leaking the engine's message to WARN")
        void shouldReportSignatureRejection() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(raw -> {
                throw new TokenValidationException(SecurityEventCounter.EventType.SIGNATURE_VALIDATION_FAILED,
                        "engine detail naming the attacker-supplied token");
            }, validator, binding);

            assertFalse(receiver.receive(RAW, NOW).accepted());

            assertEquals(1, warningsFor(LogoutRejection.SIGNATURE_REJECTED));
            assertEquals(0, TestLoggerFactory.getTestHandler()
                            .resolveLogMessagesContaining(TestLogLevel.WARN, "attacker-supplied").size(),
                    "the engine's own message describes attacker-supplied input and must stay at DEBUG");
        }

        @Test
        @DisplayName("Should latch the repeated signature failure an unauthenticated caller can drive")
        void shouldLatchRepeatedSignatureRejection() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(raw -> {
                throw new TokenValidationException(SecurityEventCounter.EventType.SIGNATURE_VALIDATION_FAILED,
                        "rejected");
            }, validator, binding);

            for (int attempt = 0; attempt < 5; attempt++) {
                assertFalse(receiver.receive(RAW, NOW).accepted());
            }

            assertEquals(1, warningsFor(LogoutRejection.SIGNATURE_REJECTED),
                    "the back-channel path is reserved and unauthenticated — a caller must not be able "
                            + "to drive an unbounded WARN flood by posting garbage to it");
        }

        @Test
        @DisplayName("Should refuse before the verifier when the binding cannot honour IdP destruction")
        void shouldRefuseWithoutDestructionCapability() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.UNSUPPORTED, 3);
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(raw -> {
                throw new IllegalStateException("the verifier must not be reached at all");
            }, validator, binding);

            BackchannelResult result = receiver.receive(RAW, NOW);

            assertFalse(result.accepted());
            assertEquals(0, result.destroyed());
            assertEquals(1, warningsFor(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY));
        }
    }

    /**
     * Records which destruction the receiver drove, so the sid-vs-sub decision is asserted directly
     * rather than through a store's observable side effects. Every other seam method is unreachable
     * from the back-channel path and says so rather than returning a plausible value.
     */
    private static final class RecordingBinding implements SessionBinding {

        private final IdpDestruction idpDestruction;
        private final int destroyedCount;
        private @Nullable String destroyedSid;
        private @Nullable String destroyedSub;

        RecordingBinding(IdpDestruction idpDestruction, int destroyedCount) {
            this.idpDestruction = idpDestruction;
            this.destroyedCount = destroyedCount;
        }

        @Override
        public int destroyBySid(String sid) {
            destroyedSid = sid;
            return destroyedCount;
        }

        @Override
        public int destroyBySub(String sub) {
            destroyedSub = sub;
            return destroyedCount;
        }

        @Override
        public IdpDestruction idpDestruction() {
            return idpDestruction;
        }

        @Override
        public BoundSession bind(SessionRecord session, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never binds a session");
        }

        @Override
        public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never resolves a session");
        }

        @Override
        public BoundSession persist(SessionRecord rotated, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never persists a session");
        }

        @Override
        public void destroy(SessionRecord session) {
            throw new UnsupportedOperationException("the back-channel path destroys by sid/sub only");
        }

        @Override
        public String clearingSetCookieHeader() {
            throw new UnsupportedOperationException("the back-channel path emits no Set-Cookie");
        }
    }
}
