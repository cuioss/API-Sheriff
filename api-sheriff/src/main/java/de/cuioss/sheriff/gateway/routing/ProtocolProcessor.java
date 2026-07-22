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
package de.cuioss.sheriff.gateway.routing;

import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;

/**
 * Strategy carrying the verb semantics for a route's application protocol. The
 * {@link ProtocolProcessorRegistry} selects one processor per route at boot; unsupported
 * protocols are rejected at boot rather than represented here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface ProtocolProcessor {

    /**
     * @return the stable identifier of this processor (e.g. {@code "http"})
     */
    String id();

    /**
     * @return the standard verb set this processor serves
     */
    Set<HttpMethod> standardMethods();

    /**
     * @param method the request method
     * @return {@code true} when this processor serves {@code method}
     */
    boolean supports(HttpMethod method);
}
