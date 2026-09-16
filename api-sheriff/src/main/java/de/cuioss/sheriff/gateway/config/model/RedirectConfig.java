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

/**
 * The per-route {@code redirect} terminal-action block (ADR-0014 Amendment A1).
 * <p>
 * A redirect action answers the request at the gateway with {@code status} and a
 * {@code Location} header, without contacting any upstream. A route carries exactly
 * one terminal action: {@code upstream}, {@code asset} or {@code redirect}.
 * <p>
 * {@code location} is written verbatim — it is a raw gateway path (never
 * context-path-prefixed) and no request placeholders are expanded. When
 * {@code keepQuery} is set, the raw inbound query string is appended.
 * <p>
 * <strong>Open-redirect contract.</strong> The record itself carries no URI policy;
 * the configuration validator reviews {@code location} at boot. Without
 * {@code allowExternal} the location must be a single-slash gateway path free of
 * backslashes, percent-encoded slashes or backslashes, dot-segments, whitespace,
 * control characters, scheme and authority (and free of {@code #} when
 * {@code keepQuery} is set). With {@code allowExternal} it may additionally be an
 * absolute {@code http}/{@code https} URI with a non-empty host and no user-info;
 * opting in emits a boot warning. {@code keepQuery} and {@code allowExternal} are
 * refused <em>together</em>: the query is attacker-supplied, so carrying it onto a
 * foreign origin is a disclosure no boot review can vet. The value range of
 * {@code status} ({@code 301, 302, 303, 307, 308}) is owned by the endpoint schema.
 *
 * @param location      the {@code Location} value (mandatory)
 * @param status        the redirect status code; mandatory, since {@code 301} and
 *                      {@code 308} are cached by browsers and permanence must be an
 *                      explicit operator choice
 * @param keepQuery     whether the raw inbound query string is appended to
 *                      {@code location}; same-origin only, so it cannot be combined
 *                      with {@code allowExternal}
 * @param allowExternal whether {@code location} may target another origin
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record RedirectConfig(String location, int status, boolean keepQuery, boolean allowExternal) {

    /**
     * Canonical constructor requiring {@code location}.
     */
    public RedirectConfig {
        Objects.requireNonNull(location, "location");
    }
}
