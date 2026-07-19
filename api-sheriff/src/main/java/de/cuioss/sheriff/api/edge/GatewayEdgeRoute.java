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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.config.SecurityConfigurationBuilder;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.api.ApiSheriffLogMessages;
import de.cuioss.sheriff.api.auth.AuthenticationStage;
import de.cuioss.sheriff.api.auth.GatewayValidator;
import de.cuioss.sheriff.api.config.model.ForwardedConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.api.events.EventCategory;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayEventCounter;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.forward.ForwardPolicyStage;
import de.cuioss.sheriff.api.forward.TcpPeerGate;
import de.cuioss.sheriff.api.pipeline.BasicChecksStage;
import de.cuioss.sheriff.api.pipeline.CanonicalPathGuard;
import de.cuioss.sheriff.api.pipeline.FramingGate;
import de.cuioss.sheriff.api.pipeline.PipelineRequest;
import de.cuioss.sheriff.api.pipeline.RouteSelectionStage;
import de.cuioss.sheriff.api.pipeline.SecurityHeadersStage;
import de.cuioss.sheriff.api.pipeline.ThoroughChecksStage;
import de.cuioss.sheriff.api.pipeline.VerbGateStage;
import de.cuioss.sheriff.api.quarkus.SheriffMetrics;
import de.cuioss.sheriff.api.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.api.routing.RouteRuntime;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

