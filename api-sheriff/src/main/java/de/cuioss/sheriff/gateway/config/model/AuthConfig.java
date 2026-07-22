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

import java.util.List;
import java.util.Objects;

import lombok.Builder;

/**
 * The {@code auth} block, declarable at endpoint level (mandatory default
 * posture) and per route (wholesale override).
 * <p>
 * {@code require} is one of {@code none} / {@code bearer} / {@code session}; the
 * value set is enforced by the configuration validator. {@code required_scopes}
 * is valid at either level; because override is wholesale, a route-level block
 * that omits it drops endpoint-level scope enforcement for that route.
 *
 * @param require        the authentication requirement (mandatory)
 * @param requiredScopes the scopes enforced for this posture, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record AuthConfig(String require, List<String> requiredScopes) {

    /**
     * Canonical constructor defensively copying {@code requiredScopes} into an
     * unmodifiable list and normalizing an absent list to empty.
     */
    public AuthConfig {
        Objects.requireNonNull(require, "require");
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
    }
}
