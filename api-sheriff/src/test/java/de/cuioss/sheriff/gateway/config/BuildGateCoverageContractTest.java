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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code build.map.java} entries that make the repository's declared gate-requiring file
 * classes actually reach a build, so erasing one becomes a red build instead of a silent hole.
 * <p>
 * <strong>Why this test exists.</strong> {@code CLAUDE.md} declares which file classes oblige a full
 * quality gate. That declaration is prose; the surface that <em>enforces</em> it is
 * {@code .plan/marshal.json}'s {@code build.map}, which the {@code build-decision} verb consults to
 * answer "does this footprint need a build?". The two had drifted: several declared classes were
 * mapped by nothing at all, so a change touching only a Dockerfile, a compose file, a stylesheet or
 * a TypeScript source returned {@code not_necessary} and shipped without the gate the declaration
 * demands. The gap is invisible from either side — the prose still reads as authoritative, and the
 * map is well-formed — which is exactly the shape a contract test exists for.
 * <p>
 * <strong>What this guard does NOT assert.</strong> Membership here requires that a Maven build can
 * actually observe the file class. "Not documentation-only" and "a quality gate validates it" are
 * different claims, and only globs satisfying the second belong in the map — registering a class no
 * profile reads buys a multi-minute build that cannot see the change. {@code .github/workflows/*}
 * was pinned here on the first claim and has been removed for failing the second; see
 * {@link #REQUIRED_GLOBS}.
 * <p>
 * <strong>Why the five entries need pinning specifically.</strong> They are <em>hand-added</em>. The
 * build map is otherwise seeded from the applicable domain extensions, and an entry is preserved by
 * a re-seed only when some extension's {@code classify_globs()} derives it. No extension derives
 * these five, so they are re-derivable from nothing and are erased on either of two routes:
 * {@code manage-config build-map seed --force}, which rewrites the block from the derivation, and a
 * {@code marshall-steward} reconcile, which drives that same seeding path during configuration
 * upgrade. {@code manage-config build-map drift} reports them as removed rather than restoring them.
 * An erasure is therefore a normal-looking maintenance action with no local symptom — the map still
 * parses, every remaining entry is correct, and the only observable consequence is that some future
 * change quietly stops being gated. This guard converts that silence into a failing build.
 * <p>
 * <strong>Containment, not equality.</strong> The assertion is that each required glob is
 * <em>present</em> with {@code role: config} and {@code build_class: verify} — deliberately not that
 * the java block holds exactly twelve entries. A legitimate future seed adding a Maven route must
 * not fail this guard; only losing one of the five, or weakening a required entry's
 * {@code build_class} away from {@code verify}, may.
 * <p>
 * <strong>The required globs are hand-written here, not read from {@code CLAUDE.md}.</strong> The
 * map's spellings deliberately differ from the human-readable declaration, and the two lists are not
 * even the same length — the prose enumerates every class that must not pass as documentation-only,
 * while this set holds only those a Maven build can observe. Deriving one from the other would
 * encode both that translation and that filter as a second piece of logic to keep correct. This test
 * pins the <em>map</em> side only; it asserts nothing about what {@code CLAUDE.md} still says.
 * <p>
 * <strong>No vacuous pass.</strong> Both the required-glob set's cardinality and the parsed java
 * array's non-emptiness are asserted before the per-glob loop, so neither a shrunken constant nor a
 * lost {@code build.map.java} node can let the loop pass by iterating over nothing. A matched
 * positive/negative control drives a synthetic map through the same extraction helper — one entry
 * correct, one with the wrong {@code build_class}, one absent — so the primary assertion cannot be
 * green merely because the detector accepts everything.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("build.map covers every file class declared gate-requiring")
class BuildGateCoverageContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /**
     * The repository-root marker, and the file this contract asserts over. It is tracked in git, so
     * it resolves in a fresh checkout exactly as it does locally.
     */
    private static final String MARSHAL_JSON = ".plan/marshal.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The role every required entry must carry: these are build-affecting configuration files. */
    private static final String REQUIRED_ROLE = "config";

    /**
     * The build class every required entry must carry. {@code verify} is the strongest class and the
     * only one that satisfies the declaration — a required entry demoted to {@code compile} would
     * gate the change with something weaker than the full quality gate {@code CLAUDE.md} demands,
     * which is why the assertion pins the class rather than merely the glob's presence.
     */
    private static final String REQUIRED_BUILD_CLASS = "verify";

    /**
     * The globs whose absence from {@code build.map.java} is the gap this guard closes, written in
     * the map's own route-contract spelling rather than derived from {@code CLAUDE.md}. Held as a
     * set so a copy-paste duplicate collapses and is caught by the cardinality assertion below.
     * <p>
     * {@code .github/workflows/*} was deliberately REMOVED from this set. It was pinned here
     * originally on the reasoning that a workflow change is not documentation-only and so must not
     * skip the gate — but that conflates two different claims. {@code build.map} answers the binary
     * question "does this footprint need a build?", and no Maven profile in this repository reads
     * {@code .github/workflows/}: a workflow-only change compiles and tests exactly the same code as
     * before the edit, so the gate it triggered could only ever burn minutes without observing the
     * changed file. Workflow YAML is validated by CI executing it, which happens regardless of what
     * this map says. The four globs below are kept because each one CAN break something a Maven
     * build catches — see {@link #REQUIRED_GLOB_COUNT}.
     */
    private static final Set<String> REQUIRED_GLOBS = new LinkedHashSet<>(List.of(
            "Dockerfile*",
            "docker-compose*.yml",
            "*.css",
            "*.ts"));

    /**
     * How many distinct globs {@link #REQUIRED_GLOBS} must carry; see that field's rationale.
     * <p>
     * Four, not five: {@code Dockerfile*} and {@code docker-compose*.yml} are exercised by
     * {@code -Pintegration-tests}, which builds the native image and runs {@code ImageMetadataIT}
     * against it and starts the stack from those compose files; {@code *.css} and {@code *.ts} are
     * front-end sources the reactor's npm modules build. {@code .github/workflows/*} had no such
     * validator at any profile and was removed.
     */
    private static final int REQUIRED_GLOB_COUNT = 4;

    /**
     * A synthetic {@code build.map.java} block for the detector control: one entry that must be
     * accepted, and one whose {@code build_class} is deliberately wrong so it must be rejected even
     * though its glob is present.
     */
    private static final String SYNTHETIC_JAVA_MAP = """
            [
              { "glob": "*.control-present",     "role": "config", "build_class": "verify" },
              { "glob": "*.control-wrong-class", "role": "config", "build_class": "compile" }
            ]
            """;

    private static final String CONTROL_PRESENT_GLOB = "*.control-present";
    private static final String CONTROL_WRONG_CLASS_GLOB = "*.control-wrong-class";
    private static final String CONTROL_ABSENT_GLOB = "*.control-absent";

    @Test
    @DisplayName("Every declared gate-requiring file class is mapped with role config and build_class verify")
    void buildMapCoversEveryDeclaredGateRequiringFileClass() throws Exception {
        // Arrange
        JsonNode entries = javaBuildMap();

        // Act + Assert — the two vacuity guards run BEFORE the loop: without them a shrunken
        // constant or a lost build.map.java node would let the loop pass by iterating over nothing
        assertEquals(REQUIRED_GLOB_COUNT, REQUIRED_GLOBS.size(),
                "the required-glob set no longer carries " + REQUIRED_GLOB_COUNT + " distinct globs, so the"
                        + " loop below would assert over a shrunken set and pass while a declared file class"
                        + " went unmapped. Either a glob was deleted from the constant, or two entries were"
                        + " spelled identically and collapsed");
        assertFalse(entries.isEmpty(),
                MARSHAL_JSON + ": build.map.java is an empty array, so this guard would assert over nothing."
                        + " The whole java block is gone, not merely one entry — restore it rather than"
                        + " relaxing this check");

        for (String glob : REQUIRED_GLOBS) {
            assertTrue(carriesGateEntry(entries, glob), () -> missingEntryMessage(glob));
        }
    }

    /**
     * Matched positive/negative control over the extraction helper itself. Without it the assertion
     * above is unfalsifiable by inspection: it is green today because the map is complete, and it
     * would be equally green if {@link #carriesGateEntry(JsonNode, String)} accepted every glob it
     * was handed.
     */
    @Test
    @DisplayName("The detector accepts a correct entry and rejects both a wrong build_class and an absent glob")
    void detectorAcceptsOnlyAnEntryMatchingGlobRoleAndBuildClass() throws Exception {
        // Arrange
        JsonNode entries = MAPPER.readTree(SYNTHETIC_JAVA_MAP);

        // Act
        boolean acceptsCorrectEntry = carriesGateEntry(entries, CONTROL_PRESENT_GLOB);
        boolean acceptsWrongBuildClass = carriesGateEntry(entries, CONTROL_WRONG_CLASS_GLOB);
        boolean acceptsAbsentGlob = carriesGateEntry(entries, CONTROL_ABSENT_GLOB);

        // Assert
        assertTrue(acceptsCorrectEntry, "the detector rejected an entry carrying the requested glob with"
                + " role '" + REQUIRED_ROLE + "' and build_class '" + REQUIRED_BUILD_CLASS + "'. It would"
                + " then reject the real entries too, and the guard above would fail for a reason that has"
                + " nothing to do with the map");
        assertFalse(acceptsWrongBuildClass, "the detector accepted an entry whose glob matches but whose"
                + " build_class is not '" + REQUIRED_BUILD_CLASS + "'. Demoting a required entry's"
                + " build_class gates the change with something weaker than the declared quality gate, so"
                + " it must be caught rather than tolerated");
        assertFalse(acceptsAbsentGlob, "the detector accepted a glob the synthetic map does not contain,"
                + " so it is not actually reading the entries and the guard above proves nothing");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The failure message for a required glob that {@code build.map.java} no longer carries. It names
     * the glob, explains why the entry disappears without anyone deleting it on purpose, and points
     * at the declaration the map is supposed to honour.
     *
     * @param glob the required glob that is missing or misconfigured
     * @return the assertion message
     */
    private static String missingEntryMessage(String glob) {
        return MARSHAL_JSON + ": build.map.java carries no entry for the glob '" + glob + "' with role '"
                + REQUIRED_ROLE + "' and build_class '" + REQUIRED_BUILD_CLASS + "'. Without it a change"
                + " touching that file class makes build-decision answer 'not_necessary', and the change"
                + " ships without the quality gate CLAUDE.md lines 65-66 declare it requires."
                + " This entry is hand-added: no extension's classify_globs() derives it, so it is"
                + " re-derivable from nothing and is erased by 'manage-config build-map seed --force' and"
                + " by a marshall-steward reconcile driving that same seeding path;"
                + " 'manage-config build-map drift' reports it as removed rather than restoring it."
                + " Re-add the entry to " + MARSHAL_JSON + " — do NOT delete this test to make the build"
                + " green, because deleting it restores exactly the silent coverage gap it was written to"
                + " close.";
    }

    /**
     * Whether the entries carry one naming {@code glob} with the required role and build class. This
     * is the extraction the control test exercises against a synthetic map.
     *
     * @param entries the {@code build.map.java} array
     * @param glob    the glob to look for
     * @return {@code true} when some entry matches on all three fields
     */
    private static boolean carriesGateEntry(JsonNode entries, String glob) {
        for (JsonNode entry : entries) {
            if (glob.equals(entry.path("glob").asText())
                    && REQUIRED_ROLE.equals(entry.path("role").asText())
                    && REQUIRED_BUILD_CLASS.equals(entry.path("build_class").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code build.map.java} array parsed out of the repository's {@code marshal.json}.
     * <p>
     * The file is parsed as JSON rather than string-matched: a raw {@code contains} over the document
     * text would match a glob mentioned anywhere — in the javascript block, or in a comment-shaped
     * string — and would report success for an entry that is not where the build map reads it.
     *
     * @return the java domain's entry array
     * @throws IOException when the file cannot be read
     */
    private static JsonNode javaBuildMap() throws IOException {
        Path marshalJson = repoRoot().resolve(MARSHAL_JSON);
        JsonNode entries = MAPPER.readTree(Files.readString(marshalJson)).path("build").path("map").path("java");
        if (!entries.isArray()) {
            return fail(marshalJson + ": build.map.java is absent or is not an array, so the file-to-build"
                    + " contract this guard asserts over does not exist. Restore the build.map.java block"
                    + " rather than relaxing this check");
        }
        return entries;
    }

    /**
     * The repository root — the nearest ancestor of the working directory that actually holds
     * {@link #MARSHAL_JSON}.
     * <p>
     * The search walks up rather than taking a fixed one-level hop because the working directory is
     * not the same everywhere: surefire runs with the module as its working directory, but an IDE
     * runner or an aggregator invocation may use another. Probing for the marker resolves the root
     * from a property of the tree instead of from an assumption about the runner, and a genuinely
     * unresolvable root then fails naming the directory it started from rather than surfacing later
     * as a {@code NoSuchFileException} on a path nobody asked for.
     *
     * @return the repository root
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(MARSHAL_JSON))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds " + MARSHAL_JSON + ", which is this contract's root marker"
                + " and the file it asserts over");
    }
}
