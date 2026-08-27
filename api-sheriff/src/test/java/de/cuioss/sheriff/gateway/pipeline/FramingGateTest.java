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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("FramingGate — D3b GW-02 anti-smuggling framing gate")
class FramingGateTest {

    /** The strict default posture — the opt-in absent, i.e. every leg enforced as before it existed. */
    private final FramingGate gate = new FramingGate(false);

    /** The same gate with {@code allow_get_with_content_length_body} enabled. */
    private final FramingGate permissiveGate = new FramingGate(true);

    @Test
    @DisplayName("rejects Content-Length and Transfer-Encoding both present")
    void rejectsConflictingFraming() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("10"),
                "transfer-encoding", List.of("chunked")), 10L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a request carrying multiple Content-Length headers (RFC 7230 §3.3.2)")
    void rejectsMultipleContentLength() {
        // Arrange — two Content-Length values is an ambiguous framing / request-smuggling vector
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("10", "20")), 10L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a single Content-Length header carrying a comma-separated value list")
    void rejectsCommaSeparatedContentLength() {
        // Arrange — "5, 6" in one Content-Length field is equally a smuggling vector per RFC 7230
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("5, 6")), 5L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a body on a bodyless GET")
    void rejectsBodyOnGet() {
        // Arrange
        PipelineRequest request = request(HttpMethod.GET, Map.of("content-length", List.of("5")), 5L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a HEAD request carrying a declared body")
    void rejectsBodyOnHead() {
        // Arrange
        PipelineRequest request = request(HttpMethod.HEAD, Map.of("content-length", List.of("3")), 3L, true);

        // Act + Assert
        assertThrows(GatewayException.class, () -> gate.process(request));
    }

    @Test
    @DisplayName("rejects a Connection token that would strip a framing header")
    void rejectsFramingHeaderStrip() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of(
                "content-length", List.of("4"),
                "connection", List.of("keep-alive, content-length")), 4L, true);

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, () -> gate.process(request));

        // Assert
        assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
    }

    @Test
    @DisplayName("rejects a Connection token that would strip the trust header Authorization")
    void rejectsTrustHeaderStrip() {
        // Arrange
        PipelineRequest request = request(HttpMethod.GET, Map.of(
                "connection", List.of("authorization")), -1L, false);

        // Act + Assert
        assertThrows(GatewayException.class, () -> gate.process(request));
    }

    @Test
    @DisplayName("accepts a well-framed POST carrying only Content-Length")
    void acceptsWellFramedPost() {
        // Arrange
        PipelineRequest request = request(HttpMethod.POST, Map.of("content-length", List.of("12")), 12L, true);

        // Act + Assert
        assertDoesNotThrow(() -> gate.process(request));
    }

    @Nested
    @DisplayName("The GET-body opt-in (security_defaults.allow_get_with_content_length_body)")
    class GetBodyOptIn {

        // --- Opt-in OFF: every rejection present before the key existed is preserved -------------

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD"})
        @DisplayName("off — rejects a declared Content-Length body on either bodyless method")
        void offRejectsContentLengthBody(HttpMethod method) {
            PipelineRequest request = request(method, Map.of("content-length", List.of("5")), 5L, true);

            assertThrows(GatewayException.class, () -> gate.process(request),
                    () -> "with the opt-in off, a body on " + method + " must still be rejected");
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD"})
        @DisplayName("off — rejects a body-present request with no declared Content-Length")
        void offRejectsBodyPresentWithoutContentLength(HttpMethod method) {
            PipelineRequest request = request(method, Map.of(), -1L, true);

            assertThrows(GatewayException.class, () -> gate.process(request),
                    () -> "the body-present leg must still fire for " + method);
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD"})
        @DisplayName("off — rejects Transfer-Encoding on either bodyless method")
        void offRejectsTransferEncoding(HttpMethod method) {
            PipelineRequest request = request(method, Map.of("transfer-encoding", List.of("chunked")), -1L, false);

            assertThrows(GatewayException.class, () -> gate.process(request),
                    () -> "chunked framing on " + method + " must still be rejected");
        }

        // --- Opt-in ON: exactly one leg relaxed, and only for GET ---------------------------------

        @Test
        @DisplayName("on — admits a GET carrying a Content-Length-framed body")
        void onAdmitsGetWithContentLengthBody() {
            PipelineRequest request = request(HttpMethod.GET, Map.of("content-length", List.of("42")), 42L, true);

            assertDoesNotThrow(() -> permissiveGate.process(request),
                    "the Elasticsearch _search shape is exactly what the opt-in admits");
        }

        @Test
        @DisplayName("on — STILL rejects a body-present GET with no declared Content-Length")
        void onStillRejectsBodyPresentGetWithoutContentLength() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(), -1L, true);

            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> permissiveGate.process(request),
                    "a body signalled without a declared Content-Length is not Content-Length-framed, "
                            + "so it lies outside the single leg the opt-in relaxes");
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("on — STILL rejects Transfer-Encoding on GET (no configuration relaxes it)")
        void onStillRejectsTransferEncodingOnGet() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(
                    "transfer-encoding", List.of("chunked")), -1L, true);

            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> permissiveGate.process(request),
                    "chunked framing on an otherwise-bodyless method is the smuggling shape the gate "
                            + "exists to constrain — the opt-in must never admit it");
            assertEquals(EventType.SECURITY_FILTER_VIOLATION, thrown.getEventType());
        }

        @Test
        @DisplayName("on — STILL rejects a GET carrying both Content-Length and Transfer-Encoding")
        void onStillRejectsClTeOnGet() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(
                    "content-length", List.of("5"),
                    "transfer-encoding", List.of("chunked")), 5L, true);

            assertThrows(GatewayException.class, () -> permissiveGate.process(request),
                    "the CL+TE desync primer is an independent check that runs before the body leg");
        }

        @Test
        @DisplayName("on — STILL rejects a GET carrying multiple Content-Length headers")
        void onStillRejectsMultipleContentLengthOnGet() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(
                    "content-length", List.of("5", "6")), 5L, true);

            assertThrows(GatewayException.class, () -> permissiveGate.process(request),
                    "ambiguous framing stays rejected — the opt-in admits a well-framed body only");
        }

        @Test
        @DisplayName("on — STILL rejects a GET whose Connection header strips a framing header")
        void onStillRejectsFramingStripOnGet() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(
                    "content-length", List.of("4"),
                    "connection", List.of("keep-alive, content-length")), 4L, true);

            assertThrows(GatewayException.class, () -> permissiveGate.process(request),
                    "the framing-header-strip defence is independent of the opt-in");
        }

        @Test
        @DisplayName("on — STILL rejects a HEAD carrying a declared body (HEAD is never relaxed)")
        void onStillRejectsBodyOnHead() {
            PipelineRequest request = request(HttpMethod.HEAD, Map.of("content-length", List.of("3")), 3L, true);

            assertThrows(GatewayException.class, () -> permissiveGate.process(request),
                    "the opt-in is scoped to GET — HEAD is unaffected on every leg");
        }

        @Test
        @DisplayName("on — STILL rejects a body-present HEAD with no declared Content-Length")
        void onStillRejectsBodyPresentHead() {
            PipelineRequest request = request(HttpMethod.HEAD, Map.of(), -1L, true);

            assertThrows(GatewayException.class, () -> permissiveGate.process(request),
                    "the opt-in never widens the HEAD body-present leg");
        }

        @Test
        @DisplayName("on — a clean GET with no body is admitted, as it always was")
        void onAdmitsCleanGet() {
            PipelineRequest request = request(HttpMethod.GET, Map.of(), -1L, false);

            assertDoesNotThrow(() -> permissiveGate.process(request));
        }
    }

    private static PipelineRequest request(HttpMethod method, Map<String, List<String>> headers,
            long contentLength, boolean bodyPresent) {
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/orders")
                .queryParameters(Map.of())
                .headers(headers)
                .declaredContentLength(contentLength)
                .bodyPresent(bodyPresent)
                .build();
    }
}
