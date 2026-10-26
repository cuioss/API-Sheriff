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
package de.cuioss.sheriff.gateway.http;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import lombok.experimental.UtilityClass;

/**
 * The gateway-owned response-direction <em>identity</em> set — the upstream response headers that
 * name the server software or framework behind the gateway, removed before a response reaches the
 * client unless the route opts out.
 * <p>
 * <strong>Membership follows the knowledge rule of ADR-0037.</strong> A name is a member when the
 * gateway knows it identifies the upstream's server software or framework, and for no other reason:
 * <ul>
 *   <li>{@code server} — RFC 9110 §10.2.4 defines it as a description of the origin's software, often
 *       down to its version ({@code nginx/1.27.0}).</li>
 *   <li>{@code x-powered-by} — the de-facto framework banner ({@code Express}, {@code PHP/8.3},
 *       {@code Servlet/6.0}), carrying no information a client needs.</li>
 * </ul>
 * A further name joins only when the same knowledge applies to it; the set is never widened to a
 * guess about what might identify something.
 * <p>
 * <strong>Why this is not a {@link ConnectionHeaders#RESPONSE_STRIP} member.</strong> ADR-0037 limits
 * that set to connection-specific and framing names — names that are <em>wrong</em> on the client's
 * connection. An identity header is well-formed on any connection; it is removed because it discloses
 * the upstream's fingerprint, which is a different reason with a different escape hatch (the per-route
 * opt-out). Merging the two would put an operator-overridable name into a set no route may override.
 * <p>
 * <strong>Removal, never rewriting.</strong> A member is dropped from the response; the gateway never
 * substitutes a neutral value such as {@code Server: gateway}. A rewritten banner would still be a
 * banner, now one the gateway asserts about itself.
 * <p>
 * <strong>Read by every path that relays upstream response headers</strong>: the proxy relay of
 * {@code edge.relay.ResponseStage} (HTTP responses, gRPC leading headers and gRPC trailers alike) and
 * the upstream-backed asset envelope {@code asset.AssetResponseEnvelope}. The set is declared once so
 * the paths cannot diverge — the lesson of issue #172 that {@link ConnectionHeaders} records.
 * <p>
 * <strong>Per-route opt-out.</strong> A route declaring {@code keep_identity_headers: true} keeps
 * the members on all of those paths; absent or {@code false}, they are stripped. The opt-out exists
 * for an upstream whose clients rely on its banner; boot validation refuses the key on a route that
 * relays no upstream response header.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class UpstreamIdentityHeaders {

    /** The identity-header names, all lower case. */
    public static final Set<String> NAMES = Set.of("server", "x-powered-by");

    /**
     * Whether {@code headerName} names the upstream's server software or framework.
     *
     * @param headerName the upstream-proposed response-header name; never {@code null}
     * @return {@code true} for a {@link #NAMES} member, matched case-insensitively under
     *         {@link Locale#ROOT}
     */
    public static boolean isIdentityHeader(String headerName) {
        Objects.requireNonNull(headerName, "headerName");
        return NAMES.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
