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
package de.cuioss.sheriff.gateway.edge.relay;

import java.util.Objects;

import de.cuioss.sheriff.gateway.http.GatewayCookies;

/**
 * Decides whether a {@code Set-Cookie} line an upstream sent is relayed to the client.
 * <p>
 * <strong>The gateway's cookies are set by the gateway alone.</strong> Every cookie under the
 * reserved {@value GatewayCookies#RESERVED_PREFIX} prefix, the configured session cookie and, in
 * cookie mode, its activity cookie are written by the gateway and by nothing else — with or without
 * a session runtime. A relayed upstream response shares the browser's cookie jar with them, so a
 * {@code Set-Cookie} line of an upstream that names one of them is dropped; every other cookie an
 * upstream sets is relayed exactly as it was sent.
 * <p>
 * <strong>How a line is matched</strong> is the user-agent reading {@link GatewayCookies} owns: the
 * name before the first {@code =} of the pair, the {@code __Host-} / {@code __Secure-} prefixes
 * compared without regard to case, and a nameless cookie whose value begins with a gateway cookie
 * name followed by {@code =} counted as that cookie.
 * <p>
 * Immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class UpstreamSetCookieFilter {

    private static final String SET_COOKIE = "Set-Cookie";

    private final GatewayCookies gatewayCookies;

    /**
     * @param gatewayCookies the gateway's own cookies, which no upstream may set
     */
    UpstreamSetCookieFilter(GatewayCookies gatewayCookies) {
        this.gatewayCookies = Objects.requireNonNull(gatewayCookies, "gatewayCookies");
    }

    /**
     * @param headerName  the upstream response-header name
     * @param headerValue the upstream response-header value
     * @return {@code false} exactly when the header is a {@code Set-Cookie} line naming a gateway
     *         cookie; {@code true} for every other header, which this filter does not judge
     */
    boolean relays(String headerName, String headerValue) {
        if (!SET_COOKIE.equalsIgnoreCase(headerName)) {
            return true;
        }
        return !gatewayCookies.namesGatewayCookie(headerValue);
    }
}