/**
 * The public data-plane edge: a single catch-all Vert.x route that runs every inbound request
 * through the fixed pipeline (stages 0-7) on a virtual thread, then relays the streamed upstream
 * response back to the client.
 * <p>
 * <strong>Boot-time assembly.</strong> The constructor compiles the frozen {@link RouteTable} into
 * immutable {@link RouteRuntime} instances via the {@link RouteRuntimeAssembler} (deduplicating the
 * shared Vert.x clients and SmallRye guards), builds every stage once with the shared
 * {@link SecurityEventCounter}, the default {@link SecurityConfiguration}, the boot-wired
 * {@link ForwardedHeaderResolver} + {@link TcpPeerGate} (from the global {@code forwarded} block),
 * and the shared {@link GatewayEventCounter}. That same shared {@link SecurityEventCounter} is bound
 * to {@link SheriffMetrics} here so its per-{@code UrlSecurityFailureType} counts surface as the
 * {@code sheriff_security_events_total} meter. An unsupported protocol or {@code session} auth fails
 * boot here (fail fast), so no request is ever served on an invalid route set.
 * <p>
 * <strong>Per-request flow.</strong> The catch-all is registered {@linkplain io.vertx.ext.web.Route#last()
 * last} so management / health routes keep working. Each request is admitted under a bounded
 * {@linkplain EdgeHardeningOptions#admissionCap() admission cap} <em>before</em> a virtual thread is
 * dispatched (a flood is rejected {@code 503} rather than spawning unbounded virtual threads), then
 * the request stream is paused and the whole pipeline runs on a virtual thread:
 * <ol>
 *   <li>stage 0 — response-header preparation + CORS preflight (short-circuits a preflight here);</li>
 *   <li>stage 1 — baseline security filter (records the single canonical path), the canonical-path
 *       guard, and the framing gate;</li>
 *   <li>stage 2 / 2b — deny-by-default route selection then the per-route verb gate;</li>
 *   <li>stage 3 — per-route thorough checks ({@code allowed_paths}, body cap, divergent pipeline);</li>
 *   <li>stage 4 — offline bearer-token validation;</li>
 *   <li>stage 5 — the zero-trust forward policy, consuming the route's resolved
 *       {@link RouteRuntime#getEffectiveForward() effectiveForward} and the global forwarded block;</li>
 *   <li>stage 6 / 7 — streamed upstream dispatch (byte-capped) and the streamed response relay.</li>
 * </ol>
 * A {@link GatewayException} at any stage is rendered as an RFC 9457 {@code application/problem+json}
 * response carrying the failing event's status and problem type, never leaking internal detail. On
 * {@code SIGTERM} the edge stops admitting new requests and drains in-flight ones within a bounded
 * window.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class GatewayEdgeRoute {

    private static final CuiLogger LOGGER = new CuiLogger(GatewayEdgeRoute.class);

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String DEFAULT_EMIT_MODE = "x-forwarded";
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int INTERNAL_ERROR = 500;
    private static final int BAD_GATEWAY = 502;
    private static final long DRAIN_POLL_INTERVAL_MILLIS = 50L;

    /** Per-request {@link RoutingContext} data key holding the resolved metrics route label. */
    private static final String ROUTE_KEY = "sheriff.route";

    private final List<RouteRuntime> routes;
    private final ExecutorService virtualThreadExecutor;
    private final EdgeHardeningOptions hardening;

    private final long defaultMaxBodySize;
    private final GatewayEventCounter gatewayEventCounter;
    private final UpstreamFailureMapper upstreamFailureMapper;
    private final SheriffMetrics sheriffMetrics;

    private final SecurityHeadersStage securityHeadersStage;
    private final BasicChecksStage basicChecksStage;
    private final CanonicalPathGuard canonicalPathGuard;
    private final FramingGate framingGate;
    private final RouteSelectionStage routeSelectionStage;
    private final VerbGateStage verbGateStage;
    private final ThoroughChecksStage thoroughChecksStage;
    private final AuthenticationStage authenticationStage;
    private final ForwardPolicyStage forwardPolicyStage;
    private final ResponseStage responseStage;

    private final Semaphore admission;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean draining;

    /**
     * Assembles the routes and every stage once, at boot.
     *
     * @param routeTable            the frozen, longest-prefix-ordered route table
     * @param gatewayConfig         the bound global gateway document (source of the {@code forwarded}
     *                              block and {@code security_headers})
     * @param tokenValidator        a lazy CDI handle to the gateway's shared offline bearer-token
     *                              validator; resolved via {@link Instance#get()} only when a
     *                              {@code require: bearer} route actually validates a token, so a
     *                              config with only {@code require: none} routes never touches the
     *                              validator producer (and never fails boot on a missing
     *                              {@code token_validation} block)
     * @param vertx                 the Vert.x instance the per-tuple upstream clients are created on
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor
     * @param hardening             the edge transport / admission bounds
     * @param sheriffMetrics        the Micrometer adapter the request/error/upstream signals are
     *                              recorded through
     */
    @Inject
    public GatewayEdgeRoute(RouteTable routeTable, GatewayConfig gatewayConfig,
            @GatewayValidator Instance<TokenValidator> tokenValidator, Vertx vertx,
            @VirtualThreads ExecutorService virtualThreadExecutor, EdgeHardeningOptions hardening,
            SheriffMetrics sheriffMetrics) {
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.hardening = hardening;
        this.sheriffMetrics = sheriffMetrics;
        this.admission = new Semaphore(hardening.admissionCap());

        SecurityEventCounter securityEventCounter = new SecurityEventCounter();
        SecurityConfiguration defaultConfiguration = SecurityConfiguration.defaults();
        this.defaultMaxBodySize = defaultConfiguration.maxBodySize();
        this.gatewayEventCounter = new GatewayEventCounter();
        this.upstreamFailureMapper = new UpstreamFailureMapper(gatewayEventCounter);

        List<String> trustedProxies = gatewayConfig.forwarded()
                .map(ForwardedConfig::trustedProxies).orElse(List.of());
        String emitMode = gatewayConfig.forwarded().flatMap(ForwardedConfig::emit).orElse(DEFAULT_EMIT_MODE);
        ForwardedResolverConfig resolverConfig = ForwardedResolverConfig.builder()
                .trustedProxies(Set.copyOf(trustedProxies))
                .securityConfig(defaultConfiguration)
                .build();
        ForwardedHeaderResolver resolver = new ForwardedHeaderResolver(resolverConfig, securityEventCounter);
        TcpPeerGate peerGate = new TcpPeerGate(trustedProxies);

        RouteRuntimeAssembler assembler = new RouteRuntimeAssembler(new ProtocolProcessorRegistry());
        this.routes = assembler.assemble(routeTable,
                GatewayEdgeRoute::securityConfigurationFor,
                target -> vertx.createHttpClient(),
                this::guardFor);
        LOGGER.info(ApiSheriffLogMessages.INFO.ROUTE_TABLE_COMPILED, routes.size());

        this.securityHeadersStage = new SecurityHeadersStage(gatewayConfig.securityHeaders());
        this.basicChecksStage = new BasicChecksStage(defaultConfiguration, securityEventCounter);
        this.canonicalPathGuard = new CanonicalPathGuard();
        this.framingGate = new FramingGate();
        this.routeSelectionStage = new RouteSelectionStage(routes);
        this.verbGateStage = new VerbGateStage();
        this.thoroughChecksStage = new ThoroughChecksStage(defaultConfiguration, securityEventCounter);
        this.authenticationStage = new AuthenticationStage(tokenValidator);
        this.forwardPolicyStage = new ForwardPolicyStage(resolver, peerGate, emitMode);
        this.responseStage = new ResponseStage();

        // Bind the boot-shared cui-http counter to Micrometer so the per-UrlSecurityFailureType
        // security-filter counts surface as sheriff_security_events_total, completing the fixed
        // five-meter contract alongside the request/duration/error/upstream meters recorded above.
        sheriffMetrics.bindSecurityEventCounter(securityEventCounter);
    }

    /**
     * Registers the catch-all data-plane route, last, so management / health routes keep priority.
     *
     * @param router the Vert.x web router, observed during Quarkus startup
     */
    public void registerRoutes(@Observes Router router) {
        router.route().last().handler(this::handle);
        LOGGER.debug("Registered catch-all gateway edge over %s route(s)", routes.size());
    }

    /**
     * Stops admitting new requests on {@code SIGTERM} and drains in-flight ones within a bounded
     * window so the shutdown completes cleanly within the Quarkus shutdown timeout.
     *
     * @param event the Quarkus shutdown event
     */
    void onShutdown(@Observes ShutdownEvent event) {
        draining = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hardening.drainTimeoutMillis());
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOGGER.debug("Edge drain finished; in-flight=%s", inFlight.get());
    }

    private void handle(RoutingContext ctx) {
        if (draining || !admission.tryAcquire()) {
            reject(ctx, SERVICE_UNAVAILABLE);
            return;
        }
        long startNanos = System.nanoTime();
        inFlight.incrementAndGet();
        // Guard the admission accounting so it is rolled back exactly once. The end handler releases
        // it on the normal path; the executor-rejection path below also rolls it back, and both must
        // never double-release (reject() ends the response, which itself fires this end handler).
        AtomicBoolean admissionReleased = new AtomicBoolean();
        ctx.addEndHandler(result -> {
            releaseAdmission(admissionReleased);
            recordRequestMetrics(ctx, startNanos);
        });
        ctx.request().pause();
        try {
            virtualThreadExecutor.execute(() -> process(ctx));
        } /*~~(TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe)~~>*/catch (RuntimeException rejected) {
            // The virtual-thread executor refused the dispatch (e.g. RejectedExecutionException during
            // a shutdown race), so process(ctx) will never run and the response would otherwise never
            // end — leaking the admission permit and the in-flight count. Roll the admission back now
            // (idempotently) and fail the request 503 directly.
            LOGGER.debug(rejected, "Virtual-thread dispatch rejected: %s", rejected.getMessage());
            releaseAdmission(admissionReleased);
            reject(ctx, SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Releases one admission permit and decrements the in-flight counter exactly once, guarded by
     * {@code released} so the normal end-handler path and the executor-rejection rollback path can
     * both call it without double-counting.
     */
    private void releaseAdmission(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            admission.release();
            inFlight.decrementAndGet();
        }
    }

    /**
     * Records the terminal {@link SheriffMetrics#REQUESTS_TOTAL request count} and
     * {@link SheriffMetrics#REQUEST_DURATION_SECONDS request-duration timer} for one served request
     * from the single end-of-response hook, so every terminal path (streamed success, short-circuit,
     * and rendered failure) is metered exactly once. The bounded {@code route} label is the id
     * stashed at route selection, or {@link SheriffMetrics#NO_ROUTE} for an unmatched or
     * short-circuited request.
     */
    private void recordRequestMetrics(RoutingContext ctx, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        String route = routeLabel(ctx);
        String method = ctx.request().method().name();
        sheriffMetrics.recordRequest(route, method, SheriffMetrics.statusFamily(ctx.response().getStatusCode()));
        sheriffMetrics.recordRequestDuration(route, elapsed);
    }

    /**
     * Counts one categorized failure against {@link SheriffMetrics#ERRORS_TOTAL}, keyed by the
     * request's stashed route (or {@link SheriffMetrics#NO_ROUTE}) and the failure
     * {@link EventCategory}. An uncategorized failure (e.g. an unexpected internal error) carries no
     * category and surfaces only through the {@code 5xx} bucket of {@link SheriffMetrics#REQUESTS_TOTAL}.
     */
    private void recordError(RoutingContext ctx, EventType eventType) {
        EventCategory category = eventType.category();
        if (category != null) {
            sheriffMetrics.recordError(routeLabel(ctx), category);
        }
    }

    private static String routeLabel(RoutingContext ctx) {
        String route = ctx.get(ROUTE_KEY);
        return route != null ? route : SheriffMetrics.NO_ROUTE;
    }

    private void process(RoutingContext ctx) {
        PipelineRequest request = null;
        try {
            HttpServerRequest raw = ctx.request();
            Optional<HttpMethod> parsedMethod = parseMethod(raw.method().name());
            if (parsedMethod.isEmpty()) {
                renderProblem(ctx, null, EventType.METHOD_NOT_ALLOWED);
                return;
            }
            HttpMethod method = parsedMethod.get();
            request = buildPipelineRequest(raw, method);

            securityHeadersStage.process(request);
            basicChecksStage.process(request);
            canonicalPathGuard.process(request);
            framingGate.process(request);
            if (request.shortCircuitStatus().isPresent()) {
                writeShortCircuit(ctx, request);
                return;
            }
            routeSelectionStage.process(request);
            verbGateStage.process(request);
            RouteRuntime route = requireSelectedRoute(request);
            ctx.put(ROUTE_KEY, route.getId());
            thoroughChecksStage.process(request, route.getEffectiveAllowedPaths());
            authenticationStage.process(request);
            ForwardPolicyStage.Result forward = forwardPolicyStage.process(request,
                    route.getEffectiveForward(), route.isNotModifiedEnabled());
            dispatchAndRelay(ctx, request, route, forward);
        } catch (GatewayException rejected) {
            // Upstream failures are already metered inside UpstreamFailureMapper; meter the rest here.
            if (rejected.getEventType().category() != EventCategory.UPSTREAM) {
                gatewayEventCounter.increment(rejected.getEventType());
            }
            if (rejected.getEventType() == EventType.SECURITY_FILTER_VIOLATION) {
                // Security-relevant WARN (D4): the failure-type detail only, never the raw payload —
                // rejected.getMessage() already carries a sanitized description (see GatewayException).
                LOGGER.warn(ApiSheriffLogMessages.WARN.SECURITY_FILTER_VIOLATION, routeLabel(ctx), rejected.getMessage());
            }
            recordError(ctx, rejected.getEventType());
            renderProblem(ctx, request, rejected.getEventType());
        } /*~~(TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe)~~>*/ catch (RuntimeException unexpected) {
            LOGGER.debug(unexpected, "Unexpected edge failure: %s", unexpected.getMessage());
            renderProblem(ctx, request, null);
        }
    }

    private void dispatchAndRelay(RoutingContext ctx, PipelineRequest request, RouteRuntime route,
            ForwardPolicyStage.Result forward) {
        String prefix = stripTrailingSlash(route.getMatcher().pathPrefix());
        String canonical = requireCanonicalPath(request);
        String remainder = canonical.length() >= prefix.length() ? canonical.substring(prefix.length()) : "";
        String query = renderQuery(forward.query());
        String uri = DispatchStage.upstreamRequestUri(route.getUpstream(), remainder, query);
        long cap = route.getSecurityConfiguration().map(SecurityConfiguration::maxBodySize).orElse(defaultMaxBodySize);

        DispatchStage dispatchStage = new DispatchStage(cap, upstreamFailureMapper);
        long upstreamStartNanos = System.nanoTime();
        HttpClientResponse upstream = dispatchStage.dispatch(route, request.method(), uri, forward.headers(),
                ctx.request());
        sheriffMetrics.recordUpstreamDuration(route.getId(), Duration.ofNanos(System.nanoTime() - upstreamStartNanos));
        gatewayEventCounter.increment(EventType.REQUEST_FORWARDED);
        // The relay mutates the Vert.x HttpServerResponse (status, headers) and subscribes the
        // upstream pipeTo — all of which are event-loop-bound and NOT safe to touch from this
        // virtual thread. Hop back onto the event loop exactly like every other terminal path
        // (renderProblem / writeShortCircuit / failRelay); doing the relay off-loop races the
        // response object and corrupts / truncates the streamed body.
        ctx.vertx().runOnContext(v ->
                responseStage.relay(upstream, ctx.response(), route.isNotModifiedEnabled(), request.responseHeaders())
                        .onFailure(failure -> failRelay(ctx, failure)));
    }

    private void failRelay(RoutingContext ctx, Throwable failure) {
        LOGGER.debug(failure, "Response relay failed: %s", failure.getMessage());
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            // If the relay failed mid-stream after the response head was already written, the status
            // line is gone — Vert.x throws IllegalStateException on setStatusCode once headWritten()
            // is true. Only set the 502 when the head has not yet been written; either way end() the
            // (possibly truncated) response so the client connection is closed cleanly.
            if (!response.headWritten()) {
                response.setStatusCode(BAD_GATEWAY);
            }
            response.end();
        });
    }

    private void writeShortCircuit(RoutingContext ctx, PipelineRequest request) {
        int status = request.shortCircuitStatus().orElse(204);
        Map<String, String> responseHeaders = Map.copyOf(request.responseHeaders());
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(status);
            responseHeaders.forEach(response::putHeader);
            response.end();
        });
    }

    private void renderProblem(RoutingContext ctx, @Nullable PipelineRequest request, @Nullable EventType eventType) {
        int status;
        String type;
        String title;
        if (eventType == null) {
            status = INTERNAL_ERROR;
            type = "about:blank";
            title = "Internal Server Error";
        } else {
            status = eventType.hasHttpMapping() ? eventType.httpStatus() : INTERNAL_ERROR;
            EventCategory category = eventType.category();
            type = category != null ? category.problemType() : "about:blank";
            title = category != null ? category.title() : "Internal Server Error";
        }
        String body = "{\"type\":\"" + type + "\",\"title\":\"" + title + "\",\"status\":" + status + "}";
        Map<String, String> responseHeaders = request != null ? Map.copyOf(request.responseHeaders()) : Map.of();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(status);
            responseHeaders.forEach(response::putHeader);
            response.putHeader("Content-Type", PROBLEM_JSON);
            response.end(body);
        });
    }

    private static void reject(RoutingContext ctx, int status) {
        HttpServerResponse response = ctx.response();
        if (!response.ended()) {
            response.setStatusCode(status).end();
        }
    }

    private static PipelineRequest buildPipelineRequest(HttpServerRequest raw, HttpMethod method) {
        String rawUri = raw.uri();
        int queryStart = rawUri.indexOf('?');
        String rawPath = queryStart < 0 ? rawUri : rawUri.substring(0, queryStart);
        long contentLength = parseContentLength(raw);
        boolean bodyPresent = contentLength > 0 || raw.headers().contains("Transfer-Encoding");
        return PipelineRequest.builder()
                .method(method)
                .requestPath(rawPath)
                .queryParameters(toListMap(raw.params()))
                .headers(toListMap(raw.headers()))
                .host(raw.authority() != null ? raw.authority().host() : raw.host())
                .peerAddress(raw.remoteAddress() != null ? raw.remoteAddress().hostAddress() : null)
                .declaredContentLength(contentLength)
                .bodyPresent(bodyPresent)
                .build();
    }

    private static Map<String, List<String>> toListMap(MultiMap multiMap) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : multiMap.names()) {
            map.put(name, List.copyOf(multiMap.getAll(name)));
        }
        return map;
    }

    private static long parseContentLength(HttpServerRequest raw) {
        String value = raw.getHeader("Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    private static String renderQuery(Map<String, List<String>> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            for (String value : entry.getValue()) {
                if (!first) {
                    rendered.append('&');
                }
                rendered.append(encode(entry.getKey())).append('=').append(encode(value));
                first = false;
            }
        }
        return rendered.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Optional<HttpMethod> parseMethod(String name) {
        try {
            return Optional.of(HttpMethod.valueOf(name));
        } catch (IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }

    private static RouteRuntime requireSelectedRoute(PipelineRequest request) {
        RouteRuntime route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Route dispatch requires the route selected at stage 2");
        }
        return route;
    }

    private static String requireCanonicalPath(PipelineRequest request) {
        String canonical = request.canonicalPath();
        if (canonical == null) {
            throw new IllegalStateException("Route dispatch requires the canonical path resolved at stage 1");
        }
        return canonical;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Maps a route's {@code security_filter} block to a cui-http {@link SecurityConfiguration},
     * seeding the safe builder defaults and overriding only the limits the route declared, so an
     * undeclared dimension never falls below the gateway baseline.
     */
    private static SecurityConfiguration securityConfigurationFor(SecurityFilterConfig filter) {
        SecurityConfigurationBuilder builder = SecurityConfiguration.builder();
        filter.maxBodyBytes().ifPresent(value -> builder.maxBodySize(value.longValue()));
        filter.maxQueryParams().ifPresent(builder::maxParameterCount);
        filter.maxHeaderCount().ifPresent(builder::maxHeaderCount);
        filter.maxParamValueLength().ifPresent(builder::maxParameterValueLength);
        filter.maxHeaderValueLength().ifPresent(builder::maxHeaderValueLength);
        if (!filter.allowedHeaderNames().isEmpty()) {
            builder.allowedHeaderNames(Set.copyOf(filter.allowedHeaderNames()));
        }
        if (!filter.blockedHeaderNames().isEmpty()) {
            builder.blockedHeaderNames(Set.copyOf(filter.blockedHeaderNames()));
        }
        if (!filter.allowedContentTypes().isEmpty()) {
            builder.allowedContentTypes(Set.copyOf(filter.allowedContentTypes()));
        }
        return builder.build();
    }

    /**
     * Builds the per-shape SmallRye Fault-Tolerance guard: a circuit breaker plus an upstream
     * timeout, with retry added only for a route that enables it. Gateway rejections
     * ({@link GatewayException}, e.g. a body-cap breach) are skipped so they never trip the breaker
     * or trigger a retry.
     */
    private Guard guardFor(RouteRuntimeAssembler.ResilienceShape shape) {
        // Include retryEnabled in the breaker name: RouteRuntimeAssembler's guardCache is keyed by the
        // full ResilienceShape (target + retryEnabled), so two routes to the same host:port that differ
        // only in retryEnabled resolve to two distinct guards. Deriving the name from host:port alone
        // would hand both the SAME programmatic circuit-breaker name, which SmallRye rejects as a
        // duplicate. The retry-qualified name is used for both .name(...) and the transition callback.
        String name = shape.target().host() + ":" + shape.target().port() + ":retry=" + shape.retryEnabled();
        Guard.Builder builder = Guard.create().withDescription("upstream-" + name);
        builder.withCircuitBreaker()
                .name(name)
                .requestVolumeThreshold(20)
                .failureRatio(0.5)
                .delay(5, ChronoUnit.SECONDS)
                .successThreshold(2)
                .skipOn(GatewayException.class)
                .onStateChange(state -> upstreamFailureMapper.recordBreakerTransition(name, state))
                .done();
        builder.withTimeout().duration(30, ChronoUnit.SECONDS).done();
        if (shape.retryEnabled()) {
            builder.withRetry().maxRetries(2).delay(100, ChronoUnit.MILLIS)
                    .abortOn(GatewayException.class).done();
        }
        return builder.build();
    }
}
