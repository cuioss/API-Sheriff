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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("PipelineRequest — per-request response state and trust-boundary state")
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
     * The trust-boundary state: the response-header snapshot taken when the route is selected, which
     * {@link PipelineRequest#restoreUnroutedResponse()} puts back, and the two markers the edge and the
     * authentication stage set.
     */
    @Nested
    @DisplayName("the unrouted response snapshot and the trust-boundary markers")
    class TrustBoundaryState {

        private static final String HSTS = "Strict-Transport-Security";
        private static final String FRAME_OPTIONS = "X-Frame-Options";
        private static final String CSP = "Content-Security-Policy";

        @Test
        @DisplayName("restore puts back both header maps as they were at selection, in their order, and clears the cookies")
        void restoreReturnsTheSelectionSnapshot() {
            PipelineRequest request = request();
            String hsts = "max-age=" + Generators.integers(1, 99_999_999).next();
            String policy = "default-src " + Generators.letterStrings(3, 10).next();
            request.responseHeaders().put(HSTS, hsts);
            request.responseHeaders().put(CSP, policy);
            request.responseDefaultHeaders().put(FRAME_OPTIONS, "SAMEORIGIN");
            request.selectedRoute(route());
            request.responseHeaders().clear();
            request.responseDefaultHeaders().clear();
            request.responseHeaders().put(FRAME_OPTIONS, "DENY");
            request.responseHeaders().put("WWW-Authenticate", "Bearer");
            request.responseHeaders().put("Allow", "GET");
            request.addResponseSetCookie(setCookieLine());

            request.restoreUnroutedResponse();

            assertAll("the response is back in its unrouted state",
                    () -> assertEquals(List.of(Map.entry(HSTS, hsts), Map.entry(CSP, policy)),
                            List.copyOf(request.responseHeaders().entrySet()),
                            "the set-mode map, names, values and order"),
                    () -> assertEquals(Map.of(FRAME_OPTIONS, "SAMEORIGIN"), request.responseDefaultHeaders(),
                            "the default-mode map"),
                    () -> assertEquals(List.of(), request.responseSetCookies(), "no stage cookie survives"),
                    () -> assertFalse(request.responseHeaders().containsKey(CACHE_CONTROL),
                            "the no-store a cookie forced is gone with the cookie"));
        }

        @Test
        @DisplayName("the snapshot is a copy: a later write to the live maps does not reach it")
        void snapshotIsACopy() {
            PipelineRequest request = request();
            request.selectedRoute(route());
            request.responseHeaders().put(HSTS, "max-age=1");

            request.restoreUnroutedResponse();

            assertTrue(request.responseHeaders().isEmpty(), "the empty selection-time map is restored");
        }

        @Test
        @DisplayName("restore without a selected route is refused: there is no snapshot")
        void restoreWithoutSelectionIsRefused() {
            PipelineRequest request = request();

            assertThrows(IllegalStateException.class, request::restoreUnroutedResponse);
        }

        @Test
        @DisplayName("both markers start unset and stay set once marked")
        void markersAreOneWay() {
            PipelineRequest request = request();
            boolean trustBefore = request.trustBoundaryPassed();
            boolean sessionBefore = request.onSessionBranch();

            request.markTrustBoundaryPassed();
            request.markSessionBranch();

            assertAll("the markers",
                    () -> assertFalse(trustBefore, "a new request has not passed the trust boundary"),
                    () -> assertFalse(sessionBefore, "a new request is on no session branch"),
                    () -> assertTrue(request.trustBoundaryPassed()),
                    () -> assertTrue(request.onSessionBranch()));
        }

        @Test
        @DisplayName("control: the markers are independent of each other")
        void markersAreIndependent() {
            PipelineRequest trusted = request();
            PipelineRequest session = request();

            trusted.markTrustBoundaryPassed();
            session.markSessionBranch();

            assertAll("one marker never sets the other",
                    () -> assertFalse(trusted.onSessionBranch()),
                    () -> assertFalse(session.trustBoundaryPassed()));
        }

        private static Route route() {
            return Route.builder().id(Generators.letterStrings(3, 10).next())
                    .match(MatchConfig.builder().pathPrefix("/app").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .upstream(new ResolvedUpstream("https", "app.example", 443, ""))
                    .build();
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
