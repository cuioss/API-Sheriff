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
package de.cuioss.sheriff.gateway.config.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the concern map of {@code doc/development/boot-validation-rules.adoc} to the rule list
 * {@code ConfigValidator} actually runs, so a rule registered without its row in the map becomes a red
 * build instead of a silently stale page.
 * <p>
 * <strong>Why this test exists.</strong> The concern map names, for every boot validation rule, the
 * concern class that owns it. It is written by hand, and nothing else ties it to
 * {@code DEFAULT_RULES}: a rule added to a concern class and registered in the list compiles, runs and
 * passes its own tests whether or not the map mentions it, and a rule moved between concern classes
 * leaves the map naming the old owner. A contributor follows the map to decide where a new rule goes,
 * so a stale map sends the next rule to the wrong class.
 * <p>
 * <strong>How it reads the list.</strong> {@code DEFAULT_RULES} is a private list of lambdas, and a
 * lambda does not name the method it calls, so reflection cannot recover the registrations. Widening
 * the class or the {@code ValidationRule} seam would change production code for a test, so the guard
 * reads {@code ConfigValidator.java} as text instead: it slices the {@code DEFAULT_RULES} block, splits
 * it into its top-level entries, and takes every {@code XxxRules.validateYyy} call and
 * {@code XxxRules::validateYyy} method reference in an entry as that entry's registration. The concern
 * map is read from the AsciiDoc table under the {@code == The concern map} heading, as a map from the
 * concern class in the first column to the backticked rule names in the second.
 * <p>
 * <strong>What it asserts.</strong>
 * <ul>
 * <li>Every entry of {@code DEFAULT_RULES} registers exactly one concern-class rule, so the number of
 * registrations equals the number of entries. An entry that calls no concern class — a rule body
 * written privately in {@code ConfigValidator} — fails here, and so does an entry that calls two.</li>
 * <li>Every registered rule is listed in the row of the concern class it is called through. The failure
 * names the rule and the class whose row lacks it, or says that the class has no row at all.</li>
 * </ul>
 * <p>
 * <strong>What it does not assert.</strong> The reverse direction: a row naming a rule that no
 * registration calls any longer is not detected. Nor the order of the list, which the map deliberately
 * does not record, nor the configuration-surface and state columns, which are prose. It reads source
 * text, not bytecode, so a registration routed through a helper method that is not itself a concern
 * class is not recognised as one — and fails the first assertion rather than passing unseen.
 * <p>
 * <strong>No vacuous pass.</strong> Both the parsed entry list and the parsed concern map are asserted
 * non-empty before anything is checked against them, and every anchor the extraction relies on fails
 * naming the file and the anchor when it is not found. Two controls drive synthetic specimens through
 * the same extraction helpers: one asserts that a rule missing from its row and a rule whose class has
 * no row are both reported while the listed rules are not, the other that an entry calling no concern
 * class and an entry calling two are both reported while a well-formed entry is not. Without them the
 * primary assertions would be green today and equally green if the detectors accepted everything.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The boot-validation concern map lists every rule DEFAULT_RULES registers")
class BootValidationConcernMapContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The validator whose rule list is the authoritative set, relative to the repository root. */
    private static final String CONFIG_VALIDATOR_SOURCE =
            "api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java";

    /** The page carrying the hand-written concern map, relative to the repository root. */
    private static final String CONCERN_MAP_DOC = "doc/development/boot-validation-rules.adoc";

    /** Opens the rule list; the parenthesis it ends with is the one the list's own closing one matches. */
    private static final String RULE_LIST_ANCHOR = "DEFAULT_RULES = List.of(";

    /** The section heading the concern-map table sits under. */
    private static final String CONCERN_MAP_HEADING = "== The concern map";

    /** The AsciiDoc delimiter opening and closing a table. */
    private static final String TABLE_DELIMITER = "|===";

    /** The AsciiDoc cell separator; the concern map's cells contain no other pipe. */
    private static final String CELL_SEPARATOR = "|";

    /** The first header cell, proving the table read is the concern map and not another one. */
    private static final String CONCERN_CLASS_HEADER = "Concern class";

    /** The column holding the concern class. */
    private static final int CONCERN_CLASS_COLUMN = 0;

    /** The column holding the backticked rule names. */
    private static final int RULES_COLUMN = 1;

    /**
     * One registration: a concern class named {@code XxxRules}, followed by a call ({@code .}) or a
     * method reference ({@code ::}) to one of its {@code validateXxx} methods.
     */
    private static final Pattern REGISTRATION =
            Pattern.compile("\\b([A-Z]\\w*Rules)\\s*+(?:\\.|::)\\s*+(validate\\w+)\\b");

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /**
     * A synthetic rule list for the map control: two rules listed in their row, one whose class has a
     * row that does not list it, and one whose class has no row at all.
     */
    private static final String MAP_CONTROL_SOURCE = """
            private static final List<ValidationRule> DEFAULT_RULES = List.of(
                    (gateway, endpoints, topology, errors) -> AlphaRules.validateListed(gateway, errors),
                    AlphaRules::validateAlsoListed,
                    (gateway, endpoints, topology, errors) -> BetaRules.validateUnlisted(endpoints,
                            errors),
                    (gateway, endpoints, topology, errors) -> GammaRules.validateRowless(gateway, errors));
            """;

    /** The synthetic concern map the map control reads; see {@link #MAP_CONTROL_SOURCE}. */
    private static final String MAP_CONTROL_DOC = """
            == The concern map

            [cols="2,4,3,2"]
            |===
            | Concern class | Rules | Configuration surface | State

            | `AlphaRules`
            | `validateListed`,
              `validateAlsoListed`
            | `alpha.*`
            | extracted

            | `BetaRules`
            | `validateSomethingElse`
            | `beta.*`
            | extracted
            |===
            """;

    /**
     * A synthetic rule list for the entry control: a well-formed entry, an entry whose rule body is
     * private to the validator, and a block lambda registering two concern-class rules in one entry.
     */
    private static final String ENTRY_CONTROL_SOURCE = """
            private static final List<ValidationRule> DEFAULT_RULES = List.of(
                    (gateway, endpoints, topology, errors) -> AlphaRules.validateListed(gateway, errors),
                    (gateway, endpoints, topology, errors) -> validatePrivately(gateway, errors),
                    (gateway, endpoints, topology, errors) -> {
                        AlphaRules.validateListed(gateway, errors);
                        BetaRules.validateUnlisted(endpoints, errors);
                    });
            """;

    @Test
    @DisplayName("Every DEFAULT_RULES entry registers exactly one concern-class rule")
    void everyEntryRegistersExactlyOneConcernClassRule() throws Exception {
        List<String> entries = topLevelEntries(
                ruleListBlock(read(CONFIG_VALIDATOR_SOURCE), CONFIG_VALIDATOR_SOURCE));

        List<Integer> offending = entriesWithoutExactlyOneRegistration(entries);
        int registrations = registrationsOf(entries).size();

        assertFalse(entries.isEmpty(), CONFIG_VALIDATOR_SOURCE + ": the anchor \"" + RULE_LIST_ANCHOR
                + "\" opened a rule list with no entries, so every check below would assert over nothing");
        assertAll("one concern-class registration per DEFAULT_RULES entry",
                () -> assertTrue(offending.isEmpty(), () -> CONFIG_VALIDATOR_SOURCE + ": these DEFAULT_RULES"
                        + " entries do not register exactly one concern-class rule. Every rule body lives in a"
                        + " concern class of the rule package and is registered through it, one entry per rule,"
                        + " so an entry calling no concern class carries a private rule and an entry calling two"
                        + " merges two rules into one: " + describeEntries(entries, offending)),
                () -> assertEquals(entries.size(), registrations, CONFIG_VALIDATOR_SOURCE + ": DEFAULT_RULES"
                        + " holds " + entries.size() + " entries but " + registrations
                        + " concern-class registrations"));
    }

    @Test
    @DisplayName("Every registered rule is listed in the concern-map row of the class it is called through")
    void everyRegisteredRuleIsListedInItsConcernClassRow() throws Exception {
        List<Registration> registrations = registrationsOf(
                topLevelEntries(ruleListBlock(read(CONFIG_VALIDATOR_SOURCE), CONFIG_VALIDATOR_SOURCE)));
        Map<String, Set<String>> concernMap = concernMap(read(CONCERN_MAP_DOC), CONCERN_MAP_DOC);

        List<Registration> unmapped = unmappedRegistrations(registrations, concernMap);

        assertFalse(registrations.isEmpty(), CONFIG_VALIDATOR_SOURCE + ": no concern-class registration was"
                + " found in DEFAULT_RULES, so this guard would assert over nothing");
        assertFalse(concernMap.isEmpty(), CONCERN_MAP_DOC + ": the concern-map table under \""
                + CONCERN_MAP_HEADING + "\" has no rows, so this guard would assert over nothing");
        assertTrue(unmapped.isEmpty(), () -> CONCERN_MAP_DOC + " no longer lists every rule DEFAULT_RULES"
                + " registers. The concern map is written by hand and tells a contributor which class owns"
                + " which rule, so add each rule below to the row of its concern class (or add the row): "
                + describeMisses(unmapped, concernMap));
    }

    /**
     * Matched control over the map detector. Without it the assertion above is green today because the
     * map is complete, and it would be equally green if {@link #unmappedRegistrations} reported nothing.
     */
    @Test
    @DisplayName("The map detector reports a rule missing from its row and a class with no row, and nothing else")
    void mapDetectorReportsExactlyTheUnlistedRules() {
        List<Registration> registrations = registrationsOf(
                topLevelEntries(ruleListBlock(MAP_CONTROL_SOURCE, "the map control")));
        Map<String, Set<String>> concernMap = concernMap(MAP_CONTROL_DOC, "the map control");

        List<Registration> unmapped = unmappedRegistrations(registrations, concernMap);

        assertAll("map control",
                () -> assertEquals(4, registrations.size(),
                        "the extraction did not find the four registrations of the synthetic rule list, among"
                                + " them one method reference and one call wrapped across two lines: "
                                + registrations),
                () -> assertEquals(Set.of("AlphaRules", "BetaRules"), concernMap.keySet(),
                        "the table reader did not recover the two rows of the synthetic concern map"),
                () -> assertEquals(List.of(new Registration("BetaRules", "validateUnlisted"),
                                new Registration("GammaRules", "validateRowless")), unmapped,
                        "the detector must report exactly the rule its row does not list and the rule whose"
                                + " class has no row, and must accept both rules the AlphaRules row lists,"
                                + " including the one written on a continuation line of the cell"));
    }

    /**
     * Matched control over the entry detector. Without it the per-entry assertion above would be green
     * if {@link #entriesWithoutExactlyOneRegistration} accepted every entry. The specimen also shows why
     * the count equality alone is not enough: its three entries carry three registrations in total.
     */
    @Test
    @DisplayName("The entry detector reports an entry calling no concern class and one calling two, and nothing else")
    void entryDetectorReportsEntriesWithoutExactlyOneRegistration() {
        List<String> entries = topLevelEntries(ruleListBlock(ENTRY_CONTROL_SOURCE, "the entry control"));

        List<Integer> offending = entriesWithoutExactlyOneRegistration(entries);

        assertAll("entry control",
                () -> assertEquals(3, entries.size(),
                        "the splitter did not recover the three top-level entries of the synthetic rule list,"
                                + " whose lambda parameter lists and block body carry commas of their own: "
                                + entries),
                () -> assertEquals(List.of(1, 2), offending,
                        "the detector must report the entry with a private rule body and the entry registering"
                                + " two rules, and must accept the well-formed first entry"),
                () -> assertEquals(entries.size(), registrationsOf(entries).size(),
                        "the specimen is built so that the total count matches while two entries are wrong"));
    }

    // --- extraction helpers ----------------------------------------------------------------------

    /**
     * One rule as {@code DEFAULT_RULES} registers it: the concern class it is called through and the
     * rule method's name.
     *
     * @param concernClass the simple name of the concern class
     * @param rule         the name of the {@code validateXxx} method
     */
    record Registration(String concernClass, String rule) {

        @Override
        public String toString() {
            return concernClass + "." + rule;
        }
    }

    /**
     * The text between the parentheses of {@code List.of(...)} that {@link #RULE_LIST_ANCHOR} opens.
     *
     * @param source the Java source to read
     * @param label  names the source in a failure message
     * @return the rule list's contents, without the enclosing parentheses
     */
    private static String ruleListBlock(String source, String label) {
        int anchor = source.indexOf(RULE_LIST_ANCHOR);
        if (anchor < 0) {
            return fail(label + ": the anchor \"" + RULE_LIST_ANCHOR + "\" is not found, so the rule list"
                    + " this contract reads cannot be located. Update the anchor if the list was renamed");
        }
        int open = anchor + RULE_LIST_ANCHOR.length() - 1;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        return fail(label + ": the parenthesis opened by \"" + RULE_LIST_ANCHOR + "\" is never closed");
    }

    /**
     * Splits a rule list into its entries at the commas that sit outside every parenthesis and brace,
     * so a lambda's parameter list, a call's arguments and a block body stay inside their entry.
     *
     * @param block the rule list's contents
     * @return the entries, stripped, in list order
     */
    private static List<String> topLevelEntries(String block) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                addEntry(entries, block.substring(start, i));
                start = i + 1;
            }
        }
        addEntry(entries, block.substring(start));
        return entries;
    }

    private static void addEntry(List<String> entries, String raw) {
        String entry = raw.strip();
        if (!entry.isEmpty()) {
            entries.add(entry);
        }
    }

    private static List<Registration> registrationsOf(String entry) {
        List<Registration> registrations = new ArrayList<>();
        Matcher matcher = REGISTRATION.matcher(entry);
        while (matcher.find()) {
            registrations.add(new Registration(matcher.group(1), matcher.group(2)));
        }
        return registrations;
    }

    private static List<Registration> registrationsOf(List<String> entries) {
        List<Registration> registrations = new ArrayList<>();
        for (String entry : entries) {
            registrations.addAll(registrationsOf(entry));
        }
        return registrations;
    }

    /**
     * The zero-based positions of the entries that do not register exactly one concern-class rule.
     *
     * @param entries the rule list's entries
     * @return the offending positions, in list order; empty when every entry registers one rule
     */
    private static List<Integer> entriesWithoutExactlyOneRegistration(List<String> entries) {
        List<Integer> offending = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (registrationsOf(entries.get(i)).size() != 1) {
                offending.add(i);
            }
        }
        return offending;
    }

    /**
     * The concern map read from the AsciiDoc table under {@link #CONCERN_MAP_HEADING}: each concern class
     * of the first column mapped to the backticked rule names of the second. The column count is taken
     * from the header line, and a rule cell may continue over several lines.
     *
     * @param document the AsciiDoc page
     * @param label    names the page in a failure message
     * @return the concern classes in table order, each with its listed rules
     */
    private static Map<String, Set<String>> concernMap(String document, String label) {
        String body = tableBody(document, label);
        String header = body.strip().lines().findFirst().orElse("");
        int columns = (int) header.chars().filter(c -> c == CELL_SEPARATOR.charAt(0)).count();
        List<String> cells = new ArrayList<>(List.of(body.split(Pattern.quote(CELL_SEPARATOR), -1)));
        if (!cells.removeFirst().isBlank()) {
            fail(label + ": the concern-map table carries text before its first cell");
        }
        if (columns < 2 || cells.size() % columns != 0
                || !CONCERN_CLASS_HEADER.equals(cells.get(CONCERN_CLASS_COLUMN).strip())) {
            fail(label + ": the table under \"" + CONCERN_MAP_HEADING + "\" does not have the expected shape:"
                    + " a header line opening with \"" + CONCERN_CLASS_HEADER + "\" and rows of " + columns
                    + " cells. Cells read: " + cells.size());
        }
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int row = columns; row < cells.size(); row += columns) {
            List<String> concern = backticked(cells.get(row + CONCERN_CLASS_COLUMN));
            if (concern.size() != 1) {
                fail(label + ": a concern-map row does not name exactly one concern class in its first cell: "
                        + cells.get(row + CONCERN_CLASS_COLUMN).strip());
            }
            Set<String> rules = new LinkedHashSet<>(backticked(cells.get(row + RULES_COLUMN)));
            if (map.putIfAbsent(concern.getFirst(), rules) != null) {
                fail(label + ": the concern map carries two rows for " + concern.getFirst()
                        + ", so a rule listed in only one of them would be judged by whichever was read last");
            }
        }
        return map;
    }

    private static String tableBody(String document, String label) {
        int heading = document.indexOf(CONCERN_MAP_HEADING);
        if (heading < 0) {
            return fail(label + ": the heading \"" + CONCERN_MAP_HEADING + "\" is not found, so the concern map"
                    + " this contract reads cannot be located");
        }
        int open = document.indexOf(TABLE_DELIMITER, heading);
        if (open < 0) {
            return fail(label + ": no table follows the heading \"" + CONCERN_MAP_HEADING + "\"");
        }
        int bodyStart = open + TABLE_DELIMITER.length();
        int close = document.indexOf(TABLE_DELIMITER, bodyStart);
        if (close < 0) {
            return fail(label + ": the concern-map table opened under \"" + CONCERN_MAP_HEADING
                    + "\" is never closed");
        }
        return document.substring(bodyStart, close);
    }

    private static List<String> backticked(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = BACKTICKED.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group(1).strip());
        }
        return tokens;
    }

    /**
     * The registrations whose rule is not listed in the row of the concern class they are called
     * through — either because that row omits the rule or because the class has no row.
     *
     * @param registrations the registrations read from the rule list
     * @param concernMap    the concern map read from the page
     * @return the unlisted registrations, in list order; empty when the map lists every one
     */
    private static List<Registration> unmappedRegistrations(List<Registration> registrations,
            Map<String, Set<String>> concernMap) {
        List<Registration> unmapped = new ArrayList<>();
        for (Registration registration : registrations) {
            Set<String> row = concernMap.get(registration.concernClass());
            if (row == null || !row.contains(registration.rule())) {
                unmapped.add(registration);
            }
        }
        return unmapped;
    }

    private static String describeMisses(List<Registration> unmapped, Map<String, Set<String>> concernMap) {
        List<String> lines = new ArrayList<>();
        for (Registration registration : unmapped) {
            if (concernMap.containsKey(registration.concernClass())) {
                lines.add("rule " + registration.rule() + " is missing from the row of "
                        + registration.concernClass());
            } else {
                lines.add("rule " + registration.rule() + " is registered through "
                        + registration.concernClass() + ", which has no row in the concern map");
            }
        }
        return String.join("; ", lines);
    }

    private static String describeEntries(List<String> entries, List<Integer> positions) {
        List<String> lines = new ArrayList<>();
        for (int position : positions) {
            lines.add("entry " + (position + 1) + " registers " + registrationsOf(entries.get(position)).size()
                    + ": " + entries.get(position).replaceAll("\\s+", " "));
        }
        return String.join("; ", lines);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repoRoot().resolve(relativePath));
    }

    /**
     * The repository root — the nearest ancestor of the working directory that holds both files this
     * contract reads. Probing for them rather than taking a fixed hop from the module keeps the guard
     * independent of the directory a runner starts in, and an unresolvable root fails naming that
     * directory.
     *
     * @return the repository root
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(CONFIG_VALIDATOR_SOURCE))
                    && Files.isRegularFile(candidate.resolve(CONCERN_MAP_DOC))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds both " + CONFIG_VALIDATOR_SOURCE + " and " + CONCERN_MAP_DOC);
    }
}
