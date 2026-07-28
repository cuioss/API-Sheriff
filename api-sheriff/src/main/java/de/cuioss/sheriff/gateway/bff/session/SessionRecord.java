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
 * A single authenticated session, as held by whichever {@link SessionBinding} is in force.
 * <p>
 * The record holds the mediated token material — the access token injected as
 * {@code Authorization: Bearer} on proxied requests, the optional refresh token, and the raw
 * ID token retained as the {@code id_token_hint} at logout — plus the session metadata
 * ({@code expiry}, {@code acr}, {@code auth_time}, {@code sid}, {@code sub}). The token material is
 * <strong>never disclosed in the clear</strong>, so {@link #toString()} redacts every credential to
 * keep tokens out of logs and stack traces.
 * <p>
 * <strong>Session identity.</strong> {@link #sessionId()} is the one identity model the seam
 * defines: a stable per-session identity every {@link SessionBinding} populates, and the key the
 * refresh coordinator uses for single-flight coalescing. Login always mints one with
 * {@link #newSessionId()} before the mode-neutral {@link SessionBinding#bind}, but only server mode
 * keeps it:
 * <ul>
 *   <li><strong>server mode</strong>: the minted id becomes the opaque store key, and the session
 *       cookie carries it <em>in the clear</em> — the cookie value IS this identity, which is why it
 *       is itself a bearer credential;</li>
 *   <li><strong>cookie mode</strong>: the minted id is <em>discarded</em> — it is not among the
 *       fields sealed into the cookie. The binding replaces it with an identity <em>derived</em>
 *       from the sealed payload (a salted digest over the login instant and {@code sub}), so it is
 *       stable across re-seals, un-recomputable outside this gateway, and never emitted to the
 *       browser.</li>
 * </ul>
 * It is redacted from {@link #toString()} either way.
 * <p>
 * {@link #sid()} and {@link #sub()} back a server-mode store's secondary index for O(1)
 * back-channel logout destruction.
 *
 * @param sessionId    the stable per-session identity (see the identity model above)
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
     * Generates a 256-bit URL-safe opaque session id — the <strong>server-mode</strong> session
     * identity, which is also the value carried in that mode's session cookie.
     * <p>
     * Login calls this before the mode-neutral {@link SessionBinding#bind}, so a cookie-mode login
     * mints one too — but that binding discards it and derives its own identity from the sealed
     * payload instead. Nothing downstream may assume the minted value survives {@code bind}.
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
