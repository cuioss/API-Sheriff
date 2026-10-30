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
package de.cuioss.sheriff.gateway.testsupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import lombok.experimental.UtilityClass;

/**
 * The assertions the boot-validation tests make about the {@link ConfigError}s a validator reports.
 * <p>
 * Two match strengths are offered on purpose. {@link #assertHasError} matches a pointer <em>fragment</em>
 * and tolerates other errors, for a fixture that may report unrelated gaps; the others pin the exact
 * pointer, and {@link #singleRefusalAt} and {@link #assertSingleRefusal} also pin that the refusal is the
 * only one:
 * {@snippet :
 * List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());
 *
 * assertHasError(errors, "/anchors", "pairwise disjoint");
 * String message = singleRefusalAt(errors, "/oidc/login/path").message();
 * }
 * <p>
 * Thread-safe: the class is stateless. Import its members with a star static import, because a named
 * static import of an {@code @UtilityClass} member does not resolve under javac.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ValidationAsserts {

    /**
     * Asserts that at least one error matches both fragments.
     *
     * @param errors          the reported errors
     * @param pointerContains a fragment of the expected JSON pointer
     * @param messageContains a fragment of the expected message
     */
    public void assertHasError(List<ConfigError> errors, String pointerContains, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(e -> e.pointer().contains(pointerContains) && e.message().contains(messageContains)),
                () -> "expected an error whose pointer contains '" + pointerContains + "' and message contains '"
                        + messageContains + "', but got: " + errors);
    }

    /**
     * Asserts that at least one error sits at exactly this pointer and carries the message fragment.
     *
     * @param errors          the reported errors
     * @param pointer         the exact JSON pointer
     * @param messageContains a fragment of the expected message
     */
    public void assertRefused(List<ConfigError> errors, String pointer, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(error -> pointer.equals(error.pointer()) && error.message().contains(messageContains)),
                () -> "expected an error at " + pointer + " containing '" + messageContains + "', but got: " + errors);
    }

    /**
     * Asserts that the whole result is one refusal at exactly this file and pointer, carrying the message
     * fragment.
     *
     * @param errors          the reported errors
     * @param file            the exact file
     * @param pointer         the exact JSON pointer
     * @param messageContains a fragment of the expected message
     */
    public void assertSingleRefusal(List<ConfigError> errors, String file, String pointer, String messageContains) {
        assertEquals(1, errors.size(), errors::toString);
        ConfigError error = errors.getFirst();
        assertAll(
                () -> assertEquals(file, error.file()),
                () -> assertEquals(pointer, error.pointer()),
                () -> assertTrue(error.message().contains(messageContains),
                        () -> "expected the refusal to contain '" + messageContains + "': " + error.message()));
    }

    /**
     * Returns the one refusal at this pointer, in whichever file it was reported.
     *
     * @param errors  the reported errors
     * @param pointer the exact JSON pointer
     * @return the refusal; the assertion fails unless there is exactly one at the pointer
     */
    public ConfigError singleRefusalAt(List<ConfigError> errors, String pointer) {
        List<ConfigError> refusals = errors.stream().filter(error -> pointer.equals(error.pointer())).toList();
        assertEquals(1, refusals.size(), () -> "expected exactly one refusal at " + pointer + ", got: " + errors);
        return refusals.getFirst();
    }

    /**
     * Returns the one refusal at this pointer of this file, for a rule that reports the same pointer in
     * several documents.
     *
     * @param errors  the reported errors
     * @param file    the exact file
     * @param pointer the exact JSON pointer
     * @return the refusal; the assertion fails unless there is exactly one at the file and pointer
     */
    public ConfigError singleRefusalAt(List<ConfigError> errors, String file, String pointer) {
        List<ConfigError> refusals = errors.stream()
                .filter(error -> file.equals(error.file()) && pointer.equals(error.pointer())).toList();
        assertEquals(1, refusals.size(),
                () -> "expected exactly one refusal at " + file + pointer + ", got: " + errors);
        return refusals.getFirst();
    }
}
