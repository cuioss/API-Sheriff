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
package de.cuioss.sheriff.api.routing;

import java.util.List;
import java.util.Optional;
import java.util.Set;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.api.asset.AssetSource;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.ForwardConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;
import lombok.Builder;
import lombok.Getter;

/**
 * The immutable, boot-time-compiled runtime for one route. The request pipeline consumes
 * these already-resolved values on the hot path and never re-derives inheritance.
 * <p>
 * <strong>Framework-coupled by design (operator resolution 2026-07-19).</strong> Unlike the
 * agnostic core, this type holds the shared data-plane Vert.x {@link HttpClient} reference and
 * the per-route SmallRye Fault-Tolerance {@link Guard} directly — there is no separate
 * {@code edge.RouteBinding}. Consequently the {@code routing} package is excluded from the
 * ADR-0005 framework-agnostic arch-gate. The client and guard are shared instances handed in
 * by the {@code RouteRuntimeAssembler}; this type holds references, it does not construct them.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
@Getter
public final class RouteRuntime {

    /** The route id (also the bounded metrics {@code route} label). */
    private final String id;

    /** The effective protocol. */
    private final Protocol protocol;

    /** The compiled matcher set (prefix, methods, host, headers). */
    private final RouteMatcher matcher;

    /** The protocol processor serving this route (shared across same-protocol routes). */
    private final ProtocolProcessor protocolProcessor;

    /** The effective {@code allowed_methods} verb allowlist for the stage-2b verb gate (405). */
    private final Set<HttpMethod> effectiveAllowedMethods;

    /** The materialized auth posture. */
    private final AuthConfig effectiveAuth;

    /** The required scopes enforced for this route (empty when none). */
    private final List<String> requiredScopes;

    /** The deduplicated cui-http security configuration, empty when the route declares none. */
    private final Optional<SecurityConfiguration> securityConfiguration;

    /** The effective response-header posture, empty when none resolves. */
    private final Optional<SecurityHeadersConfig> securityHeaders;

    /**
     * The effective, deny-by-default {@code forward} allowlist consumed by stage 5 — the
     * per-route {@code headers_allow} / {@code query_allow} / {@code set_headers} sets resolved
     * once at boot. An empty {@link ForwardConfig} when the route declares no {@code forward} block.
     */
    @Builder.Default
    private final ForwardConfig effectiveForward = ForwardConfig.builder().build();

    /**
     * The effective {@code allowed_paths} whitelist enforced by stage 3 — the per-route path
     * allowlist resolved once at boot from the route's {@code security_filter} block. Empty when
     * the route declares no whitelist (unrestricted). Carried separately from
     * {@link #securityConfiguration} because {@code allowed_paths} is a gateway concept the cui-http
     * {@link SecurityConfiguration} does not model.
     */
    @Builder.Default
    private final List<String> effectiveAllowedPaths = List.of();

    /** The materialized upstream-retry toggle (meaningful only for a proxy route). */
    private final boolean retryEnabled;

    /** The materialized HTTP-304 not-modified toggle (meaningful only for a proxy route). */
    private final boolean notModifiedEnabled;

    /**
     * The resolved upstream target for a proxy route; empty for an asset route. A route
     * carries exactly one terminal action — a proxy {@link #upstream} or an
     * {@link #assetSource} — never both (ADR-0014).
     */
    @Builder.Default
    private final Optional<ResolvedUpstream> upstream = Optional.empty();

    /**
     * The shared Vert.x client for a proxy route's upstream tuple (one instance per
     * tuple); empty for an asset route.
     */
    @Builder.Default
    private final Optional<HttpClient> httpClient = Optional.empty();

    /**
     * The shared SmallRye Fault-Tolerance guard for a proxy route's resilience shape;
     * empty for an asset route.
     */
    @Builder.Default
    private final Optional<Guard> resilienceGuard = Optional.empty();

    /**
     * The live asset source serving an asset route's terminal action (a directory
     * reader or an SSRF-guarded upstream fetcher); empty for a proxy route.
     */
    @Builder.Default
    private final Optional<AssetSource> assetSource = Optional.empty();

    /**
     * The materialized, lower-cased exact-match {@code Origin} allowlist enforced on a WebSocket
     * upgrade (GW-09 / CSWSH). Empty for a non-WebSocket route, and empty (no enforcement) for a
     * non-bearer WebSocket route that declares no allowlist — a bearer WebSocket route always
     * resolves a non-empty allowlist (fail-closed at boot).
     */
    @Builder.Default
    private final Set<String> effectiveAllowedOrigins = Set.of();

    /**
     * The materialized WebSocket idle timeout with the {@code 300}-second default applied; empty
     * for a non-WebSocket route. Bounds an established relay — no frame in either direction, with
     * ping/pong counting as activity.
     */
    @Builder.Default
    private final Optional<Integer> effectiveWebSocketIdleTimeoutSeconds = Optional.empty();
}
