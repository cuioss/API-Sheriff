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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
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
 * ciphertext || tag(16B)}, base64url-encoded without padding — <em>once</em>, for transport. The
 * {@code version}, {@code key-id}, and the cookie <em>name</em> are bound into the GCM
 * <em>associated data</em>, so a sealed value cannot be replayed under a different format version, a
 * different key generation, or a different cookie name — the tag check fails and the value unseals
 * to "no session".
 * <p>
 * <strong>Packaging pipeline.</strong> The ciphertext covers a <em>deflated</em>
 * {@link SealedSessionPayload#encode() length-prefixed raw UTF-8 payload}: encode, deflate, seal,
 * base64url. Compress-then-encrypt is safe <em>here</em> and only here because the plaintext carries
 * no attacker-chosen material next to a secret and is never returned to the client as a
 * length-observable oracle — the sealed value is written once per session change and read back only
 * by this gateway. See {@code doc/adr/0043} for the CRIME/BREACH discriminator that bounds that
 * claim.
 * <p>
 * <strong>Inflation is bounded.</strong> {@link #unseal} inflates only bytes the GCM tag has already
 * authenticated, so this is not untrusted-input decompression; the inflated size is nonetheless
 * capped at {@link SealedSessionPayload#MAX_PLAINTEXT_BYTES} so an authenticated-but-corrupt buffer
 * cannot drive an unbounded allocation. A malformed or over-long stream takes the same
 * {@code payload-format} rejection a malformed payload does — "no session", never an error.
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
 * is logged with its non-sensitive disposition only, and the catalogued {@code WARN} is
 * <em>latched per disposition</em> — see {@link #reject} for why an unlatched record on this path
 * is a remotely-reachable log-amplification lever.
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
 * splitting across multiple {@code Set-Cookie} headers remains a deliberate non-goal: the packaging
 * pipeline above is the answer instead, and it is what makes a three-token session fit a single
 * browser-safe cookie. An operator whose token set still does not fit after packaging is expected to
 * reduce it or run server mode.
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
     * Version {@code 3} carries the nine-field payload framed as length-prefixed raw UTF-8 and
     * deflated before sealing. It replaces version {@code 2}, which base64url-armoured every field
     * <em>inside</em> the plaintext and then base64url-encoded the sealed value again for transport —
     * a compounded ~33 % expansion applied to material that is already base64 text, and the reason a
     * session carrying access + refresh + ID tokens could not fit the browser-safe budget.
     * <p>
     * The bump is a clean break with no migration path: {@link #unseal} reads the leading version
     * byte and rejects anything other than {@code FORMAT_VERSION} at an explicit gate, before a
     * {@link Cipher} is even constructed — so a version-2 cookie is refused outright rather than
     * being inflated and mis-parsed against the new framing, and decryption is never attempted for
     * it. The version byte is additionally bound into the GCM associated data, so it cannot be forged
     * onto a value sealed under a different version either. Pre-existing cookie sessions are
     * therefore refused fail-closed and the browser simply re-logs in.
     */
    public static final byte FORMAT_VERSION = 3;

    /**
     * The {@code Max-Age} attribute introducer, shared by the header assembly in
     * {@link #toSetCookieHeader} and the overhead derivation in
     * {@link #setCookieHeaderOverhead(String, Duration)} so the two can never count different bytes.
     */
    private static final String MAX_AGE_ATTRIBUTE = "; Max-Age=";

    /**
     * The invariant hardening attribute run, shared by the header assembly in
     * {@link #toSetCookieHeader} and the overhead derivation in
     * {@link #setCookieHeaderOverhead(String, Duration)}.
     */
    private static final String HARDENING_ATTRIBUTES = "; Path=/; Secure; HttpOnly; SameSite=Lax";

    /** The {@code =} between the cookie name and its value. */
    private static final int NAME_VALUE_SEPARATOR_BYTES = 1;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 2 + NONCE_BYTES;
    private static final int MIN_SEALED_BYTES = HEADER_BYTES + (TAG_BITS / 8);

    /** The transfer-buffer size for the deflate/inflate loops — a working buffer, not a bound. */
    private static final int ZIP_CHUNK_BYTES = 1024;

    /**
     * The default sealed cookie-value size budget in bytes (~4 KB). Browsers are only required to
     * accept 4096 bytes per cookie, so a larger value risks being silently dropped by the browser —
     * which is why the default sits at that figure rather than at the gateway's transport ceiling.
     * <p>
     * <strong>It is a VALUE budget, and the browser's 4096 is a HEADER budget.</strong> The two are
     * not the same number and must not be compared to each other directly: a value sealing to
     * exactly this budget is emitted as a {@code Set-Cookie} header of
     * {@code 4096 + }{@link #DEFAULT_SET_COOKIE_HEADER_OVERHEAD} bytes, which is already past what
     * RFC 6265 6.1 guarantees. {@link #BROWSER_SAFE_COOKIE_VALUE_BUDGET} is the value budget that
     * corresponds to the guarantee, and it is the number any browser-deliverability comparison
     * belongs against. This default is deliberately left where it is — it is the documented,
     * schema-described default and moving it is a separate decision from stating it accurately.
     */
    public static final int DEFAULT_COOKIE_VALUE_BUDGET = 4096;

    /**
     * The per-cookie budget RFC 6265 6.1 asks a user agent to honour, in bytes.
     * <p>
     * It governs the <em>whole</em> {@code Set-Cookie} header — the cookie's name, its value
     * <em>and</em> its attributes summed together — never the value in isolation. Every
     * browser-deliverability comparison <em>in the gateway</em> goes through this constant plus
     * {@link #DEFAULT_SET_COOKIE_HEADER_OVERHEAD} rather than against
     * {@link #DEFAULT_COOKIE_VALUE_BUDGET}, because the latter measures a different quantity.
     * The integration suite deliberately does not: its own budget constant is declared
     * independently of this one so the test cannot inherit a product-side mistake in the very
     * number it exists to check.
     */
    public static final int BROWSER_PER_COOKIE_HEADER_GUARANTEE = 4096;

    /**
     * The bytes {@link #toSetCookieHeader} wraps around the sealed value <em>under the default
     * cookie name and a 3600-second TTL</em> — the default-configuration case, and nothing wider:
     * <ul>
     *   <li><strong>23</strong> for {@code __Host-sheriff-session=} — 22 name bytes
     *       ({@code SessionCookieCodec.DEFAULT_COOKIE_NAME}) plus the {@code =} separator;</li>
     *   <li><strong>54</strong> for the attribute run
     *       {@code ; Max-Age=3600; Path=/; Secure; HttpOnly; SameSite=Lax} — 50 invariant bytes plus
     *       the four {@code Max-Age} digits a 3600-second session TTL produces.</li>
     * </ul>
     * <strong>This constant is NOT the general answer, and no guard may be built on it.</strong>
     * Both inputs are configurable ({@code session.cookie_name}, {@code session.ttl_seconds}) and
     * {@link #toSetCookieHeader} uses the <em>configured</em> pair, so a longer cookie name or a TTL
     * past 9999 seconds emits a larger header than this figure describes. The general answer is
     * {@link #setCookieHeaderOverhead(String, Duration)}, which derives the overhead from the
     * resolved configuration; {@code ConfigValidator} goes through that method, never through this
     * constant. What survives here is the documented default-configuration figure the reference
     * material and the {@code ApiSheriff-114} / {@code ApiSheriff-124} catalogue entries cite, kept
     * pinned to the derivation by {@code SealedSessionCookieCodecTest} so the two cannot drift.
     */
    public static final int DEFAULT_SET_COOKIE_HEADER_OVERHEAD = 77;

    /**
     * The largest sealed cookie-<em>value</em> budget whose emitted {@code Set-Cookie} header still
     * fits {@link #BROWSER_PER_COOKIE_HEADER_GUARANTEE} <em>under the default configuration</em>:
     * {@code 4096 - 77 = 4019}.
     * <p>
     * It is the default-configuration case of
     * {@code BROWSER_PER_COOKIE_HEADER_GUARANTEE - }{@link #setCookieHeaderOverhead(String, Duration)},
     * carried as a named constant because it is the figure the operator-facing documentation quotes.
     * A deliverability threshold is computed from the resolved configuration through that method
     * rather than read from here — a gateway running a longer cookie name has a lower browser-safe
     * value budget than 4019, and comparing against this constant would under-warn by exactly the
     * configured deviation.
     * <p>
     * What the constant does still record is why the threshold is not
     * {@link #DEFAULT_COOKIE_VALUE_BUDGET}: warning on 4096 leaves a 77-byte band — a value budget
     * in {@code 4020..4096} — in which the gateway emits a header the browser is not obliged to keep
     * and nothing anywhere says so.
     */
    public static final int BROWSER_SAFE_COOKIE_VALUE_BUDGET =
            BROWSER_PER_COOKIE_HEADER_GUARANTEE - DEFAULT_SET_COOKIE_HEADER_OVERHEAD;

    /**
     * The smallest configurable budget: the encoded length of the sealed envelope alone
     * ({@code version || key-id || nonce || tag}, base64url without padding), carrying no ciphertext
     * at all. A budget below this could not admit even a structurally minimal sealed value, so it is
     * refused at boot rather than failing every seal at runtime.
     * <p>
     * It is a <em>floor</em>, not an achievable length: the shortest value this codec actually emits
     * is longer, because even an empty payload deflates to a non-empty stream. Keeping the constant
     * at the envelope size states exactly what it guards — the structural minimum — without pinning
     * it to whatever the compressor happens to produce for an input the gateway never seals.
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

    /**
     * Latches the catalogued rejection {@code WARN} to the FIRST occurrence of each disposition in
     * this codec's lifetime; every later occurrence of that same disposition is a {@code DEBUG}
     * diagnostic. Set membership is drawn exclusively from the five private
     * {@code DISPOSITION_*} constants — {@link #reject} is private and no call site passes a
     * computed value — so the set is bounded by construction and the latch holds no session state,
     * preserving the codec's statelessness and thread-safety.
     */
    private final Set<String> warnedDispositions = ConcurrentHashMap.newKeySet();

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
            sealed = cipher.doFinal(deflate(payload.encode()));
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

        byte[] compressed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(version, keyId));
            compressed = cipher.doFinal(sealed);
        } catch (GeneralSecurityException _) {
            // Covers the tag mismatch (tampered ciphertext / nonce / tag, or a value replayed under
            // a different cookie name or key generation) — all are "no session", never an error.
            return reject(DISPOSITION_TAG);
        }
        Optional<byte[]> plaintext = inflate(compressed);
        if (plaintext.isEmpty()) {
            return reject(DISPOSITION_PAYLOAD);
        }
        Optional<SealedSessionPayload> payload = SealedSessionPayload.decode(plaintext.get());
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
        long configuredTtlSeconds = Math.max(0L, sessionTtl.toSeconds());
        long remaining = Math.clamp(
                Duration.between(now, loginInstant.plus(sessionTtl)).toSeconds(),
                0L, configuredTtlSeconds);
        return cookieName + "=" + sealedValue + MAX_AGE_ATTRIBUTE + remaining + HARDENING_ATTRIBUTES;
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the sealed session cookie.
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return cookieName + "=" + MAX_AGE_ATTRIBUTE + "0" + HARDENING_ATTRIBUTES;
    }

    /**
     * The bytes {@link #toSetCookieHeader} wraps around the sealed value for a given configuration —
     * the ONE derivation every browser-deliverability comparison goes through.
     * <p>
     * <strong>Why it lives here rather than in the validator.</strong> A deliverability guard needs
     * the size of the emitted {@code Set-Cookie} header, and the only place that header is assembled
     * is {@link #toSetCookieHeader}. Deriving the overhead anywhere else means a second copy of the
     * same arithmetic drifting away from the first — which is exactly how a fixed 77-byte figure came
     * to stand in for a configurable quantity, leaving a gateway with a longer
     * {@code session.cookie_name} or a five-digit {@code session.ttl_seconds} emitting an
     * over-guarantee header with nothing to say so. This method and the header assembly count the
     * same two constants, and {@code SealedSessionCookieCodecTest} pins them against a formatted
     * header so a change to either alone turns a test red.
     * <p>
     * <strong>{@code Max-Age} is counted at its widest.</strong> The emitted attribute carries the
     * <em>remaining</em> lifetime, which is at most {@code sessionTtl} (the value at the login
     * instant) and shrinks from there, so the digit count of the full TTL is an upper bound on every
     * header this codec will ever emit for that configuration. A deliverability guard wants exactly
     * that bound: it must not report a header smaller than one the gateway can actually send.
     *
     * @param cookieName the resolved session-cookie name ({@code session.cookie_name}, or the
     *                   {@code __Host-} default when the key is omitted)
     * @param sessionTtl the resolved absolute session lifetime ({@code session.ttl_seconds})
     * @return the number of bytes the emitted {@code Set-Cookie} header adds around the sealed value
     */
    public static int setCookieHeaderOverhead(String cookieName, Duration sessionTtl) {
        requireNonBlank(cookieName);
        Objects.requireNonNull(sessionTtl, "sessionTtl");
        long widestMaxAge = Math.max(0L, sessionTtl.toSeconds());
        return cookieName.getBytes(StandardCharsets.UTF_8).length
                + NAME_VALUE_SEPARATOR_BYTES
                + MAX_AGE_ATTRIBUTE.length()
                + Long.toString(widestMaxAge).length()
                + HARDENING_ATTRIBUTES.length();
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

    /**
     * Compresses the encoded payload before it is sealed — the step that makes a three-token session
     * fit a single browser-safe cookie.
     * <p>
     * {@link Deflater#BEST_COMPRESSION} is chosen over the default level because the work is done
     * once per session change (login, refresh, logout) rather than per request, so the extra CPU buys
     * cookie bytes at a cost that is not on the hot path. The incompressible 43-character session
     * nonce is a fixed floor on what any level can achieve.
     */
    private static byte[] deflate(byte[] plaintext) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(plaintext);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(plaintext.length);
            byte[] chunk = new byte[ZIP_CHUNK_BYTES];
            while (!deflater.finished()) {
                compressed.write(chunk, 0, deflater.deflate(chunk));
            }
            return compressed.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Expands an authenticated compressed payload, bounded by
     * {@link SealedSessionPayload#MAX_PLAINTEXT_BYTES}.
     * <p>
     * The input has already cleared the GCM tag, so this is not untrusted-input decompression and the
     * bound is not a defence against a chosen compression bomb. It is the allocation guard for the
     * one case that survives authentication: a buffer sealed under this key that is not a well-formed
     * stream of this format — a corrupt or foreign payload — which could otherwise expand without
     * limit. Every refusal is {@link Optional#empty()}, which the caller turns into the
     * {@code payload-format} rejection: "no session", never an error.
     */
    private static Optional<byte[]> inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream plaintext = new ByteArrayOutputStream(compressed.length);
            byte[] chunk = new byte[ZIP_CHUNK_BYTES];
            while (!inflater.finished()) {
                int produced = inflater.inflate(chunk);
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    // A truncated stream, or one demanding a preset dictionary this format never
                    // writes: the inflater can make no further progress and would otherwise spin.
                    return Optional.empty();
                }
                if (plaintext.size() + produced > SealedSessionPayload.MAX_PLAINTEXT_BYTES) {
                    return Optional.empty();
                }
                plaintext.write(chunk, 0, produced);
            }
            return Optional.of(plaintext.toByteArray());
        } catch (DataFormatException _) {
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }

    private byte[] associatedData(byte version, byte keyId) {
        byte[] name = cookieName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(name.length + 2).put(name).put(version).put(keyId).array();
    }

    /**
     * Records the rejection and returns "no session".
     * <p>
     * <strong>This runs on EVERY failed unseal, and a failed unseal is remotely reachable.</strong>
     * {@link #unseal} is called per request from the session-authentication stage and from every
     * reserved BFF endpoint, and it is reached <em>before</em> anything about the caller is
     * authenticated — any client sending a junk {@code Cookie} header takes the
     * {@code malformed} or {@code authentication-tag} branch. An unconditional {@code WARN} here
     * is therefore an unauthenticated log-amplification lever (CWE-779) on a security gateway,
     * degrading the very channel a genuine tamper signal has to surface in.
     * <p>
     * The same shape floods without any attacker: with one sealing key there is no rollover, so
     * after a key change every still-live cookie takes the {@code unknown-key-id} branch once per
     * request per session for the entire re-authentication window. That is precisely the condition
     * the retired {@code previous_key} rollover path carried an {@link java.util.concurrent.atomic.AtomicBoolean}
     * latch for; collapsing to one key removed the latch while making its triggering condition more
     * frequent, so the bound is restored here, at the surface that actually emits the record.
     * <p>
     * The bound is one catalogued {@code WARN} per disposition per process: the operator still sees
     * every distinct rejection class the first time it occurs — the signal an alert fires on — while
     * the record count stays at most five regardless of request rate. Every repeat is a {@code DEBUG}
     * diagnostic, which carries no {@link de.cuioss.tools.logging.LogRecord} by the CUI logging
     * contract. No sensitive data is involved either way: the disposition is a fixed constant, never
     * the offending cookie value.
     *
     * @param disposition the bounded, non-sensitive rejection disposition
     * @return always {@link Optional#empty()} — a rejection is "no session", never an error
     */
    private Optional<Unsealed> reject(String disposition) {
        if (warnedDispositions.add(disposition)) {
            LOGGER.warn(BffLogMessages.WARN.COOKIE_UNSEAL_REJECTED, disposition);
        } else {
            LOGGER.debug("Sealed session cookie rejected: %s — already recorded at WARN for this "
                    + "disposition, so the repeat stays at DEBUG", disposition);
        }
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
     * <p>
     * <strong>Load-bearing — do not remove.</strong> This record is the payload wrapper of
     * {@link #unseal(String)}'s return type, and its production consumer is the
     * {@code readSealedValue(…).flatMap(codec::unseal).filter(…).map(…)} chain in
     * {@code CookieSessionBinding.resolve}. That call site uses the <em>method-reference</em> form
     * {@code codec::unseal}, so a reachability search for the call form {@code unseal(} alone reports
     * this API as having no production consumer — a false "unused" verdict that would justify an
     * unsafe removal. Search both forms before re-opening the question.
     * <p>
     * The {@code Optional<Unsealed>} return type is <em>not</em> a residue of the PLAN-36 sweep:
     * {@link de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec#unseal(String)} is a
     * computed method return, and ADR-0033 retires {@code Optional} only from <em>stored</em>
     * positions — fields, declared parameters and record components. A computed return is explicitly
     * sanctioned, so a future sweep should not re-flag it.
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
