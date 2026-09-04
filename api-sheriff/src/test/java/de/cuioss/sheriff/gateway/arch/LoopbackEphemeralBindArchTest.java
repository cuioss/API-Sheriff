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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.AccessTarget.ConstructorCallTarget;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Standing guard against reintroducing a <strong>bare ephemeral wildcard bind</strong> in this
 * module's test tree.
 * <p>
 * The exposure this closes was measured, not guessed. A fixture that binds with the single-argument
 * {@code listen(0)} overload gets the <em>dual-stack wildcard</em>, while the client in that same
 * fixture dials {@code 127.0.0.1}. Netty sets {@code SO_REUSEADDR}, macOS then lets that wildcard
 * bind coexist with an ephemeral port another process already holds as a
 * {@code 127.0.0.1}-specific listener, and BSD most-specific-match routes the fixture's own request
 * to that other process — which accepts it and never answers. The evidence, its four controls and
 * the measured collision rate are written up in
 * {@code doc/development/build-gate-discipline.adoc}.
 * <p>
 * <strong>The rule keys on the overload signature, never on the port literal.</strong> ArchUnit
 * reads bytecode and cannot see that the argument is {@code 0}. It can see which overload was
 * called, and that is enough: the bare single-int forms bind the wildcard whatever the port, and
 * their host-bound siblings do not. Both spellings are banned together —
 * {@link ServerSocket#ServerSocket(int)} and the single-int {@code listen(int)} overload — because
 * a guard against one leaves the other free to reintroduce the identical bind under a different
 * name.
 * <p>
 * <strong>What the bytecode rule cannot see, and what covers it.</strong> Keying on the signature
 * buys precision at a stated cost: {@code listen(0, "0.0.0.0")} and
 * {@code listen(0, LoopbackHost.ADDRESS)} are <em>the same call target</em> — same name, same
 * parameter types — so the rule accepts both, and the first rebuilds the wildcard bind this guard
 * exists to refuse. The discriminator is the argument's text, which lives in the source rather than
 * the bytecode. {@link WildcardHostLiteralSweep} covers exactly that gap with a source sweep, and
 * carries its own matched controls. Neither half is sufficient alone: the rule reaches call shapes
 * a text scan would misread, and the sweep reaches literals the rule is blind to. This limit is
 * recorded rather than left implicit so a green rule is not read as more than it proves.
 * <p>
 * <strong>Scope: the test tree only.</strong> Production binds configured ports rather than
 * ephemeral ones, and {@code SniFrontListener.start()} binds the wildcard deliberately via
 * {@code netServer.listen(publicPort)}. Selecting production would make this guard demand a change
 * that would break the gateway. {@link MatchedControls#productionWildcardBinderIsOutOfScope()} pins
 * that exclusion so it cannot erode silently.
 * <p>
 * <strong>Carve-out 1 — the specimen package.</strong>
 * {@code de.cuioss.sheriff.gateway.arch.specimen} is excluded from the guarded selection because it
 * holds the controls themselves, one of which violates the rule on purpose. Without the exclusion
 * the negative control would fail the main rule and the guard could never be green.
 * <p>
 * <strong>Carve-out 2 — {@code de.cuioss.sheriff.gateway.tls.TlsEdgeProducerTest}.</strong> Its
 * wildcard-bound sockets are the only ones in this tree that stay wildcard-bound, and deliberately
 * so. They serve two roles: the collision holders occupy a port precisely so that production's
 * <em>wildcard</em> bind is refused, and {@code freePort()} binds to allocate a candidate and again
 * to re-probe it against the bind scope production will use. No count is given — it has gone stale
 * twice already, and the carve-out is the class rather than an enumerated list of sites. None
 * is ever dialled, so none is exposed. Narrowing them to loopback would leave the wildcard free,
 * the producer's bind would succeed, and {@code failsWhenThePublicPortIsHeld} would go red on macOS
 * while staying green on Linux CI — the exact platform-divergent failure class this whole change
 * exists to remove. The full justification is recorded in place, at each site and in
 * the {@code freePort()} Javadoc, and is not restated here. The carve-out covers the outer class
 * <em>and its nested classes</em>, since three of the four sites live inside {@code @Nested}
 * fixtures.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, wiring the guard into the quality gate — the same
 * arrangement {@link NoStoredOptionalArchTest} uses.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class LoopbackEphemeralBindArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";
    private static final String SPECIMEN_PACKAGE = "de.cuioss.sheriff.gateway.arch.specimen";
    private static final String WILDCARD_SPECIMEN = SPECIMEN_PACKAGE + ".WildcardEphemeralBindSpecimen";
    private static final String LOOPBACK_SPECIMEN = SPECIMEN_PACKAGE + ".LoopbackEphemeralBindSpecimen";

    /** The single carved-out fixture; its nested classes are carved out with it. */
    private static final String CARVED_OUT_TEST = "de.cuioss.sheriff.gateway.tls.TlsEdgeProducerTest";

    /** Production's deliberate wildcard binder, which this guard must never select. */
    private static final String PRODUCTION_WILDCARD_BINDER = "de.cuioss.sheriff.gateway.tls.SniFrontListener";

    private static final String LISTEN = "listen";

    /** Where this module's test sources live, relative to the module directory Surefire runs in. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    /**
     * Collapses a matched offender to one line for the failure message. Hoisted out of the scan loop
     * so the pattern is compiled once rather than per offender.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * The wrapped specimen's call, required to still contain a newline inside its argument list.
     * Exists because the count assertion cannot see layout: joining that call onto one line leaves
     * the violation count unchanged, so only a shape-specific check can tell that the multi-line
     * coverage is still being exercised.
     * <p>
     * Horizontal whitespace and the line break are matched separately — {@code [ \\t]*+} then
     * {@code \\R} — rather than as {@code \\s*\\n\\s*}. {@code \\s} matches the newline too, so that
     * spelling leaves the engine several ways to divide the same text and backtracks over each; the
     * classes here cannot overlap, so there is nothing to backtrack. Reported by Sonar
     * ({@code java:S8786}), the same rule that caught the sweep pattern earlier on this branch.
     */
    private static final Pattern WRAPPED_WILDCARD_CALL = Pattern.compile(
            "listen\\([ \\t]*+\\R[ \\t]*+0,[ \\t]*+\\R[ \\t]*+\"0\\.0\\.0\\.0\"\\)");

    /**
     * Matches a {@code listen(<port>, "<wildcard host>")} call in source text.
     * <p>
     * The wildcard hosts are the three spellings that bind every interface: IPv4 {@code 0.0.0.0},
     * IPv6 {@code ::}, and the empty host. A quoted literal is the only shape this can match by
     * construction — a host held in a constant carries no literal to match, which is exactly the
     * correct usage this must not flag.
     * <p>
     * <strong>Applied to whole-file content, never line by line.</strong> The {@code \\s*} runs match
     * newlines, so a wrapped call — {@code listen(}, then {@code 0,}, then {@code "0.0.0.0")} on
     * three physical lines — is caught. A per-line scan would miss it, and the ArchUnit rule misses
     * it too (it is still {@code listen(int, String)}), so a line-based sweep would leave the exact
     * bypass this pair exists to close. Reported by CodeRabbit on PR #255.
     * <p>
     * <strong>The port is matched as any single argument, not as a numeric literal.</strong> The
     * exposure is decided entirely by the HOST, so {@code listen(port, "0.0.0.0")} is the same
     * defect as {@code listen(0, "0.0.0.0")} and a {@code \\d+} port would have missed it — again in
     * a shape the bytecode rule also accepts. The port alternation spans one argument — plain text,
     * or a single level of nested parentheses so {@code listen(freePort(), "0.0.0.0")} is caught
     * too — without crossing the argument comma, which keeps a two-argument {@code listen} from
     * matching across an unrelated neighbouring call. Also reported by CodeRabbit on PR #255.
     * <p>
     * <strong>One level of nesting, stated as the limit it is.</strong> A regex cannot balance
     * arbitrary parentheses, so a doubly-nested port expression
     * ({@code listen(f(g()), "0.0.0.0")}) is out of this sweep's reach and always will be. That is
     * recorded rather than papered over: this pattern is a net, not a parser, and the honest way to
     * run it is knowing where its holes are.
     * <p>
     * <strong>Possessive quantifiers, and no {@code \\s*} beside the argument class.</strong>
     * {@code [^,()]} matches whitespace itself, so an adjacent {@code \\s*} makes the split between
     * them ambiguous and the engine backtracks over every division — super-linear on a long
     * non-matching line. The {@code \\s*} either side of the argument is therefore dropped (the class
     * absorbs that whitespace anyway) and both remaining quantifiers are possessive, which forbids
     * the backtracking outright rather than merely making it unlikely. Reported by Sonar
     * ({@code java:S8786}) on PR #255.
     */
    private static final Pattern WILDCARD_HOST_LISTEN =
            Pattern.compile("\\.\\s*+listen\\s*+\\((?:[^,()\"]|\\([^()]*+\\))*+,\\s*+\"(?:0\\.0\\.0\\.0|::|)\"");

    private static final JavaClasses TEST_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    /**
     * The specimen package is imported <em>separately</em> and by name, so the controls address
     * exactly their own fixtures rather than whatever the guarded selection happens to contain.
     */
    private static final JavaClasses SPECIMEN_CLASSES = new ClassFileImporter()
            .importPackages(SPECIMEN_PACKAGE);

    /**
     * Production is imported only so {@link MatchedControls#productionWildcardBinderIsOutOfScope()}
     * can assert the near-miss property still holds before asserting the exclusion.
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    private static final DescribedPredicate<JavaClass> IN_GUARDED_SELECTION =
            new DescribedPredicate<>("test classes outside the specimen package and outside the "
                    + "TlsEdgeProducerTest carve-out") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return isGuarded(javaClass);
                }
            };

    private static boolean isGuarded(JavaClass javaClass) {
        String name = javaClass.getName();
        if (name.startsWith(SPECIMEN_PACKAGE + ".")) {
            return false;
        }
        return !CARVED_OUT_TEST.equals(name) && !name.startsWith(CARVED_OUT_TEST + "$");
    }

    /**
     * Phrased positively — "must NOT call …" — and used with {@code classes().should(…)} rather than
     * {@code noClasses().should(…)}. That is deliberate: under the {@code no…} form ArchUnit inverts
     * event polarity, so a condition that emits {@code violated} events reports nothing at all and
     * the rule passes vacuously. Emitting {@code violated} from a positive rule keeps the polarity
     * unambiguous — the same reasoning {@link NoStoredOptionalArchTest} records.
     *
     * @return the condition both the main rule and every control are checked against
     */
    private static ArchCondition<JavaClass> notBindABareEphemeralWildcard() {
        return new ArchCondition<>("not call the single-int ServerSocket(int) constructor "
                + "nor the single-int listen(int) overload") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                    if (isBareServerSocketConstructor(call.getTarget())) {
                        events.add(violation(javaClass, call, "new ServerSocket(int)",
                                "new ServerSocket(port, backlog, "
                                        + "InetAddress.getByName(LoopbackHost.ADDRESS))"));
                    }
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if (isBareListenOverload(call.getTarget())) {
                        events.add(violation(javaClass, call, "listen(int)",
                                "listen(port, LoopbackHost.ADDRESS)"));
                    }
                }
            }
        };
    }

    private static ConditionEvent violation(JavaClass owner, JavaAccess<?> call, String spelling,
            String replacement) {
        return SimpleConditionEvent.violated(owner,
                owner.getName() + " calls the bare " + spelling + " form at "
                        + call.getSourceCodeLocation() + " — that binds the dual-stack wildcard while "
                        + "the fixture dials loopback, which is the measured stall exposure. Use "
                        + replacement + " instead, or carve the site out explicitly with its "
                        + "justification recorded in place.");
    }

    private static boolean isBareServerSocketConstructor(ConstructorCallTarget target) {
        return target.getOwner().isEquivalentTo(ServerSocket.class) && isSingleIntParameter(target);
    }

    private static boolean isBareListenOverload(MethodCallTarget target) {
        return LISTEN.equals(target.getName()) && isSingleIntParameter(target);
    }

    /**
     * The {@code .java} sources the wildcard sweep scans.
     *
     * <p>Excludes the same two things {@link #isGuarded(JavaClass)} does, and that agreement is the
     * point: the sweep and the bytecode rule are two halves of one guard, so a file exempt from one
     * must be exempt from the other. The specimen package carries the sweep's own deliberate
     * violations. {@code TlsEdgeProducerTest} is the class-level carve-out for deliberate wildcard
     * binds — scanning it here while the rule excludes it would let the sweep reject a collision
     * control the rule deliberately permits, so the two halves would disagree about what the guard
     * protects.
     *
     * @return the guarded source files
     * @throws IOException when the source tree cannot be walked
     */
    private static List<Path> guardedSources() throws IOException {
        if (!Files.isDirectory(TEST_SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(TEST_SOURCE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isCarvedOutSource(path))
                    .toList();
        }
    }

    /**
     * Whether a source path is outside the sweep's scope, mirroring {@link #isGuarded(JavaClass)}.
     *
     * @param path a source file
     * @return {@code true} when the sweep must not scan it
     */
    private static boolean isCarvedOutSource(Path path) {
        String normalised = path.toString().replace('\\', '/');
        return normalised.contains("/" + SPECIMEN_PACKAGE.replace('.', '/') + "/")
                || normalised.endsWith("/" + CARVED_OUT_TEST.replace('.', '/') + ".java");
    }

    /**
     * The 1-based line a character offset falls on, so a whole-file match still reports a location
     * a reader can open.
     *
     * @param content the file content the offset indexes into
     * @param offset  the match's start offset
     * @return the 1-based line number
     */
    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if ('\n' == content.charAt(i)) {
                line++;
            }
        }
        return line;
    }

    private static boolean isSingleIntParameter(CodeUnitCallTarget target) {
        var parameters = target.getRawParameterTypes();
        return parameters.size() == 1 && parameters.get(0).isEquivalentTo(int.class);
    }

    private static boolean isHostBoundListenOverload(MethodCallTarget target) {
        if (!LISTEN.equals(target.getName())) {
            return false;
        }
        var parameters = target.getRawParameterTypes();
        return parameters.size() == 2
                && parameters.get(0).isEquivalentTo(int.class)
                && parameters.get(1).isEquivalentTo(String.class);
    }

    private static boolean callsHostBoundListen(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(call -> isHostBoundListenOverload(call.getTarget()));
    }

    private static boolean callsBareListen(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(call -> isBareListenOverload(call.getTarget()));
    }

    private static ArchRule ruleAgainst(String fullyQualifiedName) {
        return classes()
                .that().haveFullyQualifiedName(fullyQualifiedName)
                .should(notBindABareEphemeralWildcard())
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("Test fixtures must not bind a bare ephemeral wildcard socket")
    void fixturesMustNotBindABareEphemeralWildcard() {
        ArchRule rule = classes()
                .that(IN_GUARDED_SELECTION)
                .should(notBindABareEphemeralWildcard())
                .because("a wildcard ephemeral bind that is later dialled on loopback can coexist with a "
                        + "foreign 127.0.0.1 listener on the same port, and the kernel then routes the "
                        + "fixture's own client to that other process, which never answers; binding "
                        + "through LoopbackHost.ADDRESS makes the bind collide instead of coexist");

        rule.check(TEST_CLASSES);
    }

    /**
     * Guards the guard. Three ways this rule could pass while protecting nothing, each closed by one
     * assertion below: the test-tree import resolves to no classes at all; the specimen package
     * stops resolving, silently disarming every control; or the call-scanning machinery sees no
     * {@code listen} calls in the selection, in which case zero violations says nothing about
     * whether a bare bind would have been found.
     * <p>
     * The third is the one the controls cannot cover. They exercise their own hardcoded specimen
     * package, so they prove the mechanism works while saying nothing about whether the real
     * selection is still being scanned. Requiring a host-bound {@code listen(int, String)} call in
     * the selection is the positive evidence that it is — after the loopback conversion, every live
     * fixture in this tree makes one.
     * <p>
     * Deliberately direct counts rather than {@link ArchRule}s with always-true conditions: any such
     * condition risks failing for its own reason instead of for emptiness, which would make this
     * guard red for a reason unrelated to the gap it exists to detect.
     */
    @Test
    @DisplayName("Loopback-bind guard is non-vacuous: selection, specimens and a host-bound listen call all resolve")
    void guardIsNonVacuous() {
        long guarded = TEST_CLASSES.stream().filter(IN_GUARDED_SELECTION).count();
        long specimens = SPECIMEN_CLASSES.stream().count();
        boolean sawHostBoundListen = TEST_CLASSES.stream()
                .filter(IN_GUARDED_SELECTION)
                .anyMatch(LoopbackEphemeralBindArchTest::callsHostBoundListen);

        assertAll("the loopback-bind guard is non-vacuous",
                () -> assertTrue(guarded > 0,
                        "The guarded selection resolved to NO test classes — the rule above is checking "
                                + "nothing. Either ONLY_INCLUDE_TESTS stopped matching this build's output "
                                + "layout, or BASE_PACKAGE was renamed."),
                () -> assertTrue(specimens > 0,
                        "The specimen package '" + SPECIMEN_PACKAGE + "' resolved to NO classes, so every "
                                + "control below is exercising an empty set and proves nothing."),
                () -> assertTrue(sawHostBoundListen,
                        "No class in the guarded selection was seen calling the host-bound "
                                + "listen(int, String) form. Zero violations is then uninformative: it "
                                + "cannot be told apart from a scan that sees no bind calls at all."));
    }

    @Nested
    @DisplayName("Wildcard host literal sweep")
    class WildcardHostLiteralSweep {

        /**
         * Closes the gap the bytecode rule structurally cannot reach. {@code listen(0, "0.0.0.0")}
         * and {@code listen(0, LoopbackHost.ADDRESS)} are the same call target — same name, same
         * parameter types — so {@link #isHostBoundListenOverload} accepts both and the rule above
         * treats the first as safe. Vert.x reads {@code "0.0.0.0"} as the wildcard host, so that
         * spelling reconstitutes the exact bind the guard exists to refuse, in a form the guard
         * cannot see.
         * <p>
         * The discriminator is the argument's <em>text</em>, which lives in the source rather than
         * the bytecode — hence a source sweep rather than a wider ArchUnit rule. Reported by
         * CodeRabbit on PR #255.
         */
        @Test
        @DisplayName("No fixture passes a wildcard host literal to the host-bound listen overload")
        void noFixturePassesAWildcardHostLiteral() throws Exception {
            List<String> offenders = new ArrayList<>();
            for (Path source : guardedSources()) {
                String content = Files.readString(source);
                Matcher matcher = WILDCARD_HOST_LISTEN.matcher(content);
                while (matcher.find()) {
                    offenders.add(source.getFileName() + ":" + lineOf(content, matcher.start())
                            + " — " + WHITESPACE_RUN.matcher(matcher.group()).replaceAll(" "));
                }
            }

            assertTrue(offenders.isEmpty(),
                    "A fixture binds an ephemeral port to a wildcard host literal. That is the "
                            + "measured stall exposure in the two-argument spelling, and the bytecode "
                            + "rule above cannot see it — listen(int, String) is one call target "
                            + "whatever the host string. Bind through LoopbackHost.ADDRESS instead. "
                            + "Offenders: " + offenders);
        }

        /**
         * The matched positive control. Without it, a sweep whose regex stopped matching — or one
         * pointed at a directory that no longer holds sources — would report zero offenders and be
         * indistinguishable from a clean tree.
         */
        @Test
        @DisplayName("The sweep finds the specimen's deliberate wildcard host literal (positive control)")
        void sweepFindsTheDeliberateWildcardHostLiteral() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(
                    "de/cuioss/sheriff/gateway/arch/specimen/WildcardEphemeralBindSpecimen.java");

            assertTrue(Files.exists(specimen),
                    "The wildcard specimen source is missing at " + specimen + ", so the sweep's "
                            + "positive control is exercising nothing.");

            String content = Files.readString(specimen);
            long matches = WILDCARD_HOST_LISTEN.matcher(content).results().count();

            assertEquals(4, matches,
                    "The sweep must match ALL FOUR deliberate violations in the specimen: the "
                            + "single-line listen(0, \"0.0.0.0\"), the one wrapped across lines, the "
                            + "one whose port is a variable, and the one whose port is a nested "
                            + "call. Each was a "
                            + "real bypass at some point in this guard's history, and each is a shape "
                            + "the bytecode rule also accepts — so a count below four means the "
                            + "sweep has regressed to a narrower predicate and its clean verdict over "
                            + "the rest of the tree covers less than it appears to. Found "
                            + matches + ".");

            assertTrue(WILDCARD_HOST_LISTEN.matcher("server . listen(0, \"0.0.0.0\")").find(),
                    "The sweep does not match a spaced selector. Java permits `server . listen(...)`, "
                            + "a line break included, so that spelling would pass both this sweep and "
                            + "the bytecode rule. Asserted against a literal here rather than a source "
                            + "specimen ON PURPOSE: the -Ppre-commit formatter closes the spacing up, "
                            + "so a specimen carrying this shape is normalised on the next gate run "
                            + "and its coverage disappears while the violation count stays green — "
                            + "the same silent retirement the multi-line check below exists to catch. "
                            + "A shape the formatter will not preserve has to be pinned in a string.");

            assertTrue(WRAPPED_WILDCARD_CALL.matcher(content).find(),
                    "The wrapped specimen no longer spans multiple physical lines. The count "
                            + "assertion above cannot detect that — joining the call leaves four "
                            + "violations and still passes — so this shape needs its own check. "
                            + "Without it a formatter could silently retire the multi-line coverage "
                            + "while every count stayed green.");
        }

        /**
         * The matched negative control: the sweep must NOT flag the loopback-bound spelling. Without
         * it, a pattern that matched every {@code listen(int, String)} call would pass the positive
         * control above while flagging every correct site.
         */
        @Test
        @DisplayName("The sweep leaves the loopback-bound spelling alone (negative control)")
        void sweepDoesNotFlagTheLoopbackBoundSpelling() throws Exception {
            Path specimen = TEST_SOURCE_ROOT.resolve(
                    "de/cuioss/sheriff/gateway/arch/specimen/LoopbackEphemeralBindSpecimen.java");

            assertTrue(Files.exists(specimen), "The loopback specimen source is missing at " + specimen);
            assertFalse(WILDCARD_HOST_LISTEN.matcher(Files.readString(specimen)).find(),
                    "The sweep flags the host-bound loopback spelling, so it does not discriminate "
                            + "between a wildcard literal and a correct bind.");
        }

        /**
         * The sweep and the bytecode rule must exempt the same files. Without this, the sweep could
         * scan {@code TlsEdgeProducerTest} — which the rule excludes as a class-level carve-out —
         * and reject a wildcard-host collision control the rule deliberately permits, so the two
         * halves of one guard would disagree about what they protect.
         */
        @Test
        @DisplayName("The sweep exempts the same files the bytecode rule does")
        void sweepScopeMatchesRuleScope() throws Exception {
            List<Path> scanned = guardedSources();

            assertAll("the sweep's scope mirrors isGuarded()",
                    () -> assertTrue(scanned.stream().noneMatch(p -> p.toString()
                                    .replace('\\', '/').endsWith("/TlsEdgeProducerTest.java")),
                            "The sweep scans TlsEdgeProducerTest, which the bytecode rule carves out. "
                                    + "A deliberate wildcard-host collision control there would be "
                                    + "reported as an offender by one half of the guard and permitted "
                                    + "by the other."),
                    () -> assertTrue(scanned.stream().noneMatch(p -> p.toString()
                                    .replace('\\', '/').contains("/arch/specimen/")),
                            "The sweep scans the specimen package, whose whole purpose is to hold "
                                    + "deliberate violations — every run would report them as "
                                    + "offenders."),
                    () -> assertTrue(scanned.stream().anyMatch(p -> p.toString()
                                    .replace('\\', '/').endsWith("/AwaitsTest.java")),
                            "The sweep scans neither specimen nor carve-out AND misses an ordinary "
                                    + "fixture, so the exclusions have over-reached and the sweep is "
                                    + "covering less than it claims."));
        }

        /** Non-vacuity: the sweep must actually be reading a populated source tree. */
        @Test
        @DisplayName("Wildcard sweep is non-vacuous: the guarded source set resolves")
        void sweepIsNonVacuous() throws Exception {
            assertTrue(Files.isDirectory(TEST_SOURCE_ROOT),
                    "The test source root did not resolve to a directory at " + TEST_SOURCE_ROOT
                            + ", so the sweep scanned nothing and its clean verdict is empty.");
            assertTrue(guardedSources().size() > 1,
                    "The guarded source set resolved to at most one file, which cannot be the whole "
                            + "test tree — the sweep is scanning far less than it claims.");
        }
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        /**
         * Negative control: proves the rule actually fails on a real bare wildcard bind, in both
         * banned spellings.
         * <p>
         * <strong>{@code allowEmptyShould(true)} is deliberate and must not be removed.</strong> If
         * the specimen ever stops resolving, that setting makes {@code check} pass, which makes
         * {@code assertThrows} fail loudly and tells us the control has stopped controlling
         * anything. Removing it would invert that: an unresolved specimen would make {@code check}
         * throw on emptiness, {@code assertThrows} would be satisfied by the wrong exception, and
         * the test would go green while proving nothing.
         */
        @Test
        @DisplayName("Guard detects a deliberately wildcard-bound specimen (negative control)")
        void guardFailsOnWildcardSpecimen() {
            ArchRule rule = ruleAgainst(WILDCARD_SPECIMEN);

            assertThrows(AssertionError.class,
                    () -> rule.check(SPECIMEN_CLASSES),
                    "The guard must fail on WildcardEphemeralBindSpecimen's new ServerSocket(0) and "
                            + "listen(0) — if it does not, either the specimen's call sites were widened "
                            + "to a host-bound overload or the specimen no longer resolves");
        }

        /**
         * Matched positive control: the same rule against the host-bound near-miss must pass.
         * Without this, a rule that failed on every bind would satisfy the negative control alone;
         * this is what proves the guard discriminates between the bare and the host-bound overload.
         */
        @Test
        @DisplayName("Guard accepts the matched host-bound specimen (positive control)")
        void guardPassesOnLoopbackSpecimen() {
            ArchRule rule = ruleAgainst(LOOPBACK_SPECIMEN);

            assertDoesNotThrow(() -> rule.check(SPECIMEN_CLASSES),
                    "The guard must accept LoopbackEphemeralBindSpecimen, which binds "
                            + LoopbackHost.ADDRESS + " through the host-bound overloads — a rule that "
                            + "failed here would be always-failing rather than discriminating");
        }

        /**
         * Matched positive control for the scope boundary: production's deliberate wildcard binder
         * must stay outside the guarded selection.
         * <p>
         * The order of the two assertions is load-bearing. "Not selected" is trivially true of a
         * class that no longer calls the bare form at all, so the near-miss property is asserted
         * first: {@code SniFrontListener} really does call {@code listen(int)}, and would really be
         * reported if it were ever selected. Only then does the exclusion mean anything.
         */
        @Test
        @DisplayName("Production's deliberate wildcard binder stays out of scope (positive control)")
        void productionWildcardBinderIsOutOfScope() {
            Optional<JavaClass> binder = PRODUCTION_CLASSES.stream()
                    .filter(javaClass -> PRODUCTION_WILDCARD_BINDER.equals(javaClass.getName()))
                    .findFirst();
            assertTrue(binder.isPresent(),
                    PRODUCTION_WILDCARD_BINDER + " did not resolve in the production import, so this "
                            + "control cannot establish anything about the scope boundary");

            assertTrue(callsBareListen(binder.get()),
                    PRODUCTION_WILDCARD_BINDER + " no longer calls the bare listen(int) overload, so it "
                            + "is no longer the near-miss this control needs. Either production changed "
                            + "its bind, in which case retarget this control, or the overload matcher "
                            + "stopped matching, in which case the whole guard is blind");

            assertFalse(TEST_CLASSES.stream()
                            .anyMatch(javaClass -> PRODUCTION_WILDCARD_BINDER.equals(javaClass.getName())),
                    PRODUCTION_WILDCARD_BINDER + " appeared in the guarded test selection. The guard "
                            + "would demand a loopback bind from production, which binds its configured "
                            + "public port on purpose — fix the import scope rather than the production "
                            + "bind");
        }
    }
}
