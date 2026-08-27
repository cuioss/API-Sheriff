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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that makes the descriptor inventory claimed by
 * {@code doc/development/declared-limit-assertion-coverage.adoc} machine-checked
 * <em>against that note</em>.
 * <p>
 * That note opens by enumerating the declared surface as <em>twelve files</em> — four
 * {@code gateway.yaml} documents, one per {@code sheriff-config*} directory, seven
 * {@code sheriff-config/endpoints/*.yaml} files and one
 * {@code sheriff-config/topology.properties} — and then states that a limit-shaped key sweep over
 * those twelve returns hits only in the four gateway documents and in five of the seven endpoint
 * files. Both are load-bearing claims: every status row in the note is scoped to that surface, so a
 * descriptor the note never enumerated is not merely undocumented, it is <em>invisible</em> to the
 * whole coverage index.
 * <p>
 * <strong>This guard owns no copy of either claim.</strong> It reads three AsciiDoc tagged blocks
 * out of the note at runtime — {@code inventory}, {@code limit-key-regex} and
 * {@code limit-bearing} — and uses the parsed values as its expected sets. That direction matters:
 * with the expectations transcribed into constants instead, adding a descriptor would fail the
 * test, a developer would update the constants, the build would go green, and the note would never
 * be touched — the very drift this guard exists to prevent. Deriving from the note makes the note
 * the only place the expectation can be changed.
 * <p>
 * It asserts <strong>exact set equality</strong> rather than a floor, in both directions: an
 * addition and a removal each fail loudly, and the failure message names the note so the fix is
 * obvious.
 * <p>
 * <strong>Anti-vacuity.</strong> A derivation that silently yields an empty expected set and then
 * passes would be strictly worse than the transcribed constants it replaces, so every failure of
 * the derivation itself is loud: a note that cannot be located, a missing or unterminated marker, a
 * block with no content, a block that yields no path, and a sweep block that does not hold exactly
 * one pattern each fail with a message naming the resolved note path.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network. It is
 * deliberately <em>additive</em>: it does not widen the sibling guards' globs, whose narrower reach
 * is a separate, report-only finding recorded in the note.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class DescriptorInventoryWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The note this guard derives from, relative to the repository root. */
    private static final String NOTE = "doc/development/declared-limit-assertion-coverage.adoc";

    /** The note's tagged block holding the enumerated-surface table. */
    private static final String TAG_INVENTORY = "inventory";

    /** The note's tagged block holding the limit-shaped key sweep pattern. */
    private static final String TAG_LIMIT_KEY_REGEX = "limit-key-regex";

    /** The note's tagged block holding the files recorded as declaring a limit-shaped key. */
    private static final String TAG_LIMIT_BEARING = "limit-bearing";

    /** Backtick-delimited span — the note writes every path and the sweep pattern as monospace. */
    private static final Pattern MONOSPACE_SPAN = Pattern.compile("`([^`]+)`");

    @Test
    @DisplayName("the committed descriptor inventory is exactly the surface the note enumerates")
    void descriptorInventoryIsExactlyTheEnumeratedSurface() throws Exception {
        // Arrange — the note's own claim, parsed rather than transcribed
        Set<String> declared = declaredPaths(TAG_INVENTORY);

        // Act
        Set<String> committed = committedInventory();

        // Assert — exact equality, so an ADDED descriptor fails just as loudly as a removed one.
        // A floor would let a new overlay directory or a new endpoints/*.yaml slip in unrecorded.
        assertEquals(declared, committed, "the committed descriptor inventory under " + DOCKER
                + " no longer matches the surface enumerated in " + note()
                + ". Every status row in that note is scoped to this surface, so a descriptor it does"
                + " not enumerate is invisible to the whole coverage index — add or remove the row in"
                + " the same change that adds or removes the file. This guard reads its expectation"
                + " from the note's '" + TAG_INVENTORY + "' block, so the note is the only place the"
                + " expectation can be corrected.");
    }

    @Test
    @DisplayName("no limit-shaped key lives outside the surfaces the note records as declaring one")
    void limitShapedKeysLiveOnlyInTheRecordedSurfaces() throws Exception {
        // Arrange — both the sweep and the expected partition come from the note
        Pattern sweep = declaredLimitShapedKeyPattern();
        Set<String> declaredLimitBearing = declaredPaths(TAG_LIMIT_BEARING);
        Set<String> committed = committedInventory();

        // Act — partition the inventory on "declares at least one limit-shaped key"
        Set<String> limitBearing = new TreeSet<>();
        for (String relative : committed) {
            if (declaresLimitShapedKey(relative, sweep)) {
                limitBearing.add(relative);
            }
        }

        // Assert — exact equality asserts both halves of the note's claim in one step: the files it
        // says declare limits still do, and the files it says declare none still declare none.
        assertEquals(declaredLimitBearing, limitBearing,
                "the set of descriptors declaring a limit-shaped key no longer matches " + note()
                        + ". A newly limit-bearing file has no status row and is therefore an unasserted"
                        + " declared limit; a file that lost its keys leaves a stale row behind. Update"
                        + " the note's '" + TAG_LIMIT_BEARING + "' block and its Coverage Matrix in the"
                        + " same change.");
    }

    @Test
    @DisplayName("the note's three marked blocks are present, non-empty and mutually consistent")
    void theDerivationFromTheNoteIsNotVacuous() throws Exception {
        // Arrange + Act — each accessor fails loudly on an absent note, a missing or unterminated
        // marker, an empty block, or a block that yields nothing usable
        Set<String> inventory = declaredPaths(TAG_INVENTORY);
        Set<String> limitBearing = declaredPaths(TAG_LIMIT_BEARING);
        Pattern sweep = declaredLimitShapedKeyPattern();

        // Assert — a derived expectation is only worth having if it is non-empty and self-consistent
        assertFalse(sweep.pattern().isBlank(),
                "the '" + TAG_LIMIT_KEY_REGEX + "' block in " + note() + " yields a blank pattern,"
                        + " which would match nothing and make the sweep assertion vacuous");
        assertTrue(inventory.containsAll(limitBearing),
                "the '" + TAG_LIMIT_BEARING + "' block in " + note() + " names paths that its '"
                        + TAG_INVENTORY + "' block does not enumerate: "
                        + difference(limitBearing, inventory)
                        + ". A limit-bearing file outside the enumerated surface cannot be asserted,"
                        + " because the sweep only ever partitions the enumerated surface.");
    }

    /**
     * The note's location, resolved explicitly from the module working directory. Surefire runs with
     * the {@code integration-tests} module root as the working directory and the note lives at the
     * repository root, hence the step up to the parent.
     *
     * @return the note's absolute path
     */
    private static Path note() {
        Path repositoryRoot = MODULE.getParent();
        assertNotNull(repositoryRoot, "the module directory " + MODULE + " has no parent, so the"
                + " repository root — and with it " + NOTE + ", from which this guard derives every"
                + " expectation — cannot be resolved");
        Path note = repositoryRoot.resolve(NOTE);
        assertTrue(Files.isRegularFile(note), "the note this guard derives its expectations from was"
                + " not found at " + note + " (resolved from the module working directory " + MODULE
                + "). It was moved, renamed or removed: point this guard at the new location in the"
                + " same change, rather than leaving it to derive nothing and pass.");
        return note;
    }

    /**
     * The lines strictly between an AsciiDoc {@code tag::NAME[]} / {@code end::NAME[]} marker pair in
     * the note. The markers are line comments, so they do not affect the rendered document.
     *
     * @param tag the marker name
     * @return the block's lines, guaranteed to contain at least one non-blank line
     * @throws IOException when the note cannot be read
     */
    private static List<String> taggedBlock(String tag) throws IOException {
        Path note = note();
        List<String> lines = Files.readAllLines(note);
        String open = "// tag::" + tag + "[]";
        String close = "// end::" + tag + "[]";
        int from = lines.indexOf(open);
        int to = lines.indexOf(close);
        assertTrue(from >= 0, "the marker '" + open + "' is absent from " + note
                + ", so this guard has no expectation to derive. Restore the marker (it must stand"
                + " alone on its own line, unindented) rather than leaving the guard vacuous.");
        assertTrue(to > from, "the marker '" + close + "' is absent from " + note
                + " or precedes its opening marker, so the '" + tag + "' block cannot be read.");
        List<String> block = new ArrayList<>(lines.subList(from + 1, to));
        assertTrue(block.stream().anyMatch(line -> !line.isBlank()), "the '" + tag + "' block in "
                + note + " is empty. An empty block would derive an empty expectation and pass"
                + " vacuously, which is why it fails here instead.");
        return block;
    }

    /**
     * Every descriptor path a tagged block spells. Paths are written as monospace spans; glob
     * patterns (spans containing {@code *}) are the note's surface labels rather than files and are
     * skipped.
     *
     * @param tag the marker name of the block to read
     * @return the parsed paths, sorted for a readable assertion diff and guaranteed non-empty
     * @throws IOException when the note cannot be read
     */
    private static Set<String> declaredPaths(String tag) throws IOException {
        Set<String> paths = new TreeSet<>();
        for (String line : taggedBlock(tag)) {
            Matcher matcher = MONOSPACE_SPAN.matcher(line);
            while (matcher.find()) {
                String span = matcher.group(1).trim();
                if (span.indexOf('*') < 0 && !span.isEmpty()) {
                    paths.add(span);
                }
            }
        }
        assertFalse(paths.isEmpty(), "the '" + tag + "' block in " + note() + " spells no descriptor"
                + " path (paths are written as monospace spans; glob patterns are skipped). An empty"
                + " expected set would pass against anything, so it fails here instead.");
        return paths;
    }

    /**
     * The note's limit-shaped key sweep, compiled from the pattern the note states. The pattern is
     * repository-controlled prose from a checked-in document — never external or request-supplied
     * input — and is applied to declared KEY NAMES only, never to raw file text, so the descriptors'
     * extensive prose comments cannot manufacture a hit.
     *
     * @return the compiled sweep
     * @throws IOException when the note cannot be read
     */
    private static Pattern declaredLimitShapedKeyPattern() throws IOException {
        List<String> spans = new ArrayList<>();
        for (String line : taggedBlock(TAG_LIMIT_KEY_REGEX)) {
            Matcher matcher = MONOSPACE_SPAN.matcher(line);
            while (matcher.find()) {
                spans.add(matcher.group(1).trim());
            }
        }
        assertEquals(1, spans.size(), "the '" + TAG_LIMIT_KEY_REGEX + "' block in " + note()
                + " must hold exactly one monospace span — the sweep pattern — but holds " + spans.size()
                + ": " + spans + ". Anything else is ambiguous, and guessing would risk deriving a"
                + " pattern that matches nothing.");
        return Pattern.compile(spans.getFirst());
    }

    /**
     * Every committed file under a {@code sheriff-config*} directory, as slash-separated paths
     * relative to the docker directory. The walk is recursive and unfiltered on purpose — filtering
     * by extension would hide exactly the kind of unrecorded companion file this guard exists to
     * catch.
     *
     * @return the committed inventory, sorted for a readable assertion diff
     * @throws IOException when the docker tree cannot be listed
     */
    private static Set<String> committedInventory() throws IOException {
        Set<String> inventory = new TreeSet<>();
        List<Path> roots = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                roots.add(directory);
            }
        }
        assertFalse(roots.isEmpty(), "no sheriff-config* directory was found under " + DOCKER
                + " — this guard would compare against an empty inventory; check the module working"
                + " directory and the glob");
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .map(path -> DOCKER.relativize(path).toString().replace('\\', '/'))
                        .forEach(inventory::add);
            }
        }
        return inventory;
    }

    /**
     * Whether one inventory file declares at least one limit-shaped key. YAML documents are parsed
     * and every declared key name — at every nesting depth, including inside sequences — is matched;
     * {@code .properties} files contribute their property names. Any other file kind declares no
     * keys and therefore no limit.
     *
     * @param relative the file's docker-relative path
     * @param sweep the note's limit-shaped key pattern
     * @return {@code true} when at least one declared key name matches the note's sweep
     * @throws IOException when the file cannot be read
     */
    private static boolean declaresLimitShapedKey(String relative, Pattern sweep) throws IOException {
        Set<String> keys = new TreeSet<>();
        Path file = DOCKER.resolve(relative);
        if (relative.endsWith(".yaml")) {
            collectKeys(loadYaml(file), keys);
        } else if (relative.endsWith(".properties")) {
            keys.addAll(loadProperties(file).stringPropertyNames());
        }
        return keys.stream()
                .anyMatch(key -> sweep.matcher(key.toLowerCase(Locale.ROOT)).find());
    }

    /**
     * Collects every declared key name of a parsed YAML node, recursing through nested maps and
     * sequences.
     *
     * @param node the parsed node; anything that is neither a map nor a sequence declares no key
     * @param keys the accumulator to add the discovered key names to
     */
    private static void collectKeys(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                collectKeys(entry.getValue(), keys);
            }
        } else if (node instanceof List<?> sequence) {
            for (Object item : sequence) {
                collectKeys(item, keys);
            }
        }
    }

    /**
     * The members of {@code candidate} absent from {@code reference}, for a legible failure message.
     *
     * @param candidate the set whose surplus members are wanted
     * @param reference the set to subtract
     * @return the difference, sorted
     */
    private static Set<String> difference(Set<String> candidate, Set<String> reference) {
        Set<String> surplus = new TreeSet<>(candidate);
        surplus.removeAll(reference);
        return surplus;
    }

    private static Object loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }
}
