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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitExit;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitLogRecord;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLogLineCount;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, end to end in the shipped native image, that the gateway refuses to start when the
 * token-validation extension's own issuer namespace is configured. {@code ExtensionIssuerNamespaceGateTest}
 * proves the gate against a configuration in JVM mode; this suite proves that the variable a deployment
 * would set, {@value #ROGUE_ISSUER_ENVIRONMENT}, reaches the gate in the native executable and stops the
 * start there — ahead of the extension's own startup observers and of any validator they would build —
 * so the refusal is not a rule that parses and never runs in the artifact that ships.
 * <p>
 * <strong>The topology.</strong> One one-off bearer-only gateway on the compose network, started
 * through {@link OneOffGatewayContainers#startGateway(String, String, java.nio.file.Path, int, List)}
 * with {@value #ROGUE_ISSUER_ENVIRONMENT}{@code =true}, the spelling by which the extension discovers an
 * issuer configured only through the environment. It runs the committed
 * {@code sheriff-config-threat/gateway.yaml}, unchanged ({@link ThreatClassificationIT#THREAT_GATEWAY}):
 * {@link ThreatClassificationIT} boots that same descriptor on the same image to readiness {@code UP},
 * so the descriptor itself is not what this gateway refuses.
 * <p>
 * <strong>What the test proves.</strong> The gateway logs {@code ERROR} {@value #REFUSAL_RECORD} on a line
 * that names the variable, and its process exits with a non-zero status: it never becomes ready,
 * because it never finishes starting. The matched control is the compose primary instance, which sets no
 * variable of the namespace — read from its container rather than assumed — and reports readiness
 * {@code UP} on the same image.
 * <p>
 * <strong>Why the harness's start checks are not made.</strong> The gateway exits before its management
 * or its application listener could answer, so neither the readiness poll nor
 * {@link OneOffGatewayContainers#assertApplicationPortAnswers(String, String)} has anything to reach;
 * the exit status read by {@link OneOffGatewayContainers#awaitExit(String)} takes their place.
 * <p>
 * Runs in the sequential Failsafe execution: it takes a fixed host port and reads the primary instance's
 * readiness.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Native image: a configured sheriff.token.issuers.* key refuses the gateway's start")
class ExtensionIssuerNamespaceRefusalIT extends BaseIntegrationTest {

    /**
     * The fixed host port of the one-off gateway's application listener, published on every interface
     * (see {@link OneOffGatewayContainers#applicationPortPublication(int)}); used by no other suite.
     */
    private static final int APPLICATION_PORT = 10478;

    /** The environment spelling of the extension's issuer namespace, matched by the gate in any letter case. */
    private static final String NAMESPACE_ENVIRONMENT_PREFIX = "SHERIFF_TOKEN_ISSUERS_";

    /** The variable that enables an issuer of the extension's own, configured only through the environment. */
    private static final String ROGUE_ISSUER_ENVIRONMENT = NAMESPACE_ENVIRONMENT_PREFIX + "ROGUE_ENABLED";

    /** ERROR — a key of the token-validation extension's own issuer namespace is configured. */
    private static final String REFUSAL_RECORD = "ApiSheriff-203";

    /** The compose service of the primary instance, the matched control. */
    private static final String PRIMARY_SERVICE = "api-sheriff";

    /** Upper bound for the refusal record to appear after {@code docker run}. */
    private static final long REFUSAL_TIMEOUT_SECONDS = 90L;

    private static final int READY = 200;

    @Test
    @DisplayName("the gateway logs ApiSheriff-203 naming the variable and exits, while the primary instance without it is ready")
    void configuredExtensionIssuerRefusesTheStart() {
        String gateway = "issuer-namespace-gateway-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            startGateway(gateway, composeNetwork(), ThreatClassificationIT.THREAT_GATEWAY, APPLICATION_PORT,
                    List.of(ROGUE_ISSUER_ENVIRONMENT + "=true"));
            awaitLogRecord(gateway, REFUSAL_RECORD, REFUSAL_TIMEOUT_SECONDS,
                    "a gateway started with " + ROGUE_ISSUER_ENVIRONMENT + " must log " + REFUSAL_RECORD
                            + " before its start completes");
            int exitStatus = awaitExit(gateway);
            long namingLines = gatewayLogLineCount(gateway, REFUSAL_RECORD, ROGUE_ISSUER_ENVIRONMENT);
            List<String> primaryNamespaceVariables = namespaceVariablesOf(primaryContainer());
            int primaryReadiness = givenManagement().when().get("/health/ready").statusCode();

            assertAll("a gateway configuring the extension's own issuer namespace, beside one that does not",
                    () -> assertTrue(namingLines >= 1, () -> "the " + REFUSAL_RECORD + " line names the offending "
                            + "variable " + ROGUE_ISSUER_ENVIRONMENT + ". " + gatewayLog(gateway)),
                    () -> assertNotEquals(0, exitStatus, () -> "the refused start ends the process with a failure "
                            + "status, so the gateway never becomes ready. " + gatewayLog(gateway)),
                    () -> assertEquals(List.of(), primaryNamespaceVariables, "matched control: the primary "
                            + "instance sets no variable of the extension's issuer namespace"),
                    () -> assertEquals(READY, primaryReadiness, "matched control: the primary instance, on the "
                            + "same image without the variable, is ready"));
        } finally {
            removeContainer(gateway);
        }
    }

    /** The container of the compose primary instance. */
    private static String primaryContainer() {
        Map<String, String> containers = ContainerHealthInspector.composeContainers();
        String primary = containers.get(PRIMARY_SERVICE);
        assertNotNull(primary, () -> "the compose project runs no '" + PRIMARY_SERVICE + "' service: " + containers);
        return primary;
    }

    /**
     * The names of the variables of the extension's issuer namespace a container is started with,
     * matched in any letter case as the gate matches them. Only the names are returned: a value of
     * such a variable may hold a secret.
     */
    private static List<String> namespaceVariablesOf(String container) {
        return docker("read the environment of " + container, "inspect", "--format",
                "{{range .Config.Env}}{{println .}}{{end}}", container)
                .lines()
                .map(entry -> entry.contains("=") ? entry.substring(0, entry.indexOf('=')) : entry)
                .map(String::strip)
                .filter(name -> name.toUpperCase(Locale.ROOT).startsWith(NAMESPACE_ENVIRONMENT_PREFIX))
                .toList();
    }
}
