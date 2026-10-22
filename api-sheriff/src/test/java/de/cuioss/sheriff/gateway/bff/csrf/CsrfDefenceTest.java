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
package de.cuioss.sheriff.gateway.bff.csrf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract of the gateway's same-origin proof. Both instances are fail-closed on unsafe methods: they
 * accept only a trusted {@code Origin} (full-origin match, case-insensitive) or, when {@code Origin} is
 * absent, a {@code Sec-Fetch-Site: same-origin} header — everything else is rejected with
 * {@link EventType#CSRF_REJECTED}. Safe methods ({@code GET}/{@code HEAD}/{@code OPTIONS}) are never
 * checked. The session instance trusts exactly its configured origins; the cookie-forwarding instance
 * trusts the origin the request was received on plus its additional origins, comparing both with the
 * default port dropped.
 */
@EnableTestLogger
@DisplayName("CsrfDefence — fail-closed same-origin proof for unsafe-method requests")
class CsrfDefenceTest {

    private static final String ORIGIN_HEADER = "Origin";
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final String TRUSTED_ORIGIN = "https://app.example.com";
    private static final String UNTRUSTED_ORIGIN = "https://evil.example.com";

    private static PipelineRequest request(HttpMethod method, Map<String, List<String>> headers) {
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/api/orders")
                .headers(headers)
                .build();
    }

    private static void assertRejected(CsrfDefence defence, PipelineRequest request, String reason) {
        GatewayException thrown = assertThrows(GatewayException.class, () -> defence.enforce(request), reason);
        assertEquals(EventType.CSRF_REJECTED, thrown.getEventType(),
                "a CSRF rejection must carry the CSRF_REJECTED event");
    }

    @Nested
    @DisplayName("the fixed session-route defence")
    class SessionDefence {

        private final CsrfDefence defence = new CsrfDefence(Set.of(TRUSTED_ORIGIN));

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS"})
        @DisplayName("a CSRF-safe method bypasses the defence even with no Origin proof")
        void safeMethodsBypass(HttpMethod method) {
            PipelineRequest request = request(method, Map.of());

            assertDoesNotThrow(() -> defence.enforce(request),
                    "safe methods carry no CSRF risk and are never checked");
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("an unsafe method with a trusted Origin is accepted")
        void unsafeMethodTrustedOriginAccepted(HttpMethod method) {
            PipelineRequest request = request(method, Map.of(ORIGIN_HEADER, List.of(TRUSTED_ORIGIN)));

            assertDoesNotThrow(() -> defence.enforce(request));
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("an unsafe method with an untrusted Origin is rejected")
        void unsafeMethodUntrustedOriginRejected(HttpMethod method) {
            PipelineRequest request = request(method, Map.of(ORIGIN_HEADER, List.of(UNTRUSTED_ORIGIN)));

            assertRejected(defence, request, "an untrusted Origin fails the exact-match check");
        }

        @Test
        @DisplayName("the trusted-Origin match is case-insensitive")
        void trustedOriginMatchIsCaseInsensitive() {
            PipelineRequest request = request(HttpMethod.POST,
                    Map.of(ORIGIN_HEADER, List.of("HTTPS://App.Example.COM")));

            assertDoesNotThrow(() -> defence.enforce(request),
                    "the inbound Origin is lower-cased before comparison against the pre-lower-cased trusted set");
        }

        @Test
        @DisplayName("an absent Origin with Sec-Fetch-Site: same-origin is accepted")
        void absentOriginSameOriginFetchAccepted() {
            PipelineRequest request = request(HttpMethod.POST, Map.of(SEC_FETCH_SITE_HEADER, List.of(SAME_ORIGIN)));

            assertDoesNotThrow(() -> defence.enforce(request),
                    "with no Origin, a same-origin fetch-metadata header is the accepted proof");
        }

        @Test
        @DisplayName("an absent Origin with Sec-Fetch-Site: cross-site is rejected")
        void absentOriginCrossSiteFetchRejected() {
            PipelineRequest request = request(HttpMethod.POST, Map.of(SEC_FETCH_SITE_HEADER, List.of("cross-site")));

            assertRejected(defence, request, "a cross-site fetch is not same-origin provenance");
        }

        @Test
        @DisplayName("an unsafe method with neither Origin nor Sec-Fetch-Site is rejected")
        void bothHeadersAbsentRejected() {
            PipelineRequest request = request(HttpMethod.POST, Map.of());

            assertRejected(defence, request, "no proof of same-origin provenance is fail-closed");
        }

        @Test
        @DisplayName("a present untrusted Origin is rejected even when Sec-Fetch-Site is same-origin")
        void presentOriginTakesPrecedenceOverFetchMetadata() {
            PipelineRequest request = request(HttpMethod.POST, Map.of(
                    ORIGIN_HEADER, List.of(UNTRUSTED_ORIGIN),
                    SEC_FETCH_SITE_HEADER, List.of(SAME_ORIGIN)));

            assertRejected(defence, request,
                    "a present Origin is authoritative — an untrusted Origin is not rescued by fetch metadata");
        }

        @Test
        @DisplayName("with an empty trusted set only a same-origin fetch can pass an unsafe method")
        void emptyTrustedSetIsFailClosedExceptSameOriginFetch() {
            CsrfDefence closed = new CsrfDefence(Set.of());

            assertRejected(closed, request(HttpMethod.POST, Map.of(ORIGIN_HEADER, List.of(TRUSTED_ORIGIN))),
                    "with no trusted origins configured, every Origin is untrusted");
            assertDoesNotThrow(() -> closed.enforce(request(HttpMethod.POST,
                    Map.of(SEC_FETCH_SITE_HEADER, List.of(SAME_ORIGIN)))),
                    "a same-origin fetch still passes with an empty trusted set");
        }

        @Test
        @DisplayName("the origin the request was received on is not trusted by the session instance")
        void receivedOriginIsNotTrusted() {
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.POST)
                    .requestPath("/api/orders")
                    .scheme("https")
                    .host("gateway.example.com")
                    .headers(Map.of(ORIGIN_HEADER, List.of("https://gateway.example.com")))
                    .build();

            assertRejected(defence, request,
                    "the session defence trusts its configured origins only, never the received origin");
        }

        @Test
        @DisplayName("the session instance compares its configured origins exactly, a default port included")
        void defaultPortIsNotNormalised() {
            PipelineRequest request = request(HttpMethod.POST, Map.of(ORIGIN_HEADER, List.of(TRUSTED_ORIGIN + ":443")));

            assertRejected(defence, request,
                    "default-port normalisation belongs to the cookie-forwarding instance only");
        }

        @Test
        @DisplayName("the constructor rejects a null trusted-origin set")
        void constructorRejectsNullTrustedOrigins() {
            assertThrows(NullPointerException.class, () -> new CsrfDefence(null));
        }
    }

    /**
     * The cookie-forwarding instance trusts the origin each request was received on — its scheme,
     * authority host and authority port — plus the route's {@code additional_origins}.
     */
    @Nested
    @DisplayName("the same-origin check of a cookie-forwarding route")
    class CookieForwardingRoute {

        private static final String GATEWAY_HOST = "app.example";
        private static final String RECEIVED_ORIGIN = "https://app.example";
        private static final String ADDITIONAL_ORIGIN = "https://partner.example";

        private final CsrfDefence defence = CsrfDefence.forCookieForwardingRoute(List.of(ADDITIONAL_ORIGIN));

        private static PipelineRequest received(@Nullable String scheme, @Nullable String host, int port,
                Map<String, List<String>> headers) {
            return PipelineRequest.builder()
                    .method(HttpMethod.POST)
                    .requestPath("/api/orders")
                    .scheme(scheme)
                    .host(host)
                    .authorityPort(port)
                    .headers(headers)
                    .build();
        }

        private static Map<String, List<String>> origin(String value) {
            return Map.of(ORIGIN_HEADER, List.of(value));
        }

        @Test
        @DisplayName("an Origin equal to the received origin is trusted")
        void receivedOriginTrusted() {
            PipelineRequest request = received("https", GATEWAY_HOST, -1, origin(RECEIVED_ORIGIN));

            assertDoesNotThrow(() -> defence.enforce(request),
                    "a same-origin page posts with the origin the gateway was reached on");
        }

        @Test
        @DisplayName("the received origin is compared case-insensitively")
        void receivedOriginCaseInsensitive() {
            PipelineRequest request = received("https", "App.Example", -1, origin("HTTPS://APP.example"));

            assertDoesNotThrow(() -> defence.enforce(request));
        }

        @Test
        @DisplayName("a declared non-default port is part of the received origin")
        void nonDefaultPortIsPartOfTheReceivedOrigin() {
            PipelineRequest samePort = received("https", GATEWAY_HOST, 8443, origin(RECEIVED_ORIGIN + ":8443"));
            PipelineRequest portLess = received("https", GATEWAY_HOST, 8443, origin(RECEIVED_ORIGIN));

            assertDoesNotThrow(() -> defence.enforce(samePort), "the same port is the same origin");
            assertRejected(defence, portLess, "a different port is a different origin");
        }

        @Test
        @DisplayName("an Origin with the received host but another scheme is refused")
        void schemeIsPartOfTheReceivedOrigin() {
            PipelineRequest request = received("http", GATEWAY_HOST, -1, origin(RECEIVED_ORIGIN));

            assertRejected(defence, request, "https://app.example is not the origin of a request received over http");
        }

        @Test
        @DisplayName("an additional origin is trusted beside the received origin")
        void additionalOriginTrusted() {
            PipelineRequest request = received("https", GATEWAY_HOST, -1, origin(ADDITIONAL_ORIGIN));

            assertDoesNotThrow(() -> defence.enforce(request));
        }

        @Test
        @DisplayName("a foreign Origin is refused")
        void foreignOriginRefused() {
            PipelineRequest request = received("https", GATEWAY_HOST, -1, origin(UNTRUSTED_ORIGIN));

            assertRejected(defence, request, "a cross-site page is neither the received nor an additional origin");
        }

        @Test
        @DisplayName("an absent Origin passes with Sec-Fetch-Site: same-origin and is refused without it")
        void absentOrigin() {
            PipelineRequest sameOriginFetch = received("https", GATEWAY_HOST, -1,
                    Map.of(SEC_FETCH_SITE_HEADER, List.of(SAME_ORIGIN)));
            PipelineRequest noProof = received("https", GATEWAY_HOST, -1, Map.of());

            assertDoesNotThrow(() -> defence.enforce(sameOriginFetch),
                    "with no Origin, a same-origin fetch-metadata header is the accepted proof");
            assertRejected(defence, noProof, "no proof of same-origin provenance is fail-closed");
        }

        @Test
        @DisplayName("a default port is dropped from the received origin before the comparison")
        void receivedDefaultPortNormalised() {
            PipelineRequest https = received("https", GATEWAY_HOST, 443, origin(RECEIVED_ORIGIN));
            PipelineRequest http = received("http", GATEWAY_HOST, 80, origin("http://app.example"));

            assertDoesNotThrow(() -> defence.enforce(https), "https://app.example:443 is https://app.example");
            assertDoesNotThrow(() -> defence.enforce(http), "http://app.example:80 is http://app.example");
        }

        @Test
        @DisplayName("a default port is dropped from the inbound Origin before the comparison")
        void inboundDefaultPortNormalised() {
            PipelineRequest request = received("https", GATEWAY_HOST, -1, origin(RECEIVED_ORIGIN + ":443"));

            assertDoesNotThrow(() -> defence.enforce(request));
        }

        @Test
        @DisplayName("a default port is dropped from an additional origin before the comparison")
        void additionalOriginDefaultPortNormalised() {
            CsrfDefence explicitPort = CsrfDefence.forCookieForwardingRoute(List.of(ADDITIONAL_ORIGIN + ":443"));
            PipelineRequest request = received("https", GATEWAY_HOST, -1, origin(ADDITIONAL_ORIGIN));

            assertDoesNotThrow(() -> explicitPort.enforce(request));
        }

        @Test
        @DisplayName("only the scheme's own default port is dropped")
        void foreignDefaultPortKept() {
            PipelineRequest request = received("https", GATEWAY_HOST, 80, origin(RECEIVED_ORIGIN));

            assertRejected(defence, request, "port 80 is not the default port of https");
        }

        @Test
        @DisplayName("a request with no host has no received origin, so only an additional origin can pass")
        void nullHostRefused() {
            PipelineRequest receivedLooking = received("https", null, -1, origin(RECEIVED_ORIGIN));
            PipelineRequest additional = received("https", null, -1, origin(ADDITIONAL_ORIGIN));

            assertRejected(defence, receivedLooking, "with no authority host there is no origin to match");
            assertDoesNotThrow(() -> defence.enforce(additional), "an additional origin needs no received origin");
        }

        @Test
        @DisplayName("a request with no scheme has no received origin")
        void nullSchemeRefused() {
            PipelineRequest request = received(null, GATEWAY_HOST, -1, origin(RECEIVED_ORIGIN));

            assertRejected(defence, request, "with no scheme there is no origin to match");
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS"})
        @DisplayName("a CSRF-safe method passes with a foreign Origin")
        void safeMethodsBypass(HttpMethod method) {
            PipelineRequest request = PipelineRequest.builder()
                    .method(method)
                    .requestPath("/api/orders")
                    .headers(origin(UNTRUSTED_ORIGIN))
                    .build();

            assertDoesNotThrow(() -> defence.enforce(request));
        }

        @Test
        @DisplayName("a route declaring no additional origin trusts the received origin alone")
        void noAdditionalOrigins() {
            CsrfDefence receivedOnly = CsrfDefence.forCookieForwardingRoute(List.of());

            assertDoesNotThrow(() -> receivedOnly.enforce(received("https", GATEWAY_HOST, -1, origin(RECEIVED_ORIGIN))));
            assertRejected(receivedOnly, received("https", GATEWAY_HOST, -1, origin(ADDITIONAL_ORIGIN)),
                    "an origin the route did not declare is foreign");
        }

        @Test
        @DisplayName("the factory rejects a null origin list")
        void factoryRejectsNull() {
            assertThrows(NullPointerException.class, () -> CsrfDefence.forCookieForwardingRoute(null));
        }
    }

    @Nested
    @DisplayName("the unsafe-method predicate")
    class UnsafeMethod {

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS"})
        @DisplayName("a CSRF-safe method is not unsafe")
        void safeMethods(HttpMethod method) {
            assertFalse(CsrfDefence.isUnsafeMethod(method));
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every other method is unsafe")
        void unsafeMethods(HttpMethod method) {
            assertTrue(CsrfDefence.isUnsafeMethod(method));
        }
    }
}
