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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Renders every gateway-originated error answer: the RFC 9457 {@code application/problem+json}
 * document, the trailers-only gRPC status of a rejected {@code protocol: grpc} route, and — with
 * {@code portal.error_pages} on — the negotiated HTML error page, plus the portal page writer those
 * share. {@link #handleGatewayRejection} composes the two halves of a rejection: it reports it through
 * the {@link RejectionReporter}, then renders it.
 * <p>
 * <strong>Negotiated HTML error pages.</strong> The three gateway-originated error writers — the
 * problem renderer, a failed OIDC callback and a {@code source: directory} asset miss — answer an
 * {@link ErrorPageClassifier HTML-eligible} exit with the portal's HTML error page instead, when the
 * request's {@code Accept} lists {@code text/html} explicitly; the status is identical either way
 * (see {@link #answeredWithErrorPage}).
 * <p>
 * Every writer mutates the response on the request's event loop; the methods may be called from the
 * virtual thread. Thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ErrorResponseWriter {

    private static final CuiLogger LOGGER = new CuiLogger(ErrorResponseWriter.class);

    /** The {@code Retry-After} response header an open-circuit {@code 503} carries. */
    public static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String PROBLEM_JSON = "application/problem+json";
    /** The request header whose explicit {@code text/html} range selects an HTML error page. */
    private static final String ACCEPT_HEADER = "Accept";
    /** The {@code Cache-Control} response header a cookie-setting portal page governs. */
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    /** The {@code Cache-Control} value forced on a cookie-setting portal page. */
    private static final String NO_STORE = "no-store";
    /** The {@code Vary} response header the portal envelope merges into. */
    private static final String VARY_HEADER = "Vary";
    private static final int INTERNAL_ERROR = 500;

    private final PortalEndpoint portalEndpoint;
    private final GatewayJson gatewayJson;
    private final GrpcStatusMapper grpcStatusMapper;
    private final RejectionReporter rejectionReporter;

    /**
     * @param portalEndpoint    the application portal, whose {@code portal.error_pages} switch and
     *                          error-page renderer the negotiation consults
     * @param gatewayJson       the serializer the RFC 9457 problem body is rendered through
     * @param grpcStatusMapper  the mapper a rejected gRPC route's trailers-only status is written by
     * @param rejectionReporter the accounting half {@link #handleGatewayRejection} reports through
     */
    public ErrorResponseWriter(PortalEndpoint portalEndpoint, GatewayJson gatewayJson,
            GrpcStatusMapper grpcStatusMapper, RejectionReporter rejectionReporter) {
        this.portalEndpoint = Objects.requireNonNull(portalEndpoint, "portalEndpoint");
        this.gatewayJson = Objects.requireNonNull(gatewayJson, "gatewayJson");
        this.grpcStatusMapper = Objects.requireNonNull(grpcStatusMapper, "grpcStatusMapper");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
    }

    /**
     * Meters and renders a categorized {@link GatewayException} rejection: reports it through the
     * {@link RejectionReporter#reportRejection} — the event counter, the security-relevant WARN for
     * filter violations and smuggled passthrough hosts, the latched INFO for an aborted inbound body
     * and for a request-body idle timeout, and the error metric — then renders the rejection.
     *
     * @param ctx      the rejected request
     * @param request  the pipeline request, {@code null} for a rejection raised before one was built
     * @param rejected the rejection
     */
    public void handleGatewayRejection(RoutingContext ctx, @Nullable PipelineRequest request,
            GatewayException rejected) {
        rejectionReporter.reportRejection(ctx, rejected);
        renderRejection(ctx, request, rejected);
    }

    /**
     * The single negotiation point of the gateway-originated error writers ({@link #renderProblem},
     * the reserved-path writer for a failed callback, the buffered-asset writer for a directory-asset
     * miss): answers the error with the portal's HTML error page — and reports {@code true} — only when
     * all three hold:
     * <ol>
     *   <li>{@code portal.error_pages} is on ({@link PortalEndpoint#errorPagesEnabled()});</li>
     *   <li>the {@link ErrorPageClassifier} classifies the exit
     *       {@link ErrorPageClassifier.Classification#HTML_ELIGIBLE HTML_ELIGIBLE};</li>
     *   <li>the request's {@code Accept} lists {@code text/html} explicitly
     *       ({@link ErrorPageClassifier#offersHtml(String)} — a wildcard never qualifies).</li>
     * </ol>
     * Otherwise it writes nothing and reports {@code false}, and the caller answers in the exit's
     * current shape. The status is preserved either way; the page carries the stage headers, the
     * stage {@code Set-Cookie} lines plus {@code extraSetCookies}, and the portal
     * {@code Content-Security-Policy} / {@code nosniff} composed by
     * {@link SecurityHeadersStage#applyPortalHeaders}. A response relayed from an origin never reaches
     * this method.
     * <p>
     * A page that fails to render falls back to the current shape rather than leaving the response
     * unanswered: the error being reported must still reach the client.
     *
     * @param ctx             the request being answered
     * @param request         the pipeline request, {@code null} for an exit answered before one was
     *                        built — such an exit never negotiates
     * @param classification  the exit's classification
     * @param status          the status the exit answers with
     * @param extraSetCookies {@code Set-Cookie} lines the exit itself emits beyond the stage's own
     * @return {@code true} when the HTML error page was written and the caller must stop
     */
    public boolean answeredWithErrorPage(RoutingContext ctx, @Nullable PipelineRequest request,
            ErrorPageClassifier.Classification classification, int status, List<String> extraSetCookies) {
        if (request == null || !portalEndpoint.errorPagesEnabled()
                || classification != ErrorPageClassifier.Classification.HTML_ELIGIBLE
                || !ErrorPageClassifier.offersHtml(acceptHeader(request))) {
            return false;
        }
        PortalEndpoint.PortalResponse page;
        // A deliberate last-resort net for one error response: the page renders an operator-authored
        // template, and a failure there must degrade to the exit's current shape instead of escaping
        // an error writer and abandoning the response. The failure goes to the debug log only.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            page = portalEndpoint.renderError(status);
        } catch (RuntimeException renderFailure) {
            LOGGER.debug(renderFailure, "HTML error page rendering failed — answering in the current shape: %s",
                    renderFailure.getMessage());
            return false;
        }
        writePortal(ctx, request, page, extraSetCookies);
        return true;
    }

    /**
     * Writes a portal answer on the event loop: the portal's fixed security headers are composed
     * onto the stage headers first ({@link SecurityHeadersStage#applyPortalHeaders} — the portal
     * {@code Content-Security-Policy} and {@code nosniff} replace whatever the resolved block declared),
     * then the envelope headers are written last so they win any name collision. {@code Vary} is the
     * one merge: the envelope's {@code Cookie} joins, never displaces, a {@code Vary} the stage
     * already announced (the CORS reflection's {@code Origin}). The stage's {@code Set-Cookie} lines
     * ride along as on every gateway-authored response, followed by {@code extraSetCookies} — the
     * lines an answered exit emits itself (a failed callback's clearing cookie), each on its own line.
     *
     * @param ctx             the request being answered
     * @param request         the pipeline request
     * @param portal          the rendered portal answer
     * @param extraSetCookies {@code Set-Cookie} lines the exit itself emits beyond the stage's own
     */
    public static void writePortal(RoutingContext ctx, PipelineRequest request,
            PortalEndpoint.PortalResponse portal, List<String> extraSetCookies) {
        SecurityHeadersStage.applyPortalHeaders(request);
        Map<String, String> stageHeaders = request.gatewayAuthoredResponseHeaders();
        List<String> stageSetCookies = request.responseSetCookies();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(portal.status());
            stageHeaders.forEach(response::putHeader);
            ResponseEnds.applyStageSetCookies(response, stageSetCookies);
            ResponseEnds.applyStageSetCookies(response, extraSetCookies);
            portal.headers().forEach((name, value) -> response.putHeader(name, VARY_HEADER.equalsIgnoreCase(name)
                    ? SecurityHeadersStage.mergedVary(stageHeaders.get(VARY_HEADER), List.of(value))
                    : value));
            // A page that sets a cookie is never cacheable, whatever the envelope declared: written
            // after the envelope headers so it wins the name.
            if (!stageSetCookies.isEmpty() || !extraSetCookies.isEmpty()) {
                response.putHeader(CACHE_CONTROL_HEADER, NO_STORE);
            }
            ConnectionRetirement.retireMarkedConnection(ctx);
            ResponseEnds.endResponse(ctx, () -> response.end(portal.body()));
        });
    }

    /**
     * Gives the {@code 503} of an open circuit ({@link EventType#UPSTREAM_CIRCUIT_OPEN}) its
     * {@code Retry-After} header, as the error contract in {@code architecture.adoc} states: the
     * header is seeded onto the request's {@linkplain PipelineRequest#responseHeaders() response-header
     * map}, which every writer of a gateway-authored answer applies — the problem document, the
     * negotiated HTML error page and the trailers-only gRPC status alike. Every other rejection is left
     * as it is.
     * <p>
     * The value is {@value UpstreamGuards#BREAKER_RESET_SECONDS}, the whole seconds the breaker stays
     * open ({@link UpstreamGuards#BREAKER_RESET_SECONDS}); a delay in seconds is the one form of the
     * header that needs no clock (RFC 9110 §10.2.3). It is a constant of the gateway and carries nothing
     * of the request or of the upstream.
     * <p>
     * Public so the header is asserted directly by {@code GatewayEdgeRouteRetryAfterTest}, matching
     * the precedent {@link #problemBody} sets.
     *
     * @param request   the rejected request, {@code null} for a rejection answered before one was
     *                  built — such a rejection is never an open circuit and gets no header
     * @param eventType the event the rejection reports
     */
    public static void seedRetryAfter(@Nullable PipelineRequest request, EventType eventType) {
        if (request != null && eventType == EventType.UPSTREAM_CIRCUIT_OPEN) {
            request.responseHeaders().put(RETRY_AFTER_HEADER, Integer.toString(UpstreamGuards.BREAKER_RESET_SECONDS));
        }
    }

    /**
     * Renders a gateway rejection that carries no problem extension members — the unrouted/unreserved
     * {@code 404}, a rejection answered before a {@link GatewayException} exists, and an unexpected
     * internal failure ({@code eventType == null}).
     *
     * @param ctx       the request being answered
     * @param request   the pipeline request, {@code null} for a rejection answered before one was built
     * @param eventType the event the rejection reports, {@code null} for an unexpected internal failure
     */
    public void renderProblem(RoutingContext ctx, @Nullable PipelineRequest request, @Nullable EventType eventType) {
        renderProblem(ctx, request, eventType, Map.of());
    }

    /**
     * Builds the RFC 9457 {@code application/problem+json} body: the standard members {@code type},
     * {@code title} and {@code status}, followed by the extension members in their iteration order.
     * The standard members come from the gateway's own event catalogue and are written as they are;
     * every extension member name and value is serialized through {@link GatewayJson}, which applies
     * RFC 8259 string escaping. Without extension members the body is exactly the three standard
     * members.
     * <p>
     * Public so the rendering is asserted directly by {@code GatewayEdgeRouteTest}, matching the
     * precedent {@code SecurityPostures.securityPostureFor} sets.
     *
     * @param gatewayJson       the serializer the extension members are rendered through
     * @param type              the problem type URI
     * @param title             the problem title
     * @param status            the HTTP status
     * @param problemExtensions the extension members to append, empty for none
     * @return the compact JSON body
     */
    public static String problemBody(GatewayJson gatewayJson, String type, String title, int status,
            Map<String, Object> problemExtensions) {
        StringBuilder body = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"title\":\"").append(title)
                .append("\",\"status\":").append(status);
        problemExtensions.forEach((name, value) -> body.append(',')
                .append(gatewayJson.toJson(name)).append(':').append(gatewayJson.toJson(value)));
        return body.append('}').toString();
    }

    /**
     * Renders a post-route-resolution rejection: a {@code protocol: grpc} route emits a trailers-only
     * gRPC status via {@link GrpcStatusMapper} (a gRPC client cannot consume problem+json), every
     * other route renders the RFC 9457 problem+json response, carrying the rejection's
     * {@linkplain GatewayException#getProblemExtensions() problem extension members}. A rejection that
     * fires before route selection (no selected route) always renders problem+json. A gRPC status has
     * no place for extension members, so they are not rendered there.
     * <p>
     * An open circuit's {@code 503} is given its {@code Retry-After} header first
     * ({@link #seedRetryAfter}), so it rides on whichever of the three shapes answers.
     */
    private void renderRejection(RoutingContext ctx, @Nullable PipelineRequest request, GatewayException rejected) {
        EventType eventType = rejected.getEventType();
        seedRetryAfter(request, eventType);
        Route selected = request != null ? request.selectedRoute() : null;
        if (selected != null && selected.protocol() == Protocol.GRPC) {
            Map<String, String> responseHeaders = request.gatewayAuthoredResponseHeaders();
            List<String> setCookies = request.responseSetCookies();
            ctx.vertx().runOnContext(v -> {
                ResponseEnds.applyStageSetCookies(ctx.response(), setCookies);
                // The trailers-only status passes the one end rule like every other answer.
                grpcStatusMapper.renderRejection(ctx.response(), eventType, responseHeaders,
                        ending -> ResponseEnds.endResponse(ctx, ending));
            });
            return;
        }
        renderProblem(ctx, request, eventType, rejected.getProblemExtensions());
    }

    /**
     * Renders a gateway rejection — every {@link GatewayException} outside a gRPC route, the
     * unrouted/unreserved {@code 404}, the {@code 404} of the withheld client JWKS path (see
     * {@code ReservedPathHandler}) and an unexpected internal failure ({@code eventType == null}).
     * An {@link ErrorPageClassifier#classify(EventType) HTML-eligible} event negotiates the portal's
     * HTML error page first ({@link #answeredWithErrorPage}); otherwise, or when the request does not
     * explicitly accept {@code text/html}, the RFC 9457 {@code application/problem+json} body is
     * written with the same status. The body carries the standard members first and then
     * {@code problemExtensions}; the HTML error page never does.
     */
    private void renderProblem(RoutingContext ctx, @Nullable PipelineRequest request, @Nullable EventType eventType,
            Map<String, Object> problemExtensions) {
        int status;
        String type;
        String title;
        ErrorPageClassifier.Classification classification;
        if (eventType == null) {
            status = INTERNAL_ERROR;
            type = "about:blank";
            title = "Internal Server Error";
            classification = ErrorPageClassifier.classify(ErrorPageClassifier.Exit.UNEXPECTED_INTERNAL);
        } else {
            status = eventType.hasHttpMapping() ? eventType.httpStatus() : INTERNAL_ERROR;
            EventCategory category = eventType.category();
            type = category != null ? category.problemType() : "about:blank";
            title = category != null ? category.title() : "Internal Server Error";
            classification = ErrorPageClassifier.classify(eventType);
        }
        if (answeredWithErrorPage(ctx, request, classification, status, List.of())) {
            return;
        }
        String body = problemBody(gatewayJson, type, title, status, problemExtensions);
        Map<String, String> responseHeaders = request != null ? request.gatewayAuthoredResponseHeaders() : Map.of();
        // A rejection still carries the stage's Set-Cookie values: an XHR whose refresh failed is a
        // 401 problem response, and the clearing cookie that drops the revoked session rides on it.
        List<String> setCookies = request != null ? request.responseSetCookies() : List.of();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            response.setStatusCode(status);
            responseHeaders.forEach(response::putHeader);
            ResponseEnds.applyStageSetCookies(response, setCookies);
            response.putHeader("Content-Type", PROBLEM_JSON);
            ConnectionRetirement.retireMarkedConnection(ctx);
            ResponseEnds.endResponse(ctx, () -> response.end(body));
        });
    }

    /**
     * Every {@code Accept} line of the request joined into one list value, or {@code null} when the
     * request carries none — so a range sent on a second header line still counts.
     */
    private static @Nullable String acceptHeader(PipelineRequest request) {
        List<String> values = request.headerValues(ACCEPT_HEADER);
        return values.isEmpty() ? null : String.join(",", values);
    }
}
