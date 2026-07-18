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
package de.cuioss.sheriff.api.gateway.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.gateway.proxy.ProxyLogMessages.INFO;
import de.cuioss.sheriff.api.gateway.proxy.ProxyLogMessages.WARN;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Interim minimal reverse proxy: a Vert.x route per configured
 * {@code path_prefix} that forwards matched requests to the upstream resolved for
 * that route.
 * <p>
 * The routing table is the {@link RouteTable} assembled by the configuration
 * subsystem (Deliverable 7); this edge sources each route's upstream from the
 * table's {@link ResolvedUpstream} rather than from a single static configuration
 * bean. Behaviour (deliberately interim — Plan 03 keeps this edge and replaces the
 * internals with the real request pipeline):
 * <ul>
 *   <li>One route is registered per {@code path_prefix} in the table; the prefix
 *       is stripped and the remaining path plus the query string are appended to
 *       the route's upstream base URL.</li>
 *   <li>Method, path remainder, query and body pass through; hop-by-hop headers
 *       are stripped in both directions.</li>
 *   <li>Forwarding uses a blocking JDK {@link HttpClient} {@code send()} executed
 *       on a virtual thread, so the Vert.x event loop is never blocked.</li>
 *   <li>Any path with no matching route falls through to {@code 404}
 *       (deny-by-default); upstream failures surface as {@code 502}.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ProxyRoute {

    private static final CuiLogger LOGGER = new CuiLogger(ProxyRoute.class);

    /**
     * Hop-by-hop headers (RFC 7230 §6.1) plus JDK {@link HttpClient} restricted
     * request headers, which must not be forwarded to the upstream. All lower case.
     */
    private static final Set<String> REQUEST_SKIP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "expect");

    /**
     * Hop-by-hop headers plus length/framing headers that Vert.x recomputes when
     * the response body is written. All lower case.
     */
    private static final Set<String> RESPONSE_SKIP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    /**
     * Disposable stopgap mitigation: cap the buffered request body the interim
     * proxy accepts, so an unbounded upload cannot exhaust heap before Plan 04
     * replaces this edge with the real streaming pipeline. An over-limit body is
     * rejected by the Vert.x {@link BodyHandler} with {@code 413 Payload Too Large}.
     */
    private static final long MAX_REQUEST_BODY_BYTES = 1024L * 1024L;

    /**
     * Disposable stopgap mitigation: bound each upstream request so a slow or hung
     * upstream cannot pin a virtual thread indefinitely. On expiry the JDK
     * {@link HttpClient} throws {@link java.net.http.HttpTimeoutException} (an
     * {@link IOException}), which surfaces to the client as {@code 502}. Superseded
     * by Plan 04's timeouts-from-config.
     */
    private static final Duration UPSTREAM_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Test-only seam: JVM system property that overrides {@link #UPSTREAM_REQUEST_TIMEOUT}
     * (value in milliseconds) so the timeout-trips behaviour can be exercised without a
     * multi-second wait. This is a throwaway JVM property, NOT gateway configuration —
     * config-driven upstream timeouts are Plan 04's edge and are deliberately not added here.
     */
    private static final String UPSTREAM_REQUEST_TIMEOUT_MS_PROPERTY =
            "apisheriff.proxy.upstream-request-timeout-ms";

    private final RouteTable routeTable;
    private final ExecutorService virtualThreadExecutor;
    private final HttpClient httpClient;

    /**
     * Creates the proxy route.
     *
     * @param routeTable            the assembled route table sourcing each route's
     *                              upstream
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor
     *                              (thread names use {@code quarkus.virtual-threads.name-prefix})
     */
    @Inject
    public ProxyRoute(RouteTable routeTable, @VirtualThreads ExecutorService virtualThreadExecutor) {
        this.routeTable = routeTable;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Registers one catch-all proxy route per {@code path_prefix} in the route
     * table on the data-plane router at startup.
     *
     * @param router the Vert.x web router, observed during Quarkus startup
     */
    public void registerRoutes(@Observes Router router) {
        for (ResolvedRoute route : routeTable.routes()) {
            String routePath = stripTrailingSlash(route.pathPrefix()) + "/*";
            router.route(routePath)
                    .handler(BodyHandler.create().setBodyLimit(MAX_REQUEST_BODY_BYTES))
                    .handler(ctx -> virtualThreadExecutor.execute(() -> forward(ctx)));
            LOGGER.info(INFO.ROUTE_REGISTERED, route.pathPrefix(), upstreamBaseUrl(route.upstream()));
        }
    }

    /**
     * Forwards a single request to the upstream resolved for its matching route.
     * Runs on a virtual thread; the response is written back on the Vert.x context.
     *
     * @param ctx the routing context of the matched request
     */
    private void forward(RoutingContext ctx) {
        String rawUri = ctx.request().uri();
        int queryStart = rawUri.indexOf('?');
        String rawPath = queryStart < 0 ? rawUri : rawUri.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? "" : rawUri.substring(queryStart);

        Optional<ResolvedRoute> match = routeTable.lookup(rawPath);
        if (match.isEmpty()) {
            ctx.vertx().runOnContext(v -> {
                if (!ctx.response().ended()) {
                    ctx.response().setStatusCode(404).end();
                }
            });
            return;
        }

        String prefix = stripTrailingSlash(match.get().pathPrefix());
        String upstreamBaseUrl = upstreamBaseUrl(match.get().upstream());
        // Everything that can throw (substring, URI parsing, send) stays inside the
        // try so any failure yields a 502 rather than an uncaught error that would
        // leave the client hanging on the virtual thread.
        String target = upstreamBaseUrl;
        try {
            String remainder = rawPath.substring(prefix.length());
            target = upstreamBaseUrl + remainder + rawQuery;

            HttpRequest upstreamRequest = buildUpstreamRequest(ctx, target);
            HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            ctx.vertx().runOnContext(v -> writeUpstreamResponse(ctx, upstreamResponse));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failBadGateway(ctx, target, e);
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            failBadGateway(ctx, target, e);
        }
    }

    private HttpRequest buildUpstreamRequest(RoutingContext ctx, String target) {
        HttpServerRequest request = ctx.request();
        Buffer body = ctx.body() == null ? null : ctx.body().buffer();
        HttpRequest.BodyPublisher bodyPublisher = body == null || body.length() == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body.getBytes());

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(resolveUpstreamRequestTimeout());
        request.headers().forEach(header -> {
            if (!REQUEST_SKIP_HEADERS.contains(header.getKey().toLowerCase())) {
                builder.header(header.getKey(), header.getValue());
            }
        });
        return builder.method(request.method().name(), bodyPublisher).build();
    }

    private void writeUpstreamResponse(RoutingContext ctx, HttpResponse<byte[]> upstreamResponse) {
        HttpServerResponse response = ctx.response();
        response.setStatusCode(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (!RESPONSE_SKIP_HEADERS.contains(name.toLowerCase())) {
                // headers().add (not putHeader) so multi-valued headers such as
                // Set-Cookie are all preserved rather than collapsed to the last.
                values.forEach(value -> response.headers().add(name, value));
            }
        });
        response.end(Buffer.buffer(upstreamResponse.body()));
    }

    private void failBadGateway(RoutingContext ctx, String target, Exception cause) {
        LOGGER.warn(cause, WARN.UPSTREAM_FAILURE, target, cause.getMessage());
        ctx.vertx().runOnContext(v -> {
            if (!ctx.response().ended()) {
                ctx.response().setStatusCode(502).end();
            }
        });
    }

    private static String upstreamBaseUrl(ResolvedUpstream upstream) {
        return stripTrailingSlash("%s://%s:%d%s".formatted(upstream.scheme(), upstream.host(), upstream.port(),
                upstream.basePath()));
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Resolves the upstream request timeout, honouring the {@value #UPSTREAM_REQUEST_TIMEOUT_MS_PROPERTY}
     * test-only override when set and falling back to {@link #UPSTREAM_REQUEST_TIMEOUT} otherwise.
     * {@link Long#getLong(String, long)} returns the default when the property is absent or unparseable.
     * Read per request so a test can set the override immediately before a call without depending on
     * class-load or Quarkus-boot ordering.
     */
    private static Duration resolveUpstreamRequestTimeout() {
        return Duration.ofMillis(
                Long.getLong(UPSTREAM_REQUEST_TIMEOUT_MS_PROPERTY, UPSTREAM_REQUEST_TIMEOUT.toMillis()));
    }
}
