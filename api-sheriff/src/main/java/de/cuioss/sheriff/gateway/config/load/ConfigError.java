/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * A single configuration error, carrying the source file, the location within that
 * file, and a human-readable message.
 * <p>
 * The {@link ConfigLoader} collects these across the whole configuration set and
 * raises them together as a {@link ConfigLoadException}, so an operator sees every
 * problem in one boot attempt rather than one per restart.
 *
 * @param file    the configuration file the error originates from (e.g.
 *                {@code gateway.yaml} or {@code endpoints/orders.yaml})
 * @param pointer the location within the file — a JSON-pointer / JSONPath-style
 *                instance location, empty for whole-file errors
 * @param message the human-readable description of the problem
 * @author API Sheriff Team
 * @since 1.0
 */
public record ConfigError(String file, String pointer, String message) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Canonical constructor requiring all components to be non-null.
     */
    public ConfigError {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
