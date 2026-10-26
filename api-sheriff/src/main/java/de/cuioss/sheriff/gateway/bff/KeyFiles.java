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
package de.cuioss.sheriff.gateway.bff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

import lombok.experimental.UtilityClass;

/**
 * The one bounded read of a BFF key file — the step the two signing-key files
 * ({@code ClientSigningKey}, ADR-0058) and the cookie sealing-key file ({@code CookieKeyMaterial},
 * ADR-0072) share.
 * <p>
 * <strong>It parses nothing.</strong> It answers one question only: is the configured path a
 * readable regular file within the caller's byte bound? The bytes go back to the caller, which owns
 * its format — the PEM parser of the signing keys and the base64 decode of the sealing key stay
 * separate, so no shared code can admit one format where the other is expected.
 * <p>
 * <strong>A refusal names the field, never the path.</strong> Every refusal is an
 * {@link IllegalStateException} whose message is the caller's configuration field followed by the
 * defect. The JDK's own exception text carries the path, so no cause is ever chained.
 * <p>
 * Stateless and safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class KeyFiles {

    /**
     * Reads a configured key file once, bounded.
     *
     * @param keyFile     the already-substituted path value of the configuration field
     * @param configField the configuration field the path came from; it opens every refusal message
     * @param maxBytes    the largest file the caller accepts, in bytes
     * @return the file's bytes, at most {@code maxBytes} of them
     * @throws IllegalStateException when the value is not a usable path, names no file, names
     *                               something other than a regular file, names a file larger than
     *                               {@code maxBytes}, or names a file that cannot be read
     */
    public static byte[] readBounded(String keyFile, String configField, int maxBytes) {
        Objects.requireNonNull(keyFile, "keyFile must not be null");
        Objects.requireNonNull(configField, "configField must not be null");
        Path path;
        try {
            path = Path.of(keyFile);
        } catch (InvalidPathException _) {
            // The exception text echoes the configured value — name the field, never chain the cause.
            throw refusal(configField, "does not name a usable file path");
        }
        if (Files.notExists(path)) {
            throw refusal(configField, "names a file that does not exist");
        }
        if (!Files.isRegularFile(path)) {
            throw refusal(configField, "does not name a regular file");
        }
        try (InputStream content = Files.newInputStream(path)) {
            byte[] bytes = content.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw refusal(configField, "names a file larger than the %d byte limit".formatted(maxBytes));
            }
            return bytes;
        } catch (IOException _) {
            // The exception text carries the path — name the field, never chain the cause.
            throw refusal(configField, "names a file that cannot be read");
        }
    }

    private static IllegalStateException refusal(String configField, String defect) {
        return new IllegalStateException(configField + " " + defect);
    }
}
