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
package de.cuioss.sheriff.api.gateway.proxy;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Interim proxy route configuration, bound to the {@code sheriff.proxy.*}
 * MicroProfile config namespace.
 * <p>
 * This is deliberately minimal: a single catch-all route defined by a matching
 * path prefix and a single upstream base URL. It is replaced by the real
 * configuration subsystem in Plan 02 and the real request pipeline in Plan 03.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ConfigMapping(prefix = "sheriff.proxy")
public interface ProxyConfiguration {

    /**
     * The path prefix that activates proxying. Requests whose path starts with
     * {@code <path-prefix>/} are forwarded to {@link #upstreamUrl()}; the prefix
     * is stripped and the remaining path is appended to the upstream URL.
     * Everything else is left to the default {@code 404} (deny-by-default).
     *
     * @return the activating path prefix, never {@code null}
     */
    @WithDefault("/proxy")
    String pathPrefix();

    /**
     * The upstream base URL that matched requests are forwarded to. The request
     * path remainder (after the prefix) and query string are appended to it.
     *
     * @return the upstream base URL, never {@code null}
     */
    @WithDefault("http://localhost:8080")
    String upstreamUrl();
}
