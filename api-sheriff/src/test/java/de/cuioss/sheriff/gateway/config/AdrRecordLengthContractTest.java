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
package de.cuioss.sheriff.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.testsupport.RepositoryRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build when the body of a decision record in {@code doc/adr} is longer than
 * {@value #MAX_BODY_LINES} lines.
 * <p>
 * <strong>Why this test exists.</strong> A decision record has one job: to state one decision, the
 * alternatives that lost and why they lost. Operator how-to, configuration examples, implementation
 * narrative and reproduction evidence have their own pages, and a record that collects them grows
 * until the decision is hard to find in it. Nothing in the build notices that growth — every record
 * renders, however long — so the ceiling the authoring guide sets would hold only as long as every
 * author remembered it. This test makes the ceiling a build failure instead.
 * <p>
 * <strong>What is measured.</strong> A record's body is every line after its <em>meta block</em>,
 * to the end of the file. The meta block is line 1 through the last line before the first level-2
 * heading (a line starting with {@code == }) that follows the {@code == Status} heading: the title
 * line, the document attributes, the metadata and authoring comment blocks and the status section.
 * Lines are counted as {@link Files#readAllLines(Path)} reads them, so blank lines count and a
 * trailing newline adds no line. The definition is the one in {@code doc/development/adr-writing.adoc}
 * (section "Meta Block and the Length Ceiling"), which is the authority for the rule and for how a
 * record that cannot fit is split.
 * <p>
 * <strong>A property, not a membership list.</strong> No ordinal and no filename is written into
 * this test, so adding a record needs no edit here, and the test cannot go stale against the
 * directory it reads.
 * <p>
 * <strong>What this guard does NOT assert.</strong> It counts lines only. It does not check heading
 * depth or the order and names of the body sections, it does not bound the length of the meta block,
 * and it says nothing about prose quality — whether a record follows the writing rule, uses the term
 * list, or keeps every rejected alternative is a review matter, not a property this test can read.
 * <p>
 * <strong>No vacuous pass.</strong> Before the ceiling assertion the test requires that at least one
 * record was listed, so an unresolved or empty directory cannot pass by iterating over nothing, and
 * that every listed record carries a {@code == Status} heading followed by another level-2 heading,
 * because a record whose meta block the detector cannot delimit has no measurable body and would
 * otherwise be skipped. Matched controls drive the same detector over a temporary directory — one
 * record one line over the ceiling, one exactly at it, and two records it cannot measure — so the
 * primary assertion cannot be green merely because the detector flags nothing or measures nothing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Every decision record in doc/adr keeps its body within the length ceiling")
class AdrRecordLengthContractTest {

    /**
     * The repository-root marker, and the directory this contract asserts over. It is tracked in
     * git, so it resolves in a fresh checkout exactly as it does locally.
     */
    private static final String ADR_DIRECTORY = "doc/adr";

    /** The file extension every decision record carries. */
    private static final String RECORD_SUFFIX = ".adoc";

    /** The longest body a decision record may have, in lines. */
    private static final int MAX_BODY_LINES = 500;

    /** The heading that opens the status section, the last section of the meta block. */
    private static final String STATUS_HEADING = "== Status";

    /**
     * The prefix of a level-2 heading. A level-3 heading ({@code === }) does not match, because its
     * third character is {@code =} where this prefix requires a space.
     */
    private static final String LEVEL_TWO_PREFIX = "== ";

    private static final String CONTROL_OVERSIZED = "0001-one_line_over_the_ceiling.adoc";
    private static final String CONTROL_AT_CEILING = "0002-exactly_at_the_ceiling.adoc";
    private static final String CONTROL_NO_STATUS = "0003-no_status_heading.adoc";
    private static final String CONTROL_NOTHING_AFTER_STATUS = "0004-no_heading_after_status.adoc";

    @Test
    @DisplayName("No decision record in doc/adr has a body longer than the ceiling")
    void noDecisionRecordExceedsTheBodyCeiling() throws Exception {
        Path adrDirectory = RepositoryRoot.holding(ADR_DIRECTORY).resolve(ADR_DIRECTORY);

        BodyMeasures measures = bodyMeasures(adrDirectory);

        // The two vacuity guards run BEFORE the ceiling assertion: an empty listing, or a record whose
        // body the detector could not delimit, would otherwise let it pass over less than the
        // directory holds
        assertFalse(measures.records().isEmpty(),
                adrDirectory + " lists no " + RECORD_SUFFIX + " file, so this guard would assert over nothing."
                        + " Either the decision records moved or the directory resolved to the wrong place —"
                        + " point this contract at where the records live rather than relaxing this check");
        assertEquals(List.of(), measures.unmeasurable(),
                adrDirectory + " holds records with no '" + STATUS_HEADING + "' heading, or with no level-2"
                        + " heading after it. The detector cannot tell where such a record's meta block ends,"
                        + " so it cannot measure the body and the record is invisible to the ceiling check"
                        + " below. Give the record the skeleton of doc/development/adr-writing.adoc");
        Map<String, Integer> overCeiling = measures.overCeiling();
        assertTrue(overCeiling.isEmpty(), () -> overCeilingMessage(adrDirectory, overCeiling));
    }

    /**
     * Matched positive/negative control over the ceiling comparison. Without it the assertion above
     * is unfalsifiable by inspection: it is green whenever every record fits, and it would be equally
     * green if {@link #bodyMeasures(Path)} never flagged a record at all. The pair sits on either
     * side of the boundary, so an off-by-one in the comparison or in the meta-block delimitation
     * fails one of the two assertions.
     *
     * @param fixture a temporary directory, so no fixture file is ever written under {@code doc/adr}
     * @throws Exception when a fixture file cannot be written or the directory cannot be listed
     */
    @Test
    @DisplayName("The detector flags a body one line over the ceiling and not one exactly at it")
    void detectorFlagsAnOversizedRecordAndNotOneAtTheCeiling(@TempDir Path fixture) throws Exception {
        Files.write(fixture.resolve(CONTROL_OVERSIZED), recordWithBody(MAX_BODY_LINES + 1), StandardCharsets.UTF_8);
        Files.write(fixture.resolve(CONTROL_AT_CEILING), recordWithBody(MAX_BODY_LINES), StandardCharsets.UTF_8);

        BodyMeasures measures = bodyMeasures(fixture);

        assertEquals(Map.of(CONTROL_OVERSIZED, MAX_BODY_LINES + 1, CONTROL_AT_CEILING, MAX_BODY_LINES),
                measures.bodyLines(),
                "the detector did not measure the fixture bodies at exactly " + (MAX_BODY_LINES + 1) + " and "
                        + MAX_BODY_LINES + " lines, so it delimits the meta block differently from"
                        + " doc/development/adr-writing.adoc and the guard above measures the wrong thing");
        assertEquals(Map.of(CONTROL_OVERSIZED, MAX_BODY_LINES + 1), measures.overCeiling(),
                "the detector must flag the record one line over the ceiling and not the one exactly at it."
                        + " A detector that flags neither cannot fail the guard above on a real overrun; one that"
                        + " flags both fails it for a record the rule allows. Flagged: " + measures.overCeiling());
    }

    /**
     * Control over the vacuity guard. A record with no {@code == Status} heading, and a record whose
     * status section is the last section, must both be reported as unmeasurable rather than measured
     * or silently dropped — otherwise the guard above could never fail and a malformed record would
     * escape the ceiling.
     *
     * @param fixture a temporary directory, so no fixture file is ever written under {@code doc/adr}
     * @throws Exception when a fixture file cannot be written or the directory cannot be listed
     */
    @Test
    @DisplayName("The detector reports a record whose meta block it cannot delimit as unmeasurable")
    void detectorReportsARecordItCannotMeasure(@TempDir Path fixture) throws Exception {
        Files.write(fixture.resolve(CONTROL_NO_STATUS),
                List.of("= ADR-0003: No status heading", "", "== Context", "", "Body line."),
                StandardCharsets.UTF_8);
        Files.write(fixture.resolve(CONTROL_NOTHING_AFTER_STATUS),
                List.of("= ADR-0004: Nothing after status", "", STATUS_HEADING, "", "Proposed", "",
                        "=== Not a level-2 heading", "", "Body line."),
                StandardCharsets.UTF_8);

        BodyMeasures measures = bodyMeasures(fixture);

        assertEquals(List.of(CONTROL_NO_STATUS, CONTROL_NOTHING_AFTER_STATUS), measures.unmeasurable(),
                "the detector did not report both records it cannot delimit as unmeasurable, so the vacuity"
                        + " guard above cannot fail on a malformed record. Reported: " + measures.unmeasurable());
        assertTrue(measures.bodyLines().isEmpty(),
                "the detector measured a body for a record whose meta block has no end: " + measures.bodyLines());
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * What one directory listing says about record body lengths.
     *
     * @param records      every listed record filename, sorted
     * @param unmeasurable the listed filenames whose meta block the detector cannot delimit, sorted
     * @param bodyLines    each measurable record's body length in lines, keyed and sorted by filename,
     *                     so a message built from it is stable across file systems
     */
    private record BodyMeasures(List<String> records, List<String> unmeasurable, Map<String, Integer> bodyLines) {

        /**
         * @return the records whose body is longer than {@code MAX_BODY_LINES}, with their body
         *         length, sorted by filename
         */
        Map<String, Integer> overCeiling() {
            Map<String, Integer> offenders = new TreeMap<>(bodyLines);
            offenders.values().removeIf(lines -> lines <= MAX_BODY_LINES);
            return offenders;
        }
    }

    /**
     * Lists the regular files directly in {@code directory} whose name ends in
     * {@link #RECORD_SUFFIX} and measures each one's body. This is the detection all three tests
     * share: the primary test points it at {@code doc/adr}, the controls at a temporary fixture.
     * <p>
     * The listing is not recursive, and a directory whose name happens to end in the suffix is not a
     * record.
     *
     * @param directory the directory holding the records
     * @return the listed records, those that cannot be measured, and each measured body length
     * @throws IOException when the directory cannot be listed or a record cannot be read
     */
    private static BodyMeasures bodyMeasures(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.filter(Files::isRegularFile)
                    .filter(entry -> entry.getFileName().toString().endsWith(RECORD_SUFFIX))
                    .sorted()
                    .toList();
        }
        List<String> records = new ArrayList<>();
        List<String> unmeasurable = new ArrayList<>();
        Map<String, Integer> bodyLines = new TreeMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            records.add(name);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            OptionalInt metaBlock = metaBlockLength(lines);
            if (metaBlock.isPresent()) {
                bodyLines.put(name, lines.size() - metaBlock.getAsInt());
            } else {
                unmeasurable.add(name);
            }
        }
        return new BodyMeasures(List.copyOf(records), List.copyOf(unmeasurable), bodyLines);
    }

    /**
     * The length of a record's meta block: the number of lines before the first level-2 heading
     * that follows the {@code == Status} heading.
     *
     * @param lines the record's lines
     * @return the meta-block length, or empty when the record has no {@code == Status} heading or
     *         no level-2 heading after it
     */
    private static OptionalInt metaBlockLength(List<String> lines) {
        boolean statusSeen = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!statusSeen) {
                statusSeen = STATUS_HEADING.equals(line.stripTrailing());
            } else if (line.startsWith(LEVEL_TWO_PREFIX)) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * A fixture record in the shape of the skeleton: a title line, the status section, then a body
     * of exactly {@code bodyLines} lines opened by a level-2 heading.
     *
     * @param bodyLines the body length the fixture must measure at
     * @return the record's lines
     */
    private static List<String> recordWithBody(int bodyLines) {
        List<String> lines = new ArrayList<>(List.of("= ADR-0001: Fixture record", "", STATUS_HEADING, "",
                "Proposed", "", "== Decision"));
        IntStream.range(1, bodyLines).mapToObj(index -> "Body line " + index + ".").forEach(lines::add);
        return lines;
    }

    /**
     * The failure message for a directory in which some record body is longer than the ceiling. It
     * names each offending record with its body length, and says how an overrun is repaired.
     *
     * @param adrDirectory the directory the records were listed from
     * @param overCeiling  the records over the ceiling, with their body length
     * @return the assertion message
     */
    private static String overCeilingMessage(Path adrDirectory, Map<String, Integer> overCeiling) {
        String offenders = overCeiling.entrySet().stream()
                .map(entry -> entry.getKey() + " has " + entry.getValue() + " body lines")
                .collect(Collectors.joining("; "));
        return adrDirectory + ": " + overCeiling.size() + " decision record(s) have a body longer than "
                + MAX_BODY_LINES + " lines — " + offenders + ". Move content that is not decision, rejected"
                + " alternatives, consequences or links to the page doc/development/adr-writing.adoc names for"
                + " it, and split a record that still cannot fit into a new record at the next free ordinal."
                + " Never summarise away a rejected alternative. Do NOT delete this test or raise the ceiling"
                + " to make the build green.";
    }
}
