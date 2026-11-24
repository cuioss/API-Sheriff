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
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.gateway.auth.JwksTrustProfileResolver;
import de.cuioss.sheriff.gateway.auth.SignatureOnlyTokenVerifier;
import de.cuioss.sheriff.gateway.bff.client.ClientSigningKey;
import de.cuioss.sheriff.gateway.bff.cookie.CookieKeyMaterial;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.ScopedEngineFlows;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenReplayGuard;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.logout.VerifiedLogoutToken;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.InMemoryPendingAuthorizationStore;
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
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.StepUpEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.ForwardedTrust;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.bff.runtime.PendingQuotaKeys;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.validation.rule.OidcClientRules;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.CachingDiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.IssValidator;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
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
 * widening, both refresh legs and RFC 7009 revocation — on the refresh path and on RP-initiated
 * logout alike.
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
 * flow — the one flow's code exchange, which serves a login callback and a widening callback alike,
 * and every refresh grant, near-expiry and scope-driven — in both
 * client-authentication modes. A generated key lives for one process: it is replaced on every
 * restart and cannot be shared, so every instance behind one identity-provider client must be given
 * the same key files. A separate DPoP proof key is never published at the client JWKS endpoint.
 * <p>
 * <strong>The two signing keys are two keys (FAPI 2.0 section 6.8).</strong> In key mode the key that
 * signs the client assertion must not also sign the DPoP proofs. Boot validation refuses the two
 * {@code key_file} fields naming the same file; once both keys are resolved, this producer also
 * refuses a client-authentication key and a sender-constraint key with the same key id — the same key
 * provided in two files, a copy — with {@code CONFIG_INVALID}, naming both fields and no
 * key id. In client-secret mode there is no client-authentication key to compare.
 * <p>
 * <strong>A token response that is not bound to the proof key is refused by the engine.</strong> The
 * runtime's one token-endpoint client is the engine's own {@link TokenEndpointClient} over the base
 * back-channel configuration. Under the DPoP sender constraint every flow passes it, the engine refuses
 * a response whose {@code token_type} is not {@code DPoP} and a JWT access token whose {@code cnf.jkt}
 * is absent, malformed or another key's, as a {@code RedeemedResponseException}. It is the single
 * instance handed to the one authorization-code flow and the one refresh flow, so the code exchange of
 * a login and of a widening and every refresh grant, near-expiry and scope-driven, read their token
 * response through it; the producer does not read the client-authentication mode for it. The refusal surfaces
 * where the response was requested: the callback answers {@code 400}, creates no session and merges
 * nothing into a live one, and a refresh ends the session and follows
 * {@code oidc.session.refresh.on_failure}. The check judges token responses only — a session whose
 * token is bound to an earlier key is never re-checked, so replacing the key does not by itself end a
 * session.
 * <p>
 * <strong>Per-request scope (ADR-0083).</strong> The runtime builds one {@link ClientConfiguration},
 * carrying no scope, by {@link #backChannelConfiguration(OidcConfig)}, and one
 * {@link AuthorizationCodeFlow} and one {@code RefreshFlow} over it. Every leg that sends a scope goes
 * through {@link ScopedEngineFlows}, which names the scope set per call: the login and the widening
 * their requested set, the refresh grant the session's set, and the RFC 9470 step-up re-drive the
 * static {@code oidc.scopes}, which the step-up coordinator is handed as the scope set its re-drive
 * requests. The callback exchange and revocation send no scope.
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
 * <strong>A fair pending-authorization store.</strong> The runtime's one
 * {@link InMemoryPendingAuthorizationStore} keeps its global bound and caps the live records per
 * requester source, so one source's login flood cannot evict another user's live login. The source a
 * pending record is counted under is derived by one {@link PendingQuotaKeys}, built from the global
 * {@code forwarded.trusted_proxies} exactly as the edge's forward stage reads them, and handed to the
 * session stage and to the runtime, which puts it on every reserved-path request.
 * <p>
 * <strong>Response mode.</strong> Every authorization request — login, widening and the step-up
 * re-drive — is requested from the engine with {@code response_mode=query} through
 * {@link ScopedEngineFlows}, so the callback is a top-level GET the browser sends the
 * {@code SameSite=Lax} binding cookie on. See that class for the reasoning and for the accepted
 * code-in-the-URL tradeoff.
 * <p>
 * <strong>Every authorization request is pushed (ADR-0058).</strong> None of the three seams — the
 * login leg, the session widening (silent and interactive attempt alike) and the RFC 9470 step-up
 * re-drive — sends an authorization request through the browser. The login and the widening are
 * pushed by the engine itself ({@code AuthorizationCodeFlow.authorizePushed}, RFC 9126) through the
 * pushed-request client the one {@link AuthorizationCodeFlow} owns; the step-up re-drive, for which
 * the engine offers no pushed entry point, is pushed by {@link ScopedEngineFlows} through an engine
 * pushed-request client over the same configuration. The redirect carries {@code client_id} and
 * {@code request_uri} and nothing else, so a silent widening's {@code prompt=none} is a parameter of
 * the pushed request, not of the redirect. The engine's {@code FlowContext} is kept unchanged on every
 * seam. This producer constructs no pushed-request client. An identity provider that offers no
 * pushed-authorization endpoint, and a push that fails, refuse the login or the widening with
 * {@code 502} before the pending authorization is stored and before the binding cookie is set. In both
 * client-authentication modes.
 * <p>
 * <strong>Transparent refresh is switchable.</strong> {@code oidc.session.refresh.enabled} governs
 * the whole refresh path and is applied here, at the two points that path is constructed: the
 * {@code CodeExchange} seam retains the exchange's refresh token only when refresh is on, and the
 * {@link TokenRefreshCoordinator} is assembled only when refresh is on. With the switch off the
 * stage's refresh seams degrade to the unwired binding — session unchanged, no cookies — and no refresh
 * token is stored anywhere. An absent key (or an absent {@code refresh} block) means <em>on</em>.
 * With the switch on, each coordinator outcome reaches the stage as one of three dispositions: an
 * outcome carrying a session is mediated; a failed refresh — the refresh itself ended the session —
 * clears the session cookie before the refresh-failure response; an unavailable refresh — the identity
 * provider was unreachable and the access token has expired, but the session is kept — answers the
 * refresh-failure response without clearing the cookie. That response is
 * {@code oidc.session.refresh.on_failure}, resolved here and handed to the stage:
 * {@code reauthenticate} (also when omitted) re-drives the login negotiation, {@code reject} answers
 * {@code 401} for every request. Where the gateway can name a refresh token still live at the identity
 * provider after the refresh itself ended the session — on a refused redemption or a persist failure —
 * it is revoked, best-effort, through the engine's RFC 7009 {@link RevocationClient} built from the same
 * back-channel configuration. A refresh whose session was terminated while it was in flight (server
 * mode) revokes nothing: it lost a race to that destruction and abandons its rotated refresh token,
 * because a session that replaced the destroyed one may share the identity provider's user session.
 * The revocation is dispatched on the
 * Quarkus-managed virtual-thread executor after the session-ended outcome has been published, so the
 * failing request never waits for the revocation endpoint. RP-initiated logout revokes through that
 * same client independently of this switch, but only a refresh token the session holds: with the
 * switch off none is retained, so such a logout sends no revocation request. A fourth outcome
 * carries no session and ends none, the no-session outcome: the refresh leader could not resolve a
 * session from the request's own cookie a second time; its persist or its refusal was superseded by a
 * concurrent widening or step-up; or its persist or its refusal found the session already gone,
 * destroyed by a logout, a back-channel logout, a login callback, an expiry sweep or an eviction (server
 * mode). It answers the refresh-failure response <em>without</em> a clearing cookie, because in server
 * mode that cookie value may just have been re-issued by a step-up while the session lives on, or a
 * login callback may just have given the browser a replacement session's cookie.
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
 * <strong>Lazy, cached discovery (ADR-0079, ADR-0084).</strong> The OIDC provider metadata is resolved
 * on first engine use, not at boot, through the runtime's one engine {@link CachingDiscoveryResolver}
 * over the engine {@link DiscoveryResolver}: a BFF gateway in either session mode therefore boots (and
 * is unit-testable) without a live IdP. Every engine leg calls {@code resolve()} on that one instance.
 * A resolved document is served for {@link #DISCOVERY_TTL} and then refreshed on the next engine use,
 * so a changed provider endpoint is picked up without a restart. While refreshes fail the last document
 * is still served for {@link #DISCOVERY_GRACE_PERIOD} past its time to live; after that the resolution
 * fails closed and login and refresh stop until discovery succeeds again. After a failed attempt the
 * next one waits {@link #DISCOVERY_MIN_REFRESH_INTERVAL}. The values are fixed, not configuration keys,
 * and no jitter is applied. The discovery-dependent {@code end_session_endpoint} is read by the logout
 * leg per request, after the local session has been ended: a provider that publishes none, or metadata
 * that cannot be obtained, leaves the logout a local one that lands on {@code final_redirect}. The
 * resolver itself is not a bean — the token-sheriff-client extension refuses to boot an application
 * that injects one without its own {@code sheriff.client.discovery-cache.*} keys — so the producer
 * publishes a gateway-owned {@link GatewayReadinessCheck.DiscoveryState} view over it, and the readiness
 * probe reads the resolver's non-fetching state under {@code oidc_discovery}; boot never resolves, so a
 * fresh runtime reports {@code not-attempted}. A bearer-only gateway has no resolver.
 * <p>
 * <strong>The identity-provider back-channel carries a pinned TLS posture (ADR-0045).</strong> The
 * {@link ClientConfiguration} every engine seam dials the identity provider with — discovery, the
 * pushed authorization request, the authorization-code exchange, refresh and refresh-token
 * revocation on the refresh path and on RP-initiated logout — is the sixth TLS-terminating outbound
 * leg, and it is bound
 * to the global {@code egress_tls} block through its own peer keys: {@code oidc_verify_hostname} is
 * passed to the builder's {@code verifyHostname} on every build, the {@code true} path included, so
 * the leg's effect never depends on token-sheriff's own default ({@code doc/technical_aspects.adoc},
 * "Adopting Upstream Security-Default Changes"); {@code oidc_tls_profile},
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
    /**
     * How long a resolved OIDC discovery document is served before the next engine use refreshes it.
     * Fixed, not configurable (operator decision {@code discovery-ttl}, ADR-0084).
     */
    static final Duration DISCOVERY_TTL = Duration.ofHours(1);
    /**
     * How long past its time to live the last resolved discovery document is still served while every
     * refresh fails. Once it has passed, a resolution fails closed — 25 hours after the last successful
     * resolution. Fixed, not configurable (ADR-0084).
     */
    static final Duration DISCOVERY_GRACE_PERIOD = Duration.ofHours(24);
    /**
     * The minimum time between a failed discovery refresh and the next attempt, so an identity-provider
     * outage is not hammered by every request. Fixed, not configurable (ADR-0084).
     */
    static final Duration DISCOVERY_MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final Duration BACKCHANNEL_FRESHNESS_WINDOW = Duration.ofMinutes(2);
    /**
     * How many accepted back-channel logout tokens are remembered at once, each for the
     * {@link #BACKCHANNEL_FRESHNESS_WINDOW} it could be accepted in. Fixed, not configurable: the
     * bound is reached only when the identity provider delivers more distinct logout tokens than this
     * inside one window, and a token beyond it is refused rather than acted on unremembered.
     */
    static final int LOGOUT_TOKEN_REPLAY_CAPACITY = 10_000;
    /**
     * How many long-lived relays opened with a session are tracked at once, so each can be closed when
     * its session ends. Fixed, not configurable. A relay beyond it is refused before it is opened,
     * never opened untracked; the edge's own WebSocket relay budget is a separate, earlier limit.
     */
    static final int SESSION_RELAY_CAPACITY = 10_000;
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
    /**
     * The fixed detail of the boot refusal of one key provided for both client authentication and
     * DPoP. Names both fields, and neither a path nor a key id. Its reason and remedy are the ones the
     * boot-validation refusal of one file named for both keys carries, stated once in
     * {@link OidcClientRules#SIGNING_KEY_SEPARATION_REASON}.
     */
    private static final String SHARED_SIGNING_KEY_DETAIL =
            "oidc.client_authentication.key_file and oidc.sender_constraint.key_file hold the same key; "
                    + OidcClientRules.SIGNING_KEY_SEPARATION_REASON;

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
    /** Guards the one-time assembly of {@link #backChannel}. */
    private final Object backChannelLock = new Object();
    /**
     * The one back-channel configuration of the active runtime and the one caching discovery resolver
     * over it, assembled on first need by whichever of {@link #bffRuntime()} and
     * {@link #discoveryState()} runs first, so the runtime's engine legs and the readiness probe share
     * the same resolver and every leg dials with the same configuration. {@code null} until then.
     */
    private @Nullable BackChannel backChannel;

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
     * @param virtualThreadExecutor the Quarkus-managed virtual-thread executor a best-effort refresh-path
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
     * Produces the non-fetching view of the active runtime's provider-metadata state, which the
     * readiness probe reports under {@code oidc_discovery}.
     * <p>
     * The view is a gateway-owned type on purpose, and the engine {@link CachingDiscoveryResolver} is
     * deliberately <em>not</em> published as a bean. The token-sheriff-client Quarkus extension ships its
     * own producer of that type, configured from its {@code sheriff.client.discovery-cache.*} keys, and
     * refuses to start any application that declares an injection point of it — whatever the qualifier,
     * {@link jakarta.enterprise.inject.Instance} included — unless those keys are set. Publishing the
     * resolver would therefore either stop the boot or turn the fixed cache values (ADR-0084) into
     * extension configuration keys. The view exposes the resolver's state alone, never its last-failure
     * cause or last-success instant (ADR-0079).
     * <p>
     * {@link Singleton} because the view is fixed once the resolver is chosen. Producing it never
     * resolves the metadata and never builds the runtime. A bearer-only gateway has no resolver: callers
     * resolve this bean only after {@link #isBffMode(OidcConfig)} has confirmed an active runtime.
     *
     * @return the state view over the runtime's caching discovery resolver
     * @throws IllegalStateException when the gateway runs no BFF runtime
     */
    @Produces
    @Singleton
    public GatewayReadinessCheck.DiscoveryState discoveryState() {
        return GatewayReadinessCheck.DiscoveryState.of(discoveryResolver());
    }

    /**
     * The active runtime's one engine caching discovery resolver, the very instance every engine leg
     * resolves the provider metadata through and {@link #discoveryState()} reads. Not a bean, see
     * {@link #discoveryState()}. Calling it never resolves the metadata and never builds the runtime.
     *
     * @return the runtime's caching discovery resolver
     * @throws IllegalStateException when the gateway runs no BFF runtime
     */
    CachingDiscoveryResolver discoveryResolver() {
        OidcConfig oidc = gatewayConfig.oidc();
        if (oidc == null || !isBffMode(oidc)) {
            throw new IllegalStateException("No BFF-mode oidc block — the gateway resolves no OIDC provider metadata");
        }
        return backChannel(oidc).discovery();
    }

    /**
     * The runtime's one back-channel configuration and caching discovery resolver, assembled once. The
     * configuration refuses the relaxed-hostname / trust-profile collision itself, so a refused posture
     * leaves nothing assembled and every later call refuses again.
     */
    private BackChannel backChannel(OidcConfig oidc) {
        synchronized (backChannelLock) {
            BackChannel assembled = backChannel;
            if (assembled == null) {
                ClientConfiguration configuration = backChannelConfiguration(oidc);
                assembled = new BackChannel(configuration, new CachingDiscoveryResolver(
                        new DiscoveryResolver(configuration), DISCOVERY_TTL, DISCOVERY_GRACE_PERIOD,
                        DISCOVERY_MIN_REFRESH_INTERVAL, Duration.ZERO, Clock.systemUTC()));
                backChannel = assembled;
            }
            return assembled;
        }
    }

    /**
     * The one back-channel configuration of the active runtime and the caching discovery resolver over it.
     *
     * @param configuration the configuration every engine leg dials the identity provider with
     * @param discovery     the caching resolver every engine leg and the readiness probe share
     */
    private record BackChannel(ClientConfiguration configuration, CachingDiscoveryResolver discovery) {
    }

    /**
     * The mode-aware activation predicate: a BFF runtime is built for either recognised
     * {@code session.mode} — {@code server} or {@code cookie} — provided a {@code redirect_uri} is
     * configured. An unrecognised or absent mode leaves the gateway bearer-only. Shared with the
     * readiness probe, which reports the {@code oidc} and {@code oidc_discovery} data exactly when it holds.
     *
     * @param oidc the global {@code oidc} block
     * @return {@code true} when the gateway runs an active BFF runtime
     */
    static boolean isBffMode(OidcConfig oidc) {
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
        // Both bounds are resolved through the methods boot validation shares.
        int maxSessions = session.effectiveMaxSessions();
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

        // The one back-channel configuration of the runtime serves every leg: discovery, the pushed
        // authorization requests, the callback code exchange, refresh, the RFC 9470 step-up and
        // revocation. It carries no scope of its own: every leg that sends a scope names it per call
        // through ScopedEngineFlows (ADR-0083), the step-up re-drive included, so there is exactly one
        // configuration and one pinned posture.
        reportBackChannelPosture(oidc);
        BackChannel runtimeBackChannel = backChannel(oidc);
        ClientConfiguration clientConfiguration = runtimeBackChannel.configuration();
        ClientCredential clientCredential = selectClientCredential(oidc, clientId, issuer);
        ClientAuthentication clientAuthentication = clientCredential.authentication();
        // ADR-0058: one sender-constraint key, and the one SenderConstraint it hands out, for the whole
        // runtime. The key id is the thumbprint every accepted access token must name in cnf.jkt.
        ClientSigningKey senderConstraintKey = resolveSenderConstraintKey(oidc);
        // FAPI 2.0 section 6.8: the client assertion and the DPoP proofs are signed with two keys.
        refuseSharedSigningKey(clientCredential.signingKey(), senderConstraintKey);
        SenderConstraint senderConstraint = senderConstraintKey.senderConstraint();
        // Every engine leg resolves the provider metadata through the runtime's one caching resolver: a
        // fresh document is served from the cache, a stale one is refreshed on that use, and the readiness
        // probe reads the same resolver's status (ADR-0079).
        CachingDiscoveryResolver discovery = runtimeBackChannel.discovery();
        Supplier<ProviderMetadata> metadata = discovery::resolve;

        TokenValidator validator = tokenValidator.get();
        TokenValidationBridge tokenBridge = new TokenValidationBridge(validator);
        IdTokenValidationBridge idBridge = new IdTokenValidationBridge(validator);
        // The one token-endpoint client of the runtime. The engine's own client refuses a token response
        // that is not bound to the proof key of the DPoP sender constraint, and because the one flow and
        // the one refresh flow hold this single instance, no login, widening or refresh can obtain a token
        // that bypasses the check.
        TokenEndpointClient tokenEndpointClient = new TokenEndpointClient(clientConfiguration);
        // The one authorization-code flow of the runtime. It pushes every login and widening request
        // (ADR-0058) through the engine's own pushed-request client, built over the same configuration,
        // and redeems every callback code; the response mode, the prompt and the scope set are named per
        // call by ScopedEngineFlows. The trailing argument is the shared DPoP sender constraint, so the
        // code exchange — of a login callback and of a widening callback alike — presents a proof and its
        // access token is bound to the proof key.
        AuthorizationCodeFlow authorizationCodeFlow = new AuthorizationCodeFlow(clientConfiguration,
                tokenEndpointClient, tokenBridge, idBridge, new IssValidator(), senderConstraint);
        // ADR-0083: the engine takes the scope set per call, so one flow and one refresh flow serve every
        // scope set; ScopedEngineFlows names the canonical set on each call and translates every push
        // refusal into the 502 the callers handle.
        ScopedEngineFlows scopedFlows = new ScopedEngineFlows(clientConfiguration, authorizationCodeFlow,
                new RefreshFlow(clientConfiguration, tokenEndpointClient, tokenBridge, clientAuthentication,
                        senderConstraint),
                clientAuthentication);

        BindingCookieCodec bindingCookieCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        // D7 seam: the whole BFF foundation binds SessionBinding, never the store directly. The mode
        // selects only which implementation is assembled — everything below is mode-independent.
        // The idle timeout is resolved through the one method boot validation shares, and reaches the
        // binding of either mode: the store behind the server binding, or the cookie binding itself.
        Duration idleTimeout = Duration.ofSeconds(session.effectiveIdleTimeoutSeconds());
        Clock clock = Clock.systemUTC();
        // The long-lived relays opened with a session, in both modes: each is closed at its session's
        // absolute expiry. Only a server-mode store can additionally report a session that ended
        // earlier, so only there is the registry wired as the store's end listener.
        SessionRelayRegistry sessionRelays = new SessionRelayRegistry(SESSION_RELAY_CAPACITY, clock);
        Predicate<String> sessionHeld;
        SessionBinding sessionBinding;
        if (session.isCookieMode()) {
            sessionBinding = cookieSessionBinding(session, cookieName, sessionTtl, idleTimeout);
            // A stateless binding holds no session, so it has nothing to report as gone.
            sessionHeld = sessionId -> true;
        } else {
            InMemorySessionStore sessionStore = new InMemorySessionStore(maxSessions, idleTimeout,
                    session.effectiveMaxSessionsPerSubject(), sessionRelays);
            sessionHeld = sessionStore::isHeld;
            sessionBinding = new ServerSessionBinding(sessionStore, new SessionCookieCodec(cookieName, sessionTtl));
            // Server mode only: cookie mode holds no session to sweep.
            registerSessionSweep(sessionStore, clock);
        }
        PendingAuthorizationStore pendingStore = new InMemoryPendingAuthorizationStore(DEFAULT_MAX_PENDING);

        // Resolved once: the login flow, the login-initiation endpoint (through the flow), the session
        // widening, the step-up endpoint and the RFC 9470 step-up re-drive all fall back to the same
        // configured target.
        String defaultReturnUrl = defaultReturnUrl(oidc);

        // D5 login flow — the AuthorizationInitiation seam reaches the engine at runtime, requesting
        // exactly the scope set the caller names (a route's neededScopes, or oidc.scopes). The engine
        // pushes the request (ADR-0058), and the redirect keeps the engine's FlowContext while its URL
        // carries client_id and request_uri only. A refused push propagates from here as the 502
        // ScopedEngineFlows raises, before LoginFlow stores the pending record or sets the binding cookie.
        LoginFlow loginFlow = new LoginFlow(
                scopes -> scopedFlows.authorize(metadata.get(), scopes),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl, clock);

        // Session widening — the live-session sibling of the login flow, on the same pending store,
        // binding cookie and callback landing. Its requested set S ∪ needed is IdP-derived and is named
        // per call. Exactly one instance exists per runtime: the callback's interactive re-drive goes
        // through it.
        // ADR-0058: a widening request is pushed exactly as a login request is — the silent attempt and
        // the interactive re-drive both pass this one seam, so neither can reach the browser as a
        // front-channel request. The silent attempt's prompt=none travels inside the pushed request.
        // A refused push propagates from here, before SessionWidening stores the pending record or sets
        // the binding cookie.
        SessionWidening sessionWidening = new SessionWidening(
                (scopes, silent) -> scopedFlows.widen(metadata.get(), scopes, silent),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl, clock);

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
        // It is built whether or not refresh is on: the RP-initiated logout below revokes through this
        // one instance too, so the runtime holds exactly one revocation client.
        // The refresh grant requests the set the coordinator names through ScopedEngineFlows — the
        // session's active scope set A near expiry, A plus the missing scopes on the scope-driven leg —
        // never the static oidc.scopes the base configuration carries.
        // ONE coordinator serves both stage seams — the near-expiry leg and the scope-driven leg — so the
        // two share its single-flight exclusion.
        RevocationClient revocationClient = new RevocationClient(clientConfiguration);
        // The coordinator verifies every processed refresh through the engine's RefreshResultVerifier over
        // the runtime's one ID-token bridge before anything of it is persisted (OIDC Core 12.2, ADR-0084).
        TokenRefreshCoordinator refreshCoordinator = refreshEnabled
                ? new TokenRefreshCoordinator(refreshLeeway,
                sessionRecord -> tokenBridge.validateAccessToken(sessionRecord.accessToken())
                        .getExpirationDateTime().toInstant(),
                (refreshToken, scopes) -> scopedFlows.refresh(metadata.get(), refreshToken, scopes),
                idBridge,
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
        // One quota-key derivation for every producer of a pending record: the session stage hands it to
        // both navigation seams, and the runtime exposes it to the edge for the reserved paths.
        PendingQuotaKeys pendingQuotaKeys = pendingQuotaKeys();
        OidcConfig.StepUp stepUp = oidc.stepUp();
        SessionAuthenticationStage sessionStage = new SessionAuthenticationStage(sessionBinding,
                tokenRefresh,
                scopeRefresh,
                (returnUrl, scopes, quotaKey, now) -> {
                    LoginFlow.LoginRedirect redirect = loginFlow.initiate(returnUrl, scopes, quotaKey, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                (live, returnUrl, neededScopes, quotaKey, now) -> {
                    LoginFlow.LoginRedirect redirect = sessionWidening.initiate(live, returnUrl, neededScopes,
                            PendingAuthorizationRecord.Widening.Attempt.SILENT, quotaKey, now);
                    return new SessionAuthenticationStage.LoginChallenge(redirect.authorizationUrl(),
                            redirect.setCookieHeaders());
                },
                pendingQuotaKeys,
                onFailure,
                stepUp == null ? null : stepUp.path(),
                clock);

        // D7 RFC 9470 step-up — the engine StepUpHandler mints the step-up transaction inside
        // ScopedEngineFlows#stepUp. No edge code drives the step-up coordinator: the re-drive is assembled
        // here and proven at unit level, but a running gateway never reaches it.
        // The step-up request names the static oidc.scopes as its scope set and response_mode=query,
        // exactly like the login leg, so the callback stays a top-level GET and the re-drive records
        // oidc.scopes as its requested set (the PLAN-20 residual, ADR-0083). Like the login request it
        // is pushed — by ScopedEngineFlows, because the engine offers no pushed step-up entry point — so
        // the re-drive location carries client_id and request_uri only.
        StepUpCoordinator stepUpCoordinator = new StepUpCoordinator(
                (sessionRecord, challenge, now) -> Optional.empty(),
                challenge -> scopedFlows.stepUp(metadata.get(), challenge, oidc.scopes()),
                pendingStore, bindingCookieCodec, gatewayOrigin, defaultReturnUrl, oidc.scopes(), clock);

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
        // a back-channel logout token is not an ID token (sub optional, no azp), so the
        // ID-token pipeline rejected every spec-valid sid-only token before LogoutTokenValidator —
        // which already implements the full back-channel claim set — was ever reached (BFF-11).
        // LogoutTokenValidator stays the SOLE claim authority on this path; the verifier hands it the
        // token's typ header beside the claims, and the replay guard lets each token act once.
        SignatureOnlyTokenVerifier signatureVerifier = logoutTokenVerifier.get();
        BackchannelLogoutReceiver backchannelReceiver = new BackchannelLogoutReceiver(
                rawLogoutToken -> {
                    SignatureOnlyTokenVerifier.VerifiedToken verified = signatureVerifier.verify(rawLogoutToken);
                    return new VerifiedLogoutToken(verified.content(), verified.headerType());
                },
                new LogoutTokenValidator(issuer, clientId, BACKCHANNEL_FRESHNESS_WINDOW),
                sessionBinding,
                new LogoutTokenReplayGuard(LOGOUT_TOKEN_REPLAY_CAPACITY));
        BackchannelLogoutEndpoint backchannelLogoutEndpoint =
                new BackchannelLogoutEndpoint(backchannelReceiver, sessionBinding);

        // D5 RP-initiated logout. The discovery-sourced end_session_endpoint is handed over as the
        // caching resolver's supplier itself, never as a resolved value: the logout asks for it per
        // request and only after the local session is gone, so neither a provider without that
        // endpoint nor a failed discovery can stand between a user and the end of their session.
        // buildLogoutEndpoint binds the token-revocation seam to revokeOnLogout over the runtime's one
        // RevocationClient and one ClientAuthentication: once the LogoutEndpoint has destroyed the local
        // session, the session's refresh token is revoked at the identity provider (RFC 7009),
        // synchronously and before the end-session redirect is built. The local destruction stays the
        // authoritative logout; a failed revocation never turns the logout into an error.
        // The one session CSRF defence: the edge enforces it on unsafe-method session requests, and the
        // logout leg judges its provenance by a derivation of the same trusted origins that also trusts
        // the gateway's own origin — the origin its confirmation page posts back from — whatever
        // session.csrf.trusted_origins lists.
        CsrfDefence csrfDefence = new CsrfDefence(trustedOrigins);
        CsrfDefence logoutDefence = csrfDefence.withGatewayOrigin(gatewayOrigin);
        // The assembly reads no provider metadata (see buildLogoutEndpoint), so it is built here, once.
        LogoutEndpoint assembledLogoutEndpoint = buildLogoutEndpoint(oidc, gatewayOrigin, metadata, sessionBinding,
                revocationClient, clientAuthentication, logoutDefence);
        Supplier<LogoutEndpoint> logoutEndpoint = () -> assembledLogoutEndpoint;

        // build(...) is reached for BOTH modes, so the diagnostic must name the mode that was actually
        // resolved — this is the line an operator greps to confirm which binding came up.
        LOGGER.debug("%s-mode BFF runtime assembled for origin %s (issuer %s)",
                session.isCookieMode() ? OidcConfig.Session.MODE_COOKIE : OidcConfig.Session.MODE_SERVER,
                gatewayOrigin, issuer);
        return new BffRuntime(sessionStage, csrfDefence, stepUpCoordinator, callbackEndpoint, logoutEndpoint,
                backchannelLogoutEndpoint, userInfoEndpoint, loginInitiationEndpoint, stepUpEndpoint,
                clientCredential.jwksEndpoint(), gatewayJson, sessionRelays, sessionHeld, pendingQuotaKeys);
    }

    /**
     * Builds the derivation of the quota key a pending-authorization record is counted under, from the
     * global {@code forwarded.trusted_proxies}: the peer gate and the forwarded-header resolver come from
     * {@link ForwardedTrust}, the one derivation the edge builds its forward stage from as well
     * ({@code GatewayEdgeRoute}), so both judge forwarding headers under the same trusted-proxy set and
     * resolver configuration. They share the trust, not the peer: behind the gateway's own SNI front
     * listener the quota key judges the relayed client address while the forward stage judges the TCP
     * peer, the relay's loopback leg, so the two can name different clients for one request (see
     * {@link ForwardedTrust}). The resolver has a security-event counter of its own, so a malformed
     * forwarding header of a trusted proxy is not counted twice.
     *
     * @return the quota-key derivation the session stage and the reserved paths share
     */
    private PendingQuotaKeys pendingQuotaKeys() {
        ForwardedTrust forwardedTrust = ForwardedTrust.of(gatewayConfig);
        return new PendingQuotaKeys(forwardedTrust.peerGate(), forwardedTrust.resolver(new SecurityEventCounter()));
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
     * an upstream default change cannot silently move this gateway's posture
     * ({@code doc/technical_aspects.adoc}, "Adopting Upstream Security-Default Changes"). The trust
     * context is set only when a profile is named; otherwise the leg keeps the JVM default trust store.
     * <p>
     * <strong>One configuration for every scope set (ADR-0083).</strong> The configuration carries no
     * scope: {@link ScopedEngineFlows} names the scope set of every authorization request and refresh
     * grant per call, so the runtime builds this configuration once and every leg dials the identity
     * provider with the one posture it carries. The build is silent; the relaxed or replaced posture is
     * reported once per runtime by {@link #reportBackChannelPosture(OidcConfig)}.
     * <p>
     * <strong>The back-channel origin is transport, never identity.</strong> A declared
     * {@code oidc.back_channel_origin} is handed to the engine as its back-channel origin, so the
     * server-to-server calls to an endpoint the issuer advertises on its own origin — discovery, the
     * pushed authorization request, the authorization-code exchange, refresh and refresh-token
     * revocation, on the refresh path and on RP-initiated logout — dial the configured origin instead, while the issuer stays the identity every
     * exact-match check uses and the authorization endpoint the browser is sent to is untouched. Since
     * token-sheriff 0.9.8 the DPoP proof of such a re-targeted call names the URL actually dialled, not
     * the advertised one, which is what an identity provider that compares the proof's {@code htu} with
     * the URL it received (Keycloak) expects. An identity provider that instead compares {@code htu}
     * with a configured public URL rejects those proofs, and because the mapping is issuer-wide and
     * all-or-nothing, {@code back_channel_origin} must not be set for such a provider. Like the TLS
     * posture it is set on the one configuration every leg dials with, so no leg can fall back to
     * dialling the issuer. An absent key sets nothing: one authority for everything. The
     * cleartext posture follows the explicit opt-in alone: {@code allowInsecureHttp} is set only when
     * {@code oidc.back_channel_allow_insecure_http} is {@code true}, which boot validation admits only
     * together with an {@code http} origin. The engine applies that flag to the whole client, not just
     * the origin — the widening {@link #reportBackChannelPosture(OidcConfig)} reports. The engine's SSRF
     * egress guard is relaxed for exactly the hosts {@code oidc.allowed_egress_hosts} declares and for no
     * other: an absent list leaves the secure default in force, and no host is derived from the issuer or
     * the origin.
     *
     * @param oidc the global {@code oidc} block, already cleared by the BFF-mode activation predicate
     * @return the back-channel client configuration carrying no scope, the resolved hostname posture,
     *         the declared back-channel origin, cleartext opt-in and egress allowance and, when a
     *         profile is named, its trust anchors
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when
     *                          {@code egress_tls.oidc_verify_hostname} is {@code false} while
     *                          {@code egress_tls.oidc_tls_profile} is named, or when the named profile
     *                          cannot be resolved to trust anchors
     */
    ClientConfiguration backChannelConfiguration(OidcConfig oidc) {
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
                // No scope of its own: every leg names its scope set per call (ADR-0083).
                .scopes(List.of()).redirectUri(redirectUri)
                // Called unconditionally, on the true path as well, so the posture never rests on the
                // library default (doc/technical_aspects.adoc, "Adopting Upstream Security-Default Changes").
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
        String backChannelOrigin = oidc.backChannelOrigin();
        if (backChannelOrigin != null) {
            builder.backChannelOrigin(backChannelOrigin);
        }
        if (Boolean.TRUE.equals(oidc.backChannelAllowInsecureHttp())) {
            builder.allowInsecureHttp(true);
        }
        // The engine's host-address egress policy refuses an identity provider on a loopback or private
        // address by default; only the hosts the operator declared are exempted, host-exact, and nothing
        // is derived from the issuer or the back-channel origin.
        builder.allowedEgressHosts(oidc.allowedEgressHosts());
        return builder.build();
    }

    /**
     * Selects the confidential-client credential once, for the single build this {@link Singleton}
     * runtime performs: the authentication every authenticated back-channel leg presents, and the form
     * of the client JWKS endpoint that goes with it. The authentication instance is shared by the pushed
     * authorization request, the code exchange, the refresh grant and RFC 7009 revocation on the refresh
     * path and on RP-initiated logout.
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
                    ClientJwksEndpoint.withheld(), null);
        }
        OidcConfig.ClientAuthenticationSettings settings = oidc.clientAuthentication();
        ClientSigningKey signingKey = resolveSigningKey(settings == null ? null : settings.keyFile(),
                ClientSigningKey.Purpose.CLIENT_AUTHENTICATION);
        LOGGER.debug("BFF client authentication: private_key_jwt (key mode=%s, algorithm=%s)",
                signingKey.mode().diagnosticName(), signingKey.algorithm());
        return new ClientCredential(signingKey.clientAuthentication(clientId, issuer),
                new ClientJwksEndpoint(signingKey.publicJwk()), signingKey);
    }

    /**
     * What the one client-authentication decision yields. The two are held together because they must
     * agree: the endpoint publishes the key the authentication signs with, or withholds when the
     * authentication is a secret.
     *
     * @param authentication the client authentication every authenticated back-channel leg presents
     * @param jwksEndpoint   the client JWKS endpoint in the form that goes with that authentication
     * @param signingKey     the key the authentication signs with in key mode, {@code null} in
     *                       client-secret mode; held so it can be compared with the sender-constraint key
     */
    private record ClientCredential(ClientAuthentication authentication, ClientJwksEndpoint jwksEndpoint,
    @Nullable ClientSigningKey signingKey) {
    }

    /**
     * Refuses one key used for both purposes: in key mode, a client-authentication key and a
     * sender-constraint key with the same key id are the same key, which FAPI 2.0 section 6.8 forbids
     * — a key serves one purpose. Boot validation already refuses the two {@code key_file} fields
     * naming the same file; this check catches what that file comparison cannot see, the same key
     * provided in two different files (a copy). A generated key is fresh per boot and
     * never equals another key, so only two provided files can trip it. The message names both fields
     * and no key id.
     *
     * @param clientAuthenticationKey the resolved client-authentication key, {@code null} in
     *                                client-secret mode, which signs no client assertion
     * @param senderConstraintKey     the resolved sender-constraint key
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when both keys are the same key
     */
    private static void refuseSharedSigningKey(@Nullable ClientSigningKey clientAuthenticationKey,
            ClientSigningKey senderConstraintKey) {
        if (clientAuthenticationKey != null
                && clientAuthenticationKey.keyId().equals(senderConstraintKey.keyId())) {
            throw new GatewayException(EventType.CONFIG_INVALID, SHARED_SIGNING_KEY_DETAIL);
        }
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
     * resolves {@code false}, {@code ApiSheriff-126} when an {@code oidc_tls_profile} is in effect, and
     * {@link ConfigLogMessages.WARN#OIDC_BACK_CHANNEL_INSECURE_HTTP} when
     * {@code oidc.back_channel_allow_insecure_http} is {@code true} — the engine then admits cleartext
     * for the whole OIDC client, not just the back-channel origin. The collision of the first two is
     * refused first, so the pair is never reported as if it were in effect.
     *
     * @param oidc the global {@code oidc} block, already cleared by the BFF-mode activation predicate
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when
     *                          {@code egress_tls.oidc_verify_hostname} is {@code false} while
     *                          {@code egress_tls.oidc_tls_profile} is named
     */
    void reportBackChannelPosture(OidcConfig oidc) {
        boolean verifyHostname = egressTls.oidcVerifyHostname();
        String tlsProfile = egressTls.oidcTlsProfile();
        refuseRelaxedProfileCollision(verifyHostname, tlsProfile);
        if (!verifyHostname) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_HOSTNAME_VERIFICATION_DISABLED);
        }
        if (tlsProfile != null) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_TRUST_PROFILE_IN_EFFECT, tlsProfile);
        }
        if (Boolean.TRUE.equals(oidc.backChannelAllowInsecureHttp())) {
            LOGGER.warn(ConfigLogMessages.WARN.OIDC_BACK_CHANNEL_INSECURE_HTTP);
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
     * the refresh itself ended the session — ends the session so the stage clears the cookie;
     * {@code UNAVAILABLE} — the session was kept but its access token has expired — fails only this
     * request, so the cookie survives for the next attempt; {@code NO_SESSION} — the refresh ended
     * nothing, and in server mode the session may have been ended by something else while the refresh
     * was in flight — fails this request without clearing the cookie.
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
     * request it) and {@code DEFERRED} (a refresh that is backing off) both hand the kept session back.
     * An identity provider's outright {@code invalid_scope} refusal is one of the refreshes that back off:
     * the engine's {@code RefreshFlow.classify} reports it as its own {@code SCOPE_REFUSED} kind, and the
     * coordinator deliberately gives it the pre-redemption disposition — session kept, bounded back-off,
     * {@code ApiSheriff-127} — because the provider issued no grant and the same request would be refused
     * again, so it arrives here as {@code DEFERRED}, or as {@code UNAVAILABLE} once the access token has
     * expired. The stage compares the returned session against the route's needed scopes again, so no
     * mediated session is ever relayed under-scoped.
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
     * ended nothing — it found no session for this request, its write was superseded, or something else
     * had already ended the session — so the browser's cookie must stay: it may be one a step-up has just
     * re-issued, or one a login callback has just set for a replacement session.
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
     * Revokes one refresh token at the identity provider through the engine's RFC 7009 client, with
     * {@code token_type_hint=refresh_token}. It has two callers: the refresh coordinator's best-effort
     * revocation of a token still live after its session ended, and {@link #revokeOnLogout}, which
     * revokes the session's refresh token on RP-initiated logout. A provider that declares no
     * {@code revocation_endpoint} leaves nothing to call: the engine's
     * {@link RevocationClient#revokeIfSupported} sends no request and records the gap at {@code DEBUG},
     * and the gateway's own {@code DEBUG} line names it as well; the session has already been destroyed
     * locally either way. A transport failure or a non-success answer of the endpoint propagates to the
     * caller.
     *
     * @param revocationClient     the engine revocation client on the pinned back-channel posture
     * @param metadata             the resolved provider metadata
     * @param refreshToken         the refresh token still live at the provider
     * @param clientAuthentication the confidential-client authentication to present
     */
    static void revokeRefreshToken(RevocationClient revocationClient, ProviderMetadata metadata,
            String refreshToken, ClientAuthentication clientAuthentication) {
        if (!revocationClient.revokeIfSupported(metadata, refreshToken, REFRESH_TOKEN_TYPE_HINT,
                clientAuthentication)) {
            LOGGER.debug("Provider declares no revocation_endpoint — refresh token not revoked after the session ended");
        }
    }

    /**
     * The binding of {@link RpInitiatedLogout.TokenRevocation}: revokes the logged-out session's
     * refresh token at the identity provider (RFC 7009) through {@link #revokeRefreshToken}.
     * <p>
     * A session that holds no refresh token — {@code oidc.session.refresh.enabled=false}, or an
     * identity provider that granted none — leaves nothing to revoke: one {@code DEBUG} line is written
     * and no request is sent. A provider that declares no {@code revocation_endpoint} sends nothing
     * either (see {@link #revokeRefreshToken}). The access token is never revoked.
     * <p>
     * The provider metadata is read here, per logout, and not when the logout endpoint is assembled, so
     * a discovery failure surfaces to the caller as the {@link RuntimeException} the supplier raises,
     * exactly like a transport failure or a non-success answer of the revocation endpoint. The caller,
     * {@link RpInitiatedLogout#initiate}, reports such a failure and completes the logout anyway. No log
     * line written here carries the token value.
     *
     * @param revocationClient     the runtime's one engine revocation client, on the pinned back-channel
     *                             posture
     * @param metadata             the provider metadata through the runtime's caching resolver, resolved
     *                             here and not at assembly
     * @param clientAuthentication the runtime's one confidential-client authentication
     * @param session              the session being logged out
     */
    static void revokeOnLogout(RevocationClient revocationClient, Supplier<ProviderMetadata> metadata,
            ClientAuthentication clientAuthentication, SessionRecord session) {
        String refreshToken = session.refreshToken();
        if (refreshToken == null) {
            LOGGER.debug("RP-initiated logout for a session without a refresh token — nothing to revoke at the identity provider");
            return;
        }
        revokeRefreshToken(revocationClient, metadata.get(), refreshToken, clientAuthentication);
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
     * Per ADR-0072 the configuration carries a location and never key material: a declared
     * {@code oidc.session.key_file} names a file holding one line of base64 that decodes to the
     * 32-byte sealing key, and an omitted one selects a key generated at startup. The concrete
     * runtime choice is named by a startup diagnostic reporting the active key mode only, never the
     * path and never any key bytes. The generate-on-startup mode additionally raises the catalogued
     * INFO {@code COOKIE_KEY_GENERATED} from {@link CookieKeyMaterial}, because its
     * sessions-die-on-restart consequence is operationally notable rather than merely diagnostic.
     * <p>
     * A refused key file fails the boot the way a refused signing-key file does: the refusal text
     * {@link CookieKeyMaterial} raises names the field and the defect — never the configured path
     * nor the file content — and is carried over as it is into a {@link GatewayException} with
     * {@link EventType#CONFIG_INVALID}.
     * <p>
     * The codec's seal-time size budget comes from {@code oidc.session.max_cookie_size} — the single
     * declared number that also drives the edge's pre-route {@code Cookie} header-value cap, so the
     * two ends of the round trip cannot drift apart.
     * <p>
     * The activity-cookie codec is built from the same key material, over a key derived for that cookie
     * alone, and handed to the binding together with the resolved idle timeout. It exists in cookie
     * mode only: server mode keeps the last access in its store.
     *
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the sealing key file is
     *                          refused
     */
    private static SessionBinding cookieSessionBinding(OidcConfig.Session session, String cookieName,
            Duration sessionTtl, Duration idleTimeout) {
        CookieKeyMaterial keyMaterial = resolveSealingKey(session.keyFile());
        Integer declaredMaxCookieSize = session.maxCookieSize();
        int maxCookieSize = declaredMaxCookieSize == null
                ? SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET
                : declaredMaxCookieSize;
        LOGGER.debug("Cookie-mode key material resolved: mode=%s, maxCookieSize=%s",
                keyMaterial.mode().diagnosticName(), maxCookieSize);
        return new CookieSessionBinding(keyMaterial.codec(cookieName, sessionTtl, maxCookieSize),
                keyMaterial.identitySalt(), keyMaterial.activityCodec(cookieName), idleTimeout);
    }

    /**
     * Resolves the cookie sealing key and translates a refusal into the boot failure every other
     * invalid configuration raises, exactly as {@link #resolveSigningKey} does for a signing key.
     *
     * @param keyFile the configured {@code oidc.session.key_file}, {@code null} to generate a key
     * @return the resolved key material
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the key file is refused
     */
    private static CookieKeyMaterial resolveSealingKey(@Nullable String keyFile) {
        try {
            return CookieKeyMaterial.resolve(keyFile);
        } catch (IllegalStateException refused) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    Objects.requireNonNullElse(refused.getMessage(), "oidc.session.key_file is refused"), refused);
        }
    }

    /**
     * Assembles the RP-initiated logout endpoint.
     * <p>
     * <strong>The assembly reads no provider metadata.</strong> The discovery-sourced
     * {@code end_session_endpoint} is bound as a seam the logout consults on each request, after it has
     * ended the local session. A provider that publishes no such endpoint, and discovery that cannot be
     * completed, therefore leave the logout endpoint available: the session is ended, the cookies are
     * cleared, and the browser lands on {@code final_redirect}.
     * <p>
     * <strong>The logout revokes the session's refresh token.</strong> The token-revocation seam is
     * bound to {@link #revokeOnLogout} over the runtime's one {@link RevocationClient} and one
     * {@link ClientAuthentication}, so after the local session is destroyed and before the end-session
     * redirect is built, the session's refresh token is revoked at the identity provider (RFC 7009),
     * synchronously. The provider metadata that revocation needs is likewise read per request. The
     * access token is not revoked.
     * <p>
     * <strong>The logout acts only on a top-level document navigation from a trusted origin.</strong> The
     * endpoint judges it by the
     * logout leg's own {@link CsrfDefence}: the runtime's session defence with the gateway's origin added
     * ({@link CsrfDefence#withGatewayOrigin(String)}), so the confirmation page the leg answers — served
     * under that origin — can post back even when {@code session.csrf.trusted_origins} names only other
     * origins. A request without the proof is answered with that confirmation page, whose form posts to
     * the logout path the {@link ReservedPathRegistry} reserves — resolved through the registry's own
     * derivation, so the form posts exactly to the path the edge dispatches.
     *
     * @param oidc                 the global {@code oidc} block
     * @param gatewayOrigin        the gateway's own origin, the base of the default return leg
     * @param metadata             the provider metadata through the runtime's caching resolver, resolved
     *                             by the seams and not here
     * @param sessionBinding       the session binding the logout resolves, destroys and clears through
     * @param revocationClient     the runtime's one engine revocation client
     * @param clientAuthentication the runtime's one confidential-client authentication
     * @param csrfDefence          the logout leg's CSRF defence: the runtime's one session defence with the
     *                             gateway's own origin trusted for the logout navigation
     *                             ({@link CsrfDefence#withGatewayOrigin(String)}), derived by the caller
     * @return the logout endpoint
     */
    // java:S107 suppressed deliberately: every parameter is a runtime-wide collaborator build(...) creates
    // once — the session binding and the one revocation client and client authentication, shared with the
    // other legs as those very instances, never second copies — or, for the CSRF defence, the logout leg's
    // own derivation of the one session defence, which adds only the gateway's origin for the logout
    // navigation. A parameter object would exist only for this single private call and would hide which of
    // the runtime's collaborators the logout leg depends on.
    @SuppressWarnings("java:S107")
    private static LogoutEndpoint buildLogoutEndpoint(OidcConfig oidc, String gatewayOrigin,
            Supplier<ProviderMetadata> metadata, SessionBinding sessionBinding, RevocationClient revocationClient,
            ClientAuthentication clientAuthentication, CsrfDefence csrfDefence) {
        OidcConfig.Logout logout = oidc.logout();
        String declaredPostLogoutRedirectUri = logout == null ? null : logout.postLogoutRedirectUri();
        String postLogoutRedirectUri = declaredPostLogoutRedirectUri == null
                ? gatewayOrigin + "/"
                : declaredPostLogoutRedirectUri;
        String declaredFinalRedirect = logout == null ? null : logout.finalRedirect();
        String finalRedirect = declaredFinalRedirect == null
                ? DEFAULT_FINAL_REDIRECT
                : declaredFinalRedirect;
        EndSessionFlow endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(postLogoutRedirectUri)));
        RpInitiatedLogout rpInitiatedLogout = new RpInitiatedLogout(endSessionFlow,
                sessionRecord -> revokeOnLogout(revocationClient, metadata, clientAuthentication, sessionRecord),
                () -> metadata.get().getEndSessionEndpoint(), postLogoutRedirectUri, finalRedirect,
                LOGOUT_STATE_TTL);
        return new LogoutEndpoint(rpInitiatedLogout, sessionBinding, csrfDefence, reservedLogoutPath(oidc));
    }

    /**
     * The path the reserved-path registry dispatches to the logout leg, through the registry's own
     * derivation, so the confirmation form posts exactly where the edge answers. {@code null} when the
     * {@code oidc} block reserves no logout path — the logout leg is then never dispatched.
     *
     * @param oidc the global {@code oidc} block
     * @return the reserved logout path, {@code null} when none is reserved
     */
    static @Nullable String reservedLogoutPath(OidcConfig oidc) {
        return ReservedPathRegistry.reservedPaths(oidc).stream()
                .filter(path -> ReservedPathRegistry.reservedKind(oidc, path)
                        .filter(ReservedPathRegistry.ReservedEndpoint.LOGOUT::equals).isPresent())
                .findFirst()
                .orElse(null);
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
}
