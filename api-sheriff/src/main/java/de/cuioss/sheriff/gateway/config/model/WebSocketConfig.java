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
import java.util.Optional;

import lombok.Builder;

/**
 * The per-route {@code websocket} block carried by a {@code protocol: websocket}
 * route.
 * <p>
 * {@code allowedOrigins} is a fail-closed, deny-by-default allowlist of exact-match
 * {@code Origin} strings (scheme + host + port, no wildcards); an absent or empty
 * allowlist on a bearer WebSocket route rejects the upgrade at boot — there is no
 * "any origin" default. Host matching is case-insensitive; the effective allowlist
 * is lower-cased once, at route-table assembly. {@code idleTimeoutSeconds} bounds an
 * established relay ("idle" = no frame in either direction, ping/pong counting as
 * activity); it defaults to {@code 300} when absent, applied at resolution.
 *
 * @param allowedOrigins     the exact-match, case-insensitive-host origin allowlist,
 *                           empty when none is declared
 * @param idleTimeoutSeconds the per-route idle timeout in seconds, empty when the
 *                           default applies
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record WebSocketConfig(List<String> allowedOrigins, Optional<Integer> idleTimeoutSeconds) {

    /**
     * Canonical constructor defensively copying {@code allowedOrigins} into an
     * unmodifiable copy, normalizing an absent list to empty and an absent
     * {@code idleTimeoutSeconds} to {@link Optional#empty()}.
     */
    public WebSocketConfig {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        idleTimeoutSeconds = Objects.requireNonNullElse(idleTimeoutSeconds, Optional.empty());
    }
}
