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

import java.io.Serial;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
 * scalar are supported, and a scalar is always walked to its end: every unset bare
 * {@code ${NAME}} it carries is named in the one {@link MissingVariableException} it
 * raises, so an operator sees every missing variable of the scalar at once rather than
 * one per attempt. There is <strong>no escape syntax</strong>: a scalar that
 * contains a {@code ${} sequence which is not a well-formed placeholder fails the
 * boot with a {@link MalformedPlaceholderException} — a loud failure is always
 * preferred over silently leaving an un-substituted literal in a resolved value.
 * <p>
 * {@link #isBareReference(String)} lets the secrets rule classify a field on its
 * <em>pre-substitution</em> value: a secret must be written as a bare
 * {@code ${VAR}} reference, never a literal or a defaulted placeholder.
 * <p>
 * {@link #resolve(String, Consumer)} reports each variable whose in-file default was
 * applied — by <em>name only</em>, never the default and never any value — so a caller
 * can tell an operator which settings silently fell back.
 * <p>
 * <strong>Why this engine is kept (ADR-0062).</strong> The platform's own expression
 * resolution, SmallRye Config, does not replace it. The reasons are:
 * <ul>
 * <li>its own {@code :-} default syntax, with no escape form;</li>
 * <li>refusal of a malformed placeholder instead of leaving it in the value;</li>
 * <li>every missing name of a value reported at once, not only the first;</li>
 * <li>a call-back on each defaulted name, by name only;</li>
 * <li>the environment as the only source, reached only through an injected lookup that
 * defaults to {@link System#getenv(String)} — which also makes the engine
 * deterministically testable;</li>
 * <li>it runs on the pre-boot {@code --validate-config} path (ADR-0061), before SmallRye
 * Config exists.</li>
 * </ul>
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
     * Substitutes every placeholder in the value with the resolved environment value or its
     * literal default, and reports each variable whose in-file default was applied.
     * <p>
     * Each time a {@code ${NAME:-default}} placeholder falls back to its default because
     * {@code NAME} is unset, {@code NAME} is handed to {@code onDefaulted} — once per
     * variable name per value, in first-occurrence order. The consumer never receives the
     * default literal or any resolved value. A default is reported as it is applied, so it
     * is reported even when the same value then fails on an unset bare variable.
     * <p>
     * Usage:
     * {@snippet :
     * List<String> defaulted = new ArrayList<>();
     * String resolved = resolver.resolve("${HOST:-localhost}:${PORT:-8080}", defaulted::add);
     * // defaulted holds HOST and PORT when neither variable is set
     * }
     *
     * @param value       the raw configuration value
     * @param onDefaulted receives the name of each variable whose in-file default applied
     * @return the value with all placeholders substituted
     * @throws MissingVariableException     when one or more bare {@code ${NAME}}
     *                                      placeholders name an undefined variable; it
     *                                      names every such variable of the value
     * @throws MalformedPlaceholderException when the value contains a {@code ${} that
     *                                      is not a well-formed placeholder
     * @since 1.0
     */
    public String resolve(String value, Consumer<String> onDefaulted) {
        Objects.requireNonNull(onDefaulted, "onDefaulted");
        assertNoMalformedPlaceholder(value);
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> defaulted = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            String resolved = lookup.apply(name);
            String replacement;
            if (resolved != null) {
                replacement = resolved;
            } else if (defaultValue != null) {
                replacement = defaultValue;
                if (defaulted.add(name)) {
                    onDefaulted.accept(name);
                }
            } else {
                missing.add(name);
                replacement = "";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        if (!missing.isEmpty()) {
            throw new MissingVariableException(List.copyOf(missing));
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
     * Signals that one or more bare {@code ${NAME}} placeholders of a single value named
     * an undefined environment variable.
     * <p>
     * The message lists every missing name comma-separated after a fixed prefix
     * ({@code Unresolved environment variable: A, B}); it never echoes a resolved value or
     * a default.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class MissingVariableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final List<String> variableNames;

        MissingVariableException(List<String> variableNames) {
            super("Unresolved environment variable: " + String.join(", ", variableNames));
            this.variableNames = List.copyOf(variableNames);
        }

        /**
         * Returns the names of every undefined environment variable of the value, in
         * first-occurrence order and without duplicates.
         *
         * @return the non-empty, unmodifiable list of missing variable names
         */
        public List<String> variableNames() {
            return variableNames;
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

        @Serial
        private static final long serialVersionUID = 1L;

        MalformedPlaceholderException() {
            super("malformed placeholder: a '${' is not a well-formed ${NAME} or ${NAME:-default} reference");
        }
    }
}
