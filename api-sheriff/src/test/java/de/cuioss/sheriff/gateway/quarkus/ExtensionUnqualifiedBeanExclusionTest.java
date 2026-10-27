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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import de.cuioss.sheriff.gateway.auth.GatewayValidator;
import de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck;
import de.cuioss.sheriff.token.quarkus.health.TokenValidatorHealthCheck;
import de.cuioss.sheriff.token.quarkus.metrics.JwtMetricsCollector;
import de.cuioss.sheriff.token.quarkus.observability.ObservedValidatorResolver;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.context.AccessTokenRequest;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins the <em>split</em> ADR-0074 decides for the token-validation extension's beans: its two
 * {@code health.*} probes stay excluded from the bean set, and its {@code metrics.JwtMetricsCollector}
 * is accommodated because it observes this gateway's own validator.
 * <p>
 * <strong>This class was rewritten deliberately, not loosened.</strong> It used to assert that the
 * collector is <em>not</em> a bean and that <em>no</em> scheduled job comes from the extension. That
 * held while the collector constructor-injected the {@code SecurityEventCounter} of the extension's
 * own unqualified producer, which fails on the empty {@code sheriff.token.issuers.*} namespace — up
 * to token-sheriff 0.9.4 the collector failed on every tick. From 0.9.5 on the collector reads
 * through the extension's {@code ObservedValidatorResolver}. With the namespace empty, the resolver
 * skips the extension's producer and observes the single remaining {@code TokenValidator} bean: the
 * {@code @GatewayValidator} validator built from {@code gateway.yaml}. The collector therefore
 * publishes {@code sheriff.token.validation.*} for the validator the request path uses, and each
 * assertion below pins one property of that new contract:
 * <ul>
 * <li>The collector <em>is</em> a bean, and the resolver reports {@code EXTERNAL_VALIDATOR} while
 * observing the {@code @GatewayValidator} validator. A second foreign {@code TokenValidator} bean
 * would make the resolver ambiguous and silently stop the meters; this assertion turns that red.</li>
 * <li>Both {@code health.*} probes stay unresolvable, paired with {@link SheriffMetrics} resolvable,
 * so a container that contributed nothing cannot masquerade as a successful exclusion.
 * {@link DefaultProfileReadinessTest} pins the readiness consequence of the same exclusion.</li>
 * <li>The fitness function: every scheduled job the extension's Quarkus wiring owns is the
 * collector, the collector's job is registered, and one collector tick completes without throwing.
 * A future extension bean that lands eagerly driven by the scheduler fails this the moment it
 * lands.</li>
 * <li>The token-validation metrics proof: after the gateway validator rejects a malformed token, a
 * collector tick moves a {@code sheriff.token.validation.errors} counter in the
 * {@link MeterRegistry}.</li>
 * </ul>
 * <p>
 * The fitness function observes registered scheduled jobs only, so it catches an eagerly-driven
 * extension bean only when the scheduler is what drives it. An extension bean driven by a startup
 * observer or by an HTTP endpoint would pass it. The exclusion remains a reviewed decision rather
 * than something these assertions can derive. The job filter carries its own matched
 * positive/negative control, so a filter that had stopped matching could not pass unnoticed.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@DisplayName("Token-validation extension: health probes excluded, metrics collector accommodated")
@Tag("quarkus-fork")
class ExtensionUnqualifiedBeanExclusionTest {

    /**
     * Package prefix of the extension's Quarkus wiring. Matched as a substring because a scheduled job
     * is identified by {@code <bean class>#<method>} with a generated prefix.
     */
    private static final String EXTENSION_QUARKUS_PACKAGE = "de.cuioss.sheriff.token.quarkus.";

    /**
     * The one extension-owned scheduled method this gateway accepts: the collector's tick. Matched
     * exactly against the trigger's method description, never as a substring, so a future extension
     * method that merely starts with this name (say {@code #updateCountersExtra}) is not mistaken for
     * the collector.
     */
    private static final String COLLECTOR_JOB = JwtMetricsCollector.class.getName() + "#updateCounters";

    /**
     * The collector's method description as {@link #describe(Trigger)} renders it: parenthesised at
     * the end of the identity, which is what makes an exact suffix match possible.
     */
    private static final String COLLECTOR_METHOD_SUFFIX = "(" + COLLECTOR_JOB + ")";

    /** The Micrometer name of the extension's validation-error counters. */
    private static final String VALIDATION_ERRORS = "sheriff.token.validation.errors";

    @Inject
    Instance<JwtMetricsCollector> extensionMetricsCollector;

    @Inject
    Instance<ObservedValidatorResolver> observedValidatorResolver;

    @Inject
    Instance<JwksEndpointHealthCheck> extensionJwksHealthCheck;

    @Inject
    Instance<TokenValidatorHealthCheck> extensionValidatorHealthCheck;

    @Inject
    Instance<SheriffMetrics> gatewayMetrics;

