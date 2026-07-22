/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.api";

    /**
     * The agnostic core packages the ADR-0005 gate protects. {@code routing} is intentionally
     * absent — see the class Javadoc.
     */
    private static final String[] AGNOSTIC_PACKAGES = {
            "de.cuioss.sheriff.api.config.model..",
            "de.cuioss.sheriff.api.config.validation..",
            "de.cuioss.sheriff.api.events..",
            "de.cuioss.sheriff.api.forward..",
            "de.cuioss.sheriff.api.pipeline.."
    };

    /** Framework packages that an agnostic-core class must never depend on. */
    private static final String[] FRAMEWORK_PACKAGES = {
            "io.quarkus..",
            "io.vertx..",
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
                        + "pipeline to remain framework-agnostic (routing is excluded by design)")
                .allowEmptyShould(true);

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("ADR-0005 gate detects a deliberate framework dependency (negative control)")
    void gateFailsOnFrameworkDependency() {
        ArchRule ruleAgainstFrameworkCoupledPackage = noClasses()
                .that().resideInAPackage("de.cuioss.sheriff.api.quarkus..")
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .allowEmptyShould(true);

        assertThrows(AssertionError.class,
                () -> ruleAgainstFrameworkCoupledPackage.check(PRODUCTION_CLASSES),
                "The gate must fail when a covered package depends on a framework package — "
                        + "the framework-coupled quarkus package is the standing negative control");
    }
}
