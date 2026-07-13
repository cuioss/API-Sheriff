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
package de.cuioss.sheriff.api.config.model;

import java.util.List;
import java.util.Map;

import lombok.Builder;

/**
 * The per-route {@code forward} block: a deny-by-default allowlist of what
 * crosses to the upstream.
 * <p>
 * {@code headers_allow} / {@code query_allow} are allowlists — anything not
 * listed is not forwarded. {@code set_headers} adds static, gateway-controlled
 * headers. The mediated {@code Authorization} and the regenerated
 * forwarding-header set are injected automatically and are never configured here.
 *
 * @param headersAllow the forwardable request-header names, empty when none
 * @param queryAllow   the forwardable query-parameter names, empty when none
 * @param setHeaders   static gateway-set headers, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record ForwardConfig(List<String> headersAllow, List<String> queryAllow, Map<String, String> setHeaders) {

    /**
     * Canonical constructor defensively copying the collections into unmodifiable
     * copies and normalizing absent collections to empty.
     */
    public ForwardConfig {
        headersAllow = headersAllow == null ? List.of() : List.copyOf(headersAllow);
        queryAllow = queryAllow == null ? List.of() : List.copyOf(queryAllow);
        setHeaders = setHeaders == null ? Map.of() : Map.copyOf(setHeaders);
    }
}
