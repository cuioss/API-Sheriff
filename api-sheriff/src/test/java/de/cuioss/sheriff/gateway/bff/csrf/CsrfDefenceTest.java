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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract of the fixed CSRF defence for {@code require: session} routes (D7). The defence is
 * fail-closed on unsafe methods: it accepts only a trusted {@code Origin} (exact full-origin match,
 * case-insensitive) or, when {@code Origin} is absent, a {@code Sec-Fetch-Site: same-origin} header —
 * everything else is rejected with {@link EventType#CSRF_REJECTED}. Safe methods
 * ({@code GET}/{@code HEAD}/{@code OPTIONS}) are never checked.
 */
@EnableTestLogger
@DisplayName("CsrfDefence — fixed, fail-closed CSRF defence for session routes")
class CsrfDefenceTest {

    private static final String ORIGIN_HEADER = "Origin";
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String TRUSTED_ORIGIN = "https://app.example.com";
    private static final String UNTRUSTED_ORIGIN = "https://evil.example.com";

    private final CsrfDefence defence = new CsrfDefence(Set.of(TRUSTED_ORIGIN));

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
        PipelineRequest request = request(HttpMethod.POST, Map.of(ORIGIN_HEADER, List.of("HTTPS://App.Example.COM")));

        assertDoesNotThrow(() -> defence.enforce(request),
                "the inbound Origin is lower-cased before comparison against the pre-lower-cased trusted set");
    }

    @Test
    @DisplayName("an absent Origin with Sec-Fetch-Site: same-origin is accepted")
    void absentOriginSameOriginFetchAccepted() {
        PipelineRequest request = request(HttpMethod.POST, Map.of(SEC_FETCH_SITE_HEADER, List.of("same-origin")));

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
                SEC_FETCH_SITE_HEADER, List.of("same-origin")));

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
                        Map.of(SEC_FETCH_SITE_HEADER, List.of("same-origin")))),
                "a same-origin fetch still passes with an empty trusted set");
    }

    @Test
    @DisplayName("the constructor rejects a null trusted-origin set")
    void constructorRejectsNullTrustedOrigins() {
        assertThrows(NullPointerException.class, () -> new CsrfDefence(null));
    }
}
