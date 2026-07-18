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
package de.cuioss.sheriff.api.routing;

import java.util.EnumSet;
import java.util.Set;

import de.cuioss.sheriff.api.config.model.HttpMethod;

/**
 * The standard-verb HTTP processor. Serves every proxyable verb in {@link HttpMethod} and
 * is reused for GraphQL-over-HTTP routes (the {@link ProtocolProcessorRegistry} maps both
 * {@code HTTP} and {@code GRAPHQL} to a single shared instance).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class HttpProtocolProcessor implements ProtocolProcessor {

    private static final Set<HttpMethod> STANDARD_METHODS = Set.copyOf(EnumSet.allOf(HttpMethod.class));

    @Override
    public String id() {
        return "http";
    }

    @Override
    public Set<HttpMethod> standardMethods() {
        return STANDARD_METHODS;
    }

    @Override
    public boolean supports(HttpMethod method) {
        return STANDARD_METHODS.contains(method);
    }
}
