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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Objects;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A single {@code routes[]} entry of an endpoint file.
 * <p>
 * An absent {@code auth} block inherits the endpoint's (or anchor's) {@code auth}
 * wholesale; a route-level block replaces it wholesale (no field merging). An
 * absent {@code protocol} means {@link Protocol#HTTP}. {@code anchor}, when
 * present, overrides the endpoint's default anchor membership for this route
 * (ADR-0007).
 *
 * @param id             the route id, unique across all endpoint files (mandatory)
 * @param protocol       the served protocol, {@code null} meaning HTTP
 * @param anchor         the per-route anchor override, {@code null} when the endpoint
 *                       anchor applies
 * @param match          the matcher set (mandatory)
 * @param auth           the route-level auth override, {@code null} when inheriting the
 *                       endpoint/anchor default
 * @param securityFilter the route-level security filter, {@code null} when the route declares
 *                       none and the endpoint's, else the anchor's block applies; with none
 *                       declared the profile comes from {@code security_defaults}
 *                       ({@code PolicyBlock#SECURITY_FILTER})
 * @param forward        the route-level forward filter, {@code null} when the route declares
 *                       none and the endpoint's block applies; with neither declared the
 *                       posture is forward-all on both dimensions, not a nothing-crosses one
 *                       ({@code PolicyBlock#FORWARD})
 * @param cookies        the route-level {@code cookies} block — a block of its own, not a key inside
 *                       {@code forward} — {@code null} when the route declares none and the nearest
 *                       of the endpoint's, the anchor's and the gateway's block applies; with none
 *                       declared no cookie crosses ({@code PolicyBlock#COOKIES}). Refused on an asset
 *                       or redirect route, which has no upstream to forward a cookie to
 * @param upstream      the upstream target settings, {@code null} when omitted
 * @param asset          the asset terminal-action settings, {@code null} when omitted
 * @param redirect       the redirect terminal-action settings, {@code null} when omitted;
 *                       a route carries at most one terminal action, so
 *                       {@code upstream}, {@code asset} and {@code redirect} are
 *                       mutually exclusive (ADR-0014 and its Amendment A1)
 * @param rateLimit      the reserved rate-limit block, {@code null} when omitted
 * @param websocket      the per-route WebSocket settings ({@code allowed_origins},
 *                       {@code idle_timeout_seconds}), {@code null} for non-WebSocket routes
 * @param keepIdentityHeaders the per-route {@code keep_identity_headers} opt-out: {@code true}
 *                       keeps the upstream's server-identity headers ({@code Server} and
 *                       {@code X-Powered-By}, see {@code UpstreamIdentityHeaders}) on every response path of
 *                       this route; {@code null} (absent) and {@code false} both mean they are
 *                       stripped. A per-route scalar, not a policy block (ADR-0067): it has no
 *                       endpoint, anchor or gateway scope. Refused at boot on a redirect, a
 *                       directory-asset and a WebSocket route, which relay no upstream response
 *                       header
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record RouteConfig(
String id,
@Nullable Protocol protocol,
@Nullable String anchor,
MatchConfig match,
@Nullable AuthConfig auth,
@Nullable SecurityFilterConfig securityFilter,
@Nullable ForwardConfig forward,
@Nullable CookiesConfig cookies,
@Nullable UpstreamConfig upstream,
@Nullable AssetConfig asset,
@Nullable RedirectConfig redirect,
@Nullable RateLimitConfig rateLimit,
@Nullable WebSocketConfig websocket,
@Nullable Boolean keepIdentityHeaders) {

    /**
     * Canonical constructor requiring {@code id} and {@code match}.
     */
    public RouteConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
    }

    /**
     * Returns whether this route is a proxy route, i.e. it declares neither an
     * {@code asset} nor a {@code redirect} terminal action and therefore forwards to
     * the endpoint's upstream.
     * <p>
     * This is the single shared predicate behind the conditional {@code base_url}
     * rule: an endpoint must declare {@code base_url} exactly when at least one of its
     * routes is a proxy route. Both the route-table assembly and the configuration
     * validator consume it, so the rule cannot drift between the two.
     *
     * @return {@code true} when the route proxies to an upstream
     */
    public boolean isProxyRoute() {
        return asset == null && redirect == null;
    }
}
