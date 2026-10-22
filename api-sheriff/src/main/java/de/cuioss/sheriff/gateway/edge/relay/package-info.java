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
/**
 * Streaming an upstream answer back to the client: the response relay, the filtering of the
 * upstream's {@code Set-Cookie} lines, the gRPC status mapping, and the opaque WebSocket relay.
 * <p>
 * <strong>The {@code Set-Cookie} filter is driven by the route's {@code cookies} policy.</strong> A
 * line naming one of the gateway's own cookies is dropped under every mode; {@code allow: none}, the
 * default, drops every line; a list relays only the lines setting a listed cookie; {@code allow: all}
 * relays the rest. A relayed line loses its {@code Domain} attribute and keeps everything else as
 * sent. One shared predicate of the response relay applies the filter to the HTTP relay's headers and
 * to the gRPC relay's leading headers and trailers alike; the WebSocket relay relays no upstream
 * {@code Set-Cookie} line, on an accepted or a refused upgrade.
 * <p>
 * <strong>Contents.</strong> {@link de.cuioss.sheriff.gateway.edge.relay.ResponseStage} with its
 * package-private {@code UpstreamSetCookieFilter},
 * {@link de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper} and
 * {@link de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage}.
 * <p>
 * <strong>Dependencies.</strong> This package may depend on
 * {@link de.cuioss.sheriff.gateway.edge.end} and {@link de.cuioss.sheriff.gateway.edge.upstream}
 * only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.relay;

import org.jspecify.annotations.NullMarked;
