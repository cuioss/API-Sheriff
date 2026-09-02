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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Machine-asserts the contract of the published downstream build parent,
 * {@code de.cuioss.sheriff.gateway:api-sheriff-build-parent}, against the four POMs that jointly
 * define it and the shipped {@code application.properties} it reaches into.
 *
 * <h2>What this class is protecting</h2>
 *
 * The build parent makes one promise: <strong>a third party who never clones this repository
 * inherits from it, sets one property, and their build produces an API Sheriff image carrying a
 * custom context path; adopting a new release is a {@code <parent><version>} bump and nothing
 * else.</strong> Every assertion below is a clause of that promise, and each one guards a failure
 * mode that is <em>silent</em> — the build stays green while the promise stops holding. That is why
 * these are asserted structurally rather than left to a runbook.
 *
 * <h2>The two clauses that are counter-intuitive, and were established by measurement</h2>
 *
 * <ul>
 * <li><strong>The dependency version is {@code ${project.parent.version}}, not
 * {@code ${project.version}}.</strong> An inherited {@code <dependencies>} entry is interpolated
 * against the <em>child's</em> effective model, so {@code ${project.version}} resolves to the
 * CONSUMER's own version: the worked example failed with
 * {@code Could not find artifact de.cuioss.sheriff.gateway:api-sheriff:jar:1.0.0-SNAPSHOT}.
 * {@code ${project.parent.version}} resolves, in the consumer's model, to the version of the parent
 * it inherits — which is exactly the "bump the parent and the application moves with it" promise.</li>
 * <li><strong>The property mapping targets CARRIER keys, not the stock Quarkus keys.</strong>
 * {@code quarkus-maven-plugin} forwards only {@code quarkus.}-prefixed properties into augmentation,
 * and they land at ordinal 100 — below the ordinal-250 {@code application.properties} shipped inside
 * the {@code api-sheriff} JAR. A mapping straight onto {@code quarkus.http.root-path} is therefore
 * overridden by the declaration it means to change, silently: a build carrying
 * {@code sheriff.context-path=/gw} produced ZERO occurrences of {@code /gw} in the generated
 * bytecode. The shipped file instead declares those keys as <em>expressions</em> over
 * {@code quarkus.sheriff-context-path} / {@code quarkus.sheriff-mgmt-path}, which the parent
 * supplies. {@link ContextPathDefaultsTest} pins the declaration side; the coupling test below pins
 * that the two sides still name the SAME carrier — a rename on either side alone is the exact edit
 * that breaks the parent while leaving every build green.</li>
 * </ul>
 *
 * <h2>Every assertion fails on absence, not only on mismatch</h2>
 *
 * A structural test that reads XML is unfalsifiable by inspection unless the extraction itself is
 * controlled: a broken lookup that returns "nothing found" everywhere would pass a mismatch-only
 * assertion for every clause at once. So each clause asserts a concrete expected value against a
 * value that is {@code null} when the element is absent, and the {@link Controls} nested class pins
 * the extraction helpers in both directions against synthetic XML.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Downstream build parent: the inheritance contract a consumer relies on")
class BuildParentContractTest {

    /**
     * Module basedir is Surefire's working directory, so the reactor root is one level up. Every POM
     * below is resolved from there rather than from the classpath — these are BUILD inputs, never
     * packaged into any artifact, so there is no classpath form of them to read.
     */
    private static final Path REACTOR_ROOT = Path.of("..");

    private static final Path ROOT_POM = REACTOR_ROOT.resolve("pom.xml");
    private static final Path BUILD_PARENT_POM = REACTOR_ROOT.resolve("build-parent/pom.xml");
    private static final Path EXAMPLE_POM = REACTOR_ROOT.resolve("build-parent/example/pom.xml");
    private static final Path API_SHERIFF_POM = REACTOR_ROOT.resolve("api-sheriff/pom.xml");

    /**
     * The PACKAGED properties file, for the carrier-coupling assertion. Deliberately the packaged
     * copy and not {@code src/main/resources/...}, for the reason
     * {@link ShippedApplicationPropertiesTest} records: the artifact is what a consumer's
     * augmentation actually reads.
     */
    private static final Path PACKAGED_APPLICATION_PROPERTIES = Path.of("target/classes/application.properties");

    /** The single property a consumer is promised is enough. */
    private static final String CONSUMER_PROPERTY = "sheriff.context-path";

    /** The management-side sibling of {@link #CONSUMER_PROPERTY}. */
    private static final String CONSUMER_MANAGEMENT_PROPERTY = "sheriff.management-context-path";

    /** Matches {@code ${carrier:default}} and captures the carrier key. */
    private static final Pattern CARRIER_EXPRESSION = Pattern.compile("^\\$\\{([^:}]+):[^}]*}$");

