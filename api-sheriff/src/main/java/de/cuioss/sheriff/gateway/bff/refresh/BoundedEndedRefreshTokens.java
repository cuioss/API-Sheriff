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
 * The cookie-mode binding: a bounded {@link ConcurrentHashMap} from a salted token digest to the
 * marker's expiry. Thread-safe; the bound may be exceeded transiently by at most the number of
 * concurrent writers, exactly as the coordinator's own back-off map.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BoundedEndedRefreshTokens implements EndedRefreshTokens {

    private static final CuiLogger LOGGER = new CuiLogger(BoundedEndedRefreshTokens.class);
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int SALT_BYTES = 32;

    private final byte[] salt;
    private final ConcurrentMap<String, Instant> ended = new ConcurrentHashMap<>();

    /**
     * @param random the source the per-instance salt is drawn from
     */
    BoundedEndedRefreshTokens(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        this.salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
    }

    @Override
    public void markEnded(String refreshToken, Instant expiresAt, Instant now) {
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
        // Never evict a live marker: while the store stays full, every replay of this token makes one
        // identity-provider call per replayed request.
        LOGGER.debug("Ended refresh-token marker store is full — token not recorded");
    }

    @Override
    public boolean isEnded(String refreshToken, Instant now) {
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
