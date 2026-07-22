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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Raised when configuration loading fails, carrying the complete set of
 * {@link ConfigError}s discovered in a single loading pass.
 * <p>
 * The loader aggregates every schema violation, secret-resolution failure, and
 * binding error rather than failing on the first, so this exception describes all
 * problems at once. It is a fail-fast boot signal: the application refuses to start
 * when it is thrown.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public class ConfigLoadException extends Exception implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<ConfigError> errors;

    /**
     * Creates the exception from the collected errors.
     *
     * @param errors the non-empty, aggregated set of configuration errors; copied
     *               defensively into an unmodifiable list
     */
    public ConfigLoadException(List<ConfigError> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * Returns the aggregated configuration errors.
     *
     * @return the unmodifiable list of every error discovered during loading
     */
    public List<ConfigError> errors() {
        return errors;
    }

    private static String buildMessage(List<ConfigError> errors) {
        return "Configuration loading failed with %d error(s):%n%s".formatted(errors.size(),
                errors.stream()
                        .map(e -> "  - %s [%s]: %s".formatted(e.file(), e.pointer(), e.message()))
                        .collect(Collectors.joining(System.lineSeparator())));
    }
}
