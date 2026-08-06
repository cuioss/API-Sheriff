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
package de.cuioss.sheriff.gateway.http;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import lombok.experimental.UtilityClass;

/**
 * The single response-direction connection-header policy — the set of header names an upstream
 * or backing source may propose but the gateway must never place on the client's connection.
 * <p>
 * <strong>Every path that answers a client reads this one set.</strong> The gateway has two:
 * the proxy data plane's streamed relay ({@code edge.ResponseStage}) and the asset terminal
 * action's buffered envelope ({@code asset.AssetResponseEnvelope}). They apply the policy
 * differently — the proxy path re-establishes framing afterwards because it streams a body it
 * does not hold, the asset path lets the framework compute the length of the buffer it writes —
 * but the <em>set</em> is one rule about one protocol, so it is declared once. Two copies of it
 * is not a style objection: issue #172 was exactly a header the proxy path stripped and the asset
 * path did not, and a mirrored list would have let the next such name diverge just as quietly.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ConnectionHeaders {

    /**
     * The connection-specific and framing response headers, all lower case.
     * <p>
     * The membership has two independent sources, and it matters which name comes from which:
     * <ul>
     *   <li><strong>RFC 9113 §8.2.2</strong> names {@code Connection}, {@code Proxy-Connection},
     *       {@code Keep-Alive}, {@code Transfer-Encoding} and {@code Upgrade} as connection-specific
     *       and declares any HTTP/2 message carrying one <em>malformed</em>. This is not advisory:
     *       the client discards the whole stream — the {@code PROTOCOL_ERROR} of issue #172, where
     *       an origin's routine {@code Connection: keep-alive} made a served asset arrive as nothing
     *       at all. {@code TE} is admitted by the RFC only with the value {@code trailers}; the
     *       gateway does not relay it in the response direction at all.</li>
     *   <li><strong>RFC 7230 §6.1</strong> adds the remaining hop-by-hop names ({@code Trailer},
     *       {@code Proxy-Authenticate}, {@code Proxy-Authorization}), which describe the hop the
     *       gateway terminates rather than the message it forwards.</li>
     *   <li><strong>{@code Content-Length}</strong> is framing, not hop-by-hop, and is stripped for
     *       a protocol-independent reason: it is a claim about the <em>source's</em> body, and
     *       neither relay writes that body verbatim in every case. Each path re-establishes the
     *       framing it can actually honour.</li>
     * </ul>
     */
    public static final Set<String> RESPONSE_STRIP = Set.of(
            "connection", "proxy-connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "content-length");

    /**
     * Whether {@code headerName} belongs to the source's own connection rather than to the message
     * the gateway relays.
     *
     * @param headerName the source-proposed response-header name; never {@code null}
     * @return {@code true} for a {@link #RESPONSE_STRIP} member (matched case-insensitively) or for
     *         an HTTP/2 pseudo-header — any name beginning with {@code ':'}, which a source reached
     *         over HTTP/2 reports among its response headers and which is malformed by definition
     *         inside a normal header block
     */
    public static boolean isConnectionSpecific(String headerName) {
        Objects.requireNonNull(headerName, "headerName");
        return headerName.startsWith(":")
                || RESPONSE_STRIP.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
