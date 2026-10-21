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
package de.cuioss.sheriff.gateway.edge.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * The accounting half of every rejection the edge answers: meters it on the in-process event counter
 * and on {@link SheriffMetrics}, writes the security-relevant {@code WARN} and the per-route latched
 * {@code INFO} records, and records the per-request metrics from the single end-of-response hook.
 * Rendering the answer is {@link ErrorResponseWriter}'s job.
 * <p>
 * Thread-safe: the two report latches are concurrent sets, and every other collaborator is
 * thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RejectionReporter {

    private static final CuiLogger LOGGER = new CuiLogger(RejectionReporter.class);

    /** Per-request {@link RoutingContext} data key holding the resolved metrics route label. */
    public static final String ROUTE_KEY = "sheriff.route";
    /** The fixed disposition the aborted-inbound-body record carries; nothing the client supplied. */
    private static final String INBOUND_BODY_ABORTED_DISPOSITION = "body-stream-failed";

    private final GatewayEventCounter gatewayEventCounter;
    private final SheriffMetrics sheriffMetrics;
    private final long requestBodyIdleTimeoutMillis;
    private final long requestBodyDeadlineMillis;
    /**
     * The route ids an aborted inbound body has already been reported for at {@code INFO} (see
     * {@link #logInboundBodyAborted}). Bounded by the number of configured routes.
     */
    private final Set<String> inboundBodyAbortReported = ConcurrentHashMap.newKeySet();
    /**
     * The route ids a request-body idle timeout has already been reported for at {@code INFO} (see
     * {@link #logRequestBodyTimeout}). Bounded by the number of configured routes.
     */
    private final Set<String> requestBodyTimeoutReported = ConcurrentHashMap.newKeySet();
    /**
     * The route ids a request body past its deadline has already been reported for at {@code INFO}
     * (see {@link #logRequestBodyDeadline}). Bounded by the number of configured routes.
     */
    private final Set<String> requestBodyDeadlineReported = ConcurrentHashMap.newKeySet();

    /**
     * @param gatewayEventCounter          the shared in-process event counter
     * @param sheriffMetrics               the Micrometer adapter the request and error signals are
     *                                     recorded through
     * @param requestBodyIdleTimeoutMillis the request-body idle timeout the timeout record reports
     *                                     ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyIdleTimeoutMillis()})
     * @param requestBodyDeadlineMillis    the request-body deadline the deadline record reports
     *                                     ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions#requestBodyDeadlineMillis()})
     */
    public RejectionReporter(GatewayEventCounter gatewayEventCounter, SheriffMetrics sheriffMetrics,
            long requestBodyIdleTimeoutMillis, long requestBodyDeadlineMillis) {
        this.gatewayEventCounter = Objects.requireNonNull(gatewayEventCounter, "gatewayEventCounter");
        this.sheriffMetrics = Objects.requireNonNull(sheriffMetrics, "sheriffMetrics");
        this.requestBodyIdleTimeoutMillis = requestBodyIdleTimeoutMillis;
        this.requestBodyDeadlineMillis = requestBodyDeadlineMillis;
    }

    /**
     * Meters and logs a categorized {@link GatewayException} rejection without answering it — the
     * accounting half of {@link ErrorResponseWriter#handleGatewayRejection}, shared with the late body
     * refusal of a proxied request ({@code TerminalDispatch.lateBodyRefusal}). Increments the event
     * counter (except for upstream failures already metered inside {@code UpstreamFailureMapper}), emits
     * the security-relevant WARN for filter violations and smuggled passthrough hosts and the latched
     * INFO for an aborted inbound body, for a request-body idle timeout and for a request body past its
     * deadline, and records the error metric.
     *
     * @param ctx      the rejected request
     * @param rejected the rejection
     */
    public void reportRejection(RoutingContext ctx, GatewayException rejected) {
        // Upstream failures are already metered inside UpstreamFailureMapper; meter the rest here.
        if (rejected.getEventType().category() != EventCategory.UPSTREAM) {
            gatewayEventCounter.increment(rejected.getEventType());
        }
        switch (rejected.getEventType()) {
            // Security-relevant WARN (D4): the failure-type detail only, never the raw payload —
            // rejected.getMessage() already carries a sanitized description (see GatewayException).
            case SECURITY_FILTER_VIOLATION -> LOGGER.warn(ApiSheriffLogMessages.WARN.SECURITY_FILTER_VIOLATION,
                    routeLabel(ctx), rejected.getMessage());
            // Security-relevant WARN: a terminated Host named a reserved passthrough SNI. The
            // message is a fixed disposition (never the raw Host value).
            case PASSTHROUGH_HOST_SMUGGLED -> LOGGER.warn(ApiSheriffLogMessages.WARN.PASSTHROUGH_HOST_SMUGGLED,
                    rejected.getMessage());
            // A client-caused termination, not a security event: an INFO, latched per route.
            case INBOUND_BODY_ABORTED -> logInboundBodyAborted(routeLabel(ctx));
            // A body that stopped arriving while the dispatch held an upstream connection, or one still
            // arriving at the request-body deadline: an INFO each, latched per route.
            case REQUEST_BODY_TIMEOUT -> {
                if (rejected instanceof DispatchStage.RequestBodyDeadlineExceeded) {
                    logRequestBodyDeadline(routeLabel(ctx));
                } else {
                    logRequestBodyTimeout(routeLabel(ctx));
                }
            }
            default -> {
                // Every other rejection is rendered without a log record; it is metered above.
            }
        }
        recordError(ctx, rejected.getEventType());
    }

    /**
     * Counts one categorized failure against {@link SheriffMetrics#ERRORS_TOTAL}, keyed by the
     * request's stashed route (or {@link SheriffMetrics#NO_ROUTE}), the failure
     * {@link EventCategory} and the {@link EventType} name as the {@code event} label. An
     * uncategorized failure (e.g. an unexpected internal error) carries no category, records nothing
     * here and surfaces only through the {@code 5xx} bucket of {@link SheriffMetrics#REQUESTS_TOTAL}.
     *
     * @param ctx       the failed request
     * @param eventType the failure's event
     */
    public void recordError(RoutingContext ctx, EventType eventType) {
        if (eventType.category() != null) {
            sheriffMetrics.recordError(routeLabel(ctx), eventType);
        }
    }

    /**
     * Records the terminal {@link SheriffMetrics#REQUESTS_TOTAL request count} and
     * {@link SheriffMetrics#REQUEST_DURATION_SECONDS request-duration timer} for one served request
     * from the single end-of-response hook, so every terminal path (streamed success, short-circuit,
     * and rendered failure) is metered exactly once. The bounded {@code route} label is the id
     * stashed at route selection, or {@link SheriffMetrics#NO_ROUTE} for an unmatched or
     * short-circuited request. The method is handed over parsed — an {@link HttpMethod}, or
     * {@code null} for a method the gateway does not parse — so the raw method token never reaches the
     * bounded {@code method} label.
     *
     * @param ctx        the served request
     * @param startNanos the {@link System#nanoTime()} reading the request was admitted at
     */
    public void recordRequestMetrics(RoutingContext ctx, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        String route = routeLabel(ctx);
        @Nullable HttpMethod method = EdgeRequests.parseMethod(ctx.request().method().name()).orElse(null);
        sheriffMetrics.recordRequest(route, method, SheriffMetrics.statusFamily(ctx.response().getStatusCode()));
        sheriffMetrics.recordRequestDuration(route, elapsed);
    }

    private static String routeLabel(RoutingContext ctx) {
        String route = ctx.get(ROUTE_KEY);
        return route != null ? route : SheriffMetrics.NO_ROUTE;
    }

    /**
     * Records one {@link EventType#INBOUND_BODY_ABORTED} — a request body that failed after dispatch
     * began, such as a client disconnecting mid-upload.
     * <p>
     * <strong>Latched per route.</strong> Every caller a route admits can end its own upload at will,
     * and on a route without authentication that is any caller at all, so a record per occurrence
     * would hand out a log line per request (CWE-779). The first occurrence on a route is recorded at
     * {@code INFO}; every repeat on that route is a {@code DEBUG} diagnostic. The latch is keyed by the
     * route id, so it holds at most one entry per configured route. The absence of a repeated
     * {@code INFO} says nothing about the rate — {@link SheriffMetrics#ERRORS_TOTAL} carries that.
     * <p>
     * The record carries the route id and a fixed disposition only: never the failure's message, which
     * can describe what the client sent.
     */
    private void logInboundBodyAborted(String route) {
        if (inboundBodyAbortReported.add(route)) {
            LOGGER.info(ApiSheriffLogMessages.INFO.INBOUND_BODY_ABORTED, route, INBOUND_BODY_ABORTED_DISPOSITION);
            return;
        }
        LOGGER.debug("Request body on route '%s' did not arrive whole again (%s) — already reported once for "
                + "this route, stays at DEBUG for the rest of the process", route, INBOUND_BODY_ABORTED_DISPOSITION);
    }

    /**
     * Records one {@link EventType#REQUEST_BODY_TIMEOUT} — a proxied request whose body made no
     * progress for the request-body idle timeout while its dispatch held an upstream connection, which
     * was then released.
     * <p>
     * <strong>Latched per route, to bound the log volume.</strong> The first occurrence on a route is
     * recorded at {@code INFO}; every repeat on that route is a {@code DEBUG} diagnostic, so the log
     * holds one record per route. The latch is keyed by the route id, so it holds at most one entry per
     * configured route. The rate is carried by {@link SheriffMetrics#ERRORS_TOTAL}, not by this record.
     * <p>
     * The record carries the route id and the fixed timeout in seconds only — never body content or
     * headers.
     */
    private void logRequestBodyTimeout(String route) {
        long timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(requestBodyIdleTimeoutMillis);
        if (requestBodyTimeoutReported.add(route)) {
            LOGGER.info(ApiSheriffLogMessages.INFO.REQUEST_BODY_TIMEOUT, route, timeoutSeconds);
            return;
        }
        LOGGER.debug("Request body on route '%s' made no progress for %s seconds again — already reported once "
                + "for this route, stays at DEBUG for the rest of the process", route, timeoutSeconds);
    }

    /**
     * Records one {@link EventType#REQUEST_BODY_TIMEOUT} raised as a
     * {@link DispatchStage.RequestBodyDeadlineExceeded} — a proxied request whose body was still
     * arriving when the request-body deadline passed, whose upstream request was then reset.
     * <p>
     * <strong>Latched per route, to bound the log volume</strong>, exactly as
     * {@link #logRequestBodyTimeout}: the first occurrence on a route is recorded at {@code INFO}; every
     * repeat on that route is a {@code DEBUG} diagnostic. The rate is carried by
     * {@link SheriffMetrics#ERRORS_TOTAL}, not by this record.
     * <p>
     * The record carries the route id and the fixed deadline in seconds only — never body content or
     * headers.
     */
    private void logRequestBodyDeadline(String route) {
        long deadlineSeconds = TimeUnit.MILLISECONDS.toSeconds(requestBodyDeadlineMillis);
        if (requestBodyDeadlineReported.add(route)) {
            LOGGER.info(ApiSheriffLogMessages.INFO.REQUEST_BODY_DEADLINE, route, deadlineSeconds);
            return;
        }
        LOGGER.debug("Request body on route '%s' did not end within %s seconds again — already reported once "
                + "for this route, stays at DEBUG for the rest of the process", route, deadlineSeconds);
    }
}
