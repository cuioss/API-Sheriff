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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the split of the integration-test run into a sequential and a concurrent failsafe execution:
 * the two executions of the {@code integration-tests} profile partition the IT classes, and no class
 * that runs concurrently shares one of the pieces of state this guard can name with another one.
 * <p>
 * <strong>Why this test exists.</strong> Which execution an IT class runs in is decided by the JUnit
 * tag {@value #SLEEP_BOUND} on the class and by two {@code maven-failsafe-plugin} executions that
 * select on it. Neither half is checked by the compiler, and getting either wrong stays green: an
 * execution that does not exclude the tag runs a class twice, one that excludes a tag nothing selects
 * stops running a class at all, and a class that counts the records of an instance log passes on a
 * record another class caused beside it. The IT lane itself cannot be asked — it needs the native
 * image and the compose stack, and a false green is green there too. This guard runs with the unit
 * tests of the module and reads both halves.
 * <p>
 * <strong>What it asserts.</strong>
 * <ul>
 * <li>The profile declares exactly two failsafe executions. The first has the id
 * {@value #DEFAULT_EXECUTION}, selects no tag, excludes exactly {@value #SLEEP_BOUND} and declares no
 * fork count. The second selects exactly {@value #SLEEP_BOUND}, excludes nothing, forks more than
 * once and does not reuse a fork. Neither declares the include pattern, the exclusions or the system
 * properties again: those stand on the plugin, for both.</li>
 * <li>The sequential execution is declared first, and an untagged IT class logs in through
 * {@link BffKeycloakLoginFlow}. The identity provider creates the keys of its realm on the first
 * login, and two first logins at the same instant were seen to fail one of them; the sequential
 * execution is what makes sure a login has completed before two can coincide.</li>
 * <li>The {@code jfr} profile keeps one execution that selects on no tag.</li>
 * <li>The tag is written only as a {@code @Tag} on a top-level {@code *IT} class.</li>
 * <li>No two tagged classes name the same instance log file, and no two declare the same fixed host
 * port.</li>
 * <li>A tagged class that drives the identity provider's admin API logs in only as users no other
 * class names: what it revokes or deletes is then its own.</li>
 * <li>A tagged class neither uses the stub identity-provider rig, whose container name, network and
 * port are fixed, nor attaches a compose container to a further network.</li>
 * </ul>
 * Every rule is a property of a class, not a list of classes.
 * <p>
 * <strong>What it does not assert.</strong> That two tagged classes are <em>safe</em> beside each
 * other. It reads source text: it sees the class that <em>reads</em> an instance log, not the one that
 * causes a record in it without naming the file; it sees a user named in code, not one a helper logs
 * in; and it knows nothing of timing margins. A class that fails only when it runs concurrently is
 * found by its failing, and the answer then is to take the tag off it, not to change the test.
 * <p>
 * <strong>Controls</strong> (ADR-0030). The population every rule selects is asserted non-empty
 * before the rule runs, and each detector is driven with deliberately wrong specimens it must reject
 * and a correct one it must accept. The specimens are strings in this file, which is excluded from
 * the scanned sources by name ({@link #SELF}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("the two failsafe executions partition the IT classes and concurrent classes share no named state")
class FailsafeConcurrencyContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String POM = "pom.xml";
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    /** This file, relative to {@link #TEST_SOURCES}: it holds the specimens and is not scanned. */
    private static final Path SELF = Path.of("de", "cuioss", "sheriff", "gateway", "integration",
            "FailsafeConcurrencyContractTest.java");

    private static final String SLEEP_BOUND = "sleep-bound";
    private static final String DEFAULT_EXECUTION = "default";
    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final String IT_PROFILE = "integration-tests";
    private static final String JFR_PROFILE = "jfr";

    /** What both executions share and therefore only the plugin may declare. */
    private static final List<String> SHARED_CONFIGURATION =
            List.of("includes", "excludes", "systemPropertyVariables", "skipITs");

    /** The default user of {@link BffKeycloakLoginFlow}: a login that names no user logs in as it. */
    private static final String DEFAULT_USER = "USERNAME";

    private static final Pattern TEST_ANNOTATION_IMPORT = Pattern.compile(
            "(?m)^import org\\.junit\\.jupiter\\.(api\\.(Test|RepeatedTest|TestFactory|TestTemplate)"
                    + "|params\\.ParameterizedTest);");
    private static final Pattern TOP_LEVEL_TAG = Pattern.compile("(?m)^@Tag\\(\"sleep-bound\"\\)");
    private static final Pattern TAG_LITERAL = Pattern.compile("\"sleep-bound\"");
    private static final Pattern INSTANCE_LOG = Pattern.compile("\"(quarkus[a-z0-9-]*\\.log)\"");
    private static final Pattern FIXED_PORT = Pattern.compile("\\b[A-Z_]*PORT\\s*=\\s*(\\d{4,5});");
    private static final Pattern NAMED_USER = Pattern.compile("\\bBffKeycloakLoginFlow\\.([A-Z_]*USERNAME)\\b");
    private static final Pattern ADMIN_API = Pattern.compile("/admin/realms/");
    private static final Pattern LOGIN = Pattern.compile("\\bBffKeycloakLoginFlow\\.login\\(");

    /** A login through the flow's one-argument form, which names no user and logs the default one in. */
    private static final Pattern LOGIN_WITHOUT_USER = Pattern.compile("\\bBffKeycloakLoginFlow\\.login\\([^,()]*\\)");
    private static final Pattern STUB_RIG = Pattern.compile("\\bStubIdentityProviderRig\\b");

    /**
     * The harness method that attaches a compose container to a further network, by name: a class
     * that calls it imports it or qualifies it, and either spells the name. The specimen below names
     * it as an import, not as a call — {@code OneOffGatewayContainersTest} lists the files that
     * spell the call, and this file is not a caller.
     */
    private static final Pattern NETWORK_ATTACHMENT = Pattern.compile("\\bconnectToNetwork\\b");

    private static final String REMEDY = " Take the tag off the class; do not change the test.";

    // --- the real pom.xml and the real sources -----------------------------------------------------

    @Test
    @DisplayName("the integration-tests profile runs every IT class in exactly one of its two executions")
    void executionsPartitionTheClasses() throws Exception {
        List<ItExecution> executions = failsafeExecutions(parse(Files.readString(pom())), IT_PROFILE);

        assertFalse(executions.isEmpty(), pom() + " declares no " + FAILSAFE + " execution in the profile "
                + IT_PROFILE + ", so this guard would assert over nothing");
        assertEquals(List.of(), partitionViolations(executions));
    }

    @Test
    @DisplayName("the jfr profile keeps one execution that selects on no tag")
    void jfrProfileRunsOneSequentialExecution() throws Exception {
        List<ItExecution> executions = failsafeExecutions(parse(Files.readString(pom())), JFR_PROFILE);

        assertEquals(1, executions.size(), "the " + JFR_PROFILE + " profile runs every class in one execution");
        ItExecution only = executions.getFirst();
        assertAll(
                () -> assertEquals(Set.of(), only.groups(), "it selects on no tag"),
                () -> assertEquals(Set.of(), only.excludedGroups(), "and excludes none"),
                () -> assertEquals("", only.forkCount(), "and runs one class at a time"));
    }

    @Test
    @DisplayName("no class of the concurrent execution shares a log file, a port, a user it revokes or a fixed rig")
    void taggedClassesShareNoNamedState() throws Exception {
        List<ItSource> sources = sources();
        List<ItSource> tagged = select(sources, ItSource::isTagged);
        List<ItSource> sequential = select(sources, source -> source.isIntegrationTest() && !source.isTagged());

        assertAll("each rule selects something today, so none passes by matching nothing",
                () -> assertFalse(tagged.isEmpty(), "no class carries " + SLEEP_BOUND
                        + ", so the concurrent execution runs nothing"),
                () -> assertFalse(sequential.isEmpty(), "no IT class is untagged, so the sequential execution"
                        + " runs nothing"),
                () -> assertFalse(select(sequential, source -> LOGIN.matcher(source.text()).find()).isEmpty(),
                        "no untagged IT class logs in, so the first logins of a run would be concurrent ones"),
                () -> assertFalse(select(tagged, source -> !source.instanceLogs().isEmpty()).isEmpty(),
                        "no tagged class names an instance log"),
                () -> assertFalse(select(tagged, source -> !source.fixedPorts().isEmpty()).isEmpty(),
                        "no tagged class declares a fixed host port"),
                () -> assertFalse(select(tagged, ItSource::drivesTheAdminApi).isEmpty(),
                        "no tagged class drives the identity provider's admin API"),
                () -> assertFalse(select(sequential, ItSource::usesAFixedRig).isEmpty(),
                        "no untagged IT class uses the stub rig or attaches a compose container to a network"));
        assertEquals(List.of(), sharedStateViolations(sources));
    }

    // --- controls over the detectors ---------------------------------------------------------------

    @Test
    @DisplayName("the partition detector accepts the declared split and rejects each way of breaking it")
    void partitionDetectorRejectsBrokenSplits() {
        String sequential = execution(DEFAULT_EXECUTION, "", SLEEP_BOUND, "", "", "");
        String concurrent = execution("concurrent", SLEEP_BOUND, "", "3", "false", "");
        Function<String, List<String>> violations = executions -> {
            try {
                return partitionViolations(failsafeExecutions(parse(pomWith(executions)), IT_PROFILE));
            } catch (ParserConfigurationException | SAXException | IOException e) {
                throw new AssertionError("the specimen is not a readable POM: " + executions, e);
            }
        };

        assertAll(
                () -> assertEquals(List.of(), violations.apply(sequential + concurrent)),
                () -> assertFalse(violations.apply(execution(DEFAULT_EXECUTION, "", "", "", "", "") + concurrent)
                        .isEmpty(), "a sequential execution that does not exclude the tag was accepted"),
                () -> assertFalse(violations.apply(sequential).isEmpty(),
                        "a profile in which no execution selects the excluded tag was accepted"),
                () -> assertFalse(violations.apply(concurrent + sequential).isEmpty(),
                        "the concurrent execution declared before the sequential one was accepted"),
                () -> assertFalse(violations.apply(execution("sequential", "", SLEEP_BOUND, "", "", "") + concurrent)
                        .isEmpty(), "a sequential execution under another id than the inherited one was accepted"),
                () -> assertFalse(violations.apply(sequential + concurrent + concurrent).isEmpty(),
                        "two executions selecting the same tag were accepted"),
                () -> assertFalse(violations.apply(sequential
                                + execution("concurrent", SLEEP_BOUND, "", "1", "false", "")).isEmpty(),
                        "a concurrent execution with one fork was accepted"),
                () -> assertFalse(violations.apply(sequential
                                + execution("concurrent", SLEEP_BOUND, "", "3", "true", "")).isEmpty(),
                        "a concurrent execution that reuses its forks was accepted"),
                () -> assertFalse(violations.apply(execution(DEFAULT_EXECUTION, "", SLEEP_BOUND, "3", "", "")
                        + concurrent).isEmpty(), "a sequential execution with a fork count was accepted"),
                () -> assertFalse(violations.apply(sequential + execution("concurrent", SLEEP_BOUND, "", "3",
                                "false", "<includes><include>**/*IT.java</include></includes>")).isEmpty(),
                        "an execution that declares the include pattern for itself was accepted"));
    }

    @Test
    @DisplayName("the shared-state detector accepts isolated classes and rejects each kind of sharing")
    void sharedStateDetectorRejectsEachSharing() {
        ItSource plain = specimen("PlainIT.java", "@Tag(\"sleep-bound\")\n", "");
        ItSource untagged = specimen("OtherIT.java", "", "  String log = \"quarkus-refresh.log\";\n");
        ItSource readsALog = specimen("LogIT.java", "@Tag(\"sleep-bound\")\n",
                "  String log = \"quarkus-refresh.log\";\n  int A_PORT = 10462;\n");
        ItSource readsTheSameLog = specimen("SameLogIT.java", "@Tag(\"sleep-bound\")\n",
                "  String log = \"quarkus-refresh.log\";\n");
        ItSource takesTheSamePort = specimen("SamePortIT.java", "@Tag(\"sleep-bound\")\n", "  int B_PORT = 10462;\n");
        ItSource revokesItsOwnUser = specimen("RevokeIT.java", "@Tag(\"sleep-bound\")\n",
                "  post(\"/admin/realms/x\"); login(BffKeycloakLoginFlow.OWN_USERNAME);\n");
        ItSource sharesTheRevokedUser = specimen("SharingIT.java", "",
                "  login(BffKeycloakLoginFlow.OWN_USERNAME);\n");
        ItSource revokesTheDefaultUser = specimen("DefaultUserIT.java", "@Tag(\"sleep-bound\")\n",
                "  post(\"/admin/realms/x\"); BffKeycloakLoginFlow.login(path);\n");
        ItSource usesTheStubRig = specimen("RigIT.java", "@Tag(\"sleep-bound\")\n", "  StubIdentityProviderRig rig;\n");
        ItSource attachesAContainer = specimen("AttachIT.java",
                "import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.connectToNetwork;\n"
                        + "@Tag(\"sleep-bound\")\n",
                "");
        ItSource tagOnAMethod = specimen("SplitIT.java", "", "    @Tag(\"sleep-bound\")\n    void m() {}\n");
        ItSource taggedUnitTest = specimen("SomeTest.java", "@Tag(\"sleep-bound\")\n", "");

        assertAll(
                () -> assertEquals(List.of(), sharedStateViolations(List.of(plain, untagged, readsALog,
                        revokesItsOwnUser))),
                () -> assertFalse(sharedStateViolations(List.of(readsALog, readsTheSameLog)).isEmpty(),
                        "two tagged classes naming the same instance log were accepted"),
                () -> assertFalse(sharedStateViolations(List.of(readsALog, takesTheSamePort)).isEmpty(),
                        "two tagged classes declaring the same host port were accepted"),
                () -> assertFalse(sharedStateViolations(List.of(revokesItsOwnUser, sharesTheRevokedUser)).isEmpty(),
                        "a tagged class driving the admin API for a user another class names was accepted"),
                () -> assertFalse(sharedStateViolations(List.of(revokesTheDefaultUser)).isEmpty(),
                        "a tagged class driving the admin API while naming no user of its own was accepted"),
                () -> assertFalse(sharedStateViolations(List.of(usesTheStubRig)).isEmpty(),
                        "a tagged class using the stub rig was accepted"),
                () -> assertFalse(sharedStateViolations(List.of(attachesAContainer)).isEmpty(),
                        "a tagged class attaching a compose container to a network was accepted"),
                () -> assertFalse(sharedStateViolations(List.of(tagOnAMethod)).isEmpty(),
                        "the tag on a method was accepted"),
                () -> assertFalse(sharedStateViolations(List.of(taggedUnitTest)).isEmpty(),
                        "the tag on a class failsafe does not run was accepted"));
    }

    // --- the rules ---------------------------------------------------------------------------------

    /**
     * Why the executions do not run every IT class exactly once, the untagged ones first; empty when
     * they do.
     *
     * @param executions the failsafe executions of the profile, in document order
     * @return one message per defect
     */
    private static List<String> partitionViolations(List<ItExecution> executions) {
        List<String> violations = new ArrayList<>();
        if (executions.size() != 2) {
            violations.add("expected exactly two executions, a sequential and a concurrent one, found "
                    + executions.stream().map(ItExecution::id).toList());
            return violations;
        }
        ItExecution sequential = executions.getFirst();
        ItExecution concurrent = executions.getLast();
        if (!DEFAULT_EXECUTION.equals(sequential.id())) {
            violations.add("the first execution has the id '" + sequential.id() + "'; it has to be "
                    + DEFAULT_EXECUTION + ", declared first: under another id the execution inherited from the"
                    + " root pom.xml stays beside it and runs every class again, and declared second it lets the"
                    + " first logins of a run be concurrent ones");
        }
        if (!sequential.groups().isEmpty() || !sequential.excludedGroups().equals(Set.of(SLEEP_BOUND))) {
            violations.add("execution " + sequential.id() + " selects " + sequential.groups() + " and excludes "
                    + sequential.excludedGroups() + "; it has to select nothing and exclude exactly " + SLEEP_BOUND
                    + ", or a class runs twice or not at all");
        }
        if (!sequential.forkCount().isEmpty()) {
            violations.add("execution " + sequential.id() + " declares forkCount " + sequential.forkCount()
                    + "; the classes it runs are the ones that must not run side by side");
        }
        if (!concurrent.groups().equals(Set.of(SLEEP_BOUND)) || !concurrent.excludedGroups().isEmpty()) {
            violations.add("execution " + concurrent.id() + " selects " + concurrent.groups() + " and excludes "
                    + concurrent.excludedGroups() + "; it has to select exactly " + SLEEP_BOUND + " and exclude"
                    + " nothing, or a class runs twice or not at all");
        }
        if (!concurrent.forkCount().matches("[2-9]|[1-9]\\d+")) {
            violations.add("execution " + concurrent.id() + " declares forkCount '" + concurrent.forkCount()
                    + "'; a whole number above one is what makes it concurrent");
        }
        if (!"false".equals(concurrent.reuseForks())) {
            violations.add("execution " + concurrent.id() + " declares reuseForks '" + concurrent.reuseForks()
                    + "'; it has to be false: IT classes set JVM-wide REST Assured state the next class in the"
                    + " same JVM would inherit");
        }
        for (ItExecution execution : executions) {
            if (!execution.redeclared().isEmpty()) {
                violations.add("execution " + execution.id() + " declares " + execution.redeclared() + " for itself;"
                        + " both executions share them, so they stand on the plugin and nowhere else");
            }
        }
        return violations;
    }

    /**
     * Why the tagged classes cannot run side by side, as far as source text shows it; empty when
     * nothing does.
     *
     * @param sources every source file under the test source root
     * @return one message per defect
     */
    private static List<String> sharedStateViolations(List<ItSource> sources) {
        List<String> violations = new ArrayList<>();
        for (ItSource source : sources) {
            if (source.namesTheTagOutsideATopLevelTag()) {
                violations.add(source.path() + " names the tag " + SLEEP_BOUND + " somewhere other than a @Tag on"
                        + " its top-level class; on a nested class or a method it splits one class across two"
                        + " executions");
            }
            if (source.isTagged() && !source.isIntegrationTest()) {
                violations.add(source.path() + " carries " + SLEEP_BOUND + " and is not an *IT class with tests,"
                        + " so no failsafe execution selects it by that tag");
            }
        }
        List<ItSource> tagged = select(sources, ItSource::isTagged);
        shared(tagged, ItSource::instanceLogs).forEach((log, classes) -> violations.add(classes + " all name the"
                + " instance log " + log + " and all carry " + SLEEP_BOUND + ": a record one of them causes can"
                + " satisfy a count another one makes. Keep the longest of them tagged." + REMEDY));
        shared(tagged, ItSource::fixedPorts).forEach((port, classes) -> violations.add(classes + " all declare"
                + " the host port " + port + " and all carry " + SLEEP_BOUND + "." + REMEDY));
        for (ItSource source : tagged) {
            if (source.usesAFixedRig()) {
                violations.add(source.path() + " carries " + SLEEP_BOUND + " and uses the stub identity-provider"
                        + " rig or attaches a compose container to a further network; both are state every other"
                        + " class meets." + REMEDY);
            }
            if (source.drivesTheAdminApi()) {
                violations.addAll(revocationViolations(source, sources));
            }
        }
        return violations;
    }

    /** Why a tagged class that drives the admin API can end a session it did not create. */
    private static List<String> revocationViolations(ItSource source, List<ItSource> sources) {
        List<String> violations = new ArrayList<>();
        Set<String> users = source.namedUsers();
        if (users.isEmpty() || users.contains(DEFAULT_USER) || LOGIN_WITHOUT_USER.matcher(source.text()).find()) {
            violations.add(source.path() + " carries " + SLEEP_BOUND + ", drives the identity provider's admin API"
                    + " and logs in as the default user or names no user: give the suite a user of its own in"
                    + " integration-realm.json and BffKeycloakLoginFlow.");
        }
        for (String user : users) {
            List<String> others = sources.stream()
                    .filter(other -> other != source && !other.declaresTheUsers())
                    .filter(other -> other.namedUsers().contains(user))
                    .map(ItSource::path).toList();
            if (!others.isEmpty()) {
                violations.add(source.path() + " carries " + SLEEP_BOUND + " and drives the identity provider's"
                        + " admin API for " + user + ", which " + others + " log in as too: what it revokes or"
                        + " deletes could be theirs. Give the suite a user of its own.");
            }
        }
        return violations;
    }

    /** What more than one class names, with the classes that name it. */
    private static Map<String, List<String>> shared(List<ItSource> sources, Function<ItSource, Set<String>> named) {
        Map<String, List<String>> classesByName = new TreeMap<>();
        for (ItSource source : sources) {
            for (String name : named.apply(source)) {
                classesByName.computeIfAbsent(name, key -> new ArrayList<>()).add(source.path());
            }
        }
        classesByName.values().removeIf(classes -> classes.size() < 2);
        return classesByName;
    }

    // --- reading ------------------------------------------------------------------------------------

    /**
     * One {@code maven-failsafe-plugin} execution, as far as this guard reads it.
     *
     * @param id             the execution id, empty when it declares none
     * @param groups         the tags the execution selects
     * @param excludedGroups the tags the execution excludes
     * @param forkCount      the execution's {@code forkCount} text, empty when it declares none
     * @param reuseForks     the execution's {@code reuseForks} text, empty when it declares none
     * @param redeclared     the elements of {@link #SHARED_CONFIGURATION} the execution declares itself
     */
    private record ItExecution(String id, Set<String> groups, Set<String> excludedGroups, String forkCount,
    String reuseForks, List<String> redeclared) {
    }

    /**
     * A source file under the test source root.
     *
     * @param path the path relative to the test source root, with forward slashes
     * @param text the file's content
     */
    private record ItSource(String path, String text) {

        /** A class failsafe runs: named {@code *IT} and holding tests. */
        boolean isIntegrationTest() {
            return path.endsWith("IT.java") && TEST_ANNOTATION_IMPORT.matcher(text).find();
        }

        boolean isTagged() {
            return TOP_LEVEL_TAG.matcher(text).find();
        }

        boolean namesTheTagOutsideATopLevelTag() {
            return TAG_LITERAL.matcher(text).results().count() != TOP_LEVEL_TAG.matcher(text).results().count();
        }

        Set<String> instanceLogs() {
            return firstGroups(INSTANCE_LOG);
        }

        Set<String> fixedPorts() {
            return firstGroups(FIXED_PORT);
        }

        Set<String> namedUsers() {
            return firstGroups(NAMED_USER);
        }

        boolean drivesTheAdminApi() {
            return ADMIN_API.matcher(text).find();
        }

        boolean usesAFixedRig() {
            return STUB_RIG.matcher(text).find() || NETWORK_ATTACHMENT.matcher(text).find();
        }

        /** The helper that declares the users names each of them and logs none in for itself. */
        boolean declaresTheUsers() {
            return path.endsWith("BffKeycloakLoginFlow.java");
        }

        private Set<String> firstGroups(Pattern pattern) {
            Set<String> found = new TreeSet<>();
            pattern.matcher(text).results().forEach(result -> found.add(result.group(1)));
            return found;
        }
    }

    private static List<ItSource> select(List<ItSource> sources, Predicate<ItSource> rule) {
        return sources.stream().filter(rule).toList();
    }

    /** A specimen for the shared-state control: a test class that exists only as text. */
    private static ItSource specimen(String path, String annotations, String body) {
        return new ItSource(path, "import org.junit.jupiter.api.Test;\n" + annotations + "class Specimen {\n" + body
                + "}\n");
    }

    /**
     * Every source file under the test source root, this file excepted.
     *
     * @return the files, in path order
     * @throws IOException when the source tree cannot be read
     */
    private static List<ItSource> sources() throws IOException {
        Path sourceRoot = MODULE.resolve(TEST_SOURCES);
        assertTrue(Files.isDirectory(sourceRoot), sourceRoot + " is not a directory");
        assertTrue(Files.isRegularFile(sourceRoot.resolve(SELF)),
                sourceRoot.resolve(SELF) + " is not where this guard expects itself, so its specimens would be"
                        + " scanned as if they were sources");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            files = walk.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".java"))
                    .map(sourceRoot::relativize).filter(file -> !file.equals(SELF)).sorted().toList();
        }
        List<ItSource> sources = new ArrayList<>();
        for (Path file : files) {
            sources.add(new ItSource(file.toString().replace('\\', '/'), Files.readString(sourceRoot.resolve(file))));
        }
        return sources;
    }

    private static Path pom() {
        Path pom = MODULE.resolve(POM);
        assertTrue(Files.isRegularFile(pom), pom + " does not exist; this guard reads the module's own pom.xml"
                + " and expects the module root as its working directory");
        return pom;
    }

    /**
     * The failsafe executions declared under {@code build/plugins} of one profile.
     *
     * @param pom     the parsed POM
     * @param profile the profile id
     * @return the executions in document order, empty when the profile does not declare the plugin
     */
    private static List<ItExecution> failsafeExecutions(Document pom, String profile) {
        List<ItExecution> executions = new ArrayList<>();
        for (Element profiles : children(pom.getDocumentElement(), "profiles")) {
            for (Element candidate : children(profiles, "profile")) {
                if (profile.equals(childText(candidate, "id"))) {
                    plugins(candidate).filter(plugin -> FAILSAFE.equals(childText(plugin, "artifactId")))
                            .flatMap(plugin -> children(plugin, "executions").stream())
                            .flatMap(holder -> children(holder, "execution").stream())
                            .map(FailsafeConcurrencyContractTest::itExecution)
                            .forEach(executions::add);
                }
            }
        }
        return executions;
    }

    private static Stream<Element> plugins(Element profile) {
        return children(profile, "build").stream()
                .flatMap(build -> children(build, "plugins").stream())
                .flatMap(plugins -> children(plugins, "plugin").stream());
    }

    private static ItExecution itExecution(Element execution) {
        String groups = "";
        String excludedGroups = "";
        String forkCount = "";
        String reuseForks = "";
        List<String> redeclared = new ArrayList<>();
        for (Element configuration : children(execution, "configuration")) {
            groups = childText(configuration, "groups");
            excludedGroups = childText(configuration, "excludedGroups");
            forkCount = childText(configuration, "forkCount");
            reuseForks = childText(configuration, "reuseForks");
            SHARED_CONFIGURATION.stream().filter(name -> !children(configuration, name).isEmpty())
                    .forEach(redeclared::add);
        }
        return new ItExecution(childText(execution, "id"), tags(groups), tags(excludedGroups), forkCount, reuseForks,
                redeclared);
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

    /** A POM whose integration-tests profile holds the given failsafe executions, for the control. */
    private static String pomWith(String executions) {
        return "<project><profiles><profile><id>" + IT_PROFILE + "</id><build><plugins><plugin><artifactId>"
                + FAILSAFE + "</artifactId><executions>" + executions
                + "</executions></plugin></plugins></build></profile></profiles></project>";
    }

    private static String execution(String id, String groups, String excludedGroups, String forkCount,
            String reuseForks, String further) {
        return "<execution><id>" + id + "</id><configuration><groups>" + groups + "</groups><excludedGroups>"
                + excludedGroups + "</excludedGroups><forkCount>" + forkCount + "</forkCount><reuseForks>"
                + reuseForks + "</reuseForks>" + further + "</configuration></execution>";
    }
}
