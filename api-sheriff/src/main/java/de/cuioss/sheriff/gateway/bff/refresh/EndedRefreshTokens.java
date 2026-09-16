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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


import de.cuioss.tools.logging.CuiLogger;

/**
 * The gateway-side amplification guard for a replayed cookie-mode refresh token whose session the
 * refresh coordinator has already ended — a bounded, per-instance negative cache of ended refresh
 * tokens.
 * <p>
 * <strong>Why it exists.</strong> In cookie mode {@code SessionBinding.destroy} holds nothing
 * server-side, so a session ended for {@code credential-rejected}, {@code redeemed-response-refused} or
 * {@code persist-failure} is ended only by the clearing {@code Set-Cookie}. A client that retains or
 * replays the sealed cookie would otherwise drive a fresh refresh grant to the identity provider, and a
 * {@code WARN ApiSheriff-111} with stack trace, on every near-expiry request until the absolute session
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
 * token is simply not recorded, so a later replay degrades to one identity-provider call rather than
 * failing open.
 * <p>
 * <strong>Mode.</strong> Only cookie mode binds {@link #bounded()}; server mode binds {@link #inert()},
 * because {@code destroy} already removes the server-side session there.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public sealed interface EndedRefreshTokens permits EndedRefreshTokens.Inert, EndedRefreshTokens.Bounded {

    /**
     * The upper bound on marked refresh tokens per instance. Once reached, expired markers are pruned
     * first; if the store is still full the token is not recorded. Not configurable.
     */
    int MAX_ENTRIES = 10_000;

    /**
     * Marks a refresh token whose session was ended, until the session's absolute lifetime.
     *
     * @param refreshToken the refresh token the ended session presented
     * @param expiresAt    the session's absolute lifetime ({@code SessionRecord#expiresAt()}); the marker
     *                     never outlives it
     * @param now          the reference instant
     */
    void markEnded(String refreshToken, Instant expiresAt, Instant now);

    /**
     * Whether a refresh token was marked ended and the marker has not yet expired.
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
        return new Bounded(new SecureRandom());
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

    /**
     * The cookie-mode binding: a bounded {@link ConcurrentHashMap} from a salted token digest to the
     * marker's expiry. Thread-safe; the bound may be exceeded transiently by at most the number of
     * concurrent writers, exactly as the coordinator's own back-off map.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    final class Bounded implements EndedRefreshTokens {

        private static final CuiLogger LOGGER = new CuiLogger(Bounded.class);
        private static final String DIGEST_ALGORITHM = "SHA-256";
        private static final int SALT_BYTES = 32;

        private final byte[] salt;
        private final ConcurrentMap<String, Instant> ended = new ConcurrentHashMap<>();

        /**
         * @param random the source the per-instance salt is drawn from
         */
        Bounded(SecureRandom random) {
            Objects.requireNonNull(random, "random");
            this.salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
        }

        @Override
        public void markEnded(String refreshToken, Instant expiresAt, Instant now) {
            Objects.requireNonNull(refreshToken, "refreshToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(now, "now");
            if (!now.isBefore(expiresAt)) {
                // The session is already past its absolute lifetime; resolve() refuses it without us.
                return;
            }
            String key = digest(refreshToken);
            if (ended.computeIfPresent(key, (digest, previous) -> expiresAt) != null) {
                return;
            }
            if (ended.size() >= MAX_ENTRIES) {
                ended.values().removeIf(expiry -> !now.isBefore(expiry));
            }
            if (ended.size() < MAX_ENTRIES) {
                ended.put(key, expiresAt);
                return;
            }
            // Never evict a live marker: a replay of this token degrades to one identity-provider call.
            LOGGER.debug("Ended refresh-token marker store is full — token not recorded");
        }

        @Override
        public boolean isEnded(String refreshToken, Instant now) {
            Objects.requireNonNull(refreshToken, "refreshToken");
            Objects.requireNonNull(now, "now");
            Instant expiry = ended.get(digest(refreshToken));
            return expiry != null && now.isBefore(expiry);
        }

        /**
         * The salted, non-reversible key a refresh token is held under. Package-private so a test can
         * show two instances key the same token differently.
         *
         * @param refreshToken the refresh token
         * @return the URL-safe Base64 SHA-256 digest over this instance's salt and the token
         */
        String digest(String refreshToken) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(DIGEST_ALGORITHM + " is required for the ended refresh-token marker",
                        unavailable);
            }
            digest.update(salt);
            digest.update(refreshToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        }

        /**
         * @return the number of markers currently held, expired ones included until pruned
         */
        int size() {
            return ended.size();
        }
    }
}
