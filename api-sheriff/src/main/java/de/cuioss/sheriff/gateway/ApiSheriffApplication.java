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
package de.cuioss.sheriff.gateway;

import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main entry point for the API Sheriff gateway application.
 * <p>
 * This application provides the security-focused API Gateway with REST endpoints,
 * health checks, and metrics in a containerized environment.
 *
 * <h2>Pre-boot branches</h2>
 * <p>
 * {@link #main(String[])} answers two command-line requests before Quarkus is started, and
 * terminates the process with their exit code instead of booting the gateway:
 * <ul>
 * <li><strong>The container health probe</strong> ({@code --health-probe}). The distroless
 * production image carries no shell and no {@code curl}, so its {@code HEALTHCHECK} can only invoke
 * the application executable itself. All of the probe's behaviour, and the reasoning behind it,
 * lives in {@link HealthProbe}.</li>
 * <li><strong>The offline configuration check</strong> ({@code --validate-config <dir>}). It runs
 * the boot's own configuration pipeline over a configuration directory and exits with a status code
 * a CI pipeline can act on. All of its behaviour lives in {@link ConfigValidationCommand}.</li>
 * </ul>
 * This class only routes to them; the health probe takes precedence when both tokens are present.
 * <p>
 * The routing has to happen here rather than in {@link #run(String...)}: Quarkus honours an explicit
 * {@code main} on the {@link QuarkusMain}-annotated class, whereas {@code run} is reached only once
 * the listeners are bound. A health-probe branch there could never answer while the application is
 * still starting — precisely the window a container health check has to cover — and an offline
 * check there would already have bound the listeners and connected the clients it must never open.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusMain
public class ApiSheriffApplication implements QuarkusApplication {

    /**
     * Application entry point, and the routing point for the pre-boot health probe and offline
     * configuration check.
     * <p>
     * When {@code args} requests a probe, the process terminates with the probe's exit code; when it
     * requests the offline configuration check, the process terminates with the check's exit code.
     * In both cases Quarkus is never touched, so no listener is bound. Otherwise control falls through
     * to Quarkus unchanged.
     * <p>
     * This method is deliberately a shim carrying no logic of its own. It cannot be exercised by a
     * test — every path either terminates the JVM or hands control to Quarkus — which is why it is
     * excluded from coverage measurement, and why {@link HealthProbe} and
     * {@link ConfigValidationCommand} exist as separate, measured classes rather than as branches
     * inside this one.
     *
     * @param args the raw command line; consulted by {@link HealthProbe#isProbe(String[])} and
     *             {@link ConfigValidationCommand#isRequested(String[])} and otherwise handed to
     *             Quarkus unchanged
     * @since 1.0
     */
    public static void main(String[] args) {
        if (HealthProbe.isProbe(args)) {
            System.exit(HealthProbe.probe());
        }
        if (ConfigValidationCommand.isRequested(args)) {
            System.exit(ConfigValidationCommand.run(args, System.out, System.err, new EnvSecretResolver())); // NOSONAR java:S106 pre-boot offline check: no logging manager yet; the report is program output
        }
        Quarkus.run(ApiSheriffApplication.class, args);
    }

    /**
     * Runs the started application until the container asks it to exit.
     * <p>
     * This method is reached only after Quarkus has bound its listeners, which is why the health
     * probe and the offline configuration check are routed from {@link #main(String[])} instead.
     *
     * @param args the command line, as handed on by Quarkus
     * @return {@code 0} once the application has been asked to shut down
     * @throws Exception if the application fails while waiting for exit
     * @since 1.0
     */
    @Override
    public int run(String... args) throws Exception {
        Quarkus.waitForExit();
        return 0;
    }
}
