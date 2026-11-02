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
 * <strong>Three contracts this enum makes explicit.</strong>
 * <ol>
 *   <li><strong>An omitted block resolves to {@link #STRICT}</strong> ({@link #DEFAULT_PROFILE}).
 *       When {@code security_defaults} is absent, or present without a {@code profile}, the
 *       gateway-wide effective profile is {@code STRICT} — the fail-closed choice. Adding
 *       {@link #PARANOID} did not move that default: the tightest <em>available</em> mode and the
 *       <em>default</em> mode are deliberately different questions.</li>
 *   <li><strong>The line-break deviation is carried by every tightened mode.</strong> cui-http
 *       admits decoded CR/LF in url-parameter values in <em>every</em> preset, {@code paranoid()}
 *       included — it is {@code strict()} plus two content block-lists and differs in nothing else.
 *       {@link #STRICT} and {@link #PARANOID} therefore both switch
 *       {@code allowLineBreaksInParameterValues} off, so that {@code PARANOID} is tighter than
 *       {@code STRICT} on every axis and looser on none. A tightened mode that took the preset
 *       verbatim would be the silent inversion this contract exists to rule out.</li>
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

    /**
     * The tightest posture that judges <em>form</em> only, backed by
     * {@link SecurityConfiguration#strict()} with exactly one deviation: decoded line breaks (CR/LF)
     * in url-parameter values are refused — see {@link #preset()}.
     */
    STRICT,

    /**
     * {@link #STRICT} plus the application-layer <em>content</em> detection no lower mode performs:
     * backed by {@link SecurityConfiguration#paranoid()}, which seeds cui-http's sensitive-path and
     * suspicious-parameter-name block-lists. It carries {@code STRICT}'s line-break deviation too,
     * because {@code paranoid()} admits decoded CR/LF exactly as {@code strict()} does.
     * <p>
     * <strong>Has a real false-positive profile.</strong> The block-listed literals are filesystem
     * paths and configuration filenames, and parameter names such as {@code file}, {@code path} and
     * {@code url} — ordinary REST vocabulary. Select it for a surface whose paths and parameters
     * genuinely reach a filesystem; {@link #STRICT} stays the right choice elsewhere.
     */
    PARANOID,

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
     * The {@link #STRICT} policy: the cui-http {@link SecurityConfiguration#strict()} preset with
     * {@code allowLineBreaksInParameterValues} switched off. Built once through
     * {@link SecurityConfigurations#builderSeededFrom(SecurityConfiguration)}, so every other
     * component stays on the cui-http preset's value.
     */
    private static final SecurityConfiguration STRICT_PRESET = SecurityConfigurations
            .builderSeededFrom(SecurityConfiguration.strict())
            .allowLineBreaksInParameterValues(false)
            .build();

    /**
     * The {@link #PARANOID} policy: the cui-http {@link SecurityConfiguration#paranoid()} preset
     * carrying the same {@code allowLineBreaksInParameterValues} deviation {@link #STRICT_PRESET}
     * carries.
     * <p>
     * <strong>Why the deviation is repeated rather than inherited.</strong> {@code paranoid()} is
     * {@code strict()} plus two content block-lists — it does not derive from this project's
     * {@code STRICT_PRESET}, so the line-break switch is not inherited through it. cui-http
     * documents {@code allowLineBreaksInParameterValues} as defaulting to {@code true} in
     * <em>every</em> preset, and reading the shipped {@code PARANOID_CONFIGURATION} confirms it:
     * taking the preset verbatim would make {@code PARANOID} admit a decoded CR/LF that
     * {@code STRICT} refuses, i.e. looser than the mode it is sold as tightening.
     */
    private static final SecurityConfiguration PARANOID_PRESET = SecurityConfigurations
            .builderSeededFrom(SecurityConfiguration.paranoid())
            .allowLineBreaksInParameterValues(false)
            .build();

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
     * Returns the backing cui-http policy for this mode.
     * <p>
     * {@link #LENIENT} returns {@link SecurityConfiguration#lenient()} unchanged. {@link #STRICT} and
     * {@link #PARANOID} return {@link SecurityConfiguration#strict()} and
     * {@link SecurityConfiguration#paranoid()} respectively, each with <strong>the same single
     * deliberate deviation</strong>: {@code allowLineBreaksInParameterValues} is {@code false}, so a
     * url-parameter value that decodes to CR or LF is refused rather than forwarded upstream. Every
     * other component equals the cui-http preset. Both tightened policies are cached constants, so
     * repeated calls return the same instance.
     *
     * @return the backing cui-http policy for this mode
     * @throws IllegalStateException when called on {@link #MINIMAL}, which contributes no limits
     *                               policy — resolve one through
     *                               {@link #limitsProfile(SecurityProfile, SecurityProfile)} first
     */
    public SecurityConfiguration preset() {
        return switch (this) {
            case STRICT -> STRICT_PRESET;
            case PARANOID -> PARANOID_PRESET;
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
