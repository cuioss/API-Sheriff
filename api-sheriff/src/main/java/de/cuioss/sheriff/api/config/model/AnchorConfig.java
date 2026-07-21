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
import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * A named, namespace-scoped policy anchor declared under {@code gateway.anchors}
 * (decision: ADR-0007).
 * <p>
 * Each anchor <em>owns</em> a URL path namespace ({@code pathPrefix}); anchor
 * prefixes are pairwise disjoint (enforced by the configuration validator). An
 * anchor optionally carries the policy floor — {@code auth},
 * {@code securityFilter}, {@code securityHeaders} and {@code allowedMethods} — that
 * every route inside its namespace inherits, subject to wholesale replacement at
 * the endpoint and route levels. The auth floor cannot be weakened to
 * {@code require: none}; replacing one non-{@code none} posture with another is
 * allowed. Anchors vanish at runtime: the route-table builder materializes each
 * route's effective values and the request pipeline never consults an anchor.
 * <p>
 * Every anchor additionally declares two required classification axes (decision:
 * ADR-0013): {@link AnchorType type} ({@code proxy} / {@code bff} / {@code asset} —
 * the former {@code passthrough} value renamed to {@code proxy}) and
 * {@link AccessLevel access} ({@code public} / {@code authenticated}). Both are
 * mandatory on every anchor and drive the fail-closed access-to-auth boot matrix.
 * <p>
 * {@code name} is the anchor's map key from {@code gateway.yaml}, injected during
 * loading; it is not a property of the anchor block itself.
 *
 * @param name             the anchor name (its map key, mandatory)
 * @param pathPrefix       the owned path namespace (mandatory)
 * @param type             the anchor kind (mandatory) — {@code proxy} /
 *                         {@code bff} / {@code asset}
 * @param access           the anchor audience (mandatory) — {@code public} /
 *                         {@code authenticated}
 * @param auth             the anchor-level auth floor, empty when the anchor
 *                         declares none
 * @param securityFilter   the anchor-level security filter, empty when the anchor
 *                         declares none
 * @param securityHeaders  the anchor-level response-header posture, empty when the
 *                         anchor declares none
 * @param allowedMethods   the anchor-level verb allowlist, empty when the anchor
 *                         declares none
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record AnchorConfig(String name, String pathPrefix, AnchorType type, AccessLevel access,
Optional<AuthConfig> auth,
Optional<SecurityFilterConfig> securityFilter, Optional<SecurityHeadersConfig> securityHeaders,
List<HttpMethod> allowedMethods) {

    /**
     * Canonical constructor requiring {@code name}, {@code pathPrefix},
     * {@code type} and {@code access}, defensively copying {@code allowedMethods},
     * and normalizing absent optional blocks to {@link Optional#empty()}.
     */
    public AnchorConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(access, "access");
        auth = Objects.requireNonNullElse(auth, Optional.empty());
        securityFilter = Objects.requireNonNullElse(securityFilter, Optional.empty());
        securityHeaders = Objects.requireNonNullElse(securityHeaders, Optional.empty());
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
    }
}
