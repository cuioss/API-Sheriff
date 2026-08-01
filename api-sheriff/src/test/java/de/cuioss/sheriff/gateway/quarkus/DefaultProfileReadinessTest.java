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
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@code /q/health/ready} reports on the <strong>shipped default profile</strong>, where no
 * {@code sheriff.token.issuers.*} issuer is configured at all.
 * <p>
 * <strong>This is a CHARACTERIZATION test of a known defect, not an endorsement of the behaviour it
 * pins.</strong> The deciding spike it implements asked whether the token-sheriff validation
 * extension — which requires at least one enabled issuer — drags aggregate readiness DOWN when the
 * shipped configuration names no issuer. The answer, observed rather than inferred, is <em>yes</em>:
 *
 * <pre>
 * {"status":"DOWN","checks":[
 *   {"name":"de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck_ClientProxy",
 *    "status":"DOWN",
 *    "data":{"exceptionClass":"java.lang.IllegalStateException",
 *            "rootCause":"No issuer configurations found in properties"}},
 *   {"name":"gateway-readiness","status":"UP","data":{"jwks":"ready","issuers":1,...}}]}
 * </pre>
 *
 * The sharp edge is the contrast inside that one payload. Bearer validation <em>is</em> configured
 * and healthy here — the boot fixture's {@code gateway.yaml} declares a {@code token_validation}
 * issuer, the gateway's own {@code @GatewayValidator} {@link org.eclipse.microprofile.health.HealthCheck}
 * resolves it, and {@code gateway-readiness} reports {@code jwks: ready}. Aggregate readiness is DOWN
 * anyway, because the extension gates on a <em>separate, independent</em> configuration surface: the
 * {@code sheriff.token.issuers.*} Quarkus property namespace, which {@code gateway.yaml} never
 * populates. A deployment can therefore be fully and correctly configured for token validation and
 * still never reach ready.
 * <p>
 * The defect is pre-existing and was <em>masked</em>: the {@code %it} profile injected a static-file
 * issuer into that property namespace, so the container the integration suite observes never entered
 * this state and no test ever saw it. Removing that {@code %it} block is what exposes it. The fix
 * belongs to the deliverable that performs the removal, which consequently is not a plain deletion;
 * this test is updated to the post-fix expectation there.
 * <p>
 * The assertion is deliberately made against the <em>aggregate</em>, never against
 * {@link GatewayReadinessCheck} alone. That probe reads {@code gateway.yaml} and the
 * {@code @GatewayValidator} validator only — never {@code sheriff.token.issuers.*} — so it is UP
 * whichever branch it takes (jwks {@code ready} with a {@code token_validation} block, jwks
 * {@code not-applicable} without one at GatewayReadinessCheck.java:132) and carries zero signal about
 * the extension. Reading it alone would have answered the spike wrongly.
 * <p>
 * Aggregation goes through SmallRye's own composition via {@link SmallRyeHealthReporter} — the same
 * code path {@code /q/health/ready} serves — so neither HTTP nor the management port is needed (the
 * test profile sets {@code quarkus.management.enabled=false}).
 * <p>
 * <strong>Anti-false-negative guard.</strong> A run in which the extension contributed no readiness
 * bean at all would fold to a vacuous result indistinguishable from a genuine one. Every aggregate
 * assertion below is therefore preceded by a check that the extension's
 * {@code health.JwksEndpointHealthCheck} bean is actually in the resolved contributor set.
 */
@QuarkusTest
@DisplayName("Default-profile readiness with zero sheriff.token.issuers.* configured")
class DefaultProfileReadinessTest {

    /**
     * Fragment of the extension health-check's binary name
     * ({@code de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck}). Matched as a substring
     * rather than an exact FQN because CDI hands out a client proxy whose class name carries the bean
     * class as a prefix, so the package-plus-simple-name fragment is the stable part — and it is the
     * same fragment the composed payload names the check by.
     */
    private static final String EXTENSION_READINESS_CHECK = "health.JwksEndpointHealthCheck";

