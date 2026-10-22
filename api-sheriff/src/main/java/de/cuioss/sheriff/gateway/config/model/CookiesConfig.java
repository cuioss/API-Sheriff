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

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The {@code cookies} block: which browser cookies a route forwards to its upstream, which upstream
 * {@code Set-Cookie} lines it relays back, and how a cookie-forwarding route is guarded against
 * cross-site requests.
 * <p>
 * The block is declarable at the gateway, an anchor, an endpoint and a route; the nearest declared
 * block replaces every broader one wholesale ({@code PolicyBlock#COOKIES}). When no scope declares
 * one, {@link #NONE} applies: no cookie crosses in either direction. The gateway's own cookies never
 * cross under any block.
 * <p>
 * Usage:
 * {@snippet lang = "yaml" :
 * cookies:
 *   allow: [app_session, theme]
 *   same_origin_check: true
 *   additional_origins: [https://app.example.com]
 * }
 * <p>
 * Immutable and safe for concurrent use.
 *
 * @param allow             the cookie mode — {@code none}, {@code all} or a list of names (mandatory)
 * @param sameOriginCheck   whether an unsafe request carrying a forwarded cookie must come from the
 *                          gateway's own origin or an {@code additionalOrigins} entry, {@code null} when
 *                          omitted, which means {@code true}
 * @param additionalOrigins the further exact origins a cookie-carrying unsafe request may come from,
 *                          lower-cased once here; {@code null} when omitted, so a declared empty list
 *                          stays distinguishable from an omitted one
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record CookiesConfig(
CookieAllow allow,
@Nullable Boolean sameOriginCheck,
@Nullable List<String> additionalOrigins) {

    /** The block a route resolves when no scope declares one: {@code allow: none}. */
    public static final CookiesConfig NONE = new CookiesConfig(CookieAllow.NONE, null, null);

    /**
     * Canonical constructor requiring {@code allow} and lower-casing a declared
     * {@code additionalOrigins} list into an unmodifiable copy.
     */
    public CookiesConfig {
        Objects.requireNonNull(allow, "allow");
        additionalOrigins = additionalOrigins == null ? null
                : additionalOrigins.stream().map(origin -> origin.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Whether any cookie other than the gateway's own crosses under this block.
     *
     * @return {@code true} when {@code allow} is {@code all} or a list of names
     */
    public boolean forwardsAny() {
        return !(allow instanceof CookieAllow.None);
    }

    /**
     * The same-origin check with its default applied.
     *
     * @return the declared {@code same_origin_check}, {@code true} when it is omitted
     */
    public boolean effectiveSameOriginCheck() {
        return !Boolean.FALSE.equals(sameOriginCheck);
    }
}
