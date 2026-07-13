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
package de.cuioss.sheriff.api.config.load;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code ${ENV_VAR}} secret references embedded in configuration string
 * values against a supplied environment lookup.
 * <p>
 * A reference names an environment variable using the pattern
 * {@code ${NAME}} where {@code NAME} starts with a letter or underscore and
 * continues with letters, digits, or underscores. Every reference in a value is
 * substituted; a reference whose variable is absent is a hard failure surfaced as a
 * {@link MissingVariableException} so the loader can record it as a
 * {@link ConfigError}.
 * <p>
 * The environment lookup is constructor-injected (defaulting to
 * {@link System#getenv(String)}), keeping the resolver framework-agnostic and
 * deterministically testable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EnvSecretResolver {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_]\\w*)}");

    private final UnaryOperator<@Nullable String> lookup;

    /**
     * Creates a resolver backed by the process environment
     * ({@link System#getenv(String)}).
     */
    public EnvSecretResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the supplied lookup.
     *
     * @param lookup maps an environment-variable name to its value, or {@code null}
     *               when the variable is undefined
     */
    public EnvSecretResolver(UnaryOperator<@Nullable String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Reports whether the value contains at least one {@code ${VAR}} reference.
     *
     * @param value the raw configuration value
     * @return {@code true} when a reference is present
     */
    public boolean hasReference(String value) {
        return REFERENCE.matcher(value).find();
    }

    /**
     * Substitutes every {@code ${VAR}} reference in the value with the resolved
     * environment value.
     *
     * @param value the raw configuration value
     * @return the value with all references substituted
     * @throws MissingVariableException when a referenced variable is undefined
     */
    public String resolve(String value) {
        Matcher matcher = REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String resolved = lookup.apply(name);
            if (resolved == null) {
                throw new MissingVariableException(name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Signals that a referenced environment variable is undefined.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class MissingVariableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String variableName;

        MissingVariableException(String variableName) {
            super("Unresolved environment variable: " + variableName);
            this.variableName = variableName;
        }

        /**
         * Returns the name of the undefined environment variable.
         *
         * @return the missing variable name
         */
        public String variableName() {
            return variableName;
        }
    }
}
