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
package de.cuioss.sheriff.gateway.edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessor;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.http.HttpClient;
import org.jspecify.annotations.Nullable;

/**
 * The boot-only runtime assembly step: completes every {@link Route} of the frozen
 * {@link RouteTable} with the components whose construction needs a framework-backed factory, and
 * builds the shared upstream clients and resilience guards the proxy dispatch serves those routes
 * with.
 * <p>
 * <strong>Completing a route.</strong> Each table route is completed through
 * {@link Route#toBuilder()} followed by {@code build()} — never copied field by field — so every
 * resolved value the route-table builder set stays exactly as it was. Four components are attached:
 * <ul>
 *   <li>{@code protocolProcessor}, from the {@link ProtocolProcessorRegistry}, which fails boot on an
 *       unsupported protocol;</li>
 *   <li>{@code securityProfile} and {@code securityConfiguration}, from one resolved
 *       {@linkplain SecurityPosture security posture} per distinct {@link SecurityFilterConfig} shape
 *       ({@code null} being its own shape). The posture is resolved for <em>every</em> route,
 *       including one that declares no {@code security_filter} block at all, so a gateway-wide
 *       {@code profile} reaches a block-less route;</li>
 *   <li>{@code assetSource}, for an asset route only.</li>
 * </ul>
 * <p>
 * <strong>Upstream clients.</strong> The Vert.x {@link HttpClient} and the SmallRye Fault-Tolerance
 * {@link Guard} are not route components: they are framework types, and {@link Route} stays
 * framework-free so the pre-boot path can build it. They are held in the immutable
 * {@link UpstreamClients} lookup instead, deduplicated so shared shapes reuse one object:
 * <ul>
 *   <li>one {@link HttpClient} per distinct {@linkplain UpstreamTarget upstream-target tuple}
 *       (scheme, host, port, forced-HTTP/2);</li>
 *   <li>one {@link Guard} per distinct {@linkplain ResilienceShape resilience shape}.</li>
 * </ul>
 * The lookup is populated only for the proxy routes whose {@linkplain DispatchPath dispatch path}
 * runs through {@link DispatchStage} — {@link Protocol#HTTP}, {@link Protocol#GRAPHQL} and
 * {@link Protocol#GRPC}. A {@link Protocol#WEBSOCKET} route is dialed by the edge-wide WebSocket
 * client and gets neither a client nor a guard; an asset or redirect route never contacts a proxy
 * upstream and gets neither either. {@link DispatchPath} is the one statement of that protocol split,
 * read both here and by the edge's dispatch switch, so the two cannot disagree.
 * <p>
 * The heavy objects are produced by the injected factories, so tests supply fakes and the production
 * wiring supplies the real Vert.x / SmallRye instances. {@code require: session} routes are assembled
 * like any other route — their stage-4 runtime is the
 * {@code de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage}.
 * <p>
 * <strong>Thread safety.</strong> The assembler holds only the immutable registry; each
 * {@link #assemble} call works on its own local caches, so concurrent calls are safe. The returned
 * {@link AssembledRoutes} is immutable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteAssembler {

    private final ProtocolProcessorRegistry protocolRegistry;

    /**
     * @param protocolRegistry the registry selecting a processor per route and rejecting
     *                         unsupported protocols at boot
     */
    public RouteAssembler(ProtocolProcessorRegistry protocolRegistry) {
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry");
    }

    /**
     * Completes every route in {@code table} with its boot-attached components and builds the shared
     * upstream clients and guards.
     *
     * @param table                 the frozen route table
     * @param securityConfigFactory resolves one {@link SecurityPosture} per security-filter shape,
     *                              invoked for every route (an absent block included)
     * @param clientFactory         builds one {@link HttpClient} per upstream target tuple
     * @param guardFactory          builds one {@link Guard} per resilience shape
     * @param assetSourceFactory    builds the live {@link AssetSource} for an asset route's
     *                              terminal action
     * @return the assembled routes, in the table's exact-first then longest-prefix-first order,
     *         together with the upstream clients lookup
     * @throws de.cuioss.sheriff.gateway.events.GatewayException when a route declares an unsupported
     *                                                           protocol
     */
    public AssembledRoutes assemble(RouteTable table, SecurityConfigurationFactory securityConfigFactory,
            UpstreamClientFactory clientFactory, ResilienceGuardFactory guardFactory,
            AssetSourceFactory assetSourceFactory) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(securityConfigFactory, "securityConfigFactory");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(guardFactory, "guardFactory");
        Objects.requireNonNull(assetSourceFactory, "assetSourceFactory");

        Map<@Nullable SecurityFilterConfig, SecurityPosture> securityCache = new HashMap<>();
        Map<UpstreamTarget, HttpClient> clientCache = new HashMap<>();
        Map<ResilienceShape, Guard> guardCache = new HashMap<>();
        List<Route> assembled = new ArrayList<>();

        for (Route route : table.routes()) {
            ProtocolProcessor processor = protocolRegistry.require(route.protocol(), route.id());

            // Resolved for EVERY route, including one whose effective filter is null: a gateway-wide
            // profile must also govern a route that declares no security_filter block. The cache is a
            // HashMap precisely because it must key that absent shape under the null key.
            SecurityPosture posture = securityCache.computeIfAbsent(route.effectiveSecurityFilter(),
                    securityConfigFactory::create);

            Route.RouteBuilder completed = route.toBuilder()
                    .protocolProcessor(processor)
                    .securityProfile(posture.profile())
                    .securityConfiguration(posture.configuration());
            ResolvedAsset asset = route.asset();
            if (asset != null) {
                completed.assetSource(assetSourceFactory.create(asset));
            }

            // A route resolves exactly one terminal action (ADR-0014 and its Amendment A1). Only a proxy
            // route the HTTP dispatch serves needs a client and a guard: an asset route's egress rides the
            // source's own SSRF-controlled fetch seam, a redirect route never contacts an upstream, and a
            // WebSocket route is dialed by the edge-wide WebSocket client.
            ResolvedUpstream upstream = route.upstream();
            if (upstream != null && DispatchPath.of(route.protocol()).usesDispatchStage()) {
                ResilienceShape shape = ResilienceShape.of(route);
                clientCache.computeIfAbsent(shape.target(), clientFactory::create);
                guardCache.computeIfAbsent(shape, guardFactory::create);
            }

            assembled.add(completed.build());
        }
        return new AssembledRoutes(assembled, new UpstreamClients(clientCache, guardCache));
    }

    /**
     * The result of one {@link #assemble} call.
     *
     * @param routes          the assembled routes, in the table's exact-first then
     *                        longest-prefix-first order
     * @param upstreamClients the shared upstream clients and guards of the dispatched proxy routes
     */
    public record AssembledRoutes(List<Route> routes, UpstreamClients upstreamClients) {

        /**
         * Canonical constructor copying {@code routes} and requiring {@code upstreamClients}.
         */
        public AssembledRoutes {
            routes = List.copyOf(routes);
            Objects.requireNonNull(upstreamClients, "upstreamClients");
        }
    }

    /**
     * The immutable lookup of the shared upstream clients and resilience guards the proxy dispatch
     * serves a route with, keyed by the route's {@link UpstreamTarget} and {@link ResilienceShape}.
     * <p>
     * <strong>Thread safety.</strong> Both maps are copied into unmodifiable form, so the lookup is
     * safe to share across threads.
     *
     * @param clients one Vert.x client per upstream target tuple
     * @param guards  one Fault-Tolerance guard per resilience shape
     */
    public record UpstreamClients(Map<UpstreamTarget, HttpClient> clients, Map<ResilienceShape, Guard> guards) {

        /**
         * Canonical constructor copying both maps into unmodifiable form, an absent one becoming empty.
         */
        public UpstreamClients {
            clients = clients == null ? Map.of() : Map.copyOf(clients);
            guards = guards == null ? Map.of() : Map.copyOf(guards);
        }

        /**
         * @param target the upstream target tuple
         * @return the shared client for {@code target}, or empty when no dispatched route targets it
         */
        public Optional<HttpClient> client(UpstreamTarget target) {
            return Optional.ofNullable(clients.get(target));
        }

        /**
         * @param shape the resilience shape
         * @return the shared guard for {@code shape}, or empty when no dispatched route has it
         */
        public Optional<Guard> guard(ResilienceShape shape) {
            return Optional.ofNullable(guards.get(shape));
        }
    }

    /**
     * The upstream-target tuple keying Vert.x client dedup: routes sharing
     * (scheme, host, port, forced-h2) share one client instance. The {@code forcedHttp2} dimension
     * separates a gRPC route's forced-HTTP/2 client from an HTTP/1.1 client to the same host:port.
     *
     * @param scheme      the upstream scheme
     * @param host        the upstream host
     * @param port        the upstream port
     * @param forcedHttp2 whether the client is forced to HTTP/2 (a gRPC route)
     */
    public record UpstreamTarget(String scheme, String host, int port, boolean forcedHttp2) {

        /**
         * @param upstream    the resolved upstream
         * @param forcedHttp2 whether the client is forced to HTTP/2 (a gRPC route)
         * @return the target tuple for {@code upstream}
         */
        public static UpstreamTarget of(ResolvedUpstream upstream, boolean forcedHttp2) {
            return new UpstreamTarget(upstream.scheme(), upstream.host(), upstream.port(), forcedHttp2);
        }

        /**
         * The target tuple of a proxy route: its resolved upstream, forced to HTTP/2 for a gRPC route,
         * since gRPC requires HTTP/2 end-to-end.
         *
         * @param route the proxy route
         * @return the target tuple for the route's upstream
         * @throws IllegalStateException when the route resolves no upstream (an asset or redirect route)
         */
        public static UpstreamTarget of(Route route) {
            ResolvedUpstream upstream = route.upstream();
            if (upstream == null) {
                throw new IllegalStateException("route '" + route.id() + "' resolves no proxy upstream");
            }
            return of(upstream, route.protocol() == Protocol.GRPC);
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

        /**
         * @param route the proxy route
         * @return the resilience shape of the route: its target tuple and retry toggle
         * @throws IllegalStateException when the route resolves no upstream (an asset or redirect route)
         */
        public static ResilienceShape of(Route route) {
            return new ResilienceShape(UpstreamTarget.of(route), route.retryEnabled());
        }
    }

    /**
     * The path the edge serves a proxy route of a given protocol on — the single statement of the
     * protocol split. The assembler reads it to decide which routes get a shared client and guard
     * ({@link #usesDispatchStage()}), and the edge's protocol-dispatch switch reads it to pick the
     * dispatch, so the two can never disagree about which protocols {@link DispatchStage} serves.
     * <p>
     * {@link #of} is an exhaustive switch with no default arm: a {@link Protocol} constant added
     * without a decided path breaks compilation here, instead of reaching a request on a route that was
     * assembled without the client or guard its dispatch needs.
     */
    enum DispatchPath {

        /** The HTTP dispatch over {@link DispatchStage}, serving HTTP and GraphQL routes. */
        HTTP_DISPATCH(true),

        /** The forced-HTTP/2 {@link GrpcDispatchStage}, which delegates to {@link DispatchStage}. */
        GRPC_DISPATCH(true),

        /** The opaque WebSocket relay, dialed by the edge-wide WebSocket client rather than a route client. */
        WEBSOCKET_RELAY(false);

        private final boolean dispatchStage;

        DispatchPath(boolean dispatchStage) {
            this.dispatchStage = dispatchStage;
        }

        /**
         * @param protocol the route's effective protocol
         * @return the path a proxy route of that protocol is served on
         */
        static DispatchPath of(Protocol protocol) {
            return switch (protocol) {
                case HTTP, GRAPHQL -> HTTP_DISPATCH;
                case GRPC -> GRPC_DISPATCH;
                case WEBSOCKET -> WEBSOCKET_RELAY;
            };
        }

        /**
         * @return {@code true} when this path dispatches through {@link DispatchStage} and so needs the
         *         route's shared upstream client and resilience guard
         */
        boolean usesDispatchStage() {
            return dispatchStage;
        }
    }

    /**
     * A route's resolved inbound-filter posture: the effective {@link SecurityProfile} and the
     * concrete cui-http {@link SecurityConfiguration} that carries its limits.
     * <p>
     * The configuration is always present — even under {@link SecurityProfile#MINIMAL}, which
     * disables validation but never limits, so the retained {@code max_body_bytes} guard keeps a
     * concrete {@code maxBodySize} to enforce.
     *
     * @param profile       the effective mode after the
     *                      {@code security_filter → security_defaults} fallback
     * @param configuration the limits policy: the nearest non-{@code minimal} profile's preset, with
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
     * route, so {@code filter} is {@code null} for a route that declares no
     * {@code security_filter} block and the gateway-wide fallback applies.
     */
    @FunctionalInterface
    public interface SecurityConfigurationFactory {

        /**
         * @param filter the route's effective security-filter shape, {@code null} when it declares
         *               none
         * @return the resolved posture
         */
        SecurityPosture create(@Nullable SecurityFilterConfig filter);
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
