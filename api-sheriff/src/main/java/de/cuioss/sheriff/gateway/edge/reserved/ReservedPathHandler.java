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
package de.cuioss.sheriff.gateway.edge.reserved;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Answers the gateway's own reserved paths, ahead of the route table: the OIDC reserved paths of the
 * server-mode BFF runtime (D2/D16) and the application portal's {@code portal.path}. Both are matched
 * after the stage-0/1 checks and the passthrough host guard and before route selection, so a prefix
 * route never swallows either.
 * <p>
 * Runs on the virtual thread — a reserved handler may reach the confidential-client engine and the
 * portal may reach the session store — and hops every response mutation back onto the event loop.
 * Thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ReservedPathHandler {

    private static final CuiLogger LOGGER = new CuiLogger(ReservedPathHandler.class);

    private static final String CLAIMS_PARAM = "claims";
    private static final String RETURN_URL_PARAM = "returnUrl";
    private static final String STATE_PARAM = "state";
    private static final String COOKIE_HEADER = "Cookie";
    private static final String LOCATION_HEADER = "Location";
    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private static final int NOT_FOUND = 404;
    private static final int FIRST_ERROR_STATUS = 400;

    private final ReservedPathRegistry reservedPathRegistry;
    private final BffRuntime bffRuntime;
    private final PortalEndpoint portalEndpoint;
    private final ErrorResponseWriter errorResponseWriter;

    /**
     * @param reservedPathRegistry the OIDC reserved-path registry; empty (and inert) unless the global
     *                             {@code oidc} block declares a {@code redirect_uri}
     * @param bffRuntime           the server-mode BFF runtime whose reserved-endpoint handlers serve the
     *                             carved-out OIDC paths; the {@linkplain BffRuntime#inert() inert}
     *                             runtime carves them out without serving them
     * @param portalEndpoint       the application portal's own reserved path; the
     *                             {@linkplain PortalEndpoint#inert() inert} endpoint never matches
     * @param errorResponseWriter  the writer a carved-out path and a failed callback are answered by
     */
    public ReservedPathHandler(ReservedPathRegistry reservedPathRegistry, BffRuntime bffRuntime,
            PortalEndpoint portalEndpoint, ErrorResponseWriter errorResponseWriter) {
        this.reservedPathRegistry = Objects.requireNonNull(reservedPathRegistry, "reservedPathRegistry");
        this.bffRuntime = Objects.requireNonNull(bffRuntime, "bffRuntime");
        this.portalEndpoint = Objects.requireNonNull(portalEndpoint, "portalEndpoint");
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter, "errorResponseWriter");
    }

    /**
     * Resolves and dispatches a reserved OIDC carve-out (D2/D16): a reserved endpoint is matched here,
     * ahead of the route table, so a proxy route such as {@code path_prefix: /bff} can never swallow
     * the exact {@code /bff/callback}. When the server-mode BFF runtime is wired the matched path is
     * DISPATCHED to its handler; a bearer-only / cookie-mode gateway carves it out of proxy dispatch
     * and renders {@code NO_ROUTE_MATCHED} (the empty reserved registry never matches there anyway).
     *
     * @param ctx     the request
     * @param request the pipeline request, through the passthrough host guard
     * @return {@code true} when the request was a reserved path and has been handled (the caller must
     *         stop processing); {@code false} when no reserved path matched and normal routing continues
     */
    public boolean handleReservedPath(RoutingContext ctx, PipelineRequest request) {
        Optional<ReservedEndpoint> reserved =
                reservedPathRegistry.match(request.host(), EdgeRequests.requireCanonicalPath(request));
        if (reserved.isEmpty()) {
            return false;
        }
        if (bffRuntime.isActive()) {
            dispatchReserved(ctx, request, reserved.get());
        } else {
            LOGGER.debug("Reserved gateway path carved out of proxy dispatch: %s",
                    EdgeRequests.requireCanonicalPath(request));
            errorResponseWriter.renderProblem(ctx, request, EventType.NO_ROUTE_MATCHED);
        }
        return true;
    }

    /**
     * Answers the application portal's own reserved path. It runs immediately after
     * {@link #handleReservedPath} and before route selection: the stage-0/1 checks and the passthrough
     * host guard have already run, an OIDC reserved path has already won, and a prefix route covering
     * {@code portal.path} never sees the request. The match is exact and host-independent (see
     * {@link PortalEndpoint#matches}); the {@linkplain PortalEndpoint#inert() inert} endpoint never
     * matches.
     * <p>
     * The page is rendered here, on the virtual thread — session resolution may reach the session
     * store — and the response is written on the event loop by
     * {@link ErrorResponseWriter#writePortal}. The {@code notice} parameter is handed over in its raw,
     * still-encoded wire form, so only an exact vocabulary value is ever recognised.
     *
     * @param ctx     the request
     * @param request the pipeline request, past the OIDC reserved paths
     * @return {@code true} when the request addressed the portal path and has been answered (the
     *         caller must stop processing); {@code false} when normal routing continues
     */
    public boolean handlePortal(RoutingContext ctx, PipelineRequest request) {
        if (!portalEndpoint.matches(EdgeRequests.requireCanonicalPath(request))) {
            return false;
        }
        PortalEndpoint.PortalResponse response = portalEndpoint.handle(request.method(),
                EdgeRequests.firstRawQueryValue(request, PortalEndpoint.NOTICE_PARAM),
                request.firstHeader(COOKIE_HEADER).orElse(null), Instant.now());
        ErrorResponseWriter.writePortal(ctx, request, response, List.of());
        return true;
    }

    /**
     * Dispatches a matched reserved OIDC path (D16): extracts the raw request pieces each handler
     * consumes, drives the {@link BffRuntime#dispatch reserved dispatch}, and renders the normalized
     * response. Runs on the virtual thread (the handler may reach the confidential-client engine), then
     * hops the response mutation back onto the event loop like every other terminal path.
     */
    private void dispatchReserved(RoutingContext ctx, PipelineRequest request, ReservedEndpoint kind) {
        String cookieHeader = request.firstHeader(COOKIE_HEADER).orElse(null);
        String method = ctx.request().method().name();
        // Back-channel logout is the ONE reserved path that still consumes a request body, so it is the
        // only kind that reads the eagerly buffered body here (and the only kind
        // ReservedBodyReader.needsReservedBodyRead buffers one for). The OIDC callback carries its
        // code/state in the query under response_mode=query and is handed ctx.request().query() below —
        // the genuinely raw, never map-collapsed query string the BFF-13 duplicate-parameter defence
        // re-parses.
        String rawFormBody = kind == ReservedEndpoint.BACKCHANNEL_LOGOUT ? ReservedBodyReader.readFormBody(ctx) : null;
        BffRuntime.ReservedHttpRequest reservedRequest = new BffRuntime.ReservedHttpRequest(
                ctx.request().query(), cookieHeader, firstQueryParam(ctx, CLAIMS_PARAM),
                firstQueryParam(ctx, RETURN_URL_PARAM), firstQueryParam(ctx, STATE_PARAM), rawFormBody, method);
        BffRuntime.ReservedHttpResponse response = bffRuntime.dispatch(kind, reservedRequest, Instant.now());
        renderReserved(ctx, request, kind, response);
    }

    /**
     * Writes a dispatched reserved path's normalized response. A failed OIDC callback — an error
     * status without a {@code Location} — negotiates an HTML error page
     * ({@link ErrorPageClassifier.Exit#CALLBACK_FAILURE}); its own {@code Set-Cookie} lines ride
     * on the page. Every other reserved outcome written here, the user-info JSON and every redirect
     * included, is written in its current shape.
     * <p>
     * <strong>One outcome is not written here at all</strong>: the {@code 404} of the client JWKS
     * endpoint's withheld form (client-secret mode, where there is no key set to publish). It is
     * handed to {@link ErrorResponseWriter#renderProblem} with {@link EventType#NO_ROUTE_MATCHED} — the
     * renderer and the event of a path no route matches — so the status, the media type, the body, the
     * headers and the HTML error-page negotiation are those of an unknown path by construction, not by
     * imitation. A {@code 404} written in a shape of its own would let an anonymous caller tell a
     * client-secret gateway from a gateway without the endpoint. The path stays reserved all the same:
     * the request was matched by the reserved-path registry and never reaches route selection.
     */
    private void renderReserved(RoutingContext ctx, PipelineRequest request, ReservedEndpoint kind,
            BffRuntime.ReservedHttpResponse response) {
        if (isWithheldClientJwks(kind, response)) {
            errorResponseWriter.renderProblem(ctx, request, EventType.NO_ROUTE_MATCHED);
            return;
        }
        if (isFailedCallback(kind, response) && errorResponseWriter.answeredWithErrorPage(ctx, request,
                ErrorPageClassifier.classify(ErrorPageClassifier.Exit.CALLBACK_FAILURE), response.status(),
                response.setCookieHeaders())) {
            return;
        }
        Map<String, String> stageHeaders = request.gatewayAuthoredResponseHeaders();
        List<String> stageSetCookies = request.responseSetCookies();
        ctx.vertx().runOnContext(v -> {
            HttpServerResponse httpResponse = ctx.response();
            if (httpResponse.ended()) {
                return;
            }
            httpResponse.setStatusCode(response.status());
            stageHeaders.forEach(httpResponse::putHeader);
            ResponseEnds.applyStageSetCookies(httpResponse, stageSetCookies);
            response.headers().forEach(httpResponse::putHeader);
            response.locationOptional().ifPresent(location -> httpResponse.putHeader(LOCATION_HEADER, location));
            // Multiple Set-Cookie headers must each be a distinct header line (never a comma-joined value).
            response.setCookieHeaders().forEach(cookie -> httpResponse.headers().add(SET_COOKIE_HEADER, cookie));
            ResponseEnds.endResponse(ctx,
                    () -> response.jsonBodyOptional().map(httpResponse::end).orElseGet(httpResponse::end));
        });
    }

    /**
     * @return {@code true} for a callback outcome answering an error status without a redirect
     */
    private static boolean isFailedCallback(ReservedEndpoint kind, BffRuntime.ReservedHttpResponse response) {
        return kind == ReservedEndpoint.CALLBACK && response.status() >= FIRST_ERROR_STATUS
                && response.locationOptional().isEmpty();
    }

    /**
     * @return {@code true} for the {@code 404} the client JWKS endpoint yields in its withheld form.
     *         The publishing form answers {@code 200} or {@code 405} only, so the status identifies
     *         the form
     */
    private static boolean isWithheldClientJwks(ReservedEndpoint kind, BffRuntime.ReservedHttpResponse response) {
        return kind == ReservedEndpoint.CLIENT_JWKS && response.status() == NOT_FOUND;
    }

    /**
     * Reads the first <em>decoded</em> value of a reserved-path query parameter ({@code claims},
     * {@code returnUrl}, {@code state}) from the transport. The pipeline query carries the raw,
     * still-encoded pair sequence (ADR-0047), which is the wrong form for these consumers: they compare or
     * redirect on the value's meaning, so they read Vert.x's decoded view instead.
     * <p>
     * {@code returnUrl} is read one way for every kind, so the login-initiation and the step-up
     * endpoints receive the identical decoded target; each same-origin-validates it before it can
     * become a redirect location, and the raw pair has already passed the stage-1 checks, which run
     * before any reserved path is matched (ADR-0019).
     */
    private static @Nullable String firstQueryParam(RoutingContext ctx, String name) {
        return ctx.request().getParam(name);
    }
}
