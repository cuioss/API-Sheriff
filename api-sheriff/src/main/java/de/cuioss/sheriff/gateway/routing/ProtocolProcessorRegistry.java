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
package de.cuioss.sheriff.gateway.routing;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * Boot-time registry mapping each supported {@link Protocol} to its {@link ProtocolProcessor}.
 * {@code HTTP} and {@code GRAPHQL} share a single {@link HttpProtocolProcessor} instance;
 * {@code WEBSOCKET} is served by a dedicated {@link WebSocketProtocolProcessor}, and {@code GRPC}
 * by a dedicated {@link GrpcProtocolProcessor}. Every supported protocol is now registered, so
 * {@link #require(Protocol, String)} only fails boot for a protocol that is genuinely absent from
 * the {@link Protocol} enum's served set.
 * <p>
 * A {@code protocol: websocket} route with {@code session} auth is still boot-rejected upstream by
 * {@code RouteRuntimeAssembler} (session-auth WebSocket routes remain unimplemented until Plan 07);
 * this registry only decides which processor serves the protocol.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ProtocolProcessorRegistry {

    private final Map<Protocol, ProtocolProcessor> processors;

    /**
     * Builds the default registry: a shared HTTP processor for {@code HTTP} and {@code GRAPHQL},
     * a dedicated WebSocket processor for {@code WEBSOCKET}, and a dedicated gRPC processor for
     * {@code GRPC}.
     */
    public ProtocolProcessorRegistry() {
        HttpProtocolProcessor http = new HttpProtocolProcessor();
        Map<Protocol, ProtocolProcessor> map = new EnumMap<>(Protocol.class);
        map.put(Protocol.HTTP, http);
        map.put(Protocol.GRAPHQL, http);
        map.put(Protocol.WEBSOCKET, new WebSocketProtocolProcessor());
        map.put(Protocol.GRPC, new GrpcProtocolProcessor());
        this.processors = Collections.unmodifiableMap(map);
    }

    /**
     * Resolves the processor for a route's protocol, failing boot when the protocol is not served.
     *
     * @param protocol the route's effective protocol
     * @param routeId  the route id, for the failure message
     * @return the processor serving {@code protocol}
     * @throws GatewayException carrying {@link EventType#CONFIG_INVALID} when the protocol is unsupported
     */
    public ProtocolProcessor require(Protocol protocol, String routeId) {
        Objects.requireNonNull(protocol, "protocol");
        ProtocolProcessor processor = processors.get(protocol);
        if (processor == null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "Route '" + routeId + "' requests protocol " + protocol + " which is not yet implemented");
        }
        return processor;
    }

    /**
     * @param protocol the protocol to test
     * @return {@code true} when a processor is registered for {@code protocol}
     */
    public boolean supports(Protocol protocol) {
        return processors.containsKey(protocol);
    }
}
