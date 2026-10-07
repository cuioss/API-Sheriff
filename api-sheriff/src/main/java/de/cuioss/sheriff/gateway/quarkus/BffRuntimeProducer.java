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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.auth.JwksTrustProfileResolver;
import de.cuioss.sheriff.gateway.auth.SignatureOnlyTokenVerifier;
import de.cuioss.sheriff.gateway.bff.client.ClientSigningKey;
import de.cuioss.sheriff.gateway.bff.cookie.CookieKeyMaterial;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.BoundTokenEndpointClient;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.PushedAuthorizationRequests;
import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.ScopedEngineFlows;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
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
import de.cuioss.sheriff.gateway.bff.reserved.ClientJwksEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.IdTokenClaimProjection;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.LogoutEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.StepUpEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
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
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.CallbackHandler;
import de.cuioss.sheriff.token.client.flow.IssValidator;
import de.cuioss.sheriff.token.client.flow.ParClient;
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
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
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
 * collaborator (login flow, CSRF defence, RFC 9470 step-up, refresh, and all reserved endpoints) is
 * identical, and cookie mode reaches the confidential-client engine exactly as server mode does.
 * On the active path the producer assembles the session binding, the cookie codecs, the CSRF
 * defence, the token-refresh / step-up coordinators, the
 * reserved-endpoint handlers, and the {@code require: session} stage-4 runtime, and binds the
 * {@code token-sheriff-client} engine seams, so the engine is reached at runtime.
 * <p>
 * <strong>Client authentication has two modes, selected once (ADR-0058).</strong> The mode is read
 * through {@link OidcConfig#usesClientSecret()}, and the one {@link ClientAuthentication} instance it
 * yields is shared by every pushed authorization request, the code exchange of a login and of a
 * widening, both refresh legs and RFC 7009 revocation.
 * <ul>
 *   <li><em>Key mode</em> — no {@code oidc.client_secret}, the default. The client-authentication key
 *       is resolved by {@link ClientSigningKey} from {@code oidc.client_authentication.key_file}; an
 *       absent block or an absent key selects a key generated at startup. The gateway authenticates
 *       with {@code private_key_jwt}, whose assertion names the resolved issuer as its audience. A
 *       key file the resolver refuses fails the boot with {@code CONFIG_INVALID}.</li>
 *   <li><em>Client-secret mode</em> — {@code oidc.client_secret} is configured. The gateway
 *       authenticates with {@code client_secret_basic}, resolves no client-authentication key, and
 *       reports {@code ApiSheriff-133} once per assembled runtime, so at every boot.</li>
 * </ul>
 * <p>
 * <strong>The client JWKS endpoint follows the same decision.</strong> The {@link ClientJwksEndpoint}
 * is assembled in both session modes and handed to the runtime in both client-authentication modes.
 * In key mode it publishes the public half of the client-authentication key — for a provided and for
 * a generated key alike — so the identity provider can verify the client assertion. In client-secret
 * mode there is no such key: the endpoint is the withheld form, which answers {@code 404} while the
 * path stays reserved. The authentication and the endpoint are yielded together by the one mode
 * decision, so the key the gateway signs with and the key it publishes cannot differ.
 * <p>
 * <strong>One DPoP sender constraint binds every access token (ADR-0058).</strong> The sender-constraint key
 * is resolved by {@link ClientSigningKey} from {@code oidc.sender_constraint.key_file}, with the same
 * refusal translation as the client-authentication key; an absent block or an absent key selects a
 * key generated at startup. The one {@link SenderConstraint} that key hands out is shared by every
 * flow — the base flow's code exchange, which serves a login callback and a widening callback alike,
 * every per-scope flow and every refresh grant, near-expiry and scope-driven — in both
 * client-authentication modes. A generated key lives for one process: it is replaced on every
 * restart and cannot be shared, so every instance behind one identity-provider client must be given
 * the same key files. A separate DPoP proof key is never published at the client JWKS endpoint.
 * <p>
 * <strong>A token response that is not bound to the proof key is refused.</strong> The runtime's one
 * token-endpoint client is a {@link BoundTokenEndpointClient} over the base back-channel
 * configuration and the key id of the sender-constraint key. It is the single instance handed to the
 * base flow and to {@link ScopedEngineFlows}, so the code exchange of a login and of a widening and
 * every refresh grant, near-expiry and scope-driven, read their token response through it; the
 * producer does not read the client-authentication mode for it. The refusal surfaces where the
 * response was requested: the callback answers {@code 400}, creates no session and merges nothing
 * into a live one, and a refresh ends the session and follows
 * {@code oidc.session.refresh.on_failure}. The check judges token responses only — a session whose
 * token is bound to an earlier key is never re-checked, so replacing the key does not by itself end a
 * session.
 * <p>
 * <strong>Per-request scope (ADR-0048).</strong> A leg requesting a per-request scope set goes through
 * {@link ScopedEngineFlows}, which drives a flow over a
 * {@link ClientConfiguration} built for exactly that set by
 * {@link #backChannelConfiguration(OidcConfig, List)}. The callback exchange, the RFC 9470 step-up and
 * revocation stay on the base configuration carrying {@code oidc.scopes}; the step-up coordinator is
 * handed that static set as the scope set its re-drive requests.
 * <p>
 * <strong>Scope enforcement on session routes.</strong> The session stage compares the session's
 * active scope set against the route's {@code neededScopes} on every request, and the producer binds
 * the two seams it obtains a missing scope through: the scope-driven refresh seam to the same
 * {@link TokenRefreshCoordinator} the near-expiry seam drives (or, with refresh switched off, to a
 * pass-through that hands the session back unchanged), and the
 * widening seam to the runtime's one {@link SessionWidening}, always starting with a silent attempt.
 * {@code oidc.step_up.path} is handed to the stage as the path its {@code 403} answer names; when the
 * key is absent the answer names no step-up URL.
 * <p>
 * <strong>Response mode.</strong> The authorization-URL seams are wired with the gateway-owned
 * {@link QueryResponseModeAuthorizationRequestBuilder}, so the flow is driven with
 * {@code response_mode=query} and the callback is a top-level GET the browser sends the
 * {@code SameSite=Lax} binding cookie on. See that class for the reasoning and for the accepted
 * code-in-the-URL tradeoff.
 * <p>
 * <strong>Every authorization request is pushed (ADR-0058).</strong> The URL the engine builds on
 * any of the three seams — the login leg, the session widening (silent and interactive attempt
 * alike) and the RFC 9470 step-up re-drive — is not sent to the browser. It is
 * the parameter source of a pushed authorization request (RFC 9126): the one
 * {@link PushedAuthorizationRequests} this producer builds pushes those parameters to the identity
 * provider and yields the redirect, which carries {@code client_id} and {@code request_uri} and
 * nothing else. A silent widening's {@code prompt=none} is therefore a parameter of the pushed
 * request, not of the redirect. The engine's {@code FlowContext} is kept unchanged on every seam. The
 * producer is the only class that constructs the engine's {@link ParClient}, over the base back-channel
 * configuration, and it never calls it. An identity provider that offers no pushed-authorization
 * endpoint, and a push that fails, refuse the login or the widening with {@code 502} before the
 * pending authorization is stored and before the binding cookie is set. In both
 * client-authentication modes.
 * <p>
 * <strong>Transparent refresh is switchable.</strong> {@code oidc.session.refresh.enabled} governs
 * the whole refresh path and is applied here, at the two points that path is constructed: the
 * {@code CodeExchange} seam retains the exchange's refresh token only when refresh is on, and the
 * {@link TokenRefreshCoordinator} is assembled only when refresh is on. With the switch off the
 * stage's refresh seams degrade to the unwired binding — session unchanged, no cookies — and no refresh
 * token is stored anywhere. An absent key (or an absent {@code refresh} block) means <em>on</em>.
 * With the switch on, each coordinator outcome reaches the stage as one of three dispositions: an
 * outcome carrying a session is mediated; a failed refresh — the session was destroyed, or in server
 * mode was found already terminated when the rotation was to be persisted —
 * clears the session cookie before the refresh-failure response; an unavailable refresh — the identity
 * provider was unreachable and the access token has expired, but the session is kept — answers the
 * refresh-failure response without clearing the cookie. That response is
 * {@code oidc.session.refresh.on_failure}, resolved here and handed to the stage:
 * {@code reauthenticate} (also when omitted) re-drives the login negotiation, {@code reject} answers
 * {@code 401} for every request. Where the gateway can name a refresh token still live at the identity
 * provider after a session ends on a refused redemption, on a persist failure or, in server mode, on a
 * session terminated while the refresh was in flight, it is revoked, best-effort, through the engine's
 * RFC 7009 {@link RevocationClient} built from the same back-channel configuration — dispatched on the
 * Quarkus-managed virtual-thread executor after the session-ended outcome has been published, so the
 * failing request never waits for the revocation endpoint. A fourth outcome carries no session and
 * ends none: the refresh leader could not resolve a session from the request's own cookie a second
 * time. It answers the refresh-failure response <em>without</em> a clearing cookie, because in server
 * mode that cookie value may just have been re-issued by a step-up while the session lives on.
 * <p>
 * <strong>Idle timeout, in both modes.</strong> {@code oidc.session.idle_timeout_seconds} is resolved
 * once through {@link OidcConfig.Session#effectiveIdleTimeoutSeconds()} — the resolution boot
 * validation shares — and handed to whichever binding is assembled: to the store behind the
 * server-mode binding, which keeps each session's last access beside its record, and to the cookie-mode
 * binding together with the activity-cookie codec built from the same key material, which keeps the
 * last access in a separate sealed cookie.
 * <p>
 * <strong>Periodic sweep, server mode only.</strong> A server-mode runtime registers one Vert.x
 * periodic timer that runs the store's {@code sweepExpired} every {@link #SESSION_SWEEP_INTERVAL}, so
 * an expired session leaves memory within that interval whether or not anything looks it up. The timer
 * fires on an event loop and only <em>dispatches</em>: the sweep itself runs on the Quarkus-managed
 * virtual-thread executor, so the store's monitor is never taken on an event loop. The timer is
 * cancelled when the application shuts down. Cookie mode holds no sessions and a bearer-only gateway
 * builds no runtime, so neither registers a timer.
 * <p>
 * <strong>Lazy discovery.</strong> The OIDC provider metadata is resolved through a memoized supplier
 * on first engine use, not at boot: a BFF gateway in either session mode therefore boots (and is
 * unit-testable) without a live IdP, and the discovery-dependent {@code end_session_endpoint} the
 * logout leg needs is materialized only when the first logout arrives.
 * <p>
 * <strong>The identity-provider back-channel carries a pinned TLS posture (ADR-0045).</strong> The
 * {@link ClientConfiguration} every engine seam dials the identity provider with — discovery, the
 * pushed authorization request, the authorization-code exchange, refresh and refresh-token
 * revocation — is the sixth TLS-terminating outbound leg, and it is bound
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
    /**
     * How often the server-mode session store is swept for expired sessions. Fixed: it bounds how long
     * an expired session can stay in memory unseen, and is unrelated to when a session expires — a
     * lookup evicts an expired session at once. Not configurable.
     */
    static final Duration SESSION_SWEEP_INTERVAL = Duration.ofSeconds(60);
    /** The value of {@link #sessionSweepTimer} while no sweep timer is registered; never a Vert.x timer id. */
    private static final long NO_SWEEP_TIMER = -1L;
    private static final Duration BACKCHANNEL_FRESHNESS_WINDOW = Duration.ofMinutes(2);
    private static final Duration LOGOUT_STATE_TTL = Duration.ofMinutes(1);
    private static final String DEFAULT_FINAL_REDIRECT = "/";
    /** The fallback return target when {@code oidc.login.default_return_url} is omitted. */
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
    /**
     * The signature-only verification seam the back-channel logout receiver is bound to, resolved
     * lazily for the same reason {@link #tokenValidator} is: a bearer-only gateway must never trigger
     * it. See {@link SignatureOnlyTokenVerifier} for why the logout token cannot ride the ID-token
     * validation path.
     */
    private final Instance<SignatureOnlyTokenVerifier> logoutTokenVerifier;
    private final JwksTrustProfileResolver trustProfileResolver;
    private final ExecutorService virtualThreadExecutor;
    private final GatewayJson gatewayJson;
    private final Vertx vertx;
    /**
     * The id of the periodic session-sweep timer, {@link #NO_SWEEP_TIMER} while none is registered —
     * always so in cookie mode and on a bearer-only gateway. Held so shutdown can cancel the timer.
     */
    private final AtomicLong sessionSweepTimer = new AtomicLong(NO_SWEEP_TIMER);
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
     * @param routeTable            the boot-built route table a return target's scope set is resolved
     *                              against
     * @param tokenValidator        a lazy handle to the gateway's shared offline validator, resolved
     *                              only on the active BFF path in either session mode (a bearer-only
     *                              gateway never triggers it)
     * @param logoutTokenVerifier   a lazy handle to the signature-only verifier the back-channel logout
     *                              receiver is bound to, resolved only on the active BFF path
     * @param trustProfileResolver  the single seam mapping a logical {@code egress_tls.oidc_tls_profile}
     *                              name to concrete trust anchors, consulted only on the active path and
     *                              only when a profile is named
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor a best-effort refresh-token
     *                              revocation is dispatched on, off the request path, and the periodic
     *                              session sweep runs on, off the event loop
     * @param gatewayJson           the serializer the runtime renders its gateway-authored JSON bodies
     *                              through
     * @param vertx                 the Quarkus-managed Vert.x instance the periodic session-sweep timer
     *                              is registered on, in server mode only
     */
    // Each parameter is one independently injected collaborator of the runtime assembly; a parameter
    // object would only regroup CDI injection points without removing one.
    @SuppressWarnings("java:S107")
    public BffRuntimeProducer(GatewayConfig gatewayConfig, RouteTable routeTable,
            @GatewayValidator Instance<TokenValidator> tokenValidator,
            Instance<SignatureOnlyTokenVerifier> logoutTokenVerifier,
            JwksTrustProfileResolver trustProfileResolver,
            @VirtualThreads ExecutorService virtualThreadExecutor, GatewayJson gatewayJson, Vertx vertx) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.routeTable = Objects.requireNonNull(routeTable, "routeTable");
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.logoutTokenVerifier = Objects.requireNonNull(logoutTokenVerifier, "logoutTokenVerifier");
        this.trustProfileResolver = Objects.requireNonNull(trustProfileResolver, "trustProfileResolver");
        this.virtualThreadExecutor = Objects.requireNonNull(virtualThreadExecutor, "virtualThreadExecutor");
        this.gatewayJson = Objects.requireNonNull(gatewayJson, "gatewayJson");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
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
        // both whether the refresh token is retained and whether the coordinator
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
        // request a per-request scope set: discovery, the transport of the pushed authorization
        // request, the callback code exchange, the RFC 9470 step-up and revocation. The legs that
        // request one go through ScopedEngineFlows below, whose factory is this same method — so every
        // scoped variant carries the identical pinned posture.
        reportBackChannelPosture();
        ClientConfiguration clientConfiguration = backChannelConfiguration(oidc, oidc.scopes());
        ClientCredential clientCredential = selectClientCredential(oidc, clientId, issuer);
        ClientAuthentication clientAuthentication = clientCredential.authentication();
        // ADR-0058: one sender-constraint key, and the one SenderConstraint it hands out, for the whole
        // runtime. The key id is the thumbprint every accepted access token must name in cnf.jkt.
        ClientSigningKey senderConstraintKey = resolveSenderConstraintKey(oidc);
        SenderConstraint senderConstraint = senderConstraintKey.senderConstraint();
        Supplier<ProviderMetadata> metadata = memoize(() -> new DiscoveryResolver(clientConfiguration).resolve());

        TokenValidator validator = tokenValidator.get();
        TokenValidationBridge tokenBridge = new TokenValidationBridge(validator);
        IdTokenValidationBridge idBridge = new IdTokenValidationBridge(validator);
        // The one token-endpoint client of the runtime. It refuses a token response that is not bound
        // to the proof key, and because the base flow, every per-scope flow and every refresh grant hold
        // this single instance, none of them can obtain a token that bypasses the check.
        TokenEndpointClient tokenEndpointClient = new BoundTokenEndpointClient(clientConfiguration,
                senderConstraintKey.keyId());
        // The gateway drives response_mode=query, NOT the engine's built-in form_post: the callback has
        // to be a top-level GET navigation so the SameSite=Lax browser-binding cookie is actually sent
        // on it (a Lax cookie is dropped on the cross-site POST a form_post callback performs, which
        // dead-ended every real-browser login on the "no binding cookie" 403 branch). One instance is
        // shared, so every engine seam that builds an authorization URL — login, widening and the
        // RFC 9470 step-up — carries the corrected mode. The IssValidator and the CallbackHandler are
        // the defaults the 4-arg AuthorizationCodeFlow constructor supplies on its own; the trailing
        // argument is the shared DPoP sender constraint, so the code exchange — of a login callback and
        // of a widening callback alike — presents a proof and its access token is bound to the proof key.
        AuthorizationRequestBuilder authorizationRequestBuilder = new QueryResponseModeAuthorizationRequestBuilder();
        AuthorizationCodeFlow authorizationCodeFlow = new AuthorizationCodeFlow(clientConfiguration,
                tokenEndpointClient, tokenBridge, idBridge, new IssValidator(), authorizationRequestBuilder,
                new CallbackHandler(), senderConstraint);
        // ADR-0048: the engine reads scope only from ClientConfiguration.getScopes(), so each
        // per-request scope set rides a configuration built for exactly that set.
        ScopedEngineFlows scopedFlows = new ScopedEngineFlows(scopes -> backChannelConfiguration(oidc, scopes),
                tokenEndpointClient, tokenBridge, idBridge, authorizationRequestBuilder, clientAuthentication,
                senderConstraint);

        BindingCookieCodec bindingCookieCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        // D7 seam: the whole BFF foundation binds SessionBinding, never the store directly. The mode
        // selects only which implementation is assembled — everything below is mode-independent.
        // The idle timeout is resolved through the one method boot validation shares, and reaches the
        // binding of either mode: the store behind the server binding, or the cookie binding itself.
        Duration idleTimeout = Duration.ofSeconds(session.effectiveIdleTimeoutSeconds());
        Clock clock = Clock.systemUTC();
        SessionBinding sessionBinding;
        if (session.isCookieMode()) {
            sessionBinding = cookieSessionBinding(session, cookieName, sessionTtl, idleTimeout);
        } else {
            SessionStore sessionStore = new InMemorySessionStore(maxSessions, idleTimeout);
            sessionBinding = new ServerSessionBinding(sessionStore, new SessionCookieCodec(cookieName, sessionTtl));
            // Server mode only: cookie mode holds no session to sweep.
            registerSessionSweep(sessionStore, clock);
        }
        PendingAuthorizationStore pendingStore = new PendingAuthorizationStore.InMemory(DEFAULT_MAX_PENDING);

        // Resolved once: the login flow, the login-initiation endpoint (through the flow), the session
        // widening, the step-up endpoint and the RFC 9470 step-up re-drive all fall back to the same
        // configured target.
        String defaultReturnUrl = defaultReturnUrl(oidc);

        // ADR-0058: every authorization request is pushed. The one ParClient rides the base back-channel
        // configuration, so the push carries the ADR-0045 hostname and trust posture and the engine's
        // timeouts; this producer builds it and never calls it — PushedAuthorizationRequests does.
        PushedAuthorizationRequests pushedRequests =
                new PushedAuthorizationRequests(new ParClient(clientConfiguration), clientAuthentication);

        // D5 login flow — the AuthorizationInitiation seam reaches the engine at runtime, requesting
        // exactly the scope set the caller names (a route's neededScopes, or oidc.scopes). The engine
        // renders the request, the request is pushed, and the redirect keeps the engine's FlowContext
        // while its URL carries client_id and request_uri only. A failed push propagates from here,
        // before LoginFlow stores the pending record or sets the binding cookie.
        LoginFlow loginFlow = new LoginFlow(
                scopes -> pushed(pushedRequests, metadata.get(), clientId,
                        scopedFlows.authorize(metadata.get(), scopes)),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl);

        // Session widening — the live-session sibling of the login flow, on the same pending store,
        // binding cookie and callback landing. Its authorization leg is built per call on
        // ScopedEngineFlows because the requested set S ∪ needed is IdP-derived. Exactly one instance
        // exists per runtime: the callback's interactive re-drive goes through it.
        // ADR-0058: a widening request is pushed exactly as a login request is — the silent attempt and
        // the interactive re-drive both pass this one seam, so neither can reach the browser as a
        // front-channel request. The silent attempt's prompt=none travels inside the pushed request.
        // A failed push propagates from here, before SessionWidening stores the pending record or sets
        // the binding cookie.
        SessionWidening sessionWidening = new SessionWidening(
                (scopes, silent) -> pushed(pushedRequests, metadata.get(), clientId,
                        scopedFlows.widen(metadata.get(), scopes, silent)),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl);

        // D2 callback — the CodeExchange seam reaches the engine's code exchange + token validation,
        // then hands the result to the refresh policy, which is where the exchange's refresh token is
        // retained or dropped. See applyRefreshPolicy for why the drop happens at the exchange rather than
        // at storage time.
        CallbackEndpoint.CodeExchange codeExchange = (context, params) -> applyRefreshPolicy(
                authorizationCodeFlow.exchange(metadata.get(), context, params, clientAuthentication),
                refreshEnabled);
        CallbackEndpoint callbackEndpoint = new CallbackEndpoint(codeExchange, pendingStore, bindingCookieCodec,
                sessionBinding, sessionTtl, sessionWidening);

        // D7/D9 transparent refresh — near-expiry decision + engine RefreshFlow, session persistence.
        // Assembled ONLY when refresh.enabled: with the switch off no coordinator exists and the
        // stage's refresh seam degrades to sessionUnchanged() — the unwired binding
        // SessionAuthenticationStage.TokenRefresh documents (session unchanged, no cookies).
        // The revocation client is built from the SAME back-channel configuration, so a refresh token
        // revoked after a refused redemption travels the pinned ADR-0045 posture like every other leg.
        // The refresh grant requests the set the coordinator names through ScopedEngineFlows — the
        // session's active scope set A near expiry, A plus the missing scopes on the scope-driven leg —
        // never the static oidc.scopes the base configuration carries.
        // ONE coordinator serves both stage seams — the near-expiry leg and the scope-driven leg — so the
        // two share its single-flight exclusion.
        RevocationClient revocationClient = new RevocationClient(clientConfiguration);
        TokenRefreshCoordinator refreshCoordinator = refreshEnabled
                ? new TokenRefreshCoordinator(refreshLeeway,
                sessionRecord -> tokenBridge.validateAccessToken(sessionRecord.accessToken())
                        .getExpirationDateTime().toInstant(),
                (refreshToken, scopes) -> scopedFlows.refresh(metadata.get(), refreshToken, scopes),
                sessionBinding,
                liveRefreshToken -> revokeRefreshToken(revocationClient, metadata.get(), liveRefreshToken,
                        clientAuthentication),
                virtualThreadExecutor,
                endedRefreshTokens(session))
                : null;
        SessionAuthenticationStage.TokenRefresh tokenRefresh = refreshCoordinator == null
                ? sessionUnchanged()
                : nearExpiryRefresh(refreshCoordinator);
        // With refresh switched off no grant can restore a scope, so the scope seam hands the session
        // back unchanged.
        SessionAuthenticationStage.ScopeRefresh scopeRefresh = refreshCoordinator == null
                ? scopesUnobtainable()
                : scopeRefresh(refreshCoordinator);

        // D4 session stage-4 runtime — binds both refresh seams, the login-redirect seam and the
        // widening seam. A session route enforces its needed scopes on every request: missing scopes
        // inside the granted set are refreshed, anything else is widened through the runtime's ONE
        // SessionWidening (silent attempt first) or refused 403 naming oidc.step_up.path.
        OidcConfig.StepUp stepUp = oidc.stepUp();
        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                tokenRefresh,
                scopeRefresh,
                (returnUrl, scopes, now) -> {
                    LoginFlow.LoginRedirect redirect = loginFlow.initiate(returnUrl, scopes, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                (live, returnUrl, neededScopes, now) -> {
                    LoginFlow.LoginRedirect redirect = sessionWidening.initiate(live, returnUrl, neededScopes,
                            PendingAuthorizationRecord.Widening.Attempt.SILENT, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                onFailure,
                stepUp == null ? null : stepUp.path(),
                clock);

        // D7 RFC 9470 step-up — instantiated with the engine StepUpHandler seam. No edge code drives the
        // step-up coordinator: the re-drive is assembled here and proven at unit level, but a running
        // gateway never reaches it.
        // Built with the SAME response-mode-corrected builder as the login leg: StepUpHandler#initiate
        // constructs its own authorization URL through an AuthorizationRequestBuilder, so leaving it on
        // the default builder would keep the step-up re-drive emitting response_mode=form_post and
        // reintroduce the dropped-binding-cookie failure on that leg alone.
        // The step-up request is built from the base configuration, so the re-drive records the static
        // oidc.scopes as its requested set (the PLAN-20 residual, ADR-0048). Like the login request it
        // is pushed: the re-drive location carries client_id and request_uri only.
        StepUpHandler stepUpHandler = new StepUpHandler(authorizationRequestBuilder);
        StepUpCoordinator stepUpCoordinator = new StepUpCoordinator(
                (sessionRecord, challenge, now) -> Optional.empty(),
                challenge -> pushed(pushedRequests, metadata.get(), clientId,
                        stepUpHandler.initiate(clientConfiguration, metadata.get(), challenge)),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl, oidc.scopes());

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

        // Step-up endpoint (oidc.step_up.path). It reuses the runtime's ONE SessionWidening (the instance
        // the callback re-drives through), never a second, and the same return-target resolver as the
        // login fold.
        // It is dispatched only when the registry reserved oidc.step_up.path, so wiring it
        // unconditionally costs nothing when the key is absent.
        StepUpEndpoint stepUpEndpoint = new StepUpEndpoint(sessionWidening, sessionBinding, returnTargetScopes,
                gatewayOrigin, defaultReturnUrl);

        // D2c back-channel logout — JWKS signature verification through the engine, then the claim residual.
        // The endpoint stays wired in both modes: it is gated on the binding's IdP-destruction
        // capability, so a stateless binding answers a deliberate 404 on the reserved path rather than
        // letting that path fall through to the proxy route table.
        // The verifier seam is SignatureOnlyTokenVerifier and deliberately NOT idBridge::validateRefreshedIdToken:
        // a back-channel logout token is not an ID token (no exp, sub optional, no azp), so the
        // ID-token pipeline rejected every spec-valid sid-only token before LogoutTokenValidator —
        // which already implements the full back-channel claim set — was ever reached (BFF-11).
        // LogoutTokenValidator stays the SOLE claim authority on this path.
        BackchannelLogoutReceiver backchannelReceiver = new BackchannelLogoutReceiver(
                logoutTokenVerifier.get()::verify,
                new LogoutTokenValidator(issuer, clientId, BACKCHANNEL_FRESHNESS_WINDOW),
                sessionBinding);
        BackchannelLogoutEndpoint backchannelLogoutEndpoint =
                new BackchannelLogoutEndpoint(backchannelReceiver, sessionBinding);

        // D5 RP-initiated logout — lazy so the discovery-sourced end_session_endpoint is resolved on
        // first logout, not at boot. buildLogoutEndpoint binds the token-revocation seam to a no-op, so
        // no revocation request is sent on logout; the authoritative logout is the local session
        // destruction the LogoutEndpoint performs.
        Supplier<LogoutEndpoint> logoutEndpoint = memoize(() -> buildLogoutEndpoint(oidc, gatewayOrigin,
                metadata.get(), sessionBinding));

        CsrfDefence csrfDefence = new CsrfDefence(trustedOrigins);

        // build(...) is reached for BOTH modes, so the diagnostic must name the mode that was actually
        // resolved — this is the line an operator greps to confirm which binding came up.
        LOGGER.debug("%s-mode BFF runtime assembled for origin %s (issuer %s)",
                session.isCookieMode() ? OidcConfig.Session.MODE_COOKIE : OidcConfig.Session.MODE_SERVER,
                gatewayOrigin, issuer);
        return new BffRuntime(sessionStage, csrfDefence, stepUpCoordinator, callbackEndpoint, logoutEndpoint,
                backchannelLogoutEndpoint, userInfoEndpoint, loginInitiationEndpoint, stepUpEndpoint,
                clientCredential.jwksEndpoint(), gatewayJson);
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
        ClientConfiguration.ClientConfigurationBuilder builder = ClientConfiguration.builder()
                .issuer(issuer).clientId(clientId)
                .scopes(scopes).redirectUri(redirectUri)
                // Called unconditionally, on the true path as well, so the posture never rests on the
                // library default (ADR-0022).
                .verifyHostname(verifyHostname);
        // The same predicate selects the authentication instance in selectClientCredential(...), so the
        // declared method and the credential actually presented cannot disagree. Key mode sets no
        // secret at all: the engine admits an absent secret for the key-based methods and refuses a
        // blank one.
        String clientSecret = oidc.clientSecret();
        if (oidc.usesClientSecret() && clientSecret != null) {
            builder.clientSecret(clientSecret).authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC);
        } else {
            builder.authMethod(ClientAuthMethod.PRIVATE_KEY_JWT);
        }
        if (tlsProfile != null) {
            builder.sslContext(trustProfileResolver.resolveEgressProfile(OIDC_TLS_PROFILE_KEY, tlsProfile));
        }
        return builder.build();
    }

    /**
     * Selects the confidential-client credential once, for the single build this {@link Singleton}
     * runtime performs: the authentication every authenticated back-channel leg presents, and the form
     * of the client JWKS endpoint that goes with it. The authentication instance is shared by the pushed
     * authorization request, the code exchange, the refresh grant and RFC 7009 revocation.
     * <p>
     * <strong>Client-secret mode</strong> — {@link OidcConfig#usesClientSecret()} is {@code true}. The
     * gateway keeps authenticating with {@code client_secret_basic} and reports {@code ApiSheriff-133},
     * whose template takes no parameter and therefore cannot carry the secret. No client-authentication
     * key is resolved in this mode, so none is generated and none is read — and none can be published:
     * the JWKS endpoint is the {@linkplain ClientJwksEndpoint#withheld() withheld} form.
     * <p>
     * <strong>Key mode</strong> — no secret is configured. The key is resolved from
     * {@code oidc.client_authentication.key_file}; an absent block or an absent key selects the
     * generated mode. The key hands out the {@code private_key_jwt} authentication itself, so this
     * producer never handles the private key, and its public JWK is what the JWKS endpoint
     * publishes. Both come from the one resolved key, so the published key is the signing key.
     * <p>
     * The mode is named by one {@code DEBUG} line, in key mode together with the key mode and the
     * algorithm — never key material and never the secret.
     *
     * @param oidc     the global {@code oidc} block, already cleared by the BFF-mode activation
     *                 predicate
     * @param clientId the resolved client id
     * @param issuer   the resolved issuer, used in key mode as the audience of the client assertion
     * @return the client authentication and the client JWKS endpoint of the selected mode
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the configured
     *                          client-authentication key file is refused
     */
    private static ClientCredential selectClientCredential(OidcConfig oidc, String clientId, String issuer) {
        String clientSecret = oidc.clientSecret();
        if (oidc.usesClientSecret() && clientSecret != null) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_CLIENT_SECRET_AUTHENTICATION);
            LOGGER.debug("BFF client authentication: client_secret_basic");
            return new ClientCredential(new ClientSecretBasicAuth(clientId, clientSecret),
                    ClientJwksEndpoint.withheld());
        }
        OidcConfig.ClientAuthenticationSettings settings = oidc.clientAuthentication();
        ClientSigningKey signingKey = resolveSigningKey(settings == null ? null : settings.keyFile(),
                ClientSigningKey.Purpose.CLIENT_AUTHENTICATION);
        LOGGER.debug("BFF client authentication: private_key_jwt (key mode=%s, algorithm=%s)",
                signingKey.mode().diagnosticName(), signingKey.algorithm());
        return new ClientCredential(signingKey.clientAuthentication(clientId, issuer),
                new ClientJwksEndpoint(signingKey.publicJwk()));
    }

    /**
     * What the one client-authentication decision yields. The two are held together because they must
     * agree: the endpoint publishes the key the authentication signs with, or withholds when the
     * authentication is a secret.
     *
     * @param authentication the client authentication every authenticated back-channel leg presents
     * @param jwksEndpoint   the client JWKS endpoint in the form that goes with that authentication
     */
    private record ClientCredential(ClientAuthentication authentication, ClientJwksEndpoint jwksEndpoint) {
    }

    /**
     * Replaces the URL of an engine-built login or widening redirect with its pushed form. The engine's
     * {@code FlowContext} — {@code state}, {@code nonce} and the PKCE verifier — is kept as it is:
     * it is what the callback is later checked against, and the pushed request carries exactly the
     * parameters derived from it.
     *
     * @param pushedRequests the runtime's pushed-authorization-request adapter
     * @param metadata       the resolved provider metadata
     * @param clientId       the resolved client id
     * @param redirect       the engine's authorization redirect
     * @return the same transaction context with the pushed-request redirect URL
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the request cannot be pushed
     */
    private static AuthorizationCodeFlow.AuthorizationRedirect pushed(PushedAuthorizationRequests pushedRequests,
            ProviderMetadata metadata, String clientId, AuthorizationCodeFlow.AuthorizationRedirect redirect) {
        return new AuthorizationCodeFlow.AuthorizationRedirect(
                pushedRequests.push(metadata, clientId, redirect.authorizationUrl()), redirect.context());
    }

    /**
     * Replaces the URL of an engine-built step-up request with its pushed form, keeping its
     * {@code FlowContext} — the step-up counterpart of the login overload.
     *
     * @param pushedRequests the runtime's pushed-authorization-request adapter
     * @param metadata       the resolved provider metadata
     * @param clientId       the resolved client id
     * @param request        the engine's step-up authorization request
     * @return the same transaction context with the pushed-request redirect URL
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the request cannot be pushed
     */
    private static StepUpHandler.StepUpRequest pushed(PushedAuthorizationRequests pushedRequests,
            ProviderMetadata metadata, String clientId, StepUpHandler.StepUpRequest request) {
        return new StepUpHandler.StepUpRequest(
                pushedRequests.push(metadata, clientId, request.authorizationUrl()), request.context());
    }

    /**
     * Resolves the sender-constraint key — the key the gateway signs its DPoP proofs with — from
     * {@code oidc.sender_constraint.key_file}. An absent block or an absent key selects the generated
     * mode. The key is resolved in both client-authentication modes: a configured client secret
     * changes how the gateway authenticates, not whether its access tokens are sender-constrained.
     * <p>
     * The key mode and the algorithm are named by one {@code DEBUG} line — never key material and
     * never the key id.
     *
     * @param oidc the global {@code oidc} block, already cleared by the BFF-mode activation predicate
     * @return the resolved sender-constraint key
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the configured
     *                          sender-constraint key file is refused
     */
    private static ClientSigningKey resolveSenderConstraintKey(OidcConfig oidc) {
        OidcConfig.SenderConstraintSettings settings = oidc.senderConstraint();
        ClientSigningKey signingKey = resolveSigningKey(settings == null ? null : settings.keyFile(),
                ClientSigningKey.Purpose.SENDER_CONSTRAINT);
        LOGGER.debug("BFF sender constraint: DPoP (key mode=%s, algorithm=%s)",
                signingKey.mode().diagnosticName(), signingKey.algorithm());
        return signingKey;
    }

    /**
     * Resolves one signing key of the confidential client and translates a refusal into the boot
     * failure every other invalid configuration raises. The text {@link ClientSigningKey} refuses a
     * key file with names the configuration field and the defect only — never the configured path
     * nor a line of the file — so it is carried over as it is.
     *
     * @param keyFile the configured {@code key_file} of the purpose, {@code null} to generate a key
     * @param purpose what the key signs
     * @return the resolved key
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the key file is refused
     */
    private static ClientSigningKey resolveSigningKey(@Nullable String keyFile, ClientSigningKey.Purpose purpose) {
        try {
            return ClientSigningKey.resolve(keyFile, purpose);
        } catch (IllegalStateException refused) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    Objects.requireNonNullElse(refused.getMessage(), purpose.configField() + " is refused"),
                    refused);
        }
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
     * Resolves {@code oidc.login.default_return_url} — the target fallen back to when no usable
     * same-origin return URL is supplied. An omitted key (or an omitted {@code login} block) resolves
     * to {@code /}. Boot
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
     * seam. {@code CURRENT}, {@code REFRESHED}, {@code DEFERRED} and {@code SCOPE_REFUSED} carry a
     * session and are mediated with whatever {@code Set-Cookie} the re-bind produced; {@code FAILED} —
     * the session was destroyed, or in server mode was found already terminated when the rotation was to
     * be persisted — ends the session so the stage clears the cookie; {@code UNAVAILABLE} —
     * the session was kept but its access token has expired — fails only this request, so the cookie
     * survives for the next attempt.
     * <p>
     * {@code SCOPE_REFUSED} reaches this leg only when the near-expiry request coalesced with a concurrent
     * scope-driven refresh of the same session; it always carries the kept session and any cookie the
     * shared re-bind produced, so mediating it is the only consistent mapping. It is safe because the
     * stage's scope comparison runs after mediation, so a mediated session is never relayed short of a
     * needed scope.
     * <p>
     * Extracted so the enabled and disabled bindings of the seam read as the two alternatives they
     * are, rather than one of them being a multi-statement lambda inline in the assembly.
     *
     * @param coordinator the assembled refresh coordinator
     * @return the stage seam driving {@code coordinator}'s near-expiry leg
     */
    static SessionAuthenticationStage.TokenRefresh nearExpiryRefresh(TokenRefreshCoordinator coordinator) {
        return (sessionRecord, cookieHeader, now) ->
                refreshResult(coordinator.refresh(sessionRecord, cookieHeader, now));
    }

    /**
     * Adapts the refresh coordinator's scope-driven leg to the stage's
     * {@link SessionAuthenticationStage.ScopeRefresh} seam, with the same outcome mapping as
     * {@link #nearExpiryRefresh}. On this leg a mediated session does not always carry the requested
     * set: {@code SCOPE_REFUSED} (a narrower grant, no refresh token, or a shared refresh that did not
     * request it) and {@code DEFERRED} (a refresh that is backing off — which is also how an identity
     * provider's outright {@code invalid_scope} refusal arrives, TokenSheriff#763) both hand the kept
     * session back. The stage compares the returned session against the route's needed scopes again,
     * so neither is ever relayed under-scoped.
     *
     * @param coordinator the assembled refresh coordinator
     * @return the stage seam driving {@code coordinator}'s scope-driven leg
     */
    static SessionAuthenticationStage.ScopeRefresh scopeRefresh(TokenRefreshCoordinator coordinator) {
        return (sessionRecord, cookieHeader, requestedScopes, now) ->
                refreshResult(coordinator.refreshForScopes(sessionRecord, cookieHeader, requestedScopes, now));
    }

    /**
     * Maps a coordinator outcome onto the stage's four dispositions. The switch has no {@code default}
     * arm on purpose: a later outcome kind fails compilation here instead of being mediated silently.
     * <p>
     * {@code NO_SESSION} is kept apart from {@code FAILED} all the way to the stage: {@code FAILED} means
     * the coordinator ended the session and the browser's cookie must go, {@code NO_SESSION} means it
     * found none for this request and ended nothing, so the browser's cookie — possibly one a step-up
     * has just re-issued — must stay.
     */
    private static SessionAuthenticationStage.RefreshResult refreshResult(
            TokenRefreshCoordinator.RefreshOutcome outcome) {
        return switch (outcome.kind()) {
            case CURRENT, REFRESHED, DEFERRED, SCOPE_REFUSED -> SessionAuthenticationStage.RefreshResult.mediate(
                    new SessionBinding.BoundSession(Objects.requireNonNull(outcome.session(), "session"),
                            outcome.setCookieHeaders()));
            case FAILED -> SessionAuthenticationStage.RefreshResult.sessionEnded();
            case UNAVAILABLE -> SessionAuthenticationStage.RefreshResult.requestFailed();
            case NO_SESSION -> SessionAuthenticationStage.RefreshResult.noSession();
        };
    }

    /**
     * Registers the periodic sweep of the server-mode session store.
     * <p>
     * The Vert.x timer fires on an event loop, where the store's monitor must never be taken — a sweep
     * over a large store, or one waiting behind a request, would stall every connection that loop
     * serves. The timer handler therefore only hands the sweep to the virtual-thread executor. A
     * dispatch the executor refuses — it is shutting down — is dropped: the timer is about to be
     * cancelled, and expiry does not depend on the sweep.
     *
     * @param sessionStore the server-mode store to sweep
     * @param clock        the clock a sweep reads its reference instant from
     */
    private void registerSessionSweep(SessionStore sessionStore, Clock clock) {
        long timerId = vertx.setPeriodic(SESSION_SWEEP_INTERVAL.toMillis(), ignored -> {
            try {
                virtualThreadExecutor.execute(() -> sweepSessions(sessionStore, clock));
            } catch (RejectedExecutionException shuttingDown) {
                LOGGER.debug(shuttingDown, "Session sweep not dispatched — the executor no longer accepts tasks");
            }
        });
        cancelTimer(sessionSweepTimer.getAndSet(timerId));
    }

    private static void sweepSessions(SessionStore sessionStore, Clock clock) {
        int swept = sessionStore.sweepExpired(clock.instant());
        LOGGER.debug("Periodic session sweep removed %s expired session(s)", swept);
    }

    /**
     * Cancels the periodic session sweep when the application shuts down. A no-op when no timer was
     * registered — cookie mode, a bearer-only gateway, or a runtime that was never produced.
     */
    @PreDestroy
    void cancelSessionSweep() {
        cancelTimer(sessionSweepTimer.getAndSet(NO_SWEEP_TIMER));
    }

    private void cancelTimer(long timerId) {
        if (timerId != NO_SWEEP_TIMER) {
            vertx.cancelTimer(timerId);
        }
    }

    /**
     * Selects the coordinator's ended-refresh-token marker by session mode. Cookie mode binds the bounded
     * in-memory marker, because {@code destroy} holds nothing server-side there and a retained sealed
     * cookie would otherwise drive a fresh refresh grant; server mode binds
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
     * resolved session verbatim, produces no {@code Set-Cookie} and never reaches the
     * engine's refresh grant.
     *
     * @return the unwired stage seam
     */
    static SessionAuthenticationStage.TokenRefresh sessionUnchanged() {
        return (sessionRecord, cookieHeader, now) ->
                SessionAuthenticationStage.RefreshResult.mediate(new SessionBinding.BoundSession(sessionRecord, List.of()));
    }

    /**
     * The disabled binding of the scope-driven seam — the alternative {@link #scopeRefresh} adapts to.
     * With {@code oidc.session.refresh.enabled=false} no coordinator exists and no refresh token is
     * retained, so no grant can restore a scope: the seam yields the session verbatim and never reaches
     * the engine.
     *
     * @return the unwired stage seam
     */
    static SessionAuthenticationStage.ScopeRefresh scopesUnobtainable() {
        return (sessionRecord, cookieHeader, requestedScopes, now) ->
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
     * <p>
     * The activity-cookie codec is built from the same key material, over a key derived for that cookie
     * alone, and handed to the binding together with the resolved idle timeout. It exists in cookie
     * mode only: server mode keeps the last access in its store.
     */
    private static SessionBinding cookieSessionBinding(OidcConfig.Session session, String cookieName,
            Duration sessionTtl, Duration idleTimeout) {
        CookieKeyMaterial keyMaterial = CookieKeyMaterial.resolve(session.encryptionKey());
        Integer declaredMaxCookieSize = session.maxCookieSize();
        int maxCookieSize = declaredMaxCookieSize == null
                ? SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET
                : declaredMaxCookieSize;
        LOGGER.debug("Cookie-mode key material resolved: mode=%s, maxCookieSize=%s",
                keyMaterial.mode().diagnosticName(), maxCookieSize);
        return new CookieSessionBinding(keyMaterial.codec(cookieName, sessionTtl, maxCookieSize),
                keyMaterial.identitySalt(), keyMaterial.activityCodec(cookieName), idleTimeout);
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
                    // A no-op binding: no revocation request is sent to the identity provider on
                    // logout. The authoritative logout is the local session destruction.
                },
                endSessionEndpoint, postLogoutRedirectUri, finalRedirect, LOGOUT_STATE_TTL);
        return new LogoutEndpoint(rpInitiatedLogout, sessionBinding);
    }

    /**
     * Derives the gateway's own origin (scheme + host + optional non-default port) from the configured
     * {@code redirect_uri}, used to same-origin-validate return URLs and as the default
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
