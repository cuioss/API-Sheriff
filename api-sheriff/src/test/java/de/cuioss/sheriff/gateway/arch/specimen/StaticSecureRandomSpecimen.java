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
package de.cuioss.sheriff.gateway.arch.specimen;

import java.security.SecureRandom;

/**
 * Standing <strong>negative control</strong> for the runtime-init registration gate in
 * {@code NativeRuntimeInitRegistrationArchTest}: a class that deliberately holds a
 * {@code static final SecureRandom} while being absent from the
 * {@code --initialize-at-run-time=} list in {@code application.properties}, so the gate has a known
 * violation to detect.
 * <p>
 * Its matched positive counterparts are not specimens but real production classes —
 * {@code SealedSessionCookieCodec} (a per-instance {@code SecureRandom} field) and
 * {@code CookieKeyMaterial} (a local {@code new SecureRandom()} argument). Together they prove the
 * gate <em>discriminates</em> on the {@code static}/{@code final} modifiers and on field declaration,
 * rather than merely always-failing on any mention of {@link SecureRandom}.
 * <p>
 * <strong>This class must never be added to the registration list</strong>, and the field below must
 * keep both the {@code static} and {@code final} modifiers. Either change silently disarms the
 * control: the gate would stop reporting a violation here and the {@code assertThrows} guarding it
 * would fail, which is the loud failure this constraint exists to keep loud.
 * <p>
 * Living in {@code src/test} it is never compiled into the native image, so the deliberate violation
 * carries no build-time or security consequence of its own.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StaticSecureRandomSpecimen {

    /**
     * The deliberate violation: static, final, and unregistered.
     * <p>
     * Private with a package-private reader below rather than exposed directly — the gate reads
     * declared fields from bytecode and is indifferent to visibility, so the narrower shape costs the
     * control nothing while keeping the specimen a well-formed class.
     */
    private static final SecureRandom UNREGISTERED_RANDOM = new SecureRandom();

    private StaticSecureRandomSpecimen() {
        // Specimen: it exists to be read from bytecode, never to be instantiated.
    }

    /**
     * Draws a byte from the specimen's generator, so the field is genuinely used.
     *
     * @return an arbitrary byte
     */
    static byte nextByte() {
        byte[] drawn = new byte[1];
        UNREGISTERED_RANDOM.nextBytes(drawn);
        return drawn[0];
    }
}
