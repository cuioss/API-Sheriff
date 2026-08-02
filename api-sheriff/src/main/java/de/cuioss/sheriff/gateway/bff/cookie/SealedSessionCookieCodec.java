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
package de.cuioss.sheriff.gateway.bff.cookie;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The AES-256-GCM sealed-session cookie codec (D1, {@code session.mode: cookie}) — the
 * cryptographic core of the stateless BFF variant.
 * <p>
 * <strong>Cookie value layout.</strong> {@code version(1B) || key-id(1B) || nonce(12B) ||
 * ciphertext || tag(16B)}, base64url-encoded without padding. The {@code version}, {@code key-id},
 * and the cookie <em>name</em> are bound into the GCM <em>associated data</em>, so a sealed value
 * cannot be replayed under a different format version, a different key generation, or a different
 * cookie name — the tag check fails and the value unseals to "no session".
 * <p>
 * <strong>Nonce discipline.</strong> {@link #seal} draws a fresh 96-bit nonce from
 * {@link SecureRandom} on <em>every</em> call. The nonce is never derived from the payload and
 * never counter-based: GCM nonce reuse under one key is catastrophic (it leaks the authentication
 * subkey and the XOR of the two plaintexts), so the only safe construction here is a fresh random
 * nonce per seal.
 * <p>
 * <strong>Fail-closed unsealing.</strong> {@link #unseal} reads the {@code version} and
 * {@code key-id} and selects the key <em>deterministically</em> — never a try-every-key decrypt.
 * Any authentication-tag failure, malformed length, unknown version, or unknown key id returns
 * {@link Optional#empty()}: a tampered cookie is "no session", never a {@code 500}. The rejection
 * is logged with its non-sensitive disposition only.
 * <p>
 * <strong>Exactly one key.</strong> The codec holds a single sealing key and stamps its key id into
 * every value; there is no decrypt-only companion key and no in-flight rotation state. Changing the
 * configured key is a clean break: every value still sealed under the withdrawn key carries an
 * unknown key id and is therefore <em>unauthenticated</em> — "no session", never an error — so the
 * browser simply re-authenticates.
 * <p>
 * <strong>Size budget.</strong> A sealed value larger than the configured
 * {@linkplain #maxCookieValueBytes() budget} fails the seal with
 * {@link CookieSizeBudgetExceededException} and a logged warning — never a silent truncation. Cookie
 * splitting across multiple {@code Set-Cookie} headers is a deliberate non-goal; an operator whose
 * token set does not fit is expected to reduce it or run server mode.
 * <p>
 * The budget is <strong>one declared number</strong> ({@code oidc.session.max_cookie_size},
 * defaulting to {@link #DEFAULT_COOKIE_VALUE_BUDGET}) that drives BOTH ends of the round trip: the
 * seal-time budget enforced here, and the gateway's pre-route {@code Cookie} header-value cap in
 * {@code GatewayEdgeRoute}. Encoding the same limit as two independent constants is what made
 * cookie mode unusable before — every request carrying a live sealed cookie was rejected {@code 400}
 * at the edge by a 2048-character header-value cap the 4096-byte seal budget contradicted.
 * <p>
 * <strong>Absolute lifetime.</strong> {@link #toSetCookieHeader} sets {@code Max-Age} to the
 * <em>remaining</em> lifetime computed from the payload's login instant, so a re-seal after a token
 * refresh never extends the session. The header reuses the landed hardening: the {@code __Host-}
 * prefix, {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}, and {@code Path=/}.
 * <p>
 * The codec is framework-agnostic and safe for concurrent use — it holds only immutable
 * configuration and a thread-safe {@link SecureRandom}, and creates a fresh {@link Cipher} per
 * operation.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SealedSessionCookieCodec {

    private static final CuiLogger LOGGER = new CuiLogger(SealedSessionCookieCodec.class);

    /**
     * The current sealed-cookie format version, bound into the GCM associated data.
     * <p>
     * Version {@code 2} carries the nine-field payload that added the per-session nonce keying the
     * derived session identity. The bump is a clean break with no migration path: {@link #unseal}
     * reads the leading version byte and rejects anything other than {@code FORMAT_VERSION} at an
     * explicit gate, before a {@link Cipher} is even constructed — so a version-1 cookie is refused
     * outright rather than being mis-parsed against the nine-field shape, and decryption is never
     * attempted for it. The version byte is additionally bound into the GCM associated data, so it
     * cannot be forged onto a value sealed under a different version either. Pre-existing cookie
     * sessions are therefore refused fail-closed and the browser simply re-logs in.
     */
    public static final byte FORMAT_VERSION = 2;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 2 + NONCE_BYTES;
    private static final int MIN_SEALED_BYTES = HEADER_BYTES + (TAG_BITS / 8);

    /**
     * The default sealed cookie-value size budget in bytes (~4 KB). Browsers are only required to
     * accept 4096 bytes per cookie, so a larger value risks being silently dropped by the browser —
     * which is why the default sits exactly there rather than at the gateway's transport ceiling.
     */
    public static final int DEFAULT_COOKIE_VALUE_BUDGET = 4096;

    /**
     * The smallest configurable budget: the encoded length of a sealed value carrying an
     * <em>empty</em> plaintext ({@code version || key-id || nonce || tag}, base64url without
     * padding). A budget below this could not admit even a structurally minimal sealed value, so it
     * is refused at boot rather than failing every seal at runtime.
     */
    public static final int COOKIE_VALUE_BUDGET_FLOOR = (4 * MIN_SEALED_BYTES + 2) / 3;

    /**
     * The largest configurable budget (8 KiB), deliberately kept well below the gateway's 16 KiB
     * inbound request-header-block limit ({@code EdgeHardeningOptions}). The {@code Cookie} header
     * shares that block with every other inbound header, so a budget at or above the transport
     * ceiling would produce a value the seal accepts but the transport rejects with {@code 431}.
     */
    public static final int COOKIE_VALUE_BUDGET_CEILING = 8192;

    private static final String DISPOSITION_MALFORMED = "malformed";
    private static final String DISPOSITION_UNKNOWN_VERSION = "unknown-version";
    private static final String DISPOSITION_UNKNOWN_KEY_ID = "unknown-key-id";
    private static final String DISPOSITION_TAG = "authentication-tag";
    private static final String DISPOSITION_PAYLOAD = "payload-format";

    private final SecureRandom secureRandom = new SecureRandom();
    private final String cookieName;
    private final Duration sessionTtl;
    private final int maxCookieValueBytes;
    private final SecretKey currentKey;
    private final byte currentKeyId;

    /**
     * Assembles the codec over its one sealing key — every value is sealed and unsealed under that
     * single key.
     *
     * @param cookieName          the session-cookie name (bound into the associated data)
     * @param sessionTtl          the absolute session lifetime from login
     * @param maxCookieValueBytes the sealed cookie-value size budget, the ONE declared number that
     *                            also drives the gateway's pre-route {@code Cookie} header-value cap
     * @param currentKey          the AES-256 key values are sealed under
     * @param currentKeyId        the id identifying {@code currentKey} in the cookie header
     */
    public SealedSessionCookieCodec(String cookieName, Duration sessionTtl, int maxCookieValueBytes,
            SecretKey currentKey, byte currentKeyId) {
        this.cookieName = requireNonBlank(cookieName);
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.maxCookieValueBytes = requireViableBudget(maxCookieValueBytes);
        this.currentKey = Objects.requireNonNull(currentKey, "currentKey");
        this.currentKeyId = currentKeyId;
    }

    /**
     * Seals a payload into the cookie value.
     *
     * @param payload the session payload to seal
     * @return the base64url-encoded sealed cookie value
     * @throws CookieSizeBudgetExceededException when the sealed value exceeds the configured
     *         {@linkplain #maxCookieValueBytes() budget}
     */
    public String seal(SealedSessionPayload payload) throws CookieSizeBudgetExceededException {
        Objects.requireNonNull(payload, "payload");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);

        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(FORMAT_VERSION, currentKeyId));
            sealed = cipher.doFinal(payload.encode());
        } catch (GeneralSecurityException sealingFailure) {
            // A misconfigured key (wrong algorithm or length) is an operator error the gateway
            // cannot serve around — unlike unsealing, sealing has no "no session" fallback.
            throw new IllegalStateException("cookie-mode session sealing failed", sealingFailure);
        }

        byte[] value = ByteBuffer.allocate(HEADER_BYTES + sealed.length)
                .put(FORMAT_VERSION).put(currentKeyId).put(nonce).put(sealed).array();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        if (encoded.length() > maxCookieValueBytes) {
            LOGGER.warn(BffLogMessages.WARN.COOKIE_SIZE_BUDGET_EXCEEDED, encoded.length());
            throw new CookieSizeBudgetExceededException(encoded.length(), maxCookieValueBytes);
        }
        LOGGER.info(BffLogMessages.INFO.COOKIE_SESSION_SEALED, encoded.length());
        return encoded;
    }

    /**
     * Unseals a cookie value back into its payload, fail-closed.
     *
     * @param cookieValue the base64url-encoded sealed value read from the request cookie
     * @return the authenticated payload; empty when the value is malformed, carries an unknown
     *         version or key id, or fails its authentication tag — every rejection is "no session",
     *         never an error
     */
    public Optional<Unsealed> unseal(String cookieValue) {
        Objects.requireNonNull(cookieValue, "cookieValue");
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cookieValue);
        } catch (IllegalArgumentException _) {
            // Covers a cookie value that is not valid base64url at all (truncated, re-encoded by an
            // intermediary, or simply foreign) — "no session", never an error.
            return reject(DISPOSITION_MALFORMED);
        }
        if (raw.length < MIN_SEALED_BYTES) {
            return reject(DISPOSITION_MALFORMED);
        }
        byte version = raw[0];
        if (version != FORMAT_VERSION) {
            return reject(DISPOSITION_UNKNOWN_VERSION);
        }
        byte keyId = raw[1];
        // Deterministic check against the one stamped id — never a try-every-key decrypt.
        if (keyId != currentKeyId) {
            return reject(DISPOSITION_UNKNOWN_KEY_ID);
        }

        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(raw, 2, nonce, 0, NONCE_BYTES);
        byte[] sealed = new byte[raw.length - HEADER_BYTES];
        System.arraycopy(raw, HEADER_BYTES, sealed, 0, sealed.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(version, keyId));
            plaintext = cipher.doFinal(sealed);
        } catch (GeneralSecurityException _) {
            // Covers the tag mismatch (tampered ciphertext / nonce / tag, or a value replayed under
            // a different cookie name or key generation) — all are "no session", never an error.
            return reject(DISPOSITION_TAG);
        }
        Optional<SealedSessionPayload> payload = SealedSessionPayload.decode(plaintext);
        if (payload.isEmpty()) {
            return reject(DISPOSITION_PAYLOAD);
        }
        return payload.map(Unsealed::new);
    }

    /**
     * Builds the hardened {@code Set-Cookie} header carrying the sealed value, with {@code Max-Age}
     * set to the session's <em>remaining</em> absolute lifetime so a re-seal never extends it.
     *
     * @param sealedValue  the sealed cookie value from {@link #seal}
     * @param loginInstant the payload's absolute login instant
     * @param now          the reference instant
     * @return the hardened {@code Set-Cookie} header value
     */
    public String toSetCookieHeader(String sealedValue, Instant loginInstant, Instant now) {
        Objects.requireNonNull(sealedValue, "sealedValue");
        Objects.requireNonNull(loginInstant, "loginInstant");
        Objects.requireNonNull(now, "now");
        long remaining = Math.max(0L, Duration.between(now, loginInstant.plus(sessionTtl)).toSeconds());
        return "%s=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Lax"
                .formatted(cookieName, sealedValue, remaining);
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the sealed session cookie.
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return cookieName + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    /**
     * Reads the sealed value out of a request {@code Cookie} header.
     *
     * @param cookieHeader the raw {@code Cookie} header value (may be absent/blank)
     * @return the sealed value when the session cookie is present and non-empty; empty otherwise
     */
    public Optional<String> readSealedValue(@Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && cookieName.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * @return the configured absolute session lifetime from login
     */
    public Duration sessionTtl() {
        return sessionTtl;
    }

    /**
     * @return the configured sealed cookie-value size budget in bytes — the same declared number the
     *         gateway derives its pre-route {@code Cookie} header-value cap from
     */
    public int maxCookieValueBytes() {
        return maxCookieValueBytes;
    }

    private byte[] associatedData(byte version, byte keyId) {
        byte[] name = cookieName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length + 2).put(name).put(version).put(keyId).array();
    }

    private static Optional<Unsealed> reject(String disposition) {
        LOGGER.warn(BffLogMessages.WARN.COOKIE_UNSEAL_REJECTED, disposition);
        return Optional.empty();
    }

    /**
     * Refuses a budget that could not admit even a structurally minimal sealed value. The
     * operator-facing bounds check (floor AND ceiling, with a config-pointer message) lives in
     * {@code ConfigValidator}; this is the codec's own structural guard for programmatic callers.
     */
    private static int requireViableBudget(int maxCookieValueBytes) {
        if (maxCookieValueBytes < COOKIE_VALUE_BUDGET_FLOOR) {
            throw new IllegalArgumentException("maxCookieValueBytes must be at least %d, but was %d"
                    .formatted(COOKIE_VALUE_BUDGET_FLOOR, maxCookieValueBytes));
        }
        return maxCookieValueBytes;
    }

    private static String requireNonBlank(String cookieName) {
        Objects.requireNonNull(cookieName, "cookieName");
        if (cookieName.isBlank()) {
            throw new IllegalArgumentException("cookieName must not be blank");
        }
        return cookieName;
    }

    /**
     * The successful outcome of {@link #unseal(String)}: the authenticated session payload.
     *
     * @param payload the authenticated session payload
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Unsealed(SealedSessionPayload payload) {

        /**
         * Canonical constructor rejecting an absent payload.
         */
        public Unsealed {
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * Raised when a sealed cookie value exceeds the browser-safe size budget. Checked by design:
     * the caller must decide what to do rather than silently emit a value the browser will drop.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class CookieSizeBudgetExceededException extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient int sealedLength;
        private final transient int budget;

        CookieSizeBudgetExceededException(int sealedLength, int budget) {
            super("sealed session cookie is %d bytes, over the %d byte budget".formatted(sealedLength, budget));
            this.sealedLength = sealedLength;
            this.budget = budget;
        }

        /**
         * @return the length the sealed value would have had
         */
        public int sealedLength() {
            return sealedLength;
        }

        /**
         * @return the configured budget the value exceeded
         */
        public int budget() {
            return budget;
        }
    }
}
