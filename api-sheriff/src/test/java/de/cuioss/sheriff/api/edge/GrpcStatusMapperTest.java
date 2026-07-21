/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.api.events.EventType;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link GrpcStatusMapper}: the canonical HTTP-status → gRPC-status mapping (one row per
 * rejection cause, architecture.adoc § gRPC error contract) and the trailers-only rejection render
 * (HTTP {@code 200}, {@code content-type: application/grpc}, {@code grpc-status} / {@code grpc-message},
 * no DATA frame). The render is exercised over a live Vert.x server so a real
 * {@code HttpServerResponse} is mutated exactly as production does.
 */
@DisplayName("GrpcStatusMapper — HTTP→gRPC status mapping and trailers-only rejection render")
class GrpcStatusMapperTest {

    private final GrpcStatusMapper mapper = new GrpcStatusMapper();

    @Nested
    @DisplayName("HTTP-status → gRPC-status mapping (one row per rejection cause)")
    class StatusMapping {

        @Test
        @DisplayName("400 (input validation) maps to INVALID_ARGUMENT")
        void mapsBadRequest() {
            assertEquals(GrpcStatusMapper.INVALID_ARGUMENT, mapper.toGrpcStatus(EventType.SECURITY_FILTER_VIOLATION),
                    "an HTTP 400 rejection maps to gRPC INVALID_ARGUMENT (3)");
        }

        @Test
        @DisplayName("401 (authentication) maps to UNAUTHENTICATED")
        void mapsUnauthenticated() {
            assertEquals(GrpcStatusMapper.UNAUTHENTICATED, mapper.toGrpcStatus(EventType.TOKEN_MISSING),
                    "an HTTP 401 rejection maps to gRPC UNAUTHENTICATED (16)");
        }

        @Test
        @DisplayName("403 (authorization) maps to PERMISSION_DENIED")
        void mapsPermissionDenied() {
            assertEquals(GrpcStatusMapper.PERMISSION_DENIED, mapper.toGrpcStatus(EventType.SCOPE_MISSING),
                    "an HTTP 403 rejection maps to gRPC PERMISSION_DENIED (7)");
        }

        @Test
        @DisplayName("404 (no route matched) maps to NOT_FOUND")
        void mapsNotFound() {
            assertEquals(GrpcStatusMapper.NOT_FOUND, mapper.toGrpcStatus(EventType.NO_ROUTE_MATCHED),
                    "an HTTP 404 rejection maps to gRPC NOT_FOUND (5)");
        }

        @Test
        @DisplayName("405 (method not allowed) maps to UNIMPLEMENTED")
        void mapsUnimplemented() {
            assertEquals(GrpcStatusMapper.UNIMPLEMENTED, mapper.toGrpcStatus(EventType.METHOD_NOT_ALLOWED),
                    "an HTTP 405 rejection maps to gRPC UNIMPLEMENTED (12)");
        }

        @Test
        @DisplayName("502 (upstream error / h2-negotiation failure) maps to UNAVAILABLE")
        void mapsBadGatewayToUnavailable() {
            assertEquals(GrpcStatusMapper.UNAVAILABLE, mapper.toGrpcStatus(EventType.UPSTREAM_ERROR),
                    "an HTTP 502 upstream failure maps to gRPC UNAVAILABLE (14)");
        }

        @Test
        @DisplayName("503 (circuit open) maps to UNAVAILABLE")
        void mapsServiceUnavailableToUnavailable() {
            assertEquals(GrpcStatusMapper.UNAVAILABLE, mapper.toGrpcStatus(EventType.UPSTREAM_CIRCUIT_OPEN),
                    "an HTTP 503 open-circuit rejection maps to gRPC UNAVAILABLE (14)");
        }

        @Test
        @DisplayName("504 (upstream timeout) maps to DEADLINE_EXCEEDED")
        void mapsGatewayTimeoutToDeadlineExceeded() {
            assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED, mapper.toGrpcStatus(EventType.UPSTREAM_TIMEOUT),
                    "an HTTP 504 upstream timeout maps to gRPC DEADLINE_EXCEEDED (4)");
        }

