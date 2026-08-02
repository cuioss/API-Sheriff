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
package de.cuioss.sheriff.gateway.bff.refresh;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.TokenSheriffException;
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
 * <strong>no</strong> OAuth leg: the engine owns the refresh grant, refresh-token rotation, and —
 * inside its own refresh-token-family primitive — the <em>reuse detection</em> that revokes a
 * family when a superseded refresh token is replayed (a stolen-token signal, RFC 9700). The engine
 * surfaces that as a {@link TokenSheriffException}; the coordinator's residual is to
 * <em>destroy the session</em> on any refresh failure so a revoked family can never be mediated
 * from again.
 * <p>
 * <strong>Single-flight per session.</strong> Concurrent requests on one session share one
 * refresh: the first request to observe near-expiry becomes the in-flight leader (registered
 * atomically in {@link #inFlight}, keyed on {@link SessionRecord#sessionId()} — the stable
 * per-session identity every binding populates — so the check-then-act TOCTOU window is closed by
 * the map's atomic {@code putIfAbsent}, the per-session mutual-exclusion primitive); every
 * concurrent request on the same session joins the leader's result instead of launching its own
 * refresh. The leader re-resolves the session through the binding under this exclusion and
 * re-checks near-expiry, so a request that arrives just after a refresh completed observes the
 * already-rotated token and makes no engine call.
 * <p>
 * <strong>On-failure semantics.</strong> A {@link RefreshOutcome#failed() failed} outcome means the
 * session has already been destroyed and the caller MUST treat the request as
 * <em>unauthenticated</em>, running the same content negotiation as {@code require: session}: a
 * navigation request (its {@code Accept} offers {@code text/html}) is re-driven through login,
 * anything else gets {@code 401 application/problem+json}. This class is framework-agnostic — it
 * carries no request/response coupling and is unit-testable without a container or a live IdP; the
 * session runtime performs the negotiation and binds the engine seams.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TokenRefreshCoordinator {

    private static final CuiLogger LOGGER = new CuiLogger(TokenRefreshCoordinator.class);

    /**
     * The bounded, non-sensitive reason recorded on a refresh failure. Engine rejection and
     * engine-detected refresh-token reuse are deliberately reported as one disposition: both end the
     * session identically, and distinguishing them in a log line would tell an attacker whether a
     * replayed token was recognised as reuse.
     */
    private static final String REFRESH_FAILURE_REASON = "engine rejection or refresh-token reuse";

    private final Duration leeway;
    private final AccessTokenExpiry accessTokenExpiry;
    private final RefreshExchange refreshExchange;
    private final SessionBinding sessionBinding;
    private final ConcurrentMap<String, CompletableFuture<RefreshOutcome>> inFlight = new ConcurrentHashMap<>();

    /**
     * Assembles the coordinator with the refresh leeway, the engine seams, and the session binding.
     *
     * @param leeway            how long before access-token expiry a refresh is triggered
     *                          ({@code session.refresh.leeway_seconds})
     * @param accessTokenExpiry the mediated-access-token expiry seam (bound to the engine token
     *                          parsing; a test binds a fixed instant)
     * @param refreshExchange   the engine refresh seam (bound to {@code RefreshFlow#refresh}; a test
     *                          binds a stubbed rotation or a throwing stub)
     * @param sessionBinding    the mode-neutral session binding the rotated record is persisted
     *                          through and the failed session is destroyed through
     */
    public TokenRefreshCoordinator(Duration leeway, AccessTokenExpiry accessTokenExpiry,
            RefreshExchange refreshExchange, SessionBinding sessionBinding) {
        this.leeway = Objects.requireNonNull(leeway, "leeway");
        this.accessTokenExpiry = Objects.requireNonNull(accessTokenExpiry, "accessTokenExpiry");
        this.refreshExchange = Objects.requireNonNull(refreshExchange, "refreshExchange");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
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
     *         session, or {@link RefreshOutcome#failed() failed} when the session was destroyed
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
        try {
            RefreshOutcome outcome = performRefresh(cookieHeader, now);
            leader.complete(outcome);
            return outcome;
        } finally {
            // Guarantee coalesced waiters never hang: a no-op when the leader already completed
            // above, but a deterministic FAILED when performRefresh threw before completing it (the
            // unexpected exception still propagates out of this method for the leader's own caller).
            leader.complete(RefreshOutcome.failed());
            inFlight.remove(sessionId, leader);
        }
    }

    private RefreshOutcome performRefresh(@Nullable String cookieHeader, Instant now) {
        Optional<SessionRecord> resolved = sessionBinding.resolve(cookieHeader, now);
        if (resolved.isEmpty()) {
            // Destroyed or expired between the near-expiry check and acquiring the lead — unauthenticated.
            return RefreshOutcome.failed();
        }
        SessionRecord latest = resolved.get();
        String presentedRefreshToken = latest.refreshToken();
        if (presentedRefreshToken == null || !nearExpiry(latest, now)) {
            // A coalesced leader already rotated this session — share the current token, no engine call.
            return RefreshOutcome.current(latest);
        }
        try {
            RotationResult rotation = refreshExchange.exchange(presentedRefreshToken);
            SessionRecord rotated = rotate(latest, rotation);
            // persist() re-binds in place (an upsert in server mode, a re-seal in a stateless mode), so
            // no pre-persist destroy is needed on the success path. Destroying first would open a
            // window where a concurrent resolve() misses the rotating session.
            SessionBinding.BoundSession bound = sessionBinding.persist(rotated, now);
            LOGGER.info(BffLogMessages.INFO.TOKEN_REFRESHED);
            return RefreshOutcome.refreshed(bound.session(), bound.setCookieHeaders());
        } catch (TokenSheriffException refreshFailure) {
            // Engine failure OR refresh-token reuse (the engine revoked the family) — the session can no
            // longer be sustained. Destroy it so the caller re-drives login / returns 401.
            sessionBinding.destroy(latest);
            // Bounded, non-sensitive reason only — never the presented refresh token or session id.
            LOGGER.warn(refreshFailure, BffLogMessages.WARN.SESSION_REFRESH_FAILED, REFRESH_FAILURE_REASON);
            return RefreshOutcome.failed();
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
     * stubbed rotation or a throwing stub. The engine owns the refresh grant, refresh-token
     * rotation, and reuse detection; keeping the confidential-client wiring (provider metadata,
     * client authentication) behind the seam decouples the coordinator from it.
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
         * @throws de.cuioss.sheriff.token.commons.error.TokenSheriffException when the refresh fails
         *         (IdP rejection) or the engine detects refresh-token reuse and revokes the family
         */
        RotationResult exchange(String refreshToken);
    }

    /**
     * The framework-agnostic result of a refresh attempt. Token material is never disclosed to the
     * browser — the rotated {@link SessionRecord} crosses only inside the binding's own cookie
     * representation, which is opaque in server mode and authenticated-encrypted in a stateless mode.
     *
     * @param kind             which of the three refresh outcomes occurred
     * @param session          the session to mediate from, present for {@link Kind#CURRENT} and
     *                         {@link Kind#REFRESHED}, {@code null} for {@link Kind#FAILED}
     * @param setCookieHeaders the {@code Set-Cookie} header values the re-bind produced, empty when
     *                         the binding needs no new cookie
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record RefreshOutcome(Kind kind, @Nullable SessionRecord session, List<String> setCookieHeaders) {

        /**
         * The three terminal states of a refresh attempt.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        public enum Kind {
            /** No refresh was needed — the mediated token is not yet within {@code leeway} of expiry. */
            CURRENT,
            /** The mediated token was rotated through the engine and persisted. */
            REFRESHED,
            /** The refresh failed and the session was destroyed; the caller treats it as unauthenticated. */
            FAILED
        }

        /**
         * Canonical constructor enforcing the presence contract.
         */
        public RefreshOutcome {
            Objects.requireNonNull(kind, "kind");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
            if (kind != Kind.FAILED && session == null) {
                throw new IllegalArgumentException("a " + kind + " outcome must carry a session");
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
         * A failed-refresh outcome carrying no session — the session has been destroyed and the
         * caller must treat the request as unauthenticated (navigation re-driven through login, any
         * other request answered {@code 401 application/problem+json}).
         *
         * @return the failed outcome
         */
        public static RefreshOutcome failed() {
            return new RefreshOutcome(Kind.FAILED, null, List.of());
        }

        /**
         * @return {@code true} when the refresh failed and the session was destroyed
         */
        public boolean isFailure() {
            return kind == Kind.FAILED;
        }
    }
}
