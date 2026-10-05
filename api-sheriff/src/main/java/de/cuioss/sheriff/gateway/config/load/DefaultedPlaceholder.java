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
package de.cuioss.sheriff.gateway.config.load;

import java.util.Objects;

/**
 * Records that a {@code ${NAME:-default}} placeholder fell back to its in-file default
 * because {@code NAME} was unset.
 * <p>
 * It carries the location of the placeholder — the configuration file plus the JSON
 * pointer of the scalar, or {@code topology.properties} plus the alias — and the name of
 * the variable whose default applied.
 * <p>
 * <strong>Never a value.</strong> The record has no component for the default literal or
 * for any resolved value, by construction: a default may itself be sensitive (a fallback
 * host, a development credential), so no caller can print one through this type. It is
 * the same location vocabulary a {@link ConfigError} on that scalar carries, so a report
 * can place the fallback next to any violation of the same field.
 * <p>
 * Immutable and therefore thread-safe.
 *
 * @param file         the configuration file holding the placeholder (e.g.
 *                     {@code gateway.yaml}, {@code endpoints/orders.yaml} or
 *                     {@code topology.properties})
 * @param pointer      the JSON pointer of the scalar within {@code file}, or the topology
 *                     alias when {@code file} is {@code topology.properties}
 * @param variableName the name of the unset variable whose in-file default was applied
 * @author API Sheriff Team
 * @since 1.0
 */
public record DefaultedPlaceholder(String file, String pointer, String variableName) {

    /**
     * Canonical constructor requiring all components to be non-null.
     */
    public DefaultedPlaceholder {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(variableName, "variableName");
    }
}
