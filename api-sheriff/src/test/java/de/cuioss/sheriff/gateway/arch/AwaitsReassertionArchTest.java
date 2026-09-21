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
package de.cuioss.sheriff.gateway.arch;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fitness function for the <em>until-then-re-assert</em> invariant: a declared test that waits on a
 * condition with {@code Awaits.until(..)} must assert the state it waited for <em>after</em> the
 * wait returns.
 *
 * <h2>Why a wait is not an assertion</h2>
 *
 * {@code Awaits.until} polls and either returns or throws a {@code TimeoutException}. A test whose
 * last word on the awaited state is the poll therefore records only that the condition held at some
 * instant during polling — never that it holds at the point the test finishes reasoning about it. If
 * the production behaviour the test names is removed, the poll may still be satisfied transiently by
 * a neighbouring effect, and the test stays green. Re-asserting after the wait moves the evidence
 * onto the post-state, which is the property the test's name actually claims.
 *
 * <h2>Why a source sweep rather than an ArchUnit rule</h2>
 *
 * The discriminator is the <em>order</em> of two calls inside one method body, and whether the second
 * one is an assertion. ArchUnit reads bytecode, where an assertion call and an await call are both
 * just method calls from the same code unit with no recoverable ordering, so the bytecode rule that
 * would express this does not exist. The sweep therefore reads the source — the same shape
 * {@code LoopbackEphemeralBindArchTest}'s wildcard-host-literal sweep uses, and for the same reason.
 *
 * <p>The sweep is phrased <strong>positively</strong>, per ADR-0030: it collects offenders and
 * asserts the collection is empty, with each offender named in the failure message. The inverted
 * {@code no...should} spelling is deliberately not used — ArchUnit inverts a condition's event
 * polarity under it, so such a rule reports nothing and stays green over an arbitrarily dirty
 * corpus.
 *
 * <h2>The four control legs (ADR-0030)</h2>
 *
 * <ol>
 *   <li><strong>Non-vacuity guard</strong> — {@link #guardIsNonVacuous()} asserts the source root
 *       resolves, the selection is non-empty, it contains declared test methods, and at least one of
 *       those methods really does call {@code Awaits.until}. Without the last one, "zero offenders"
 *       cannot be told apart from a sweep whose pattern stopped matching.</li>
 *   <li><strong>Negative control</strong> — {@code AwaitsWithoutReassertionSpecimen} carries two
 *       deliberate violations the sweep must report.</li>
 *   <li><strong>Matched positive controls</strong> — {@code AwaitsWithReassertionSpecimen} carries
 *       the compliant shape and the helper-tier near miss; each control asserts its near-miss
 *       property still holds before asserting exclusion.</li>
 *   <li><strong>Specimen carve-out</strong> — the specimen package is excluded from the production
 *       selection by name in {@link #isCarvedOutSource(Path)}, not by relying on any import or
 *       package filter, and the carve-out has its own control.</li>
 * </ol>
 *
 * <h2>Accepted limits, stated rather than left to be discovered</h2>
 *
 * <p><strong>The selection is declared test methods, not every method.</strong> A wait that lives in
 * a named helper — {@code awaitReleased}, {@code awaitNotListening}, {@code awaitLog} — is a wait
 * <em>primitive</em>: its re-assertion obligation belongs to whichever test calls it, and several
 * such helpers legitimately end with the await. Sweeping every method body would report all of them,
 * which is a noisy guard rather than a finding. The cost of the scope is real and is not hidden: a
 * helper that both waits and never re-asserts is out of reach, and so is a test that delegates its
 * entire wait to such a helper.
 *
 * <p><strong>The lane is {@code api-sheriff} only.</strong> {@code integration-tests} depends on
 * {@code api-sheriff}, not the reverse, so this test structurally cannot see integration-lane
 * sources. Mirroring the sweep into that module is explicitly not taken — the integration lane
 * carries no {@code Awaits.until} call at all today, so a second copy would guard an empty set while
 * doubling the maintenance surface.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Awaits.until must be followed by a re-assertion")
class AwaitsReassertionArchTest {

    /** The module-relative test source root; the sweep reads source text, not bytecode. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    private static final String SPECIMEN_DIRECTORY = "de/cuioss/sheriff/gateway/arch/specimen";
    private static final String WITHOUT_REASSERTION_SPECIMEN =
            SPECIMEN_DIRECTORY + "/AwaitsWithoutReassertionSpecimen.java";
    private static final String WITH_REASSERTION_SPECIMEN =
            SPECIMEN_DIRECTORY + "/AwaitsWithReassertionSpecimen.java";

    /** The annotations that declare a method to be a test; the sweep's selection is exactly these. */
    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest)\\b");

    /** A call to the waiting entry point, tolerant of whitespace around the selector. */
    private static final Pattern AWAITS_UNTIL = Pattern.compile("\\bAwaits\\s*\\.\\s*until\\s*\\(");

    /**
     * Any JUnit-shaped assertion call. Deliberately matched on the {@code assertXxx(} spelling rather
     * than on a fixed list, so a project-local assertion helper counts too — the invariant is that
     * the post-state is asserted, not which assertion library says so.
     */
    private static final Pattern ASSERTION_CALL = Pattern.compile("\\bassert[A-Z]\\w*\\s*\\(");

    // --- the sweep ------------------------------------------------------

    /**
     * The guarded source files: every test source except the specimen package and this guard's own
     * source.
     *
     * @return the sources the sweep scans
     * @throws IOException when the source tree cannot be walked
     */
    private static List<Path> guardedSources() throws IOException {
        if (!Files.isDirectory(TEST_SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(TEST_SOURCE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isCarvedOutSource(path))
                    .toList();
        }
    }

    /**
     * Whether a source path is outside the sweep's scope.
     *
     * <p>Two carve-outs, both by name. The specimen package holds the sweep's own deliberate
     * violations, so scanning it would make the production sweep permanently red. This guard's own
     * source is excluded for the same reason the sibling guards exclude theirs: it necessarily
     * contains the spellings it refuses.
     *
     * @param path a source file
     * @return {@code true} when the sweep must not scan it
     */
    private static boolean isCarvedOutSource(Path path) {
        String normalised = path.toString().replace('\\', '/');
        return normalised.contains("/" + SPECIMEN_DIRECTORY + "/")
                || normalised.endsWith("/AwaitsReassertionArchTest.java");
    }

    /**
     * Every {@code Awaits.until} call in {@code content}'s declared test methods that is not followed
     * by an assertion inside the same method.
     *
     * @param label   how the file is named in a failure message
     * @param content the source file's full text
     * @return one entry per offending call site, as {@code label:line}
     */
    private static List<String> offendersIn(String label, String content) {
        String code = blankNonCode(content);
        List<String> offenders = new ArrayList<>();
        for (int[] body : testMethodBodies(code)) {
            for (int site : untilSitesIn(code, body[0], body[1])) {
                int callEnd = matchingCloser(code, code.indexOf('(', site), '(', ')');
                int searchFrom = callEnd < 0 ? body[1] : callEnd + 1;
                if (searchFrom >= body[1]
                        || !ASSERTION_CALL.matcher(code.substring(searchFrom, body[1])).find()) {
                    offenders.add(label + ":" + lineOf(content, site));
                }
            }
        }
        return offenders;
    }

    /**
     * The offsets of every {@code Awaits.until} call inside declared test methods.
     *
     * @param code source text with comments and literals blanked
     * @return the call-site offsets, in source order
     */
    private static List<Integer> untilSitesInTestMethods(String code) {
        List<Integer> sites = new ArrayList<>();
        for (int[] body : testMethodBodies(code)) {
            sites.addAll(untilSitesIn(code, body[0], body[1]));
        }
        return sites;
    }

    /**
     * The offsets of every {@code Awaits.until} call that is NOT inside a declared test method — the
     * helper tier the sweep's scope deliberately leaves alone.
     *
     * @param code source text with comments and literals blanked
     * @return the call-site offsets, in source order
     */
    private static List<Integer> untilSitesOutsideTestMethods(String code) {
        List<Integer> inTests = untilSitesInTestMethods(code);
        List<Integer> outside = new ArrayList<>(untilSitesIn(code, 0, code.length()));
        outside.removeAll(inTests);
        return outside;
    }

    private static List<Integer> untilSitesIn(String code, int from, int to) {
        List<Integer> sites = new ArrayList<>();
        Matcher until = AWAITS_UNTIL.matcher(code.substring(from, to));
        while (until.find()) {
            sites.add(from + until.start());
        }
        return sites;
    }

    /**
     * The body ranges of every declared test method, as {@code {firstIndexInsideBody, closingBrace}}.
     *
     * <p>Located by walking forward from each test annotation to the first brace that is not inside a
     * parameter or annotation-argument list, then brace-matching. A method that reaches a {@code ;}
     * at depth zero first has no body and is skipped.
     *
     * @param code source text with comments and literals blanked
     * @return the body ranges, in source order
     */
    private static List<int[]> testMethodBodies(String code) {
        List<int[]> bodies = new ArrayList<>();
        Matcher annotation = TEST_ANNOTATION.matcher(code);
        int from = 0;
        while (annotation.find(from)) {
            from = annotation.end();
            int open = bodyBraceAfter(code, annotation.end());
            if (open < 0) {
                continue;
            }
            int close = matchingCloser(code, open, '{', '}');
            if (close < 0) {
                continue;
            }
            bodies.add(new int[]{open + 1, close});
            from = close;
        }
        return bodies;
    }

    private static int bodyBraceAfter(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                if (c == '{') {
                    return i;
                }
                if (c == ';') {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static int matchingCloser(String code, int openIndex, char opener, char closer) {
        if (openIndex < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = openIndex; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Replaces every comment, string literal, text block and character literal with spaces, keeping
     * newlines and every offset intact.
     *
     * <p>Not a Java parser, and it does not need to be. It exists so the two things the sweep counts
     * — braces and identifiers — cannot be faked by text that is not code: a JSON text block would
     * otherwise unbalance the brace walk, and an {@code assertXxx(} written inside a comment after an
     * await would otherwise make an offender read as compliant.
     *
     * @param content the raw source
     * @return the same text with non-code blanked, byte-for-byte the same length
     */
    private static String blankNonCode(String content) {
        char[] out = content.toCharArray();
        int length = content.length();
        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c == '/' && i + 1 < length && content.charAt(i + 1) == '/') {
                int newline = content.indexOf('\n', i);
                i = blankTo(out, content, i, newline < 0 ? length : newline);
            } else if (c == '/' && i + 1 < length && content.charAt(i + 1) == '*') {
                int end = content.indexOf("*/", i + 2);
                i = blankTo(out, content, i, end < 0 ? length : end + 2);
            } else if (c == '"' && content.startsWith("\"\"\"", i)) {
                int end = content.indexOf("\"\"\"", i + 3);
                i = blankTo(out, content, i, end < 0 ? length : end + 3);
            } else if (c == '"' || c == '\'') {
                i = blankTo(out, content, i, endOfSimpleLiteral(content, i));
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blankTo(char[] out, String content, int from, int to) {
        for (int i = from; i < to; i++) {
            if (content.charAt(i) != '\n') {
                out[i] = ' ';
            }
        }
        return to;
    }

    private static int endOfSimpleLiteral(String content, int start) {
        char quote = content.charAt(start);
        int i = start + 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\n') {
                return i;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return content.length();
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // --- the rule -------------------------------------------------------

    @Test
    @DisplayName("Every declared test that waits on a condition re-asserts it after the wait")
    void everyAwaitedTestReassertsAfterTheWait() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : guardedSources()) {
            offenders.addAll(offendersIn(source.getFileName().toString(), Files.readString(source)));
        }

        assertTrue(offenders.isEmpty(),
                "A test waits with Awaits.until and never asserts the state it waited for. The poll is "
                        + "then the test's only evidence, so the test survives the removal of the "
                        + "behaviour its name claims: the condition can be satisfied transiently by a "
                        + "neighbouring effect and the method still returns. Assert the awaited state "
                        + "after the wait — WebSocketRelayStageTest.awaitReleases is the shape. "
                        + "Offenders: " + offenders);
    }

    /**
     * Guards the guard. Four ways this sweep could pass while protecting nothing, each closed by one
     * assertion below: the source root does not resolve; the walk returns no sources; the sources
     * carry no method the sweep recognises as a test; or no recognised test calls
     * {@code Awaits.until} at all, in which case zero offenders says nothing about whether an
     * un-reasserted wait would have been found.
     * <p>
     * The fourth is the one the controls cannot cover. They read their own hardcoded specimen paths,
     * so they prove the mechanism works while saying nothing about whether the real selection is
     * still being scanned.
     */
    @Test
    @DisplayName("Until-then-re-assert guard is non-vacuous: sources, test methods and awaited sites all resolve")
    void guardIsNonVacuous() throws Exception {
        List<Path> sources = guardedSources();
        int testMethods = 0;
        int awaitedSites = 0;
        for (Path source : sources) {
            String code = blankNonCode(Files.readString(source));
            testMethods += testMethodBodies(code).size();
            awaitedSites += untilSitesInTestMethods(code).size();
        }
        int declaredTestMethods = testMethods;
        int declaredAwaitedSites = awaitedSites;

        assertAll("the until-then-re-assert guard is non-vacuous",
                () -> assertTrue(Files.isDirectory(TEST_SOURCE_ROOT),
                        "The test source root did not resolve to a directory at "
                                + TEST_SOURCE_ROOT.toAbsolutePath() + ", so the sweep read nothing and "
                                + "its clean verdict is about an empty set."),
                () -> assertFalse(sources.isEmpty(),
                        "The guarded selection resolved to NO source files — either the walk stopped "
                                + "matching '.java', or both carve-outs widened to the whole tree."),
                () -> assertTrue(declaredTestMethods > 0,
                        "No declared test method was recognised in the selection. Either the annotation "
                                + "pattern stopped matching or the brace walk stopped resolving bodies; "
                                + "either way the sweep is scanning zero methods."),
                () -> assertTrue(declaredAwaitedSites > 0,
                        "No declared test method in the selection was seen calling Awaits.until. Zero "
                                + "offenders is then uninformative: it cannot be told apart from a sweep "
                                + "whose call pattern no longer matches anything."));
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        /**
         * Negative control: the sweep must report BOTH deliberate violations in the specimen. A count
         * below two means one shape stopped being recognised — most likely the ordering half, whose
         * assertion sits before the wait — and the clean verdict over the real tree then covers less
         * than it appears to.
         */
        @Test
        @DisplayName("Sweep reports both deliberate violations in the specimen (negative control)")
        void sweepReportsTheSpecimenWithoutReassertion() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(WITHOUT_REASSERTION_SPECIMEN);
            assertTrue(Files.exists(specimen),
                    "The negative-control specimen is missing at " + specimen
                            + ", so this control is exercising nothing.");

            List<String> offenders =
                    offendersIn(specimen.getFileName().toString(), Files.readString(specimen));

            assertEquals(2, offenders.size(),
                    "The sweep must report BOTH deliberate violations: the test that asserts nothing at "
                            + "all, and the one that asserts before the wait and not after. Offenders: "
                            + offenders);
        }

        /**
         * Matched positive control for the compliant shape. The near-miss property — that the specimen
         * really does carry an awaited test method — is asserted first; without it the exclusion would
         * hold just as well over a file that no longer waits at all, which is the always-passing
         * failure one level down.
         */
        @Test
        @DisplayName("Sweep accepts the re-asserting test in the matched specimen (positive control)")
        void sweepAcceptsTheReassertingTest() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(WITH_REASSERTION_SPECIMEN);
            assertTrue(Files.exists(specimen),
                    "The positive-control specimen is missing at " + specimen
                            + ", so this control is exercising nothing.");
            String content = Files.readString(specimen);

            assertFalse(untilSitesInTestMethods(blankNonCode(content)).isEmpty(),
                    "The matched specimen no longer carries an Awaits.until inside a declared test "
                            + "method, so the acceptance below is about an empty selection rather than "
                            + "about the sweep discriminating.");
            assertTrue(offendersIn(specimen.getFileName().toString(), content).isEmpty(),
                    "The sweep must accept a test that re-asserts after its wait — a sweep that "
                            + "reported it would be always-failing rather than discriminating.");
        }

        /**
         * Matched positive control for the scope limit. The helper-tier await is character-for-character
         * the violation the negative control carries; only the absence of a test annotation excludes it.
         * <p>
         * The order is load-bearing. "Not reported" is trivially true of a specimen that quietly gained
         * a re-assertion, so the near-miss property is asserted first: an await outside every test
         * method, with no assertion after it anywhere in the file.
         */
        @Test
        @DisplayName("Sweep leaves the helper-tier await alone, and the near miss still holds (positive control)")
        void sweepLeavesTheHelperTierAwaitAlone() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(WITH_REASSERTION_SPECIMEN);
            String content = Files.readString(specimen);
            String code = blankNonCode(content);

            List<Integer> helperSites = untilSitesOutsideTestMethods(code);
            assertFalse(helperSites.isEmpty(),
                    "The matched specimen no longer carries an Awaits.until outside a declared test "
                            + "method, so the exclusion below is about a file that has nothing to "
                            + "exclude rather than about the guard's scope.");
            int lastHelperSite = helperSites.get(helperSites.size() - 1);
            assertFalse(ASSERTION_CALL.matcher(code.substring(lastHelperSite)).find(),
                    "The helper-tier await at line " + lineOf(content, lastHelperSite) + " is now "
                            + "followed by an assertion, so it is no longer the un-reasserted near miss "
                            + "this control needs. Restore an await-only helper.");

            assertTrue(offendersIn(specimen.getFileName().toString(), content).isEmpty(),
                    "The sweep must leave a wait primitive alone: its re-assertion obligation belongs to "
                            + "the test that calls it, and reporting it would make the guard noisy "
                            + "rather than useful.");
        }

        /**
         * Carve-out control. The specimen package is excluded by name, and that exclusion is asserted
         * against a specimen that demonstrably still violates the rule — otherwise "the production
         * sweep is clean" would be satisfied by a specimen that had stopped violating anything.
         */
        @Test
        @DisplayName("The specimen package is excluded from the production selection by name (carve-out)")
        void specimenPackageIsExcludedFromTheProductionSelection() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(WITHOUT_REASSERTION_SPECIMEN);

            assertFalse(offendersIn(specimen.getFileName().toString(), Files.readString(specimen)).isEmpty(),
                    "The negative-control specimen carries no violation any more, so its absence from "
                            + "the production selection proves nothing about the carve-out.");
            assertTrue(isCarvedOutSource(specimen),
                    "The specimen package must be carved out by name, not by relying on a package or "
                            + "import filter that a refactor could quietly widen.");
            assertTrue(guardedSources().stream().noneMatch(AwaitsReassertionArchTest::isSpecimenSource),
                    "A specimen source reached the production selection, which would make the sweep "
                            + "permanently red for the violations it deliberately owns.");
        }
    }

    private static boolean isSpecimenSource(Path path) {
        return path.toString().replace('\\', '/').contains("/" + SPECIMEN_DIRECTORY + "/");
    }
}
