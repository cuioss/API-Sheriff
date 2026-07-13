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

import java.util.Objects;

/**
 * A topology alias decomposed once, at boot, into its upstream URL components
 * (ADR-0004).
 * <p>
 * Decomposing the configured URL a single time — rather than re-parsing it on every
 * request — is the decompose-on-read contract: the hot path consumes the already
 * split scheme, host, port, and base path.
 *
 * @param scheme   the URL scheme (e.g. {@code https})
 * @param host     the upstream host
 * @param port     the upstream port; the scheme default when the URL omits it
 * @param basePath the base path prefix, empty when the URL carries none
 * @author API Sheriff Team
 * @since 1.0
 */
public record ResolvedUpstream(String scheme, String host, int port, String basePath) {

    /**
     * Canonical constructor requiring {@code scheme} and {@code host} and normalizing
     * an absent {@code basePath} to the empty string.
     */
    public ResolvedUpstream {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        basePath = basePath == null ? "" : basePath;
    }
}
