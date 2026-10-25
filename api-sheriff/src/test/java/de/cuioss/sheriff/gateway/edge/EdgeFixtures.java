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

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;

import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.tls.EgressTrustProfileResolver;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

/**
 * The setup the tests that drive a live {@link GatewayEdgeRoute} share: a token validator, the
 * {@link Instance} the edge resolves it through, and the edge itself with every collaborator a case
 * does not name left at its inert default.
 * {@snippet :
 * GatewayEdgeRoute edge = EdgeFixtures.edge(vertx, virtualThreadExecutor)
 *         .routes(route)
 *         .runtime(runtime)
 *         .build();
 * HttpServer front = EdgeFixtures.serve(vertx, edge);
 * }
 * <p>
 * <strong>It never creates a Vert.x instance.</strong> The calling test class owns its
 * {@link Vertx} and hands it in. {@code SurefireForkGroupContractTest} reads the source of a test
 * class to decide whether it needs a JVM of its own, and creating Vert.x is one of the reasons it
 * looks for; a creation moved in here would hide that reason from the class that has it.
 * <p>
 * Public because the edge sub-package tests reach it from outside this package.
 * <p>
 * Thread-safe: the class is stateless, and every {@link Edge} is confined to the test that built it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EdgeFixtures {

    private EdgeFixtures() {
    }

    /**
     * A bearer-token validator trusting the issuer of a freshly generated access token.
     *
     * @return the validator
     */
    public static TokenValidator tokenValidator() {
        return TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
    }

    /**
     * An {@link Instance} that resolves to {@code value}. The edge calls only {@link Instance#get()},
     * and only when a {@code require: bearer} route validates a token. The three {@code select}
     * overloads, {@code getHandle} and {@code handles} throw {@link UnsupportedOperationException};
     * {@code isUnsatisfied} and {@code isAmbiguous} answer {@code false}, {@code iterator} yields the one
     * value, and {@code destroy} does nothing.
     *
     * @param value the bean the instance resolves to
     * @param <T>   the bean type
     * @return the instance
     */
    public static <T> Instance<T> singleton(T value) {
        return new SingletonInstance<>(value);
    }

    /**
     * Starts building an edge on the caller's Vert.x and virtual-thread executor.
     *
     * @param vertx                 the Vert.x instance the calling test class created
     * @param virtualThreadExecutor the executor the edge runs its pipeline on
     * @return the builder
     */
    public static Edge edge(Vertx vertx, ExecutorService virtualThreadExecutor) {
        return new Edge(vertx, virtualThreadExecutor);
    }

    /**
     * Registers {@code edge} on a new router and serves it on an ephemeral loopback port.
     *
     * @param vertx the Vert.x instance to serve on
     * @param edge  the edge to serve
     * @return the listening front server; the caller closes it
     * @throws Exception when the server does not start listening in time
     */
    public static HttpServer serve(Vertx vertx, GatewayEdgeRoute edge) throws Exception {
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        return Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
    }

    /**
     * An edge under construction. Each collaborator a case does not set keeps the default named on
     * its setter.
     */
    public static final class Edge {

        private final Vertx vertx;
        private final ExecutorService virtualThreadExecutor;
        private RouteTable routeTable = new RouteTable(List.of());
        private GatewayConfig gatewayConfig = ConfigFixtures.gateway().build();
        private TokenValidator tokenValidator = EdgeFixtures.tokenValidator();
        private EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        private SheriffMetrics metrics = new SheriffMetrics(new SimpleMeterRegistry());
        private BffRuntime runtime = BffRuntime.inert();
        private EgressTrustProfileResolver egressTrust = EgressTrustProfiles.unconsulted();
        private PortalEndpoint portal = PortalEndpoint.inert();

        private Edge(Vertx vertx, ExecutorService virtualThreadExecutor) {
            this.vertx = vertx;
            this.virtualThreadExecutor = virtualThreadExecutor;
        }

        /**
         * @param routes the routes of the table; default none
         * @return this builder
         */
        public Edge routes(Route... routes) {
            return routes(new RouteTable(List.of(routes)));
        }

        /**
         * @param table the route table; default an empty one
         * @return this builder
         */
        public Edge routes(RouteTable table) {
            this.routeTable = table;
            return this;
        }

        /**
         * @param config the gateway document; default {@link ConfigFixtures#gateway()}
         * @return this builder
         */
        public Edge gatewayConfig(GatewayConfig config) {
            this.gatewayConfig = config;
            return this;
        }

        /**
         * @param validator the bearer-token validator; default {@link EdgeFixtures#tokenValidator()}
         * @return this builder
         */
        public Edge tokenValidator(TokenValidator validator) {
            this.tokenValidator = validator;
            return this;
        }

        /**
         * @param options the transport and admission bounds; default {@code new EdgeHardeningOptions()}
         * @return this builder
         */
        public Edge hardening(EdgeHardeningOptions options) {
            this.hardening = options;
            return this;
        }

        /**
         * @param sheriffMetrics the metrics adapter; default one over a fresh {@link SimpleMeterRegistry}
         * @return this builder
         */
        public Edge metrics(SheriffMetrics sheriffMetrics) {
            this.metrics = sheriffMetrics;
            return this;
        }

        /**
         * @param bffRuntime the BFF runtime; default {@link BffRuntime#inert()}
         * @return this builder
         */
        public Edge runtime(BffRuntime bffRuntime) {
            this.runtime = bffRuntime;
            return this;
        }

        /**
         * @param resolver the egress trust-profile resolver; default {@link EgressTrustProfiles#unconsulted()}
         * @return this builder
         */
        public Edge egressTrust(EgressTrustProfileResolver resolver) {
            this.egressTrust = resolver;
            return this;
        }

        /**
         * @param endpoint the portal endpoint; default {@link PortalEndpoint#inert()}
         * @return this builder
         */
        public Edge portal(PortalEndpoint endpoint) {
            this.portal = endpoint;
            return this;
        }

        /**
         * Assembles the edge through its CDI constructor.
         *
         * @return the edge
         */
        public GatewayEdgeRoute build() {
            return new GatewayEdgeRoute(routeTable, gatewayConfig, singleton(tokenValidator), vertx,
                    virtualThreadExecutor, hardening, metrics, runtime, egressTrust, portal,
                    GatewayEdgeRouteBffWiringTest.gatewayJson());
        }

        /**
         * Assembles the edge and serves it, as {@link EdgeFixtures#serve(Vertx, GatewayEdgeRoute)} does.
         *
         * @return the listening front server; the caller closes it
         * @throws Exception when the server does not start listening in time
         */
        public HttpServer serve() throws Exception {
            return EdgeFixtures.serve(vertx, build());
        }
    }

    /** The {@link Instance} behind {@link #singleton(Object)}. */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        private SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
