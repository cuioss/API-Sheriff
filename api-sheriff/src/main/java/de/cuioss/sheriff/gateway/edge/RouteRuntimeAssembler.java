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
package de.cuioss.sheriff.gateway.edge;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessor;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.RouteMatcher;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;

/**
 * Boot-time assembler compiling the frozen {@link RouteTable} into immutable
 * {@link RouteRuntime} instances, deduplicating the heavy collaborators so shared shapes
 * reuse one object rather than copying it:
 * <ul>
 *   <li>one resolved {@linkplain SecurityPosture security posture} — effective
 *       {@link SecurityProfile} plus its cui-http {@link SecurityConfiguration} — per distinct
 *       {@code Optional<}{@link SecurityFilterConfig}{@code >} shape. The posture is resolved for
 *       <em>every</em> route, including one that declares no {@code security_filter} block at all,
 *       so a gateway-wide {@code profile} reaches a block-less route;</li>
 *   <li>one Vert.x {@link HttpClient} per distinct {@linkplain UpstreamTarget upstream-target tuple}
 *       (scheme, host, port) — routes sharing a tuple hold the same client reference;</li>
 *   <li>one SmallRye Fault-Tolerance {@link Guard} per distinct {@linkplain ResilienceShape
 *       resilience shape}.</li>
 * </ul>
 * The heavy objects are produced by the injected factories (so tests supply fakes and the
 * production wiring supplies the real Vert.x / SmallRye instances). An unsupported protocol fails
 * boot through the {@link ProtocolProcessorRegistry}. {@code require: session} routes are compiled
 * like any other route — their stage-4 runtime is the
 * {@code de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage} (D4), which replaces the
 * boot-time rejection this assembler used to raise.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteRuntimeAssembler {

    private final ProtocolProcessorRegistry protocolRegistry;

    /**
     * @param protocolRegistry the registry selecting a processor per route and rejecting
     *                         unsupported protocols at boot
     */
    public RouteRuntimeAssembler(ProtocolProcessorRegistry protocolRegistry) {
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry");
    }

    /**
     * Compiles every route in {@code table} into a {@link RouteRuntime}, sharing the deduplicated
     * heavy collaborators.
     *
     * @param table                 the frozen route table
     * @param securityConfigFactory resolves one {@link SecurityPosture} per security-filter shape,
     *                              invoked for every route (an absent block included)
     * @param clientFactory         builds one {@link HttpClient} per upstream target tuple
     * @param guardFactory          builds one {@link Guard} per resilience shape
     * @param assetSourceFactory    builds the live {@link AssetSource} for an asset route's
     *                              terminal action
     * @return the assembled runtimes, in the table's longest-prefix-first order
     * @throws GatewayException when a route declares an unsupported protocol
     */
    public List<RouteRuntime> assemble(RouteTable table, SecurityConfigurationFactory securityConfigFactory,
            UpstreamClientFactory clientFactory, ResilienceGuardFactory guardFactory,
            AssetSourceFactory assetSourceFactory) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(securityConfigFactory, "securityConfigFactory");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(guardFactory, "guardFactory");
        Objects.requireNonNull(assetSourceFactory, "assetSourceFactory");

        Map<Optional<SecurityFilterConfig>, SecurityPosture> securityCache = new HashMap<>();
        Map<UpstreamTarget, HttpClient> clientCache = new HashMap<>();
        Map<ResilienceShape, Guard> guardCache = new HashMap<>();
        List<RouteRuntime> runtimes = new ArrayList<>();

        for (ResolvedRoute route : table.routes()) {
            ProtocolProcessor processor = protocolRegistry.require(route.protocol(), route.id());

            // Resolved for EVERY route, not only inside effectiveSecurityFilter().map(...): a
            // gateway-wide profile must also govern a route that declares no security_filter block.
            SecurityPosture posture = securityCache.computeIfAbsent(route.effectiveSecurityFilter(),
                    securityConfigFactory::create);

            RouteRuntime.RouteRuntimeBuilder runtime = RouteRuntime.builder()
                    .id(route.id())
                    .protocol(route.protocol())
                    .matcher(RouteMatcher.from(route.match()))
                    .protocolProcessor(processor)
                    .effectiveAllowedMethods(toMethodSet(route.effectiveAllowedMethods()))
                    .effectiveAuth(route.effectiveAuth())
                    .requiredScopes(route.effectiveAuth().requiredScopes())
                    // Both set unconditionally: RouteRuntime's @Builder.Default STRICT exists for the
                    // builder's test call sites, never as a stand-in for the production resolution.
                    .securityProfile(posture.profile())
                    .securityConfiguration(Optional.of(posture.configuration()))
                    .securityHeaders(route.effectiveSecurityHeaders())
                    .effectiveForward(route.effectiveForward())
                    .effectiveAllowedPaths(route.effectiveSecurityFilter()
                            .map(SecurityFilterConfig::allowedPaths).orElse(List.of()))
                    .retryEnabled(route.retryEnabled())
                    .notModifiedEnabled(route.notModifiedEnabled())
                    .effectiveAllowedOrigins(route.effectiveAllowedOrigins())
                    .effectiveWebSocketIdleTimeoutSeconds(route.effectiveWebSocketIdleTimeoutSeconds());

            // A route resolves exactly one terminal action (ADR-0014). An asset route builds its
            // live source and skips the Vert.x client / resilience-guard dedup entirely — its
            // egress rides the source's own SSRF-controlled fetch seam, not the proxy data plane.
            Optional<ResolvedAsset> asset = route.asset();
            if (asset.isPresent()) {
                runtime.assetSource(Optional.of(assetSourceFactory.create(asset.get())));
            } else {
                ResolvedUpstream resolvedUpstream = route.upstream().orElseThrow(() -> new GatewayException(
                        EventType.CONFIG_INVALID,
                        "Route '" + route.id() + "' resolves no terminal action (neither upstream nor asset)"));
                // gRPC requires HTTP/2 end-to-end, so the forced-h2 flag joins the client-sharing tuple:
                // a gRPC route to host:port holds a distinct forced-h2 client from an HTTP/1.1 route to
                // the same host:port.
                UpstreamTarget target = UpstreamTarget.of(resolvedUpstream, route.protocol() == Protocol.GRPC);
                HttpClient client = clientCache.computeIfAbsent(target, clientFactory::create);
                ResilienceShape shape = new ResilienceShape(target, route.retryEnabled());
                Guard guard = guardCache.computeIfAbsent(shape, guardFactory::create);
                runtime.upstream(Optional.of(resolvedUpstream))
                        .httpClient(Optional.of(client))
                        .resilienceGuard(Optional.of(guard));
            }

            runtimes.add(runtime.build());
        }
        return List.copyOf(runtimes);
    }

    private static Set<HttpMethod> toMethodSet(List<HttpMethod> methods) {
        return methods.isEmpty() ? EnumSet.noneOf(HttpMethod.class) : EnumSet.copyOf(methods);
    }

    /**
     * The upstream-target tuple keying Vert.x client dedup: routes sharing
     * (scheme, host, port, forced-h2) share one client instance. The {@code forcedHttp2} dimension
     * separates a gRPC route's forced-HTTP/2 client from an HTTP/1.1 client to the same host:port.
     *
     * @param scheme       the upstream scheme
     * @param host         the upstream host
     * @param port         the upstream port
     * @param forcedHttp2  whether the client is forced to HTTP/2 (a gRPC route)
     */
    public record UpstreamTarget(String scheme, String host, int port, boolean forcedHttp2) {

        /**
         * @param upstream     the resolved upstream
         * @param forcedHttp2  whether the client is forced to HTTP/2 (a gRPC route)
         * @return the target tuple for {@code upstream}
         */
        public static UpstreamTarget of(ResolvedUpstream upstream, boolean forcedHttp2) {
            return new UpstreamTarget(upstream.scheme(), upstream.host(), upstream.port(), forcedHttp2);
        }
    }

    /**
     * The resilience shape keying Fault-Tolerance guard dedup: routes sharing an upstream target
     * and retry posture share one guard instance.
     *
     * @param target       the upstream target
     * @param retryEnabled the materialized retry toggle
     */
    public record ResilienceShape(UpstreamTarget target, boolean retryEnabled) {
    }

    /**
     * A route's resolved inbound-filter posture: the effective {@link SecurityProfile} and the
     * concrete cui-http {@link SecurityConfiguration} that carries its limits.
     * <p>
     * The configuration is always present — even under {@link SecurityProfile#NONE}, which
     * disables validation but never limits, so the retained {@code max_body_bytes} guard keeps a
     * concrete {@code maxBodySize} to enforce.
     *
     * @param profile       the effective mode after the
     *                      {@code security_filter → security_defaults} fallback
     * @param configuration the limits policy: the nearest non-{@code none} profile's preset, with
     *                      the route's declared limit overrides applied on top
     */
    public record SecurityPosture(SecurityProfile profile, SecurityConfiguration configuration) {

        /**
         * Canonical constructor rejecting an absent component — a posture is fully resolved or it
         * is a boot defect.
         */
        public SecurityPosture {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    /**
     * Factory resolving one {@link SecurityPosture} for a security-filter shape. Invoked for every
     * route, so {@code filter} is {@link Optional#empty()} for a route that declares no
     * {@code security_filter} block and the gateway-wide fallback applies.
     */
    @FunctionalInterface
    public interface SecurityConfigurationFactory {

        /**
         * @param filter the route's effective security-filter shape, empty when it declares none
         * @return the resolved posture
         */
        SecurityPosture create(Optional<SecurityFilterConfig> filter);
    }

    /**
     * Factory building one Vert.x {@link HttpClient} for an upstream target tuple.
     */
    @FunctionalInterface
    public interface UpstreamClientFactory {

        /**
         * @param target the upstream target tuple
         * @return the built (or shared) client
         */
        HttpClient create(UpstreamTarget target);
    }

    /**
     * Factory building one SmallRye {@link Guard} for a resilience shape.
     */
    @FunctionalInterface
    public interface ResilienceGuardFactory {

        /**
         * @param shape the resilience shape
         * @return the built guard
         */
        Guard create(ResilienceShape shape);
    }

    /**
     * Factory building the live {@link AssetSource} for an asset route's resolved terminal
     * action — a directory reader for a {@code directory} source, an SSRF-guarded upstream
     * fetcher for an {@code upstream} source.
     */
    @FunctionalInterface
    public interface AssetSourceFactory {

        /**
         * @param asset the resolved asset terminal action
         * @return the built asset source
         */
        AssetSource create(ResolvedAsset asset);
    }
}
