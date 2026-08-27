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

import org.jspecify.annotations.Nullable;

/**
 * Standing <strong>matched positive control</strong> for the stored-{@code Optional} guard in
 * {@code NoStoredOptionalArchTest}: the same shape as {@link OptionalFieldSpecimen} but carrying
 * the {@code @Nullable} field the conversion prescribes, so the guard must find <em>no</em>
 * violation here.
 * <p>
 * Pairing it with the negative control is what proves the guard discriminates: a rule that always
 * failed would satisfy the negative control alone, and this specimen is what rules that out.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class NullableFieldSpecimen {

    /** The prescribed shape: a nullable value rather than a stored {@link java.util.Optional}. */
    private final @Nullable String value;

    /**
     * Creates a specimen wrapping {@code value}.
     *
     * @param value the value to store, {@code null} for an absent one
     */
    public NullableFieldSpecimen(@Nullable String value) {
        this.value = value;
    }

    /**
     * Reads whether a value is present. Package-private and non-matching, mirroring
     * {@link OptionalFieldSpecimen#hasValue()} so the two specimens differ only in the field type
     * under test.
     *
     * @return {@code true} when a value is present
     */
    boolean hasValue() {
        return value != null;
    }
}
