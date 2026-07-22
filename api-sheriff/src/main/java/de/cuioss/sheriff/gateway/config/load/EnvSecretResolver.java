/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The single in-file placeholder substitution engine (D4, ADR-0004 Amendment A1).
 * <p>
 * It resolves two placeholder forms embedded in configuration string values against
 * a supplied environment lookup:
 * <ul>
 * <li>{@code ${NAME}} — <em>required</em>: the variable must be set, or the boot
 * fails with a {@link MissingVariableException};</li>
 * <li>{@code ${NAME:-default}} — <em>optional</em>: the literal {@code default}
 * (everything between the first {@code :-} and the closing {@code }}) applies when
 * the variable is unset.</li>
 * </ul>
 * {@code NAME} matches {@code [A-Za-z_][A-Za-z0-9_]*}. Multiple placeholders per
 * scalar are supported. There is <strong>no escape syntax</strong>: a scalar that
 * contains a {@code ${} sequence which is not a well-formed placeholder fails the
 * boot with a {@link MalformedPlaceholderException} — a loud failure is always
 * preferred over silently leaving an un-substituted literal in a resolved value.
 * <p>
 * {@link #isBareReference(String)} lets the secrets rule classify a field on its
 * <em>pre-substitution</em> value: a secret must be written as a bare
 * {@code ${VAR}} reference, never a literal or a defaulted placeholder.
 * <p>
 * The environment lookup is constructor-injected (defaulting to
 * {@link System#getenv(String)}), keeping the engine framework-agnostic and
 * deterministically testable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EnvSecretResolver {

    private static final Pattern PLACEHOLDER = Pattern
            .compile("\\$\\{([A-Za-z_]\\w*)(?::-((?:(?!\\$\\{).)*?))?}");
    private static final Pattern BARE_REFERENCE = Pattern.compile("\\$\\{[A-Za-z_]\\w*}");
    private static final String OPEN = "${";

    private final UnaryOperator<@Nullable String> lookup;

    /**
     * Creates an engine backed by the process environment
     * ({@link System#getenv(String)}).
     */
    public EnvSecretResolver() {
        this(System::getenv);
    }

    /**
     * Creates an engine backed by the supplied lookup.
     *
     * @param lookup maps an environment-variable name to its value, or {@code null}
     *               when the variable is undefined
     */
    public EnvSecretResolver(UnaryOperator<@Nullable String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Reports whether the value contains at least one {@code ${} placeholder opener.
     *
     * @param value the raw configuration value
     * @return {@code true} when a placeholder opener is present
     */
    public boolean hasReference(String value) {
        return value.contains(OPEN);
    }

    /**
     * Reports whether the value is exactly a single bare {@code ${VAR}} reference —
     * no default, no surrounding literal text.
     *
     * @param value the raw configuration value
     * @return {@code true} when the value is a bare {@code ${VAR}} reference
     */
    public boolean isBareReference(String value) {
        return BARE_REFERENCE.matcher(value).matches();
    }

    /**
     * Substitutes every placeholder in the value with the resolved environment value
     * or its literal default.
     *
     * @param value the raw configuration value
     * @return the value with all placeholders substituted
     * @throws MissingVariableException     when a bare {@code ${NAME}} names an
     *                                      undefined variable
     * @throws MalformedPlaceholderException when the value contains a {@code ${} that
     *                                      is not a well-formed placeholder
     */
    public String resolve(String value) {
        assertNoMalformedPlaceholder(value);
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            String resolved = lookup.apply(name);
            String replacement;
            if (resolved != null) {
                replacement = resolved;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                throw new MissingVariableException(name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void assertNoMalformedPlaceholder(String value) {
        String stripped = PLACEHOLDER.matcher(value).replaceAll("");
        if (stripped.contains(OPEN)) {
            throw new MalformedPlaceholderException();
        }
    }

    /**
     * Signals that a bare {@code ${NAME}} placeholder named an undefined environment
     * variable.
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

    /**
     * Signals that a scalar contained a {@code ${} sequence that is not a well-formed
     * {@code ${NAME}} or {@code ${NAME:-default}} placeholder. The offending value is
     * deliberately <em>not</em> echoed — it may carry sensitive text.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class MalformedPlaceholderException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedPlaceholderException() {
            super("malformed placeholder: a '${' is not a well-formed ${NAME} or ${NAME:-default} reference");
        }
    }
}
