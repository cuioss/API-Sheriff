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
package de.cuioss.sheriff.gateway.integration;

import static de.cuioss.sheriff.gateway.integration.ContainerHealthInspector.composeContainers;
import static de.cuioss.sheriff.gateway.integration.ContainerHealthInspector.inspectHealthStatus;
import static de.cuioss.sheriff.gateway.integration.ContainerHealthInspector.inspectHealthcheckTest;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Asserts that the container health signal the deployment relies on is real — declared by the
 * <strong>built image</strong> and reported by the <strong>running containers</strong>.
 * <p>
 * Neither assertion target is the Dockerfile text, and that is the point. A {@code HEALTHCHECK} line
 * can read perfectly in {@code Dockerfile.native} while the signal never reaches a container: the
 * stack could be running an image built before the line was added, a Compose {@code healthcheck:} key
 * <em>overrides</em> the image's declaration rather than merging with it, and Docker rewrites every
 * string-form {@code test} to {@code CMD-SHELL} — which resolves {@code /bin/sh}, an executable this
 * distroless base does not ship, so the probe would fail permanently while the source still looked
 * correct. Only the built artifact can answer what the image declares, and only a running container
 * can answer what it reports.
 * <p>
 * That distinction is what the bring-up gate now depends on. {@code start-integration-container.sh}
 * waits on {@code compose up --wait}, which blocks on exactly this baked healthcheck — so a
 * healthcheck that silently degraded to none would not fail the wait, it would make the wait
 * meaningless, clearing the moment the containers started rather than when they were healthy. This IT
 * is what turns that silent degradation into a red build.
 * <p>
 * The third test is a negative control. Both positive legs assert a specific present value, and a
 * reader that reported some constant non-empty status for everything would satisfy them — so the
 * control asserts that a service the stack deliberately runs <em>without</em> a healthcheck reads
 * back empty, proving the reader discriminates a present health state from an absent one.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Container health signal")
class ContainerHealthIT {

    /** The image the integration-test harness builds and every gateway service runs. */
    private static final String IMAGE = "api-sheriff:distroless";

    /**
     * The service-name prefix every gateway instance shares, mirroring the {@code GATEWAY_PREFIX} the
     * bring-up script derives its probe targets by. The container set itself is read from the running
     * project, never restated here.
     */
    private static final String GATEWAY_PREFIX = "api-sheriff";

    /**
     * The service used as the negative control: this stack runs Keycloak from an image that declares
     * no healthcheck and adds no {@code healthcheck:} key of its own, so its health state is the
     * absent one the control needs. Named rather than discovered, because "a service that is
     * deliberately unhealthchecked" is a property of this stack's design, not something to infer from
     * the very reader under test.
     */
    private static final String UNHEALTHCHECKED_SERVICE = "keycloak";

    /** The form marker Docker records for an exec-form declaration; the string form yields CMD-SHELL. */
    private static final String EXEC_FORM = "CMD";

    private static final String EXECUTABLE = "/app/application";
    private static final String PROBE_FLAG = "--health-probe";

    @Test
    @DisplayName("the image declares an exec-form healthcheck that runs the executable's own probe")
    void imageDeclaresExecFormHealthcheck() {
        List<String> test = inspectHealthcheckTest(IMAGE);

        assertFalse(test.isEmpty(), () -> IMAGE + " declares no HEALTHCHECK at all. The bring-up gate's"
                + " `compose up --wait` has nothing to wait on, so it clears as soon as the containers"
                + " start rather than when they are healthy.");
        assertEquals(EXEC_FORM, test.getFirst(), () -> "the HEALTHCHECK on " + IMAGE + " resolved to '"
                + test.getFirst() + "' rather than '" + EXEC_FORM + "' — it was declared in the string"
                + " form, which Docker rewrites to CMD-SHELL. That runs /bin/sh, which this distroless"
                + " base does not ship, so the probe would fail permanently. Declared test: " + test);
        assertTrue(test.contains(EXECUTABLE), () -> "the HEALTHCHECK on " + IMAGE + " does not invoke "
                + EXECUTABLE + ", the only executable the image carries. Declared test: " + test);
        assertTrue(test.contains(PROBE_FLAG), () -> "the HEALTHCHECK on " + IMAGE + " does not pass "
                + PROBE_FLAG + ", so it does not reach the pre-boot probe branch. Declared test: " + test);
    }

    @Test
    @DisplayName("every running gateway container reports itself healthy")
    void everyGatewayContainerReportsHealthy() {
        List<Executable> assertions = composeContainers().entrySet().stream()
                .filter(gateway -> gateway.getKey().startsWith(GATEWAY_PREFIX))
                .<Executable>map(gateway -> () -> assertGatewayHealthy(gateway.getKey(), gateway.getValue()))
                .toList();

        assertFalse(assertions.isEmpty(), () -> "the running Compose project reports no " + GATEWAY_PREFIX
                + "* container, so this assertion would pass without measuring anything. Is the stack up?");
        assertAll("every " + GATEWAY_PREFIX + "* container is healthy", assertions);
    }

    @Test
    @DisplayName("a container that declares no healthcheck reads back empty — the reader discriminates")
    void unhealthcheckedContainerReadsBackEmpty() {
        String container = composeContainers().get(UNHEALTHCHECKED_SERVICE);

        assertNotNull(container, () -> "the running Compose project has no " + UNHEALTHCHECKED_SERVICE
                + " container, so the negative control cannot be established.");
        String status = inspectHealthStatus(container);

        assertTrue(status.isEmpty(), () -> "reading the health state of " + UNHEALTHCHECKED_SERVICE
                + " (" + container + "), which declares no healthcheck, returned '" + status
                + "' rather than an empty result. The reader is not reporting real health state, so the"
                + " assertions above prove nothing.");
    }

    /**
     * Asserts one gateway container is healthy, keeping "reports nothing" and "reports unhealthy"
     * separate: the first means the baked healthcheck never reached the container, the second means it
     * reached it and the instance failed it. They have different causes and different fixes, so a
     * message that conflated them would send the reader to the wrong one.
     *
     * @param service   the Compose service name, used to name the instance in the failure message
     * @param container the container currently realising that service
     */
    private static void assertGatewayHealthy(String service, String container) {
        String status = inspectHealthStatus(container);

        assertFalse(status.isEmpty(), () -> service + " (" + container + ") reports no health state at"
                + " all — it declares no healthcheck. Either it is not running " + IMAGE + ", or a"
                + " Compose healthcheck: key overrode the image's declaration with an empty one.");
        assertEquals("healthy", status, () -> service + " (" + container + ") reports '" + status
                + "' rather than 'healthy' — the baked probe reached the container and the instance did"
                + " not pass it.");
    }
}
