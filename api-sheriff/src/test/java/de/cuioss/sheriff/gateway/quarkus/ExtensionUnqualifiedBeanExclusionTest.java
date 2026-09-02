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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;


import de.cuioss.sheriff.token.quarkus.metrics.JwtMetricsCollector;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the <em>scope</em> of the token-validation extension's bean exclusion: no extension bean that
 * reaches the extension's <em>unqualified</em> {@code TokenValidator} survives into the running
 * application.
 * <p>
 * {@link DefaultProfileReadinessTest} pins the readiness half of the same decision — the two
 * {@code health.*} probes. This class pins the other half, and it exists because the health probes
 * were not the only bean in that position. The extension's {@code metrics.JwtMetricsCollector}
 * constructor-injects a {@code SecurityEventCounter} produced by the same unqualified
 * {@code producer.TokenValidatorProducer}, which resolves issuers from the extension's
 * {@code sheriff.token.issuers.<name>.*} namespace. This gateway never populates that namespace — the
 * request path runs off the {@code @GatewayValidator}-qualified validator built from
 * {@code gateway.yaml} — so the producer throws
 * {@code IllegalStateException: No issuer configurations found in properties} on every attempt to
 * create it.
 * <p>
 * That bean is {@code @Scheduled(every = "10s")}, which turns a latent misfit into a continuous one:
 * the collector is re-created on every tick, fails on every tick, and the scheduler logs a full stack
 * trace each time. The gateway itself stays healthy and token validation keeps working — the damage
 * is roughly sixty lines of stack trace every ten seconds, which buries every other log line the
 * operator needs.
 * <p>
 * <strong>Two assertions, because either alone would be weak.</strong>
 * <ul>
 * <li>The <em>specific</em> one — {@code JwtMetricsCollector} is not a bean — pins the exclusion
 * entry that fixes the reported defect. Paired with {@link SheriffMetrics} being resolvable, so a run
 * in which CDI contributed nothing at all cannot masquerade as a successful exclusion.</li>
 * <li>The <em>general</em> one — no scheduled job anywhere names the extension's Quarkus package —
 * is the fitness function. The exclusion is a type pattern over another project's packages, and the
 * failure mode it guards is a <em>future</em> extension bean landing in the same position: eagerly
 * driven, reaching the unqualified producer, failing on a schedule. A bean-by-bean assertion would
 * only ever catch the beans someone already knew about.</li>
 * </ul>
 * <p>
 * Note what the general assertion does <em>not</em> claim. It observes registered scheduled jobs, so
 * it catches an eagerly-driven extension bean only when the scheduler is what drives it. An extension
 * bean driven by a startup observer or by an HTTP endpoint would pass it. The exclusion remains a
 * deliberate, reviewed decision rather than something these assertions can derive.
 * <p>
 * With the exclusion in place nothing in this application schedules anything, so Quarkus does not
 * start the scheduler at all and the assertion passes over an empty set. That is the intended resting
 * state and it is not vacuous for the purpose: the moment a bean under the extension's Quarkus
 * package registers a {@code @Scheduled} method the scheduler starts, the job appears, and the
 * assertion fires — which is exactly the regression it exists to catch. Because that resting state
 * makes the assertion green over an empty input, the filter it depends on carries its own matched
 * positive/negative control, so a filter that had stopped matching anything could not pass for a
 * gateway that schedules nothing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@DisplayName("Token-validation extension: unqualified bean exclusion")
class ExtensionUnqualifiedBeanExclusionTest {

    /**
     * Package prefix of the extension's Quarkus wiring — the whole surface that reads the
     * {@code sheriff.token.issuers.*} namespace this gateway never populates. Matched as a substring
     * because a scheduled job is identified by {@code <bean class>#<method>} with a generated prefix.
     */
    private static final String EXTENSION_QUARKUS_PACKAGE = "de.cuioss.sheriff.token.quarkus.";

    @Inject
    Instance<JwtMetricsCollector> extensionMetricsCollector;

    @Inject
    Instance<SheriffMetrics> gatewayMetrics;

    @Inject
    Instance<Scheduler> scheduler;

