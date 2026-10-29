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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.auth.AuthBranch;
import de.cuioss.sheriff.gateway.auth.AuthenticationStage;
import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.admission.EdgeAdmission;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.edge.admission.SourceGate;
import de.cuioss.sheriff.gateway.edge.admission.SourceKeys;
import de.cuioss.sheriff.gateway.edge.admission.ThreatAlertDocument;
import de.cuioss.sheriff.gateway.edge.admission.ThreatAlerts;
import de.cuioss.sheriff.gateway.edge.admission.ThreatClassifier;
import de.cuioss.sheriff.gateway.edge.assembly.AssetSources;
import de.cuioss.sheriff.gateway.edge.assembly.DispatchPath;
import de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler;
import de.cuioss.sheriff.gateway.edge.assembly.SecurityPostures;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.RejectionReporter;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.edge.relay.WebSocketRelayStage;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.edge.reserved.ReservedBodyReader;
import de.cuioss.sheriff.gateway.edge.reserved.ReservedPathHandler;
import de.cuioss.sheriff.gateway.edge.terminal.RedirectStage;
import de.cuioss.sheriff.gateway.edge.terminal.TerminalDispatch;
import de.cuioss.sheriff.gateway.edge.upstream.DispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.EgressClients;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamFailureMapper;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.forward.ForwardPolicyStage;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.pipeline.BasicChecksStage;
import de.cuioss.sheriff.gateway.pipeline.CanonicalPathGuard;
import de.cuioss.sheriff.gateway.pipeline.FramingGate;
import de.cuioss.sheriff.gateway.pipeline.OriginValidationStage;
import de.cuioss.sheriff.gateway.pipeline.PassthroughHostGuardStage;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.RouteSelectionStage;
import de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage;
import de.cuioss.sheriff.gateway.pipeline.ThoroughChecksStage;
import de.cuioss.sheriff.gateway.pipeline.VerbGateStage;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.tls.EgressTrustProfileResolver;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
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
 * This class is the edge's entry point and holds only the wiring and the per-request flow: the CDI
 * constructor, {@link #registerRoutes}, the shutdown observer, {@code handle}, {@code dispatchProcessing}
 * and {@code process}. Every job the flow hands off lives in a sub-package of
 * {@code de.cuioss.sheriff.gateway.edge}, none of which depends back on this class:
 * {@code assembly} (boot-time route completion and posture resolution), {@code upstream} (guarded
 * dispatch, egress clients and guards), {@code end} (the one end rule and connection retirement),
 * {@code relay} (streaming an upstream answer back), {@code admission} (admission and drain),
 * {@code request} (building the pipeline request), {@code error} (rejection metering and rendering),
 * {@code reserved} (the reserved paths and the portal) and {@code terminal} (serving a route's
 * terminal action).
 * <p>
 * <strong>Boot-time assembly.</strong> The constructor completes every {@link Route} of the frozen
 * {@link RouteTable} with its boot-attached components via the {@link RouteAssembler}, which also
 * builds the {@link UpstreamClients} lookup holding the deduplicated shared Vert.x
 * clients and SmallRye guards the proxy dispatch resolves per route. It builds every stage once with the shared
 * {@link SecurityEventCounter}, the gateway-wide {@link SecurityConfiguration} seeded from the
 * resolved {@code security_defaults} {@link SecurityProfile} (an omitted block resolving to
 * {@link SecurityProfile#DEFAULT_PROFILE}), the boot-wired
 * {@link ForwardedHeaderResolver} + {@link TcpPeerGate} (from the global {@code forwarded} block),
 * and the shared {@link GatewayEventCounter}. That same shared {@link SecurityEventCounter} is bound
 * to {@link SheriffMetrics} here so its per-{@code UrlSecurityFailureType} counts surface as the
 * {@code sheriff_security_events_total} meter. An unsupported protocol or {@code session} auth fails
 * boot here (fail fast), so no request is ever served on an invalid route set.
 * <p>
 * <strong>Per-request flow.</strong> The catch-all is registered {@linkplain io.vertx.ext.web.Route#last()
 * last} so management / health routes keep working. Each request is admitted under a bounded
 * {@linkplain EdgeHardeningOptions#admissionCap() admission cap} ({@link EdgeAdmission}) <em>before</em>
 * a virtual thread is dispatched (a flood is rejected {@code 503} rather than spawning unbounded
 * virtual threads), then the request stream is paused and the whole pipeline runs on a virtual thread
 * (the one body-carrying reserved POST path — back-channel logout — instead has its small body read on
 * the event loop first, under the {@linkplain EdgeHardeningOptions#reservedBodyMaxBytes() reserved-body
 * byte ceiling}, by the {@link ReservedBodyReader}, then dispatches, so a handler never has to drain a
 * paused stream from a virtual thread):
 * <ol>
 *   <li>stage 0 — global response security headers + global CORS (short-circuits a preflight here,
 *       before route selection and authentication);</li>
 *   <li>stage 1 — baseline security filter (records the single canonical path), the canonical-path
 *       guard, and the framing gate;</li>
 *   <li>the gateway's own reserved paths ({@link ReservedPathHandler}), ahead of the route table and
 *       after the passthrough host guard: first the OIDC reserved paths (exact; on the OIDC host,
 *       except for the two kinds the identity provider dials server-to-server — back-channel logout
 *       and the client JWKS — which are matched on every host), then the application portal's
 *       {@code portal.path} (exact, on any host) — so a prefix route never swallows either;</li>
 *   <li>stage 2 / 2a / 2b — deny-by-default route selection, then the selected route's resolved
 *       {@code security_headers} block replacing the global one wholesale (ADR-0007 Amendment A1),
 *       then the per-route verb gate;</li>
 *   <li>stage 3 — per-route thorough checks ({@code allowed_paths}, body cap, divergent pipeline);</li>
 *   <li>stage 4 — authentication on the {@link AuthBranch} resolved once for the request: offline
 *       bearer-token validation on the bearer branch, the server-session runtime on the session
 *       branch. The session branch — a {@code require: session} route, or the
 *       {@code Authorization}-less request on a {@code session_fallback} route — first passes the
 *       fixed CSRF defence; the bearer branch does not. A cookie-forwarding route's
 *       {@linkplain Route#cookieOriginDefence() same-origin check} runs next, on an unsafe request
 *       that carries a cookie the route would forward. A {@code session_fallback} route counts the
 *       selected branch against {@link SheriffMetrics#AUTH_BRANCH_TOTAL};</li>
 *   <li>stage 5 — the zero-trust forward policy, consuming the route's resolved
 *       {@link Route#effectiveForward() effectiveForward}, its resolved
 *       {@link Route#effectiveCookies() effectiveCookies} (which rebuilds the outbound {@code Cookie}
 *       header) and the global forwarded block;</li>
 *   <li>stage 6 / 7 — the route's terminal action ({@link TerminalDispatch}): streamed upstream
 *       dispatch (byte-capped) and the streamed response relay for a proxy route, the buffered
 *       governed asset response for an asset route, or — for a redirect route — the
 *       {@link RedirectStage} answer (configured status, {@code Location}, the accumulated stage
 *       headers, {@code Cache-Control: no-store} when the route is authenticated or the answer carries
 *       a {@code Set-Cookie}, {@code Vary} naming the route's {@code match.headers} matchers, empty
 *       body) written without consuming the forward policy or contacting any upstream. A redirect is
 *       answered only after stage 4, so an unauthenticated request under an authenticated anchor is
 *       challenged before the location is disclosed.</li>
 * </ol>
 * A {@link GatewayException} at any stage is metered and logged by the {@link RejectionReporter} and
 * rendered by the {@link ErrorResponseWriter} as an RFC 9457 {@code application/problem+json}
 * response carrying the failing event's status and problem type, followed by the exception's
 * {@linkplain GatewayException#getProblemExtensions() problem extension members} when it carries any,
 * never leaking internal detail.
 * <p>
 * <strong>The trust boundary.</strong> A request passes it once its bearer token validated offline or
 * stage 4 completed on any branch; {@link AuthenticationStage} records the passage on the request,
 * and the session-branch resolution below is recorded beside it. A rejection raised after route
 * selection and before that point — the verb gate, stage 3, the CSRF and cookie-origin checks and the
 * bearer branch of stage 4 — is still metered and logged under its true event first, then answered
 * to the caller exactly as an unrouted address while {@code security_defaults.uniform_not_found} is
 * on: the {@code 404}, the unrouted response headers and the negotiated HTML page, never a
 * trailers-only gRPC status (see {@link ErrorResponseWriter} and
 * {@link de.cuioss.sheriff.gateway.edge.error.UniformNotFound}). A caller past the boundary keeps the
 * honest answer. An unexpected internal failure in that same window is answered the same way and
 * counted under {@link EventType#PRE_TRUST_INTERNAL_FAILURE}; anywhere else it is an opaque {@code 500}.
 * Every unrouted {@code 404}, genuine or resolved, also leaves the edge no earlier than the
 * response-timing floor after admission: the edge stashes its admission reading on the context
 * ({@link ResponseDelay#ADMITTED_AT_KEY}), and the writer computes the release on the event loop as it
 * arms the answer and holds it back on a Vert.x timer.
 * A held answer moves from the general admission pool to the held-answer sub-budget through the
 * {@link ResponseDelay.Hold} the edge stashes beside it ({@link ResponseDelay#HOLD_KEY}); when that
 * sub-budget is full the answer is a {@code 503} instead, written no earlier than the floor's release.
 * <p>
 * <strong>The source key.</strong> Whenever {@code enumeration_detection} or
 * {@code threat_classification} is enabled — an omitted block runs with its defaults — the constructor
 * builds one {@link SourceKeys} stage, keying sources through a {@link SourceKeyResolver} holding the
 * declared {@code match.host} set and through the {@code forwarded} block's {@link TcpPeerGate} trust
 * decision. {@code handle} resolves and stashes the request's source through it after the
 * retired-connection check and before admission, so both per-source consumers read the same key and
 * neither depends on the other being enabled. With both blocks disabled no source is resolved.
 * <p>
 * <strong>Per-source enumeration detection.</strong> Unless {@code enumeration_detection} is disabled,
 * the constructor builds a {@link SourceGate} over the recon ledger. {@code handle} asks the gate,
 * after the source was stashed and before admission, to answer a blocked source {@code 429} with
 * {@code Retry-After} — and, under {@code delay}, a tripped source whose share of the held-answer
 * sub-budget is used up; the {@link ErrorResponseWriter} feeds the gate every reported rejection under
 * its true event. Under the default {@code observe} action a trip changes no answer. A disabled block
 * builds no ledger and no gate.
 * <p>
 * <strong>Per-source threat classification.</strong> Unless {@code threat_classification} is disabled,
 * the constructor builds a {@link ThreatClassifier} with a threat ledger of its own, whose trip raises
 * one ALERT per episode through {@link ThreatAlerts}. The {@link ErrorResponseWriter} hands it every
 * rejection {@code handleGatewayRejection} reports, under its true event, and the classifier scores the
 * ones that carry a weight ({@code ThreatWeights} lists the exact sets); the {@link SourceGate} hands it
 * every recon trip as one more weighted input. The classifier only observes: no answer, status, header or timing changes. A
 * disabled block builds no classifier, and a recon trip then raises nothing beyond its own record.
 * <p>
 * <strong>Negotiated HTML error pages.</strong> With {@code portal.error_pages} on, the three
 * gateway-originated error writers — the problem renderer, a failed OIDC callback and a
 * {@code source: directory} asset miss — answer an {@link ErrorPageClassifier HTML-eligible} exit with
 * the portal's HTML error page instead, when the request's {@code Accept} lists {@code text/html}
 * explicitly; the status is identical either way. A response relayed from an origin
 * ({@code ResponseStage.relay}, the gRPC trailer relay, an {@code upstream} asset, the WebSocket
 * relay), an admission {@code 503}, a gRPC trailers-only rejection and a relay failure are never
 * negotiated. On
 * {@code SIGTERM} the edge stops admitting new requests and drains in-flight ones within a bounded
 * window.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class GatewayEdgeRoute {

    private static final CuiLogger LOGGER = new CuiLogger(GatewayEdgeRoute.class);

    private static final String DEFAULT_EMIT_MODE = "x-forwarded";
    private static final int SERVICE_UNAVAILABLE = 503;

    private final List<Route> routes;
    private final ExecutorService virtualThreadExecutor;
    private final SheriffMetrics sheriffMetrics;
    private final BffRuntime bffRuntime;

    private final SecurityHeadersStage securityHeadersStage;
    private final BasicChecksStage basicChecksStage;
    private final CanonicalPathGuard canonicalPathGuard;
    private final PassthroughHostGuardStage passthroughHostGuardStage;
    private final RouteSelectionStage routeSelectionStage;
    private final VerbGateStage verbGateStage;
    private final ThoroughChecksStage thoroughChecksStage;
    private final AuthenticationStage authenticationStage;
    private final ForwardPolicyStage forwardPolicyStage;
    private final RedirectStage redirectStage;

    private final EdgeAdmission edgeAdmission;
    /**
     * The source-key stage; {@code null} when both {@code enumeration_detection} and
     * {@code threat_classification} are disabled.
     */
    private final @Nullable SourceKeys sourceKeys;
    /** The per-source gate; {@code null} when {@code enumeration_detection} is disabled. */
    private final @Nullable SourceGate sourceGate;
    private final ConnectionRetirement connectionRetirement;
    private final RejectionReporter rejectionReporter;
    private final ErrorResponseWriter errorResponseWriter;
    private final ReservedBodyReader reservedBodyReader;
    private final ReservedPathHandler reservedPathHandler;
    private final TerminalDispatch terminalDispatch;

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
     * @param bffRuntime            the server-mode BFF runtime (D16); its {@code require: session}
     *                              stage-4 runtime is injected into the session-aware
     *                              {@link AuthenticationStage} and its reserved-endpoint handlers serve
     *                              the carved-out OIDC paths. The {@linkplain BffRuntime#inert() inert}
     *                              runtime (a bearer-only or cookie-mode gateway) leaves the bearer
     *                              path and the empty reserved registry unchanged.
     * @param egressTrustProfileResolver the seam binding {@code egress_tls.upstream_tls_profile}'s
     *                              logical name to the deployment's trust anchors. Consulted once at
     *                              boot, and only when the document names a profile; a gateway that
     *                              names none never reaches it and keeps the JVM default trust store
     * @param portalEndpoint        the application portal's own reserved path, consulted after the
     *                              OIDC reserved paths and before route selection; the
     *                              {@linkplain PortalEndpoint#inert() inert} endpoint (no
     *                              {@code portal} block) never matches and leaves the edge unchanged
     * @param gatewayJson           the serializer the RFC 9457 problem body is rendered through
     */
    @Inject
    public GatewayEdgeRoute(RouteTable routeTable, GatewayConfig gatewayConfig,
            @GatewayValidator Instance<TokenValidator> tokenValidator, Vertx vertx,
            @VirtualThreads ExecutorService virtualThreadExecutor, EdgeHardeningOptions hardening,
            SheriffMetrics sheriffMetrics, BffRuntime bffRuntime,
            EgressTrustProfileResolver egressTrustProfileResolver, PortalEndpoint portalEndpoint,
            GatewayJson gatewayJson) {
        this(routeTable, gatewayConfig, tokenValidator, vertx, virtualThreadExecutor, hardening, sheriffMetrics,
                bffRuntime, egressTrustProfileResolver, portalEndpoint, gatewayJson,
                WebSocketRelayStage.RelayObserver.NO_OP);
    }

    /**
     * <strong>Test-only.</strong> Assembles the edge exactly as the CDI constructor does, but hands
     * the WebSocket relay stage the given {@link WebSocketRelayStage.RelayObserver}, so a full-edge
     * relay test can observe the wiring time and the first relayed frame per direction of every relay
     * this edge builds. Its only callers are the full-edge relay tests in {@code WebSocketRelayStageTest},
     * which lives with its subject in {@code edge.relay} — the reason this constructor is public.
     * <p>
     * Not a CDI entry point: CDI resolves only the {@link Inject @Inject} constructor, which delegates
     * here with {@link WebSocketRelayStage.RelayObserver#NO_OP}, so production behaviour is unchanged.
     * The observer type is public only so this edge can hand it on to the relay stage, and no
     * configuration key or system property selects it.
     *
     * @param routeTable                 as for the CDI constructor
     * @param gatewayConfig             as for the CDI constructor
     * @param tokenValidator             as for the CDI constructor
     * @param vertx                      as for the CDI constructor
     * @param virtualThreadExecutor      as for the CDI constructor
     * @param hardening                  as for the CDI constructor
     * @param sheriffMetrics             as for the CDI constructor
     * @param bffRuntime                 as for the CDI constructor
     * @param egressTrustProfileResolver as for the CDI constructor
     * @param portalEndpoint             as for the CDI constructor
     * @param gatewayJson                as for the CDI constructor
     * @param relayObserver              the observer every WebSocket relay this edge builds reports to
     */
    // The parameter list mirrors the CDI constructor's by construction, plus the observer; a parameter
    // object would exist only to satisfy the count and would diverge from the CDI signature it mirrors.
    @SuppressWarnings("java:S107") // test-only overload of the @Inject constructor
    public GatewayEdgeRoute(RouteTable routeTable, GatewayConfig gatewayConfig, Instance<TokenValidator> tokenValidator,
            Vertx vertx, ExecutorService virtualThreadExecutor, EdgeHardeningOptions hardening,
            SheriffMetrics sheriffMetrics, BffRuntime bffRuntime,
            EgressTrustProfileResolver egressTrustProfileResolver, PortalEndpoint portalEndpoint,
            GatewayJson gatewayJson, WebSocketRelayStage.RelayObserver relayObserver) {
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.sheriffMetrics = sheriffMetrics;
        this.bffRuntime = bffRuntime;
        Objects.requireNonNull(portalEndpoint, "portalEndpoint");
        Objects.requireNonNull(gatewayJson, "gatewayJson");
        this.edgeAdmission = new EdgeAdmission(hardening);

        SecurityEventCounter securityEventCounter = new SecurityEventCounter();
        // The gateway-wide baseline is seeded from the RESOLVED security_defaults profile rather
        // than from a hard-coded SecurityConfiguration.defaults(). This single instance feeds
        // BasicChecksStage, ThoroughChecksStage's skip-if-equal baseline,
        // ForwardedResolverConfig.securityConfig and defaultMaxBodySize — so seeding it here is
        // what makes an omitted security_defaults block genuinely mean 'strict' for every route. The
        // declared allow_extended_ascii override lands on it here, and on every route's preset below.
        SecurityProfile globalProfile = RouteTableBuilder.globalProfile(gatewayConfig);
        SecurityDefaultsConfig securityDefaultsConfig = gatewayConfig.securityDefaults();
        Boolean allowExtendedAscii = securityDefaultsConfig == null ? null : securityDefaultsConfig.allowExtendedAscii();
        SecurityConfiguration defaultConfiguration = SecurityConfigurations.withDeclaredCharacterPolicy(
                SecurityProfile.limitsProfile(globalProfile, globalProfile).preset(), allowExtendedAscii);
        long defaultMaxBodySize = defaultConfiguration.maxBodySize();
        GatewayEventCounter gatewayEventCounter = new GatewayEventCounter();
        UpstreamFailureMapper upstreamFailureMapper = new UpstreamFailureMapper(gatewayEventCounter);

        ForwardedConfig forwardedConfig = gatewayConfig.forwarded();
        List<String> trustedProxies = forwardedConfig == null ? List.of() : forwardedConfig.trustedProxies();
        String declaredEmitMode = forwardedConfig == null ? null : forwardedConfig.emit();
        String emitMode = declaredEmitMode != null ? declaredEmitMode : DEFAULT_EMIT_MODE;
        ForwardedResolverConfig resolverConfig = ForwardedResolverConfig.builder()
                .trustedProxies(Set.copyOf(trustedProxies))
                .securityConfig(defaultConfiguration)
                .build();
        ForwardedHeaderResolver resolver = new ForwardedHeaderResolver(resolverConfig, securityEventCounter);
        TcpPeerGate peerGate = new TcpPeerGate(trustedProxies);

        // The add-only asset content-type additions are resolved ONCE here, at boot, and handed to
        // every asset source the assembler builds — so the request path performs no config lookup
        // and the sources hold an immutable map rather than a live view of configuration.
        Map<String, String> assetContentTypes = AssetSources.assetContentTypesOf(gatewayConfig);

        // The gateway-global egress-TLS settings (ADR-0040) are resolved ONCE here, with their WARNs,
        // and bound to every governed egress client: the per-tuple clients the assembler builds below
        // and the edge-wide WebSocket client further down.
        EgressClients egressClients = new EgressClients(vertx, gatewayConfig, egressTrustProfileResolver);
        UpstreamGuards upstreamGuards = new UpstreamGuards(upstreamFailureMapper);

        RouteAssembler assembler = new RouteAssembler(new ProtocolProcessorRegistry());
        RouteAssembler.AssembledRoutes assembled = assembler.assemble(routeTable,
                filter -> SecurityPostures.securityPostureFor(filter, globalProfile, allowExtendedAscii),
                egressClients::clientFor,
                upstreamGuards::guardFor,
                (asset, keepIdentityHeaders) -> AssetSources.assetSourceFor(asset, assetContentTypes,
                        keepIdentityHeaders));
        this.routes = assembled.routes();
        LOGGER.info(ApiSheriffLogMessages.INFO.ROUTE_TABLE_COMPILED, routes.size());

        // Reserved OIDC endpoints (D2) are carved out of the proxy route table: the registry is
        // consulted in process() ahead of route selection, so a proxy route such as
        // path_prefix: /bff never swallows the exact /bff/callback. Empty (and inert) unless the
        // global oidc block declares a redirect_uri. The reserved-path exemption from the
        // url-parameter pipeline is now STRUCTURAL rather than predicate-driven: handleReservedPath
        // returns before route selection, and the parameter pipeline moved after it into
        // ThoroughChecksStage, so a reserved path never reaches it (ADR-0019, amended).
        ReservedPathRegistry reservedPathRegistry = ReservedPathRegistry.from(gatewayConfig.oidc());
        this.securityHeadersStage = new SecurityHeadersStage(gatewayConfig.securityHeaders());
        // Both header-value carve-outs are resolved ONCE and handed to BOTH stages that measure a
        // header value. Stage 3 re-validates every header under the ROUTE's configuration whenever
        // that configuration diverges from the baseline, so without the same two policies it would
        // undo the carve-outs one stage later — and it runs before the authentication stage, so an
        // Authorization value is still present when it does.
        SecurityConfiguration cookieHeaderConfiguration =
                SecurityPostures.cookieHeaderConfigurationFor(gatewayConfig, bffRuntime, defaultConfiguration);
        SecurityConfiguration authorizationHeaderConfiguration =
                SecurityPostures.authorizationHeaderConfigurationFor(gatewayConfig, defaultConfiguration);
        this.basicChecksStage = new BasicChecksStage(defaultConfiguration, securityEventCounter,
                cookieHeaderConfiguration, authorizationHeaderConfiguration);
        this.canonicalPathGuard = new CanonicalPathGuard();
        // The GET-body opt-in is gateway-wide by necessity, not by convenience: the framing gate runs
        // at stage 1, before route selection, so there is no resolved route to scope it to.
        FramingGate framingGate = new FramingGate(securityDefaultsConfig != null
                && securityDefaultsConfig.effectiveAllowGetWithContentLengthBody());
        TlsConfig tlsConfig = gatewayConfig.tls();
        this.passthroughHostGuardStage = new PassthroughHostGuardStage(
                tlsConfig == null ? Set.of() : tlsConfig.passthroughSni().keySet());
        this.routeSelectionStage = new RouteSelectionStage(routes);
        this.verbGateStage = new VerbGateStage();
        this.thoroughChecksStage = new ThoroughChecksStage(defaultConfiguration, securityEventCounter,
                cookieHeaderConfiguration, authorizationHeaderConfiguration);
        // A server-mode BFF wires the session-aware AuthenticationStage so a require:session route is
        // served through the SessionAuthenticationStage (D4) rather than failing at request time; a
        // bearer-only gateway keeps the no-session constructor unchanged.
        this.authenticationStage = bffRuntime.isActive()
                ? new AuthenticationStage(tokenValidator, bffRuntime.sessionStage())
                : new AuthenticationStage(tokenValidator);
        // The gateway's own cookies, derived once from the configuration — the reserved prefix with or
        // without a BFF, plus the configured session cookie and its activity sibling. One instance
        // serves both directions: stage 5 withholds them from every upstream request's Cookie header,
        // and an upstream Set-Cookie line naming one of them is not relayed.
        GatewayCookies gatewayCookies = GatewayCookies.of(gatewayConfig);
        this.forwardPolicyStage = new ForwardPolicyStage(resolver, peerGate, emitMode, gatewayCookies);
        ResponseStage responseStage = new ResponseStage(gatewayCookies);
        this.redirectStage = new RedirectStage();
        OriginValidationStage originValidationStage = new OriginValidationStage();
        // One WebSocketClient for the whole edge, carrying the gateway-global egress-TLS values (see
        // EgressClients#webSocketClient).
        WebSocketRelayStage webSocketRelayStage = new WebSocketRelayStage(egressClients.webSocketClient(),
                upstreamFailureMapper, gatewayEventCounter, relayObserver);
        GrpcStatusMapper grpcStatusMapper = new GrpcStatusMapper();

        // The collaborators the per-request flow hands its jobs to, each owning the state of its job.
        this.connectionRetirement = new ConnectionRetirement(framingGate);
        this.rejectionReporter = new RejectionReporter(gatewayEventCounter, sheriffMetrics,
                hardening.requestBodyIdleTimeoutMillis(), hardening.requestBodyDeadlineMillis());
        // The trust-boundary branch is on unless security_defaults.uniform_not_found is declared false:
        // an omitted block resolves to the secure default exactly as a block omitting the key does.
        boolean uniformNotFound = securityDefaultsConfig == null || securityDefaultsConfig.effectiveUniformNotFound();
        // Per-source enumeration detection: an omitted block resolves to the defaults, so detection runs
        // under observe unless the operator disables it — and then there is no ledger, no gate and no
        // feed. The source is keyed through the declared match.host set and the same trust decision the
        // forward stage takes; the resolver gets a counter of its own so a malformed forwarding header
        // of a trusted proxy is not counted twice on sheriff_security_events_total.
        EnumerationDetectionConfig declaredDetection = gatewayConfig.enumerationDetection();
        EnumerationDetectionConfig detection = declaredDetection != null
                ? declaredDetection : EnumerationDetectionConfig.defaults();
        // Per-source threat classification: an omitted block resolves to the defaults as well, so the
        // threat score is kept unless the operator disables it. It observes only — no answer changes.
        ThreatClassificationConfig declaredThreat = gatewayConfig.threatClassification();
        ThreatClassificationConfig threat = declaredThreat != null
                ? declaredThreat : ThreatClassificationConfig.defaults();
        // The source is resolved once, by one stage, whenever either consumer runs, so the threat score
        // does not depend on enumeration detection being enabled.
        this.sourceKeys = detection.effectiveEnabled() || threat.effectiveEnabled()
                ? new SourceKeys(new SourceKeyResolver(declaredHosts(routes)), peerGate,
                new ForwardedHeaderResolver(resolverConfig, new SecurityEventCounter()))
                : null;
        ThreatClassifier threatClassifier = threat.effectiveEnabled()
                ? new ThreatClassifier(threat, new ThreatAlerts(new ThreatAlertDocument(gatewayJson), sheriffMetrics,
                Clock.systemUTC()), System::nanoTime)
                : null;
        // A recon trip is one more input to the threat score, never an ALERT of its own; without threat
        // classification it has already been counted and logged by the gate, and nothing else follows.
        Consumer<SourceTrip> reconTripObserver = threatClassifier != null
                ? threatClassifier::admitReconTrip
                : _ -> {
                    // no threat score to raise
                };
        this.sourceGate = detection.effectiveEnabled()
                ? new SourceGate(detection, gatewayEventCounter, sheriffMetrics, gatewayJson,
                edgeAdmission::heldShareExhausted, reconTripObserver)
                : null;
        this.errorResponseWriter = new ErrorResponseWriter(portalEndpoint, gatewayJson, grpcStatusMapper,
                rejectionReporter, uniformNotFound,
                sourceGate != null ? sourceGate : ErrorResponseWriter.ReconFeed.NONE,
                threatClassifier != null ? threatClassifier : ErrorResponseWriter.ThreatFeed.NONE);
        this.reservedBodyReader = new ReservedBodyReader(reservedPathRegistry, hardening.reservedBodyMaxBytes(),
                gatewayEventCounter, rejectionReporter, errorResponseWriter);
        this.reservedPathHandler = new ReservedPathHandler(reservedPathRegistry, bffRuntime, portalEndpoint,
                errorResponseWriter);
        this.terminalDispatch = new TerminalDispatch(assembled.upstreamClients(), defaultMaxBodySize, hardening,
                upstreamFailureMapper, sheriffMetrics, gatewayEventCounter, bffRuntime, edgeAdmission,
                connectionRetirement, rejectionReporter, errorResponseWriter, responseStage,
                originValidationStage, webSocketRelayStage);

        // Bind the boot-shared cui-http counter to Micrometer so the per-UrlSecurityFailureType
        // security-filter counts surface as sheriff_security_events_total. It is the one meter of the
        // SheriffMetrics contract bound at boot rather than recorded per request: the request,
        // duration, error, upstream-duration, session-event and auth-branch meters are recorded as
        // the traffic that moves them is served.
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
     * window so the shutdown completes cleanly within the Quarkus shutdown timeout (see
     * {@link EdgeAdmission#drain()}).
     *
     * @param event the Quarkus shutdown event
     */
    void onShutdown(@Observes ShutdownEvent event) {
        edgeAdmission.drain();
    }

    private void handle(RoutingContext ctx) {
        HttpConnection connection = ctx.request().connection();
        if (connectionRetirement.isRetired(connection)) {
            // The HTTP/1.x connection was retired by a framing rejection: its request boundaries are no
            // longer trusted, so nothing that arrives on it is processed, answered or forwarded.
            connection.close();
            return;
        }
        // The source is resolved and stashed here, on the event loop, for both per-source consumers, and a
        // blocked source is answered 429 before admission: no permit, no virtual thread and no timer is
        // spent on it.
        if (sourceKeys != null) {
            sourceKeys.resolveAndStash(ctx);
        }
        if (sourceGate != null && sourceGate.answersBlocked(ctx)) {
            return;
        }
        if (!edgeAdmission.tryAdmit()) {
            ResponseEnds.reject(ctx, SERVICE_UNAVAILABLE);
            return;
        }
        long startNanos = System.nanoTime();
        // The admission reading is also the origin of the uniform 404's response-timing floor: the
        // writer that answers on the far side of the virtual-thread hop reads it from the context.
        ctx.put(ResponseDelay.ADMITTED_AT_KEY, startNanos);
        // Guard the admission accounting so it is rolled back exactly once. The end handler releases
        // it on the normal path; the executor-rejection path below also rolls it back, and both must
        // never double-release (reject() ends the response, which itself fires this end handler).
        // The end handler is NOT the only release site: a protocol: websocket route that completes its
        // upgrade takes the connection over, so the HTTP response never ends and this handler never
        // fires. That route therefore releases through the same guard from the relay's teardown
        // callback (see TerminalDispatch.dispatchWebSocket / WebSocketRelayStage); the guard is stashed
        // on the context under ADMISSION_GUARD_KEY because the virtual-thread hop carries only the
        // RoutingContext.
        AtomicBoolean admissionReleased = edgeAdmission.armReleaseGuards(ctx);
        // An answer held back on a timer — the uniform 404's floor, the delay action — leaves the general
        // pool for the held-answer sub-budget through this hold; the writers that hold answers are built
        // without the admission, so the hold rides on the context to them.
        ctx.put(ResponseDelay.HOLD_KEY, (ResponseDelay.Hold) () -> edgeAdmission.tryEnterHold(ctx));
        // This handler runs on the client connection's event loop, so the current context IS that
        // connection's context. It is captured here, before the virtual-thread hop, because a WebSocket
        // relay must run its upstream dial and client upgrade on it (see TerminalDispatch.dispatchWebSocket).
        ctx.put(TerminalDispatch.CLIENT_CONTEXT_KEY, Vertx.currentContext());
        ctx.addEndHandler(result -> {
            // The end handler runs once per request, so the admission is released in the finally: a
            // failure while the departure releases the upstream request must never keep the permit.
            try {
                if (result.failed()) {
                    // The response ended without being written: the client reset its stream or closed its
                    // connection first. A dispatch still waiting for its upstream answer learns it here,
                    // since a request whose body has ended no longer reports either.
                    DispatchStage.InboundBody armed = ctx.get(ResponseEnds.INBOUND_BODY_KEY);
                    if (armed != null) {
                        armed.clientDeparted(result.cause());
                    }
                }
            } finally {
                edgeAdmission.releaseAdmission(ctx, admissionReleased);
                rejectionReporter.recordRequestMetrics(ctx, startNanos);
            }
        });
        if (reservedBodyReader.needsReservedBodyRead(ctx)) {
            // The one body-carrying reserved POST (back-channel logout) is dispatched on a virtual
            // thread that cannot reliably re-arm a paused request stream. Read the small, gateway-
            // terminated body here on its own event loop — the natural Vert.x path — under a bounded
            // deadline, stash it, then dispatch. This avoids any paused-stream / cross-thread resume.
            reservedBodyReader.readReservedBodyThenDispatch(ctx, () -> dispatchProcessing(ctx, admissionReleased));
        } else {
            // Armed here, on the connection's own event loop and before the virtual-thread hop: an
            // inbound failure from now on is remembered and reaches the upstream dispatch even when
            // that dispatch subscribes to the body only later (see DispatchStage.InboundBody).
            ctx.put(ResponseEnds.INBOUND_BODY_KEY, DispatchStage.InboundBody.arm(ctx.request()));
            ctx.request().pause();
            dispatchProcessing(ctx, admissionReleased);
        }
    }

    /**
     * Hands the request to the virtual-thread pipeline, rolling admission back and failing {@code 503}
     * if the executor refuses the dispatch (a shutdown race) so the response always ends.
     */
    private void dispatchProcessing(RoutingContext ctx, AtomicBoolean admissionReleased) {
        try {
            virtualThreadExecutor.execute(() -> process(ctx));
        } catch (RejectedExecutionException rejected) {
            // The virtual-thread executor refused the dispatch (a shutdown race), so process(ctx) will
            // never run and the response would otherwise never end — leaking the admission permit and
            // the in-flight count. Roll the admission back now (idempotently) and fail the request 503
            // directly. Narrowed to RejectedExecutionException deliberately: Executor#execute contracts
            // this as its only failure (the submitted command is a non-null lambda literal, so the
            // NullPointerException arm is unreachable), and process(ctx) runs on the virtual thread
            // rather than inline, so no pipeline exception can surface here.
            LOGGER.debug(rejected, "Virtual-thread dispatch rejected: %s", rejected.getMessage());
            edgeAdmission.releaseAdmission(ctx, admissionReleased);
            ResponseEnds.reject(ctx, SERVICE_UNAVAILABLE);
        }
    }

    private void process(RoutingContext ctx) {
        PipelineRequest request = null;
        // The trailing RuntimeException catch below is a deliberate last-resort safety net for one
        // request, NOT an oversight: the pipeline runs a dozen independent stages, so the
        // unexpected-failure type is genuinely unknowable and no narrower catch is correct. Letting a
        // RuntimeException escape would abandon the response without ending it (the client hangs until
        // timeout) and strand the admission permit, since the end handler never fires. Every escape is
        // therefore handed to ErrorResponseWriter.renderUnexpected: one raised after route selection and
        // before the trust boundary is answered as the floored unrouted 404 and counted under
        // PRE_TRUST_INTERNAL_FAILURE, so a crafted input that makes a stage fail cannot tell a routed
        // address from an absent one; every other escape is an opaque 500. Either way the stack trace
        // goes to the debug log and never to the client.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            HttpServerRequest raw = ctx.request();
            Optional<HttpMethod> parsedMethod = EdgeRequests.parseMethod(raw.method().name());
            if (parsedMethod.isEmpty()) {
                errorResponseWriter.renderProblem(ctx, null, EventType.METHOD_NOT_ALLOWED);
                return;
            }
            HttpMethod method = parsedMethod.get();
            request = EdgeRequests.buildPipelineRequest(raw, method);

            securityHeadersStage.process(request);
            basicChecksStage.process(request);
            canonicalPathGuard.process(request);
            connectionRetirement.rejectFramingAndRetire(ctx, request);
            if (request.shortCircuitStatus().isPresent()) {
                terminalDispatch.writeShortCircuit(ctx, request);
                return;
            }
            passthroughHostGuardStage.process(request);
            if (reservedPathHandler.handleReservedPath(ctx, request)) {
                return;
            }
            if (reservedPathHandler.handlePortal(ctx, request)) {
                return;
            }
            routeSelectionStage.process(request);
            Route route = EdgeRequests.requireSelectedRoute(request);
            // Stash the metrics label the moment the route is known, not after the stages below have
            // agreed to serve it. Every one of them can end the request by throwing — the verb gate's
            // 405 first among them — and a label written after them would leave those terminal
            // responses metered under NO_ROUTE, which is both wrong and the opposite of what makes
            // them worth metering: a route answering 405 is exactly what an operator needs to find.
            ctx.put(RejectionReporter.ROUTE_KEY, route.id());
            // Stage 2a: from here on every response — the 405 of the verb gate included — is
            // route-scoped, so the route's resolved security_headers block (anchor before global,
            // wholesale) replaces the global block seeded at stage 0. Everything answered earlier
            // (a stage-1 rejection, an unrouted 404, the CORS preflight) keeps the global block.
            securityHeadersStage.applyRouteHeaders(request, route.securityHeaders(),
                    route.matcher().matchHeaderNames());
            verbGateStage.process(request);
            thoroughChecksStage.process(request, route.effectiveAllowedPaths());
            // Resolve the authentication branch through the same pure resolver stage 4 calls again,
            // so the CSRF gate below and the authentication stage cannot disagree on it. Only a
            // session_fallback route meters its branch — a plain route's branch is its posture and
            // carries no signal, so it never touches sheriff_auth_branch_total.
            AuthConfig effectiveAuth = route.effectiveAuth();
            AuthBranch branch = AuthBranch.resolve(effectiveAuth, request);
            if (effectiveAuth.effectiveSessionFallback()) {
                sheriffMetrics.recordAuthBranch(route.id(), branch);
            }
            // Recorded before the CSRF gate below so a rejection it raises already knows its branch: the
            // session branch keeps its 302 / 401 negotiation for an authentication failure, while its
            // CSRF refusal is still answered as the unrouted 404 to an untrusted caller.
            if (branch == AuthBranch.SESSION) {
                request.markSessionBranch();
            }
            // Fixed CSRF defence (D7): every unsafe-method request resolved onto the SESSION branch must
            // prove same-origin provenance before the session runtime resolves it — a require:session
            // route exactly as before, and the Authorization-less branch of a session_fallback route.
            // The BEARER branch carries no ambient credential, so it is not a CSRF surface and skips the
            // guard. A bearer-only gateway has no session branch and never reaches this guard.
            if (bffRuntime.isActive() && branch == AuthBranch.SESSION) {
                bffRuntime.csrfDefence().enforce(request);
            }
            enforceCookieOrigin(request, route);
            authenticationStage.process(request);
            // Honor an auth-stage short-circuit before forwarding: the BFF SessionAuthenticationStage
            // redirects a navigation by setting shortCircuit(302) plus a Location header. Without this gate
            // the request would fall through to the upstream (200) instead of being redirected — a
            // security defect. Mirrors the post-framing short-circuit gate above.
            if (request.shortCircuitStatus().isPresent()) {
                terminalDispatch.writeShortCircuit(ctx, request);
                return;
            }
            // A redirect route is answered here, after authentication and the short-circuit gate
            // above: an unauthenticated request under an authenticated anchor is challenged before
            // any configured location is disclosed. No forward policy is consumed and no upstream is
            // contacted, so REQUEST_FORWARDED is deliberately not incremented.
            RedirectConfig redirect = route.redirect();
            if (redirect != null) {
                terminalDispatch.writeRedirect(ctx, request, redirectStage.answer(redirect, ctx.request().query(),
                        route.effectiveAuth(), request.responseSetCookies(),
                        route.matcher().matchHeaderNames()));
                return;
            }
            // The route's cookie policy is applied here, once, so every dispatch path below — the HTTP
            // proxy, gRPC and the WebSocket upgrade — receives the same rebuilt Cookie header.
            ForwardPolicyStage.Result forward = forwardPolicyStage.process(request,
                    route.effectiveForward(), route.effectiveCookies(), route.notModifiedEnabled());
            // Protocol-dispatch seam: a WebSocket route validates its handshake Origin and hands the
            // upgrade to the opaque relay; a gRPC route dispatches over the forced-h2 GrpcDispatchStage
            // and relays response trailers; HTTP and GraphQL take the HTTP dispatch path. The split is
            // DispatchPath, the same one the assembler built clients and guards from.
            // A switch expression with no default arm: a DispatchPath constant without an arm won't compile.
            PipelineRequest admitted = request;
            Runnable dispatch = switch (DispatchPath.of(route.protocol())) {
                case WEBSOCKET_RELAY -> () -> terminalDispatch.dispatchWebSocket(ctx, admitted, route, forward);
                case GRPC_DISPATCH -> () -> terminalDispatch.dispatchGrpc(ctx, admitted, route, forward);
                case HTTP_DISPATCH -> () -> terminalDispatch.dispatchAndRelay(ctx, admitted, route, forward);
            };
            dispatch.run();
        } catch (GatewayException rejected) {
            errorResponseWriter.handleGatewayRejection(ctx, request, rejected);
        } catch (RuntimeException unexpected) {
            errorResponseWriter.renderUnexpected(ctx, request, unexpected);
        }
    }

    /**
     * The hosts the routes declare in {@code match.host} — the set a source's authority host is folded
     * to, so a caller rotating {@code Host} cannot multiply its detection keys.
     */
    private static List<String> declaredHosts(List<Route> routes) {
        return routes.stream()
                .map(route -> route.match().host())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The same-origin check of a cookie-forwarding route, run before authentication so a refused
     * request reaches neither the identity check nor the upstream. A route carries the check exactly
     * when it forwards browser cookies with {@code cookies.same_origin_check} on and is not a WebSocket
     * route, whose upgrade {@code OriginValidationStage} gates instead.
     *
     * @param request the in-flight request
     * @param route   the selected route
     * @throws GatewayException carrying {@code CSRF_REJECTED} when the check refuses the request
     */
    private void enforceCookieOrigin(PipelineRequest request, Route route) {
        CsrfDefence cookieOriginDefence = route.cookieOriginDefence();
        // Asked only of an unsafe request that carries at least one cookie the route would forward,
        // read through the same admittedCookiePairs stage 5 rebuilds the Cookie header from. A request
        // carrying no forwardable cookie — no Cookie at all, only the gateway's own cookies, or only
        // names the route's list does not admit — hands the upstream no ambient browser credential, so
        // there is no cross-site request to forge, and a non-browser client without an Origin passes.
        // A safe method is never checked.
        if (cookieOriginDefence != null && CsrfDefence.isUnsafeMethod(request.method())
                && !forwardPolicyStage.admittedCookiePairs(request, route.effectiveCookies()).isEmpty()) {
            cookieOriginDefence.enforce(request);
        }
    }
}
