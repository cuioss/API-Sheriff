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

import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.discovery._ProviderMetadata_DslJsonConverter;
import de.cuioss.sheriff.token.client.flow.ParResponse;
import de.cuioss.sheriff.token.client.flow._ParResponse_DslJsonConverter;
import de.cuioss.sheriff.token.client.token.TokenResponse;
import de.cuioss.sheriff.token.client.token.UserInfoResponse;
import de.cuioss.sheriff.token.client.token._TokenResponse_DslJsonConverter;
import de.cuioss.sheriff.token.client.token._UserInfoResponse_DslJsonConverter;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the {@code token-sheriff-client} confidential-client engine response
 * DTOs and their generated DSL-JSON converters for reflection so the engine can
 * deserialize IdP responses in a GraalVM native image.
 * <p>
 * The engine parses every back-channel IdP response (OIDC discovery, the token
 * endpoint, PAR, and userinfo) with a DSL-JSON instance built by
 * {@code de.cuioss.sheriff.token.commons.transport.ParserConfig}. For each response
 * type the DSL-JSON annotation processor emits a compile-time
 * {@code _<Type>_DslJsonConverter} that implements
 * {@code com.dslplatform.json.Configuration}. DSL-JSON discovers these converters at
 * runtime through {@code com.dslplatform.json.ExternalConverterAnalyzer}, which
 * derives the converter class name by the {@code <pkg>._<Name>_DslJsonConverter}
 * naming convention, loads it via {@link ClassLoader#loadClass(String)}, instantiates
 * it with the no-arg constructor, and calls {@code configure(dslJson)} to register the
 * reader/writer bindings.
 * <p>
 * That reflective {@code loadClass} + {@code newInstance} step works on the JVM but
 * fails in a native image unless the converter classes are registered for reflection —
 * the engine then throws {@code Unable to find reader for provided type ... and
 * fallback serialization is not registered}. Registering the converter classes here is
 * the load-bearing fix: it makes DSL-JSON's reflective lookup resolve in native. The
 * DTO records are registered alongside as defence in depth; the converters instantiate
 * their nested {@code $ObjectFormatConverter} readers via a direct {@code new} and are
 * therefore reached by the native-image static analysis without an explicit entry.
 * <p>
 * The class has no runtime behavior; it exists solely to carry the
 * {@link RegisterForReflection} targets. Referencing the generated converter classes by
 * symbol (rather than by string) makes any future rename in the client engine fail the
 * build loudly instead of silently regressing native deserialization.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@RegisterForReflection(targets = {
        _ProviderMetadata_DslJsonConverter.class,
        _ParResponse_DslJsonConverter.class,
        _TokenResponse_DslJsonConverter.class,
        _UserInfoResponse_DslJsonConverter.class,
        ProviderMetadata.class,
        ParResponse.class,
        TokenResponse.class,
        UserInfoResponse.class
})
public final class TokenClientDslJsonReflection {

    private TokenClientDslJsonReflection() {
        // Reflection-registration holder; not instantiable.
    }
}
