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

import java.util.Optional;

/**
 * Standing <strong>negative control</strong> for the stored-{@code Optional} guard in
 * {@code NoStoredOptionalArchTest}: a class that deliberately stores an {@link Optional} in a
 * field, so the guard has a known violation to detect.
 * <p>
 * Its matched positive counterpart is {@link NullableFieldSpecimen}, which carries the same shape
 * with a {@code @Nullable} field. The pair is what proves the guard <em>discriminates</em> rather
 * than merely always-failing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class OptionalFieldSpecimen {

    /**
     * The deliberate violation.
     * <p>
     * <strong>This field must NOT carry Lombok {@code @Getter}, and this class must NOT declare a
     * matching public no-arg accessor ({@code value()} / {@code getValue()} / {@code isValue()}).</strong>
     * Either would make the guard's accessor-based exemption apply and silently disarm this control —
     * the guard would stop reporting a violation here and {@code assertThrows} would fail, which is
     * the loud failure this constraint exists to keep loud. {@link #hasValue()} is deliberately
     * package-private and deliberately not name-matching for exactly that reason.
     */
    private final Optional<String> value;

    /**
     * Creates a specimen wrapping {@code value}.
     * <p>
     * The parameter is a plain {@link String} rather than an {@code Optional} on purpose: the
     * declared-parameter half of the guard must not find an incidental violation here, so this
     * specimen controls the <em>field</em> rule and nothing else.
     *
     * @param value the value to store, {@code null} for an absent one
     */
    public OptionalFieldSpecimen(String value) {
        this.value = Optional.ofNullable(value);
    }

    /**
     * Reads whether a value is present. Package-private and non-matching by design — see the field
     * comment.
     *
     * @return {@code true} when a value is present
     */
    boolean hasValue() {
        return value.isPresent();
    }
}
