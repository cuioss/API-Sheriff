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
package de.cuioss.sheriff.gateway.bff.pending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link BindingCookieCodec}: the {@code __Host-} hardening of the emitted
 * {@code Set-Cookie} header, the clearing form, and the request-header parse that reads back the
 * bound record id (returning empty whenever the binding cookie is absent or empty-valued — the
 * request-side half of the cross-browser callback rejection).
 */
class BindingCookieCodecTest {

    private final BindingCookieCodec codec = new BindingCookieCodec(Duration.ofMinutes(5));

    @Nested
    @DisplayName("Set-Cookie hardening")
    class SetCookie {

        @Test
        @DisplayName("Should emit a __Host- prefixed, Secure, HttpOnly, SameSite=Lax, Path=/ cookie")
        void shouldEmitHardenedCookie() {
            String header = codec.toSetCookieHeader("record-123");

            assertTrue(header.startsWith("__Host-sheriff-binding=record-123"), header);
            assertTrue(header.contains("; Path=/"), header);
            assertTrue(header.contains("; Secure"), header);
            assertTrue(header.contains("; HttpOnly"), header);
            assertTrue(header.contains("; SameSite=Lax"), header);
            assertTrue(header.contains("; Max-Age=300"), header);
        }

        @Test
        @DisplayName("Should clear the cookie with Max-Age=0 while keeping the __Host- attributes")
        void shouldClearCookie() {
            String header = codec.toClearingSetCookieHeader();

            assertTrue(header.startsWith("__Host-sheriff-binding="), header);
            assertTrue(header.contains("; Max-Age=0"), header);
            assertTrue(header.contains("; Path=/"), header);
            assertTrue(header.contains("; Secure"), header);
            assertTrue(header.contains("; HttpOnly"), header);
        }
    }

    @Nested
    @DisplayName("Reading the binding record id")
    class ReadRecordId {

        @Test
        @DisplayName("Should read the record id from a Cookie header carrying the binding cookie among others")
        void shouldReadRecordIdFromCookieHeader() {
            String cookieHeader = "other=1; __Host-sheriff-binding=abc123; last=z";
            assertEquals(Optional.of("abc123"), codec.readRecordId(cookieHeader));
        }

        @Test
        @DisplayName("Should read the record id when the binding cookie is the sole cookie")
        void shouldReadSoleBindingCookie() {
            assertEquals(Optional.of("xyz"), codec.readRecordId("__Host-sheriff-binding=xyz"));
        }

        @ParameterizedTest(name = "cookie header \"{0}\" yields no binding record id")
        @ValueSource(strings = {"", "   ", "session=abc", "__Host-sheriff-binding=", "notbinding=__Host-sheriff-binding"})
        @DisplayName("Should return empty when the binding cookie is absent or empty-valued")
        void shouldReturnEmptyWhenAbsentOrEmpty(String cookieHeader) {
            assertTrue(codec.readRecordId(cookieHeader).isEmpty());
        }

        @Test
        @DisplayName("Should return empty for a null Cookie header (no binding cookie sent)")
        void shouldReturnEmptyForNullHeader() {
            assertTrue(codec.readRecordId(null).isEmpty());
        }
    }
}
