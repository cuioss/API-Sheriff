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
package de.cuioss.sheriff.gateway.edge.assembly;

import de.cuioss.sheriff.gateway.config.model.Protocol;

/**
 * The path the edge serves a proxy route of a given protocol on — the single statement of the
 * protocol split. The assembler reads it to decide which routes get a shared client and guard
 * ({@link #usesDispatchStage()}), and the edge's protocol-dispatch switch reads it to pick the
 * dispatch, so the two can never disagree about which protocols
 * {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage} serves.
 * <p>
 * Both directions of that agreement are checked by the compiler. {@link #of} is an exhaustive
 * switch with no default arm: a {@link Protocol} constant added without a decided path breaks
 * compilation here, instead of reaching a request on a route that was assembled without the client
 * or guard its dispatch needs. The edge root's dispatch is in turn an exhaustive switch expression
 * over this enum with no default arm: a constant added here without a dispatch arm breaks
 * compilation there, instead of leaving a request on such a route unanswered.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum DispatchPath {

    /**
     * The HTTP dispatch over {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage}, serving
     * HTTP and GraphQL routes.
     */
    HTTP_DISPATCH(true),

    /**
     * The forced-HTTP/2 {@link de.cuioss.sheriff.gateway.edge.upstream.GrpcDispatchStage}, which
     * delegates to {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage}.
     */
    GRPC_DISPATCH(true),

    /** The opaque WebSocket relay, dialed by the edge-wide WebSocket client rather than a route client. */
    WEBSOCKET_RELAY(false);

    private final boolean dispatchStage;

    DispatchPath(boolean dispatchStage) {
        this.dispatchStage = dispatchStage;
    }

    /**
     * @param protocol the route's effective protocol
     * @return the path a proxy route of that protocol is served on
     */
    public static DispatchPath of(Protocol protocol) {
        return switch (protocol) {
            case HTTP, GRAPHQL -> HTTP_DISPATCH;
            case GRPC -> GRPC_DISPATCH;
            case WEBSOCKET -> WEBSOCKET_RELAY;
        };
    }

    /**
     * @return {@code true} when this path dispatches through
     *         {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage} and so needs the route's
     *         shared upstream client and resilience guard
     */
    boolean usesDispatchStage() {
        return dispatchStage;
    }
}
