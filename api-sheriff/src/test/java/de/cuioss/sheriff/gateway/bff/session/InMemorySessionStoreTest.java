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
package de.cuioss.sheriff.gateway.bff.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the D3 server-side session store: {@link SessionRecord} (credential redaction, TTL,
 * normalization) and {@link InMemorySessionStore} (create/resolve/destroy, lazy + swept TTL
 * eviction, O(1) back-channel destruction by {@code sid}/{@code sub}, and the max-session bound).
 */
class InMemorySessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant FUTURE = T0.plusSeconds(3600);

    private static SessionRecord session(String sessionId, String sub, Optional<String> sid, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken("access-" + sessionId)
                .refreshToken(Optional.of("refresh-" + sessionId))
                .idToken("id-" + sessionId)
                .sub(sub)
                .sid(sid)
                .expiresAt(expiresAt)
                .acr(Optional.of("urn:acr:silver"))
                .authTime(Optional.of(T0))
                .build();
    }

    @Nested
    @DisplayName("Create / resolve / destroy")
    class Lifecycle {

        @Test
        @DisplayName("Should resolve a created session and drop it after destroy-by-id")
        void shouldCreateResolveDestroy() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", Optional.of("sid1"), FUTURE));

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
            store.create(session("s1", "sub1", Optional.empty(), T0.plusSeconds(10)));

            assertTrue(store.resolve("s1", T0.plusSeconds(11)).isEmpty(), "an expired session is refused");
            assertEquals(0, store.size(), "the expired session was evicted lazily on resolve");
        }

        @Test
        @DisplayName("Should treat the TTL boundary as expired (inclusive)")
        void shouldTreatBoundaryAsExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            Instant expiry = T0.plusSeconds(10);
            store.create(session("s1", "sub1", Optional.empty(), expiry));

            assertTrue(store.resolve("s1", expiry).isEmpty(), "expiry is inclusive of the boundary");
        }

        @Test
        @DisplayName("Should sweep every expired session and leave live ones untouched")
        void shouldSweepExpired() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("dead1", "sub1", Optional.empty(), T0.plusSeconds(5)));
            store.create(session("dead2", "sub2", Optional.empty(), T0.plusSeconds(5)));
            store.create(session("live", "sub3", Optional.empty(), FUTURE));

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
            store.create(session("s1", "sub1", Optional.of("A"), FUTURE));
            store.create(session("s2", "sub2", Optional.of("A"), FUTURE));
            store.create(session("s3", "sub3", Optional.of("B"), FUTURE));

            assertEquals(2, store.destroyBySid("A"));
            assertTrue(store.resolve("s1", T0).isEmpty());
            assertTrue(store.resolve("s2", T0).isEmpty());
            assertTrue(store.resolve("s3", T0).isPresent(), "an unrelated sid is untouched");
        }

        @Test
        @DisplayName("Should destroy every session for the given subject")
        void shouldDestroyBySub() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "user-x", Optional.empty(), FUTURE));
            store.create(session("s2", "user-x", Optional.empty(), FUTURE));
            store.create(session("s3", "user-y", Optional.empty(), FUTURE));

            assertEquals(2, store.destroyBySub("user-x"));
            assertTrue(store.resolve("s3", T0).isPresent(), "an unrelated subject is untouched");
        }

        @Test
        @DisplayName("Should report zero and clean the index when the sid is already gone")
        void shouldReturnZeroForUnknownSidAndCleanIndex() {
            InMemorySessionStore store = new InMemorySessionStore(16);
            store.create(session("s1", "sub1", Optional.of("A"), FUTURE));

            assertEquals(1, store.destroyBySid("A"));
            assertEquals(0, store.destroyBySid("A"), "the secondary index was cleaned after the destroy");
            assertEquals(0, store.destroyBySid("never-seen"));
        }
    }

    @Nested
    @DisplayName("Max-session bound")
    class Capacity {

        @Test
        @DisplayName("Should refuse a session created beyond the max-session bound")
        void shouldEnforceMaxBound() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", Optional.empty(), FUTURE));
            store.create(session("s2", "sub2", Optional.empty(), FUTURE));

            SessionRecord overflow = session("s3", "sub3", Optional.empty(), FUTURE);
            assertThrows(IllegalStateException.class, () -> store.create(overflow));
        }

        @Test
        @DisplayName("Should free capacity once expired sessions are swept")
        void shouldFreeCapacityAfterSweep() {
            InMemorySessionStore store = new InMemorySessionStore(2);
            store.create(session("s1", "sub1", Optional.empty(), T0.plusSeconds(5)));
            store.create(session("s2", "sub2", Optional.empty(), FUTURE));

            store.sweepExpired(T0.plusSeconds(10));
            store.create(session("s3", "sub3", Optional.empty(), FUTURE));

            assertEquals(2, store.size());
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
            SessionRecord record = SessionRecord.builder()
                    .sessionId("SID-SECRET")
                    .accessToken("AT-SECRET")
                    .refreshToken(Optional.of("RT-SECRET"))
                    .idToken("IT-SECRET")
                    .sub("user-123")
                    .sid(Optional.of("idp-sid"))
                    .expiresAt(FUTURE)
                    .build();

            String rendered = record.toString();

            assertFalse(rendered.contains("SID-SECRET"), "the bearer session id must be redacted");
            assertFalse(rendered.contains("AT-SECRET"), "the access token must be redacted");
            assertFalse(rendered.contains("RT-SECRET"), "the refresh token must be redacted");
            assertFalse(rendered.contains("IT-SECRET"), "the ID token must be redacted");
            assertTrue(rendered.contains("***REDACTED***"));
            assertTrue(rendered.contains("user-123"), "the subject is non-secret metadata and stays visible");
        }

        @Test
        @DisplayName("Should normalize absent optionals and reject null mandatory components")
        void shouldNormalizeAndReject() {
            SessionRecord sparse = new SessionRecord("s", "at", null, "it", "sub", null, FUTURE, null, null);
            assertTrue(sparse.refreshToken().isEmpty());
            assertTrue(sparse.sid().isEmpty());
            assertTrue(sparse.acr().isEmpty());
            assertTrue(sparse.authTime().isEmpty());

            assertThrows(NullPointerException.class,
                    () -> new SessionRecord(null, "at", Optional.empty(), "it", "sub", Optional.empty(), FUTURE,
                            Optional.empty(), Optional.empty()));
            assertThrows(NullPointerException.class,
                    () -> new SessionRecord("s", "at", Optional.empty(), "it", null, Optional.empty(), FUTURE,
                            Optional.empty(), Optional.empty()));
        }
    }
}
