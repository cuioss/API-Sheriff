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
 * <strong>Key rotation (D2) — the previous key is decrypt-only.</strong> The codec holds a current
 * key and, optionally, one previous key. {@link #seal} <em>always</em> uses the current key and
 * stamps its key id; the previous key is never selected for a seal, so a rollover is strictly
 * one-way and cannot be walked backwards. {@link #unseal} selects between the two by the stamped
 * key id and reports which one authenticated the value through
 * {@link Unsealed#sealedWithPreviousKey()}, so the binding can complete the rollover on the
 * session's next write. Withdrawing the previous key makes every value still sealed under it
 * <em>unauthenticated</em> — an unknown key id, hence "no session", never an error.
 * <p>
 * <strong>Size budget.</strong> A sealed value larger than {@link #MAX_COOKIE_VALUE_BYTES} fails
 * the seal with {@link CookieSizeBudgetExceededException} and a logged warning — never a silent
 * truncation. Cookie splitting across multiple {@code Set-Cookie} headers is a deliberate
 * non-goal; an operator whose token set does not fit is expected to reduce it or run server mode.
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

    /** The current sealed-cookie format version, bound into the GCM associated data. */
    public static final byte FORMAT_VERSION = 1;

    /**
     * The sealed cookie-value size budget in bytes (~4 KB). Browsers are only required to accept
     * 4096 bytes per cookie, so a larger value would be silently dropped by the browser.
     */
    public static final int MAX_COOKIE_VALUE_BYTES = 4096;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 2 + NONCE_BYTES;
    private static final int MIN_SEALED_BYTES = HEADER_BYTES + (TAG_BITS / 8);

    private static final String DISPOSITION_MALFORMED = "malformed";
    private static final String DISPOSITION_UNKNOWN_VERSION = "unknown-version";
    private static final String DISPOSITION_UNKNOWN_KEY_ID = "unknown-key-id";
    private static final String DISPOSITION_TAG = "authentication-tag";
    private static final String DISPOSITION_PAYLOAD = "payload-format";

    private final SecureRandom secureRandom = new SecureRandom();
    private final String cookieName;
    private final Duration sessionTtl;
    private final SecretKey currentKey;
    private final byte currentKeyId;
    private final @Nullable SecretKey previousKey;
    private final byte previousKeyId;

    /**
     * Assembles the codec without a rotation key — every value is sealed and unsealed under the one
     * current key. This is the shape the generate-on-startup key mode takes, where there is by
     * construction no previous key.
     *
     * @param cookieName   the session-cookie name (bound into the associated data)
     * @param sessionTtl   the absolute session lifetime from login
     * @param currentKey   the AES-256 key new values are sealed under
     * @param currentKeyId the id identifying {@code currentKey} in the cookie header
     */
    public SealedSessionCookieCodec(String cookieName, Duration sessionTtl, SecretKey currentKey, byte currentKeyId) {
        this.cookieName = requireNonBlank(cookieName);
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.currentKey = Objects.requireNonNull(currentKey, "currentKey");
        this.currentKeyId = currentKeyId;
        this.previousKey = null;
        this.previousKeyId = currentKeyId;
    }

    /**
     * Assembles the codec with a decrypt-only rotation key. Values already sealed under
     * {@code previousKey} keep unsealing, but nothing is ever sealed under it again.
     *
     * @param cookieName    the session-cookie name (bound into the associated data)
     * @param sessionTtl    the absolute session lifetime from login
     * @param currentKey    the AES-256 key new values are sealed under
     * @param currentKeyId  the id identifying {@code currentKey} in the cookie header
     * @param previousKey   the AES-256 key retired values are still accepted under, decrypt-only
     * @param previousKeyId the id identifying {@code previousKey}; must differ from
     *                      {@code currentKeyId}, otherwise the stamped id could not select a key
     */
    public SealedSessionCookieCodec(String cookieName, Duration sessionTtl, SecretKey currentKey, byte currentKeyId,
            SecretKey previousKey, byte previousKeyId) {
        this.cookieName = requireNonBlank(cookieName);
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.currentKey = Objects.requireNonNull(currentKey, "currentKey");
        this.currentKeyId = currentKeyId;
        this.previousKey = Objects.requireNonNull(previousKey, "previousKey");
        if (previousKeyId == currentKeyId) {
            // Unsealing selects the key by the stamped id alone; a shared id would make that
            // selection ambiguous and silently turn the rotation into a try-both decrypt.
            throw new IllegalArgumentException("previousKeyId must differ from currentKeyId");
        }
        this.previousKeyId = previousKeyId;
    }

    /**
     * Seals a payload into the cookie value.
     *
     * @param payload the session payload to seal
     * @return the base64url-encoded sealed cookie value
     * @throws CookieSizeBudgetExceededException when the sealed value exceeds
     *         {@link #MAX_COOKIE_VALUE_BYTES}
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
        if (encoded.length() > MAX_COOKIE_VALUE_BYTES) {
            LOGGER.warn(BffLogMessages.WARN.COOKIE_SIZE_BUDGET_EXCEEDED, encoded.length());
            throw new CookieSizeBudgetExceededException(encoded.length(), MAX_COOKIE_VALUE_BYTES);
        }
        LOGGER.info(BffLogMessages.INFO.COOKIE_SESSION_SEALED, encoded.length());
        return encoded;
    }

    /**
     * Unseals a cookie value back into its payload, fail-closed.
     *
     * @param cookieValue the base64url-encoded sealed value read from the request cookie
     * @return the payload together with the key generation that authenticated it; empty when the
     *         value is malformed, carries an unknown version or key id, or fails its authentication
     *         tag — every rejection is "no session", never an error
     */
    public Optional<Unsealed> unseal(String cookieValue) {
        Objects.requireNonNull(cookieValue, "cookieValue");
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cookieValue);
        } catch (IllegalArgumentException notBase64) {
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
        // Deterministic selection by the stamped id — never a try-both decrypt.
        SecretKey key;
        boolean sealedWithPreviousKey;
        if (keyId == currentKeyId) {
            key = currentKey;
            sealedWithPreviousKey = false;
        } else if (previousKey != null && keyId == previousKeyId) {
            key = previousKey;
            sealedWithPreviousKey = true;
        } else {
            return reject(DISPOSITION_UNKNOWN_KEY_ID);
        }

        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(raw, 2, nonce, 0, NONCE_BYTES);
        byte[] sealed = new byte[raw.length - HEADER_BYTES];
        System.arraycopy(raw, HEADER_BYTES, sealed, 0, sealed.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(version, keyId));
            plaintext = cipher.doFinal(sealed);
        } catch (GeneralSecurityException tamperedOrMisconfigured) {
            // Covers the tag mismatch (tampered ciphertext / nonce / tag, or a value replayed under
            // a different cookie name or key generation) — all are "no session", never an error.
            return reject(DISPOSITION_TAG);
        }
        Optional<SealedSessionPayload> payload = SealedSessionPayload.decode(plaintext);
        if (payload.isEmpty()) {
            return reject(DISPOSITION_PAYLOAD);
        }
        return payload.map(decoded -> new Unsealed(decoded, sealedWithPreviousKey));
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

    private byte[] associatedData(byte version, byte keyId) {
        byte[] name = cookieName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length + 2).put(name).put(version).put(keyId).array();
    }

    private static Optional<Unsealed> reject(String disposition) {
        LOGGER.warn(BffLogMessages.WARN.COOKIE_UNSEAL_REJECTED, disposition);
        return Optional.empty();
    }

    private static String requireNonBlank(String cookieName) {
        Objects.requireNonNull(cookieName, "cookieName");
        if (cookieName.isBlank()) {
            throw new IllegalArgumentException("cookieName must not be blank");
        }
        return cookieName;
    }

    /**
     * The successful outcome of {@link #unseal(String)}: the authenticated payload plus which key
     * generation authenticated it.
     * <p>
     * {@code sealedWithPreviousKey} is the rotation signal {@link CookieSessionBinding} consumes —
     * it is <em>not</em> a rejection reason. A value sealed under the previous key is a fully valid
     * session; the flag only says the session still sits on the retired generation and should be
     * rolled onto the current key by its next write.
     *
     * @param payload               the authenticated session payload
     * @param sealedWithPreviousKey {@code true} when the decrypt-only previous key authenticated the
     *                              value, {@code false} when the current key did
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Unsealed(SealedSessionPayload payload, boolean sealedWithPreviousKey) {

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
