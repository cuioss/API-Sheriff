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
package de.cuioss.sheriff.gateway.edge.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
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
 *   <li>{@code assetSource}, for an asset route only, built with the route's
 *       {@code keep_identity_headers} opt-out.</li>
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
 * runs through {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage} —
 * {@link Protocol#HTTP}, {@link Protocol#GRAPHQL} and
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
     *                              terminal action, handed the route's {@code keep_identity_headers}
     *                              opt-out
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
                completed.assetSource(assetSourceFactory.create(asset, route.keepIdentityHeaders()));
            }

            // A route resolves exactly one terminal action (ADR-0014). Only a proxy
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
}
