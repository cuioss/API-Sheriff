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
package de.cuioss.sheriff.gateway.forward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.http.UpstreamIdentityHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the header inventories {@code doc/configuration.adoc} enumerates by hand to the Java sets that
 * enforce them: the response-direction identity set to {@link UpstreamIdentityHeaders#NAMES}, and the
 * two tiers of the gateway-understood protocol set to
 * {@link ForwardPolicyStage#UNCONDITIONAL_PROTOCOL_HEADERS} and
 * {@link ForwardPolicyStage#CONDITIONAL_HEADERS}. A header added to or removed from one of those
 * constants turns this test red until the reference documents the change.
 * <p>
 * <strong>No vacuous pass.</strong> Each extraction is anchored on a literal sentence fragment of the
 * reference and ends at a second one. When either anchor is missing, or the span between them names no
 * header, the test fails naming the anchor rather than comparing an empty set.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The documented header sets stay bound to the constants that enforce them")
class DocumentedHeaderSetsContractTest {

    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String CONFIGURATION_ADOC = "doc/configuration.adoc";

    /** Opens the identity-set paragraph, immediately before its header list. */
    private static final String IDENTITY_START = "are removed before the response reaches the client:";

    /** Closes the identity-set header list. */
    private static final String IDENTITY_END = "They are dropped";

    /** Opens the unconditional protocol tier. */
    private static final String UNCONDITIONAL_START = "* *Unconditional* --";

    /** Closes the unconditional tier's header list. */
    private static final String UNCONDITIONAL_END = "They cross whatever";

    /** Opens the conditional protocol tier. */
    private static final String CONDITIONAL_START = "* *Conditional* --";

    /** Closes the conditional tier's header list: the end of its first sentence. */
    private static final String CONDITIONAL_END = ". On";

    /** A backticked token shaped like a header name: letters, optionally joined by hyphens. */
    private static final Pattern BACKTICKED_HEADER = Pattern.compile("`([A-Za-z]+(?:-[A-Za-z]+)*)`");

    @Test
    @DisplayName("the identity set in configuration.adoc names exactly UpstreamIdentityHeaders.NAMES")
    void identitySetMatchesTheStrippedNames() throws Exception {
        // Arrange
        String document = configurationAdoc();

        // Act
        Set<String> documented = lowerCased(headersBetween(document, IDENTITY_START, IDENTITY_END));

        // Assert
        assertEquals(new TreeSet<>(UpstreamIdentityHeaders.NAMES), documented,
                "the identity set documented in " + CONFIGURATION_ADOC + " must match UpstreamIdentityHeaders.NAMES");
    }

    @Test
    @DisplayName("the unconditional protocol tier in configuration.adoc names exactly UNCONDITIONAL_PROTOCOL_HEADERS")
    void unconditionalTierMatchesTheConstant() throws Exception {
        // Arrange
        String document = configurationAdoc();

        // Act
        Set<String> documented = lowerCased(headersBetween(document, UNCONDITIONAL_START, UNCONDITIONAL_END));

        // Assert
        assertEquals(lowerCased(Set.copyOf(ForwardPolicyStage.UNCONDITIONAL_PROTOCOL_HEADERS)), documented,
                "the unconditional tier documented in " + CONFIGURATION_ADOC
                        + " must match ForwardPolicyStage.UNCONDITIONAL_PROTOCOL_HEADERS");
    }

    @Test
    @DisplayName("the conditional protocol tier in configuration.adoc names exactly CONDITIONAL_HEADERS")
    void conditionalTierMatchesTheConstant() throws Exception {
        // Arrange
        String document = configurationAdoc();

        // Act
        Set<String> documented = lowerCased(headersBetween(document, CONDITIONAL_START, CONDITIONAL_END));

        // Assert
        assertEquals(lowerCased(Set.copyOf(ForwardPolicyStage.CONDITIONAL_HEADERS)), documented,
                "the conditional tier documented in " + CONFIGURATION_ADOC
                        + " must match ForwardPolicyStage.CONDITIONAL_HEADERS");
    }

    @Test
    @DisplayName("negative control: the extraction skips non-header tokens and sees a header the constant lacks")
    void extractionDistinguishesHeadersFromOtherTokens() {
        // Arrange — a status code and a key path are backticked but are no header names
        String sample = "* *Conditional* -- the validators a `304` answers, `If-None-Match` and `X-Extra`, "
                + "governed by `not_modified`. On every other method";

        // Act
        Set<String> documented = lowerCased(headersBetween(sample, CONDITIONAL_START, CONDITIONAL_END));

        // Assert
        assertEquals(Set.of("if-none-match", "x-extra"), documented,
                "only the two header-shaped tokens are extracted");
        assertNotEquals(lowerCased(Set.copyOf(ForwardPolicyStage.CONDITIONAL_HEADERS)), documented,
                "a header the constant lacks must make the comparison unequal");
    }

    private static Set<String> headersBetween(String document, String start, String end) {
        int from = document.indexOf(start);
        if (from < 0) {
            return fail("anchor not found in " + CONFIGURATION_ADOC + ": " + start);
        }
        int to = document.indexOf(end, from + start.length());
        if (to < 0) {
            return fail("closing anchor not found after '" + start + "' in " + CONFIGURATION_ADOC + ": " + end);
        }
        Matcher matcher = BACKTICKED_HEADER.matcher(document.substring(from + start.length(), to));
        Set<String> headers = new TreeSet<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.contains("-") || Character.isUpperCase(token.charAt(0))) {
                headers.add(token);
            }
        }
        assertFalse(headers.isEmpty(), () -> "no header named between '" + start + "' and '" + end + "'");
        return headers;
    }

    private static Set<String> lowerCased(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String configurationAdoc() throws IOException {
        return Files.readString(repoRoot().resolve(CONFIGURATION_ADOC), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("doc"))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE);
    }
}