    /**
     * The hygiene toolchain a consumer must NOT inherit: this project's own code-quality gates,
     * which a deployer never opted into. Every one is switched off as an inherited default.
     */
    private static final List<String> REQUIRED_HYGIENE_SKIPS = List.of(
            "maven.javadoc.skip", "checkstyle.skip", "spotbugs.skip", "pmd.skip", "cpd.skip",
            "enforcer.skip", "jacoco.skip", "license.skip", "dependency-check.skip", "skip.openrewrite");

    /**
     * Switches that must stay UNSET, because each one would break something the parent exists to
     * deliver. {@code skipPublishing} is the effective publish switch under {@code cui-java-parent}
     * and setting it would stop this POM reaching Maven Central at all; {@code maven.deploy.skip} is
     * the same concern one layer down; {@code maven.install.skip} would stop {@code mvn install}
     * putting the POM in the local repository, which silently breaks every inheriting consumer;
     * {@code maven.compiler.skip} and {@code skipTests} belong to the CONSUMER's own build, which
     * compiles its own sources and runs its own tests.
     */
    private static final List<String> MUST_NOT_BE_SET = List.of(
            "skipPublishing", "maven.deploy.skip", "maven.install.skip", "maven.compiler.skip", "skipTests");

    /**
     * The consumer-facing guide. It restates two of this POM's property sets as hardcoded prose — the
     * disabled-switch enumeration and the untouched-switch table — and neither can be generated into
     * AsciiDoc at build time. Where derivation is structurally impossible the answer is an assertion,
     * so assertions (6) and (7) below read both lists back out of the guide and compare them.
     */
    private static final Path DOWNSTREAM_GUIDE = REACTOR_ROOT.resolve("doc/user/downstream-parent.adoc");

    /** The sentence the guide's disabled-switch enumeration immediately follows. */
    private static final String DISABLED_LIST_ANCHOR =
            "switches every one of them off as an inherited default:";

    /** Matches the sentence introducing the untouched-switch table, capturing its count claim. */
    private static final Pattern LEFT_ALONE_INTRO =
            Pattern.compile("^(\\w+) switches are deliberately \\*left alone\\*");

    /** A backticked identifier — the form every property name takes in the guide. */
    private static final Pattern BACKTICKED = Pattern.compile("`([A-Za-z0-9._-]+)`");

    /** English cardinals 0..10, for the count claim the untouched-switch table's intro makes. */
    private static final List<String> CARDINALS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

    @Test
    @DisplayName("(1) One-property promise: the example declares one property and no build machinery")
    void exampleDeclaresExactlyOneConsumerProperty() throws Exception {
        Element example = rootOf(EXAMPLE_POM);
        Map<String, String> properties = propertiesOf(example);

        assertAll("the worked example must stay a one-property POM",
                () -> assertEquals(Set.of(CONSUMER_PROPERTY), properties.keySet(),
                        () -> "the example POM must declare EXACTLY one property, " + CONSUMER_PROPERTY
                                + ", because that single line IS the promise this parent makes; found "
                                + properties.keySet() + ". A property appearing here means the parent stopped "
                                + "supplying something a consumer needs, and the consumer had to compensate — "
                                + "fix the parent rather than widening this expectation"),
                () -> assertNotNull(emptyToNull(properties.get(CONSUMER_PROPERTY)),
                        () -> CONSUMER_PROPERTY + " must be declared with a non-empty value; an empty one would "
                                + "make the example prove nothing about the mapping it exists to exercise"),
                () -> assertNull(firstChild(example, "build"),
                        "the example POM must declare NO <build> element. Needing one is precisely the symptom "
                                + "that the parent no longer binds quarkus-maven-plugin for its consumers, and "
                                + "compensating here would hide that regression behind a green build"),
                () -> assertNull(firstChild(example, "dependencies"),
                        "the example POM must declare NO <dependencies> element: inheriting the parent is what "
                                + "puts api-sheriff on the classpath, and a consumer that has to declare it has "
                                + "lost the promise"),
                () -> assertNull(firstChild(example, "profiles"),
                        "the example POM must declare NO <profiles> element: -Pnative is inherited from the "
                                + "parent, and restating it here would let the two drift apart unnoticed"));
    }

