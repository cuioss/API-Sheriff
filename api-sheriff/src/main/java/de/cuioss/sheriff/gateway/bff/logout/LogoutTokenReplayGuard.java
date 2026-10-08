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
package de.cuioss.sheriff.gateway.bff.logout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers the {@code jti} of every accepted back-channel logout token for as long as that token
 * could be accepted again, so each token is acted on once.
 * <p>
 * <strong>How long a value is remembered.</strong> A logout token is accepted only while its
 * {@code iat} lies inside the validator's freshness window. The validator reports the last instant of
 * that window with every accepted token, and the {@code jti} is remembered until that instant has
 * passed. After it the token is refused for its age, so nothing is lost by forgetting it.
 * <p>
 * <strong>Bounded, and closed when full.</strong> At most {@code capacity} values are held. When the
 * bound is reached, values whose instant has passed are dropped; if none has, the token is
 * <em>not admitted</em>. A value that is still inside its window is never dropped to make room: the
 * token it stands for could then be presented a second time and be accepted.
 * <p>
 * <strong>What is held.</strong> The SHA-256 digest of the {@code jti}, not the value, so an entry has
 * a fixed size whatever length the identity provider chose.
 * <p>
 * The memory is per process: a token accepted by one gateway instance is not known to another.
 * <p>
 * Thread-safe; every operation runs under the instance's monitor and never blocks on I/O.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LogoutTokenReplayGuard {

    private final int capacity;
    private final Map<String, Instant> rememberedUntil = new HashMap<>();

    /**
     * @param capacity the largest number of {@code jti} values held at once
     * @throws IllegalArgumentException when {@code capacity} is not positive
     */
    public LogoutTokenReplayGuard(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Admits a token that passed every other check, and remembers it.
     *
     * @param jti             the token's {@code jti}
     * @param acceptableUntil the last instant at which the token would still pass the freshness check
     * @param now             the reference instant
     * @return {@link Admission#ADMITTED} for a token seen for the first time, now remembered;
     *         {@link Admission#REPLAYED} for one already admitted inside its window;
     *         {@link Admission#FULL} when the token is new but cannot be remembered
     */
    public synchronized Admission admit(String jti, Instant acceptableUntil, Instant now) {
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(acceptableUntil, "acceptableUntil");
        Objects.requireNonNull(now, "now");
        String key = digest(jti);
        Instant held = rememberedUntil.get(key);
        if (held != null && !now.isAfter(held)) {
            return Admission.REPLAYED;
        }
        if (held == null && rememberedUntil.size() >= capacity) {
            rememberedUntil.values().removeIf(now::isAfter);
            if (rememberedUntil.size() >= capacity) {
                return Admission.FULL;
            }
        }
        rememberedUntil.put(key, acceptableUntil);
        return Admission.ADMITTED;
    }

    private static String digest(String jti) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(jti.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    /**
     * The outcome of {@link #admit}.
     *
     * @since 1.0
     */
    public enum Admission {

        /** The token was not seen before and is now remembered. */
        ADMITTED,

        /** A token with this {@code jti} was already admitted and is still inside its window. */
        REPLAYED,

        /** The token is new, the memory is at its bound and holds no value that may be dropped. */
        FULL
    }
}
