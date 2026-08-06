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
 * The gateway-owned connection-header policy for <strong>both</strong> directions — the header
 * names the gateway never relays, enumerated once per direction.
 * <p>
 * {@link #RESPONSE_STRIP} is the response-direction set: names an upstream or backing source may
 * propose but the gateway must never place on the client's connection. {@link #REQUEST_STRIP} is
 * the request-direction set: names a client may send but the gateway never forwards upstream. The
 * request-direction set is what makes the permissive forward-all baseline safe — without it a
 * forward-all route would send the BFF's sealed session cookie to every upstream, defeating the
 * pattern whose entire premise is that the backend never sees it.
 * <p>
 * <strong>Every path that answers a client reads the response set.</strong> The gateway has two:
 * the proxy data plane's streamed relay ({@code edge.ResponseStage}) and the asset terminal
 * action's buffered envelope ({@code asset.AssetResponseEnvelope}). They apply the policy
 * differently — the proxy path re-establishes framing afterwards because it streams a body it
 * does not hold, the asset path lets the framework compute the length of the buffer it writes —
 * but the <em>set</em> is one rule about one protocol, so it is declared once. Two copies of it
 * is not a style objection: issue #172 was exactly a header the proxy path stripped and the asset
 * path did not, and a mirrored list would have let the next such name diverge just as quietly.
 * <p>
 * <strong>The two sets are independent enumerations, not one derived from the other.</strong>
 * {@code REQUEST_STRIP} does currently contain every {@code RESPONSE_STRIP} name, but that is an
 * <em>observation</em> about the two lists as they stand — not a derivation and not an invariant.
 * A name added to one direction does not join the other: the directions answer different
 * questions, so each list is maintained on its own terms and neither is computed from its sibling.
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
     * The request-direction never-forward set, all lower case — the header names a client may send
     * that the gateway never relays upstream, whatever forward mode the route resolves.
     * <p>
     * Membership answers one of four questions, and it matters which applies to which name:
     * <ul>
     *   <li><strong>Credentials the gateway terminates or mediates.</strong> {@code authorization}
     *       (superseded by the mediated bearer), {@code cookie} (the BFF's sealed session cookie is
     *       the gateway's, never the backend's).</li>
     *   <li><strong>Hop-by-hop and connection-specific names</strong> describing the hop the gateway
     *       terminates rather than the message it forwards: {@code connection}, {@code keep-alive},
     *       {@code proxy-connection}, {@code te}, {@code trailer}, {@code transfer-encoding},
     *       {@code upgrade}, {@code proxy-authenticate}, {@code proxy-authorization}.</li>
     *   <li><strong>Framing the gateway re-establishes:</strong> {@code content-length} is a claim
     *       about the client's body, and the dispatch path frames what it actually sends.</li>
     *   <li><strong>Names the gateway itself owns or regenerates:</strong> {@code host} (the
     *       upstream authority is the gateway's to set), {@code origin} and {@code referer}
     *       (inbound provenance signals the gateway consumes for CORS/CSRF rather than relays), and
     *       {@code date} (the message the upstream receives is the gateway's, not the client's).</li>
     * </ul>
     * <p>
     * <strong>Three names are deliberately lifted from {@link #RESPONSE_STRIP} rather than
     * inherited</strong> — each is here on its own request-direction reasoning:
     * <ul>
     *   <li>{@code proxy-connection} — RFC 9113 §8.2.2 connection-specific <em>and</em>
     *       client-sendable, so unlike most connection-specific names it genuinely arrives inbound.</li>
     *   <li>{@code proxy-authorization} — RFC 7230 §6.1 hop-by-hop, and a credential for the hop the
     *       gateway itself terminates; forwarding it would hand the upstream a secret addressed to
     *       the gateway.</li>
     *   <li>{@code proxy-authenticate} — a response-direction header with no legitimate
     *       request-direction use at all. Withholding it costs one string and denies a header-
     *       smuggling vector; admitting it would buy nothing.</li>
     * </ul>
     * <p>
     * <strong>Two behaviours ride on top of membership, and neither lives in this literal.</strong>
     * {@code te} is withheld unless its value is exactly the {@code trailers} token, and
     * {@code authorization} is the single re-admittable member (honoured only when a route's
     * {@code headers_allow} names it). Both are value- or mode-dependent, so they belong to the
     * request-direction application in {@code forward.ForwardPolicyStage}, not to a set of names.
     */
    public static final Set<String> REQUEST_STRIP = Set.of(
            "authorization", "connection", "content-length", "cookie", "date", "host",
            "keep-alive", "origin", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "referer", "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * Whether {@code headerName} is withheld from the upstream request regardless of the route's
     * forward mode.
     * <p>
     * This is plain membership in {@link #REQUEST_STRIP}, matched case-insensitively. It answers
     * only "is this name gateway-owned?" — the {@code TE: trailers} carve-out and the single-member
     * {@code authorization} re-admission are value- and mode-dependent and are applied by the
     * caller, not decided here.
     *
     * @param headerName the client-sent request-header name; never {@code null}
     * @return {@code true} when the name is a {@link #REQUEST_STRIP} member
     */
    public static boolean isRequestStripped(String headerName) {
        Objects.requireNonNull(headerName, "headerName");
        return REQUEST_STRIP.contains(headerName.toLowerCase(Locale.ROOT));
    }

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
