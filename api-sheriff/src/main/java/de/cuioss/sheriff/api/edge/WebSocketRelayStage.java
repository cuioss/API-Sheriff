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

import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.api.ApiSheriffLogMessages;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayEventCounter;
import de.cuioss.sheriff.api.routing.RouteRuntime;
import de.cuioss.tools.logging.CuiLogger;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * The WebSocket relay terminal action — the protocol-dispatch seam's WebSocket leg, replacing the
 * HTTP {@link DispatchStage} for a {@code protocol: websocket} route once the pipeline and the
 * {@code OriginValidationStage} have accepted the handshake.
 * <p>
 * <strong>Dial-before-upgrade.</strong> The upstream WebSocket is dialed first, over the route's
 * shared Vert.x {@link HttpClient}. Only when the upstream confirms {@code 101} is the client
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
 * Every socket operation is event-loop-bound; the relay hops onto the request's Vert.x context so
 * both legs share one event loop and the frame relay is single-threaded.
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

    private final UpstreamFailureMapper failureMapper;
    private final GatewayEventCounter eventCounter;

    /**
     * @param failureMapper the shared mapper turning an upstream dial failure into the error contract
     * @param eventCounter  the shared in-process event counter
     */
    public WebSocketRelayStage(UpstreamFailureMapper failureMapper, GatewayEventCounter eventCounter) {
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
    }

    /**
     * Dials the upstream WebSocket and, on success, upgrades the client and establishes the opaque
     * relay. Runs asynchronously on the request's Vert.x context; the caller returns immediately.
     * <p>
     * The stage-0 security headers accumulated on the request are retained across the asynchronous
     * dial so that a handshake-failure response ({@link #onUpstreamFailure}) carries the same
     * gateway-controlled headers as the HTTP ({@link ResponseStage#relay}) and gRPC
     * ({@link GrpcStatusMapper#renderRejection}) rejection paths.
     *
     * @param ctx             the routing context (client request/response and Vert.x handle)
     * @param route           the resolved route runtime (upstream, shared client, idle timeout)
     * @param forwardHeaders  the deny-by-default forwarded header set computed by stage 5
     * @param securityHeaders the stage-0 security headers accumulated on the response, applied to a
     *                        handshake-failure response before it is ended
     * @param requestUri      the upstream request URI (path + allow-listed query)
     */
    public void relay(RoutingContext ctx, RouteRuntime route, Map<String, String> forwardHeaders,
            Map<String, String> securityHeaders, String requestUri) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(forwardHeaders, "forwardHeaders");
        Objects.requireNonNull(securityHeaders, "securityHeaders");
        Objects.requireNonNull(requestUri, "requestUri");
        Map<String, String> retainedSecurityHeaders = Map.copyOf(securityHeaders);
        HttpClient client = route.getHttpClient()
                .orElseThrow(() -> new IllegalStateException("WebSocket dispatch requires an upstream client"));
        ResolvedUpstream upstream = route.getUpstream()
                .orElseThrow(() -> new IllegalStateException("WebSocket dispatch requires a resolved upstream"));
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setSsl(HTTPS.equalsIgnoreCase(upstream.scheme()))
                .setURI(requestUri);
        forwardHeaders.forEach(options::addHeader);
        ctx.vertx().runOnContext(v -> client.webSocket(options)
                .onSuccess(upstreamWs -> onUpstreamConnected(ctx, route, upstreamWs))
                .onFailure(failure -> onUpstreamFailure(ctx, route, failure, retainedSecurityHeaders)));
    }

    private void onUpstreamConnected(RoutingContext ctx, RouteRuntime route, WebSocket upstreamWs) {
        ctx.request().toWebSocket()
                .onSuccess(clientWs -> establishRelay(ctx, route, clientWs, upstreamWs))
                .onFailure(failure -> {
                    // The upstream is already upgraded but the client handshake could not complete;
                    // there is no HTTP response to render anymore. Close the upstream leg and drop.
                    LOGGER.debug(failure, "WebSocket client upgrade failed on route '%s': %s", route.getId(),
                            failure.getMessage());
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
            WebSocket upstreamWs) {
        int idleSeconds = route.getEffectiveWebSocketIdleTimeoutSeconds().orElse(DEFAULT_IDLE_TIMEOUT_SECONDS);
        LOGGER.info(ApiSheriffLogMessages.INFO.WEBSOCKET_RELAY_ESTABLISHED, route.getId());
        eventCounter.increment(EventType.REQUEST_FORWARDED);
        new RelaySession(ctx.vertx(), route.getId(), clientWs, upstreamWs, idleSeconds, eventCounter).start();
    }

    private static void closeQuietly(WebSocketBase ws, short code, @Nullable String reason) {
        if (!ws.isClosed()) {
            ws.close(code, reason);
        }
    }

    /**
     * One established relay: the two legs, the idle-timeout timer, and the frame-relay wiring. All
     * callbacks run on the shared request event loop, so the mutable {@code closed} / timer state is
     * single-threaded and needs no synchronization.
     */
    private static final class RelaySession {

        private final Vertx vertx;
        private final String routeId;
        private final ServerWebSocket clientWs;
        private final WebSocket upstreamWs;
        private final int idleSeconds;
        private final long idleMillis;
        private final GatewayEventCounter eventCounter;
        private long idleTimerId = -1L;
        private boolean closed;

        RelaySession(Vertx vertx, String routeId, ServerWebSocket clientWs, WebSocket upstreamWs, int idleSeconds,
                GatewayEventCounter eventCounter) {
            this.vertx = vertx;
            this.routeId = routeId;
            this.clientWs = clientWs;
            this.upstreamWs = upstreamWs;
            this.idleSeconds = idleSeconds;
            this.idleMillis = idleSeconds * 1000L;
            this.eventCounter = eventCounter;
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