    @Test
    @DisplayName("(2) Bump-is-enough promise: parent version tracks the reactor, dependency tracks the parent")
    void adoptingAReleaseIsAParentVersionBump() throws Exception {
        String reactorVersion = textOf(firstChild(rootOf(ROOT_POM), "version"));
        String exampleParentVersion = textOf(firstChild(firstChild(rootOf(EXAMPLE_POM), "parent"), "version"));
        String dependencyVersion = apiSheriffDependencyVersionIn(rootOf(BUILD_PARENT_POM));

        assertAll("adopting a release must cost exactly one edit",
                () -> assertEquals(reactorVersion, exampleParentVersion,
                        () -> "the example's <parent><version> must equal the reactor version (" + reactorVersion
                                + "); found " + rendered(exampleParentVersion) + ". A drifting example stops "
                                + "exercising the parent it documents, and the runbook in "
                                + "doc/user/downstream-parent.adoc would then be building an old contract"),
                () -> assertEquals("${project.parent.version}", dependencyVersion,
                        () -> "build-parent must declare its api-sheriff dependency at "
                                + "${project.parent.version}, not ${project.version}; found "
                                + rendered(dependencyVersion) + ". This is MEASURED, not stylistic: an inherited "
                                + "<dependencies> entry interpolates against the CHILD's effective model, so "
                                + "${project.version} resolves to the CONSUMER's own version and the build fails "
                                + "with 'Could not find artifact'. ${project.parent.version} resolves to the "
                                + "version of the parent the consumer inherits, which is the promise being made"));
    }

    @Test
    @DisplayName("(3) The parent maps the consumer property onto the CARRIER keys the shipped file reads")
    void parentMapsConsumerPropertyOntoCarrierKeys() throws Exception {
        Map<String, String> properties = propertiesOf(rootOf(BUILD_PARENT_POM));
        // Resolved here, outside assertAll, so a broken/flattened declaration in the shipped file fails
        // this test directly and with its own diagnosis rather than surfacing as a confusing null lookup.
        String applicationCarrier = carrierFor("quarkus.http.root-path");
        String managementCarrier = carrierFor("quarkus.management.root-path");

        assertAll("the consumer-facing knob and its mapping",
                () -> assertEquals("/", properties.get(CONSUMER_PROPERTY),
                        () -> CONSUMER_PROPERTY + " must default to '/' so an inheriting build that overrides "
                                + "nothing produces the stock image; found " + rendered(properties.get(CONSUMER_PROPERTY))),
                () -> assertEquals("/q", properties.get(CONSUMER_MANAGEMENT_PROPERTY),
                        () -> CONSUMER_MANAGEMENT_PROPERTY + " must default to '/q'; found "
                                + rendered(properties.get(CONSUMER_MANAGEMENT_PROPERTY))),
                () -> assertEquals("${" + CONSUMER_PROPERTY + "}", properties.get(applicationCarrier),
                        () -> "build-parent must map " + CONSUMER_PROPERTY + " onto the carrier key '"
                                + applicationCarrier + "' that the shipped application.properties reads for "
                                + "quarkus.http.root-path; found " + rendered(properties.get(applicationCarrier))
                                + ". The two sides must name the SAME carrier — renaming one alone is the exact "
                                + "edit that breaks this parent while leaving every build green. Do NOT 'correct' "
                                + "the target to quarkus.http.root-path: that key is beaten by the ordinal-250 "
                                + "declaration inside the api-sheriff JAR, so the override is silently lost"),
                () -> assertEquals("${" + CONSUMER_MANAGEMENT_PROPERTY + "}", properties.get(managementCarrier),
                        () -> "build-parent must map " + CONSUMER_MANAGEMENT_PROPERTY + " onto the carrier key '"
                                + managementCarrier + "' that the shipped application.properties reads for "
                                + "quarkus.management.root-path; found "
                                + rendered(properties.get(managementCarrier))),
                () -> assertNull(properties.get("quarkus.http.root-path"),
                        "build-parent must NOT set quarkus.http.root-path directly — an ordinal-100 build-system "
                                + "property cannot beat the ordinal-250 declaration in the api-sheriff JAR, so "
                                + "such a mapping is inert and its presence means the carrier indirection was "
                                + "misunderstood"),
                () -> assertNull(properties.get("quarkus.management.root-path"),
                        "build-parent must NOT set quarkus.management.root-path directly, for the same "
                                + "ordinal reason as quarkus.http.root-path"));
    }

    @Test
    @DisplayName("(4) Native-profile parity: the parent and api-sheriff configure the same native image")
    void nativeProfileMatchesApiSheriff() throws Exception {
        Map<String, String> parentNative = profilePropertiesOf(rootOf(BUILD_PARENT_POM), "native");
        Map<String, String> applicationNative = profilePropertiesOf(rootOf(API_SHERIFF_POM), "native");

        assertAll("the two native profiles must not drift apart",
                () -> assertFalse(applicationNative.isEmpty(),
                        "api-sheriff/pom.xml must declare a 'native' profile with properties; finding none means "
                                + "the lookup broke rather than that parity holds, and the comparison below would "
                                + "be vacuously green"),
                () -> assertEquals(applicationNative, parentNative,
                        () -> "build-parent's 'native' profile must mirror api-sheriff's property-for-property, "
                                + "so -Pnative produces the same image for a consumer by inheritance as it does "
                                + "in this reactor. api-sheriff declares " + applicationNative
                                + " but build-parent declares " + parentNative + ". Move the two back into step; "
                                + "do not delete this assertion, because a silently differently-configured native "
                                + "image is exactly what it exists to prevent"));
    }

