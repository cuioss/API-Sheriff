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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The per-route {@code asset} terminal-action block (decision: ADR-0014).
 * <p>
 * An asset action serves static content through the gateway-owned
 * {@code AssetResponseEnvelope} instead of proxying to an upstream. A route
 * carries at most one terminal action: either {@code upstream} or {@code asset},
 * never both. The {@link Source source} discriminator selects where the bytes come
 * from:
 * <ul>
 *   <li>{@link Source#DIRECTORY} — a local directory / volume mount, served through
 *       path confinement ({@code directory} names the root; no classpath-embedded
 *       resources).</li>
 *   <li>{@link Source#UPSTREAM} — a secondary origin reached through the same
 *       SSRF-controlled data plane the proxy action uses ({@code upstream} names the
 *       topology alias).</li>
 * </ul>
 * The configuration values ({@code directory} / {@code upstream}) are lowercase; the
 * case-insensitive YAML binding maps them onto the {@link Source} constants.
 *
 * @param source    the source discriminator (mandatory)
 * @param directory the local directory root, present for {@link Source#DIRECTORY}
 * @param upstream  the topology alias of the secondary origin, present for
 *                  {@link Source#UPSTREAM}
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record AssetConfig(Source source, Optional<String> directory, Optional<String> upstream) {

    /**
     * The asset content source (decision: ADR-0014).
     */
    public enum Source {

        /** A local directory / volume mount. */
        DIRECTORY,
        /** A secondary origin reached through the SSRF-controlled data plane. */
        UPSTREAM
    }

    /**
     * Canonical constructor requiring {@code source} and normalizing absent
     * source-specific fields to {@link Optional#empty()}.
     */
    public AssetConfig {
        Objects.requireNonNull(source, "source");
        directory = Objects.requireNonNullElse(directory, Optional.empty());
        upstream = Objects.requireNonNullElse(upstream, Optional.empty());
    }
}
