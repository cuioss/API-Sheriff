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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.http.UpstreamIdentityHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the header inventories {@code doc/configuration.adoc} enumerates by hand to the Java sets that
 * enforce them: the request-direction never-forward set to {@link ConnectionHeaders#REQUEST_STRIP}, the
 * response-direction connection set to {@link ConnectionHeaders#RESPONSE_STRIP}, the response-direction
 * identity set to {@link UpstreamIdentityHeaders#NAMES}, and the two tiers of the gateway-understood
 * protocol set to {@link ForwardPolicyStage#UNCONDITIONAL_PROTOCOL_HEADERS} and
 * {@link ForwardPolicyStage#CONDITIONAL_HEADERS}. A header added to or removed from one of those
 * constants turns this test red until the reference documents the change, and so does a name added to
 * or removed from the reference.
 * <p>
 * <strong>No vacuous pass.</strong> Each extraction is anchored on a literal sentence fragment of the
 * reference and ends at a second one. When either anchor is missing, or the span between them names no
 * header, the test fails naming the anchor rather than comparing an empty set. A header-shaped token is
 * a backticked name of letters and digits joined by {@code -} or {@code _}, kept only when it carries a
 * separator or starts upper case, so a key path such as {@code not_modified} or a word such as
 * {@code h2c} is skipped while {@code TE}, {@code HTTP2-Settings} and {@code X_Forwarded_For} are read.
 * Two negative controls feed copies of the reference whose never-forward and response-direction spans
 * gained or lost one name back through the same extraction and assert the comparison fails.
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

    /** Opens the request-direction never-forward set. */
    private static final String NEVER_FORWARD_START = "*Request direction.* These client headers are withheld";

    /** Closes the never-forward enumeration, ahead of the separator-folding example spellings. */
    private static final String NEVER_FORWARD_END = "Every name in";

    /** Opens the response-direction connection set. */
    private static final String RESPONSE_STRIP_START = "*Response direction.* These upstream response headers are never placed";

    /** Closes the response-direction connection set's header list. */
    private static final String RESPONSE_STRIP_END = "and any HTTP/2 pseudo-header";

    /** A never-forward member the first negative control removes from its span. */
    private static final String REMOVED_NEVER_FORWARD = "`Host`, ";

    /** A name the second negative control adds to the response-direction span; no constant holds it. */
    private static final String ADDED_RESPONSE_STRIP = " `X-Diverged`,";

    /**
     * A backticked token shaped like a header name: letters and digits, starting with a letter,
     * optionally joined by hyphens or underscores.
     */
    private static final Pattern BACKTICKED_HEADER = Pattern
            .compile("`([A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)*)`");

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
    @DisplayName("the never-forward set in configuration.adoc names exactly ConnectionHeaders.REQUEST_STRIP")
    void neverForwardSetMatchesTheRequestStrip() throws Exception {
        // Arrange
        String document = configurationAdoc();

        // Act
        Set<String> documented = lowerCased(headersBetween(document, NEVER_FORWARD_START, NEVER_FORWARD_END));

        // Assert
        assertEquals(new TreeSet<>(ConnectionHeaders.REQUEST_STRIP), documented,
                "the never-forward set documented in " + CONFIGURATION_ADOC
                        + " must match ConnectionHeaders.REQUEST_STRIP");
    }

    @Test
    @DisplayName("the response-direction set in configuration.adoc names exactly ConnectionHeaders.RESPONSE_STRIP")
    void responseDirectionSetMatchesTheResponseStrip() throws Exception {
        // Arrange
        String document = configurationAdoc();

        // Act
        Set<String> documented = lowerCased(headersBetween(document, RESPONSE_STRIP_START, RESPONSE_STRIP_END));

        // Assert
        assertEquals(new TreeSet<>(ConnectionHeaders.RESPONSE_STRIP), documented,
                "the response-direction set documented in " + CONFIGURATION_ADOC
                        + " must match ConnectionHeaders.RESPONSE_STRIP");
    }

    @Test
    @DisplayName("negative control: a never-forward span that lost a member no longer matches REQUEST_STRIP")
    void neverForwardSpanMissingAMemberIsUnequal() throws Exception {
        // Arrange
        String diverged = withinSpan(configurationAdoc(), NEVER_FORWARD_START, NEVER_FORWARD_END,
                span -> removedOnce(span, REMOVED_NEVER_FORWARD));

        // Act
        Set<String> documented = lowerCased(headersBetween(diverged, NEVER_FORWARD_START, NEVER_FORWARD_END));

        // Assert
        assertNotEquals(new TreeSet<>(ConnectionHeaders.REQUEST_STRIP), documented,
                "a member removed from the documented never-forward set must make the comparison unequal");
        assertFalse(documented.contains("host"), "precondition: the diverged span no longer names Host");
    }

    @Test
    @DisplayName("negative control: a response-direction span that gained a name no longer matches RESPONSE_STRIP")
    void responseDirectionSpanWithAnExtraNameIsUnequal() throws Exception {
        // Arrange
        String diverged = withinSpan(configurationAdoc(), RESPONSE_STRIP_START, RESPONSE_STRIP_END,
                span -> span.replaceFirst(Pattern.quote(":"), Matcher.quoteReplacement(":" + ADDED_RESPONSE_STRIP)));

        // Act
        Set<String> documented = lowerCased(headersBetween(diverged, RESPONSE_STRIP_START, RESPONSE_STRIP_END));

        // Assert
        assertNotEquals(new TreeSet<>(ConnectionHeaders.RESPONSE_STRIP), documented,
                "a name added to the documented response-direction set must make the comparison unequal");
        assertTrue(documented.contains("x-diverged"), "precondition: the diverged span names the added header");
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

    /**
     * Returns a copy of {@code document} whose span between the two anchors was rewritten by {@code edit};
     * the rest of the document is unchanged, so the edit cannot reach a span another binding reads.
     */
    private static String withinSpan(String document, String start, String end, UnaryOperator<String> edit) {
        int from = document.indexOf(start);
        assertTrue(from >= 0, () -> "anchor not found in " + CONFIGURATION_ADOC + ": " + start);
        int spanStart = from + start.length();
        int to = document.indexOf(end, spanStart);
        assertTrue(to >= 0, () -> "closing anchor not found after '" + start + "': " + end);
        return document.substring(0, spanStart) + edit.apply(document.substring(spanStart, to))
                + document.substring(to);
    }

    private static String removedOnce(String span, String token) {
        assertTrue(span.contains(token), () -> "precondition: the span names " + token);
        return span.replaceFirst(Pattern.quote(token), "");
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
