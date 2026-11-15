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
package de.cuioss.sheriff.gateway.portal;

import java.util.Optional;


import org.jspecify.annotations.Nullable;

/**
 * The closed vocabulary of notices the portal page can display, selected by the {@code notice}
 * query parameter.
 * <p>
 * The parameter is attacker-controlled, so it is never echoed: {@link #parse} maps it onto one of the
 * fixed constants by exact, case-sensitive comparison with the constant's {@linkplain #wireValue() wire
 * value}, and drops every other value — an unknown word, an empty value, a different casing, a
 * percent-encoded spelling or HTML-bearing text alike. Only the constant's own wire value ever reaches
 * the rendered page.
 * <p>
 * Thread-safe (enum).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum PortalNotice {

    /** The browser has just signed out — the landing of {@code oidc.logout.final_redirect}. */
    LOGGED_OUT("logged-out");

    private final String wireValue;

    PortalNotice(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * @return the exact query-parameter value selecting this notice, which is also the value handed to
     * the template
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Maps a raw {@code notice} query-parameter value onto the fixed vocabulary.
     *
     * @param raw the raw parameter value as received, {@code null} when the parameter is absent
     * @return the matching notice, or empty when {@code raw} is absent or not exactly one of the
     * vocabulary's wire values
     */
    public static Optional<PortalNotice> parse(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (PortalNotice notice : values()) {
            if (notice.wireValue.equals(raw)) {
                return Optional.of(notice);
            }
        }
        return Optional.empty();
    }
}
