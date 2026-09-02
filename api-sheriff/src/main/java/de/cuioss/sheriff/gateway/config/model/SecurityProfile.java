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

import java.util.Locale;
import java.util.Optional;


import de.cuioss.http.security.config.SecurityConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * The canonical inbound-filter mode set — the value space of the {@code profile} knob on both
 * {@code security_defaults} and every {@code security_filter} block. The value range itself is
 * gated by the bundled JSON Schema (three symmetric enum sites), so this enum never sees an
 * unrecognized value at boot; {@link #parse(String)} exists for the model layer and for tests.
 * <p>
 * <strong>Two contracts this enum makes explicit.</strong>
 * <ol>
 *   <li><strong>An omitted block resolves to {@link #STRICT}</strong> ({@link #DEFAULT_PROFILE}).
 *       When {@code security_defaults} is absent, or present without a {@code profile}, the
 *       gateway-wide effective profile is {@code STRICT} — the fail-closed choice.</li>
 *   <li><strong>{@link #MINIMAL} contributes no limits policy.</strong> {@code minimal} is a
 *       statement about <em>validation</em>, never about <em>limits</em>: a {@code minimal} route
 *       still needs a concrete {@link SecurityConfiguration} so the retained {@code max_body_bytes}
 *       guard has a {@code maxBodySize} to enforce. {@link #preset()} therefore refuses to answer
 *       for {@code MINIMAL}; callers resolve the limits policy through
 *       {@link #limitsProfile(SecurityProfile, SecurityProfile)}, which walks to the nearest
 *       non-{@code minimal} profile in the fallback chain and lands on {@code STRICT} when the whole
 *       chain is {@code minimal}.</li>
 * </ol>
 * <p>
 * {@code minimal} is a <strong>partial</strong> disable, not "the inbound filter is off": it turns
 * off exactly the url-parameter name/value validation and the per-route pipeline re-run. The
 * pre-route floor, {@code max_body_bytes} and {@code allowed_paths} all keep running under it. See
 * ADR-0024.
 * <p>
 * Immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum SecurityProfile {

    /** The tightest inbound posture, backed by {@link SecurityConfiguration#strict()}. */
    STRICT,

    /** The relaxed inbound posture, backed by {@link SecurityConfiguration#lenient()}. */
    LENIENT,

    /**
     * The partial disable: url-parameter name/value validation and the per-route pipeline re-run
     * are skipped, while the pre-route floor, the body cap and the path allowlist keep running.
     * Refused at boot on effectively-authenticated and BFF routes.
     */
    MINIMAL;

    /**
     * The profile an omitted {@code security_defaults} block — or a {@code security_defaults} block
     * without a {@code profile} — resolves to. Fail-closed by construction.
     */
    public static final SecurityProfile DEFAULT_PROFILE = STRICT;

    /**
     * Parses a configured {@code profile} scalar case-insensitively.
     *
     * @param value the raw configured value, may be {@code null}
     * @return the matching profile, or {@link Optional#empty()} when the value is {@code null} or
     *         names no mode of this set (the dropped {@code default} preset included)
     */
    public static Optional<SecurityProfile> parse(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (SecurityProfile profile : values()) {
            if (profile.name().equals(normalized)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the profile whose preset supplies the <em>limits</em> policy for a route, by walking
     * to the nearest non-{@link #MINIMAL} entry of the {@code effective → fallback} chain and landing
     * on {@link #STRICT} when every level is {@code MINIMAL}. This is what keeps a {@code minimal}
     * route's body cap and collection limits concrete and enforceable.
     *
     * @param effective the route's (or the gateway's) effective profile
     * @param fallback  the next profile down the chain — the gateway-wide profile for a route
     * @return the profile to take the limits policy from; never {@link #MINIMAL}
     */
    public static SecurityProfile limitsProfile(SecurityProfile effective, SecurityProfile fallback) {
        if (effective != MINIMAL) {
            return effective;
        }
        return fallback != MINIMAL ? fallback : STRICT;
    }

    /**
     * @return the backing cui-http preset for this mode
     * @throws IllegalStateException when called on {@link #MINIMAL}, which contributes no limits
     *                               policy — resolve one through
     *                               {@link #limitsProfile(SecurityProfile, SecurityProfile)} first
     */
    public SecurityConfiguration preset() {
        return switch (this) {
            case STRICT -> SecurityConfiguration.strict();
            case LENIENT -> SecurityConfiguration.lenient();
            case MINIMAL -> throw new IllegalStateException(
                    "profile 'minimal' has no cui-http preset; resolve the limits profile via limitsProfile(..)");
        };
    }

    /**
     * @return {@code true} when the skippable half of the per-route checks — the url-parameter
     *         name/value validation and the pipeline re-run — is active; {@code false} only for
     *         {@link #MINIMAL}
     */
    public boolean skippableValidationEnabled() {
        return this != MINIMAL;
    }
}
