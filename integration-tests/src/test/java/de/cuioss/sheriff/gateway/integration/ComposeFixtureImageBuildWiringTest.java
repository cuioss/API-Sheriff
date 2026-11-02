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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that every integration-test lane builds every Compose
 * fixture image the stack starts, rather than leaving the fixtures to {@code docker compose up -d}.
 * <p>
 * <strong>Why this test exists.</strong> {@code up -d} builds a service's image only when the daemon
 * holds no image of that name yet. A fixture image built once on a machine — {@code grpc-echo},
 * {@code passthrough-backend} — is then reused by every later run, so a change to the fixture's
 * Dockerfile or sources never reaches the container and the lane stays green against the old one.
 * The {@code docker-build-distroless} execution of the {@code integration-tests} profile and the
 * {@code docker-build-jfr} execution of the {@code jfr} profile close that by naming every fixture on
 * their {@code docker compose build} command line, so each run builds every image it starts. None of
 * this is checked by a compiler: a fixture service added to {@code docker-compose.yml} and not to the
 * command lines stays green, so does a profile-gated service named on them, and so does a further
 * profile that starts the stack and builds no fixture at all.
 * <p>
 * <strong>What it asserts.</strong> The lanes are derived from the POM rather than listed: every
 * profile with an {@code exec-maven-plugin} execution that Maven runs — bound to the {@code exec} goal at
 * {@code pre-integration-test}, the phase both lanes use, and not skipped — and that runs
 * {@code start-integration-container.sh} is a lane. Its image-build executions are its
 * {@code exec-maven-plugin} executions that run {@code docker} with a {@code build} argument and that Maven
 * runs before the stack starts: bound to the {@code exec} goal at that same phase, declared before the
 * start (Maven runs one plugin's executions of one phase in declaration order), and not skipped. Naming
 * the one phase keeps the guard free of a copy of Maven's lifecycle while still rejecting a misspelled
 * phase, which Maven would never run. An execution that carries the arguments but no goal, another phase,
 * a declaration after the start, or {@code <skip>true</skip>} is not an image build. The services of {@code docker-compose.yml} that declare a {@code build}
 * section and no {@code profiles} — the services the IT lanes start from a build — are exactly the
 * services named after the {@code build} argument of every such execution, and a lane with no such
 * execution is a defect. A profile-gated service such as {@code k6} ({@code profiles: [benchmark]}) is
 * started by no IT lane, so no IT lane builds it. The derived lanes must include the two lanes this
 * guard was written against, so a derivation that stops seeing them fails instead of asserting over
 * fewer lanes.
 * <p>
 * <strong>What it does not prove.</strong> That the build ran, that Docker rebuilt a layer rather
 * than reusing one from its cache, or that the running container uses the image the build produced:
 * those are properties of a Docker daemon, which this test never reaches. It does not read the
 * Compose overlays either — {@code docker-compose.jfr.yml} re-declares only the {@code api-sheriff}
 * build, and {@code docker-compose.benchmark.yml} declares none — so a {@code build} section added to
 * an overlay alone is not seen. It reads this module's POM only, so a lane another module's POM
 * declares is not seen, and neither is a profile that starts the stack by any means other than
 * {@code start-integration-container.sh}. It parses the committed descriptor and the committed POM only; it starts no
 * container and reaches no network.
 * <p>
 * <strong>Controls</strong> ("Comment-Only Invariants Become Fitness Functions" in
 * {@code doc/development/test-corpus-integrity.adoc}). Both populations the rule selects are asserted
 * before the rule runs — the fixtures non-empty, the lanes a superset of the known ones — and the lane
 * selector and the detector are driven with deliberately wrong specimens they must reject and a correct
 * one they must accept.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("every IT lane builds every compose fixture image the stack starts")
class ComposeFixtureImageBuildWiringTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String COMPOSE = "docker-compose.yml";
    private static final String POM = "pom.xml";
    private static final String EXEC = "exec-maven-plugin";
    private static final String BUILD = "build";
    private static final String DOCKER = "docker";

    /** The script every IT lane starts the Compose stack with; a profile that runs it is a lane. */
    private static final String START_SCRIPT = "start-integration-container.sh";

    /** The goal an {@value #EXEC} execution must be bound to for Maven to run it. */
    private static final String EXEC_GOAL = "exec";

    /** The phase both real lanes bind their image build and their stack start to. */
    private static final String PRE_IT = "pre-integration-test";

    /**
     * The {@value #EXEC} configuration element that makes an execution run nothing; appended to a
     * specimen binding, it is moved into that execution's {@code <configuration>}.
     */
    private static final String SKIP = "<skip>true</skip>";


    /** The execution id a lane is recorded under when it runs no {@code docker ... build} at all. */
    private static final String NO_BUILD = "";

    /**
     * The two IT lanes this guard was written against, each with the execution that builds the images
     * its stack starts — the independent oracle the lanes derived from the POM must include.
     */
    private static final List<Lane> KNOWN_LANES = List.of(
            new Lane("integration-tests", "docker-build-distroless"),
            new Lane("jfr", "docker-build-jfr"));

    /** A descriptor holding each kind of service the selector has to tell apart, for the controls. */
    private static final String SPECIMEN_COMPOSE = """
            services:
              gateway:
                build:
                  context: ../gateway
              echo:
                build: ./echo
              upstream:
                image: upstream:latest
              load:
                build: ./load
                profiles: [benchmark]
            """;

    // --- the real docker-compose.yml and the real pom.xml ------------------------------------------

    @Test
    @DisplayName("each lane's compose build names exactly the services the stack starts from a build")
    void lanesBuildEveryStartedFixture() throws Exception {
        Set<String> fixtures = servicesStartedFromABuild(Files.readString(file(COMPOSE)));
        Map<Lane, List<String>> lanes = stackStartingLanes(parse(Files.readString(file(POM))));

        assertFalse(fixtures.isEmpty(), file(COMPOSE) + " declares no service with a build section and no"
                + " profiles, so this guard would assert over nothing");
        assertTrue(lanes.keySet().containsAll(KNOWN_LANES), file(POM) + " yields the lanes " + lanes.keySet()
                + ", which miss one of " + KNOWN_LANES + ": the derivation no longer sees a lane known to start"
                + " the stack");
        assertEquals(List.of(), laneViolations(fixtures, lanes));
    }

    // --- controls over the selectors and the detector ----------------------------------------------

    @Test
    @DisplayName("the selector takes the services with a build section and no profiles, in either build form")
    void selectorTakesUnprofiledBuildServices() {
        assertEquals(Set.of("echo", "gateway"), servicesStartedFromABuild(SPECIMEN_COMPOSE));
    }

    @Test
    @DisplayName("the lane selector takes every profile that starts the stack, with its compose build")
    void laneSelectorTakesStackStartingProfiles() {
        String specimen = pom(
                profile("it", true, "build-images", buildArguments("gateway")),
                profile("unit", false, "build-images", buildArguments("gateway")),
                profile("up-only", true, "start-up", "<argument>compose</argument><argument>up</argument>"),
                profile("smoke", true, null, null));

        assertEquals(List.of(new Lane("it", "build-images"), new Lane("up-only", NO_BUILD),
                new Lane("smoke", NO_BUILD)), List.copyOf(stackStartingLanes(parsed(specimen)).keySet()));
    }

    @Test
    @DisplayName("the lane selector counts a build only when Maven runs it before the stack starts")
    void laneSelectorRequiresABuildBoundBeforeTheStart() {
        String arguments = buildArguments("gateway");
        String bound = binding(PRE_IT, EXEC_GOAL);
        String specimen = pom(
                profile("bound", bound, "build-images", arguments, bound, false),
                profile("other-phase", bound, "build-images", arguments, binding("package", EXEC_GOAL), false),
                profile("no-goal", bound, "build-images", arguments, binding(PRE_IT, null), false),
                profile("no-phase", bound, "build-images", arguments, binding(null, EXEC_GOAL), false),
                profile("after-start", bound, "build-images", arguments, bound, true),
                profile("skipped", bound, "build-images", arguments, bound + SKIP, false),
                profile("start-unbound", binding(PRE_IT, null), "build-images", arguments, bound, false),
                profile("start-skipped", bound + SKIP, "build-images", arguments, bound, false),
                profile("misspelled-phase", binding("pre-integration-tset", EXEC_GOAL), "build-images", arguments,
                        binding("pre-integration-tset", EXEC_GOAL), false));

        assertEquals(List.of(new Lane("bound", "build-images"), new Lane("other-phase", NO_BUILD),
                new Lane("no-goal", NO_BUILD), new Lane("no-phase", NO_BUILD), new Lane("after-start", NO_BUILD),
                new Lane("skipped", NO_BUILD)), List.copyOf(stackStartingLanes(parsed(specimen)).keySet()));
    }

    @Test
    @DisplayName("the detector accepts complete lanes and rejects a missing fixture, a profiled service and an"
            + " unbuilt lane")
    void detectorRejectsIncompleteLanes() {
        Set<String> fixtures = servicesStartedFromABuild(SPECIMEN_COMPOSE);
        String complete = buildArguments("gateway", "echo");
        String withOverlay = "<argument>-f</argument><argument>docker-compose.yml</argument>"
                + "<argument>-f</argument><argument>docker-compose.jfr.yml</argument>" + complete;

        assertAll(
                () -> assertEquals(List.of(), violationsOf(fixtures, knownLanes(complete, withOverlay))),
                () -> assertFalse(violationsOf(fixtures, knownLanes(buildArguments("gateway"), withOverlay))
                        .isEmpty(), "a lane that leaves a fixture to 'up -d' was accepted"),
                () -> assertFalse(violationsOf(fixtures, knownLanes(complete, buildArguments("gateway"))).isEmpty(),
                        "the jfr lane leaving a fixture to 'up -d' was accepted"),
                () -> assertFalse(violationsOf(fixtures, knownLanes(buildArguments("gateway", "echo", "load"),
                        withOverlay)).isEmpty(), "a lane that builds a profile-gated service was accepted"),
                () -> assertFalse(violationsOf(fixtures, knownLanes(buildArguments("gateway", "echo", "upstream"),
                        withOverlay)).isEmpty(), "a lane that builds a service without a build section was accepted"),
                () -> assertFalse(violationsOf(fixtures, knownLanes("<argument>compose</argument><argument>up"
                                + "</argument>", withOverlay)).isEmpty(),
                        "a lane whose command line has no build argument was accepted"),
                () -> assertFalse(violationsOf(fixtures, knownLanes(complete, null)).isEmpty(),
                        "a profile without its image-build execution was accepted"),
                () -> assertFalse(violationsOf(fixtures, pom(knownLane(KNOWN_LANES.getFirst(), complete),
                                knownLane(KNOWN_LANES.getLast(), withOverlay),
                                profile("smoke", true, "build-smoke", buildArguments("gateway")))).isEmpty(),
                        "a further profile that starts the stack and leaves a fixture to 'up -d' was accepted"),
                () -> assertFalse(violationsOf(fixtures, pom(profile(KNOWN_LANES.getFirst().profile(),
                                binding(PRE_IT, EXEC_GOAL), KNOWN_LANES.getFirst().execution(), complete,
                                binding(PRE_IT, null), false),
                                knownLane(KNOWN_LANES.getLast(), withOverlay))).isEmpty(),
                        "an image-build execution bound to no goal, which Maven never runs, was accepted"));
    }

    // --- the rule ----------------------------------------------------------------------------------

    /**
     * Why a lane does not build exactly the given services; empty when every lane does.
     *
     * @param fixtures the services the stack starts from a build
     * @param lanes    the lanes, each with the command line of its image-build execution
     * @return one message per defect
     */
    private static List<String> laneViolations(Set<String> fixtures, Map<Lane, List<String>> lanes) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Lane, List<String>> entry : lanes.entrySet()) {
            Lane lane = entry.getKey();
            if (NO_BUILD.equals(lane.execution())) {
                violations.add("profile " + lane.profile() + " runs " + START_SCRIPT + " but no " + DOCKER + " "
                        + BUILD + " execution, so that lane builds no image before its stack starts");
                continue;
            }
            List<String> commandLine = entry.getValue();
            int build = commandLine.indexOf(BUILD);
            Set<String> named = new TreeSet<>(commandLine.subList(build + 1, commandLine.size()));
            Set<String> missing = new TreeSet<>(fixtures);
            missing.removeAll(named);
            Set<String> extra = new TreeSet<>(named);
            extra.removeAll(fixtures);
            if (!missing.isEmpty()) {
                violations.add(lane + " does not build " + missing + ": 'up -d' then starts whatever image of"
                        + " that name the daemon already holds, so a change to the fixture never reaches the"
                        + " container. Name it after the " + BUILD + " argument.");
            }
            if (!extra.isEmpty()) {
                violations.add(lane + " builds " + extra + ", which " + COMPOSE + " does not start from a build"
                        + " in an IT lane: the service is profile-gated or declares no build section.");
            }
        }
        return violations;
    }

    // --- reading -----------------------------------------------------------------------------------

    /**
     * One IT lane: the profile that selects it and the execution that builds its images.
     *
     * @param profile   the profile id
     * @param execution the id of the {@value #EXEC} execution that runs {@code docker compose build}, or
     *                  empty ({@code NO_BUILD}) when the profile starts the stack and runs no such build
     */
    private record Lane(String profile, String execution) {

        @Override
        public String toString() {
            return NO_BUILD.equals(execution) ? "profile " + profile + " (no image-build execution)"
                    : "execution " + execution + " of profile " + profile;
        }
    }

    /**
     * The services of a Compose descriptor that declare a {@code build} section and no
     * {@code profiles}.
     *
     * @param compose the descriptor's text
     * @return the service names, sorted
     */
    private static Set<String> servicesStartedFromABuild(String compose) {
        Map<?, ?> document = new Yaml().loadAs(compose, Map.class);
        Object services = document.get("services");
        assertInstanceOf(Map.class, services, COMPOSE + " must declare services");
        Set<String> started = new TreeSet<>();
        ((Map<?, ?>) services).forEach((name, definition) -> {
            if (definition instanceof Map<?, ?> service && service.containsKey(BUILD)
                    && !service.containsKey("profiles")) {
                started.add(String.valueOf(name));
            }
        });
        return started;
    }

    /**
     * The IT lanes a POM declares: every profile with an {@value #EXEC} execution that runs
     * {@value #START_SCRIPT}, once per {@value #EXEC} execution of that profile that runs {@code docker}
     * with a {@code build} argument. A profile that starts the stack and runs no such build is recorded
     * once, under an empty execution id and an empty command line, so the rule can report it.
     *
     * @param pom the parsed POM
     * @return the lanes in document order, each mapped to its execution's argument texts
     */
    private static Map<Lane, List<String>> stackStartingLanes(Document pom) {
        Map<Lane, List<String>> lanes = new LinkedHashMap<>();
        for (Element profiles : children(pom.getDocumentElement(), "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                List<Element> executions = execExecutions(profile);
                int start = indexOfBoundStart(executions);
                if (start < 0) {
                    continue;
                }
                String id = childText(profile, "id");
                int before = lanes.size();
                for (int index = 0; index < executions.size(); index++) {
                    Element execution = executions.get(index);
                    List<String> commandLine = commandLine(execution);
                    if (DOCKER.equals(executable(execution)) && commandLine.contains(BUILD)
                            && runsBefore(execution, index, executions.get(start), start)
                            && !skipped(execution)) {
                        lanes.put(new Lane(id, childText(execution, "id")), commandLine);
                    }
                }
                if (lanes.size() == before) {
                    lanes.put(new Lane(id, NO_BUILD), List.of());
                }
            }
        }
        return lanes;
    }

    /**
     * The position of the first {@value #EXEC} execution that runs {@value #START_SCRIPT} and that Maven
     * runs: bound to the {@value #EXEC_GOAL} goal at {@value #PRE_IT} and not skipped.
     *
     * @param executions the profile's {@value #EXEC} executions
     * @return the position, or {@code -1} when the profile starts no stack
     */
    private static int indexOfBoundStart(List<Element> executions) {
        for (int index = 0; index < executions.size(); index++) {
            Element execution = executions.get(index);
            if (executable(execution).endsWith(START_SCRIPT) && boundToExec(execution)
                    && PRE_IT.equals(childText(execution, "phase")) && !skipped(execution)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Whether Maven runs the build execution before the start execution: the build is bound to the
     * {@value #EXEC_GOAL} goal at the start's own phase and declared before it.
     *
     * @param build      the image-build execution
     * @param buildIndex its position among the profile's {@value #EXEC} executions
     * @param start      the stack-start execution
     * @param startIndex its position among the same executions
     * @return {@code true} when the build runs first
     */
    private static boolean runsBefore(Element build, int buildIndex, Element start, int startIndex) {
        return boundToExec(build) && childText(build, "phase").equals(childText(start, "phase"))
                && buildIndex < startIndex;
    }

    private static boolean boundToExec(Element execution) {
        return children(execution, "goals").stream()
                .flatMap(goals -> children(goals, "goal").stream())
                .anyMatch(goal -> EXEC_GOAL.equals(goal.getTextContent().strip()));
    }

    /** Whether the execution's configuration sets {@code <skip>true</skip>}, so the plugin runs nothing. */
    private static boolean skipped(Element execution) {
        return children(execution, "configuration").stream()
                .anyMatch(configuration -> "true".equals(childText(configuration, "skip")));
    }

    private static String executable(Element execution) {
        return children(execution, "configuration").stream()
                .map(configuration -> childText(configuration, "executable"))
                .findFirst()
                .orElse("");
    }

    private static List<String> commandLine(Element execution) {
        return children(execution, "configuration").stream()
                .flatMap(configuration -> children(configuration, "arguments").stream())
                .flatMap(arguments -> children(arguments, "argument").stream())
                .map(argument -> argument.getTextContent().strip())
                .toList();
    }

    private static List<Element> execExecutions(Element profile) {
        return children(profile, "build").stream()
                .flatMap(build -> children(build, "plugins").stream())
                .flatMap(plugins -> children(plugins, "plugin").stream())
                .filter(plugin -> EXEC.equals(childText(plugin, "artifactId")))
                .flatMap(plugin -> children(plugin, "executions").stream())
                .flatMap(executions -> children(executions, "execution").stream())
                .toList();
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

    private static Path file(String name) {
        Path file = MODULE.resolve(name);
        assertTrue(Files.isRegularFile(file), file + " does not exist; this guard reads the module's own " + name
                + " and expects the module root as its working directory");
        return file;
    }

    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /**
     * The detector's verdict over a specimen POM, with the lanes derived exactly as the real test
     * derives them.
     *
     * @param fixtures the services the stack starts from a build
     * @param pom      the specimen POM text
     * @return one message per defect
     */
    private static List<String> violationsOf(Set<String> fixtures, String pom) {
        return laneViolations(fixtures, stackStartingLanes(parsed(pom)));
    }

    private static Document parsed(String pom) {
        try {
            return parse(pom);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AssertionError("the specimen is not a readable POM", e);
        }
    }

    /**
     * A POM declaring both known lanes, each starting the stack and building with the given arguments.
     *
     * @param distroless the arguments of the integration-tests lane's image-build execution
     * @param jfr        the arguments of the jfr lane's image-build execution, or {@code null} to leave
     *                   that execution out of its profile
     * @return the POM text
     */
    private static String knownLanes(String distroless, String jfr) {
        return pom(knownLane(KNOWN_LANES.getFirst(), distroless), knownLane(KNOWN_LANES.getLast(), jfr));
    }

    private static String knownLane(Lane lane, String arguments) {
        return profile(lane.profile(), true, arguments == null ? null : lane.execution(), arguments);
    }

    /**
     * The phase and goal elements of a specimen execution.
     *
     * @param phase the phase, or {@code null} to declare none
     * @param goal  the goal, or {@code null} to declare none
     * @return the elements' text
     */
    private static String withoutSkip(String binding) {
        return binding.replace(SKIP, "");
    }

    private static String skipOf(String binding) {
        return binding.contains(SKIP) ? SKIP : "";
    }

    private static String binding(String phase, String goal) {
        return (phase == null ? "" : "<phase>" + phase + "</phase>")
                + (goal == null ? "" : "<goals><goal>" + goal + "</goal></goals>");
    }

    private static String pom(String... profiles) {
        return "<project><profiles>" + String.join("", profiles) + "</profiles></project>";
    }

    /**
     * One profile of a specimen POM, its build execution bound the way the real lanes bind theirs.
     *
     * @param id          the profile id
     * @param startsStack whether the profile runs {@value #START_SCRIPT}
     * @param execution   the id of its {@code docker} execution, or {@code null} to declare none
     * @param arguments   the arguments of that execution
     * @return the profile's text
     */
    private static String profile(String id, boolean startsStack, String execution, String arguments) {
        String bound = binding(PRE_IT, EXEC_GOAL);
        return profile(id, startsStack ? bound : null, execution, arguments, bound, false);
    }

    /**
     * One profile of a specimen POM.
     *
     * @param id           the profile id
     * @param startBinding the phase, goal and skip elements of its {@value #START_SCRIPT} execution, or
     *                     {@code null} to declare none
     * @param execution    the id of its {@code docker} execution, or {@code null} to declare none
     * @param arguments    the arguments of that execution
     * @param buildBinding the phase and goal elements of that execution
     * @param buildLast    whether that execution is declared after the start execution
     * @return the profile's text
     */
    private static String profile(String id, String startBinding, String execution, String arguments,
            String buildBinding, boolean buildLast) {
        String build = execution == null ? ""
                : "<execution><id>" + execution + "</id>" + withoutSkip(buildBinding) + "<configuration><executable>"
                + DOCKER + "</executable><arguments>" + arguments + "</arguments>" + skipOf(buildBinding)
                + "</configuration></execution>";
        String start = startBinding == null ? ""
                : "<execution><id>start-integration-app</id>" + withoutSkip(startBinding)
                + "<configuration><executable>./scripts/" + START_SCRIPT + "</executable>" + skipOf(startBinding)
                + "</configuration></execution>";
        return "<profile><id>" + id + "</id><build><plugins><plugin><artifactId>" + EXEC + "</artifactId><executions>"
                + (buildLast ? start + build : build + start) + "</executions></plugin></plugins></build></profile>";
    }

    private static String buildArguments(String... services) {
        StringBuilder arguments = new StringBuilder("<argument>compose</argument><argument>" + BUILD + "</argument>");
        for (String service : services) {
            arguments.append("<argument>").append(service).append("</argument>");
        }
        return arguments.toString();
    }
}
