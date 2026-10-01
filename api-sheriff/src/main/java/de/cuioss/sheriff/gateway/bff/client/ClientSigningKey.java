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
package de.cuioss.sheriff.gateway.bff.client;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.token.client.auth.PrivateKeyJwtAuth;
import de.cuioss.sheriff.token.client.dpop.DpopProofGenerator;
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.validation.security.JwsAlgorithm;
import de.cuioss.sheriff.token.validation.util.JwkThumbprintUtil;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * One signing key of the confidential client (ADR-0057), resolved at boot into one of two
 * first-class, fully supported production modes. The gateway holds one such key per
 * {@link Purpose}: the key that signs the {@code private_key_jwt} client assertion, and the key that
 * signs the DPoP proofs its tokens are bound to.
 * <p>
 * <strong>(a) {@link Mode#PROVIDED}.</strong> The operator named a PEM file on a mount. The file is
 * read once, bounded to {@value #MAX_KEY_FILE_BYTES} bytes, and must hold exactly one unencrypted
 * PKCS#8 {@code PRIVATE KEY} block and exactly one {@code PUBLIC KEY} block and nothing else but
 * whitespace. The key type is established by which JDK key factory accepts the blocks — no ASN.1 is
 * parsed here. One sign-and-verify round proves that the two halves belong together.
 * <p>
 * <strong>(b) {@link Mode#GENERATED}.</strong> No key file is configured, so an EC P-256 key pair is
 * generated from {@link SecureRandom} at boot and INFO {@code SIGNING_KEY_GENERATED} records the
 * purpose and the mode — never the material. This mode carries two caveats the operator must accept:
 * <em>the key is replaced on every restart</em>, and <em>it cannot be shared across replicas</em>, so
 * a multi-replica deployment must provide a key file.
 * <p>
 * <strong>The algorithm follows the key type.</strong> An RSA key of at least
 * {@value #RSA_MINIMUM_MODULUS_BITS} bits signs {@code PS256}; an EC key on curve P-256 signs
 * {@code ES256}. There is no algorithm setting, and both modes are held to the same floor.
 * <p>
 * <strong>The key id is derived, never configured.</strong> It is the RFC 7638 thumbprint of the
 * public key, so it changes exactly when the key does.
 * <p>
 * Exactly one key per purpose is ever active — there is no retiring companion key. The type never
 * logs, serialises, or {@link #toString()}s key material and has no accessor that returns the
 * private key; the material is reachable only through {@link #clientAuthentication(String, String)}
 * and {@link #senderConstraint()}, both of which hand it to the token engine without disclosing it.
 * Every refusal names the configuration field of the purpose and the defect, and never the file
 * content nor the configured path.
 * <p>
 * Instances are immutable and safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ClientSigningKey {

    private static final CuiLogger LOGGER = new CuiLogger(ClientSigningKey.class);

    /** The upper bound, in bytes, of a provided key file. */
    static final int MAX_KEY_FILE_BYTES = 16 * 1024;

    /** The smallest RSA modulus, in bits, either half of a provided key may carry. */
    static final int RSA_MINIMUM_MODULUS_BITS = 2048;

    private static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";
    private static final String PUBLIC_KEY_LABEL = "PUBLIC KEY";
    private static final String ENCRYPTED_PRIVATE_KEY_LABEL = "ENCRYPTED PRIVATE KEY";
    private static final String RSA_PRIVATE_KEY_LABEL = "RSA PRIVATE KEY";
    private static final String EC_PRIVATE_KEY_LABEL = "EC PRIVATE KEY";
    private static final String PEM_BEGIN = "-----BEGIN ";
    private static final String PEM_END = "-----END ";
    private static final String PEM_DASHES = "-----";
    private static final int MAX_PEM_LABEL_LENGTH = 64;
    private static final String PKCS8_CONVERSION_HINT =
            "convert it to an unencrypted PKCS#8 PRIVATE KEY block, for example with "
                    + "'openssl pkcs8 -topk8 -nocrypt'";

    private static final String RSA = "RSA";
    private static final String EC = "EC";
    private static final String EDDSA = "EdDSA";
    private static final String CURVE_SECP256R1 = "secp256r1";
    private static final String JWK_CURVE_P256 = "P-256";
    private static final int P256_COORDINATE_BYTES = 32;
    private static final String ECDSA_SHA256 = "SHA256withECDSA";
    private static final String PROBE_MESSAGE = "api-sheriff:client-signing-key:halves-probe:v1";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final Purpose purpose;
    private final Mode mode;
    private final JwsAlgorithm algorithm;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final Map<String, Object> publicJwk;

    private ClientSigningKey(Purpose purpose, Mode mode, PrivateKey privateKey, PublicKey publicKey) {
        this.purpose = purpose;
        this.mode = mode;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.algorithm = requireSupportedKey(purpose, privateKey, publicKey);
        Map<String, Object> publicMembers = publicMembersOf(publicKey);
        Map<String, Object> thumbprintInput = new LinkedHashMap<>();
        thumbprintInput.put("kty", algorithm.getKeyType());
        thumbprintInput.putAll(publicMembers);
        this.keyId = JwkThumbprintUtil.computeThumbprint(thumbprintInput);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", algorithm.getKeyType());
        jwk.put("use", "sig");
        jwk.put("alg", algorithm.getJwaName());
        jwk.put("kid", keyId);
        jwk.putAll(publicMembers);
        this.publicJwk = Collections.unmodifiableMap(jwk);
    }

    /**
     * Resolves the configured key reference into the active key material.
     *
     * @param keyFile the already-substituted {@code key_file} value of the purpose's block — the path
     *                of a PEM file — or {@code null} to select {@link Mode#GENERATED}
     * @param purpose what the key signs; selects the configuration field named in refusals and the
     *                label of the generated-key record
     * @return the resolved key
     * @throws IllegalStateException when a provided file is missing, unreadable, not a regular file
     *                               or oversize, does not hold exactly one unencrypted PKCS#8
     *                               {@code PRIVATE KEY} block and one {@code PUBLIC KEY} block, holds
     *                               a key that is neither RSA of at least
     *                               {@value #RSA_MINIMUM_MODULUS_BITS} bits nor EC on curve P-256, or
     *                               holds two halves that do not belong together
     */
    public static ClientSigningKey resolve(@Nullable String keyFile, Purpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (keyFile == null) {
            ClientSigningKey generated = generate(purpose);
            LOGGER.info(BffLogMessages.INFO.SIGNING_KEY_GENERATED, purpose.logLabel(),
                    Mode.GENERATED.diagnosticName());
            return generated;
        }
        return load(keyFile, purpose);
    }

    /**
     * @return the active key-material mode, for the startup diagnostic and the producer's reporting
     */
    public Mode mode() {
        return mode;
    }

    /**
     * @return the JOSE {@code alg} name this key signs with — {@code PS256} for an RSA key,
     *         {@code ES256} for an EC P-256 key
     */
    public String algorithm() {
        return algorithm.getJwaName();
    }

    /**
     * @return the key id — the RFC 7638 base64url thumbprint of the public key, identical to the
     *         {@code jkt} the token engine computes for the same key
     */
    public String keyId() {
        return keyId;
    }

    /**
     * The public half as a JWK, ready to be published in a key set.
     * <p>
     * The map is insertion-ordered: {@code kty}, {@code use} ({@code sig}), {@code alg}, {@code kid},
     * then the public members of the key type ({@code n} and {@code e} for RSA; {@code crv},
     * {@code x} and {@code y} for EC). It carries no {@code key_ops} and no private member.
     *
     * @return the unmodifiable public JWK
     */
    public Map<String, Object> publicJwk() {
        return publicJwk;
    }

    /**
     * Hands the key to the token engine as {@code private_key_jwt} client authentication.
     *
     * @param clientId the OAuth 2.0 client id, used as the assertion's {@code iss} and {@code sub}
     * @param audience the assertion's {@code aud}, rendered by the engine as one JSON string
     * @return the client authentication signing with this key under {@link #keyId()} and
     *         {@link #algorithm()}
     */
    public PrivateKeyJwtAuth clientAuthentication(String clientId, String audience) {
        return new PrivateKeyJwtAuth(clientId, audience, privateKey, keyId, algorithm.getJwaName());
    }

    /**
     * Hands the key to the token engine as a DPoP sender constraint.
     *
     * @return a DPoP sender constraint whose proofs are signed with this key under
     *         {@link #algorithm()} and whose binding confirms {@link #keyId()}
     */
    public SenderConstraint senderConstraint() {
        return SenderConstraint.dpop(
                new DpopProofGenerator(new KeyPair(publicKey, privateKey), algorithm.getJwaName()));
    }

    /**
     * Overridden to expose only the non-sensitive purpose, mode and algorithm — never key material.
     *
     * @return the redacted description
     */
    @Override
    public String toString() {
        return "ClientSigningKey[purpose=%s, mode=%s, algorithm=%s]".formatted(purpose.logLabel(),
                mode.diagnosticName(), algorithm.getJwaName());
    }

    private static ClientSigningKey generate(Purpose purpose) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(EC);
            generator.initialize(new ECGenParameterSpec(CURVE_SECP256R1), new SecureRandom());
            KeyPair generated = generator.generateKeyPair();
            return new ClientSigningKey(purpose, Mode.GENERATED, generated.getPrivate(), generated.getPublic());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "EC curve P-256 is required to generate the signing key for " + purpose.configField(),
                    unavailable);
        }
    }

    private static ClientSigningKey load(String keyFile, Purpose purpose) {
        Map<String, List<String>> blocks = parseBlocks(readBounded(keyFile, purpose), purpose);
        KeySpec privateSpec = new PKCS8EncodedKeySpec(singleBlock(blocks, PRIVATE_KEY_LABEL, purpose));
        KeySpec publicSpec = new X509EncodedKeySpec(singleBlock(blocks, PUBLIC_KEY_LABEL, purpose));
        for (String keyType : List.of(RSA, EC)) {
            Optional<PrivateKey> privateKey = decode(keyType, privateSpec, KeyFactory::generatePrivate);
            if (privateKey.isEmpty()) {
                continue;
            }
            PublicKey publicKey = decode(keyType, publicSpec, KeyFactory::generatePublic)
                    .orElseThrow(() -> refusal(purpose,
                            ("holds a PRIVATE KEY block with an %s key and a PUBLIC KEY block that is not an %s "
                                    + "public key; both blocks must hold the two halves of one key")
                                    .formatted(keyType, keyType)));
            ClientSigningKey provided = new ClientSigningKey(purpose, Mode.PROVIDED, privateKey.get(), publicKey);
            provided.proveHalvesMatch();
            return provided;
        }
        if (decode(EDDSA, privateSpec, KeyFactory::generatePrivate).isPresent()) {
            throw refusal(purpose, "holds an EdDSA key; EdDSA is not offered, because the token engine's "
                    + "client-assertion signer does not sign it — provide an RSA key of at least "
                    + RSA_MINIMUM_MODULUS_BITS + " bits or an EC key on curve P-256");
        }
        throw refusal(purpose, "holds a PRIVATE KEY block that is neither an RSA nor an EC key in "
                + "unencrypted PKCS#8 form");
    }

    /**
     * Reads the key file once, bounded. The I/O failure text of the JDK carries the path, so no
     * cause is chained into the refusal.
     */
    private static String readBounded(String keyFile, Purpose purpose) {
        Path path;
        try {
            path = Path.of(keyFile);
        } catch (InvalidPathException malformed) {
            // The exception text echoes the configured value — name the field, never chain the cause.
            throw refusal(purpose, "does not name a usable file path");
        }
        if (Files.notExists(path)) {
            throw refusal(purpose, "names a file that does not exist");
        }
        if (!Files.isRegularFile(path)) {
            throw refusal(purpose, "does not name a regular file");
        }
        try (InputStream content = Files.newInputStream(path)) {
            byte[] bytes = content.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (bytes.length > MAX_KEY_FILE_BYTES) {
                throw refusal(purpose, "names a file larger than the %d byte limit".formatted(MAX_KEY_FILE_BYTES));
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            // The exception text carries the path — name the field, never chain the cause.
            throw refusal(purpose, "names a file that cannot be read");
        }
    }

    /**
     * Splits the file into its PEM blocks, keyed by label in file order. Anything outside a block
     * other than whitespace is refused, and so is every label other than the two the file may hold.
     */
    private static Map<String, List<String>> parseBlocks(String content, Purpose purpose) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        @Nullable String openLabel = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : content.lines().toList()) {
            String line = rawLine.strip();
            if (openLabel == null) {
                if (line.isEmpty()) {
                    continue;
                }
                openLabel = beginLabelOf(line, purpose);
                body.setLength(0);
            } else if (line.equals(PEM_END + openLabel + PEM_DASHES)) {
                blocks.computeIfAbsent(openLabel, _ -> new ArrayList<>()).add(body.toString());
                openLabel = null;
            } else if (line.startsWith(PEM_DASHES)) {
                throw refusal(purpose, "holds a %s block that is not closed by its own END line".formatted(openLabel));
            } else {
                body.append(line);
            }
        }
        if (openLabel != null) {
            throw refusal(purpose, "holds a %s block that is not closed by its own END line".formatted(openLabel));
        }
        return blocks;
    }

    /**
     * Reads the label of a {@code BEGIN} line and refuses everything that is not one of the two
     * supported blocks. A refused label is named only when it is a well-formed PEM label, so a
     * refusal can never echo arbitrary file content.
     */
    private static String beginLabelOf(String line, Purpose purpose) {
        if (!line.startsWith(PEM_BEGIN) || !line.endsWith(PEM_DASHES)
                || line.length() <= PEM_BEGIN.length() + PEM_DASHES.length()) {
            throw refusal(purpose, "holds content outside a PEM block; the file must hold exactly one "
                    + "PRIVATE KEY block and one PUBLIC KEY block and nothing else but whitespace");
        }
        String label = line.substring(PEM_BEGIN.length(), line.length() - PEM_DASHES.length());
        if (label.length() > MAX_PEM_LABEL_LENGTH || !label.chars().allMatch(ClientSigningKey::isLabelCharacter)) {
            throw refusal(purpose, "holds a malformed PEM BEGIN line");
        }
        return switch (label) {
            case PRIVATE_KEY_LABEL, PUBLIC_KEY_LABEL -> label;
            case ENCRYPTED_PRIVATE_KEY_LABEL -> throw refusal(purpose,
                    "holds an ENCRYPTED PRIVATE KEY block; an encrypted key is not supported — "
                            + PKCS8_CONVERSION_HINT);
            case RSA_PRIVATE_KEY_LABEL, EC_PRIVATE_KEY_LABEL -> throw refusal(purpose,
                    "holds a block labelled %s; that key format is not supported — %s".formatted(label,
                            PKCS8_CONVERSION_HINT));
            default -> throw refusal(purpose,
                    ("holds an unsupported PEM block '%s'; the file must hold exactly one PRIVATE KEY block "
                            + "and one PUBLIC KEY block").formatted(label));
        };
    }

    private static boolean isLabelCharacter(int character) {
        return character == ' ' || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9');
    }

    private static byte[] singleBlock(Map<String, List<String>> blocks, String label, Purpose purpose) {
        List<String> bodies = blocks.getOrDefault(label, List.of());
        if (bodies.isEmpty()) {
            throw refusal(purpose, "holds no %s block".formatted(label));
        }
        if (bodies.size() > 1) {
            throw refusal(purpose, "holds more than one %s block".formatted(label));
        }
        try {
            return Base64.getDecoder().decode(bodies.getFirst());
        } catch (IllegalArgumentException notBase64) {
            // The offending text is key material — name the field and the block, never chain the cause.
            throw refusal(purpose, "holds a %s block that is not valid base64".formatted(label));
        }
    }

    /**
     * Asks the JDK key factory of one key type whether it accepts the encoded key. A factory that
     * refuses the encoding is the answer "not this key type", not a failure.
     */
    private static <K extends Key> Optional<K> decode(String keyType, KeySpec encoded, KeyDecoding<K> decoding) {
        try {
            return Optional.of(decoding.decode(KeyFactory.getInstance(keyType), encoded));
        } catch (InvalidKeySpecException notThisKeyType) {
            return Optional.empty();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("The JDK offers no %s key factory".formatted(keyType), unavailable);
        }
    }

    /**
     * Establishes the key type of a key pair, holds both halves to the floor of that type and
     * returns the one algorithm the type signs with.
     */
    private static JwsAlgorithm requireSupportedKey(Purpose purpose, PrivateKey privateKey, PublicKey publicKey) {
        if (privateKey instanceof RSAKey privateRsa && publicKey instanceof RSAKey publicRsa) {
            if (privateRsa.getModulus().bitLength() < RSA_MINIMUM_MODULUS_BITS
                    || publicRsa.getModulus().bitLength() < RSA_MINIMUM_MODULUS_BITS) {
                throw refusal(purpose, "holds an RSA key with a modulus below %d bits".formatted(
                        RSA_MINIMUM_MODULUS_BITS));
            }
            return JwsAlgorithm.PS256;
        }
        if (privateKey instanceof ECKey privateEc && publicKey instanceof ECKey publicEc) {
            ECParameterSpec p256 = p256Parameters();
            if (!isOnCurve(privateEc.getParams(), p256) || !isOnCurve(publicEc.getParams(), p256)) {
                throw refusal(purpose, "holds an EC key on a curve other than P-256");
            }
            return JwsAlgorithm.ES256;
        }
        throw refusal(purpose, "holds a key that is neither RSA nor EC");
    }

    private static ECParameterSpec p256Parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance(EC);
            parameters.init(new ECGenParameterSpec(CURVE_SECP256R1));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("The JDK does not offer curve P-256 (secp256r1)", unavailable);
        }
    }

    /**
     * Compares the full domain parameters. A field-size check alone would admit any other 256-bit
     * curve, whose key would then be published in a JWK claiming {@code "crv":"P-256"}.
     * {@link ECParameterSpec} defines no {@code equals}, so its components are compared one by one.
     */
    private static boolean isOnCurve(ECParameterSpec actual, ECParameterSpec expected) {
        return expected.getCurve().equals(actual.getCurve())
                && expected.getGenerator().equals(actual.getGenerator())
                && expected.getOrder().equals(actual.getOrder())
                && expected.getCofactor() == actual.getCofactor();
    }

    /**
     * Proves by one sign-and-verify round that the two halves of a provided key belong together.
     */
    private void proveHalvesMatch() {
        byte[] probe = PROBE_MESSAGE.getBytes(StandardCharsets.US_ASCII);
        boolean verified;
        try {
            Signature signer = newSignature();
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = newSignature();
            verifier.initVerify(publicKey);
            verifier.update(probe);
            verified = verifier.verify(signature);
        } catch (GeneralSecurityException unusable) {
            // The failure is derived from the file's content — name the field, never chain the cause.
            throw refusal(purpose, "holds a key that cannot produce and verify a %s signature".formatted(
                    algorithm.getJwaName()));
        }
        if (!verified) {
            throw refusal(purpose, "holds a PRIVATE KEY block and a PUBLIC KEY block that do not belong to the "
                    + "same key");
        }
    }

    private Signature newSignature() throws GeneralSecurityException {
        Signature signature = Signature.getInstance(algorithm.getJcaSignatureAlgorithm().orElse(ECDSA_SHA256));
        Optional<PSSParameterSpec> pssParameters = algorithm.getPssParameters();
        if (pssParameters.isPresent()) {
            signature.setParameter(pssParameters.get());
        }
        return signature;
    }

    /**
     * Renders the public members of the key type, in the order they are published. The encodings
     * follow RFC 7518: the RSA members are unsigned and variable-width, the EC coordinates are
     * left-padded to the full coordinate width.
     */
    private static Map<String, Object> publicMembersOf(PublicKey publicKey) {
        Map<String, Object> members = new LinkedHashMap<>();
        if (publicKey instanceof RSAPublicKey rsaKey) {
            members.put("n", base64UrlUnsigned(rsaKey.getModulus()));
            members.put("e", base64UrlUnsigned(rsaKey.getPublicExponent()));
        } else if (publicKey instanceof ECPublicKey ecKey) {
            members.put("crv", JWK_CURVE_P256);
            members.put("x", base64UrlFixedWidth(ecKey.getW().getAffineX()));
            members.put("y", base64UrlFixedWidth(ecKey.getW().getAffineY()));
        }
        return members;
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return BASE64_URL.encodeToString(bytes);
    }

    private static String base64UrlFixedWidth(BigInteger coordinate) {
        byte[] magnitude = coordinate.toByteArray();
        int offset = 0;
        while (offset < magnitude.length - 1 && magnitude[offset] == 0) {
            offset++;
        }
        int significant = magnitude.length - offset;
        byte[] fixedWidth = new byte[P256_COORDINATE_BYTES];
        System.arraycopy(magnitude, offset, fixedWidth, P256_COORDINATE_BYTES - significant, significant);
        return BASE64_URL.encodeToString(fixedWidth);
    }

    private static IllegalStateException refusal(Purpose purpose, String defect) {
        return new IllegalStateException(purpose.configField() + " " + defect);
    }

    /**
     * One decoding step of a JDK key factory, so both halves are decoded through the same probe.
     *
     * @param <K> the key half the step yields
     */
    @FunctionalInterface
    private interface KeyDecoding<K extends Key> {

        K decode(KeyFactory factory, KeySpec encoded) throws InvalidKeySpecException;
    }

    /**
     * The two first-class signing-key modes.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum Mode {

        /** The operator named a key file, so the key survives a restart and can be shared by replicas. */
        PROVIDED("provided key file"),

        /** No key file was configured, so a key was generated at startup and is replaced on restart. */
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

    /**
     * What a signing key is used for. Each purpose has its own configuration block, its own key and
     * its own key id.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum Purpose {

        /** The key that signs the {@code private_key_jwt} client assertion. */
        CLIENT_AUTHENTICATION("oidc.client_authentication.key_file", "client-authentication"),

        /** The key that signs the DPoP proofs the tokens are bound to. */
        SENDER_CONSTRAINT("oidc.sender_constraint.key_file", "sender-constraint");

        private final String configField;
        private final String logLabel;

        Purpose(String configField, String logLabel) {
            this.configField = configField;
            this.logLabel = logLabel;
        }

        /**
         * @return the configuration field a refusal names for this purpose
         */
        public String configField() {
            return configField;
        }

        /**
         * @return the bounded, non-sensitive label this purpose is logged under
         */
        public String logLabel() {
            return logLabel;
        }
    }
}
