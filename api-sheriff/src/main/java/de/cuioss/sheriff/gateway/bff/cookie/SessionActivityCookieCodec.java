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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The HMAC-SHA-256 codec of the cookie-mode <em>activity cookie</em> ({@code session.mode: cookie}) —
 * the small cookie that remembers when a session was last accessed, so the idle timeout can be
 * enforced without rewriting the token-bearing session cookie.
 * <p>
 * <strong>Why a cookie of its own.</strong> A stateless gateway has nowhere to keep a last-access
 * instant but the browser. Putting it into the sealed session payload would re-seal the tokens on
 * every access, race a concurrent refresh for the same cookie, and change the sealed format. The
 * activity cookie carries no token material and leaves {@link SealedSessionCookieCodec} and its format
 * version untouched.
 * <p>
 * <strong>Authenticated, not encrypted.</strong> The value carries one fact, the instant of the last
 * access, and that instant is not a secret. The codec therefore authenticates it with a message
 * authentication code and does not encrypt it. This is a deliberate choice of primitive: the binding
 * keeps no state per session, so a client decides how often a new activity cookie is written — it can
 * send requests in parallel, or present one old activity cookie again and again. A cipher that draws a
 * random nonce per value is limited in how many values one key may protect, and that limit would be
 * spent at a rate the client controls. HMAC-SHA-256 has no such limit, and it is deterministic: the
 * same session and the same instant always give the same value.
 * <p>
 * <strong>Cookie value layout.</strong> {@code version(1B) || key-id(1B) || last-access(8B, epoch
 * milliseconds) || tag(32B)} — 42 bytes, base64url-encoded without padding to 56 characters. The tag
 * is the HMAC-SHA-256 over {@code cookie-name || version || key-id || session-identity(16B) ||
 * last-access}.
 * <p>
 * <strong>Bound to one session.</strong> The derived identity of the session is part of the
 * authenticated data and is <em>not</em> part of the value: the caller supplies the identity of the
 * session cookie on the same request, and a value signed for another session does not verify. An
 * activity cookie therefore cannot be moved from one session to another to keep the second one alive,
 * and the session identity is never sent to the browser.
 * <p>
 * <strong>A key of its own.</strong> The codec signs under a key that exists for this cookie alone
 * ({@link CookieKeyMaterial#activityCodec(String)} derives it from the sealing key), so the key that
 * seals tokens is used for nothing else.
 * <p>
 * <strong>Fail-closed verification.</strong> A value that is malformed, truncated, of another
 * version, signed under another key, for another cookie name or for another session, or tampered with
 * verifies to {@link Optional#empty()} — "no activity cookie", never an error. The session is then
 * measured from its login instant, which can only shorten its life. Every rejection is logged at
 * {@code DEBUG} only: any client can send a junk activity cookie on any request, so a record above
 * {@code DEBUG} here would be an unauthenticated log-amplification lever.
 * <p>
 * <strong>A last access in the future counts as now.</strong> {@link #verify} clamps the instant to
 * the reference instant, so a value signed by a gateway whose clock runs ahead cannot buy idle time.
 * <p>
 * The codec holds only immutable configuration, creates a fresh {@link Mac} per operation, and is
 * safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionActivityCookieCodec {

    private static final CuiLogger LOGGER = new CuiLogger(SessionActivityCookieCodec.class);

    /**
     * The format version of the activity cookie, bound into the authenticated data. It is this
     * cookie's own counter and independent of {@link SealedSessionCookieCodec#FORMAT_VERSION}: a change
     * to the layout above increments it, and a value of any other version is refused before a
     * {@link Mac} is constructed.
     */
    public static final byte FORMAT_VERSION = 2;

    /**
     * The suffix appended to the session cookie name to form the activity cookie name. A suffix, not a
     * prefix, so the activity cookie keeps the {@code __Host-} prefix whenever the session cookie has
     * it.
     */
    public static final String COOKIE_NAME_SUFFIX = "-activity";

    /** The width of the derived session identity the value is bound to, in bytes. */
    static final int SESSION_IDENTITY_BYTES = 16;

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int TAG_BYTES = 32;
    private static final int HEADER_BYTES = 2;
    private static final int SIGNED_VALUE_BYTES = HEADER_BYTES + Long.BYTES + TAG_BYTES;
    private static final String MAX_AGE_ATTRIBUTE = "; Max-Age=";
    private static final String HARDENING_ATTRIBUTES = "; Path=/; Secure; HttpOnly; SameSite=Lax";

    private final String cookieName;
    private final SecretKey key;
    private final byte keyId;

    /**
     * Assembles the codec for the activity cookie that accompanies the named session cookie.
     *
     * @param sessionCookieName the session cookie's name; the activity cookie is named after it with
     *                          {@link #COOKIE_NAME_SUFFIX}
     * @param key               the HMAC-SHA-256 key that exists for the activity cookie alone
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
     * Signs a last-access instant for one session.
     *
     * @param sessionIdentity the derived identity of the session the value belongs to — the
     *                        base64url encoding of {@value #SESSION_IDENTITY_BYTES} bytes, as the
     *                        cookie-mode binding derives it
     * @param lastAccess      the instant of the access
     * @return the base64url-encoded signed cookie value; the same arguments always give the same value
     * @throws IllegalArgumentException when {@code sessionIdentity} is not the encoding of
     *                                  {@value #SESSION_IDENTITY_BYTES} bytes
     */
    public String sign(String sessionIdentity, Instant lastAccess) {
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(lastAccess, "lastAccess");
        long lastAccessMillis = lastAccess.toEpochMilli();
        byte[] value = ByteBuffer.allocate(SIGNED_VALUE_BYTES)
                .put(FORMAT_VERSION).put(keyId).putLong(lastAccessMillis)
                .put(tag(identityBytes(sessionIdentity), lastAccessMillis)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * Verifies an activity cookie value against the session it is presented with, fail-closed.
     *
     * @param cookieValue     the base64url-encoded value read from the request cookie
     * @param sessionIdentity the derived identity of the session the same request resolved
     * @param now             the reference instant; a signed last access later than it counts as it
     * @return the last access the value carries; empty when the value is malformed, of another version,
     *         signed under another key, for another cookie name or for another session, or tampered
     *         with
     * @throws IllegalArgumentException when {@code sessionIdentity} is not the encoding of
     *                                  {@value #SESSION_IDENTITY_BYTES} bytes
     */
    public Optional<Instant> verify(String cookieValue, String sessionIdentity, Instant now) {
        Objects.requireNonNull(cookieValue, "cookieValue");
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(now, "now");
        byte[] identity = identityBytes(sessionIdentity);
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cookieValue);
        } catch (IllegalArgumentException _) {
            return reject("malformed");
        }
        // The layout is fixed-width, so any other length is not a value this codec signed.
        if (raw.length != SIGNED_VALUE_BYTES) {
            return reject("malformed");
        }
        if (raw[0] != FORMAT_VERSION) {
            return reject("unknown-version");
        }
        // Deterministic check against the one stamped id — never a try-every-key verification.
        if (raw[1] != keyId) {
            return reject("unknown-key-id");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, HEADER_BYTES, Long.BYTES + TAG_BYTES);
        long lastAccessMillis = buffer.getLong();
        byte[] presentedTag = new byte[TAG_BYTES];
        buffer.get(presentedTag);
        // Constant-time: the comparison must not tell a caller how many tag bytes were right.
        if (!MessageDigest.isEqual(tag(identity, lastAccessMillis), presentedTag)) {
            // A tampered value, or one signed for another session, another cookie name or under another key.
            return reject("authentication-tag");
        }
        Instant signedLastAccess = Instant.ofEpochMilli(lastAccessMillis);
        return Optional.of(signedLastAccess.isAfter(now) ? now : signedLastAccess);
    }

    /**
     * Reads and verifies the activity cookie of a request.
     *
     * @param cookieHeader    the raw request {@code Cookie} header value, may be absent
     * @param sessionIdentity the derived identity of the session the same request resolved
     * @param now             the reference instant; a signed last access later than it counts as it
     * @return the last access; empty when the request carries no activity cookie or its value does not
     *         verify for this session
     */
    public Optional<Instant> read(@Nullable String cookieHeader, String sessionIdentity, Instant now) {
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(now, "now");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && cookieName.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : verify(value, sessionIdentity, now);
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the hardened {@code Set-Cookie} header carrying a signed value. {@code Max-Age} is the
     * session's <em>remaining</em> absolute lifetime, so the activity cookie never outlives the
     * session cookie it accompanies.
     *
     * @param signedValue      the signed cookie value from {@link #sign}
     * @param sessionExpiresAt the session's absolute expiry
     * @param now              the reference instant
     * @return the {@code Set-Cookie} header value, carrying {@code Secure}, {@code HttpOnly},
     *         {@code SameSite=Lax} and {@code Path=/}
     */
    public String toSetCookieHeader(String signedValue, Instant sessionExpiresAt, Instant now) {
        Objects.requireNonNull(signedValue, "signedValue");
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        Objects.requireNonNull(now, "now");
        long remaining = Math.max(0L, Duration.between(now, sessionExpiresAt).toSeconds());
        return cookieName + "=" + signedValue + MAX_AGE_ATTRIBUTE + remaining + HARDENING_ATTRIBUTES;
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the activity cookie.
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return cookieName + "=" + MAX_AGE_ATTRIBUTE + "0" + HARDENING_ATTRIBUTES;
    }

    /**
     * The HMAC-SHA-256 over the cookie name, the two header bytes, the session identity and the last
     * access. The cookie name is the only variable-width part and comes first; everything after it is
     * fixed-width, so two different inputs never give the same byte sequence.
     */
    private byte[] tag(byte[] identity, long lastAccessMillis) {
        byte[] name = cookieName.getBytes(StandardCharsets.UTF_8);
        byte[] authenticated = ByteBuffer.allocate(name.length + HEADER_BYTES + SESSION_IDENTITY_BYTES + Long.BYTES)
                .put(name).put(FORMAT_VERSION).put(keyId).put(identity).putLong(lastAccessMillis).array();
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(authenticated);
        } catch (GeneralSecurityException signingFailure) {
            // A misconfigured key is an operator error the gateway cannot serve around.
            throw new IllegalStateException("cookie-mode activity signing failed", signingFailure);
        }
    }

    private static byte[] identityBytes(String sessionIdentity) {
        byte[] identity = Base64.getUrlDecoder().decode(sessionIdentity);
        if (identity.length != SESSION_IDENTITY_BYTES) {
            throw new IllegalArgumentException("sessionIdentity must encode %d bytes, but encoded %d"
                    .formatted(SESSION_IDENTITY_BYTES, identity.length));
        }
        return identity;
    }

    /**
     * Records the rejection at {@code DEBUG} and returns "no activity cookie". The disposition is a
     * fixed constant, never the offending cookie value.
     */
    private static Optional<Instant> reject(String disposition) {
        LOGGER.debug("Session activity cookie ignored: %s", disposition);
        return Optional.empty();
    }
}
