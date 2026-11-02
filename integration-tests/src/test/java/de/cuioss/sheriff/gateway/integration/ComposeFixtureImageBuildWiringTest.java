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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
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
 * Fast, no-Docker <em>surefire</em> guard that both integration-test lanes build every Compose
 * fixture image the stack starts, rather than leaving the fixtures to {@code docker compose up -d}.
 * <p>
 * <strong>Why this test exists.</strong> {@code up -d} builds a service's image only when the daemon
 * holds no image of that name yet. A fixture image built once on a machine — {@code grpc-echo},
 * {@code passthrough-backend} — is then reused by every later run, so a change to the fixture's
 * Dockerfile or sources never reaches the container and the lane stays green against the old one.
 * The {@code docker-build-distroless} execution of the {@code integration-tests} profile and the
 * {@code docker-build-jfr} execution of the {@code jfr} profile close that by naming every fixture on
 * their {@code docker compose build} command line, so each run builds every image it starts. Neither
 * half is checked by a compiler: a fixture service added to {@code docker-compose.yml} and not to the
 * two command lines stays green, and so does a profile-gated service named on them.
 * <p>
 * <strong>What it asserts.</strong> The services of {@code docker-compose.yml} that declare a
 * {@code build} section and no {@code profiles} — the services the IT lanes start from a build — are
 * exactly the services named after the {@code build} argument of both executions. A profile-gated
 * service such as {@code k6} ({@code profiles: [benchmark]}) is started by no IT lane, so no IT lane
 * builds it.
 * <p>
 * <strong>What it does not prove.</strong> That the build ran, that Docker rebuilt a layer rather
 * than reusing one from its cache, or that the running container uses the image the build produced:
 * those are properties of a Docker daemon, which this test never reaches. It does not read the
 * Compose overlays either — {@code docker-compose.jfr.yml} re-declares only the {@code api-sheriff}
 * build, and {@code docker-compose.benchmark.yml} declares none — so a {@code build} section added to
 * an overlay alone is not seen. It parses the committed descriptor and the committed POM only; it
 * starts no container and reaches no network.
 * <p>
 * <strong>Controls</strong> ("Comment-Only Invariants Become Fitness Functions" in
 * {@code doc/development/test-corpus-integrity.adoc}). The population the rule selects is asserted
 * non-empty before the rule runs, and the detector is driven with deliberately wrong specimens it must
 * reject and a correct one it must accept.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("both IT lanes build every compose fixture image the stack starts")
class ComposeFixtureImageBuildWiringTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final String COMPOSE = "docker-compose.yml";
    private static final String POM = "pom.xml";
    private static final String EXEC = "exec-maven-plugin";
    private static final String BUILD = "build";

    /** The two IT lanes, each with the execution that builds the images its stack starts. */
    private static final List<Lane> LANES = List.of(
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

        assertFalse(fixtures.isEmpty(), file(COMPOSE) + " declares no service with a build section and no"
                + " profiles, so this guard would assert over nothing");
        assertEquals(List.of(), laneViolations(fixtures, parse(Files.readString(file(POM)))));
    }

    // --- controls over the selector and the detector -----------------------------------------------

    @Test
    @DisplayName("the selector takes the services with a build section and no profiles, in either build form")
    void selectorTakesUnprofiledBuildServices() {
        assertEquals(Set.of("echo", "gateway"), servicesStartedFromABuild(SPECIMEN_COMPOSE));
    }

    @Test
    @DisplayName("the detector accepts complete lanes and rejects a missing fixture and a profiled service")
    void detectorRejectsIncompleteLanes() {
        Set<String> fixtures = servicesStartedFromABuild(SPECIMEN_COMPOSE);
        BiFunction<String, String, List<String>> violations = (distroless, jfr) -> {
            try {
                return laneViolations(fixtures, parse(pomWith(distroless, jfr)));
            } catch (ParserConfigurationException | SAXException | IOException e) {
                throw new AssertionError("the specimen is not a readable POM", e);
            }
        };
        String complete = buildArguments("gateway", "echo");
        String withOverlay = "<argument>-f</argument><argument>docker-compose.yml</argument>"
                + "<argument>-f</argument><argument>docker-compose.jfr.yml</argument>" + complete;

        assertAll(
                () -> assertEquals(List.of(), violations.apply(complete, withOverlay)),
                () -> assertFalse(violations.apply(buildArguments("gateway"), withOverlay).isEmpty(),
                        "a lane that leaves a fixture to 'up -d' was accepted"),
                () -> assertFalse(violations.apply(complete, buildArguments("gateway")).isEmpty(),
                        "the jfr lane leaving a fixture to 'up -d' was accepted"),
                () -> assertFalse(violations.apply(buildArguments("gateway", "echo", "load"), withOverlay)
                        .isEmpty(), "a lane that builds a profile-gated service was accepted"),
                () -> assertFalse(violations.apply(buildArguments("gateway", "echo", "upstream"), withOverlay)
                        .isEmpty(), "a lane that builds a service without a build section was accepted"),
                () -> assertFalse(violations.apply("<argument>compose</argument><argument>up</argument>",
                        withOverlay).isEmpty(), "a lane whose command line has no build argument was accepted"),
                () -> assertFalse(violations.apply(complete, null).isEmpty(),
                        "a profile without its image-build execution was accepted"));
    }

    // --- the rule ----------------------------------------------------------------------------------

    /**
     * Why a lane does not build exactly the given services; empty when every lane does.
     *
     * @param fixtures the services the stack starts from a build
     * @param pom      the parsed POM
     * @return one message per defect
     */
    private static List<String> laneViolations(Set<String> fixtures, Document pom) {
        List<String> violations = new ArrayList<>();
        for (Lane lane : LANES) {
            Optional<List<String>> arguments = arguments(pom, lane);
            if (arguments.isEmpty()) {
                violations.add(lane + " is not declared, so that lane builds no image before its stack starts");
                continue;
            }
            List<String> commandLine = arguments.get();
            int build = commandLine.indexOf(BUILD);
            if (build < 0) {
                violations.add(lane + " runs 'docker " + String.join(" ", commandLine) + "', which has no "
                        + BUILD + " argument");
                continue;
            }
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
     * @param execution the id of the {@value #EXEC} execution that runs {@code docker compose build}
     */
    private record Lane(String profile, String execution) {

        @Override
        public String toString() {
            return "execution " + execution + " of profile " + profile;
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
     * The {@code arguments} of a lane's {@value #EXEC} execution, in document order.
     *
     * @param pom  the parsed POM
     * @param lane the lane
     * @return the argument texts, empty when the profile does not declare the execution
     */
    private static Optional<List<String>> arguments(Document pom, Lane lane) {
        for (Element profiles : children(pom.getDocumentElement(), "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                if (!lane.profile().equals(childText(profile, "id"))) {
                    continue;
                }
                for (Element execution : execExecutions(profile)) {
                    if (lane.execution().equals(childText(execution, "id"))) {
                        return Optional.of(children(execution, "configuration").stream()
                                .flatMap(configuration -> children(configuration, "arguments").stream())
                                .flatMap(arguments -> children(arguments, "argument").stream())
                                .map(argument -> argument.getTextContent().strip())
                                .toList());
                    }
                }
            }
        }
        return Optional.empty();
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
     * A POM declaring both lanes' image-build executions with the given arguments, for the control.
     *
     * @param distroless the arguments of the integration-tests lane's execution
     * @param jfr        the arguments of the jfr lane's execution, or {@code null} to leave the
     *                   execution out of its profile
     * @return the POM text
     */
    private static String pomWith(String distroless, String jfr) {
        return "<project><profiles>" + profileWith(LANES.getFirst(), distroless)
                + profileWith(LANES.getLast(), jfr) + "</profiles></project>";
    }

    private static String profileWith(Lane lane, String arguments) {
        String execution = arguments == null ? ""
                : "<execution><id>" + lane.execution() + "</id><configuration><arguments>" + arguments
                + "</arguments></configuration></execution>";
        return "<profile><id>" + lane.profile() + "</id><build><plugins><plugin><artifactId>" + EXEC
                + "</artifactId><executions>" + execution + "</executions></plugin></plugins></build></profile>";
    }

    private static String buildArguments(String... services) {
        StringBuilder arguments = new StringBuilder("<argument>compose</argument><argument>" + BUILD + "</argument>");
        for (String service : services) {
            arguments.append("<argument>").append(service).append("</argument>");
        }
        return arguments.toString();
    }
}
