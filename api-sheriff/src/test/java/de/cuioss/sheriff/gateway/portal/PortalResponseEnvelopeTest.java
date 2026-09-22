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
package de.cuioss.sheriff.gateway.portal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.portal.PortalResponseEnvelope.Cacheability;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link PortalResponseEnvelope}: the content type and {@code nosniff} on every response, the
 * {@code no-store} versus {@code max-age} cache split, and the {@code Vary: Cookie} announcement of a
 * cacheable page while the BFF runtime is active.
 */
@EnableGeneratorController
@DisplayName("PortalResponseEnvelope")
class PortalResponseEnvelopeTest {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String VARY = "Vary";

    @ParameterizedTest
    @EnumSource(Cacheability.class)
    @DisplayName("Always carries the HTML content type and nosniff")
    void alwaysCarriesContentTypeAndNosniff(Cacheability cacheability) {
        Map<String, String> headers = new PortalResponseEnvelope(60, true).headers(cacheability, null);

        assertAll("every portal-rendered response",
                () -> assertEquals("text/html; charset=utf-8", headers.get(CONTENT_TYPE)),
                () -> assertEquals("nosniff", headers.get(NOSNIFF_HEADER)),
                () -> assertFalse(headers.containsKey("Set-Cookie"), "the envelope never sets a cookie"));
    }

    @Nested
    @DisplayName("Cache-Control")
    class CacheControl {

        @Test
        @DisplayName("A session-bearing page is no-store, whatever cache_seconds says")
        void sessionBearingIsNoStore() {
            int cacheSeconds = Generators.integers(1, 86_400).next();

            Map<String, String> headers = new PortalResponseEnvelope(cacheSeconds, true)
                    .headers(Cacheability.SESSION_BEARING, null);

            assertAll("session data must never be stored",
                    () -> assertEquals("no-store", headers.get(CACHE_CONTROL)),
                    () -> assertFalse(headers.containsKey(VARY)));
        }

        @Test
        @DisplayName("An HTML error page is no-store")
        void errorPageIsNoStore() {
            Map<String, String> headers = new PortalResponseEnvelope(300, true)
                    .headers(Cacheability.ERROR_PAGE, null);

            assertEquals("no-store", headers.get(CACHE_CONTROL));
        }

        @Test
        @DisplayName("A session-free page is max-age=<cache_seconds>")
        void sessionFreeIsMaxAge() {
            int cacheSeconds = Generators.integers(1, 86_400).next();

            Map<String, String> headers = new PortalResponseEnvelope(cacheSeconds, false)
                    .headers(Cacheability.SESSION_FREE, null);

            assertEquals("max-age=" + cacheSeconds, headers.get(CACHE_CONTROL));
        }

        @Test
        @DisplayName("A session-free page with cache_seconds 0 is max-age=0")
        void sessionFreeWithZeroIsMaxAgeZero() {
            Map<String, String> headers = new PortalResponseEnvelope(0, false)
                    .headers(Cacheability.SESSION_FREE, null);

            assertEquals("max-age=0", headers.get(CACHE_CONTROL));
        }

        @Test
        @DisplayName("A negative cache_seconds is refused")
        void negativeCacheSecondsRefused() {
            assertThrows(IllegalArgumentException.class, () -> new PortalResponseEnvelope(-1, false));
        }
    }

    @Nested
    @DisplayName("Vary: Cookie")
    class VaryCookie {

        @Test
        @DisplayName("A session-free page announces Vary: Cookie while the BFF runtime is active")
        void announcedWithActiveBff() {
            Map<String, String> headers = new PortalResponseEnvelope(60, true)
                    .headers(Cacheability.SESSION_FREE, null);

            assertEquals("Cookie", headers.get(VARY));
        }

        @Test
        @DisplayName("No Vary without an active BFF runtime")
        void absentWithoutBff() {
            Map<String, String> headers = new PortalResponseEnvelope(60, false)
                    .headers(Cacheability.SESSION_FREE, "Origin");

            assertFalse(headers.containsKey(VARY), "the accumulated Vary is left to the caller untouched");
        }

        @Test
        @DisplayName("Merges Cookie into an existing Vary: Origin")
        void mergesWithExistingVary() {
            Map<String, String> headers = new PortalResponseEnvelope(60, true)
                    .headers(Cacheability.SESSION_FREE, "Origin");

            assertEquals("Origin, Cookie", headers.get(VARY));
        }

        @Test
        @DisplayName("Does not repeat a Cookie already announced")
        void doesNotRepeatCookie() {
            Map<String, String> headers = new PortalResponseEnvelope(60, true)
                    .headers(Cacheability.SESSION_FREE, "cookie");

            assertEquals("cookie", headers.get(VARY));
        }
    }

    @Test
    @DisplayName("Emits the headers in a stable order")
    void emitsHeadersInStableOrder() {
        Map<String, String> headers = new PortalResponseEnvelope(60, true).headers(Cacheability.SESSION_FREE, null);

        assertEquals(List.of(CONTENT_TYPE, NOSNIFF_HEADER, CACHE_CONTROL, VARY), List.copyOf(headers.keySet()));
    }
}
