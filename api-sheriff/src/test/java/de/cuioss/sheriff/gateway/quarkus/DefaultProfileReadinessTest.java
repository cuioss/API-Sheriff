/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import java.util.ArrayList;
import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@code /q/health/ready} reports on the <strong>shipped default profile</strong>, where no
 * {@code sheriff.token.issuers.*} issuer is configured at all: aggregate readiness is
 * <strong>UP</strong>.
 * <p>
 * This class previously <em>characterized a defect</em>. The deciding spike asked whether the
 * token-sheriff-validation extension — which requires at least one enabled issuer — drags aggregate
 * readiness DOWN when the shipped configuration names no issuer, and the observed answer was
 * <em>yes</em>: its {@code health.JwksEndpointHealthCheck} reported DOWN with
 * {@code "No issuer configurations found in properties"} while the gateway's own probe reported
 * {@code jwks: ready} in the very same payload. A deployment could therefore be fully and correctly
 * configured for token validation and still never reach ready.
 * <p>
 * <strong>That defect is now fixed, and this class pins the fix.</strong> The two extension probes
 * ({@code health.JwksEndpointHealthCheck}, {@code health.TokenValidatorHealthCheck}) report on the
 * extension's <em>unqualified</em> {@code TokenValidator}, built from its own
 * {@code sheriff.token.issuers.*} Quarkus namespace. This gateway bypasses that validator entirely:
 * the request path injects the {@code @GatewayValidator}-qualified validator built from
 * {@code gateway.yaml}'s {@code token_validation} block. The probes' DOWN was therefore a false
 * negative about machinery the product does not use, so both beans are excluded from bean discovery
 * by a single unconditional entry in the shipped {@code application.properties}:
 *
 * <pre>
 * quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*
 * </pre>
 *
 * The exclusion is deliberately scoped to those two beans in that one package — it is <em>not</em>
 * {@code quarkus.health.extensions.enabled=false} or any other global switch, because
 * blanket-disabling a security-relevant readiness check is forbidden. No coverage is lost: both
 * excluded probes iterate the <em>extension's</em> issuer list, empty here, so neither ever observed
 * this gateway's JWKS loaders, while {@link GatewayReadinessCheck} reports {@code jwks} for the real
 * validator. That datum is a boot-time constructibility fact rather than a live JWKS signal — see the
 * comment block above {@code quarkus.arc.exclude-types} in the shipped {@code application.properties}
 * — which is why the assertion below pins the {@code ready} value and claims nothing beyond it.
 * <p>
 * <strong>Anti-false-negative guard.</strong> A bare "aggregate is UP" assertion is worthless on its
 * own, because a run in which the health subsystem contributed <em>nothing</em> would also fold to UP
 * — vacuously. Every assertion below is therefore paired: the extension's beans must be
 * <em>absent</em> (proving the exclusion actually took effect rather than the beans silently failing
 * to register for some unrelated reason), <em>and</em> the gateway's own probe must be
 * <em>present</em> (proving the readiness machinery genuinely ran and contributed). Only both
 * together make a green UP distinguishable from a vacuous one.
 * <p>
 * <strong>The zero-issuer premise is asserted, not assumed.</strong> The paired guard above proves the
 * readiness machinery ran; it says nothing about the scenario that machinery ran <em>in</em>. Because
 * {@code quarkus.arc.exclude-types} is unconditional, it removes both extension probes whether or not
 * any {@code sheriff.token.issuers.*} property exists — so all three tests below would stay green
 * against a reintroduced issuer key while no longer exercising the issuer-less scenario this class is
 * named for. That is not a hypothetical: the defect this class pins was originally <em>masked</em> by
 * exactly such an issuer block. {@code shouldReportAggregateReadinessUpWithZeroIssuers} therefore
 * inspects the merged effective configuration ({@code ConfigProvider}) and fails when any property
 * name starts with {@code sheriff.token.issuers.}, so the premise is a guarded precondition rather
 * than a claim in the class name.
 * <p>
 * Aggregation goes through SmallRye's own composition via {@link SmallRyeHealthReporter} — the same
 * code path {@code /q/health/ready} serves — so neither HTTP nor the management port is needed (the
 * test profile sets {@code quarkus.management.enabled=false}).
 */
