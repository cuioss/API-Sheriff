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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed deployment descriptors actually
 * <strong>activate</strong> the plain-HTTP management opt-out on the dedicated
 * {@code api-sheriff-plain-mgmt} instance — and, as the matched negative control, that no other
 * gateway instance activates it by accident.
 * <p>
 * The guard exists because the opt-out is opt-in and its failure mode is silent in exactly the
 * direction that hides it: an instance whose activation variable was dropped simply serves HTTPS
 * management like everything else, and {@code ManagementPlainHttpOptOutIT} would then be asserting
 * plain HTTP against a listener that is not the one under test. Lesson 2026-07-25-15-001 — an opt-in
 * runtime feature needs a deployment-activation assertion, not only a component test.
 * <p>
 * The negative control derives the instance set from {@code docker-compose.yml} rather than
 * enumerating it. A hand-maintained list could not fire for the very case the control exists for — a
 * newly added gateway instance that sets the activation variable by accident would simply be absent
 * from the enumeration. Because a derived set can also silently become empty, the control asserts its
 * own non-emptiness before looping.
 * <p>
 * The selection is only half the mechanism. The named bucket must also exist in the shipped
 * {@code application.properties} and must carry <em>no key material</em>: an unresolvable name fails
 * the boot outright, and a name that resolves to a bucket carrying a certificate would keep
 * management on HTTPS while looking configured. Both halves are asserted here, together with the
 * standing prohibition on ever declaring a DEFAULT {@code quarkus.tls.key-store.*} bucket, which the
 * management interface would silently inherit HTTPS from.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Plain-HTTP management opt-out — deployment activation")
class ManagementPlainHttpActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path APPLICATION_PROPERTIES =
            MODULE.resolve("../api-sheriff/src/main/resources/application.properties");

    private static final String OPT_OUT_SERVICE = "api-sheriff-plain-mgmt";
    private static final String BUCKET = "plain-management";
    private static final String SELECTION_VARIABLE = "QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME";
    private static final String SELECTION = SELECTION_VARIABLE + "=" + BUCKET;
    /** Every gateway instance in the compose topology is named after the application. */
    private static final String GATEWAY_SERVICE_PREFIX = "api-sheriff";

    @Test
    @DisplayName("the opt-out instance selects the key-less TLS bucket")
    void optOutInstanceSelectsTheKeyLessBucket() throws Exception {
        List<String> environment = environment(OPT_OUT_SERVICE);

        assertTrue(environment.contains(SELECTION),
                () -> OPT_OUT_SERVICE + " must set " + SELECTION + " — without it the instance serves "
                        + "HTTPS management like every other one and the opt-out IT tests nothing");
    }

    @Test
    @DisplayName("the opt-out instance carries no management certificate")
    void optOutInstanceCarriesNoManagementCertificate() throws Exception {
        List<String> environment = environment(OPT_OUT_SERVICE);

        assertFalse(environment.stream().anyMatch(e -> e.startsWith("QUARKUS_MANAGEMENT_SSL_CERTIFICATE_")),
                "the opt-out instance must not also configure a management certificate — the selected "
                        + "key-less bucket already wins, so leaving one set would be misleading noise");
    }

    @Test
    @DisplayName("the opt-out instance publishes management port 19005")
    void optOutInstancePublishesTheDedicatedManagementPort() throws Exception {
        assertTrue(publishedPorts(OPT_OUT_SERVICE).stream().anyMatch(p -> p.startsWith("19005:")),
                "the opt-out instance must publish 19005 — it is the port the dedicated http:// "
                        + "readiness gate and ManagementPlainHttpOptOutIT both address");
    }

    @Test
    @DisplayName("no other gateway instance activates the opt-out")
    void noOtherInstanceActivatesTheOptOut() throws Exception {
        // Arrange — derived from the compose file itself, so an instance added there is covered
        // without a manual edit here. A hand-maintained enumeration could not fire for exactly the
        // case this negative control exists for: a NEWLY added instance that sets the variable.
        List<String> httpsServices = otherGatewayServices();

        // Guard the control itself: if the derivation ever yields nothing, the loop below would pass
        // vacuously and report success while asserting about no service at all.
        assertFalse(httpsServices.isEmpty(),
                "the derived gateway-instance set must not be empty — an empty set turns this negative "
                        + "control into a vacuous pass");

        // Act + Assert
        for (String service : httpsServices) {
            List<String> environment = environment(service);
            assertFalse(environment.stream().anyMatch(ManagementPlainHttpActivationWiringTest::selectsManagementTls),
                    () -> service + " must NOT select a management TLS configuration name — the "
                            + "https + -k readiness gate and the k6 health benchmarks depend on its "
                            + "management port staying HTTPS");
        }
    }

    @Test
    @DisplayName("the activation predicate recognises the bare compose entry as well as KEY=value")
    void theActivationPredicateRecognisesBothComposeListForms() {
        // The negative control above can only fire if its predicate recognises every list form
        // compose accepts; a predicate that saw only KEY=value would pass green on a bare entry.
        assertTrue(selectsManagementTls(SELECTION_VARIABLE),
                "a BARE compose entry resolves its value from the host shell at compose-up time and "
                        + "must count as an activation");
        assertTrue(selectsManagementTls(SELECTION), "the KEY=value form must count as an activation");
        assertFalse(selectsManagementTls(SELECTION_VARIABLE + "_SUFFIX=x"),
                "a longer variable that merely shares the prefix is a different setting");
    }

    @Test
    @DisplayName("the selected TLS bucket is declared in the shipped application.properties, unconditionally")
    void theSelectedBucketIsDeclaredUnconditionally() throws Exception {
        List<String> bucketKeys = shippedPropertyLines().stream()
                .filter(line -> line.startsWith("quarkus.tls." + BUCKET + "."))
                .toList();

        assertFalse(bucketKeys.isEmpty(),
                "the '" + BUCKET + "' bucket must be declared in application.properties — an "
                        + "unresolvable configuration name fails the boot with a ConfigurationException");
        assertTrue(shippedPropertyLines().stream().noneMatch(line -> line.contains("%")
                        && line.contains("quarkus.tls." + BUCKET + ".")),
                "the bucket declaration must not be profile-scoped: only the SELECTION is per-deployment");
    }

    @Test
    @DisplayName("the selected TLS bucket carries no key material, and no default bucket exists")
    void noKeyMaterialReachesTheTlsRegistry() throws Exception {
        List<String> properties = shippedPropertyLines();

        assertTrue(properties.stream().noneMatch(line -> line.startsWith("quarkus.tls." + BUCKET + ".key-store.")
                        || line.startsWith("quarkus.tls." + BUCKET + ".trust-store.")),
                "the '" + BUCKET + "' bucket must carry no key or trust material — its emptiness IS "
                        + "the mechanism that leaves getKeyCertOptions() null");
        assertTrue(properties.stream().noneMatch(line -> line.startsWith("quarkus.tls.key-store.")),
                "no DEFAULT quarkus.tls.key-store.* bucket may be declared: the management interface "
                        + "falls back to the default registry bucket and would inherit HTTPS through a "
                        + "path no configuration file mentions (upstream quarkus issue 43380)");
    }

    /**
     * Whether a compose {@code environment} entry activates the management TLS configuration name.
     * <p>
     * Both list forms count. Compose accepts {@code KEY=value} <em>and</em> a BARE {@code KEY} entry
     * whose value is resolved from the host shell at {@code compose up} time — so an {@code =}-anchored
     * prefix alone would let a bare entry activate plain-HTTP management from a host value while this
     * negative control still passed green.
     */
    private static boolean selectsManagementTls(String entry) {
        return SELECTION_VARIABLE.equals(entry) || entry.startsWith(SELECTION_VARIABLE + "=");
    }

    private static List<String> shippedPropertyLines() throws IOException {
        return Files.readAllLines(APPLICATION_PROPERTIES).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> environment(String service) throws IOException {
        Map<String, Object> serviceMap = (Map<String, Object>) service(service);
        Object environment = serviceMap.get("environment");
        assertInstanceOf(List.class, environment, "the '" + service + "' service environment must be a list");
        return ((List<?>) environment).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> publishedPorts(String service) throws IOException {
        Map<String, Object> serviceMap = (Map<String, Object>) service(service);
        Object ports = serviceMap.get("ports");
        assertInstanceOf(List.class, ports, "the '" + service + "' service must publish ports");
        return ((List<?>) ports).stream().map(String::valueOf).toList();
    }

    /**
     * Every gateway instance in the compose topology except the opt-out one — the set that must stay
     * on HTTPS management. Derived from the compose file so it cannot drift from the deployment.
     */
    private static List<String> otherGatewayServices() throws IOException {
        return services().keySet().stream()
                .filter(name -> name.startsWith(GATEWAY_SERVICE_PREFIX))
                .filter(name -> !OPT_OUT_SERVICE.equals(name))
                .sorted()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws IOException {
        Map<String, Object> compose;
        try (InputStream in = Files.newInputStream(MODULE.resolve("docker-compose.yml"))) {
            compose = new Yaml().loadAs(in, Map.class);
        }
        Object services = compose.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    private static Object service(String service) throws IOException {
        Object node = services().get(service);
        assertNotNull(node, "docker-compose.yml must declare the '" + service + "' service");
        return node;
    }
}
