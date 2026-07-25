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
 * that reads back the opaque session id (never any token material).
 */
class SessionCookieCodecTest {

    private final SessionCookieCodec codec =
            new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, Duration.ofHours(1));

    @Nested
    @DisplayName("Set-Cookie hardening")
    class SetCookie {

        @Test
        @DisplayName("Should emit a __Host- prefixed, Secure, HttpOnly, SameSite=Lax, Path=/ cookie carrying only the id")
        void shouldEmitHardenedCookie() {
            String header = codec.toSetCookieHeader("opaque-id");

            assertTrue(header.startsWith("__Host-sheriff-session=opaque-id"), header);
            assertTrue(header.contains("; Path=/"), header);
            assertTrue(header.contains("; Secure"), header);
            assertTrue(header.contains("; HttpOnly"), header);
            assertTrue(header.contains("; SameSite=Lax"), header);
            assertTrue(header.contains("; Max-Age=3600"), header);
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
    @DisplayName("Reading the session id")
    class ReadSessionId {

        @Test
        @DisplayName("Should read the session id from a Cookie header carrying the session cookie among others")
        void shouldReadSessionIdFromCookieHeader() {
            String cookieHeader = "csrf=1; __Host-sheriff-session=opaque-id; last=z";
            assertEquals(Optional.of("opaque-id"), codec.readSessionId(cookieHeader));
        }

        @ParameterizedTest(name = "cookie header \"{0}\" yields no session id")
        @ValueSource(strings = {"", "   ", "other=abc", "__Host-sheriff-session="})
        @DisplayName("Should return empty when the session cookie is absent or empty-valued")
        void shouldReturnEmptyWhenAbsentOrEmpty(String cookieHeader) {
            assertTrue(codec.readSessionId(cookieHeader).isEmpty());
        }

        @Test
        @DisplayName("Should return empty for a null Cookie header")
        void shouldReturnEmptyForNullHeader() {
            assertTrue(codec.readSessionId(null).isEmpty());
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
