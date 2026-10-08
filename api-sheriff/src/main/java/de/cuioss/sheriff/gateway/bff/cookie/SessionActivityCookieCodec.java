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

import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The AES-256-GCM codec of the cookie-mode <em>activity cookie</em> ({@code session.mode: cookie}) —
 * the small cookie that remembers when a session was last accessed, so the idle timeout can be
 * enforced without rewriting the token-bearing session cookie.
 * <p>
 * <strong>Why a cookie of its own.</strong> A stateless gateway has nowhere to keep a last-access
 * instant but the browser. Putting it into the sealed session payload would re-seal the tokens on
 * every access, race a concurrent refresh for the same cookie, and change the sealed format. The
 * activity cookie carries no token material, is written at most once per re-issue interval per session
 * (the smaller of 60 seconds and half the idle timeout, see {@link CookieSessionBinding}), and leaves
 * {@link SealedSessionCookieCodec} and its format version untouched.
 * <p>
 * <strong>Cookie value layout.</strong> {@code version(1B) || key-id(1B) || nonce(12B) ||
 * ciphertext(24B) || tag(16B)} — 54 bytes, base64url-encoded without padding to 72 characters. The
 * ciphertext covers {@code session-identity(16B) || last-access(8B, epoch milliseconds)}. The
 * {@code version}, the {@code key-id} and the cookie <em>name</em> are bound into the GCM associated
 * data.
 * <p>
 * <strong>Bound to one session.</strong> The sealed value carries the derived identity of the session
 * it belongs to. The binding compares it with the identity of the session cookie on the same request
 * and ignores an activity cookie that names another session, so an activity cookie cannot be moved
 * from one session to another to keep the second one alive.
 * <p>
 * <strong>A key of its own.</strong> The codec seals under a key that exists for this cookie alone
 * ({@link CookieKeyMaterial#activityCodec(String)} derives it from the sealing key). Its seal
 * cadence therefore does not count against the random-nonce budget of the key that seals tokens,
 * and a value sealed by either codec is refused by the other at the key-id gate or the tag check.
 * <p>
 * <strong>Nonce discipline.</strong> {@link #seal} draws a fresh 96-bit nonce from
 * {@link SecureRandom} on every call, never derived and never counter-based.
 * <p>
 * <strong>Fail-closed unsealing.</strong> A value that is malformed, truncated, of another version,
 * sealed under another key or for another cookie name, or tampered with unseals to
 * {@link Optional#empty()} — "no activity cookie", never an error. The session is then measured from
 * its login instant, which can only shorten its life. Every rejection is logged at {@code DEBUG} only:
 * any client can send a junk activity cookie on any request, so a record above {@code DEBUG} here would
 * be an unauthenticated log-amplification lever.
 * <p>
 * <strong>A last access in the future counts as now.</strong> {@link #unseal} clamps the instant to
 * the reference instant, so a value sealed by a gateway whose clock runs ahead cannot buy idle time.
 * <p>
 * The codec holds only immutable configuration and a thread-safe {@link SecureRandom}, creates a
 * fresh {@link Cipher} per operation, and is safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionActivityCookieCodec {

    private static final CuiLogger LOGGER = new CuiLogger(SessionActivityCookieCodec.class);

    /**
     * The format version of the activity cookie, bound into the GCM associated data. It is this
     * cookie's own counter and independent of {@link SealedSessionCookieCodec#FORMAT_VERSION}: a change
     * to the layout above increments it, and a value of any other version is refused before a
     * {@link Cipher} is constructed.
     */
    public static final byte FORMAT_VERSION = 1;

    /**
     * The suffix appended to the session cookie name to form the activity cookie name. A suffix, not a
     * prefix, so the activity cookie keeps the {@code __Host-} prefix whenever the session cookie has
     * it.
     */
    public static final String COOKIE_NAME_SUFFIX = "-activity";

    /** The width of the derived session identity the value is bound to, in bytes. */
    static final int SESSION_IDENTITY_BYTES = 16;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 2 + NONCE_BYTES;
    private static final int PLAINTEXT_BYTES = SESSION_IDENTITY_BYTES + Long.BYTES;
    private static final int SEALED_BYTES = HEADER_BYTES + PLAINTEXT_BYTES + (TAG_BITS / 8);
    private static final String MAX_AGE_ATTRIBUTE = "; Max-Age=";
    private static final String HARDENING_ATTRIBUTES = "; Path=/; Secure; HttpOnly; SameSite=Lax";

    private final SecureRandom secureRandom = new SecureRandom();
    private final String cookieName;
    private final SecretKey key;
    private final byte keyId;

    /**
     * Assembles the codec for the activity cookie that accompanies the named session cookie.
     *
     * @param sessionCookieName the session cookie's name; the activity cookie is named after it with
     *                          {@link #COOKIE_NAME_SUFFIX}
     * @param key               the AES-256 key that exists for the activity cookie alone
     * @param keyId             the id identifying {@code key} in the cookie header
     * @throws IllegalArgumentException when {@code sessionCookieName} is blank
     */
    public SessionActivityCookieCodec(String sessionCookieName, SecretKey key, byte keyId) {
        Objects.requireNonNull(sessionCookieName, "sessionCookieName");
        if (sessionCookieName.isBlank()) {
            throw new IllegalArgumentException("sessionCookieName must not be blank");
        }
        this.cookieName = sessionCookieName + COOKIE_NAME_SUFFIX;
        this.key = Objects.requireNonNull(key, "key");
        this.keyId = keyId;
    }

    /**
     * @return the activity cookie's name: the session cookie name followed by
     *         {@value #COOKIE_NAME_SUFFIX}
     */
    public String cookieName() {
        return cookieName;
    }

    /**
     * Seals a last-access instant for one session.
     *
     * @param sessionIdentity the derived identity of the session the value belongs to — the
     *                        base64url encoding of {@value #SESSION_IDENTITY_BYTES} bytes, as the
     *                        cookie-mode binding derives it
     * @param lastAccess      the instant of the access
     * @return the base64url-encoded sealed cookie value
     * @throws IllegalArgumentException when {@code sessionIdentity} is not the encoding of
     *                                  {@value #SESSION_IDENTITY_BYTES} bytes
     */
    public String seal(String sessionIdentity, Instant lastAccess) {
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(lastAccess, "lastAccess");
        byte[] identity = Base64.getUrlDecoder().decode(sessionIdentity);
        if (identity.length != SESSION_IDENTITY_BYTES) {
            throw new IllegalArgumentException("sessionIdentity must encode %d bytes, but encoded %d"
                    .formatted(SESSION_IDENTITY_BYTES, identity.length));
        }
        byte[] plaintext = ByteBuffer.allocate(PLAINTEXT_BYTES)
                .put(identity).putLong(lastAccess.toEpochMilli()).array();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);

        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData());
            sealed = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException sealingFailure) {
            // A misconfigured key is an operator error the gateway cannot serve around.
            throw new IllegalStateException("cookie-mode activity sealing failed", sealingFailure);
        }
        byte[] value = ByteBuffer.allocate(HEADER_BYTES + sealed.length)
                .put(FORMAT_VERSION).put(keyId).put(nonce).put(sealed).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * Unseals an activity cookie value, fail-closed.
     *
     * @param cookieValue the base64url-encoded value read from the request cookie
     * @param now         the reference instant; a sealed last access later than it counts as it
     * @return the session identity the value is bound to and its last access; empty when the value is
     *         malformed, of another version, sealed under another key or cookie name, or tampered with
     */
    public Optional<Activity> unseal(String cookieValue, Instant now) {
        Objects.requireNonNull(cookieValue, "cookieValue");
        Objects.requireNonNull(now, "now");
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cookieValue);
        } catch (IllegalArgumentException _) {
            return reject("malformed");
        }
        // The layout is fixed-width, so any other length is not a value this codec sealed.
        if (raw.length != SEALED_BYTES) {
            return reject("malformed");
        }
        if (raw[0] != FORMAT_VERSION) {
            return reject("unknown-version");
        }
        // Deterministic check against the one stamped id — never a try-every-key decrypt.
        if (raw[1] != keyId) {
            return reject("unknown-key-id");
        }
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 2, NONCE_BYTES));
            // Both header bytes were just checked against this codec's own, so the data is the same
            // on both sides.
            cipher.updateAAD(associatedData());
            plaintext = cipher.doFinal(raw, HEADER_BYTES, raw.length - HEADER_BYTES);
        } catch (GeneralSecurityException _) {
            // A tampered value, or one sealed for another cookie name or under another key.
            return reject("authentication-tag");
        }
        ByteBuffer buffer = ByteBuffer.wrap(plaintext);
        byte[] identity = new byte[SESSION_IDENTITY_BYTES];
        buffer.get(identity);
        Instant sealedLastAccess = Instant.ofEpochMilli(buffer.getLong());
        Instant lastAccess = sealedLastAccess.isAfter(now) ? now : sealedLastAccess;
        return Optional.of(new Activity(Base64.getUrlEncoder().withoutPadding().encodeToString(identity),
                lastAccess));
    }

    /**
     * Reads and unseals the activity cookie of a request.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant; a sealed last access later than it counts as it
     * @return the unsealed activity; empty when the request carries no activity cookie or its value
     *         does not unseal
     */
    public Optional<Activity> read(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && cookieName.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : unseal(value, now);
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the hardened {@code Set-Cookie} header carrying a sealed value. {@code Max-Age} is the
     * session's <em>remaining</em> absolute lifetime, so the activity cookie never outlives the
     * session cookie it accompanies.
     *
     * @param sealedValue      the sealed cookie value from {@link #seal}
     * @param sessionExpiresAt the session's absolute expiry
     * @param now              the reference instant
     * @return the {@code Set-Cookie} header value, carrying {@code Secure}, {@code HttpOnly},
     *         {@code SameSite=Lax} and {@code Path=/}
     */
    public String toSetCookieHeader(String sealedValue, Instant sessionExpiresAt, Instant now) {
        Objects.requireNonNull(sealedValue, "sealedValue");
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        Objects.requireNonNull(now, "now");
        long remaining = Math.max(0L, Duration.between(now, sessionExpiresAt).toSeconds());
        return cookieName + "=" + sealedValue + MAX_AGE_ATTRIBUTE + remaining + HARDENING_ATTRIBUTES;
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the activity cookie.
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return cookieName + "=" + MAX_AGE_ATTRIBUTE + "0" + HARDENING_ATTRIBUTES;
    }

    private byte[] associatedData() {
        byte[] name = cookieName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length + 2).put(name).put(FORMAT_VERSION).put(keyId).array();
    }

    /**
     * Records the rejection at {@code DEBUG} and returns "no activity cookie". The disposition is a
     * fixed constant, never the offending cookie value.
     */
    private static Optional<Activity> reject(String disposition) {
        LOGGER.debug("Session activity cookie ignored: %s", disposition);
        return Optional.empty();
    }

    /**
     * An unsealed activity cookie: which session it belongs to, and when that session was last
     * accessed.
     *
     * @param sessionIdentity the derived identity of the session the value is bound to, in the
     *                        encoding the cookie-mode binding uses for
     *                        {@code SessionRecord.sessionId()}
     * @param lastAccess      the last access, never later than the reference instant it was unsealed at
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Activity(String sessionIdentity, Instant lastAccess) {

        /**
         * Canonical constructor rejecting absent components.
         */
        public Activity {
            Objects.requireNonNull(sessionIdentity, "sessionIdentity");
            Objects.requireNonNull(lastAccess, "lastAccess");
        }
    }
}