    /** The gateway's own probe, named by {@code GatewayReadinessCheck.CHECK_NAME}. */
    private static final String GATEWAY_READINESS_CHECK = "gateway-readiness";

    private static final String STATUS = "status";
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    @Inject
    SmallRyeHealthReporter reporter;

    @Inject
    @Any
    Instance<HealthCheck> healthChecks;

    @Test
    @DisplayName("Should resolve the extension's JWKS check as a @Readiness contributor")
    void shouldResolveExtensionReadinessContributor() {
        // Arrange + Act
        List<String> contributors = readinessContributorNames();

        // Assert
        assertTrue(containsExtensionCheck(contributors),
                "the token-sheriff-validation extension must contribute its " + EXTENSION_READINESS_CHECK
                        + " bean — without it every aggregate assertion in this class would be vacuous. "
                        + "Resolved @Readiness contributors: " + contributors);
    }

    @Test
    @DisplayName("Should report aggregate readiness DOWN — the extension refuses zero issuers (known defect)")
    void shouldReportAggregateReadinessDownWithZeroIssuers() {
        // Arrange — the guard runs before the fold, so a vacuous result cannot masquerade as a genuine one.
        List<String> contributors = readinessContributorNames();
        assertTrue(containsExtensionCheck(contributors),
                "guard: " + EXTENSION_READINESS_CHECK + " absent from the resolved contributor set "
                        + contributors + " — the aggregate assertion below would be vacuous");

        // Act
        SmallRyeHealth readiness = reporter.getReadiness();
        JsonObject payload = readiness.getPayload();

        // Assert — the aggregate is DOWN, so a no-token-validation deployment never reaches ready.
        assertEquals(DOWN, payload.getString(STATUS),
                "the composed /q/health/ready status with zero issuers configured; payload: " + payload);

        // Assert — the extension's check is the contributor that drags it down, and why.
        JsonObject extensionCheck = checkNamed(payload, EXTENSION_READINESS_CHECK);
        assertEquals(DOWN, extensionCheck.getString(STATUS),
                "the extension's JWKS check is the DOWN contributor; payload: " + payload);
        assertEquals("No issuer configurations found in properties",
                extensionCheck.getJsonObject("data").getString("rootCause"),
                "the extension refuses an issuer-less configuration outright; payload: " + payload);

        // Assert — the gateway's own bearer validation is configured AND healthy at the same moment the
        // aggregate is DOWN. The two surfaces are independent: gateway.yaml's token_validation block
        // drives this probe, sheriff.token.issuers.* drives the extension's. That contrast is the whole
        // finding, and it is why the spike had to fold the aggregate rather than read this one check.
        JsonObject gatewayCheck = checkNamed(payload, GATEWAY_READINESS_CHECK);
        assertEquals(UP, gatewayCheck.getString(STATUS),
                "gateway-readiness is UP — the gateway's own validator resolved; payload: " + payload);
        assertEquals("ready", gatewayCheck.getJsonObject("data").getString("jwks"),
                "the boot fixture declares a token_validation issuer, so the gateway's own JWKS is ready "
                        + "while the extension's independent issuer surface is empty; payload: " + payload);
    }

    /** @return the binary class names of every {@code @Readiness}-qualified {@link HealthCheck} bean */
    private List<String> readinessContributorNames() {
        List<String> names = new ArrayList<>();
        for (HealthCheck check : healthChecks.select(new ReadinessLiteral())) {
            names.add(check.getClass().getName());
        }
        return names;
    }

    private static boolean containsExtensionCheck(List<String> contributors) {
        return contributors.stream().anyMatch(name -> name.contains(EXTENSION_READINESS_CHECK));
    }

    /**
     * @param payload   the composed readiness payload
     * @param nameOrFragment the check name, or a fragment of it (the extension's check is named by its
     *                       proxied binary class name, so an exact match is not stable)
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
