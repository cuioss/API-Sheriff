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
package de.cuioss.sheriff.gateway.bff.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.bff.session.SessionBinding.BoundSession;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link ServerSessionBinding} — the regression net proving the seam adapter carries the
 * {@link SessionStore} semantics through unchanged: the opaque-handle cookie on bind, the
 * replace-in-place on persist (no destroy-then-create window), the lazy TTL eviction on resolve, and
 * the O(1) {@code sid}/{@code sub} destruction with its {@code SUPPORTED} capability.
 * <p>
 * It also pins the rule that keeps a terminated session terminated: persist updates a session the
 * store still holds and never creates one, so after a destroy by identity, by {@code sid} or by
 * {@code sub} it reports the session gone and nothing resolves. Bind stays the creating write.
 * <p>
 * The cookie value is an opaque handle the binding mints, never the session id. The
 * {@code ReissuingWrite} cases cover the write of a step-up or a widening — a new cookie value, the
 * previous one resolving nothing, the same session — and the {@code IdleDeadline} cases the idle
 * timeout the store behind the binding enforces.
 */
class ServerSessionBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-session-1";
    private static final Set<String> GRANTED_SCOPES = Set.of("openid", "profile", "orders:read");
    private static final String TERMINATIONS =
            "de.cuioss.sheriff.gateway.bff.session.ServerSessionBindingTest#terminations";

    private InMemorySessionStore store;
    private SessionCookieCodec cookieCodec;
    private ServerSessionBinding binding;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16, IDLE_TIMEOUT, Integer.MAX_VALUE, sessionId -> {
        });
        cookieCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL);
        binding = new ServerSessionBinding(store, cookieCodec);
    }

    private static SessionRecord session(String sessionId, String accessToken) {
        return SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken(accessToken)
                .idToken("raw-id-token")
                .sub(SUB)
                .sid(SID)
                .expiresAt(NOW.plus(SESSION_TTL))
                .build();
    }

    private static SessionRecord scopedSession(String sessionId, String accessToken, Set<String> activeScopes,
            Set<String> grantedScopes) {
        return SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken(accessToken)
                .idToken("raw-id-token")
                .sub(SUB)
                .sid(SID)
                .expiresAt(NOW.plus(SESSION_TTL))
                .activeScopes(activeScopes)
                .grantedScopes(grantedScopes)
                .build();
    }

    private static String cookieHeaderFor(String cookieValue) {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + cookieValue;
    }

    /** The request {@code Cookie} header a browser sends back for the single cookie a write returned. */
    private static String requestCookieOf(BoundSession bound) {
        assertEquals(1, bound.setCookieHeaders().size(), "the write returned exactly one session cookie");
        String setCookie = bound.setCookieHeaders().getFirst();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /** The cookie handle carried by the single cookie a write returned. */
    private String handleOf(BoundSession bound) {
        return cookieCodec.readCookieHandle(requestCookieOf(bound)).orElseThrow();
    }

    @Test
    @DisplayName("Should store the session and hand the browser only the opaque handle on bind")
    void shouldBindSessionAndEmitOpaqueCookie() {
        // Arrange
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");

        // Act
        BoundSession bound = binding.bind(session, NOW);

        // Assert
        assertSame(session, bound.session(), "bind returns the session it stored");
        assertEquals(1, bound.setCookieHeaders().size(), "server mode emits exactly one session cookie");
        String setCookie = bound.setCookieHeaders().getFirst();
        String cookieHandle = handleOf(bound);
        assertEquals(cookieCodec.toSetCookieHeader(cookieHandle), setCookie,
                "the cookie is the landed hardened opaque-handle header, unchanged");
        assertFalse(setCookie.contains("access-token-1"), "no token material ever reaches the browser");
        assertEquals(Optional.of(session), store.resolve(cookieHandle, NOW),
                "the record is held server-side in the store");
    }

    @Test
    @DisplayName("Should never hand the browser the session id")
    void shouldNotEmitTheSessionId() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");

        BoundSession bound = binding.bind(session, NOW);

        String setCookie = bound.setCookieHeaders().getFirst();
        assertNotEquals(session.sessionId(), handleOf(bound), "the cookie value is not the session id");
        assertFalse(setCookie.contains(session.sessionId()), "the session id appears nowhere in the Set-Cookie");
        assertTrue(binding.resolve(cookieHeaderFor(session.sessionId()), NOW).isEmpty(),
                "a request presenting the session id as the cookie value resolves nothing");
    }

    @Test
    @DisplayName("Should mint a distinct 256-bit handle per bind")
    void shouldMintDistinctHandles() {
        BoundSession first = binding.bind(session(SessionRecord.newSessionId(), "access-token-1"), NOW);
        BoundSession second = binding.bind(session(SessionRecord.newSessionId(), "access-token-2"), NOW);

        assertNotEquals(handleOf(first), handleOf(second), "two sessions never share a handle");
        assertEquals(43, handleOf(first).length(), "32 random bytes, base64url without padding");
    }

    @Test
    @DisplayName("Should read the session cookie back to the stored session on resolve")
    void shouldResolveSessionFromCookieHeader() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession bound = binding.bind(session, NOW);

        Optional<SessionRecord> resolved = binding.resolve(requestCookieOf(bound), NOW);

        assertEquals(Optional.of(session), resolved);
    }

    @Test
    @DisplayName("Should resolve empty when the request carries no session cookie")
    void shouldResolveEmptyWithoutCookie() {
        assertTrue(binding.resolve(null, NOW).isEmpty(), "an absent Cookie header carries no session");
        assertTrue(binding.resolve("other=value", NOW).isEmpty(), "an unrelated cookie carries no session");
    }

    @Test
    @DisplayName("Should resolve empty for a cookie value the store does not know")
    void shouldResolveEmptyForUnknownSessionId() {
        assertTrue(binding.resolve(cookieHeaderFor(SessionRecord.newSessionId()), NOW).isEmpty());
    }

    @Test
    @DisplayName("Should evict and report absent an expired session on resolve (lazy TTL)")
    void shouldEvictExpiredSessionOnResolve() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession bound = binding.bind(session, NOW);
        Instant afterExpiry = session.expiresAt().plusSeconds(1);

        Optional<SessionRecord> resolved = binding.resolve(requestCookieOf(bound), afterExpiry);

        assertTrue(resolved.isEmpty(), "an expired session resolves as absent");
        assertTrue(store.resolve(handleOf(bound), NOW).isEmpty(),
                "the expired session was evicted from the store, not merely hidden");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should replace the live session's record in place on persist, with no new cookie")
    void shouldReplaceOnPersistWithoutNewCookie() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession created = binding.bind(session, NOW);
        SessionRecord rotated = session(session.sessionId(), "access-token-2");

        Optional<BoundSession> persisted = binding.persist(rotated, NOW);

        BoundSession bound = persisted.orElseThrow(() -> new AssertionError("a live session is updated"));
        assertSame(rotated, bound.session());
        assertTrue(bound.setCookieHeaders().isEmpty(),
                "the opaque handle is unchanged, so the browser needs no new Set-Cookie");
        assertEquals(Optional.of(rotated), store.resolve(handleOf(created), NOW),
                "the rotated record replaced the previous one under the same handle");
        assertEquals(1, store.size(), "the update added no entry");
    }

    static Stream<Arguments> terminations() {
        BiConsumer<ServerSessionBinding, SessionRecord> byIdentity = SessionBinding::destroy;
        BiConsumer<ServerSessionBinding, SessionRecord> bySid = (target, session) -> target.destroyBySid(SID);
        BiConsumer<ServerSessionBinding, SessionRecord> bySub = (target, session) -> target.destroyBySub(SUB);
        return Stream.of(Arguments.of("destroy", byIdentity), Arguments.of("destroyBySid", bySid),
                Arguments.of("destroyBySub", bySub));
    }

    @ParameterizedTest(name = "after {0}, persist reports the session gone")
    @MethodSource("terminations")
    @DisplayName("Should report a terminated session gone on persist and never bring it back")
    void shouldReportTerminatedSessionGoneOnPersist(String label,
            BiConsumer<ServerSessionBinding, SessionRecord> termination) {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession created = binding.bind(session, NOW);
        termination.accept(binding, session);

        Optional<BoundSession> persisted = binding.persist(session(session.sessionId(), "access-token-2"), NOW);

        assertTrue(persisted.isEmpty(), () -> "after " + label + " the update must report the session gone");
        assertTrue(binding.resolve(requestCookieOf(created), NOW).isEmpty(),
                () -> "the session terminated by " + label + " stays unresolvable");
        assertEquals(0, store.size(), "nothing was written back into the store");
    }

    @Test
    @DisplayName("Should report a session gone on persist when it was never bound")
    void shouldReportUnboundSessionGoneOnPersist() {
        SessionRecord neverBound = session(SessionRecord.newSessionId(), "access-token-1");

        Optional<BoundSession> persisted = binding.persist(neverBound, NOW);

        assertTrue(persisted.isEmpty(), "persist is the updating write and never creates a session");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should still create a session through bind after a persist was refused")
    void shouldStillCreateThroughBind() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        Optional<BoundSession> refused = binding.persist(session, NOW);

        BoundSession bound = binding.bind(session, NOW);

        assertTrue(refused.isEmpty(), "the persist before the bind created nothing");
        assertEquals(1, bound.setCookieHeaders().size(), "bind is the creating write and emits the session cookie");
        assertEquals(Optional.of(session), binding.resolve(requestCookieOf(bound), NOW));
    }

    @Test
    @DisplayName("Should keep the session resolvable throughout a persist (no destroy-then-create window)")
    void shouldNotDropTheSessionWhilePersisting() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession created = binding.bind(session, NOW);

        Optional<BoundSession> persisted = binding.persist(session(session.sessionId(), "access-token-2"), NOW);

        assertTrue(persisted.isPresent(), "the live session is updated");
        assertTrue(binding.resolve(requestCookieOf(created), NOW).isPresent(),
                "a concurrent resolve must never miss a rotating session");
    }

    @Test
    @DisplayName("Should carry the granted scope set S through bind and resolve")
    void shouldKeepGrantedScopesAcrossBindAndResolve() {
        SessionRecord session = scopedSession(SessionRecord.newSessionId(), "access-token-1",
                Set.of("openid"), GRANTED_SCOPES);
        BoundSession created = binding.bind(session, NOW);

        SessionRecord resolved = binding.resolve(requestCookieOf(created), NOW).orElseThrow();

        assertEquals(GRANTED_SCOPES, resolved.grantedScopes(), "S is held server-side with the session");
        assertEquals(Set.of("openid"), resolved.activeScopes(), "A and S stay independent");
    }

    @Test
    @DisplayName("Should carry the granted scope set S through persist and resolve")
    void shouldKeepGrantedScopesAcrossPersistAndResolve() {
        SessionRecord session = scopedSession(SessionRecord.newSessionId(), "access-token-1",
                GRANTED_SCOPES, GRANTED_SCOPES);
        BoundSession created = binding.bind(session, NOW);
        SessionRecord refreshed = scopedSession(session.sessionId(), "access-token-2", Set.of("openid"),
                GRANTED_SCOPES);

        Optional<BoundSession> persisted = binding.persist(refreshed, NOW);
        SessionRecord resolved = binding.resolve(requestCookieOf(created), NOW).orElseThrow();

        assertTrue(persisted.isPresent(), "the live session is updated");
        assertEquals(Set.of("openid"), resolved.activeScopes(), "the persisted A reaches the next request");
        assertEquals(GRANTED_SCOPES, resolved.grantedScopes(), "the persisted S reaches the next request");
    }

    @Test
    @DisplayName("Should destroy the session by its identity")
    void shouldDestroySession() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        BoundSession created = binding.bind(session, NOW);

        binding.destroy(session);

        assertTrue(store.resolve(handleOf(created), NOW).isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should tolerate destroying a session that is already gone")
    void shouldTolerateDestroyingAnAbsentSession() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");

        binding.destroy(session);

        assertEquals(0, store.size(), "destroy of an absent session is a no-op");
    }

    @Test
    @DisplayName("Should destroy every session carrying the IdP sid")
    void shouldDestroyBySid() {
        binding.bind(session(SessionRecord.newSessionId(), "access-token-1"), NOW);
        binding.bind(session(SessionRecord.newSessionId(), "access-token-2"), NOW);

        assertEquals(2, binding.destroyBySid(SID), "both sessions share the IdP sid");
        assertEquals(0, binding.destroyBySid(SID), "a repeated back-channel logout destroys nothing more");
    }

    @Test
    @DisplayName("Should destroy every session for the subject")
    void shouldDestroyBySub() {
        binding.bind(session(SessionRecord.newSessionId(), "access-token-1"), NOW);
        binding.bind(session(SessionRecord.newSessionId(), "access-token-2"), NOW);

        assertEquals(2, binding.destroyBySub(SUB));
        assertEquals(0, binding.destroyBySub(SUB));
    }

    @Test
    @DisplayName("Should report SUPPORTED IdP-driven destruction — the store holds the secondary index")
    void shouldReportSupportedIdpDestruction() {
        assertEquals(IdpDestruction.SUPPORTED, binding.idpDestruction());
    }

    @Test
    @DisplayName("Should clear the session cookie through the landed clearing header")
    void shouldClearSessionCookie() {
        assertEquals(List.of(cookieCodec.toClearingSetCookieHeader()), binding.clearingSetCookieHeaders(),
                "server mode sets one cookie, so it clears exactly one");
        assertTrue(binding.clearingSetCookieHeaders().getFirst().contains("Max-Age=0"));
    }

    @Nested
    @DisplayName("Re-issuing write (step-up and scope widening)")
    class ReissuingWrite {

        @Test
        @DisplayName("Should return a new cookie value and stop resolving the previous one at once")
        void shouldReissueTheCookieValue() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            SessionRecord widened = session(session.sessionId(), "access-token-2");

            BoundSession reissued = binding.persistReissuingCookie(widened, NOW)
                    .orElseThrow(() -> new AssertionError("a live session is updated"));

            assertSame(widened, reissued.session());
            assertNotEquals(handleOf(created), handleOf(reissued), "the browser is handed a new cookie value");
            assertNotEquals(session.sessionId(), handleOf(reissued), "the new value is not the session id either");
            assertEquals(Optional.of(widened), binding.resolve(requestCookieOf(reissued), NOW),
                    "the new cookie resolves the updated session");
            assertTrue(binding.resolve(requestCookieOf(created), NOW).isEmpty(),
                    "the previous cookie value resolves nothing from the re-issue on");
        }

        @Test
        @DisplayName("Should keep the session the same session: id, absolute expiry and a single store entry")
        void shouldKeepTheSession() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            binding.bind(session, NOW);

            BoundSession reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-2"), NOW).orElseThrow();

            SessionRecord resolved = binding.resolve(requestCookieOf(reissued), NOW).orElseThrow();
            assertEquals(session.sessionId(), resolved.sessionId(), "the session id is unchanged");
            assertEquals(session.expiresAt(), resolved.expiresAt(), "the absolute expiry is unchanged");
            assertEquals(1, store.size(), "the re-issue created no second session");
        }

        @Test
        @DisplayName("Should give the re-issued cookie the remaining lifetime and the login cookie the full one")
        void shouldCarryRemainingLifetimeAsMaxAge() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            Instant threeHoursIn = NOW.plus(Duration.ofHours(3));
            store.recordAccess(session.sessionId(), threeHoursIn);

            BoundSession reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-2"), threeHoursIn)
                    .orElseThrow();

            assertTrue(created.setCookieHeaders().getFirst().contains("; Max-Age=28800;"),
                    "the login cookie carries the full ttl of eight hours");
            assertTrue(reissued.setCookieHeaders().getFirst().contains("; Max-Age=18000;"),
                    "the re-issued cookie carries the five hours the session still has");
            assertEquals(cookieCodec.toSetCookieHeader(handleOf(reissued), Duration.ofHours(5)),
                    reissued.setCookieHeaders().getFirst(), "it is otherwise the same hardened header");
        }

        @ParameterizedTest(name = "after {0}, the re-issuing write reports the session gone")
        @MethodSource(TERMINATIONS)
        @DisplayName("Should report a terminated session gone on the re-issuing write and create nothing")
        void shouldReportTerminatedSessionGone(String label,
                BiConsumer<ServerSessionBinding, SessionRecord> termination) {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            termination.accept(binding, session);

            Optional<BoundSession> reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-2"), NOW);

            assertTrue(reissued.isEmpty(), () -> "after " + label + " the write must report the session gone");
            assertTrue(binding.resolve(requestCookieOf(created), NOW).isEmpty(),
                    () -> "the session terminated by " + label + " stays unresolvable");
            assertEquals(0, store.size(), "no session was created and no cookie handed out");
        }

        @Test
        @DisplayName("Should report a session gone on the re-issuing write when it was never bound")
        void shouldReportUnboundSessionGone() {
            SessionRecord neverBound = session(SessionRecord.newSessionId(), "access-token-1");

            Optional<BoundSession> reissued = binding.persistReissuingCookie(neverBound, NOW);

            assertTrue(reissued.isEmpty(), "the re-issuing write is an updating write and never creates a session");
            assertEquals(0, store.size());
        }

        @ParameterizedTest(name = "{0} ends a session whose cookie was re-issued")
        @MethodSource(TERMINATIONS)
        @DisplayName("Should end a re-issued session through every destruction path")
        void shouldEndReissuedSession(String label, BiConsumer<ServerSessionBinding, SessionRecord> termination) {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            binding.bind(session, NOW);
            SessionRecord widened = session(session.sessionId(), "access-token-2");
            BoundSession reissued = binding.persistReissuingCookie(widened, NOW).orElseThrow();

            termination.accept(binding, widened);

            assertTrue(binding.resolve(requestCookieOf(reissued), NOW).isEmpty(),
                    () -> label + " reaches the session whatever cookie value currently resolves to it");
            assertEquals(0, store.size());
        }

        @Test
        @DisplayName("Should leave the re-issued cookie in force when a refresh persists afterwards")
        void shouldKeepReissuedCookieAcrossLaterPersist() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            BoundSession reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-2"), NOW).orElseThrow();
            SessionRecord rotated = session(session.sessionId(), "access-token-3");

            Optional<BoundSession> persisted = binding.persist(rotated, NOW);

            assertTrue(persisted.isPresent(), "the refresh finds the session by its stable id");
            assertEquals(Optional.of(rotated), binding.resolve(requestCookieOf(reissued), NOW));
            assertTrue(binding.resolve(requestCookieOf(created), NOW).isEmpty(),
                    "the refresh did not put the previous cookie value back");
        }
    }

    @Nested
    @DisplayName("Idle deadline")
    class IdleDeadline {

        @Test
        @DisplayName("Should report a session absent once it was idle for the idle timeout")
        void shouldEndIdleSession() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            Instant idleDeadline = NOW.plus(IDLE_TIMEOUT);

            assertTrue(binding.resolve(requestCookieOf(created), idleDeadline.minusSeconds(1)).isPresent(),
                    "resolving does not count as an access, and the session is live up to the deadline");
            assertTrue(binding.resolve(requestCookieOf(created), idleDeadline).isEmpty(),
                    "the session ends at the idle deadline although its absolute lifetime runs on");
            assertEquals(0, store.size(), "the idle session was evicted");
        }

        @Test
        @DisplayName("Should move the idle deadline on a recorded access and return no cookie for it")
        void shouldMoveDeadlineOnRecordedAccess() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            Instant access = NOW.plus(Duration.ofMinutes(20));

            List<String> cookies = binding.recordAccess(session, requestCookieOf(created), access);

            assertTrue(cookies.isEmpty(), "the last access lives in the store, so the browser needs no cookie");
            assertTrue(binding.resolve(requestCookieOf(created), NOW.plus(IDLE_TIMEOUT)).isPresent(),
                    "the deadline measured from the login no longer applies");
            assertTrue(binding.resolve(requestCookieOf(created), access.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the session ends one idle timeout after the recorded access");
        }

        @Test
        @DisplayName("Should record an access for a request that still carried the previous cookie value")
        void shouldRecordAccessByStableSessionId() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            BoundSession created = binding.bind(session, NOW);
            BoundSession reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-2"), NOW).orElseThrow();
            Instant access = NOW.plus(Duration.ofMinutes(20));

            binding.recordAccess(session, requestCookieOf(created), access);

            assertTrue(binding.resolve(requestCookieOf(reissued), NOW.plus(IDLE_TIMEOUT)).isPresent(),
                    "the access is keyed on the session id, so it reached the session under its new handle");
        }

        @Test
        @DisplayName("Should not move the idle deadline by a refresh write or a re-issue")
        void shouldNotCountAnUpdatingWriteAsAccess() {
            SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
            binding.bind(session, NOW);
            Instant later = NOW.plus(Duration.ofMinutes(20));

            binding.persist(session(session.sessionId(), "access-token-2"), later);
            BoundSession reissued = binding
                    .persistReissuingCookie(session(session.sessionId(), "access-token-3"), later).orElseThrow();

            assertTrue(binding.resolve(requestCookieOf(reissued), NOW.plus(IDLE_TIMEOUT)).isEmpty(),
                    "neither updating write is an access: idleness is still measured from the login");
        }
    }
}
