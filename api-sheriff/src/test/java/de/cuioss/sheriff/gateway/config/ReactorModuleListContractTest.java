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
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Binds the module lists in {@code AGENTS.md} and {@code CLAUDE.md} to the reactor the root
 * {@code pom.xml} declares.
 * <p>
 * <strong>Why this test exists.</strong> Both documents open by telling an assistant which modules
 * the repository has, and each says in its own words that the list is the one the root
 * {@code pom.xml} declares. Nothing ties that statement to the POM. A module added to, removed from
 * or renamed in the reactor leaves both lists as they were, and each still reads as authoritative
 * while describing a build that no longer exists. Generating Markdown from the POM at build time is
 * not an option for hand-written guidance, so the lists stay hand-written and the drift becomes a
 * failing build instead.
 * <p>
 * <strong>A property, not a membership list.</strong> No module name is written into this test. The
 * reactor is read from {@code pom.xml} on every run, so adding a module to the reactor and to both
 * documents needs no edit here.
 * <p>
 * <strong>Only the reactor's own modules count.</strong> The reactor is the {@code <module>}
 * children of the {@code <modules>} element that is a direct child of {@code <project>}. Collecting
 * every {@code <module>} in the document would also take in a profile-scoped module, which a default
 * build does not walk and the documents do not claim to list.
 * <p>
 * <strong>Two assertions per document.</strong> The number of bullets a document lists, counted
 * before de-duplication, must equal the number of reactor modules; and the set of listed names must
 * equal the set of reactor modules. The count is what catches a bullet listed twice — a set cannot
 * see a duplicate, so a document naming one module twice collapses into exactly the set a correct
 * document produces.
 * <p>
 * <strong>What this guard does NOT assert.</strong> It reads the module name only: the first
 * backticked token of each bullet. The prose describing a module is free-form and is not compared
 * with anything.
 * <p>
 * <strong>No vacuous pass.</strong> The reactor module list is asserted non-empty before any
 * comparison, so a parse that finds nothing on both sides cannot pass as "empty equals empty", and a
 * document whose anchor is gone fails naming the document and the anchor rather than comparing an
 * empty list. A matched positive/negative control drives the same extraction and the same comparison
 * over synthetic text — a duplicated bullet, a missing module and a correct block — and a second
 * control pins that a profile-scoped module is not read as part of the reactor.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("AGENTS.md and CLAUDE.md list exactly the modules the root pom.xml declares")
class ReactorModuleListContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /**
     * The repository-root marker, and one of the two documents this contract asserts over.
     * {@code pom.xml} cannot be the marker: every module directory holds one, and surefire runs with
     * the module as its working directory, so a walk looking for it would stop at the module's own
     * POM, which declares no {@code <modules>}.
     */
    private static final String AGENTS_MD = "AGENTS.md";

    private static final String CLAUDE_MD = "CLAUDE.md";

    /** The authoritative source of the module set, resolved against the repository root. */
    private static final String ROOT_POM = "pom.xml";

    /** The sentence in {@code AGENTS.md} that the module list follows. */
    private static final String AGENTS_ANCHOR = "Modules, as declared in the root `pom.xml`:";

    /**
     * The heading in {@code CLAUDE.md} whose section opens with the module list. One introductory
     * sentence sits between the heading and the first bullet.
     */
    private static final String CLAUDE_ANCHOR = "## Project Structure";

    /**
     * A module bullet: a line starting with {@code - } followed by a backticked token. The match is
     * anchored at the start of the line, so an indented continuation line is never a bullet even
     * when it begins with a backticked token of its own.
     */
    private static final Pattern MODULE_BULLET = Pattern.compile("^- `([^`]+)`");

    /** Opens a Markdown heading; reaching one before any bullet means the section lists nothing. */
    private static final String HEADING_PREFIX = "#";

    private static final String DIRECTORY_SUFFIX = "/";

    private static final String CONTROL_ANCHOR = "Modules of the control reactor:";

    private static final List<String> CONTROL_REACTOR = List.of("alpha", "beta");

    /**
     * A block that lists the control reactor correctly. It carries the three shapes the extraction
     * must not be fooled by: a backticked module name before the anchor, a continuation line that
     * begins with a backticked token, and a later list that starts after the block has ended.
     */
    private static final String CONTROL_CORRECT_BLOCK = """
            A preamble naming `gamma/` before the anchor.

            Modules of the control reactor:

            - `alpha/` — the first module, described over two lines whose second begins with
              `beta/` as a backticked token
            - `beta/` — the second module

            A sentence that ends the block.

            - `gamma/` — a later list that is not part of the block
            """;

    /** The control reactor with one bullet listed twice: the set is right, the count is not. */
    private static final String CONTROL_DUPLICATED_BLOCK = """
            Modules of the control reactor:

            - `alpha/` — the first module
            - `beta/` — the second module
            - `alpha/` — the first module, listed a second time
            """;

    /** The control reactor with one module left out. */
    private static final String CONTROL_MISSING_BLOCK = """
            Modules of the control reactor:

            - `alpha/` — the first module
            """;

    /** A document from which the anchor sentence is gone, while a bullet list remains. */
    private static final String CONTROL_NO_ANCHOR = """
            - `alpha/` — the first module
            - `beta/` — the second module
            """;

    /** A POM declaring two reactor modules and a third inside a profile. */
    private static final String CONTROL_POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modules>
                <module>alpha</module>
                <module>beta</module>
              </modules>
              <profiles>
                <profile>
                  <id>extra</id>
                  <modules>
                    <module>gamma</module>
                  </modules>
                </profile>
              </profiles>
            </project>
            """;

    @Test
    @DisplayName("AGENTS.md lists exactly the reactor modules, each once")
    void agentsMdListsExactlyTheReactorModules() throws Exception {
        assertListsTheReactorModules(AGENTS_MD, AGENTS_ANCHOR);
    }

    @Test
    @DisplayName("CLAUDE.md lists exactly the reactor modules, each once")
    void claudeMdListsExactlyTheReactorModules() throws Exception {
        assertListsTheReactorModules(CLAUDE_MD, CLAUDE_ANCHOR);
    }

    /**
     * Matched positive/negative control over the extraction and the comparison. Without it the two
     * document assertions are unfalsifiable by inspection: they are green while the documents agree
     * with the reactor, and they would be equally green if {@link #listedModules(String, String)}
     * dropped duplicates or {@link #compare(List, List)} reported agreement for everything.
     */
    @Test
    @DisplayName("The extraction and comparison report a duplicated bullet and a missing module, and accept a correct block")
    void extractionAndComparisonDiscriminate() {
        Optional<List<String>> correct = listedModules(CONTROL_CORRECT_BLOCK, CONTROL_ANCHOR);
        Optional<List<String>> duplicated = listedModules(CONTROL_DUPLICATED_BLOCK, CONTROL_ANCHOR);
        Optional<List<String>> missing = listedModules(CONTROL_MISSING_BLOCK, CONTROL_ANCHOR);
        Optional<List<String>> withoutAnchor = listedModules(CONTROL_NO_ANCHOR, CONTROL_ANCHOR);

        assertAll("extraction and comparison controls",
                () -> assertEquals(Optional.of(CONTROL_REACTOR), correct,
                        "a correct block must yield its module names in listed order, with the trailing"
                                + " slash removed. A backticked token before the anchor, on a continuation"
                                + " line, or in a later list is not a module bullet of this block"),
                () -> assertTrue(agrees(compare(correct.orElseThrow(), CONTROL_REACTOR)),
                        "the comparison rejected a block that lists the control reactor correctly, so the"
                                + " document assertions would fail for a reason that has nothing to do with"
                                + " the documents"),
                () -> assertEquals(3, duplicated.orElseThrow().size(),
                        "the extraction must return a duplicated bullet twice: de-duplicating here would"
                                + " hide the one defect the count assertion exists to catch"),
                () -> assertFalse(compare(duplicated.orElseThrow(), CONTROL_REACTOR).countsAgree(),
                        "a block listing one module twice must be reported through the count taken before"
                                + " de-duplication"),
                () -> assertTrue(compare(duplicated.orElseThrow(), CONTROL_REACTOR).setsAgree(),
                        "a duplicated bullet leaves the SET of listed names equal to the reactor's, which is"
                                + " why the count is asserted separately; if the sets differ here the control"
                                + " no longer isolates the duplicate"),
                () -> assertEquals(Set.of("beta"),
                        compare(missing.orElseThrow(), CONTROL_REACTOR).onlyInReactor(),
                        "a block leaving a reactor module out must be reported through set inequality,"
                                + " naming the module the document does not list"),
                () -> assertEquals(Optional.empty(), withoutAnchor,
                        "a document without the anchor must yield no list at all rather than an empty or a"
                                + " guessed one, so the document assertion can fail naming the anchor"));
    }

    /**
     * Control over the reactor extraction: a module declared inside a profile is not a reactor
     * module. The root {@code pom.xml} declares none today, so without this control a helper that
     * collected every {@code <module>} in the document would read the same six names and pass.
     *
     * @throws Exception when the synthetic POM cannot be parsed
     */
    @Test
    @DisplayName("The reactor extraction reads the project's own modules and not a profile-scoped one")
    void reactorExtractionIgnoresProfileScopedModules() throws Exception {
        Element project = parse(new StringReader(CONTROL_POM));

        List<String> modules = reactorModules(project);

        assertEquals(CONTROL_REACTOR, modules,
                "only the <module> children of the <modules> element directly under <project> are the"
                        + " reactor; the profile-scoped module of the synthetic POM must not be collected");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Asserts that {@code documentName} lists the reactor modules: the same number of bullets as the
     * reactor has modules, and the same names.
     *
     * @param documentName the document, relative to the repository root
     * @param anchor       the line the document's module list follows
     * @throws Exception when the document or the root POM cannot be read or parsed
     */
    private static void assertListsTheReactorModules(String documentName, String anchor) throws Exception {
        Path root = repoRoot();
        Path pom = root.resolve(ROOT_POM);
        List<String> reactor;
        try (Reader reader = Files.newBufferedReader(pom)) {
            reactor = reactorModules(parse(reader));
        }
        String document = Files.readString(root.resolve(documentName));

        Optional<List<String>> listed = listedModules(document, anchor);

        // The non-vacuity guard runs BEFORE any comparison: a parse that found no module would
        // otherwise agree with a document that lists none
        assertFalse(reactor.isEmpty(),
                pom + " yields no <module> under the <modules> element of <project>, so this guard would"
                        + " compare the document against nothing. Either the reactor moved or the extraction"
                        + " no longer reads it — fix that rather than relaxing this check");
        List<String> modules = listed.orElseGet(() -> fail(documentName + ": the anchor line \"" + anchor
                + "\" was not found, so the module list cannot be located. Restore the line, or update the"
                + " anchor in this test to follow the rewrite"));
        ListComparison comparison = compare(modules, reactor);
        assertEquals(comparison.reactorCount(), comparison.listedCount(),
                documentName + " lists " + comparison.listedCount() + " module bullet(s) after \"" + anchor
                        + "\" but " + pom + " declares " + comparison.reactorCount() + " reactor module(s)."
                        + " The count is taken over the bullets as listed, before de-duplication, so a module"
                        + " listed twice fails here even when the set of names is right. Listed: " + modules
                        + "; reactor: " + reactor);
        assertTrue(comparison.setsAgree(),
                documentName + " has drifted from the reactor " + pom + " declares. Listed only in the"
                        + " document: " + comparison.onlyInDocument() + "; declared only in the reactor: "
                        + comparison.onlyInReactor() + ". Correct the document — the POM is the authoritative"
                        + " source, and this assertion is not the thing to change");
    }

    /**
     * Whether a comparison found the listed modules and the reactor in full agreement.
     *
     * @param comparison the comparison to judge
     * @return {@code true} when both the count and the set agree
     */
    private static boolean agrees(ListComparison comparison) {
        return comparison.countsAgree() && comparison.setsAgree();
    }

    /**
     * How a document's module list relates to the reactor.
     *
     * @param listedCount    the number of bullets the document lists, before de-duplication
     * @param reactorCount   the number of modules the reactor declares
     * @param onlyInDocument the names the document lists and the reactor does not declare, sorted
     * @param onlyInReactor  the names the reactor declares and the document does not list, sorted
     */
    private record ListComparison(int listedCount, int reactorCount, Set<String> onlyInDocument,
    Set<String> onlyInReactor) {

        boolean countsAgree() {
            return listedCount == reactorCount;
        }

        boolean setsAgree() {
            return onlyInDocument.isEmpty() && onlyInReactor.isEmpty();
        }
    }

    /**
     * Compares the module names a document lists with the modules the reactor declares. This is the
     * comparison the document assertions and the control share.
     *
     * @param listed  the names as listed, in order and before de-duplication
     * @param reactor the reactor's module names
     * @return the two counts and the names present on one side only
     */
    private static ListComparison compare(List<String> listed, List<String> reactor) {
        Set<String> onlyInDocument = new TreeSet<>(listed);
        reactor.forEach(onlyInDocument::remove);
        Set<String> onlyInReactor = new TreeSet<>(reactor);
        listed.forEach(onlyInReactor::remove);
        return new ListComparison(listed.size(), reactor.size(), onlyInDocument, onlyInReactor);
    }

    /**
     * The module names a document lists in the block that follows {@code anchor}, in listed order
     * and before any de-duplication. This is the extraction the document assertions and the control
     * share.
     * <p>
     * The block is the first run of module bullets after the anchor line. Lines between the anchor
     * and the first bullet — a blank line, an introductory sentence — are passed over, but a heading
     * reached before any bullet means the anchor's section lists nothing. Inside the block a blank
     * line and an indented continuation line are skipped; the first line that is neither those nor a
     * bullet ends it. The module name is the bullet's first backticked token with one trailing
     * {@code /} removed.
     *
     * @param document the document text
     * @param anchor   the line the module list follows, compared ignoring trailing whitespace
     * @return the listed module names, or empty when the document carries no such anchor line
     */
    private static Optional<List<String>> listedModules(String document, String anchor) {
        List<String> lines = document.lines().toList();
        int index = 0;
        while (index < lines.size() && !anchor.equals(lines.get(index).stripTrailing())) {
            index++;
        }
        if (index == lines.size()) {
            return Optional.empty();
        }
        index++;
        while (index < lines.size() && !MODULE_BULLET.matcher(lines.get(index)).lookingAt()) {
            if (lines.get(index).startsWith(HEADING_PREFIX)) {
                return Optional.of(List.of());
            }
            index++;
        }
        List<String> modules = new ArrayList<>();
        for (; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher bullet = MODULE_BULLET.matcher(line);
            if (bullet.lookingAt()) {
                modules.add(withoutDirectorySuffix(bullet.group(1)));
            } else if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
        }
        return Optional.of(modules);
    }

    /**
     * A bullet's token as a module name: one trailing {@code /} removed, so {@code api-sheriff/}
     * names the module {@code api-sheriff}.
     *
     * @param token the first backticked token of a module bullet
     * @return the module name
     */
    private static String withoutDirectorySuffix(String token) {
        return token.endsWith(DIRECTORY_SUFFIX) ? token.substring(0, token.length() - DIRECTORY_SUFFIX.length())
                : token;
    }

    /**
     * The reactor's module names: the text of each {@code <module>} child of every {@code <modules>}
     * element that is a direct child of {@code <project>}, in document order. A {@code <modules>}
     * element anywhere deeper — inside a profile — is not read.
     *
     * @param project the POM's document element
     * @return the reactor module names; empty when the element is not a {@code <project>} or
     *         declares no modules
     */
    private static List<String> reactorModules(Element project) {
        List<String> modules = new ArrayList<>();
        if (!"project".equals(project.getLocalName())) {
            return modules;
        }
        for (Element container : childElements(project, "modules")) {
            for (Element module : childElements(container, "module")) {
                modules.add(module.getTextContent().strip());
            }
        }
        return modules;
    }

    /**
     * The direct child elements of {@code parent} with the local name {@code name}.
     *
     * @param parent the element whose children are read
     * @param name   the local name to select
     * @return the matching children, in document order
     */
    private static List<Element> childElements(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && name.equals(child.getLocalName())) {
                matches.add(child);
            }
        }
        return matches;
    }

    /**
     * Parses a POM and returns its document element.
     *
     * @param xml the POM text
     * @return the document element
     * @throws IOException                  when the text cannot be read
     * @throws SAXException                 when the text is not well-formed, or declares a DOCTYPE
     * @throws ParserConfigurationException when the parser cannot be configured as required
     */
    private static Element parse(Reader xml) throws IOException, SAXException, ParserConfigurationException {
        return documentBuilder().parse(new InputSource(xml)).getDocumentElement();
    }

    /**
     * A namespace-aware builder with secure processing on and DOCTYPE declarations refused. The POM
     * is a trusted build input and carries no DOCTYPE, but a parser left able to resolve external
     * entities is a habit this project does not keep anywhere.
     *
     * @return the configured builder
     * @throws ParserConfigurationException when a required feature is not supported
     */
    private static DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /**
     * The repository root — the nearest ancestor of the working directory that holds the regular
     * file {@link #AGENTS_MD}.
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
            if (Files.isRegularFile(candidate.resolve(AGENTS_MD))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it holds " + AGENTS_MD + ", which is this contract's root marker and"
                + " one of the documents it asserts over");
    }
}
