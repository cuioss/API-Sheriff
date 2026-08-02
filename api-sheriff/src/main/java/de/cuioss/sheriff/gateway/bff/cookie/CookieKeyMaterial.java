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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The cookie-mode sealing key material (D2b, {@code session.mode: cookie}), resolved at boot into
 * one of two first-class, fully supported production modes.
 * <p>
 * <strong>(a) {@link Mode#PASSED}.</strong> The operator supplied {@code oidc.session.encryption_key}
 * as an {@code ${ENV_VAR}} reference, so no key material ever lives in a descriptor (ADR-0011). The
 * value must decode to a 256-bit key; anything else is rejected at boot with a message naming the
 * defect but never the value.
 * <p>
 * <strong>(b) {@link Mode#GENERATED}.</strong> Cookie mode is configured with no
 * {@code encryption_key}, so a fresh AES-256 key is generated from {@link SecureRandom} at boot and
 * INFO {@code COOKIE_KEY_GENERATED} records the fact — the mode only, never the material. This mode
 * carries two caveats the operator must accept: <em>every session is dropped on restart</em>, and
 * <em>the key cannot be shared across replicas</em>, so a multi-replica deployment must pass a key.
 * <p>
 * Exactly one sealing key is ever active — there is no decrypt-only companion key and no in-flight
 * rotation state. Replacing the configured key is therefore a clean break: every cookie still sealed
 * under the withdrawn key carries an unknown key id, unseals to "no session", and the browser simply
 * re-authenticates.
 * <p>
 * The type never logs, serialises, or {@link #toString()}s key bytes; the material is reachable only
 * through {@link #codec(String, Duration, int)} and {@link #identitySalt()}, both of which consume it
 * without disclosing it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CookieKeyMaterial {

    private static final CuiLogger LOGGER = new CuiLogger(CookieKeyMaterial.class);

    private static final String ALGORITHM = "AES";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int AES_256_KEY_BITS = AES_256_KEY_BYTES * 8;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String IDENTITY_SALT_LABEL = "api-sheriff:cookie-session-identity:v1";
    private static final String KEY_ID_LABEL = "api-sheriff:cookie-key-id:v1";
    private static final String ENCRYPTION_KEY_FIELD = "session.encryption_key";

    private final Mode mode;
    private final SecretKey currentKey;

    private CookieKeyMaterial(Mode mode, SecretKey currentKey) {
        this.mode = mode;
        this.currentKey = currentKey;
    }

    /**
     * Resolves the configured key reference into the active key material.
     *
     * @param encryptionKey the already-substituted {@code session.encryption_key} value, {@code null}
     *                      to select {@link Mode#GENERATED}
     * @return the resolved material
     * @throws IllegalStateException when the supplied value is not a base64 AES-256 key
     */
    public static CookieKeyMaterial resolve(@Nullable String encryptionKey) {
        if (encryptionKey == null) {
            SecretKey generated = generateKey();
            LOGGER.info(BffLogMessages.INFO.COOKIE_KEY_GENERATED, Mode.GENERATED.diagnosticName());
            return new CookieKeyMaterial(Mode.GENERATED, generated);
        }
        return new CookieKeyMaterial(Mode.PASSED, decodeKey(encryptionKey, ENCRYPTION_KEY_FIELD));
    }

    /**
     * @return the active key-material mode, for the startup diagnostic and the producer's reporting
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Builds the sealed-cookie codec over this material's one sealing key.
     *
     * @param cookieName          the session-cookie name bound into the associated data
     * @param sessionTtl          the absolute session lifetime from login
     * @param maxCookieValueBytes the configured sealed cookie-value size budget
     * @return the codec sealing and unsealing under the current key
     */
    public SealedSessionCookieCodec codec(String cookieName, Duration sessionTtl, int maxCookieValueBytes) {
        return new SealedSessionCookieCodec(cookieName, sessionTtl, maxCookieValueBytes, currentKey, currentKeyId());
    }

    /**
     * @return the key id every value sealed under the current key is stamped with
     */
    public byte currentKeyId() {
        return keyIdOf(currentKey);
    }

    /**
     * Derives a key's wire id from the key itself, so a given key carries the same id in every
     * deployment that holds it.
     * <p>
     * Deriving the id from the key — rather than fixing it positionally — is what makes a key change
     * fail closed: a cookie sealed under the withdrawn key still carries the withdrawn key's id, so
     * {@link SealedSessionCookieCodec#unseal} refuses it at the key-id gate before a {@link
     * javax.crypto.Cipher} is even constructed. A positional id would instead hand that cookie to
     * the replacement key and fall through to an authentication-tag failure.
     */
    private static byte keyIdOf(SecretKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            digest.update(KEY_ID_LABEL.getBytes(StandardCharsets.UTF_8));
            return digest.digest(key.getEncoded())[0];
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is required to derive the cookie-mode key id",
                    unavailable);
        }
    }

    /**
     * Derives the per-gateway salt keying the cookie-mode session identity. It is bound to the
     * current sealing key, so it needs no operator input and cannot be recomputed off-gateway; the
     * key bytes never leave this type.
     *
     * @return a fresh copy of the derived salt
     */
    public byte[] identitySalt() {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            digest.update(IDENTITY_SALT_LABEL.getBytes(StandardCharsets.UTF_8));
            return digest.digest(currentKey.getEncoded());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    DIGEST_ALGORITHM + " is required to derive the cookie-mode session identity salt", unavailable);
        }
    }

    /**
     * The sealing key's length in bytes — the crypto-strength fact, carrying none of the material.
     * <p>
     * Package-private on purpose: it exists so a test can assert the AES-256 key <em>length</em>
     * directly. The derived {@link #identitySalt()} cannot stand in for that: it is a fixed-width
     * SHA-256 digest, so it measures 32 bytes for a 128-bit key exactly as for a 256-bit one.
     *
     * @return the current sealing key's encoded length in bytes, always {@value #AES_256_KEY_BYTES}
     */
    int currentKeyLengthBytes() {
        return currentKey.getEncoded().length;
    }

    /**
     * Overridden to expose only the non-sensitive mode — never key material.
     *
     * @return the redacted description
     */
    @Override
    public String toString() {
        return "CookieKeyMaterial[mode=%s]".formatted(mode.diagnosticName());
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
            generator.init(AES_256_KEY_BITS, new SecureRandom());
            return generator.generateKey();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(ALGORITHM + " is required to generate the cookie-mode sealing key",
                    unavailable);
        }
    }

    private static SecretKey decodeKey(String encoded, String field) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            // The offending value is key material — name the field, never echo the value.
            throw new IllegalStateException(field + " is not valid base64", notBase64);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException("%s must decode to %d bytes (AES-256), but was %d"
                    .formatted(field, AES_256_KEY_BYTES, decoded.length));
        }
        return new SecretKeySpec(decoded, ALGORITHM);
    }

    /**
     * The two first-class cookie key-material modes.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum Mode {

        /** The operator passed {@code session.encryption_key}, so sessions survive a restart. */
        PASSED("passed key"),

        /** No key was configured, so one was generated at startup and is lost on restart. */
        GENERATED("generated on startup");

        private final String diagnosticName;

        Mode(String diagnosticName) {
            this.diagnosticName = diagnosticName;
        }

        /**
         * @return the bounded, non-sensitive name this mode is reported under in diagnostics
         */
        public String diagnosticName() {
            return diagnosticName;
        }
    }
}
