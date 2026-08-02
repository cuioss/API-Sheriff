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
 * — and optionally the decrypt-only {@code oidc.session.previous_key} — as {@code ${ENV_VAR}}
 * references, so no key material ever lives in a descriptor (ADR-0011). Each value must decode to a
 * 256-bit key; anything else is rejected at boot with a message naming the defect but never the
 * value. Rotation composes with this mode only.
 * <p>
 * <strong>(b) {@link Mode#GENERATED}.</strong> Cookie mode is configured with no
 * {@code encryption_key}, so a fresh AES-256 key is generated from {@link SecureRandom} at boot and
 * INFO {@code COOKIE_KEY_GENERATED} records the fact — the mode only, never the material. This mode
 * carries two caveats the operator must accept: <em>every session is dropped on restart</em>, and
 * <em>the key cannot be shared across replicas</em>, so a multi-replica deployment must pass a key.
 * There is by construction no previous key in this mode, so a {@code previous_key} without an
 * {@code encryption_key} is refused rather than silently ignored — the same companion rule
 * {@code ConfigValidator} enforces at configuration-validation time.
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
    private static final String PREVIOUS_KEY_FIELD = "session.previous_key";

    private final Mode mode;
    private final SecretKey currentKey;
    private final @Nullable SecretKey previousKey;

    private CookieKeyMaterial(Mode mode, SecretKey currentKey, @Nullable SecretKey previousKey) {
        this.mode = mode;
        this.currentKey = currentKey;
        this.previousKey = previousKey;
    }

    /**
     * Resolves the configured key references into the active key material.
     *
     * @param encryptionKey the already-substituted {@code session.encryption_key} value, {@code null}
     *                      to select {@link Mode#GENERATED}
     * @param previousKey   the already-substituted {@code session.previous_key} value, {@code null}
     *                      when no rotation is in progress
     * @return the resolved material
     * @throws IllegalStateException when a supplied value is not a base64 AES-256 key, or when a
     *                               {@code previous_key} is supplied without an {@code encryption_key}
     */
    public static CookieKeyMaterial resolve(@Nullable String encryptionKey, @Nullable String previousKey) {
        if (encryptionKey == null) {
            if (previousKey != null) {
                // A decrypt-only rotation key with no current key to roll onto is semantically
                // nonsensical, and rotation composes with the passed-key mode only.
                throw new IllegalStateException(
                        PREVIOUS_KEY_FIELD + " requires " + ENCRYPTION_KEY_FIELD + " — rotation composes with the "
                                + "passed-key mode only, and the generate-on-startup mode has no previous key");
            }
            SecretKey generated = generateKey();
            LOGGER.info(BffLogMessages.INFO.COOKIE_KEY_GENERATED, Mode.GENERATED.diagnosticName());
            return new CookieKeyMaterial(Mode.GENERATED, generated, null);
        }
        SecretKey current = decodeKey(encryptionKey, ENCRYPTION_KEY_FIELD);
        SecretKey previous = previousKey == null ? null : decodeKey(previousKey, PREVIOUS_KEY_FIELD);
        if (previous != null && keyIdOf(current) == keyIdOf(previous)) {
            // The 1-byte wire key id selects the generation deterministically, so two keys that
            // derive the same id cannot be told apart. Fail the boot rather than roll over into a
            // state where the retired cookies decrypt under the wrong key.
            throw new IllegalStateException(ENCRYPTION_KEY_FIELD + " and " + PREVIOUS_KEY_FIELD
                    + " derive the same key id — rotate to a different encryption_key");
        }
        return new CookieKeyMaterial(Mode.PASSED, current, previous);
    }

    /**
     * @return the active key-material mode, for the startup diagnostic and the producer's reporting
     */
    public Mode mode() {
        return mode;
    }

    /**
     * @return {@code true} when a decrypt-only rotation key is active
     */
    public boolean hasPreviousKey() {
        return previousKey != null;
    }

    /**
     * Builds the sealed-cookie codec over this material, wiring the decrypt-only previous key only
     * when one is active.
     *
     * @param cookieName          the session-cookie name bound into the associated data
     * @param sessionTtl          the absolute session lifetime from login
     * @param maxCookieValueBytes the configured sealed cookie-value size budget
     * @return the codec sealing under the current key and, when rotating, unsealing under both
     */
    public SealedSessionCookieCodec codec(String cookieName, Duration sessionTtl, int maxCookieValueBytes) {
        SecretKey retired = previousKey;
        if (retired == null) {
            return new SealedSessionCookieCodec(cookieName, sessionTtl, maxCookieValueBytes, currentKey,
                    currentKeyId());
        }
        return new SealedSessionCookieCodec(cookieName, sessionTtl, maxCookieValueBytes, currentKey, currentKeyId(),
                retired, keyIdOf(retired));
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
     * This is what makes rotation work across a restart: the key that was <em>current</em> before
     * the rotation keeps its id when it becomes the <em>previous</em> key, so the cookies it sealed
     * still select it. A positional id (say, "current is always 1") would instead hand the retired
     * cookies to the new key and silently drop every session the rotation was meant to preserve.
     * The 1-byte field means two keys can collide; {@link #resolve} fails the boot in that case.
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
     * Overridden to expose only the non-sensitive mode and rotation state — never key material.
     *
     * @return the redacted description
     */
    @Override
    public String toString() {
        return "CookieKeyMaterial[mode=%s, rotating=%s]".formatted(mode.diagnosticName(), hasPreviousKey());
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

        /** The operator passed {@code session.encryption_key}; rotation composes with this mode. */
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
