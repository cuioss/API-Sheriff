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
package de.cuioss.sheriff.gateway.bff.session;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * A single server-side session (D3, {@code mode: server}).
 * <p>
 * The record holds the mediated token material — the access token injected as
 * {@code Authorization: Bearer} on proxied requests, the optional refresh token, and the raw
 * ID token retained as the {@code id_token_hint} at logout — plus the session metadata
 * ({@code expiry}, {@code acr}, {@code auth_time}, {@code sid}, {@code sub}). The token material
 * <strong>never leaves the server</strong>: the browser only ever carries the opaque
 * {@link #sessionId()} in the session cookie, so {@link #toString()} redacts every credential
 * (the session id itself is a bearer credential) to keep tokens out of logs and stack traces.
 * <p>
 * {@link #sid()} and {@link #sub()} back the store's secondary index for O(1) back-channel
 * logout destruction.
 *
 * @param sessionId    the opaque session id (store key and session-cookie value)
 * @param accessToken  the mediated access token injected as the upstream bearer
 * @param refreshToken the refresh token, empty when the IdP granted none
 * @param idToken      the raw ID token retained for the logout {@code id_token_hint}
 * @param sub          the subject claim (back-channel destroy-by-sub key)
 * @param sid          the IdP session id claim, empty when absent (back-channel destroy-by-sid key)
 * @param expiresAt    the absolute session expiry (from login), independent of activity
 * @param acr          the authentication context class, empty when absent
 * @param authTime     the IdP authentication instant, empty when absent
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record SessionRecord(String sessionId, String accessToken, Optional<String> refreshToken, String idToken,
        String sub, Optional<String> sid, Instant expiresAt, Optional<String> acr, Optional<Instant> authTime) {

    private static final String REDACTED = "***REDACTED***";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SESSION_ID_BYTES = 32;

    /**
     * Canonical constructor rejecting absent mandatory components and normalizing absent
     * optionals to {@link Optional#empty()}.
     */
    public SessionRecord {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(expiresAt, "expiresAt");
        refreshToken = Objects.requireNonNullElse(refreshToken, Optional.empty());
        sid = Objects.requireNonNullElse(sid, Optional.empty());
        acr = Objects.requireNonNullElse(acr, Optional.empty());
        authTime = Objects.requireNonNullElse(authTime, Optional.empty());
    }

    /**
     * Generates a 256-bit URL-safe opaque session id.
     *
     * @return the base64url (unpadded) encoding of 32 secure-random bytes
     */
    public static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Whether this session has expired at {@code now} — expiry is inclusive of the boundary.
     *
     * @param now the reference instant
     * @return {@code true} when {@code now} is at or after {@link #expiresAt()}
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Overridden to redact every credential — the session id and all three tokens. The default
     * record {@code toString()} would otherwise print the bearer session id and the raw token
     * material into any log line, exception message, or debugger view.
     *
     * @return a string representation with all credential-bearing fields redacted
     */
    @Override
    public String toString() {
        return "SessionRecord[sessionId=%s, accessToken=%s, refreshToken=%s, idToken=%s, sub=%s, sid=%s, expiresAt=%s, acr=%s, authTime=%s]"
                .formatted(REDACTED, REDACTED, refreshToken.isPresent() ? "Optional[" + REDACTED + "]" : "Optional.empty",
                        REDACTED, sub, sid, expiresAt, acr, authTime);
    }
}
