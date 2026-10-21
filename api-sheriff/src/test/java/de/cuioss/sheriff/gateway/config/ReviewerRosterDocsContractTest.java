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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the reviewer roster the development docs state to the one {@code .plan/marshal.json} configures,
 * so renaming, adding or re-classing a review bot in the configuration without the docs becomes a red
 * build instead of a page that names the wrong reviewers.
 * <p>
 * <strong>Why this test exists.</strong> {@code doc/development/re-review-on-loopback.adoc} and its row
 * in {@code doc/development/README.adoc} name the repository's review bots and say which one gates the
 * merge. Those names are copied by hand from the {@code required_bots} and {@code optional_bots} values
 * of the {@code plan-marshall:automatic-review} step in {@code .plan/marshal.json}, which is what the
 * finalize phase actually waits on. Nothing else ties the two together, and the page's reasoning — why
 * an automatic re-review per loop-back only spends quota — depends on which bots are named.
 * <p>
 * <strong>How it reads both sides.</strong> The configured roster is parsed from
 * {@code .plan/marshal.json} as JSON; each value is a comma-separated list of bot identifiers. The
 * documented roster is every {@code Name (required)} and {@code Name (optional)} mention in the page and
 * in the page's row of the contributor-guide table, compared case-insensitively against the
 * identifiers, so the docs keep their display spelling ({@code CodeRabbit} for {@code coderabbit}). No
 * reviewer name is written in this test: both sides are read from the files.
 * <p>
 * <strong>What it asserts.</strong> Each document names exactly the configured required bots as
 * required and exactly the configured optional bots as optional — a bot missing from a document, a
 * bot the configuration no longer has, and a bot named in the wrong role all fail, naming the document
 * and both rosters.
 * <p>
 * <strong>What it does not assert.</strong> The rest of the prose: that the bots re-review on push, the
 * on-demand review comment each bot answers to, or the value of {@code re_review_on_loopback} itself.
 * A reviewer mentioned without one of the two role markers is not read as a roster entry.
 * <p>
 * <strong>No vacuous pass.</strong> The configured roster is asserted non-empty before it is compared,
 * every anchor the extraction relies on fails naming the file when it is not found, and a document that
 * names no reviewer at all is reported as drift rather than accepted. A control drives a synthetic
 * configuration and synthetic documents through the same helpers: a matching pair is accepted, while a
 * document naming a bot in the wrong role or a bot the configuration lacks, a configuration carrying a
 * bot no document names, and a document naming no reviewer are each reported.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The development docs name the review bots .plan/marshal.json configures, in their roles")
class ReviewerRosterDocsContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The configuration carrying the authoritative roster, relative to the repository root. */
    private static final String MARSHAL_JSON = ".plan/marshal.json";

    /** The page that states the roster in its reasoning, relative to the repository root. */
    private static final String LOOPBACK_DOC = "doc/development/re-review-on-loopback.adoc";

    /** The contributor-guide index whose row for the page restates the roster. */
    private static final String GUIDE_INDEX = "doc/development/README.adoc";

    /** Opens the index row of the loop-back page; the row runs to the next row or the table end. */
    private static final String LOOPBACK_ROW_ANCHOR = "| link:re-review-on-loopback.adoc[";

    private static final String NEXT_ROW = "\n| link:";

    private static final String TABLE_END = "\n|===";

    /** The finalize step whose settings carry the roster. */
    private static final String REVIEW_STEP = "plan-marshall:automatic-review";

    private static final String REQUIRED_KEY = "required_bots";

    private static final String OPTIONAL_KEY = "optional_bots";

    private static final String REQUIRED = "required";

    private static final String OPTIONAL = "optional";

    /** One documented roster entry: a reviewer's name followed by its role in parentheses. */
    private static final Pattern ROSTER_MENTION =
            Pattern.compile("\\b(\\p{L}[\\p{L}\\p{N}-]*+) \\((" + REQUIRED + "|" + OPTIONAL + ")\\)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A synthetic configuration for the control: one required and one optional bot. */
    private static final String CONTROL_MARSHAL = """
            { "plan": { "phase-6-finalize": { "steps": { "plan-marshall:automatic-review": {
                "required_bots": "alphabot", "optional_bots": "betabot" } } } } }
            """;

    /** The same configuration with a second required bot that no control document names. */
    private static final String CONTROL_MARSHAL_EXTRA_BOT = """
            { "plan": { "phase-6-finalize": { "steps": { "plan-marshall:automatic-review": {
                "required_bots": "alphabot, deltabot", "optional_bots": "betabot" } } } } }
            """;

    private static final String CONTROL_MATCHING_DOC =
            "Alphabot (required) gates the merge, Betabot (optional) never blocks it.";

    private static final String CONTROL_WRONG_ROLE_DOC =
            "Alphabot (optional) and Betabot (required) review every push.";

    private static final String CONTROL_UNKNOWN_BOT_DOC =
            "Alphabot (required), Betabot (optional) and Gammabot (optional) review every push.";

    private static final String CONTROL_SILENT_DOC = "The bots review every push.";

    /**
     * The roster of one side, as lower-cased bot identifiers per role.
     *
     * @param required the bots that gate the merge
     * @param optional the bots that review without gating
     */
    record Roster(Set<String> required, Set<String> optional) {

        boolean isEmpty() {
            return required.isEmpty() && optional.isEmpty();
        }

        @Override
        public String toString() {
            return REQUIRED + "=" + required + ", " + OPTIONAL + "=" + optional;
        }
    }

    @Test
    @DisplayName("The loop-back page and its index row name exactly the configured bots in their roles")
    void docsNameExactlyTheConfiguredReviewersInTheirRoles() throws Exception {
        Roster configured = configuredRoster(read(MARSHAL_JSON), MARSHAL_JSON);
        String page = read(LOOPBACK_DOC);
        String row = loopbackRow(read(GUIDE_INDEX));

        List<String> drift = new ArrayList<>();
        drift.addAll(rosterDrift(configured, documentedRoster(page), LOOPBACK_DOC));
        drift.addAll(rosterDrift(configured, documentedRoster(row), GUIDE_INDEX + " (the loop-back page's row)"));

        assertFalse(configured.isEmpty(), MARSHAL_JSON + ": the " + REVIEW_STEP + " step configures no review"
                + " bot at all, so this guard would compare the docs against nothing");
        assertTrue(drift.isEmpty(), () -> "The reviewer roster in the development docs no longer matches "
                + MARSHAL_JSON + ", which is what the finalize phase waits on. Update the docs to name each"
                + " configured bot in its role, as Name (required) or Name (optional): "
                + String.join("; ", drift));
    }

    /**
     * Matched control over the drift detector. Without it the assertion above is green today because the
     * docs match, and it would be equally green if {@link #rosterDrift} reported nothing or if
     * {@link #documentedRoster} parsed nothing.
     */
    @Test
    @DisplayName("The drift detector accepts a matching pair and reports every kind of mismatch")
    void driftDetectorReportsEveryMismatchAndAcceptsAMatchingPair() throws Exception {
        Roster configured = configuredRoster(CONTROL_MARSHAL, "the control configuration");
        Roster configuredWithExtraBot = configuredRoster(CONTROL_MARSHAL_EXTRA_BOT, "the extra-bot configuration");

        assertAll("drift control",
                () -> assertEquals(new Roster(Set.of("alphabot"), Set.of("betabot")), configured,
                        "the configuration reader did not recover the synthetic roster"),
                () -> assertEquals(new Roster(Set.of("alphabot"), Set.of("betabot")),
                        documentedRoster(CONTROL_MATCHING_DOC),
                        "the document reader did not recover the synthetic roster, case-folded"),
                () -> assertEquals(List.of(), rosterDrift(configured, documentedRoster(CONTROL_MATCHING_DOC),
                        "the matching doc"), "the detector must accept a document naming each bot in its role"),
                () -> assertFalse(rosterDrift(configured, documentedRoster(CONTROL_WRONG_ROLE_DOC),
                        "the wrong-role doc").isEmpty(), "the detector must report bots named in the wrong role"),
                () -> assertFalse(rosterDrift(configured, documentedRoster(CONTROL_UNKNOWN_BOT_DOC),
                        "the unknown-bot doc").isEmpty(),
                        "the detector must report a bot the configuration does not have"),
                () -> assertFalse(rosterDrift(configuredWithExtraBot, documentedRoster(CONTROL_MATCHING_DOC),
                        "the matching doc").isEmpty(),
                        "the detector must report a configured bot the document does not name"),
                () -> assertFalse(rosterDrift(configured, documentedRoster(CONTROL_SILENT_DOC),
                        "the silent doc").isEmpty(),
                        "the detector must report a document that names no reviewer, not accept it"));
    }

    // --- extraction helpers ----------------------------------------------------------------------

    /**
     * The roster {@code .plan/marshal.json} configures for the automatic-review step.
     *
     * @param json  the configuration document
     * @param label names the document in a failure message
     * @return the configured bots per role
     * @throws IOException when the document is not valid JSON
     */
    private static Roster configuredRoster(String json, String label) throws IOException {
        JsonNode step = MAPPER.readTree(json).path("plan").path("phase-6-finalize").path("steps").path(REVIEW_STEP);
        return new Roster(botList(step, REQUIRED_KEY, label), botList(step, OPTIONAL_KEY, label));
    }

    private static Set<String> botList(JsonNode step, String key, String label) {
        JsonNode value = step.path(key);
        if (!value.isTextual()) {
            return fail(label + ": plan.phase-6-finalize.steps[\"" + REVIEW_STEP + "\"]." + key
                    + " is absent or is not a string, so the roster this contract compares against cannot be read");
        }
        Set<String> bots = new LinkedHashSet<>();
        for (String bot : value.asText().split(",")) {
            String identifier = bot.strip().toLowerCase(Locale.ROOT);
            if (!identifier.isEmpty()) {
                bots.add(identifier);
            }
        }
        return bots;
    }

    /**
     * The roster a document states: every {@code Name (required)} and {@code Name (optional)} mention,
     * with the name lower-cased so it compares against the configured identifiers.
     *
     * @param text the document text to read
     * @return the documented bots per role; a bot named in both roles appears in both
     */
    private static Roster documentedRoster(String text) {
        Set<String> required = new LinkedHashSet<>();
        Set<String> optional = new LinkedHashSet<>();
        Matcher matcher = ROSTER_MENTION.matcher(text);
        while (matcher.find()) {
            String bot = matcher.group(1).toLowerCase(Locale.ROOT);
            if (REQUIRED.equals(matcher.group(2))) {
                required.add(bot);
            } else {
                optional.add(bot);
            }
        }
        return new Roster(required, optional);
    }

    /**
     * The differences between the configured and the documented roster, per role.
     *
     * @param configured the roster read from the configuration
     * @param documented the roster read from one document
     * @param label      names the document in each difference
     * @return one line per role that differs; empty when the document states exactly the configured roster
     */
    private static List<String> rosterDrift(Roster configured, Roster documented, String label) {
        List<String> drift = new ArrayList<>();
        if (!configured.required().equals(documented.required())) {
            drift.add(label + " names " + documented.required() + " as " + REQUIRED + " but the configuration has "
                    + configured.required());
        }
        if (!configured.optional().equals(documented.optional())) {
            drift.add(label + " names " + documented.optional() + " as " + OPTIONAL + " but the configuration has "
                    + configured.optional());
        }
        return drift;
    }

    /**
     * The loop-back page's row of the contributor-guide table: from its link to the next row or the end
     * of the table.
     *
     * @param index the contributor-guide index
     * @return the row text
     */
    private static String loopbackRow(String index) {
        int start = index.indexOf(LOOPBACK_ROW_ANCHOR);
        if (start < 0) {
            return fail(GUIDE_INDEX + ": no table row opens with \"" + LOOPBACK_ROW_ANCHOR + "\", so the restated"
                    + " roster this contract reads cannot be located. Update the anchor if the page was renamed");
        }
        int end = firstOf(index, start + LOOPBACK_ROW_ANCHOR.length(), NEXT_ROW, TABLE_END);
        if (end < 0) {
            return fail(GUIDE_INDEX + ": the row opened by \"" + LOOPBACK_ROW_ANCHOR + "\" is followed by neither"
                    + " another row nor the end of the table");
        }
        return index.substring(start, end);
    }

    private static int firstOf(String text, int from, String... markers) {
        int first = -1;
        for (String marker : markers) {
            int at = text.indexOf(marker, from);
            if (at >= 0 && (first < 0 || at < first)) {
                first = at;
            }
        }
        return first;
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
            if (Files.isRegularFile(candidate.resolve(MARSHAL_JSON))
                    && Files.isRegularFile(candidate.resolve(LOOPBACK_DOC))
                    && Files.isRegularFile(candidate.resolve(GUIDE_INDEX))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds " + MARSHAL_JSON + ", " + LOOPBACK_DOC + " and " + GUIDE_INDEX);
    }
}
