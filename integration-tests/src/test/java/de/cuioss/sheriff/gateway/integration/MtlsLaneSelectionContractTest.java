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
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that every failsafe lane which runs {@code MtlsHandshakeIT} hands it every {@code test.mtls.*}
 * system property it reads, and that its {@code test.mtls.port} names a host port the
 * {@value #MTLS_SERVICE} compose service publishes.
 * <p>
 * <strong>Why this test exists.</strong> {@code MtlsHandshakeIT} falls back to defaults that point at
 * a <em>different</em> instance than the one it names: without {@code test.mtls.port} it dials the
 * primary instance on {@value #PRIMARY_PORT}, which never asks for a client certificate, and without
 * the keystore properties the client offers no identity. A lane that selects the IT without those
 * properties does not fail cleanly — {@code trustedClientCertAccepted} passes having proven nothing
 * about mTLS while the two rejection legs fail against a listener that was never asked to reject.
 * That is how the {@code jfr} lane failed in GitHub issue 201, and only nine counted runs told the
 * lane defect from a gateway defect. A lane declaring only some of the properties fails the same way,
 * so it is refused too.
 * <p>
 * <strong>What it asserts.</strong> For every profile of the module's {@code pom.xml} and every
 * {@code maven-failsafe-plugin} execution in it — a lane — the effective configuration is computed the
 * way Maven merges it: the root {@code pom.xml}'s {@code pluginManagement} entry, the module's own
 * build, the profile, and the execution, each dominant over the one before, executions matched by id
 * (an execution without one is {@code default}). A lane selects the IT when an include matches its
 * path, no exclude does, and its tags pass the lane's {@code groups} and {@code excludedGroups}. Every
 * lane that selects it declares every required property, and its {@code test.mtls.port} is a host
 * port the compose service publishes. The required properties are derived from the IT's source — every
 * {@code test.mtls.*} key it reads through {@code System.getProperty} — not listed here.
 * <p>
 * <strong>What it does not read.</strong> Configuration from the parents above the root
 * {@code pom.xml}, properties supplied on the command line, and {@code %regex[...]} patterns: includes
 * and excludes are read as Ant-style globs.
 * <p>
 * <strong>Controls</strong> (ADR-0030). The population is asserted non-empty before the rule runs: the
 * IT reads at least one property and among them the port, at least one lane selects the IT today and
 * at least one leaves it out. The detector is driven with POM specimens it must reject — a selecting
 * lane with no properties, with only the port, with the primary port — and ones it must accept. The
 * specimens are strings in this file only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("every failsafe lane that runs MtlsHandshakeIT wires every test.mtls property it reads")
class MtlsLaneSelectionContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The IT's path relative to the test source root, which is what failsafe patterns match. */
    private static final String IT_PATH = "de/cuioss/sheriff/gateway/integration/MtlsHandshakeIT.java";

    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final String MTLS_SERVICE = "api-sheriff-mtls";
    private static final String PORT_KEY = "test.mtls.port";
    private static final String PRIMARY_PORT = "10443";
    private static final String DEFAULT_EXECUTION = "default";
    private static final String NO_PROFILE = "(no profile)";

    /** Failsafe's own includes, in force when no layer declares any. */
    private static final List<String> DEFAULT_INCLUDES = List.of("**/IT*.java", "**/*IT.java", "**/*ITCase.java");

    private static final Pattern REQUIRED_PROPERTY =
            Pattern.compile("System\\.getProperty\\(\\s*\"(test\\.mtls\\.[^\"]+)\"");
    private static final Pattern TOP_LEVEL_TAG = Pattern.compile("(?m)^@Tag\\(\"([^\"]+)\"\\)");

    // --- the real pom.xml files, the real IT and the real compose file ------------------------------

    @Test
    @DisplayName("every lane that selects MtlsHandshakeIT declares every test.mtls property and the published mTLS port")
    void lanesSelectingTheItWireEveryMtlsProperty() throws Exception {
        String itSource = Files.readString(existing(MODULE.resolve("src/test/java").resolve(IT_PATH)));
        Set<String> required = requiredProperties(itSource);
        Set<String> tags = tags(itSource);
        Set<String> published = publishedPorts();
        List<Lane> lanes = lanes(parse(Files.readString(existing(MODULE.resolve("pom.xml")))),
                parse(Files.readString(existing(MODULE.getParent().resolve("pom.xml")))));

        assertAll("each population is non-empty today, so the rule cannot pass by matching nothing",
                () -> assertFalse(required.isEmpty(), "MtlsHandshakeIT reads no test.mtls.* property"),
                () -> assertTrue(required.contains(PORT_KEY), "MtlsHandshakeIT no longer reads " + PORT_KEY
                        + ", so the published-port rule checks nothing; read " + required),
                () -> assertFalse(published.isEmpty(), "the '" + MTLS_SERVICE + "' service publishes no port"),
                () -> assertTrue(lanes.stream().anyMatch(lane -> lane.selects(IT_PATH, tags)),
                        "no failsafe lane selects MtlsHandshakeIT; lanes read: " + lanes),
                () -> assertTrue(lanes.stream().anyMatch(lane -> !lane.selects(IT_PATH, tags)),
                        "every failsafe lane selects MtlsHandshakeIT, so no exclusion is exercised; lanes read: "
                                + lanes));
        assertEquals(List.of(), violations(lanes, required, tags, published));
    }

    // --- controls over the detector -----------------------------------------------------------------

    @Test
    @DisplayName("the detector accepts a fully wired or excluding lane and rejects each partly wired one")
    void detectorRejectsPartlyWiredLanes() throws Exception {
        Set<String> required = requiredProperties(
                Files.readString(existing(MODULE.resolve("src/test/java").resolve(IT_PATH))));
        Set<String> published = Set.of("10444");
        Map<String, String> wired = new LinkedHashMap<>();
        required.forEach(key -> wired.put(key, "declared"));
        wired.put(PORT_KEY, "10444");
        Map<String, String> primaryPort = new LinkedHashMap<>(wired);
        primaryPort.put(PORT_KEY, PRIMARY_PORT);

        assertAll(
                () -> assertEquals(List.of(), specimenViolations(profile("wired", properties(wired), ""),
                        required, published), "a fully wired lane was rejected"),
                () -> assertEquals(List.of(), specimenViolations(profile("excluding", "",
                        "<excludes><exclude>**/MtlsHandshakeIT.java</exclude></excludes>"), required, published),
                        "a lane that excludes the IT was rejected"),
                () -> assertFalse(specimenViolations(profile("bare", "", ""), required, published).isEmpty(),
                        "a lane selecting the IT with no properties was accepted"),
                () -> assertFalse(specimenViolations(profile("port-only", properties(Map.of(PORT_KEY, "10444")), ""),
                        required, published).isEmpty(), "a lane selecting the IT with only the port was accepted"),
                () -> assertFalse(specimenViolations(profile("primary", "", properties(primaryPort)), required,
                        published).isEmpty(), "a lane selecting the IT with the primary port was accepted"),
                () -> assertFalse(specimenViolations("<build><plugins>" + failsafe("", "") + "</plugins></build>",
                        required, published).isEmpty(), "a bare lane in the module's own build was accepted"));
    }

    // --- the rule -----------------------------------------------------------------------------------

    /**
     * Why the lanes that select the IT cannot serve it; empty when every one of them can.
     *
     * @param lanes     every failsafe lane of the module
     * @param required  the {@code test.mtls.*} keys the IT reads
     * @param tags      the JUnit tags on the IT class
     * @param published the host ports the mTLS compose service publishes
     * @return one message per defect
     */
    private static List<String> violations(List<Lane> lanes, Set<String> required, Set<String> tags,
            Set<String> published) {
        List<String> violations = new ArrayList<>();
        for (Lane lane : lanes) {
            if (!lane.selects(IT_PATH, tags)) {
                continue;
            }
            Set<String> missing = new TreeSet<>(required);
            lane.properties().forEach((key, value) -> {
                if (!value.isBlank()) {
                    missing.remove(key);
                }
            });
            if (!missing.isEmpty()) {
                violations.add("lane " + lane.name() + " selects MtlsHandshakeIT but does not declare " + missing
                        + ": the IT then falls back to the primary instance or offers no client identity, and"
                        + " passes or fails for the wrong reason. Declare them, or exclude the IT from the lane");
            }
            String port = lane.properties().get(PORT_KEY);
            if (required.contains(PORT_KEY) && port != null && !published.contains(port)) {
                violations.add("lane " + lane.name() + " sets " + PORT_KEY + " to '" + port + "', but the '"
                        + MTLS_SERVICE + "' service publishes " + published + ": the IT would reach another listener");
            }
        }
        return violations;
    }

    private static List<String> specimenViolations(String moduleBody, Set<String> required, Set<String> published)
            throws ParserConfigurationException, SAXException, IOException {
        String root = "<project><build><pluginManagement><plugins>"
                + failsafe("<includes><include>**/*IT.java</include></includes>", "")
                + "</plugins></pluginManagement></build></project>";
        return violations(lanes(parse("<project>" + moduleBody + "</project>"), parse(root)), required, Set.of(),
                published);
    }

    // --- reading the IT and the compose file --------------------------------------------------------

    private static Set<String> requiredProperties(String itSource) {
        Set<String> keys = new TreeSet<>();
        REQUIRED_PROPERTY.matcher(itSource).results().forEach(match -> keys.add(match.group(1)));
        return keys;
    }

    private static Set<String> tags(String itSource) {
        Set<String> tags = new TreeSet<>();
        TOP_LEVEL_TAG.matcher(itSource).results().forEach(match -> tags.add(match.group(1)));
        return tags;
    }

    /**
     * The host ports the mTLS compose service publishes, from both compose forms: the short
     * {@code HOST:CONTAINER} (or {@code IP:HOST:CONTAINER}) string and the long mapping with
     * {@code published}.
     */
    private static Set<String> publishedPorts() throws IOException {
        Map<?, ?> compose;
        try (InputStream in = Files.newInputStream(existing(MODULE.resolve("docker-compose.yml")))) {
            compose = new Yaml().loadAs(in, Map.class);
        }
        Object services = compose.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml declares no services");
        Object service = ((Map<?, ?>) services).get(MTLS_SERVICE);
        assertInstanceOf(Map.class, service, "docker-compose.yml declares no '" + MTLS_SERVICE + "' service");
        Object ports = ((Map<?, ?>) service).get("ports");
        assertInstanceOf(List.class, ports, "the '" + MTLS_SERVICE + "' service publishes no ports");
        Set<String> published = new TreeSet<>();
        for (Object entry : (List<?>) ports) {
            if (entry instanceof Map<?, ?> longForm) {
                published.add(String.valueOf(longForm.get("published")));
            } else {
                String[] parts = String.valueOf(entry).split(":");
                if (parts.length > 1) {
                    published.add(parts[parts.length - 2]);
                }
            }
        }
        return published;
    }

    private static Path existing(Path file) {
        assertTrue(Files.isRegularFile(file), file + " does not exist; this guard expects the module root as its"
                + " working directory");
        return file;
    }

    // --- computing the lanes ------------------------------------------------------------------------

    /**
     * One failsafe execution under one profile, with its effective configuration.
     *
     * @param name           {@code profile/execution}
     * @param includes       the effective include patterns
     * @param excludes       the effective exclude patterns
     * @param groups         the tags the lane selects, empty for all
     * @param excludedGroups the tags the lane excludes
     * @param properties     the effective {@code systemPropertyVariables}
     */
    private record Lane(String name, List<String> includes, List<String> excludes, Set<String> groups,
    Set<String> excludedGroups, Map<String, String> properties) {

        boolean selects(String path, Set<String> tags) {
            return includes.stream().anyMatch(pattern -> matches(pattern, path))
                    && excludes.stream().noneMatch(pattern -> matches(pattern, path))
                    && (groups.isEmpty() || tags.stream().anyMatch(groups::contains))
                    && tags.stream().noneMatch(excludedGroups::contains);
        }

        private static boolean matches(String pattern, String path) {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(path));
        }
    }

    /**
     * Every failsafe lane of the module: one per execution, per profile that declares the plugin, and
     * per execution of the module's own build when it declares the plugin.
     *
     * @param module the module's parsed {@code pom.xml}
     * @param root   the root's parsed {@code pom.xml}, whose {@code pluginManagement} entry every lane inherits
     * @return the lanes in document order
     */
    private static List<Lane> lanes(Document module, Document root) {
        Optional<Element> managed = failsafe(children(root.getDocumentElement(), "build").stream()
                .flatMap(build -> children(build, "pluginManagement").stream()));
        Optional<Element> own = failsafe(children(module.getDocumentElement(), "build").stream());
        List<Lane> lanes = new ArrayList<>();
        own.ifPresent(plugin -> lanes.addAll(lanesOf(NO_PROFILE, layers(plugin, own, managed))));
        for (Element profiles : children(module.getDocumentElement(), "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                failsafe(children(profile, "build").stream()).ifPresent(plugin -> lanes.addAll(
                        lanesOf(childText(profile, "id"), layers(plugin, own, managed))));
            }
        }
        return lanes;
    }

    /** The plugin declarations a lane is merged from, the most dominant first, each once. */
    private static List<Element> layers(Element plugin, Optional<Element> own, Optional<Element> managed) {
        Set<Element> layers = new LinkedHashSet<>();
        layers.add(plugin);
        own.ifPresent(layers::add);
        managed.ifPresent(layers::add);
        return List.copyOf(layers);
    }

    private static List<Lane> lanesOf(String profile, List<Element> layers) {
        Conf pluginLevel = null;
        Map<String, Conf> executions = new LinkedHashMap<>();
        for (Element layer : layers) {
            pluginLevel = merge(pluginLevel, configuration(layer));
            for (Element holder : children(layer, "executions")) {
                for (Element execution : children(holder, "execution")) {
                    String id = childText(execution, "id");
                    String key = id.isEmpty() ? DEFAULT_EXECUTION : id;
                    Conf merged = merge(executions.get(key), configuration(execution));
                    executions.put(key, merged);
                }
            }
        }
        List<Lane> lanes = new ArrayList<>();
        for (Map.Entry<String, Conf> execution : executions.entrySet()) {
            lanes.add(lane(profile + "/" + execution.getKey(), merge(execution.getValue(), pluginLevel)));
        }
        return lanes;
    }

    private static Lane lane(String name, Conf effective) {
        List<String> includes = values(effective, "includes");
        Map<String, String> properties = new LinkedHashMap<>();
        child(effective, "systemPropertyVariables")
                .ifPresent(variables -> variables.children().forEach(property -> properties.put(property.name(),
                        property.value())));
        return new Lane(name, includes.isEmpty() ? DEFAULT_INCLUDES : includes, values(effective, "excludes"),
                commaSeparated(effective, "groups"), commaSeparated(effective, "excludedGroups"), properties);
    }

    private static List<String> values(Conf effective, String list) {
        return child(effective, list).map(holder -> holder.children().stream().map(Conf::value).toList())
                .orElse(List.of());
    }

    private static Set<String> commaSeparated(Conf effective, String name) {
        Set<String> tags = new TreeSet<>();
        child(effective, name).ifPresent(conf -> {
            for (String tag : conf.value().split(",")) {
                if (!tag.isBlank()) {
                    tags.add(tag.strip());
                }
            }
        });
        return tags;
    }

    private static Optional<Conf> child(Conf conf, String name) {
        return conf == null ? Optional.empty()
                : conf.children().stream().filter(candidate -> name.equals(candidate.name())).findFirst();
    }

    // --- Maven's configuration merge ----------------------------------------------------------------

    /**
     * A plugin configuration element.
     *
     * @param name            the element name
     * @param value           the element text, empty when it has child elements
     * @param children        the child elements in document order
     * @param combineChildren the {@code combine.children} attribute, empty when absent
     * @param combineSelf     the {@code combine.self} attribute, empty when absent
     */
    private record Conf(String name, String value, List<Conf> children, String combineChildren, String combineSelf) {

        static Conf of(Element element) {
            List<Conf> children = new ArrayList<>();
            for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child) {
                    children.add(of(child));
                }
            }
            return new Conf(element.getTagName(), children.isEmpty() ? element.getTextContent().strip() : "",
                    List.copyOf(children), element.getAttribute("combine.children"),
                    element.getAttribute("combine.self"));
        }
    }

    /**
     * Merges two configurations the way Maven's {@code Xpp3Dom} does: the dominant side keeps its value
     * and its children, a child name only the recessive side has is added, and a repeated name is
     * merged position by position, so the recessive side's surplus entries of a list the dominant side
     * declares are dropped. {@code combine.self="override"} keeps the dominant side alone and
     * {@code combine.children="append"} keeps both sides' children.
     *
     * @param dominant  the more specific configuration, {@code null} when absent
     * @param recessive the inherited configuration, {@code null} when absent
     * @return the merged configuration, {@code null} when both are absent
     */
    private static Conf merge(Conf dominant, Conf recessive) {
        if (dominant == null || recessive == null) {
            return dominant == null ? recessive : dominant;
        }
        if ("override".equals(dominant.combineSelf())) {
            return dominant;
        }
        List<Conf> children = new ArrayList<>(dominant.children());
        if ("append".equals(dominant.combineChildren())) {
            children.addAll(recessive.children());
        } else {
            Map<String, Integer> consumed = new HashMap<>();
            for (Conf inherited : recessive.children()) {
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < dominant.children().size(); i++) {
                    if (dominant.children().get(i).name().equals(inherited.name())) {
                        positions.add(i);
                    }
                }
                if (positions.isEmpty()) {
                    children.add(inherited);
                } else {
                    int next = consumed.merge(inherited.name(), 1, Integer::sum) - 1;
                    if (next < positions.size()) {
                        int at = positions.get(next);
                        children.set(at, merge(children.get(at), inherited));
                    }
                }
            }
        }
        String value = dominant.value().isEmpty() && dominant.children().isEmpty()
                ? recessive.value() : dominant.value();
        return new Conf(dominant.name(), value, List.copyOf(children), dominant.combineChildren(),
                dominant.combineSelf());
    }

    // --- XML ----------------------------------------------------------------------------------------

    private static Optional<Element> failsafe(Stream<Element> builds) {
        return builds.flatMap(build -> children(build, "plugins").stream())
                .flatMap(plugins -> children(plugins, "plugin").stream())
                .filter(plugin -> FAILSAFE.equals(childText(plugin, "artifactId"))).findFirst();
    }

    private static Conf configuration(Element holder) {
        List<Element> configuration = children(holder, "configuration");
        return configuration.isEmpty() ? null : Conf.of(configuration.getFirst());
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

    // --- specimens ----------------------------------------------------------------------------------

    /** A module profile whose failsafe plugin carries the given plugin-level and execution-level configuration. */
    private static String profile(String id, String pluginConfiguration, String executionConfiguration) {
        return "<profiles><profile><id>" + id + "</id><build><plugins>"
                + failsafe(pluginConfiguration, executionConfiguration) + "</plugins></build></profile></profiles>";
    }

    private static String failsafe(String pluginConfiguration, String executionConfiguration) {
        return "<plugin><artifactId>" + FAILSAFE + "</artifactId><configuration>" + pluginConfiguration
                + "</configuration><executions><execution><configuration>" + executionConfiguration
                + "</configuration></execution></executions></plugin>";
    }

    private static String properties(Map<String, String> properties) {
        StringBuilder xml = new StringBuilder("<systemPropertyVariables>");
        properties.forEach((key, value) -> xml.append('<').append(key).append('>').append(value).append("</")
                .append(key).append('>'));
        return xml.append("</systemPropertyVariables>").toString();
    }
}
