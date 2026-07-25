/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
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
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
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
 * CDI producer of the server-mode {@link BffRuntime} — the D16 edge wiring that makes the D1–D12 BFF
 * components reachable at the live gateway edge.
 * <p>
 * The producer builds the active runtime <strong>only when a global {@code oidc} block with
 * {@code session.mode=server} and a {@code redirect_uri} is configured</strong>; otherwise it produces
 * the {@linkplain BffRuntime#inert() inert} runtime, so a bearer-only gateway (or a cookie-mode BFF)
 * is unchanged and never touches the confidential-client engine. On the active path it assembles the
 * server-side stores, cookie codecs, the CSRF defence, the token-refresh / step-up coordinators, the
 * reserved-endpoint handlers, and the {@code require: session} stage-4 runtime, and binds the
 * {@code token-sheriff-client} engine seams — {@code AuthorizationCodeFlow#authorize} /
 * {@code #exchange} for login and callback, {@code RefreshFlow#refresh} for transparent refresh, and
 * {@code StepUpHandler#initiate} for RFC 9470 re-drive — so the engine is reached at runtime.
 * <p>
 * <strong>Lazy discovery.</strong> The OIDC provider metadata is resolved through a memoized supplier
 * on first engine use, not at boot: a server-mode gateway therefore boots (and is unit-testable)
 * without a live IdP, and the discovery-dependent {@code end_session_endpoint} the logout leg needs is
 * materialized only when the first logout arrives.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class BffRuntimeProducer {

    private static final CuiLogger LOGGER = new CuiLogger(BffRuntimeProducer.class);

    private static final String SESSION_MODE_SERVER = "server";
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
     *                       the active server-mode path (a bearer-only gateway never triggers it)
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
     * @return the active server-mode runtime, or the inert runtime for a bearer-only / cookie-mode gateway
     */
    @Produces
    @Singleton
    public BffRuntime bffRuntime() {
        Optional<OidcConfig> oidc = gatewayConfig.oidc();
        if (!isServerModeBff(oidc)) {
            LOGGER.debug("No server-mode oidc block — BFF runtime inert (bearer-only proxy path unchanged)");
            return BffRuntime.inert();
        }
        return build(oidc.orElseThrow());
    }

    private static boolean isServerModeBff(Optional<OidcConfig> oidc) {
        boolean serverMode = oidc.flatMap(OidcConfig::session).flatMap(OidcConfig.Session::mode)
                .filter(SESSION_MODE_SERVER::equalsIgnoreCase).isPresent();
        boolean hasRedirect = oidc.flatMap(OidcConfig::redirectUri).isPresent();
        return serverMode && hasRedirect;
    }

    private BffRuntime build(OidcConfig oidc) {
        String redirectUri = oidc.redirectUri().orElseThrow();
        String gatewayOrigin = originOf(redirectUri);
        String issuer = oidc.issuer().orElse(gatewayOrigin);
        String clientId = oidc.clientId().orElse("");
        String clientSecret = oidc.clientSecret().orElse("");

        Optional<OidcConfig.Session> session = oidc.session();
        Duration sessionTtl = Duration.ofSeconds(
                session.flatMap(OidcConfig.Session::ttlSeconds).orElse(DEFAULT_SESSION_TTL_SECONDS));
        String cookieName = session.flatMap(OidcConfig.Session::cookieName)
                .orElse(SessionCookieCodec.DEFAULT_COOKIE_NAME);
        int maxSessions = session.flatMap(OidcConfig.Session::maxSessions).orElse(DEFAULT_MAX_SESSIONS);
        Duration refreshLeeway = Duration.ofSeconds(session.flatMap(OidcConfig.Session::refresh)
                .flatMap(OidcConfig.Refresh::leewaySeconds).orElse(DEFAULT_REFRESH_LEEWAY_SECONDS));
        Set<String> trustedOrigins = Set.copyOf(session.flatMap(OidcConfig.Session::csrf)
                .map(OidcConfig.Csrf::trustedOrigins).filter(list -> !list.isEmpty())
                .orElse(List.of(gatewayOrigin)));

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
        AuthorizationCodeFlow authorizationCodeFlow = new AuthorizationCodeFlow(clientConfiguration,
                tokenEndpointClient, tokenBridge, idBridge);
        RefreshFlow refreshFlow = new RefreshFlow(clientConfiguration, tokenEndpointClient, tokenBridge,
                clientAuthentication);

        SessionCookieCodec sessionCookieCodec = new SessionCookieCodec(cookieName, sessionTtl);
        BindingCookieCodec bindingCookieCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        SessionStore sessionStore = new InMemorySessionStore(maxSessions);
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(DEFAULT_MAX_PENDING);
        Clock clock = Clock.systemUTC();

        // D5 login flow — the AuthorizationInitiation seam reaches the engine at runtime.
        LoginFlow loginFlow = new LoginFlow(() -> authorizationCodeFlow.authorize(metadata.get()),
                pendingStore, bindingCookieCodec, gatewayOrigin);

        // D2 callback — the CodeExchange seam reaches the engine's code exchange + token validation.
        CallbackEndpoint callbackEndpoint = new CallbackEndpoint(
                (context, params) -> authorizationCodeFlow.exchange(metadata.get(), context, params,
                        clientAuthentication),
                pendingStore, bindingCookieCodec, sessionStore, sessionCookieCodec, sessionTtl);

        // D7/D9 transparent refresh — near-expiry decision + engine RefreshFlow, session persistence.
        TokenRefreshCoordinator refreshCoordinator = new TokenRefreshCoordinator(refreshLeeway,
                sessionRecord -> tokenBridge.validateAccessToken(sessionRecord.accessToken())
                        .getExpirationDateTime().toInstant(),
                refreshToken -> refreshFlow.refresh(metadata.get(), refreshToken),
                sessionStore);

        // D4 session stage-4 runtime — binds refresh, scope enforcement, and the login-redirect seam.
        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionStore, sessionCookieCodec,
                (record, now) -> refreshCoordinator.refresh(record, now).session().orElse(record),
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
        StepUpHandler stepUpHandler = new StepUpHandler();
        StepUpCoordinator stepUpCoordinator = new StepUpCoordinator(
                (record, challenge, now) -> Optional.empty(),
                challenge -> stepUpHandler.initiate(clientConfiguration, metadata.get(), challenge),
                pendingStore, bindingCookieCodec, gatewayOrigin);

        // D11 user-info fold — validated ID-token claims through the engine, capped by the allowlist.
        ClaimAllowlistFilter claimFilter = new ClaimAllowlistFilter(
                oidc.userInfo().map(OidcConfig.UserInfo::allowedClaims).orElse(List.of()),
                oidc.userInfo().map(OidcConfig.UserInfo::defaultView).orElse(List.of()));
        UserInfoEndpoint userInfoEndpoint = new UserInfoEndpoint(sessionStore, sessionCookieCodec, claimFilter,
                sessionRecord -> toClaimMap(idBridge.validateRefreshedIdToken(sessionRecord.idToken()).getClaims()));

        // D12 login-initiation fold — the browser-facing start mirror of the callback.
        LoginInitiationEndpoint loginInitiationEndpoint = new LoginInitiationEndpoint(loginFlow, sessionStore,
                sessionCookieCodec, gatewayOrigin);

        // D2c back-channel logout — JWKS signature verification through the engine, then the claim residual.
        BackchannelLogoutReceiver backchannelReceiver = new BackchannelLogoutReceiver(
                idBridge::validateRefreshedIdToken,
                new LogoutTokenValidator(issuer, clientId, BACKCHANNEL_FRESHNESS_WINDOW),
                sessionStore);
        BackchannelLogoutEndpoint backchannelLogoutEndpoint = new BackchannelLogoutEndpoint(backchannelReceiver);

        // D5 RP-initiated logout — lazy so the discovery-sourced end_session_endpoint is resolved on
        // first logout, not at boot. Revocation at the IdP is best-effort; the authoritative logout is
        // the local session destruction the LogoutEndpoint performs.
        Supplier<LogoutEndpoint> logoutEndpoint = memoize(() -> buildLogoutEndpoint(oidc, gatewayOrigin,
                metadata.get(), sessionStore, sessionCookieCodec));

        CsrfDefence csrfDefence = new CsrfDefence(trustedOrigins);

        LOGGER.debug("Server-mode BFF runtime assembled for origin %s (issuer %s)", gatewayOrigin, issuer);
        return new BffRuntime(sessionStage, csrfDefence, stepUpCoordinator, callbackEndpoint, logoutEndpoint,
                backchannelLogoutEndpoint, userInfoEndpoint, loginInitiationEndpoint);
    }

    private static LogoutEndpoint buildLogoutEndpoint(OidcConfig oidc, String gatewayOrigin, ProviderMetadata metadata,
            SessionStore sessionStore, SessionCookieCodec sessionCookieCodec) {
        Optional<OidcConfig.Logout> logout = oidc.logout();
        String postLogoutRedirectUri = logout.flatMap(OidcConfig.Logout::postLogoutRedirectUri)
                .orElse(gatewayOrigin + "/");
        String finalRedirect = logout.flatMap(OidcConfig.Logout::finalRedirect).orElse(DEFAULT_FINAL_REDIRECT);
        String endSessionEndpoint = metadata.getEndSessionEndpoint()
                .orElseThrow(() -> new IllegalStateException(
                        "OIDC provider metadata declares no end_session_endpoint — RP-initiated logout unavailable"));
        EndSessionFlow endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(postLogoutRedirectUri)));
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(endSessionFlow,
                sessionRecord -> {
                    // Best-effort by design: the authoritative logout is the local session destruction.
                },
                endSessionEndpoint, postLogoutRedirectUri, finalRedirect, LOGOUT_STATE_TTL);
        return new LogoutEndpoint(rpInitiatedLogout, sessionStore, sessionCookieCodec);
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
    private static String originOf(String redirectUri) {
        URI uri = URI.create(redirectUri);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalStateException("oidc.redirect_uri is not an absolute URI: " + redirectUri);
        }
        int port = uri.getPort();
        StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            origin.append(':').append(port);
        }
        return origin.toString();
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
