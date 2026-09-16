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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


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
 * Transparent, single-flight token refresh for a {@code require: session} route (D7/D9) — the
 * refresh coordinator bound to the {@code SessionAuthenticationStage} refresh seam (the D9 hook).
 * <p>
 * When the mediated access token is within {@code leeway} of expiry the coordinator refreshes it
 * <strong>through the engine</strong> ({@code token-sheriff-client}'s {@code RefreshFlow}, reached
 * via the {@link RefreshExchange} seam) and persists the rotated token material through the
 * mode-neutral {@link SessionBinding} seam — a store write in server mode, a re-bound cookie in a
 * stateless mode — so the browser never sees a token and never drives a leg. The gateway re-implements
 * <strong>no</strong> OAuth leg: the engine owns the refresh grant and refresh-token rotation.
 * <p>
 * <strong>A refused refresh is disposed by what the identity provider did to the presented refresh
 * token.</strong> Only the exchange itself is classified, through the engine's
 * {@code RefreshFlow.classify}, and every kind has its own disposition:
 * <ul>
 *   <li><strong>{@code PRE_REDEMPTION}</strong> — the provider never processed the grant (a connection
 *       or DNS failure, a {@code 5xx}, a {@code 4xx} not attributed to the credential). The presented
 *       token is still valid, so the session is <em>kept</em>, nothing is revoked, and the session's
 *       next attempt waits out a fixed five-second back-off. The outcome is
 *       {@link RefreshOutcome.Kind#DEFERRED DEFERRED} while the access token has not expired, otherwise
 *       {@link RefreshOutcome.Kind#UNAVAILABLE UNAVAILABLE}; the record is {@code ApiSheriff-127}.</li>
 *   <li><strong>{@code CREDENTIAL_REJECTED}</strong> — the provider answered {@code invalid_grant},
 *       which is also how a provider enforcing strict rotation rejects a replayed refresh token. The
 *       session is destroyed, nothing is revoked (nothing was redeemed), and the outcome is
 *       {@link RefreshOutcome.Kind#FAILED FAILED} with reason {@code credential-rejected}.</li>
 *   <li><strong>{@code REDEEMED}</strong> — the provider redeemed the grant and the gateway refused its
 *       response. The session is destroyed and the one live refresh token the gateway can name is
 *       revoked: the successor when rotated, the presented token when not rotated, none when rotation
 *       is unknown. The outcome is {@link RefreshOutcome.Kind#FAILED FAILED} with reason
 *       {@code redeemed-response-refused}.</li>
 *   <li><strong>Persist failure</strong> — the exchange succeeded but the rotated session could not be
 *       persisted. The presented token is already redeemed, so the session is destroyed and the refresh
 *       token the exchange returned is revoked. The outcome is {@link RefreshOutcome.Kind#FAILED FAILED}
 *       with reason {@code persist-failure}.</li>
 * </ul>
 * Every session-ending disposition records {@code ApiSheriff-111}. The session-ended outcome is published
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
 * runs after near-expiry is re-confirmed and before the back-off. The marker is keyed on the token, not
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
 * already-rotated token and makes no engine call. The pre-redemption back-off is read and written
 * under the same exclusion, so for one session only the leader decides whether an attempt is due.
 * Across stateless instances the back-off is per instance: each instance attempts at most once per
 * window. The per-session windows are bounded ({@link #MAX_BACKOFF_ENTRIES}); once that bound is
 * saturated, the sessions without their own window share one coarse overflow window instead, so an
 * outage large enough to fill the bound is still throttled — at the cost that such a session may see
 * its refresh deferred for up to the fixed back-off even if it has not failed itself. Once an overflow
 * window elapses, exactly one untracked session claims it atomically and probes the identity provider;
 * every other untracked session is deferred with no engine call and no record, so the untracked
 * sessions together make one attempt per window however many arrive at once. A probe the provider
 * processes, or one that fails but can be tracked on its own window, releases the claim; a probe that
 * fails while the map is still saturated keeps the window in force.
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
     * sessions together — the single session that claims the elapsed window's probe — while a transient
     * fault still heals within seconds. Not configurable.
     */
    static final Duration PRE_REDEMPTION_RETRY_BACKOFF = Duration.ofSeconds(5);

    /**
     * The upper bound on sessions carrying their own pending back-off. Once reached, expired entries are
     * pruned; when the map is still full, the failing session is not given an entry — instead one coarse
     * overflow window of {@link #PRE_REDEMPTION_RETRY_BACKOFF} is opened, and every session without its
     * own entry makes no engine call and records nothing until it closes. When it closes, one untracked
     * session claims the probe for the next window and every other one keeps waiting; the claim is
     * released once the identity provider processes a grant or the probe can be tracked on its own window,
     * and kept while the map stays saturated. The throttle therefore degrades rather than disappears, and
     * memory stays bounded — one entry per tracked session plus a single instant — under any number of
     * failing sessions.
     * <p>
     * The accepted trade: while the map is saturated, the untracked sessions share that one window, so a
     * session that has not failed itself may also see its refresh deferred for up to the fixed back-off;
     * its still-valid access token is mediated meanwhile, and only an access token that has actually
     * expired leaves a request without one.
     */
    static final int MAX_BACKOFF_ENTRIES = 10_000;

    /**
     * The upper bound on best-effort revocations in flight at once. A session-ending disposition that
     * finds the bound saturated skips its revocation and records that at {@code DEBUG}, so a slow or
     * hanging revocation endpoint can hold at most this many dispatched tasks. Not configurable.
     */
    static final int MAX_CONCURRENT_REVOCATIONS = 64;

    /** The bounded reason recorded when the identity provider rejected the presented refresh token. */
    static final String REASON_CREDENTIAL_REJECTED = "credential-rejected";

    /** The bounded reason recorded when the gateway refused a response the provider already redeemed. */
    static final String REASON_REDEEMED_RESPONSE_REFUSED = "redeemed-response-refused";

    /** The bounded reason recorded when the rotated session could not be persisted. */
    static final String REASON_PERSIST_FAILURE = "persist-failure";

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
     * opened when a failing session could not be recorded because the map held
     * {@link #MAX_BACKOFF_ENTRIES} unexpired windows. An elapsed window is claimed by moving it, with a
     * compare-and-set against the exact instance read, to the claiming probe's own window; the claim is
     * released by a compare-and-set from that claimed instance back to {@link Instant#MIN}.
     * {@link Instant#MIN} while no window is open — never opened, or released.
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
     *                               revoked through when its session ends after a redemption
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
     *         pre-redemption failure, or {@link RefreshOutcome#failed() failed} when the session was
     *         destroyed
     */
    public RefreshOutcome refresh(SessionRecord session, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (session.refreshToken() == null || !nearExpiry(session, now)) {
            return RefreshOutcome.current(session);
        }
        String sessionId = session.sessionId();
        CompletableFuture<RefreshOutcome> leader = new CompletableFuture<>();
        CompletableFuture<RefreshOutcome> existing = inFlight.putIfAbsent(sessionId, leader);
        if (existing != null) {
            // A concurrent request already leads the refresh for this session — share its result.
            return existing.join();
        }
        Disposition disposition;
        try {
            disposition = performRefresh(sessionId, cookieHeader, now);
            // Publish the outcome to every coalesced waiter before any revocation is issued.
            leader.complete(disposition.outcome());
        } finally {
            // Guarantee coalesced waiters never hang: a no-op when the leader already completed
            // above, but a deterministic FAILED when performRefresh threw before completing it (the
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

    private Disposition performRefresh(String sessionId, @Nullable String cookieHeader, Instant now) {
        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            // Destroyed or expired between the near-expiry check and acquiring the lead — unauthenticated.
            retryNotBefore.remove(sessionId);
            return Disposition.of(RefreshOutcome.failed());
        }
        SessionRecord latest = resolved.get();
        String presentedRefreshToken = latest.refreshToken();
        if (presentedRefreshToken == null || !nearExpiry(latest, now)) {
            // A coalesced leader already rotated this session — share the current token, no engine call.
            return Disposition.of(RefreshOutcome.current(latest));
        }
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
            rotation = refreshExchange.exchange(presentedRefreshToken);
        } catch (RuntimeException refreshFailure) {
            return disposeRefusal(sessionId, latest, presentedRefreshToken, refreshFailure, now, admission);
        }
        // The identity provider processed the grant: whatever follows, the outage the probe tested is over.
        releaseProbe(admission);
        // The presented token is already redeemed here, so a persist failure cannot keep the session.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            SessionRecord rotated = rotate(latest, rotation);
            // persist() re-binds in place (an upsert in server mode, a re-seal in a stateless mode), so
            // no pre-persist destroy is needed on the success path. Destroying first would open a
            // window where a concurrent resolve() misses the rotating session.
            SessionBinding.BoundSession bound = sessionBinding.persist(rotated, now);
            retryNotBefore.remove(sessionId);
            LOGGER.info(BffLogMessages.INFO.TOKEN_REFRESHED);
            return Disposition.of(RefreshOutcome.refreshed(bound.session(), bound.setCookieHeaders()));
        } catch (RuntimeException persistFailure) {
            return endSession(sessionId, latest, now, persistFailure, REASON_PERSIST_FAILURE,
                    rotation.refreshToken());
        }
    }

    private Disposition disposeRefusal(String sessionId, SessionRecord latest, String presentedRefreshToken,
            RuntimeException refreshFailure, Instant now, Admission admission) {
        RefreshFailureClassification classification = RefreshFlow.classify(refreshFailure);
        // A switch expression, not a statement: javac rejects it the moment the engine adds a fourth kind.
        return switch (classification.kind()) {
            case PRE_REDEMPTION -> Disposition.of(backOff(sessionId, latest, refreshFailure, now, admission));
            case CREDENTIAL_REJECTED -> {
                releaseProbe(admission);
                yield endSession(sessionId, latest, now, refreshFailure, REASON_CREDENTIAL_REJECTED, null);
            }
            case REDEEMED -> {
                releaseProbe(admission);
                yield endSession(sessionId, latest, now, refreshFailure, REASON_REDEEMED_RESPONSE_REFUSED,
                        liveRefreshToken(Objects.requireNonNull(classification.redemption(), "redemption"),
                                presentedRefreshToken));
            }
        };
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
     * governed by that entry alone. A session without one proceeds with a single read while no overflow
     * window is open, and is deferred while an overflow window is still in force. Once an overflow window
     * has elapsed, exactly one untracked session claims the probe for it — atomically moving the window to
     * {@code now} plus the back-off, compared against the exact instance just read — and every other
     * untracked session is deferred with no engine call and no record. A probe that hangs past its claimed
     * window lets the next session claim the next window, so attempts stay at one per window.
     */
    private Admission admit(String sessionId, Instant now) {
        Instant own = retryNotBefore.get(sessionId);
        if (own != null) {
            return now.isBefore(own) ? Admission.BACKING_OFF : Admission.ADMITTED;
        }
        while (true) {
            Instant window = overflowNotBefore.get();
            if (Instant.MIN.equals(window)) {
                return Admission.ADMITTED;
            }
            if (now.isBefore(window)) {
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
        if (retryNotBefore.size() >= MAX_BACKOFF_ENTRIES) {
            retryNotBefore.values().removeIf(entry -> !now.isBefore(entry));
        }
        if (retryNotBefore.size() < MAX_BACKOFF_ENTRIES) {
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
     * The disposition of a session kept after a pre-redemption failure: its still-valid access token
     * is mediated, and only an access token that has actually expired leaves the request without one.
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
     * and {@code ApiSheriff-111} recorded. The live refresh token is only handed back; {@link #refresh}
     * revokes it after the outcome has been published to the coalesced waiters.
     * <p>
     * The presented refresh token is {@code latest.refreshToken()}: {@link #performRefresh} only reaches a
     * session-ending disposition after confirming it is present.
     */
    private Disposition endSession(String sessionId, SessionRecord latest, Instant now, RuntimeException failure,
            String reason, @Nullable String liveRefreshToken) {
        String presentedRefreshToken = Objects.requireNonNull(latest.refreshToken(),
                "a session-ending disposition requires the presented refresh token");
        retryNotBefore.remove(sessionId);
        sessionBinding.destroy(latest);
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
     * Rebuilds the session with the rotated token material, carrying every non-token component over
     * from {@code previous} unchanged.
     * <p>
     * Because this reconstructs the record component-by-component, any component NOT copied here is
     * silently dropped from the rotated session. That is load-bearing for
     * {@link SessionRecord#sessionNonce()}: dropping it would make the cookie-mode binding re-seal
     * without a nonce — changing the derived session identity mid-session and breaking the
     * single-flight coalescing this coordinator depends on. Add a copy line here for every component
     * added to {@link SessionRecord}.
     */
    private static SessionRecord rotate(SessionRecord previous, RotationResult rotation) {
        String rotatedIdToken = rotation.idToken();
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
                .build();
    }

    /**
     * What {@link #performRefresh} decided: the outcome to publish and, for a session that ended after a
     * redemption, the one refresh token still live at the identity provider, revoked only after the
     * outcome has been published. {@link #toString()} never renders the token.
     */
    private record Admission(boolean admitted, @Nullable Instant probeClaim) {

        static final Admission ADMITTED = new Admission(true, null);
        static final Admission BACKING_OFF = new Admission(false, null);

        static Admission probe(Instant claim) {
            return new Admission(true, claim);
        }
    }

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
     * The engine refresh seam. The session runtime binds it to the engine as
     * {@code refreshToken -> refreshFlow.refresh(providerMetadata, refreshToken)}; a test binds a
     * stubbed rotation or a throwing stub. The engine owns the refresh grant and refresh-token
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
         * @return the engine rotation result (new access/refresh/ID token + access-token lifetime)
         * @throws RuntimeException when the refresh is refused — before the provider processed the
         *         grant, because the provider rejected the credential (including a replayed refresh
         *         token under strict rotation), or after the provider redeemed it; the coordinator
         *         classifies it through {@code RefreshFlow.classify}
         */
        RotationResult exchange(String refreshToken);
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
     * @param session          the session to mediate from, present for {@link Kind#CURRENT},
     *                         {@link Kind#REFRESHED} and {@link Kind#DEFERRED}, {@code null} for
     *                         {@link Kind#UNAVAILABLE} and {@link Kind#FAILED}
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
            /** No refresh was needed — the mediated token is not yet within {@code leeway} of expiry. */
            CURRENT,
            /** The mediated token was rotated through the engine and persisted. */
            REFRESHED,
            /**
             * The refresh failed before the identity provider processed it; the session is kept and its
             * still-valid access token is mediated.
             */
            DEFERRED,
            /**
             * The refresh failed before the identity provider processed it and the access token has
             * expired; the session is kept, but this request has no token to mediate.
             */
            UNAVAILABLE,
            /** The session was destroyed; the caller treats the request as unauthenticated. */
            FAILED
        }

        /**
         * Canonical constructor enforcing the presence contract.
         */
        public RefreshOutcome {
            Objects.requireNonNull(kind, "kind");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
            boolean carriesSession = switch (kind) {
                case CURRENT, REFRESHED, DEFERRED -> true;
                case UNAVAILABLE, FAILED -> false;
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
