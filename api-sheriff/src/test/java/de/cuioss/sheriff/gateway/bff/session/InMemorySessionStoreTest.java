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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
 */
class InMemorySessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant FUTURE = T0.plusSeconds(3600);
    private static final String SESSION_ID = "s1";
    private static final String OLD_SUB = "old-sub";
    private static final String NEW_SUB = "new-sub";
    private static final String OLD_SID = "old-sid";
    private static final String NEW_SID = "new-sid";

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
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", "sid1", FUTURE), T0);

            assertTrue(store.resolve("s1", T0).isPresent());
            store.destroyById("s1");
            assertTrue(store.resolve("s1", T0).isEmpty(), "a destroyed session no longer resolves");
        }

        @Test
        @DisplayName("Should return empty for an unknown session id")
        void shouldReturnEmptyForUnknownId() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            assertTrue(store.resolve("nope", T0).isEmpty());
        }

        @Test
        @DisplayName("Should treat destroy-by-id of an absent session as a no-op")
        void shouldNoOpDestroyAbsent() {
            InMemorySessionStore store = new InMemorySessionStore(16);
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
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", null, T0.plusSeconds(10)), T0);

            assertTrue(store.resolve("s1", T0.plusSeconds(11)).isEmpty(), "an expired session is refused");
            assertEquals(0, store.size(), "the expired session was evicted lazily on resolve");
        }

        @Test
        @DisplayName("Should treat the TTL boundary as expired (inclusive)")
        void shouldTreatBoundaryAsExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            Instant expiry = T0.plusSeconds(10);
            store.create(session("s1", "sub1", null, expiry), T0);

            assertTrue(store.resolve("s1", expiry).isEmpty(), "expiry is inclusive of the boundary");
        }

        @Test
        @DisplayName("Should sweep every expired session and leave live ones untouched")
        void shouldSweepExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("dead1", "sub1", null, T0.plusSeconds(5)), T0);
            store.create(session("dead2", "sub2", null, T0.plusSeconds(5)), T0);
            store.create(session("live", "sub3", null, FUTURE), T0);

            int swept = store.sweepExpired(T0.plusSeconds(10));

            assertEquals(2, swept, "both expired sessions were swept");
            assertEquals(1, store.size());
            assertTrue(store.resolve("live", T0.plusSeconds(10)).isPresent());
        }
    }

    @Nested
    @DisplayName("O(1) back-channel destruction")
    class BackChannel {

        @Test
        @DisplayName("Should destroy every session carrying the given sid")
        void shouldDestroyBySid() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", "A", FUTURE), T0);
            store.create(session("s2", "sub2", "A", FUTURE), T0);
            store.create(session("s3", "sub3", "B", FUTURE), T0);

            assertEquals(2, store.destroyBySid("A"));
            assertTrue(store.resolve("s1", T0).isEmpty());
            assertTrue(store.resolve("s2", T0).isEmpty());
            assertTrue(store.resolve("s3", T0).isPresent(), "an unrelated sid is untouched");
        }

        @Test
        @DisplayName("Should destroy every session for the given subject")
        void shouldDestroyBySub() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "user-x", null, FUTURE), T0);
            store.create(session("s2", "user-x", null, FUTURE), T0);
            store.create(session("s3", "user-y", null, FUTURE), T0);

            assertEquals(2, store.destroyBySub("user-x"));
            assertTrue(store.resolve("s3", T0).isPresent(), "an unrelated subject is untouched");
        }

        @Test
        @DisplayName("Should report zero and clean the index when the sid is already gone")
        void shouldReturnZeroForUnknownSidAndCleanIndex() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", "A", FUTURE), T0);

            assertEquals(1, store.destroyBySid("A"));
            assertEquals(0, store.destroyBySid("A"), "the secondary index was cleaned after the destroy");
            assertEquals(0, store.destroyBySid("never-seen"));
        }

        @Test
        @DisplayName("Should drop the previous subject's index entry when an upsert changes sub")
        void shouldRepairSubIndexOnUpsert() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "old-sub", null, FUTURE), T0);

            store.create(session("s1", "new-sub", null, FUTURE), T0);

            assertEquals(0, store.destroyBySub("old-sub"),
                    "the replaced record's subject must not keep resolving to the session id — otherwise a"
                            + " back-channel logout for the old subject destroys the replacement");
            assertTrue(store.resolve("s1", T0).isPresent(), "the replacement survived the stale-subject destroy");
            assertEquals(1, store.destroyBySub("new-sub"), "the replacement is reachable under its own subject");
        }

        @Test
        @DisplayName("Should drop the previous sid's index entry when an upsert changes sid")
        void shouldRepairSidIndexOnUpsert() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", "old-sid", FUTURE), T0);

            store.create(session("s1", "sub1", "new-sid", FUTURE), T0);

            assertEquals(0, store.destroyBySid("old-sid"),
                    "a create replacing a stored id must not leave the old sid destroying the new record");
            assertTrue(store.resolve("s1", T0).isPresent(), "the replacement survived the stale-sid destroy");
            assertEquals(1, store.destroyBySid("new-sid"), "the replacement is reachable under its own sid");
        }
    }

    @Nested
    @DisplayName("Max-session bound")
    class Capacity {

        @Test
        @DisplayName("Should free capacity once expired sessions are swept")
        void shouldFreeCapacityAfterSweep() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", null, T0.plusSeconds(5)), T0);
            store.create(session("s2", "sub2", null, FUTURE), T0);

            store.sweepExpired(T0.plusSeconds(10));
            store.create(session("s3", "sub3", null, FUTURE), T0);

            assertEquals(2, store.size());
        }

        @Test
        @DisplayName("Should reclaim an expired session's slot when a create reaches the bound")
        void shouldReclaimExpiredCapacityOnCreate() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", null, T0.plusSeconds(5)), T0);
            store.create(session("s2", "sub2", null, T0.plusSeconds(5)), T0);

            // Driven through create() alone — sweepExpired is deliberately never called by hand here.
            // Calling it would merely re-test "Should free capacity once expired sessions are swept"
            // above; what this case exists to prove is that reaching the bound is itself the trigger.
            store.create(session("s3", "sub3", null, FUTURE), T0.plusSeconds(10));

            assertEquals(1, store.size(), "both expired sessions released their slots, leaving only the new one");
            assertTrue(store.resolve("s3", T0.plusSeconds(10)).isPresent(), "the reclaiming create was admitted");
        }

        @Test
        @DisplayName("Should still refuse a create at the bound when every stored session is live")
        void shouldRefuseWhenBoundIsFullOfLiveSessions() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", null, FUTURE), T0);
            store.create(session("s2", "sub2", null, FUTURE), T0);

            SessionRecord overflow = session("s3", "sub3", null, FUTURE);
            Instant afterExpiry = T0.plusSeconds(10);
            assertThrows(IllegalStateException.class, () -> store.create(overflow, afterExpiry),
                    "the at-capacity sweep reclaims nothing while every session is live, so the DoS"
                            + " guard must still refuse — reclamation may not become a way around the bound");
            assertEquals(2, store.size(), "the refused create left the store untouched");
        }

        @Test
        @DisplayName("Should admit an upsert of an already-stored session id at the bound")
        void shouldAdmitUpsertAtCapacity() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", null, FUTURE), T0);
            store.create(session("s2", "sub2", null, FUTURE), T0);

            assertDoesNotThrow(() -> store.create(session("s1", "sub1", null, FUTURE), T0),
                    "a create naming an already-stored id replaces a record already counted against the"
                            + " bound, so it consumes no new capacity");
            assertEquals(2, store.size(), "the upsert replaced in place rather than adding an entry");
        }

        @Test
        @DisplayName("Should reject a non-positive capacity bound")
        void shouldRejectNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(0));
            assertThrows(IllegalArgumentException.class, () -> new InMemorySessionStore(-1));
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
            InMemorySessionStore store = new InMemorySessionStore(16);
            Set<String> scopes = Set.of("openid", "profile", "email", "orders:read");
            SessionRecord scoped = SessionRecord.builder()
                    .sessionId("s1").accessToken("at").idToken("it").sub("sub1").expiresAt(FUTURE)
                    .activeScopes(scopes).build();

            store.create(scoped, T0);

            assertEquals(scopes, store.resolve("s1", T0).orElseThrow().activeScopes());
        }

        @Test
        @DisplayName("Should resolve the replaced active scope set after a replacement")
        void shouldReplaceActiveScopesOnReplacement() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(SessionRecord.builder().sessionId("s1").accessToken("at").idToken("it").sub("sub1")
                    .expiresAt(FUTURE).activeScopes(Set.of("openid", "orders:read")).build(), T0);

            boolean replaced = store.replaceIfPresent(SessionRecord.builder().sessionId("s1").accessToken("at2")
                    .idToken("it").sub("sub1").expiresAt(FUTURE).activeScopes(Set.of("openid")).build());

            assertTrue(replaced, "the session is stored, so the refresh's write replaces it");
            assertEquals(Set.of("openid"), store.resolve("s1", T0).orElseThrow().activeScopes(),
                    "a refresh persisted through a replacement carries the narrowed set to the next request");
        }

        @Test
        @DisplayName("Should resolve the granted scope set a stored session was created with, and its replacement")
        void shouldKeepGrantedScopesAcrossStore() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            Set<String> granted = Set.of("openid", "orders:read");
            Set<String> widened = Set.of("openid", "orders:read", "orders:write");
            store.create(SessionRecord.builder().sessionId("s1").accessToken("at").idToken("it").sub("sub1")
                    .expiresAt(FUTURE).activeScopes(granted).grantedScopes(granted).build(), T0);
            SessionRecord created = store.resolve("s1", T0).orElseThrow();

            boolean replaced = store.replaceIfPresent(SessionRecord.builder().sessionId("s1").accessToken("at2")
                    .idToken("it").sub("sub1").expiresAt(FUTURE).activeScopes(widened).grantedScopes(widened)
                    .build());

            assertTrue(replaced, "the session is stored, so the widening's write replaces it");
            assertEquals(granted, created.grantedScopes());
            assertEquals(widened, store.resolve("s1", T0).orElseThrow().grantedScopes(),
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
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            SessionRecord replacement = SessionRecord.builder().sessionId(SESSION_ID).accessToken("rotated-access")
                    .idToken("rotated-id").sub(OLD_SUB).sid(OLD_SID).expiresAt(FUTURE).build();

            boolean replaced = store.replaceIfPresent(replacement);

            assertTrue(replaced, "a stored id is replaced");
            assertEquals(1, store.size(), "the replacement took the stored record's place, adding no entry");
            assertEquals(replacement, store.resolve(SESSION_ID, T0).orElseThrow(),
                    "the next resolve sees the replacement");
        }

        @Test
        @DisplayName("Should refuse an id the store does not hold, report it and leave the store empty")
        void shouldRefuseAbsentId() {
            InMemorySessionStore store = new InMemorySessionStore(16);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE));

            assertFalse(replaced, "nothing is stored under the id, so nothing is replaced");
            assertEquals(0, store.size(), "the conditional write never creates");
            assertTrue(store.resolve(SESSION_ID, T0).isEmpty(), "no session resolves afterwards");
            assertEquals(0, store.destroyBySid(OLD_SID), "the refused record was not indexed by sid");
            assertEquals(0, store.destroyBySub(OLD_SUB), "the refused record was not indexed by sub");
        }

        @Test
        @DisplayName("Should find a replacement carrying another sid and sub under the new keys only")
        void shouldReindexWhenSidAndSubChange() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            assertTrue(replaced);
            assertEquals(0, store.destroyBySid(OLD_SID), "the previous sid no longer resolves to the session");
            assertEquals(0, store.destroyBySub(OLD_SUB), "the previous sub no longer resolves to the session");
            assertTrue(store.resolve(SESSION_ID, T0).isPresent(),
                    "a destroy on the previous keys left the replacement in place");
            assertEquals(1, store.destroyBySid(NEW_SID), "the new sid destroys the session");
            assertTrue(store.resolve(SESSION_ID, T0).isEmpty());
        }

        @Test
        @DisplayName("Should destroy a replaced session through its new sub")
        void shouldDestroyReplacedSessionByNewSub() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            int destroyed = store.destroyBySub(NEW_SUB);

            assertEquals(1, destroyed, "the new sub destroys the session");
            assertTrue(store.resolve(SESSION_ID, T0).isEmpty());
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
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            destruction.accept(store);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE));

            assertFalse(replaced, () -> "after " + label + " there is no record to replace");
            assertEquals(0, store.size(), () -> label + " must not be undone by a write that was in flight");
            assertTrue(store.resolve(SESSION_ID, T0).isEmpty(), () -> "the session stays gone after " + label);
        }

        @Test
        @DisplayName("Should replace at the max-session bound without sweeping and without refusing")
        void shouldReplaceAtTheBound() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, FUTURE), T0);
            store.create(session("expired", "sub2", null, T0.plusSeconds(5)), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, NEW_SUB, NEW_SID, FUTURE));

            assertTrue(replaced, "a replacement consumes no capacity, so the bound does not refuse it");
            assertEquals(2, store.size(),
                    "the expired neighbour still holds its slot — a replacement never triggers the sweep");
        }

        @Test
        @DisplayName("Should replace a lapsed record nothing has evicted yet, and keep refusing it on resolve")
        void shouldReplaceLapsedRecordWithoutRevivingIt() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            Instant expiry = T0.plusSeconds(5);
            store.create(session(SESSION_ID, OLD_SUB, OLD_SID, expiry), T0);

            boolean replaced = store.replaceIfPresent(session(SESSION_ID, OLD_SUB, OLD_SID, expiry));

            assertTrue(replaced, "the check is on presence, not on liveness");
            assertTrue(store.resolve(SESSION_ID, T0.plusSeconds(10)).isEmpty(),
                    "the absolute TTL is still enforced on resolve against the expiry the replacement carries");
        }

        @Test
        @DisplayName("Should reject a null replacement")
        void shouldRejectNullReplacement() {
            InMemorySessionStore store = new InMemorySessionStore(16);

            assertThrows(NullPointerException.class, () -> store.replaceIfPresent(null));
        }
    }
}
