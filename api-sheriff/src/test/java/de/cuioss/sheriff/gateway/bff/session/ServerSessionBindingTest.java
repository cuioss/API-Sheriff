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


import de.cuioss.sheriff.gateway.bff.session.SessionBinding.BoundSession;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServerSessionBinding} — the regression net proving the seam adapter preserves
 * every landed {@link SessionStore} semantic byte for byte: the opaque-handle cookie on bind, the
 * upsert-in-place on persist (no destroy-then-create window), the lazy TTL eviction on resolve, and
 * the O(1) {@code sid}/{@code sub} destruction with its {@code SUPPORTED} capability.
 */
class ServerSessionBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-session-1";

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
    @DisplayName("Should upsert the rotated record in place on persist, with no new cookie")
    void shouldUpsertOnPersistWithoutNewCookie() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);
        SessionRecord rotated = session(session.sessionId(), "access-token-2");

        BoundSession bound = binding.persist(rotated, NOW);

        assertSame(rotated, bound.session());
        assertTrue(bound.setCookieHeaders().isEmpty(),
                "the opaque handle is unchanged, so the browser needs no new Set-Cookie");
        assertEquals(Optional.of(rotated), store.resolve(session.sessionId(), NOW),
                "the rotated record replaced the previous one under the same id");
    }

    @Test
    @DisplayName("Should keep the session resolvable throughout a persist (no destroy-then-create window)")
    void shouldNotDropTheSessionWhilePersisting() {
        SessionRecord session = session(SessionRecord.newSessionId(), "access-token-1");
        binding.bind(session, NOW);

        binding.persist(session(session.sessionId(), "access-token-2"), NOW);

        assertTrue(binding.resolve(cookieHeaderFor(session.sessionId()), NOW).isPresent(),
                "a concurrent resolve must never miss a rotating session");
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
