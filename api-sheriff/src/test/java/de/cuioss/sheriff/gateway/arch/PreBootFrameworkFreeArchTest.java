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
 * Pre-boot framework-free gate (ADR-0062). Asserts that the five pre-boot configuration packages
 * ({@code config.boot}, {@code config.load}, {@code config.model}, {@code config.validation},
 * {@code config.topology}) depend on no framework package.
 * <p>
 * The reason is not portability. The offline {@code --validate-config} check runs these packages
 * before the framework starts (ADR-0061), and a framework type reached from them would put framework
 * code on a path that must start nothing.
 * <p>
 * <strong>{@code events}, {@code forward} and {@code pipeline} are deliberately not covered.</strong>
 * ADR-0062 retires the framework-agnostic core rule those three packages were held to; they are not
 * on the pre-boot path and may use platform types. Adding them back here would re-enact a rule that
 * no longer exists.
 * <p>
 * The gate checks the dependencies of the five packages only. A class outside them that the pre-boot
 * path calls is not covered here; the integration test that runs the flag on the built image is what
 * observes that case.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, wiring the boundary into the quality gate.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class PreBootFrameworkFreeArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";

    /**
     * The pre-boot configuration packages the gate protects: everything the offline configuration
     * check runs before the framework starts. See the class Javadoc for the packages that are
     * intentionally absent.
     */
    private static final String[] PRE_BOOT_PACKAGES = {
            "de.cuioss.sheriff.gateway.config.boot..",
            "de.cuioss.sheriff.gateway.config.load..",
            "de.cuioss.sheriff.gateway.config.model..",
            "de.cuioss.sheriff.gateway.config.validation..",
            "de.cuioss.sheriff.gateway.config.topology.."
    };

    /**
     * Framework packages that a pre-boot class must never depend on.
     * <p>
     * {@code io.smallrye..} is listed because SmallRye Config and SmallRye Fault-Tolerance are exactly
     * the platform mechanisms that do not exist before the framework starts. {@code io.netty..} and
     * {@code org.jboss..} are listed for the same reason: they arrive transitively with the
     * Quarkus/Vert.x stack and none of them may be reached on a path that must start nothing.
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
    @DisplayName("Pre-boot configuration packages must not depend on framework packages")
    void preBootPackagesMustNotDependOnFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(PRE_BOOT_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .because("the offline --validate-config check runs config.boot, config.load, config.model, "
                        + "config.validation and config.topology before the framework starts (ADR-0061), so "
                        + "a framework type reached from them puts framework code on a path that must start "
                        + "nothing (ADR-0062)");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard: every entry in {@link #PRE_BOOT_PACKAGES} must actually resolve to at least
     * one production class.
     * <p>
     * Without this, a renamed package or a single misspelled entry silently narrows the rule above —
     * that entry would match zero classes, contribute zero violations, and the rule would stay green
     * while one pre-boot package went unprotected. The negative control below cannot catch that: it
     * exercises its own hardcoded package, so it proves the ArchUnit mechanism works while saying
     * nothing about whether the real package list still resolves. This test is what makes an empty
     * match loud, and it names the offending entry rather than failing generically.
     */
    @Test
    @DisplayName("Pre-boot gate is non-vacuous: every protected package resolves to at least one class")
    void everyPreBootPackageResolvesToClasses() {
        for (String preBootPackage : PRE_BOOT_PACKAGES) {
            long matched = countClassesIn(preBootPackage);

            assertTrue(matched > 0,
                    () -> "Pre-boot package '" + preBootPackage + "' resolved to NO production classes "
                            + "— the framework-free rule above is silently not protecting it. Fix the "
                            + "entry in PRE_BOOT_PACKAGES, or remove it deliberately if the package was "
                            + "retired.");
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
    @DisplayName("Pre-boot gate detects a deliberate framework dependency (negative control)")
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
