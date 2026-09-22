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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;


import de.cuioss.http.security.config.SecurityConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SecurityProfile — the canonical strict/paranoid/lenient/minimal inbound-filter mode set")
class SecurityProfileTest {

    /**
     * One of the two modes whose preset tightens the cui-http baseline. Both carry the single
     * deliberate deviation this enum makes ({@code allowLineBreaksInParameterValues} off), so the
     * guards below run over the pair rather than restating one assertion per mode. An annotation
     * value must be a compile-time constant, which is why the names are held here rather than being
     * derived from {@link SecurityProfile#values()}; {@link #upstreamPresetOf(SecurityProfile)}
     * fails loudly if the pair and the enum ever disagree.
     */
    private static final String MODE_STRICT = "STRICT";

    /** The second tightened mode; see {@link #MODE_STRICT}. */
    private static final String MODE_PARANOID = "PARANOID";

    @ParameterizedTest
    @CsvSource({
            "strict,STRICT",
            "paranoid,PARANOID",
            "lenient,LENIENT",
            "minimal,MINIMAL",
            "STRICT,STRICT",
            "PARANOID,PARANOID",
            "Lenient,LENIENT",
            "MINIMAL,MINIMAL",
            "  minimal  ,MINIMAL",
            "  Paranoid  ,PARANOID"
    })
    @DisplayName("Should parse every accepted value case-insensitively")
    void shouldParseAcceptedValues(String raw, SecurityProfile expected) {
        // Arrange - the raw scalar as it appears in gateway.yaml / endpoints/*.yaml

        // Act
        Optional<SecurityProfile> parsed = SecurityProfile.parse(raw);

        // Assert
        assertEquals(Optional.of(expected), parsed, "'%s' parses to %s".formatted(raw, expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "DEFAULT", "none", "NONE", "off", "disabled", "strictest", "", "  "})
    @DisplayName("Should reject the dropped 'default' preset, the renamed 'none' and every unknown value")
    void shouldRejectUnknownValues(String raw) {
        // Arrange - 'default' was dropped and 'none' was renamed to 'minimal'; the rest were never
        // members. 'none' must NOT resolve: it survives only as the unrelated auth.require value.

        // Act
        Optional<SecurityProfile> parsed = SecurityProfile.parse(raw);

        // Assert
        assertTrue(parsed.isEmpty(), "'%s' names no mode of the set".formatted(raw));
    }

    @Test
    @DisplayName("Should reject a null value rather than throwing")
    void shouldRejectNull() {
        // Act
        Optional<SecurityProfile> parsed = SecurityProfile.parse(null);

        // Assert
        assertTrue(parsed.isEmpty(), "an absent scalar parses to empty, never an NPE");
    }

    @Test
    @DisplayName("Should map LENIENT onto the unmodified cui-http lenient preset")
    void shouldMapLenientToPreset() {
        // Act + Assert
        assertEquals(SecurityConfiguration.lenient(), SecurityProfile.LENIENT.preset(),
                "LENIENT is backed by SecurityConfiguration.lenient()");
    }

    @ParameterizedTest
    @EnumSource(value = SecurityProfile.class, names = {MODE_STRICT, MODE_PARANOID})
    @DisplayName("Should refuse decoded line breaks in parameter values under every tightened mode")
    void shouldRefuseLineBreaksInParameterValues(SecurityProfile profile) {
        // Arrange - cui-http admits decoded CR/LF in every one of its presets, paranoid() included,
        // so a mode sold as a tightening that took its preset verbatim would be LOOSER than STRICT
        // on this one axis. Both tightened modes must therefore switch it off.
        SecurityConfiguration upstream = upstreamPresetOf(profile);

        // Act
        SecurityConfiguration tightened = profile.preset();

        // Assert
        assertTrue(upstream.allowLineBreaksInParameterValues(),
                "the premise of this guard is that the cui-http preset backing %s admits line breaks;"
                        + " upstream has changed and the deviation may no longer be needed".formatted(profile));
        assertFalse(tightened.allowLineBreaksInParameterValues(),
                "%s refuses a url-parameter value that decodes to CR or LF".formatted(profile));
        assertNotEquals(upstream, tightened,
                "the deviation is real: the cui-http preset backing %s admits line breaks".formatted(profile));
    }

    @ParameterizedTest
    @EnumSource(value = SecurityProfile.class, names = {MODE_STRICT, MODE_PARANOID})
    @DisplayName("Should equal its cui-http preset in every component but the line-break flag")
    void shouldEqualUpstreamPresetExceptLineBreakFlag(SecurityProfile profile) {
        // Arrange
        SecurityConfiguration upstream = upstreamPresetOf(profile);

        // Act - put the one deviating component back to the upstream value
        SecurityConfiguration reseeded = SecurityConfigurations
                .builderSeededFrom(profile.preset())
                .allowLineBreaksInParameterValues(upstream.allowLineBreaksInParameterValues())
                .build();

        // Assert
        assertEquals(upstream, reseeded,
                "allowLineBreaksInParameterValues is the only component %s changes".formatted(profile));
    }

    @ParameterizedTest
    @EnumSource(value = SecurityProfile.class, names = {MODE_STRICT, MODE_PARANOID})
    @DisplayName("Should return the same cached policy on every call for a tightened mode")
    void shouldCacheTightenedPreset(SecurityProfile profile) {
        // Act + Assert
        assertSame(profile.preset(), profile.preset(),
                "%s's derived policy is built once, not per call".formatted(profile));
    }

    @Test
    @DisplayName("Should make PARANOID strictly tighter than STRICT — content block-lists added, nothing relaxed")
    void shouldMakeParanoidStrictlyTighterThanStrict() {
        // Arrange
        SecurityConfiguration strict = SecurityProfile.STRICT.preset();

        // Act
        SecurityConfiguration paranoid = SecurityProfile.PARANOID.preset();

        // Assert - the two block-lists are what PARANOID adds …
        assertFalse(paranoid.blockedPathPatterns().isEmpty(),
                "PARANOID seeds the sensitive-path block-list STRICT leaves empty");
        assertFalse(paranoid.blockedParameterNames().isEmpty(),
                "PARANOID seeds the suspicious-parameter-name block-list STRICT leaves empty");
        assertTrue(paranoid.blockedPathPatterns().containsAll(strict.blockedPathPatterns()),
                "PARANOID may only add to STRICT's path block-list, never drop from it");
        assertTrue(paranoid.blockedParameterNames().containsAll(strict.blockedParameterNames()),
                "PARANOID may only add to STRICT's parameter-name block-list, never drop from it");

        // … and nothing else differs, so no axis is quietly relaxed. Copying PARANOID's block-lists
        // onto STRICT must reproduce PARANOID exactly.
        assertEquals(paranoid,
                strict.withContentBlockLists(paranoid.blockedPathPatterns(), paranoid.blockedParameterNames()),
                "PARANOID differs from STRICT in the two content block-lists and in nothing else");
    }

    /**
     * The unmodified cui-http preset a tightened mode is built from — the baseline each guard above
     * compares against.
     *
     * @param profile one of the tightened modes
     * @return the upstream preset backing it
     */
    private static SecurityConfiguration upstreamPresetOf(SecurityProfile profile) {
        return switch (profile) {
            case STRICT -> SecurityConfiguration.strict();
            case PARANOID -> SecurityConfiguration.paranoid();
            case LENIENT, MINIMAL -> throw new IllegalArgumentException(
                    profile + " is not a tightened mode; TIGHTENED_MODES and this helper have drifted apart");
        };
    }

    @Test
    @DisplayName("Should refuse a preset for MINIMAL — 'minimal' contributes no limits policy")
    void shouldRefusePresetForMinimal() {
        // Act
        IllegalStateException refused = assertThrows(IllegalStateException.class, SecurityProfile.MINIMAL::preset,
                "MINIMAL.preset() is a programming error, not a silent fallback");

        // Assert
        assertTrue(refused.getMessage().contains("minimal"),
                "the refusal names the offending mode so the boot log is diagnosable");
    }

    @Test
    @DisplayName("Should resolve the limits profile to the nearest non-minimal entry of the chain")
    void shouldResolveLimitsProfileToNearestNonMinimal() {
        // Arrange + Act + Assert - the route's own profile wins whenever it is not 'minimal'
        assertEquals(SecurityProfile.LENIENT,
                SecurityProfile.limitsProfile(SecurityProfile.LENIENT, SecurityProfile.STRICT),
                "a declared non-minimal profile supplies its own limits");
        assertEquals(SecurityProfile.STRICT,
                SecurityProfile.limitsProfile(SecurityProfile.STRICT, SecurityProfile.MINIMAL),
                "a declared non-minimal profile is unaffected by a 'minimal' fallback");

        // A 'minimal' route inherits the limits of the next level down …
        assertEquals(SecurityProfile.LENIENT,
                SecurityProfile.limitsProfile(SecurityProfile.MINIMAL, SecurityProfile.LENIENT),
                "a 'minimal' route takes the nearest non-minimal profile's limits");

        // … and lands on STRICT when the whole chain is 'minimal'.
        assertEquals(SecurityProfile.STRICT,
                SecurityProfile.limitsProfile(SecurityProfile.MINIMAL, SecurityProfile.MINIMAL),
                "an all-minimal chain falls back to STRICT, so the body cap stays enforceable");

        // PARANOID is limits-bearing like any other non-minimal mode — it supplies its own limits
        // and is what a 'minimal' route inherits when it sits beneath one.
        assertEquals(SecurityProfile.PARANOID,
                SecurityProfile.limitsProfile(SecurityProfile.PARANOID, SecurityProfile.MINIMAL),
                "PARANOID supplies its own limits and is unaffected by a 'minimal' fallback");
        assertEquals(SecurityProfile.PARANOID,
                SecurityProfile.limitsProfile(SecurityProfile.MINIMAL, SecurityProfile.PARANOID),
                "a 'minimal' route under a paranoid gateway takes the paranoid limits");
    }

    @ParameterizedTest
    @EnumSource(SecurityProfile.class)
    @DisplayName("Should always resolve a limits profile that has a callable preset")
    void shouldAlwaysResolveACallablePreset(SecurityProfile profile) {
        // Act
        SecurityConfiguration resolved = SecurityProfile.limitsProfile(profile, profile).preset();

        // Assert
        assertTrue(resolved.maxBodySize() > 0,
                "every mode — 'minimal' included — resolves a concrete, enforceable body cap");
    }

    @ParameterizedTest
    @EnumSource(SecurityProfile.class)
    @DisplayName("Should disable the skippable validation half only for MINIMAL")
    void shouldGateSkippableValidationOnMinimalOnly(SecurityProfile profile) {
        // Arrange - the population is the enum itself rather than a hand-listed trio, so a mode added
        // later is covered by this guard on the day it is declared instead of being silently omitted

        // Act
        boolean enabled = profile.skippableValidationEnabled();

        // Assert
        assertEquals(profile != SecurityProfile.MINIMAL, enabled,
                "MINIMAL is the one mode that skips the url-parameter validation and the pipeline"
                        + " re-run; %s reported %s".formatted(profile, enabled));
    }

    @Test
    @DisplayName("Should declare STRICT as the fail-closed default for an omitted block")
    void shouldDefaultToStrict() {
        // Assert - STRICT, not PARANOID: the default is the tightest mode that judges form alone,
        // because PARANOID's content block-lists reject ordinary REST vocabulary and must be an
        // explicit operator choice rather than something an omitted block opts into
        assertEquals(SecurityProfile.STRICT, SecurityProfile.DEFAULT_PROFILE,
                "an omitted security_defaults block resolves to STRICT");
        assertNotEquals(SecurityProfile.PARANOID, SecurityProfile.DEFAULT_PROFILE,
                "adding PARANOID must not move the default; opting into content block-lists is a"
                        + " deployment decision, never an inherited one");
    }
}
