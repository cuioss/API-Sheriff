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

import java.util.Map;
import java.util.Optional;

/**
 * The immutable, fully-resolved topology: every topology alias referenced by an
 * enabled endpoint mapped to its decomposed {@link ResolvedUpstream}.
 * <p>
 * Aliases referenced only by disabled endpoints are absent, since disabled
 * endpoints are exempt from alias resolution.
 *
 * @param aliases the alias-to-upstream mapping, empty when nothing is resolved
 * @author API Sheriff Team
 * @since 1.0
 */
public record ResolvedTopology(Map<String, ResolvedUpstream> aliases) {

    /**
     * Canonical constructor defensively copying {@code aliases} into an unmodifiable
     * map and normalizing an absent map to empty.
     */
    public ResolvedTopology {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    }

    /**
     * Looks up the resolved upstream for a topology alias.
     *
     * @param alias the topology alias
     * @return the resolved upstream, or empty when the alias is not resolved
     */
    public Optional<ResolvedUpstream> lookup(String alias) {
        return Optional.ofNullable(aliases.get(alias));
    }
}
