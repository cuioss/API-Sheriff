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
package de.cuioss.sheriff.gateway.bff.refresh;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EndedRefreshTokens}: the bounded cookie-mode marker and the inert server-mode one.
 * The refresh tokens are opaque to the marker, so generated values serve; the instants are fixed
 * because the expiry boundary is the contract under test.
 */
@EnableGeneratorController
class EndedRefreshTokensTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Instant LIFETIME = NOW.plusSeconds(3600);

    private static String token() {
        return Generators.letterStrings(24, 48).next();
    }

    private static EndedRefreshTokens.Bounded bounded() {
        return new EndedRefreshTokens.Bounded(new SecureRandom());
    }

    @Nested
    @DisplayName("Bounded cookie-mode marker")
    class BoundedMarker {

        @Test
        @DisplayName("Should report a marked token ended and an unmarked one not")
        void shouldHitMarkedTokenOnly() {
            EndedRefreshTokens.Bounded marker = bounded();
            String ended = token();
            String other = ended + "-successor";

            marker.markEnded(ended, LIFETIME, NOW);

            assertAll(
                    () -> assertTrue(marker.isEnded(ended, NOW.plusSeconds(1)), "the marked token is ended"),
                    () -> assertFalse(marker.isEnded(other, NOW.plusSeconds(1)), "a different token is not"));
        }

        @Test
        @DisplayName("Should stop reporting a token ended at and after the session's absolute lifetime")
        void shouldExpireAtAbsoluteLifetime() {
            EndedRefreshTokens.Bounded marker = bounded();
            String ended = token();

            marker.markEnded(ended, LIFETIME, NOW);

            assertAll(
                    () -> assertTrue(marker.isEnded(ended, LIFETIME.minusSeconds(1))),
                    () -> assertFalse(marker.isEnded(ended, LIFETIME), "the marker never outlives the lifetime"),
                    () -> assertFalse(marker.isEnded(ended, LIFETIME.plusSeconds(1))));
        }

        @Test
        @DisplayName("Should not record a token whose session is already past its absolute lifetime")
        void shouldNotRecordExpiredSession() {
            EndedRefreshTokens.Bounded marker = bounded();

            marker.markEnded(token(), NOW, NOW);

            assertEquals(0, marker.size());
        }

        @Test
        @DisplayName("Should neither record nor evict a live marker when the store is full of unexpired markers")
        void shouldDegradeWhenFullOfLiveMarkers() {
            EndedRefreshTokens.Bounded marker = bounded();
            for (int i = 0; i < EndedRefreshTokens.MAX_ENTRIES; i++) {
                marker.markEnded("live-" + i, LIFETIME, NOW);
            }
            String overflow = token();

            marker.markEnded(overflow, LIFETIME, NOW);

            assertAll("a full store degrades to one identity-provider call rather than failing open",
                    () -> assertFalse(marker.isEnded(overflow, NOW), "the overflowing token is not recorded"),
                    () -> assertTrue(marker.isEnded("live-0", NOW), "no live marker was evicted"),
                    () -> assertEquals(EndedRefreshTokens.MAX_ENTRIES, marker.size()));
        }

        @Test
        @DisplayName("Should prune expired markers first and then record when the store is full")
        void shouldPruneExpiredMarkersWhenFull() {
            EndedRefreshTokens.Bounded marker = bounded();
            Instant shortLifetime = NOW.plusSeconds(10);
            for (int i = 0; i < EndedRefreshTokens.MAX_ENTRIES; i++) {
                marker.markEnded("short-" + i, shortLifetime, NOW);
            }
            String later = token();
            Instant afterExpiry = shortLifetime.plusSeconds(1);

            marker.markEnded(later, LIFETIME, afterExpiry);

            assertAll(
                    () -> assertTrue(marker.isEnded(later, afterExpiry), "the token is recorded after pruning"),
                    () -> assertEquals(1, marker.size(), "every expired marker was pruned"));
        }

        @Test
        @DisplayName("Should hold only a per-instance salted digest, never the raw token")
        void shouldKeyOnPerInstanceSaltedDigest() {
            String refreshToken = token();
            EndedRefreshTokens.Bounded first = bounded();
            EndedRefreshTokens.Bounded second = bounded();

            String firstDigest = first.digest(refreshToken);
            String secondDigest = second.digest(refreshToken);

            assertAll("the key is non-reversible and salted per instance",
                    () -> assertEquals(firstDigest, first.digest(refreshToken), "stable within one instance"),
                    () -> assertNotEquals(firstDigest, secondDigest, "two instances key the same token differently"),
                    () -> assertFalse(firstDigest.contains(refreshToken), "the raw token is not part of the key"));
        }

        @Test
        @DisplayName("Should not report a token ended on another instance")
        void shouldBePerInstance() {
            EndedRefreshTokens.Bounded first = bounded();
            EndedRefreshTokens.Bounded second = bounded();
            String ended = token();

            first.markEnded(ended, LIFETIME, NOW);

            assertFalse(second.isEnded(ended, NOW), "a sibling instance has not seen the session end");
        }
    }

    @Nested
    @DisplayName("Inert server-mode marker")
    class InertMarker {

        @Test
        @DisplayName("Should never report a token ended")
        void shouldNeverHit() {
            EndedRefreshTokens marker = EndedRefreshTokens.inert();
            String ended = token();

            marker.markEnded(ended, LIFETIME, NOW);

            assertAll(
                    () -> assertFalse(marker.isEnded(ended, NOW)),
                    () -> assertSame(EndedRefreshTokens.inert(), marker, "one shared stateless instance"));
        }
    }
}
