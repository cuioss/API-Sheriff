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
package de.cuioss.sheriff.gateway.bff.refresh;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.RefreshFailureClassification;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.RefreshRedemption;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * Transparent, single-flight token refresh for a {@code require: session} route (D7/D9).
 * <p>
 * When the mediated access token is within {@code leeway} of expiry the coordinator refreshes it
 * <strong>through the engine</strong> ({@code token-sheriff-client}'s {@code RefreshFlow}, reached
 * via the {@link RefreshExchange} seam) and persists the rotated token material through the
 * mode-neutral {@link SessionBinding} seam — a store write in server mode, made only while the store
 * still holds the session exactly as the refresh resolved it, and a re-sealed cookie in a stateless
 * mode — so the browser never sees a
 * token and never drives a leg. The gateway re-implements
 * <strong>no</strong> OAuth leg: the engine owns the refresh grant and refresh-token rotation.
 * <p>
 * <strong>The refresh keeps the session's scope.</strong> The near-expiry grant requests the session's
 * active scope set {@code A} ({@link SessionRecord#activeScopes()}), never the static {@code oidc.scopes},
 * so a session that logged in on a route needing extra scopes keeps them across refreshes. The rotated
 * session's {@code A} becomes the response's {@code scope} — which may narrow it — or the requested set
 * when the response omits {@code scope}. The near-expiry leg carries the session's granted scope set
 * {@code S} ({@link SessionRecord#grantedScopes()}) over unchanged, and no refresh ever adds to it.
 * <p>
 * <strong>The scope-driven leg.</strong> {@link #refreshForScopes} is the second trigger of the same
 * engine exchange: a session route whose needed scopes are missing from {@code A} but inside {@code S}
 * asks for exactly the set it is given (the caller passes {@code A ∪ missing}), whatever the access
 * token's remaining lifetime. It shares the near-expiry leg's single-flight exclusion, its back-off, its
 * ended-token check and every refusal disposition below. It adds one outcome,
 * {@link RefreshOutcome.Kind#SCOPE_REFUSED SCOPE_REFUSED}: the session is kept but does not carry the
 * requested set — most notably because the exchange succeeded and the rotated session was persisted,
 * but the granted {@code scope} still lacks a requested member. No back-off is recorded and nothing is
 * logged at {@code WARN}.
 * <p>
 * <strong>A processed scope refresh keeps {@code S} truthful.</strong> When the identity provider
 * processes the scope-driven grant and returns a {@code scope} lacking a requested member, every such
 * member is removed from the rotated session's {@code S} before it is persisted: the provider has just
 * shown it no longer grants that scope to this session. A response that omits
 * {@code scope} refuses nothing (RFC 6749 §5.1) and leaves {@code S} as it was, as does every outcome
 * in which the provider did not process the grant.
 * <p>
 * An identity provider that refuses the requested scope outright ({@code invalid_scope}) <em>is</em>
 * told apart: {@code RefreshFlow.classify} reports that answer as its own {@code SCOPE_REFUSED} kind.
 * The gateway deliberately gives it the pre-redemption disposition below — the session is kept, the
 * next attempt waits out the bounded back-off, and {@code ApiSheriff-127} is recorded — because the
 * provider issued no grant and rotated nothing, and the same request would be refused again, so an
 * immediate retry would only hammer the provider. That engine kind is therefore <em>not</em> this
 * coordinator's {@link RefreshOutcome.Kind#SCOPE_REFUSED SCOPE_REFUSED} outcome: the caller receives
 * {@link RefreshOutcome.Kind#DEFERRED DEFERRED} or {@link RefreshOutcome.Kind#UNAVAILABLE UNAVAILABLE}.
 * The caller never relays a session short of its needed scopes, whichever outcome it receives.
 * <p>
 * <strong>A refused refresh is disposed by what the identity provider did to the presented refresh
 * token.</strong> Only the exchange itself is classified, through the engine's
 * {@code RefreshFlow.classify}, and every kind maps to one disposition:
 * <ul>
 *   <li><strong>{@code PRE_REDEMPTION}</strong> — the provider never processed the grant (a connection
 *       or DNS failure, a {@code 5xx}, a {@code 4xx} not attributed to the credential). The presented
 *       token is still valid, so the session is <em>kept</em>, nothing is revoked, and the session's
 *       next attempt waits out a fixed five-second back-off. The outcome is
 *       {@link RefreshOutcome.Kind#DEFERRED DEFERRED} while the access token has not expired, otherwise
 *       {@link RefreshOutcome.Kind#UNAVAILABLE UNAVAILABLE}; the record is {@code ApiSheriff-127}.</li>
 *   <li><strong>{@code SCOPE_REFUSED}</strong> — the provider answered {@code invalid_scope}. It issued
 *       no grant, so the presented token is still valid, and the gateway deliberately disposes it exactly
 *       as {@code PRE_REDEMPTION}: session kept, nothing revoked, the same back-off, the same outcomes and
 *       {@code ApiSheriff-127}.</li>
 *   <li><strong>{@code CREDENTIAL_REJECTED}</strong> — the provider answered {@code invalid_grant},
 *       which is also how a provider enforcing strict rotation rejects a replayed refresh token. The
 *       session is destroyed, nothing is revoked (nothing was redeemed), and the outcome is
 *       {@link RefreshOutcome.Kind#FAILED FAILED} with reason {@code credential-rejected} — unless the
 *       session's record was replaced or the session already ended meanwhile (see below).</li>
 *   <li><strong>{@code REDEEMED}</strong> — the provider redeemed the grant and the gateway refused its
 *       response. The session is destroyed and the one live refresh token the gateway can name is
 *       revoked: the successor when rotated, the presented token when not rotated, none when rotation
 *       is unknown. The outcome is {@link RefreshOutcome.Kind#FAILED FAILED} with reason
 *       {@code redeemed-response-refused} — unless the session's record was replaced or the session
 *       already ended meanwhile, in which case nothing is revoked (see below).</li>
 *   <li><strong>Persist failure</strong> — the exchange succeeded but the binding could not hold the
 *       rotated session. The presented token is already redeemed, so the session is destroyed and the
 *       refresh token the exchange returned is revoked. The outcome is
 *       {@link RefreshOutcome.Kind#FAILED FAILED} with reason {@code persist-failure}.</li>
 *   <li><strong>Session terminated at the persist</strong> — the exchange succeeded, but the binding
 *       reported the session gone when the rotated session was written: a logout, a validated
 *       back-channel logout, a login callback that replaced the session in the same browser, an expiry
 *       sweep or an eviction destroyed it while the refresh was in flight. The rotated session is
 *       <em>not</em> written, so the session stays terminated. This refresh did not end the session, so it
 *       ends nothing either: nothing is destroyed, nothing is marked ended, no {@code ApiSheriff-111} is
 *       recorded, the back-off entry is dropped and the record is a {@code DEBUG} line carrying neither
 *       token nor session id. The outcome is {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION}, so the
 *       caller sends no clearing cookie: a clearing response from this refresh could reach the browser
 *       after a login callback's own response and delete the replacement session's new cookie. A request
 *       presenting the cookie of a session that is really gone is answered by the ordinary no-session
 *       path, so a genuine logout that raced the refresh still leaves the browser unauthenticated.
 *       Nothing is revoked: the refresh lost a race to a session destruction, so the refresh token the
 *       exchange returned is abandoned — the presented one is already redeemed. A login callback's
 *       replacement session may share the identity provider's user session, and a grant-level revocation
 *       could end it. That leaves a residual risk on a genuine logout too: RP-initiated logout revokes
 *       the refresh token of the record it destroys, or the resolved record's when the session was
 *       already gone at the destruction. This residual arises when the logout destroys the session
 *       before the refresh persists: the destroyed record still holds the presented token the rotation
 *       has spent, and the rotated successor is never stored, so it cannot be revoked. Whether the
 *       provider also invalidates the rotated successor is the provider's policy (RFC 7009 allows it
 *       but does not require it). The abandoned token is held by nobody — never sent to the browser, no
 *       copy kept — and may stay valid at the provider until its refresh-token lifetime ends, the same
 *       accepted residual as the superseded disposition below. Only a binding that holds
 *       server-side state can report a session gone, so this disposition occurs in server mode only: a
 *       stateless binding cannot observe a logout and re-seals the rotated session.</li>
 *   <li><strong>Superseded at the persist</strong> — the exchange succeeded, but the binding reported
 *       that the session's record was no longer the one the leader resolved: a widening or a step-up
 *       replaced it while the refresh was in flight. The leader passes the record it resolved as the
 *       basis of the persist, and a server-state binding compares and writes in one atomic step, so the
 *       rotated session is <em>not</em> written and the widening's scopes, refresh token, {@code acr} and
 *       {@code auth_time} stay as it left them. The session lives on: nothing is destroyed, nothing is
 *       marked ended, the back-off entry is dropped and the record is a {@code DEBUG} line carrying
 *       neither token nor session id. The outcome is {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION},
 *       and nothing is revoked: the refresh token the exchange returned is abandoned, because the
 *       widening's grant shares its client session at the identity provider and a grant-level
 *       revocation could end the very session this disposition keeps; it lapses under the provider's
 *       refresh-token lifetime. Like a terminated session this occurs in server mode only: a stateless
 *       binding holds no record to compare against and re-seals the rotated session, so whichever
 *       cookie the browser stores last wins — the stated residual of that mode.</li>
 * </ul>
 * Both legs share these dispositions, and so does every request that coalesced with the refresh.
 * <strong>A refusal ends only the session it read.</strong> {@code CREDENTIAL_REJECTED} and
 * {@code REDEEMED} destroy the session through {@link SessionBinding#destroyIfCurrent} against the record
 * the leader resolved. When a widening or a step-up replaced that record while the exchange was in
 * flight — such a write does not pass this coordinator's single-flight — a server-state binding reports
 * it superseded, and the refusal takes the superseded disposition instead: nothing is destroyed,
 * marked or revoked, a {@code DEBUG} line records it and the outcome is
 * {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION}, because the replacing write may share the identity
 * provider's user session and a grant-level revocation could end it. When a logout, a back-channel
 * logout, a login callback that replaced the session, an expiry sweep or an eviction already destroyed
 * the record, the binding reports it gone, and the refusal ends nothing either: it lost a race to that
 * destruction and takes the same disposition as a refresh whose persist finds the session gone, so
 * nothing is destroyed, marked or revoked, a {@code DEBUG} line records it and the outcome is
 * {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION}. A stateless binding can observe neither, so in
 * cookie mode a refusal always ends the session.
 * Every session-ending disposition records {@code ApiSheriff-111}; a refresh that was superseded or found
 * its session gone — at the persist or at the refusal — ends no session and records none. Only the two
 * dispositions in which the refresh <em>itself</em> ends the session revoke a token — a {@code REDEEMED}
 * refusal that destroyed the record it read, and a persist failure. A refresh that lost a race to a
 * session replacement (superseded, at the persist or at the refusal) or to a session destruction
 * (terminated at the persist, or a refusal that found the session gone) abandons its live token
 * unrevoked, and {@code CREDENTIAL_REJECTED}, {@code PRE_REDEMPTION}, {@code SCOPE_REFUSED} and a written
 * refresh revoke nothing. Where a revocation is due, the session-ended outcome is published
 * to the failing request and every coalesced waiter <em>first</em>; the revocation is dispatched only
 * afterwards, off the request path, so a slow or hanging revocation endpoint delays the revocation and
 * nothing else. At most {@link #MAX_CONCURRENT_REVOCATIONS} revocations are in flight at once; beyond
 * that bound a revocation is skipped and recorded at {@code DEBUG}. Revocation is best-effort: a failure
 * or a refused dispatch is recorded at {@code DEBUG} and never changes the disposition, because the local
 * session destruction is what ends the session.
 * <p>
 * <strong>An ended refresh token is refused locally.</strong> Every session-ending disposition also marks
 * the presented refresh token in {@link EndedRefreshTokens} until the session's absolute lifetime. A later
 * request presenting a marked token — in cookie mode, a retained or replayed sealed cookie, which
 * {@code destroy} cannot invalidate — takes the session-ended disposition ({@code FAILED}, so the stage
 * clears the cookie and negotiates {@code on_failure}) with no engine call, no revocation and no
 * {@code ApiSheriff-111}, only a {@code DEBUG} line carrying neither token nor session id. The check
 * runs before the back-off. The marker is keyed on the token, not
 * the session, so a successor cookie still reaches the identity provider once; it is bounded, in memory
 * and per instance, and inert in server mode, where {@code destroy} already removes the session.
 * <p>
 * <strong>Refresh-token reuse detection is the identity provider's job.</strong> A confidential client
 * only ever sees the refresh tokens it presents itself and keeps no token family across stateless
 * instances, so the gateway cannot recognise a replayed refresh token. An identity provider enforcing
 * strict refresh-token rotation (on Keycloak {@code revokeRefreshToken: true} with a maximum reuse of
 * {@code 0}) rejects the replay with {@code invalid_grant}, which arrives here as
 * {@code CREDENTIAL_REJECTED} and ends the session (ADR-0046). Without that setting neither side
 * detects reuse.
 * <p>
 * <strong>Single-flight per session.</strong> Concurrent requests on one session share one
 * refresh: the first request to observe near-expiry becomes the in-flight leader (registered
 * atomically in {@link #inFlight}, keyed on {@link SessionRecord#sessionId()} — the stable
 * per-session identity every binding populates — so the check-then-act TOCTOU window is closed by
 * the map's atomic {@code putIfAbsent}, the per-session mutual-exclusion primitive); every
 * concurrent request on the same session joins the leader's result instead of launching its own
 * refresh. The leader re-resolves the session through the binding under this exclusion and
 * re-checks near-expiry, so a request that arrives just after a refresh completed observes the
 * already-rotated token and makes no engine call. That second resolve reads the request's own
 * {@code Cookie} header, and <strong>an empty result is not a destroyed session</strong>: in server
 * mode the header can name a cookie handle a step-up has just re-issued while the session lives on.
 * It is reported as {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION} — nothing is destroyed, marked
 * or revoked, and the caller answers without a clearing cookie. The rotated session of a refresh that
 * was in flight across a re-issue that left the record as the leader resolved it is persisted under the
 * stable session id, so it still finds the session and leaves the re-issued handle in place; a re-issue
 * that carried a widened record supersedes the refresh instead (see the dispositions above). The
 * scope-driven leg leads through the same map: a
 * request that coalesces with a refresh of the other leg shares that refresh's result, and a scope
 * request whose shared current or refreshed session does not carry its requested set receives
 * {@link RefreshOutcome.Kind#SCOPE_REFUSED SCOPE_REFUSED} with the shared session and cookies rather
 * than a second exchange. The pre-redemption back-off is read and written
 * under the same exclusion, so for one session only the leader decides whether an attempt is due.
 * Across stateless instances the back-off is per instance: each instance attempts at most once per
 * window. The per-session windows are bounded ({@link #MAX_BACKOFF_ENTRIES}); once that bound is
 * saturated, the sessions without their own window share one coarse overflow window instead, so an
 * outage large enough to fill the bound is still throttled — at the cost that such a session may see
 * its refresh deferred for up to the fixed back-off even if it has not failed itself. When the bound is
 * first found saturated with no overflow window open, and again whenever an overflow window elapses,
 * exactly one untracked session claims the window atomically before any engine call and probes the
 * identity provider; every other untracked session is deferred with no engine call and no record, so
 * the untracked sessions together make one attempt per window however many arrive at once. A probe the
 * provider processes, or one that fails but can be tracked on its own window, releases the claim; a
 * probe that fails while the map is still saturated keeps the window in force. With a healthy provider
 * this serialises the untracked refreshes to one in flight at a time while the map stays saturated,
 * which lasts only until the saturating windows expire and are pruned.
 * <p>
 * <strong>On-failure semantics.</strong> A {@link RefreshOutcome#failed() failed} outcome means the
 * session has already been destroyed; an {@link RefreshOutcome#unavailable() unavailable} outcome
 * means the session is still live but this request has no valid token to mediate. In both cases the
 * caller treats the request as unauthenticated. This class is framework-agnostic — it carries no
 * request/response coupling and is unit-testable without a container or a live IdP; the session
 * runtime performs the negotiation and binds the engine seams.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TokenRefreshCoordinator {

    private static final CuiLogger LOGGER = new CuiLogger(TokenRefreshCoordinator.class);

    /**
     * How long a session waits after a pre-redemption failure before its next refresh attempt. Fixed
     * and deliberately short: while fewer than {@link #MAX_BACKOFF_ENTRIES} sessions are backing off it
     * bounds the engine calls and the warning records an identity-provider outage produces to one per
     * session per window, and beyond that bound to one per overflow window for all the untracked
     * sessions together — the single session that claims the window's probe, the first window at a
     * saturated map as much as an elapsed one — while a transient fault still heals within seconds. Not
     * configurable.
     */
    static final Duration PRE_REDEMPTION_RETRY_BACKOFF = Duration.ofSeconds(5);

    /**
     * The upper bound on sessions carrying their own pending back-off. Once reached, expired entries are
     * pruned; when the map is still full, a session without its own entry is not given one — instead one
     * coarse overflow window of {@link #PRE_REDEMPTION_RETRY_BACKOFF} governs every such session. The
     * first untracked session to arrive at the saturated map claims that window atomically before its
     * engine call, as does the first one after the window elapses; every other untracked session makes no
     * engine call and records nothing until the claimed window closes. The claim is released once the
     * identity provider processes a grant or the probe can be tracked on its own window, and kept while the
     * map stays saturated. The throttle therefore degrades rather than disappears, and memory stays
     * bounded — one entry per tracked session plus a single instant — under any number of failing sessions.
     * <p>
     * The accepted trade: while the map is saturated, the untracked sessions share that one window, so a
     * session that has not failed itself may also see its refresh deferred for up to the fixed back-off.
     * With a healthy provider the same claim serialises the untracked
     * refreshes to one in flight at a time — each success releases it at once — and only until the
     * saturating windows expire and are pruned.
     */
    static final int MAX_BACKOFF_ENTRIES = 10_000;

    /**
     * The upper bound on best-effort revocations in flight at once. A revoking disposition that finds
     * the bound saturated skips its revocation and records that at {@code DEBUG}, so a slow or
     * hanging revocation endpoint can hold at most this many dispatched tasks. Not configurable.
     */
    static final int MAX_CONCURRENT_REVOCATIONS = 64;

    /** The bounded reason recorded when the identity provider rejected the presented refresh token. */
    static final String REASON_CREDENTIAL_REJECTED = "credential-rejected";

    /** The bounded reason recorded when the gateway refused a response the provider already redeemed. */
    static final String REASON_REDEEMED_RESPONSE_REFUSED = "redeemed-response-refused";

    /**
     * The bounded reason recorded when the binding could not hold the rotated session of a session that was
     * still there — a fault worth investigating, recorded with the exception that caused it. A persist that
     * finds the session already gone is not a failure of this kind: it ends nothing and records nothing.
     */
    static final String REASON_PERSIST_FAILURE = "persist-failure";

    /** The RFC 6749 §3.3 delimiter between the names of a {@code scope} value. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Duration leeway;
    private final AccessTokenExpiry accessTokenExpiry;
    private final RefreshExchange refreshExchange;
    private final SessionBinding sessionBinding;
    private final RefreshTokenRevocation refreshTokenRevocation;
    private final Executor revocationExecutor;
    private final EndedRefreshTokens endedRefreshTokens;
    private final Semaphore revocationPermits = new Semaphore(MAX_CONCURRENT_REVOCATIONS);
    private final ConcurrentMap<String, CompletableFuture<RefreshOutcome>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> retryNotBefore = new ConcurrentHashMap<>();
    /**
     * The coarse back-off window shared by every session without its own {@link #retryNotBefore} entry,
     * in force while the map holds {@link #MAX_BACKOFF_ENTRIES} unexpired windows. Both the first window
     * at a saturated map ({@link Instant#MIN} read) and an elapsed window are claimed before any engine
     * call by moving the field, with a compare-and-set against the exact instance read, to the claiming
     * probe's own window; a probe that fails while the map is still saturated keeps that claim in force as
     * the window. The claim is released by a compare-and-set from that claimed instance back to
     * {@link Instant#MIN}. {@link Instant#MIN} while no window is open — never opened, or released.
     */
    private final AtomicReference<Instant> overflowNotBefore = new AtomicReference<>(Instant.MIN);

    /**
     * Assembles the coordinator with the refresh leeway, the engine seams, and the session binding.
     *
     * @param leeway                 how long before access-token expiry a refresh is triggered
     *                               ({@code session.refresh.leeway_seconds})
     * @param accessTokenExpiry      the mediated-access-token expiry seam (bound to the engine token
     *                               parsing; a test binds a fixed instant)
     * @param refreshExchange        the engine refresh seam (bound to {@code RefreshFlow#refresh}; a
     *                               test binds a stubbed rotation or a throwing stub)
     * @param sessionBinding         the mode-neutral session binding the rotated record is persisted
     *                               through and an ended session is destroyed through
     * @param refreshTokenRevocation the best-effort RFC 7009 revocation seam a live refresh token is
     *                               revoked through when the refresh itself ends its session after a
     *                               redemption (a refused redeemed response or a persist failure)
     * @param revocationExecutor     the executor a revocation is dispatched on, off the request path,
     *                               after the session-ended outcome has been published (the session
     *                               runtime binds the Quarkus-managed virtual-thread executor)
     * @param endedRefreshTokens     the marker of refresh tokens whose session this coordinator ended —
     *                               {@link EndedRefreshTokens#bounded()} in cookie mode,
     *                               {@link EndedRefreshTokens#inert()} in server mode
     */
    public TokenRefreshCoordinator(Duration leeway, AccessTokenExpiry accessTokenExpiry,
            RefreshExchange refreshExchange, SessionBinding sessionBinding,
            RefreshTokenRevocation refreshTokenRevocation, Executor revocationExecutor,
            EndedRefreshTokens endedRefreshTokens) {
        this.leeway = Objects.requireNonNull(leeway, "leeway");
        this.accessTokenExpiry = Objects.requireNonNull(accessTokenExpiry, "accessTokenExpiry");
        this.refreshExchange = Objects.requireNonNull(refreshExchange, "refreshExchange");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
        this.refreshTokenRevocation = Objects.requireNonNull(refreshTokenRevocation, "refreshTokenRevocation");
        this.revocationExecutor = Objects.requireNonNull(revocationExecutor, "revocationExecutor");
        this.endedRefreshTokens = Objects.requireNonNull(endedRefreshTokens, "endedRefreshTokens");
    }

    /**
     * Returns the session to mediate from, refreshing its mediated token when within {@code leeway}
     * of expiry. Concurrent calls for the same session share one refresh.
     *
     * @param session      the resolved live session
     * @param cookieHeader the raw request {@code Cookie} header value the session was resolved
     *                     from — the leader re-resolves through it under single-flight exclusion;
     *                     may be absent
     * @param now          the reference instant
     * @return {@link RefreshOutcome#current(SessionRecord) current} when no refresh was needed,
     *         {@link RefreshOutcome#refreshed(SessionRecord, List) refreshed} carrying the rotated
     *         session, {@link RefreshOutcome#deferred(SessionRecord) deferred} or
     *         {@link RefreshOutcome#unavailable() unavailable} when the session was kept after a
     *         pre-redemption failure, {@link RefreshOutcome#failed() failed} when the session was
     *         destroyed, or {@link RefreshOutcome#noSession() no session} when the leader resolved none,
     *         its persist or its refusal was superseded by a concurrent widening or step-up, or its persist
     *         or its refusal found the session already ended; a request that coalesced with a
     *         concurrent {@link #refreshForScopes scope refresh}
     *         shares its result, which may be {@link RefreshOutcome.Kind#SCOPE_REFUSED SCOPE_REFUSED}
     *         carrying the kept session
     */
    public RefreshOutcome refresh(SessionRecord session, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (session.refreshToken() == null || !nearExpiry(session, now)) {
            return RefreshOutcome.current(session);
        }
        String sessionId = session.sessionId();
        return singleFlight(sessionId, () -> performRefresh(sessionId, cookieHeader, now), UnaryOperator.identity());
    }

    /**
     * Returns a session carrying every scope of {@code requestedScopes}, refreshing it through the engine
     * with exactly that set as the grant's {@code scope} when its active scope set {@code A} lacks a
     * member — the scope-driven leg of a session route whose missing scopes lie inside the granted set
     * {@code S}. The access token's remaining lifetime plays no part. Concurrent calls for the same
     * session share one refresh, and share it with a concurrent near-expiry {@link #refresh}.
     * <p>
     * The leader re-resolves the session through the binding under the single-flight exclusion and
     * re-checks the set: a session that already carries it — a concurrent refresh obtained it — is
     * returned as {@link RefreshOutcome#current(SessionRecord) current} with no engine call. A successful
     * exchange persists the rotated session through the binding; its {@code S} is the previous one less
     * every requested scope the response's {@code scope} did not return, so it is unchanged whenever the
     * set was obtained or the response omitted {@code scope}. When the binding reports the session gone
     * at that persist — a logout, a back-channel logout, a login callback, an expiry sweep or an eviction
     * destroyed it meanwhile, server mode — nothing is written, this refresh ends nothing itself, and the
     * outcome is {@link RefreshOutcome#noSession() no session}, never {@code SCOPE_REFUSED}; when it
     * reports the session's record superseded — a widening or a step-up landed meanwhile, server mode —
     * nothing is written and the outcome is {@link RefreshOutcome#noSession() no session} too. A refused
     * exchange takes exactly the near-expiry leg's disposition (see the class documentation); an
     * {@code invalid_scope} answer is reported by {@code RefreshFlow.classify} as {@code SCOPE_REFUSED}, and
     * the gateway deliberately gives it the pre-redemption disposition — the session is kept and backs off
     * ({@code ApiSheriff-127}), so the outcome is {@code DEFERRED} or {@code UNAVAILABLE} — because the
     * provider issued no grant and the same request would be refused again.
     *
     * @param session         the resolved live session
     * @param cookieHeader    the raw request {@code Cookie} header value the session was resolved from —
     *                        the leader re-resolves through it under single-flight exclusion; may be absent
     * @param requestedScopes the scope set the grant requests, sent verbatim as its {@code scope}; the
     *                        caller passes {@code A ∪ missing}
     * @param now             the reference instant
     * @return {@link RefreshOutcome#current(SessionRecord) current} when the session already carries the
     *         set, {@link RefreshOutcome#refreshed(SessionRecord, List) refreshed} carrying the rotated
     *         session that carries it, {@link RefreshOutcome#scopeRefused(SessionRecord, List) scope
     *         refused} carrying the kept session when the set was not obtained with no refusal to dispose
     *         (a narrower grant, whose refused scopes have left the carried session's {@code S}; no
     *         refresh token; or a shared refresh that did not request it),
     *         {@link RefreshOutcome#deferred(SessionRecord) deferred} or
     *         {@link RefreshOutcome#unavailable() unavailable} after a pre-redemption failure, or
     *         {@link RefreshOutcome#failed() failed} when the session was destroyed, or
     *         {@link RefreshOutcome#noSession() no session} when the leader resolved none, its persist
     *         or its refusal was superseded by a concurrent widening or step-up, or its persist or its
     *         refusal found the session already ended
     */
    public RefreshOutcome refreshForScopes(SessionRecord session, @Nullable String cookieHeader,
            Set<String> requestedScopes, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        Set<String> requested = Set.copyOf(Objects.requireNonNull(requestedScopes, "requestedScopes"));
        if (session.activeScopes().containsAll(requested)) {
            return RefreshOutcome.current(session);
        }
        if (session.refreshToken() == null) {
            // Nothing to present — the set can only be obtained by widening.
            return RefreshOutcome.scopeRefused(session, List.of());
        }
        String sessionId = session.sessionId();
        return singleFlight(sessionId, () -> performScopeRefresh(sessionId, cookieHeader, requested, now),
                shared -> sharedForScopes(shared, requested));
    }

    /**
     * Runs {@code leaderWork} as the one in-flight refresh of the session, or shares the result of the
     * refresh already in flight for it — of either leg — through {@code waiterView}. The leader
     * publishes its outcome to every coalesced waiter before any revocation is dispatched.
     */
    private RefreshOutcome singleFlight(String sessionId, Supplier<Disposition> leaderWork,
            UnaryOperator<RefreshOutcome> waiterView) {
        CompletableFuture<RefreshOutcome> leader = new CompletableFuture<>();
        CompletableFuture<RefreshOutcome> existing = inFlight.putIfAbsent(sessionId, leader);
        if (existing != null) {
            // A concurrent request already leads the refresh for this session — share its result.
            return waiterView.apply(existing.join());
        }
        Disposition disposition;
        try {
            disposition = leaderWork.get();
            // Publish the outcome to every coalesced waiter before any revocation is issued.
            leader.complete(disposition.outcome());
        } finally {
            // Guarantee coalesced waiters never hang: a no-op when the leader already completed
            // above, but a deterministic FAILED when the leader's work threw before completing it (the
            // unexpected exception still propagates out of this method for the leader's own caller).
            leader.complete(RefreshOutcome.failed());
            inFlight.remove(sessionId, leader);
        }
        String liveRefreshToken = disposition.liveRefreshToken();
        if (liveRefreshToken != null) {
            dispatchRevocation(liveRefreshToken);
        }
        return disposition.outcome();
    }

    /**
     * What a scope request that coalesced with another refresh receives: the shared outcome, except that
     * a current or refreshed session which does not carry the requested set becomes {@code SCOPE_REFUSED}
     * with the shared session and cookies. A second exchange is never started from here: in a stateless
     * mode the request's own cookie still names the refresh token the shared refresh just rotated away.
     */
    private static RefreshOutcome sharedForScopes(RefreshOutcome shared, Set<String> requested) {
        SessionRecord sharedSession = shared.session();
        boolean obtainedNothingToDispose = shared.kind() == RefreshOutcome.Kind.CURRENT
                || shared.kind() == RefreshOutcome.Kind.REFRESHED;
        if (sharedSession != null && obtainedNothingToDispose
                && !sharedSession.activeScopes().containsAll(requested)) {
            return RefreshOutcome.scopeRefused(sharedSession, shared.setCookieHeaders());
        }
        return shared;
    }

    private Disposition performRefresh(String sessionId, @Nullable String cookieHeader, Instant now) {
        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            return Disposition.of(noSessionForRequest());
        }
        SessionRecord latest = resolved.get();
        String presentedRefreshToken = latest.refreshToken();
        if (presentedRefreshToken == null || !nearExpiry(latest, now)) {
            // A coalesced leader already rotated this session — share the current token, no engine call.
            return Disposition.of(RefreshOutcome.current(latest));
        }
        // The grant requests the session's active scope set A — never the static oidc.scopes.
        return redeem(sessionId, latest, presentedRefreshToken, latest.activeScopes(), false, now);
    }

    private Disposition performScopeRefresh(String sessionId, @Nullable String cookieHeader, Set<String> requested,
            Instant now) {
        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            return Disposition.of(noSessionForRequest());
        }
        SessionRecord latest = resolved.get();
        if (latest.activeScopes().containsAll(requested)) {
            // A concurrent refresh already obtained the set — share the current token, no engine call.
            return Disposition.of(RefreshOutcome.current(latest));
        }
        String presentedRefreshToken = latest.refreshToken();
        if (presentedRefreshToken == null) {
            return Disposition.of(RefreshOutcome.scopeRefused(latest, List.of()));
        }
        Disposition disposition = redeem(sessionId, latest, presentedRefreshToken, requested, true, now);
        RefreshOutcome outcome = disposition.outcome();
        SessionRecord rotated = outcome.session();
        if (outcome.kind() == RefreshOutcome.Kind.REFRESHED && rotated != null
                && !rotated.activeScopes().containsAll(requested)) {
            // The provider processed the grant but granted less than requested: the rotated session is
            // persisted and kept — its S already without the refused scopes — and nothing backs off.
            return new Disposition(RefreshOutcome.scopeRefused(rotated, outcome.setCookieHeaders()),
                    disposition.liveRefreshToken());
        }
        return disposition;
    }

    /**
     * The outcome of a leader whose second resolve — from the request's own {@code Cookie} header, under
     * the single-flight exclusion — found no session.
     * <p>
     * It is <strong>not</strong> a destroyed session. In server mode that header can name a cookie
     * handle a step-up has just re-issued while the session lives on under the new one; in either mode
     * the session may simply have reached a deadline since the caller resolved it. Either way this
     * coordinator destroyed nothing, so nothing is destroyed, marked or revoked here, and the caller
     * must not clear the browser's cookie on the strength of it.
     * <p>
     * The session's back-off entry is left as it is: the session may be alive, and dropping the entry
     * would lift the throttle of a session that is still backing off. An entry of a session that is
     * really gone is reclaimed by the pruning at the bound, like that of any abandoned session.
     */
    private static RefreshOutcome noSessionForRequest() {
        LOGGER.debug("Refresh leader resolved no session from the request's cookie — nothing destroyed");
        return RefreshOutcome.noSession();
    }

    /**
     * The engine half both legs share, once the leader holds a live session with a refresh token: the
     * ended-token check, the back-off admission, the exchange requesting {@code scopes}, and the persist
     * of the rotated session — or the refusal's disposition.
     *
     * @param scopeDriven {@code true} on the scope-driven leg, where a requested scope the processed
     *                    grant did not return leaves the rotated session's granted scope set
     */
    private Disposition redeem(String sessionId, SessionRecord latest, String presentedRefreshToken,
            Set<String> scopes, boolean scopeDriven, Instant now) {
        if (endedRefreshTokens.isEnded(presentedRefreshToken, now)) {
            // A replay of a refresh token whose session this instance already ended (cookie mode keeps no
            // server-side session to destroy): refuse it locally — no engine call, no revocation, no WARN.
            retryNotBefore.remove(sessionId);
            sessionBinding.destroy(latest);
            LOGGER.debug("Refresh refused locally: the presented refresh token belongs to an already-ended session");
            return Disposition.of(RefreshOutcome.failed());
        }
        Admission admission = admit(sessionId, now);
        if (!admission.admitted()) {
            // A pre-redemption failure is still backing off, or another untracked session holds this
            // overflow window's probe — no engine call, same disposition.
            return Disposition.of(keptSession(latest, now));
        }
        RotationResult rotation;
        // The engine and the seam binding also raise IllegalStateException / IllegalArgumentException
        // (missing token endpoint, discovery failure); classify owns the mapping of every one of them.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            rotation = refreshExchange.exchange(presentedRefreshToken, scopes);
        } catch (RuntimeException refreshFailure) {
            return disposeRefusal(sessionId, latest, presentedRefreshToken, refreshFailure, now, admission);
        }
        // The identity provider processed the grant: whatever follows, the outage the probe tested is over.
        releaseProbe(admission);
        // The presented token is already redeemed here, so a persist failure cannot keep the session.
        SessionBinding.PersistResult persisted;
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            SessionRecord rotated = rotate(latest, rotation, scopes, scopeDriven);
            // persist() updates in place and never creates (a replace-if-current against latest in
            // server mode, a re-seal in a stateless mode), so no pre-persist destroy is needed on the
            // success path. Destroying first would open a window where a concurrent resolve() misses the
            // rotating session.
            persisted = sessionBinding.persist(latest, rotated, now);
        } catch (RuntimeException persistFailure) {
            return endSession(sessionId, latest, now, persistFailure, REASON_PERSIST_FAILURE,
                    rotation.refreshToken());
        }
        return switch (persisted) {
            case SessionBinding.PersistResult.Written(SessionBinding.BoundSession bound) -> {
                retryNotBefore.remove(sessionId);
                LOGGER.info(BffLogMessages.INFO.TOKEN_REFRESHED);
                yield Disposition.of(RefreshOutcome.refreshed(bound.session(), bound.setCookieHeaders()));
            }
            // The binding found the session gone: a logout, a back-channel logout, a login callback, an
            // expiry sweep or an eviction destroyed it after the leader resolved it (server mode). Nothing
            // was written, so it stays terminated, and this refresh ends nothing itself — no clearing
            // cookie, no session-end record, the rotated refresh token abandoned: see foundSessionGone(...).
            case SessionBinding.PersistResult.Gone _ -> foundSessionGone(sessionId, "persist");
            // The rotated refresh token is abandoned, not revoked: see superseded(...).
            case SessionBinding.PersistResult.Superseded _ -> superseded(sessionId, "persist");
        };
    }

    /**
     * The disposition of a refresh whose persist found the session's record superseded: a widening or a
     * step-up replaced it after the leader resolved it (server mode), so the rotated session was computed
     * from a record that is no longer the session's.
     * <p>
     * Nothing is written, so the later grant's scopes, refresh token, {@code acr} and {@code auth_time}
     * stay as that write left them. The session lives on under the cookie value the widening re-issued,
     * so nothing is destroyed, nothing is marked ended and nothing is logged at {@code WARN}; the
     * session's back-off entry is dropped, because the identity provider has just processed a grant
     * for it. The request is answered {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION} — it is
     * unauthenticated, and the caller must not clear the browser's cookie.
     * <p>
     * The refresh token the exchange returned is <em>abandoned, not revoked</em>. The widening is a grant
     * for the same client on the identity provider's session, so the refresh token it stored belongs to
     * the same client session as the rotated one. RFC 7009 lets the provider revoke the whole grant on a
     * revocation, and a provider that does so would end at the provider the very session this disposition
     * keeps: Keycloak answers a refresh-token revocation by detaching the revoking client from the user
     * session the token belongs to, which the {@code BffLogoutIT} integration suite pins; that the next
     * refresh of the kept session would then fail is reasoning, not asserted by a test. The rotated token
     * is never sent to the browser and no copy is held, so it lapses under the provider's own
     * refresh-token lifetime — exactly as the rotated token of a refresh a widening overwrites after it
     * persisted.
     * <p>
     * {@link #endRefusedSession} takes the same disposition for a refusal whose destruction found the
     * record superseded.
     *
     * @param sessionId the session whose back-off entry is dropped
     * @param step      which step found the record superseded — {@code persist} or {@code refusal} —
     *                  named in the {@code DEBUG} line only
     */
    private Disposition superseded(String sessionId, String step) {
        retryNotBefore.remove(sessionId);
        // Neither the token nor the session id: the line says only which disposition was taken.
        LOGGER.debug("Refresh %s superseded by a concurrent widening or step-up — nothing written, destroyed or revoked",
                step);
        return Disposition.of(RefreshOutcome.noSession());
    }

    private Disposition disposeRefusal(String sessionId, SessionRecord latest, String presentedRefreshToken,
            RuntimeException refreshFailure, Instant now, Admission admission) {
        RefreshFailureClassification classification = RefreshFlow.classify(refreshFailure);
        // A switch expression, not a statement: javac rejects it the moment the engine adds a further kind.
        // SCOPE_REFUSED (an invalid_scope answer) joins PRE_REDEMPTION: the identity provider issued no grant
        // and rotated nothing, so the session is kept; the same request would be refused again, so the
        // bounded back-off, not an immediate retry, is what keeps the provider from being hammered.
        return switch (classification.kind()) {
            case PRE_REDEMPTION, SCOPE_REFUSED -> Disposition.of(backOff(sessionId, latest, refreshFailure, now, admission));
            case CREDENTIAL_REJECTED -> {
                releaseProbe(admission);
                yield endRefusedSession(sessionId, latest, now, refreshFailure, REASON_CREDENTIAL_REJECTED, null);
            }
            case REDEEMED -> {
                releaseProbe(admission);
                yield endRefusedSession(sessionId, latest, now, refreshFailure, REASON_REDEEMED_RESPONSE_REFUSED,
                        liveRefreshToken(Objects.requireNonNull(classification.redemption(), "redemption"),
                                presentedRefreshToken));
            }
        };
    }

    /**
     * The session end of a refused refresh, decided on {@code latest}: the session is destroyed only
     * while the binding still holds that record, through {@link SessionBinding#destroyIfCurrent}.
     * <p>
     * A widening or a step-up that replaced the record while the exchange was in flight writes through
     * the binding without passing this coordinator's single-flight, so the record the refusal was decided
     * on may no longer be the session's. The binding then reports it superseded, and the refusal takes the
     * superseded disposition: nothing is destroyed, nothing is marked ended, nothing is revoked and no
     * {@code ApiSheriff-111} is recorded; the request is answered
     * {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION}. Nothing is revoked for the same reason a
     * superseded persist abandons its rotated token: the replacing write may share the identity
     * provider's user session, and a grant-level revocation could end it.
     * <p>
     * When the binding instead finds the session already gone — something else ended it while the exchange
     * was in flight — the refusal ended nothing and lost a race to that destruction: see
     * {@link #foundSessionGone}, which destroys, marks, records and revokes nothing.
     * <p>
     * Only when the binding destroyed the record the refusal read is the disposition the ordinary session
     * end, including the revocation of {@code liveRefreshToken}.
     */
    private Disposition endRefusedSession(String sessionId, SessionRecord latest, Instant now,
            RuntimeException failure, String reason, @Nullable String liveRefreshToken) {
        // A switch expression, not a statement: javac rejects it the moment the binding adds a further result.
        return switch (sessionBinding.destroyIfCurrent(latest)) {
            case DESTROYED -> recordSessionEnd(sessionId, latest, now, failure, reason, liveRefreshToken);
            case SUPERSEDED -> superseded(sessionId, "refusal");
            case GONE -> foundSessionGone(sessionId, "refusal");
        };
    }

    /**
     * The disposition of a refresh whose persist, or whose refusal's destruction, found the session
     * already gone: a logout, a back-channel logout, a login callback that replaced the session in the
     * same browser, an expiry sweep or an eviction ended it while the exchange was in flight (server
     * mode).
     * <p>
     * This refresh did not end the session — whatever destroyed it did — so it ends nothing either.
     * Nothing is written, so the session stays terminated; nothing is destroyed, nothing is marked ended
     * and no {@code ApiSheriff-111} is recorded, because the session's end belongs to whatever ended it
     * and is not recorded a second time here. The session's back-off entry is dropped, because the
     * session is gone. The request is answered {@link RefreshOutcome.Kind#NO_SESSION NO_SESSION}, so the
     * caller sends no clearing cookie: when the destruction was a login callback that replaced the
     * session, a clearing response from this refresh could reach the browser after the callback's own
     * response and delete the replacement session's new cookie. A request presenting the cookie of a
     * session that is really gone is answered by the ordinary no-session path, so a genuine logout that
     * raced a refresh still leaves the browser unauthenticated.
     * <p>
     * Nothing is revoked. The refresh lost a race to a session destruction, and a login callback's
     * replacement session may share the identity provider's user session, which a grant-level revocation
     * could end. The live refresh token the refresh names — the one the exchange returned at the persist,
     * or the one a refusal names — is abandoned and lapses under the provider's refresh-token lifetime;
     * it is never sent to the browser and no copy is held.
     *
     * @param sessionId the session whose back-off entry is dropped
     * @param step      which step found the session gone — {@code persist} or {@code refusal} — named in
     *                  the {@code DEBUG} line only
     */
    private Disposition foundSessionGone(String sessionId, String step) {
        retryNotBefore.remove(sessionId);
        // Neither the token nor the session id: the line says only which disposition was taken.
        LOGGER.debug("Refresh %s found the session already ended — nothing written, destroyed, marked or revoked",
                step);
        return Disposition.of(RefreshOutcome.noSession());
    }

    private RefreshOutcome backOff(String sessionId, SessionRecord latest, RuntimeException refreshFailure,
            Instant now, Admission admission) {
        if (!recordBackOff(sessionId, now.plus(PRE_REDEMPTION_RETRY_BACKOFF), now)) {
            // The probe could be tracked on its own window, so the per-session windows govern again. When it
            // opened or extended the overflow window instead, the claim simply stays in force as that window.
            releaseProbe(admission);
        }
        LOGGER.warn(refreshFailure, BffLogMessages.WARN.SESSION_REFRESH_DEFERRED,
                PRE_REDEMPTION_RETRY_BACKOFF.toSeconds());
        return keptSession(latest, now);
    }

    /**
     * Decides whether this attempt may reach the engine. A session holding its own back-off entry is
     * governed by that entry alone. A session without one proceeds while no overflow window is open and
     * the back-off map is below {@link #MAX_BACKOFF_ENTRIES} — one reference read and one size read — and
     * is deferred while an overflow window is still in force. When no overflow window is open but the map
     * is at the bound, expired entries are pruned first; if the map is still saturated, the first window is
     * claimed exactly like an elapsed one, before any engine call. In both claim cases exactly one untracked
     * session wins — atomically moving the window to {@code now} plus the back-off, compared against the
     * exact instance just read — and every other untracked session re-reads the claimed window and is
     * deferred with no engine call and no record. A probe that hangs past its claimed window lets the next
     * session claim the next window, so attempts stay at one per window.
     */
    private Admission admit(String sessionId, Instant now) {
        Instant own = retryNotBefore.get(sessionId);
        if (own != null) {
            return now.isBefore(own) ? Admission.BACKING_OFF : Admission.PROCEED;
        }
        while (true) {
            Instant window = overflowNotBefore.get();
            if (Instant.MIN.equals(window)) {
                if (!saturatedAfterPruning(now)) {
                    // The healthy path: the per-session windows have room, so nothing throttles this attempt.
                    return Admission.PROCEED;
                }
                // Saturated with unexpired windows and no overflow window open yet: claim the first window
                // before any engine call, exactly as an elapsed window is claimed below. Instant.MIN is the
                // constant the field is initialised and released to, so the reference compare matches it.
            } else if (now.isBefore(window)) {
                return Admission.BACKING_OFF;
            }
            Instant claim = now.plus(PRE_REDEMPTION_RETRY_BACKOFF);
            if (overflowNotBefore.compareAndSet(window, claim)) {
                return Admission.probe(claim);
            }
            // Another session claimed, extended or released the window meanwhile — decide on its new value.
        }
    }

    /**
     * Whether the back-off map is still saturated once expired entries are pruned. Pruning runs only at
     * the bound, so the unsaturated path stays a single size read; at the bound it removes every entry
     * that is not after {@code now}, which abandoned sessions would otherwise leave behind for good.
     */
    private boolean saturatedAfterPruning(Instant now) {
        if (retryNotBefore.size() < MAX_BACKOFF_ENTRIES) {
            return false;
        }
        retryNotBefore.values().removeIf(entry -> !now.isBefore(entry));
        return retryNotBefore.size() >= MAX_BACKOFF_ENTRIES;
    }

    /**
     * Releases an overflow probe claim back to "no window open", but only from the exact claimed
     * instance: a newer window that another failure or a later probe opened meanwhile is never erased.
     */
    private void releaseProbe(Admission admission) {
        Instant claim = admission.probeClaim();
        if (claim != null) {
            overflowNotBefore.compareAndSet(claim, Instant.MIN);
        }
    }

    /**
     * Records a pre-redemption back-off for the session.
     *
     * @return {@code true} when the session could not be tracked on its own window and the shared overflow
     *         window was used instead — the explicit signal a probe needs, because with an equal instant the
     *         overflow reference does not change
     */
    private boolean recordBackOff(String sessionId, Instant notBefore, Instant now) {
        if (retryNotBefore.computeIfPresent(sessionId, (id, previous) -> notBefore) != null) {
            // Already tracked: the session's own window moves forward and takes no capacity.
            return false;
        }
        if (!saturatedAfterPruning(now)) {
            retryNotBefore.put(sessionId, notBefore);
            return false;
        }
        // Still saturated with unexpired windows: degrade to one coarse window shared by every session
        // without its own entry, rather than dropping the throttle for exactly the largest outages.
        overflowNotBefore.accumulateAndGet(notBefore, (current, candidate) ->
                candidate.isAfter(current) ? candidate : current);
        return true;
    }

    /**
     * The disposition of a session kept after a pre-redemption failure: {@code DEFERRED} while its
     * access token has not expired, otherwise {@code UNAVAILABLE}.
     */
    private RefreshOutcome keptSession(SessionRecord latest, Instant now) {
        if (now.isBefore(accessTokenExpiry.expiryOf(latest))) {
            return RefreshOutcome.deferred(latest);
        }
        return RefreshOutcome.unavailable();
    }

    /**
     * The synchronous, disposition-defining half of a session end: the back-off entry is dropped, the
     * session destroyed, the presented refresh token marked ended until the session's absolute lifetime,
     * and {@code ApiSheriff-111} recorded. The live refresh token is only handed back; the single-flight
     * leader revokes it after the outcome has been published to the coalesced waiters. The destruction
     * here is unconditional — a persist failure takes it; a refused exchange ends its session through
     * {@link #endRefusedSession} instead, which destroys only the record it read. A persist that found the
     * session gone ends nothing and does not come here: see {@link #foundSessionGone}.
     * <p>
     * The presented refresh token is {@code latest.refreshToken()}: {@link #redeem} is only reached after
     * both legs confirmed it is present.
     *
     * @param failure          the exception that ended the session, recorded with {@code ApiSheriff-111}
     * @param liveRefreshToken the refresh token to revoke once the outcome is published — the one the
     *                         exchange returned, absent when the provider returned none
     */
    private Disposition endSession(String sessionId, SessionRecord latest, Instant now,
            RuntimeException failure, String reason, @Nullable String liveRefreshToken) {
        sessionBinding.destroy(latest);
        return recordSessionEnd(sessionId, latest, now, failure, reason, liveRefreshToken);
    }

    /**
     * The half of a session end that follows its destruction: the back-off entry is dropped, the
     * presented refresh token marked ended until the session's absolute lifetime, {@code ApiSheriff-111}
     * recorded with the exception that ended the session, and the live refresh token handed back for
     * revocation. {@link #endSession} reaches it once the session has been destroyed after a persist
     * failure; {@link #endRefusedSession} only once the binding destroyed the record the refusal read. A
     * refresh whose persist or refusal finds the session gone, or superseded, does not.
     */
    private Disposition recordSessionEnd(String sessionId, SessionRecord latest, Instant now,
            RuntimeException failure, String reason, @Nullable String liveRefreshToken) {
        String presentedRefreshToken = Objects.requireNonNull(latest.refreshToken(),
                "a session-ending disposition requires the presented refresh token");
        retryNotBefore.remove(sessionId);
        endedRefreshTokens.markEnded(presentedRefreshToken, latest.expiresAt(), now);
        // Bounded, non-sensitive reason only — never the presented refresh token or session id.
        LOGGER.warn(failure, BffLogMessages.WARN.SESSION_REFRESH_FAILED, reason);
        return new Disposition(RefreshOutcome.failed(), liveRefreshToken);
    }

    /**
     * The one refresh token still alive at the identity provider after a refused redemption: the
     * successor when the provider rotated, the presented token when it did not, and none when rotation
     * could not be determined — that token is knowingly abandoned, since there is nothing to name.
     */
    private static @Nullable String liveRefreshToken(RefreshRedemption redemption, String presentedRefreshToken) {
        String successor = redemption.rotatedRefreshToken();
        if (successor != null) {
            return successor;
        }
        if (!redemption.presentedTokenBurned()) {
            return presentedRefreshToken;
        }
        LOGGER.debug("Refresh rotation unknown after a refused redemption — no live refresh token to revoke");
        return null;
    }

    /**
     * Hands a live refresh token to the revocation executor, off the request path. At most
     * {@link #MAX_CONCURRENT_REVOCATIONS} revocations are in flight at once; beyond that the revocation is
     * skipped and recorded at {@code DEBUG}, consistent with best-effort. A refused dispatch is swallowed
     * the same way. Neither record carries the refresh token or the session id.
     */
    private void dispatchRevocation(String refreshToken) {
        if (!revocationPermits.tryAcquire()) {
            LOGGER.debug("Best-effort refresh token revocation skipped — %s revocations already in flight",
                    MAX_CONCURRENT_REVOCATIONS);
            return;
        }
        // Executor#execute may refuse the task (RejectedExecutionException) or a misbehaving executor may
        // raise anything else; either way the revocation is simply not issued.
        // The permit is released exactly once, whether the task ran, was refused, or both.
        AtomicBoolean released = new AtomicBoolean();
        Runnable releasePermit = () -> {
            if (released.compareAndSet(false, true)) {
                revocationPermits.release();
            }
        };
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            revocationExecutor.execute(() -> {
                try {
                    revokeBestEffort(refreshToken);
                } finally {
                    releasePermit.run();
                }
            });
        } catch (RuntimeException dispatchFailure) {
            releasePermit.run();
            LOGGER.debug(dispatchFailure, "Best-effort refresh token revocation could not be dispatched");
        }
    }

    private void revokeBestEffort(String refreshToken) {
        // Revocation is best-effort: the session is already destroyed locally, which is what ends it, so
        // a failing revocation endpoint must not change the disposition. The catch is deliberately broad.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            refreshTokenRevocation.revoke(refreshToken);
        } catch (RuntimeException revocationFailure) {
            LOGGER.debug(revocationFailure, "Best-effort refresh token revocation failed after the session ended");
        }
    }

    private boolean nearExpiry(SessionRecord session, Instant now) {
        Instant expiry = accessTokenExpiry.expiryOf(session);
        return !now.isBefore(expiry.minus(leeway));
    }

    /**
     * Rebuilds the session with the rotated token material.
     * <p>
     * Because this reconstructs the record component-by-component, any component NOT copied here is
     * silently dropped from the rotated session. That is load-bearing for
     * {@link SessionRecord#sessionNonce()}: dropping it would make the cookie-mode binding re-seal
     * without a nonce — changing the derived session identity mid-session and breaking the
     * single-flight coalescing this coordinator depends on. Add a copy line here for every component
     * added to {@link SessionRecord}.
     * <p>
     * The active scope set becomes the refresh response's {@code scope}, and becomes
     * {@code requestedScopes} when the response omits or blanks it (RFC 6749 §5.1 — an omitted
     * {@code scope} is identical to the one requested). On the near-expiry leg the requested set is
     * {@code previous}'s own {@code A}, so an omitted {@code scope} leaves it unchanged.
     * <p>
     * The granted scope set {@code S} is never added to — only a widening obtains a scope the session
     * was not granted. On the near-expiry leg it is copied verbatim, even when the response narrows
     * {@code A}. On the scope-driven leg ({@code scopeDriven}) it loses every requested scope the new
     * {@code A} lacks: the identity provider processed a grant naming that scope and did not return it.
     */
    private static SessionRecord rotate(SessionRecord previous, RotationResult rotation, Set<String> requestedScopes,
            boolean scopeDriven) {
        String rotatedIdToken = rotation.idToken();
        Set<String> rotatedActiveScopes = refreshedActiveScopes(rotation.grantedScope(), requestedScopes);
        return SessionRecord.builder()
                .sessionId(previous.sessionId())
                .accessToken(rotation.accessToken().getRawToken())
                .refreshToken(rotation.refreshToken())
                .idToken(rotatedIdToken == null || rotatedIdToken.isBlank() ? previous.idToken() : rotatedIdToken)
                .sub(previous.sub())
                .sid(previous.sid())
                .expiresAt(previous.expiresAt())
                .acr(previous.acr())
                .authTime(previous.authTime())
                .sessionNonce(previous.sessionNonce())
                .activeScopes(rotatedActiveScopes)
                .grantedScopes(scopeDriven
                        ? withoutRefused(previous.grantedScopes(), requestedScopes, rotatedActiveScopes)
                        : previous.grantedScopes())
                .build();
    }

    /**
     * The granted scope set after a processed scope-driven refresh: {@code granted} less every member of
     * {@code requested} that {@code obtained} — the rotated active scope set — does not carry. Returns
     * {@code granted} itself when nothing was refused, which is the ordinary case.
     */
    private static Set<String> withoutRefused(Set<String> granted, Set<String> requested, Set<String> obtained) {
        if (obtained.containsAll(requested)) {
            return granted;
        }
        Set<String> truthful = new HashSet<>(granted);
        for (String scope : requested) {
            if (!obtained.contains(scope)) {
                truthful.remove(scope);
            }
        }
        return truthful;
    }

    /**
     * The active scope set after a refresh: the response's {@code scope} split on whitespace, or the
     * {@code requested} set when the response omitted or blanked it.
     */
    private static Set<String> refreshedActiveScopes(@Nullable String grantedScope, Set<String> requested) {
        if (grantedScope == null || grantedScope.isBlank()) {
            return requested;
        }
        return Set.copyOf(Arrays.asList(WHITESPACE.split(grantedScope.strip())));
    }

    /**
     * Whether a refresh was admitted, carrying the overflow-probe claim (or its absence) from
     * {@code admit()} to {@code releaseProbe()}. Holds no token.
     */
    private record Admission(boolean admitted, @Nullable Instant probeClaim) {

        static final Admission PROCEED = new Admission(true, null);
        static final Admission BACKING_OFF = new Admission(false, null);

        static Admission probe(Instant claim) {
            return new Admission(true, claim);
        }
    }

    /**
     * The outcome to publish and, for a session the refresh itself ended after a redemption, the one
     * refresh token still live at the identity provider, revoked only after the outcome has been
     * published. {@link #toString()} never renders the token.
     */
    private record Disposition(RefreshOutcome outcome, @Nullable String liveRefreshToken) {

        static Disposition of(RefreshOutcome outcome) {
            return new Disposition(outcome, null);
        }

        @Override
        public String toString() {
            return "Disposition[outcome=" + outcome.kind() + ", revocation=" + (liveRefreshToken != null) + "]";
        }
    }

    /**
     * The mediated-access-token expiry seam. The session runtime binds it to the engine token
     * parsing (the access token's {@code exp}); a test binds a fixed instant. Keeping the token
     * parsing behind the seam decouples the coordinator from the engine and keeps it unit-testable,
     * and lets the near-expiry decision use the access-token lifetime rather than the absolute
     * session cap ({@link SessionRecord#expiresAt()}).
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface AccessTokenExpiry {

        /**
         * @param session the live session whose mediated access token is inspected
         * @return the instant the session's mediated access token expires
         */
        Instant expiryOf(SessionRecord session);
    }

    /**
     * The engine refresh seam. The session runtime binds it to the per-scope-set engine seam as
     * {@code (refreshToken, scopes) -> scopedEngineFlows.refresh(providerMetadata, refreshToken,
     * scopes)} (ADR-0048), so the grant's {@code scope} is the session's active scope set on the
     * near-expiry leg and the requested set on the scope-driven leg; a test
     * binds a stubbed rotation or a throwing stub. The engine owns the refresh grant and refresh-token
     * rotation, and its {@code RefreshFlow.classify} decides what a refusal means for the presented
     * token; refresh-token reuse detection is not part of the exchange — it is the identity provider's
     * strict rotation (ADR-0046). Keeping the confidential-client wiring (provider metadata, client
     * authentication) behind the seam decouples the coordinator from it.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface RefreshExchange {

        /**
         * Refreshes the mediated tokens using the current refresh token.
         *
         * @param refreshToken the session's current refresh token
         * @param scopes       the scope set sent as the grant's {@code scope} — the session's active scope
         *                     set {@code A} on the near-expiry leg, the requested set on the scope-driven leg
         * @return the engine rotation result (new access/refresh/ID token + access-token lifetime, and
         *         the granted {@code scope} when the response carried one)
         * @throws RuntimeException when the refresh is refused — before the provider processed the
         *         grant, because the provider rejected the credential (including a replayed refresh
         *         token under strict rotation), or after the provider redeemed it; the coordinator
         *         classifies it through {@code RefreshFlow.classify}
         */
        RotationResult exchange(String refreshToken, Set<String> scopes);
    }

    /**
     * The best-effort RFC 7009 refresh-token revocation seam. The session runtime binds it to the
     * engine's revocation client against the provider's {@code revocation_endpoint}; a test binds a
     * recording or a throwing stub. The coordinator swallows any failure it raises.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface RefreshTokenRevocation {

        /**
         * Revokes a refresh token that is still live at the identity provider.
         *
         * @param refreshToken the refresh token to revoke
         */
        void revoke(String refreshToken);
    }

    /**
     * The framework-agnostic result of a refresh attempt. Token material is never disclosed to the
     * browser — the rotated {@link SessionRecord} crosses only inside the binding's own cookie
     * representation, which is opaque in server mode and authenticated-encrypted in a stateless mode.
     *
     * @param kind             which of the refresh outcomes occurred
     * @param session          the carried session, present for {@link Kind#CURRENT},
     *                         {@link Kind#REFRESHED}, {@link Kind#DEFERRED} and {@link Kind#SCOPE_REFUSED},
     *                         {@code null} for {@link Kind#UNAVAILABLE}, {@link Kind#FAILED} and
     *                         {@link Kind#NO_SESSION}
     * @param setCookieHeaders the {@code Set-Cookie} header values the re-bind produced, empty when
     *                         the binding needs no new cookie
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record RefreshOutcome(Kind kind, @Nullable SessionRecord session, List<String> setCookieHeaders) {

        /**
         * The terminal states of a refresh attempt.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        public enum Kind {
            /** No refresh was needed. */
            CURRENT,
            /** The mediated token was rotated through the engine and persisted. */
            REFRESHED,
            /**
             * The refresh failed before the identity provider processed it; the session is kept and its
             * access token has not expired.
             */
            DEFERRED,
            /**
             * The refresh failed before the identity provider processed it and the access token has
             * expired; the session is kept, but this request has no token to mediate.
             */
            UNAVAILABLE,
            /** The session was destroyed; the caller treats the request as unauthenticated. */
            FAILED,
            /**
             * This request holds no session to mediate from, and nothing was destroyed or marked. Either
             * the leader resolved no session from this request's {@code Cookie} header when it resolved
             * it again under the single-flight exclusion — the session may be alive under a cookie value
             * re-issued meanwhile (server mode), or may have reached a deadline — or the leader's persist
             * was superseded: a widening or a step-up replaced the session's record while the refresh was
             * in flight (server mode), the rotated session was not written, and the refresh token the
             * exchange returned is abandoned without revocation — or the leader's persist found the
             * session already ended by a logout, a back-channel logout, a login callback, an expiry sweep
             * or an eviction (server mode), so nothing was written and that token is abandoned the same
             * way — or a refusal that would have ended the session found its record replaced, or the
             * session already ended, the same ways, and destroyed and revoked nothing. The caller treats
             * the request as unauthenticated and must <strong>not</strong> clear the browser's cookie on
             * the strength of it: a login callback may just have given the browser a replacement session's
             * cookie.
             */
            NO_SESSION,
            /**
             * A scope-driven refresh did not obtain the requested set and there was no refusal to dispose:
             * the identity provider granted a narrower {@code scope} (the rotated session is persisted and
             * carried with its cookies, its granted scope set already without the scopes the grant did not
             * return), the session carries no refresh token, or the refresh this request
             * coalesced with did not obtain the set. The session is kept, no back-off is recorded and
             * nothing is logged at {@code WARN}; the caller never relays the carried
             * session short of its needed scopes. An outright {@code invalid_scope} refusal is not reported
             * here: {@code RefreshFlow.classify} reports it as the engine's own {@code SCOPE_REFUSED} kind,
             * and the gateway deliberately gives it the pre-redemption disposition — session kept, bounded
             * back-off, {@code ApiSheriff-127}, outcome {@link #DEFERRED} or {@link #UNAVAILABLE} — because
             * the provider issued no grant and the same request would be refused again. Produced by
             * {@link TokenRefreshCoordinator#refreshForScopes}, and received by a near-expiry
             * {@link TokenRefreshCoordinator#refresh} only when it coalesced with such a scope refresh.
             */
            SCOPE_REFUSED
        }

        /**
         * Canonical constructor enforcing the presence contract.
         */
        public RefreshOutcome {
            Objects.requireNonNull(kind, "kind");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
            boolean carriesSession = switch (kind) {
                case CURRENT, REFRESHED, DEFERRED, SCOPE_REFUSED -> true;
                case UNAVAILABLE, FAILED, NO_SESSION -> false;
            };
            if (carriesSession && session == null) {
                throw new IllegalArgumentException("a " + kind + " outcome must carry a session");
            }
            if (!carriesSession && session != null) {
                throw new IllegalArgumentException("a " + kind + " outcome must not carry a session");
            }
        }

        /**
         * A no-refresh-needed outcome carrying the unchanged session and no new cookie.
         *
         * @param session the live session, unchanged
         * @return the current outcome
         */
        public static RefreshOutcome current(SessionRecord session) {
            return new RefreshOutcome(Kind.CURRENT, session, List.of());
        }

        /**
         * A successful-refresh outcome carrying the rotated session and the re-bind's cookies.
         *
         * @param session          the session with the rotated token material
         * @param setCookieHeaders the {@code Set-Cookie} header values the re-bind produced
         * @return the refreshed outcome
         */
        public static RefreshOutcome refreshed(SessionRecord session, List<String> setCookieHeaders) {
            return new RefreshOutcome(Kind.REFRESHED, session, setCookieHeaders);
        }

        /**
         * A kept-session outcome after a pre-redemption failure, carrying the unchanged session whose
         * access token has not expired yet.
         *
         * @param session the live session, unchanged
         * @return the deferred outcome
         */
        public static RefreshOutcome deferred(SessionRecord session) {
            return new RefreshOutcome(Kind.DEFERRED, session, List.of());
        }

        /**
         * A kept-session outcome after a pre-redemption failure whose access token has expired: the
         * session is NOT destroyed, but this request carries no token to mediate.
         *
         * @return the unavailable outcome
         */
        public static RefreshOutcome unavailable() {
            return new RefreshOutcome(Kind.UNAVAILABLE, null, List.of());
        }

        /**
         * A failed-refresh outcome carrying no session — the session has been destroyed and the
         * caller must treat the request as unauthenticated.
         *
         * @return the failed outcome
         */
        public static RefreshOutcome failed() {
            return new RefreshOutcome(Kind.FAILED, null, List.of());
        }

        /**
         * The outcome of a refresh whose leader resolved no session from the request's own
         * {@code Cookie} header, whose persist or refusal was superseded by a concurrent widening or step-up, or
         * whose persist or refusal found the session already ended. It carries no session and no cookie, and — unlike
         * {@link #failed()} — says that this refresh destroyed nothing.
         *
         * @return the no-session outcome
         */
        public static RefreshOutcome noSession() {
            return new RefreshOutcome(Kind.NO_SESSION, null, List.of());
        }

        /**
         * A kept-session outcome of a scope-driven refresh that did not obtain the requested set, carrying
         * the session — rotated and persisted when the provider granted a narrower {@code scope} — and the
         * re-bind's cookies, which the caller must still emit.
         *
         * @param session          the kept session, which does not carry the requested set
         * @param setCookieHeaders the {@code Set-Cookie} header values a re-bind produced, empty when none
         * @return the scope-refused outcome
         */
        public static RefreshOutcome scopeRefused(SessionRecord session, List<String> setCookieHeaders) {
            return new RefreshOutcome(Kind.SCOPE_REFUSED, session, setCookieHeaders);
        }

        /**
         * @return {@code true} when the session was destroyed
         */
        public boolean isFailure() {
            return kind == Kind.FAILED;
        }

        /**
         * @return {@code true} when the session was kept but this request has no token to mediate
         */
        public boolean requestFailed() {
            return kind == Kind.UNAVAILABLE;
        }
    }
}
