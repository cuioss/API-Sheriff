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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Signing-key fixtures for the tests of {@link ClientSigningKey} and of everything assembled over
 * it. Every key is generated with {@link KeyPairGenerator} when a test asks for it and written as a
 * PEM file into a directory the test supplies, so no key material is committed for unit tests.
 * <p>
 * The helper writes the one accepted file shape — an unencrypted PKCS#8 {@code PRIVATE KEY} block
 * followed by a {@code PUBLIC KEY} block — and one variant per way a provided key file can be
 * refused: a single block, a duplicated block, a relabelled block, two halves that do not belong
 * together, two halves of different key types, an undersized RSA key, an EC key on another curve
 * and an Ed25519 key.
 */
public final class TestSigningKeys {

    /** The PEM label of the unencrypted PKCS#8 private block. */
    public static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";

    /** The PEM label of the public block. */
    public static final String PUBLIC_KEY_LABEL = "PUBLIC KEY";

    private static final int RSA_BITS = 2048;
    private static final int UNDERSIZED_RSA_BITS = 1024;
    private static final String CURVE_P256 = "secp256r1";
    private static final String CURVE_P384 = "secp384r1";
    private static final int PEM_LINE_LENGTH = 64;

    private TestSigningKeys() {
    }

    /**
     * @return a fresh RSA key pair at the accepted floor of 2048 bits
     */
    public static KeyPair rsaKeyPair() {
        return rsaKeyPair(RSA_BITS);
    }

    /**
     * @return a fresh RSA key pair of 1024 bits, below the accepted floor
     */
    public static KeyPair undersizedRsaKeyPair() {
        return rsaKeyPair(UNDERSIZED_RSA_BITS);
    }

    /**
     * @return a fresh EC key pair on the accepted curve P-256
     */
    public static KeyPair ecKeyPair() {
        return ecKeyPair(CURVE_P256);
    }

    /**
     * @return a fresh EC key pair on curve P-384, which is not accepted
     */
    public static KeyPair otherCurveKeyPair() {
        return ecKeyPair(CURVE_P384);
    }

    /**
     * @return a fresh Ed25519 key pair, a key type that is not accepted
     */
    public static KeyPair ed25519KeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Ed25519 key generation is unavailable", unavailable);
        }
    }

    /**
     * Writes the accepted file shape: the private block followed by the public block of one pair.
     *
     * @param directory the directory the test supplies
     * @param keyPair   the pair to write
     * @return the written key file
     */
    public static Path writeKeyFile(Path directory, KeyPair keyPair) {
        return writeHalves(directory, keyPair.getPrivate(), keyPair.getPublic());
    }

    /**
     * Writes a private block and a public block that need not belong together. Passing the halves of
     * two pairs of one key type yields mismatched halves; passing the halves of an RSA pair and an
     * EC pair yields mixed key types.
     *
     * @param directory  the directory the test supplies
     * @param privateKey the key written into the {@code PRIVATE KEY} block
     * @param publicKey  the key written into the {@code PUBLIC KEY} block
     * @return the written key file
     */
    public static Path writeHalves(Path directory, PrivateKey privateKey, PublicKey publicKey) {
        return writeContent(directory, privateBlock(privateKey) + publicBlock(publicKey));
    }

    /**
     * @param directory the directory the test supplies
     * @param keyPair   the pair whose private half is written
     * @return a key file holding the private block only
     */
    public static Path writePrivateBlockOnly(Path directory, KeyPair keyPair) {
        return writeContent(directory, privateBlock(keyPair.getPrivate()));
    }

    /**
     * @param directory the directory the test supplies
     * @param keyPair   the pair whose public half is written
     * @return a key file holding the public block only
     */
    public static Path writePublicBlockOnly(Path directory, KeyPair keyPair) {
        return writeContent(directory, publicBlock(keyPair.getPublic()));
    }

    /**
     * @param directory the directory the test supplies
     * @param keyPair   the pair to write
     * @return a key file holding the private block twice and the public block once
     */
    public static Path writeDuplicatedPrivateBlock(Path directory, KeyPair keyPair) {
        return writeContent(directory, privateBlock(keyPair.getPrivate()) + privateBlock(keyPair.getPrivate())
                + publicBlock(keyPair.getPublic()));
    }

    /**
     * @param directory the directory the test supplies
     * @param keyPair   the pair to write
     * @return a key file holding the private block once and the public block twice
     */
    public static Path writeDuplicatedPublicBlock(Path directory, KeyPair keyPair) {
        return writeContent(directory, privateBlock(keyPair.getPrivate()) + publicBlock(keyPair.getPublic())
                + publicBlock(keyPair.getPublic()));
    }

    /**
     * Writes a key file whose private block carries another PEM label — the shape of an encrypted
     * key ({@code ENCRYPTED PRIVATE KEY}) or of a key in a pre-PKCS#8 format
     * ({@code RSA PRIVATE KEY}, {@code EC PRIVATE KEY}). The block body stays the PKCS#8 encoding:
     * the label alone is what the refusal is decided on.
     *
     * @param directory    the directory the test supplies
     * @param keyPair      the pair to write
     * @param privateLabel the PEM label written in place of {@code PRIVATE KEY}
     * @return the written key file
     */
    public static Path writeRelabelledPrivateBlock(Path directory, KeyPair keyPair, String privateLabel) {
        return writeContent(directory, pemBlock(privateLabel, keyPair.getPrivate().getEncoded())
                + publicBlock(keyPair.getPublic()));
    }

    /**
     * Writes arbitrary content as a key file, for the shapes no other method produces.
     *
     * @param directory the directory the test supplies
     * @param content   the file content
     * @return the written key file
     */
    public static Path writeContent(Path directory, String content) {
        try {
            Path keyFile = Files.createTempFile(directory, "signing-key-", ".pem");
            return Files.writeString(keyFile, content, StandardCharsets.US_ASCII);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("could not write the test key file", unwritable);
        }
    }

    /**
     * @param privateKey the key to render
     * @return the unencrypted PKCS#8 {@code PRIVATE KEY} block of the key
     */
    public static String privateBlock(PrivateKey privateKey) {
        return pemBlock(PRIVATE_KEY_LABEL, privateKey.getEncoded());
    }

    /**
     * @param publicKey the key to render
     * @return the {@code PUBLIC KEY} block of the key
     */
    public static String publicBlock(PublicKey publicKey) {
        return pemBlock(PUBLIC_KEY_LABEL, publicKey.getEncoded());
    }

    /**
     * @param label the PEM label
     * @param body  the block's binary content
     * @return one PEM block, its body wrapped at 64 characters and terminated by a line break
     */
    public static String pemBlock(String label, byte[] body) {
        String encoded = Base64.getMimeEncoder(PEM_LINE_LENGTH, new byte[]{'\n'}).encodeToString(body);
        return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n";
    }

    private static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("RSA key generation is unavailable", unavailable);
        }
    }

    private static KeyPair ecKeyPair(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("EC key generation on " + curve + " is unavailable", unavailable);
        }
    }
}