@QuarkusTest
@DisplayName("Default-profile readiness with zero sheriff.token.issuers.* configured")
class DefaultProfileReadinessTest {

    /**
     * Package prefix of the token-sheriff-validation extension's health probes
     * ({@code JwksEndpointHealthCheck}, {@code TokenValidatorHealthCheck}) — and precisely the scope
     * the {@code quarkus.arc.exclude-types} entry names. Matched as a substring rather than an exact
     * FQN because CDI hands out a client proxy whose class name carries the bean class as a prefix.
     * Asserting on the package rather than on one class name covers <em>both</em> probes, whichever
     * health qualifier each carries.
     */
    private static final String EXTENSION_HEALTH_PACKAGE = "de.cuioss.sheriff.token.quarkus.health.";

    /**
     * Configuration prefix of the token-sheriff-validation extension's own issuer namespace — the
     * namespace whose <em>emptiness</em> is the scenario this class is named for. Every issuer key the
     * extension reads lives under it, so a prefix match over the effective property names is the whole
     * precondition check.
     */
    private static final String EXTENSION_ISSUER_PREFIX = "sheriff.token.issuers.";

    /** The gateway's own probe, named by {@code GatewayReadinessCheck.CHECK_NAME}. */
    private static final String GATEWAY_READINESS_CHECK = "gateway-readiness";

    private static final String STATUS = "status";
    private static final String UP = "UP";

    @Inject
    SmallRyeHealthReporter reporter;

    @Inject
    @Any
    Instance<HealthCheck> healthChecks;

    @Test
    @DisplayName("Should exclude both extension health probes from bean discovery entirely")
    void shouldExcludeExtensionHealthChecksFromBeanDiscovery() {
        // Arrange + Act — every HealthCheck bean, not just the @Readiness-qualified ones, so the
        // assertion covers both probes regardless of which health qualifier each carries.
        List<String> allChecks = beanClassNames(healthChecks);

        // Assert — the exclusion took effect: neither extension probe is a bean at all.
        assertFalse(containsExtensionCheck(allChecks),
                "quarkus.arc.exclude-types=" + EXTENSION_HEALTH_PACKAGE + "* must remove the extension's "
                        + "health probes from bean discovery; resolved HealthCheck beans: " + allChecks);
    }

    @Test
    @DisplayName("Should still resolve the gateway's own probe as a @Readiness contributor")
    void shouldResolveGatewayReadinessContributor() {
        // Arrange + Act
        List<String> contributors = readinessContributorNames();

        // Assert — the readiness machinery genuinely ran; without this, a UP aggregate is vacuous.
        assertTrue(containsGatewayCheck(contributors),
                "GatewayReadinessCheck must remain a @Readiness contributor — without it every "
                        + "aggregate assertion in this class would be vacuously UP. Resolved "
                        + "@Readiness contributors: " + contributors);
    }