    @Test
    @DisplayName("(5) Publishability: hygiene is switched off, but nothing that would stop the POM shipping")
    void parentIsPublishableAndNeutralisesHygieneOnly() throws Exception {
        Map<String, String> properties = propertiesOf(rootOf(BUILD_PARENT_POM));

        assertAll("the parent must ship, and must not impose this project's toolchain on a consumer",
                () -> assertEquals(REQUIRED_HYGIENE_SKIPS,
                        REQUIRED_HYGIENE_SKIPS.stream().filter(key -> "true".equals(properties.get(key))).toList(),
                        () -> "every hygiene switch must be set to 'true' as an inherited default, so a deployer "
                                + "is not handed failing builds over conventions they never adopted. Declared "
                                + "values: " + REQUIRED_HYGIENE_SKIPS.stream()
                                .map(key -> key + "=" + rendered(properties.get(key))).toList()),
                () -> assertEquals(List.of(),
                        MUST_NOT_BE_SET.stream().filter(properties::containsKey).toList(),
                        () -> "none of " + MUST_NOT_BE_SET + " may be set here. skipPublishing and "
                                + "maven.deploy.skip would stop this POM reaching Maven Central; "
                                + "maven.install.skip would stop `mvn install` putting it in the local "
                                + "repository, silently breaking every inheriting consumer's own install; "
                                + "maven.compiler.skip and skipTests belong to the consumer's build, which "
                                + "compiles its own sources and runs its own tests. Declared: "
                                + MUST_NOT_BE_SET.stream().filter(properties::containsKey).toList()));
    }

    @Test
    @DisplayName("(6) Guide parity: the documented disabled-switch list is the set the parent declares")
    void guideEnumeratesExactlyTheDeclaredSkipSwitches() throws Exception {
        Set<String> declared = declaredSkipSwitches();
        Set<String> documented = documentedDisabledSwitches(guideLines());

        assertAll("the guide's hardcoded enumeration must mirror the POM's authoritative set",
                () -> assertFalse(declared.isEmpty(),
                        "build-parent/pom.xml must declare at least one skip switch; finding none means the "
                                + "property lookup broke rather than that the two sides agree, and the comparison "
                                + "below would be vacuously green"),
                () -> assertEquals(declared, documented,
                        () -> "doc/user/downstream-parent.adoc enumerates " + documented
                                + " but build-parent/pom.xml declares " + declared
                                + ". A consumer reads that list to decide which switches they can flip, so a "
                                + "switch present in one side and absent from the other is a false statement about "
                                + "their build. The list cannot be generated into AsciiDoc at build time, which is "
                                + "why it is asserted instead — edit the GUIDE to match the POM. Widening this "
                                + "expectation, or deleting the assertion, restores exactly the silent drift it "
                                + "exists to catch"));
    }

    @Test
    @DisplayName("(7) Guide parity: the documented untouched-switch table is the must-not-be-set contract")
    void guideEnumeratesExactlyTheUntouchedSwitches() throws Exception {
        List<String> lines = guideLines();
        Set<String> documented = documentedUntouchedSwitches(lines);
        String cardinal = documentedUntouchedCardinal(lines);

        assertAll("the untouched-switch table must mirror the must-not-be-set contract",
                () -> assertTrue(MUST_NOT_BE_SET.size() < CARDINALS.size(),
                        () -> "the must-not-be-set contract has grown to " + MUST_NOT_BE_SET.size()
                                + " entries, beyond the cardinals this assertion can spell. Extend CARDINALS "
                                + "rather than dropping the count check"),
                () -> assertEquals(Set.copyOf(MUST_NOT_BE_SET), documented,
                        () -> "doc/user/downstream-parent.adoc's untouched-switch table lists " + documented
                                + " but the contract asserted by (5) is " + MUST_NOT_BE_SET
                                + ". Each of those switches is left unset for a reason the table states; a row "
                                + "the contract does not carry documents a promise nothing keeps, and a contract "
                                + "entry with no row leaves a consumer guessing why their build broke. Edit the "
                                + "GUIDE, or extend MUST_NOT_BE_SET if the contract genuinely changed — do not "
                                + "relax the comparison"),
                () -> assertEquals(CARDINALS.get(MUST_NOT_BE_SET.size()), cardinal.toLowerCase(Locale.ROOT),
                        () -> "the guide says \"" + cardinal + " switches are deliberately left alone\" while the "
                                + "contract carries " + MUST_NOT_BE_SET.size()
                                + ". A count in prose goes stale the moment the set beneath it moves, and it is "
                                + "the one part of the sentence a reader trusts without checking"));
    }

