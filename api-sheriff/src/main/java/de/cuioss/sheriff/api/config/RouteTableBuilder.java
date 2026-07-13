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
package de.cuioss.sheriff.api.config;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.MatchConfig.HeaderMatcher;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;

/**
 * Assembles the immutable {@link RouteTable} from the validated configuration
 * (pipeline step 8).
 * <p>
 * The builder merges the routes of the <em>enabled endpoints only</em> — disabled
 * endpoints contribute no rows — orders them by descending {@code path_prefix}
 * length (most specific first), enforces same-prefix disjointness, and
 * materializes each route's effective auth, effective {@code allowed_methods}, and
 * effective retry / not-modified toggles into a {@link ResolvedRoute}. The three
 * inheritance chains are resolved here, once, so the request pipeline never
 * re-implements them.
 * <p>
 * Framework-agnostic (ADR-0005): the collaborators are supplied as method
 * arguments and the builder carries no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RouteTableBuilder {

    private static final List<HttpMethod> STANDARD_ALLOWED_METHODS = List.copyOf(EnumSet.allOf(HttpMethod.class));

    /**
     * Builds the route table from the enabled endpoints and the resolved topology.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the endpoints to merge; disabled entries are skipped
     * @param topology  the resolved topology providing each endpoint's upstream
     * @return the immutable, longest-prefix-ordered route table
     * @throws RouteTableException when an enabled endpoint's alias does not resolve
     *                             or two same-prefix routes are not disjoint
     */
    public RouteTable build(GatewayConfig gateway, List<EndpointConfig> endpoints, ResolvedTopology topology) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(endpoints, "endpoints");
        Objects.requireNonNull(topology, "topology");

        List<ResolvedRoute> resolved = new ArrayList<>();
        for (EndpointConfig endpoint : endpoints) {
            if (!endpoint.enabled()) {
                continue;
            }
            ResolvedUpstream upstream = topology.lookup(endpoint.baseUrl()).orElseThrow(() -> new RouteTableException(
                    "unresolved topology alias for enabled endpoint '%s': %s".formatted(endpoint.id(),
                            endpoint.baseUrl())));
            UpstreamDefaultsConfig defaults = resolveDefaults(gateway, endpoint);
            List<HttpMethod> allowedMethods = effectiveAllowedMethods(gateway, endpoint);
            for (RouteConfig route : endpoint.routes()) {
                resolved.add(resolveRoute(route, endpoint, upstream, defaults, allowedMethods));
            }
        }

        resolved.sort(Comparator.comparingInt((ResolvedRoute route) -> route.pathPrefix().length()).reversed()
                .thenComparing(ResolvedRoute::pathPrefix));
        enforceDisjointness(resolved);
        return new RouteTable(resolved);
    }

    private static ResolvedRoute resolveRoute(RouteConfig route, EndpointConfig endpoint, ResolvedUpstream upstream,
            UpstreamDefaultsConfig defaults, List<HttpMethod> allowedMethods) {
        AuthConfig auth = route.auth().orElse(endpoint.auth());
        boolean retryEnabled = route.upstream()
                .flatMap(UpstreamConfig::retry)
                .flatMap(UpstreamConfig.Retry::enabled)
                .orElse(defaults.retryEnabled());
        boolean notModifiedEnabled = route.upstream()
                .flatMap(UpstreamConfig::notModified)
                .flatMap(UpstreamConfig.NotModified::enabled)
                .orElse(defaults.notModifiedEnabled());
        return ResolvedRoute.builder()
                .id(route.id())
                .protocol(route.protocol().orElse(Protocol.HTTP))
                .match(route.match())
                .effectiveAuth(auth)
                .effectiveAllowedMethods(allowedMethods)
                .retryEnabled(retryEnabled)
                .notModifiedEnabled(notModifiedEnabled)
                .upstream(upstream)
                .build();
    }

    private static UpstreamDefaultsConfig resolveDefaults(GatewayConfig gateway, EndpointConfig endpoint) {
        UpstreamDefaultsConfig global = gateway.upstreamDefaults().orElseGet(UpstreamDefaultsConfig::defaults);
        return endpoint.upstreamDefaults().orElse(global);
    }

    private static List<HttpMethod> effectiveAllowedMethods(GatewayConfig gateway, EndpointConfig endpoint) {
        if (!endpoint.allowedMethods().isEmpty()) {
            return List.copyOf(endpoint.allowedMethods());
        }
        if (!gateway.allowedMethods().isEmpty()) {
            return List.copyOf(gateway.allowedMethods());
        }
        return STANDARD_ALLOWED_METHODS;
    }

    private static void enforceDisjointness(List<ResolvedRoute> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                ResolvedRoute first = routes.get(i);
                ResolvedRoute second = routes.get(j);
                if (first.pathPrefix().equals(second.pathPrefix()) && overlaps(first.match(), second.match())) {
                    throw new RouteTableException(
                            "routes '%s' and '%s' share prefix '%s' and are not disjoint".formatted(first.id(),
                                    second.id(), first.pathPrefix()));
                }
            }
        }
    }

    private static boolean overlaps(MatchConfig first, MatchConfig second) {
        return hostsOverlap(first, second) && methodsOverlap(first, second) && !headersDistinguish(first, second);
    }

    private static boolean hostsOverlap(MatchConfig first, MatchConfig second) {
        return first.host().isEmpty() || second.host().isEmpty() || first.host().equals(second.host());
    }

    private static boolean methodsOverlap(MatchConfig first, MatchConfig second) {
        return first.methods().isEmpty() || second.methods().isEmpty()
                || !Collections.disjoint(first.methods(), second.methods());
    }

    private static boolean headersDistinguish(MatchConfig first, MatchConfig second) {
        for (HeaderMatcher headerA : first.headers()) {
            for (HeaderMatcher headerB : second.headers()) {
                if (headerA.name().equalsIgnoreCase(headerB.name())
                        && (valuesDistinguish(headerA, headerB) || presenceDistinguishes(headerA, headerB))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean valuesDistinguish(HeaderMatcher headerA, HeaderMatcher headerB) {
        Optional<String> valueA = headerA.value();
        Optional<String> valueB = headerB.value();
        return valueA.isPresent() && valueB.isPresent() && !valueA.get().equals(valueB.get());
    }

    private static boolean presenceDistinguishes(HeaderMatcher headerA, HeaderMatcher headerB) {
        Optional<Boolean> presentA = headerA.present();
        Optional<Boolean> presentB = headerB.present();
        return presentA.isPresent() && presentB.isPresent() && !presentA.get().equals(presentB.get());
    }

    /**
     * Signals a route-table assembly failure: an enabled endpoint whose alias does
     * not resolve, or two same-prefix routes that are not disjoint. Both are boot
     * failures for an otherwise structurally valid configuration.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class RouteTableException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with the given detail message.
         *
         * @param message the human-readable description of the assembly failure
         */
        public RouteTableException(String message) {
            super(message);
        }
    }
}
