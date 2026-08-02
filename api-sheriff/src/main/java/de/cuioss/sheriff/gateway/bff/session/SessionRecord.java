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

import lombok.Builder;
import org.jspecify.annotations.Nullable;

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
 *       from the sealed payload (a salted digest over the login instant, {@code sub} and the
 *       per-session {@link #sessionNonce()}), so it is
 *       stable across re-seals, un-recomputable outside this gateway, and never emitted to the
 *       browser.</li>
 * </ul>
 * It is redacted from {@link #toString()} either way.
 * <p>
 * <strong>The session nonce is cookie-mode-only.</strong> {@link #sessionNonce()} is
 * {@code null} for every server-mode record — that mode's identity is the minted opaque
 * id, which is already unique, so it needs no additional entropy. Only the cookie-mode binding
 * populates it, minting it once at login and re-sealing it verbatim on every refresh; without it,
 * two logins by the same subject inside one clock second would derive the same identity and
 * cross-wire the refresh coordinator's single-flight keying. It is redacted from
 * {@link #toString()} because it keys the derived identity.
 * <p>
 * {@link #sid()} and {@link #sub()} back a server-mode store's secondary index for O(1)
 * back-channel logout destruction.
 *
 * @param sessionId    the stable per-session identity (see the identity model above)
 * @param accessToken  the mediated access token injected as the upstream bearer
 * @param refreshToken the refresh token, {@code null} when the IdP granted none
 * @param idToken      the raw ID token retained for the logout {@code id_token_hint}
 * @param sub          the subject claim (back-channel destroy-by-sub key)
 * @param sid          the IdP session id claim, {@code null} when absent (back-channel destroy-by-sid key)
 * @param expiresAt    the absolute session expiry (from login), independent of activity
 * @param acr          the authentication context class, {@code null} when absent
 * @param authTime     the IdP authentication instant, {@code null} when absent
 * @param sessionNonce the per-session nonce keying the cookie-mode derived identity; always
 *                     {@code null} in server mode (see the mode split above), and never blank when present
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record SessionRecord(
String sessionId,
String accessToken,
@Nullable String refreshToken,
String idToken,
String sub,
@Nullable String sid,
Instant expiresAt,
@Nullable String acr,
@Nullable Instant authTime,
@Nullable String sessionNonce) {

    private static final String REDACTED = "***REDACTED***";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SESSION_ID_BYTES = 32;

    /**
     * Canonical constructor rejecting absent mandatory components.
     * <p>
     * {@code sessionNonce} stays optional — an <em>absent</em> ({@code null}) nonce is valid and is
     * the normal server-mode shape. Only a <em>present but blank</em> value is rejected: it keys the
     * cookie-mode derived identity, so an empty string would silently degrade that identity back to
     * the colliding pre-nonce shape instead of failing. The nonce value itself never reaches the
     * exception message.
     *
     * @throws NullPointerException     when a mandatory component is {@code null}
     * @throws IllegalArgumentException when {@code sessionNonce} is present but blank
     */
    public SessionRecord {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(idToken, "idToken");
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (sessionNonce != null && sessionNonce.isBlank()) {
            throw new IllegalArgumentException("sessionNonce must not be blank when present");
        }
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
     * Overridden to redact every credential — the session id, all three tokens, and the session
     * nonce that keys the cookie-mode derived identity. The default
     * record {@code toString()} would otherwise print the bearer session id and the raw token
     * material into any log line, exception message, or debugger view.
     *
     * @return a string representation with all credential-bearing fields redacted
     */
    @Override
    public String toString() {
        return "SessionRecord[sessionId=%s, accessToken=%s, refreshToken=%s, idToken=%s, sub=%s, sid=%s, expiresAt=%s, acr=%s, authTime=%s, sessionNonce=%s]"
                .formatted(REDACTED, REDACTED, refreshToken == null ? "null" : REDACTED,
                        REDACTED, sub, sid, expiresAt, acr, authTime,
                        sessionNonce == null ? "null" : REDACTED);
    }
}
