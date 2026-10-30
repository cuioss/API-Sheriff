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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import de.cuioss.sheriff.token.commons.events.EventCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the {@code category} values {@code doc/architecture/observability-and-errors.adoc} lists by hand
 * for {@code sheriff_token_validation_errors_total} to the library's {@link EventCategory}, the enum the
 * extension's collector takes the label from (ADR-0074) and {@code MetricsIT} derives its expected labels
 * from. A category added to or removed from either side turns this test red until the two agree again.
 * <p>
 * <strong>No vacuous pass.</strong> The extraction is anchored on a literal fragment of the row and ends
 * at a second one. When either anchor is missing, or the span between them names no category, the test
 * fails naming the anchor rather than comparing an empty set.
 * <p>
 * <strong>Every backticked token in the span counts.</strong> The extraction does not filter by the shape
 * of a category name, so a documented entry the enum lacks is compared, whatever its spelling, rather than
 * skipped. The two negative controls below mutate the real document to show that removing and adding an
 * entry each make the comparison unequal.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The documented token-validation categories stay bound to the library's EventCategory")
class DocumentedTokenValidationCategoriesContractTest {

    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String OBSERVABILITY_ADOC = "doc/architecture/observability-and-errors.adoc";

    /** Opens the category list inside the {@code sheriff_token_validation_errors_total} row. */
    private static final String CATEGORIES_START = "`result=\"failure\"`, `category` in";

    /** Closes the category list: the end of its clause, before the ADR link. */
    private static final String CATEGORIES_END = "; see";

    /** A backticked token, whatever its spelling. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @Test
    @DisplayName("the category list in the metric row names exactly EventCategory.values()")
    void documentedCategoriesMatchTheEnum() throws Exception {
        // Arrange
        String document = observabilityAdoc();

        // Act
        Set<String> documented = categoriesBetween(document, CATEGORIES_START, CATEGORIES_END);

        // Assert
        assertEquals(enumNames(), documented,
                "the categories documented in " + OBSERVABILITY_ADOC + " must match EventCategory.values()");
    }

    @Test
    @DisplayName("negative control: a category removed from the document makes the comparison unequal")
    void removingADocumentedCategoryIsDetected() throws Exception {
        // Arrange
        String document = observabilityAdoc();
        Set<String> expected = new TreeSet<>(categoriesBetween(document, CATEGORIES_START, CATEGORIES_END));
        String removed = enumNames().iterator().next();
        assertTrue(expected.remove(removed), () -> "control: " + removed + " must be documented to be removed");

        // Act
        Set<String> documented = categoriesBetween(document.replace("`" + removed + "`", ""),
                CATEGORIES_START, CATEGORIES_END);

        // Assert
        assertEquals(expected, documented, "the extraction must see exactly the removal");
        assertNotEquals(enumNames(), documented, "a removed category must make the comparison unequal");
    }

    @Test
    @DisplayName("negative control: a category added to the document makes the comparison unequal")
    void addingADocumentedCategoryIsDetected() throws Exception {
        // Arrange
        String added = "not_an_event_category";
        String document = observabilityAdoc();
        Set<String> expected = new TreeSet<>(categoriesBetween(document, CATEGORIES_START, CATEGORIES_END));
        assertTrue(expected.add(added), () -> "control: " + added + " must not be documented already");

        // Act
        Set<String> documented = categoriesBetween(
                document.replace(CATEGORIES_START, CATEGORIES_START + " `" + added + "`,"),
                CATEGORIES_START, CATEGORIES_END);

        // Assert
        assertEquals(expected, documented, "the extraction must see exactly the addition");
        assertNotEquals(enumNames(), documented, "an added category must make the comparison unequal");
    }

    @Test
    @DisplayName("a missing opening anchor fails naming the anchor")
    void missingOpeningAnchorFailsNamingIt() {
        // Act
        AssertionError failure = assertThrows(AssertionError.class,
                () -> categoriesBetween("`INVALID_SIGNATURE`; see", CATEGORIES_START, CATEGORIES_END));

        // Assert
        assertTrue(failure.getMessage().contains(CATEGORIES_START), failure::getMessage);
    }

    @Test
    @DisplayName("a missing closing anchor fails naming the anchor")
    void missingClosingAnchorFailsNamingIt() {
        // Act
        AssertionError failure = assertThrows(AssertionError.class,
                () -> categoriesBetween(CATEGORIES_START + " `INVALID_SIGNATURE`",
                        CATEGORIES_START, CATEGORIES_END));

        // Assert
        assertTrue(failure.getMessage().contains(CATEGORIES_END), failure::getMessage);
    }

    @Test
    @DisplayName("a span naming no category fails naming the anchors")
    void emptySpanFailsNamingTheAnchors() {
        // Act
        AssertionError failure = assertThrows(AssertionError.class,
                () -> categoriesBetween(CATEGORIES_START + " nothing" + CATEGORIES_END,
                        CATEGORIES_START, CATEGORIES_END));

        // Assert
        assertTrue(failure.getMessage().contains(CATEGORIES_START), failure::getMessage);
    }

    private static Set<String> categoriesBetween(String document, String start, String end) {
        int from = document.indexOf(start);
        if (from < 0) {
            return fail("anchor not found in " + OBSERVABILITY_ADOC + ": " + start);
        }
        int to = document.indexOf(end, from + start.length());
        if (to < 0) {
            return fail("closing anchor not found after '" + start + "' in " + OBSERVABILITY_ADOC + ": " + end);
        }
        Matcher matcher = BACKTICKED.matcher(document.substring(from + start.length(), to));
        Set<String> categories = new TreeSet<>();
        while (matcher.find()) {
            categories.add(matcher.group(1));
        }
        assertFalse(categories.isEmpty(), () -> "no category named between '" + start + "' and '" + end + "'");
        return categories;
    }

    private static Set<String> enumNames() {
        return Arrays.stream(EventCategory.values()).map(EventCategory::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String observabilityAdoc() throws IOException {
        return Files.readString(repoRoot().resolve(OBSERVABILITY_ADOC), StandardCharsets.UTF_8);
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
