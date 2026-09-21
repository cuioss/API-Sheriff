/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
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


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The {@code auth} block, declarable on an anchor (policy floor), at endpoint level (default
 * posture) and per route (override).
 * <p>
 * {@code require} is a {@link Require} posture ({@code none} / {@code bearer} /
 * {@code session}); the value set is declared in the JSON schemas and refused there
 * before binding. {@code token_relay} decides whether a {@code require: session} route
 * relays the session's access token upstream as {@code Authorization: Bearer}; it acts on
 * {@code session} routes only and resolves to {@code true} when absent (see
 * {@link #effectiveTokenRelay()}). The block is replaced <em>wholesale</em> through the
 * route &rarr; endpoint &rarr; anchor cascade, so a route-level block that omits
 * {@code token_relay} resolves it to the default rather than inheriting a lower-level value.
 * <p>
 * The block carries no scope list: the scopes a route needs are declared additively by the
 * owning endpoint's {@code scopes} key, outside {@code auth}, and materialized once per route
 * as {@link ResolvedRoute#neededScopes()}.
 * <p>
 * <strong>Thread safety.</strong> This immutable record is thread-safe and may be shared
 * freely across request threads: {@link Require} is an enum and {@link Boolean} is immutable.
 *
 * @param require    the authentication requirement (mandatory)
 * @param tokenRelay whether a {@code require: session} route relays the session's access
 *                   token upstream, {@code null} when omitted (resolves to {@code true})
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record AuthConfig(Require require, @Nullable Boolean tokenRelay) {

    /**
     * Canonical constructor requiring {@code require}.
     */
    public AuthConfig {
        Objects.requireNonNull(require, "require");
    }

    /**
     * Resolves the declared {@code token_relay} value: an omitted key relays the token.
     *
     * @return {@code false} only when {@code token_relay: false} is declared, {@code true}
     *         otherwise
     */
    public boolean effectiveTokenRelay() {
        return !Boolean.FALSE.equals(tokenRelay);
    }
}
