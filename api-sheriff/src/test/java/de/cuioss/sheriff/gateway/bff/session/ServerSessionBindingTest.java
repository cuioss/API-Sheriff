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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.session.SessionBinding.BoundSession;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 */
class ServerSessionBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-session-1";
    private static final Set<String> GRANTED_SCOPES = Set.of("openid", "profile", "orders:read");

    private InMemorySessionStore store;
    private SessionCookieCodec cookieCodec;
    private ServerSessionBinding binding;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore(16);
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

    private static String cookieHeaderFor(String sessionId) {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + sessionId;
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
        assertEquals(cookieCodec.toSetCookieHeader(session.sessionId()), setCookie,
                "the cookie is the landed hardened opaque-handle header, unchanged");
        assertFalse(setCookie.contains("access-token-1"), "no token material ever reaches the browser");
        assertEquals(Optional.of(session), store.resolve(session.sessionId(), NOW),
                "the record is held server-side in the store");
    }

    @Test
    @DisplayName("Should read the session cookie back to the stored session on resolve")
    void shouldResolveSessionFromCookieHeader() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);

        Optional<SessionRecord> resolved = binding.resolve(cookieHeaderFor(session.sessionId()), NOW);

        assertEquals(Optional.of(session), resolved);
    }

    @Test
    @DisplayName("Should resolve empty when the request carries no session cookie")
    void shouldResolveEmptyWithoutCookie() {
        assertTrue(binding.resolve(null, NOW).isEmpty(), "an absent Cookie header carries no session");
        assertTrue(binding.resolve("other=value", NOW).isEmpty(), "an unrelated cookie carries no session");
    }

    @Test
    @DisplayName("Should resolve empty for a session id the store does not know")
    void shouldResolveEmptyForUnknownSessionId() {
        assertTrue(binding.resolve(cookieHeaderFor(SessionRecord.newSessionId()), NOW).isEmpty());
    }

    @Test
    @DisplayName("Should evict and report absent an expired session on resolve (lazy TTL)")
    void shouldEvictExpiredSessionOnResolve() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);
        Instant afterExpiry = session.expiresAt().plusSeconds(1);

        Optional<SessionRecord> resolved = binding.resolve(cookieHeaderFor(session.sessionId()), afterExpiry);

        assertTrue(resolved.isEmpty(), "an expired session resolves as absent");
        assertTrue(store.resolve(session.sessionId(), NOW).isEmpty(),
                "the expired session was evicted from the store, not merely hidden");
    }

    @Test
    @DisplayName("Should replace the live session's record in place on persist, with no new cookie")
    void shouldReplaceOnPersistWithoutNewCookie() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);
        SessionRecord rotated = session(session.sessionId(), "access-token-2");

        Optional<BoundSession> persisted = binding.persist(rotated, NOW);

        BoundSession bound = persisted.orElseThrow(() -> new AssertionError("a live session is updated"));
        assertSame(rotated, bound.session());
        assertTrue(bound.setCookieHeaders().isEmpty(),
                "the opaque handle is unchanged, so the browser needs no new Set-Cookie");
        assertEquals(Optional.of(rotated), store.resolve(session.sessionId(), NOW),
                "the rotated record replaced the previous one under the same id");
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
        binding.bind(session, NOW);
        termination.accept(binding, session);

        Optional<BoundSession> persisted = binding.persist(session(session.sessionId(), "access-token-2"), NOW);

        assertTrue(persisted.isEmpty(), () -> "after " + label + " the update must report the session gone");
        assertTrue(binding.resolve(cookieHeaderFor(session.sessionId()), NOW).isEmpty(),
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
        assertEquals(Optional.of(session), binding.resolve(cookieHeaderFor(session.sessionId()), NOW));
    }

    @Test
    @DisplayName("Should keep the session resolvable throughout a persist (no destroy-then-create window)")
    void shouldNotDropTheSessionWhilePersisting() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);

        Optional<BoundSession> persisted = binding.persist(session(session.sessionId(), "access-token-2"), NOW);

        assertTrue(persisted.isPresent(), "the live session is updated");
        assertTrue(binding.resolve(cookieHeaderFor(session.sessionId()), NOW).isPresent(),
                "a concurrent resolve must never miss a rotating session");
    }

    @Test
    @DisplayName("Should carry the granted scope set S through bind and resolve")
    void shouldKeepGrantedScopesAcrossBindAndResolve() {
        SessionRecord session = scopedSession(SessionRecord.newSessionId(), "access-token-1",
                Set.of("openid"), GRANTED_SCOPES);
        binding.bind(session, NOW);

        SessionRecord resolved = binding.resolve(cookieHeaderFor(session.sessionId()), NOW).orElseThrow();

        assertEquals(GRANTED_SCOPES, resolved.grantedScopes(), "S is held server-side with the session");
        assertEquals(Set.of("openid"), resolved.activeScopes(), "A and S stay independent");
    }

    @Test
    @DisplayName("Should carry the granted scope set S through persist and resolve")
    void shouldKeepGrantedScopesAcrossPersistAndResolve() {
        SessionRecord session = scopedSession(SessionRecord.newSessionId(), "access-token-1",
                GRANTED_SCOPES, GRANTED_SCOPES);
        binding.bind(session, NOW);
        SessionRecord refreshed = scopedSession(session.sessionId(), "access-token-2", Set.of("openid"),
                GRANTED_SCOPES);

        Optional<BoundSession> persisted = binding.persist(refreshed, NOW);
        SessionRecord resolved = binding.resolve(cookieHeaderFor(session.sessionId()), NOW).orElseThrow();

        assertTrue(persisted.isPresent(), "the live session is updated");
        assertEquals(Set.of("openid"), resolved.activeScopes(), "the persisted A reaches the next request");
        assertEquals(GRANTED_SCOPES, resolved.grantedScopes(), "the persisted S reaches the next request");
    }

    @Test
    @DisplayName("Should destroy the session by its identity")
    void shouldDestroySession() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);

        binding.destroy(session);

        assertTrue(store.resolve(session.sessionId(), NOW).isEmpty());
    }

    @Test
    @DisplayName("Should tolerate destroying a session that is already gone")
    void shouldTolerateDestroyingAnAbsentSession() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");

        binding.destroy(session);

        assertTrue(store.resolve(session.sessionId(), NOW).isEmpty(), "destroy of an absent session is a no-op");
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
        assertEquals(cookieCodec.toClearingSetCookieHeader(), binding.clearingSetCookieHeader());
        assertTrue(binding.clearingSetCookieHeader().contains("Max-Age=0"));
    }
}
