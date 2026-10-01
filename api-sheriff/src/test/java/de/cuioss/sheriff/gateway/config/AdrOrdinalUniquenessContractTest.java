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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build when two decision records in {@code doc/adr} claim the same four-digit ordinal.
 * <p>
 * <strong>Why this test exists.</strong> A decision record is addressed by its ordinal: the
 * {@code NNNN-} filename prefix, the {@code ADR-NNNN} title line, and every {@code ADR-NNNN} mention
 * in the documentation and in code comments. Nothing hands the ordinals out. An author takes the next
 * free number by looking at the directory, so two records written on parallel branches can each take
 * the same one, and both land without a conflict because the filenames differ after the prefix. From
 * then on {@code ADR-NNNN} names two decisions, and a reader following a plain-text reference cannot
 * tell which one is meant. The collision has no local symptom — both files render and every link
 * that spells out a full filename still resolves — which is the shape a contract test exists for.
 * <p>
 * <strong>A property, not a membership list.</strong> The assertion is that no ordinal is claimed
 * twice. No ordinal and no filename is written into this test, so adding a record needs no edit
 * here, and the test cannot go stale against the directory it reads.
 * <p>
 * <strong>What this guard does NOT assert.</strong> It reads filenames only. Whether the ordinal in
 * a record's {@code = ADR-NNNN:} title line agrees with its filename, whether the ordinals are
 * contiguous, and whether a reference elsewhere names a record that exists are separate properties
 * and are not checked here.
 * <p>
 * <strong>No vacuous pass.</strong> Before the uniqueness assertion the test requires that at least
 * one record was listed, so an unresolved or empty directory cannot pass by iterating over nothing,
 * and that every listed filename carries a four-digit ordinal prefix, because a name the detector
 * cannot read an ordinal from would be invisible to it. A matched positive/negative control drives
 * the same detector over a temporary directory — two files sharing one ordinal, one file with an
 * ordinal of its own — so the primary assertion cannot be green merely because the detector reports
 * nothing, nor the control green merely because it reports everything.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Every decision record in doc/adr claims an ordinal of its own")
class AdrOrdinalUniquenessContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /**
     * The repository-root marker, and the directory this contract asserts over. It is tracked in
     * git, so it resolves in a fresh checkout exactly as it does locally.
     */
    private static final String ADR_DIRECTORY = "doc/adr";

    /** The file extension every decision record carries. */
    private static final String RECORD_SUFFIX = ".adoc";

    /**
     * A record filename's ordinal: exactly four digits, then the hyphen that opens the title slug. A
     * five-digit prefix does not match, because its fifth digit sits where the hyphen is required.
     */
    private static final Pattern ORDINAL_PREFIX = Pattern.compile("^(\\d{4})-");

    private static final String CONTROL_SHARED_ORDINAL = "0007";
    private static final String CONTROL_DISTINCT_ORDINAL = "0008";
    private static final String CONTROL_FIRST_CLAIMANT = CONTROL_SHARED_ORDINAL + "-first_claimant.adoc";
    private static final String CONTROL_SECOND_CLAIMANT = CONTROL_SHARED_ORDINAL + "-second_claimant.adoc";
    private static final String CONTROL_SOLE_CLAIMANT = CONTROL_DISTINCT_ORDINAL + "-sole_claimant.adoc";

    @Test
    @DisplayName("No ordinal in doc/adr is claimed by two decision records")
    void noOrdinalIsClaimedByTwoDecisionRecords() throws Exception {
        Path adrDirectory = repoRoot().resolve(ADR_DIRECTORY);

        OrdinalClaims claims = ordinalClaims(adrDirectory);

        // The two vacuity guards run BEFORE the uniqueness assertion: an empty listing, or a record
        // the detector could not read an ordinal from, would otherwise let it pass over less than
        // the directory holds
        assertFalse(claims.records().isEmpty(),
                adrDirectory + " lists no " + RECORD_SUFFIX + " file, so this guard would assert over nothing."
                        + " Either the decision records moved or the directory resolved to the wrong place —"
                        + " point this contract at where the records live rather than relaxing this check");
        assertEquals(List.of(), claims.withoutOrdinal(),
                adrDirectory + " holds " + RECORD_SUFFIX + " files whose name does not start with a four-digit"
                        + " ordinal and a hyphen (NNNN-…). The detector cannot read an ordinal from such a name,"
                        + " so the record is invisible to the uniqueness check below. Rename it to carry its"
                        + " ordinal, or move it out of the decision-record directory");
        assertTrue(claims.claimedTwice().isEmpty(), () -> duplicateOrdinalMessage(adrDirectory, claims));
    }

    /**
     * Matched positive/negative control over the detector itself. Without it the assertion above is
     * unfalsifiable by inspection: it is green whenever the directory is collision-free, and it
     * would be equally green if {@link #ordinalClaims(Path)} never reported a shared ordinal at all.
     *
     * @param fixture a temporary directory, so no fixture file is ever written under {@code doc/adr}
     * @throws IOException when a fixture file cannot be created or the directory cannot be listed
     */
    @Test
    @DisplayName("The detector reports an ordinal two files share and not one a single file claims")
    void detectorReportsASharedOrdinalAndNotADistinctOne(@TempDir Path fixture) throws Exception {
        Files.createFile(fixture.resolve(CONTROL_FIRST_CLAIMANT));
        Files.createFile(fixture.resolve(CONTROL_SECOND_CLAIMANT));
        Files.createFile(fixture.resolve(CONTROL_SOLE_CLAIMANT));

        OrdinalClaims claims = ordinalClaims(fixture);

        assertEquals(List.of(CONTROL_FIRST_CLAIMANT, CONTROL_SECOND_CLAIMANT),
                claims.claimedTwice().get(CONTROL_SHARED_ORDINAL),
                "the detector did not report the ordinal " + CONTROL_SHARED_ORDINAL + " with both files"
                        + " claiming it, so the guard above cannot fail on a real collision and proves nothing."
                        + " Reported: " + claims.claimedTwice());
        assertFalse(claims.claimedTwice().containsKey(CONTROL_DISTINCT_ORDINAL),
                "the detector reported the ordinal " + CONTROL_DISTINCT_ORDINAL + ", which a single file"
                        + " claims. A detector that reports every ordinal would fail the guard above for a"
                        + " reason that has nothing to do with the directory. Reported: " + claims.claimedTwice());
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * What one directory listing says about ordinal ownership.
     *
     * @param records        every listed record filename, sorted
     * @param withoutOrdinal the listed filenames carrying no four-digit ordinal prefix, sorted
     * @param claimedTwice   each ordinal more than one file claims, mapped to every filename claiming
     *                       it; ordinals and filenames are both sorted, so a message built from it is
     *                       stable across file systems
     */
    private record OrdinalClaims(List<String> records, List<String> withoutOrdinal,
    Map<String, List<String>> claimedTwice) {
    }

    /**
     * Lists the regular files directly in {@code directory} whose name ends in
     * {@link #RECORD_SUFFIX} and reports which ordinals more than one of them claims. This is the
     * detection both tests share: the primary test points it at {@code doc/adr}, the control at a
     * temporary fixture.
     * <p>
     * The listing is not recursive, and a directory whose name happens to end in the suffix is not a
     * record.
     *
     * @param directory the directory holding the records
     * @return the listed records, those without an ordinal, and the ordinals claimed more than once
     * @throws IOException when the directory cannot be listed
     */
    private static OrdinalClaims ordinalClaims(Path directory) throws IOException {
        List<String> records;
        try (Stream<Path> entries = Files.list(directory)) {
            records = entries.filter(Files::isRegularFile)
                    .map(entry -> entry.getFileName().toString())
                    .filter(name -> name.endsWith(RECORD_SUFFIX))
                    .sorted()
                    .toList();
        }
        List<String> withoutOrdinal = new ArrayList<>();
        Map<String, List<String>> claimants = new TreeMap<>();
        for (String name : records) {
            Matcher ordinal = ORDINAL_PREFIX.matcher(name);
            if (ordinal.lookingAt()) {
                claimants.computeIfAbsent(ordinal.group(1), _ -> new ArrayList<>()).add(name);
            } else {
                withoutOrdinal.add(name);
            }
        }
        claimants.values().removeIf(names -> names.size() < 2);
        return new OrdinalClaims(records, withoutOrdinal, claimants);
    }

    /**
     * The failure message for a directory in which some ordinal is claimed more than once. It names
     * each offending ordinal with every filename claiming it, and says how a collision is repaired.
     *
     * @param adrDirectory the directory the records were listed from
     * @param claims       the detection result carrying at least one shared ordinal
     * @return the assertion message
     */
    private static String duplicateOrdinalMessage(Path adrDirectory, OrdinalClaims claims) {
        String offenders = claims.claimedTwice().entrySet().stream()
                .map(entry -> entry.getKey() + " is claimed by " + entry.getValue())
                .collect(Collectors.joining("; "));
        return adrDirectory + ": an ordinal is claimed by more than one decision record — " + offenders
                + ". An ordinal addresses exactly one record, so 'ADR-NNNN' in prose is ambiguous while two"
                + " files share it. Give the later record the next free ordinal (the highest existing one"
                + " plus one): rename the file, change its '= ADR-NNNN:' title line, and repair every"
                + " reference that names it. Do NOT delete this test to make the build green.";
    }

    /**
     * The repository root — the nearest ancestor of the working directory that holds the directory
     * {@link #ADR_DIRECTORY}.
     * <p>
     * The search walks up rather than taking a fixed one-level hop because the working directory is
     * not the same everywhere: surefire runs with the module as its working directory, but an IDE
     * runner or an aggregator invocation may use another. A genuinely unresolvable root fails naming
     * the directory the walk started from rather than surfacing later as a
     * {@code NoSuchFileException} on a path nobody asked for.
     *
     * @return the repository root
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(ADR_DIRECTORY))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds the directory " + ADR_DIRECTORY + ", which is this contract's"
                + " root marker and the directory it asserts over");
    }
}
