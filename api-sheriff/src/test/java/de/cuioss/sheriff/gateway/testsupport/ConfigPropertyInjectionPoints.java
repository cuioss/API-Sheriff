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

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;


import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads a bean's {@code @Inject} constructor reflectively and locates the parameter that a
 * configuration key is injected into, by its {@code @ConfigProperty} name.
 * <p>
 * This is the reading {@code DeclaredKeyMaterialKeysDriftTest} applies to the two TLS audits, made
 * reusable for tests that must <em>drive</em> an injection point per key rather than restate the
 * constructor's signature by hand: a case generated from a key vocabulary finds the parameter the key
 * really reaches, and a key that reaches none is reported as absent instead of being skipped.
 * <p>
 * Stateless, and therefore safe for concurrent use.
 */
public final class ConfigPropertyInjectionPoints {

    private ConfigPropertyInjectionPoints() {
    }

    /**
     * The single {@code @Inject}-annotated constructor of a bean.
     *
     * @param type the bean class
     * @return its injection constructor
     * @throws IllegalArgumentException when the class declares no, or more than one, {@code @Inject}
     *                                  constructor
     */
    public static Constructor<?> injectionConstructor(Class<?> type) {
        List<Constructor<?>> injecting = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class)).toList();
        if (injecting.size() != 1) {
            throw new IllegalArgumentException(type.getName() + " declares " + injecting.size()
                    + " @Inject constructors; exactly one is expected");
        }
        return injecting.getFirst();
    }

    /**
     * The position of the constructor parameter whose {@code @ConfigProperty} name equals the key.
     *
     * @param constructor the injection constructor
     * @param key         the configuration key
     * @return the parameter index, or empty when no parameter is injected with that key
     */
    public static OptionalInt parameterIndexOf(Constructor<?> constructor, String key) {
        Parameter[] parameters = constructor.getParameters();
        return IntStream.range(0, parameters.length)
                .filter(index -> key.equals(configPropertyName(parameters[index])))
                .findFirst();
    }

    /**
     * A value that <em>declares</em> the key for an injection point of the shape the TLS audits use:
     * a non-empty list for an {@code Optional<List<String>>} parameter and a non-blank string for an
     * {@code Optional<String>} parameter.
     *
     * @param parameter the injection point
     * @return the declaring value
     * @throws IllegalArgumentException for any other parameter shape, so an unsupported shape fails
     *                                  the calling test instead of being driven with a guess
     */
    public static Optional<?> declaringValueFor(Parameter parameter) {
        Type type = parameter.getParameterizedType();
        if (type instanceof ParameterizedType optional && optional.getRawType() == Optional.class) {
            Type element = optional.getActualTypeArguments()[0];
            if (element == String.class) {
                return Optional.of("/etc/certs/declared-material");
            }
            if (element instanceof ParameterizedType list && list.getRawType() == List.class
                    && list.getActualTypeArguments()[0] == String.class) {
                return Optional.of(List.of("declared-material.pem"));
            }
        }
        throw new IllegalArgumentException("no declaring value for injection point '" + parameter
                + "' of type " + type);
    }

    private static String configPropertyName(Parameter parameter) {
        ConfigProperty configProperty = parameter.getAnnotation(ConfigProperty.class);
        return configProperty == null ? null : configProperty.name();
    }
}
