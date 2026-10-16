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
package de.cuioss.sheriff.gateway.routing;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The one immutable per-route type: every inherited setting materialized once into its effective
 * value, every pure derivation of those values, and the runtime collaborators the request pipeline
 * serves the route with. The pipeline consumes these already-resolved values on the hot path and
 * never re-implements inheritance or compiles anything per request.
 * <p>
 * The inheritance chains are resolved by the route-table builder — effective auth (route override
 * wholesale, else the endpoint default, else the anchor floor), effective {@code allowed_methods}
 * (endpoint list if declared, else the anchor list, else the global list, else the standard set),
 * effective {@code security_filter} (route block, else the endpoint block, else the anchor block),
 * effective {@code forward} (route block, else the endpoint block), effective
 * {@code security_headers} (anchor block, else the gateway block), and the effective retry /
 * not-modified toggles (global {@code upstream_defaults}, wholesale-replaced by an endpoint block,
 * overridden per route). Anchors (ADR-0007) vanish here: only the resolving anchor's name is
 * retained, for the boot-log posture line.
 * <p>
 * <strong>Two-pass construction.</strong> A route is built in two passes and never copied field by
 * field between them:
 * <ol>
 *   <li><em>Policy resolution, pre-boot.</em> The framework-free route-table builder, which also runs
 *       on the offline {@code --validate-config} path, sets every resolved value ({@code id},
 *       {@code protocol}, {@code anchor}, {@code match}, {@code effectiveAuth},
 *       {@code effectiveAllowedMethods}, {@code neededScopes}, {@code effectiveSecurityFilter},
 *       {@code securityHeaders}, {@code effectiveForward}, {@code retryEnabled},
 *       {@code notModifiedEnabled}, the one terminal action {@code upstream} / {@code asset} /
 *       {@code redirect}, {@code effectiveAllowedOrigins} and
 *       {@code effectiveWebSocketIdleTimeoutSeconds}) plus the pure derivations {@code matcher},
 *       {@code effectiveAllowedPaths} and {@code locationRewriter}. This pass starts nothing.</li>
 *   <li><em>Runtime assembly, boot only.</em> The assembly step attaches the components whose
 *       construction needs a framework-backed factory — {@code protocolProcessor},
 *       {@code securityProfile}, {@code securityConfiguration} and, for an asset route,
 *       {@code assetSource} — through the Lombok-generated {@code toBuilder()}, leaving every resolved value
 *       unchanged.</li>
 * </ol>
 * A route the first pass built therefore carries {@code null} for the boot-attached nullable
 * components and the fail-closed {@link SecurityProfile#STRICT} profile until the second pass
 * completes it; only assembled routes reach the request pipeline.
 * <p>
 * <strong>Framework-free.</strong> This type holds no Vert.x or SmallRye type: the shared upstream
 * client and the resilience guard are kept per upstream tuple outside the route, so the pre-boot
 * path can build a route without starting anything (ADR-0061, ADR-0062). Absence is a
 * {@link Nullable} component, never a stored {@code Optional} (ADR-0033).
 * <p>
 * <strong>Thread safety.</strong> Instances are immutable and safe to share across threads: every
 * collection component is defensively copied into an unmodifiable view, and every other component
 * is either immutable or a collaborator shared read-only by design.
 *
 * @param id                     the route id, also the bounded metrics {@code route} label
 *                               (mandatory)
 * @param protocol               the effective protocol (defaults to {@link Protocol#HTTP})
 * @param anchor                 the resolving anchor name, {@code null} when the route is
 *                               unanchored
 * @param match                  the resolved matcher set (mandatory)
 * @param matcher                the compiled matcher set (exact path or prefix, methods, host,
 *                               headers); compiled from {@code match} when absent
 * @param effectiveAuth          the materialized auth posture (mandatory)
 * @param effectiveAllowedMethods the effective {@code allowed_methods} verb allowlist for the
 *                               stage-2b verb gate (405), copied as an {@link EnumSet}; empty when
 *                               absent
 * @param neededScopes           the scope set a request on this route needs —
 *                               {@code oidc.scopes} united with the owning endpoint's
 *                               {@code scopes}, derived exactly once so every reader reads one
 *                               value. A bearer route checks the token against it
 *                               ({@code 403 insufficient_scope} on a shortfall); a session route
 *                               requests it at login and compares the session's active scope set
 *                               against it on every request. Empty when neither level declares a
 *                               scope, which skips both checks
 * @param effectiveSecurityFilter the materialized security filter, {@code null} when none resolves
 * @param effectiveAllowedPaths  the effective {@code allowed_paths} whitelist enforced by stage 3,
 *                               resolved from the effective {@code security_filter}; derived from
 *                               {@code effectiveSecurityFilter} when absent, so it is empty only when
 *                               the route declares no whitelist (unrestricted). Carried separately from
 *                               {@code securityConfiguration} because {@code allowed_paths} is a
 *                               gateway concept the cui-http {@link SecurityConfiguration} does not
 *                               model
 * @param securityHeaders        the effective response-header posture (the anchor block, else the
 *                               gateway block, resolved wholesale), applied by the stage-2a
 *                               {@code SecurityHeadersStage.applyRouteHeaders} to every response
 *                               produced after route selection; {@code null} when none resolves,
 *                               which leaves no gateway-owned security header on those responses
 * @param effectiveForward       the effective {@code forward} filter consumed by stage 5 — the
 *                               per-route {@code headers_allow} / {@code headers_deny} /
 *                               {@code query_allow} / {@code query_deny} / {@code set_headers}
 *                               sets. An absent value becomes an all-absent {@link ForwardConfig},
 *                               the <em>forward-all</em> posture on both dimensions: a permissive
 *                               default because deny-by-default lives at the URL layer, and what
 *                               does cross stays bounded by the gateway-owned never-forward set
 * @param retryEnabled           the materialized upstream-retry toggle (meaningful only for a
 *                               proxy route)
 * @param notModifiedEnabled     the materialized HTTP-304 not-modified toggle (meaningful only for
 *                               a proxy route)
 * @param upstream               the resolved upstream target, present when the route's terminal
 *                               action is proxy and {@code null} for an asset or redirect route
 * @param locationRewriter       the {@code upstream.rewrite_location} mapping applied to an upstream
 *                               {@code Location} response header; {@code null} for a proxy route
 *                               that does not opt in and for every asset or redirect route (the
 *                               {@code Location} is then relayed unchanged)
 * @param asset                  the resolved asset terminal action, present when the route serves
 *                               assets and {@code null} otherwise
 * @param redirect               the redirect terminal action answered at the gateway (status and
 *                               {@code Location}, no upstream contact), {@code null} otherwise; a
 *                               route resolves exactly one terminal action, so {@code upstream},
 *                               {@code asset} and {@code redirect} are mutually exclusive
 *                               (ADR-0014 and its Amendment A1)
 * @param effectiveAllowedOrigins the materialized, lower-cased exact-match {@code Origin} allowlist
 *                               enforced on a WebSocket upgrade (GW-09 / CSWSH); empty for a
 *                               non-WebSocket route, and empty (no enforcement) only for a
 *                               {@code require: none} WebSocket route that declares no allowlist
 * @param effectiveWebSocketIdleTimeoutSeconds the materialized WebSocket idle timeout with the
 *                               {@code 300}-second default applied, {@code null} for a
 *                               non-WebSocket route
 * @param protocolProcessor      the protocol processor serving this route (shared across
 *                               same-protocol routes); attached at boot, {@code null} on a route
 *                               the pre-boot pass built
 * @param securityProfile        the resolved effective inbound-filter mode the stage-3 checks branch
 *                               their skippable half on; attached at boot. An absent value is
 *                               {@link SecurityProfile#STRICT}, the fail-closed value, so a route
 *                               the boot pass has not completed over-enforces rather than silently
 *                               skipping validation
 * @param securityConfiguration  the deduplicated cui-http security configuration carrying this
 *                               route's limits; attached at boot. {@code null} is the fail-closed
 *                               absent value, which the stage-3 checks resolve against their
 *                               default configuration
 * @param assetSource            the live asset source serving an asset route's terminal action (a
 *                               directory reader or an SSRF-guarded upstream fetcher); attached at
 *                               boot for an asset route, {@code null} otherwise
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder(toBuilder = true)
public record Route(
String id,
Protocol protocol,
@Nullable String anchor,
MatchConfig match,
RouteMatcher matcher,
AuthConfig effectiveAuth,
Set<HttpMethod> effectiveAllowedMethods,
Set<String> neededScopes,
@Nullable SecurityFilterConfig effectiveSecurityFilter,
List<String> effectiveAllowedPaths,
@Nullable SecurityHeadersConfig securityHeaders,
ForwardConfig effectiveForward,
boolean retryEnabled,
boolean notModifiedEnabled,
@Nullable ResolvedUpstream upstream,
@Nullable LocationRewriter locationRewriter,
@Nullable ResolvedAsset asset,
@Nullable RedirectConfig redirect,
Set<String> effectiveAllowedOrigins,
@Nullable Integer effectiveWebSocketIdleTimeoutSeconds,
@Nullable ProtocolProcessor protocolProcessor,
SecurityProfile securityProfile,
@Nullable SecurityConfiguration securityConfiguration,
@Nullable AssetSource assetSource) {

    /**
     * Canonical constructor requiring the mandatory components ({@code id}, {@code match},
     * {@code effectiveAuth}), enforcing the terminal-action invariant — exactly one of
     * {@code upstream} (proxy), {@code asset} or {@code redirect} resolves — and applying the
     * defaults: an absent {@code protocol} becomes {@link Protocol#HTTP}, an absent {@code matcher}
     * is compiled from {@code match}, an absent {@code effectiveAllowedPaths} is taken from the
     * effective security filter's {@code allowed_paths} (fail-closed: a declared whitelist is never
     * dropped by omission), an absent {@code effectiveForward} becomes the all-absent
     * forward-all {@link ForwardConfig}, an absent {@code securityProfile} becomes the fail-closed
     * {@link SecurityProfile#STRICT}, and every collection is defensively copied into an
     * unmodifiable form, an absent one becoming empty.
     *
     * @throws NullPointerException     when {@code id}, {@code match} or {@code effectiveAuth} is
     *                                  absent
     * @throws IllegalArgumentException when the route resolves zero or more than one terminal action
     */
    public Route {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(effectiveAuth, "effectiveAuth");
        int terminalActions = (upstream != null ? 1 : 0) + (asset != null ? 1 : 0) + (redirect != null ? 1 : 0);
        if (terminalActions != 1) {
            throw new IllegalArgumentException("route '" + id
                    + "' must resolve exactly one terminal action (one of upstream, asset, redirect)");
        }
        protocol = protocol == null ? Protocol.HTTP : protocol;
        matcher = matcher == null ? RouteMatcher.from(match) : matcher;
        effectiveAllowedMethods = toMethodSet(effectiveAllowedMethods);
        neededScopes = neededScopes == null ? Set.of() : Set.copyOf(neededScopes);
        // Fail-closed derivation, mirroring the matcher default above: an absent whitelist is taken from
        // the effective security filter, so a route carrying a filter that declares allowed_paths can
        // never reach stage 3 unrestricted merely because the derived component was not stated.
        if (effectiveAllowedPaths != null) {
            effectiveAllowedPaths = List.copyOf(effectiveAllowedPaths);
        } else if (effectiveSecurityFilter != null) {
            effectiveAllowedPaths = effectiveSecurityFilter.allowedPaths();
        } else {
            effectiveAllowedPaths = List.of();
        }
        effectiveForward = effectiveForward == null ? ForwardConfig.builder().build() : effectiveForward;
        effectiveAllowedOrigins = effectiveAllowedOrigins == null ? Set.of() : Set.copyOf(effectiveAllowedOrigins);
        securityProfile = securityProfile == null ? SecurityProfile.STRICT : securityProfile;
    }

    /**
     * The route's match key — the exact {@code path} of an exact route, otherwise its
     * {@code path_prefix} — the precedence and ordering key carried by the {@link #match()} block.
     *
     * @return the declared exact path or path prefix of this route
     * @see MatchConfig#matchKey()
     */
    public String matchKey() {
        return match.matchKey();
    }

    private static Set<HttpMethod> toMethodSet(@Nullable Set<HttpMethod> methods) {
        EnumSet<HttpMethod> copy = EnumSet.noneOf(HttpMethod.class);
        if (methods != null) {
            copy.addAll(methods);
        }
        return Collections.unmodifiableSet(copy);
    }
}
