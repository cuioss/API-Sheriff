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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

@DisplayName("SecurityProfile — the canonical strict/lenient/none inbound-filter mode set")
class SecurityProfileTest {

    @ParameterizedTest
    @CsvSource({
            "strict,STRICT",
            "lenient,LENIENT",
            "none,NONE",
            "STRICT,STRICT",
            "Lenient,LENIENT",
            "  none  ,NONE"
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
    @ValueSource(strings = {"default", "DEFAULT", "off", "disabled", "strictest", "", "  "})
    @DisplayName("Should reject the dropped 'default' preset and every unknown value")
    void shouldRejectUnknownValues(String raw) {
        // Arrange - 'default' was dropped with this plan; the rest were never members

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
    @DisplayName("Should map STRICT and LENIENT onto their cui-http presets")
    void shouldMapToPresets() {
        // Act + Assert
        assertEquals(SecurityConfiguration.strict(), SecurityProfile.STRICT.preset(),
                "STRICT is backed by SecurityConfiguration.strict()");
        assertEquals(SecurityConfiguration.lenient(), SecurityProfile.LENIENT.preset(),
                "LENIENT is backed by SecurityConfiguration.lenient()");
    }

    @Test
    @DisplayName("Should refuse a preset for NONE — 'none' contributes no limits policy")
    void shouldRefusePresetForNone() {
        // Act
        IllegalStateException refused = assertThrows(IllegalStateException.class, SecurityProfile.NONE::preset,
                "NONE.preset() is a programming error, not a silent fallback");

        // Assert
        assertTrue(refused.getMessage().contains("none"),
                "the refusal names the offending mode so the boot log is diagnosable");
    }

    @Test
    @DisplayName("Should resolve the limits profile to the nearest non-none entry of the chain")
    void shouldResolveLimitsProfileToNearestNonNone() {
        // Arrange + Act + Assert - the route's own profile wins whenever it is not 'none'
        assertEquals(SecurityProfile.LENIENT,
                SecurityProfile.limitsProfile(SecurityProfile.LENIENT, SecurityProfile.STRICT),
                "a declared non-none profile supplies its own limits");
        assertEquals(SecurityProfile.STRICT,
                SecurityProfile.limitsProfile(SecurityProfile.STRICT, SecurityProfile.NONE),
                "a declared non-none profile is unaffected by a 'none' fallback");

        // A 'none' route inherits the limits of the next level down …
        assertEquals(SecurityProfile.LENIENT,
                SecurityProfile.limitsProfile(SecurityProfile.NONE, SecurityProfile.LENIENT),
                "a 'none' route takes the nearest non-none profile's limits");

        // … and lands on STRICT when the whole chain is 'none'.
        assertEquals(SecurityProfile.STRICT,
                SecurityProfile.limitsProfile(SecurityProfile.NONE, SecurityProfile.NONE),
                "an all-none chain falls back to STRICT, so the body cap stays enforceable");
    }

    @ParameterizedTest
    @EnumSource(SecurityProfile.class)
    @DisplayName("Should always resolve a limits profile that has a callable preset")
    void shouldAlwaysResolveACallablePreset(SecurityProfile profile) {
        // Act
        SecurityConfiguration resolved = SecurityProfile.limitsProfile(profile, profile).preset();

        // Assert
        assertTrue(resolved.maxBodySize() > 0,
                "every mode — 'none' included — resolves a concrete, enforceable body cap");
    }

    @Test
    @DisplayName("Should disable the skippable validation half only for NONE")
    void shouldGateSkippableValidationOnNoneOnly() {
        // Act + Assert
        assertTrue(SecurityProfile.STRICT.skippableValidationEnabled(), "STRICT runs the full check set");
        assertTrue(SecurityProfile.LENIENT.skippableValidationEnabled(), "LENIENT runs the full check set");
        assertFalse(SecurityProfile.NONE.skippableValidationEnabled(),
                "NONE is the one mode that skips the url-parameter validation and the pipeline re-run");
    }

    @Test
    @DisplayName("Should declare STRICT as the fail-closed default for an omitted block")
    void shouldDefaultToStrict() {
        // Assert
        assertEquals(SecurityProfile.STRICT, SecurityProfile.DEFAULT_PROFILE,
                "an omitted security_defaults block resolves to the most restrictive mode");
    }
}
