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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;


import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the D3 server-side session store: {@link SessionRecord} (credential redaction, TTL,
 * normalization) and {@link InMemorySessionStore} (create/resolve/destroy, lazy + swept TTL
 * eviction, O(1) back-channel destruction by {@code sid}/{@code sub}, and the max-session bound).
 * <p>
 * The {@code Capacity} cases cover the bound from both sides: a create that reaches the bound
 * reclaims the slots of sessions expired at its reference instant, while a bound genuinely full of
 * live sessions still refuses fail-closed. Both directions are asserted, because reclamation that
 * quietly stopped refusing would turn the DoS guard off rather than make it accurate.
 * <p>
 * The {@code ConditionalReplace} cases cover the write a refresh or a widening makes:
 * {@code replaceIfPresent} replaces a stored record and never creates one, so a session destroyed by
 * id, by {@code sid} or by {@code sub} is not written back. Each refusal is paired with the
 * replacement that succeeds on a stored record, so a method that refused everything would fail too.
 * <p>
 * A session is resolved by its cookie handle, never by its session id: every case creates a session
 * under a handle that differs from the id. The {@code HandleReissue} cases cover the re-issuing write
 * of a step-up or a widening — the new handle resolves, the previous one resolves nothing, and the
 * session stays the same session. The {@code IdleTimeout} cases cover the second deadline: it moves
 * only on a recorded access, and recording one never replaces the stored record.
 */
class InMemorySessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant FUTURE = T0.plusSeconds(3600);
    private static final String SESSION_ID = "s1";
    private static final String OLD_SUB = "old-sub";
    private static final String NEW_SUB = "new-sub";
    private static final String OLD_SID = "old-sid";
    private static final String NEW_SID = "new-sid";
    /**
     * An idle timeout longer than every instant the lifetime, index and capacity cases look at, so the
     * absolute lifetime is the only deadline in play there. The {@code IdleTimeout} cases use
     * {@link #IDLE_TIMEOUT} instead.
     */
    private static final Duration NO_IDLE_EFFECT = Duration.ofDays(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(600);
    private static final String REISSUED_HANDLE = "reissued-handle";
    /** The three destruction paths, shared by the conditional-replace and the handle-re-issue cases. */
    private static final String DESTRUCTIONS =
            "de.cuioss.sheriff.gateway.bff.session.InMemorySessionStoreTest$ConditionalReplace#destructions";

    /** The cookie handle a session is created with in these cases — never the session id itself. */
    private static String handleOf(String sessionId) {
        return "handle-of-" + sessionId;
    }

    private static void create(InMemorySessionStore store, SessionRecord session, Instant now) {
        store.create(session, handleOf(session.sessionId()), now);
    }

    /** Resolves the session by the handle it was created with. */
    private static Optional<SessionRecord> resolve(InMemorySessionStore store, String sessionId, Instant now) {
        return store.resolve(handleOf(sessionId), now);
    }

    private static SessionRecord session(String sessionId, String sub, @Nullable String sid, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken("access-" + sessionId)
                .refreshToken("refresh-" + sessionId)
                .idToken("id-" + sessionId)
                .sub(sub)
                .sid(sid)
                .expiresAt(expiresAt)
                .acr("urn:acr:silver")
                .authTime(T0)
                .build();
    }

    @Nested
    @DisplayName("Create / resolve / destroy")
    class Lifecycle {

        @Test
        @DisplayName("Should resolve a created session and drop it after destroy-by-id")
        void shouldCreateResolveDestroy() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", "sid1", FUTURE), T0);

            assertTrue(resolve(store,"s1", T0).isPresent());
            store.destroyById("s1");
            assertTrue(resolve(store,"s1", T0).isEmpty(), "a destroyed session no longer resolves");
        }

        @Test
        @DisplayName("Should return empty for an unknown session id")
        void shouldReturnEmptyForUnknownId() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            assertTrue(resolve(store,"nope", T0).isEmpty());
        }

        @Test
        @DisplayName("Should treat destroy-by-id of an absent session as a no-op")
        void shouldNoOpDestroyAbsent() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            store.destroyById("absent");
            assertEquals(0, store.size());
        }
    }

    @Nested
    @DisplayName("Absolute TTL eviction (lazy + swept)")
    class TtlEviction {

        @Test
        @DisplayName("Should evict an expired session lazily on resolve")
        void shouldEvictExpiredLazily() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", null, T0.plusSeconds(10)), T0);

            assertTrue(resolve(store,"s1", T0.plusSeconds(11)).isEmpty(), "an expired session is refused");
            assertEquals(0, store.size(), "the expired session was evicted lazily on resolve");
        }

        @Test
        @DisplayName("Should treat the TTL boundary as expired (inclusive)")
        void shouldTreatBoundaryAsExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Instant expiry = T0.plusSeconds(10);
            create(store,session("s1", "sub1", null, expiry), T0);

            assertTrue(resolve(store,"s1", expiry).isEmpty(), "expiry is inclusive of the boundary");
        }

        @Test
        @DisplayName("Should sweep every expired session and leave live ones untouched")
        void shouldSweepExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("dead1", "sub1", null, T0.plusSeconds(5)), T0);
            create(store,session("dead2", "sub2", null, T0.plusSeconds(5)), T0);
            create(store,session("live", "sub3", null, FUTURE), T0);

            int swept = store.sweepExpired(T0.plusSeconds(10));

            assertEquals(2, swept, "both expired sessions were swept");
            assertEquals(1, store.size());
            assertTrue(resolve(store,"live", T0.plusSeconds(10)).isPresent());
        }
    }

    @Nested
    @DisplayName("O(1) back-channel destruction")
    class BackChannel {

        @Test
        @DisplayName("Should destroy every session carrying the given sid")
        void shouldDestroyBySid() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", "A", FUTURE), T0);
            create(store,session("s2", "sub2", "A", FUTURE), T0);
            create(store,session("s3", "sub3", "B", FUTURE), T0);

            assertEquals(2, store.destroyBySid("A"));
            assertTrue(resolve(store,"s1", T0).isEmpty());
            assertTrue(resolve(store,"s2", T0).isEmpty());
            assertTrue(resolve(store,"s3", T0).isPresent(), "an unrelated sid is untouched");
        }

        @Test
        @DisplayName("Should destroy every session for the given subject")
        void shouldDestroyBySub() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "user-x", null, FUTURE), T0);
            create(store,session("s2", "user-x", null, FUTURE), T0);
            create(store,session("s3", "user-y", null, FUTURE), T0);

            assertEquals(2, store.destroyBySub("user-x"));
            assertTrue(resolve(store,"s3", T0).isPresent(), "an unrelated subject is untouched");
        }

        @Test
        @DisplayName("Should report zero and clean the index when the sid is already gone")
        void shouldReturnZeroForUnknownSidAndCleanIndex() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", "A", FUTURE), T0);

            assertEquals(1, store.destroyBySid("A"));
            assertEquals(0, store.destroyBySid("A"), "the secondary index was cleaned after the destroy");
            assertEquals(0, store.destroyBySid("never-seen"));
        }

        @Test
        @DisplayName("Should drop the previous subject's index entry when an upsert changes sub")
        void shouldRepairSubIndexOnUpsert() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "old-sub", null, FUTURE), T0);

            create(store,session("s1", "new-sub", null, FUTURE), T0);

            assertEquals(0, store.destroyBySub("old-sub"),
                    "the replaced record's subject must not keep resolving to the session id — otherwise a"
                            + " back-channel logout for the old subject destroys the replacement");
            assertTrue(resolve(store,"s1", T0).isPresent(), "the replacement survived the stale-subject destroy");
            assertEquals(1, store.destroyBySub("new-sub"), "the replacement is reachable under its own subject");
        }

        @Test
        @DisplayName("Should drop the previous sid's index entry when an upsert changes sid")
        void shouldRepairSidIndexOnUpsert() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", "old-sid", FUTURE), T0);

            create(store,session("s1", "sub1", "new-sid", FUTURE), T0);

            assertEquals(0, store.destroyBySid("old-sid"),
                    "a create replacing a stored id must not leave the old sid destroying the new record");
            assertTrue(resolve(store,"s1", T0).isPresent(), "the replacement survived the stale-sid destroy");
            assertEquals(1, store.destroyBySid("new-sid"), "the replacement is reachable under its own sid");
        }
    }

    @Nested
    @DisplayName("Max-session bound")
    class Capacity {

        @Test
        @DisplayName("Should free capacity once expired sessions are swept")
        void shouldFreeCapacityAfterSweep() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", null, T0.plusSeconds(5)), T0);
            create(store,session("s2", "sub2", null, FUTURE), T0);

            store.sweepExpired(T0.plusSeconds(10));
            create(store,session("s3", "sub3", null, FUTURE), T0);

            assertEquals(2, store.size());
        }

        @Test
        @DisplayName("Should reclaim an expired session's slot when a create reaches the bound")
        void shouldReclaimExpiredCapacityOnCreate() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", null, T0.plusSeconds(5)), T0);
            create(store,session("s2", "sub2", null, T0.plusSeconds(5)), T0);

            // Driven through create() alone — sweepExpired is deliberately never called by hand here.
            // Calling it would merely re-test "Should free capacity once expired sessions are swept"
            // above; what this case exists to prove is that reaching the bound is itself the trigger.
            create(store,session("s3", "sub3", null, FUTURE), T0.plusSeconds(10));

            assertEquals(1, store.size(), "both expired sessions released their slots, leaving only the new one");
            assertTrue(resolve(store,"s3", T0.plusSeconds(10)).isPresent(), "the reclaiming create was admitted");
        }

        @Test
        @DisplayName("Should still refuse a create at the bound when every stored session is live")
        void shouldRefuseWhenBoundIsFullOfLiveSessions() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", null, FUTURE), T0);
            create(store,session("s2", "sub2", null, FUTURE), T0);

            SessionRecord overflow = session("s3", "sub3", null, FUTURE);
            Instant afterExpiry = T0.plusSeconds(10);
            assertThrows(IllegalStateException.class, () -> create(store,overflow, afterExpiry),
                    "the at-capacity sweep reclaims nothing while every session is live, so the DoS"
                            + " guard must still refuse — reclamation may not become a way around the bound");
            assertEquals(2, store.size(), "the refused create left the store untouched");
        }

        @Test
        @DisplayName("Should admit an upsert of an already-stored session id at the bound")
        void shouldAdmitUpsertAtCapacity() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store,session("s1", "sub1", null, FUTURE), T0);
            create(store,session("s2", "sub2", null, FUTURE), T0);

            assertDoesNotThrow(() -> create(store,session("s1", "sub1", null, FUTURE), T0),
                    "a create naming an already-stored id replaces a record already counted against the"
                            + " bound, so it consumes no new capacity");
            assertEquals(2, store.size(), "the upsert replaced in place rather than adding an entry");
        }

        @Test
        @DisplayName("Should reject a non-positive capacity bound")
        void shouldRejectNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(0, NO_IDLE_EFFECT));
            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(-1, NO_IDLE_EFFECT));
        }
    }

    @Nested
    @DisplayName("Session record contract")
    class RecordContract {

        @Test
        @DisplayName("Should generate distinct opaque session ids")
        void shouldGenerateDistinctIds() {
            assertNotEquals(SessionRecord.newSessionId(), SessionRecord.newSessionId());
            assertFalse(SessionRecord.newSessionId().isBlank());
        }

        @Test
        @DisplayName("Should redact the session id and every token in toString, keeping sub visible")
        void shouldRedactCredentialsInToString() {
            SessionRecord session = SessionRecord.builder()
                    .sessionId("SID-SECRET")
                    .accessToken("AT-SECRET")
                    .refreshToken("RT-SECRET")
                    .idToken("IT-SECRET")
                    .sub("user-123")
                    .sid("idp-sid")
                    .expiresAt(FUTURE)
                    .build();

            String rendered = session.toString();

            assertFalse(rendered.contains("SID-SECRET"), "the bearer session id must be redacted");
            assertFalse(rendered.contains("AT-SECRET"), "the access token must be redacted");
            assertFalse(rendered.contains("RT-SECRET"), "the refresh token must be redacted");
            assertFalse(rendered.contains("IT-SECRET"), "the ID token must be redacted");
            assertTrue(rendered.contains("***REDACTED***"));
            assertTrue(rendered.contains("user-123"), "the subject is non-secret metadata and stays visible");
        }

        @Test
        @DisplayName("Should accept absent nullable components and reject null mandatory components")
        void shouldAcceptAbsentAndReject() {
            SessionRecord sparse = new SessionRecord("s", "at", null, "it", "sub", null, FUTURE, null, null, null,
                    null, null);
            assertNull(sparse.refreshToken());
            assertNull(sparse.sid());
            assertNull(sparse.acr());
            assertNull(sparse.authTime());
            assertNull(sparse.sessionNonce(), "an absent session nonce stays null");
            assertTrue(sparse.activeScopes().isEmpty(), "an absent active scope set normalizes to empty");
            assertTrue(sparse.grantedScopes().isEmpty(), "an absent granted scope set normalizes to empty");

            assertThrows(NullPointerException.class,
                    () -> new SessionRecord(null, "at", null, "it", "sub", null, FUTURE, null, null, null, Set.of(),
                            Set.of()));
            assertThrows(NullPointerException.class,
                    () -> new SessionRecord("s", "at", null, "it", null, null, FUTURE, null, null, null, Set.of(),
                            Set.of()));
        }

        @Test
        @DisplayName("Should hold the active scope set as an immutable defensive copy")
        void shouldCopyActiveScopes() {
            Set<String> source = new HashSet<>(Set.of("openid", "orders:read"));
            SessionRecord session = new SessionRecord("s", "at", null, "it", "sub", null, FUTURE, null, null, null,
                    source, Set.of());
            source.add("profile");

            assertEquals(Set.of("openid", "orders:read"), session.activeScopes());
            Set<String> held = session.activeScopes();
            assertThrows(UnsupportedOperationException.class, () -> held.add("email"));
        }

        @Test
        @DisplayName("Should hold the granted scope set as an immutable defensive copy, independent of A")
        void shouldCopyGrantedScopes() {
            Set<String> source = new HashSet<>(Set.of("openid", "orders:read", "orders:write"));
            SessionRecord session = new SessionRecord("s", "at", null, "it", "sub", null, FUTURE, null, null, null,
                    Set.of("openid"), source);
            source.add("profile");

            assertEquals(Set.of("openid", "orders:read", "orders:write"), session.grantedScopes());
            assertEquals(Set.of("openid"), session.activeScopes(), "S never leaks into A");
            Set<String> held = session.grantedScopes();
            assertThrows(UnsupportedOperationException.class, () -> held.add("email"));
        }

        @Test
        @DisplayName("Should reject a null element in the granted scope set")
        void shouldRejectNullGrantedScope() {
            Set<String> withNull = new HashSet<>();
            withNull.add(null);

            assertThrows(NullPointerException.class, () -> new SessionRecord("s", "at", null, "it", "sub", null,
                    FUTURE, null, null, null, Set.of(), withNull));
        }

        @Test
        @DisplayName("Should render the active and granted scope names in toString — they are not credentials")
        void shouldRenderScopeSets() {
            SessionRecord session = SessionRecord.builder()
                    .sessionId("s").accessToken("AT-SECRET").idToken("IT-SECRET").sub("user-123")
                    .expiresAt(FUTURE).activeScopes(Set.of("orders:read"))
                    .grantedScopes(Set.of("orders:read", "orders:write")).build();

            String rendered = session.toString();
            assertTrue(rendered.contains("activeScopes=[orders:read]"), rendered);
            assertTrue(rendered.contains("grantedScopes="), rendered);
            assertTrue(rendered.contains("orders:write"), rendered);
        }
    }

    @Nested
    @DisplayName("Active scope set in the server-mode store")
    class ActiveScopes {

        @Test
        @DisplayName("Should resolve the active scope set a stored session was created with")
        void shouldKeepActiveScopesAcrossStore() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Set<String> scopes = Set.of("openid", "profile", "email", "orders:read");
            SessionRecord scoped = SessionRecord.builder()
                    .sessionId("s1").accessToken("at").idToken("it").sub("sub1").expiresAt(FUTURE)
                    .activeScopes(scopes).build();

            create(store,scoped, T0);

            assertEquals(scopes, resolve(store,"s1", T0).orElseThrow().activeScopes());
        }

        @Test
        @DisplayName("Should resolve the replaced active scope set after a replacement")
        void shouldReplaceActiveScopesOnReplacement() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,SessionRecord.builder().sessionId("s1").accessToken("at").idToken("it").sub("sub1")
                    .expiresAt(FUTURE).activeScopes(Set.of("openid", "orders:read")).build(), T0);

            boolean replaced = store.replaceIfPresent(SessionRecord.builder().sessionId("s1").accessToken("at2")
                    .idToken("it").sub("sub1").expiresAt(FUTURE).activeScopes(Set.of("openid")).build());

            assertTrue(replaced, "the session is stored, so the refresh's write replaces it");
            assertEquals(Set.of("openid"), resolve(store,"s1", T0).orElseThrow().activeScopes(),
                    "a refresh persisted through a replacement carries the narrowed set to the next request");
        }

        @Test
        @DisplayName("Should resolve the granted scope set a stored session was created with, and its replacement")
        void shouldKeepGrantedScopesAcrossStore() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Set<String> granted = Set.of("openid", "orders:read");
            Set<String> widened = Set.of("openid", "orders:read", "orders:write");
            create(store,SessionRecord.builder().sessionId("s1").accessToken("at").idToken("it").sub("sub1")
                    .expiresAt(FUTURE).activeScopes(granted).grantedScopes(granted).build(), T0);
            SessionRecord created = resolve(store,"s1", T0).orElseThrow();

            boolean replaced = store.replaceIfPresent(SessionRecord.builder().sessionId("s1").accessToken("at2")
                    .idToken("it").sub("sub1").expiresAt(FUTURE).activeScopes(widened).grantedScopes(widened)
                    .build());

            assertTrue(replaced, "the session is stored, so the widening's write replaces it");
            assertEquals(granted, created.grantedScopes());
            assertEquals(widened, resolve(store,"s1", T0).orElseThrow().grantedScopes(),
                    "a widening persisted through a replacement carries the widened S to the next request");
        }
    }

    /**
     * The conditional write: {@code replaceIfPresent} is how a refresh and a widening update a session
     * that already exists. It never creates, which is what keeps a destroyed session destroyed.
     */
    @Nested
    @DisplayName("Conditional replace (the write of a refresh or a widening)")
    class ConditionalReplace {

        @Test
        @DisplayName("Should replace a stored record in place and report the replacement")
        void shouldReplaceStoredRecord() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            SessionRecord replacement = SessionRecord.builder().sessionId(SESSION_ID).accessToken("rotated-access")
                    .idToken("rotated-id").sub(OLD_SUB).sid(OLD_SID).expiresAt(FUTURE).build();

            boolean replaced = store.replaceIfPresent(replacement);

            assertTrue(replaced, "a stored id is replaced");
            assertEquals(1, store.size(), "the replacement took the stored record's place, adding no entry");
            assertEquals(replacement, resolve(store,SESSION_ID, T0).orElseThrow(),
                    "the next resolve sees the replacement");
        }

        @Test
        @DisplayName("Should refuse an id the store does not hold, report it and leave the store empty")
        void shouldRefuseAbsentId() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE));

            assertFalse(replaced, "nothing is stored under the id, so nothing is replaced");
            assertEquals(0, store.size(), "the conditional write never creates");
            assertTrue(resolve(store,SESSION_ID, T0).isEmpty(), "no session resolves afterwards");
            assertEquals(0, store.destroyBySid(OLD_SID), "the refused record was not indexed by sid");
            assertEquals(0, store.destroyBySub(OLD_SUB), "the refused record was not indexed by sub");
        }

        @Test
        @DisplayName("Should find a replacement carrying another sid and sub under the new keys only")
        void shouldReindexWhenSidAndSubChange() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            assertTrue(replaced);
            assertEquals(0, store.destroyBySid(OLD_SID), "the previous sid no longer resolves to the session");
            assertEquals(0, store.destroyBySub(OLD_SUB), "the previous sub no longer resolves to the session");
            assertTrue(resolve(store,SESSION_ID, T0).isPresent(),
                    "a destroy on the previous keys left the replacement in place");
            assertEquals(1, store.destroyBySid(NEW_SID), "the new sid destroys the session");
            assertTrue(resolve(store,SESSION_ID, T0).isEmpty());
        }

        @Test
        @DisplayName("Should destroy a replaced session through its new sub")
        void shouldDestroyReplacedSessionByNewSub() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            int destroyed = store.destroyBySub(NEW_SUB);

            assertEquals(1, destroyed, "the new sub destroys the session");
            assertTrue(resolve(store,SESSION_ID, T0).isEmpty());
            assertEquals(0, store.destroyBySid(NEW_SID), "the sid index was cleaned with the session");
        }

        static Stream<Arguments> destructions() {
            Consumer<InMemorySessionStore> byId = store -> store.destroyById(SESSION_ID);
            Consumer<InMemorySessionStore> bySid = store -> store.destroyBySid(OLD_SID);
            Consumer<InMemorySessionStore> bySub = store -> store.destroyBySub(OLD_SUB);
            return Stream.of(Arguments.of("destroyById", byId), Arguments.of("destroyBySid", bySid),
                    Arguments.of("destroyBySub", bySub));
        }

        @ParameterizedTest(name = "{0} followed by the conditional write leaves the session gone")
        @MethodSource("destructions")
        @DisplayName("Should not write a destroyed session back, however it was destroyed")
        void shouldNotRecreateDestroyedSession(String label, Consumer<InMemorySessionStore> destruction) {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            destruction.accept(store);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE));

            assertFalse(replaced, () -> "after " + label + " there is no record to replace");
            assertEquals(0, store.size(), () -> label + " must not be undone by a write that was in flight");
            assertTrue(resolve(store,SESSION_ID, T0).isEmpty(), () -> "the session stays gone after " + label);
        }

        @Test
        @DisplayName("Should replace at the max-session bound without sweeping and without refusing")
        void shouldReplaceAtTheBound() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            create(store,session("expired", "sub2", null, T0.plusSeconds(5)), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            assertTrue(replaced, "a replacement consumes no capacity, so the bound does not refuse it");
            assertEquals(2, store.size(),
                    "the expired neighbour still holds its slot — a replacement never triggers the sweep");
        }

        @Test
        @DisplayName("Should replace a lapsed record nothing has evicted yet, and keep refusing it on resolve")
        void shouldReplaceLapsedRecordWithoutRevivingIt() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Instant expiry = T0.plusSeconds(5);
            create(store,session(SESSION_ID, OLD_SUB, OLD_SID, expiry), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, expiry));

            assertTrue(replaced, "the check is on presence, not on liveness");
            assertTrue(resolve(store,SESSION_ID, T0.plusSeconds(10)).isEmpty(),
                    "the absolute TTL is still enforced on resolve against the expiry the replacement carries");
        }

        @Test
        @DisplayName("Should reject a null replacement")
        void shouldRejectNullReplacement() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);

            assertThrows(NullPointerException.class, () -> store.replaceIfPresent(null));
        }
    }

    /**
     * The re-issuing write: {@code replaceAndReissueHandle} is how a step-up and a widening update a
     * session and swap the value the browser holds. The session stays the same session.
     */
    @Nested
    @DisplayName("Cookie-handle re-issue (the write of a step-up or a widening)")
    class HandleReissue {

        @Test
        @DisplayName("Should resolve the session by its cookie handle and never by its session id")
        void shouldNotResolveBySessionId() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);

            assertTrue(store.resolve(handleOf(SESSION_ID), T0).isPresent(), "the handle resolves the session");
            assertTrue(store.resolve(SESSION_ID, T0).isEmpty(),
                    "the session id is the internal identity and resolves nothing when presented as a handle");
        }

        @Test
        @DisplayName("Should resolve the session from the new handle and nothing from the previous one")
        void shouldSwapTheHandle() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            SessionRecord widened = SessionRecord.builder().sessionId(SESSION_ID).accessToken("widened-access")
                    .idToken("widened-id").sub(OLD_SUB).sid(OLD_SID).expiresAt(FUTURE).build();

            boolean reissued = store.replaceAndReissueHandle(widened, REISSUED_HANDLE);

            assertTrue(reissued, "a stored id is replaced and its handle swapped");
            assertEquals(widened, store.resolve(REISSUED_HANDLE, T0).orElseThrow(),
                    "the new handle resolves the replacement record");
            assertTrue(store.resolve(handleOf(SESSION_ID), T0).isEmpty(),
                    "the previous handle resolves nothing from the re-issue on");
            assertEquals(1, store.size(), "the re-issue replaced in place, adding no entry");
        }

        @Test
        @DisplayName("Should keep the session id and the absolute expiry across a re-issue")
        void shouldKeepIdentityAndAbsoluteExpiry() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Instant expiry = T0.plusSeconds(100);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, expiry), T0);

            store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, expiry), REISSUED_HANDLE);

            SessionRecord resolved = store.resolve(REISSUED_HANDLE, expiry.minusSeconds(1)).orElseThrow();
            assertEquals(SESSION_ID, resolved.sessionId(), "the session keeps its id");
            assertEquals(expiry, resolved.expiresAt(), "the absolute expiry is the one before the re-issue");
            assertTrue(store.resolve(REISSUED_HANDLE, expiry).isEmpty(),
                    "the session still ends at its original absolute expiry under the new handle");
        }

        @Test
        @DisplayName("Should still destroy a re-issued session through its sid")
        void shouldKeepSidIndexAcrossReissue() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), REISSUED_HANDLE);

            int destroyed = store.destroyBySid(OLD_SID);

            assertEquals(1, destroyed, "the sid index is keyed on the session id, which the re-issue kept");
            assertTrue(store.resolve(REISSUED_HANDLE, T0).isEmpty(), "the re-issued handle went with the session");
        }

        @Test
        @DisplayName("Should still destroy a re-issued session through its sub")
        void shouldKeepSubIndexAcrossReissue() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), REISSUED_HANDLE);

            int destroyed = store.destroyBySub(OLD_SUB);

            assertEquals(1, destroyed, "the sub index is keyed on the session id, which the re-issue kept");
            assertTrue(store.resolve(REISSUED_HANDLE, T0).isEmpty(), "the re-issued handle went with the session");
        }

        @Test
        @DisplayName("Should store nothing and register no handle for an id the store does not hold")
        void shouldRefuseAbsentId() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);

            boolean reissued = store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE),
                    REISSUED_HANDLE);

            assertFalse(reissued, "nothing is stored under the id, so nothing is re-issued");
            assertEquals(0, store.size(), "the re-issuing write never creates");
            assertTrue(store.resolve(REISSUED_HANDLE, T0).isEmpty(), "the refused handle resolves nothing");
            assertEquals(0, store.destroyBySid(OLD_SID), "the refused record was not indexed by sid");
            assertEquals(0, store.destroyBySub(OLD_SUB), "the refused record was not indexed by sub");
            SessionRecord other = session("other", "other-sub", null, FUTURE);
            assertDoesNotThrow(() -> store.create(other, REISSUED_HANDLE, T0),
                    "the refused handle was not registered, so another session may take it");
        }

        @ParameterizedTest(name = "{0} followed by the re-issuing write leaves the session gone")
        @MethodSource(DESTRUCTIONS)
        @DisplayName("Should not write a destroyed session back through a re-issue, however it was destroyed")
        void shouldNotRecreateDestroyedSession(String label, Consumer<InMemorySessionStore> destruction) {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            destruction.accept(store);

            boolean reissued = store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE),
                    REISSUED_HANDLE);

            assertFalse(reissued, () -> "after " + label + " there is no record to re-issue");
            assertEquals(0, store.size(), () -> label + " must not be undone by a re-issue that was in flight");
            assertTrue(store.resolve(REISSUED_HANDLE, T0).isEmpty(),
                    () -> "no handle was registered after " + label);
        }

        @Test
        @DisplayName("Should keep the re-issued handle when a replacement is written after the re-issue")
        void shouldKeepReissuedHandleOnLaterReplace() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), REISSUED_HANDLE);
            SessionRecord rotated = SessionRecord.builder().sessionId(SESSION_ID).accessToken("rotated-access")
                    .idToken("rotated-id").sub(OLD_SUB).sid(OLD_SID).expiresAt(FUTURE).build();

            boolean replaced = store.replaceIfPresent(rotated);

            assertTrue(replaced, "a refresh in flight across the re-issue still finds the session by its id");
            assertEquals(rotated, store.resolve(REISSUED_HANDLE, T0).orElseThrow(),
                    "the re-issued handle resolves the record the later replacement stored");
            assertTrue(store.resolve(handleOf(SESSION_ID), T0).isEmpty(),
                    "the later replacement did not put the previous handle back");
        }

        @ParameterizedTest(name = "{0} removes the cookie handle with the session")
        @MethodSource(DESTRUCTIONS)
        @DisplayName("Should remove the cookie handle together with the session, however it is destroyed")
        void shouldRemoveHandleWithSession(String label, Consumer<InMemorySessionStore> destruction) {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);

            destruction.accept(store);

            assertTrue(store.resolve(handleOf(SESSION_ID), T0).isEmpty(),
                    () -> "the handle resolves nothing after " + label);
            SessionRecord other = session("other", "other-sub", null, FUTURE);
            assertDoesNotThrow(() -> store.create(other, handleOf(SESSION_ID), T0),
                    () -> label + " left no handle behind, so another session may take the same value");
            assertEquals("other", store.resolve(handleOf(SESSION_ID), T0).orElseThrow().sessionId());
        }

        @Test
        @DisplayName("Should remove the handle of a session evicted on resolve and of one swept")
        void shouldRemoveHandleOnEvictionAndSweep() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            Instant expiry = T0.plusSeconds(5);
            create(store, session("evicted", "sub1", null, expiry), T0);
            create(store, session("swept", "sub2", null, expiry), T0);

            assertTrue(resolve(store, "evicted", expiry).isEmpty());
            assertEquals(1, store.sweepExpired(expiry));

            SessionRecord first = session("first", "sub3", null, FUTURE);
            SessionRecord second = session("second", "sub4", null, FUTURE);
            assertDoesNotThrow(() -> store.create(first, handleOf("evicted"), expiry),
                    "the lazy eviction dropped the handle with the session");
            assertDoesNotThrow(() -> store.create(second, handleOf("swept"), expiry),
                    "the sweep dropped the handle with the session");
        }

        @Test
        @DisplayName("Should admit a re-issue at the max-session bound without sweeping")
        void shouldReissueAtTheBound() {
            InMemorySessionStore store = new InMemorySessionStore(2, NO_IDLE_EFFECT);
            create(store, session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            create(store, session("expired", "sub2", null, T0.plusSeconds(5)), T0);

            boolean reissued = store.replaceAndReissueHandle(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE),
                    REISSUED_HANDLE);

            assertTrue(reissued, "a re-issue consumes no capacity, so the bound does not refuse it");
            assertEquals(2, store.size(),
                    "the expired neighbour still holds its slot — a re-issue never triggers the sweep");
            assertTrue(store.resolve(REISSUED_HANDLE, T0).isPresent());
        }

        @Test
        @DisplayName("Should refuse a handle that already resolves to another session, on create and on re-issue")
        void shouldRefuseHandleOfAnotherSession() {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            create(store, session("s1", "sub1", null, FUTURE), T0);
            create(store, session("s2", "sub2", null, FUTURE), T0);
            SessionRecord third = session("s3", "sub3", null, FUTURE);
            SessionRecord second = session("s2", "sub2", null, FUTURE);

            assertThrows(IllegalStateException.class, () -> store.create(third, handleOf("s1"), T0));
            assertThrows(IllegalStateException.class, () -> store.replaceAndReissueHandle(second, handleOf("s1")));

            assertEquals("s1", resolve(store, "s1", T0).orElseThrow().sessionId(),
                    "the handle still resolves to the session it belongs to");
            assertEquals("s2", resolve(store, "s2", T0).orElseThrow().sessionId(),
                    "the refused re-issue left the other session's handle in force");
        }

        @Test
        @DisplayName("Should leave neither a session nor a handle behind when a re-issue races a destroy")
        void shouldLeaveNothingBehindWhenReissueRacesDestroy() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore(16, NO_IDLE_EFFECT);
            SessionRecord contended = session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE);
            SessionRecord probe = session("probe", "probe-sub", null, FUTURE);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 500; round++) {
                    create(store, contended, T0);
                    CyclicBarrier start = new CyclicBarrier(2);
                    Future<Boolean> reissue = executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return store.replaceAndReissueHandle(contended, REISSUED_HANDLE);
                    });
                    Future<?> destroy = executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        store.destroyById(SESSION_ID);
                        return null;
                    });
                    reissue.get(5, TimeUnit.SECONDS);
                    destroy.get(5, TimeUnit.SECONDS);

                    assertEquals(0, store.size(), "the destroy wins in either order: no session is left");
                    assertTrue(store.resolve(REISSUED_HANDLE, T0).isEmpty(), "the new handle resolves nothing");
                    assertTrue(store.resolve(handleOf(SESSION_ID), T0).isEmpty(),
                            "the previous handle resolves nothing");
                    // A handle left registered without its session would refuse both of these.
                    store.create(probe, REISSUED_HANDLE, T0);
                    store.replaceAndReissueHandle(probe, handleOf(SESSION_ID));
                    store.destroyById("probe");
                }
            }
        }
    }

    /** The second deadline: a session ends when no access was recorded for the idle timeout. */
    @Nested
    @DisplayName("Idle timeout (lazy + swept)")
    class IdleTimeout {

        @Test
        @DisplayName("Should evict an idle session lazily on resolve, inclusive of the boundary")
        void shouldEvictIdleSessionOnResolve() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);
            Instant idleDeadline = T0.plus(IDLE_TIMEOUT);

            assertTrue(resolve(store, "s1", idleDeadline.minusSeconds(1)).isPresent(),
                    "a session is live up to its idle deadline");
            assertTrue(resolve(store, "s1", idleDeadline).isEmpty(),
                    "a session idle for the full timeout is refused although its absolute lifetime runs on");
            assertEquals(0, store.size(), "the idle session was evicted lazily on resolve");
        }

        @Test
        @DisplayName("Should sweep an idle session whose absolute lifetime has not lapsed")
        void shouldSweepIdleSession() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("idle", "sub1", null, FUTURE), T0);
            create(store, session("active", "sub2", null, FUTURE), T0);
            store.recordAccess("active", T0.plusSeconds(500));

            int swept = store.sweepExpired(T0.plus(IDLE_TIMEOUT));

            assertEquals(1, swept, "only the session without a recorded access is idle-expired");
            assertEquals(1, store.size());
            assertTrue(resolve(store, "active", T0.plus(IDLE_TIMEOUT)).isPresent());
        }

        @Test
        @DisplayName("Should move the idle deadline when an access is recorded")
        void shouldMoveDeadlineOnRecordedAccess() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);
            Instant access = T0.plusSeconds(300);

            store.recordAccess("s1", access);

            assertTrue(resolve(store, "s1", T0.plus(IDLE_TIMEOUT)).isPresent(),
                    "the deadline measured from creation no longer applies");
            assertTrue(resolve(store, "s1", access.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the session ends one idle timeout after the recorded access");
        }

        @Test
        @DisplayName("Should not extend the idle deadline by resolving")
        void shouldNotExtendOnResolve() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);

            assertTrue(resolve(store, "s1", T0.plus(IDLE_TIMEOUT).minusSeconds(1)).isPresent());

            assertTrue(resolve(store, "s1", T0.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the resolve a second before the deadline did not count as an access");
        }

        @Test
        @DisplayName("Should leave the stored record untouched when an access is recorded")
        void shouldNotReplaceRecordOnRecordedAccess() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);
            SessionRecord rotated = SessionRecord.builder().sessionId("s1").accessToken("rotated-access")
                    .refreshToken("rotated-refresh").idToken("rotated-id").sub("sub1").expiresAt(FUTURE).build();
            store.replaceIfPresent(rotated);

            store.recordAccess("s1", T0.plusSeconds(60));

            assertSame(rotated, resolve(store, "s1", T0.plusSeconds(60)).orElseThrow(),
                    "only the instant is written: the record a refresh stored is the one still held");
        }

        @Test
        @DisplayName("Should only move the last access forward")
        void shouldIgnoreAnEarlierAccess() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);
            Instant later = T0.plusSeconds(300);
            store.recordAccess("s1", later);

            store.recordAccess("s1", T0.plusSeconds(100));

            assertTrue(resolve(store, "s1", later.plus(IDLE_TIMEOUT).minusSeconds(1)).isPresent(),
                    "an access recorded out of order did not pull the deadline back");
        }

        @Test
        @DisplayName("Should treat a recorded access for an id the store does not hold as a no-op")
        void shouldNoOpAccessForAbsentId() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);

            store.recordAccess("absent", T0);

            assertEquals(0, store.size(), "recording an access never creates a session");
        }

        @Test
        @DisplayName("Should not extend the absolute lifetime by a recorded access")
        void shouldNotExtendAbsoluteLifetime() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            Instant expiry = T0.plusSeconds(100);
            create(store, session("s1", "sub1", null, expiry), T0);

            store.recordAccess("s1", expiry.minusSeconds(1));

            assertTrue(resolve(store, "s1", expiry).isEmpty(),
                    "the session ends at its absolute expiry however recently it was accessed");
        }

        @Test
        @DisplayName("Should carry the last access over a replacement and a re-issue — neither is an access")
        void shouldKeepLastAccessAcrossUpdatingWrites() {
            InMemorySessionStore store = new InMemorySessionStore(16, IDLE_TIMEOUT);
            create(store, session("s1", "sub1", null, FUTURE), T0);

            store.replaceIfPresent(session("s1", "sub1", null, FUTURE));
            store.replaceAndReissueHandle(session("s1", "sub1", null, FUTURE), REISSUED_HANDLE);

            assertTrue(store.resolve(REISSUED_HANDLE, T0.plus(IDLE_TIMEOUT).minusSeconds(1)).isPresent());
            assertTrue(store.resolve(REISSUED_HANDLE, T0.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the idle deadline is still measured from the creation, the last recorded access");
        }

        @Test
        @DisplayName("Should reclaim the slot of an idle session when a create reaches the bound")
        void shouldReclaimIdleCapacityOnCreate() {
            InMemorySessionStore store = new InMemorySessionStore(1, IDLE_TIMEOUT);
            create(store, session("idle", "sub1", null, FUTURE), T0);

            create(store, session("s2", "sub2", null, FUTURE), T0.plus(IDLE_TIMEOUT));

            assertEquals(1, store.size(), "the idle session released its slot to the new one");
            assertTrue(resolve(store, "s2", T0.plus(IDLE_TIMEOUT)).isPresent());
        }

        @Test
        @DisplayName("Should reject a non-positive or absent idle timeout")
        void shouldRejectNonPositiveIdleTimeout() {
            Duration negative = Duration.ofSeconds(-1);

            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(16, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(16, negative));
            assertThrows(NullPointerException.class, () -> new InMemorySessionStore(16, null));
        }
    }
}