    @Inject
    Instance<Scheduler> scheduler;

    @Inject
    @GatewayValidator
    TokenValidator gatewayValidator;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("Should resolve the metrics collector observing the @GatewayValidator validator")
    void shouldResolveMetricsCollectorObservingTheGatewayValidator() {
        // Arrange
        assertTrue(extensionMetricsCollector.isResolvable(),
                JwtMetricsCollector.class.getName() + " must be a bean — ADR-0074 accommodates it, so "
                        + "quarkus.arc.exclude-types must no longer name the extension's metrics package");
        assertTrue(observedValidatorResolver.isResolvable(),
                "guard: the collector reads through " + ObservedValidatorResolver.class.getSimpleName()
                        + ", which must resolve for the observation below to mean anything");
        ObservedValidatorResolver resolver = observedValidatorResolver.get();

        // Act
        ObservedValidatorResolver.Outcome outcome = resolver.outcome();
        TokenValidator observed = resolver.observedValidator()
                .orElseThrow(() -> new AssertionError("the resolver observes no validator; outcome: " + outcome));

        // Assert — the resolver skipped the extension's own producer and found exactly one foreign
        // validator. Several candidates would read NOT_CONFIGURED and silently stop the meters.
        assertEquals(ObservedValidatorResolver.Outcome.EXTERNAL_VALIDATOR, outcome,
                "with no sheriff.token.issuers.* issuer the resolver must observe the single foreign "
                        + "validator, which is the @GatewayValidator one");
        // Both handles are client proxies over the same contextual instance, so the counter the
        // collector reads is the very object the gateway validator counts into.
        assertSame(gatewayValidator.getSecurityEventCounter(), observed.getSecurityEventCounter(),
                "the observed validator must be the @GatewayValidator instance — otherwise the "
                        + "collector publishes counts of a validator the request path never touches");
    }

    @Test
    @DisplayName("Should keep both extension health probes out of the bean set")
    void shouldKeepExtensionHealthProbesUnresolvable() {
        // Arrange — the paired guard: the gateway's own metrics bean proves CDI genuinely ran, so the
        // absence asserted below is an exclusion rather than a container that contributed nothing.
        assertTrue(gatewayMetrics.isResolvable(),
                "guard: the gateway's own " + SheriffMetrics.class.getSimpleName() + " must resolve — "
                        + "without it the absence asserted below would prove nothing about the exclusion");

        // Act
        boolean jwksProbeResolvable = extensionJwksHealthCheck.isResolvable();
        boolean validatorProbeResolvable = extensionValidatorHealthCheck.isResolvable();

        // Assert — quarkus.arc.exclude-types still removes both, although the extension registers them
        // as unremovable additional beans.
        assertFalse(jwksProbeResolvable,
                JwksEndpointHealthCheck.class.getName() + " must stay excluded: on this gateway it would "
                        + "report UP with checkedEndpoints=0 while observing nothing");
        assertFalse(validatorProbeResolvable,
                TokenValidatorHealthCheck.class.getName() + " must stay excluded: on this gateway it "
                        + "would report UP with issuerCount=0 while observing nothing");
    }

    @Test
    @DisplayName("Should register only the metrics collector as an extension scheduled job, and tick cleanly")
    void shouldRegisterOnlyTheCollectorAsExtensionJob() {
        // Arrange — without a resolvable, started Scheduler the job set is unobservable. The collector
        // carries a @Scheduled method, so Quarkus must have started the scheduler.
        assertTrue(scheduler.isResolvable(),
                "guard: the Quarkus Scheduler must resolve — otherwise the registered-job set is "
                        + "unobservable and the assertions below prove nothing");
        Scheduler resolved = scheduler.get();
        assertTrue(resolved.isStarted(),
                "the accommodated collector registers a @Scheduled method, so the scheduler must be "
                        + "started; a stopped scheduler means the collector's job never registered");

        // Act
        List<String> extensionJobs = extensionJobsAmong(resolved.getScheduledJobs().stream()
                .map(ExtensionUnqualifiedBeanExclusionTest::describe)
                .toList());

        // Assert — the collector's job is there, and it is the only extension-owned job.
        assertTrue(extensionJobs.stream().anyMatch(ExtensionUnqualifiedBeanExclusionTest::isCollectorJob),
                "the collector's tick " + COLLECTOR_JOB + " must be a registered scheduled job; "
                        + "registered extension jobs: " + extensionJobs);
        assertEquals(List.of(), nonCollectorJobsAmong(extensionJobs),
                "every scheduled job from " + EXTENSION_QUARKUS_PACKAGE + "* must be the metrics "
                        + "collector — any other one is an eagerly-driven extension bean ADR-0074 did not "
                        + "review. Read it and extend quarkus.arc.exclude-types rather than relaxing this "
                        + "assertion");
        // One tick completes without throwing — the collector goes idle or exports deltas, but never
        // fails on a schedule the way it did up to token-sheriff 0.9.4.
        JwtMetricsCollector collector = extensionMetricsCollector.get();
        assertDoesNotThrow(collector::updateCounters,
                "one collector tick must complete without throwing");
    }