        @Test
        @DisplayName("an event with no HTTP mapping falls through to UNKNOWN")
        void mapsUnmappedToUnknown() {
            assertEquals(GrpcStatusMapper.UNKNOWN, mapper.toGrpcStatus(EventType.CONFIG_INVALID),
                    "an event that renders no HTTP status maps to gRPC UNKNOWN (2)");
        }

        @Test
        @DisplayName("rejects a null event type")
        void rejectsNullEventType() {
            assertThrows(NullPointerException.class, () -> mapper.toGrpcStatus(null),
                    "the mapper requires a non-null event type");
        }
    }

    @Nested
    @DisplayName("trailers-only rejection render over a live Vert.x server")
    class RejectionRender {

        private Vertx vertx;
        private HttpClient client;
        private HttpServer server;

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("renders HTTP 200, application/grpc, and the mapped grpc-status / grpc-message")
        void rendersTrailersOnlyRejection() throws Exception {
            // Act — a 403 authorization rejection on a gRPC route
            HttpClientResponse response = render(EventType.SCOPE_MISSING, Map.of());

            // Assert — a gRPC client observes an HTTP 200 whose grpc-status names the failure
            assertEquals(200, response.statusCode(), "a trailers-only gRPC rejection is an HTTP 200");
            assertEquals("application/grpc", response.getHeader("content-type"),
                    "the response content type is application/grpc so the RPC runtime consumes it");
            assertEquals(Integer.toString(GrpcStatusMapper.PERMISSION_DENIED), response.getHeader("grpc-status"),
                    "the grpc-status carries the mapped PERMISSION_DENIED code");
            assertEquals("authorization", response.getHeader("grpc-message"),
                    "the grpc-message carries the failure category slug only, never internal detail");
        }

        @Test
        @DisplayName("applies stage-0 security headers, and the gateway content-type wins a name collision")
        void appliesStageHeadersGatewayWins() throws Exception {
            // Arrange — a stage-0 security header plus a colliding content-type the gateway must override
            Map<String, String> stageHeaders = Map.of(
                    "X-Frame-Options", "DENY",
                    "content-type", "text/plain");

            // Act
            HttpClientResponse response = render(EventType.TOKEN_MISSING, stageHeaders);

            // Assert — the security header passes through and the gRPC content-type wins the collision
            assertEquals("DENY", response.getHeader("X-Frame-Options"),
                    "a stage-0 security header is applied to the gRPC rejection response");
            assertEquals("application/grpc", response.getHeader("content-type"),
                    "the gateway-controlled content type wins over a colliding stage header");
            assertEquals(Integer.toString(GrpcStatusMapper.UNAUTHENTICATED), response.getHeader("grpc-status"),
                    "a 401 rejection maps to gRPC UNAUTHENTICATED");
        }

        @Test
        @DisplayName("falls back to a 'unknown' grpc-message for a null-category event")
        void rendersUnknownMessageForNullCategory() throws Exception {
            // Act — WEBSOCKET_IDLE_TIMEOUT has no category and no HTTP mapping
            HttpClientResponse response = render(EventType.WEBSOCKET_IDLE_TIMEOUT, Map.of());

            // Assert
            assertEquals(Integer.toString(GrpcStatusMapper.UNKNOWN), response.getHeader("grpc-status"),
                    "an unmapped event renders gRPC UNKNOWN");
            assertEquals("unknown", response.getHeader("grpc-message"),
                    "a null-category event renders the 'unknown' grpc-message fallback");
        }

        private HttpClientResponse render(EventType eventType, Map<String, String> stageHeaders) throws Exception {
            server = vertx.createHttpServer()
                    .requestHandler(req -> mapper.renderRejection(req.response(), eventType, stageHeaders))
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            int port = server.actualPort();
            return client.request(HttpMethod.POST, port, "localhost", "/svc.Service/Method")
                    .compose(req -> req.send())
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("rejects a null response on render")
    void rejectsNullResponse() {
        assertThrows(NullPointerException.class,
                () -> mapper.renderRejection(null, EventType.SCOPE_MISSING, Map.of()),
                "the render requires a non-null response");
    }
}
