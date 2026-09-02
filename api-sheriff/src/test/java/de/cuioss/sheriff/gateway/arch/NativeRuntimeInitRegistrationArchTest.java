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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;


import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
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
 * Standing guard that every gateway class holding a {@code static final SecureRandom} is registered
 * for GraalVM <em>runtime</em> initialization.
 * <p>
 * GraalVM initializes static state at <strong>build</strong> time by default. A
 * {@code static final SecureRandom} therefore gets its class initializer run inside the image build,
 * which either aborts the build ("instance of Random/SplittableRandom in the image heap") or — worse,
 * where the instance is reachable through a shape the analysis tolerates — bakes a
 * <em>seeded</em> generator into the image heap, so every process started from that image draws the
 * same "random" bytes. Both outcomes are fixed by the same declaration: an
 * {@code --initialize-at-run-time=} entry in {@code quarkus.native.additional-build-args}.
 * <p>
 * That declaration lives in {@code application.properties} and the field lives in Java, so nothing
 * links the two. Adding a {@code static final SecureRandom} without the matching entry is a silent
 * omission that only surfaces during a native build (minutes away) or, in the seeded-heap case, not
 * at all. This gate closes that gap in the JVM test cycle.
 * <p>
 * <strong>Four legs, all load-bearing.</strong>
 * <ol>
 *   <li><em>Positive rule</em> — every selected field's owner must be covered by the registration
 *       list. Phrased as {@code fields().should(condition)} emitting {@code violated} events, never
 *       {@code noFields().should(…)}: under the {@code no…} form ArchUnit inverts event polarity, so
 *       a condition emitting {@code violated} would report nothing and the rule would pass vacuously.</li>
 *   <li><em>Non-vacuity guard</em> — a direct count proving the selection resolves to at least one
 *       class today, that the {@code quarkus.native.additional-build-args} key is present, and that
 *       the parsed registration list is non-empty. Without it, a renamed package or a retired
 *       configuration key would reduce the rule to a green no-op.</li>
 *   <li><em>Negative control</em> — the same condition run against
 *       {@link de.cuioss.sheriff.gateway.arch.specimen.StaticSecureRandomSpecimen}, proving the
 *       condition actually fails on an unregistered class. The specimen resides <em>under</em> the
 *       gated package prefix, so the positive rule carves its package out explicitly rather than
 *       leaning on {@code DO_NOT_INCLUDE_TESTS} as the single guard — see {@link #SPECIMEN_PACKAGE}.</li>
 *   <li><em>Matched positive controls</em> — {@code SealedSessionCookieCodec} (a per-instance
 *       {@code private final SecureRandom}, constructed at runtime) and {@code CookieKeyMaterial} (a
 *       local {@code new SecureRandom()} argument, not a field) must <strong>not</strong> be
 *       selected. These are what prove the selection discriminates rather than matching everything
 *       that mentions {@code SecureRandom}. Both first assert their near-miss is <em>real</em> and
 *       only then assert exclusion: an exclusion assertion on its own would pass just as happily if
 *       the class were renamed, moved or deleted, reporting green while proving nothing.</li>
 * </ol>
 * <p>
 * <strong>Coverage is evaluated class → list, never list → class.</strong> The gate asks "is this
 * class covered by some entry?" and never "does every entry still name a live class". The list
 * legitimately carries entries this gate cannot see — most visibly the package-prefix entry
 * {@code de.cuioss.sheriff.token.client.flow}, which covers a package in another artifact — and the
 * detected set is deliberately <em>not</em> frozen to today's fully-qualified names: freezing it
 * would force an edit to this test for every legitimate new registration, which is exactly the
 * maintenance tax that makes a gate get deleted.
 * <p>
 * <strong>The properties file is read from {@code target/classes}, not the classpath.</strong>
 * {@code getResourceAsStream("/application.properties")} would resolve against the whole test
 * classpath, where a dependency's own {@code application.properties} can win the lookup and the gate
 * would then assert against a foreign file. The compiled output path is unambiguous, and
 * {@link Files#isRegularFile} guards it so a missing file fails loudly instead of parsing to an empty
 * list and passing vacuously.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, wiring the guard into the quality gate — the same
 * arrangement {@link FrameworkAgnosticArchTest} and {@link NoStoredOptionalArchTest} use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class NativeRuntimeInitRegistrationArchTest {

    /**
     * The one package selector this gate is scoped by: the whole gateway tree, not just BFF.
     * <p>
     * Crypto-adjacent static state is not a BFF-only concern. A {@code static final SecureRandom} added
     * under {@code auth}, {@code tls}, {@code edge}, {@code forward}, {@code routing} or any other
     * gateway package carries the identical build-time-initialization hazard, and a bff-scoped
     * selection would simply not see it — the gate would report green while GraalVM baked a seeded
     * generator into the image heap. Every holder happens to live under {@code bff} today, so the wider
     * radius costs nothing now and closes the gap ahead of the first non-BFF holder.
     * <p>
     * It is deliberately <em>one</em> constant: the import in {@link #PRODUCTION_CLASSES} and — through
     * {@link #GATED_CLASS} — both the rule and the guard that proves the rule non-vacuous derive from
     * this single value, so one edit moves them together. See {@link #GATED_CLASS} for why that
     * selector is one shared object rather than two literals.
     */
    private static final String GATED_PACKAGE = "de.cuioss.sheriff.gateway";

    /**
     * The negative control's specimen package, carved out of {@link #GATED_PACKAGE} explicitly.
     * <p>
     * The specimen lives in {@code src/test} <em>under</em> the gated prefix, and
     * {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS} already keeps it out of
     * {@link #PRODUCTION_CLASSES}. The explicit carve-out in {@link #GATED_CLASS} exists so that
     * {@code ImportOption} is not the <em>single</em> load-bearing guard: were the production import
     * ever widened to include tests, the specimen's deliberate violation would otherwise start failing
     * the positive rule rather than only its own negative control.
     */
    private static final String SPECIMEN_PACKAGE = "de.cuioss.sheriff.gateway.arch.specimen";

    private static final String SEALED_SESSION_COOKIE_CODEC =
            "de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec";
    private static final String COOKIE_KEY_MATERIAL =
            "de.cuioss.sheriff.gateway.bff.cookie.CookieKeyMaterial";

    /** The configuration key carrying the GraalVM build arguments, including the registrations. */
    private static final String NATIVE_BUILD_ARGS_KEY = "quarkus.native.additional-build-args";

    /** The single build-argument prefix this gate reads out of the comma-separated list. */
    private static final String RUNTIME_INIT_PREFIX = "--initialize-at-run-time=";

    /**
     * The compiled properties file, resolved relative to the module base directory Surefire runs in.
     * Read from {@code target/classes} rather than the classpath — see the class Javadoc.
     */
    private static final Path COMPILED_APPLICATION_PROPERTIES =
            Path.of("target", "classes", "application.properties");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(GATED_PACKAGE);

    /**
     * The specimen package is imported <em>separately and with tests included</em>: the control lives
     * in {@code src/test}, so the production import above deliberately cannot see it.
     */
    private static final JavaClasses SPECIMEN_CLASSES = new ClassFileImporter()
            .importPackages(SPECIMEN_PACKAGE);

    /**
     * The class-level half of the selection: a gateway class that is not the arch-test specimen.
     * <p>
     * Shared <em>verbatim</em> — the same object, not two expressions of one intent — by the ArchUnit
     * rule in {@link #everyStaticSecureRandomHolderIsRegisteredForRuntimeInit()} and by
     * {@link #selectedOwnerNames()}. That is what makes the non-vacuity guard's count a statement about
     * exactly the set the rule checks, and what stops a future scope edit from moving one without the
     * other.
     */
    private static final DescribedPredicate<JavaClass> GATED_CLASS =
            new DescribedPredicate<>("classes under " + GATED_PACKAGE + " outside " + SPECIMEN_PACKAGE) {
                @Override
                public boolean test(JavaClass javaClass) {
                    String packageName = javaClass.getPackageName();
                    return isWithin(packageName, GATED_PACKAGE) && !isWithin(packageName, SPECIMEN_PACKAGE);
                }
            };

    /**
     * The selection: a field whose raw type is exactly {@link SecureRandom} and whose modifiers carry
     * both {@code STATIC} and {@code FINAL}.
     * <p>
     * Both modifiers are required and neither is redundant. {@code STATIC} is the whole point — only
     * static state is touched by build-time class initialization, which is why a per-instance field
     * such as {@code SealedSessionCookieCodec}'s is correctly ignored. {@code FINAL} narrows the
     * selection to the immutable-holder shape the registration list is actually about; a mutable
     * static generator is a different (and separately objectionable) design that this gate does not
     * claim to cover.
     */
    private static final DescribedPredicate<JavaField> STATIC_FINAL_SECURE_RANDOM =
            new DescribedPredicate<>("static final java.security.SecureRandom fields") {
                @Override
                public boolean test(JavaField field) {
                    return field.getRawType().isEquivalentTo(SecureRandom.class)
                            && field.getModifiers().contains(JavaModifier.STATIC)
                            && field.getModifiers().contains(JavaModifier.FINAL);
                }
            };

    @Test
    @DisplayName("Every gateway class holding a static final SecureRandom is registered for runtime initialization")
    void everyStaticSecureRandomHolderIsRegisteredForRuntimeInit() {
        ArchRule rule = fields()
                .that().areDeclaredInClassesThat(GATED_CLASS)
                .and(STATIC_FINAL_SECURE_RANDOM)
                .should(beDeclaredInAClassRegisteredForRuntimeInit(runtimeInitRegistrations()))
                .because("GraalVM initializes static state at build time, so an unregistered "
                        + "static final SecureRandom either fails the native build or bakes a seeded "
                        + "generator into the image heap — add an " + RUNTIME_INIT_PREFIX
                        + "<fqcn> entry to " + NATIVE_BUILD_ARGS_KEY + " in application.properties");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard, on all three axes the rule above silently depends on: the field selection, the
     * configuration key, and the parsed registration list.
     * <p>
     * Any one of them resolving to nothing would leave the rule green while checking nothing — a
     * renamed gateway package matches zero fields, a retired configuration key parses to an empty list,
     * and a reworked build-argument syntax yields zero registrations. The negative control below
     * cannot catch that: it exercises its own hardcoded specimen package, so it proves the condition
     * works while saying nothing about whether the real inputs still resolve.
     * <p>
     * Deliberately a direct count rather than an {@link ArchRule} with an always-true condition: any
     * such condition risks failing for its own reason instead of for emptiness, which would make this
     * guard red for a reason unrelated to the gap it exists to detect.
     */
    @Test
    @DisplayName("Runtime-init gate is non-vacuous: field selection, config key, and parsed list all resolve")
    void gateIsNonVacuous() {
        Properties compiled = loadCompiledApplicationProperties();
        String declaredValue = compiled.getProperty(NATIVE_BUILD_ARGS_KEY);
        Set<String> selectedOwners = selectedOwnerNames();
        Set<String> registrations = declaredValue == null
                ? Set.of()
                : parseRuntimeInitRegistrations(declaredValue);

        assertAll("runtime-init gate non-vacuity",
                () -> assertNotNull(declaredValue,
                        "Key '" + NATIVE_BUILD_ARGS_KEY + "' is absent from "
                                + COMPILED_APPLICATION_PROPERTIES + " — the rule above is parsing "
                                + "nothing and silently protecting nothing. Restore the key, or retire "
                                + "this gate deliberately if native builds no longer read it."),
                () -> assertFalse(selectedOwners.isEmpty(),
                        "No static final SecureRandom field was found among " + GATED_CLASS.getDescription()
                                + " — the rule above matched zero fields and passed vacuously. Fix "
                                + "GATED_PACKAGE if the package was renamed, or retire this gate "
                                + "deliberately if the last such field was removed."),
                () -> assertFalse(registrations.isEmpty(),
                        "Key '" + NATIVE_BUILD_ARGS_KEY + "' carries no '" + RUNTIME_INIT_PREFIX
                                + "' entry — every selected class would report as unregistered, or the "
                                + "build-argument syntax changed under this parser."));
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        /**
         * Negative control: proves the condition actually fails on a class that holds a
         * {@code static final SecureRandom} without a matching registration.
         * <p>
         * <strong>{@code allowEmptyShould(true)} is deliberate and must not be removed.</strong> If the
         * specimen package ever empties out, that setting makes {@code check} pass, which makes
         * {@code assertThrows} fail loudly and tells us the control has stopped controlling anything.
         * Removing it would invert that: an empty package would make {@code check} throw on emptiness,
         * {@code assertThrows} would be satisfied by the wrong exception, and the test would go green
         * while proving nothing.
         */
        @Test
        @DisplayName("Gate detects a deliberately unregistered static final SecureRandom (negative control)")
        void gateFailsOnUnregisteredSpecimen() {
            ArchRule ruleAgainstSpecimens = fields()
                    .that().areDeclaredInClassesThat().resideInAPackage(SPECIMEN_PACKAGE)
                    .and(STATIC_FINAL_SECURE_RANDOM)
                    .should(beDeclaredInAClassRegisteredForRuntimeInit(runtimeInitRegistrations()))
                    .allowEmptyShould(true);

            assertThrows(AssertionError.class,
                    () -> ruleAgainstSpecimens.check(SPECIMEN_CLASSES),
                    "The gate must fail on StaticSecureRandomSpecimen's unregistered static final "
                            + "SecureRandom — if it does not, the specimen lost the field, lost a "
                            + "modifier, or was somehow covered by a registration entry");
        }

        /**
         * Matched positive control: {@code SealedSessionCookieCodec} holds a {@link SecureRandom}
         * field, but a <em>per-instance</em> one, constructed when the codec is constructed at runtime.
         * Nothing about it reaches the image heap, so it must not be selected.
         * <p>
         * The control asserts the near-miss is real — the class does declare a {@code SecureRandom}
         * field — before asserting it is excluded. Without that first half the control would pass just
         * as happily if the field were deleted, proving nothing about the selection.
         */
        @Test
        @DisplayName("Selection excludes a per-instance SecureRandom field (positive control)")
        void selectionExcludesPerInstanceSecureRandomField() {
            assertAll("SealedSessionCookieCodec is a real, excluded near-miss",
                    () -> assertTrue(declaresSecureRandomField(SEALED_SESSION_COOKIE_CODEC),
                            SEALED_SESSION_COOKIE_CODEC + " no longer declares a SecureRandom field — "
                                    + "this control has stopped controlling anything; point it at another "
                                    + "per-instance holder or retire it deliberately"),
                    () -> assertFalse(selectedOwnerNames().contains(SEALED_SESSION_COOKIE_CODEC),
                            SEALED_SESSION_COOKIE_CODEC + " was selected, but its SecureRandom is a "
                                    + "per-instance field constructed at runtime — the selection has "
                                    + "stopped requiring the STATIC modifier and now over-matches"));
        }

        /**
         * Matched positive control: {@code CookieKeyMaterial} constructs a {@link SecureRandom} as a
         * local argument to {@code KeyGenerator.init} and stores none, so it must not be selected.
         * <p>
         * This is the second discrimination axis — the first control proves the selection reads
         * modifiers, this one proves it reads <em>fields</em> rather than every mention of the type.
         * <p>
         * Like its sibling above, it asserts the near-miss is <em>real</em> before asserting exclusion,
         * and for the same reason: {@link #selectedOwnerNames()} can never contain a class that no
         * longer exists, so an exclusion-only control would report green just as happily if
         * {@code CookieKeyMaterial} were renamed, moved out of the imported tree or deleted. The
         * near-miss has two halves and both are asserted: the class must still <em>reference</em>
         * {@link SecureRandom} (otherwise it is no longer near anything) and must still declare no
         * {@code SecureRandom} <em>field</em> (otherwise it is no longer a miss).
         */
        @Test
        @DisplayName("Selection excludes a local SecureRandom construction (positive control)")
        void selectionExcludesLocalSecureRandomConstruction() {
            assertAll("CookieKeyMaterial is a real, excluded near-miss",
                    () -> assertTrue(referencesSecureRandom(COOKIE_KEY_MATERIAL),
                            COOKIE_KEY_MATERIAL + " is not an imported production class referencing "
                                    + "SecureRandom — it was renamed, moved out of the imported tree, "
                                    + "deleted, or stopped using SecureRandom altogether. This control "
                                    + "has stopped controlling anything; point it at another local-"
                                    + "construction site or retire it deliberately"),
                    () -> assertFalse(declaresSecureRandomField(COOKIE_KEY_MATERIAL),
                            COOKIE_KEY_MATERIAL + " now declares a SecureRandom field, so it is no "
                                    + "longer the local-construction near-miss this control needs. "
                                    + "Point the control at another local-construction site — and check "
                                    + "whether the new field needs a runtime-init registration"),
                    () -> assertFalse(selectedOwnerNames().contains(COOKIE_KEY_MATERIAL),
                            COOKIE_KEY_MATERIAL + " was selected, but it constructs SecureRandom as a "
                                    + "local argument and stores none — the selection has stopped "
                                    + "reading declared fields and now matches any reference to the type"));
        }
    }

    /**
     * The condition: the field's owning class must be covered by the registration list.
     * <p>
     * Phrased to emit {@link SimpleConditionEvent#violated} from a positive
     * {@code fields().should(…)} rule — see the class Javadoc for why the {@code noFields()} form
     * would pass vacuously here.
     *
     * @param registrations the class names and package prefixes parsed out of the build arguments
     * @return the condition
     */
    private static ArchCondition<JavaField> beDeclaredInAClassRegisteredForRuntimeInit(
            Set<String> registrations) {
        return new ArchCondition<>("be declared in a class registered for GraalVM runtime initialization") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                String owner = field.getOwner().getFullName();
                if (!isRegistered(owner, registrations)) {
                    events.add(SimpleConditionEvent.violated(field,
                            owner + " declares the static final SecureRandom field '" + field.getName()
                                    + "' but no " + RUNTIME_INIT_PREFIX + " entry in "
                                    + NATIVE_BUILD_ARGS_KEY + " covers it — GraalVM would initialize the "
                                    + "class at build time and bake a seeded generator into the image heap"));
                }
            }
        };
    }

    /**
     * Answers coverage class → list: a class is registered when an entry names it exactly, or names a
     * package that contains it. GraalVM reads a bare package name as a prefix over that package and
     * its classes, which is how the one package-prefix entry in the list covers a whole flow package.
     */
    private static boolean isRegistered(String className, Set<String> registrations) {
        return registrations.stream().anyMatch(entry -> isWithin(className, entry));
    }

    /**
     * Reads and parses the registration list, failing the calling test loudly when the key is absent
     * rather than handing back an empty set that would make every check pass vacuously.
     */
    private static Set<String> runtimeInitRegistrations() {
        String declaredValue = loadCompiledApplicationProperties().getProperty(NATIVE_BUILD_ARGS_KEY);
        assertNotNull(declaredValue,
                "Key '" + NATIVE_BUILD_ARGS_KEY + "' is absent from " + COMPILED_APPLICATION_PROPERTIES);
        return parseRuntimeInitRegistrations(declaredValue);
    }

    /**
     * Splits the comma-separated build-argument list and keeps the {@code --initialize-at-run-time=}
     * suffixes. Every other argument (URL protocols, optimisation level, …) is ignored: this gate
     * reads one flag out of a shared list and makes no claim about the rest.
     */
    private static Set<String> parseRuntimeInitRegistrations(String declaredValue) {
        return Arrays.stream(declaredValue.split(","))
                .map(String::trim)
                .filter(argument -> argument.startsWith(RUNTIME_INIT_PREFIX))
                .map(argument -> argument.substring(RUNTIME_INIT_PREFIX.length()).trim())
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Properties loadCompiledApplicationProperties() {
        assertTrue(Files.isRegularFile(COMPILED_APPLICATION_PROPERTIES),
                "Expected the compiled properties at " + COMPILED_APPLICATION_PROPERTIES.toAbsolutePath()
                        + " — this gate reads the compiled output rather than the classpath so a "
                        + "dependency's own application.properties cannot win the lookup. Run the "
                        + "module's compile phase before this test.");

        Properties compiled = new Properties();
        assertDoesNotThrow(() -> {
            try (InputStream declared = Files.newInputStream(COMPILED_APPLICATION_PROPERTIES)) {
                compiled.load(declared);
            }
        }, "Failed to read " + COMPILED_APPLICATION_PROPERTIES);
        return compiled;
    }

    /**
     * The fully-qualified names of the classes the selection currently matches, sorted so a failure
     * message reads the same on every run.
     */
    private static Set<String> selectedOwnerNames() {
        return PRODUCTION_CLASSES.stream()
                .filter(GATED_CLASS)
                .flatMap(javaClass -> javaClass.getFields().stream())
                .filter(STATIC_FINAL_SECURE_RANDOM::test)
                .map(field -> field.getOwner().getFullName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean declaresSecureRandomField(String className) {
        return PRODUCTION_CLASSES.stream()
                .filter(javaClass -> javaClass.getFullName().equals(className))
                .flatMap(javaClass -> javaClass.getFields().stream())
                .anyMatch(field -> field.getRawType().isEquivalentTo(SecureRandom.class));
    }

    /**
     * Whether the named class is present in {@link #PRODUCTION_CLASSES} <em>and</em> depends on
     * {@link SecureRandom} in any way — a constructor call, a method call, a parameter or a field type.
     * <p>
     * This is the "is it still a near-miss?" half of the local-construction control. It is deliberately
     * broader than field declaration: the whole point of that control is a class that touches
     * {@code SecureRandom} without holding one, so the anchor has to see the touch. It is also
     * deliberately narrower than mere existence — a class that stopped mentioning
     * {@code SecureRandom} entirely is no longer near the selection and controls nothing.
     */
    private static boolean referencesSecureRandom(String className) {
        return PRODUCTION_CLASSES.stream()
                .filter(javaClass -> javaClass.getFullName().equals(className))
                .anyMatch(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(SecureRandom.class)));
    }

    /**
     * Self-or-descendant containment over a dotted namespace: {@code candidate} <em>is</em>
     * {@code prefix}, or sits beneath it. Serves both halves of this gate — the package selection in
     * {@link #GATED_CLASS} and the registration lookup in {@link #isRegistered} — so the two cannot
     * drift into disagreeing about what "inside" means.
     * <p>
     * The dot is appended deliberately: a bare {@code startsWith} would let {@code …gateway} swallow
     * an unrelated sibling package such as {@code …gatewayadmin}, and a registration entry for
     * {@code …bff.cookie} cover an unrelated {@code …bff.cookiejar}.
     */
    private static boolean isWithin(String candidate, String prefix) {
        return candidate.equals(prefix) || candidate.startsWith(prefix + ".");
    }
}