    @Test
    @DisplayName("Should move a sheriff.token.validation.errors counter after the gateway validator rejects a token")
    void shouldMoveValidationErrorCounterAfterRejectedToken() {
        // Arrange — create the collector and settle its baseline, so the measurement below sees only
        // the delta this test causes.
        JwtMetricsCollector collector = extensionMetricsCollector.get();
        collector.updateCounters();
        double before = validationErrorTotal();
        AccessTokenRequest malformed = AccessTokenRequest.of("not-a-jwt");

        // Act — the gateway validator rejects the token and counts the event; one tick exports it.
        assertThrows(TokenValidationException.class, () -> gatewayValidator.createAccessToken(malformed),
                "a malformed bearer token must be rejected by the @GatewayValidator validator");
        collector.updateCounters();
        double after = validationErrorTotal();

        // Assert
        assertTrue(after > before,
                "a collector tick after a rejected token must move a " + VALIDATION_ERRORS + " counter; "
                        + "before=" + before + ", after=" + after);
    }

    /**
     * Matched positive/negative control over both filters the scheduled-job assertion depends on.
     * Without it that assertion is unfalsifiable-by-inspection: a filter that matched nothing would
     * pass a gateway whose extension schedules anything at all.
     */
    @Test
    @DisplayName("The filters flag a non-collector extension job and accept the collector and gateway jobs")
    void filtersFlagNonCollectorExtensionJobsOnly() {
        // Arrange — real trigger identities: the collector's tick, a hypothetical second extension job,
        // the gateway's own job, the near miss in the validation library this gateway does use, and
        // the near miss inside the collector itself: a method whose name merely starts with the tick's.
        String collectorJob = "1_" + COLLECTOR_JOB + " (" + COLLECTOR_JOB + ")";
        String otherExtensionJob = "2_de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck#poll "
                + "(de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck#poll)";
        String gatewayJob = "3_de.cuioss.sheriff.gateway.quarkus.SheriffMetrics#sweep "
                + "(de.cuioss.sheriff.gateway.quarkus.SheriffMetrics#sweep)";
        String validationLibraryJob = "4_de.cuioss.sheriff.token.validation.SomeJob#run "
                + "(de.cuioss.sheriff.token.validation.SomeJob#run)";
        String collectorPrefixJob = "5_" + COLLECTOR_JOB + "Extra (" + COLLECTOR_JOB + "Extra)";

        // Act
        List<String> extensionJobs = extensionJobsAmong(
                List.of(gatewayJob, collectorJob, otherExtensionJob, validationLibraryJob, collectorPrefixJob));
        List<String> flagged = nonCollectorJobsAmong(extensionJobs);

        // Assert — the extension filter selects the extension's Quarkus wiring only; the validation
        // library shares the de.cuioss.sheriff.token prefix and must not be over-selected. The second
        // filter flags every extension job except the collector's tick — including a method that only
        // shares the tick's name as a prefix, which a substring match would wrongly accept.
        assertEquals(List.of(collectorJob, otherExtensionJob, collectorPrefixJob), extensionJobs,
                "the extension filter must select jobs under " + EXTENSION_QUARKUS_PACKAGE + " and leave "
                        + "the gateway's own package and the validation library alone");
        assertEquals(List.of(otherExtensionJob, collectorPrefixJob), flagged,
                "the collector filter must flag every extension job except exactly " + COLLECTOR_JOB);
    }

    /** @return the summed count of every {@code sheriff.token.validation.errors} counter */
    private double validationErrorTotal() {
        return meterRegistry.find(VALIDATION_ERRORS).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** @return the entries of {@code jobDescriptions} owned by the extension's Quarkus wiring */
    private static List<String> extensionJobsAmong(List<String> jobDescriptions) {
        return jobDescriptions.stream()
                .filter(description -> description.contains(EXTENSION_QUARKUS_PACKAGE))
                .toList();
    }

    /** @return the entries of {@code extensionJobs} that are not the collector's tick */
    private static List<String> nonCollectorJobsAmong(List<String> extensionJobs) {
        return extensionJobs.stream()
                .filter(description -> !isCollectorJob(description))
                .toList();
    }

    /**
     * @return whether {@code description}, as rendered by {@link #describe(Trigger)}, names exactly the
     *         collector's tick as its method — a method that only starts with that name does not count
     */
    private static boolean isCollectorJob(String description) {
        return description.endsWith(COLLECTOR_METHOD_SUFFIX);
    }

    /** @return an identity for {@code trigger} that names the bean class whichever field carries it */
    private static String describe(Trigger trigger) {
        return trigger.getId() + " (" + trigger.getMethodDescription() + ")";
    }
}
