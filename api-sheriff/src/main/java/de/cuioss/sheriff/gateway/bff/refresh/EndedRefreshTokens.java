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

import java.security.SecureRandom;
import java.time.Instant;

/**
 * The gateway-side amplification guard for a replayed cookie-mode refresh token whose session the
 * refresh coordinator has already ended — a bounded, per-instance negative cache of ended refresh
 * tokens.
 * <p>
 * <strong>Why it exists.</strong> In cookie mode {@code SessionBinding.destroy} holds nothing
 * server-side, so a session the refresh coordinator ends there — for whichever of the reasons
 * {@link TokenRefreshCoordinator} defines — is ended only by the clearing {@code Set-Cookie}. A client
 * that retains or replays the sealed cookie would otherwise drive a fresh refresh grant to the identity
 * provider, and a {@code WARN ApiSheriff-111} with stack trace, until the absolute session
 * TTL. {@link TokenRefreshCoordinator} marks the presented refresh token when it ends such a session and
 * refuses a marked token locally, with no engine call, no revocation and no warning record.
 * <p>
 * <strong>Keyed on the token, not the session.</strong> All cookies of one cookie-mode session share one
 * derived session identity, so a session-keyed marker would also refuse the legitimate successor cookie.
 * The key is the refresh-token generation instead: the exact replayed ended cookie is refused locally,
 * while a successor cookie — a different refresh token — still reaches the identity provider once, is
 * refused there, and is then marked itself. Reuse detection stays the identity provider's job
 * (ADR-0046); this is not a token family.
 * <p>
 * <strong>What is held.</strong> Only a salted, non-reversible SHA-256 digest of the refresh token, the
 * salt drawn from {@link SecureRandom} per instance — never the raw token and never the cookie value, in
 * memory or in any log. A marker expires at the session's absolute lifetime. The store is in memory,
 * per instance and bounded by {@link #MAX_ENTRIES}: it does not survive a restart, a sibling instance
 * still makes one identity-provider call before it marks the token itself, and when the bound is full a
 * token is simply not recorded, so while the store stays full every later replay of that token makes one
 * identity-provider call per replayed request — the provider refuses each one, and the store never fails
 * open.
 * <p>
 * <strong>Mode.</strong> Only cookie mode binds {@link #bounded()}; server mode binds {@link #inert()},
 * because {@code destroy} already removes the server-side session there.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public sealed interface EndedRefreshTokens permits EndedRefreshTokens.Inert, BoundedEndedRefreshTokens {

    /**
     * The upper bound on marked refresh tokens per instance. Once reached, expired markers are pruned
     * first; if the store is still full the token is not recorded. Not configurable.
     */
    int MAX_ENTRIES = 10_000;

    /**
     * Marks a refresh token whose session was ended, until the session's absolute lifetime.
     * <p>
     * Every argument is non-null (the package is {@code @NullMarked}); the implementations do not
     * re-check, because the only production caller, {@link TokenRefreshCoordinator}, guarantees it.
     *
     * @param refreshToken the refresh token the ended session presented
     * @param expiresAt    the session's absolute lifetime ({@code SessionRecord#expiresAt()}); the marker
     *                     never outlives it
     * @param now          the reference instant
     */
    void markEnded(String refreshToken, Instant expiresAt, Instant now);

    /**
     * Whether a refresh token was marked ended and the marker has not yet expired.
     * <p>
     * Every argument is non-null (the package is {@code @NullMarked}); the implementations do not
     * re-check, because the only production caller, {@link TokenRefreshCoordinator}, guarantees it.
     *
     * @param refreshToken the presented refresh token
     * @param now          the reference instant
     * @return {@code true} when the token is marked and {@code now} is before the marker's expiry
     */
    boolean isEnded(String refreshToken, Instant now);

    /**
     * The inert binding for server mode: marks nothing and never reports a token ended.
     *
     * @return the shared inert instance
     */
    static EndedRefreshTokens inert() {
        return Inert.INSTANCE;
    }

    /**
     * The bounded in-memory binding for cookie mode, salted per instance from {@link SecureRandom}.
     *
     * @return a new, empty bounded store
     */
    static EndedRefreshTokens bounded() {
        return new BoundedEndedRefreshTokens(new SecureRandom());
    }

    /**
     * The server-mode binding. Stateless and therefore thread-safe.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    enum Inert implements EndedRefreshTokens {
        /** The single inert instance. */
        INSTANCE;

        @Override
        public void markEnded(String refreshToken, Instant expiresAt, Instant now) {
            // Server mode ends a session by removing it from the store; there is nothing to remember.
        }

        @Override
        public boolean isEnded(String refreshToken, Instant now) {
            return false;
        }
    }
}
