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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.cuioss.http.client.handler.HttpHandler;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocketClientOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Standing guard that every outbound TLS client this gateway constructs is handed its TLS posture
 * <strong>explicitly</strong>, at the construction site, rather than inheriting a library default.
 * <p>
 * <strong>Why an implicit default is a defect here.</strong> Each outbound leg's hostname-verification
 * and trust posture is a security property of the gateway. Where a construction site sets nothing, that
 * property rests on whatever the client library defaults to today, and an upstream default change moves
 * it silently (ADR-0022). The {@code egress_tls} legs are pinned for that reason (ADR-0040, ADR-0041,
 * ADR-0045), and the asset-origin JDK client pins the JVM default context for the same reason. What no
 * reviewer can hold in place by reading is the <em>next</em> leg: a seventh client built without its
 * posture would pass every existing test. This rule is that standing check.
 * <p>
 * <strong>The property.</strong> Every call to an outbound TLS-client construction target is
 * accompanied, in the calling class, by a call to that target's posture method. The map below is the
 * rule's single source:
 * <ul>
 *   <li>Vert.x {@code createHttpClient(HttpClientOptions…)} → {@code HttpClientOptions#setVerifyHost};</li>
 *   <li>Vert.x {@code createWebSocketClient(WebSocketClientOptions…)} →
 *       {@code WebSocketClientOptions#setVerifyHost};</li>
 *   <li>JDK {@code HttpClient#newBuilder()} → {@code HttpClient.Builder#sslContext} or
 *       {@code #sslParameters};</li>
 *   <li>token-sheriff {@code ClientConfiguration#builder()} → the builder's {@code verifyHostname};</li>
 *   <li>token-sheriff {@code HttpJwksLoaderConfig#builder()} → the builder's {@code verifyHostname};</li>
 *   <li>cui-http {@code HttpHandler#builder()} → the builder's {@code verifyHostname} — no production site
 *       today, mapped so the first one is caught.</li>
 * </ul>
 * Three targets cannot carry a posture at all and are always a violation: the option-less Vert.x
 * {@code createHttpClient} and {@code createWebSocketClient} overloads, and JDK
 * {@code HttpClient#newHttpClient()}. Plain-TCP {@code createNetClient} is deliberately not a target:
 * it negotiates no TLS (ADR-0017), and {@code TlsEdgeProducer} is kept as the matched near-miss proving
 * the rule does not reach it.
 * <p>
 * <strong>Four legs, per ADR-0030.</strong>
 * <ol>
 *   <li><em>Non-vacuity</em> — the production selection resolves, and every mapped family that has a
 *       production site today resolves at least one construction call. A property rather than a frozen
 *       member list: a correctly pinned new leg needs no edit here.</li>
 *   <li><em>Negative control</em> — {@code UnpinnedEgressClientSpecimen} must make the rule throw, once
 *       per unpinned family.</li>
 *   <li><em>Matched positive controls</em> — {@code PinnedEgressClientSpecimen} passes, and
 *       {@code TlsEdgeProducer} is first asserted to call {@code createNetClient} and only then asserted
 *       not reported.</li>
 *   <li><em>Specimen carve-out</em> — the specimen package is excluded from the production selection
 *       explicitly, not only by the test-source import filter.</li>
 * </ol>
 * The rule is phrased positively — {@code classes().should(condition)} emitting
 * {@link SimpleConditionEvent#violated} — because under the {@code no…} form ArchUnit inverts event
 * polarity and the rule would pass vacuously.
 * <p>
 * <strong>Granularity limit, stated rather than implied.</strong> Co-occurrence is read per class, not
 * per construction. A class that pins one client of a family and builds a second client of the same
 * family without its posture is not caught, because the first posture call satisfies both. Every class in
 * the tree builds at most one client per family today. The rule also reads calls, not values: a
 * {@code verifyHostname(false)} is a posture call, and deciding whether a relaxation is legitimate stays
 * with the configuration surface that feeds it.
 * <p>
 * <strong>JVM-level limit.</strong> The JDK system property
 * {@code jdk.internal.httpclient.disableHostnameVerification} disables hostname verification on every
 * JDK-client leg below every gateway key and below every construction site. It is a start-up flag, not
 * bytecode, so no fitness function can see it; it is a named residual of the deployment, not of this
 * rule.
 * <p>
 * This is a plain JUnit 5 test (no ArchUnit {@code @AnalyzeClasses} runner) so it runs in both
 * {@code test} and {@code verify -Ppre-commit}, the same arrangement {@link LoopbackEphemeralBindArchTest}
 * and {@link NativeRuntimeInitRegistrationArchTest} use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class EgressTlsPostureArchTest {

    private static final String BASE_PACKAGE = "de.cuioss.sheriff.gateway";

    /** The specimen package, carved out of the production selection explicitly — see the class Javadoc. */
    private static final String SPECIMEN_PACKAGE = BASE_PACKAGE + ".arch.specimen";
    private static final String UNPINNED_SPECIMEN = SPECIMEN_PACKAGE + ".UnpinnedEgressClientSpecimen";
    private static final String PINNED_SPECIMEN = SPECIMEN_PACKAGE + ".PinnedEgressClientSpecimen";

    /** The production class that dials a plain-TCP Vert.x client: the matched near-miss. */
    private static final String PLAIN_TCP_NEAR_MISS = BASE_PACKAGE + ".tls.TlsEdgeProducer";
    private static final String CREATE_NET_CLIENT = "createNetClient";

    private static final String CREATE_HTTP_CLIENT = "createHttpClient";
    private static final String CREATE_WEB_SOCKET_CLIENT = "createWebSocketClient";
    private static final String SET_VERIFY_HOST = "setVerifyHost";
    private static final String VERIFY_HOSTNAME = "verifyHostname";
    private static final String BUILDER = "builder";

    /**
     * The target → posture map for every construction target that can carry a posture. The single
     * source of the rule, the non-vacuity guard and the negative control's expected count.
     */
    private static final List<EgressFamily> PINNABLE_FAMILIES = List.of(
            new EgressFamily("a Vert.x HTTP client",
                    new ConstructionTarget(Vertx.class, CREATE_HTTP_CLIENT, HttpClientOptions.class),
                    new PostureCall(HttpClientOptions.class, Set.of(SET_VERIFY_HOST)), true),
            new EgressFamily("a Vert.x WebSocket client",
                    new ConstructionTarget(Vertx.class, CREATE_WEB_SOCKET_CLIENT, WebSocketClientOptions.class),
                    new PostureCall(WebSocketClientOptions.class, Set.of(SET_VERIFY_HOST)), true),
            new EgressFamily("a JDK HTTP client",
                    new ConstructionTarget(java.net.http.HttpClient.class, "newBuilder", null),
                    new PostureCall(java.net.http.HttpClient.Builder.class, Set.of("sslContext", "sslParameters")),
                    true),
            new EgressFamily("a token-sheriff OIDC client configuration",
                    new ConstructionTarget(ClientConfiguration.class, BUILDER, null),
                    new PostureCall(builderOf(ClientConfiguration.class), Set.of(VERIFY_HOSTNAME)), true),
            new EgressFamily("a token-sheriff JWKS loader configuration",
                    new ConstructionTarget(HttpJwksLoaderConfig.class, BUILDER, null),
                    new PostureCall(builderOf(HttpJwksLoaderConfig.class), Set.of(VERIFY_HOSTNAME)), true),
            new EgressFamily("a cui-http handler",
                    new ConstructionTarget(HttpHandler.class, BUILDER, null),
                    new PostureCall(builderOf(HttpHandler.class), Set.of(VERIFY_HOSTNAME)), false));

    /** The construction targets that take no options object and therefore can never carry a posture. */
    private static final List<UnpinnableTarget> UNPINNABLE_TARGETS = List.of(
            new UnpinnableTarget(Vertx.class, CREATE_HTTP_CLIENT, HttpClientOptions.class,
                    "createHttpClient(new HttpClientOptions().setVerifyHost(...))"),
            new UnpinnableTarget(Vertx.class, CREATE_WEB_SOCKET_CLIENT, WebSocketClientOptions.class,
                    "createWebSocketClient(new WebSocketClientOptions().setVerifyHost(...))"),
            new UnpinnableTarget(java.net.http.HttpClient.class, "newHttpClient", null,
                    "HttpClient.newBuilder().sslContext(...)"));

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    /**
     * The specimen package is imported <em>separately and with tests included</em>: the controls live in
     * {@code src/test}, so the production import above deliberately cannot see them.
     */
    private static final JavaClasses SPECIMEN_CLASSES = new ClassFileImporter()
            .importPackages(SPECIMEN_PACKAGE);

    /**
     * The production selection: every gateway class outside the specimen package. Shared verbatim by the
     * rule, the non-vacuity guard and the carve-out control, so a scope edit moves all three together.
     */
    private static final DescribedPredicate<JavaClass> IN_PRODUCTION_SELECTION =
            new DescribedPredicate<>("classes under " + BASE_PACKAGE + " outside " + SPECIMEN_PACKAGE) {
                @Override
                public boolean test(JavaClass javaClass) {
                    String packageName = javaClass.getPackageName();
                    return isWithin(packageName, BASE_PACKAGE) && !isWithin(packageName, SPECIMEN_PACKAGE);
                }
            };

    @Test
    @DisplayName("Every outbound TLS client is constructed with an explicit TLS posture")
    void everyOutboundTlsClientCarriesAnExplicitPosture() {
        ArchRule rule = classes()
                .that(IN_PRODUCTION_SELECTION)
                .should(passAnExplicitPostureToEveryOutboundTlsClientItConstructs())
                .because("a client built without its posture inherits the library's TLS default, and an "
                        + "upstream default change would then move the gateway's hostname or trust "
                        + "posture silently (ADR-0022)");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * Guards the guard. The rule above passes over an empty selection and over a selection in which the
     * construction matchers see nothing, and neither can be told apart from a clean tree by its verdict.
     * <p>
     * The per-family half is the one the controls cannot cover: the specimens exercise two families in
     * their own package, so they prove the mechanism works while saying nothing about whether each
     * real production leg is still being recognised. A matcher that stopped resolving — a renamed
     * library type, a changed overload — would otherwise turn that leg's check into a silent no-op.
     * <p>
     * Deliberately direct counts rather than {@link ArchRule}s with always-true conditions: such a
     * condition risks failing for its own reason instead of for emptiness.
     */
    @Test
    @DisplayName("Egress TLS posture guard is non-vacuous: selection, specimens and every live family resolve")
    void guardIsNonVacuous() {
        long selected = PRODUCTION_CLASSES.stream().filter(IN_PRODUCTION_SELECTION).count();
        long specimens = SPECIMEN_CLASSES.stream().count();
        List<String> unresolvedFamilies = PINNABLE_FAMILIES.stream()
                .filter(EgressFamily::productionSiteToday)
                .filter(family -> PRODUCTION_CLASSES.stream()
                        .filter(IN_PRODUCTION_SELECTION)
                        .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                        .noneMatch(call -> family.construction().matches(call.getTarget())))
                .map(EgressFamily::label)
                .toList();

        assertAll("the egress TLS posture guard is non-vacuous",
                () -> assertTrue(selected > 0,
                        "The production selection resolved to NO classes — the rule above is checking "
                                + "nothing. Either DO_NOT_INCLUDE_TESTS stopped matching this build's "
                                + "output layout, or BASE_PACKAGE was renamed."),
                () -> assertTrue(specimens > 0,
                        "The specimen package '" + SPECIMEN_PACKAGE + "' resolved to NO classes, so every "
                                + "control below is exercising an empty set and proves nothing."),
                () -> assertTrue(unresolvedFamilies.isEmpty(),
                        "No construction call was recognised in production for " + unresolvedFamilies
                                + ". Either that leg was removed — then mark its family as having no "
                                + "production site — or its construction matcher stopped matching, in "
                                + "which case every future client of that family would pass unchecked."));
    }

    @Nested
    @DisplayName("Matched controls")
    class MatchedControls {

        /**
         * Negative control: the rule must fail on the unpinned specimen, and must report both of its
         * constructions — one per family — rather than stopping at the first.
         * <p>
         * <strong>{@code allowEmptyShould(true)} is deliberate and must not be removed.</strong> If the
         * specimen ever stops resolving, that setting makes {@code check} pass, which makes
         * {@code assertThrows} fail loudly. Removing it would let an unresolved specimen throw on
         * emptiness, satisfying {@code assertThrows} with the wrong exception.
         */
        @Test
        @DisplayName("Guard rejects the unpinned specimen once per unpinned family (negative control)")
        void guardFailsOnUnpinnedSpecimen() {
            ArchRule rule = ruleAgainst(UNPINNED_SPECIMEN);

            assertThrows(AssertionError.class, () -> rule.check(SPECIMEN_CLASSES),
                    "The guard must fail on UnpinnedEgressClientSpecimen's posture-less JDK client and "
                            + "back-channel configuration — if it does not, either a posture call was added "
                            + "to the specimen or the specimen no longer resolves");
            assertEquals(2, rule.evaluate(SPECIMEN_CLASSES).getFailureReport().getDetails().size(),
                    "The guard must report BOTH unpinned constructions in the specimen. A count below two "
                            + "means one family's matcher stopped discriminating, and the rule's clean "
                            + "verdict over production covers less than it appears to.");
        }

        /**
         * Matched positive control: the same two constructions with their posture calls must pass.
         * Without this, a rule that failed on every construction would satisfy the negative control.
         */
        @Test
        @DisplayName("Guard accepts the matched pinned specimen (positive control)")
        void guardPassesOnPinnedSpecimen() {
            ArchRule rule = ruleAgainst(PINNED_SPECIMEN);

            assertTrue(SPECIMEN_CLASSES.contain(PINNED_SPECIMEN),
                    PINNED_SPECIMEN + " did not resolve, so this control would pass over nothing");
            assertDoesNotThrow(() -> rule.check(SPECIMEN_CLASSES),
                    "The guard must accept PinnedEgressClientSpecimen, which passes sslContext and "
                            + "verifyHostname — a rule that failed here would be always-failing rather "
                            + "than discriminating");
        }

        /**
         * Matched positive control for the plain-TCP boundary. {@code TlsEdgeProducer} dials a Vert.x
         * {@code NetClient}, which negotiates no TLS, so it must not be reported.
         * <p>
         * The order is load-bearing. "Not reported" is trivially true of a class that no longer calls
         * {@code createNetClient} at all, so the near-miss property is asserted first; only then does the
         * exclusion mean the rule discriminates between a TLS client and a plain-TCP one.
         */
        @Test
        @DisplayName("Guard leaves the plain-TCP createNetClient near-miss alone (positive control)")
        void plainTcpNearMissIsNotReported() {
            assertTrue(PRODUCTION_CLASSES.contain(PLAIN_TCP_NEAR_MISS),
                    PLAIN_TCP_NEAR_MISS + " did not resolve in the production import, so this control "
                            + "cannot establish anything about the plain-TCP boundary");
            JavaClass nearMiss = PRODUCTION_CLASSES.get(PLAIN_TCP_NEAR_MISS);

            assertAll(PLAIN_TCP_NEAR_MISS + " is a real, unreported near-miss",
                    () -> assertTrue(IN_PRODUCTION_SELECTION.test(nearMiss),
                            PLAIN_TCP_NEAR_MISS + " is outside the production selection, so its absence "
                                    + "from the report would say nothing about the matchers"),
                    () -> assertTrue(nearMiss.getMethodCallsFromSelf().stream()
                                    .anyMatch(call -> CREATE_NET_CLIENT.equals(call.getTarget().getName())),
                            PLAIN_TCP_NEAR_MISS + " no longer calls createNetClient, so it is no longer the "
                                    + "near-miss this control needs. Retarget the control at another "
                                    + "plain-TCP dial site or retire it deliberately"),
                    () -> assertTrue(violationsOf(nearMiss).isEmpty(),
                            PLAIN_TCP_NEAR_MISS + " was reported, but createNetClient negotiates no TLS — a "
                                    + "construction matcher has widened past the mapped TLS targets"));
        }

        /**
         * The specimen carve-out, asserted explicitly. The specimens live in {@code src/test}, so the
         * production import's {@code DO_NOT_INCLUDE_TESTS} already keeps them out; the predicate's own
         * exclusion exists so that the import filter is not the single load-bearing guard.
         * <p>
         * The unpinned specimen is first asserted to be a class the rule WOULD report — otherwise its
         * exclusion from the selection proves nothing.
         */
        @Test
        @DisplayName("The specimen package is carved out of the production selection explicitly")
        void specimenPackageIsCarvedOut() {
            assertTrue(SPECIMEN_CLASSES.contain(UNPINNED_SPECIMEN),
                    UNPINNED_SPECIMEN + " did not resolve, so the carve-out has nothing to exclude");
            JavaClass unpinned = SPECIMEN_CLASSES.get(UNPINNED_SPECIMEN);

            assertAll("the specimen package is carved out",
                    () -> assertFalse(violationsOf(unpinned).isEmpty(),
                            UNPINNED_SPECIMEN + " would not be reported even if selected, so excluding it "
                                    + "demonstrates nothing"),
                    () -> assertFalse(IN_PRODUCTION_SELECTION.test(unpinned),
                            "The production selection accepts " + UNPINNED_SPECIMEN + " — were the import "
                                    + "ever widened to include tests, its deliberate violation would fail "
                                    + "the production rule"),
                    () -> assertFalse(PRODUCTION_CLASSES.contain(UNPINNED_SPECIMEN),
                            "The production import resolved " + UNPINNED_SPECIMEN + ", so the test-source "
                                    + "filter no longer separates the specimens from production"));
        }
    }

    /**
     * The condition: every construction call the class makes must be matched, in the same class, by its
     * family's posture call; an unpinnable target is a violation outright.
     *
     * @return the condition the main rule and every control are checked against
     */
    private static ArchCondition<JavaClass> passAnExplicitPostureToEveryOutboundTlsClientItConstructs() {
        return new ArchCondition<>("pass an explicit TLS posture to every outbound TLS client it constructs") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                violationsOf(javaClass).forEach(events::add);
            }
        };
    }

    /**
     * The violations one class carries under the target → posture map.
     *
     * @param javaClass the class to inspect
     * @return one violated event per unpinned or unpinnable construction call, empty when compliant
     */
    private static List<ConditionEvent> violationsOf(JavaClass javaClass) {
        Set<JavaMethodCall> calls = javaClass.getMethodCallsFromSelf();
        List<ConditionEvent> violations = new ArrayList<>();
        for (EgressFamily family : PINNABLE_FAMILIES) {
            boolean pinned = calls.stream().anyMatch(call -> family.posture().matches(call.getTarget()));
            if (pinned) {
                continue;
            }
            calls.stream()
                    .filter(call -> family.construction().matches(call.getTarget()))
                    .forEach(call -> violations.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " constructs " + family.label() + " through "
                                    + spelling(call) + " at " + call.getSourceCodeLocation()
                                    + " but never calls " + family.posture().spelling() + " — its TLS "
                                    + "posture then rests on a library default an upstream change can "
                                    + "move. Pass the posture explicitly where the client is built.")));
        }
        for (UnpinnableTarget target : UNPINNABLE_TARGETS) {
            calls.stream()
                    .filter(call -> target.matches(call.getTarget()))
                    .forEach(call -> violations.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " constructs an outbound client through " + spelling(call)
                                    + " at " + call.getSourceCodeLocation() + ", which takes no options "
                                    + "object and so can never carry a TLS posture. Use "
                                    + target.replacement() + " instead.")));
        }
        return violations;
    }

    private static String spelling(JavaMethodCall call) {
        return call.getTarget().getOwner().getSimpleName() + "#" + call.getTarget().getName();
    }

    private static ArchRule ruleAgainst(String fullyQualifiedName) {
        return classes()
                .that().haveFullyQualifiedName(fullyQualifiedName)
                .should(passAnExplicitPostureToEveryOutboundTlsClientItConstructs())
                .allowEmptyShould(true);
    }

    /**
     * The builder type a library's static {@code builder()} factory returns — resolved from the factory
     * itself rather than spelled out, so a generated builder's name is never guessed.
     */
    private static Class<?> builderOf(Class<?> built) {
        try {
            return built.getMethod(BUILDER).getReturnType();
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException(built.getName() + " no longer declares a static builder() factory, "
                    + "so its construction target in the egress TLS posture map is stale", missing);
        }
    }

    private static boolean hasParameter(MethodCallTarget target, Class<?> parameterType) {
        return target.getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(parameterType));
    }

    /**
     * Self-or-descendant containment over a dotted namespace; the appended dot keeps
     * {@code …gateway} from swallowing an unrelated sibling such as {@code …gatewayadmin}.
     */
    private static boolean isWithin(String candidate, String prefix) {
        return candidate.equals(prefix) || candidate.startsWith(prefix + ".");
    }

    /**
     * One mapped client family: how its construction is recognised and which call pins its posture.
     *
     * @param label               the family as named in a violation message
     * @param construction        the construction target
     * @param posture             the posture call that must accompany it in the same class
     * @param productionSiteToday whether production constructs this family today, which the non-vacuity
     *                            guard then requires its matcher to recognise
     */
    private record EgressFamily(String label, ConstructionTarget construction, PostureCall posture,
            boolean productionSiteToday) {
    }

    /**
     * A construction call: the method name first (cheap), then the declaring type — matched by
     * assignability so a call through a sub-interface of the library type is still recognised.
     *
     * @param owner             the library type declaring the construction method
     * @param method            the construction method's name
     * @param requiredParameter a parameter type the overload must take, or {@code null} for any overload
     */
    private record ConstructionTarget(Class<?> owner, String method, @Nullable Class<?> requiredParameter) {

        boolean matches(MethodCallTarget target) {
            return method.equals(target.getName())
                    && owner.isAssignableFrom(target.getOwner().reflect())
                    && (requiredParameter == null || hasParameter(target, requiredParameter));
        }
    }

    /**
     * A posture call. Matched when the call's bytecode owner is the builder type <em>or one of its
     * supertypes</em>: a builder method inherited from a generic parent is emitted against that parent,
     * and an exact-owner match would then miss a correctly pinned site.
     *
     * @param builder the options or builder type the posture is set on
     * @param methods the posture method names, any one of which pins the family
     */
    private record PostureCall(Class<?> builder, Set<String> methods) {

        boolean matches(MethodCallTarget target) {
            return methods.contains(target.getName()) && target.getOwner().reflect().isAssignableFrom(builder);
        }

        String spelling() {
            return builder.getSimpleName() + "#" + String.join(" or #", methods.stream().sorted().toList());
        }
    }

    /**
     * A construction target that takes no options object and so is a violation wherever it appears.
     *
     * @param owner            the library type declaring the construction method
     * @param method           the construction method's name
     * @param absentParameter  the options type whose absence makes an overload unpinnable, or {@code null}
     *                         when every overload of the method is unpinnable
     * @param replacement      the pinnable spelling a violation message recommends
     */
    private record UnpinnableTarget(Class<?> owner, String method, @Nullable Class<?> absentParameter,
            String replacement) {

        boolean matches(MethodCallTarget target) {
            return method.equals(target.getName())
                    && owner.isAssignableFrom(target.getOwner().reflect())
                    && (absentParameter == null || !hasParameter(target, absentParameter));
        }
    }
}
