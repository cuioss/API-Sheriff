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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


import de.cuioss.sheriff.token.client.flow.FlowContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the D2b pending-authorization primitives: the bounded single-use
 * {@link PendingAuthorizationStore.InMemory}, the {@link PendingAuthorizationRecord} contract
 * (single-use enforcement, TTL expiry, same-origin return-URL validation), and the
 * security-critical cross-browser callback rejection (a valid {@code state} presented in a
 * browser without the matching binding cookie is refused).
 */
class PendingAuthorizationStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";

    private static FlowContext flow() {
        return FlowContext.create(GATEWAY_ORIGIN + "/callback");
    }

    private static PendingAuthorizationRecord pendingRecord(String returnUrl, Instant createdAt) {
        return PendingAuthorizationRecord.create(flow(), returnUrl, createdAt);
    }

    @Nested
    @DisplayName("Bounded single-use in-memory store")
    class InMemoryStore {

        @Test
        @DisplayName("Should resolve a stored record exactly once (single-use consumption)")
        void shouldConsumeStoredRecordExactlyOnce() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored);

            Optional<PendingAuthorizationRecord> first = store.consume(stored.id(), T0);
            Optional<PendingAuthorizationRecord> second = store.consume(stored.id(), T0);

            assertTrue(first.isPresent(), "the first consumption resolves the record");
            assertEquals(stored.id(), first.get().id());
            assertTrue(second.isEmpty(), "single-use: the second consumption is refused");
        }

        @Test
        @DisplayName("Should refuse a record consumed after its TTL has elapsed")
        void shouldRefuseExpiredRecord() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored);

            Instant afterTtl = T0.plus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);
            assertTrue(store.consume(stored.id(), afterTtl).isEmpty(), "an expired record is refused");
        }

        @Test
        @DisplayName("Should resolve a record consumed within its TTL")
        void shouldResolveRecordWithinTtl() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(16);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored);

            assertTrue(store.consume(stored.id(), T0.plusSeconds(1)).isPresent());
        }

        @Test
        @DisplayName("Should evict the oldest record once the capacity bound is exceeded")
        void shouldEvictOldestBeyondCapacity() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(2);
            PendingAuthorizationRecord first = pendingRecord("/a", T0);
            PendingAuthorizationRecord second = pendingRecord("/b", T0);
            PendingAuthorizationRecord third = pendingRecord("/c", T0);
            store.store(first);
            store.store(second);
            store.store(third);

            assertEquals(2, store.size(), "the store never exceeds its capacity bound");
            assertTrue(store.consume(first.id(), T0).isEmpty(), "the oldest record was evicted");
            assertTrue(store.consume(second.id(), T0).isPresent());
            assertTrue(store.consume(third.id(), T0).isPresent());
        }

        @Test
        @DisplayName("Should return empty for an unknown record id")
        void shouldReturnEmptyForUnknownId() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(4);
            assertTrue(store.consume("nonexistent", T0).isEmpty());
        }

        @Test
        @DisplayName("Should reject a non-positive capacity bound")
        void shouldRejectNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new PendingAuthorizationStore.InMemory(0));
            assertThrows(IllegalArgumentException.class, () -> new PendingAuthorizationStore.InMemory(-1));
        }
    }

    @Nested
    @DisplayName("Cross-browser callback rejection (binding cookie required)")
    class CrossBrowserRejection {

        @Test
        @DisplayName("Should refuse a callback that carries no binding cookie even with a stored record")
        void shouldRefuseCallbackWithoutBindingCookie() {
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(8);
            BindingCookieCodec codec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored);

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
            PendingAuthorizationStore.InMemory store = new PendingAuthorizationStore.InMemory(8);
            BindingCookieCodec codec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
            PendingAuthorizationRecord stored = pendingRecord("/app", T0);
            store.store(stored);

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
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, "/dashboard", T0);

            assertSame(flow, pending.flowContext(), "the record wraps the engine DTO, never re-invents it");
            assertEquals("/dashboard", pending.returnUrl());
            assertEquals(T0, pending.createdAt());
            assertEquals(PendingAuthorizationRecord.FIXED_TTL, pending.ttl());
            assertEquals(T0.plus(PendingAuthorizationRecord.FIXED_TTL), pending.expiresAt());
        }

        @Test
        @DisplayName("Should treat the TTL boundary as expired (inclusive)")
        void shouldTreatTtlBoundaryAsExpired() {
            PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow(), "/app", T0);
            assertFalse(pending.isExpired(pending.expiresAt().minusNanos(1)));
            assertTrue(pending.isExpired(pending.expiresAt()), "expiry is inclusive of the boundary");
        }

        @Test
        @DisplayName("Should reject a null mandatory component")
        void shouldRejectNullComponents() {
            Duration ttl = Duration.ofMinutes(1);
            FlowContext flow = flow();
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord(null, flow, "/a", T0, ttl));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", null, "/a", T0, ttl));
            assertThrows(NullPointerException.class,
                    () -> new PendingAuthorizationRecord("id", flow, null, T0, ttl));
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

        @Test
        @DisplayName("Should reject a null or blank return URL")
        void shouldRejectNullOrBlank() {
            assertFalse(PendingAuthorizationRecord.sameOrigin(null, GATEWAY_ORIGIN));
            assertFalse(PendingAuthorizationRecord.sameOrigin("   ", GATEWAY_ORIGIN));
        }
    }
}