    /**
     * Matched positive/negative controls over the extraction helpers every assertion above depends
     * on. Without these the whole class is unfalsifiable by inspection: it is green today because
     * the POMs are correct, and it would be equally green if {@link #propertiesOf},
     * {@link #firstChild} or {@link #profilePropertiesOf} were broken and reported "nothing found"
     * for everything — which is precisely the state in which a mismatch-only assertion cannot fire.
     */
    @Nested
    @DisplayName("Controls: the XML extraction discriminates present from absent")
    class Controls {

        private static final String SAMPLE = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <properties>
                    <alpha>one</alpha>
                    <beta></beta>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>de.cuioss.sheriff.gateway</groupId>
                      <artifactId>api-sheriff</artifactId>
                      <version>${project.parent.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>other</groupId>
                      <artifactId>api-sheriff</artifactId>
                      <version>WRONG</version>
                    </dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>native</id>
                      <properties><gamma>yes</gamma></properties>
                    </profile>
                    <profile>
                      <id>other</id>
                      <properties><delta>no</delta></properties>
                    </profile>
                  </profiles>
                </project>
                """;

        @Test
        @DisplayName("propertiesOf finds a declared property and reports an absent one as absent")
        void propertyLookupDiscriminates() throws Exception {
            Map<String, String> properties = propertiesOf(parse(SAMPLE));

            assertAll("property extraction",
                    () -> assertEquals("one", properties.get("alpha"),
                            "a declared property must be found with its exact value"),
                    () -> assertEquals("", properties.get("beta"),
                            "an empty declaration must read as empty, not as absent — the two mean different "
                                    + "things for the one-property assertion"),
                    () -> assertNull(properties.get("epsilon"),
                            "an undeclared property must read as null; if it did not, every 'must not be set' "
                                    + "assertion above would be vacuous"));
        }

        @Test
        @DisplayName("firstChild finds a present element and reports an absent one as absent")
        void elementLookupDiscriminates() throws Exception {
            Element root = parse(SAMPLE);

            assertAll("element extraction",
                    () -> assertNotNull(firstChild(root, "dependencies"),
                            "a present element must be found, or assertion (1)'s no-<build> checks would pass "
                                    + "against a POM that does declare one"),
                    () -> assertNull(firstChild(root, "build"),
                            "an absent element must read as null"));
        }

        @Test
        @DisplayName("the dependency lookup keys on groupId AND artifactId, not artifactId alone")
        void dependencyLookupIsCoordinateScoped() throws Exception {
            assertEquals("${project.parent.version}", apiSheriffDependencyVersionIn(parse(SAMPLE)),
                    "the lookup must match the full de.cuioss.sheriff.gateway:api-sheriff coordinate; matching "
                            + "on artifactId alone would pick up the decoy entry and assertion (2) would then "
                            + "report a version that is not the one a consumer resolves");
        }

        @Test
        @DisplayName("the profile lookup selects by id and reports an unknown id as empty")
        void profileLookupDiscriminates() throws Exception {
            Element root = parse(SAMPLE);

            assertAll("profile extraction",
                    () -> assertEquals(Map.of("gamma", "yes"), profilePropertiesOf(root, "native"),
                            "the named profile's properties must be returned, and only that profile's — picking "
                                    + "up a sibling profile would make the parity assertion compare the wrong sets"),
                    () -> assertEquals(Map.of(), profilePropertiesOf(root, "absent"),
                            "an unknown profile id must yield an empty map, which is why assertion (4) checks "
                                    + "api-sheriff's set is non-empty before comparing"));
        }

        @Test
        @DisplayName("the carrier extraction reads the key out of an expression and rejects a literal")
        void carrierExtractionDiscriminates() {
            assertAll("carrier extraction",
                    () -> assertEquals(Optional.of("quarkus.sheriff-context-path"),
                            carrierIn("${quarkus.sheriff-context-path:/}"),
                            "the carrier key must be read out of the expression, since that is the name "
                                    + "build-parent has to map onto"),
                    () -> assertEquals(Optional.empty(), carrierIn("/"),
                            "a plain literal must yield no carrier — that is the state in which the build seam "
                                    + "is dead, and assertion (3) must fail rather than silently compare null "
                                    + "against null"),
                    () -> assertEquals(Optional.empty(), carrierIn("${no-default}"),
                            "an expression without an inline default must yield no carrier: the default is what "
                                    + "keeps an unconfigured build shipping the stock path"));
        }

        /**
         * Deliberately seeds BOTH cell kinds with backticked property names. The table's prose cells
         * genuinely do name properties in backticks — the real guide's last row explains the
         * {@code skipPublishing} / {@code maven.deploy.skip} distinction inside its prose — so a parse
         * that collected every backtick in the block would still be green against today's table by
         * coincidence, and would silently start collecting the wrong set the moment a prose cell
         * mentioned a property the table does not list.
         */
        private static final String TABLE_SAMPLE = """
                Three switches are deliberately *left alone*, and each for a reason you may care about:

                [cols="1,3", options="header"]
                |===
                | Property | Why it is untouched

                | `alpha.skip`
                | Prose that names `decoy.skip`, which is not a row.

                | `beta.skip` +
                  `gamma.skip`
                | Both left unset; see `second.decoy` for why.
                |===
                """;

        @Test
        @DisplayName("the untouched-switch table parse reads property cells and ignores prose backticks")
        void untouchedTableParseDiscriminatesCellKinds() {
            List<String> lines = TABLE_SAMPLE.lines().toList();

            assertAll("untouched-switch table extraction",
                    () -> assertEquals(Set.of("alpha.skip", "beta.skip", "gamma.skip"),
                            documentedUntouchedSwitches(lines),
                            "only a cell BEGINNING with a backticked token is a property cell; a prose cell "
                                    + "naming a property in backticks must not be collected, and a multi-property "
                                    + "row's continuation line must be"),
                    () -> assertEquals("Three", documentedUntouchedCardinal(lines),
                            "the count claim must be read out of the introducing sentence, since that is the "
                                    + "number a reader checks the table against"));
        }

        @Test
        @DisplayName("the disabled-switch parse reads the anchored paragraph and nothing after it")
        void disabledListParseStopsAtTheParagraph() {
            List<String> lines = ("""
                    ... so the build parent switches every one of them off as an inherited default:

                    `alpha.skip`, `beta.skip`,
                    `gamma.skip`.

                    A later paragraph mentioning `not.a.member` must not be collected.
                    """).lines().toList();

            assertEquals(Set.of("alpha.skip", "beta.skip", "gamma.skip"), documentedDisabledSwitches(lines),
                    "the enumeration is the one paragraph after the anchor sentence — it wraps across lines, so "
                            + "the parse must join them, and it must stop at the blank line rather than absorbing "
                            + "every backtick in the rest of the document");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Extraction helpers. Every one returns null / empty on absence so the assertions above can fail
    // on absence rather than only on mismatch; the Controls nested class pins that in both directions.
    // ---------------------------------------------------------------------------------------------

    /**
     * The skip switches build-parent declares as inherited defaults: every property whose NAME names a
     * skip and whose value is {@code true}. Derived from the POM rather than restated, so a switch
     * added there is picked up here without editing this class — which is what makes assertion (6) a
     * guard on the guide rather than on a second hardcoded copy.
     *
     * @return the declared skip-switch names, in document order
     * @throws IOException if the POM cannot be read
     * @throws SAXException if the POM cannot be parsed
     * @throws ParserConfigurationException if the parser cannot be configured
     */
    private static Set<String> declaredSkipSwitches()
            throws IOException, SAXException, ParserConfigurationException {
        Set<String> skips = new LinkedHashSet<>();
        propertiesOf(rootOf(BUILD_PARENT_POM)).forEach((key, value) -> {
            if (key.toLowerCase(Locale.ROOT).contains("skip") && "true".equals(value)) {
                skips.add(key);
            }
        });
        return skips;
    }

    /**
     * @return the consumer guide's lines
     * @throws IOException if the guide cannot be read
     */
    private static List<String> guideLines() throws IOException {
        assertTrue(Files.isRegularFile(DOWNSTREAM_GUIDE),
                () -> "expected the consumer guide at " + DOWNSTREAM_GUIDE.toAbsolutePath()
                        + " (module basedir is Surefire's working directory, so the reactor root is '..'). "
                        + "It is missing, so both guide-parity assertions would assert over nothing");
        return Files.readAllLines(DOWNSTREAM_GUIDE);
    }

    /**
     * The property names the guide enumerates as switched off, read out of the paragraph following
     * {@link #DISABLED_LIST_ANCHOR}. The anchor is a sentence rather than a line number so the parse
     * survives ordinary editing above it; a rewording of the anchor itself fails loudly instead of
     * silently reading an empty list.
     *
     * @param lines the guide's lines
     * @return the enumerated property names, in document order
     */
    private static Set<String> documentedDisabledSwitches(List<String> lines) {
        int index = indexOfLineContaining(lines, DISABLED_LIST_ANCHOR) + 1;
        while (index < lines.size() && lines.get(index).isBlank()) {
            index++;
        }
        StringBuilder paragraph = new StringBuilder();
        while (index < lines.size() && !lines.get(index).isBlank()) {
            paragraph.append(lines.get(index)).append(' ');
            index++;
        }
        Set<String> names = backtickedIn(paragraph.toString());
        assertFalse(names.isEmpty(),
                () -> "the paragraph after \"" + DISABLED_LIST_ANCHOR + "\" in "
                        + DOWNSTREAM_GUIDE.toAbsolutePath() + " names no backticked property. The enumeration "
                        + "is read from exactly there, so an empty read means the parse broke rather than that "
                        + "the guide lists nothing");
        return names;
    }

    /**
     * The property names the guide's untouched-switch table lists, taken from its PROPERTY cells only.
     * A cell is a property cell when it begins with a backticked token; a prose cell that happens to
     * name a property in backticks is deliberately not collected, because collecting it would compare
     * the contract against a set the table never claimed.
     *
     * @param lines the guide's lines
     * @return the tabulated property names, in document order
     */
    private static Set<String> documentedUntouchedSwitches(List<String> lines) {
        int start = indexOfLineFrom(lines, indexOfLineMatching(lines, LEFT_ALONE_INTRO), "|===");
        Set<String> names = new LinkedHashSet<>();
        StringBuilder cell = new StringBuilder();
        boolean propertyCell = false;
        for (int index = start + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if ("|===".equals(line.strip())) {
                if (propertyCell) {
                    names.addAll(backtickedIn(cell.toString()));
                }
                return names;
            }
            if (line.startsWith("|")) {
                if (propertyCell) {
                    names.addAll(backtickedIn(cell.toString()));
                }
                cell.setLength(0);
                String body = line.substring(1).stripLeading();
                propertyCell = body.startsWith("`");
                cell.append(body);
            } else if (propertyCell) {
                cell.append(' ').append(line);
            }
        }
        return fail("the untouched-switch table in " + DOWNSTREAM_GUIDE.toAbsolutePath()
                + " is not terminated by a '|===' delimiter, so its rows cannot be read. Restore the "
                + "delimiter rather than relaxing this assertion");
    }

    /**
     * The count claim the untouched-switch table's introducing sentence makes, as written.
     *
     * @param lines the guide's lines
     * @return the cardinal word, in its original casing
     */
    private static String documentedUntouchedCardinal(List<String> lines) {
        Matcher matcher = LEFT_ALONE_INTRO.matcher(lines.get(indexOfLineMatching(lines, LEFT_ALONE_INTRO)));
        assertTrue(matcher.find(),
                "the line located by the untouched-switch intro pattern must match it — if it does not, the "
                        + "lookup and the extraction disagree and the count claim is read from the wrong line");
        return matcher.group(1);
    }

    /** @return the backticked identifiers in {@code text}, in order of appearance */
    private static Set<String> backtickedIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = BACKTICKED.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** @return the index of the first line containing {@code needle}; fails when there is none */
    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(needle)) {
                return index;
            }
        }
        return fail("no line containing \"" + needle + "\" in " + DOWNSTREAM_GUIDE.toAbsolutePath()
                + " — the enumeration is located by that anchor sentence, so rewording it silently disarms "
                + "the guard. Restore the sentence, or update the anchor to follow the rewrite");
    }

    /** @return the index of the first line matching {@code pattern}; fails when there is none */
    private static int indexOfLineMatching(List<String> lines, Pattern pattern) {
        for (int index = 0; index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                return index;
            }
        }
        return fail("no line matching /" + pattern.pattern() + "/ in " + DOWNSTREAM_GUIDE.toAbsolutePath()
                + " — the untouched-switch table is located by that sentence, so rewording it silently "
                + "disarms the guard. Restore the sentence, or update the pattern to follow the rewrite");
    }

    /** @return the index of the first line at or after {@code from} equal to {@code exact} */
    private static int indexOfLineFrom(List<String> lines, int from, String exact) {
        for (int index = from; index < lines.size(); index++) {
            if (exact.equals(lines.get(index).strip())) {
                return index;
            }
        }
        return fail("no '" + exact + "' delimiter after line " + from + " in "
                + DOWNSTREAM_GUIDE.toAbsolutePath() + " — the untouched-switch table must follow its "
                + "introducing sentence");
    }

    /**
     * The carrier key the shipped {@code application.properties} reads for {@code key}.
     *
     * @param key the stock Quarkus key whose declared value is a carrier expression
     * @return the carrier key name
     * @throws IOException if the packaged properties file cannot be read
     */
    private static String carrierFor(String key) throws IOException {
        Properties packaged = new Properties();
        assertTrue(Files.isRegularFile(PACKAGED_APPLICATION_PROPERTIES),
                () -> "expected the packaged properties file at " + PACKAGED_APPLICATION_PROPERTIES.toAbsolutePath()
                        + "; without it the carrier-coupling assertion would assert over nothing");
        try (Reader reader = Files.newBufferedReader(PACKAGED_APPLICATION_PROPERTIES)) {
            packaged.load(reader);
        }
        String declared = packaged.getProperty(key);
        return carrierIn(declared).orElseThrow(() -> new AssertionError(
                key + " must be declared in the packaged application.properties as an expression over a carrier "
                        + "key, e.g. ${quarkus.sheriff-context-path:/}; found " + rendered(declared)
                        + ". Without that expression the build seam is dead: a Maven property cannot beat this "
                        + "file's ordinal-250 declaration. See ContextPathDefaultsTest and the (e) block in "
                        + "src/main/resources/application.properties"));
    }

    /** @return the carrier key inside a {@code ${carrier:default}} expression, if the value is one */
    private static Optional<String> carrierIn(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = CARRIER_EXPRESSION.matcher(value.strip());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** @return the {@code <properties>} of {@code parent}, in document order; empty when absent */
    private static Map<String, String> propertiesOf(Element parent) {
        Map<String, String> properties = new LinkedHashMap<>();
        Element node = firstChild(parent, "properties");
        if (node == null) {
            return properties;
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                properties.put(child.getLocalName() == null ? child.getNodeName() : child.getLocalName(),
                        child.getTextContent().strip());
            }
        }
        return properties;
    }

    /** @return the properties of the {@code <profile>} carrying {@code id}; empty when there is none */
    private static Map<String, String> profilePropertiesOf(Element project, String id) {
        Element profiles = firstChild(project, "profiles");
        if (profiles == null) {
            return Map.of();
        }
        NodeList children = profiles.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && "profile".equals(localNameOf(child))
                    && id.equals(textOf(firstChild((Element) child, "id")))) {
                return propertiesOf((Element) child);
            }
        }
        return Map.of();
    }

    /**
     * @return the {@code <version>} of the {@code de.cuioss.sheriff.gateway:api-sheriff} dependency,
     *         matched on the FULL coordinate; {@code null} when the dependency is absent
     */
    private static String apiSheriffDependencyVersionIn(Element project) {
        Element dependencies = firstChild(project, "dependencies");
        if (dependencies == null) {
            return null;
        }
        NodeList children = dependencies.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(localNameOf(child))) {
                continue;
            }
            Element dependency = (Element) child;
            if ("de.cuioss.sheriff.gateway".equals(textOf(firstChild(dependency, "groupId")))
                    && "api-sheriff".equals(textOf(firstChild(dependency, "artifactId")))) {
                return textOf(firstChild(dependency, "version"));
            }
        }
        return null;
    }

    /** @return the first direct child element named {@code name}, or {@code null} when absent */
    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(localNameOf(child))) {
                return (Element) child;
            }
        }
        return null;
    }

    /** @return the element's local name, falling back to the qualified name for a non-namespaced doc */
    private static String localNameOf(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    /** @return the stripped text of {@code element}, or {@code null} when the element is absent */
    private static String textOf(Element element) {
        return element == null ? null : element.getTextContent().strip();
    }

    /** @return {@code value} unless it is blank, in which case {@code null} */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** @return the value quoted, or an explicit marker when it is absent */
    private static String rendered(String value) {
        return value == null ? "no declaration at all" : "'" + value + "'";
    }

    /** @return the document element of the POM at {@code path} */
    private static Element rootOf(Path path) throws IOException, SAXException, ParserConfigurationException {
        assertTrue(Files.isRegularFile(path),
                () -> "expected a POM at " + path.toAbsolutePath() + " (module basedir is Surefire's working "
                        + "directory, so the reactor root is '..'). It is missing, so every assertion reading it "
                        + "would assert over nothing — fix the layout rather than relaxing the guard");
        try (Reader reader = Files.newBufferedReader(path)) {
            return documentBuilder().parse(new InputSource(reader)).getDocumentElement();
        }
    }

    /** @return the document element of an inline XML sample, for the {@link Controls} */
    private static Element parse(String xml) throws IOException, SAXException, ParserConfigurationException {
        try (Reader reader = Reader.of(xml)) {
            return documentBuilder().parse(new InputSource(reader)).getDocumentElement();
        }
    }

    /**
     * A namespace-aware, XXE-hardened builder. External entities and DOCTYPEs are disabled: these
     * POMs are trusted build inputs, but a parser configured to fetch external resources is a habit
     * this project does not keep anywhere, least of all in a security-focused gateway.
     */
    private static DocumentBuilder documentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
