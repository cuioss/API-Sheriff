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

import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * The WebSocket relay terminal action — the protocol-dispatch seam's WebSocket leg, replacing the
 * HTTP {@link DispatchStage} for a {@code protocol: websocket} route once the pipeline and the
 * {@code OriginValidationStage} have accepted the handshake.
 * <p>
 * <strong>Dial-before-upgrade.</strong> The upstream WebSocket is dialed first, over the edge-wide
 * Vert.x {@link WebSocketClient} — the dedicated dialer that replaced the deprecated
 * {@code HttpClient.webSocket(WebSocketConnectOptions)}. One client serves every route because the
 * dialer carries no per-route state: host, port, TLS and URI all ride on the per-dial
 * {@link WebSocketConnectOptions}. Only when the upstream confirms {@code 101} is the client
 * upgrade completed ({@link io.vertx.core.http.HttpServerRequest#toWebSocket()}); the two legs are
 * then relayed opaquely. If the upstream is unreachable or times out, the failure is mapped to
 * {@code 502}/{@code 504} <em>before</em> the client upgrade, so no half-open upgrade is ever left
 * dangling. If the upstream <em>refuses</em> the upgrade with a status, that status is relayed to
 * the client verbatim.
 * <p>
 * <strong>Opaque bidirectional relay.</strong> Every frame — text, binary, continuation, ping,
 * pong — is forwarded to the other leg with fragmentation preserved and no per-frame filtering;
 * close is relayed transparently and a half-close on either leg closes both. Data-frame relay
 * applies Vert.x write-queue backpressure (pause the busy source until the target drains). An
 * established relay is bounded by the route's per-route {@code idle_timeout_seconds}: a timer,
 * reset by any frame in either direction (ping/pong counting as activity), closes both legs with
 * WebSocket close code {@code 1001} (Going Away) on expiry and meters
 * {@link EventType#WEBSOCKET_IDLE_TIMEOUT}.
 * <p>
 * <strong>Threading.</strong> Every socket operation is event-loop-bound, so the relay runs on the client
 * connection's context: {@code GatewayEdgeRoute.handle()} captures it on the connection's event loop
 * before the virtual-thread hop, and {@link #relay} hops onto it whatever thread calls it. The upstream
 * dial and its success and failure callbacks, the client upgrade's completion, {@code establishRelay} and
 * every {@code RelaySession} handler — frame, pong, close, exception, idle timer and drain — run on the
 * client connection's context; the upstream connection is created from that context and shares its
 * event loop, so the frame relay is single-threaded. Before this fix the relay hopped onto the context
 * of whatever thread called {@code relay()}. In production, Quarkus's context-preserving virtual-thread
 * executor had already bound the client connection's context to that thread, and the early-frame
 * integration test saw no loss in N = 50 upgrades; the client-leg production consequence is therefore
 * refuted as far as measured ({@code d3-production-verdict: GREEN}, inconclusive with stated power —
 * zero losses in 50 upgrades bounds the per-upgrade loss rate below about 6% at 95% confidence). The
 * unit fixture's plain virtual-thread executor did not bind that context, and that is where the
 * client-leg loss reproduced.
 * <p>
 * <strong>No frame reaches a leg before its handler.</strong> Vert.x delivers a WebSocket's inbound
 * frames from the moment the socket exists, while the relay installs its frame handlers only when
 * {@code RelaySession.start()} runs; a frame arriving on either leg in between was dispatched to no
 * handler and lost. {@code WebSocketRelayStageTest}'s deterministic reproductions, which defer the wiring
 * by a fixed delay, proved that window on both legs — the client's first frame written in its own upgrade
 * callback, and an upstream greeting written on accept, were each lost in every recorded run. Three
 * remedies were evaluated against them:
 * <ol>
 *   <li><em>(a) Hop to the captured client connection context — taken.</em> {@link #relay} runs the
 *       upstream dial, its callbacks and the client upgrade on the context {@code GatewayEdgeRoute}
 *       captured on the client connection's event loop, whatever thread called {@code relay()}. On its
 *       own it cannot close the upstream leg, whose window spans the whole asynchronous client upgrade,
 *       nor survive a scheduling gap before the wiring runs.</li>
 *   <li><em>(b) Pause each leg at acquisition, resume after wiring — taken.</em> The upstream leg is
 *       paused as the first statement of the dial's success callback and the client leg as the first
 *       statement of the upgrade's completion; {@code RelaySession.start()} installs every handler on
 *       both legs, then resumes both. A frame that arrives in between waits in Vert.x's own inbound
 *       buffer, so the reproductions pass with their deferred wiring still in place.</li>
 *   <li><em>(c) A gateway-side early-frame buffer — rejected.</em> Pausing already buffers inside Vert.x;
 *       a buffer of the gateway's own would add a bounded-drop surface — a drop log record and its
 *       documentation — for no gain.</li>
 * </ol>
 * No frame is dropped by this design, so the window has no log record and no counter.
 * <p>
 * <strong>An established relay owns an admission permit for its lifetime.</strong> A completed client
 * upgrade takes the connection over, so the HTTP response never ends and the edge's end handler never
 * fires — the relay is therefore handed a release callback and is the component responsible for
 * returning the permit. It is invoked on every teardown path and never at upgrade completion: releasing
 * on upgrade would under-count concurrent relays and re-open the exhaustion window the callback exists
 * to close. Two terminal paths carry it — the established relay's single idempotent
 * {@code RelaySession.closeBoth} funnel (graceful close, abrupt disconnect, idle reclaim, relay error),
 * and the client-upgrade-failure branch of {@link #onUpstreamConnected}, which constructs no
 * {@code RelaySession} at all. The upstream-dial-failure path ({@link #onUpstreamFailure}) does not
 * invoke it: that path ends the HTTP response, so the edge's own end handler releases the permit
 * through the same idempotent guard.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class WebSocketRelayStage {

    private static final CuiLogger LOGGER = new CuiLogger(WebSocketRelayStage.class);

    private static final String HTTPS = "https";
    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 300;
    private static final int BAD_GATEWAY = 502;
    private static final short CLOSE_NORMAL = 1000;
    private static final short CLOSE_INTERNAL_ERROR = 1011;

    private final WebSocketClient webSocketClient;
    private final UpstreamFailureMapper failureMapper;
    private final GatewayEventCounter eventCounter;
    private final RelayObserver observer;

    /**
     * Creates the production relay stage, which runs every relay's wiring immediately (the no-op
     * {@link RelayObserver#NO_OP} observer).
     *
     * @param webSocketClient the edge-wide dialer for every upstream WebSocket handshake
     * @param failureMapper the shared mapper turning an upstream dial failure into the error contract
     * @param eventCounter  the shared in-process event counter
     */
    public WebSocketRelayStage(WebSocketClient webSocketClient, UpstreamFailureMapper failureMapper,
            GatewayEventCounter eventCounter) {
        this(webSocketClient, failureMapper, eventCounter, RelayObserver.NO_OP);
    }

    /**
     * <strong>Test-only.</strong> Creates a relay stage whose relay wiring is handed to the given
     * {@link RelayObserver} instead of running directly, so a test can observe or deliberately delay
     * the moment the relay's frame handlers are installed. Package-private and selected by no
     * configuration key or system property; production code constructs the stage through the public
     * constructor, which passes {@link RelayObserver#NO_OP}.
     *
     * @param webSocketClient the edge-wide dialer for every upstream WebSocket handshake
     * @param failureMapper the shared mapper turning an upstream dial failure into the error contract
     * @param eventCounter  the shared in-process event counter
     * @param observer      the test observer every established relay hands its wiring to
     */
    WebSocketRelayStage(WebSocketClient webSocketClient, UpstreamFailureMapper failureMapper,
            GatewayEventCounter eventCounter, RelayObserver observer) {
        this.webSocketClient = Objects.requireNonNull(webSocketClient, "webSocketClient");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Dials the upstream WebSocket and, on success, upgrades the client and establishes the opaque
     * relay. Runs asynchronously on the client connection's context, whatever thread calls it; the
     * caller returns immediately.
     * <p>
     * The stage-0 security headers accumulated on the request are retained across the asynchronous
     * dial so that a handshake-failure response ({@link #onUpstreamFailure}) carries the same
     * gateway-controlled headers as the HTTP ({@link ResponseStage#relay}) and gRPC
     * ({@link GrpcStatusMapper#renderRejection}) rejection paths.
     *
     * @param ctx             the routing context (client request/response and Vert.x handle)
     * @param clientContext   the client connection's own Vert.x context, captured on its event loop
     *                        before any virtual-thread hop; the upstream dial, its callbacks, the client
     *                        upgrade and every relay callback run on it
     * @param route           the resolved route runtime (upstream, shared client, idle timeout)
     * @param forwardHeaders  the mode-filtered forwarded header set computed by stage 5
     * @param securityHeaders the stage-0 security headers accumulated on the response, applied to a
     *                        handshake-failure response before it is ended
     * @param requestUri      the upstream request URI (path + mode-filtered query)
     * @param releaseAdmission the edge's idempotent admission-release callback, invoked once at relay
     *                        teardown — on the established relay's {@code closeBoth} funnel and on the
     *                        client-upgrade-failure branch — and never at upgrade completion
     */
    public void relay(RoutingContext ctx, Context clientContext, RouteRuntime route,
            Map<String, String> forwardHeaders, Map<String, String> securityHeaders, String requestUri,
            Runnable releaseAdmission) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(clientContext, "clientContext");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(forwardHeaders, "forwardHeaders");
        Objects.requireNonNull(securityHeaders, "securityHeaders");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(releaseAdmission, "releaseAdmission");
        Map<String, String> retainedSecurityHeaders = Map.copyOf(securityHeaders);
        // No per-route HttpClient guard here: the handshake is dialed by the edge-wide WebSocketClient,
        // so the route's own client is not an input to this path. DispatchStage keeps that guard for the
        // HTTP leg, which does consume it. The resolved upstream below IS this path's input.
        ResolvedUpstream upstream = route.getUpstream();
        if (upstream == null) {
            throw new IllegalStateException("WebSocket dispatch requires a resolved upstream");
        }
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl(HTTPS.equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        forwardHeaders.forEach(options::addHeader);
        // The client connection's own context, not the calling thread's: a virtual thread may carry no
        // context, or one the executor bound for its own purposes, and a hop onto either would run the
        // client upgrade's completion off the connection's event loop.
        clientContext.runOnContext(v -> webSocketClient.connect(options)
                .onSuccess(upstreamWs -> onUpstreamConnected(ctx, route, upstreamWs, releaseAdmission))
                .onFailure(failure -> onUpstreamFailure(ctx, route, failure, retainedSecurityHeaders)));
    }

    private void onUpstreamConnected(RoutingContext ctx, RouteRuntime route, WebSocket upstreamWs,
            Runnable releaseAdmission) {
        // Hold the upstream leg's inbound frames from the moment it is acquired: an upstream that speaks
        // first would otherwise reach a leg with no frame handler during the asynchronous client upgrade.
        // RelaySession.start() resumes it once every handler is installed.
        upstreamWs.pause();
        ctx.request().toWebSocket()
                .onSuccess(clientWs -> {
                    // Likewise for the client leg: its first frame may already be in flight.
                    clientWs.pause();
                    establishRelay(ctx, route, clientWs, upstreamWs, releaseAdmission);
                })
                .onFailure(failure -> {
                    // The upstream is already upgraded but the client handshake could not complete;
                    // there is no HTTP response to render anymore. Close the upstream leg and drop.
                    // No RelaySession is constructed here, so this branch owns its own admission
                    // release — without it the permit acquired for this request is stranded, since the
                    // HTTP end handler no longer has a response to fire on.
                    LOGGER.debug(failure, "WebSocket client upgrade failed on route '%s': %s", route.getId(),
                            failure.getMessage());
                    releaseAdmission.run();
                    closeQuietly(upstreamWs, CLOSE_INTERNAL_ERROR, "client upgrade failed");
                });
    }

    private void onUpstreamFailure(RoutingContext ctx, RouteRuntime route, Throwable failure,
            Map<String, String> securityHeaders) {
        int status;
        if (failure instanceof UpgradeRejectedException rejected) {
            // The upstream explicitly refused the upgrade with a status — relay it verbatim.
            status = rejected.getStatus();
            LOGGER.debug("WebSocket upstream refused upgrade on route '%s' with status %s", route.getId(), status);
        } else {
            EventType type = failureMapper.classify(failure);
            eventCounter.increment(type);
            status = type.hasHttpMapping() ? type.httpStatus() : BAD_GATEWAY;
            LOGGER.debug(failure, "WebSocket upstream dial failed on route '%s': %s", route.getId(),
                    failure.getMessage());
        }
        HttpServerResponse response = ctx.response();
        if (response.ended()) {
            return;
        }
        if (!response.headWritten()) {
            // Apply the gateway (stage-0) security headers before the head is written, mirroring the
            // HTTP (ResponseStage.relay) and gRPC (GrpcStatusMapper.renderRejection) rejection paths.
            securityHeaders.forEach(response::putHeader);
            response.setStatusCode(status);
        }
        response.end();
    }

    private void establishRelay(RoutingContext ctx, RouteRuntime route, ServerWebSocket clientWs,
            WebSocket upstreamWs, Runnable releaseAdmission) {
        Integer declaredIdleSeconds = route.getEffectiveWebSocketIdleTimeoutSeconds();
        int idleSeconds = declaredIdleSeconds == null ? DEFAULT_IDLE_TIMEOUT_SECONDS : declaredIdleSeconds;
        LOGGER.info(ApiSheriffLogMessages.INFO.WEBSOCKET_RELAY_ESTABLISHED, route.getId());
        eventCounter.increment(EventType.REQUEST_FORWARDED);
        // The admission permit stays held for the relay's whole lifetime — the session releases it from
        // its single teardown funnel, never here at upgrade completion.
        RelaySession session = new RelaySession(ctx.vertx(), route.getId(), clientWs, upstreamWs, idleSeconds,
                eventCounter, releaseAdmission);
        observer.beforeWiring(session::start);
    }

    private static void closeQuietly(WebSocketBase ws, short code, @Nullable String reason) {
        if (!ws.isClosed()) {
            ws.close(code, reason);
        }
    }

    /**
     * <strong>Test-only</strong> observation seam on an established relay's wiring. Every established
     * relay hands the installation of its frame, pong, close and exception handlers
     * ({@code RelaySession.start()}) to {@link #beforeWiring(Runnable)} rather than running it
     * directly, so a test can defer that installation and reproduce a frame that reaches a leg before
     * its handler exists.
     * <p>
     * Package-private and reachable only through the stage's package-private constructor: no
     * configuration key or system property selects an observer, and production always runs with
     * {@link #NO_OP}.
     *
     * @since 1.0
     */
    interface RelayObserver {

        /** The production observer: runs the wiring immediately, on the calling thread. */
        RelayObserver NO_OP = Runnable::run;

        /**
         * Receives an established relay's wiring. An implementation must run {@code wiring} exactly
         * once, on the relay's Vert.x context — immediately, or later through that context (for example
         * from a {@code vertx.setTimer} callback) to model a scheduling gap.
         *
         * @param wiring installs every handler on both relay legs and arms the idle timer
         */
        void beforeWiring(Runnable wiring);
    }

    /**
     * One established relay: the two legs, the idle-timeout timer, the frame-relay wiring, and the
     * admission permit the relay holds for its lifetime. All callbacks run on the client connection's
     * context — the upstream leg's on the upstream connection created from it, which shares its event
     * loop — so the mutable {@code closed} / timer state is single-threaded and needs no synchronization.
     * <p>
     * {@link #closeBoth} is the single idempotent teardown funnel every terminal path reaches — client
     * close, upstream close, idle reclaim and relay error alike — so it is also the single site the
     * admission-release callback is invoked from, exactly once.
     */
    private static final class RelaySession {

        private final Vertx vertx;
        private final String routeId;
        private final ServerWebSocket clientWs;
        private final WebSocket upstreamWs;
        private final int idleSeconds;
        private final long idleMillis;
        private final GatewayEventCounter eventCounter;
        private final Runnable releaseAdmission;
        private long idleTimerId = -1L;
        private boolean closed;

        RelaySession(Vertx vertx, String routeId, ServerWebSocket clientWs, WebSocket upstreamWs, int idleSeconds,
                GatewayEventCounter eventCounter, Runnable releaseAdmission) {
            this.vertx = vertx;
            this.routeId = routeId;
            this.clientWs = clientWs;
            this.upstreamWs = upstreamWs;
            this.idleSeconds = idleSeconds;
            this.idleMillis = idleSeconds * 1000L;
            this.eventCounter = eventCounter;
            this.releaseAdmission = releaseAdmission;
        }

        void start() {
            wire(clientWs, upstreamWs);
            wire(upstreamWs, clientWs);
            clientWs.closeHandler(v -> closeBoth(resolveCloseCode(clientWs.closeStatusCode()), clientWs.closeReason()));
            upstreamWs.closeHandler(v ->
                    closeBoth(resolveCloseCode(upstreamWs.closeStatusCode()), upstreamWs.closeReason()));
            clientWs.exceptionHandler(this::abort);
            upstreamWs.exceptionHandler(this::abort);
            resetIdle();
            // Both legs were paused at acquisition; only now that every handler is installed may their
            // buffered frames flow. This precedes any write-queue backpressure pause, which is only ever
            // applied from a relayed frame.
            clientWs.resume();
            upstreamWs.resume();
        }

        private void wire(WebSocketBase source, WebSocketBase target) {
            source.frameHandler(frame -> relayFrame(source, target, frame));
            // Vert.x surfaces received pong frames on a dedicated handler (not the frame handler) and
            // auto-responds to pings; a pong is relay activity, so it resets the idle timer.
            source.pongHandler(pong -> resetIdle());
        }

        private void relayFrame(WebSocketBase source, WebSocketBase target, WebSocketFrame frame) {
            if (closed) {
                return;
            }
            resetIdle();
            if (frame.isClose()) {
                // The close is surfaced separately via closeHandler, which closes both legs.
                return;
            }
            if (frame.isPing()) {
                target.writeFrame(WebSocketFrame.pingFrame(frame.binaryData()));
                return;
            }
            target.writeFrame(dataFrame(frame));
            applyBackpressure(source, target);
        }

        private static WebSocketFrame dataFrame(WebSocketFrame frame) {
            if (frame.isText()) {
                return WebSocketFrame.textFrame(frame.textData(), frame.isFinal());
            }
            if (frame.isContinuation()) {
                return WebSocketFrame.continuationFrame(frame.binaryData(), frame.isFinal());
            }
            return WebSocketFrame.binaryFrame(frame.binaryData(), frame.isFinal());
        }

        private static void applyBackpressure(WebSocketBase source, WebSocketBase target) {
            if (target.writeQueueFull()) {
                source.pause();
                target.drainHandler(v -> source.resume());
            }
        }

        private void resetIdle() {
            if (closed) {
                return;
            }
            if (idleTimerId != -1L) {
                vertx.cancelTimer(idleTimerId);
            }
            idleTimerId = vertx.setTimer(idleMillis, id -> onIdle());
        }

        private void onIdle() {
            if (closed) {
                return;
            }
            LOGGER.warn(ApiSheriffLogMessages.WARN.WEBSOCKET_IDLE_RECLAIM, routeId, Integer.toString(idleSeconds));
            eventCounter.increment(EventType.WEBSOCKET_IDLE_TIMEOUT);
            closeBoth((short) EventType.WEBSOCKET_IDLE_TIMEOUT.wsCloseCode(), "idle timeout");
        }

        private void abort(Throwable failure) {
            LOGGER.debug(failure, "WebSocket relay error on route '%s': %s", routeId, failure.getMessage());
            closeBoth(CLOSE_INTERNAL_ERROR, "relay error");
        }

        private void closeBoth(short code, @Nullable String reason) {
            if (closed) {
                return;
            }
            closed = true;
            // The relay held the admission permit for its whole lifetime; return it here, behind the
            // latch, so every terminal path releases it exactly once.
            releaseAdmission.run();
            if (idleTimerId != -1L) {
                vertx.cancelTimer(idleTimerId);
                idleTimerId = -1L;
            }
            closeLeg(clientWs, code, reason);
            closeLeg(upstreamWs, code, reason);
        }

        private static void closeLeg(WebSocketBase ws, short code, @Nullable String reason) {
            if (!ws.isClosed()) {
                ws.close(code, reason);
            }
        }

        private static short resolveCloseCode(@Nullable Short code) {
            return code != null ? code : CLOSE_NORMAL;
        }
    }
}
