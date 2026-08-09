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

import java.util.Locale;

/**
 * The authentication posture an {@link AuthConfig auth} block requires.
 * <p>
 * The value set is declared in {@code gateway.schema.json} and
 * {@code endpoint.schema.json}, so an unknown value is refused during schema
 * validation, before binding ever reaches this type. Modelling the posture as an
 * enum rather than a {@link String} is therefore a <em>type-safety</em> change and
 * carries no behavioural delta at the configuration boundary: it replaces the three
 * duplicated {@code REQUIRE_*} string-constant sets that had drifted across the
 * validator, the authentication stage and the edge route with one shared type, and
 * lets the posture dispatch be an exhaustive switch the compiler checks.
 * <p>
 * The constants are uppercase per Java convention; the case-insensitive YAML binding
 * ({@code MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS}) maps the lowercase
 * {@code none} / {@code bearer} / {@code session} configuration values onto them.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum Require {

    /** No authentication required: the surface is anonymous. */
    NONE,
    /** A validated bearer token is required; the gateway needs a configured issuer. */
    BEARER,
    /** An authenticated session is required; the gateway needs an OIDC block. */
    SESSION;

    /**
     * The configuration spelling of this posture — the lowercase form as it appears in
     * {@code gateway.yaml}.
     * <p>
     * Overridden so that operator-facing text renders the posture the way the operator
     * wrote it: validation errors and the route-posture log line interpolate this value
     * with {@code %s}, and reporting {@code BEARER} for a file that says {@code bearer}
     * would make the message harder to trace back to the offending line. Binding is
     * unaffected — Jackson reads enums by constant name (case-insensitively here) and
     * does not consult {@code toString()}.
     *
     * @return the lowercase configuration value for this posture
     */
    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
