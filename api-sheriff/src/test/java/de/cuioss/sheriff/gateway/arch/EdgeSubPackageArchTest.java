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
package de.cuioss.sheriff.gateway.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.Slices;
import de.cuioss.sheriff.gateway.testsupport.RepositoryRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Standing guard for the shape of the edge (ADR-0068): the sub-packages beneath
 * {@code de.cuioss.sheriff.gateway.edge} form an acyclic graph, and none of them depends back on
 * the edge root package, where {@code GatewayEdgeRoute}, the thin entry point that wires them,
 * sits.
 * <p>
 * <strong>Two rules, each a property over a root package.</strong>
 * <ol>
 *   <li><em>No cycle between sub-packages</em> — every direct sub-package of the root is one slice
 *       (its classes and everything below it), and the slices are free of cycles.</li>
 *   <li><em>No dependency back on the root package</em> — no class in a sub-package depends on any
 *       class whose package is exactly the root package: {@code GatewayEdgeRoute}, the classes nested
 *       in it, and any further class later added to the root. A cycle through the root
 *       ({@code sub → GatewayEdgeRoute → sub}) is not a cycle between slices, because the root is in
 *       no slice; this rule is what closes that path.</li>
 * </ol>
 * Neither rule names the current sub-packages: a new sub-package is held to both the moment it
 * exists, without this file being edited. Both are written as methods over a root so the specimen
 * root can be run through exactly the same rule as production.
 * <p>
 * <strong>Four legs, per "Comment-Only Invariants Become Fitness Functions" in
 * {@code doc/development/test-corpus-integrity.adoc}.</strong>
 * <ol>
 *   <li><em>Non-vacuity</em> — at least two slices resolve, at least one sub-package of the root is
 *       declared, every declared sub-package still resolves to at least one class other than its
 *       {@code package-info}, and {@code GatewayEdgeRoute} is present in the root. The declared
 *       sub-packages are derived from the {@code package-info} classes the production import holds
 *       directly beneath the root, not from a list kept in this file. Without these a renamed root or
 *       an emptied tree would leave both rules green over nothing.</li>
 *   <li><em>Negative controls</em> — both rules must throw when run against the specimen root
 *       {@code arch.specimen.edgecycle}, whose {@code alpha} and {@code beta} sub-packages reference
 *       each other and whose {@code beta} references a class in the specimen root package.</li>
 *   <li><em>Matched positive control</em> — {@code GatewayEdgeRoute} is first asserted to live in
 *       the root and to depend on sub-package classes, and only then asserted to be outside the
 *       selection of the second rule. In that order the exclusion shows that the rule tells the root
 *       apart from its sub-packages, not that the class had stopped depending on them.</li>
 *   <li><em>Specimen carve-out</em> — the production import excludes the specimen package
 *       explicitly through {@link #EXCLUDE_SPECIMENS}, not only through the test-source filter, and
 *       that exclusion has its own control.</li>
 * </ol>
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, the same arrangement
 * {@link PreBootFrameworkFreeArchTest} uses.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Tag("isolated-fork")
class EdgeSubPackageArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";
    private static final String EDGE_ROOT = BASE_PACKAGE + ".edge";
    private static final String EDGE_ROOT_CLASS = EDGE_ROOT + ".GatewayEdgeRoute";

    private static final String SPECIMEN_PACKAGE = BASE_PACKAGE + ".arch.specimen";
    private static final String SPECIMEN_ROOT = SPECIMEN_PACKAGE + ".edgecycle";
    private static final String SPECIMEN_ROOT_CLASS = SPECIMEN_ROOT + ".EdgeRootSpecimen";

    /** The simple name ArchUnit gives the class compiled from a {@code package-info.java}. */
    private static final String PACKAGE_INFO = "package-info";

    /**
     * Keeps the specimen package out of an import, independent of the test-source filter. The
     * location is matched on its path segments, so it holds for a directory and a jar layout alike.
     */
    private static final ImportOption EXCLUDE_SPECIMENS =
            location -> !location.contains("/" + SPECIMEN_PACKAGE.replace('.', '/') + "/");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(EXCLUDE_SPECIMENS)
            .importPackages(BASE_PACKAGE);

    /**
     * The specimen root is imported <em>separately and with tests included</em>: the specimens live
     * in {@code src/test}, so the production import above deliberately cannot see them.
     */
    private static final JavaClasses SPECIMEN_CLASSES = new ClassFileImporter()
            .importPackages(SPECIMEN_ROOT);

    @Test
    @DisplayName("The edge sub-packages are free of cycles")
    void edgeSubPackagesAreFreeOfCycles() {
        subPackagesMustBeFreeOfCycles(EDGE_ROOT).check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("No class in an edge sub-package depends on a class in the edge root package")
    void edgeSubPackagesDoNotDependOnTheEdgeRootPackage() {
        subPackagesMustNotDependOnRootPackage(EDGE_ROOT).check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard. Both rules pass over an empty selection, and a rule that passes over nothing
     * cannot be told apart from a clean tree by its verdict.
     * <p>
     * The sub-packages checked here are the ones the production tree declares: every package of
     * {@code de.cuioss.sheriff.gateway} carries a {@code package-info.java}, whose compiled class the
     * import holds. A {@code package-info} alone does not count as content, so a sub-package that is
     * emptied but keeps its declaration is reported by name instead of quietly dropping out of the
     * rules' reach.
     * <p>
     * Deliberately direct counts rather than {@link ArchRule}s with always-true conditions: such a
     * condition risks failing for its own reason instead of for emptiness.
     */
    @Test
    @DisplayName("Edge sub-package guard is non-vacuous: slices, every declared sub-package and the root class resolve")
    void guardIsNonVacuous() {
        Set<String> slices = sliceNamesOf(EDGE_ROOT, PRODUCTION_CLASSES);
        Set<String> declared = declaredSubPackagesOf(EDGE_ROOT, PRODUCTION_CLASSES);
        List<String> unresolved = declared.stream()
                .filter(subPackage -> countNonPackageInfoClassesIn(EDGE_ROOT + "." + subPackage) == 0)
                .toList();

        assertAll("the edge sub-package guard is non-vacuous",
                () -> assertTrue(slices.size() >= 2,
                        "Only " + slices + " resolved as slices beneath " + EDGE_ROOT
                                + " — a cycle needs two slices, so the cycle rule is checking nothing"),
                () -> assertFalse(declared.isEmpty(),
                        "No package-info resolved directly beneath " + EDGE_ROOT
                                + ", so the per-sub-package check below is checking nothing"),
                () -> assertEquals(List.of(), unresolved,
                        "These declared edge sub-packages resolved to NO production class besides their "
                                + "package-info, so both rules are silently not covering them. Remove the "
                                + "sub-package, package-info included, if it was retired."),
                () -> assertTrue(PRODUCTION_CLASSES.contain(EDGE_ROOT_CLASS),
                        EDGE_ROOT_CLASS + " did not resolve, so the edge root package the second rule "
                                + "protects may hold nothing"));
    }

    @Test
    @DisplayName("doc/architecture/README.adoc edge sub-package table and count match declared sub-packages")
    void edgeSubPackagesTableMatchesDeclared() throws Exception {
        Path readme = RepositoryRoot.holding("doc/architecture/README.adoc").resolve("doc/architecture/README.adoc");
        String content = Files.readString(readme);

        Pattern sectionPattern = Pattern.compile("=== Edge Package Structure.*?(?=\\n== |\\z)", Pattern.DOTALL);
        Matcher sectionMatcher = sectionPattern.matcher(content);
        assertTrue(sectionMatcher.find(), "=== Edge Package Structure section must be present in doc/architecture/README.adoc");
        String section = sectionMatcher.group(0);

        Pattern rowPattern = Pattern.compile("^\\| `([a-z]+)`", Pattern.MULTILINE);
        Matcher rowMatcher = rowPattern.matcher(section);
        List<String> rawDocumented = new ArrayList<>();
        while (rowMatcher.find()) {
            rawDocumented.add(rowMatcher.group(1));
        }

        Set<String> declared = declaredSubPackagesOf(EDGE_ROOT, PRODUCTION_CLASSES);
        Set<String> documented = new TreeSet<>(rawDocumented);

        assertAll("doc/architecture/README.adoc edge sub-package contract",
                () -> assertEquals(rawDocumented.size(), documented.size(),
                        "The edge sub-package table contains duplicate rows: " + rawDocumented),
                () -> assertEquals(declared, documented,
                        "The edge sub-package table in doc/architecture/README.adoc must match the declared sub-packages exactly"),
                () -> assertTrue(section.contains(declared.size() + " acyclic sub-packages")
                        || section.contains("nine acyclic sub-packages"),
                        "doc/architecture/README.adoc must state the exact count of acyclic sub-packages (" + declared.size() + ")"));
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        /**
         * Negative control for the cycle rule: the specimen's {@code alpha} and {@code beta}
         * sub-packages reference each other.
         * <p>
         * <strong>{@code allowEmptyShould(true)} is deliberate and must not be removed.</strong> If the
         * specimen ever stops resolving, that setting makes {@code check} pass, which makes
         * {@code assertThrows} fail loudly. Removing it would let an unresolved specimen throw on
         * emptiness, satisfying {@code assertThrows} with the wrong exception.
         */
        @Test
        @DisplayName("The cycle rule rejects the alpha <-> beta specimen cycle (negative control)")
        void cycleRuleFailsOnSpecimenCycle() {
            ArchRule rule = subPackagesMustBeFreeOfCycles(SPECIMEN_ROOT).allowEmptyShould(true);

            assertThrows(AssertionError.class, () -> rule.check(SPECIMEN_CLASSES),
                    "The cycle rule must fail on CycleAlphaSpecimen <-> CycleBetaSpecimen — if it does not, "
                            + "either a reference was removed from a specimen or the specimen root no longer "
                            + "resolves");
        }

        /**
         * Negative control for the root-package rule: the specimen's {@code beta} sub-package
         * references {@code EdgeRootSpecimen}, a class in the specimen root package.
         * {@code allowEmptyShould(true)} for the reason given on {@link #cycleRuleFailsOnSpecimenCycle()}.
         */
        @Test
        @DisplayName("The root-package rule rejects beta's reference to the specimen root package (negative control)")
        void rootPackageRuleFailsOnSpecimenBackReference() {
            ArchRule rule = subPackagesMustNotDependOnRootPackage(SPECIMEN_ROOT).allowEmptyShould(true);

            assertThrows(AssertionError.class, () -> rule.check(SPECIMEN_CLASSES),
                    "The root-package rule must fail on CycleBetaSpecimen's reference to EdgeRootSpecimen — "
                            + "if it does not, either the reference was removed or the specimen root no longer "
                            + "resolves");
        }

        /**
         * Matched positive control: {@code GatewayEdgeRoute} depends on sub-package classes and is not
         * reported, because it is not in the second rule's selection.
         * <p>
         * The order is load-bearing. "Outside the selection" is trivially true of a class that has left
         * the root or stopped wiring the sub-packages, so both near-miss properties are asserted first.
         */
        @Test
        @DisplayName("GatewayEdgeRoute depends on the sub-packages and is outside the root-package rule's selection (positive control)")
        void edgeRootClassIsOutsideTheRootPackageRuleSelection() {
            assertTrue(PRODUCTION_CLASSES.contain(EDGE_ROOT_CLASS),
                    EDGE_ROOT_CLASS + " did not resolve, so this control cannot establish anything");
            JavaClass edgeRoute = PRODUCTION_CLASSES.get(EDGE_ROOT_CLASS);
            DescribedPredicate<JavaClass> inSubPackage = inSubPackageOf(EDGE_ROOT);

            assertAll(EDGE_ROOT_CLASS + " is the wiring root, not a sub-package class",
                    () -> assertEquals(EDGE_ROOT, edgeRoute.getPackageName(),
                            EDGE_ROOT_CLASS + " no longer lives in the edge root"),
                    () -> assertTrue(edgeRoute.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(dependency -> inSubPackage.test(dependency.getTargetClass())),
                            EDGE_ROOT_CLASS + " depends on no sub-package class, so it is no longer the near-miss "
                                    + "this control needs"),
                    () -> assertFalse(inSubPackage.test(edgeRoute),
                            "The root-package rule selects " + EDGE_ROOT_CLASS + " itself, so its own wiring of the "
                                    + "sub-packages would be reported as a violation"));
        }

        /**
         * The specimen carve-out, asserted explicitly. The specimens live in {@code src/test}, so the
         * production import's {@code DO_NOT_INCLUDE_TESTS} already keeps them out;
         * {@link #EXCLUDE_SPECIMENS} exists so that filter is not the single load-bearing guard. It is
         * therefore checked on an import that includes tests, where only it can keep the specimens out.
         */
        @Test
        @DisplayName("The specimen package is carved out of the production import explicitly")
        void specimenPackageIsCarvedOut() {
            JavaClasses withTestsButCarvedOut = new ClassFileImporter()
                    .withImportOption(EXCLUDE_SPECIMENS)
                    .importPackages(SPECIMEN_ROOT);

            assertAll("the specimen package is carved out",
                    () -> assertTrue(SPECIMEN_CLASSES.contain(SPECIMEN_ROOT_CLASS),
                            SPECIMEN_ROOT_CLASS + " did not resolve, so the carve-out has nothing to exclude"),
                    () -> assertEquals(0, withTestsButCarvedOut.size(),
                            "EXCLUDE_SPECIMENS let " + withTestsButCarvedOut.size() + " specimen class(es) "
                                    + "through an import that includes tests, so the carve-out rests on the "
                                    + "test-source filter alone"),
                    () -> assertFalse(PRODUCTION_CLASSES.contain(SPECIMEN_ROOT_CLASS),
                            "The production import resolved " + SPECIMEN_ROOT_CLASS));
        }
    }

    /**
     * The cycle rule over a root: every direct sub-package of {@code root} is one slice, and the
     * slices must be free of cycles.
     *
     * @param root the root package whose sub-packages are the slices
     * @return the rule
     */
    private static ArchRule subPackagesMustBeFreeOfCycles(String root) {
        return slices().matching(root + ".(*)..")
                .should().beFreeOfCycles()
                .because("the sub-packages of " + root + " form an acyclic graph (ADR-0068), so each can be "
                        + "read and changed without the ones that depend on it");
    }

    /**
     * The root-package rule over a root: no class in a sub-package of {@code root} depends on a class
     * whose package is exactly {@code root}. That covers the wiring class, the classes nested in it
     * (they share its package) and any further class added to the root, without naming any of them.
     *
     * @param root the root package whose sub-packages are selected and that none of them may reach
     * @return the rule
     */
    private static ArchRule subPackagesMustNotDependOnRootPackage(String root) {
        return noClasses()
                .that(inSubPackageOf(root))
                .should().dependOnClassesThat(resideExactlyIn(root))
                .because("the root package " + root + " wires its sub-packages; a dependency back on any class "
                        + "in it would close a cycle through the root that the slice rule cannot see (ADR-0068)");
    }

    /**
     * Selects the classes in a sub-package of {@code root} — never the root package itself.
     *
     * @param root the root package
     * @return the predicate
     */
    private static DescribedPredicate<JavaClass> inSubPackageOf(String root) {
        return new DescribedPredicate<>("reside in a sub-package of " + root) {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getPackageName().startsWith(root + ".");
            }
        };
    }

    /**
     * Selects the classes whose package is exactly {@code root} — never a class in a sub-package.
     *
     * @param root the root package
     * @return the predicate
     */
    private static DescribedPredicate<JavaClass> resideExactlyIn(String root) {
        return new DescribedPredicate<>("reside in the root package " + root) {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getPackageName().equals(root);
            }
        };
    }

    private static Set<String> sliceNamesOf(String root, JavaClasses classes) {
        Slices slices = Slices.matching(root + ".(*)..").transform(classes);
        return StreamSupport.stream(slices.spliterator(), false)
                .map(slice -> slice.getNamePart(1))
                .collect(Collectors.toSet());
    }

    /**
     * The direct sub-packages of {@code root} that declare themselves with a {@code package-info}.
     *
     * @param root    the root package
     * @param classes the import to read the declarations from
     * @return the simple names of the declared direct sub-packages, sorted
     */
    private static Set<String> declaredSubPackagesOf(String root, JavaClasses classes) {
        String prefix = root + ".";
        return classes.stream()
                .filter(javaClass -> PACKAGE_INFO.equals(javaClass.getSimpleName()))
                .map(JavaClass::getPackageName)
                .filter(name -> name.startsWith(prefix) && name.indexOf('.', prefix.length()) < 0)
                .map(name -> name.substring(prefix.length()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static long countNonPackageInfoClassesIn(String packageName) {
        return PRODUCTION_CLASSES.stream()
                .filter(javaClass -> !PACKAGE_INFO.equals(javaClass.getSimpleName()))
                .map(JavaClass::getPackageName)
                .filter(name -> name.equals(packageName) || name.startsWith(packageName + "."))
                .count();
    }
}
