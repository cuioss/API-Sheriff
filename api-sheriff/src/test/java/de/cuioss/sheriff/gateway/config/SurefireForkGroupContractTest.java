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

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Pins the split of this module's unit run into three surefire fork groups: the executions declared
 * in {@code api-sheriff/pom.xml} partition the test classes, and every class that needs a particular
 * group carries the tag that puts it there.
 * <p>
 * <strong>Why this test exists.</strong> Which JVM a test class runs in is decided by a JUnit tag on
 * the class and by three {@code maven-surefire-plugin} executions that select on those tags. Neither
 * half is checked by the compiler, and getting either wrong stays green: an execution that excludes
 * one tag too few runs a class twice, one that excludes a tag nothing selects stops running a class
 * at all, and a class that replaces the JVM default trust store runs happily in a shared JVM until
 * the class after it dials out. This guard reads both halves and fails the build instead.
 * <p>
 * <strong>What it asserts.</strong>
 * <ul>
 * <li>The executions partition the tags: {@code default-test} selects nothing and excludes exactly
 * the tags the other executions select, each of those selects exactly one tag, and no tag is
 * selected twice.</li>
 * <li>The execution that selects {@value #ISOLATED_FORK} does not reuse its fork.</li>
 * <li>A class annotated {@code @QuarkusTest} carries one of the two tags.</li>
 * <li>A class that names {@code SanMismatchedJwksServer}, creates a Vert.x instance, sets a logger
 * level through {@code TestLogLevel.addLogger}, or is an architecture test in the {@code arch}
 * package carries {@value #ISOLATED_FORK}.</li>
 * <li>No class carries both tags, and a fork tag is written only as a {@code @Tag} on a top-level
 * class, never on a nested class or a method, where it would split one class across two JVMs.</li>
 * </ul>
 * Every rule is a property of a class, not a list of classes: a new test class that boots Quarkus or
 * creates a Vert.x instance is held to it without this file being edited. The only classes named
 * here are the near-misses of {@link #nearMissesAreNotSelected()}.
 * <p>
 * <strong>What it does not assert.</strong> That a class is <em>safe</em> in the JVM it shares. The
 * rules name the hazards known when the split was made; a class that changes JVM-wide state in a way
 * no rule recognises is found by its failing in a shared JVM, and the answer then is to tag it
 * {@value #ISOLATED_FORK} and, where the hazard has a recognisable shape, to add a rule. It also
 * reads source text, not bytecode: it sees a class that creates a Vert.x instance itself, not one
 * that is handed an instance by a helper.
 * <p>
 * <strong>Controls</strong> (ADR-0030). The populations every rule selects are asserted non-empty
 * before the rule runs. Each detector is driven with a deliberately wrong specimen it must reject and
 * a correct one it must accept. Real near-misses are asserted to keep their near-miss property and
 * to stay unselected. The specimens are strings in this file, and this file is excluded from the
 * scanned sources by name ({@link #SELF}) rather than by the accident of how it is written.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("surefire fork groups partition the test classes and every class carries the tag it needs")
class SurefireForkGroupContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String POM = "pom.xml";
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    /** This file, relative to {@link #TEST_SOURCES}: it holds the specimens and is not scanned. */
    private static final Path SELF = Path.of("de", "cuioss", "sheriff", "gateway", "config",
            "SurefireForkGroupContractTest.java");

    private static final String QUARKUS_FORK = "quarkus-fork";
    private static final String ISOLATED_FORK = "isolated-fork";
    private static final Set<String> FORK_TAGS = Set.of(QUARKUS_FORK, ISOLATED_FORK);

    private static final String DEFAULT_EXECUTION = "default-test";
    private static final String SUREFIRE = "maven-surefire-plugin";

    private static final Pattern TEST_ANNOTATION_IMPORT = Pattern.compile(
            "(?m)^import org\\.junit\\.jupiter\\.(api\\.(Test|RepeatedTest|TestFactory|TestTemplate)"
                    + "|params\\.ParameterizedTest);");
    private static final Pattern TOP_LEVEL_TAG = Pattern.compile("(?m)^@Tag\\(\"([^\"]+)\"\\)");
    private static final Pattern FORK_TAG_LITERAL = Pattern.compile("\"(quarkus-fork|isolated-fork)\"");
    private static final Pattern QUARKUS_TEST = Pattern.compile("(?m)^@QuarkusTest\\b");
    private static final Pattern TRUST_STORE_FIXTURE = Pattern.compile("\\bSanMismatchedJwksServer\\b");
    private static final Pattern VERTX_CREATION = Pattern.compile("\\bVertx\\.vertx\\(");
    private static final Pattern LOGGER_LEVEL_CHANGE = Pattern.compile("\\bTestLogLevel\\.[A-Z]+\\.addLogger\\(");
    private static final Pattern ARCH_TEST_PATH = Pattern.compile("(^|.*/)arch/[A-Za-z0-9_]+ArchTest\\.java$");

    private static final String REASON_TRUST_STORE = "names SanMismatchedJwksServer, which replaces the JVM"
            + " default trust store";
    private static final String REASON_VERTX = "creates a Vert.x instance";
    /**
     * The level stays on the logger after the class has finished. {@code TokenRefreshCoordinatorTest}
     * leaves its logger at {@code INFO} that way, and in a shared JVM {@code TokenRefreshLogHygieneTest}
     * then failed its own control, because the {@code DEBUG} record it checks for was no longer
     * captured.
     */
    private static final String REASON_LOGGER_LEVEL = "sets a logger level through TestLogLevel.addLogger, which"
            + " stays set for every class that runs after it in the same JVM";
    private static final String REASON_ARCH = "is an architecture test in the arch package";

    private static final String REMEDY = " Tag the class; do not change the test.";

    // --- the real pom.xml and the real sources -----------------------------------------------------

    @Test
    @DisplayName("default-test excludes exactly the tags the other executions select")
    void executionsPartitionTheForkTags() throws Exception {
        List<ForkExecution> executions = surefireExecutions(parse(Files.readString(pom())));

        assertFalse(executions.isEmpty(), pom() + " declares no " + SUREFIRE + " execution under"
                + " build/plugins, so this guard would assert over nothing");
        assertEquals(List.of(), partitionViolations(executions));
    }

    @Test
    @DisplayName("the execution that selects isolated-fork gives every class a JVM of its own")
    void isolatedExecutionForksPerClass() throws Exception {
        List<ForkExecution> executions = surefireExecutions(parse(Files.readString(pom())));

        assertTrue(executions.stream().anyMatch(execution -> execution.groups().contains(ISOLATED_FORK)),
                pom() + " declares no execution that selects " + ISOLATED_FORK);
        assertEquals(List.of(), forkPolicyViolations(executions));
    }

    @Test
    @DisplayName("every test class that needs a fork group carries its tag, and none carries both")
    void everyClassCarriesTheTagItNeeds() throws Exception {
        List<TestSource> sources = testSources();

        assertAll("each rule selects something today, so none passes by matching nothing",
                () -> assertFalse(sources.isEmpty(), "no test class found under " + TEST_SOURCES),
                () -> assertFalse(select(sources, TestSource::isQuarkusTest).isEmpty(),
                        "no class is annotated @QuarkusTest"),
                () -> assertFalse(selectByReason(sources, REASON_TRUST_STORE).isEmpty(),
                        "no test class names SanMismatchedJwksServer"),
                () -> assertFalse(selectByReason(sources, REASON_VERTX).isEmpty(),
                        "no test class creates a Vert.x instance"),
                () -> assertFalse(selectByReason(sources, REASON_LOGGER_LEVEL).isEmpty(),
                        "no test class sets a logger level through TestLogLevel.addLogger"),
                () -> assertFalse(selectByReason(sources, REASON_ARCH).isEmpty(),
                        "no architecture test found in the arch package"),
                () -> assertFalse(select(sources, source -> source.forkTags().contains(QUARKUS_FORK)).isEmpty(),
                        "no class carries " + QUARKUS_FORK + ", so that execution runs nothing"),
                () -> assertFalse(select(sources, source -> source.forkTags().contains(ISOLATED_FORK)).isEmpty(),
                        "no class carries " + ISOLATED_FORK + ", so that execution runs nothing"),
                () -> assertFalse(select(sources, source -> source.forkTags().isEmpty()).isEmpty(),
                        "no class is untagged, so " + DEFAULT_EXECUTION + " runs nothing"));
        assertEquals(List.of(), sources.stream().flatMap(source -> membershipViolations(source).stream()).toList());
    }

    /**
     * Matched positive controls: real files that look like a member of a selected population and are
     * not one. Each near-miss property is asserted first, so the control cannot pass because the
     * file was renamed or lost the property.
     */
    @Test
    @DisplayName("real near-misses keep their near-miss property and are not selected")
    void nearMissesAreNotSelected() throws Exception {
        Path sourceRoot = MODULE.resolve(TEST_SOURCES);
        Path gateway = Path.of("de", "cuioss", "sheriff", "gateway");
        Path mentionsQuarkusTest = gateway.resolve(Path.of("quarkus", "BffRuntimeProducerTest.java"));
        Path edgeWithoutVertx = gateway.resolve(Path.of("edge", "GatewayEdgeRouteRetryAfterTest.java"));
        Path fixtureItself = gateway.resolve(Path.of("auth", "SanMismatchedJwksServer.java"));
        TestSource mentioning = read(sourceRoot, mentionsQuarkusTest);
        TestSource edge = read(sourceRoot, edgeWithoutVertx);
        TestSource fixture = read(sourceRoot, fixtureItself);

        assertAll(
                () -> assertTrue(mentioning.text().contains("@QuarkusTest"),
                        mentionsQuarkusTest + " no longer mentions @QuarkusTest in a comment"),
                () -> assertFalse(mentioning.isQuarkusTest(),
                        mentionsQuarkusTest + " mentions @QuarkusTest only in a comment and must not count as one"),
                () -> assertTrue(edge.isTestClass(), edgeWithoutVertx + " is no longer a test class"),
                () -> assertEquals(List.of(), edge.isolationReasons(),
                        edgeWithoutVertx + " is in the edge package but creates no Vert.x instance"),
                () -> assertTrue(TRUST_STORE_FIXTURE.matcher(fixture.text()).find(),
                        fixtureItself + " no longer names the fixture it declares"),
                () -> assertFalse(fixture.isTestClass(),
                        fixtureItself + " declares the fixture and is not a test class, so no rule applies to it"));
    }

    // --- controls over the detectors ---------------------------------------------------------------

    @Test
    @DisplayName("the partition detector accepts a correct split and rejects each way of breaking it")
    void partitionDetectorRejectsBrokenSplits() {
        String correct = pomWith(execution(DEFAULT_EXECUTION, "", "quarkus-fork,isolated-fork", "true")
                + execution("quarkus", QUARKUS_FORK, "", "true") + execution("isolated", ISOLATED_FORK, "", "false"));
        String runsAClassTwice = pomWith(execution(DEFAULT_EXECUTION, "", QUARKUS_FORK, "true")
                + execution("quarkus", QUARKUS_FORK, "", "true") + execution("isolated", ISOLATED_FORK, "", "false"));
        String neverRunsAClass = pomWith(execution(DEFAULT_EXECUTION, "", "quarkus-fork,isolated-fork", "true")
                + execution("quarkus", QUARKUS_FORK, "", "true"));
        String selectsATagTwice = pomWith(execution(DEFAULT_EXECUTION, "", "quarkus-fork,isolated-fork", "true")
                + execution("quarkus", QUARKUS_FORK, "", "true") + execution("isolated", ISOLATED_FORK, "", "false")
                + execution("again", ISOLATED_FORK, "", "false"));
        String sharesTheIsolatedFork = pomWith(execution(DEFAULT_EXECUTION, "", "quarkus-fork,isolated-fork", "true")
                + execution("quarkus", QUARKUS_FORK, "", "true") + execution("isolated", ISOLATED_FORK, "", "true"));

        assertAll(
                () -> assertEquals(List.of(), partitionViolations(surefireExecutions(parse(correct)))),
                () -> assertEquals(List.of(), forkPolicyViolations(surefireExecutions(parse(correct)))),
                () -> assertFalse(partitionViolations(surefireExecutions(parse(runsAClassTwice))).isEmpty(),
                        "a default execution that does not exclude a selected tag was accepted"),
                () -> assertFalse(partitionViolations(surefireExecutions(parse(neverRunsAClass))).isEmpty(),
                        "a default execution that excludes a tag nothing selects was accepted"),
                () -> assertFalse(partitionViolations(surefireExecutions(parse(selectsATagTwice))).isEmpty(),
                        "two executions selecting the same tag were accepted"),
                () -> assertFalse(forkPolicyViolations(surefireExecutions(parse(sharesTheIsolatedFork))).isEmpty(),
                        "an isolated execution that reuses its fork was accepted"));
    }

    @Test
    @DisplayName("the membership detector accepts a tagged class and rejects each misplaced one")
    void membershipDetectorRejectsMisplacedClasses() {
        TestSource quarkusTagged = specimen("quarkus/BootTest.java", "@QuarkusTest\n@Tag(\"quarkus-fork\")\n");
        TestSource quarkusIsolated = specimen("quarkus/BootTest.java", "@QuarkusTest\n@Tag(\"isolated-fork\")\n");
        TestSource quarkusUntagged = specimen("quarkus/BootTest.java", "@QuarkusTest\n");
        TestSource vertxTagged = specimen("edge/LiveTest.java", "@Tag(\"isolated-fork\")\n  v = Vertx.vertx();\n");
        TestSource vertxUntagged = specimen("edge/LiveTest.java", "  v = Vertx.vertx();\n");
        TestSource vertxInQuarkusFork = specimen("edge/LiveTest.java",
                "@Tag(\"quarkus-fork\")\n  v = Vertx.vertx();\n");
        TestSource trustStoreUntagged = specimen("auth/TrustTest.java", "  SanMismatchedJwksServer server;\n");
        TestSource loggerLevelUntagged = specimen("auth/LevelTest.java",
                "  TestLogLevel.ERROR.addLogger(Some.class);\n");
        TestSource archUntagged = specimen("de/cuioss/sheriff/gateway/arch/SomeArchTest.java", "");
        TestSource bothTags = specimen("auth/BothTest.java", "@Tag(\"quarkus-fork\")\n@Tag(\"isolated-fork\")\n");
        TestSource tagOnAMethod = specimen("auth/SplitTest.java", "    @Tag(\"isolated-fork\")\n    void m() {}\n");
        TestSource plain = specimen("auth/PlainTest.java", "");

        assertAll(
                () -> assertEquals(List.of(), membershipViolations(quarkusTagged)),
                () -> assertEquals(List.of(), membershipViolations(quarkusIsolated)),
                () -> assertEquals(List.of(), membershipViolations(vertxTagged)),
                () -> assertEquals(List.of(), membershipViolations(plain)),
                () -> assertFalse(membershipViolations(quarkusUntagged).isEmpty(),
                        "an untagged @QuarkusTest was accepted"),
                () -> assertFalse(membershipViolations(vertxUntagged).isEmpty(),
                        "an untagged class that creates a Vert.x instance was accepted"),
                () -> assertFalse(membershipViolations(vertxInQuarkusFork).isEmpty(),
                        "a class that creates a Vert.x instance was accepted in the shared Quarkus fork"),
                () -> assertFalse(membershipViolations(trustStoreUntagged).isEmpty(),
                        "an untagged user of SanMismatchedJwksServer was accepted"),
                () -> assertFalse(membershipViolations(loggerLevelUntagged).isEmpty(),
                        "an untagged class that sets a logger level was accepted"),
                () -> assertFalse(membershipViolations(archUntagged).isEmpty(),
                        "an untagged architecture test was accepted"),
                () -> assertFalse(membershipViolations(bothTags).isEmpty(), "a class carrying both tags was accepted"),
                () -> assertFalse(membershipViolations(tagOnAMethod).isEmpty(),
                        "a fork tag on a method was accepted"));
    }

    // --- the rules ---------------------------------------------------------------------------------

    /**
     * Why the executions do not partition the tags; empty when they do.
     *
     * @param executions the surefire executions declared under {@code build/plugins}
     * @return one message per defect
     */
    private static List<String> partitionViolations(List<ForkExecution> executions) {
        List<String> violations = new ArrayList<>();
        List<ForkExecution> defaults = executions.stream()
                .filter(execution -> DEFAULT_EXECUTION.equals(execution.id())).toList();
        if (defaults.size() != 1) {
            violations.add("expected exactly one execution with the id " + DEFAULT_EXECUTION + ", found "
                    + defaults.size() + "; without it the untagged classes are not configured here at all");
            return violations;
        }
        ForkExecution defaultExecution = defaults.getFirst();
        if (!defaultExecution.groups().isEmpty()) {
            violations.add(DEFAULT_EXECUTION + " selects " + defaultExecution.groups()
                    + "; it has to run every untagged class, so it may only exclude");
        }
        Set<String> selected = new LinkedHashSet<>();
        for (ForkExecution execution : executions) {
            if (execution == defaultExecution) {
                continue;
            }
            if (execution.groups().size() != 1 || !execution.excludedGroups().isEmpty()) {
                violations.add("execution " + execution.id() + " selects " + execution.groups() + " and excludes "
                        + execution.excludedGroups() + "; it has to select exactly one tag and exclude none");
            }
            for (String tag : execution.groups()) {
                if (!selected.add(tag)) {
                    violations.add("the tag " + tag + " is selected by more than one execution, so a class"
                            + " carrying it runs more than once");
                }
            }
        }
        Set<String> excluded = defaultExecution.excludedGroups();
        for (String tag : selected) {
            if (!excluded.contains(tag)) {
                violations.add(DEFAULT_EXECUTION + " does not exclude " + tag + ", so a class carrying it runs"
                        + " twice: once there and once in the execution that selects it");
            }
        }
        for (String tag : excluded) {
            if (!selected.contains(tag)) {
                violations.add(DEFAULT_EXECUTION + " excludes " + tag + " and no execution selects it, so a"
                        + " class carrying it never runs");
            }
        }
        if (!selected.equals(FORK_TAGS)) {
            violations.add("the executions select " + selected + ", but the membership rules of this guard are"
                    + " written for " + FORK_TAGS + "; change both together");
        }
        return violations;
    }

    /**
     * Why the isolated execution does not isolate; empty when it does.
     *
     * @param executions the surefire executions declared under {@code build/plugins}
     * @return one message per defect
     */
    private static List<String> forkPolicyViolations(List<ForkExecution> executions) {
        return executions.stream()
                .filter(execution -> execution.groups().contains(ISOLATED_FORK))
                .filter(execution -> !"false".equals(execution.reuseForks()))
                .map(execution -> "execution " + execution.id() + " selects " + ISOLATED_FORK + " with reuseForks '"
                        + execution.reuseForks() + "'; it has to be false, because the classes carrying that tag"
                        + " change state the next class in the same JVM would inherit")
                .toList();
    }

    /**
     * Why a test class is in the wrong fork group; empty when it is in the right one.
     *
     * @param source the test class
     * @return one message per defect
     */
    private static List<String> membershipViolations(TestSource source) {
        List<String> violations = new ArrayList<>();
        Set<String> tags = source.forkTags();
        if (source.namesAForkTagOutsideATopLevelTag()) {
            violations.add(source.path() + " names a fork tag somewhere other than a @Tag on its top-level class."
                    + " A fork tag on a nested class or a method splits one class across two JVMs");
        }
        if (tags.size() > 1) {
            violations.add(source.path() + " carries " + tags + "; a class runs in one fork group");
        }
        if (source.isQuarkusTest() && tags.isEmpty()) {
            violations.add(source.path() + " is a @QuarkusTest and carries no fork tag, so it would boot the"
                    + " application inside the JVM the untagged classes share. Tag it " + QUARKUS_FORK + ", or "
                    + ISOLATED_FORK + " where it cannot share a JVM with the other Quarkus tests." + REMEDY);
        }
        if (!tags.contains(ISOLATED_FORK)) {
            for (String reason : source.isolationReasons()) {
                violations.add(source.path() + " " + reason + " and does not carry " + ISOLATED_FORK
                        + ", so it would run in a JVM it shares with other classes." + REMEDY);
            }
        }
        return violations;
    }

    // --- reading ------------------------------------------------------------------------------------

    /**
     * One {@code maven-surefire-plugin} execution, as far as this guard reads it.
     *
     * @param id             the execution id
     * @param groups         the tags the execution selects
     * @param excludedGroups the tags the execution excludes
     * @param reuseForks     the execution's {@code reuseForks} text, empty when it declares none
     */
    private record ForkExecution(String id, Set<String> groups, Set<String> excludedGroups, String reuseForks) {
    }

    /**
     * A source file under the test source root.
     *
     * @param path the path relative to the test source root, with forward slashes
     * @param text the file's content
     */
    private record TestSource(String path, String text) {

        boolean isTestClass() {
            return TEST_ANNOTATION_IMPORT.matcher(text).find();
        }

        boolean isQuarkusTest() {
            return QUARKUS_TEST.matcher(text).find();
        }

        Set<String> forkTags() {
            Set<String> tags = new LinkedHashSet<>();
            Matcher matcher = TOP_LEVEL_TAG.matcher(text);
            while (matcher.find()) {
                if (FORK_TAGS.contains(matcher.group(1))) {
                    tags.add(matcher.group(1));
                }
            }
            return tags;
        }

        boolean namesAForkTagOutsideATopLevelTag() {
            long literals = FORK_TAG_LITERAL.matcher(text).results().count();
            long topLevelTags = TOP_LEVEL_TAG.matcher(text).results()
                    .filter(result -> FORK_TAGS.contains(result.group(1))).count();
            return literals != topLevelTags;
        }

        List<String> isolationReasons() {
            List<String> reasons = new ArrayList<>();
            if (TRUST_STORE_FIXTURE.matcher(text).find()) {
                reasons.add(REASON_TRUST_STORE);
            }
            if (VERTX_CREATION.matcher(text).find()) {
                reasons.add(REASON_VERTX);
            }
            if (LOGGER_LEVEL_CHANGE.matcher(text).find()) {
                reasons.add(REASON_LOGGER_LEVEL);
            }
            if (ARCH_TEST_PATH.matcher(path).matches()) {
                reasons.add(REASON_ARCH);
            }
            return reasons;
        }
    }

    private static List<TestSource> select(List<TestSource> sources, Predicate<TestSource> rule) {
        return sources.stream().filter(rule).toList();
    }

    private static List<TestSource> selectByReason(List<TestSource> sources, String reason) {
        return select(sources, source -> source.isolationReasons().contains(reason));
    }

    /** A specimen for the membership control: a test class that exists only as text. */
    private static TestSource specimen(String path, String body) {
        return new TestSource(path, "import org.junit.jupiter.api.Test;\n" + body + "class Specimen {\n}\n");
    }

    /**
     * Every test class under the test source root, this file excepted.
     *
     * @return the test classes, in path order
     * @throws IOException when the source tree cannot be read
     */
    private static List<TestSource> testSources() throws IOException {
        Path sourceRoot = MODULE.resolve(TEST_SOURCES);
        assertTrue(Files.isDirectory(sourceRoot), sourceRoot + " is not a directory");
        assertTrue(Files.isRegularFile(sourceRoot.resolve(SELF)),
                sourceRoot.resolve(SELF) + " is not where this guard expects itself, so its specimens would be"
                        + " scanned as if they were test classes");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            files = walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java"))
                    .map(sourceRoot::relativize).filter(file -> !file.equals(SELF)).sorted().toList();
        }
        List<TestSource> sources = new ArrayList<>();
        for (Path file : files) {
            TestSource source = read(sourceRoot, file);
            if (source.isTestClass()) {
                sources.add(source);
            }
        }
        return sources;
    }

    private static TestSource read(Path sourceRoot, Path relative) throws IOException {
        Path file = sourceRoot.resolve(relative);
        assertTrue(Files.isRegularFile(file), file + " does not exist");
        return new TestSource(relative.toString().replace('\\', '/'), Files.readString(file));
    }

    private static Path pom() {
        Path pom = MODULE.resolve(POM);
        assertTrue(Files.isRegularFile(pom), pom + " does not exist; this guard reads the module's own pom.xml"
                + " and expects the module root as its working directory");
        return pom;
    }

    /**
     * The surefire executions declared directly under {@code project/build/plugins}. Executions in a
     * profile or in {@code pluginManagement} are deliberately not read: the split has to hold for the
     * plain build.
     *
     * @param pom the parsed POM
     * @return the executions in document order, empty when the plugin is not declared there
     */
    private static List<ForkExecution> surefireExecutions(Document pom) {
        List<ForkExecution> executions = new ArrayList<>();
        for (Element build : children(pom.getDocumentElement(), "build")) {
            for (Element plugins : children(build, "plugins")) {
                for (Element plugin : children(plugins, "plugin")) {
                    if (SUREFIRE.equals(childText(plugin, "artifactId"))) {
                        for (Element holder : children(plugin, "executions")) {
                            for (Element execution : children(holder, "execution")) {
                                executions.add(forkExecution(execution));
                            }
                        }
                    }
                }
            }
        }
        return executions;
    }

    private static ForkExecution forkExecution(Element execution) {
        String groups = "";
        String excludedGroups = "";
        String reuseForks = "";
        for (Element configuration : children(execution, "configuration")) {
            groups = childText(configuration, "groups");
            excludedGroups = childText(configuration, "excludedGroups");
            reuseForks = childText(configuration, "reuseForks");
        }
        return new ForkExecution(childText(execution, "id"), tags(groups), tags(excludedGroups), reuseForks);
    }

    private static Set<String> tags(String commaSeparated) {
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : commaSeparated.split(",")) {
            if (!tag.isBlank()) {
                tags.add(tag.strip());
            }
        }
        return tags;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static String childText(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? "" : children.getFirst().getTextContent().strip();
    }

    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /** A POM holding the given surefire executions, for the partition control. */
    private static String pomWith(String executions) {
        return "<project><build><plugins><plugin><artifactId>" + SUREFIRE + "</artifactId><executions>"
                + executions + "</executions></plugin></plugins></build></project>";
    }

    private static String execution(String id, String groups, String excludedGroups, String reuseForks) {
        return "<execution><id>" + id + "</id><configuration><groups>" + groups + "</groups><excludedGroups>"
                + excludedGroups + "</excludedGroups><reuseForks>" + reuseForks
                + "</reuseForks></configuration></execution>";
    }
}
