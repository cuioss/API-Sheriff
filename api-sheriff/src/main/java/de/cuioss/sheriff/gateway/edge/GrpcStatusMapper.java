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
package de.cuioss.sheriff.gateway.edge;

import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;

import io.vertx.core.http.HttpServerResponse;

/**
 * Maps a gateway rejection on a {@code protocol: grpc} route to the canonical gRPC status and renders
 * it as a <strong>trailers-only</strong> gRPC response (architecture.adoc § gRPC error contract).
 * <p>
 * A gRPC client cannot consume an {@code application/problem+json} body — the RPC runtime only
 * surfaces the {@code grpc-status} carried in the response headers/trailers. So a gateway rejection is
 * emitted as an HTTP {@code 200} whose {@code content-type} is {@code application/grpc} and whose
 * {@code grpc-status} (and {@code grpc-message}) name the failure, with no DATA frame — the gRPC
 * "Trailers-Only" case. The same rejection that renders as an HTTP status on an HTTP route maps onto
 * the canonical gRPC status by that HTTP status:
 * <ul>
 *   <li>{@code 400} → {@code INVALID_ARGUMENT} (3)</li>
 *   <li>{@code 401} → {@code UNAUTHENTICATED} (16)</li>
 *   <li>{@code 403} → {@code PERMISSION_DENIED} (7)</li>
 *   <li>{@code 404} → {@code NOT_FOUND} (5)</li>
 *   <li>{@code 405} → {@code UNIMPLEMENTED} (12)</li>
 *   <li>{@code 502} / {@code 503} → {@code UNAVAILABLE} (14) — also an h2-negotiation failure</li>
 *   <li>{@code 504} → {@code DEADLINE_EXCEEDED} (4)</li>
 *   <li>anything else → {@code UNKNOWN} (2)</li>
 * </ul>
 * The {@code grpc-message} carries the failure category slug only — never internal detail — exactly as
 * the problem+json contract does for HTTP routes.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GrpcStatusMapper {

    /** gRPC status code: the call completed with an unmapped / unknown error. */
    public static final int UNKNOWN = 2;
    /** gRPC status code: a client argument was invalid (maps HTTP 400). */
    public static final int INVALID_ARGUMENT = 3;
    /** gRPC status code: a deadline elapsed before completion (maps HTTP 504). */
    public static final int DEADLINE_EXCEEDED = 4;
    /** gRPC status code: the requested entity was not found (maps HTTP 404). */
    public static final int NOT_FOUND = 5;
    /** gRPC status code: the caller lacked permission (maps HTTP 403). */
    public static final int PERMISSION_DENIED = 7;
    /** gRPC status code: the operation is not implemented / not supported (maps HTTP 405). */
    public static final int UNIMPLEMENTED = 12;
    /** gRPC status code: the service is unavailable (maps HTTP 502 / 503 and h2-negotiation failure). */
    public static final int UNAVAILABLE = 14;
    /** gRPC status code: the request lacks valid authentication credentials (maps HTTP 401). */
    public static final int UNAUTHENTICATED = 16;

    private static final int GRPC_HTTP_STATUS = 200;
    private static final String GRPC_CONTENT_TYPE = "application/grpc";
    private static final String GRPC_STATUS_HEADER = "grpc-status";
    private static final String GRPC_MESSAGE_HEADER = "grpc-message";
    private static final String UNKNOWN_MESSAGE = "unknown";

    /**
     * Maps a gateway {@link EventType} to its canonical gRPC status code by the HTTP status the same
     * cause renders on an HTTP route.
     *
     * @param eventType the rejection event type
     * @return the canonical gRPC status code
     */
    public int toGrpcStatus(EventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return switch (eventType.httpStatus()) {
            case 400 -> INVALID_ARGUMENT;
            case 401 -> UNAUTHENTICATED;
            case 403 -> PERMISSION_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> UNIMPLEMENTED;
            case 502, 503 -> UNAVAILABLE;
            case 504 -> DEADLINE_EXCEEDED;
            default -> UNKNOWN;
        };
    }

    /**
     * Renders a trailers-only gRPC rejection to the client response: HTTP {@code 200},
     * {@code content-type: application/grpc}, and the mapped {@code grpc-status} / {@code grpc-message},
     * with no body. Stage-0 security headers are applied first so gateway-controlled headers win. A
     * response whose head is already written (a mid-stream failure) is left untouched.
     *
     * @param response      the client response
     * @param eventType     the rejection event type
     * @param stageHeaders  the stage-0 security headers accumulated on the request
     */
    public void renderRejection(HttpServerResponse response, EventType eventType, Map<String, String> stageHeaders) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(stageHeaders, "stageHeaders");
        if (response.ended() || response.headWritten()) {
            return;
        }
        response.setStatusCode(GRPC_HTTP_STATUS);
        stageHeaders.forEach(response::putHeader);
        response.putHeader("content-type", GRPC_CONTENT_TYPE);
        response.putHeader(GRPC_STATUS_HEADER, Integer.toString(toGrpcStatus(eventType)));
        response.putHeader(GRPC_MESSAGE_HEADER, grpcMessage(eventType));
        response.end();
    }

    private static String grpcMessage(EventType eventType) {
        EventCategory category = eventType.category();
        return category != null ? category.slug() : UNKNOWN_MESSAGE;
    }
}
