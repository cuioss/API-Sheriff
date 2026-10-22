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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Binds the reusable-workflow pin {@code doc/development/release-process.adoc} cites for the
 * version-changed guard to the pin {@code .github/workflows/release.yml} actually uses, so moving the pin
 * without re-checking the doc becomes a red build instead of a section that describes a guard the release
 * no longer runs.
 * <p>
 * <strong>Why this test exists.</strong> The section {@code [#guard-refusal-predicates]} states what the
 * central guard checks, in which order, and which reason line it logs for each refusal. Every one of those
 * claims was read off the cuioss-organization sources at one pin — the {@code uses:} of {@code release.yml}'s
 * {@code release} job — and the section names that pin as a short SHA. A pin bump changes what the release
 * runs; nothing changed what the doc says.
 * <p>
 * <strong>How it reads both sides.</strong> The documented pin is every
 * {@code cuioss/cuioss-organization/.github/workflows/<file>@<sha>} citation in the doc. The pinned value is
 * the {@code uses:} line inside the {@code release:} job block of {@code release.yml}, which carries the full
 * 40-character SHA. No SHA and no workflow path is written in this test: both sides are read from the files.
 * <p>
 * <strong>What it asserts.</strong> Each cited workflow path equals the path the {@code release} job calls,
 * and each cited short SHA is a prefix of the full SHA it pins.
 * <p>
 * <strong>What it does not assert.</strong> The nested {@code release-guard} action pin, and the two reason
 * lines quoted from that action's {@code release-guard.py}. Both live in the cuioss-organization repository at
 * the pinned commit, so they cannot be read offline; {@code .github/workflows/release-guard-docs.yml} fetches
 * them at the pinned SHAs and checks them on every pull request that touches {@code release.yml} or the doc.
 * The {@code v0.42.0} label and the line number the doc gives for the {@code uses:} line are not checked
 * either.
 * <p>
 * <strong>No vacuous pass.</strong> A doc that cites no workflow pin, a {@code release.yml} without a
 * {@code release} job, and a {@code release} job without exactly one cuioss-organization {@code uses:} line
 * each fail naming the file. Controls drive synthetic documents and workflows through the same helpers: a
 * matching pair is accepted while a moved SHA, a different workflow path and a doc citing no pin are each
 * reported; the {@code release} pin is recovered from between two other jobs that pin other workflows; and a
 * workflow without a {@code release} job and a {@code release} job with two pins are both refused.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The release-process doc cites the reusable-workflow pin release.yml's release job uses")
class ReleaseGuardPinDocsContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The doc whose guard section is read at the pin it cites, relative to the repository root. */
    private static final String RELEASE_DOC = "doc/development/release-process.adoc";

    /** The workflow whose {@code release} job pins the reusable workflow, relative to the repository root. */
    private static final String RELEASE_WORKFLOW = ".github/workflows/release.yml";

    /** The doc section a failure sends the reader back to. */
    private static final String GUARD_SECTION = "[#guard-refusal-predicates]";

    /** Opens the {@code release} job: a two-space-indented key directly under {@code jobs:}. */
    private static final Pattern RELEASE_JOB = Pattern.compile("(?m)^  release:[ \\t]*$");

    /** Opens the next job, or any other key at the same indentation, which ends the {@code release} block. */
    private static final Pattern NEXT_JOB = Pattern.compile("(?m)^  \\S");

    /** A reusable-workflow pin as the doc cites it: workflow path and a short or full SHA. */
    private static final Pattern DOCUMENTED_PIN = Pattern.compile(
            "cuioss/cuioss-organization/(\\.github/workflows/[A-Za-z0-9._-]+\\.yml)@([0-9a-f]{7,40})\\b");

    /**
     * The {@code uses:} line of a job calling a cuioss-organization reusable workflow at a full SHA. The ref
     * must end there — at the line end or a comment — so a ref that merely starts with 40 hex characters, such
     * as {@code <sha>-preview}, is not read as a commit pin.
     */
    private static final Pattern PINNED_USES = Pattern.compile(
            "(?m)^\\s+uses:\\s*cuioss/cuioss-organization/(\\.github/workflows/[A-Za-z0-9._-]+\\.yml)@([0-9a-f]{40})"
                    + "(?=[ \\t]*(?:#|$))");

    private static final String CONTROL_FULL_SHA = "0123456789abcdef0123456789abcdef01234567";

    /** A second full SHA, pinned by a job other than {@code release}. */
    private static final String CONTROL_OTHER_SHA = "fedcba9876543210fedcba9876543210fedcba98";

    /**
     * The {@code release} job is preceded and followed by jobs that also call a cuioss-organization reusable
     * workflow at a full SHA, so a reader that strays out of the {@code release} block picks up a pin the
     * control can tell apart.
     */
    private static final String CONTROL_WORKFLOW = """
            jobs:
              build:
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-build.yml@%2$s # v1.0.0
              release:
                if: always()
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@%1$s # v1.0.0
              publish-image:
                uses: cuioss/cuioss-organization/.github/workflows/reusable-image-publish.yml@%2$s # v1.0.0
            """.formatted(CONTROL_FULL_SHA, CONTROL_OTHER_SHA);

    private static final String CONTROL_NO_RELEASE_JOB = """
            jobs:
              build:
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-build.yml@%s # v1.0.0
            """.formatted(CONTROL_FULL_SHA);

    private static final String CONTROL_TWO_PINS = """
            jobs:
              release:
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@%s # v1.0.0
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@%s # v1.0.0
            """.formatted(CONTROL_FULL_SHA, CONTROL_OTHER_SHA);

    /** A {@code release} job whose ref starts with a full SHA but names a branch or tag, not a commit. */
    private static final String CONTROL_SUFFIXED_REF = """
            jobs:
              release:
                uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@%s-preview # v1.0.0
            """.formatted(CONTROL_FULL_SHA);

    private static final String CONTROL_MATCHING_DOC =
            "The release job pins `cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@0123456`.";

    private static final String CONTROL_MOVED_DOC =
            "The release job pins `cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@7654321`.";

    private static final String CONTROL_OTHER_PATH_DOC =
            "The release job pins `cuioss/cuioss-organization/.github/workflows/reusable-maven-build.yml@0123456`.";

    private static final String CONTROL_SILENT_DOC = "The release job pins the organisation's release workflow.";

    /**
     * One pin of a cuioss-organization reusable workflow.
     *
     * @param path the workflow path inside the cuioss-organization repository
     * @param sha  the commit it is pinned at — the full SHA on the workflow side, a prefix of it on the doc side
     */
    record Pin(String path, String sha) {
    }

    @Test
    @DisplayName("Every workflow pin the doc cites names the path and a prefix of the SHA the release job uses")
    void docCitesTheReusableWorkflowPinTheReleaseJobUses() throws Exception {
        Pin pinned = releaseJobPin(read(RELEASE_WORKFLOW), RELEASE_WORKFLOW);
        List<Pin> documented = documentedPins(read(RELEASE_DOC));

        List<String> drift = pinDrift(pinned, documented, RELEASE_DOC);

        assertTrue(drift.isEmpty(), () -> RELEASE_DOC + " cites the guard at a different pin than " + RELEASE_WORKFLOW
                + " uses (" + pinned.path() + "@" + pinned.sha() + "). Its section " + GUARD_SECTION
                + " describes the guard's check order and reason lines AT the cited pin, so re-check that section"
                + " against the cuioss-organization sources at the new pin — including the nested release-guard"
                + " action pin — before updating the cited SHA: " + String.join("; ", drift));
    }

    /**
     * Matched control over the drift detector. Without it the assertion above is green today because the
     * pins match, and it would be equally green if {@link #pinDrift} reported nothing or if
     * {@link #documentedPins} parsed nothing.
     */
    @Test
    @DisplayName("The drift detector accepts a matching pin and reports a moved SHA, another path and no pin")
    void driftDetectorReportsEveryMismatchAndAcceptsAMatchingPin() {
        Pin pinned = releaseJobPin(CONTROL_WORKFLOW, "the control workflow");

        assertAll("drift control",
                () -> assertEquals(new Pin(".github/workflows/reusable-maven-release.yml", CONTROL_FULL_SHA), pinned,
                        "the workflow reader did not recover the release job's pin, or read another job's"),
                () -> assertEquals(List.of(new Pin(".github/workflows/reusable-maven-release.yml", "0123456")),
                        documentedPins(CONTROL_MATCHING_DOC), "the doc reader did not recover the cited pin"),
                () -> assertEquals(List.of(), pinDrift(pinned, documentedPins(CONTROL_MATCHING_DOC), "the matching doc"),
                        "the detector must accept a doc citing a prefix of the pinned SHA at the pinned path"),
                () -> assertFalse(pinDrift(pinned, documentedPins(CONTROL_MOVED_DOC), "the moved doc").isEmpty(),
                        "the detector must report a cited SHA that is not a prefix of the pinned one"),
                () -> assertFalse(pinDrift(pinned, documentedPins(CONTROL_OTHER_PATH_DOC), "the other-path doc")
                        .isEmpty(), "the detector must report a cited workflow path the release job does not call"),
                () -> assertFalse(pinDrift(pinned, documentedPins(CONTROL_SILENT_DOC), "the silent doc").isEmpty(),
                        "the detector must report a doc that cites no pin, not accept it"));
    }

    /**
     * Matched control over the workflow reader's refusals: a workflow without a {@code release} job, a
     * {@code release} job that does not carry exactly one pin, and a ref that only starts with a full SHA each
     * fail rather than yield a pin to compare.
     */
    @Test
    @DisplayName("The workflow reader refuses a missing release job, an ambiguous pin and a ref that is not a commit SHA")
    void workflowReaderRefusesAMissingReleaseJobAnAmbiguousPinAndANonCommitRef() {
        AssertionFailedError noReleaseJob = assertThrows(AssertionFailedError.class,
                () -> releaseJobPin(CONTROL_NO_RELEASE_JOB, "the job-less control"));
        AssertionFailedError twoPins = assertThrows(AssertionFailedError.class,
                () -> releaseJobPin(CONTROL_TWO_PINS, "the two-pin control"));
        AssertionFailedError suffixedRef = assertThrows(AssertionFailedError.class,
                () -> releaseJobPin(CONTROL_SUFFIXED_REF, "the suffixed-ref control"));

        assertAll("workflow reader refusals",
                () -> assertTrue(noReleaseJob.getMessage().contains("no `release:` job"),
                        "a workflow without a release job must be refused as such: " + noReleaseJob.getMessage()),
                () -> assertTrue(twoPins.getMessage().contains("expected exactly one"),
                        "a release job with two pins must be refused as ambiguous: " + twoPins.getMessage()),
                () -> assertTrue(suffixedRef.getMessage().contains("carries 0"),
                        "a ref that only starts with a full SHA must not be read as a commit pin: "
                                + suffixedRef.getMessage()));
    }

    // --- extraction helpers ----------------------------------------------------------------------

    /**
     * The pin the {@code release} job of a workflow calls: the one cuioss-organization {@code uses:} line
     * between the job's key and the next key at the same indentation.
     *
     * @param workflow the workflow text
     * @param label    names the workflow in a failure message
     * @return the path and full SHA the {@code release} job is pinned at
     */
    private static Pin releaseJobPin(String workflow, String label) {
        Matcher job = RELEASE_JOB.matcher(workflow);
        if (!job.find()) {
            return fail(label + ": no `release:` job, so the pin the doc must cite cannot be read");
        }
        Matcher next = NEXT_JOB.matcher(workflow);
        int end = next.find(job.end()) ? next.start() : workflow.length();
        Matcher uses = PINNED_USES.matcher(workflow.substring(job.end(), end));
        List<Pin> pins = new ArrayList<>();
        while (uses.find()) {
            pins.add(new Pin(uses.group(1), uses.group(2)));
        }
        if (pins.size() != 1) {
            return fail(label + ": the `release` job carries " + pins.size() + " cuioss-organization `uses:` lines"
                    + " pinned at a full SHA, expected exactly one: " + pins);
        }
        return pins.getFirst();
    }

    /**
     * Every reusable-workflow pin a document cites.
     *
     * @param text the document text
     * @return the cited pins, in document order
     */
    private static List<Pin> documentedPins(String text) {
        List<Pin> pins = new ArrayList<>();
        Matcher matcher = DOCUMENTED_PIN.matcher(text);
        while (matcher.find()) {
            pins.add(new Pin(matcher.group(1), matcher.group(2)));
        }
        return pins;
    }

    /**
     * The differences between the pinned and the documented pins.
     *
     * @param pinned     the pin read from the workflow
     * @param documented the pins read from the document
     * @param label      names the document in each difference
     * @return one line per cited pin that differs, or one line when the document cites none; empty when every
     *         cited pin names the pinned path and a prefix of the pinned SHA
     */
    private static List<String> pinDrift(Pin pinned, List<Pin> documented, String label) {
        List<String> drift = new ArrayList<>();
        if (documented.isEmpty()) {
            drift.add(label + " cites no cuioss/cuioss-organization/.github/workflows/<file>@<sha> pin at all");
        }
        for (Pin cited : documented) {
            if (!cited.path().equals(pinned.path())) {
                drift.add(label + " cites " + cited.path() + " but the release job calls " + pinned.path());
            }
            if (!pinned.sha().startsWith(cited.sha())) {
                drift.add(label + " cites @" + cited.sha() + ", which is not a prefix of the pinned @" + pinned.sha());
            }
        }
        return drift;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repoRoot().resolve(relativePath));
    }

    /**
     * The repository root — the nearest ancestor of the working directory that holds every file this
     * contract reads, so the guard does not depend on the directory a runner starts in.
     *
     * @return the repository root
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(RELEASE_DOC))
                    && Files.isRegularFile(candidate.resolve(RELEASE_WORKFLOW))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds " + RELEASE_DOC + " and " + RELEASE_WORKFLOW);
    }
}
