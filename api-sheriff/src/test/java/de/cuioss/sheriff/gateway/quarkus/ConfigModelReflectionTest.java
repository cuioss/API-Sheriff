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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;


import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural guard over {@link ConfigModelReflection}'s consolidated
 * {@link RegisterForReflection} target list.
 * <p>
 * <strong>Why this test exists.</strong> The registration gap it guards is invisible to every
 * JVM gate. {@code ConfigLoader.bind} uses {@code ObjectMapper.treeToValue}, and on the JVM
 * Jackson introspects a record's canonical constructor reflectively without any registration —
 * so a model record missing from the target list binds perfectly under {@code verify}, under
 * {@code verify -Ppre-commit}, and in CI's JVM build. In the native image the same record's
 * constructor and components are absent from the reachability metadata, {@code treeToValue}
 * throws, and the boot validator turns the binding error into a fail-closed refusal to start.
 * The only gate that can observe the defect is the containerised integration suite, which runs
 * the native binary — the slowest, most expensive signal in the pipeline, and one that reports
 * only the <em>first</em> unbindable block because the whole document binds in a single
 * {@code treeToValue} call.
 * <p>
 * This test moves that signal to the JVM: it walks the record-component graph reachable from the
 * two Jackson binding roots ({@link GatewayConfig} and {@link EndpointConfig}) and asserts that
 * every configuration-model type it reaches is named in the target list. A newly added config
 * record therefore fails here, in seconds, instead of in the native IT run.
 */
class ConfigModelReflectionTest {

    /** The package whose types Jackson binds and which therefore require reflection registration. */
    private static final String MODEL_PACKAGE = "de.cuioss.sheriff.gateway.config.model";

    /** The types Jackson binds directly; every other bound type is reachable from one of these. */
    private static final Set<Class<?>> BINDING_ROOTS = Set.of(GatewayConfig.class, EndpointConfig.class);

    @Test
    @DisplayName("Should register every configuration-model type reachable from the Jackson binding roots")
    void shouldRegisterEveryJacksonBoundModelType() {
        // Arrange
        Set<Class<?>> registered = registeredTargets();
        Set<Class<?>> reachable = reachableModelTypes();

        // Act
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : reachable) {
            if (!registered.contains(type)) {
                missing.add(type.getTypeName());
            }
        }

        // Assert
        assertTrue(missing.isEmpty(),
                """
                        %d Jackson-bound configuration-model type(s) are absent from \
                        ConfigModelReflection's @RegisterForReflection targets: %s. \
                        Binding these succeeds on the JVM but fails in the native image, where the \
                        boot validator refuses to start. Add each one to the targets array.\
                        """.formatted(missing.size(), missing));
    }

    @Test
    @DisplayName("Should reach a non-trivial slice of the model package from the binding roots")
    void shouldTraverseTheModelGraphRatherThanTheRootsAlone() {
        // Arrange / Act
        Set<Class<?>> reachable = reachableModelTypes();

        // Assert — a traversal that silently stopped at the roots would make the guard vacuous
        assertTrue(reachable.size() > BINDING_ROOTS.size(),
                "the traversal must descend into nested records, not stop at the binding roots");
        assertTrue(reachable.containsAll(BINDING_ROOTS), "the binding roots are themselves bound types");
    }

    /** Reads the consolidated target list off the holder class. */
    private static Set<Class<?>> registeredTargets() {
        RegisterForReflection annotation = ConfigModelReflection.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "ConfigModelReflection must carry @RegisterForReflection");
        return Set.of(annotation.targets());
    }

    /**
     * Walks the record-component graph from the binding roots, collecting every reachable type
     * declared in the configuration-model package (records and the bound enums alike).
     */
    private static Set<Class<?>> reachableModelTypes() {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(BINDING_ROOTS);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!isModelType(current) || !visited.add(current)) {
                continue;
            }
            if (!current.isRecord()) {
                continue;
            }
            for (RecordComponent component : current.getRecordComponents()) {
                collectRawTypes(component.getGenericType(), queue);
            }
        }
        return visited;
    }

    /**
     * Flattens a component's generic type into the raw classes Jackson must be able to construct:
     * the type itself plus every type argument of a parameterized type (the element type of a
     * {@code List<T>}, the value type of a {@code Map<String, V>}).
     */
    private static void collectRawTypes(Type type, Deque<Class<?>> sink) {
        switch (type) {
            case Class<?> raw -> sink.add(raw);
            case ParameterizedType parameterized -> {
                collectRawTypes(parameterized.getRawType(), sink);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    collectRawTypes(argument, sink);
                }
            }
            default -> {
                // Wildcards, type variables and generic array types do not occur in the
                // configuration model.
            }
        }
    }

    private static boolean isModelType(Class<?> type) {
        return MODEL_PACKAGE.equals(type.getPackageName());
    }
}
