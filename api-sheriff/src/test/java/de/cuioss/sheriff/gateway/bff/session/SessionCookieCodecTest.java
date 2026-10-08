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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionCookieCodec}: the {@code __Host-} hardening of the opaque session
 * cookie, its clearing form, the operator-configurable cookie name, and the request-header parse
 * that reads back the opaque cookie handle (never any token material).
 */
class SessionCookieCodecTest {

    private final SessionCookieCodec codec =
            new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1));

    @Nested
    @DisplayName("Set-Cookie hardening")
    class SetCookie {

        @Test
        @DisplayName("Should emit a __Host- prefixed, Secure, HttpOnly, SameSite=Lax, Path=/ cookie carrying only the handle")
        void shouldEmitHardenedCookie() {
            String header = codec.toSetCookieHeader("opaque-handle");

            assertTrue(header.startsWith("__Host-sheriff-session=opaque-handle"), header);
            assertTrue(header.contains("; Path=/"), header);
            assertTrue(header.contains("; Secure"), header);
            assertTrue(header.contains("; HttpOnly"), header);
            assertTrue(header.contains("; SameSite=Lax"), header);
            assertTrue(header.contains("; Max-Age=3600"), header);
        }

        @Test
        @DisplayName("Should give a re-issued cookie the remaining lifetime as Max-Age, otherwise the same header")
        void shouldEmitRemainingLifetimeOnReissue() {
            String header = codec.toSetCookieHeader("opaque-handle", Duration.ofSeconds(1234));

            assertEquals("__Host-sheriff-session=opaque-handle; Max-Age=1234; Path=/; Secure; HttpOnly; SameSite=Lax",
                    header);
            assertEquals(codec.toSetCookieHeader("opaque-handle"),
                    codec.toSetCookieHeader("opaque-handle", Duration.ofHours(1)),
                    "the login form is the re-issue form at the codec's full lifetime");
        }

        @Test
        @DisplayName("Should write a negative remaining lifetime as Max-Age=0")
        void shouldClampNegativeRemainingLifetime() {
            String header = codec.toSetCookieHeader("opaque-handle", Duration.ofSeconds(-5));

            assertTrue(header.contains("; Max-Age=0;"), header);
        }

        @Test
        @DisplayName("Should clear the cookie with Max-Age=0 keeping the __Host- attributes")
        void shouldClearCookie() {
            String header = codec.toClearingSetCookieHeader();

            assertTrue(header.startsWith("__Host-sheriff-session="), header);
            assertTrue(header.contains("; Max-Age=0"), header);
            assertTrue(header.contains("; Path=/"), header);
            assertTrue(header.contains("; Secure"), header);
            assertTrue(header.contains("; HttpOnly"), header);
        }

        @Test
        @DisplayName("Should honour an operator-configured cookie name")
        void shouldHonourConfiguredName() {
            SessionCookieCodec custom = new SessionCookieCodec("__Host-my-session", Duration.ofMinutes(30));
            assertTrue(custom.toSetCookieHeader("x").startsWith("__Host-my-session=x"));
        }
    }

    @Nested
    @DisplayName("Reading the cookie handle")
    class ReadCookieHandle {

        @Test
        @DisplayName("Should read the cookie handle from a Cookie header carrying the session cookie among others")
        void shouldReadCookieHandleFromCookieHeader() {
            String cookieHeader = "csrf=1; __Host-sheriff-session=opaque-handle; last=z";
            assertEquals(Optional.of("opaque-handle"), codec.readCookieHandle(cookieHeader));
        }

        @ParameterizedTest(name = "cookie header \"{0}\" yields no cookie handle")
        @ValueSource(strings = {"", "   ", "other=abc", "__Host-sheriff-session="})
        @DisplayName("Should return empty when the session cookie is absent or empty-valued")
        void shouldReturnEmptyWhenAbsentOrEmpty(String cookieHeader) {
            assertTrue(codec.readCookieHandle(cookieHeader).isEmpty());
        }

        @Test
        @DisplayName("Should return empty for a null Cookie header")
        void shouldReturnEmptyForNullHeader() {
            assertTrue(codec.readCookieHandle(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Should reject a null or blank cookie name")
        void shouldRejectBlankName() {
            Duration ttl = Duration.ofHours(1);
            assertThrows(NullPointerException.class, () -> new SessionCookieCodec(null, ttl));
            assertThrows(IllegalArgumentException.class, () -> new SessionCookieCodec("  ", ttl));
        }
    }
}
