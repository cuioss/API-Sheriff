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

import java.util.EnumSet;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;

/**
 * The WebSocket processor. A WebSocket route is entered by the HTTP upgrade handshake — a
 * {@code GET} carrying {@code Upgrade: websocket} — so the only proxyable verb it serves is
 * {@link HttpMethod#GET}. Once the handshake is validated (Origin allowlist) and the upstream
 * confirms {@code 101}, the connection leaves the request/response verb model entirely and is
 * relayed opaquely by the edge's {@code WebSocketRelayStage}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class WebSocketProtocolProcessor implements ProtocolProcessor {

    private static final Set<HttpMethod> UPGRADE_METHODS = EnumSet.of(HttpMethod.GET);

    @Override
    public String id() {
        return "websocket";
    }

    @Override
    public Set<HttpMethod> standardMethods() {
        return UPGRADE_METHODS;
    }

    @Override
    public boolean supports(HttpMethod method) {
        return UPGRADE_METHODS.contains(method);
    }
}
