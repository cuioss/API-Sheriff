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
package de.cuioss.sheriff.gateway.quarkus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.auth.JwksTrustProfileResolver;
import de.cuioss.sheriff.gateway.bff.cookie.CookieKeyMaterial;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.ScopedEngineFlows;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.EndedRefreshTokens;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ClaimAllowlistFilter;
import de.cuioss.sheriff.gateway.bff.reserved.IdTokenClaimProjection;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.CallbackHandler;
import de.cuioss.sheriff.token.client.flow.IssValidator;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.StepUpHandler;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.lifecycle.RevocationClient;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * CDI producer of the {@link BffRuntime} — the D16 edge wiring that makes the D1–D12 BFF
 * components reachable at the live gateway edge.
 * <p>
 * The producer builds the active runtime <strong>only when a global {@code oidc} block with a
 * {@code redirect_uri} and a recognised {@code session.mode} — {@code server} or {@code cookie} —
 * is configured</strong>; otherwise it produces the {@linkplain BffRuntime#inert() inert} runtime,
 * so a bearer-only gateway is unchanged and never touches the confidential-client engine.
 * <p>
 * <strong>Both modes drive the same wiring.</strong> The only thing the mode selects is which
 * {@link SessionBinding} is assembled — the store-backed {@link ServerSessionBinding} or the
 * stateless {@link CookieSessionBinding} over the AES-256-GCM sealed-cookie codec. Every other
 * collaborator (login flow, CSRF defence, step-up, refresh, and all reserved endpoints) is
 * identical, and cookie mode reaches the confidential-client engine exactly as server mode does.
 * On the active path the producer assembles the session binding, the cookie codecs, the CSRF
 * defence, the token-refresh / step-up coordinators, the
 * reserved-endpoint handlers, and the {@code require: session} stage-4 runtime, and binds the
 * {@code token-sheriff-client} engine seams — {@link ScopedEngineFlows#authorize} for login,
 * {@code AuthorizationCodeFlow#exchange} for the callback, {@code RefreshFlow#refresh} for transparent
 * refresh, and {@code StepUpHandler#initiate} for RFC 9470 re-drive — so the engine is reached at
 * runtime.
 * <p>
 * <strong>Per-request login scope (ADR-0048).</strong> The login leg requests the scope set the
 * caller names — a session route's {@code neededScopes}, or the set {@link ReturnTargetScopes}
 * resolves for a {@code /auth/login?returnUrl=} target — through {@link ScopedEngineFlows}, which
 * drives a flow over a {@link ClientConfiguration} built for exactly that set by
 * {@link #backChannelConfiguration(OidcConfig, List)}. The callback exchange, step-up and revocation
 * stay on the base configuration carrying {@code oidc.scopes}.
 * <p>
 * <strong>Response mode.</strong> Both authorization-URL seams are wired with the gateway-owned
 * {@link QueryResponseModeAuthorizationRequestBuilder}, so the flow is driven with
 * {@code response_mode=query} and the callback is a top-level GET the browser sends the
 * {@code SameSite=Lax} binding cookie on. See that class for the reasoning and for the accepted
 * code-in-the-URL tradeoff.
 * <p>
 * <strong>Transparent refresh is switchable.</strong> {@code oidc.session.refresh.enabled} governs
 * the whole refresh path and is applied here, at the two points that path is constructed: the
 * {@code CodeExchange} seam retains the exchange's refresh token only when refresh is on, and the
 * {@link TokenRefreshCoordinator} is assembled only when refresh is on. With the switch off the
 * stage's refresh seam degrades to the unwired binding — session unchanged, no cookies — so the
 * gateway mediates the token it was issued until the absolute session TTL expires, and no refresh
 * token is stored anywhere. An absent key (or an absent {@code refresh} block) means <em>on</em>.
 * With the switch on, each coordinator outcome reaches the stage as one of three dispositions: a
 * current, refreshed or deferred session is mediated; a failed refresh — the session was destroyed —
 * clears the session cookie before the refresh-failure response; an unavailable refresh — the identity
 * provider was unreachable and the access token has expired, but the session is kept — answers the
 * refresh-failure response without clearing the cookie. That response is
 * {@code oidc.session.refresh.on_failure}, resolved here and handed to the stage:
 * {@code reauthenticate} (also when omitted) re-drives the login negotiation, {@code reject} answers
 * {@code 401} for every request. A refresh token still live at the identity provider after a session
 * ends on a refused redemption or a persist failure is revoked, best-effort, through the engine's
 * RFC 7009 {@link RevocationClient} built from the same back-channel configuration — dispatched on the
 * Quarkus-managed virtual-thread executor after the session-ended outcome has been published, so the
 * failing request never waits for the revocation endpoint.
 * <p>
 * <strong>Lazy discovery.</strong> The OIDC provider metadata is resolved through a memoized supplier
 * on first engine use, not at boot: a BFF gateway in either session mode therefore boots (and is
 * unit-testable) without a live IdP, and the discovery-dependent {@code end_session_endpoint} the
 * logout leg needs is materialized only when the first logout arrives.
 * <p>
 * <strong>The identity-provider back-channel carries a pinned TLS posture (ADR-0045).</strong> The
 * {@link ClientConfiguration} every engine seam dials the identity provider with — discovery, the
 * authorization-code exchange, refresh and refresh-token revocation — is the sixth TLS-terminating
 * outbound leg, and it is bound
 * to the global {@code egress_tls} block through its own peer keys: {@code oidc_verify_hostname} is
 * passed to the builder's {@code verifyHostname} on every build, the {@code true} path included, so
 * the leg's effect never depends on token-sheriff's own default (ADR-0022); {@code oidc_tls_profile},
 * when named, is resolved through {@link JwksTrustProfileResolver} and supplies the builder's
 * {@code sslContext}, replacing the JVM default trust store on that leg. The relaxation and a named
 * profile are mutually exclusive and the pair is refused at boot, ahead of the library's own
 * builder-vocabulary rejection (the ADR-0041 pattern). Both are applied only on the active path: a
 * bearer-only gateway builds no such client, so neither key has a leg to act on there.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class BffRuntimeProducer {

    private static final CuiLogger LOGGER = new CuiLogger(BffRuntimeProducer.class);

    private static final int DEFAULT_MAX_SESSIONS = 10_000;
    private static final int DEFAULT_MAX_PENDING = 10_000;
    private static final int DEFAULT_REFRESH_LEEWAY_SECONDS = 30;
    /**
     * The default for {@code oidc.session.refresh.enabled} when the key — or the whole
     * {@code refresh} block — is omitted. Transparent refresh is the documented BFF behaviour and is
     * what every descriptor that declares only {@code leeway_seconds} already relies on, so an
     * absent key means <em>on</em> and only an explicit {@code false} turns the path off.
     */
    private static final boolean DEFAULT_REFRESH_ENABLED = true;
    private static final Duration BACKCHANNEL_FRESHNESS_WINDOW = Duration.ofMinutes(2);
    private static final Duration LOGOUT_STATE_TTL = Duration.ofMinutes(1);
    private static final String DEFAULT_FINAL_REDIRECT = "/";
    /** The post-login fallback return target when {@code oidc.login.default_return_url} is omitted. */
    private static final String ROOT_RETURN_URL = "/";
    /** The gateway key a named BFF back-channel trust profile is declared under, for error context. */
    private static final String OIDC_TLS_PROFILE_KEY = "egress_tls.oidc_tls_profile";
    /** The RFC 7009 {@code token_type_hint} sent when a live refresh token is revoked. */
    private static final String REFRESH_TOKEN_TYPE_HINT = "refresh_token";
    /** The {@code oidc.session.refresh.on_failure} spelling that re-drives login negotiation (the default). */
    private static final String ON_FAILURE_REAUTHENTICATE = "reauthenticate";
    /** The {@code oidc.session.refresh.on_failure} spelling that answers {@code 401} for every request. */
    private static final String ON_FAILURE_REJECT = "reject";

    private final GatewayConfig gatewayConfig;
    private final RouteTable routeTable;
    private final Instance<TokenValidator> tokenValidator;
    private final JwksTrustProfileResolver trustProfileResolver;
    private final ExecutorService virtualThreadExecutor;
    /**
     * The resolved global {@code egress_tls} block, read once here for the same reason
     * {@code TokenValidatorProducer} resolves its own key once (ADR-0040): the keys are gateway-global
     * and have no per-issuer or per-route override. An absent block resolves to
     * {@link EgressTlsConfig#defaults()} rather than to a null-guarded {@code false}, so "block omitted
     * entirely" and "block present, key omitted" are the same posture — verification ON.
     */
    private final EgressTlsConfig egressTls;

    /**
     * @param gatewayConfig         the bound global gateway document carrying the {@code oidc} block and
     *                              the global {@code egress_tls} block
     * @param routeTable            the boot-built route table the login-initiation endpoint resolves a
     *                              return target's requested scope set against
     * @param tokenValidator        a lazy handle to the gateway's shared offline validator, resolved
     *                              only on the active BFF path in either session mode (a bearer-only
     *                              gateway never triggers it)
     * @param trustProfileResolver  the single seam mapping a logical {@code egress_tls.oidc_tls_profile}
     *                              name to concrete trust anchors, consulted only on the active path and
     *                              only when a profile is named
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor a best-effort refresh-token
     *                              revocation is dispatched on, off the request path
     */
    public BffRuntimeProducer(GatewayConfig gatewayConfig, RouteTable routeTable,
            @GatewayValidator Instance<TokenValidator> tokenValidator,
            JwksTrustProfileResolver trustProfileResolver,
            @VirtualThreads ExecutorService virtualThreadExecutor) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.routeTable = Objects.requireNonNull(routeTable, "routeTable");
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.trustProfileResolver = Objects.requireNonNull(trustProfileResolver, "trustProfileResolver");
        this.virtualThreadExecutor = Objects.requireNonNull(virtualThreadExecutor, "virtualThreadExecutor");
        EgressTlsConfig declaredEgressTls = gatewayConfig.egressTls();
        this.egressTls = declaredEgressTls == null ? EgressTlsConfig.defaults() : declaredEgressTls;
    }

    /**
     * Produces the BFF runtime.
     * <p>
     * {@link Singleton} (a pseudo-scope, no client proxy) because {@link BffRuntime} is a {@code final}
     * class ArC cannot subclass to build a normal-scope proxy. The runtime is immutable and assembled
     * once at boot, so a single instance is exact.
     *
     * @return the active runtime for a recognised {@code session.mode}, or the inert runtime for a
     *         bearer-only gateway
     */
    @Produces
    @Singleton
    public BffRuntime bffRuntime() {
        OidcConfig oidc = gatewayConfig.oidc();
        if (oidc == null || !isBffMode(oidc)) {
            LOGGER.debug("No BFF-mode oidc block — BFF runtime inert (bearer-only proxy path unchanged)");
            return BffRuntime.inert();
        }
        return build(oidc);
    }

    /**
     * The mode-aware activation predicate: a BFF runtime is built for either recognised
     * {@code session.mode} — {@code server} or {@code cookie} — provided a {@code redirect_uri} is
     * configured. An unrecognised or absent mode leaves the gateway bearer-only.
     */
    private static boolean isBffMode(OidcConfig oidc) {
        OidcConfig.Session session = oidc.session();
        // isRecognisedMode() is the SHARED mode predicate on the config model — the mode spelling is
        // never compared against a locally-declared constant here.
        return session != null && session.isRecognisedMode() && oidc.redirectUri() != null;
    }

    private BffRuntime build(OidcConfig oidc) {
        // Both are guaranteed non-null by the isBffMode predicate, which every caller of this method
        // clears first; the guards make that boot-time contract explicit rather than implied.
        String redirectUri = Objects.requireNonNull(oidc.redirectUri(), "oidc.redirect_uri");
        OidcConfig.Session session = Objects.requireNonNull(oidc.session(), "oidc.session");
        String gatewayOrigin = originOf(redirectUri);
        String issuer = Objects.requireNonNullElse(oidc.issuer(), gatewayOrigin);
        String clientId = Objects.requireNonNullElse(oidc.clientId(), "");
        String clientSecret = Objects.requireNonNullElse(oidc.clientSecret(), "");

        Duration sessionTtl = Duration.ofSeconds(
                Objects.requireNonNullElse(session.ttlSeconds(), OidcConfig.Session.DEFAULT_TTL_SECONDS));
        String declaredCookieName = session.cookieName();
        String cookieName = declaredCookieName == null
                ? SessionCookieCodec.DEFAULT_COOKIE_NAME
                : declaredCookieName;
        Integer declaredMaxSessions = session.maxSessions();
        int maxSessions = declaredMaxSessions == null ? DEFAULT_MAX_SESSIONS : declaredMaxSessions;
        OidcConfig.Refresh refresh = session.refresh();
        // refresh.enabled is the switch for the WHOLE transparent-refresh path, not a hint: it governs
        // both whether the refresh token is retained at login and whether the near-expiry coordinator
        // is assembled at all. Both applications are below; keeping them on one resolved boolean is
        // what stops the two halves drifting into a state where a credential is stored but no
        // machinery can ever redeem it.
        boolean refreshEnabled = Objects.requireNonNullElse(
                refresh == null ? null : refresh.enabled(), DEFAULT_REFRESH_ENABLED);
        Duration refreshLeeway = Duration.ofSeconds(Objects.requireNonNullElse(
                refresh == null ? null : refresh.leewaySeconds(), DEFAULT_REFRESH_LEEWAY_SECONDS));
        SessionAuthenticationStage.OnFailure onFailure = onFailurePolicy(refresh == null ? null : refresh.onFailure());
        OidcConfig.Csrf csrf = session.csrf();
        List<String> declaredTrustedOrigins = csrf == null ? List.of() : csrf.trustedOrigins();
        Set<String> trustedOrigins = declaredTrustedOrigins.isEmpty()
                ? Set.of(gatewayOrigin)
                : Set.copyOf(declaredTrustedOrigins);

        // The base configuration carries the static oidc.scopes and serves every leg that does not
        // request a per-request scope set: discovery, the callback code exchange, step-up and
        // revocation. The login leg requests per scope set through ScopedEngineFlows below, whose
        // factory is this same method — so every scoped variant carries the identical pinned posture.
        reportBackChannelPosture();
        ClientConfiguration clientConfiguration = backChannelConfiguration(oidc, oidc.scopes());
        ClientAuthentication clientAuthentication = new ClientSecretBasicAuth(clientId, clientSecret);
        Supplier<ProviderMetadata> metadata = memoize(() -> new DiscoveryResolver(clientConfiguration).resolve());

        TokenValidator validator = tokenValidator.get();
        TokenValidationBridge tokenBridge = new TokenValidationBridge(validator);
        IdTokenValidationBridge idBridge = new IdTokenValidationBridge(validator);
        TokenEndpointClient tokenEndpointClient = new TokenEndpointClient(clientConfiguration);
        // The gateway drives response_mode=query, NOT the engine's built-in form_post: the callback has
        // to be a top-level GET navigation so the SameSite=Lax browser-binding cookie is actually sent
        // on it (a Lax cookie is dropped on the cross-site POST a form_post callback performs, which
        // dead-ended every real-browser login on the "no binding cookie" 403 branch). One instance is
        // shared with the step-up leg below, so BOTH engine seams that build an authorization URL carry
        // the corrected mode. Every other collaborator here is exactly what the 4-arg
        // AuthorizationCodeFlow constructor supplies on its own — a default IssValidator and
        // CallbackHandler, and no sender constraint (DPoP is not in use) — so nothing else changes.
        AuthorizationRequestBuilder authorizationRequestBuilder = new QueryResponseModeAuthorizationRequestBuilder();
        AuthorizationCodeFlow authorizationCodeFlow = new AuthorizationCodeFlow(clientConfiguration,
                tokenEndpointClient, tokenBridge, idBridge, new IssValidator(), authorizationRequestBuilder,
                new CallbackHandler(), null);
        RefreshFlow refreshFlow = new RefreshFlow(clientConfiguration, tokenEndpointClient, tokenBridge,
                clientAuthentication);
        // ADR-0048: the engine reads scope only from ClientConfiguration.getScopes(), so a login that
        // requests a route's neededScopes rides a configuration built for exactly that set.
        ScopedEngineFlows scopedFlows = new ScopedEngineFlows(scopes -> backChannelConfiguration(oidc, scopes),
                tokenEndpointClient, tokenBridge, idBridge, authorizationRequestBuilder, clientAuthentication);

        BindingCookieCodec bindingCookieCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        // D7 seam: the whole BFF foundation binds SessionBinding, never the store directly. The mode
        // selects only which implementation is assembled — everything below is mode-independent.
        SessionBinding sessionBinding = session.isCookieMode()
                ? cookieSessionBinding(session, cookieName, sessionTtl)
                : new ServerSessionBinding(new InMemorySessionStore(maxSessions),
                new SessionCookieCodec(cookieName, sessionTtl));
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(DEFAULT_MAX_PENDING);
        Clock clock = Clock.systemUTC();

        // Resolved once: the login flow, the login-initiation endpoint (through the flow) and the step-up
        // re-drive all fall back to the same configured post-login target.
        String defaultReturnUrl = defaultReturnUrl(oidc);

        // D5 login flow — the AuthorizationInitiation seam reaches the engine at runtime, requesting
        // exactly the scope set the caller names (a route's neededScopes, or oidc.scopes).
        LoginFlow loginFlow = new LoginFlow(scopes -> scopedFlows.authorize(metadata.get(), scopes),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl);

        // D2 callback — the CodeExchange seam reaches the engine's code exchange + token validation,
        // then hands the result to the refresh policy, which is where the exchange's refresh token is
        // retained or dropped. See applyRefreshPolicy for why the drop happens at login rather than
        // at storage time.
        CallbackEndpoint.CodeExchange codeExchange = (context, params) -> applyRefreshPolicy(
                authorizationCodeFlow.exchange(metadata.get(), context, params, clientAuthentication),
                refreshEnabled);
        CallbackEndpoint callbackEndpoint = new CallbackEndpoint(codeExchange, pendingStore, bindingCookieCodec,
                sessionBinding, sessionTtl);

        // D7/D9 transparent refresh — near-expiry decision + engine RefreshFlow, session persistence.
        // Assembled ONLY when refresh.enabled: with the switch off no coordinator exists and the
        // stage's refresh seam degrades to sessionUnchanged() — the unwired binding
        // SessionAuthenticationStage.TokenRefresh documents (session unchanged, no cookies) — so the
        // gateway mediates the current token verbatim until the session's absolute TTL expires.
        // The revocation client is built from the SAME back-channel configuration, so a refresh token
        // revoked after a refused redemption travels the pinned ADR-0045 posture like every other leg.
        RevocationClient revocationClient = new RevocationClient(clientConfiguration);
        SessionAuthenticationStage.TokenRefresh tokenRefresh = refreshEnabled
                ? nearExpiryRefresh(new TokenRefreshCoordinator(refreshLeeway,
                sessionRecord -> tokenBridge.validateAccessToken(sessionRecord.accessToken())
                        .getExpirationDateTime().toInstant(),
                refreshToken -> refreshFlow.refresh(metadata.get(), refreshToken),
                sessionBinding,
                liveRefreshToken -> revokeRefreshToken(revocationClient, metadata.get(), liveRefreshToken,
                        clientAuthentication),
                virtualThreadExecutor,
                endedRefreshTokens(session)))
                : sessionUnchanged();

        // D4 session stage-4 runtime — binds refresh and the login-redirect seam. A session route runs
        // no scope check: the scopes it needs are requested at login, never enforced per request.
        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                tokenRefresh,
                (returnUrl, scopes, now) -> {
                    LoginFlow.LoginRedirect redirect = loginFlow.initiate(returnUrl, scopes, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                onFailure,
                clock);

        // D7 RFC 9470 step-up — instantiated with the engine StepUpHandler seam; the upstream-challenge
        // edge integration is exercised by the Keycloak integration tests.
        // Built with the SAME response-mode-corrected builder as the login leg: StepUpHandler#initiate
        // constructs its own authorization URL through an AuthorizationRequestBuilder, so leaving it on
        // the default builder would keep the step-up re-drive emitting response_mode=form_post and
        // reintroduce the dropped-binding-cookie failure on that leg alone.
        StepUpHandler stepUpHandler = new StepUpHandler(authorizationRequestBuilder);
        StepUpCoordinator stepUpCoordinator = new StepUpCoordinator(
                (sessionRecord, challenge, now) -> Optional.empty(),
                challenge -> stepUpHandler.initiate(clientConfiguration, metadata.get(), challenge),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl);

        // D11 user-info fold — validated ID-token claims through the engine, projected to their native
        // JSON types, capped by the allowlist.
        OidcConfig.UserInfo userInfo = oidc.userInfo();
        ClaimAllowlistFilter claimFilter = new ClaimAllowlistFilter(
                userInfo == null ? List.of() : userInfo.allowedClaims(),
                userInfo == null ? List.of() : userInfo.defaultView());
        UserInfoEndpoint userInfoEndpoint = new UserInfoEndpoint(sessionBinding, claimFilter,
                sessionRecord -> IdTokenClaimProjection.project(
                        idBridge.validateRefreshedIdToken(sessionRecord.idToken())));

        // D12 login-initiation fold — the browser-facing start mirror of the callback.
        // Its fresh login requests the scope set of the route the return target lands on.
        ReturnTargetScopes returnTargetScopes = new ReturnTargetScopes(routeTable, gatewayOrigin, oidc.scopes());
        LoginInitiationEndpoint loginInitiationEndpoint = new LoginInitiationEndpoint(loginFlow, sessionBinding,
                gatewayOrigin, returnTargetScopes);

        // D2c back-channel logout — JWKS signature verification through the engine, then the claim residual.
        // The endpoint stays wired in both modes: it is gated on the binding's IdP-destruction
        // capability, so a stateless binding answers a deliberate 404 on the reserved path rather than
        // letting that path fall through to the proxy route table.
        BackchannelLogoutReceiver backchannelReceiver = new BackchannelLogoutReceiver(
                idBridge::validateRefreshedIdToken,
                new LogoutTokenValidator(issuer, clientId, BACKCHANNEL_FRESHNESS_WINDOW),
                sessionBinding);
        BackchannelLogoutEndpoint backchannelLogoutEndpoint =
                new BackchannelLogoutEndpoint(backchannelReceiver, sessionBinding);

        // D5 RP-initiated logout — lazy so the discovery-sourced end_session_endpoint is resolved on
        // first logout, not at boot. Revocation at the IdP is best-effort; the authoritative logout is
        // the local session destruction the LogoutEndpoint performs.
        Supplier<LogoutEndpoint> logoutEndpoint = memoize(() -> buildLogoutEndpoint(oidc, gatewayOrigin,
                metadata.get(), sessionBinding));

        CsrfDefence csrfDefence = new CsrfDefence(trustedOrigins);

        // build(...) is reached for BOTH modes, so the diagnostic must name the mode that was actually
        // resolved — this is the line an operator greps to confirm which binding came up.
        LOGGER.debug("%s-mode BFF runtime assembled for origin %s (issuer %s)",
                session.isCookieMode() ? OidcConfig.Session.MODE_COOKIE : OidcConfig.Session.MODE_SERVER,
                gatewayOrigin, issuer);
        return new BffRuntime(sessionStage, csrfDefence, stepUpCoordinator, callbackEndpoint, logoutEndpoint,
                backchannelLogoutEndpoint, userInfoEndpoint, loginInitiationEndpoint);
    }

    /**
     * Builds the {@link ClientConfiguration} the BFF OIDC back-channel dials the identity provider with,
     * carrying the pinned {@code egress_tls} posture of that leg.
     * <p>
     * <strong>The collision is refused before the builder is touched.</strong> token-sheriff implements
     * {@code verifyHostname(false)} by relaxing the context <em>it</em> derives from the JVM default
     * trust store, so it rejects that relaxation together with a caller-supplied {@code sslContext} —
     * there is nothing to relax in a context the caller built. A named {@code oidc_tls_profile} supplies
     * exactly such a context, so the pair is refused here with a {@code CONFIG_INVALID} naming both
     * gateway keys, instead of surfacing as the library's {@link IllegalArgumentException} phrased in
     * its own builder vocabulary.
     * <p>
     * <strong>The hostname posture is passed unconditionally.</strong> {@code verifyHostname} is called
     * on the {@code true} path as well, so the key's effect is independent of the library default and
     * an upstream default change cannot silently move this gateway's posture (ADR-0022). The trust
     * context is set only when a profile is named; otherwise the leg keeps the JVM default trust store.
     * <p>
     * <strong>One posture for every scope set (ADR-0048).</strong> The {@code scopes} argument is the
     * only thing that varies between the configurations this method builds: the base configuration
     * passes {@code oidc.scopes}, and {@link ScopedEngineFlows} passes each per-request scope set. The
     * collision refusal, the hostname posture and the trust context are applied identically on every
     * call, so a scoped variant can never dial the identity provider with a weaker posture than the
     * base configuration. The build is silent; the relaxed or replaced posture is reported once per
     * runtime by {@link #reportBackChannelPosture()}, never once per scoped variant or per refresh.
     *
     * @param oidc   the global {@code oidc} block, already cleared by the BFF-mode activation predicate
     * @param scopes the scope list the configuration carries — the {@code scope} value the engine
     *               sends on the authorization request and the refresh grant it drives with it
     * @return the back-channel client configuration carrying {@code scopes}, the resolved hostname
     *         posture and, when a profile is named, its trust anchors
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when
     *                          {@code egress_tls.oidc_verify_hostname} is {@code false} while
     *                          {@code egress_tls.oidc_tls_profile} is named, or when the named profile
     *                          cannot be resolved to trust anchors
     */
    ClientConfiguration backChannelConfiguration(OidcConfig oidc, List<String> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        String redirectUri = Objects.requireNonNull(oidc.redirectUri(), "oidc.redirect_uri");
        boolean verifyHostname = egressTls.oidcVerifyHostname();
        String tlsProfile = egressTls.oidcTlsProfile();
        refuseRelaxedProfileCollision(verifyHostname, tlsProfile);
        // The configured oidc.issuer, or the gateway's own origin when the key is omitted. The explicit null
        // tests on captured locals are deliberate (java:S2637): Sonar does not prove an Objects.requireNonNullElse
        // result non-null at the @NonNull issuer/clientId builder setters, so do not collapse them back.
        String gatewayOrigin = originOf(redirectUri);
        String declaredIssuer = oidc.issuer();
        String issuer = declaredIssuer == null ? gatewayOrigin : declaredIssuer;
        String declaredClientId = oidc.clientId();
        String clientId = declaredClientId == null ? "" : declaredClientId;
        String declaredClientSecret = oidc.clientSecret();
        String clientSecret = declaredClientSecret == null ? "" : declaredClientSecret;
        ClientConfiguration.ClientConfigurationBuilder builder = ClientConfiguration.builder()
                .issuer(issuer).clientId(clientId).clientSecret(clientSecret)
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(scopes).redirectUri(redirectUri)
                // Called unconditionally, on the true path as well, so the posture never rests on the
                // library default (ADR-0022).
                .verifyHostname(verifyHostname);
        if (tlsProfile != null) {
            builder.sslContext(trustProfileResolver.resolveEgressProfile(OIDC_TLS_PROFILE_KEY, tlsProfile));
        }
        return builder.build();
    }

    /**
     * Reports the relaxed or replaced back-channel posture once, at the single build this
     * {@link Singleton} runtime performs: {@code ApiSheriff-125} when {@code oidc_verify_hostname}
     * resolves {@code false}, {@code ApiSheriff-126} when an {@code oidc_tls_profile} is in effect. The
     * collision of the two is refused first, so the pair is never reported as if it were in effect.
     *
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when
     *                          {@code egress_tls.oidc_verify_hostname} is {@code false} while
     *                          {@code egress_tls.oidc_tls_profile} is named
     */
    void reportBackChannelPosture() {
        boolean verifyHostname = egressTls.oidcVerifyHostname();
        String tlsProfile = egressTls.oidcTlsProfile();
        refuseRelaxedProfileCollision(verifyHostname, tlsProfile);
        if (!verifyHostname) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_HOSTNAME_VERIFICATION_DISABLED);
        }
        if (tlsProfile != null) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_TRUST_PROFILE_IN_EFFECT, tlsProfile);
        }
    }

    private static void refuseRelaxedProfileCollision(boolean verifyHostname, @Nullable String tlsProfile) {
        if (!verifyHostname && tlsProfile != null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "egress_tls.oidc_tls_profile '" + tlsProfile + "' is named while "
                            + "egress_tls.oidc_verify_hostname is false — the two are mutually exclusive. The "
                            + "hostname relaxation applies only to the default-trust-store context the BFF "
                            + "OIDC back-channel derives, so a profile-supplied context leaves nothing to "
                            + "relax. Either drop egress_tls.oidc_tls_profile and bind the identity "
                            + "provider's anchors into the JVM default trust store, or set "
                            + "egress_tls.oidc_verify_hostname back to true");
        }
    }

    /**
     * Resolves {@code oidc.session.refresh.on_failure} to the stage's policy. An omitted key means
     * {@code reauthenticate}. The schema admits only the two spellings, so any other value is refused
     * rather than silently mapped onto a default.
     *
     * @param declared the declared {@code on_failure} value, {@code null} when omitted
     * @return the resolved policy
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} for an unrecognised value
     */
    static SessionAuthenticationStage.OnFailure onFailurePolicy(@Nullable String declared) {
        if (declared == null) {
            return SessionAuthenticationStage.OnFailure.REAUTHENTICATE;
        }
        return switch (declared) {
            case ON_FAILURE_REAUTHENTICATE -> SessionAuthenticationStage.OnFailure.REAUTHENTICATE;
            case ON_FAILURE_REJECT -> SessionAuthenticationStage.OnFailure.REJECT;
            default -> throw new GatewayException(EventType.CONFIG_INVALID,
                    "oidc.session.refresh.on_failure '" + declared + "' is not recognised — use '"
                            + ON_FAILURE_REAUTHENTICATE + "' or '" + ON_FAILURE_REJECT + "'");
        };
    }

    /**
     * Resolves {@code oidc.login.default_return_url} — the post-login target the login flow, the
     * login-initiation endpoint and the step-up re-drive fall back to when no usable same-origin return
     * URL is supplied. An omitted key (or an omitted {@code login} block) resolves to {@code /}. Boot
     * validation has already refused a declared value that is not same-origin with
     * {@code redirect_uri}, so the value is used as declared.
     *
     * @param oidc the bound {@code oidc} block
     * @return the configured default return URL, {@code /} when unset
     */
    static String defaultReturnUrl(OidcConfig oidc) {
        OidcConfig.Login login = oidc.login();
        String declared = login == null ? null : login.defaultReturnUrl();
        return declared == null ? ROOT_RETURN_URL : declared;
    }

    /**
     * Adapts the refresh coordinator to the stage's {@link SessionAuthenticationStage.TokenRefresh}
     * seam. {@code CURRENT}, {@code REFRESHED} and {@code DEFERRED} carry a session and are mediated
     * with whatever {@code Set-Cookie} the re-bind produced; {@code FAILED} — the session was destroyed
     * — ends the session so the stage clears the cookie; {@code UNAVAILABLE} — the session was kept
     * but its access token has expired — fails only this request, so the cookie survives for the next
     * attempt.
     * <p>
     * Extracted so the enabled and disabled bindings of the seam read as the two alternatives they
     * are, rather than one of them being a multi-statement lambda inline in the assembly.
     *
     * @param coordinator the assembled near-expiry refresh coordinator
     * @return the stage seam driving {@code coordinator}
     */
    static SessionAuthenticationStage.TokenRefresh nearExpiryRefresh(TokenRefreshCoordinator coordinator) {
        return (sessionRecord, cookieHeader, now) -> {
            TokenRefreshCoordinator.RefreshOutcome outcome = coordinator.refresh(sessionRecord, cookieHeader, now);
            return switch (outcome.kind()) {
                case CURRENT, REFRESHED, DEFERRED -> SessionAuthenticationStage.RefreshResult.mediate(
                        new SessionBinding.BoundSession(Objects.requireNonNull(outcome.session(), "session"),
                                outcome.setCookieHeaders()));
                case FAILED -> SessionAuthenticationStage.RefreshResult.sessionEnded();
                case UNAVAILABLE -> SessionAuthenticationStage.RefreshResult.requestFailed();
            };
        };
    }

    /**
     * Selects the coordinator's ended-refresh-token marker by session mode. Cookie mode binds the bounded
     * in-memory marker, because {@code destroy} holds nothing server-side there and a retained sealed
     * cookie would otherwise drive a fresh refresh grant on every near-expiry request; server mode binds
     * the inert one, because {@code destroy} already removes the session from the store.
     *
     * @param session the resolved {@code oidc.session} block
     * @return {@link EndedRefreshTokens#bounded()} in cookie mode, {@link EndedRefreshTokens#inert()}
     *         otherwise
     */
    static EndedRefreshTokens endedRefreshTokens(OidcConfig.Session session) {
        return session.isCookieMode() ? EndedRefreshTokens.bounded() : EndedRefreshTokens.inert();
    }

    /**
     * Binds the coordinator's best-effort refresh-token revocation to the engine's RFC 7009 client.
     * A provider that declares no {@code revocation_endpoint} leaves nothing to call, so the token is
     * not revoked and the gap is recorded at {@code DEBUG}; the session has already been destroyed
     * locally either way.
     *
     * @param revocationClient     the engine revocation client on the pinned back-channel posture
     * @param metadata             the resolved provider metadata
     * @param refreshToken         the refresh token still live at the provider
     * @param clientAuthentication the confidential-client authentication to present
     */
    static void revokeRefreshToken(RevocationClient revocationClient, ProviderMetadata metadata,
            String refreshToken, ClientAuthentication clientAuthentication) {
        Optional<String> revocationEndpoint = metadata.getRevocationEndpoint();
        if (revocationEndpoint.isEmpty()) {
            LOGGER.debug("Provider declares no revocation_endpoint — refresh token not revoked after the session ended");
            return;
        }
        revocationClient.revoke(revocationEndpoint.get(), refreshToken, REFRESH_TOKEN_TYPE_HINT, clientAuthentication);
    }

    /**
     * The disabled binding of the same seam — the alternative {@link #nearExpiryRefresh} adapts to.
     * With {@code oidc.session.refresh.enabled=false} no coordinator exists, so the seam yields the
     * resolved session verbatim and produces no {@code Set-Cookie}: the gateway keeps mediating the
     * token it was issued at login until the absolute session TTL expires, and never reaches the
     * engine's refresh grant.
     *
     * @return the unwired stage seam
     */
    static SessionAuthenticationStage.TokenRefresh sessionUnchanged() {
        return (sessionRecord, cookieHeader, now) ->
                SessionAuthenticationStage.RefreshResult.mediate(new SessionBinding.BoundSession(sessionRecord, List.of()));
    }

    /**
     * Applies {@code oidc.session.refresh.enabled} to a completed code exchange.
     * <p>
     * The engine returns the refresh token alongside the validated tokens and {@link CallbackEndpoint}
     * seeds it into the {@code SessionRecord}, which is what makes the session refreshable: without it
     * {@link TokenRefreshCoordinator#refresh} returns on its first guard and the near-expiry refresh
     * silently never runs. When refresh is switched off the token is therefore dropped <em>here</em>,
     * rather than being stored and ignored — a credential the gateway will never redeem is not written
     * to the session store, and in cookie mode is never sealed into the browser cookie.
     *
     * @param exchanged      the engine's authentication result for the completed exchange
     * @param refreshEnabled the resolved {@code oidc.session.refresh.enabled}
     * @return {@code exchanged} unchanged when refresh is on, otherwise a copy carrying the same
     *         validated access and ID tokens and no refresh token
     */
    static AuthorizationCodeFlow.AuthenticationResult applyRefreshPolicy(
            AuthorizationCodeFlow.AuthenticationResult exchanged, boolean refreshEnabled) {
        if (refreshEnabled) {
            return exchanged;
        }
        return new AuthorizationCodeFlow.AuthenticationResult(exchanged.accessToken(), exchanged.idToken(), null);
    }

    /**
     * Assembles the stateless cookie-mode binding from the resolved {@link CookieKeyMaterial}: the
     * AES-256-GCM sealed-cookie codec over its one sealing key, plus the per-gateway salt that keys
     * the derived, never-emitted session identity. The salt is derived from the sealing key rather
     * than configured separately, so it needs no operator input and cannot be recomputed
     * off-gateway.
     * <p>
     * Per ADR-0011 the configuration stays neutral — the key is an {@code ${ENV_VAR}} reference
     * carrying no material — so the concrete runtime choice is named by a startup diagnostic
     * reporting the active key mode, never any key bytes. The
     * generate-on-startup mode additionally raises the catalogued INFO
     * {@code COOKIE_KEY_GENERATED} from {@link CookieKeyMaterial}, because its
     * sessions-die-on-restart consequence is operationally notable rather than merely diagnostic.
     * <p>
     * The codec's seal-time size budget comes from {@code oidc.session.max_cookie_size} — the single
     * declared number that also drives the edge's pre-route {@code Cookie} header-value cap, so the
     * two ends of the round trip cannot drift apart.
     */
    private static SessionBinding cookieSessionBinding(OidcConfig.Session session, String cookieName,
            Duration sessionTtl) {
        CookieKeyMaterial keyMaterial = CookieKeyMaterial.resolve(session.encryptionKey());
        Integer declaredMaxCookieSize = session.maxCookieSize();
        int maxCookieSize = declaredMaxCookieSize == null
                ? SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET
                : declaredMaxCookieSize;
        LOGGER.debug("Cookie-mode key material resolved: mode=%s, maxCookieSize=%s",
                keyMaterial.mode().diagnosticName(), maxCookieSize);
        return new CookieSessionBinding(keyMaterial.codec(cookieName, sessionTtl, maxCookieSize),
                keyMaterial.identitySalt());
    }

    private static LogoutEndpoint buildLogoutEndpoint(OidcConfig oidc, String gatewayOrigin, ProviderMetadata metadata,
            SessionBinding sessionBinding) {
        OidcConfig.Logout logout = oidc.logout();
        String declaredPostLogoutRedirectUri = logout == null ? null : logout.postLogoutRedirectUri();
        String postLogoutRedirectUri = declaredPostLogoutRedirectUri == null
                ? gatewayOrigin + "/"
                : declaredPostLogoutRedirectUri;
        String declaredFinalRedirect = logout == null ? null : logout.finalRedirect();
        String finalRedirect = declaredFinalRedirect == null
                ? DEFAULT_FINAL_REDIRECT
                : declaredFinalRedirect;
        String endSessionEndpoint = metadata.getEndSessionEndpoint()
                .orElseThrow(() -> new IllegalStateException(
                        "OIDC provider metadata declares no end_session_endpoint — RP-initiated logout unavailable"));
        EndSessionFlow endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(postLogoutRedirectUri)));
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(endSessionFlow,
                sessionRecord -> {
                    // Best-effort by design: the authoritative logout is the local session destruction.
                },
                endSessionEndpoint, postLogoutRedirectUri, finalRedirect, LOGOUT_STATE_TTL);
        return new LogoutEndpoint(rpInitiatedLogout, sessionBinding);
    }

    /**
     * Derives the gateway's own origin (scheme + host + optional non-default port) from the configured
     * {@code redirect_uri}, used to same-origin-validate post-login return URLs and as the default
     * CSRF trusted origin.
     */
    static String originOf(String redirectUri) {
        URI uri = URI.create(redirectUri);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalStateException("oidc.redirect_uri is not an absolute URI: " + redirectUri);
        }
        int port = uri.getPort();
        StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
        // Browsers send the Origin without the scheme's default port, so https://gw:443 and
        // http://gw:80 must reduce to https://gw / http://gw. Emitting the default port here would make
        // the derived origin (same-origin return-URL check AND the default CSRF trusted-origins entry)
        // reject a genuine same-origin unsafe request whose Origin omits the default port.
        if (port != -1 && port != defaultPortFor(scheme)) {
            origin.append(':').append(port);
        }
        return origin.toString();
    }

    private static int defaultPortFor(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    /**
     * Wraps {@code delegate} in a thread-safe memoizing supplier: the delegate runs at most once, on
     * first {@link Supplier#get()}, and every later call returns the cached value. Used to defer OIDC
     * discovery (and the discovery-dependent logout endpoint) to first request rather than boot.
     */
    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        AtomicReference<T> cache = new AtomicReference<>();
        return () -> {
            T existing = cache.get();
            if (existing != null) {
                return existing;
            }
            synchronized (cache) {
                T current = cache.get();
                if (current == null) {
                    current = Objects.requireNonNull(delegate.get(), "memoized supplier produced null");
                    cache.set(current);
                }
                return current;
            }
        };
    }
}
