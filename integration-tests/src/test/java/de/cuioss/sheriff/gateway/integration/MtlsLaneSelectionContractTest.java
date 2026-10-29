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
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
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
 * <strong>What it asserts.</strong> A lane is one effective {@code maven-failsafe-plugin} execution of
 * the module, for the build without a profile and for each profile id that the root {@code pom.xml} or
 * the module's {@code pom.xml} declares the plugin under, in {@code plugins} or {@code pluginManagement};
 * a root profile and a module profile with the same id are one lane, as one {@code -P} switch activates
 * both. Its configuration is computed the way Maven builds the module's effective POM from every layer
 * the two files apply to it: in each file the profile's build is injected over the file's own build;
 * the module's {@code plugins} are inherited over the root's, and the module's {@code pluginManagement}
 * over the root's; the merged {@code pluginManagement} is applied under the merged {@code plugins}; and
 * each execution is merged over the plugin-level configuration. Every merge keeps the dominant side and
 * honours {@code combine.self} and {@code combine.children}; executions are matched by id (an execution
 * without one is {@code default}), and only a plugin some {@code plugins} layer declares runs. A root
 * declaration carrying {@code <inherited>}, which this merge does not model, fails the test naming the
 * lane. A lane selects the IT when an include matches its
 * path, no exclude does, and its tags pass the lane's {@code groups} and {@code excludedGroups}. Both
 * are read the way failsafe's JUnit Platform provider reads them: a comma-separated list of JUnit tag
 * expressions, the IT passing {@code groups} when the list is empty or any entry holds, and being
 * excluded when any {@code excludedGroups} entry holds. An entry is a plain tag name or combines them
 * with {@code !} (not), {@code &} (and) and {@code |} (or), in that precedence, with parentheses and
 * the {@code any()} / {@code none()} keywords. Every {@code ${...}} reference in either element is
 * resolved first, the way a {@code test.mtls.*} value is (below), because Maven interpolates it before
 * failsafe reads it; one that cannot be resolved fails the test naming the lane and the reference. An
 * entry this guard cannot parse fails the test naming
 * the lane and the expression — it is never read as "does not select". The IT's tags are read from
 * source the way JUnit resolves them: its own class-level {@code @Tag} and {@code @Tags} annotations
 * plus those of every superclass reached through its {@code extends} chain in the module's test
 * sources ({@code @Tag} is {@code @Inherited}), each argument a string literal or a {@code String}
 * constant of the annotated file, its superclasses or the class a qualified reference names. An
 * argument the reader cannot resolve to a literal fails the test naming the file and the
 * annotation. Every
 * lane that selects it declares every required property, and its {@code test.mtls.port} is a host
 * port the compose service publishes. The required properties are derived from the IT's source — every
 * {@code test.mtls.*} key it reads through {@code System.getProperty} — not listed here. Before a
 * {@code test.mtls.*} value is judged, every {@code ${...}} reference in it is resolved, repeatedly
 * through the values it names, against {@code ${project.basedir}} / {@code ${basedir}} (the module
 * directory) and the POM {@code <properties>} that apply to the lane: the module profile's, the
 * module's, the root profile's and the root's, the nearest winning. A reference naming nothing there,
 * one that refers back to itself, or references nested deeper than {@value #MAX_REFERENCE_DEPTH} levels
 * fail the test naming the lane, the key and the reference or property, for a lane that selects the IT —
 * an unresolved value is never compared as text.
 * <p>
 * <strong>What it does not read.</strong> The parent POMs above the root {@code pom.xml}, which live
 * outside the repository; {@code settings.xml} and its profiles; properties supplied on the command
 * line, environment variables and any other Maven model value, so a {@code test.mtls.*} value that
 * references one fails; and how a profile is activated — {@code activeByDefault} and activation by property, JDK, OS or
 * file are not read, so each profile is judged as its own lane, alone, and two profiles activated
 * together are not judged as one. An include or exclude is evaluated only
 * in the one form this guard reads exactly as surefire does: a path with a directory part (it contains
 * a {@code /}) that ends in {@code .java} and uses no wildcard but {@code *}, {@code **} and {@code ?},
 * such as {@code **}{@code /*IT.java}. Every other form surefire accepts — no directory part
 * ({@code MtlsHandshakeIT.java}, {@code *IT.java}), no extension ({@code **}{@code /*IT}), a
 * {@code .class} extension, a bare class name, {@code %regex[...]}, {@code %ant[...]}, a comma-separated
 * list, a {@code !} negation, a {@code #} method filter, or anything else it cannot prove it reads like
 * surefire — fails the test naming the lane and the pattern rather than being read as "does not
 * select". On the tag side: method-level {@code @Tag} annotations,
 * tags declared on implemented interfaces, and a superclass outside the module's test sources or
 * named through a wildcard import — the {@code extends} chain stops there.
 * <p>
 * <strong>Controls</strong> (ADR-0030). The population is asserted non-empty before the rule runs: the
 * IT reads at least one property and among them the port, at least one lane selects the IT today and
 * at least one leaves it out. The detector is driven with POM specimens it must reject — a selecting
 * lane with no properties, with only the port, with the primary port, a lane with an include or exclude
 * outside the evaluated form, a lane the root's {@code plugins} or a root profile passes down, a port
 * reference that resolves to an unpublished port, cannot be resolved or is cyclic — and ones it must
 * accept. The specimens are strings in this file only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("every failsafe lane that runs MtlsHandshakeIT wires every test.mtls property it reads")
class MtlsLaneSelectionContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The module's test source root, which the IT and its superclasses are read from. */
    private static final Path TEST_SOURCES = MODULE.resolve("src/test/java");

    /** The IT's path relative to the test source root, which is what failsafe patterns match. */
    private static final String IT_PATH = "de/cuioss/sheriff/gateway/integration/MtlsHandshakeIT.java";

    private static final String FAILSAFE = "maven-failsafe-plugin";
    private static final String MTLS_SERVICE = "api-sheriff-mtls";
    private static final String PORT_KEY = "test.mtls.port";
    private static final String PRIMARY_PORT = "10443";
    private static final String DEFAULT_EXECUTION = "default";
    private static final String NO_PROFILE = "(no profile)";
    private static final String MTLS_PROPERTY_PREFIX = "test.mtls.";

    /** A {@code ${...}} property reference in a POM value; group 1 the property name. */
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]*)}");

    /** How deep {@code ${...}} references may nest before resolving them fails. */
    private static final int MAX_REFERENCE_DEPTH = 16;

    /**
     * The Maven model values a {@code test.mtls.*} value may name besides POM properties: the module
     * directory, which is the working directory under surefire.
     */
    private static final Map<String, String> BUILT_IN_PROPERTIES =
            Map.of("project.basedir", MODULE.toString(), "basedir", MODULE.toString());

    /** Failsafe's own includes, in force when no layer declares any. */
    private static final List<String> DEFAULT_INCLUDES = List.of("**/IT*.java", "**/*IT.java", "**/*ITCase.java");

    private static final Pattern REQUIRED_PROPERTY =
            Pattern.compile("System\\.getProperty\\(\\s*\"(test\\.mtls\\.[^\"]+)\"");

    /** A top-level class declaration: group 1 the class name, group 2 the type it extends, if any. */
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "(?m)^(?:(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\\s+)*"
                    + "class\\s+(\\w+)(?:\\s*<[^{]*?>)?(?:\\s+extends\\s+([\\w.]+))?");

    /** A class-level {@code @Tag} or {@code @Tags} annotation (group 1), written unindented, up to its '('. */
    private static final Pattern TAG_ANNOTATION = Pattern.compile("(?m)^@(?:org\\.junit\\.jupiter\\.api\\.)?(Tags?)\\s*\\(");

    /** A {@code @Tag} inside a {@code @Tags} container, up to its '('. */
    private static final Pattern INNER_TAG = Pattern.compile("@(?:org\\.junit\\.jupiter\\.api\\.)?Tag\\s*\\(");

    /**
     * The one include/exclude form {@link Lane#matches} reads exactly as surefire does: a path with a
     * directory part, every segment non-empty and made of Java identifier characters and the wildcards
     * {@code *}, {@code **} and {@code ?}, ending in {@code .java}. Surefire also accepts other forms — no
     * directory part, no extension, a {@code .class} extension, a bare class name, {@code %regex[...]},
     * {@code %ant[...]}, a comma-separated list, a {@code !} negation, a {@code #} method filter — which a
     * glob reading would judge differently or match nothing with, so a lane carrying any pattern outside
     * this form is refused instead.
     */
    private static final Pattern EVALUABLE_PATTERN = Pattern.compile("[\\w$*?]+(?:/[\\w$*?]+)*/[\\w$*?]+\\.java");

    private static final Pattern NAMED_VALUE = Pattern.compile("^value\\s*=\\s*");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");
    private static final Pattern CONSTANT_REFERENCE = Pattern.compile("(?:(\\w+)\\.)?([A-Za-z_$][\\w$]*)");

    // --- the real pom.xml files, the real IT and the real compose file ------------------------------

    @Test
    @DisplayName("every lane that selects MtlsHandshakeIT declares every test.mtls property and the published mTLS port")
    void lanesSelectingTheItWireEveryMtlsProperty() throws Exception {
        String itSource = Files.readString(existing(TEST_SOURCES.resolve(IT_PATH)));
        Set<String> required = requiredProperties(itSource);
        TagReading tagReading = effectiveTags(new Source(IT_PATH, itSource),
                MtlsLaneSelectionContractTest::testTreeSource);
        Set<String> tags = tagReading.tags();
        Set<String> published = publishedPorts();
        assertEquals(List.of(), tagReading.unresolved(),
                "a @Tag on MtlsHandshakeIT or a superclass could not be read to a literal, so its lanes cannot be judged");
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
        Set<String> required = requiredProperties(Files.readString(existing(TEST_SOURCES.resolve(IT_PATH))));
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
                        required, published).isEmpty(), "a bare lane in the module's own build was accepted"),
                () -> assertFalse(specimenViolations(profile("negated", "<groups>!sleep-bound</groups>", ""),
                                required, published).isEmpty(),
                        "a lane whose groups expression !sleep-bound selects the untagged IT was accepted"),
                () -> assertEquals(List.of(), specimenViolations(profile("unsatisfied",
                        "<groups>sleep-bound &amp; docker</groups>", ""), required, published, Set.of("docker")),
                        "a lane whose groups expression the IT's tags do not satisfy was rejected"),
                () -> assertEquals(List.of(), specimenViolations(profile("tag-excluded",
                        "<excludedGroups>docker | sleep-bound</excludedGroups>", ""), required, published,
                        Set.of("docker")), "a lane whose excludedGroups expression matches the IT's tag was rejected"),
                () -> assertTrue(specimenViolations(profile("malformed", "<groups>docker &amp;</groups>", ""),
                                required, published).stream()
                                .anyMatch(violation -> violation.contains("malformed/default") && violation.contains("docker &")),
                        "a lane with an unparseable groups expression was not refused naming the lane and expression"),
                () -> assertTrue(specimenViolations(profile("regex",
                                "<includes><include>%regex[.*MtlsHandshakeIT.*]</include></includes>", ""),
                                required, published).stream()
                                .anyMatch(violation -> violation.contains("regex/default")
                                        && violation.contains("%regex[.*MtlsHandshakeIT.*]")),
                        "a lane with a %regex include, which no glob reading can judge, was not refused naming the "
                                + "lane and pattern"),
                () -> assertTrue(specimenViolations(profile("listed",
                                "<includes><include>**/Mtls*IT.java, **/Other*IT.java</include></includes>", ""),
                                required, published).stream()
                                .anyMatch(violation -> violation.contains("listed/default")
                                        && violation.contains("**/Mtls*IT.java, **/Other*IT.java")),
                        "a lane with a comma-separated include list was not refused naming the lane and pattern"),
                () -> assertFalse(specimenViolations(profile("overridden", properties(wired),
                                "<systemPropertyVariables combine.self=\"override\"><" + PORT_KEY + ">10444</" + PORT_KEY
                                        + "></systemPropertyVariables>"), required, published).isEmpty(),
                        "a lane whose execution overrides the inherited properties down to the port was accepted"),
                () -> assertEquals(List.of(), specimenViolations(profile("appended",
                        "<excludes><exclude>**/MtlsHandshakeIT.java</exclude></excludes>",
                        "<excludes combine.children=\"append\"><exclude>**/Unrelated.java</exclude></excludes>"),
                        required, published), "a lane whose appended excludes keep the IT's exclusion was rejected"));
    }

    @Test
    @DisplayName("the detector refuses every include or exclude it cannot read exactly as surefire does")
    void detectorRefusesPatternsItCannotReadLikeSurefire() throws Exception {
        Set<String> required = requiredProperties(Files.readString(existing(TEST_SOURCES.resolve(IT_PATH))));
        Set<String> published = Set.of("10444");
        Map<String, String> wired = new LinkedHashMap<>();
        required.forEach(key -> wired.put(key, "declared"));
        wired.put(PORT_KEY, "10444");

        assertAll(
                () -> assertRefused("no-extension", "<includes><include>**/*IT</include></includes>", "**/*IT",
                        required, published),
                () -> assertRefused("no-directory", "<includes><include>MtlsHandshakeIT.java</include></includes>",
                        "MtlsHandshakeIT.java", required, published),
                () -> assertRefused("no-directory-glob", "<includes><include>*IT.java</include></includes>",
                        "*IT.java", required, published),
                () -> assertRefused("bare-class", "<includes><include>MtlsHandshakeIT</include></includes>",
                        "MtlsHandshakeIT", required, published),
                () -> assertRefused("class-file", "<includes><include>**/MtlsHandshakeIT.class</include></includes>",
                        "**/MtlsHandshakeIT.class", required, published),
                () -> assertRefused("class-file-exclude",
                        "<excludes><exclude>**/MtlsHandshakeIT.class</exclude></excludes>",
                        "**/MtlsHandshakeIT.class", required, published),
                () -> assertEquals(List.of(), specimenViolations(profile("single-wildcard", properties(wired)
                        + "<includes><include>**/Mtls?andshakeIT.java</include></includes>", ""), required, published),
                        "a fully wired lane whose include is a directory-qualified .java glob using ? was refused"),
                () -> assertEquals(List.of(), specimenViolations(profile("qualified", "<includes><include>" + IT_PATH
                        + "</include></includes><excludes><exclude>**/MtlsHandshakeIT.java</exclude></excludes>",
                        ""), required, published),
                        "a lane whose fully qualified include is excluded again was refused"));
    }

    @Test
    @DisplayName("the detector reads the failsafe executions the root pom's plugins and profiles pass down to the module")
    void detectorReadsEveryLayerTheRootPassesDown() throws Exception {
        Set<String> required = requiredProperties(Files.readString(existing(TEST_SOURCES.resolve(IT_PATH))));
        Set<String> published = Set.of("10444");
        Map<String, String> wired = new LinkedHashMap<>();
        required.forEach(key -> wired.put(key, "declared"));
        wired.put(PORT_KEY, "10444");

        List<String> rootPlugins = specimenViolations("", rootBuild("<plugins>" + failsafe("", "") + "</plugins>"),
                required, published);
        List<String> rootProfile = specimenViolations("", rootBuild("") + profile("root-lane", "", ""), required,
                published);
        List<String> inheritedFlag = specimenViolations("", rootBuild("<plugins><plugin><artifactId>" + FAILSAFE
                + "</artifactId><inherited>false</inherited></plugin></plugins>"), required, published);

        assertAll(
                () -> assertTrue(rootPlugins.stream().anyMatch(violation -> violation.contains(NO_PROFILE + "/default")
                                && violation.contains("does not declare")),
                        () -> "a failsafe execution in the root's build/plugins that selects the IT with no properties"
                                + " was not rejected naming the lane: " + rootPlugins),
                () -> assertTrue(rootProfile.stream().anyMatch(violation -> violation.contains("root-lane/default")
                                && violation.contains("does not declare")),
                        () -> "a failsafe execution in a root profile that selects the IT with no properties was not"
                                + " rejected naming the lane: " + rootProfile),
                () -> assertTrue(inheritedFlag.stream().anyMatch(violation -> violation.contains(NO_PROFILE + "/default")
                                && violation.contains("<inherited>")),
                        () -> "a root failsafe plugin carrying <inherited>, which this guard does not model, was not"
                                + " refused naming the lane: " + inheritedFlag),
                () -> assertEquals(List.of(), specimenViolations("<profiles><profile><id>completing</id><build><plugins>"
                        + "<plugin><artifactId>" + FAILSAFE + "</artifactId><configuration>" + properties(wired)
                        + "</configuration></plugin></plugins></build></profile></profiles>",
                        "<build><pluginManagement><plugins>"
                                + failsafe("", "<includes><include>**/*IT.java</include></includes>")
                                + "</plugins></pluginManagement></build>", required, published),
                        "a root pluginManagement execution that a module profile completes with the properties was"
                                + " rejected"),
                () -> assertEquals(List.of(), specimenViolations(profile("shared", properties(wired), ""),
                        rootBuild("") + profile("shared", "", ""), required, published),
                        "a root profile completed by the module profile of the same id was rejected"));
    }

    @Test
    @DisplayName("the detector resolves ${...} references in test.mtls properties before judging them")
    void detectorResolvesPropertyReferences() throws Exception {
        Set<String> required = requiredProperties(Files.readString(existing(TEST_SOURCES.resolve(IT_PATH))));
        Set<String> published = Set.of("10444");
        Map<String, String> referenced = new LinkedHashMap<>();
        required.forEach(key -> referenced.put(key, "declared"));
        referenced.put(PORT_KEY, "${mtls.port}");

        List<String> unpublished = specimenViolations(pomPropertiesElement("<mtls.port>10443</mtls.port>")
                + profile("ref-unpublished", properties(referenced), ""), required, published);
        Map<String, String> unknown = new LinkedHashMap<>(referenced);
        unknown.put(PORT_KEY, "${nowhere}");
        List<String> unresolvable = specimenViolations(profile("ref-unknown", properties(unknown), ""), required,
                published);
        List<String> cyclic = specimenViolations(pomPropertiesElement("<mtls.port>${mtls.alias}</mtls.port>"
                + "<mtls.alias>${mtls.port}</mtls.alias>") + profile("ref-cyclic", properties(referenced), ""),
                required, published);
        List<String> unknownGroups = specimenViolations(profile("groups-unknown", "<groups>${lane.tags}</groups>", ""),
                required, published);

        assertAll(
                () -> assertTrue(unknownGroups.stream().anyMatch(violation -> violation.contains("groups-unknown/default")
                                && violation.contains("${lane.tags}") && violation.contains("cannot be resolved")),
                        () -> "a lane whose groups reference cannot be resolved was read as a tag name instead of"
                                + " refused naming the lane and the reference: " + unknownGroups),
                () -> assertEquals(List.of(), specimenViolations(pomPropertiesElement("<lane.tags>sleep-bound</lane.tags>")
                        + profile("groups-resolved", "<groups>${lane.tags}</groups>", ""), required, published),
                        "a lane whose groups reference resolves to a tag the IT does not carry was rejected"),
                () -> assertFalse(specimenViolations(pomPropertiesElement("<lane.tags>!sleep-bound</lane.tags>")
                        + profile("groups-selecting", "<groups>${lane.tags}</groups>", ""), required, published)
                        .isEmpty(), "a lane whose groups reference resolves to an expression selecting the IT, with"
                        + " no properties, was accepted"),
                () -> assertEquals(List.of(), specimenViolations(pomPropertiesElement("<mtls.port>10444</mtls.port>")
                        + profile("ref-published", properties(referenced), ""), required, published),
                        "a lane whose port is a reference resolving to a published port was rejected"),
                () -> assertEquals(List.of(), specimenViolations(pomPropertiesElement("<mtls.port>10443</mtls.port>")
                        + profileWithProperties("ref-nearest", "<mtls.port>10444</mtls.port>",
                        properties(referenced)),
                        rootBuild("") + pomPropertiesElement("<mtls.port>9999</mtls.port>"), required, published),
                        "a reference the lane's profile resolves to a published port, over other values of the module"
                                + " and the root, was rejected"),
                () -> assertTrue(unpublished.stream().anyMatch(violation -> violation.contains("ref-unpublished/default")
                                && violation.contains("'10443'")),
                        () -> "a lane whose port reference resolves to an unpublished port was not rejected naming the"
                                + " lane and the resolved port: " + unpublished),
                () -> assertTrue(unresolvable.stream().anyMatch(violation -> violation.contains("ref-unknown/default")
                                && violation.contains(PORT_KEY) && violation.contains("${nowhere}")
                                && violation.contains("cannot be resolved")),
                        () -> "a lane whose port reference cannot be resolved was not refused naming the lane, the key"
                                + " and the reference: " + unresolvable),
                () -> assertTrue(cyclic.stream().anyMatch(violation -> violation.contains("ref-cyclic/default")
                                && violation.contains("'mtls.port' refers back to itself")),
                        () -> "a lane whose port reference is cyclic was not refused naming the property: " + cyclic));
    }

    private static void assertRefused(String lane, String pluginConfiguration, String pattern, Set<String> required,
            Set<String> published) throws ParserConfigurationException, SAXException, IOException {
        List<String> violations = specimenViolations(profile(lane, pluginConfiguration, ""), required, published);
        assertTrue(violations.stream().anyMatch(violation -> violation.contains(lane + "/default")
                        && violation.contains("'" + pattern + "'")),
                () -> "a lane with the pattern '" + pattern + "', which this guard cannot read exactly as surefire does,"
                        + " was not refused naming the lane and the pattern: " + violations);
    }

    @Test
    @DisplayName("the tag reader resolves inherited, constant-valued and contained @Tag annotations, and refuses others")
    void tagReaderResolvesTagsTheWayJUnitDoes() throws Exception {
        Set<String> required = requiredProperties(Files.readString(existing(TEST_SOURCES.resolve(IT_PATH))));

        TagReading inherited = specimenTags("""
                class SpecimenIT extends SpecimenBase {
                }
                """, Map.of("SpecimenBase", """
                @Tag("docker")
                public abstract class SpecimenBase {
                }
                """));
        TagReading constant = specimenTags("""
                @Tag(LANE)
                class SpecimenIT extends SpecimenBase {
                    static final String LANE = "slow";
                }
                """, Map.of("SpecimenBase", """
                @Tag(SHARED)
                public abstract class SpecimenBase {
                    protected static final String SHARED = "shared";
                }
                """));
        TagReading contained = specimenTags("""
                @Tags({@Tag("alpha"), @Tag("beta")})
                class SpecimenIT {
                }
                """, Map.of());
        TagReading unresolvable = specimenTags("""
                @Tag(MISSING)
                class SpecimenIT {
                }
                """, Map.of());

        assertAll(
                () -> assertEquals(new TagReading(Set.of("docker"), List.of()), inherited,
                        "a tag declared on the superclass applies to the IT, as JUnit's @Tag is @Inherited"),
                () -> assertFalse(specimenViolations(profile("docker-lane", "<groups>docker</groups>", ""), required,
                                Set.of("10444"), inherited.tags()).isEmpty(),
                        "a lane selecting the inherited tag with no properties was accepted"),
                () -> assertEquals(new TagReading(Set.of("slow", "shared"), List.of()), constant,
                        "a @Tag naming a String constant of the file or of its superclass carries that constant's value"),
                () -> assertEquals(new TagReading(Set.of("alpha", "beta"), List.of()), contained,
                        "every @Tag of a @Tags container applies"),
                () -> assertTrue(unresolvable.unresolved().stream()
                                .anyMatch(problem -> problem.contains("SpecimenIT.java") && problem.contains("@Tag(MISSING)")),
                        () -> "an unresolvable @Tag argument was not refused naming the file and the annotation: "
                                + unresolvable));
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
            if (!lane.unreadable().isEmpty()) {
                violations.add("lane " + lane.name() + " cannot be judged: " + lane.unreadable()
                        + ". This guard cannot tell whether the lane runs MtlsHandshakeIT, so it refuses the lane"
                        + " rather than reading it as one that does not");
                continue;
            }
            if (!lane.selects(IT_PATH, tags)) {
                continue;
            }
            if (!lane.unresolvedProperties().isEmpty()) {
                violations.add("lane " + lane.name() + " selects MtlsHandshakeIT, but " + lane.unresolvedProperties()
                        + ". This guard cannot tell what the IT receives, so it refuses the lane rather than"
                        + " comparing the unresolved text");
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
        return specimenViolations(moduleBody, required, published, Set.of());
    }

    private static List<String> specimenViolations(String moduleBody, Set<String> required, Set<String> published,
            Set<String> tags) throws ParserConfigurationException, SAXException, IOException {
        return specimenViolations(moduleBody, rootBuild(""), required, published, tags);
    }

    private static List<String> specimenViolations(String moduleBody, String rootBody, Set<String> required,
            Set<String> published) throws ParserConfigurationException, SAXException, IOException {
        return specimenViolations(moduleBody, rootBody, required, published, Set.of());
    }

    private static List<String> specimenViolations(String moduleBody, String rootBody, Set<String> required,
            Set<String> published, Set<String> tags) throws ParserConfigurationException, SAXException, IOException {
        return violations(lanes(parse("<project>" + moduleBody + "</project>"), parse("<project>" + rootBody
                + "</project>")), required, tags, published);
    }

    /**
     * A root build whose {@code pluginManagement} carries the failsafe entry every specimen inherits,
     * followed by {@code plugins}.
     */
    private static String rootBuild(String plugins) {
        return "<build><pluginManagement><plugins>" + failsafe("<includes><include>**/*IT.java</include></includes>", "")
                + "</plugins></pluginManagement>" + plugins + "</build>";
    }

    // --- reading the IT and the compose file --------------------------------------------------------

    private static Set<String> requiredProperties(String itSource) {
        Set<String> keys = new TreeSet<>();
        REQUIRED_PROPERTY.matcher(itSource).results().forEach(match -> keys.add(match.group(1)));
        return keys;
    }

    /**
     * The tags JUnit gives a test class, read from source: the class's own {@code @Tag} and
     * {@code @Tags} annotations plus those of every superclass reached through its {@code extends}
     * chain, since {@code @Tag} is {@code @Inherited}. The chain is followed through the module's test
     * sources and stops at a type the lookup does not find there. A {@code @Tag} argument may be a
     * string literal or a {@code String} constant, found in the annotated file, a superclass of it, or
     * the class a qualified reference names; anything else is reported in
     * {@link TagReading#unresolved()}, never skipped.
     *
     * @param it     the test class's source
     * @param lookup finds a type's source by the simple name another source uses for it
     * @return the effective tags and the annotations that could not be read
     */
    private static TagReading effectiveTags(Source it, SourceLookup lookup) {
        List<Source> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Optional<Source> current = Optional.of(it);
        while (current.isPresent() && visited.add(current.get().path())) {
            Source source = current.get();
            chain.add(source);
            Matcher declaration = CLASS_DECLARATION.matcher(source.text());
            current = declaration.find() && declaration.group(2) != null
                    ? lookup.find(source, simpleName(declaration.group(2))) : Optional.empty();
        }
        Set<String> tags = new TreeSet<>();
        List<String> unresolved = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            readTagAnnotations(chain.subList(i, chain.size()), lookup, tags, unresolved);
        }
        return new TagReading(tags, List.copyOf(unresolved));
    }

    /**
     * Reads the class-level {@code @Tag} / {@code @Tags} annotations of the first source of
     * {@code scope}; the rest of {@code scope} is its superclass chain, searched for constants.
     */
    private static void readTagAnnotations(List<Source> scope, SourceLookup lookup, Set<String> tags,
            List<String> unresolved) {
        Source source = scope.getFirst();
        Matcher declaration = CLASS_DECLARATION.matcher(source.text());
        if (!declaration.find()) {
            unresolved.add(source.path() + ": no class declaration to read @Tag annotations from");
            return;
        }
        String header = source.text().substring(0, declaration.start());
        Matcher annotation = TAG_ANNOTATION.matcher(header);
        while (annotation.find()) {
            Optional<String> argument = annotationArgument(header, annotation.end());
            if (argument.isEmpty()) {
                unresolved.add(source.path() + ": " + annotation.group() + " is never closed");
                continue;
            }
            String text = annotation.group() + argument.get() + ")";
            if ("Tags".equals(annotation.group(1))) {
                Matcher inner = INNER_TAG.matcher(argument.get());
                while (inner.find()) {
                    Optional<String> innerArgument = annotationArgument(argument.get(), inner.end());
                    resolveTag(scope, lookup, innerArgument.map(value -> inner.group() + value + ")").orElse(text),
                            innerArgument.orElse(""), tags, unresolved);
                }
            } else {
                resolveTag(scope, lookup, text, argument.get(), tags, unresolved);
            }
        }
    }

    /** Resolves one {@code @Tag} argument to its literal, or records why it cannot be. */
    private static void resolveTag(List<Source> scope, SourceLookup lookup, String annotation, String argument,
            Set<String> tags, List<String> unresolved) {
        Source source = scope.getFirst();
        String value = NAMED_VALUE.matcher(argument.strip()).replaceFirst("");
        Matcher literal = STRING_LITERAL.matcher(value);
        if (literal.matches()) {
            tags.add(literal.group(1));
            return;
        }
        Matcher reference = CONSTANT_REFERENCE.matcher(value);
        if (!reference.matches()) {
            unresolved.add(source.path() + ": " + annotation + " is neither a string literal nor a String constant");
            return;
        }
        String owner = reference.group(1);
        String name = reference.group(2);
        Stream<Source> candidates = owner == null ? scope.stream()
                : Stream.concat(scope.stream().filter(candidate -> owner.equals(className(candidate))),
                lookup.find(source, owner).stream());
        Optional<String> constant = candidates.map(candidate -> constantIn(candidate, name))
                .flatMap(Optional::stream)
                .findFirst();
        constant.ifPresentOrElse(tags::add, () -> unresolved.add(source.path() + ": " + annotation
                + " names no String constant initialised with a literal in this file, its superclasses or "
                + (owner == null ? "any other source" : owner)));
    }

    private static Optional<String> constantIn(Source source, String name) {
        Matcher constant = Pattern.compile("\\bString\\s+" + Pattern.quote(name) + "\\s*=\\s*\"([^\"\\\\]*)\"\\s*;")
                .matcher(source.text());
        return constant.find() ? Optional.of(constant.group(1)) : Optional.empty();
    }

    private static String className(Source source) {
        Matcher declaration = CLASS_DECLARATION.matcher(source.text());
        return declaration.find() ? declaration.group(1) : "";
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /**
     * The argument text of an annotation, from just after its opening parenthesis to the one that
     * closes it, skipping quoted text and nested parentheses.
     *
     * @return the argument, or empty when it never closes
     */
    private static Optional<String> annotationArgument(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return Optional.of(text.substring(from, i));
                }
                depth--;
            }
        }
        return Optional.empty();
    }

    /** Reads a specimen IT through {@link #effectiveTags}, its superclasses found among {@code others} by name. */
    private static TagReading specimenTags(String itSource, Map<String, String> others) {
        return effectiveTags(new Source("SpecimenIT.java", itSource),
                (from, simpleName) -> Optional.ofNullable(others.get(simpleName))
                        .map(text -> new Source(simpleName + ".java", text)));
    }

    /**
     * Finds the source of a type a test source names by its simple name: through an explicit import
     * of it, else in the naming file's own package. Empty when the file is not in the module's test
     * tree, which is where reading a superclass chain stops.
     */
    private static Optional<Source> testTreeSource(Source from, String simpleName) {
        Matcher imported = Pattern.compile("(?m)^import\\s+([\\w.]+)\\." + Pattern.quote(simpleName) + "\\s*;")
                .matcher(from.text());
        String directory = imported.find() ? imported.group(1).replace('.', '/')
                : from.path().substring(0, Math.max(0, from.path().lastIndexOf('/')));
        String path = directory.isEmpty() ? simpleName + ".java" : directory + "/" + simpleName + ".java";
        Path file = TEST_SOURCES.resolve(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Source(path, Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the test source " + file, e);
        }
    }

    /**
     * A test source file.
     *
     * @param path the file's path relative to the test source root
     * @param text the file's text
     */
    private record Source(String path, String text) {
    }

    /** Finds the source of a type named, by its simple name, in another source. */
    @FunctionalInterface
    private interface SourceLookup {

        /**
         * @param from       the source naming the type
         * @param simpleName the type's simple name
         * @return the type's source, empty when it is outside the module's test tree
         */
        Optional<Source> find(Source from, String simpleName);
    }

    /**
     * The tags a test class carries.
     *
     * @param tags       the effective tags
     * @param unresolved one message per annotation that could not be read to a literal, naming its
     *                   file and the annotation
     */
    private record TagReading(Set<String> tags, List<String> unresolved) {
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
     * @param name                     {@code profile/execution}
     * @param includes                 the effective include patterns
     * @param excludes                 the effective exclude patterns
     * @param groups                   the lane's {@code groups} tag expressions, one per comma-separated
     *                                 entry; empty selects every test
     * @param excludedGroups           the lane's {@code excludedGroups} tag expressions
     * @param unreadable               why a tag expression or an include/exclude pattern could not be
     *                                 read, empty when every one of them was
     * @param properties               the effective {@code systemPropertyVariables}, every
     *                                 {@code test.mtls.*} value with its {@code ${...}} references resolved
     * @param unresolvedProperties     why a {@code test.mtls.*} value could not be resolved, naming the key
     *                                 and the reference; empty when every one of them was
     */
    private record Lane(String name, List<String> includes, List<String> excludes, List<TagExpression> groups,
    List<TagExpression> excludedGroups, List<String> unreadable, Map<String, String> properties,
    List<String> unresolvedProperties) {

        /**
         * Whether the lane runs a test at the path carrying the tags. A test passes {@code groups} when
         * that list is empty or any of its expressions holds, and is excluded when any
         * {@code excludedGroups} expression holds — how the JUnit Platform provider reads a
         * comma-separated list. A lane with an unreadable expression or pattern is treated as selecting
         * the test, so it is checked rather than passed over; {@link #violations} refuses it first.
         */
        boolean selects(String path, Set<String> tags) {
            if (!unreadable.isEmpty()) {
                return true;
            }
            return includes.stream().anyMatch(pattern -> matches(pattern, path))
                    && excludes.stream().noneMatch(pattern -> matches(pattern, path))
                    && (groups.isEmpty() || groups.stream().anyMatch(expression -> expression.test(tags)))
                    && excludedGroups.stream().noneMatch(expression -> expression.test(tags));
        }

        private static boolean matches(String pattern, String path) {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(path));
        }
    }

    /**
     * Every failsafe lane of the module: one per effective execution, for the build without a profile
     * and for each profile of the root or the module that declares the plugin in its {@code plugins} or
     * {@code pluginManagement}. A root profile and a module profile with the same id are one lane, as one
     * {@code -P} switch activates both.
     *
     * @param module the module's parsed {@code pom.xml}
     * @param root   the root's parsed {@code pom.xml}, the module's parent
     * @return the lanes, those of the build without a profile first, then the profiles in document order,
     *         the module's before the root's
     */
    private static List<Lane> lanes(Document module, Document root) {
        Element moduleProject = module.getDocumentElement();
        Element rootProject = root.getDocumentElement();
        Set<String> profileIds = new LinkedHashSet<>();
        Stream.of(moduleProject, rootProject)
                .flatMap(project -> profiles(project).stream())
                .filter(profile -> declaredFailsafe(profile).isPresent() || managedFailsafe(profile).isPresent())
                .forEach(profile -> profileIds.add(childText(profile, "id")));
        List<Lane> lanes = new ArrayList<>(lanesOf(NO_PROFILE, moduleProject, Optional.empty(), rootProject,
                Optional.empty()));
        for (String id : profileIds) {
            lanes.addAll(lanesOf(id, moduleProject, profile(moduleProject, id), rootProject, profile(rootProject, id)));
        }
        return lanes;
    }

    /**
     * The lanes of one profile — or of none — merged the way Maven builds the module's effective POM:
     * in each POM the profile is injected over that POM's own build, the module's result is inherited
     * over the root's, and the merged {@code pluginManagement} is then applied under the merged
     * {@code plugins}. Only a plugin some {@code plugins} layer declares produces lanes. A construct of the
     * root this merge does not model refuses every lane of the profile.
     */
    private static List<Lane> lanesOf(String profileId, Element moduleProject, Optional<Element> moduleProfile,
            Element rootProject, Optional<Element> rootProfile) {
        Optional<Plugin> declared = mergePlugins(
                injected(moduleProject, moduleProfile, MtlsLaneSelectionContractTest::declaredFailsafe),
                injected(rootProject, rootProfile, MtlsLaneSelectionContractTest::declaredFailsafe));
        Optional<Plugin> managed = mergePlugins(
                injected(moduleProject, moduleProfile, MtlsLaneSelectionContractTest::managedFailsafe),
                injected(rootProject, rootProfile, MtlsLaneSelectionContractTest::managedFailsafe));
        List<String> unrepresentable = inheritedFlags(rootProject, rootProfile);
        Map<String, String> pomProperties = new HashMap<>();
        Stream.of(Optional.of(rootProject), rootProfile, Optional.of(moduleProject), moduleProfile)
                .flatMap(Optional::stream)
                .flatMap(holder -> children(holder, "properties").stream())
                .flatMap(properties -> Conf.of(properties).children().stream())
                .forEach(property -> pomProperties.put(property.name(), property.value()));
        List<Lane> lanes = new ArrayList<>();
        declared.map(plugin -> plugin.over(managed)).ifPresent(effective -> effective.executions().forEach(
                (id, execution) -> lanes.add(lane(profileId + "/" + id, merge(execution, effective.configuration()),
                        unrepresentable, pomProperties))));
        return lanes;
    }

    /**
     * A system property value with every {@code ${...}} reference replaced by the property it names,
     * repeated through the values those properties hold. A name resolves through
     * {@link #BUILT_IN_PROPERTIES} first, then through {@code pomProperties}.
     *
     * @param value         the value to resolve
     * @param pomProperties the POM properties that apply to the lane, the nearest already winning
     * @param resolving     the property names whose values are being resolved, outermost first
     * @return the value without references
     * @throws IllegalArgumentException naming the reference when it names no property this guard reads,
     *                                  and naming the property when a reference refers back to it or the
     *                                  references nest deeper than {@value #MAX_REFERENCE_DEPTH} levels
     */
    private static String resolve(String value, Map<String, String> pomProperties, List<String> resolving) {
        Matcher reference = PROPERTY_REFERENCE.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (reference.find()) {
            String name = reference.group(1);
            if (resolving.contains(name)) {
                throw new IllegalArgumentException("property '" + name + "' refers back to itself through "
                        + String.join(" -> ", resolving) + " -> " + name);
            }
            if (resolving.size() >= MAX_REFERENCE_DEPTH) {
                throw new IllegalArgumentException("references nest deeper than " + MAX_REFERENCE_DEPTH
                        + " levels at property '" + name + "'");
            }
            String target = BUILT_IN_PROPERTIES.getOrDefault(name, pomProperties.get(name));
            if (target == null) {
                throw new IllegalArgumentException(reference.group() + " names no property of the lane's profile, the"
                        + " module pom or the root pom, and a value from the command line, settings.xml or the"
                        + " environment cannot be read");
            }
            List<String> deeper = new ArrayList<>(resolving);
            deeper.add(name);
            reference.appendReplacement(resolved, Matcher.quoteReplacement(resolve(target, pomProperties, deeper)));
        }
        reference.appendTail(resolved);
        return resolved.toString();
    }

    /** One POM's failsafe declaration in one section, its profile injected over its own build. */
    private static Optional<Plugin> injected(Element project, Optional<Element> profile,
            Function<Element, Optional<Element>> section) {
        return mergePlugins(profile.flatMap(section).map(Plugin::of), section.apply(project).map(Plugin::of));
    }

    /**
     * One message per failsafe declaration of the root — in its {@code plugins} or
     * {@code pluginManagement}, of its own build or of the profile — that carries {@code <inherited>} on
     * the plugin or an execution. The flag decides whether the module receives the declaration, which
     * this guard does not model. The module's own flags concern only its children, not its own build.
     */
    private static List<String> inheritedFlags(Element rootProject, Optional<Element> rootProfile) {
        return Stream.concat(Stream.of(rootProject), rootProfile.stream())
                .flatMap(holder -> Stream.of(declaredFailsafe(holder), managedFailsafe(holder)))
                .flatMap(Optional::stream)
                .filter(plugin -> !children(plugin, "inherited").isEmpty() || children(plugin, "executions").stream()
                        .flatMap(executions -> children(executions, "execution").stream())
                        .anyMatch(execution -> !children(execution, "inherited").isEmpty()))
                .map(_ -> "the root pom's " + FAILSAFE + " declares <inherited>, which decides whether the module"
                        + " receives it and which this guard does not model")
                .toList();
    }

    private static Optional<Plugin> mergePlugins(Optional<Plugin> dominant, Optional<Plugin> recessive) {
        return dominant.map(plugin -> plugin.over(recessive)).or(() -> recessive);
    }

    /** The failsafe plugin in the {@code build/plugins} of a project or profile. */
    private static Optional<Element> declaredFailsafe(Element holder) {
        return failsafe(children(holder, "build").stream());
    }

    /** The failsafe plugin in the {@code build/pluginManagement} of a project or profile. */
    private static Optional<Element> managedFailsafe(Element holder) {
        return failsafe(children(holder, "build").stream().flatMap(build -> children(build, "pluginManagement").stream()));
    }

    private static List<Element> profiles(Element project) {
        return children(project, "profiles").stream().flatMap(profiles -> children(profiles, "profile").stream())
                .toList();
    }

    private static Optional<Element> profile(Element project, String id) {
        return profiles(project).stream().filter(profile -> id.equals(childText(profile, "id"))).findFirst();
    }

    /**
     * A failsafe plugin declaration: its plugin-level configuration and its executions by id (an
     * execution without one is {@value #DEFAULT_EXECUTION}); a configuration absent from the declaration
     * is {@code null}.
     */
    private record Plugin(Conf configuration, Map<String, Conf> executions) {

        static Plugin of(Element element) {
            Map<String, Conf> executions = new LinkedHashMap<>();
            for (Element holder : children(element, "executions")) {
                for (Element execution : children(holder, "execution")) {
                    String id = childText(execution, "id");
                    String key = id.isEmpty() ? DEFAULT_EXECUTION : id;
                    executions.put(key, merge(executions.get(key),
                            MtlsLaneSelectionContractTest.configuration(execution)));
                }
            }
            return new Plugin(MtlsLaneSelectionContractTest.configuration(element),
                    Collections.unmodifiableMap(executions));
        }

        /** This declaration merged over a recessive one, executions matched by id. */
        Plugin over(Optional<Plugin> recessive) {
            if (recessive.isEmpty()) {
                return this;
            }
            Map<String, Conf> merged = new LinkedHashMap<>(executions);
            recessive.get().executions().forEach((id, inherited) -> merged.put(id,
                    merge(merged.get(id), inherited)));
            return new Plugin(merge(configuration, recessive.get().configuration()),
                    Collections.unmodifiableMap(merged));
        }
    }

    private static Lane lane(String name, Conf effective, List<String> unrepresentable,
            Map<String, String> pomProperties) {
        List<String> includes = values(effective, "includes");
        Map<String, String> properties = new LinkedHashMap<>();
        child(effective, "systemPropertyVariables")
                .ifPresent(variables -> variables.children().forEach(property -> properties.put(property.name(),
                        property.value())));
        List<String> unresolved = new ArrayList<>();
        properties.replaceAll((key, value) -> {
            if (!key.startsWith(MTLS_PROPERTY_PREFIX)) {
                return value;
            }
            try {
                return resolve(value, pomProperties, List.of());
            } catch (IllegalArgumentException e) {
                unresolved.add(key + " = '" + value + "' cannot be resolved: " + e.getMessage());
                return value;
            }
        });
        List<String> excludes = values(effective, "excludes");
        List<String> unreadable = new ArrayList<>(unrepresentable);
        refuseUnreadablePatterns("include", includes, unreadable);
        refuseUnreadablePatterns("exclude", excludes, unreadable);
        return new Lane(name, includes.isEmpty() ? DEFAULT_INCLUDES : includes, excludes,
                tagExpressions(effective, "groups", pomProperties, unreadable),
                tagExpressions(effective, "excludedGroups", pomProperties, unreadable),
                List.copyOf(unreadable), properties, List.copyOf(unresolved));
    }

    /**
     * Records every include or exclude pattern outside {@link #EVALUABLE_PATTERN}, naming the kind and
     * the pattern: read as a glob it could match nothing where surefire matches the IT, which would turn a
     * lane that runs the IT into one this guard passes over.
     */
    private static void refuseUnreadablePatterns(String kind, List<String> patterns, List<String> unreadable) {
        patterns.stream()
                .filter(pattern -> !EVALUABLE_PATTERN.matcher(pattern).matches())
                .forEach(pattern -> unreadable.add(kind + " pattern '" + pattern
                        + "' is not a directory-qualified .java path using only the wildcards *, ** and ?, the one"
                        + " form this guard reads exactly as surefire does"));
    }

    /**
     * The tag expressions of a {@code groups} or {@code excludedGroups} element: one per
     * comma-separated entry, after every {@code ${...}} reference in the element is resolved the way
     * {@link #resolve} resolves a {@code test.mtls.*} value — Maven interpolates the element before
     * failsafe reads it, so a reference read as a literal tag name would turn a lane that runs the IT
     * into one this guard passes over. An element whose references cannot be resolved, and an entry
     * that does not parse, is recorded in {@code unreadable}, naming the element and the text, and
     * contributes no expression.
     */
    private static List<TagExpression> tagExpressions(Conf effective, String name, Map<String, String> pomProperties,
            List<String> unreadable) {
        List<TagExpression> expressions = new ArrayList<>();
        Optional<String> declared = child(effective, name).map(Conf::value);
        if (declared.isEmpty()) {
            return expressions;
        }
        String value;
        try {
            value = resolve(declared.get(), pomProperties, List.of());
        } catch (IllegalArgumentException e) {
            unreadable.add(name + " '" + declared.get() + "' cannot be resolved: " + e.getMessage());
            return expressions;
        }
        for (String entry : commaSeparated(value)) {
            try {
                expressions.add(TagExpression.parse(entry));
            } catch (IllegalArgumentException e) {
                unreadable.add(name + " expression '" + entry + "' is not a JUnit tag expression: " + e.getMessage());
            }
        }
        return expressions;
    }

    private static List<String> values(Conf effective, String list) {
        return child(effective, list).map(holder -> holder.children().stream().map(Conf::value).toList())
                .orElse(List.of());
    }

    private static List<String> commaSeparated(String value) {
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            if (!entry.isBlank()) {
                entries.add(entry.strip());
            }
        }
        return entries;
    }

    // --- JUnit tag expressions ----------------------------------------------------------------------

    /**
     * A JUnit Platform tag expression, evaluated against a test's tags: plain tag names, {@code !}
     * (not), {@code &} (and), {@code |} (or) with that precedence, parentheses, and the
     * {@code any()} and {@code none()} keywords (the test has some tag / has none). Kept here rather
     * than borrowed from the JUnit launcher, which this module does not otherwise depend on.
     */
    @FunctionalInterface
    private interface TagExpression {

        boolean test(Set<String> tags);

        /**
         * @param expression the expression text
         * @return the parsed expression
         * @throws IllegalArgumentException naming the problem when the text is not a tag expression
         */
        static TagExpression parse(String expression) {
            TagExpressionParser parser = new TagExpressionParser(expression);
            TagExpression parsed = parser.or();
            parser.expectEnd();
            return parsed;
        }
    }

    /** Recursive-descent parser for {@link TagExpression}: or := and ('|' and)*, and := not ('&' not)*. */
    private static final class TagExpressionParser {

        private static final String OPERATORS = "()&|!";

        private final List<String> tokens = new ArrayList<>();
        private int position;

        TagExpressionParser(String expression) {
            int i = 0;
            while (i < expression.length()) {
                char c = expression.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (OPERATORS.indexOf(c) >= 0) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else {
                    int start = i;
                    while (i < expression.length() && !Character.isWhitespace(expression.charAt(i))
                            && OPERATORS.indexOf(expression.charAt(i)) < 0) {
                        i++;
                    }
                    tokens.add(expression.substring(start, i));
                }
            }
        }

        TagExpression or() {
            TagExpression left = and();
            while (accept("|")) {
                TagExpression first = left;
                TagExpression second = and();
                left = tags -> first.test(tags) || second.test(tags);
            }
            return left;
        }

        private TagExpression and() {
            TagExpression left = not();
            while (accept("&")) {
                TagExpression first = left;
                TagExpression second = not();
                left = tags -> first.test(tags) && second.test(tags);
            }
            return left;
        }

        private TagExpression not() {
            if (accept("!")) {
                TagExpression operand = not();
                return tags -> !operand.test(tags);
            }
            return primary();
        }

        private TagExpression primary() {
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("an operand is missing at the end");
            }
            String token = tokens.get(position++);
            if ("(".equals(token)) {
                TagExpression inner = or();
                if (!accept(")")) {
                    throw new IllegalArgumentException("a '(' is never closed");
                }
                return inner;
            }
            if (OPERATORS.contains(token)) {
                throw new IllegalArgumentException("'" + token + "' stands where an operand is expected");
            }
            if (("any".equals(token) || "none".equals(token)) && accept("(")) {
                if (!accept(")")) {
                    throw new IllegalArgumentException("'" + token + "(' is not closed by ')'");
                }
                return "any".equals(token) ? tags -> !tags.isEmpty() : Set::isEmpty;
            }
            return tags -> tags.contains(token);
        }

        private boolean accept(String token) {
            if (position < tokens.size() && tokens.get(position).equals(token)) {
                position++;
                return true;
            }
            return false;
        }

        void expectEnd() {
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("the expression is empty");
            }
            if (position < tokens.size()) {
                throw new IllegalArgumentException("'" + tokens.get(position) + "' follows a complete expression");
            }
        }
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

    /** A module profile carrying POM properties, whose failsafe plugin carries the given plugin-level configuration. */
    private static String profileWithProperties(String id, String properties, String pluginConfiguration) {
        return "<profiles><profile><id>" + id + "</id>" + pomPropertiesElement(properties) + "<build><plugins>"
                + failsafe(pluginConfiguration, "") + "</plugins></build></profile></profiles>";
    }

    /** A POM {@code <properties>} element around the given property elements. */
    private static String pomPropertiesElement(String properties) {
        return "<properties>" + properties + "</properties>";
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
