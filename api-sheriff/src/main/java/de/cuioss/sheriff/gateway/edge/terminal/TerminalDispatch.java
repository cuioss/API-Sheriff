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
package de.cuioss.sheriff.gateway.edge.terminal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeAdmission;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnd;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.RejectionReporter;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.GrpcDispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamFailureMapper;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.forward.ForwardPolicyStage;
import de.cuioss.sheriff.gateway.pipeline.OriginValidationStage;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Serves a route's terminal action once the pipeline has admitted the request (stages 6 / 7): the
 * streamed upstream dispatch and the streamed response relay of a proxy route, the forced-h2 dispatch
 * and trailer relay of a {@code protocol: grpc} route, the opaque relay of a
 * {@code protocol: websocket} route, the buffered governed response of an asset route, the redirect
 * answer and the pipeline's short-circuit answer.
 * <p>
 * The dispatch methods run on the virtual thread; every response mutation is hopped back onto the
 * request's event loop. Thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TerminalDispatch {

    private static final CuiLogger LOGGER = new CuiLogger(TerminalDispatch.class);

    /** Holds the client connection's own Vert.x {@link Context}, captured by the edge root's
     * {@code handle} on that connection's event loop before the virtual-thread hop, for the same reason
     * as {@link EdgeAdmission#ADMISSION_GUARD_KEY}: the hop carries only the {@link RoutingContext}. It
     * has two readers: {@link #dispatchWebSocket} hands it to {@link WebSocketRelayStage#relay}, which
     * runs the upstream dial and the client upgrade on it, whatever thread called {@code relay()}; and
     * every proxied HTTP dispatch reads it in {@link #lateBodyRefusal}, where the HTTP/1.x connection
     * close after a late request-body timeout is decided on it. */
    public static final String CLIENT_CONTEXT_KEY = "sheriff.clientcontext";
    /** Holds the event of the late body refusal of a proxied request (see {@link #lateBodyRefusal}),
     * empty until one is made: a response a bodyless request's refusal reset is never ended afterwards
     * by a relay failure, and a relay failure that follows a late request-body timeout keeps the status
     * the upstream sent. */
    private static final String LATE_BODY_REFUSAL_KEY = "sheriff.latebodyrefusal";
    /** The HTTP/2 error code {@code CANCEL}: the stream is abandoned before its response completed. */
    private static final long HTTP2_CANCEL = 0x8L;
    private static final String LOCATION_HEADER = "Location";
    /** The {@code Cache-Control} response header the redirect terminal action governs. */
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    /** The {@code Cache-Control} value forced on an authenticated or cookie-bearing redirect answer. */
    private static final String NO_STORE = "no-store";
    /** The {@code Vary} response header a redirect answer carries for its route's header matchers. */
    private static final String VARY_HEADER = "Vary";
    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int NOT_FOUND = 404;
    private static final int BAD_GATEWAY = 502;
    private static final int NO_CONTENT = 204;

    /** The shared upstream clients and guards every proxy dispatch resolves its route's pair from. */
    private final UpstreamClients upstreamClients;
    private final long defaultMaxBodySize;
    private final EdgeHardeningOptions hardening;
    private final UpstreamFailureMapper upstreamFailureMapper;
    private final SheriffMetrics sheriffMetrics;
    private final GatewayEventCounter gatewayEventCounter;
    private final BffRuntime bffRuntime;
    private final EdgeAdmission edgeAdmission;
    private final ConnectionRetirement connectionRetirement;
    private final RejectionReporter rejectionReporter;
    private final ErrorResponseWriter errorResponseWriter;
    private final ResponseStage responseStage;
    private final OriginValidationStage originValidationStage;
    private final WebSocketRelayStage webSocketRelayStage;
    /**
     * The route ids a gRPC call ended by its stream-idle bound has already been reported for at
     * {@code INFO} (see {@link #logGrpcStreamIdleTimeout}). Bounded by the number of configured routes.
     */
    private final Set<String> grpcStreamIdleReported = ConcurrentHashMap.newKeySet();

    /**
     * Wires the terminal actions to the boot-built collaborators of the edge.
     *
     * @param upstreamClients       the shared upstream clients and guards the assembler built
     * @param defaultMaxBodySize    the baseline body cap a route without its own security
     *                              configuration dispatches under
     * @param hardening             the edge transport / admission bounds (the request-body idle
     *                              timeout and deadline)
     * @param upstreamFailureMapper the mapper turning guarded-dispatch failures into the error contract
     * @param sheriffMetrics        the Micrometer adapter the upstream duration is recorded through
     * @param gatewayEventCounter   the shared in-process event counter
     * @param bffRuntime            the BFF runtime a session-bound WebSocket relay is tracked by
     * @param edgeAdmission         the admission a WebSocket relay holds and releases its permits under
     * @param connectionRetirement  the retirement a body the dispatch could not finish reading marks
     * @param rejectionReporter     the reporter a late body refusal is metered and logged through
     * @param errorResponseWriter   the writer a directory-asset miss negotiates its error page with
     * @param responseStage         the streamed response relay
     * @param originValidationStage the WebSocket handshake {@code Origin} check
     * @param webSocketRelayStage   the opaque WebSocket relay
     */
    // One parameter per boot-built collaborator the terminal actions share with the rest of the edge;
    // the edge root wires them once. A parameter object would exist only to satisfy the count.
    @SuppressWarnings("java:S107") // wiring constructor, called once by the edge root
    public TerminalDispatch(UpstreamClients upstreamClients, long defaultMaxBodySize,
            EdgeHardeningOptions hardening, UpstreamFailureMapper upstreamFailureMapper,
            SheriffMetrics sheriffMetrics, GatewayEventCounter gatewayEventCounter, BffRuntime bffRuntime,
            EdgeAdmission edgeAdmission, ConnectionRetirement connectionRetirement,
            RejectionReporter rejectionReporter, ErrorResponseWriter errorResponseWriter,
            ResponseStage responseStage, OriginValidationStage originValidationStage,
            WebSocketRelayStage webSocketRelayStage) {
        this.upstreamClients = Objects.requireNonNull(upstreamClients, "upstreamClients");
        this.defaultMaxBodySize = defaultMaxBodySize;
        this.hardening = Objects.requireNonNull(hardening, "hardening");
        this.upstreamFailureMapper = Objects.requireNonNull(upstreamFailureMapper, "upstreamFailureMapper");
        this.sheriffMetrics = Objects.requireNonNull(sheriffMetrics, "sheriffMetrics");
        this.gatewayEventCounter = Objects.requireNonNull(gatewayEventCounter, "gatewayEventCounter");
        this.bffRuntime = Objects.requireNonNull(bffRuntime, "bffRuntime");
        this.edgeAdmission = Objects.requireNonNull(edgeAdmission, "edgeAdmission");
        this.connectionRetirement = Objects.requireNonNull(connectionRetirement, "connectionRetirement");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
        this.responseStage = Objects.requireNonNull(responseStage, "responseStage");
        this.originValidationStage = Objects.requireNonNull(originValidationStage, "originValidationStage");
        this.webSocketRelayStage = Objects.requireNonNull(webSocketRelayStage, "webSocketRelayStage");
    }

    /**
     * Serves an HTTP or GraphQL route's terminal action: the buffered asset response for an asset
     * route, otherwise the streamed, byte-capped upstream dispatch followed by the streamed response
     * relay.
     *
     * @param ctx     the request
     * @param request the pipeline request, through stage 5
     * @param route   the selected route
     * @param forward the forward policy's result
     */
    public void dispatchAndRelay(RoutingContext ctx, PipelineRequest request, Route route,
            ForwardPolicyStage.Result forward) {
        String remainder = EdgeRequests.remainderAfterMatchKey(route, EdgeRequests.requireCanonicalPath(request));
        // An asset route serves its terminal action directly — the buffered, gateway-governed
        // asset response — instead of streaming to an upstream. Auth (stage 4) has already run,
        // so an unauthorized request never reaches the source (auth-before-source, ADR-0014).
        if (route.assetSource() != null) {
            serveAsset(ctx, request, route, remainder);
            return;
        }
        String query = EdgeRequests.renderQuery(forward.query());
        ResolvedUpstream upstreamTarget = route.upstream();
        if (upstreamTarget == null) {
            throw new IllegalStateException("proxy dispatch requires a resolved upstream");
        }
        String uri = DispatchStage.upstreamRequestUri(upstreamTarget, remainder, query);
        SecurityConfiguration routeSecurity = route.securityConfiguration();
        long cap = routeSecurity == null ? defaultMaxBodySize : routeSecurity.maxBodySize();

        DispatchStage dispatchStage = new DispatchStage(upstreamClients, cap,
                hardening.requestBodyIdleTimeoutMillis(), hardening.requestBodyDeadlineMillis(), upstreamFailureMapper);
        DispatchStage.BodyFraming framing = DispatchStage.BodyFraming.of(request.method(),
                request.declaredContentLength(), lateBodyRefusal(ctx));
        long upstreamStartNanos = System.nanoTime();
        HttpClientResponse upstream = dispatchMarkingUnreadBody(ctx, () -> dispatchStage.dispatch(route,
                request.method(), uri, forward.headers(), inboundBody(ctx), framing));
        sheriffMetrics.recordUpstreamDuration(route.id(), Duration.ofNanos(System.nanoTime() - upstreamStartNanos));
        gatewayEventCounter.increment(EventType.REQUEST_FORWARDED);
        // The relay mutates the Vert.x HttpServerResponse (status, headers) and subscribes the
        // upstream pipe — all of which are event-loop-bound and NOT safe to touch from this
        // virtual thread. Hop back onto the event loop exactly like every other terminal path
        // (renderProblem / writeShortCircuit / failRelay); doing the relay off-loop races the
        // response object and corrupts / truncates the streamed body.
        List<String> stageSetCookies = request.responseSetCookies();
        // Snapshot both header maps on this virtual thread: the relay runs on the event loop, and a
        // proxied response is the one path where the default-mode map defers to an origin header.
        Map<String, String> setHeaders = Map.copyOf(request.responseHeaders());
        Map<String, String> defaultHeaders = Map.copyOf(request.responseDefaultHeaders());
        relayOnEventLoop(ctx, stageSetCookies, upstream, end -> responseStage.relay(upstream, ctx.response(),
                route.notModifiedEnabled(), route.locationRewriter(), setHeaders, defaultHeaders, end));
    }

    /**
     * Dispatches a {@code protocol: websocket} route: validates the handshake {@code Origin} against
     * the route's effective allowlist (a foreign / absent origin throws a
     * {@link EventType#WEBSOCKET_ORIGIN_REJECTED} {@link GatewayException} rendered as {@code 403}
     * before any upstream contact — GW-09 / CSWSH), then hands the upgrade to the opaque
     * {@link WebSocketRelayStage}. The upstream dial and the client upgrade are performed by the
     * relay stage, so no HTTP {@link ResponseStage} relay runs for a WebSocket route.
     * <p>
     * <strong>Admission accounting.</strong> Because a completed upgrade takes the connection over, the
     * HTTP end handler the edge root registers never fires for an established relay — the permit
     * would otherwise be stranded for the process lifetime. The per-request CAS guard stashed under
     * {@link EdgeAdmission#ADMISSION_GUARD_KEY} is therefore read back here and handed to the relay as a
     * release callback, which the relay invokes on each of its teardown paths. Routing the release
     * through the same guard keeps it idempotent against the end handler and the executor-rejection
     * rollback, so an upstream-dial failure (which does end the response) can never double-release.
     * <p>
     * <strong>Relay sub-budget.</strong> Because that permit is held for the connection's lifetime, an
     * upgrade must additionally acquire the {@linkplain EdgeHardeningOptions#webSocketRelayCap()
     * WebSocket relay sub-budget} before the hand-off — a second permit taken <em>in addition to</em>
     * the general one, so long-lived relays cannot squeeze ordinary HTTP traffic out of the admission
     * pool. An upgrade beyond that cap is refused {@code 503}, releasing the general permit through
     * the shared guard on the way out so a refusal strands nothing.
     * <p>
     * <strong>Session-bound relays.</strong> An upgrade a session was let through with is tracked in
     * the session runtime's relay registry before the upstream is dialed, and the relay is closed at
     * the session's absolute expiry and — where sessions are held server-side — when the session is
     * destroyed or evicted. A registry at its capacity refuses the upgrade {@code 503}, exactly like
     * an exhausted relay sub-budget; no session-bound relay is ever opened untracked. A relay on a
     * route without a session is not tracked.
     * <p>
     * <strong>Client connection context.</strong> This method runs on a virtual thread, whose Vert.x
     * context is whatever the executor bound to it — the client connection's own context only when the
     * executor propagates it. The relay is therefore handed the context the edge root captured on the
     * connection's event loop under {@link #CLIENT_CONTEXT_KEY}, so the upstream dial, the client upgrade
     * and every relay callback run on the client connection's context regardless of the executor.
     *
     * @param ctx     the upgrade request
     * @param request the pipeline request, through stage 5
     * @param route   the selected {@code protocol: websocket} route
     * @param forward the forward policy's result
     */
    public void dispatchWebSocket(RoutingContext ctx, PipelineRequest request, Route route,
            ForwardPolicyStage.Result forward) {
        originValidationStage.validate(request, route.id(), route.effectiveAllowedOrigins());
        String remainder = EdgeRequests.remainderAfterMatchKey(route, EdgeRequests.requireCanonicalPath(request));
        String query = EdgeRequests.renderQuery(forward.query());
        ResolvedUpstream upstreamTarget = route.upstream();
        if (upstreamTarget == null) {
            throw new IllegalStateException("WebSocket dispatch requires a resolved upstream");
        }
        String uri = DispatchStage.upstreamRequestUri(upstreamTarget, remainder, query);
        AtomicBoolean admissionGuard = Objects.requireNonNull(ctx.get(EdgeAdmission.ADMISSION_GUARD_KEY),
                "admission guard missing — handle() must stash it before dispatch");
        Context clientContext = Objects.requireNonNull(ctx.get(CLIENT_CONTEXT_KEY),
                "client connection context missing — handle() must capture it before dispatch");
        // A relay opened for a request a session was let through with is tracked under that session, so
        // it is closed when the session ends. Tracked here, on the virtual thread and before the
        // upstream is dialed: the tracking looks the session up again, which must not run on an event
        // loop, and a relay the registry cannot take is refused before anything is opened.
        SessionRelayRegistry.Tracked sessionRelay = SessionRelayRegistry.untracked();
        Optional<PipelineRequest.AdmittingSession> admittingSession = request.admittingSession();
        if (admittingSession.isPresent()) {
            Optional<SessionRelayRegistry.Tracked> tracked = bffRuntime.trackSessionRelay(
                    admittingSession.get().sessionId(), admittingSession.get().expiresAt());
            if (tracked.isEmpty()) {
                LOGGER.debug("Session relay registry at its capacity on route '%s' — refusing the upgrade",
                        route.id());
                edgeAdmission.releaseAdmission(ctx, admissionGuard);
                ctx.vertx().runOnContext(v -> ResponseEnds.reject(ctx, SERVICE_UNAVAILABLE));
                return;
            }
            sessionRelay = tracked.get();
            ctx.put(EdgeAdmission.SESSION_RELAY_KEY, sessionRelay);
        }
        if (!edgeAdmission.tryAcquireWebSocketRelay(ctx)) {
            // The relay sub-budget is exhausted. The general admission permit is still held and this
            // request will never reach the relay teardown that would return it, so release it here —
            // through the shared guard, so the 503 response's own end handler cannot double-release.
            LOGGER.debug("WebSocket relay budget exhausted on route '%s' — refusing the upgrade",
                    route.id());
            edgeAdmission.releaseAdmission(ctx, admissionGuard);
            ctx.vertx().runOnContext(v -> ResponseEnds.reject(ctx, SERVICE_UNAVAILABLE));
            return;
        }
        ResponseEnds.applyStageSetCookies(ctx.response(), request.responseSetCookies());
        // A handshake-failure response is gateway-authored — there is no origin header to defer to — so
        // the relay receives both header maps merged.
        webSocketRelayStage.relay(ctx, clientContext, route, forward.headers(),
                request.gatewayAuthoredResponseHeaders(), uri, () -> edgeAdmission.releaseAdmission(ctx, admissionGuard),
                sessionRelay);
    }

    /**
     * Dispatches a {@code protocol: grpc} route: streams the request opaquely to the forced-h2
     * upstream via {@link GrpcDispatchStage}, then relays the upstream response — including its
     * {@code grpc-status} / {@code grpc-message} trailers — with {@link ResponseStage#relayWithTrailers}.
     * A dispatch failure (including an h2-negotiation failure) surfaces as a {@link GatewayException}
     * that {@link ErrorResponseWriter} renders as a trailers-only gRPC status rather than problem+json.
     * <p>
     * <strong>What bounds the call.</strong> The dispatch arms neither the request-body idle timeout nor
     * the request-body deadline, so a client-streaming or bidirectional call may pause between its
     * messages. Until the upstream sends its response headers the guard's whole-dispatch timeout bounds
     * the call. From then on the call's {@linkplain ResponseStage.GrpcStreamIdleBound stream-idle bound}
     * does: every request frame the dispatch reads and every upstream frame the relay writes restarts it,
     * and a call that carried no frame in either direction for its fixed five minutes is ended by the
     * relay — the upstream request is reset, and the client response ends through the one end rule with
     * {@code grpc-status: 4} ({@code DEADLINE_EXCEEDED}) in its trailers. Such an end is logged as
     * {@code ApiSheriff-25}, latched per route.
     *
     * @param ctx     the request
     * @param request the pipeline request, through stage 5
     * @param route   the selected {@code protocol: grpc} route
     * @param forward the forward policy's result
     */
    public void dispatchGrpc(RoutingContext ctx, PipelineRequest request, Route route,
            ForwardPolicyStage.Result forward) {
        String remainder = EdgeRequests.remainderAfterMatchKey(route, EdgeRequests.requireCanonicalPath(request));
        String query = EdgeRequests.renderQuery(forward.query());
        ResolvedUpstream upstreamTarget = route.upstream();
        if (upstreamTarget == null) {
            throw new IllegalStateException("gRPC dispatch requires a resolved upstream");
        }
        String uri = DispatchStage.upstreamRequestUri(upstreamTarget, remainder, query);
        SecurityConfiguration routeSecurity = route.securityConfiguration();
        long cap = routeSecurity == null ? defaultMaxBodySize : routeSecurity.maxBodySize();

        GrpcDispatchStage grpcDispatchStage = new GrpcDispatchStage(upstreamClients, cap, upstreamFailureMapper);
        relayGrpcCall(ctx, request, route, ResponseStage.GrpcStreamIdleBound.on(ctx.vertx()),
                body -> grpcDispatchStage.dispatch(route, request.method(), uri, forward.headers(), body,
                        request.declaredContentLength()));
    }

    /**
     * Runs one gRPC call under its stream-idle bound — the wiring of {@link #dispatchGrpc} once the
     * upstream URI and the body cap are resolved. The dispatch is handed the request body the bound
     * {@linkplain ResponseStage.GrpcStreamIdleBound#watch watches}, the bound is passed to
     * {@link ResponseStage#relayWithTrailers}, and its expiry is logged as {@code ApiSheriff-25}.
     * Package-private so a test can run this wiring with a bound built with a shorter timeout than the
     * fixed five minutes.
     */
    void relayGrpcCall(RoutingContext ctx, PipelineRequest request, Route route,
            ResponseStage.GrpcStreamIdleBound idleBound, Function<ReadStream<Buffer>, HttpClientResponse> dispatch) {
        // Every request frame the dispatch reads, and every upstream frame the relay writes, keeps the
        // call's stream-idle bound from expiring.
        long upstreamStartNanos = System.nanoTime();
        HttpClientResponse upstream = dispatchMarkingUnreadBody(ctx,
                () -> dispatch.apply(idleBound.watch(inboundBody(ctx))));
        sheriffMetrics.recordUpstreamDuration(route.id(), Duration.ofNanos(System.nanoTime() - upstreamStartNanos));
        gatewayEventCounter.increment(EventType.REQUEST_FORWARDED);
        idleBound.expiry().onSuccess(expired -> logGrpcStreamIdleTimeout(route.id()));
        // The trailer relay mutates the event-loop-bound response; hop back onto the event loop, exactly
        // like the HTTP relay path.
        List<String> stageSetCookies = request.responseSetCookies();
        Map<String, String> setHeaders = Map.copyOf(request.responseHeaders());
        Map<String, String> defaultHeaders = Map.copyOf(request.responseDefaultHeaders());
        relayOnEventLoop(ctx, stageSetCookies, upstream, end -> responseStage.relayWithTrailers(upstream,
                ctx.response(), route.notModifiedEnabled(), setHeaders, defaultHeaders, end, idleBound));
    }

    /**
     * Records one gRPC call its stream-idle bound ended (see {@link ResponseStage.GrpcStreamIdleBound}).
     * <p>
     * <strong>Latched per route, to bound the log volume</strong>, like the request-body idle timeout's
     * record: the first occurrence on a route is recorded at {@code INFO}; every repeat on that route is a
     * {@code DEBUG} diagnostic. The latch is keyed by the route id, so it holds at most one entry per
     * configured route. The record carries the route id and the fixed timeout in seconds only.
     */
    private void logGrpcStreamIdleTimeout(String route) {
        long timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(
                ResponseStage.GrpcStreamIdleBound.STREAM_IDLE_TIMEOUT_MILLIS);
        if (grpcStreamIdleReported.add(route)) {
            LOGGER.info(ApiSheriffLogMessages.INFO.GRPC_STREAM_IDLE_TIMEOUT, route, timeoutSeconds);
            return;
        }
        LOGGER.debug("gRPC call on route '%s' carried no frame for %s seconds again — already reported once for "
                + "this route, stays at DEBUG for the rest of the process", route, timeoutSeconds);
    }

    /**
     * Writes a redirect route's terminal answer on the event loop: the configured status, the
     * accumulated stage headers and {@code Set-Cookie} lines, the gateway-owned
     * {@code Cache-Control: no-store} when the answer is {@linkplain RedirectStage.Answer#noStore()
     * uncacheable} and the gateway-owned {@code Vary} when the answer names
     * {@linkplain RedirectStage.Answer#vary() varying request headers}, then the {@code Location} —
     * written last so no stage header can displace it — and an empty body.
     * <p>
     * All three gateway-owned headers are written <em>after</em> the stage headers, so none can be
     * displaced by an accumulated value of the same name. Neither {@code Cache-Control} nor
     * {@code Vary} is a gateway-owned security header, so neither carries a {@code header_modes}
     * entry and the set/default precedence does not apply to them — and a redirect answer has no
     * origin response to defer to in any case.
     * <p>
     * {@code Vary} is the one exception to "written after, so it wins": writing it last would
     * otherwise DISCARD the stage value rather than override a competing one, and the stage value is
     * load-bearing — the CORS reflection announces {@code Origin} there. It is therefore merged with
     * the answer's names through {@link SecurityHeadersStage#mergedVary}, the same rule the stage
     * itself uses.
     *
     * @param ctx     the request
     * @param request the pipeline request, through stage 4
     * @param answer  the redirect stage's answer
     */
    public void writeRedirect(RoutingContext ctx, PipelineRequest request, RedirectStage.Answer answer) {
        Map<String, String> stageHeaders = request.gatewayAuthoredResponseHeaders();
        List<String> stageSetCookies = request.responseSetCookies();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(answer.status());
            stageHeaders.forEach(response::putHeader);
            ResponseEnds.applyStageSetCookies(response, stageSetCookies);
            if (answer.noStore()) {
                response.putHeader(CACHE_CONTROL_HEADER, NO_STORE);
            }
            if (!answer.vary().isEmpty()) {
                // MERGE, not replace. The stage headers written above can already carry a Vary — the
                // CORS reflection announces Origin there — and overwriting it would drop that name
                // while adding these, trading one cache-variant bug for another.
                response.putHeader(VARY_HEADER,
                        SecurityHeadersStage.mergedVary(stageHeaders.get(VARY_HEADER), answer.vary()));
            }
            response.putHeader(LOCATION_HEADER, answer.location());
            ResponseEnds.endResponse(ctx, response::end);
        });
    }

    /**
     * Writes the pipeline's short-circuit answer on the event loop — a CORS preflight, or a stage that
     * answered the request itself (the BFF navigation redirect) — with the accumulated stage headers
     * and {@code Set-Cookie} lines.
     *
     * @param ctx     the request
     * @param request the pipeline request carrying the short-circuit status
     */
    public void writeShortCircuit(RoutingContext ctx, PipelineRequest request) {
        int status = request.shortCircuitStatus().orElse(NO_CONTENT);
        Map<String, String> responseHeaders = request.gatewayAuthoredResponseHeaders();
        List<String> setCookies = request.responseSetCookies();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(status);
            responseHeaders.forEach(response::putHeader);
            ResponseEnds.applyStageSetCookies(response, setCookies);
            ResponseEnds.endResponse(ctx, response::end);
        });
    }

    /**
     * Starts a streamed response relay on the request's event loop — the relay mutates the
     * event-loop-bound {@link HttpServerResponse} — after applying the pipeline's {@code Set-Cookie}
     * values, and routes <em>every</em> relay failure to {@link #failRelay}: one the relay reports on
     * its future, and one thrown while the relay is being started (an upstream response that can no
     * longer be piped throws from {@code pipe} synchronously). A thrown failure must not escape this
     * runOnContext task: it would reach only Vert.x's uncaught-exception handler, the client response
     * would never end, and the client would wait for its own timeout.
     * <p>
     * <strong>A relay that fails to start releases the upstream response.</strong> The dispatched
     * upstream response is still paused when the relay is started, and only the relay would consume
     * it. When starting the relay throws, the upstream exchange is therefore reset before the client is
     * answered — on HTTP/1.x its connection is closed instead of being held, on HTTP/2 the one stream is
     * reset. For a response that already ended the reset is a no-op. A failure the relay reports on its
     * future goes to {@code failRelay} as described above; the upstream exchange is not reset on that
     * path.
     * <p>
     * <strong>A relay ends its response through the one end rule.</strong> The relay is handed a
     * {@link ResponseEnd} that routes the end of the client response through
     * {@link ResponseEnds#endResponse}: once the upstream response has been streamed, an HTTP/2 stream
     * whose request body is still open — an upstream that answered before reading the whole body —
     * stops receiving that body, the upstream request still receiving it is reset, and the end and a
     * {@code NO_ERROR} reset follow once the body stops arriving or the time cap passes. The relay's
     * future completes after that.
     *
     * @param ctx             the request whose response the relay writes
     * @param stageSetCookies the pipeline's accumulated {@code Set-Cookie} values
     * @param upstream        the dispatched upstream response the relay streams
     * @param relay           starts the relay with the {@link ResponseEnd} it must end the response
     *                        through, and returns the future completing when it has finished
     */
    public static void relayOnEventLoop(RoutingContext ctx, List<String> stageSetCookies, HttpClientResponse upstream,
            Function<ResponseEnd, Future<Void>> relay) {
        ctx.vertx().runOnContext(v -> startRelay(ctx, stageSetCookies, upstream, relay)
                .onFailure(failure -> failRelay(ctx, failure, upstream.statusCode())));
    }

    /**
     * Serves an asset route's terminal action: resolves the confined asset through the route's
     * {@link AssetSource} (behind the shared {@code PathConfinement} and gateway-owned
     * {@code AssetResponseEnvelope}) and writes the buffered, governed response back to the
     * client. GET/HEAD-only enforcement and header governance live in the source and envelope;
     * this method only routes and writes.
     */
    private void serveAsset(RoutingContext ctx, PipelineRequest request, Route route, String remainder) {
        AssetSource source = route.assetSource();
        if (source == null) {
            throw new IllegalStateException("asset dispatch requires an asset source");
        }
        AssetSource.Served served = DispatchStage.serveAsset(source, request.method(), remainder);
        writeBufferedAsset(ctx, request, served, source instanceof DirectoryAssetSource);
    }

    /**
     * Writes a buffered asset response. A {@code source: directory} miss ({@code 404}) is the one asset
     * outcome that negotiates an HTML error page ({@link ErrorPageClassifier.Exit#DIRECTORY_ASSET_NOT_FOUND});
     * every other outcome — and every {@code source: upstream} response, which relays what a secondary
     * origin said ({@link ErrorPageClassifier.Exit#UPSTREAM_ASSET}) — is written in its governed shape.
     */
    private void writeBufferedAsset(RoutingContext ctx, PipelineRequest request, AssetSource.Served served,
            boolean directorySource) {
        if (directorySource && served.status() == NOT_FOUND && errorResponseWriter.answeredWithErrorPage(ctx, request,
                ErrorPageClassifier.classify(ErrorPageClassifier.Exit.DIRECTORY_ASSET_NOT_FOUND), served.status(),
                List.of())) {
            return;
        }
        Map<String, String> stageHeaders = request.gatewayAuthoredResponseHeaders();
        List<String> stageSetCookies = request.responseSetCookies();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(served.status());
            ResponseEnds.applyStageSetCookies(response, stageSetCookies);
            // The stage-0 security headers (HSTS, frame options, …) apply to every response; the
            // envelope's governed headers (fixed Content-Type, nosniff, no-store) are written last
            // so they win any name collision.
            stageHeaders.forEach(response::putHeader);
            served.headers().forEach(response::putHeader);
            ResponseEnds.endResponse(ctx, () -> response.end(Buffer.buffer(served.body())));
        });
    }

    /**
     * Runs an upstream dispatch and, when it ends with a body the dispatch could not finish reading,
     * marks the request exactly as a framing-gate rejection is marked
     * ({@link ConnectionRetirement#markFramingRejection}) before the failure propagates. Two triggers
     * leave such a body behind:
     * <ul>
     *   <li>the dispatch refused the body on its framing — a body byte on a bodyless method, or a body
     *       disagreeing with its declared length, both a {@link EventType#SECURITY_FILTER_VIOLATION}
     *       answered {@code 400};</li>
     *   <li>the body made no progress for the request-body idle timeout, was still arriving at the
     *       request-body deadline, or was read again only after that deadline had passed — an upstream that
     *       resumed reading it late, or an attempt that obtained its pooled connection only after the
     *       deadline — {@link EventType#REQUEST_BODY_TIMEOUT}, answered {@code 408}.</li>
     * </ul>
     * Either way the bytes behind the answer were not read, so they cannot frame a following request:
     * on HTTP/1.x the connection is retired — recorded at once, the answer carries
     * {@code Connection: close} and the connection is closed once it has been written. On HTTP/2 the
     * answer ends that one stream through the one end rule ({@link ResponseEnds#endResponse}), its open
     * body is reset with {@code NO_ERROR}, and the connection stays open.
     */
    private HttpClientResponse dispatchMarkingUnreadBody(RoutingContext ctx, Supplier<HttpClientResponse> dispatch) {
        try {
            return dispatch.get();
        } catch (GatewayException rejected) {
            EventType eventType = rejected.getEventType();
            if (eventType == EventType.SECURITY_FILTER_VIOLATION || eventType == EventType.REQUEST_BODY_TIMEOUT) {
                connectionRetirement.markFramingRejection(ctx);
            }
            throw rejected;
        }
    }

    /**
     * The late-violation callback of a proxied request (see {@link DispatchStage.BodyFraming}): a
     * refusal of the request body that came only after the dispatch handed the upstream response over
     * for relay. It runs before the dispatch resets the upstream request, and every refusal is metered
     * and logged like the same rejection answered before the hand-over
     * ({@link RejectionReporter#reportRejection}).
     * <p>
     * <strong>A body byte on a bodyless method</strong> ({@link EventType#SECURITY_FILTER_VIOLATION}):
     * the dispatch can no longer answer it with a {@code 400}, and ending the response would present a
     * truncated answer as complete, so the client response is <strong>reset</strong> instead — on HTTP/2
     * the one stream, with {@code CANCEL}. The callback runs on the inbound request's event loop, so the
     * response is reset before the aborted relay could end it; the event stashed under
     * {@link #LATE_BODY_REFUSAL_KEY} keeps {@link #failRelay} from ending it afterwards.
     * <p>
     * <strong>A body still arriving at the request-body deadline, or read again after it passed</strong>
     * ({@link EventType#REQUEST_BODY_TIMEOUT}): the relay is ended through the one end rule, and the status
     * the upstream sent stands. The reset of the upstream request that follows this callback fails the
     * relay, and {@link #failRelay} ends the client response through {@link ResponseEnds#endResponse} — on
     * HTTP/2 the stream is then reset with {@code NO_ERROR}. A client response whose head has not been
     * written yet — the upstream sent its head but no body byte had been relayed, or the relay had not
     * started — is ended with the upstream's status and no body, never answered {@code 502}: the event
     * stashed under {@link #LATE_BODY_REFUSAL_KEY}, recorded here before the reset, tells
     * {@code failRelay} so. The body behind it was not read, so an HTTP/1.x connection is recorded as
     * retired here, before that reset, in the synchronized retired set only
     * ({@link ConnectionRetirement#recordRetirement}) — this callback runs on the upstream request's event
     * loop, and the request's context data is owned by the client connection's. The end that follows
     * recognises the late timeout from the event stashed under {@link #LATE_BODY_REFUSAL_KEY} and closes
     * the connection (see {@link #failRelay}); a response that has already ended has its HTTP/1.x
     * connection closed instead, decided on the client connection's own context, the one the response is
     * ended on (see {@link #closeIfEndedOn}).
     */
    private Consumer<GatewayException> lateBodyRefusal(RoutingContext ctx) {
        AtomicReference<@Nullable EventType> lateRefusal = new AtomicReference<>();
        ctx.put(LATE_BODY_REFUSAL_KEY, lateRefusal);
        Context clientContext = Objects.requireNonNull(ctx.get(CLIENT_CONTEXT_KEY),
                "client connection context missing — handle() must capture it before dispatch");
        return refusal -> {
            rejectionReporter.reportRejection(ctx, refusal);
            lateRefusal.set(refusal.getEventType());
            if (refusal.getEventType() == EventType.REQUEST_BODY_TIMEOUT) {
                retireAfterLateBodyTimeout(ctx, clientContext);
                return;
            }
            HttpServerResponse response = ctx.response();
            if (!response.ended()) {
                response.reset(HTTP2_CANCEL);
            }
        };
    }

    /**
     * Records the HTTP/1.x connection of a request whose body outlived the request-body deadline after
     * the hand-over as retired, so nothing further is processed on it and the relay's end closes it (see
     * {@link #failRelay}); when the response has already ended there is no end to come, and the connection
     * is closed instead (see {@link #closeIfEndedOn}). A no-op on HTTP/2, where the one end rule ends just
     * the stream.
     * <p>
     * The retirement is recorded at once, on the thread the callback runs on, so it is in place before the
     * upstream request is reset and the relay fails. This runs on the upstream request's event loop, so it
     * writes only to the synchronized retired set and never to the request's context data, a plain map the
     * client connection's loop owns; the relay's end learns of the late timeout from the event stashed
     * under {@link #LATE_BODY_REFUSAL_KEY}.
     */
    private void retireAfterLateBodyTimeout(RoutingContext ctx, Context clientContext) {
        connectionRetirement.recordRetirement(ctx);
        if (ResponseEnds.isHttp1(ctx.request().version())) {
            closeIfEndedOn(clientContext, ctx.request());
        }
    }

    /**
     * Closes the HTTP/1.x connection of {@code request} when its response has already ended, deciding on
     * {@code clientContext}. The late callback runs on the upstream request's event loop, while the
     * response is ended on the client connection's, so whether it has ended is read there: a response
     * the relay ends normally just before this decision is seen as ended and its connection is closed. A
     * response not yet ended is left to the relay's end, which closes the marked connection.
     */
    static void closeIfEndedOn(Context clientContext, HttpServerRequest request) {
        clientContext.runOnContext(v -> {
            if (request.response().ended()) {
                request.connection().close();
            }
        });
    }

    /** @return the event of this request's late body refusal, or {@code null} when none was made */
    private static @Nullable EventType lateBodyRefusalOf(RoutingContext ctx) {
        AtomicReference<@Nullable EventType> lateRefusal = ctx.get(LATE_BODY_REFUSAL_KEY);
        return lateRefusal == null ? null : lateRefusal.get();
    }

    /**
     * The request body to stream upstream: the decorator the edge root armed under
     * {@link ResponseEnds#INBOUND_BODY_KEY}, which remembers an inbound failure, or the bare request
     * where none was armed.
     */
    private static ReadStream<Buffer> inboundBody(RoutingContext ctx) {
        DispatchStage.InboundBody armed = ctx.get(ResponseEnds.INBOUND_BODY_KEY);
        return armed != null ? armed : ctx.request();
    }

    /**
     * Starts the relay on the event loop, turning a failure thrown while starting it into a failed
     * future after releasing the paused upstream response (see {@link #relayOnEventLoop}).
     */
    private static Future<Void> startRelay(RoutingContext ctx, List<String> stageSetCookies,
            HttpClientResponse upstream, Function<ResponseEnd, Future<Void>> relay) {
        // Deliberately the broad RuntimeException: whatever starting the relay throws, the client
        // must be answered, so no narrower catch is correct. The failure is not swallowed — it is
        // handed to failRelay exactly like a failure the relay reports on its future.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            ResponseEnds.applyStageSetCookies(ctx.response(), stageSetCookies);
            return relay.apply(ending -> ResponseEnds.endResponse(ctx, ending));
        } catch (RuntimeException startFailure) {
            // Nothing consumes the paused upstream response once the relay did not start: release it.
            upstream.request().reset();
            return Future.failedFuture(startFailure);
        }
    }

    /**
     * Answers a relay that failed, so the client is never left waiting: the end of the truncated response
     * when its head was already written; otherwise a {@code 502}, except after a late request-body
     * timeout ({@link #lateBodyRefusal}), where the response ends with the status the upstream sent and no
     * body (see {@link #prepareFailedRelayHead}). That end passes the one end rule
     * ({@link ResponseEnds#endResponse}) like every other response end: an HTTP/2 stream whose request
     * body is still open is reset with {@code NO_ERROR} once it has been written. An HTTP/1.x connection
     * marked for retirement — a body that outlived the request-body deadline after the hand-over
     * ({@link #lateBodyRefusal}), told by the late refusal's event (see {@link #retireFailedRelayConnection})
     * — carries {@code Connection: close} when its head is still unwritten and is closed once the end has
     * been written. A response a bodyless request's late body refusal reset
     * ({@link #lateBodyRefusal}) is never ended here.
     *
     * @param ctx            the request whose relay failed
     * @param failure        the relay's failure, logged at {@code DEBUG} only
     * @param upstreamStatus the status of the upstream response the relay was streaming
     */
    private static void failRelay(RoutingContext ctx, Throwable failure, int upstreamStatus) {
        LOGGER.debug(failure, "Response relay failed: %s", failure.getMessage());
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            EventType lateRefusal = lateBodyRefusalOf(ctx);
            // A response a late body refusal reset is never ended: an end would present the truncated
            // answer as complete.
            if (response.ended() || lateRefusal == EventType.SECURITY_FILTER_VIOLATION) {
                return;
            }
            prepareFailedRelayHead(response, lateRefusal == EventType.REQUEST_BODY_TIMEOUT, upstreamStatus);
            retireFailedRelayConnection(ctx, lateRefusal);
            ResponseEnds.endResponse(ctx, response::end);
        });
    }

    /**
     * Retires the connection a failed relay's response is written on, when it has to be: the HTTP/1.x
     * connection of a request whose body outlived the request-body deadline after the hand-over
     * ({@link EventType#REQUEST_BODY_TIMEOUT}), told by the event the late callback stashed under
     * {@link #LATE_BODY_REFUSAL_KEY} rather than by a mark on the request's context data, which that
     * callback — running on the upstream request's event loop — must not write; every other failure
     * retires the connection only where the request carries the mark an earlier rejection left (see
     * {@link ConnectionRetirement#retireMarkedConnection}). Runs on the event loop, before the response is
     * ended.
     *
     * @param ctx         the request whose relay failed
     * @param lateRefusal the event of the request's late body refusal, or {@code null} when none was made
     */
    static void retireFailedRelayConnection(RoutingContext ctx, @Nullable EventType lateRefusal) {
        if (lateRefusal == EventType.REQUEST_BODY_TIMEOUT && ResponseEnds.isHttp1(ctx.request().version())) {
            ConnectionRetirement.retireConnectionAfterResponse(ctx);
        } else {
            ConnectionRetirement.retireMarkedConnection(ctx);
        }
    }

    /**
     * Sets the head of a failed relay's response that has not been written yet; a written head is left
     * as it is — Vert.x refuses a status change once {@code headWritten()} is true, and the end that
     * follows ends the truncated response.
     * <p>
     * No body byte follows an unwritten head, so the {@code Content-Length} the relay copied from the
     * upstream is removed: left in place, it would frame the answer as carrying the upstream body, and
     * the client would wait for bytes that never come. The status is the one the upstream sent when the
     * relay failed because a late request-body timeout reset the upstream request — the upstream had
     * already answered, and its status stands — and {@code 502} for every other failure.
     */
    static void prepareFailedRelayHead(HttpServerResponse response, boolean bodyTimedOutLate, int upstreamStatus) {
        if (response.headWritten()) {
            return;
        }
        response.headers().remove(CONTENT_LENGTH_HEADER);
        response.setStatusCode(bodyTimedOutLate ? upstreamStatus : BAD_GATEWAY);
    }
}
