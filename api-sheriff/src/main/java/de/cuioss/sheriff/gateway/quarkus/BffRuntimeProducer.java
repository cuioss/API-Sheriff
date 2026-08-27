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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.bff.cookie.CookieKeyMaterial;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.ClaimAllowlistFilter;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
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
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.tools.logging.CuiLogger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

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
 * {@code token-sheriff-client} engine seams — {@code AuthorizationCodeFlow#authorize} /
 * {@code #exchange} for login and callback, {@code RefreshFlow#refresh} for transparent refresh, and
 * {@code StepUpHandler#initiate} for RFC 9470 re-drive — so the engine is reached at runtime.
 * <p>
 * <strong>Response mode.</strong> Both authorization-URL seams are wired with the gateway-owned
 * {@link QueryResponseModeAuthorizationRequestBuilder}, so the flow is driven with
 * {@code response_mode=query} and the callback is a top-level GET the browser sends the
 * {@code SameSite=Lax} binding cookie on. See that class for the reasoning and for the accepted
 * code-in-the-URL tradeoff.
 * <p>
 * <strong>Lazy discovery.</strong> The OIDC provider metadata is resolved through a memoized supplier
 * on first engine use, not at boot: a BFF gateway in either session mode therefore boots (and is
 * unit-testable) without a live IdP, and the discovery-dependent {@code end_session_endpoint} the
 * logout leg needs is materialized only when the first logout arrives.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class BffRuntimeProducer {

    private static final CuiLogger LOGGER = new CuiLogger(BffRuntimeProducer.class);

    private static final int DEFAULT_SESSION_TTL_SECONDS = 3600;
    private static final int DEFAULT_MAX_SESSIONS = 10_000;
    private static final int DEFAULT_MAX_PENDING = 10_000;
    private static final int DEFAULT_REFRESH_LEEWAY_SECONDS = 30;
    private static final Duration BACKCHANNEL_FRESHNESS_WINDOW = Duration.ofMinutes(2);
    private static final Duration LOGOUT_STATE_TTL = Duration.ofMinutes(1);
    private static final String DEFAULT_FINAL_REDIRECT = "/";

    private final GatewayConfig gatewayConfig;
    private final Instance<TokenValidator> tokenValidator;

    /**
     * @param gatewayConfig  the bound global gateway document carrying the {@code oidc} block
     * @param tokenValidator a lazy handle to the gateway's shared offline validator, resolved only on
     *                       the active BFF path in either session mode (a bearer-only gateway never
     *                       triggers it)
     */
    public BffRuntimeProducer(GatewayConfig gatewayConfig,
            @GatewayValidator Instance<TokenValidator> tokenValidator) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
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
                Objects.requireNonNullElse(session.ttlSeconds(), DEFAULT_SESSION_TTL_SECONDS));
        String declaredCookieName = session.cookieName();
        String cookieName = declaredCookieName == null
                ? SessionCookieCodec.DEFAULT_COOKIE_NAME
                : declaredCookieName;
        Integer declaredMaxSessions = session.maxSessions();
        int maxSessions = declaredMaxSessions == null ? DEFAULT_MAX_SESSIONS : declaredMaxSessions;
        OidcConfig.Refresh refresh = session.refresh();
        Duration refreshLeeway = Duration.ofSeconds(Objects.requireNonNullElse(
                refresh == null ? null : refresh.leewaySeconds(), DEFAULT_REFRESH_LEEWAY_SECONDS));
        OidcConfig.Csrf csrf = session.csrf();
        List<String> declaredTrustedOrigins = csrf == null ? List.of() : csrf.trustedOrigins();
        Set<String> trustedOrigins = declaredTrustedOrigins.isEmpty()
                ? Set.of(gatewayOrigin)
                : Set.copyOf(declaredTrustedOrigins);

        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .issuer(issuer).clientId(clientId).clientSecret(clientSecret)
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(oidc.scopes()).redirectUri(redirectUri).build();
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

        BindingCookieCodec bindingCookieCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        // D7 seam: the whole BFF foundation binds SessionBinding, never the store directly. The mode
        // selects only which implementation is assembled — everything below is mode-independent.
        SessionBinding sessionBinding = session.isCookieMode()
                ? cookieSessionBinding(session, cookieName, sessionTtl)
                : new ServerSessionBinding(new InMemorySessionStore(maxSessions),
                new SessionCookieCodec(cookieName, sessionTtl));
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(DEFAULT_MAX_PENDING);
        Clock clock = Clock.systemUTC();

        // D5 login flow — the AuthorizationInitiation seam reaches the engine at runtime.
        LoginFlow loginFlow = new LoginFlow(() -> authorizationCodeFlow.authorize(metadata.get()),
                pendingStore, bindingCookieCodec, gatewayOrigin);

        // D2 callback — the CodeExchange seam reaches the engine's code exchange + token validation.
        CallbackEndpoint callbackEndpoint = new CallbackEndpoint(
                (context, params) -> authorizationCodeFlow.exchange(metadata.get(), context, params,
                        clientAuthentication),
                pendingStore, bindingCookieCodec, sessionBinding, sessionTtl);

        // D7/D9 transparent refresh — near-expiry decision + engine RefreshFlow, session persistence.
        TokenRefreshCoordinator refreshCoordinator = new TokenRefreshCoordinator(refreshLeeway,
                sessionRecord -> tokenBridge.validateAccessToken(sessionRecord.accessToken())
                        .getExpirationDateTime().toInstant(),
                refreshToken -> refreshFlow.refresh(metadata.get(), refreshToken),
                sessionBinding);

        // D4 session stage-4 runtime — binds refresh, scope enforcement, and the login-redirect seam.
        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                (sessionRecord, cookieHeader, now) -> {
                    TokenRefreshCoordinator.RefreshOutcome outcome =
                            refreshCoordinator.refresh(sessionRecord, cookieHeader, now);
                    if (outcome.isFailure()) {
                        // The coordinator already destroyed the session — signal it so the stage
                        // re-drives the unauthenticated negotiation instead of mediating the
                        // pre-refresh token of a session the gateway just revoked.
                        return Optional.empty();
                    }
                    return Optional.of(new SessionBinding.BoundSession(
                            Objects.requireNonNullElse(outcome.session(), sessionRecord),
                            outcome.setCookieHeaders()));
                },
                (accessToken, requiredScopes) -> tokenBridge.validateAccessToken(accessToken)
                        .providesScopes(requiredScopes),
                (returnUrl, now) -> {
                    LoginFlow.LoginRedirect redirect = loginFlow.initiate(returnUrl, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
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
                pendingStore, bindingCookieCodec, gatewayOrigin);

        // D11 user-info fold — validated ID-token claims through the engine, capped by the allowlist.
        OidcConfig.UserInfo userInfo = oidc.userInfo();
        ClaimAllowlistFilter claimFilter = new ClaimAllowlistFilter(
                userInfo == null ? List.of() : userInfo.allowedClaims(),
                userInfo == null ? List.of() : userInfo.defaultView());
        UserInfoEndpoint userInfoEndpoint = new UserInfoEndpoint(sessionBinding, claimFilter,
                sessionRecord -> toClaimMap(idBridge.validateRefreshedIdToken(sessionRecord.idToken()).getClaims()));

        // D12 login-initiation fold — the browser-facing start mirror of the callback.
        LoginInitiationEndpoint loginInitiationEndpoint = new LoginInitiationEndpoint(loginFlow, sessionBinding,
                gatewayOrigin);

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

    private static Map<String, Object> toClaimMap(Map<String, ClaimValue> claims) {
        Map<String, Object> converted = new LinkedHashMap<>();
        claims.forEach((name, value) -> {
            if (value != null && !value.isNotPresentForClaimValueType()) {
                String original = value.getOriginalString();
                if (original != null && !original.isBlank()) {
                    converted.put(name, original);
                }
            }
        });
        return converted;
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
