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
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
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
    private static final String JTI = "logout-token-id-1";

    private final LogoutTokenValidator validator =
            new LogoutTokenValidator(ISSUER, AUDIENCE, Duration.ofMinutes(2));

    /**
     * A spec-shaped back-channel logout token: an {@code exp} and a {@code jti}, no {@code azp}, and —
     * with {@code backchannel.logout.session.required=true} at the identity provider — no {@code sub}
     * either. Exactly the shape the ID-token validation pipeline used to reject before it could reach
     * {@link LogoutTokenValidator}.
     */
    private static Map<String, ClaimValue> logoutClaims() {
        return new HashMap<>(Map.of(
                "iss", ClaimValue.forPlainString(ISSUER),
                "aud", ClaimValue.forList("aud", List.of(AUDIENCE)),
                "iat", ClaimValue.forDateTime("iat", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                "exp", ClaimValue.forDateTime("exp", OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC)),
                "jti", ClaimValue.forPlainString(JTI),
                "events", ClaimValue.forPlainString(
                        "{" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "={}}"),
                "sid", ClaimValue.forPlainString(SID)));
    }

    /** The signature seam bound to a hand-built token typed as a logout token. */
    private static BackchannelLogoutReceiver.LogoutTokenVerifier verifying(Map<String, ClaimValue> claims) {
        return typed(claims, LogoutTokenValidator.LOGOUT_TOKEN_TYPE);
    }

    private static BackchannelLogoutReceiver.LogoutTokenVerifier typed(Map<String, ClaimValue> claims,
            @Nullable String headerType) {
        return raw -> new VerifiedLogoutToken(new IdTokenContent(claims, raw), headerType);
    }

    /** A memory of accepted tokens no case here fills. */
    private static LogoutTokenReplayGuard roomyGuard() {
        return new LogoutTokenReplayGuard(16);
    }

    private BackchannelLogoutReceiver receiver(Map<String, ClaimValue> claims, RecordingBinding binding) {
        return new BackchannelLogoutReceiver(verifying(claims), validator, binding, roomyGuard());
    }

    private static int warningsFor(LogoutRejection reason) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, reason.token()).size();
    }

    @Nested
    @DisplayName("Accepted logout tokens")
    class Accepted {

        @Test
        @DisplayName("Should destroy by sid for a sid-only, azp-less token and report the count")
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

    /**
     * A step-up or a scope widening re-issues the server-mode cookie value. The store's {@code sid} and
     * {@code sub} indexes are keyed on the stable session id, so a back-channel logout still reaches the
     * session whatever cookie value currently resolves to it. These cases run the receiver over the real
     * server-mode binding, because the property under test is that binding's.
     */
    @Nested
    @DisplayName("Session whose cookie value was re-issued (server mode)")
    class ReissuedSession {

        private static final Duration SESSION_TTL = Duration.ofHours(8);

        private final ServerSessionBinding serverBinding = new ServerSessionBinding(
                new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE, sessionId -> {
                }),
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));

        /** Binds a session, re-issues its cookie value and returns the request cookie now in force. */
        private String boundAndReissued() {
            SessionRecord session = SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken("access-token")
                    .idToken("id-token")
                    .sub(SUB)
                    .sid(SID)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            serverBinding.bind(session, NOW);
            String reissued = serverBinding.persistReissuingCookie(session, NOW).orElseThrow()
                    .setCookieHeaders().getFirst();
            return reissued.substring(0, reissued.indexOf(';'));
        }

        @Test
        @DisplayName("Should end a session whose cookie value was re-issued, by sid")
        void shouldEndReissuedSessionBySid() {
            String reissuedCookie = boundAndReissued();
            assertTrue(serverBinding.resolve(reissuedCookie, NOW).isPresent(), "precondition: the session is live");

            BackchannelResult result = new BackchannelLogoutReceiver(verifying(logoutClaims()),
                    validator, serverBinding, roomyGuard()).receive(RAW, NOW);

            assertTrue(result.accepted());
            assertEquals(1, result.destroyed(), "the logout found the session under its sid");
            assertTrue(serverBinding.resolve(reissuedCookie, NOW).isEmpty(),
                    "the re-issued cookie value resolves nothing after the logout");
        }

        @Test
        @DisplayName("Should end a session whose cookie value was re-issued, by sub")
        void shouldEndReissuedSessionBySub() {
            String reissuedCookie = boundAndReissued();
            Map<String, ClaimValue> claims = logoutClaims();
            claims.remove("sid");
            claims.put("sub", ClaimValue.forPlainString(SUB));

            BackchannelResult result = new BackchannelLogoutReceiver(verifying(claims),
                    validator, serverBinding, roomyGuard()).receive(RAW, NOW);

            assertTrue(result.accepted());
            assertEquals(1, result.destroyed(), "the logout found the session under its sub");
            assertTrue(serverBinding.resolve(reissuedCookie, NOW).isEmpty(),
                    "the re-issued cookie value resolves nothing after the logout");
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
            }, validator, binding, roomyGuard());

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
            }, validator, binding, roomyGuard());

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
            }, validator, binding, roomyGuard());

            BackchannelResult result = receiver.receive(RAW, NOW);

            assertFalse(result.accepted());
            assertEquals(0, result.destroyed());
            assertEquals(1, warningsFor(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY));
        }
    }

    /**
     * A logout token is acted on once. The first delivery ends the sessions it names; a second delivery
     * of the same token inside its window is refused and ends nothing, and a token the memory cannot
     * hold is refused rather than acted on unremembered. Each refusal is paired with the delivery that
     * succeeds, so a receiver that refused everything would fail here too.
     */
    @Nested
    @DisplayName("A logout token acts once")
    class SingleUse {

        private static final Duration SESSION_TTL = Duration.ofHours(8);

        @Test
        @DisplayName("Should act on the first delivery of a token and refuse the second")
        void shouldRefuseSecondDelivery() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = receiver(logoutClaims(), binding);

            BackchannelResult first = receiver.receive(RAW, NOW);
            BackchannelResult second = receiver.receive(RAW, NOW.plusSeconds(30));

            assertTrue(first.accepted(), "the first delivery is acted on");
            assertEquals(1, first.destroyed());
            assertFalse(second.accepted(), "the same token delivered again is refused");
            assertEquals(0, second.destroyed());
            assertEquals(1, binding.destroyCalls, "the binding was asked to destroy exactly once");
            assertEquals(1, warningsFor(LogoutRejection.REPLAYED));
        }

        @Test
        @DisplayName("Should leave a session of the subject alone that began after the token was acted on")
        void shouldNotEndLaterSessionOnRepeat() {
            ServerSessionBinding serverBinding = new ServerSessionBinding(
                    new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE, sessionId -> {
                    }),
                    new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
            Map<String, ClaimValue> claims = logoutClaims();
            claims.remove("sid");
            claims.put("sub", ClaimValue.forPlainString(SUB));
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(verifying(claims), validator,
                    serverBinding, roomyGuard());
            String endedCookie = bindSessionOfSubject(serverBinding);

            BackchannelResult logout = receiver.receive(RAW, NOW);
            String laterCookie = bindSessionOfSubject(serverBinding);
            BackchannelResult repeat = receiver.receive(RAW, NOW.plusSeconds(10));

            assertEquals(1, logout.destroyed(), "the first delivery ended the subject's session");
            assertTrue(serverBinding.resolve(endedCookie, NOW).isEmpty());
            assertFalse(repeat.accepted());
            assertTrue(serverBinding.resolve(laterCookie, NOW.plusSeconds(10)).isPresent(),
                    "the session the subject opened after the logout is still live");
        }

        @Test
        @DisplayName("Should refuse a new token when the memory is full of tokens still in their window")
        void shouldRefuseWhenMemoryIsFull() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            Map<String, ClaimValue> claims = logoutClaims();
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(verifying(claims), validator,
                    binding, new LogoutTokenReplayGuard(1));

            BackchannelResult remembered = receiver.receive(RAW, NOW);
            claims.put("jti", ClaimValue.forPlainString("logout-token-id-2"));
            BackchannelResult refused = receiver.receive(RAW, NOW);

            assertTrue(remembered.accepted(), "the token that fits the memory is acted on");
            assertFalse(refused.accepted(), "a token that cannot be remembered is not acted on");
            assertEquals(0, refused.destroyed());
            assertEquals(1, binding.destroyCalls, "nothing was destroyed for the refused token");
            assertEquals(1, warningsFor(LogoutRejection.REPLAY_MEMORY_FULL));
        }

        @Test
        @DisplayName("Should latch the warning a holder of one captured token can repeat")
        void shouldLatchRepeatedReplay() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = receiver(logoutClaims(), binding);
            receiver.receive(RAW, NOW);

            for (int attempt = 0; attempt < 5; attempt++) {
                assertFalse(receiver.receive(RAW, NOW).accepted());
            }

            assertEquals(1, warningsFor(LogoutRejection.REPLAYED),
                    "one captured token must not buy one WARN per delivery");
        }

        @Test
        @DisplayName("Should warn on every wrongly typed token, which only the identity provider can sign")
        void shouldWarnOnEveryTypeMismatch() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(typed(logoutClaims(), "JWT"),
                    validator, binding, roomyGuard());

            for (int attempt = 0; attempt < 3; attempt++) {
                assertFalse(receiver.receive(RAW, NOW).accepted());
            }

            assertEquals(3, warningsFor(LogoutRejection.TYPE_MISMATCH));
            assertEquals(0, binding.destroyCalls);
        }

        @Test
        @DisplayName("Should act on a token that carries no typ header")
        void shouldActOnUntypedToken() {
            RecordingBinding binding = new RecordingBinding(SessionBinding.IdpDestruction.SUPPORTED, 1);
            BackchannelLogoutReceiver receiver = new BackchannelLogoutReceiver(typed(logoutClaims(), null),
                    validator, binding, roomyGuard());

            assertTrue(receiver.receive(RAW, NOW).accepted());
            assertEquals(SID, binding.destroyedSid);
        }

        /** Binds a fresh session of {@link #SUB} and returns the request cookie that resolves it. */
        private String bindSessionOfSubject(ServerSessionBinding serverBinding) {
            SessionRecord session = SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken("access-token")
                    .idToken("id-token")
                    .sub(SUB)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            String setCookie = serverBinding.bind(session, NOW).setCookieHeaders().getFirst();
            return setCookie.substring(0, setCookie.indexOf(';'));
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
        private int destroyCalls;

        RecordingBinding(IdpDestruction idpDestruction, int destroyedCount) {
            this.idpDestruction = idpDestruction;
            this.destroyedCount = destroyedCount;
        }

        @Override
        public int destroyBySid(String sid) {
            destroyedSid = sid;
            destroyCalls++;
            return destroyedCount;
        }

        @Override
        public int destroyBySub(String sub) {
            destroyedSub = sub;
            destroyCalls++;
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
        public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never persists a session");
        }

        @Override
        public void destroy(SessionRecord session) {
            throw new UnsupportedOperationException("the back-channel path destroys by sid/sub only");
        }

        @Override
        public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never re-issues a cookie");
        }

        @Override
        public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
            throw new UnsupportedOperationException("the back-channel path never records an access");
        }

        @Override
        public List<String> clearingSetCookieHeaders() {
            throw new UnsupportedOperationException("the back-channel path emits no Set-Cookie");
        }
    }
}
