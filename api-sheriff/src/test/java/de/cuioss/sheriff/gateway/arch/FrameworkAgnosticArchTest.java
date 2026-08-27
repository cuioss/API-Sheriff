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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0005 framework-agnostic package-boundary gate. Asserts that the agnostic
 * core packages ({@code config.model}, {@code config.validation}, {@code events},
 * {@code forward}, {@code pipeline}) carry no framework imports, so the request
 * pipeline stays portable across the framework edge.
 * <p>
 * The {@code routing} package is deliberately <em>excluded</em> from the agnostic set
 * (operator resolution 2026-07-19): {@code routing.RouteRuntime} holds the shared Vert.x
 * {@code HttpClient} reference and the per-route SmallRye Fault-Tolerance guard by design,
 * so it is framework-coupled and must NOT be asserted framework-agnostic here.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in
 * both {@code test} and {@code verify -Ppre-commit}, wiring the boundary into the quality gate.
 *
 * @since 1.0
 */
class FrameworkAgnosticArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";

    /**
     * The agnostic core packages the ADR-0005 gate protects. {@code routing} is intentionally
     * absent — see the class Javadoc.
     */
    private static final String[] AGNOSTIC_PACKAGES = {
            "de.cuioss.sheriff.gateway.config.model..",
            "de.cuioss.sheriff.gateway.config.validation..",
            "de.cuioss.sheriff.gateway.events..",
            "de.cuioss.sheriff.gateway.forward..",
            "de.cuioss.sheriff.gateway.pipeline.."
    };

    /**
     * Framework packages that an agnostic-core class must never depend on.
     * <p>
     * {@code io.smallrye..} is listed because the class Javadoc names the per-route SmallRye
     * Fault-Tolerance guard as precisely the coupling that keeps {@code routing} out of the agnostic
     * set — leaving it unlisted would let an agnostic-core class acquire that same coupling unchecked.
     * {@code io.netty..} and {@code org.jboss..} are listed for the same reason: they arrive
     * transitively with the Quarkus/Vert.x stack and are framework by any reading of ADR-0005.
     */
    private static final String[] FRAMEWORK_PACKAGES = {
            "io.quarkus..",
            "io.vertx..",
            "io.smallrye..",
            "io.netty..",
            "org.jboss..",
            "jakarta.enterprise..",
            "jakarta.inject..",
            "org.eclipse.microprofile..",
            "io.micrometer.."
    };

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    @Test
    @DisplayName("ADR-0005: agnostic core packages must not depend on framework packages")
    void agnosticPackagesMustNotDependOnFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(AGNOSTIC_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .because("ADR-0005 requires config.model, config.validation, events, forward, and "
                        + "pipeline to remain framework-agnostic (routing is excluded by design)");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard: every entry in {@link #AGNOSTIC_PACKAGES} must actually resolve to at least
     * one production class.
     * <p>
     * Without this, a renamed package or a single misspelled entry silently reduces the rule above to
     * a no-op — it would match zero classes, find zero violations, and stay green while protecting
     * nothing. The negative control below cannot catch that: it exercises its own hardcoded package,
     * so it proves the ArchUnit mechanism works while saying nothing about whether the real package
     * list still resolves. This test is what makes an empty match loud, and it names the offending
     * entry rather than failing generically.
     */
    @Test
    @DisplayName("ADR-0005 gate is non-vacuous: every protected package resolves to at least one class")
    void everyAgnosticPackageResolvesToClasses() {
        for (String agnosticPackage : AGNOSTIC_PACKAGES) {
            long matched = countClassesIn(agnosticPackage);

            assertTrue(matched > 0,
                    () -> "ADR-0005 protected package '" + agnosticPackage + "' resolved to NO "
                            + "production classes — the framework-agnostic rule above is silently "
                            + "protecting nothing. Fix the entry in AGNOSTIC_PACKAGES, or remove it "
                            + "deliberately if the package was retired.");
        }
    }

    /**
     * Counts imported production classes residing in {@code packagePattern}, which uses the ArchUnit
     * {@code ..} suffix to mean "this package and its subpackages".
     * <p>
     * Deliberately a direct count rather than an {@link ArchRule} with an always-true condition: any
     * such condition risks failing for its own reason instead of for emptiness — {@code bePublic()
     * .orShould().bePackagePrivate()} looks universal but rejects a private nested class — which
     * would make this guard red for a reason that has nothing to do with the gap it exists to detect.
     */
    private static long countClassesIn(String packagePattern) {
        String base = packagePattern.endsWith("..")
                ? packagePattern.substring(0, packagePattern.length() - 2)
                : packagePattern;
        return PRODUCTION_CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.equals(base) || name.startsWith(base + "."))
                .count();
    }

    /**
     * Negative control: proves the ArchUnit mechanism actually fails on a real violation, using the
     * deliberately framework-coupled {@code quarkus} package as the standing specimen.
     * <p>
     * <strong>{@code allowEmptyShould(true)} here is deliberate and must not be removed</strong> — the
     * asymmetry with the rule above is load-bearing. If the control package ever empties out, this
     * setting makes {@code check} pass, which makes {@code assertThrows} fail loudly and tells us the
     * control has stopped controlling anything. Removing it would invert that: an empty package would
     * make {@code check} throw on emptiness, {@code assertThrows} would be satisfied by the wrong
     * exception, and the test would go green while proving nothing.
     */
    @Test
    @DisplayName("ADR-0005 gate detects a deliberate framework dependency (negative control)")
    void gateFailsOnFrameworkDependency() {
        ArchRule ruleAgainstFrameworkCoupledPackage = noClasses()
                .that().resideInAPackage("de.cuioss.sheriff.gateway.quarkus..")
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .allowEmptyShould(true);

        assertThrows(AssertionError.class,
                () -> ruleAgainstFrameworkCoupledPackage.check(PRODUCTION_CLASSES),
                "The gate must fail when a covered package depends on a framework package — "
                        + "the framework-coupled quarkus package is the standing negative control");
    }
}
