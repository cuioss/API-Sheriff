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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;


import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PortalNotice}: only the exact wire value of a vocabulary constant is recognised;
 * every other value — the attacker-controlled free text the parameter can carry — is dropped.
 */
@EnableGeneratorController
@DisplayName("PortalNotice")
class PortalNoticeTest {

    @Test
    @DisplayName("Recognises logged-out")
    void recognisesLoggedOut() {
        Optional<PortalNotice> notice = PortalNotice.parse("logged-out");

        assertEquals(Optional.of(PortalNotice.LOGGED_OUT), notice);
        assertEquals("logged-out", notice.orElseThrow().wireValue());
    }

    @Test
    @DisplayName("Drops an absent parameter")
    void dropsAbsentParameter() {
        assertTrue(PortalNotice.parse(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "logged-in", "Logged-Out", "LOGGED-OUT", "LOGGED_OUT", "logged-out ",
            " logged-out", "logged%2Dout", "logged-out%00", "<script>alert(1)</script>",
            "logged-out<script>", "<b>logged-out</b>", "logged-out&x=1", "logged-out\n"})
    @DisplayName("Drops unknown, empty, mixed-case, percent-encoded and HTML-bearing values")
    void dropsEveryNonVocabularyValue(String raw) {
        assertTrue(PortalNotice.parse(raw).isEmpty(), () -> "must be dropped: '" + raw + "'");
    }

    @ParameterizedTest
    @GeneratorsSource(generator = GeneratorType.NON_BLANK_STRINGS, minSize = 1, maxSize = 40, count = 20)
    @DisplayName("Drops generated free text")
    void dropsGeneratedFreeText(String raw) {
        Optional<PortalNotice> notice = PortalNotice.parse(raw);

        assertTrue(notice.isEmpty() || notice.get().wireValue().equals(raw),
                () -> "free text is never mapped onto a notice it does not spell exactly: '" + raw + "'");
    }
}
