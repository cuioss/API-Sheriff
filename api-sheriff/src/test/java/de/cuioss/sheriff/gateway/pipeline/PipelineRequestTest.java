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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("PipelineRequest — per-request response state")
class PipelineRequestTest {

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";

    private static PipelineRequest request() {
        return PipelineRequest.builder().method(HttpMethod.GET).requestPath("/app/orders").build();
    }

    private static String setCookieLine() {
        return Generators.letterStrings(4, 12).next() + "=" + Generators.letterStrings(4, 12).next() + "; Path=/";
    }

    /**
     * Every writer of a gateway response applies the set-header map, and a set-mode entry replaces the
     * value an upstream sent. Putting {@code no-store} there in the call that appends the cookie is
     * therefore what makes the rule hold on every answer a stage adds a cookie to.
     */
    @Nested
    @DisplayName("a response a cookie is appended to is marked uncacheable in the set-header map")
    class CookieMakesTheResponseUncacheable {

        @Test
        @DisplayName("appending a Set-Cookie value puts Cache-Control: no-store into the set-header map")
        void appendingACookieSetsNoStore() {
            PipelineRequest request = request();
            String line = setCookieLine();

            request.addResponseSetCookie(line);

            assertAll("the cookie and the cache policy travel together",
                    () -> assertEquals(List.of(line), request.responseSetCookies()),
                    () -> assertEquals(Map.of(CACHE_CONTROL, NO_STORE), request.responseHeaders()),
                    () -> assertEquals(NO_STORE, request.gatewayAuthoredResponseHeaders().get(CACHE_CONTROL)));
        }

        @Test
        @DisplayName("control: a request no cookie is appended to carries no Cache-Control of the gateway's")
        void noCookieNoCachePolicy() {
            PipelineRequest request = request();

            assertAll("nothing is added before a cookie is",
                    () -> assertEquals(List.of(), request.responseSetCookies()),
                    () -> assertFalse(request.responseHeaders().containsKey(CACHE_CONTROL)),
                    () -> assertFalse(request.gatewayAuthoredResponseHeaders().containsKey(CACHE_CONTROL)));
        }

        @Test
        @DisplayName("no-store replaces a set-mode Cache-Control a stage wrote earlier")
        void replacesAnEarlierSetModeValue() {
            PipelineRequest request = request();
            request.responseHeaders().put(CACHE_CONTROL, "public, max-age=600");

            request.addResponseSetCookie(setCookieLine());

            assertEquals(NO_STORE, request.responseHeaders().get(CACHE_CONTROL));
        }

        @Test
        @DisplayName("a default-mode Cache-Control, which would defer to the upstream's value, is moved to set mode")
        void movesADefaultModeValueToSetMode() {
            PipelineRequest request = request();
            request.responseDefaultHeaders().put(CACHE_CONTROL, "public, max-age=600");

            request.addResponseSetCookie(setCookieLine());

            assertAll("the name lives in the set-header map only",
                    () -> assertFalse(request.responseDefaultHeaders().containsKey(CACHE_CONTROL),
                            "a default-mode entry would let an upstream's cacheable policy through"),
                    () -> assertEquals(NO_STORE, request.responseHeaders().get(CACHE_CONTROL)));
        }

        @Test
        @DisplayName("a second cookie keeps both lines and the one cache policy")
        void secondCookieKeepsBothLines() {
            PipelineRequest request = request();
            String first = setCookieLine();
            String second = setCookieLine();

            request.addResponseSetCookie(first);
            request.addResponseSetCookie(second);

            assertAll("appending never replaces a line",
                    () -> assertEquals(List.of(first, second), request.responseSetCookies()),
                    () -> assertEquals(Map.of(CACHE_CONTROL, NO_STORE), request.responseHeaders()));
        }
    }

    /**
     * The scheme, authority host and authority port render the origin the request was received on,
     * which the same-origin check of a cookie-forwarding route trusts.
     */
    @Nested
    @DisplayName("the inbound origin fields round-trip through the builder")
    class ReceivedOrigin {

        @Test
        @DisplayName("the scheme, host and authority port the edge supplies are the ones read back")
        void roundTrip() {
            String scheme = Generators.fixedValues("http", "https").next();
            String host = Generators.letterStrings(3, 12).next() + ".example";
            int port = Generators.integers(1, 65_535).next();

            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.POST)
                    .requestPath("/app/orders")
                    .scheme(scheme)
                    .host(host)
                    .authorityPort(port)
                    .build();

            assertAll("the received origin is carried unchanged",
                    () -> assertEquals(scheme, request.scheme()),
                    () -> assertEquals(host, request.host()),
                    () -> assertEquals(port, request.authorityPort()));
        }

        @Test
        @DisplayName("control: a request the edge supplied no origin fields for has no scheme and port -1")
        void absentByDefault() {
            PipelineRequest request = request();

            assertAll("an absent authority reads as unknown, never as a default",
                    () -> assertNull(request.scheme()),
                    () -> assertNull(request.host()),
                    () -> assertEquals(-1, request.authorityPort()));
        }
    }
}
