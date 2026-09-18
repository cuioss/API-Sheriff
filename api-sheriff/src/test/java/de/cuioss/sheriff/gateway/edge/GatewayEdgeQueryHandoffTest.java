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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The raw-query hand-off at the public edge (ADR-0047, AS-13), driven over a live Vert.x server against
 * an echo upstream.
 * <p>
 * The property under test is <strong>validated equals forwarded</strong>: the per-route filter judges
 * the query in its raw, still-percent-encoded wire form, and the upstream receives exactly those bytes.
 * Every accepted case therefore asserts the upstream saw the query verbatim, and every rejected case
 * asserts the upstream saw nothing at all. The {@code strict} route runs the {@link SecurityProfile#STRICT}
 * policy, which deviates from the cui-http strict preset in exactly one component: a parameter value
 * that decodes to CR or LF is refused.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — the raw query is validated and forwarded as one form")
class GatewayEdgeQueryHandoffTest {

    /** The path the prefix-stripped request reaches the upstream at. */
    private static final String UPSTREAM_PATH = "/orders";

    /** Counts the requests that actually reached the echo upstream. */
    private final AtomicInteger upstreamHits = new AtomicInteger();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private HttpServer frontServer;
    private HttpClient client;
    /** The transport's decoded view of {@code q}, captured by a probe registered ahead of the edge. */
    private final AtomicReference<@Nullable String> transportDecodedQ = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamHits.set(0);
        transportDecodedQ.set(null);

        // Echo upstream: answers the method and the raw request-target it received, so the forwarded
        // query is observable byte for byte.
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
            upstreamHits.incrementAndGet();
            request.response().end(request.method().name() + " " + request.uri());
        }).listen(0, LoopbackHost.ADDRESS), "the echo upstream server to start listening");
        int upstreamPort = upstreamServer.actualPort();

        RouteTable routeTable = new RouteTable(List.of(
                route("strict", upstreamPort, null),
                route("lenient", upstreamPort, "lenient"),
                route("minimal", upstreamPort, "minimal"),
                route("denytoken", upstreamPort, null, ForwardConfig.builder().queryDeny(List.of("token")).build()),
                route("allowx", upstreamPort, null, ForwardConfig.builder().queryAllow(List.of("x")).build())));
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        GatewayEdgeRoute edge = new GatewayEdgeRoute(routeTable, GatewayConfig.builder().version(1).build(),
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, new EdgeHardeningOptions(),
                new SheriffMetrics(new SimpleMeterRegistry()), BffRuntime.inert(), EgressTrustProfiles.unconsulted());

        Router router = Router.router(vertx);
        // Registered before the edge's catch-all: records what the transport's decoded parameter view
        // makes of 'q', which is the representation the edge must NOT hand to the filter.
        router.route().handler(ctx -> {
            transportDecodedQ.set(ctx.request().getParam("q"));
            ctx.next();
        });
        edge.registerRoutes(router);
        frontServer = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(frontServer.close(), "the edge front server to close");
        Awaits.teardown(upstreamServer.close(), "the echo upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "q=Max%20M%C3%BCller",
            "q=2026-09-15T10%3A00%3A00Z",
            "q=a%2Fb",
            "q=%5B%22a%22%5D",
            "t=2026-09-15T10:00:00Z",
            "r=/a/b",
            "q=100%25",
            "q=a+b"
    })
    @DisplayName("strict accepts a legitimate query and forwards it still encoded, byte for byte")
    void strictAcceptsAndForwardsVerbatim(String query) throws Exception {
        // Act
        Response response = get("/strict/orders?" + query);

        // Assert
        assertAll(query,
                () -> assertEquals(200, response.status(), "strict admits a legitimate value"),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?" + query, response.body(),
                        "the upstream receives the validated raw query unchanged — never decoded or re-encoded"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "q=%00",
            "q=%01",
            "q=a%0D%0Ab",
            "q=%0A",
            "q=%252F"
    })
    @DisplayName("strict rejects a value that decodes to NUL, a control character, a line break or a double encoding")
    void strictRejectsDangerousValues(String query) throws Exception {
        // Act
        Response response = get("/strict/orders?" + query);

        // Assert
        assertAll(query,
                () -> assertEquals(400, response.status(), "strict refuses the value"),
                () -> assertEquals(0, upstreamHits.get(), "a refused value never reaches the upstream"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a%3Db=1", "a%26b=1"})
    @DisplayName("strict rejects a parameter name that decodes to a pair delimiter")
    void strictRejectsDecodedDelimiterInName(String query) throws Exception {
        // Act
        Response response = get("/strict/orders?" + query);

        // Assert
        assertAll(query,
                () -> assertEquals(400, response.status(), "the parameter-name pipeline refuses the name"),
                () -> assertEquals(0, upstreamHits.get(), "a refused name never reaches the upstream"));
    }

    @Test
    @DisplayName("strict rejects a value the injection patterns read as a protocol scheme (known false positive)")
    void strictRejectsProtocolSchemeFalsePositive() throws Exception {
        // Arrange — "data: x" is plain prose, but decoded it matches the data: scheme pattern. Pinned as
        // rejected so a change in that verdict is a deliberate, visible decision rather than a drift.

        // Act
        Response response = get("/strict/orders?q=data%3A%20x");

        // Assert
        assertEquals(400, response.status(), "the decoded value matches the protocol-scheme injection pattern");
        assertEquals(0, upstreamHits.get(), "the refused value never reaches the upstream");
    }

    @Test
    @DisplayName("the transport's decoded view differs from the raw value the upstream receives")
    void transportDecodedViewDiffersFromForwardedRawValue() throws Exception {
        // Arrange — the regression guard for a future convenience decode at the edge (ADR-0047 Risks)
        String rawValue = "Max%20M%C3%BCller";

        // Act
        Response response = get("/strict/orders?q=" + rawValue);

        // Assert
        assertAll(
                () -> assertEquals(200, response.status()),
                () -> assertEquals("Max Müller", transportDecodedQ.get(),
                        "control: the transport decodes the value for its own parameter view"),
                () -> assertNotEquals(transportDecodedQ.get(), rawValue,
                        "the decoded view and the raw value are different representations"),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?q=" + rawValue, response.body(),
                        "the upstream receives the raw value, not the transport's decoded one"));
    }

    @Test
    @DisplayName("lenient keeps the unmodified cui-http preset and still admits a line break in a value")
    void lenientStillAdmitsLineBreakInValue() throws Exception {
        // Arrange — the CR/LF refusal is a STRICT-only deviation; LENIENT is SecurityConfiguration.lenient()
        String query = "q=a%0D%0Ab";

        // Act
        Response response = get("/lenient/orders?" + query);

        // Assert
        assertEquals(200, response.status(), "lenient does not refuse a decoded line break");
        assertEquals("GET " + UPSTREAM_PATH + "?" + query, response.body(),
                "and forwards the raw pair verbatim");
    }

    @Test
    @DisplayName("lenient still rejects a double encoding in a value")
    void lenientStillRejectsDoubleEncoding() throws Exception {
        // Act
        Response response = get("/lenient/orders?q=%252F");

        // Assert
        assertEquals(400, response.status(), "lenient still runs the parameter-value pipeline");
        assertEquals(0, upstreamHits.get(), "the refused value never reaches the upstream");
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=a%0D%0Ab", "q=%252F", "q=data%3A%20x"})
    @DisplayName("minimal skips the url-parameter validation and forwards the raw pair verbatim")
    void minimalForwardsRawPairsUnvalidated(String query) throws Exception {
        // Act
        Response response = get("/minimal/orders?" + query);

        // Assert
        assertAll(query,
                () -> assertEquals(200, response.status(), "'minimal' turns the url-parameter validation off"),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?" + query, response.body(),
                        "the raw pair still crosses verbatim, never re-encoded"));
    }

    @Test
    @DisplayName("strict forwards a raw ';' in a value as %3B, so the upstream sees no separate 'token'")
    void strictEncodesSemicolonInValue() throws Exception {
        // Act — the edge splits on '&' only: this is ONE pair 'x' whose value is '1;token=abc'
        Response response = get("/strict/orders?x=1;token=abc");

        // Assert
        assertAll(
                () -> assertEquals(200, response.status(), "cui-http admits a raw ';' under strict"),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?x=1%3Btoken=abc", response.body(),
                        "the ';' crosses as %3B, so a ';'-splitting upstream cannot read a smuggled 'token'"));
    }

    @Test
    @DisplayName("a raw ';' in a parameter name is refused by strict and forwarded as %3B where admitted")
    void semicolonInNameIsEncodedWhereAdmitted() throws Exception {
        // Act — strict's parameter-name pipeline refuses a ';' name; 'minimal' skips that validation,
        // so it is the route on which such a name actually reaches the render path
        Response strict = get("/strict/orders?a;b=1");
        int strictHits = upstreamHits.get();
        Response minimal = get("/minimal/orders?a;b=1");

        // Assert
        assertAll(
                () -> assertEquals(400, strict.status(), "strict refuses a ';' in a parameter name"),
                () -> assertEquals(0, strictHits, "the refused name never reaches the upstream"),
                () -> assertEquals(200, minimal.status(), "'minimal' turns the url-parameter validation off"),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?a%3Bb=1", minimal.body(),
                        "the name's ';' is encoded on every route; the rest of the pair is untouched"));
    }

    @Test
    @DisplayName("a query without ';' is still forwarded byte for byte")
    void queryWithoutSemicolonStaysVerbatim() throws Exception {
        // Arrange — the %3B rewrite is the single exception; every other byte stays verbatim
        String query = "a=%41b&flag&c=x%2By+z";

        // Act
        Response response = get("/strict/orders?" + query);

        // Assert
        assertEquals("GET " + UPSTREAM_PATH + "?" + query, response.body(),
                "no ';' present, so the validated raw query crosses unchanged");
    }

    @Test
    @DisplayName("query_deny: [token] — x=1;token=abc forwards no separate 'token' parameter")
    void denyListCannotBeSmuggledPastWithSemicolon() throws Exception {
        // Act
        Response response = get("/denytoken/orders?x=1;token=abc");

        // Assert
        assertAll(
                () -> assertEquals(200, response.status()),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?x=1%3Btoken=abc", response.body(),
                        "the pair crosses as one encoded pair; 'token' is never a parameter of its own"),
                () -> assertFalse(response.body().contains(";"), "no raw ';' reaches the upstream"));
    }

    @Test
    @DisplayName("query_allow: [x] — the forwarded query carries %3B, never a raw ';'")
    void allowListForwardsEncodedSemicolon() throws Exception {
        // Act
        Response response = get("/allowx/orders?x=1;token=abc&other=2");

        // Assert
        assertAll(
                () -> assertEquals(200, response.status()),
                () -> assertEquals("GET " + UPSTREAM_PATH + "?x=1%3Btoken=abc", response.body(),
                        "only the allow-listed pair crosses, with its ';' encoded"),
                () -> assertFalse(response.body().contains(";"), "no raw ';' reaches the upstream"));
    }

    @Test
    @DisplayName("path validation still rejects an encoded separator before any route is selected")
    void pathValidationStillRejectsEncodedSeparator() throws Exception {
        // Act — no route matches this path, so a 400 rather than a 404 proves the pre-route floor fired
        Response response = get("/x%2Fy");

        // Assert
        assertEquals(400, response.status(), "the pre-route path validation refuses an encoded '/'");
        assertEquals(0, upstreamHits.get());
    }

    @Test
    @DisplayName("header validation still rejects a header value over the strict cap")
    void headerValidationStillRejectsOversizedValue() throws Exception {
        // Arrange
        int headerCap = SecurityProfile.STRICT.preset().maxHeaderValueLength();

        // Act
        Response response = get("/strict/orders?q=ok", Map.of("X-Custom", "a".repeat(headerCap + 1)));

        // Assert
        assertEquals(400, response.status(), "header-value validation is untouched by the query hand-off");
        assertEquals(0, upstreamHits.get());
    }

    private Response get(String uri) throws Exception {
        return get(uri, Map.of());
    }

    private Response get(String uri, Map<String, String> requestHeaders) throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(frontServer.actualPort()).setMethod(io.vertx.core.http.HttpMethod.GET).setURI(uri);
        CompletableFuture<Response> future = client.request(options)
                .compose(request -> {
                    requestHeaders.forEach(request::putHeader);
                    return request.send();
                })
                .compose(response -> response.body().map(buffer -> new Response(response.statusCode(),
                        buffer == null ? "" : buffer.toString())))
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the edge response to GET " + uri);
    }

    /**
     * A public HTTP route at {@code /{id}} dialing the echo upstream.
     *
     * @param profile the route's declared {@code security_filter.profile}, or {@code null} for a route
     *                declaring no block — which inherits the gateway-wide default, {@code strict}
     */
    private static ResolvedRoute route(String id, int upstreamPort, @Nullable String profile) {
        return route(id, upstreamPort, profile, null);
    }

    /**
     * A public HTTP route at {@code /{id}} dialing the echo upstream under a declared forward block.
     *
     * @param profile the route's declared {@code security_filter.profile}, or {@code null} for strict
     * @param forward the route's {@code forward} block, or {@code null} for forward-all
     */
    private static ResolvedRoute route(String id, int upstreamPort, @Nullable String profile,
            @Nullable ForwardConfig forward) {
        ResolvedRoute.ResolvedRouteBuilder builder = ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .effectiveForward(forward)
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""));
        if (profile != null) {
            builder.effectiveSecurityFilter(SecurityFilterConfig.builder().profile(profile).build());
        }
        return builder.build();
    }

    /** The terminal response, captured after the streamed body completes. */
    private record Response(int status, String body) {
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied validator; no route of this
     * fixture requires a bearer token, so the remaining CDI accessors are unused and throw.
     */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
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
