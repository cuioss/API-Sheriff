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
package de.cuioss.sheriff.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the operator-facing documents and the bundled JSON Schema back to the code that
 * <em>authoritatively</em> defines the sets they enumerate.
 * <p>
 * <strong>Why this test exists.</strong> Four shipped surfaces restate a set whose definition lives
 * in Java: three of them list the built-in asset extensions carried by
 * {@link AssetResponseEnvelope#builtInExtensions()}, and one lists the inbound-filter mode set
 * carried by {@link SecurityProfile}. A restated list has no mechanical tie to its source, so adding
 * a mapping or a mode leaves every restatement silently stale — the documentation still reads as
 * authoritative while describing a gateway that no longer exists. The project's own review policy
 * treats a hardcoded list mirroring a set defined elsewhere as a defect unless it is derived from
 * that source at build or run time; deriving these at build time would mean generating prose, so
 * this contract test is the sanctioned alternative: the lists stay hand-written and readable, and
 * drift becomes a failing build instead of a silent inaccuracy.
 * <p>
 * <strong>Two assertion strengths, chosen per surface.</strong> A bare enumeration is asserted by
 * <em>set equality</em>, plus the count the prose states in its own sentence — so appending an
 * extension to the list while leaving "21" untouched fails just as loudly as forgetting the list.
 * The mode set's per-mode documentation is asserted <em>structurally</em> instead, by requiring each
 * mode a table row of its own: the prose around it is free-form, so equality over it would be
 * brittle, but a row is something the document either has or has not. A bare-word containment check
 * would not do — {@code strict}, {@code lenient} and {@code minimal} are ordinary English
 * adjectives, so a sentence like "with minimal overhead" satisfies one while the mode it names has
 * no entry at all.
 * <p>
 * <strong>No vacuous pass.</strong> Every extraction is anchored on a literal sentence fragment held
 * in a named constant. When an anchor cannot be located, or locates an empty set, the test fails
 * naming both the document and the anchor rather than asserting over nothing. A guard that stops
 * matching after a rewrite must break loudly; one that quietly matches nothing is worse than absent.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Documented sets stay bound to their authoritative source")
class DocumentedSetsContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path CONFIGURATION_ADOC = repoRoot().resolve("doc/configuration.adoc");
    private static final Path USER_README_ADOC = repoRoot().resolve("doc/user/README.adoc");

    /** The bundled schema, read off the classpath so the assertion sees the shipped copy. */
    private static final String SCHEMA_RESOURCE = "/schema/gateway.schema.json";

    /**
     * Anchor for {@code doc/configuration.adoc}'s bare extension enumeration. The stated count
     * immediately precedes it ("…own 21-entry extension map"), and the parenthesised, backticked
     * list immediately follows.
     */
    private static final String CONFIG_EXTENSION_ANCHOR = "-entry extension map";

    /**
     * Anchor for {@code doc/user/README.adoc}'s bare extension enumeration ("The 21 built-in
     * extensions -- `html`, … -- always win"). The count precedes it, the backticked list runs from
     * the anchor to the closing {@code --}.
     */
    private static final String README_EXTENSION_ANCHOR = "built-in extensions --";

    /** Anchor for the schema's restatement, which lists the extensions bare inside parentheses. */
    private static final String SCHEMA_EXTENSION_ANCHOR = "an entry naming a built-in extension (";

    /**
     * Anchor for the mode enumeration in {@code doc/configuration.adoc}'s annotated skeleton. The
     * literal appears more than once in the document, so the match is narrowed to the occurrence
     * whose own line also carries a {@code #} comment containing the {@code |}-separated mode list.
     */
    private static final String CONFIG_PROFILE_ANCHOR = "profile: strict";

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @Test
    @DisplayName("doc/configuration.adoc enumerates exactly the built-in asset extensions, and states their count")
    void configurationAdocEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        int anchor = anchorIndex(document, CONFIG_EXTENSION_ANCHOR, CONFIGURATION_ADOC.toString());

        // Act
        Set<String> documented = backtickedTokens(
                parenthesised(document, anchor, CONFIGURATION_ADOC.toString(), CONFIG_EXTENSION_ANCHOR));
        int statedCount = statedCountBefore(document, anchor, CONFIGURATION_ADOC.toString(), CONFIG_EXTENSION_ANCHOR);

        // Assert
        assertExtensionsMatch(documented, statedCount, CONFIGURATION_ADOC.toString());
    }

    @Test
    @DisplayName("doc/user/README.adoc enumerates exactly the built-in asset extensions, and states their count")
    void userReadmeEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String document = read(USER_README_ADOC);
        int anchor = anchorIndex(document, README_EXTENSION_ANCHOR, USER_README_ADOC.toString());

        // Act
        int listStart = anchor + README_EXTENSION_ANCHOR.length();
        int listEnd = document.indexOf("--", listStart);
        if (listEnd < 0) {
            fail(USER_README_ADOC + ": the extension list after the anchor \"" + README_EXTENSION_ANCHOR
                    + "\" is not terminated by '--'; the anchor no longer describes the document and this"
                    + " guard would otherwise assert over the rest of the file");
        }
        Set<String> documented = backtickedTokens(document.substring(listStart, listEnd));
        int statedCount = statedCountBefore(document, anchor, USER_README_ADOC.toString(), README_EXTENSION_ANCHOR);

        // Assert
        assertExtensionsMatch(documented, statedCount, USER_README_ADOC.toString());
    }

    @Test
    @DisplayName("the bundled gateway schema enumerates exactly the built-in asset extensions")
    void gatewaySchemaEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String schema = readSchema();
        int anchor = anchorIndex(schema, SCHEMA_EXTENSION_ANCHOR, SCHEMA_RESOURCE);

        // Act — the schema lists the extensions bare (no backticks), comma separated
        int listStart = anchor + SCHEMA_EXTENSION_ANCHOR.length();
        int listEnd = schema.indexOf(')', listStart);
        if (listEnd < 0) {
            fail(SCHEMA_RESOURCE + ": the extension list opened by \"" + SCHEMA_EXTENSION_ANCHOR
                    + "\" is never closed; the anchor no longer describes the schema");
        }
        Set<String> documented = commaSeparatedTokens(schema.substring(listStart, listEnd));

        // Assert — the schema states no count of its own, so only the set is asserted
        assertFalse(documented.isEmpty(), SCHEMA_RESOURCE + ": anchor \"" + SCHEMA_EXTENSION_ANCHOR
                + "\" matched but yielded no extensions — the guard would pass vacuously");
        assertEquals(sorted(AssetResponseEnvelope.builtInExtensions()), sorted(documented),
                SCHEMA_RESOURCE + " restates the built-in extension map of"
                        + " AssetResponseEnvelope.CONTENT_TYPES and has drifted from it; correct the schema"
                        + " description (or the map, if the change was intended)");
    }

    @Test
    @DisplayName("doc/configuration.adoc's mode enumeration equals the SecurityProfile value set")
    void configurationAdocEnumeratesTheSecurityProfileModes() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        String modeComment = profileModeComment(document);

        // Act — count the RAW tokens alongside the set: the set collapses duplicates, so only the
        // raw count can observe a mode the document lists twice
        Set<String> documented = new LinkedHashSet<>();
        int listedTokens = 0;
        for (String token : modeComment.split("\\|")) {
            String mode = token.strip();
            if (!mode.isEmpty()) {
                documented.add(mode);
                listedTokens++;
            }
        }

        // Assert
        assertFalse(documented.isEmpty(), CONFIGURATION_ADOC + ": anchor \"" + CONFIG_PROFILE_ANCHOR
                + "\" matched but yielded no modes — the guard would pass vacuously");
        assertEquals(modeNames(), sorted(documented),
                CONFIGURATION_ADOC + " enumerates the security_defaults.profile mode set, which is"
                        + " authoritatively defined by SecurityProfile, and has drifted from it");
        assertEquals(SecurityProfile.values().length, listedTokens,
                CONFIGURATION_ADOC + " lists a different number of modes than SecurityProfile declares."
                        + " The count is taken over the raw '|'-separated tokens rather than over the"
                        + " de-duplicated set, so a mode listed twice fails here even though the set"
                        + " equality above still holds");
    }

    @Test
    @DisplayName("doc/configuration.adoc gives every SecurityProfile mode a definition row of its own")
    void configurationAdocDocumentsEverySecurityProfileMode() throws Exception {
        // Arrange — the per-mode text is free-form prose, so the row that introduces it is the
        // structural thing worth asserting
        String document = read(CONFIGURATION_ADOC);

        // Act + Assert
        for (SecurityProfile profile : SecurityProfile.values()) {
            String mode = profile.name().toLowerCase(Locale.ROOT);
            assertTrue(hasModeDefinitionRow(document, mode),
                    CONFIGURATION_ADOC + " has no definition row of its own for the mode '" + mode
                            + "' declared by SecurityProfile. The mode-set table must carry one cell"
                            + " holding exactly \"" + modeDefinitionRow(mode) + "\" per mode, so a newly"
                            + " added mode gets its entry and a removed one has its entry deleted");
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Asserts a documented extension enumeration against {@link AssetResponseEnvelope} — both the set
     * itself and the count the prose states alongside it, so a list and a count cannot drift apart.
     *
     * @param documented  the extensions extracted from the document
     * @param statedCount the count the document's own sentence claims
     * @param document    the document label used in every failure message
     */
    private static void assertExtensionsMatch(Set<String> documented, int statedCount, String document) {
        assertFalse(documented.isEmpty(), document + ": the anchor matched but yielded no extensions —"
                + " the guard would pass vacuously");
        assertEquals(sorted(AssetResponseEnvelope.builtInExtensions()), sorted(documented),
                document + " enumerates the built-in asset extensions, which are authoritatively"
                        + " defined by AssetResponseEnvelope.CONTENT_TYPES, and has drifted from them");
        assertEquals(AssetResponseEnvelope.builtInExtensions().size(), statedCount,
                document + " states a count that no longer matches"
                        + " AssetResponseEnvelope.CONTENT_TYPES; the list and the stated count must move"
                        + " together");
    }

    /**
     * One mode's own definition row in the mode-set table — an AsciiDoc cell holding nothing but the
     * backticked mode name.
     *
     * @param mode the lower-cased mode name
     * @return the exact row text the document must carry for that mode
     */
    private static String modeDefinitionRow(String mode) {
        return "| `" + mode + "`";
    }

    /**
     * Whether the document carries {@link #modeDefinitionRow(String)} as a line of its own. The
     * comparison is against the <em>stripped</em> line rather than against the raw document text, so
     * incidental indentation or trailing whitespace cannot decide whether a documented mode counts.
     *
     * @param document the configuration document
     * @param mode     the lower-cased mode name
     * @return {@code true} when some line of the document is exactly that row
     */
    private static boolean hasModeDefinitionRow(String document, String mode) {
        String row = modeDefinitionRow(mode);
        return document.lines().map(String::strip).anyMatch(row::equals);
    }

    /**
     * The {@code #}-comment carrying the {@code |}-separated mode list from the annotated skeleton.
     *
     * @param document the configuration document
     * @return the comment text, without the leading {@code #} and without any trailing parenthetical
     */
    private static String profileModeComment(String document) {
        int from = 0;
        while (true) {
            int anchor = document.indexOf(CONFIG_PROFILE_ANCHOR, from);
            if (anchor < 0) {
                return fail(CONFIGURATION_ADOC + ": no line carrying the anchor \"" + CONFIG_PROFILE_ANCHOR
                        + "\" also carries a '#' comment enumerating the modes with '|'; the anchor no"
                        + " longer describes the document, so this guard cannot assert anything");
            }
            int lineEnd = document.indexOf('\n', anchor);
            int end = lineEnd < 0 ? document.length() : lineEnd;
            int hash = document.indexOf('#', anchor);
            if (hash >= 0 && hash < end) {
                String comment = document.substring(hash + 1, end);
                int parenthesis = comment.indexOf('(');
                String modes = parenthesis < 0 ? comment : comment.substring(0, parenthesis);
                if (modes.indexOf('|') >= 0) {
                    return modes;
                }
            }
            from = anchor + CONFIG_PROFILE_ANCHOR.length();
        }
    }

    /**
     * Locates an anchor, failing with a message naming the document when it is absent.
     *
     * @param text     the document text
     * @param anchor   the literal anchor fragment
     * @param document the document label used in the failure message
     * @return the anchor's index
     */
    private static int anchorIndex(String text, String anchor, String document) {
        int index = text.indexOf(anchor);
        if (index < 0) {
            return fail(document + ": the anchor \"" + anchor + "\" is gone, so this contract guard no"
                    + " longer reaches the enumeration it protects. Restore the sentence, or update the"
                    + " anchor constant in DocumentedSetsContractTest to match the rewritten wording.");
        }
        return index;
    }

    /**
     * The integer immediately preceding an anchor — the count the document's own sentence states.
     *
     * @param text     the document text
     * @param anchor   the anchor's index
     * @param document the document label used in the failure message
     * @param label    the anchor fragment, named in the failure message
     * @return the stated count
     */
    private static int statedCountBefore(String text, int anchor, String document, String label) {
        int end = anchor;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return fail(document + ": no count precedes the anchor \"" + label + "\"; the sentence must"
                    + " state how many entries it enumerates so a list edit that forgets the count still"
                    + " fails");
        }
        return Integer.parseInt(text.substring(start, end));
    }

    /**
     * The text of the first parenthesised group following an index.
     *
     * @param text     the document text
     * @param from     the index to search from
     * @param document the document label used in the failure message
     * @param label    the anchor fragment, named in the failure message
     * @return the group's contents, without the surrounding parentheses
     */
    private static String parenthesised(String text, int from, String document, String label) {
        int open = text.indexOf('(', from);
        int close = open < 0 ? -1 : text.indexOf(')', open);
        if (open < 0 || close < 0) {
            return fail(document + ": no parenthesised list follows the anchor \"" + label
                    + "\"; the anchor no longer describes the document");
        }
        return text.substring(open + 1, close);
    }

    private static Set<String> backtickedTokens(String segment) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = BACKTICKED.matcher(segment);
        while (matcher.find()) {
            tokens.add(matcher.group(1).strip());
        }
        return tokens;
    }

    private static Set<String> commaSeparatedTokens(String segment) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : segment.split(",")) {
            String stripped = token.strip();
            if (!stripped.isEmpty()) {
                tokens.add(stripped);
            }
        }
        return tokens;
    }

    private static Set<String> modeNames() {
        Set<String> names = new TreeSet<>();
        for (SecurityProfile profile : SecurityProfile.values()) {
            names.add(profile.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static Set<String> sorted(Set<String> values) {
        return new TreeSet<>(values);
    }

    private static String read(Path document) throws IOException {
        return Files.readString(document);
    }

    private static String readSchema() throws IOException {
        try (InputStream in = DocumentedSetsContractTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                return fail("the bundled schema " + SCHEMA_RESOURCE + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The repository root — the nearest ancestor of the working directory that actually holds the
     * {@code doc/} tree these contracts assert against.
     * <p>
     * The search walks up rather than taking a fixed one-level hop because the working directory is
     * not the same everywhere: surefire runs with the module as its working directory, but an IDE
     * runner or an aggregator invocation may use another. Probing for {@code doc/} resolves the root
     * from a property of the tree instead of from an assumption about the runner, and a genuinely
     * unresolvable root then fails naming the directory it started from rather than surfacing later
     * as a {@code NoSuchFileException} on a path nobody asked for.
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("doc"))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it contains a doc/ directory");
    }
}