    @Test
    @DisplayName("Should report aggregate readiness UP with zero sheriff.token.issuers.* configured")
    void shouldReportAggregateReadinessUpWithZeroIssuers() {
        // Arrange — the premise guard runs first: the exclusion is unconditional, so every other
        // assertion in this class survives a reintroduced issuer key and would keep this test green
        // while the zero-issuer scenario it exists to pin had silently evaporated.
        List<String> issuerProperties = extensionIssuerPropertyNames();
        assertTrue(issuerProperties.isEmpty(),
                "guard: the effective configuration declares " + issuerProperties + " — this class pins "
                        + "the ZERO-issuer scenario, and " + EXTENSION_ISSUER_PREFIX + "* keys make every "
                        + "assertion below describe a different one. Either drop those keys or retire "
                        + "this test; do not relax the guard");

        // Arrange — the paired guard runs before the fold, so neither a silently-unregistered
        // extension bean nor a silently-absent gateway probe can masquerade as a genuine UP.
        List<String> contributors = readinessContributorNames();
        assertFalse(containsExtensionCheck(contributors),
                "guard: an extension health probe is still a @Readiness contributor " + contributors
                        + " — the exclusion did not take effect, so the aggregate below proves nothing");
        assertTrue(containsGatewayCheck(contributors),
                "guard: " + GATEWAY_READINESS_CHECK + " absent from the resolved contributor set "
                        + contributors + " — the aggregate assertion below would be vacuously UP");

        // Act
        SmallRyeHealth readiness = reporter.getReadiness();
        JsonObject payload = readiness.getPayload();

        // Assert — the aggregate is UP: an issuer-less shipped configuration now reaches ready.
        assertEquals(UP, payload.getString(STATUS),
                "the composed /q/health/ready status with zero issuers configured; payload: " + payload);

        // Assert — and it is UP for the right reason: the gateway's own validator resolved from
        // gateway.yaml's token_validation block, which is the coverage the excluded probes duplicated.
        JsonObject gatewayCheck = checkNamed(payload, GATEWAY_READINESS_CHECK);
        assertEquals(UP, gatewayCheck.getString(STATUS),
                "gateway-readiness is UP — the gateway's own validator resolved; payload: " + payload);
        assertEquals("ready", gatewayCheck.getJsonObject("data").getString("jwks"),
                "the boot fixture declares a token_validation issuer, so the gateway's own validator "
                        + "resolved and its jwks datum reads ready; payload: " + payload);
    }

    /**
     * Reads the <em>merged effective</em> configuration rather than one properties file, so a key
     * reintroduced through any source — a profile-scoped entry, an environment variable, a system
     * property — is caught just as a shipped one would be.
     *
     * @return every effective configuration property name under {@link #EXTENSION_ISSUER_PREFIX}
     */
    private static List<String> extensionIssuerPropertyNames() {
        List<String> names = new ArrayList<>();
        for (String name : ConfigProvider.getConfig().getPropertyNames()) {
            if (name.startsWith(EXTENSION_ISSUER_PREFIX)) {
                names.add(name);
            }
        }
        return names;
    }

    /** @return the binary class names of every {@code @Readiness}-qualified {@link HealthCheck} bean */
    private List<String> readinessContributorNames() {
        return beanClassNames(healthChecks.select(new ReadinessLiteral()));
    }

    private static List<String> beanClassNames(Instance<HealthCheck> beans) {
        List<String> names = new ArrayList<>();
        for (HealthCheck check : beans) {
            names.add(check.getClass().getName());
        }
        return names;
    }

    private static boolean containsExtensionCheck(List<String> names) {
        return names.stream().anyMatch(name -> name.contains(EXTENSION_HEALTH_PACKAGE));
    }

    private static boolean containsGatewayCheck(List<String> names) {
        return names.stream().anyMatch(name -> name.contains(GatewayReadinessCheck.class.getName()));
    }

    /**
     * @param payload        the composed readiness payload
     * @param nameOrFragment the check name, or a fragment of it
     * @return the single check entry whose name contains {@code nameOrFragment}
     */
    private static JsonObject checkNamed(JsonObject payload, String nameOrFragment) {
        return payload.getJsonArray("checks").getValuesAs(JsonObject.class).stream()
                .filter(check -> check.getString("name").contains(nameOrFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no readiness check named like '" + nameOrFragment + "' in payload: " + payload));
    }

    /** Programmatic {@link Readiness} qualifier for selecting the readiness contributor set. */
    private static final class ReadinessLiteral extends AnnotationLiteral<Readiness> implements Readiness {
    }
}
