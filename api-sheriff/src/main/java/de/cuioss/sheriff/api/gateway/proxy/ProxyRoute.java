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
import java.util.Set;
import java.util.concurrent.ExecutorService;

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
 * Interim minimal reverse proxy: a single catch-all Vert.x route on the data
 * plane that forwards matched requests to a configured upstream.
 * <p>
 * Behaviour (deliberately interim — Plan 03 keeps this edge and replaces the
 * internals with the real request pipeline):
 * <ul>
 *   <li>The route matches {@code <path-prefix>/*}; the prefix is stripped and the
 *       remaining path plus the query string are appended to the upstream URL.</li>
 *   <li>Method, path remainder, query and body pass through; hop-by-hop headers
 *       are stripped in both directions.</li>
 *   <li>Forwarding uses a blocking JDK {@link HttpClient} {@code send()} executed
 *       on a virtual thread, so the Vert.x event loop is never blocked.</li>
 *   <li>Any path outside the prefix falls through to the default {@code 404}
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

    private final ProxyConfiguration config;
    private final ExecutorService virtualThreadExecutor;
    private final HttpClient httpClient;
    private final String upstreamBaseUrl;

    /**
     * Creates the proxy route.
     *
     * @param config                the interim proxy configuration
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor
     *                              (thread names use {@code quarkus.virtual-threads.name-prefix})
     */
    @Inject
    public ProxyRoute(ProxyConfiguration config, @VirtualThreads ExecutorService virtualThreadExecutor) {
        this.config = config;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.upstreamBaseUrl = stripTrailingSlash(config.upstreamUrl());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Registers the catch-all proxy route on the data-plane router at startup.
     *
     * @param router the Vert.x web router, observed during Quarkus startup
     */
    public void registerRoutes(@Observes Router router) {
        String routePath = stripTrailingSlash(config.pathPrefix()) + "/*";
        router.route(routePath)
                .handler(BodyHandler.create())
                .handler(ctx -> virtualThreadExecutor.execute(() -> forward(ctx)));
        LOGGER.info(INFO.ROUTE_REGISTERED, config.pathPrefix(), upstreamBaseUrl);
    }

    /**
     * Forwards a single request to the upstream. Runs on a virtual thread; the
     * response is written back on the Vert.x context.
     *
     * @param ctx the routing context of the matched request
     */
    private void forward(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        String prefix = stripTrailingSlash(config.pathPrefix());
        String remainder = request.path().substring(prefix.length());
        String query = request.query();
        String target = upstreamBaseUrl + remainder + (query == null || query.isEmpty() ? "" : "?" + query);

        try {
            HttpRequest upstreamRequest = buildUpstreamRequest(ctx, target);
            HttpResponse<byte[]> upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            ctx.vertx().runOnContext(v -> writeUpstreamResponse(ctx, upstreamResponse));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failBadGateway(ctx, target, e);
        } catch (IOException | IllegalArgumentException e) {
            failBadGateway(ctx, target, e);
        }
    }

    private HttpRequest buildUpstreamRequest(RoutingContext ctx, String target) {
        HttpServerRequest request = ctx.request();
        Buffer body = ctx.body() == null ? null : ctx.body().buffer();
        HttpRequest.BodyPublisher bodyPublisher = body == null || body.length() == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body.getBytes());

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target));
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
                values.forEach(value -> response.putHeader(name, value));
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

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
