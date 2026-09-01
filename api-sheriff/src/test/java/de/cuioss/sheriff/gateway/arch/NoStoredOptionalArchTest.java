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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;


import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Standing guard against reintroducing a <em>stored</em> {@link Optional} in production code.
 * <p>
 * {@code Optional} stays welcome as a <em>return</em> type — that is the use it was designed for and
 * the codebase relies on it there. What this gate forbids is the two shapes the nullable-type-model
 * conversion retired: an {@code Optional}-typed <strong>field</strong> (including a record
 * component) and an {@code Optional}-typed <strong>declared parameter</strong>. Both are replaced by
 * a JSpecify {@code @Nullable} type.
 * <p>
 * <strong>Carve-out 1 — the Lombok {@code @Getter} exemption.</strong> Lombok's {@code @Getter} has
 * {@code SOURCE} retention, so it is invisible to ArchUnit, which reads bytecode. The bytecode-visible
 * proxy is the generated accessor itself: a field is exempt when its owner declares a matching public
 * no-arg accessor. This proxy deliberately <em>over</em>-exempts — it cannot tell a Lombok-generated
 * getter from a hand-written one, so a hand-written public getter exempts its field too. The known
 * consequence is that {@code routing.RouteRuntime} sits outside this guard's reach. The exemption is
 * accepted rather than tightened because the alternative (no exemption) would make the gate
 * unusable against every Lombok-annotated bean in the tree.
 * <p>
 * <strong>Records are never exempt.</strong> A record's components compile to private final fields
 * <em>and</em> matching public no-arg accessors, so the proxy above would exempt every record
 * component — exactly the shape the conversion targeted. Records are therefore checked
 * unconditionally, and the exemption is only ever consulted for a non-record owner.
 * <p>
 * <strong>Carve-out 2 — the {@code @ConfigProperty} exemption.</strong> MicroProfile Config injects
 * an optional configuration value as {@code Optional<T>}; that shape is imposed by the framework at
 * the injection point and is not ours to change. {@code @ConfigProperty} has {@code RUNTIME}
 * retention, so unlike Lombok it is readable directly from the parameter and needs no proxy.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, wiring the guard into the quality gate — the same
 * arrangement {@link FrameworkAgnosticArchTest} uses.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class NoStoredOptionalArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";
    private static final String SPECIMEN_PACKAGE = "de.cuioss.sheriff.gateway.arch.specimen";
    private static final String CONFIG_PROPERTY = "org.eclipse.microprofile.config.inject.ConfigProperty";

    /** The production packages this guard protects. */
    private static final String[] PROTECTED_PACKAGES = {
            "de.cuioss.sheriff.gateway.asset..",
            "de.cuioss.sheriff.gateway.auth..",
            "de.cuioss.sheriff.gateway.bff..",
            "de.cuioss.sheriff.gateway.config..",
            "de.cuioss.sheriff.gateway.edge..",
            "de.cuioss.sheriff.gateway.events..",
            "de.cuioss.sheriff.gateway.forward..",
            "de.cuioss.sheriff.gateway.pipeline..",
            "de.cuioss.sheriff.gateway.quarkus..",
            "de.cuioss.sheriff.gateway.routing..",
            "de.cuioss.sheriff.gateway.tls.."
    };

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    /**
     * The specimen package is imported <em>separately and with tests included</em>: the controls live
     * in {@code src/test}, so the production import above deliberately cannot see them.
     */
    private static final JavaClasses SPECIMEN_CLASSES = new ClassFileImporter()
            .importPackages(SPECIMEN_PACKAGE);

    /**
     * A field is exempt only when its owner is not a record AND the owner declares a matching public
     * no-arg accessor — the bytecode-visible proxy for Lombok {@code @Getter}. See the class Javadoc
     * for why the proxy over-exempts and why records are excluded from it.
     */
    private static final DescribedPredicate<JavaField> NOT_EXEMPT_FROM_GETTER_PROXY =
            new DescribedPredicate<>("not exempted by a matching public accessor (Lombok @Getter proxy)") {
                @Override
                public boolean test(JavaField field) {
                    return !isExempt(field);
                }
            };

    private static boolean isExempt(JavaField field) {
        JavaClass owner = field.getOwner();
        if (owner.isRecord()) {
            // Records are checked unconditionally — their generated accessors would otherwise exempt
            // every component, which is precisely the shape this guard exists to catch.
            return false;
        }
        return owner.getMethods().stream().anyMatch(method -> isMatchingPublicAccessor(method, field.getName()));
    }

    private static boolean isMatchingPublicAccessor(JavaMethod method, String fieldName) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC) || !method.getRawParameterTypes().isEmpty()) {
            return false;
        }
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String name = method.getName();
        return name.equals(fieldName) || name.equals("get" + capitalized) || name.equals("is" + capitalized);
    }

    /**
     * Phrased positively — "must NOT declare …" — and used with {@code codeUnits().should(…)} rather
     * than {@code noCodeUnits().should(…)}. That is deliberate: under the {@code no…} form ArchUnit
     * inverts event polarity, so a condition that emits {@code violated} events reports nothing at
     * all and the rule passes vacuously. Emitting {@code violated} from a positive rule keeps the
     * polarity unambiguous.
     */
    private static ArchCondition<JavaCodeUnit> notDeclareAnOptionalParameter() {
        return new ArchCondition<>("not declare a java.util.Optional parameter outside @ConfigProperty injection") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                for (JavaParameter parameter : codeUnit.getParameters()) {
                    if (isStoredOptionalParameter(parameter)) {
                        events.add(SimpleConditionEvent.violated(codeUnit,
                                codeUnit.getFullName() + " declares an Optional parameter at index "
                                        + parameter.getIndex() + " — use a @Nullable type instead"));
                    }
                }
            }
        };
    }

    private static boolean isStoredOptionalParameter(JavaParameter parameter) {
        return parameter.getRawType().isEquivalentTo(Optional.class)
                && !parameter.isAnnotatedWith(CONFIG_PROPERTY);
    }

    @Test
    @DisplayName("Production code must not store an Optional in a field or record component")
    void productionMustNotStoreOptionalInFields() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(PROTECTED_PACKAGES)
                .and(NOT_EXEMPT_FROM_GETTER_PROXY)
                .should().haveRawType(Optional.class)
                .because("a stored Optional is retired in favour of a JSpecify @Nullable type; a field is "
                        + "exempt only when a matching public no-arg accessor stands in for Lombok @Getter "
                        + "(which over-exempts a hand-written getter too, putting routing.RouteRuntime "
                        + "outside this guard's reach), and record components are never exempt");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Production code must not declare an Optional parameter outside @ConfigProperty injection")
    void productionMustNotDeclareOptionalParameters() {
        ArchRule rule = codeUnits()
                .that().areDeclaredInClassesThat().resideInAnyPackage(PROTECTED_PACKAGES)
                .should(notDeclareAnOptionalParameter())
                .because("a declared Optional parameter is retired in favour of a JSpecify @Nullable type, "
                        + "except where MicroProfile Config imposes Optional<T> at a @ConfigProperty "
                        + "injection point, which is not ours to change");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard: every entry in {@link #PROTECTED_PACKAGES} must actually resolve to at least
     * one production class.
     * <p>
     * Without this, a renamed or misspelled package silently reduces both rules above to a no-op —
     * they would match zero classes, find zero violations, and stay green while protecting nothing.
     * The controls below cannot catch that: they exercise their own hardcoded specimen package, so
     * they prove the mechanism works while saying nothing about whether the real package list still
     * resolves.
     * <p>
     * Deliberately a direct count rather than an {@link ArchRule} with an always-true condition: any
     * such condition risks failing for its own reason instead of for emptiness, which would make this
     * guard red for a reason unrelated to the gap it exists to detect.
     */
    @Test
    @DisplayName("Stored-Optional guard is non-vacuous: every protected package resolves to at least one class")
    void everyProtectedPackageResolvesToClasses() {
        for (String protectedPackage : PROTECTED_PACKAGES) {
            long matched = countClassesIn(protectedPackage);

            assertTrue(matched > 0,
                    () -> "Protected package '" + protectedPackage + "' resolved to NO production classes "
                            + "— the stored-Optional rules above are silently protecting nothing. Fix the "
                            + "entry in PROTECTED_PACKAGES, or remove it deliberately if the package was "
                            + "retired.");
        }
    }

    private static long countClassesIn(String packagePattern) {
        String base = packagePattern.endsWith("..")
                ? packagePattern.substring(0, packagePattern.length() - 2)
                : packagePattern;
        return PRODUCTION_CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.equals(base) || name.startsWith(base + "."))
                .count();
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        private ArchRule fieldRuleAgainstSpecimens() {
            return noFields()
                    .that().areDeclaredInClassesThat().resideInAPackage(SPECIMEN_PACKAGE)
                    .and(NOT_EXEMPT_FROM_GETTER_PROXY)
                    .should().haveRawType(Optional.class)
                    .allowEmptyShould(true);
        }

        /**
         * Negative control: proves the field rule actually fails on a real stored {@code Optional}.
         * <p>
         * <strong>{@code allowEmptyShould(true)} is deliberate and must not be removed.</strong> If the
         * specimen package ever empties out, that setting makes {@code check} pass, which makes
         * {@code assertThrows} fail loudly and tells us the control has stopped controlling anything.
         * Removing it would invert that: an empty package would make {@code check} throw on emptiness,
         * {@code assertThrows} would be satisfied by the wrong exception, and the test would go green
         * while proving nothing.
         */
        @Test
        @DisplayName("Guard detects a deliberately stored Optional (negative control)")
        void guardFailsOnStoredOptionalSpecimen() {
            ArchRule fieldRule = fieldRuleAgainstSpecimens();

            assertThrows(AssertionError.class,
                    () -> fieldRule.check(SPECIMEN_CLASSES),
                    "The guard must fail on OptionalFieldSpecimen's stored Optional — if it does not, the "
                            + "field either gained a matching public accessor (disarming the control via the "
                            + "Lombok @Getter proxy) or the specimen package no longer resolves");
        }

        /**
         * Matched positive control: the same rule, restricted to the {@code @Nullable} specimen, must
         * pass. Without this, a rule that always failed would satisfy the negative control alone; this
         * is what proves the guard discriminates.
         */
        @Test
        @DisplayName("Guard accepts the matched @Nullable specimen (positive control)")
        void guardPassesOnNullableSpecimen() {
            ArchRule ruleAgainstNullableSpecimenOnly = noFields()
                    .that().areDeclaredInClassesThat().haveFullyQualifiedName(
                    SPECIMEN_PACKAGE + ".NullableFieldSpecimen")
                    .and(NOT_EXEMPT_FROM_GETTER_PROXY)
                    .should().haveRawType(Optional.class)
                    .allowEmptyShould(true);

            assertDoesNotThrow(() -> ruleAgainstNullableSpecimenOnly.check(SPECIMEN_CLASSES),
                    "The guard must accept NullableFieldSpecimen — a rule that failed here would be "
                            + "always-failing rather than discriminating");
        }
    }
}
