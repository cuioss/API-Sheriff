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
package de.cuioss.sheriff.api.edge;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.api.asset.AssetSource;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedAsset;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.ProtocolProcessor;
import de.cuioss.sheriff.api.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.api.routing.RouteMatcher;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;

/**
 * Boot-time assembler compiling the frozen {@link RouteTable} into immutable
 * {@link RouteRuntime} instances, deduplicating the heavy collaborators so shared shapes
 * reuse one object rather than copying it:
 * <ul>
 *   <li>one cui-http {@link SecurityConfiguration} per distinct {@link SecurityFilterConfig} shape;</li>
 *   <li>one Vert.x {@link HttpClient} per distinct {@linkplain UpstreamTarget upstream-target tuple}
 *       (scheme, host, port) — routes sharing a tuple hold the same client reference;</li>
 *   <li>one SmallRye Fault-Tolerance {@link Guard} per distinct {@linkplain ResilienceShape
 *       resilience shape}.</li>
 * </ul>
 * The heavy objects are produced by the injected factories (so tests supply fakes and the
 * production wiring supplies the real Vert.x / SmallRye instances). A route requesting
 * {@code session} auth, or a {@code GRPC} / {@code WEBSOCKET} protocol, fails boot with a
 * {@link GatewayException} carrying {@link EventType#CONFIG_INVALID}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteRuntimeAssembler {

    private static final String SESSION_REQUIRE = "session";

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
     * @param securityConfigFactory builds one {@link SecurityConfiguration} per security-filter shape
     * @param clientFactory         builds one {@link HttpClient} per upstream target tuple
     * @param guardFactory          builds one {@link Guard} per resilience shape
     * @param assetSourceFactory    builds the live {@link AssetSource} for an asset route's
     *                              terminal action
     * @return the assembled runtimes, in the table's longest-prefix-first order
     * @throws GatewayException when a route requests {@code session} auth or an unsupported protocol
     */
    public List<RouteRuntime> assemble(RouteTable table, SecurityConfigurationFactory securityConfigFactory,
            UpstreamClientFactory clientFactory, ResilienceGuardFactory guardFactory,
            AssetSourceFactory assetSourceFactory) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(securityConfigFactory, "securityConfigFactory");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(guardFactory, "guardFactory");
        Objects.requireNonNull(assetSourceFactory, "assetSourceFactory");

        Map<SecurityFilterConfig, SecurityConfiguration> securityCache = new HashMap<>();
        Map<UpstreamTarget, HttpClient> clientCache = new HashMap<>();
        Map<ResilienceShape, Guard> guardCache = new HashMap<>();
        List<RouteRuntime> runtimes = new ArrayList<>();

        for (ResolvedRoute route : table.routes()) {
            rejectUnsupportedAuth(route);
            ProtocolProcessor processor = protocolRegistry.require(route.protocol(), route.id());

            Optional<SecurityConfiguration> securityConfiguration = route.effectiveSecurityFilter()
                    .map(filter -> securityCache.computeIfAbsent(filter, securityConfigFactory::create));

            RouteRuntime.RouteRuntimeBuilder runtime = RouteRuntime.builder()
                    .id(route.id())
                    .protocol(route.protocol())
                    .matcher(RouteMatcher.from(route.match()))
                    .protocolProcessor(processor)
                    .effectiveAllowedMethods(toMethodSet(route.effectiveAllowedMethods()))
                    .effectiveAuth(route.effectiveAuth())
                    .requiredScopes(route.effectiveAuth().requiredScopes())
                    .securityConfiguration(securityConfiguration)
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

    private static void rejectUnsupportedAuth(ResolvedRoute route) {
        if (SESSION_REQUIRE.equals(route.effectiveAuth().require())) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "Route '" + route.id() + "' requires session authentication which is not yet implemented");
        }
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
     * Factory building one cui-http {@link SecurityConfiguration} for a security-filter shape.
     */
    @FunctionalInterface
    public interface SecurityConfigurationFactory {

        /**
         * @param filter the security-filter shape
         * @return the built security configuration
         */
        SecurityConfiguration create(SecurityFilterConfig filter);
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