    @Test
    @DisplayName("Should exclude the extension's JwtMetricsCollector from bean discovery")
    void shouldExcludeExtensionMetricsCollectorFromBeanDiscovery() {
        // Arrange — the paired guard: the gateway's own metrics bean proves CDI and the metrics
        // subsystem genuinely ran, so the absence asserted below is an exclusion rather than a
        // container that contributed nothing.
        assertTrue(gatewayMetrics.isResolvable(),
                "guard: the gateway's own " + SheriffMetrics.class.getSimpleName() + " must resolve — "
                        + "without it the absence asserted below would prove nothing about the exclusion");

        // Act + Assert — the exclusion took effect: the extension's collector is not a bean at all.
        assertFalse(extensionMetricsCollector.isResolvable(),
                "quarkus.arc.exclude-types must remove " + JwtMetricsCollector.class.getName()
                        + " from bean discovery. It constructor-injects the SecurityEventCounter produced by the "
                        + "extension's UNQUALIFIED TokenValidatorProducer, which resolves issuers from the "
                        + "sheriff.token.issuers.* namespace this gateway never populates — so every @Scheduled "
                        + "tick fails with 'No issuer configurations found in properties'");
    }

    @Test
    @DisplayName("Should register no scheduled job from the token-validation extension")
    void shouldRegisterNoScheduledJobFromTheExtension() {
        // Arrange — without a resolvable Scheduler there is nothing to inspect and the assertion
        // below would be vacuously green.
        assertTrue(scheduler.isResolvable(),
                "guard: the Quarkus Scheduler must resolve — otherwise the registered-job set is "
                        + "unobservable and the assertion below proves nothing");

        // Act — a not-started scheduler is the expected state here and is STRONGER than an empty
        // filtered set: Quarkus starts the scheduler only once some bean registers a @Scheduled
        // method, so "not started" means nothing is scheduled anywhere in the application, extension
        // or otherwise. Reading getScheduledJobs() in that state throws rather than returning empty.
        Scheduler resolved = scheduler.get();
        List<String> extensionJobs = resolved.isStarted()
                ? extensionJobsAmong(resolved.getScheduledJobs().stream()
                .map(ExtensionUnqualifiedBeanExclusionTest::describe)
                .toList())
                : List.of();

        // Assert — nothing from the extension's Quarkus wiring runs on a schedule in this gateway.
        assertTrue(extensionJobs.isEmpty(),
                "no scheduled job may come from " + EXTENSION_QUARKUS_PACKAGE + "* — every bean there reads "
                        + "the extension's empty sheriff.token.issuers.* namespace, so a scheduled one fails on "
                        + "every tick and floods the log. Registered extension jobs: " + extensionJobs
                        + ". Extend quarkus.arc.exclude-types in application.properties rather than relaxing "
                        + "this assertion");
    }

    /**
     * Matched positive/negative control over the filter the assertion above depends on. Without it
     * that assertion is unfalsifiable-by-inspection: it is green today because nothing is scheduled,
     * and it would be equally green if the filter matched nothing at all — which is precisely the
     * state it must detect when a future extension bean lands.
     */
    @Test
    @DisplayName("The filter flags an extension-owned job and ignores a gateway-owned one")
    void filterFlagsExtensionJobsAndIgnoresGatewayJobs() {
        // Arrange — the extension-owned entry is the real trigger identity observed on a gateway
        // running without the exclusion; the gateway-owned one is the near miss it must not flag.
        String extensionJob = "1_de.cuioss.sheriff.token.quarkus.metrics.JwtMetricsCollector#updateCounters "
                + "(de.cuioss.sheriff.token.quarkus.metrics.JwtMetricsCollector#updateCounters)";
        String gatewayJob = "2_de.cuioss.sheriff.gateway.quarkus.SheriffMetrics#sweep "
                + "(de.cuioss.sheriff.gateway.quarkus.SheriffMetrics#sweep)";
        String validationLibraryJob = "3_de.cuioss.sheriff.token.validation.SomeJob#run "
                + "(de.cuioss.sheriff.token.validation.SomeJob#run)";

        // Act
        List<String> flagged = extensionJobsAmong(List.of(gatewayJob, extensionJob, validationLibraryJob));

        // Assert — the filter selects the extension's Quarkus wiring and only that. The validation
        // library entry is the near miss that matters: it shares the de.cuioss.sheriff.token prefix
        // but is the library this gateway genuinely uses, so a prefix trimmed one segment too short
        // would over-select it.
        assertEquals(List.of(extensionJob), flagged,
                "the filter must flag a job under " + EXTENSION_QUARKUS_PACKAGE + " and leave both the "
                        + "gateway's own package and the validation library the gateway does use alone");
    }

    /** @return the entries of {@code jobDescriptions} owned by the extension's Quarkus wiring */
    private static List<String> extensionJobsAmong(List<String> jobDescriptions) {
        return jobDescriptions.stream()
                .filter(description -> description.contains(EXTENSION_QUARKUS_PACKAGE))
                .toList();
    }

    /** @return an identity for {@code trigger} that names the bean class whichever field carries it */
    private static String describe(Trigger trigger) {
        return trigger.getId() + " (" + trigger.getMethodDescription() + ")";
    }
}
