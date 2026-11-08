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
package de.cuioss.sheriff.gateway.bff.pending;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.IntStream;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the D2b pending-authorization primitives: the bounded single-use
 * {@link InMemoryPendingAuthorizationStore} and its fair admission (expired records purged first, a
 * per-source cap with own-oldest eviction, refusal instead of foreign eviction at saturation), the
 * {@link PendingAuthorizationRecord} contract (single-use enforcement, TTL expiry, same-origin
 * return-URL validation, the login-versus-widening marker, the quota key), and the
 * security-critical cross-browser callback rejection (a valid {@code state} presented in a
 * browser without the matching binding cookie is refused).
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
class PendingAuthorizationStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String SOURCE_A = "192.0.2.10";
    private static final String SOURCE_B = "198.51.100.20";
    private static final String SOURCE_C = "203.0.113.30";
    private static final int PER_SOURCE = InMemoryPendingAuthorizationStore.MAX_PENDING_PER_SOURCE;

    private static FlowContext flow() {
        return FlowContext.create(GATEWAY_ORIGIN + "/callback");
    }

    private static final List<String> REQUESTED_SCOPES = List.of("openid", "profile", "email", "orders:read");

    private static PendingAuthorizationRecord pendingRecord(String returnUrl, Instant createdAt) {
        return PendingAuthorizationRecord.create(flow(), returnUrl, REQUESTED_SCOPES, SOURCE_A, createdAt);
    }

    private static PendingAuthorizationRecord pendingFrom(String quotaKey, Instant createdAt) {
        return PendingAuthorizationRecord.create(flow(), "/app", REQUESTED_SCOPES, quotaKey, createdAt);
    }

    /** Stores {@code count} records of {@code quotaKey} created and stored at {@code now}, oldest first. */
    private static List<PendingAuthorizationRecord> storeFrom(InMemoryPendingAuthorizationStore store,
            String quotaKey, int count, Instant now) {
        List<PendingAuthorizationRecord> stored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PendingAuthorizationRecord pending = pendingFrom(quotaKey, now);
            store.store(pending, now);
            stored.add(pending);
        }
        return stored;
    }

    @Nested
    @DisplayName("Bounded single-use in-memory store")
    class InMemoryStore {

        @Test
        @DisplayName("Should resolve a stored record exactly once (single-use consumption)")
        void shouldConsumeStoredRecordExactlyOnce() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            Optional<PendingAuthorizationRecord> first = store.consume(stored.id(), T0);
            Optional<PendingAuthorizationRecord> second = store.consume(stored.id(), T0);

            assertTrue(first.isPresent(), "the first consumption resolves the record");
            assertEquals(stored.id(), first.get().id());
            assertTrue(second.isEmpty(), "single-use: the second consumption is refused");
        }

        @Test
        @DisplayName("Should refuse a record consumed after its TTL has elapsed")
        void shouldRefuseExpiredRecord() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            Instant afterTtl = T0.plus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);
            assertTrue(store.consume(stored.id(), afterTtl).isEmpty(), "an expired record is refused");
        }

        @Test
        @DisplayName("Should resolve a record consumed within its TTL")
        void shouldResolveRecordWithinTtl() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            assertTrue(store.consume(stored.id(), T0.plusSeconds(1)).isPresent());
        }

        @Test
        @DisplayName("Should reject a null record or a null reference instant")
        void shouldRejectNullStoreArguments() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(4);
            PendingAuthorizationRecord pending = pendingRecord("/app", T0);

            assertThrows(NullPointerException.class, () -> store.store(null, T0));
            assertThrows(NullPointerException.class, () -> store.store(pending, null));
            assertEquals(0, store.size(), "a rejected call stores nothing");
        }

        @Test
        @DisplayName("Should return empty for an unknown record id")
        void shouldReturnEmptyForUnknownId() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(4);
            assertTrue(store.consume("nonexistent", T0).isEmpty());
        }

        @Test
        @DisplayName("Should reject a non-positive capacity bound")
        void shouldRejectNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new InMemoryPendingAuthorizationStore(0));
            assertThrows(IllegalArgumentException.class, () -> new InMemoryPendingAuthorizationStore(-1));
        }
    }

    @Nested
    @DisplayName("Fair admission — one source's flood never displaces another source's live record")
    class FairAdmission {

        @Test
        @DisplayName("Should keep source B's live record through a source-A flood beyond the global bound")
        void shouldKeepOtherSourceRecordThroughFlood() {
            int capacity = PER_SOURCE + 44;
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(capacity);
            PendingAuthorizationRecord victim = pendingFrom(SOURCE_B, T0);
            store.store(victim, T0);

            List<PendingAuthorizationRecord> flood = storeFrom(store, SOURCE_A, capacity * 3, T0);

            assertEquals(PER_SOURCE + 1, store.size(),
                    "the flooding source holds only its own share; the store never grows past it");
            assertTrue(store.consume(victim.id(), T0).isPresent(),
                    "the other source's live record survives a flood three times the global bound");
            assertTrue(store.consume(flood.getLast().id(), T0).isPresent(), "the newest flood record is kept");
        }

        @Test
        @DisplayName("Should purge every expired record before admitting a new one")
        void shouldPurgeExpiredRecordsFirst() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(3);
            // The two older records are still live when the third is stored and expired by T0.
            Instant longAgo = T0.minus(PendingAuthorizationRecord.FIXED_TTL).minusSeconds(10);
            Instant recently = T0.minusSeconds(20);
            PendingAuthorizationRecord expiredA = pendingFrom(SOURCE_A, longAgo);
            PendingAuthorizationRecord expiredB = pendingFrom(SOURCE_B, longAgo);
            PendingAuthorizationRecord live = pendingFrom(SOURCE_C, recently);
            store.store(expiredA, longAgo);
            store.store(expiredB, longAgo);
            store.store(live, recently);
            PendingAuthorizationRecord incoming = pendingFrom(SOURCE_A, T0);

            store.store(incoming, T0);

            assertEquals(2, store.size(), "both expired records are gone, not only the one a single slot needs");
            assertTrue(store.consume(live.id(), T0).isPresent(), "the live record is never evicted");
            assertTrue(store.consume(incoming.id(), T0).isPresent());
        }

        @Test
        @DisplayName("Should purge an expired record stored behind a younger live one")
        void shouldPurgeExpiredRecordBehindYoungerLiveOne() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(2);
            // createdAt is stamped before the push and the record is stored after it, so a login started
            // earlier can be stored later: insertion order is not createdAt order.
            Instant startedLater = T0.plusSeconds(60);
            PendingAuthorizationRecord younger = pendingFrom(SOURCE_A, startedLater);
            PendingAuthorizationRecord older = pendingFrom(SOURCE_B, T0);
            store.store(younger, startedLater);
            store.store(older, startedLater);
            Instant olderExpired = older.expiresAt();
            PendingAuthorizationRecord incoming = pendingFrom(SOURCE_C, olderExpired);

            assertDoesNotThrow(() -> store.store(incoming, olderExpired),
                    "the expired record frees its place although a live record was stored before it");

            assertEquals(2, store.size(), "the expired record was purged and the net-new record admitted");
            assertTrue(store.consume(older.id(), olderExpired).isEmpty(), "the expired record is gone");
            assertTrue(store.consume(younger.id(), olderExpired).isPresent(), "the live record is never evicted");
            assertTrue(store.consume(incoming.id(), olderExpired).isPresent());
        }

        @Test
        @DisplayName("Should keep every index consistent when a record id is stored again")
        void shouldStayConsistentWhenIdIsStoredAgain() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(10_000);
            PendingAuthorizationRecord earlier = pendingFrom(SOURCE_A, T0);
            Instant restoredAt = T0.plusSeconds(60);
            PendingAuthorizationRecord reused = PendingAuthorizationRecord.builder()
                    .id(earlier.id())
                    .flowContext(flow())
                    .returnUrl("/app")
                    .requestedScopes(Set.copyOf(REQUESTED_SCOPES))
                    .createdAt(restoredAt)
                    .ttl(PendingAuthorizationRecord.FIXED_TTL)
                    .quotaKey(SOURCE_B)
                    .build();
            store.store(earlier, T0);
            store.store(reused, restoredAt);
            Instant earlierExpired = earlier.expiresAt();
            PendingAuthorizationRecord incoming = pendingFrom(SOURCE_C, earlierExpired);

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> store.store(incoming, earlierExpired),
                    "the replaced record's expiry entry must not stall the purge of the next store call");
            List<PendingAuthorizationRecord> fromA = storeFrom(store, SOURCE_A, PER_SOURCE, earlierExpired);

            assertEquals(PER_SOURCE + 2, store.size(),
                    "the re-stored id is held once, and source A holds exactly its quota beside it");
            assertTrue(store.consume(reused.id(), earlierExpired).isPresent(),
                    "the live re-stored record survives both the purge and source A filling its quota");
            assertTrue(store.consume(fromA.getFirst().id(), earlierExpired).isPresent(),
                    "the replaced record no longer counts against source A, so A evicted none of its own");
            assertTrue(store.consume(incoming.id(), earlierExpired).isPresent());
        }

        @Test
        @DisplayName("Should refuse a net-new record at capacity and leave every live record in place")
        void shouldRefuseAtSaturation() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(3);
            PendingAuthorizationRecord fromA = pendingFrom(SOURCE_A, T0);
            PendingAuthorizationRecord fromB = pendingFrom(SOURCE_B, T0);
            PendingAuthorizationRecord fromC = pendingFrom(SOURCE_C, T0);
            store.store(fromA, T0);
            store.store(fromB, T0);
            store.store(fromC, T0);
            PendingAuthorizationRecord refused = pendingFrom("2001:db8::", T0);

            GatewayException thrown = assertThrows(GatewayException.class, () -> store.store(refused, T0));

            assertEquals(EventType.PENDING_AUTHORIZATION_SATURATED, thrown.getEventType());
            assertEquals(503, thrown.getEventType().httpStatus());
            assertEquals(3, store.size(), "nothing was evicted to make room");
            assertAll("every live record survives the refusal",
                    () -> assertTrue(store.consume(fromA.id(), T0).isPresent()),
                    () -> assertTrue(store.consume(fromB.id(), T0).isPresent()),
                    () -> assertTrue(store.consume(fromC.id(), T0).isPresent()),
                    () -> assertTrue(store.consume(refused.id(), T0).isEmpty(), "the refused record is not stored"));
        }

        @Test
        @DisplayName("Should let a source over its quota evict only its own oldest record")
        void shouldEvictOwnOldestOverQuota() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(10_000);
            PendingAuthorizationRecord other = pendingFrom(SOURCE_B, T0);
            store.store(other, T0);
            List<PendingAuthorizationRecord> own = storeFrom(store, SOURCE_A, PER_SOURCE, T0);
            PendingAuthorizationRecord overQuota = pendingFrom(SOURCE_A, T0);

            store.store(overQuota, T0);

            assertEquals(PER_SOURCE + 1, store.size(), "the over-quota record caused no net growth");
            assertTrue(store.consume(own.getFirst().id(), T0).isEmpty(), "the source's own oldest record was evicted");
            assertTrue(store.consume(own.get(1).id(), T0).isPresent(), "only the single oldest record was evicted");
            assertTrue(store.consume(overQuota.id(), T0).isPresent());
            assertTrue(store.consume(other.id(), T0).isPresent(), "the other source's record is untouched");
        }

        @Test
        @DisplayName("Should let a source at its quota replace its own oldest record while the store is saturated")
        void shouldReplaceOwnOldestAtSaturation() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(PER_SOURCE + 1);
            PendingAuthorizationRecord other = pendingFrom(SOURCE_B, T0);
            store.store(other, T0);
            List<PendingAuthorizationRecord> own = storeFrom(store, SOURCE_A, PER_SOURCE, T0);
            PendingAuthorizationRecord replacing = pendingFrom(SOURCE_A, T0);

            assertDoesNotThrow(() -> store.store(replacing, T0),
                    "a source at its quota displaces its own record, so saturation does not refuse it");

            assertEquals(PER_SOURCE + 1, store.size());
            assertTrue(store.consume(other.id(), T0).isPresent(), "the other source's record is not evicted");
            assertTrue(store.consume(own.getFirst().id(), T0).isEmpty(), "the source's own oldest made room");
            assertTrue(store.consume(replacing.id(), T0).isPresent());
        }

        @Test
        @DisplayName("Should release a source's slot when one of its records is consumed")
        void shouldReleaseSlotOnConsume() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(PER_SOURCE + 1);
            PendingAuthorizationRecord other = pendingFrom(SOURCE_B, T0);
            store.store(other, T0);
            List<PendingAuthorizationRecord> own = storeFrom(store, SOURCE_A, PER_SOURCE, T0);
            assertTrue(store.consume(own.get(PER_SOURCE / 2).id(), T0).isPresent(), "precondition: one slot freed");
            PendingAuthorizationRecord intoFreedSlot = pendingFrom(SOURCE_A, T0);
            PendingAuthorizationRecord overQuota = pendingFrom(SOURCE_A, T0);

            store.store(intoFreedSlot, T0);
            boolean oldestKeptAfterFreedSlot = store.size() == PER_SOURCE + 1;
            store.store(overQuota, T0);

            assertTrue(oldestKeptAfterFreedSlot, "the record filling the freed slot evicted nothing");
            assertTrue(store.consume(other.id(), T0).isPresent(),
                    "with the slot released the source evicts its own oldest, never the other source's record");
            assertTrue(store.consume(own.getFirst().id(), T0).isEmpty(),
                    "the second record over the freed slot displaced the source's own oldest");
            assertTrue(store.consume(own.get(1).id(), T0).isPresent(), "only one own record was displaced");
            assertTrue(store.consume(intoFreedSlot.id(), T0).isPresent());
            assertTrue(store.consume(overQuota.id(), T0).isPresent());
        }

        @Test
        @DisplayName("Should keep a refused source free to store again once a record frees up")
        void shouldAdmitAgainAfterConsume() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(1);
            PendingAuthorizationRecord first = pendingFrom(SOURCE_A, T0);
            store.store(first, T0);
            PendingAuthorizationRecord refused = pendingFrom(SOURCE_B, T0);
            GatewayException thrown = assertThrows(GatewayException.class, () -> store.store(refused, T0));
            assertEquals(EventType.PENDING_AUTHORIZATION_SATURATED, thrown.getEventType(),
                    "precondition: the store is saturated");
            store.consume(first.id(), T0);
            PendingAuthorizationRecord retried = pendingFrom(SOURCE_B, T0);

            store.store(retried, T0);

            assertTrue(store.consume(retried.id(), T0).isPresent(), "the retry is admitted into the freed capacity");
        }
    }

    @Nested
    @DisplayName("Saturation logging — one WARN per episode, no request data")
    class SaturationLogging {

        private List<LogRecord> saturationWarnings() {
            String identifier = BffLogMessages.WARN.PENDING_AUTHORIZATION_STORE_SATURATED.resolveIdentifierString();
            return SheriffDebugCapture.capturedRecords().stream()
                    .filter(captured -> Level.WARNING.equals(captured.getLevel()))
                    .filter(captured -> SheriffDebugCapture.rendered(captured).contains(identifier))
                    .toList();
        }

        private List<LogRecord> storeRecords() {
            return SheriffDebugCapture.capturedRecords().stream()
                    .filter(captured -> InMemoryPendingAuthorizationStore.class.getName()
                            .equals(captured.getLoggerName()))
                    .toList();
        }

        @Test
        @DisplayName("Should log the WARN once per saturation episode and again for the next episode")
        void shouldLogOncePerEpisode() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(1);
            PendingAuthorizationRecord held = pendingFrom(SOURCE_A, T0);
            store.store(held, T0);

            IntStream.range(0, 3).forEach(attempt -> assertThrows(GatewayException.class,
                    () -> store.store(pendingFrom(SOURCE_B, T0), T0)));
            int firstEpisode = saturationWarnings().size();
            long debugRefusals = storeRecords().stream().filter(captured -> Level.FINE.equals(captured.getLevel()))
                    .count();
            store.consume(held.id(), T0);
            store.store(pendingFrom(SOURCE_C, T0), T0);
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_B, T0), T0));

            assertEquals(1, firstEpisode, "three refusals of one episode log the WARN once");
            assertEquals(2, debugRefusals, "the further refusals of the episode stay at DEBUG");
            assertEquals(2, saturationWarnings().size(),
                    "draining below half the capacity ends the episode, so the next saturation warns again");
        }

        @Test
        @DisplayName("Should re-arm the WARN only after the store drained below half its capacity")
        void shouldReArmOnlyBelowHalfCapacity() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(4);
            List<PendingAuthorizationRecord> held = new ArrayList<>(storeFrom(store, SOURCE_A, 4, T0));
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_B, T0), T0));
            int afterFirstRefusal = saturationWarnings().size();

            store.consume(held.removeFirst().id(), T0);
            held.addAll(storeFrom(store, SOURCE_A, 1, T0));
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_B, T0), T0));
            int afterOneFreedSlot = saturationWarnings().size();

            store.consume(held.removeFirst().id(), T0);
            store.consume(held.removeFirst().id(), T0);
            held.addAll(storeFrom(store, SOURCE_A, 2, T0));
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_B, T0), T0));
            int afterDrainToHalf = saturationWarnings().size();

            store.consume(held.removeFirst().id(), T0);
            store.consume(held.removeFirst().id(), T0);
            store.consume(held.removeFirst().id(), T0);
            storeFrom(store, SOURCE_A, 3, T0);
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_B, T0), T0));
            int afterDrainBelowHalf = saturationWarnings().size();

            assertAll("the latch re-arms by hysteresis, not on a single freed slot",
                    () -> assertEquals(1, afterFirstRefusal, "the first refusal of the episode warns"),
                    () -> assertEquals(1, afterOneFreedSlot,
                            "a freed slot refilled by one insert is the same episode, so no second WARN"),
                    () -> assertEquals(1, afterDrainToHalf, "a store drained to exactly half is still the same episode"),
                    () -> assertEquals(2, afterDrainBelowHalf,
                            "a store drained below half its capacity ends the episode, so the next saturation warns"));
        }

        @Test
        @DisplayName("Should log neither the quota key, the state, the nonce nor the record id")
        void shouldLogNoRequestData() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(1);
            PendingAuthorizationRecord held = pendingFrom(SOURCE_A, T0);
            store.store(held, T0);
            PendingAuthorizationRecord refused = pendingFrom(SOURCE_B, T0);

            assertThrows(GatewayException.class, () -> store.store(refused, T0));
            assertThrows(GatewayException.class, () -> store.store(pendingFrom(SOURCE_C, T0), T0));

            assertEquals(1, saturationWarnings().size(), "precondition: the saturation WARN was logged");
            SheriffDebugCapture.assertDebugIsCaptured(InMemoryPendingAuthorizationStore.class);
            List<String> lines = storeRecords().stream().map(SheriffDebugCapture::rendered).toList();
            Map<String, String> requestData = Map.of(
                    "refused quota key", SOURCE_B,
                    "held quota key", SOURCE_A,
                    "later quota key", SOURCE_C,
                    "state", refused.flowContext().state(),
                    "nonce", refused.flowContext().nonce(),
                    "refused record id", refused.id(),
                    "held record id", held.id());
            assertAll("no request data in a store log record of any level", requestData.entrySet().stream()
                    .map(datum -> (Executable) () -> assertEquals(0,
                            lines.stream().filter(line -> line.contains(datum.getValue())).count(),
                            "log records carrying the " + datum.getKey())));
        }
    }

    @Nested
    @DisplayName("Cross-browser callback rejection (binding cookie required)")
    class CrossBrowserRejection {

        @Test
        @DisplayName("Should refuse a callback that carries no binding cookie even with a stored record")
        void shouldRefuseCallbackWithoutBindingCookie() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(8);
            BindingCookieCodec codec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            // A different browser presents a valid 'state' but carries no binding cookie.
            Optional<String> recordId = codec.readRecordId(null);
            assertTrue(recordId.isEmpty(), "no binding cookie -> no record id -> the callback resolves no record");

            // The record is untouched — only the originating browser can consume it.
            assertTrue(store.consume(stored.id(), T0).isPresent(),
                    "the cookie-less cross-browser callback never consumed the record");
        }

        @Test
        @DisplayName("Should resolve the record for the originating browser that carries the binding cookie")
        void shouldResolveForOriginatingBrowser() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(8);
            BindingCookieCodec codec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            String cookieHeader = codec.toSetCookieHeader(stored.id()).split(";", 2)[0];
            Optional<String> recordId = codec.readRecordId(cookieHeader);

            assertTrue(recordId.isPresent());
            assertTrue(store.consume(recordId.get(), T0).isPresent(),
                    "the originating browser's binding cookie resolves the record");
        }
    }

    @Nested
    @DisplayName("Record contract")
    class RecordContract {

        @Test
        @DisplayName("Should generate a distinct, non-blank unguessable id per record")
        void shouldGenerateDistinctIds() {
            assertNotEquals(PendingAuthorizationRecord.newId(), PendingAuthorizationRecord.newId());
            assertFalse(PendingAuthorizationRecord.newId().isBlank());
        }

        @Test
        @DisplayName("Should wrap the engine FlowContext and expose the gateway fields")
        void shouldWrapFlowContextAndGatewayFields() {
            FlowContext flow = flow();
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, "/dashboard",
                    REQUESTED_SCOPES, SOURCE_B, T0);

            assertSame(flow, pending.flowContext(), "the record wraps the engine DTO, never re-invents it");
            assertEquals("/dashboard", pending.returnUrl());
            assertEquals(Set.copyOf(REQUESTED_SCOPES), pending.requestedScopes());
            assertEquals(SOURCE_B, pending.quotaKey(), "the record carries the requester's quota key");
            assertEquals(T0, pending.createdAt());
            assertEquals(PendingAuthorizationRecord.FIXED_TTL, pending.ttl());
            assertEquals(T0.plus(PendingAuthorizationRecord.FIXED_TTL), pending.expiresAt());
        }

        @Test
        @DisplayName("Should retain the requested scope set through the store")
        void shouldRetainRequestedScopesThroughStore() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(4);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored, T0);

            PendingAuthorizationRecord consumed = store.consume(stored.id(), T0).orElseThrow();

            assertEquals(Set.copyOf(REQUESTED_SCOPES), consumed.requestedScopes(),
                    "the callback reads the requested set back as its fallback for the session's active scopes");
        }

        @Test
        @DisplayName("Should collapse duplicated requested scopes and hold them immutably")
        void shouldNormalizeRequestedScopes() {
            List<String> requested = new ArrayList<>(List.of("openid", "orders:read", "openid"));
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow(), "/app", requested, SOURCE_A,
                    T0);
            requested.add("profile");

            assertEquals(Set.of("openid", "orders:read"), pending.requestedScopes(),
                    "duplicates collapse and later changes to the caller's collection do not leak in");
            Set<String> held = pending.requestedScopes();
            assertThrows(UnsupportedOperationException.class, () -> held.add("email"));
        }

        @Test
        @DisplayName("Should accept an empty requested scope set")
        void shouldAcceptEmptyRequestedScopes() {
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow(), "/app", List.of(), SOURCE_A,
                    T0);

            assertTrue(pending.requestedScopes().isEmpty());
        }

        @Test
        @DisplayName("Should treat the TTL boundary as expired (inclusive)")
        void shouldTreatTtlBoundaryAsExpired() {
            PendingAuthorizationRecord pending = pendingRecord("/app", T0);
            assertFalse(pending.isExpired(pending.expiresAt().minusNanos(1)));
            assertTrue(pending.isExpired(pending.expiresAt()), "expiry is inclusive of the boundary");
        }

        @Test
        @DisplayName("Should reject a null mandatory component")
        void shouldRejectNullComponents() {
            Duration ttl = Duration.ofMinutes(1);
            FlowContext flow = flow();
            Set<String> scopes = Set.of("openid");
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord(null, flow, "/a", scopes, T0, ttl, SOURCE_A, null));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", null, "/a", scopes, T0, ttl, SOURCE_A, null));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, null, scopes, T0, ttl, SOURCE_A, null));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, "/a", null, T0, ttl, SOURCE_A, null),
                    "the requested scope set is mandatory — the callback falls back to it");
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, "/a", scopes, null, ttl, SOURCE_A, null));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, "/a", scopes, T0, null, SOURCE_A, null));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, "/a", scopes, T0, ttl, null, null),
                    "the quota key is mandatory — the store counts every record under one");
            assertThrows(NullPointerException.class,
                    () -> PendingAuthorizationRecord.create(flow, "/a", null, SOURCE_A, T0));
            assertThrows(NullPointerException.class,
                    () -> PendingAuthorizationRecord.create(flow, "/a", scopes, null, T0));
        }

        @Test
        @DisplayName("Should accept an absent widening marker — a plain login record")
        void shouldAcceptAbsentWidening() {
            PendingAuthorizationRecord pending = new PendingAuthorizationRecord("id", flow(), "/a", Set.of("openid"),
                    T0, PendingAuthorizationRecord.FIXED_TTL, SOURCE_A, null);

            assertNull(pending.widening());
        }

        @Test
        @DisplayName("Should create a plain login record without a widening marker")
        void shouldCreateLoginWithoutWidening() {
            assertNull(pendingRecord("/app", T0).widening(), "create(...) always yields a plain login");
        }

        @Test
        @DisplayName("Should render neither the id, the quota key nor the flow secrets in toString")
        void shouldRedactToString() {
            FlowContext flow = flow();
            PendingAuthorizationRecord login = PendingAuthorizationRecord.create(flow, "/app", REQUESTED_SCOPES,
                    SOURCE_B, T0);
            PendingAuthorizationRecord widening = PendingAuthorizationRecord.createWidening(flow(), "/orders",
                    REQUESTED_SCOPES, "user-sub-1", Widening.Attempt.SILENT, SOURCE_C, T0);

            String rendered = login.toString();
            String renderedWidening = widening.toString();

            assertAll("only createdAt, ttl and the widening flag are rendered",
                    () -> assertFalse(rendered.contains(login.id()), "the binding-cookie id is never rendered"),
                    () -> assertFalse(renderedWidening.contains(widening.id()),
                            "the binding-cookie id is never rendered"),
                    () -> assertFalse(rendered.contains("id="), "no id field is rendered at all"),
                    () -> assertTrue(rendered.contains(T0.toString()), "createdAt is rendered"),
                    () -> assertTrue(rendered.contains(PendingAuthorizationRecord.FIXED_TTL.toString()),
                            "the ttl is rendered"),
                    () -> assertTrue(rendered.contains("widening=false"), "a plain login renders no widening"),
                    () -> assertTrue(renderedWidening.contains("widening=true"), "a widening is flagged"),
                    () -> assertFalse(rendered.contains(SOURCE_B), "the quota key is never rendered"),
                    () -> assertFalse(renderedWidening.contains(SOURCE_C), "the quota key is never rendered"),
                    () -> assertFalse(renderedWidening.contains("user-sub-1"), "the widening subject is not rendered"),
                    () -> assertFalse(rendered.contains(flow.state()), "the state is never rendered"),
                    () -> assertFalse(rendered.contains(flow.nonce()), "the nonce is never rendered"),
                    () -> assertFalse(rendered.contains(flow.pkceChallenge().codeVerifier()),
                            "the PKCE verifier is never rendered"),
                    () -> assertFalse(rendered.contains("flowContext"), "the flow context is not rendered at all"));
        }
    }

    @Nested
    @DisplayName("Widening record")
    class WideningRecord {

        private static final String SUBJECT = "user-sub-1";

        @Test
        @DisplayName("Should round-trip a widening record through the store with its subject and attempt")
        void shouldRoundTripWideningRecord() {
            InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(4);
            FlowContext flow = flow();
            PendingAuthorizationRecord stored = PendingAuthorizationRecord.createWidening(flow, "/orders",
                    REQUESTED_SCOPES, SUBJECT, Widening.Attempt.SILENT, SOURCE_C, T0);
            store.store(stored, T0);

            PendingAuthorizationRecord consumed = store.consume(stored.id(), T0.plusSeconds(1)).orElseThrow();

            assertEquals(new Widening(SUBJECT, Widening.Attempt.SILENT), consumed.widening(),
                    "the callback reads the live identity and the attempt back");
            assertSame(flow, consumed.flowContext());
            assertEquals("/orders", consumed.returnUrl());
            assertEquals(Set.copyOf(REQUESTED_SCOPES), consumed.requestedScopes());
            assertEquals(SOURCE_C, consumed.quotaKey(), "a widening record is counted under its requester too");
            assertEquals(PendingAuthorizationRecord.FIXED_TTL, consumed.ttl(),
                    "a widening record expires as fast as a login record");
            assertTrue(store.consume(stored.id(), T0).isEmpty(), "a widening record is single-use too");
        }

        @Test
        @DisplayName("Should record the interactive attempt and a fresh id per widening record")
        void shouldRecordInteractiveAttempt() {
            PendingAuthorizationRecord silent = PendingAuthorizationRecord.createWidening(flow(), "/orders",
                    REQUESTED_SCOPES, SUBJECT, Widening.Attempt.SILENT, SOURCE_A, T0);
            PendingAuthorizationRecord interactive = PendingAuthorizationRecord.createWidening(flow(), "/orders",
                    REQUESTED_SCOPES, SUBJECT, Widening.Attempt.INTERACTIVE, SOURCE_A, T0);

            assertEquals(Widening.Attempt.INTERACTIVE, interactive.widening().attempt());
            assertNotEquals(silent.id(), interactive.id());
        }

        @Test
        @DisplayName("Should reject a widening marker without a subject or an attempt")
        void shouldRejectIncompleteWidening() {
            FlowContext flow = flow();

            assertThrows(NullPointerException.class, () -> new Widening(null, Widening.Attempt.SILENT));
            assertThrows(NullPointerException.class, () -> new Widening(SUBJECT, null));
            assertThrows(NullPointerException.class, () -> PendingAuthorizationRecord.createWidening(flow,
                    "/orders", REQUESTED_SCOPES, null, Widening.Attempt.SILENT, SOURCE_A, T0));
            assertThrows(NullPointerException.class, () -> PendingAuthorizationRecord.createWidening(flow,
                    "/orders", null, SUBJECT, Widening.Attempt.SILENT, SOURCE_A, T0));
            assertThrows(NullPointerException.class, () -> PendingAuthorizationRecord.createWidening(flow,
                    "/orders", REQUESTED_SCOPES, SUBJECT, Widening.Attempt.SILENT, null, T0));
        }
    }

    @Nested
    @DisplayName("Return-URL same-origin validation")
    class ReturnUrlValidation {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is accepted")
        @ValueSource(strings = {"/", "/dashboard", "/app/page?x=1", "https://gw.example.com/app",
                "https://gw.example.com:443/app", "HTTPS://GW.EXAMPLE.COM/app"})
        @DisplayName("Should accept a gateway-relative path or a same-origin absolute URL")
        void shouldAcceptSameOrigin(String returnUrl) {
            assertTrue(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN));
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" is rejected")
        @ValueSource(strings = {"//evil.example.com", "https://evil.example.com/app",
                "https://gw.example.com:8443/app", "http://gw.example.com/app", "javascript:alert(1)"})
        @DisplayName("Should reject a schema-relative, cross-origin, or non-http(s) return URL")
        void shouldRejectOffOrigin(String returnUrl) {
            assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN));
        }

        @ParameterizedTest(name = "backslash-authority return URL \"{0}\" is rejected")
        @ValueSource(strings = {"/\\evil.example.com", "/\\/evil.example.com", "\\evil.example.com",
                "\\\\evil.example.com", "/app\\x"})
        @DisplayName("Should reject a backslash-authority return URL (browsers normalize \\ to /)")
        void shouldRejectBackslashAuthority(String returnUrl) {
            assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN),
                    "a backslash the browser normalizes to / must never yield a protocol-relative open redirect");
        }

        @ParameterizedTest(name = "control-character return URL \"{0}\" is rejected")
        @ValueSource(strings = {"/\t/evil.example.com", "/\n/evil.example.com", "/\r/evil.example.com",
                "/\t\t/evil.example.com", "/app\0", "https://gw.example.com/\t/app"})
        @DisplayName("Should reject a return URL carrying a control character (browsers strip tab/newline)")
        void shouldRejectControlCharacters(String returnUrl) {
            assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN),
                    "a tab or newline the browser strips must never yield a protocol-relative open redirect");
        }

        @Test
        @DisplayName("Should reject a null or blank return URL")
        void shouldRejectNullOrBlank() {
            assertFalse(PendingAuthorizationRecord.sameOrigin(null, GATEWAY_ORIGIN));
            assertFalse(PendingAuthorizationRecord.sameOrigin("   ", GATEWAY_ORIGIN));
        }

        /**
         * The path spellings the shared {@code LocationPathReview} refuses and the earlier,
         * private {@code sameOrigin} check admitted (issue #323): the encoded separators in both cases,
         * double encoding, and the dot segments in their {@code %2E}/{@code %2e} and literal spellings.
         * Each is a path only; the tests below put it on the gateway-relative form and on the
         * same-origin absolute form.
         */
        static Stream<String> reviewRefusedPaths() {
            return Stream.of("/%2F%2Fevil.example.com", "/%2f%2fevil.example.com", "/app/x%2Fy", "/app/x%2fy",
                    "/%5Cevil.example.com", "/%5cevil.example.com", "/%252F%252Fevil.example.com",
                    "/%2E%2E/admin", "/%2e%2e/admin", "/app/%2E/b", "/app/%2e/b", "/app/./b", "/app/../admin",
                    "/app/..");
        }

        @ParameterizedTest(name = "review-refused gateway-relative return URL \"{0}\" is rejected")
        @MethodSource("reviewRefusedPaths")
        @DisplayName("Should reject a gateway-relative return URL whose path the shared Location review refuses")
        void shouldRejectReviewRefusedRelativePath(String path) {
            assertFalse(PendingAuthorizationRecord.sameOrigin(path, GATEWAY_ORIGIN),
                    "an encoded separator, double encoding or dot segment in the path is refused exactly as "
                            + "the redirect action and the upstream Location mapping refuse it");
        }

        @ParameterizedTest(name = "review-refused same-origin absolute return URL with path \"{0}\" is rejected")
        @MethodSource("reviewRefusedPaths")
        @DisplayName("Should reject a same-origin absolute return URL whose path the shared Location review refuses")
        void shouldRejectReviewRefusedAbsolutePath(String path) {
            String returnUrl = GATEWAY_ORIGIN + path;

            assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN),
                    "the origin matches, but the path is one the shared review refuses");
        }

        @ParameterizedTest(name = "review-refused path before a query or fragment \"{0}\" is rejected")
        @ValueSource(strings = {"/%2F%2Fevil.example.com?next=/app", "/app/../admin#top",
                "https://gw.example.com/%5Cevil.example.com?x=1"})
        @DisplayName("Should reject a refused path even when a query or fragment follows it")
        void shouldRejectReviewRefusedPathBeforeQuery(String returnUrl) {
            assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN));
        }

        @ParameterizedTest(name = "path-only scope: return URL \"{0}\" is accepted")
        @ValueSource(strings = {"/app/items?next=%2Fx", "/app/items?next=%5Cx", "/app/items?next=%252F",
                "/app/items#%2F%2Fx", "/app/items?next=/../x", "https://gw.example.com/app/items?next=%2Fx",
                "https://gw.example.com", "https://gw.example.com?next=%2Fx",
                "https://gw.example.com/app/v1.2/file.txt", "/app/.well-known"})
        @DisplayName("Should accept an encoded value outside the path and dot runs that are not dot segments")
        void shouldAcceptReviewOutsideThePath(String returnUrl) {
            assertTrue(PendingAuthorizationRecord.sameOrigin(returnUrl, GATEWAY_ORIGIN),
                    "the review is scoped to the path: a query or fragment cannot form an authority");
        }
    }
}
